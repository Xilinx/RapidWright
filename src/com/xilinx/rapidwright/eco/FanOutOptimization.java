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

package com.xilinx.rapidwright.eco;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

/**
 * Performs a fan out optimization of a high fan out net on a fully placed and
 * routed design.
 */
public class FanOutOptimization {

    private static Set<Unisim> supportedCellTypes;
    public static Map<String, String> ffTypeRstName;

    static {
        supportedCellTypes = EnumSet.of(Unisim.FDRE, Unisim.FDSE, Unisim.FDCE, Unisim.FDPE,
                Unisim.LUT1, Unisim.LUT2, Unisim.LUT3, Unisim.LUT4, Unisim.LUT5, Unisim.LUT6);
        ffTypeRstName = new HashMap<>();
        ffTypeRstName.put("FDRE", "R");
        ffTypeRstName.put("FDSE", "S");
        ffTypeRstName.put("FDCE", "CLR");
        ffTypeRstName.put("FDPE", "PRE");
    }


    private static Pair<Site, BEL> findValidPlacementOption(Design design, Cell srcCell,
            Iterator<Site> itr, ECOPlacementHelper ecoHelper, boolean onlyFindEmptySites) {
        boolean isFF = srcCell.getBEL().isFF();

        SiteInst si = srcCell.getSiteInst();
        Net clk = null;
        Net rst = null;
        Net ce = null;
        if (isFF) {
            String rstName = ffTypeRstName.get(srcCell.getType());
            clk = si.getNetFromSiteWire(srcCell.getSiteWireNameFromLogicalPin("C"));
            rst = si.getNetFromSiteWire(srcCell.getSiteWireNameFromLogicalPin(rstName));
            ce = si.getNetFromSiteWire(srcCell.getSiteWireNameFromLogicalPin("CE"));
        }

        while (itr.hasNext()) {
            Site curr = itr.next();
            SiteInst candidate = design.getSiteInstFromSite(curr);
            if (candidate == null) {
                // Empty site, let's use it
                return new Pair<Site, BEL>(curr, curr.getBEL(isFF ? "AFF" : "A6LUT"));
            } else if (onlyFindEmptySites) {
                continue;
            }
            BEL bel = isFF ? ecoHelper.getUnusedFlop(candidate, clk, ce, rst)
                    : ecoHelper.getUnusedLUT(candidate);
            if (bel != null) {
                return new Pair<Site, BEL>(curr, bel);
            }
        }
        return null;
    }

    private static int getManhattanDistance(Cell cell, Point point) {
        return Math.abs(cell.getTile().getRow() - point.y) + Math.abs(cell.getTile().getColumn() - point.x);
    }

    private static Point createPoint(Cell cell) {
        Tile tile = cell.getTile();
        return new Point(tile.getColumn(), tile.getRow());
    }

    /**
     * Given a fully placed and routed design and a net driven by a flip flop, this
     * will replicate the flop by splitByCount times and divide the set of sinks on
     * the net into neighborhood clusters to be re-routed.
     * 
     * @param design       The design
     * @param net          The high fan out net
     * @param splitByCount Desired number to split the fan out.
     */
    public static void cutFanOutOfRoutedNet(Design design, Net net, int splitByCount) {
        cutFanOutOfRoutedNet(design, net, splitByCount, /* onlyUseEmptySites= */true);
    }

