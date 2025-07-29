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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Utils;

/**
 * Optimizes a placed and routed design where the critical path has a segment
 * containing contiguous LUTs that can be optimized into a single LUT to reduce
 * logic depth.
 */
public class LUTInputConeOpt {

    /**
     * Optimizes the LUT input cone being driven by the provided pin such that
     * chained small LUTs can be replaced by a larger LUT instance. Limited to a
     * single replacement LUT at a time (up to 6 inputs).
     * 
     * @param design The current design
     * @param input  Input pin driven by a series of LUTs that should be optimized
     *               into a single LUT.
     * @return Returns the resulting cell that was the result of the optimization or
     *         null if no changes were made.
     */
    public static Cell optimizedLUTInputCone(Design design, EDIFHierPortInst input) {
        EDIFHierNet hierNet = input.getHierarchicalNet();
        List<EDIFHierPortInst> srcs = hierNet.getLeafHierPortInsts(true, false);
        assert (srcs.size() == 1);
        EDIFHierPortInst src = srcs.get(0);
        if (!LUTTools.isCellALUT(src.getFullHierarchicalInst().getInst())) {
            // If cell is not a LUT, we cannot combine it
            return null;
        }

        EDIFHierCellInst rootLut = src.getFullHierarchicalInst();
        Map<EDIFHierPortInst, EDIFHierNet> sourceNets = new HashMap<>();
        Queue<EDIFHierPortInst> q = new LinkedList<>();
        q.add(src);
        while (!q.isEmpty()) {
            EDIFHierPortInst lutOutput = q.poll();
            for (EDIFHierPortInst lutPin : lutOutput.getFullHierarchicalInst().getHierPortInsts()) {
                if (lutPin.isOutput()) {
                    continue;
                }
                EDIFHierNet currNet = lutPin.getHierarchicalNet();
                if (currNet == null) {
                    throw new RuntimeException("ERROR: Unconnected input on LUT: "
                            + lutOutput.getFullHierarchicalInstName());
                }
                srcs = currNet.getLeafHierPortInsts(true, false);
                assert (srcs.size() == 1);
                src = srcs.get(0);
                if (LUTTools.isCellALUT(src.getFullHierarchicalInst().getInst())) {
                    q.add(src);
                } else {
                    sourceNets.put(lutPin, src.getHierarchicalNet());
                }
            }
        }
        
        int lutSize = sourceNets.size();
        if (lutSize > 6) {
            throw new RuntimeException("ERROR: Unsupported LUT optimization, 6 maximum inputs "
                    + "supported, found " + sourceNets.size());
        }
        if (lutSize < 2) {
            // Do nothing, only a LUT1 in path
            return null;
        }
        EDIFCell optLut = design.getNetlist().getHDIPrimitivesLibrary().getCell("LUT" + lutSize);
        if (optLut == null) {
            optLut = Design.getUnisimCell(Unisim.valueOf("LUT" + lutSize));
            design.getNetlist().getHDIPrimitivesLibrary().addCell(optLut);
        }
        EDIFCell targetParent = input.getParentCell();
        if (Design.getMacroPrimitives(design.getSeries()).containsCell(targetParent)) {
            // We're inside a macro, pop out one more level
            targetParent = input.getFullHierarchicalInst().getParent().getParent().getCellType();
        }
        input.getFullHierarchicalInst().ensureAncestorsAreUniquified();
        EDIFCellInst lutInst = optLut.createCellInst("optimized_lut" + EDIFTools.getUniqueSuffix(),
                targetParent);
        EDIFHierCellInst hierLutInst = input.getHierarchicalInst().getChild(lutInst);

        int inputIdx = 0;
        Map<EDIFHierPortInst, String> newLutInputs = new HashMap<>();
        for (Entry<EDIFHierPortInst, EDIFHierNet> e : sourceNets.entrySet()) {
            String pin = "I" + inputIdx;
            EDIFPortInst newLutInput = lutInst.getOrCreatePortInst(pin);
            EDIFHierPortInst currPin = new EDIFHierPortInst(input.getHierarchicalInst(), newLutInput);
            EDIFTools.connectPortInstsThruHier(e.getKey(), currPin, "opt_" + pin);
            newLutInputs.put(e.getKey(), pin);
            inputIdx++;
        }

        // Configure the LUT with an equation that is derived from the participating
        // source LUTs
        String eq = getCombinedEquation(rootLut, sourceNets, newLutInputs);
        LUTTools.configureLUT(lutInst, eq);

        ECOTools.disconnectNet(design, input);
        
        // Place the new LUT near the centroid of its sources
        List<Point> points = new ArrayList<>();
        for (Entry<EDIFHierPortInst, EDIFHierNet> e : sourceNets.entrySet()) {
            Cell cell = e.getKey().getPhysicalCell(design);
            if (cell == null || cell.getTile() == null) {
                // Cell is not placed, skip
                continue;
            }
            Tile tile = cell.getTile();
            points.add(new Point(tile.getColumn(), tile.getRow()));
        }
        Cell physCell = null;
        if (points.size() == 0) {
            // No other connecting cell is placed, let's not place the cell
            physCell = createAndConnectCell(design, hierLutInst, lutInst, input, null);
        } else {
            Site centroid = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, Utils.sliceTypes);
            Iterator<Site> itr = ECOPlacementHelper.spiralOutFrom(centroid).iterator();
            while (itr.hasNext()) {
                Site curr = itr.next();
                SiteInst candidate = design.getSiteInstFromSite(curr);
                if (candidate == null) {
                    physCell = createAndConnectCell(design, hierLutInst, lutInst, input, curr);
                    break;
                }
            }
        }

