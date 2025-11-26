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
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.edif.*;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.StringTools;

import static com.xilinx.rapidwright.util.Utils.isCLB;

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

    private static final int MAX_FFS_PER_SLICE = 5;
    private static final Set<SiteTypeEnum> VALID_CENTROID_SITE_TYPES =
            new HashSet<>(Arrays.asList(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM));

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

    private static Site shiftSiteToSide(Device device, Site site, PBlock pblock, PBlockSide side) {
        Tile topLeftTile = pblock.getTopLeftTile();
        Tile bottomRightTile = pblock.getBottomRightTile();
        Tile siteTile = site.getTile();
        int pBlockTop = topLeftTile.getRow();
        int pBlockLeft = topLeftTile.getColumn();
        int pBlockRight = bottomRightTile.getColumn();
        int pBlockBottom = bottomRightTile.getRow();
        int siteTileCol = siteTile.getColumn();
        int siteTileRow = siteTile.getRow();

        Tile shiftedTile;
        if (side == PBlockSide.TOP) {
            // Shift tile up
            shiftedTile = shiftTileUntilSlice(device, pBlockTop, siteTileCol, true, true);
        } else if (side == PBlockSide.LEFT) {
            // Shift tile left
            shiftedTile = shiftTileUntilSlice(device, siteTileRow, pBlockLeft, false, true);
        } else if (side == PBlockSide.RIGHT) {
            // Shift tile right
            shiftedTile = shiftTileUntilSlice(device, siteTileRow, pBlockRight, false, false);
        } else {
            // Shift tile down
            shiftedTile = shiftTileUntilSlice(device, pBlockBottom, siteTileCol, true, false);
        }
        Site shiftedSite = site.getCorrespondingSite(site.getSiteTypeEnum(), shiftedTile);
        if (shiftedSite == null) {
            // Can't find a site on the edge of the PBlock, return the original site
            return site;
        }

        return shiftedSite;
    }

    /**
     * Add flip-flops inline on all the top-level ports of an out-of-context design.
     * This is useful for out-of-context kernels so that after the flops have been
     * placed, the router is forced to route connections of each of the ports to
     * each of the flops. This can help alleviate congestion when the kernels are
     * placed/relocated in context.
     *
     * @param design      The design to modify
     * @param clkNet      Name of the clock net to use on which to add the flops
     * @param keepOut     The pblock used to contain the kernel and the added flops will
     *                    not be placed inside this area.
     * @param portSideMap Map from ports to side of the pblock the flop should be placed on
     */
    public static void createAndPlacePortFlopsOnSide(Design design, String clkNet, PBlock keepOut,
                                                      Map<EDIFPort, PBlockSide> portSideMap) {
        assert (design.getSiteInsts().isEmpty());
        Site start = keepOut.getAllSites("SLICE").iterator().next(); // TODO this is a bit wasteful
        boolean exclude = true;

        EDIFHierNet clk = design.getNetlist().getHierNetFromName(clkNet);

        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        for (Entry<EDIFPort, PBlockSide> entry : portSideMap.entrySet()) {
            EDIFPort port = entry.getKey();
            PBlockSide side = entry.getValue();
            if (port.getName().equals(clkNet)) {
                continue;
            }
            Site shiftedSite = shiftSiteToSide(design.getDevice(), start, keepOut, side);
            for (int i : port.getBitBlastedIndices()) {
                EDIFPortInst inst = port.getInternalPortInstFromIndex(i);
                if (allLeavesAreIBUF(design, inst)) {
                    continue;
                }

                Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(shiftedSite, keepOut, exclude).iterator();
                siteItr.next(); // Skip the first site, as we are suggesting one inside the pblock
                Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr, null);
                if (loc == null) {
                    throw new RuntimeException("Failed to find valid placement location for flip-flop");
                }
                Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
                siteInstsToRoute.add(flop.getSiteInst());
            }
        }
        for (SiteInst si : siteInstsToRoute) {
            si.routeSite();
        }
    }

    /**
     * Add flip-flops inline on all the top-level ports of an out-of-context design.
     * This is useful for out-of-context kernels so that after the flops have been
     * placed, the router is forced to route connections of each of the ports to
     * each of the flops. This can help alleviate congestion when the kernels are
     * placed/relocated in context.
     *
     * @param design            The design to modify
     * @param clkNet            Name of the clock net to use on which to add the flops
     * @param keepOut           The pblock used to contain the kernel and the added flops will
     *                          not be placed inside this area.
     * @param centroidPlacement Places flip-flops based on the centroid of the top-level net pins. Should only be
     *                          used if a placement already exists.
     */
    private static void createAndPlaceFlopsInlineOnTopPorts(Design design, String clkNet, PBlock keepOut,
                                                            boolean centroidPlacement) {
//        assert (design.getSiteInsts().isEmpty());
        EDIFCell top = design.getTopEDIFCell();
        Site start = keepOut.getAllSites("SLICE").iterator().next(); // TODO this is a bit wasteful
        boolean exclude = true;

        EDIFHierNet clk = design.getNetlist().getHierNetFromName(clkNet);

        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        for (EDIFPort port : top.getPorts()) {
            if (port.getName().equals(clkNet)) {
                continue;
            }
            for (int i : port.getBitBlastedIndices()) {
                EDIFPortInst inst = port.getInternalPortInstFromIndex(i);
                if (allLeavesAreIBUF(design, inst)) {
                    continue;
                }

                if (centroidPlacement) {
                    netCentroidFlipFlopPlacement(design, inst, keepOut, clk, siteInstsToRoute);
                } else {
                    Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(start, keepOut, exclude).iterator();
                    siteItr.next(); // Skip the first site, as we are suggesting one inside the pblock
                    Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr, null);
                    Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
                    siteInstsToRoute.add(flop.getSiteInst());
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

    private static Tile shiftTileUntilSlice(Device device, int row, int col, boolean shiftRow, boolean negativeShift) {
        int offset = negativeShift ? -1 : 1;
        Tile shiftedTile = null;
        while (shiftedTile == null || !isCLB(shiftedTile.getTileTypeEnum())) {
            int shiftedRow = shiftRow ? row + offset : row;
            int shiftedCol = !shiftRow ? col + offset : col;
            if (shiftRow && (shiftedRow < 0 || shiftedRow > device.getRows())) {
                shiftedTile = null;
                break;
            }
            if (!shiftRow && (shiftedCol < 0 || shiftedCol > device.getColumns())) {
                shiftedTile = null;
                break;
            }
            shiftedTile = device.getTile(shiftedRow, shiftedCol);
            offset = negativeShift ? offset - 1 : offset + 1;
        }
        return shiftedTile;
    }

    private static Site getSiteOnPBlockEdgeClosestToSite(Device device, Site site, PBlock pblock) {
        Tile topLeftTile = pblock.getTopLeftTile();
        Tile bottomRightTile = pblock.getBottomRightTile();
        Tile siteTile = site.getTile();
        int pBlockTop = topLeftTile.getRow();
        int pBlockLeft = topLeftTile.getColumn();
        int pBlockRight = bottomRightTile.getColumn();
        int pBlockBottom = bottomRightTile.getRow();
        int siteTileCol = siteTile.getColumn();
        int siteTileRow = siteTile.getRow();
        int topDist = siteTileRow - pBlockTop;
        int bottomDist = pBlockBottom - siteTileRow;
        int leftDist = siteTileCol - pBlockLeft;
        int rightDist = pBlockRight - siteTileCol;

        Tile shiftedTile = null;
        if (topDist <= leftDist && topDist <= rightDist && topDist <= bottomDist) {
            // Shift tile up
            shiftedTile = shiftTileUntilSlice(device, pBlockTop, siteTileCol, true, true);
        } else if (leftDist <= topDist && leftDist <= rightDist && leftDist <= bottomDist) {
            // Shift tile left
            shiftedTile = shiftTileUntilSlice(device, siteTileRow, pBlockLeft, false, true);
        } else if (rightDist <= topDist && rightDist <= leftDist && rightDist <= bottomDist) {
            // Shift tile right
            shiftedTile = shiftTileUntilSlice(device, siteTileRow, pBlockRight, false, false);
        } else {
            // Shift tile down
            shiftedTile = shiftTileUntilSlice(device, pBlockBottom, siteTileCol, true, false);
        }
        Site shiftedSite = site.getCorrespondingSite(site.getSiteTypeEnum(), shiftedTile);
        if (shiftedSite == null) {
            // Can't find a site on the edge of the PBlock, return the original site
            return site;
        }

        return shiftedSite;
    }

    private static void netCentroidFlipFlopPlacement(Design design, EDIFPortInst inst, PBlock keepOut, EDIFHierNet clk,
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
            Site centroid = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points,
                    VALID_CENTROID_SITE_TYPES);
            Site shiftedCentroid = getSiteOnPBlockEdgeClosestToSite(design.getDevice(), centroid, keepOut);
            Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(shiftedCentroid, keepOut, true).iterator();
            if (keepOut.containsTile(shiftedCentroid.getTile())) {
                siteItr.next();
            }
            Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr, shiftedCentroid.getTile().getSLR());
            Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
            siteInstsToRoute.add(flop.getSiteInst());
        }
    }

    private static Pair<Site, BEL> nextAvailPlacement(Design design, Iterator<Site> itr, SLR slr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            if (slr != null && curr.getTile().getSLR() != slr) {
                continue;
            }
            SiteInst candidate = design.getSiteInstFromSite(curr);
            List<BEL> usedFFs = new ArrayList<>();
            if (candidate != null) {
                for (Cell c : candidate.getCells()) {
                    if (c.isPlaced() && c.getBEL().isFF() && !c.getBEL().isAnyIMR()) {
                        usedFFs.add(c.getBEL());
                    }
                }
            }
            if (usedFFs.size() < MAX_FFS_PER_SLICE) {
                // There is an FF available, use one of them
                List<BEL> bels = Arrays.stream(curr.getBELs()).filter((BEL b) -> b.isFF() && !b.isAnyIMR())
                        .collect(Collectors.toList());
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
        EDIFHierCellInst flopHierCellInst = flop.getEDIFHierCellInst();
        EDIFHierPortInst clkHierPortInst = flopHierCellInst.getPortInst("C");
        if (clkHierPortInst == null) {
            clk.getNet().createPortInst("C", flopHierCellInst.getInst());
            clkHierPortInst = flopHierCellInst.getPortInst("C");
        }
        EDIFTools.connectPortInstsThruHier(clk, clkHierPortInst, name + "_clk");
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
        String[] versalStaticPins = new String[]{"CKEN1", "CKEN2", "CKEN3", "CKEN4", "RST"};
        String[] ultrascaleStaticPins = new String[]{"CKEN1", "CKEN2", "CKEN3", "CKEN4", "SRST1", "SRST2"};
        String[] series7StaticPins = new String[]{"CE", "SR"};
        if (design.getSeries() != Series.Versal && design.getSeries() != Series.UltraScale
                && design.getSeries() != Series.UltraScalePlus && design.getSeries() != Series.Series7) {
            throw new RuntimeException("Unsupported device series for removing inline flops");
        }
        String[] staticPins = design.getSeries() == Series.Versal ? versalStaticPins :
                              design.getSeries() == Series.UltraScalePlus
                              || design.getSeries() == Series.UltraScale ? ultrascaleStaticPins : series7StaticPins;
        for (EDIFCellInst inst : design.getTopEDIFCell().getCellInsts()) {
            if (inst.getName().endsWith(INLINE_SUFFIX)) {
                Cell flop = design.getCell(inst.getName());
                SiteInst si = flop.getSiteInst();
                // Assume we only placed one flop per SiteInst
                siteInstToRemove.add(si);
                for (SitePinInst pin : si.getSitePinInsts()) {
                    if (pin.getNet().isGNDNet()) {
                        continue;
                    }
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


        DesignTools.batchRemoveSitePins(pinsToRemove, true);

        String[] ctrlPins = new String[]{"C", "R", "CE"};
        EDIFCell top = design.getTopEDIFCell();
        for (EDIFCellInst c : cellsToRemove) {
            // Remove control set pins
            for (String pin : ctrlPins) {
                EDIFPortInst p = c.getPortInst(pin);
                if (p != null && p.getNet() != null) {
                    p.getNet().removePortInst(p);
                }
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

        for (SiteInst si : siteInstToRemove) {
            boolean isStaticSource = si.getSitePinInsts().stream()
                    .anyMatch((p) -> p.getNet() != null && p.getNet().isGNDNet() && p.isOutPin());
            if (!isStaticSource) {
                design.removeSiteInst(si);
            }
        }
    }

    /**
     * Parses the PBlock side map into a map from EDIFPorts to PBlockSide enums. The input file should be made up of
     * some number of lines where each line contains a port name regex and a PBlockSide separated by a space. An
     * example file:
     * <pre>
     * example_inputs.* TOP
     * reset LEFT
     * example_outputs.* BOTTOM
     * </pre>
     *
     * @param netlist The netlist that the side map will be created for.
     * @param filename The name of the input side map file.
     * @return A map from EDIFPort to the PBlockSide the inline flop should be placed on.
     */
    public static Map<EDIFPort, PBlockSide> parseSideMap(EDIFNetlist netlist, String filename) {
        Map<EDIFPort, PBlockSide> externalRoutabilitySideMap = new HashMap<>();
        List<String> lines = FileTools.getLinesFromTextFile(filename);

        for (String line : lines) {
            String[] splitLine = line.split("\\s+");
            String portRegex = splitLine[0];
            String pblockSide = splitLine[1].toUpperCase();
            for (EDIFPort port : netlist.getTopCell().getPorts()) {
                if (port.getBusName().matches(portRegex) ||
                        port.getName().matches("\\" + EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE + portRegex)) {
                    if (externalRoutabilitySideMap.containsKey(port)) {
                        throw new RuntimeException("Port " + port + " matches multiple expressions in side map");
                    }
                    PBlockSide side = PBlockSide.valueOf(pblockSide);
                    externalRoutabilitySideMap.put(port, side);
                }
            }
        }
        return externalRoutabilitySideMap;
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