    /**
     * Given a fully placed and routed design and a net driven by a flip flop, this
     * will replicate the flop by splitByCount times and divide the set of sinks on
     * the net into neighborhood clusters to be re-routed.
     * 
     * @param design            The design
     * @param net               The high fan out net
     * @param splitByCount      Desired number to split the fan out.
     * @param onlyUseEmptySites Flag to indicate that any replicated cells only be
     *                          placed in empty sites. Default is true and leads to
     *                          more routable scenarios when combined with
     *                          `route_design -preserve`.
     */
    public static void cutFanOutOfRoutedNet(Design design, Net net, int splitByCount,
            boolean onlyUseEmptySites) {
        EDIFHierNet logicalNet = net.getLogicalHierNet();
        int srcIdx = -1;
        List<EDIFHierPortInst> snks = logicalNet.getLeafHierPortInsts();
        for (int i=0; i < snks.size(); i++) {
            if (snks.get(i).isOutput()) { 
                srcIdx = i;
                break;
            }
        }
        EDIFHierPortInst src = snks.remove(srcIdx);
        
        // Replicate source flop splitByCount-1 times
        Cell driverCell = src.getPhysicalCell(design);
        Unisim cellType = Unisim.valueOf(driverCell.getType());
        if (!supportedCellTypes.contains(cellType)) {
            throw new RuntimeException("ERROR: Unsupported driver cell type for fan out optimization "
                    + cellType
                    + " of cell '" + driverCell.getName() + "'.");
        }

        SiteInst si = driverCell.getSiteInst();

        boolean isFF = driverCell.getBEL().isFF();
        Net highFanoutNet = si
                .getNetFromSiteWire(driverCell.getSiteWireNameFromLogicalPin(isFF ? "Q" : "O"));
        Net clk = null;
        Net rst = null;
        Net ce = null;
        String rstName = null;
        Map<String, Net> srcNets = new HashMap<>();
        if (isFF) {
            rstName = ffTypeRstName.get(driverCell.getType());
            srcNets.put("D", si.getNetFromSiteWire(driverCell.getSiteWireNameFromLogicalPin("D")));
            clk = si.getNetFromSiteWire(driverCell.getSiteWireNameFromLogicalPin("C"));
            rst = si.getNetFromSiteWire(driverCell.getSiteWireNameFromLogicalPin(rstName));
            ce = si.getNetFromSiteWire(driverCell.getSiteWireNameFromLogicalPin("CE"));
        } else {
            assert(driverCell.getBEL().isLUT());
            for (EDIFPortInst pi : driverCell.getEDIFCellInst().getPortInsts()) { 
                if (!pi.isInput()) continue;
                String logPinName = pi.getName();
                Net srcNet = si.getNetFromSiteWire(driverCell.getSiteWireNameFromLogicalPin(logPinName));
                srcNets.put(logPinName, srcNet);
            }
        }

        Site highFanoutNetCentroid = ECOPlacementHelper.getCentroidOfNet(highFanoutNet, Utils.sliceTypes);

        EDIFCell parent = driverCell.getParentCell();
        List<Cell> sources = new ArrayList<>();
        sources.add(driverCell);
        List<Net> sourceNets = new ArrayList<>();
        sourceNets.add(highFanoutNet);
        Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(highFanoutNetCentroid).iterator();
        ECOPlacementHelper ecoHelper = new ECOPlacementHelper(design, null);
        for (int i = 0; i < splitByCount - 1; i++) {
            Pair<Site, BEL> loc = findValidPlacementOption(design, driverCell, siteItr, ecoHelper, onlyUseEmptySites);
            String copyName = driverCell.getName() + "_copy" + i;
            Cell copy = design.createAndPlaceCell(parent, copyName, cellType, loc.getFirst(), loc.getSecond());
            copy.setPropertiesMap(driverCell.getEDIFCellInst().createDuplicatePropertiesMap());
            for (Entry<String, Net> e : srcNets.entrySet()) {
                e.getValue().connect(copy, e.getKey());
            }
            if (isFF) {
                clk.connect(copy, "C");
                (rst == null ? design.getGndNet() : rst).connect(copy, rstName);
                (ce == null ? design.getVccNet() : ce).connect(copy, "CE");
            }
            sources.add(copy);
            Net newSrc = design.createNet(highFanoutNet.getName() + "_copy" + i);
            newSrc.connect(copy, isFF ? "Q" : "O");
            sourceNets.add(newSrc);
        }
        
        // Distribute high fanout net sinks among copies
        Set<Point> points = new HashSet<>();
        Map<Point, List<EDIFHierPortInst>> pinMap = new HashMap<>();
        boolean includeSources = false;
        List<EDIFHierPortInst> fanoutSinks = highFanoutNet.getLogicalHierNet().getLeafHierPortInsts(includeSources);
        for (EDIFHierPortInst ehpi : fanoutSinks) {
            Point point = createPoint(ehpi.getPhysicalCell(design));
            points.add(point);
            pinMap.computeIfAbsent(point, l -> new ArrayList<>()).add(ehpi);
        }
        Map<Point, List<Point>> clusters = KMeans.kmeansClustering(points, splitByCount, 50);

        // Disconnect all sinks, we'll reconnect them based on cluster below
        ECOTools.disconnectNet(design, fanoutSinks);

        Map<EDIFHierNet, List<EDIFHierPortInst>> netsToConnect = new HashMap<>();
        boolean[] assigned = new boolean[splitByCount];
        for (Entry<Point, List<Point>> e : clusters.entrySet()) {
            Point centroid = e.getKey();
            int minDist = Integer.MAX_VALUE;
            int closestCellToCluster = -1;
            
            for(int i=0; i < sources.size(); i++) {
                if (assigned[i])
                    continue;
                Cell source = sources.get(i);
                int dist = getManhattanDistance(source, centroid);
                if (dist < minDist) {
                    closestCellToCluster = i;
                    minDist = dist;
                }
            }
            assigned[closestCellToCluster] = true;
            Net newFanoutNet = sourceNets.get(closestCellToCluster);
            List<EDIFHierPortInst> assignedSinks = new ArrayList<>();
            for (Point point : e.getValue()) {
                for (EDIFHierPortInst ehpi : pinMap.get(point)) {
                    assignedSinks.add(ehpi);
                }
            }
            netsToConnect.put(newFanoutNet.getLogicalHierNet(), assignedSinks);
        }

        ECOTools.connectNet(design, netsToConnect, null);
    }
    
    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("USAGE: <input.dcp> <high fanout net name> <split net by k> <output>");
            return;
        }

        Design d = Design.readCheckpoint(args[0]);
        Net n = d.getNet(args[1]);
        int k = Integer.parseInt(args[2]);

        cutFanOutOfRoutedNet(d, n, k);
        
        d.writeCheckpoint(args[3]);
    }
}
