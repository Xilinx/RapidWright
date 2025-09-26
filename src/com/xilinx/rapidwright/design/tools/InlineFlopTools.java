/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.util.*;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.edif.*;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.StringTools;

/**
 * Set of tools to add/remove flip flops inline to top level port connections.
 * This is targeted at kernel replication preparation prior to routing to ensure
 * that connections are routed outside of the pblock of the out-of-context
 * kernel.
 *
 */
public class InlineFlopTools {

    private static final String CLK_OPT = "--clk";
    private static final String PBLOCK_OPT = "--pblock";
    private static final String REMOVE_FLOPS_OPT = "--remove_flops";

    public static final String INLINE_SUFFIX = "_rw_inline_flop";

    /**
     * Add flip-flops inline on all the top-level ports of an out-of-context design.
     * This is useful for out-of-context kernels prior to placement and routing so that
     * after the flops have been placed, the router is forced to route connections
     * of each of the ports to each of the flops. This can help alleviate congestion
     * when the kernels are placed/relocated in context. Note this assumes the
     * design is not implemented as in most contexts it will be placed and routed
     * immediately following this modification.
     *
     * @param design  The design to modify
     * @param clkNet  Name of the clock net to use on which to add the flops
     * @param keepOut The pblock used to contain the kernel and the added flops will
     *                not be placed inside this area.
     */
    public static void createAndPlaceFlopsInlineOnTopPortsArbitrarily(Design design, String clkNet, PBlock keepOut) {
        createAndPlaceFlopsInlineOnTopPorts(design, clkNet, keepOut, false);
    }

    /**
     * Add flip-flops inline on all the top-level ports of an out-of-context design.
     * Flip-flop placements are chosen based on the centroid of net pins for each top-level
     * I/O. This is useful for out-of-context kernels prior to final routing so that
     * after the flops have been placed, the router is forced to route connections
     * of each of the ports to each of the flops. Note this assumes the design is
     * placed and potentially partially routed.
     *
     * @param design  The design to modify
     * @param clkNet  Name of the clock net to use on which to add the flops
     * @param keepOut The pblock used to contain the kernel and the added flops will
     *                not be placed inside this area.
     */
    public static void createAndPlaceFlopsInlineOnTopPortsNearPins(Design design, String clkNet, PBlock keepOut) {
        createAndPlaceFlopsInlineOnTopPorts(design, clkNet, keepOut, true);
    }

