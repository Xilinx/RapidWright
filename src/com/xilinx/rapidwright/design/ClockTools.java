/*
 *
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
package com.xilinx.rapidwright.design;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

public class ClockTools {

    public static Map<String, String> unisimFFs;

    public static Map<String, String> unisimLatches;

    public static Map<String, String> clbRegTypes;

    static {
        unisimFFs = new HashMap<>();
        unisimFFs.put("FDRE", "C");
        unisimFFs.put("FDSE", "C");
        unisimFFs.put("FDCE", "C");
        unisimFFs.put("FDPE", "C");

        unisimLatches = new HashMap<>();
        unisimLatches.put("LDCE", "G");
        unisimLatches.put("LDPE", "G");

        clbRegTypes = new HashMap<>();
        clbRegTypes.putAll(unisimFFs);
        clbRegTypes.putAll(unisimLatches);
    }

    /**
     * If the provided instance is a CLB register (flip-flop or latch), it will get
     * the net of the connected net to the clock pin of the cell and return it.
     * 
     * @param i The instance in question.
     * @return The clock net connected to this instance's clock pin, or null if none
     *         is found.
     */
    public static EDIFHierNet getClockNet(EDIFHierCellInst i) {
        String clkInput = clbRegTypes.get(i.getCellName());
        EDIFHierPortInst portInst = clkInput != null ? i.getPortInst(clkInput) : null;
        return portInst != null ? portInst.getHierarchicalNet() : null;
    }

    /**
     * Gets the clock net from the provided design. If the design has more than one
     * net, it gets the net with the most CLB register fan out.
     * 
     * @param design The design to query.
     * @return The biggest CLB register fan out net in the design.
     */
    public static EDIFHierNet getClockFromDesign(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        Map<EDIFHierNet, Integer> clockSinkCounts = new HashMap<>();
        for (EDIFHierCellInst i : netlist.getAllLeafHierCellInstances()) {
            EDIFHierNet clk = ClockTools.getClockNet(i);
            clk = netlist.getParentNet(clk);
            if (clk != null) {
                clockSinkCounts.compute(clk, (k, v) -> v == null ? 1 : 1 + v);
            }
        }

        int largestFanout = 0;
        EDIFHierNet clkLargestFanout = null;
        for (Entry<EDIFHierNet, Integer> e : clockSinkCounts.entrySet()) {
            if (e.getValue() > largestFanout) {
                largestFanout = e.getValue();
                clkLargestFanout = e.getKey();
            }
        }

        return clkLargestFanout;
    }

}
