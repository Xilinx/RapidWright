/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.design.tools;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.rwroute.PartialRouter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes out-of-pblock static-net (VCC/GND) routing in a Vivado-routed
 * precompile DCP and re-routes only the in-pblock sinks left orphaned by the
 * removal. Vivado's complete in-pblock static-net routing (including
 * intra-site SitePIP / CKEN_INV configuration that RWRoute's static-net
 * router does not recreate) is preserved unchanged.
 *
 * <p>Intended for routed Vivado DCPs where the router occasionally pulls a
 * STATIC_SOURCE LUT1 from outside the pblock; that out-of-pblock routing
 * cascades into {@code NetTools.unrouteNetsWithOverlappingNodes} unrouting
 * the entire global static net when many such DCPs are merged into an array.
 *
 * <p>Out-of-pblock sinks (e.g. inline-flop CKEN/RST pins from the
 * port-anchor harness) are intentionally left unrouted — they are removed
 * downstream by {@code InlineFlopTools.removeInlineFlops}.
 */
public class PBlockStaticNetFixer {

    /**
     * Removes every {@code STATIC_SOURCE_*} SiteInst sitting outside the
     * union of the supplied pblocks, and walks forward from each removed
     * source's output node along the static-net PIPs, stripping each PIP
     * along the way until the route reaches a node whose tile is inside the
     * pblock (where stripping stops, preserving Vivado's complete in-pblock
     * routing).
     *
     * @return Number of PIPs removed across both static nets.
     */
    public static int stripOutOfPBlockStaticRouting(Design design, Collection<PBlock> pblocks) {
        if (pblocks == null || pblocks.isEmpty()) {
            throw new IllegalArgumentException("At least one pblock is required");
        }
        Set<Tile> pblockTiles = pblockUnionTiles(pblocks);

        // Find every SiteInst that drives one of the static nets and sits
        // outside the pblock. We can't rely on the STATIC_SOURCE name prefix
        // because Vivado may place ordinary LUT1 cells (with arbitrary names)
        // configured as constants. The authoritative test is: does this
        // SiteInst have an output SitePinInst on GND/VCC?
        Net gnd = design.getGndNet();
        Net vcc = design.getVccNet();
        Set<SiteInst> staticSourcesSet = new LinkedHashSet<>();
        Map<String, String> removedSourceNet = new LinkedHashMap<>();
        for (Net staticNet : new Net[] { gnd, vcc }) {
            if (staticNet == null) continue;
            String netLabel = staticNet.isGNDNet() ? "GLOBAL_LOGIC0" : "GLOBAL_LOGIC1";
            for (SitePinInst spi : staticNet.getPins()) {
                if (!spi.isOutPin()) continue;
                SiteInst si = spi.getSiteInst();
                if (si == null || si.getSite() == null) continue;
                if (pblockTiles.contains(si.getSite().getTile())) continue;
                if (staticSourcesSet.add(si)) {
                    removedSourceNet.put(si.getSiteName(), netLabel
                            + " @ " + si.getSite().getTile());
                }
            }
        }
        List<SiteInst> staticSourcesToRemove = new ArrayList<>(staticSourcesSet);

        // Per static net, walk forward from each out-of-pblock source's output
        // connectedNode and strip the PIPs along the route up until the route
        // enters the pblock area. PIPs whose start-node is inside the pblock
        // are never touched.
        int removedPips = 0;
        for (Net net : new Net[] { design.getGndNet(), design.getVccNet() }) {
            if (net == null) continue;

            // Build start-node -> outgoing PIPs adjacency.
            Map<Node, List<PIP>> outgoing = new HashMap<>();
            for (PIP pip : net.getPIPs()) {
                Node start = pip.getStartNode();
                if (start != null) outgoing.computeIfAbsent(start, k -> new ArrayList<>()).add(pip);
            }

            // Seed the BFS with each out-of-pblock STATIC_SOURCE's output node.
            Set<PIP> toStrip = new HashSet<>();
            ArrayDeque<Node> frontier = new ArrayDeque<>();
            Set<Node> visited = new HashSet<>();
            for (SiteInst si : staticSourcesToRemove) {
                for (SitePinInst pin : si.getSitePinInsts()) {
                    if (!pin.isOutPin()) continue;
                    Net pinNet = pin.getNet();
                    if (pinNet != net) continue;
                    Node n = pin.getConnectedNode();
                    if (n != null && visited.add(n)) frontier.add(n);
                }
            }

            while (!frontier.isEmpty()) {
                Node node = frontier.poll();
                List<PIP> outs = outgoing.get(node);
                if (outs == null) continue;
                for (PIP pip : outs) {
                    if (toStrip.contains(pip)) continue;
                    Node end = pip.getEndNode();
                    Tile endTile = end != null ? end.getTile() : null;
                    // Always strip this PIP — its start side is reachable from
                    // an out-of-pblock source. If the end node is in-pblock,
                    // stop here and don't keep walking from the in-pblock node.
                    toStrip.add(pip);
                    if (endTile == null || pblockTiles.contains(endTile)) {
                        continue;
                    }
                    if (end != null && visited.add(end)) frontier.add(end);
                }
            }

            if (!toStrip.isEmpty()) {
                List<PIP> keep = new ArrayList<>(net.getPIPs().size());
                int sampleCount = 0;
                for (PIP pip : net.getPIPs()) {
                    if (toStrip.contains(pip)) {
                        if (sampleCount < 5) {
                            System.out.println("    [stripped] " + net.getName() + ": " + pip);
                            sampleCount++;
                        }
                    } else {
                        keep.add(pip);
                    }
                }
                net.setPIPs(keep);
            }
            int n = toStrip.size();
            removedPips += n;
            System.out.println("** PBlockStaticNetFixer: stripped " + n + " out-of-pblock-source PIPs from "
                    + net.getName() + " (" + net.getPIPs().size() + " remain)");
        }

        // Now remove the out-of-pblock STATIC_SOURCE SiteInsts themselves.
        // preserveOtherRoutes=true: removing a net's source pin with the
        // single-argument removePin() unroutes the entire net, which would
        // discard the in-pblock static routing this class exists to preserve.
        for (SiteInst si : staticSourcesToRemove) {
            for (SitePinInst pin : new ArrayList<>(si.getSitePinInsts())) {
                Net pinNet = pin.getNet();
                if (pinNet != null) {
                    pinNet.removePin(pin, true);
                }
            }
            design.removeSiteInst(si);
        }
        if (!staticSourcesToRemove.isEmpty()) {
            System.out.println("** PBlockStaticNetFixer: removed " + staticSourcesToRemove.size()
                    + " out-of-pblock STATIC_SOURCE SiteInsts:");
            for (Map.Entry<String, String> e : removedSourceNet.entrySet()) {
                System.out.println("    " + e.getKey() + "  " + e.getValue());
            }
        }
        return removedPips;
    }