    /**
     * Add flip-flops inline on all the top-level ports of an out-of-context design.
     * This is useful for out-of-context kernels so that after the flops have been
     * placed, the router is forced to route connections of each of the ports to
     * each of the flops. This can help alleviate congestion when the kernels are
     * placed/relocated in context.
     *
     * @param design         The design to modify
     * @param clkNet         Name of the clock net to use on which to add the flops
     * @param keepOut        The pblock used to contain the kernel and the added flops will
     *                       not be placed inside this area.
     * @param smartPlacement Places flip-flops based on the centroid of the top-level net pins. Should only be
     *                       used if a placement already exists.
     */
    private static void createAndPlaceFlopsInlineOnTopPorts(Design design, String clkNet, PBlock keepOut,
                                                            boolean smartPlacement) {
        assert (design.getSiteInsts().isEmpty());
        EDIFCell top = design.getTopEDIFCell();
        Site start = keepOut.getAllSites("SLICE").iterator().next(); // TODO this is a bit wasteful
        boolean exclude = true;
        Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(start, keepOut, exclude).iterator();
        siteItr.next(); // Skip the first site, as we are suggesting one inside the pblock

        EDIFHierNet clk = design.getNetlist().getHierNetFromName(clkNet);

        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        for (EDIFPort port : top.getPorts()) {
            if (port.isBus()) {
                for (int i : port.getBitBlastedIndicies()) {
                    EDIFPortInst inst = port.getInternalPortInstFromIndex(i);
                    if (allLeavesAreIBUF(design, inst)) {
                        continue;
                    }

                    if (smartPlacement) {
                        smartFlipFlopPlacement(design, inst, keepOut, clk, siteInstsToRoute);
                    } else {
                        Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr);
                        Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
                        siteInstsToRoute.add(flop.getSiteInst());
                    }
                }
            } else {
                EDIFPortInst inst = port.getInternalPortInst();
                if (inst != null) {
                    if (allLeavesAreIBUF(design, inst)) {
                        continue;
                    }

                    if (smartPlacement) {
                        smartFlipFlopPlacement(design, inst, keepOut, clk, siteInstsToRoute);
                    } else {
                        Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr);
                        Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
                        siteInstsToRoute.add(flop.getSiteInst());
                    }
                }
            }
        }
        for (SiteInst si : siteInstsToRoute) {
            si.routeSite();
        }
    }

    private static boolean allLeavesAreIBUF(Design design, EDIFPortInst inst) {
        EDIFHierCellInst topInst = design.getNetlist().getTopHierCellInst();
        EDIFHierPortInst hierPortInst = new EDIFHierPortInst(topInst, inst);
        List<EDIFHierPortInst> leafHierPortInsts = hierPortInst.getHierarchicalNet().getLeafHierPortInsts();

        boolean allLeavesAreIBUF = !leafHierPortInsts.isEmpty();
        for (EDIFHierPortInst leafHierPortInst : leafHierPortInsts) {
            allLeavesAreIBUF &= leafHierPortInst.getCellType().getName().equals("IBUF");
        }
        return allLeavesAreIBUF;
    }

    private static void smartFlipFlopPlacement(Design design, EDIFPortInst inst, PBlock keepOut, EDIFHierNet clk,
                                               Set<SiteInst> siteInstsToRoute) {
        EDIFHierCellInst topInst = design.getNetlist().getTopHierCellInst();
        EDIFHierPortInst hierPortInst = new EDIFHierPortInst(topInst, inst);
        List<EDIFHierPortInst> leafHierPortInsts = hierPortInst.getHierarchicalNet().getLeafHierPortInsts();
        List<Point> points = new ArrayList<>();
        for (EDIFHierPortInst leafInst : leafHierPortInsts) {
            Cell cell = design.getCell(leafInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                Point p = new Point(t.getColumn(), t.getRow());
                points.add(p);
            }
        }

        if (!points.isEmpty()) {
            Set<SiteTypeEnum> validCentroidSiteTypes = Set.of(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM);
            Site centroid = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, validCentroidSiteTypes);
            Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(centroid, keepOut, true).iterator();
            siteItr.next();
            Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr);
            Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
            siteInstsToRoute.add(flop.getSiteInst());
        }
    }

    private static Pair<Site, BEL> nextAvailPlacement(Design design, Iterator<Site> itr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            SiteInst candidate = design.getSiteInstFromSite(curr);
            List<BEL> usedFFs = new ArrayList<>();
            if (candidate != null) {
                for (Cell c : candidate.getCells()) {
                    if (c.isPlaced() && c.getBEL().isFF() && !c.getBEL().isAnyIMR()) {
                        usedFFs.add(c.getBEL());
                    }
                }
            }
            if (usedFFs.size() < 5) {
                // There is an FF available, use one of them
                List<BEL> bels = Arrays.stream(curr.getBELs()).filter((BEL b) -> b.isFF() && !b.isAnyIMR()).toList();
                for (BEL b : bels) {
                    if (!usedFFs.contains(b)) {
                        return new Pair<>(curr, b);
                    }
                }
            }
        }
        return null;
    }

    private static Cell createAndPlaceFlopInlineOnTopPortInst(Design design, EDIFPortInst portInst, Pair<Site, BEL> loc,
                                                              EDIFHierNet clk) {
        String name = portInst.getFullName() + INLINE_SUFFIX;
        Cell flop = design.createAndPlaceCell(design.getTopEDIFCell(), name, Unisim.FDRE, loc.getFirst(),
                loc.getSecond());
        Net net = design.createNet(name);
        net.connect(flop, portInst.isInput() ? "D" : "Q");
        design.getGndNet().connect(flop, "R");
        design.getVccNet().connect(flop, "CE");
        clk.getNet().createPortInst("C", flop);
        EDIFNet origNet = portInst.getNet();
        origNet.removePortInst(portInst);
        net.getLogicalNet().addPortInst(portInst);
        Net origPhysNet = design.getNet(origNet.getName());
        if (origPhysNet == null) {
            if (origNet.isGND()) {
                origPhysNet = design.getGndNet();
            } else if (origNet.isVCC()) {
                origPhysNet = design.getVccNet();
            } else {
                origPhysNet = design.createNet(new EDIFHierNet(design.getNetlist().getTopHierCellInst(), origNet));
            }
        }
        origPhysNet.connect(flop, portInst.isInput() ? "Q" : "D");
        return flop;
    }

    /**
     * Removes the inline flops added by
     * {@link #createAndPlaceFlopsInlineOnTopPorts(Design, String, PBlock, boolean)}
     *
     * @param design The current design from which to remove the flops
     */
    public static void removeInlineFlops(Design design) {
        Map<Net, Set<SitePinInst>> pinsToRemove = new HashMap<>();
        List<SiteInst> siteInstToRemove = new ArrayList<>();
        List<EDIFCellInst> cellsToRemove = new ArrayList<>();
        Net vcc = design.getVccNet();
        Set<SitePinInst> vccPins = new HashSet<>();
        pinsToRemove.put(vcc, vccPins);
        String[] staticPins = new String[]{"CKEN1", design.getSeries() == Series.Versal ? "RST" : "SRST1"};
        for (EDIFCellInst inst : design.getTopEDIFCell().getCellInsts()) {
            if (inst.getName().endsWith(INLINE_SUFFIX)) {
                Cell flop = design.getCell(inst.getName());
                SiteInst si = flop.getSiteInst();
                // Assume we only placed one flop per SiteInst
                siteInstToRemove.add(si);
                for (SitePinInst pin : si.getSitePinInsts()) {
                    pinsToRemove.computeIfAbsent(pin.getNet(), p -> new HashSet<>()).add(pin);
                }
                for (String staticPin : staticPins) {
                    if (si.getSitePinInst(staticPin) == null) {
                        vccPins.add(vcc.createPin(staticPin, si));
                    }
                }

                cellsToRemove.add(inst);
            }
        }

        for (SiteInst si : siteInstToRemove) {
            design.removeSiteInst(si);
        }
        DesignTools.batchRemoveSitePins(pinsToRemove, true);

        String[] ctrlPins = new String[]{"C", "R", "CE"};
        EDIFCell top = design.getTopEDIFCell();
        for (EDIFCellInst c : cellsToRemove) {
            // Remove control set pins
            for (String pin : ctrlPins) {
                EDIFPortInst p = c.getPortInst(pin);
                p.getNet().removePortInst(p);
            }
            // Merge 'D' sources and 'Q' sinks, restore original net
            EDIFPortInst d = c.getPortInst("D");
            EDIFNet dNet = d.getNet();
            EDIFPortInst q = c.getPortInst("Q");
            EDIFNet qNet = q.getNet();
            if (dNet.getName().endsWith(INLINE_SUFFIX)) {
                // Input port
                for (EDIFPortInst pi : new ArrayList<>(d.getNet().getPortInsts())) {
                    if (pi.getCellInst() != c) {
                        dNet.removePortInst(pi);
                        qNet.addPortInst(pi);
                    }
                }
                qNet.removePortInst(q);
                top.removeNet(dNet);
            } else {
                // Output port
                for (EDIFPortInst pi : new ArrayList<>(q.getNet().getPortInsts())) {
                    if (pi.getCellInst() != c) {
                        qNet.removePortInst(pi);
                        dNet.addPortInst(pi);
                    }
                }
                top.removeNet(qNet);
                dNet.removePortInst(d);
            }

            top.removeCellInst(c);
            design.removeCell(c.getName());
        }

    }

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.out.println("USAGE (to add flops)   : <input.dcp> <output.dcp> " + CLK_OPT + "=<clkName> " + PBLOCK_OPT + "=<pblock range(s)>");
            System.out.println("USAGE (to remove flops): <input.dcp> <output.dcp> " + REMOVE_FLOPS_OPT);
            return;
        }

        Design d = Design.readCheckpoint(args[0]);

        if (args[2].startsWith(CLK_OPT) || args[2].startsWith(PBLOCK_OPT)) {
            d.unplaceDesign();
            String clkName = StringTools.getOptionValue(CLK_OPT, args);
            String pblockRange = StringTools.getOptionValue(PBLOCK_OPT, args);
            if (clkName == null || pblockRange == null) {
                throw new RuntimeException("ERROR: Missing value(s) for option(s): "
                        + CLK_OPT + "=" + clkName + ", " + PBLOCK_OPT + "=" + pblockRange);
            }
            PBlock pblock = new PBlock(d.getDevice(), pblockRange);
            createAndPlaceFlopsInlineOnTopPortsArbitrarily(d, clkName, pblock);
        } else if (args[2].equals(REMOVE_FLOPS_OPT)) {
            removeInlineFlops(d);
        } else {
            System.err.println("ERROR: Unrecognized option '" + args[2] + "'");
        }


        d.writeCheckpoint(args[1]);
    }
}
