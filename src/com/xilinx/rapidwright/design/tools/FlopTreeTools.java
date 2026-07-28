/*
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FlopTreeTools {

    private static final Set<SiteTypeEnum> VALID_CENTROID_SITE_TYPES = new HashSet<>(
            Arrays.asList(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM));

    /**
     * Approximate tile-row distance spanned by a single SLL super-long-line on
     * Versal devices. Used by {@link #insertSLRCrossing} to place the bottom
     * SLR-crossing flop one SLL hop away from the top one so that the boundary
     * crossing uses a single SLL wire rather than a multi-hop detour.
     */
    private static final int SLL_WIRE_LENGTH_ROWS = 87;

    /**
     * Max acceptable tile-row distance from the SLR boundary for the
     * {@code slr_xing_top} flop. Constrains the spiral search so this flop
     * lands close enough to the boundary to use an SLL endpoint.
     */
    private static final int MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS = 30;

    /**
     * Per-call set of bounding boxes that newly-inserted flops should NOT land
     * inside. Set by the public entry points that take a {@code noGoBboxes}
     * parameter (typically the bounding boxes of placed kernel/array modules,
     * inside which the local INT routing is too congested to escape from a
     * fresh flop's site pin). Consulted by {@link #applyNoGoFilter} when
     * wrapping each spiral-out iterator. Cleared in a {@code finally} block by
     * the entry point so it doesn't leak across calls.
     *
     * Single-threaded use only. Not thread-safe.
     */
    private static List<RelocatableTileRectangle> activeNoGoBboxes = Collections.emptyList();

    private static class PortInstQuadrants implements Iterable<List<EDIFHierPortInst>> {
        List<EDIFHierPortInst> topLeft;
        List<EDIFHierPortInst> topRight;
        List<EDIFHierPortInst> bottomLeft;
        List<EDIFHierPortInst> bottomRight;

        PortInstQuadrants() {
            topLeft = new ArrayList<>();
            topRight = new ArrayList<>();
            bottomLeft = new ArrayList<>();
            bottomRight = new ArrayList<>();
        }

        @Override
        public @NotNull Iterator<List<EDIFHierPortInst>> iterator() {
            return Arrays.asList(topLeft, topRight, bottomLeft, bottomRight).iterator();
        }
    }

    private static Pair<Cell, Net> createAndPlaceFlopForTree(Design design, EDIFHierNet logicalNet, String newNetName,
                                                             Pair<Site, BEL> loc, EDIFHierNet clk,
                                                             List<EDIFHierPortInst> portInsts) {
        Cell flop = design.createAndPlaceCell(design.getTopEDIFCell(), newNetName, Unisim.FDRE, loc.getFirst(),
                loc.getSecond());
        Net net = design.createNet(newNetName);
        net.connect(flop, "Q");
        design.getGndNet().connect(flop, "R");
        design.getVccNet().connect(flop, "CE");
        EDIFHierCellInst flopHierCellInst = flop.getEDIFHierCellInst();
        EDIFHierPortInst clkHierPortInst = flopHierCellInst.getPortInst("C");
        if (clkHierPortInst == null) {
            clk.getNet().createPortInst("C", flopHierCellInst.getInst());
            clkHierPortInst = flopHierCellInst.getPortInst("C");
        }
        EDIFTools.connectPortInstsThruHier(clk, clkHierPortInst, newNetName + "_clk");
        EDIFNet origNet = logicalNet.getNet();
        ECOTools.disconnectNet(design, portInsts.stream().filter(EDIFHierPortInst::isInput).collect(Collectors.toList()));
        Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        netToPortInsts.put(net.getLogicalHierNet(), portInsts);
        ECOTools.connectNet(design, netToPortInsts, null);
        Net origPhysNet = design.getNet(logicalNet.getHierarchicalNetName());
        if (origPhysNet == null) {
            if (origNet.isGND()) {
                origPhysNet = design.getGndNet();
            } else if (origNet.isVCC()) {
                origPhysNet = design.getVccNet();
            } else {
                origPhysNet = design.createNet(logicalNet);
            }
        }
        origPhysNet.connect(flop, "D");
        return new Pair<>(flop, net);
    }

    private static Site findCentroidOfPortInsts(Design design, List<EDIFHierPortInst> portInsts) {
        List<Point> points = new ArrayList<>();
        for (EDIFHierPortInst leafInst : portInsts) {
            Cell cell = design.getCell(leafInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                Point p = new Point(t.getColumn(), t.getRow());
                points.add(p);
            }
        }

        return ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
    }

    private static PortInstQuadrants splitPortInstsIntoQuadrants(Design design, List<EDIFHierPortInst> portInsts,
                                                                 Site centroid) {
        PortInstQuadrants quadrants = new PortInstQuadrants();

        Tile tile = centroid.getTile();
        int tileColumn = tile.getColumn();
        int tileRow = tile.getRow();

        for (EDIFHierPortInst portInst : portInsts) {
            Cell cell = design.getCell(portInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                int portColumn = t.getColumn();
                int portRow = t.getRow();

                if (portColumn <= tileColumn && portRow < tileRow) {
                    quadrants.topLeft.add(portInst);
                } else if (portColumn < tileColumn) {
                    quadrants.bottomLeft.add(portInst);
                } else if (portColumn > tileColumn && portRow <= tileRow) {
                    quadrants.topRight.add(portInst);
                } else {
                    quadrants.bottomRight.add(portInst);
                }
            }
        }

        return quadrants;
    }

    private static Map<SLR, List<EDIFHierPortInst>> splitPortInstsBySLR(Design design,
                                                                        List<EDIFHierPortInst> portInsts) {
        Map<SLR, List<EDIFHierPortInst>> portInstMap = new HashMap<>();

        for (EDIFHierPortInst portInst : portInsts) {
            Cell cell = portInst.getPhysicalCell(design);
            if (cell == null || !cell.isPlaced()) {
                throw new RuntimeException("Port inst: " + portInst + " is not placed");
            }
            Tile t = cell.getTile();
            SLR slr = t.getSLR();
            portInstMap.computeIfAbsent(slr, k -> new ArrayList<>()).add(portInst);
        }

        return portInstMap;
    }

