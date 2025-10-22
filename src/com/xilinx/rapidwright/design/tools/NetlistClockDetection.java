/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Misha Matlin, Advanced Micro Devices, Inc.
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONObject;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class NetlistClockDetection {

    /** A map whose keys are FPGA series and whose values are maps from the names of
     * those series' primitives which have clock input pins to those clock input pins.
     */
    public static Map<Series, Map<String, Set<String>>> seriesPrimsToClockPins = new HashMap<>();

    static {
        // Determined manually using `get_property IS_CLOCK` on all possible
        // primitive input pins in Vivado
        Map<String, Set<String>> versalPrimsToClockPins = new HashMap<>();
        versalPrimsToClockPins.put("BUFGCTRL", Stream.of("I0", "I1").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("BUFGCE_DIV", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("BUFGCE", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("BUFG_FABRIC", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("BUFG_GT", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("BUFG_PS", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("SRLC32E", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("DPLL", Stream.of("DCLK", "PSCLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("DSP48E5", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("DSP58", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("DSPCPLX", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("DSPFP32", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("FDCE", Stream.of("C").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("FDPE", Stream.of("C").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("FDRE", Stream.of("C").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("FDSE", Stream.of("C").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("IDDRE1", Stream.of("C", "CB").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("IDELAYE5", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("LDCE", Stream.of("G").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("LDPE", Stream.of("G").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("MBUFGCE_DIV", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("MBUFGCE", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("MBUFGCTRL", Stream.of("I0", "I1").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("MBUFG_GT", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("MBUFG_PS", Stream.of("I").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("MMCME5", Stream.of("DCLK", "PSCLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("ODDRE1", Stream.of("C").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("ODELAYE5", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("XPLL", Stream.of("DCLK", "PSCLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("RAM32X16DR8", Stream.of("WCLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("RAM64X8SW", Stream.of("WCLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("RAMB18E5_INT", Stream.of("CLKARDCLK", "CLKBWRCLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("RAMB36E5_INT", Stream.of("CLKARDCLKL", "CLKARDCLKU", "CLKBWRCLKL", "CLKBWRCLKU").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("SRL16E", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("URAM288E5_BASE", Stream.of("CLK").collect(Collectors.toSet()));
        versalPrimsToClockPins.put("URAM288E5", Stream.of("CLK").collect(Collectors.toSet()));
        seriesPrimsToClockPins.put(Series.Versal, versalPrimsToClockPins);
    }

    /**
     * Finds all terminal hierarchical port insts (pins) which gate changes to the given
     * hierarchical net's value, i.e., finds all terminal pins which may be drivers of
     * the net through non-clocked primitives and all likely terminal clock signal pins
     * that gate signals which drive the net through clocked primitives.
     *
     * For example, when run on the output hierarchical net of a LUT2 whose inputs come
     * from a flop and a LUT, this will return the terminal hierarchical port inst(s)
     * driving the flop's clock pin as well as any gating drivers of the input LUT's
     * inputs, determined recursively.
     *
     * @param hNet             The hierarchical net to find gating drivers of.
     * @param primsToClockPins A map from primitive names to their input clock pins, if
     *                         the primitive does have input clock pins. This is
     *                         specific to the FPGA series.
     * @param encountered      A set of already-encountered EDIFHierNets to skip during
     *                         netlist traversal.
     */
    static Set<EDIFHierPortInst> getGatingDrivers(EDIFHierNet hNet, Map<String, Set<String>> primsToClockPins, Set<EDIFHierNet> encountered) {
        Set<EDIFHierPortInst> out = new HashSet<EDIFHierPortInst>();

        for (EDIFHierPortInst hPI : hNet.getLeafHierPortInsts(true, false)) {
            if (hPI.getPortInst().getCellInst() == null) {
                out.add(hPI);
            } else {
                Set<String> clkPins = primsToClockPins.get(hPI.getPortInst().getCellInst().getCellType().getName());
                EDIFHierNet sourceHierNet;
                EDIFHierCellInst leafCellInst = hPI.getHierarchicalInst().getChild(hPI.getPortInst().getCellInst());

                List<EDIFHierPortInst> leafCellInstInputs =
                    leafCellInst.getHierPortInsts().stream().filter(EDIFHierPortInst::isInput).collect(Collectors.toList());

                if (!leafCellInstInputs.isEmpty()) {
                    for (EDIFHierPortInst leafCellInstInput : leafCellInstInputs) {
                        if (clkPins == null || clkPins.contains(leafCellInstInput.getPortInst().getName())) {
                            if ((sourceHierNet = leafCellInstInput.getHierarchicalNet()) != null && !encountered.contains(sourceHierNet)) {
                                encountered.add(sourceHierNet);
                                out.addAll(getGatingDrivers(sourceHierNet, primsToClockPins, encountered));
                            }
                        }
                    }
                } else {
                    out.add(hPI);
                }
            }
        }

        return out;
    }

    static Set<EDIFHierPortInst> getGatingDrivers(EDIFHierNet hNet, Map<String, Set<String>> primsToClockPins) {
        return getGatingDrivers(hNet, primsToClockPins, new HashSet<>());
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("USAGE: <input_design{.edif|.dcp}> hier_net_name_1 hier_net_name_2 ...");
            return;
        }

        EDIFNetlist nl = args[0].endsWith(".dcp") ? Design.readCheckpoint(args[0]).getNetlist() : EDIFTools.readEdifFile(args[0]);

        Map<String, Set<String>> primsToClockPins = seriesPrimsToClockPins.get(nl.getDevice().getSeries());

        if (primsToClockPins == null) {
            throw new RuntimeException("ERROR: NetlistClockDetection only supports likely clock pin detection for Versal-targeting netlists. "
                + "The provided netlist targets " + nl.getDevice().getSeries() + ".");
        }

        nl.expandMacroUnisims();

        JSONObject out = new JSONObject();

        for (int i = 1; i < args.length; ++i) {
            String hNetName = args[i];
            EDIFHierNet hNet = nl.getHierNetFromName(hNetName);
            if (hNet == null) {
                System.err.printf(
                    "Skipping given hierarchical net named %s which could not be found in the provided netlist....\n", hNetName);
                continue;
            }

            out.put(hNetName, getGatingDrivers(hNet, primsToClockPins).stream().map(EDIFHierPortInst::toString).toArray());
        }

        System.out.println(out.toString(4));
    }
}