    /**
     * Strips out-of-pblock static-net routing then re-routes only in-pblock
     * sinks left orphaned by the strip. Vivado's complete in-pblock static
     * routing is preserved.
     */
    public static void fix(Design design, Collection<PBlock> pblocks) {
        int removed = stripOutOfPBlockStaticRouting(design, pblocks);
        if (removed == 0) {
            System.out.println("** PBlockStaticNetFixer: no out-of-pblock static-net PIPs found, skipping reroute");
            return;
        }

        // Re-derive isRouted() from current PIPs, then collect in-pblock
        // sinks that became orphaned. Out-of-pblock sinks are intentionally
        // left alone — they belong to inline flops removed downstream.
        DesignTools.updatePinsIsRouted(design);
        Set<Tile> pblockTiles = pblockUnionTiles(pblocks);
        List<SitePinInst> inPblockOrphans = new ArrayList<>();
        for (Net net : new Net[] { design.getGndNet(), design.getVccNet() }) {
            if (net == null) continue;
            int orphanedInPblock = 0;
            int orphanedOutOfPblock = 0;
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin() || spi.isRouted()) continue;
                SiteInst si = spi.getSiteInst();
                Tile siteTile = (si != null && si.getSite() != null) ? si.getSite().getTile() : null;
                if (siteTile != null && pblockTiles.contains(siteTile)) {
                    inPblockOrphans.add(spi);
                    orphanedInPblock++;
                } else {
                    orphanedOutOfPblock++;
                }
            }
            System.out.println("** PBlockStaticNetFixer: " + net.getName() + " orphaned sinks after strip: "
                    + orphanedInPblock + " in-pblock (will reroute), "
                    + orphanedOutOfPblock + " out-of-pblock (left unrouted)");
        }

        if (inPblockOrphans.isEmpty()) {
            System.out.println("** PBlockStaticNetFixer: no in-pblock orphaned sinks, skipping reroute");
            return;
        }

        String unionPblockString = unionPBlockString(pblocks);
        System.out.println("** PBlockStaticNetFixer: running PartialRouter with --pblock \"" + unionPblockString
                + "\" on " + inPblockOrphans.size() + " in-pblock orphaned static sinks");
        PartialRouter.routeDesignWithUserDefinedArguments(design,
                new String[] {
                        "--pblock", unionPblockString,
                        "--useUTurnNodes",
                        "--nonTimingDriven",
                },
                inPblockOrphans,
                /*softPreserve=*/ false);
    }

    /**
     * Reads pblocks from the design's XDC and runs {@link #fix(Design, Collection)}.
     */
    public static void fix(Design design) {
        Map<String, PBlock> pblockMap = ConstraintTools.getPBlocksFromXDC(design);
        if (pblockMap.isEmpty()) {
            throw new RuntimeException("No pblocks found in design XDC");
        }
        System.out.println("** PBlockStaticNetFixer: found " + pblockMap.size() + " pblock(s) in XDC: "
                + pblockMap.keySet());
        fix(design, pblockMap.values());
    }

    /**
     * Reparents SitePinInsts on physical-only "static-tie" Nets — Vivado /
     * RapidWright per-cell internal constant nets that come back from
     * Module relocation or readCheckpoint as orphans named like
     * {@code <cellPath>/VCC_1} / {@code <cellPath>/GND_0} with no EDIF
     * logical net — onto the design's actual VCC/GND nets, then drops the
     * orphan Nets. Safe to call multiple times in a flow; orphans tend to
     * appear during ModuleInst placement and again after RWRoute's
     * {@code createPossiblePinsToStaticNets} materialization.
     *
     * @return Number of SitePinInsts moved.
     */
    public static int mergeOrphanStaticTieNetsIntoGlobals(Design design) {
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        EDIFNetlist netlist = design.getNetlist();

        List<Net> orphans = new ArrayList<>();
        for (Net n : design.getNets()) {
            if (n == vcc || n == gnd) continue;
            if (netlist.getHierNetFromName(n.getName()) != null) continue;
            String simpleTail = n.getName().substring(n.getName().lastIndexOf('/') + 1);
            if (!simpleTail.matches("VCC(_\\d+)?|GND(_\\d+)?")) continue;
            orphans.add(n);
        }

        int moved = 0;
        for (Net orphan : orphans) {
            String simpleTail = orphan.getName().substring(orphan.getName().lastIndexOf('/') + 1);
            Net target = simpleTail.startsWith("VCC") ? vcc : gnd;
            for (SitePinInst spi : new ArrayList<>(orphan.getPins())) {
                orphan.removePin(spi);
                target.addPin(spi);
                moved++;
            }
            design.removeNet(orphan);
        }
        return moved;
    }

    private static Set<Tile> pblockUnionTiles(Collection<PBlock> pblocks) {
        Set<Tile> tiles = new HashSet<>();
        for (PBlock p : pblocks) {
            tiles.addAll(p.getAllTiles());
        }
        return tiles;
    }

    /**
     * Concatenates each pblock's range string with spaces, the format the
     * {@code PBlock(Device, String)} constructor expects.
     */
    public static String unionPBlockString(Collection<PBlock> pblocks) {
        StringBuilder sb = new StringBuilder();
        for (PBlock p : pblocks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.toString());
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp> <output.dcp>");
            System.out.println("  Reads pblocks from the input DCP's XDC, strips out-of-pblock static-net");
            System.out.println("  (VCC/GND) PIPs and STATIC_SOURCE SiteInsts, then reroutes only the");
            System.out.println("  in-pblock sinks left orphaned via PartialRouter --pblock. Vivado's");
            System.out.println("  complete in-pblock static routing is preserved. Out-of-pblock sinks");
            System.out.println("  (e.g. inline-flop control pins) are left unrouted.");
            System.exit(1);
        }
        String inputDcp = args[0];
        String outputDcp = args[1];

        System.out.println("** PBlockStaticNetFixer: reading " + inputDcp);
        Design design = Design.readCheckpoint(inputDcp);

        fix(design);

        System.out.println("** PBlockStaticNetFixer: removing inline flops");
        InlineFlopTools.removeInlineFlops(design);

        System.out.println("** PBlockStaticNetFixer: removing BUFGs");
        ArrayBuilder.removeBUFGs(design);

        System.out.println("** PBlockStaticNetFixer: writing " + outputDcp);
        design.writeCheckpoint(outputDcp);
        System.out.println("** PBlockStaticNetFixer: done");
    }
}