/**
 * Wraps a site iterator so that only sites whose tile is NOT inside any of
 * {@link #activeNoGoBboxes} are yielded. If the active list is empty (the
 * common case for callers that don't supply no-go regions), returns the
 * base iterator unchanged.
 */
    private static Iterator<Site> applyNoGoFilter(Iterator<Site> base) {
        if (activeNoGoBboxes.isEmpty()) return base;
        final List<RelocatableTileRectangle> bboxes = activeNoGoBboxes;
        return new Iterator<Site>() {
            private Site nextSite;
            private boolean exhausted = false;

            private boolean inAnyBbox(Tile t) {
                int r = t.getRow();
                int c = t.getColumn();
                for (RelocatableTileRectangle bb : bboxes) {
                    if (r >= bb.getMinRow() && r <= bb.getMaxRow()
                            && c >= bb.getMinColumn() && c <= bb.getMaxColumn()) {
                        return true;
                    }
                }
                return false;
            }

            private void advance() {
                while (base.hasNext()) {
                    Site s = base.next();
                    if (!inAnyBbox(s.getTile())) {
                        nextSite = s;
                        return;
                    }
                }
                exhausted = true;
            }

            @Override
            public boolean hasNext() {
                if (nextSite == null && !exhausted) advance();
                return nextSite != null;
            }

            @Override
            public Site next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Site s = nextSite;
                nextSite = null;
                return s;
            }
        };
    }

    private static Iterator<Site> sitesWithinRowRange(Iterator<Site> base,
                                                       int minRow, int maxRow, int maxScans) {
        return new Iterator<Site>() {
            private Site nextSite;
            private int scanned = 0;
            private boolean exhausted = false;

            private void advance() {
                while (base.hasNext() && scanned < maxScans) {
                    Site s = base.next();
                    scanned++;
                    int r = s.getTile().getRow();
                    if (r >= minRow && r <= maxRow) {
                        nextSite = s;
                        return;
                    }
                }
                exhausted = true;
            }

            @Override
            public boolean hasNext() {
                if (nextSite == null && !exhausted) advance();
                return nextSite != null;
            }

            @Override
            public Site next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Site s = nextSite;
                nextSite = null;
                return s;
            }
        };
    }

    private static Pair<Site, BEL> nextAvailFlopPlacement(Design design, Iterator<Site> itr, SLR slr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            if (slr != null && curr.getTile().getSLR() != slr) {
                continue;
            }
            SiteInst candidate = design.getSiteInstFromSite(curr);
            Set<String> usedBelNames = new HashSet<>();
            if (candidate != null) {
                for (Cell c : candidate.getCells()) {
                    if (c.isPlaced() && c.getBEL() != null) {
                        usedBelNames.add(c.getBEL().getName());
                    }
                }
            }
            for (BEL b : curr.getBELs()) {
                if (!b.isFF() || b.isAnyIMR()) continue;
                if (usedBelNames.contains(b.getName())) continue;
                if (!isControlSetCompatibleForInsertedFDRE(candidate, b)) continue;
                String belName = b.getName();
                if (candidate != null) {
                    // Verify the FF's output site pin is not already in use. Relies on the
                    // SLICE FF naming convention (BEL "AFF"->"AQ", "AFF2"->"AQ2", etc.) since
                    // there is no direct BEL-pin -> site-pin accessor to derive it from.
                    String sitePinName = null;
                    if (belName.length() >= 3 && belName.charAt(1) == 'F' && belName.charAt(2) == 'F') {
                        sitePinName = belName.charAt(0) + "Q" + belName.substring(3);
                    }
                    if (sitePinName != null && candidate.getSitePinInst(sitePinName) != null) {
                        continue;
                    }
                }
                return new Pair<>(curr, b);
            }
        }
        return null;
    }

    private static boolean isExpectedStaticControlNet(Net net, boolean expectGnd) {
        if (net == null) {
            return true;
        }
        return expectGnd ? net.isGNDNet() : net.isVCCNet();
    }

    private static boolean isExpectedStaticControlNet(SiteInst candidate, String sitePinName, boolean expectGnd) {
        com.xilinx.rapidwright.design.SitePinInst sitePin = candidate.getSitePinInst(sitePinName);
        return sitePin == null || isExpectedStaticControlNet(sitePin.getNet(), expectGnd);
    }

    /**
     * An inserted FDRE buffer ties its clock enable high and set/reset low, and
     * inherits the candidate SLICE's shared control set. The site is only usable
     * if its clock-enable site pin is driven by VCC (or absent) and its set/reset
     * site pin is driven by GND (or absent); otherwise connecting the new FDRE's
     * CE/R would collide with the control net already on that site pin.
     *
     * Only applies on Versal; returns {@code true} for other series (no control-set
     * guard), since the CE/SR site-pin mapping used here is Versal-specific.
     */
    static boolean isControlSetCompatibleForInsertedFDRE(SiteInst candidate, BEL bel) {
        if (candidate == null || bel == null || candidate.getDesign().getSeries() != Series.Versal) {
            return true;
        }
        Pair<String, String> sitePinNames =
                DesignTools.belTypeSitePinNameMapping.get(Series.Versal).get(bel.getName());
        if (sitePinNames == null) {
            return true;
        }
        // first = clock-enable site pin (expect VCC), second = set/reset site pin (expect GND)
        return isExpectedStaticControlNet(candidate, sitePinNames.getFirst(), false)
                && isExpectedStaticControlNet(candidate, sitePinNames.getSecond(), true);
    }

    private static Pair<Site, Net> placeFlopNearCentroidOfPortInsts(Design design, String clkName, Net inputNet,
                                                                    String newNetName, List<EDIFHierPortInst> portInsts,
                                                                    Set<SiteInst> siteInstsToRoute, SLR requiredSLR) {
        Site centroid = findCentroidOfPortInsts(design, portInsts);

        if (centroid == null) {
            throw new RuntimeException("Failed to find centroid of net " + inputNet);
        }

        Iterator<Site> siteItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(centroid).iterator());
        Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, requiredSLR);
        if (loc == null) {
            throw new RuntimeException("Failed to find location to place flop in flop tree"
                    + (requiredSLR != null ? " (required SLR " + requiredSLR.getId() + ")" : ""));
        }
        if (requiredSLR != null && loc.getFirst().getTile().getSLR() != requiredSLR) {
            throw new RuntimeException("Placed flop " + newNetName + " at site " + loc.getFirst()
                    + " in SLR " + loc.getFirst().getTile().getSLR().getId()
                    + " but required SLR " + requiredSLR.getId());
        }
        Pair<Cell, Net> flopNetPair = createAndPlaceFlopForTree(design, inputNet.getLogicalHierNet(), newNetName, loc,
                design.getNetlist().getHierNetFromName(clkName), portInsts);
        if (requiredSLR != null) {
            Site placed = flopNetPair.getFirst().getSiteInst().getSite();
            if (placed.getTile().getSLR() != requiredSLR) {
                throw new RuntimeException("Flop tree flop " + newNetName + " landed at " + placed
                        + " (SLR " + placed.getTile().getSLR().getId() + ") but required SLR "
                        + requiredSLR.getId());
            }
        }
        siteInstsToRoute.add(flopNetPair.getFirst().getSiteInst());
        return new Pair<>(centroid, flopNetPair.getSecond());
    }

    private static void insertFlopTreeForNetInSLR(Design design, SLR slr, String netName, String clkName, int depth,
                                                  List<EDIFHierPortInst> sinkHierPortInsts,
                                                  Set<SiteInst> siteInstsToRoute) {
        Net topNet = design.getNet(netName);
        List<Pair<Net, List<EDIFHierPortInst>>> currPortInstList = new ArrayList<>();
        currPortInstList.add(new Pair<>(topNet, sinkHierPortInsts));

        List<Pair<Net, List<EDIFHierPortInst>>> nextPortInstList = new ArrayList<>();

        for (int currDepth = 0; currDepth < depth; currDepth++) {
            int i = 0;
            for (Pair<Net, List<EDIFHierPortInst>> pair : currPortInstList) {
                Net net = pair.getFirst();
                List<EDIFHierPortInst> portInsts = pair.getSecond();
                String newNetName = netName.replace(EDIFTools.EDIF_HIER_SEP, "_") + "_slr" + slr.getId() + "_d" + currDepth + "_" + i;
                Pair<Site, Net> centroidNetPair = placeFlopNearCentroidOfPortInsts(design, clkName, net, newNetName,
                        portInsts, siteInstsToRoute, slr);

                Site centroid = centroidNetPair.getFirst();
                Net newNet = centroidNetPair.getSecond();
                PortInstQuadrants quadrants = splitPortInstsIntoQuadrants(design, portInsts, centroid);
                for (List<EDIFHierPortInst> quadrant : quadrants) {
                    if (!quadrant.isEmpty()) {
                        nextPortInstList.add(new Pair<>(newNet, quadrant));
                    }
                }
                i++;
            }
            currPortInstList = nextPortInstList;
            nextPortInstList = new ArrayList<>();
        }
    }

    private static Site getNearestValidSite(Design design, int row, int col) {
        List<Point> points = new ArrayList<>();
        Point p = new Point(col, row);
        points.add(p);

        return ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
    }

    /**
     * Builds a per-destination chain of SLR crossings from the source SLR down to
     * a single {@code targetSLR}, with the given source-side pacing depth in each
     * pre-crossing segment. Returns the bottom flop's net of the final crossing
     * (the tap usable as the in-target-SLR fanout source).
     *
     * Currently only handles downward traversal (sinks physically below source
     * = larger tile rows). {@code srcSegmentDepths.length} must equal the number
     * of crossings (i.e. {@code |targetSLR.id - sourceSLR.id|}).
     */
    private static Net insertSourceChainToSLR(Design design, Net net, String clkName,
                                               int[] srcSegmentDepths,
                                               SLR targetSLR,
                                               List<EDIFHierPortInst> targetSLRPortInsts,
                                               Set<SiteInst> siteInstsToRoute) {
        int numCrossings = srcSegmentDepths.length;
        if (numCrossings == 0) return net;

        Cell sourceCell = net.getLogicalHierNet().getSourcePortInsts(false).get(0).getPhysicalCell(design);
        Site sourceSite = sourceCell.getSite();
        SLR sourceSLR = sourceSite.getTile().getSLR();
        String slrCrossingNamePrefix = net.getName().replace(EDIFTools.EDIF_HIER_SEP, "_")
                + "_slr_xing_to_slr" + targetSLR.getId();


        Net currentNet = net;
        int currentRow = sourceSite.getTile().getRow();
        int currentCol = sourceSite.getTile().getColumn();
        SLR currentSLR = sourceSLR;

        for (int crossingIdx = 0; crossingIdx < numCrossings; crossingIdx++) {
            int boundaryRow = currentSLR.getLowerRight().getRow();
            int thisChainDepth = srcSegmentDepths[crossingIdx];

            // Source-side pacing chain for THIS segment: from currentRow → boundaryRow.
            for (int i = 0; i < thisChainDepth; i++) {
                double frac = (double) (i + 1) / (thisChainDepth + 1);
                int row = (int) Math.round(currentRow + frac * (boundaryRow - currentRow));
                List<Point> points = new ArrayList<>();
                points.add(new Point(currentCol, row));
                Site target = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
                Iterator<Site> chainItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(target).iterator());
                Pair<Site, BEL> chainLoc = nextAvailFlopPlacement(design, chainItr, currentSLR);
                if (chainLoc == null) {
                    throw new RuntimeException("Failed to place src pacing flop in SLR " + currentSLR.getId()
                            + " for crossing " + crossingIdx + " of net " + net.getName());
                }
                Pair<Cell, Net> chainPair = createAndPlaceFlopForTree(design, currentNet.getLogicalHierNet(),
                        slrCrossingNamePrefix + "_xing" + crossingIdx + "_src_ff" + i, chainLoc,
                        design.getNetlist().getHierNetFromName(clkName), targetSLRPortInsts);
                siteInstsToRoute.add(chainPair.getFirst().getSiteInst());
                currentNet = chainPair.getSecond();
            }

            // Top flop at the SLR boundary, constrained to land within
            // MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS tile rows of the boundary.
            String topName = slrCrossingNamePrefix + (numCrossings == 1
                    ? "_top"
                    : "_xing" + crossingIdx + "_top");
            Site firstSLRSite = getNearestValidSite(design, boundaryRow, currentCol);
            Iterator<Site> siteItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(firstSLRSite).iterator());
            Iterator<Site> boundedItr = sitesWithinRowRange(siteItr,
                    boundaryRow - MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS,
                    boundaryRow,
                    100_000);
            Pair<Site, BEL> loc = nextAvailFlopPlacement(design, boundedItr, null);
            if (loc == null) {
                throw new RuntimeException("Could not place " + topName + " within "
                        + MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS
                        + " rows of SLR boundary at row " + boundaryRow
                        + " (col " + currentCol + "); consider relaxing MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS"
                        + " or freeing sites near the boundary");
            }
            Pair<Cell, Net> topNetCellPair = createAndPlaceFlopForTree(design, currentNet.getLogicalHierNet(),
                    topName, loc,
                    design.getNetlist().getHierNetFromName(clkName), targetSLRPortInsts);
            siteInstsToRoute.add(topNetCellPair.getFirst().getSiteInst());

            // Bottom flop one SLL hop below where the top flop actually landed.
            Site placedTopSite = topNetCellPair.getFirst().getSiteInst().getSite();
            int actualTopRow = placedTopSite.getTile().getRow();
            int actualTopCol = placedTopSite.getTile().getColumn();
            int bottomTargetRow = actualTopRow + SLL_WIRE_LENGTH_ROWS;

            String bottomName = slrCrossingNamePrefix + (numCrossings == 1
                    ? "_bottom"
                    : "_xing" + crossingIdx + "_bottom");
            Site secondSLRSite = getNearestValidSite(design, bottomTargetRow, actualTopCol);
            Iterator<Site> bottomItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(secondSLRSite).iterator());
            Pair<Site, BEL> bottomLoc = nextAvailFlopPlacement(design, bottomItr, null);
            Pair<Cell, Net> bottomNetCellPair = createAndPlaceFlopForTree(design,
                    topNetCellPair.getSecond().getLogicalHierNet(), bottomName, bottomLoc,
                    design.getNetlist().getHierNetFromName(clkName), targetSLRPortInsts);
            siteInstsToRoute.add(bottomNetCellPair.getFirst().getSiteInst());

            // Advance state for the next crossing iteration.
            currentNet = bottomNetCellPair.getSecond();
            Site placedBottomSite = bottomNetCellPair.getFirst().getSiteInst().getSite();
            currentRow = placedBottomSite.getTile().getRow();
            currentCol = placedBottomSite.getTile().getColumn();
            currentSLR = placedBottomSite.getTile().getSLR();
        }

        if (currentSLR != targetSLR) {
            throw new RuntimeException("Source chain to SLR " + targetSLR.getId()
                    + " ended in SLR " + currentSLR.getId() + " for net " + net.getName());
        }
        return currentNet;
    }

    public static Net insertFlopChain(Design design, Net net, String clkName, int depth,
                                       List<EDIFHierPortInst> portInsts, Set<SiteInst> siteInstsToRoute,
                                       List<RelocatableTileRectangle> noGoBboxes) {
        List<RelocatableTileRectangle> prev = activeNoGoBboxes;
        activeNoGoBboxes = noGoBboxes != null ? noGoBboxes : Collections.emptyList();
        try {
            return insertFlopChain(design, net, clkName, depth, portInsts, siteInstsToRoute);
        } finally {
            activeNoGoBboxes = prev;
        }
    }

    public static Net insertFlopChain(Design design, Net net, String clkName, int depth,
                                       List<EDIFHierPortInst> portInsts, Set<SiteInst> siteInstsToRoute) {
        Cell sourceCell = net.getLogicalHierNet().getLeafHierPortInsts(true, false).get(0).getPhysicalCell(design);
        Site sourceSite = sourceCell.getSite();
        Site portInstCentroid = findCentroidOfPortInsts(design, portInsts);

        double srcCol = sourceSite.getTile().getColumn();
        double srcRow = sourceSite.getTile().getRow();
        double dstCol = portInstCentroid.getTile().getColumn();
        double dstRow = portInstCentroid.getTile().getRow();

        Net currentNet = net;
        for (int i = 0; i < depth; i++) {
            // Place flop at evenly spaced point: (i+1)/(depth+1) of the way from source to destination
            double frac = (double) (i + 1) / (depth + 1);
            int col = (int) Math.round(srcCol + frac * (dstCol - srcCol));
            int row = (int) Math.round(srcRow + frac * (dstRow - srcRow));

            List<Point> points = new ArrayList<>();
            points.add(new Point(col, row));
            Site target = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);

            Iterator<Site> siteItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(target).iterator());
            Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, null);

            Pair<Cell, Net> netCellPair = createAndPlaceFlopForTree(design, currentNet.getLogicalHierNet(),
                    currentNet.getName().replace(EDIFTools.EDIF_HIER_SEP, "_") + "_ff" + i, loc,
                    design.getNetlist().getHierNetFromName(clkName), portInsts);

            siteInstsToRoute.add(netCellPair.getFirst().getSiteInst());
            currentNet = netCellPair.getSecond();
        }

        return currentNet;
    }

    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR,
                                            List<RelocatableTileRectangle> noGoBboxes) {
        List<RelocatableTileRectangle> prev = activeNoGoBboxes;
        activeNoGoBboxes = noGoBboxes != null ? noGoBboxes : Collections.emptyList();
        try {
            insertFlopTreeForNet(design, netName, clkName, depth, maxDepthPerSLR);
        } finally {
            activeNoGoBboxes = prev;
        }
    }

    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFHierNet parentNet = netlist.getHierNetFromName(netName).getLeafSourcePortInst().getHierarchicalNet();
        Net topNet = design.getNet(parentNet.getHierarchicalNetName());
        List<EDIFHierPortInst> sinkHierPortInsts = topNet.getLogicalHierNet().getLeafHierPortInsts(false);
        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        Map<SLR, List<EDIFHierPortInst>> slrPortInstMap = splitPortInstsBySLR(design, sinkHierPortInsts);
        List<EDIFHierPortInst> sourcePortInsts = topNet.getLogicalHierNet().getSourcePortInsts(false);
        if (sourcePortInsts.isEmpty()) {
            throw new RuntimeException("Net " + netName + " does not have a source");
        }
        if (sourcePortInsts.size() > 1) {
            throw new RuntimeException("Net " + netName + " has multiple sources");
        }
        EDIFHierPortInst sourcePortInst = sourcePortInsts.get(0);
        Cell sourceCell = sourcePortInst.getPhysicalCell(design);
        if (sourceCell == null || !sourceCell.isPlaced()) {
            throw new RuntimeException("Source cell of net " + netName + " must be placed to create flop tree");
        }
        Site sourceSite = sourceCell.getSite();
        SLR sourceSLR = sourceSite.getTile().getSLR();

        // Build a per-destination chain of crossings + pacing for each
        // destination SLR. Each destination gets its own source-to-target path
        // so its pacing budget (extra = newDepth - maxDepthPerSLR) can be split
        // evenly across (slrDist + 1) segments without sharing a chain that
        // starves the deepest SLR's dst-side share.
        int maxSlrDist = 0;
        for (SLR slr : slrPortInstMap.keySet()) {
            maxSlrDist = Math.max(maxSlrDist, Math.abs(slr.getId() - sourceSLR.getId()));
        }

        for (Map.Entry<SLR, List<EDIFHierPortInst>> slrPortInsts : slrPortInstMap.entrySet()) {
            SLR slr = slrPortInsts.getKey();
            List<EDIFHierPortInst> portInsts = slrPortInsts.getValue();
            int slrDistFromSource = Math.abs(slr.getId() - sourceSLR.getId());
            int newDepth = depth - (2 * slrDistFromSource);
            int extra = Math.max(0, newDepth - maxDepthPerSLR);
            int treeDepth = Math.min(newDepth, maxDepthPerSLR);

            // Even split across (slrDist + 1) segments: slrDist source-side
            // segments (one per crossing) plus one dst-side segment in the
            // target SLR. Remainders go to the earliest segments.
            int totalSegments = slrDistFromSource + 1;
            int basePerSegment = extra / totalSegments;
            int remainder = extra % totalSegments;
            int[] srcSegmentDepths = new int[slrDistFromSource];
            for (int i = 0; i < slrDistFromSource; i++) {
                srcSegmentDepths[i] = basePerSegment + (i < remainder ? 1 : 0);
            }
            int dstChainDepth = basePerSegment + (slrDistFromSource < remainder ? 1 : 0);


            Net slrCrossedNet;
            if (slrDistFromSource == 0) {
                slrCrossedNet = topNet;
            } else {
                slrCrossedNet = insertSourceChainToSLR(design, topNet, clkName, srcSegmentDepths,
                        slr, portInsts, siteInstsToRoute);
            }
            if (dstChainDepth > 0) {
                slrCrossedNet = insertFlopChain(design, slrCrossedNet, clkName, dstChainDepth, portInsts,
                        siteInstsToRoute);
            }
            insertFlopTreeForNetInSLR(design, slr, slrCrossedNet.getName(), clkName, treeDepth, portInsts,
                    siteInstsToRoute);
        }

        for (SiteInst si : siteInstsToRoute) {
            si.routeSite();
        }
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("USAGE : <input.dcp> <output.dcp> <netName> <clkName>");
            return;
        }

        Design d = Design.readCheckpoint(args[0]);

        EDIFTools.uniqueifyNetlist(d);
        insertFlopTreeForNet(d, args[2], args[3], 4, 3);


//        PartialRouter.routeDesignWithUserDefinedArguments(d,
//                new String[]{"--fixBoundingBox", "--useUTurnNodes", "--nonTimingDriven",});
        d.writeCheckpoint(args[1]);
    }
}
