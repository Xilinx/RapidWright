/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 *
 */
package com.xilinx.rapidwright.design.blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

/**
 * Different categories of fabric utilization.
 *
 * Created on: Apr 21, 2017
 */
public enum UtilizationType {
    CLB_LUTS("CLB LUTs"),
    LUTS_AS_LOGIC("LUTs as Logic"),
    LUTS_AS_MEMORY("LUTs as Memory"),
    CLB_REGS("CLB Regs"),
    REGS_AS_FFS("Regs as FF"),
    REGS_AS_LATCHES("Regs as Latch"),
    CARRY8S("CARRY8s"),
    F7_MUXES("F7 Muxes"), 
    F8_MUXES("F8 Muxes"), 
    F9_MUXES("F9 Muxes"),
    CLBS("CLBs"),
    CLBLS("CLBLs"),
    CLBMS("CLBMs"),
    RAMB36S_FIFOS("RAMB36s/FIFOs"),
    RAMB18S("RAMB18s"),
    URAMS("URAMs"),
    DSPS("DSPs"), BRAMS("BRAMS");

    private String name;

    public static final UtilizationType[] values = values();

    private UtilizationType(String name) {
        this.name = name;
    }

    public String getString() {
        return name;
    }

    /**
     * Calculates an estimated utilization for the given design's netlist. It
     * doesn't take into account placement and only depends on the design's netlist
     * for an estimate.
     * 
     * @param design The design to query.
     * @return A map of utilization types and their respective counts.
     */
    public static Map<UtilizationType, Integer> computeUtilization(Design design) {
        Map<UtilizationType, Integer> map = new HashMap<>();

        design.getNetlist().collapseMacroUnisims(design.getSeries());

        Set<String> notTracked = new HashSet<>();

        int[] lutCounts = new int[7];

        for (EDIFHierCellInst i : design.getNetlist().getAllLeafHierCellInstances()) {
            String type = i.getCellType().getName();
            switch (type) {
            case "LUT1":
                lutCounts[1]++;
                break;
            case "LUT2":
                lutCounts[2]++;
                break;
            case "LUT3":
                lutCounts[3]++;
                break;
            case "LUT4":
                lutCounts[4]++;
                break;
            case "LUT5":
                lutCounts[5]++;
                break;
            case "LUT6":
            case "LUT6_2":
            case "LUT6CY":
                lutCounts[6]++;
                break;
            case "SRL16E":
            case "SRLC16E":
            case "SRLC32E":
            case "RAMD64E":
            case "RAMS64E":
            case "RAMS64E1":
            case "RAMD32M64":
            case "RAMS32":
            case "RAMD32":
            case "RAMD64E5":
            case "RAM32X1S":
                map.compute(LUTS_AS_MEMORY, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "RAM32X1D":
                map.compute(LUTS_AS_MEMORY, (k, v) -> v == null ? 2 : (v + 2));
                break;
            case "RAM32M":
            case "RAM64M":
                map.compute(LUTS_AS_MEMORY, (k, v) -> v == null ? 4 : (v + 4));
                break;
            case "RAM64X1S":
            case "RAM32M16":
                map.compute(LUTS_AS_MEMORY, (k, v) -> v == null ? 8 : (v + 8));
                break;
            case "FDCE":
            case "FDPE":
            case "FDRE":
            case "FDSE":
            case "AND2B1L":
                map.compute(REGS_AS_FFS, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "LDCE":
            case "LDPE":
                map.compute(REGS_AS_LATCHES, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "CARRY8":
            case "LOOKAHEAD8":
                map.compute(CARRY8S, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "RAMB36E2":
            case "RAMB36E5_INT":
            case "FIFO36E2":
                map.compute(RAMB36S_FIFOS, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "RAMB18E2":
            case "RAMB18E5_INT":
                map.compute(RAMB18S, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "URAM288":
            case "URAM288_BASE":
            case "URAM288E5":
                map.compute(URAMS, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "DSP_PREADD58":
            case "DSP_FP_ADDER":
            case "DSP48E2":
                map.compute(DSPS, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "MUXF7":
                map.compute(F7_MUXES, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "MUXF8":
                map.compute(F8_MUXES, (k, v) -> v == null ? 1 : (v + 1));
                break;
            case "MUXF9":
                map.compute(F9_MUXES, (k, v) -> v == null ? 1 : (v + 1));
                break;
            default:
                if (notTracked.add(type))
                    System.out.println("Didn't count: " + type);
            }
        }

        design.getNetlist().expandMacroUnisims(design.getSeries());

        // Calculate potential estimates for LUTs
        int lutEstimate = 0;
        for (int i = 1; i < 7; i++) {
            lutEstimate += i == 3 ? lutCounts[i] / 2 : lutCounts[i];
        }
        map.put(CLB_LUTS, lutEstimate + map.getOrDefault(LUTS_AS_MEMORY, 0));

        map.put(CLB_REGS, map.getOrDefault(REGS_AS_FFS, 0) + map.getOrDefault(REGS_AS_LATCHES, 0));
        map.put(BRAMS, map.getOrDefault(RAMB36S_FIFOS, 0) + (map.getOrDefault(RAMB18S, 0) / 2));
        return map;
    }
}