        return physCell;
    }

    private static List<EDIFHierPortInst> getSharedSinksInSite(Design design, EDIFHierPortInst input) {
        Cell c = design.getCell(input.getFullHierarchicalInstName());
        if (c == null) {
            return Collections.emptyList();
        }
        List<EDIFHierPortInst> sharedPortInsts = new ArrayList<>();
        String siteWire = c.getSiteWireNameFromLogicalPin(input.getPortInst().getName());
        for (BELPin pin : c.getSite().getBELPins(siteWire)) {
            if (pin.isInput()) {
                Cell otherCell = c.getSiteInst().getCell(pin.getBEL());
                if (otherCell != null && !otherCell.isRoutethru() && c != otherCell) {
                    String logPinName = otherCell.getLogicalPinMapping(pin.getName());
                    if (logPinName != null) {
                        EDIFHierPortInst ehpi = otherCell.getEDIFHierCellInst()
                                .getPortInst(logPinName);
                        sharedPortInsts.add(ehpi);
                    }
                }
            }
        }

        return sharedPortInsts;
    }

    private static Cell createAndConnectCell(Design design, EDIFHierCellInst hierLutInst, EDIFCellInst lutInst,
            EDIFHierPortInst input, Site site) {
        Cell physCell = design.createCell(hierLutInst.getFullHierarchicalInstName(), lutInst);
        if (site != null)
            design.placeCell(physCell, site, site.getBEL("A6LUT"));
        EDIFNet optLutOutput = input.getParentCell().createNet(lutInst.getName());
        EDIFHierNet hierLutOutput = new EDIFHierNet(input.getFullHierarchicalInst().getParent(), optLutOutput);
        List<EDIFHierPortInst> pinsToConnect = new ArrayList<>();
        pinsToConnect.add(input);
        pinsToConnect.add(new EDIFHierPortInst(hierLutInst.getParent(), lutInst.getOrCreatePortInst("O")));

        // Check for other cells physically sharing the same SitePinInst input
        List<EDIFHierPortInst> otherPins = getSharedSinksInSite(design, input);
        if (otherPins.size() > 0) {
            // Include other sinks in the optimization by disconnecting them also and
            // connecting them to the optimized LUT output
            ECOTools.disconnectNet(design, otherPins);
            pinsToConnect.addAll(otherPins);
        }

        Map<EDIFHierNet, List<EDIFHierPortInst>> map = new HashMap<>();
        map.put(hierLutOutput, pinsToConnect);
        ECOTools.connectNet(design, map, null);
        return physCell;
    }

    /**
     * This method will recursively explore the inputs and combine the LUT equations
     * into a single one.
     * 
     * @param lut         The top or root LUT to start from.
     * @param sourceNets  A map of all LUT inputs participating in the LUT reduction
     *                    optimization to their respective nets.
     * @param newLutInput A map of the all existing LUT inputs to their new LUT
     *                    input on the combined LUT.
     * @return The combined LUT equation for the provided LUT.
     */
    private static String getCombinedEquation(EDIFHierCellInst lut,
            Map<EDIFHierPortInst, EDIFHierNet> sourceNets,
            Map<EDIFHierPortInst, String> newLutInput) {
        String eq = LUTTools.getLUTEquation(lut);
        for (EDIFHierPortInst lutPin : lut.getHierPortInsts()) {
            if (lutPin.isOutput())
                continue;
            String oldPinName = lutPin.getPortInst().getName();
            if (sourceNets.containsKey(lutPin)) {
                String newPin = newLutInput.get(lutPin);
                // We will replace I with Q to indicate that pin has already been processed and
                // to avoid name collisions in the string replace
                newPin = newPin.replace("I", "Q");
                eq = eq.replace(oldPinName, newPin);
            } else {
                EDIFHierPortInst lutOutput = lutPin.getHierarchicalNet()
                        .getLeafHierPortInsts(true, false).get(0);
                String pinEq = getCombinedEquation(lutOutput.getFullHierarchicalInst(), sourceNets,
                        newLutInput);
                pinEq = pinEq.replace("I", "Q");
                pinEq = "(" + pinEq.substring(pinEq.indexOf('=') + 1) + ")";
                eq = eq.replace(oldPinName, pinEq);
            }
        }
        return eq.replace("Q", "I");
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println(
                    "USAGE: <input.dcp> <output> <Hierarchical input pin> [Hierarchical input pin...]");
            return;
        }

        Design d = Design.readCheckpoint(args[0]);
        for (int i = 2; i < args.length; i++) {
            EDIFHierPortInst portInst = d.getNetlist().getHierPortInstFromName(args[i]);
            if (optimizedLUTInputCone(d, portInst) == null) {
                System.err.println("Failed to optimize input " + portInst.toString());
            }
        }

        d.writeCheckpoint(args[1]);
    }
}
