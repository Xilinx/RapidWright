/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Coherent Ho, Synopsys, Inc.
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

package com.xilinx.rapidwright.design.xdc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.xdc.parser.RegularEdifCellLookup;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * A collection of methods to access design constraints.
 *
 * Created on: Oct 31, 2025
 */
public class ConstraintTools {

    public static Map<String, PBlock> getPBlocksFromXDC(Design d) {
        Map<String, PBlock> pblockMap = new HashMap<>();

        for (ConstraintGroup cg : ConstraintGroup.values()) {
            XDCConstraints xdcConstraints = XDCParser.parseXDC(d.getDevice(), d.getXDCConstraints(cg), new RegularEdifCellLookup(d.getNetlist()));
            xdcConstraints.getPBlockConstraints().forEach((k,v)->{
                pblockMap.put(k, v.getPblock());
            });
        }

        return pblockMap;
    }

    public static List<String> getClockNetPortNamesFromXDC(Design d) {
        Set<String> clockNets = new HashSet<>();

        for (ConstraintGroup cg : ConstraintGroup.values()) {
            XDCConstraints xdcConstraints = XDCParser.parseXDC(d.getDevice(), d.getXDCConstraints(cg), new RegularEdifCellLookup(d.getNetlist()));
            xdcConstraints.getClockConstraints().values().forEach((v) -> {
                clockNets.add(v.getPortName());
            });
        }
        return new ArrayList<>(clockNets);
    }

    /**
     * Adds the constraints of the circuits that filled black boxes, which
     * {@link com.xilinx.rapidwright.design.DesignTools#populateBlackBox(Design, Map, boolean)} does not
     * carry over, to the design. As Vivado does when it links a reconfigurable module into its
     * partition, each circuit's {@link ConstraintGroup#EARLY} and {@link ConstraintGroup#LATE}
     * constraints are added to the same group of the design, scoped to the circuit's cell with
     * {@code current_instance}. The circuit's {@link ConstraintGroup#NORMAL} constraints (which
     * create its own pblock) and {@link ConstraintGroup#IN_CONTEXT} constraints (which describe the
     * shell it was implemented in) are not added.
     * <p>
     * A checkpoint of a reconfigurable module written in the context of a shell, such as an
     * abstract shell, also carries constraints of that context, which the design already has. These
     * are left out:
     * <ul>
     * <li>commands whose {@code src_info} has {@code save:NONE}: constraints of the context, or that
     * Vivado added itself, such as the shell's pblocks and site {@code PROHIBIT}s;</li>
     * <li>commands under a {@code current_instance} that is not a cell inside the black box;</li>
     * <li>{@code HD.RECONFIGURABLE} on the circuit's design;</li>
     * <li>commands that modify the pblock the circuit adds its top cell to
     * ({@code add_cells_to_pblock [get_pblocks <name>] -top}), which is the partition's pblock and is
     * defined by the design;</li>
     * <li>the {@code SRC_FILE_INFO} table.</li>
     * </ul>
     * Commands at the circuit's top scope whose {@code get_ports} only name top-level ports of the
     * design are applied at the design's top scope instead, as they constrain device pins.
     * <p>
     * These rules go by the text of the constraints, as Vivado writes them to a checkpoint. They
     * were checked against Vivado's {@code link_design} of DFX designs (a static shell and a
     * reconfigurable module for each of its partitions), with which the results then agreed on DRCs.
     * This must be called after the black boxes have been filled, since it looks up the cells that
     * constraints are scoped to in the design's netlist.
     *
     * @param design     The design whose black boxes were filled.
     * @param blackBoxes The circuits that filled them, keyed by the hierarchical name of each black
     *                   box, as given to populateBlackBox().
     * @return The number of commands added.
     */
    public static int addBlackBoxConstraints(Design design, Map<String, Design> blackBoxes) {
        Set<String> topPorts = new HashSet<>();
        for (EDIFPort port : design.getNetlist().getTopCell().getPorts()) {
            topPorts.add(port.getBusName());
            topPorts.add(port.getName());
            if (port.isBus()) {
                for (int i : port.getBitBlastedIndices()) {
                    topPorts.add(port.getBusName() + "[" + i + "]");
                }
            }
        }

        int added = 0;
        for (Map.Entry<String, Design> e : blackBoxes.entrySet()) {
            Design cell = e.getValue();
            // The partition's own pblock is the one the circuit adds its top cell to
            Set<String> ownPblocks = new HashSet<>();
            for (ConstraintGroup cg : ConstraintGroup.values()) {
                for (String line : cell.getXDCConstraints(cg)) {
                    Matcher m = ADD_TOP_TO_PBLOCK.matcher(line.trim());
                    if (m.find()) {
                        ownPblocks.add(m.group(1));
                    }
                }
            }
            for (ConstraintGroup cg : new ConstraintGroup[] {ConstraintGroup.EARLY, ConstraintGroup.LATE}) {
                List<String> xdc = cell.getXDCConstraints(cg);
                if (xdc.isEmpty()) continue;
                int[] count = new int[1];
                List<String> scoped = scopeBlackBoxConstraints(e.getKey(), design.getNetlist(), topPorts,
                        ownPblocks, xdc, count);
                design.addXDCConstraint(cg, scoped.toArray(new String[0]));
                added += count[0];
            }
        }
        return added;
    }

    private static final Pattern ADD_TOP_TO_PBLOCK =
            Pattern.compile("^add_cells_to_pblock\\s+\\[get_pblocks\\s+\\{?([^\\]\\s}]+)\\}?\\]\\s+-top\\b");

    private static final Pattern GET_PORTS =
            Pattern.compile("get_ports\\s+(?:-quiet\\s+)?(?:\\{([^}]*)\\}|([^\\]\\s]+))");

    /**
     * @return True if a command names ports with get_ports, all of them top-level ports.
     */
    private static boolean refersOnlyToTopPorts(String cmd, Set<String> topPorts) {
        Matcher m = GET_PORTS.matcher(cmd);
        boolean any = false;
        while (m.find()) {
            String names = m.group(1) != null ? m.group(1) : m.group(2);
            for (String name : names.trim().split("\\s+")) {
                if (name.isEmpty()) continue;
                if (!topPorts.contains(name)) return false;
                any = true;
            }
        }
        return any;
    }

    /**
     * @return True if a command modifies one of the given pblocks, rather than merely naming it.
     */
    private static boolean modifiesPblock(String cmd, Set<String> pblocks) {
        for (String pblock : pblocks) {
            String ref = "[get_pblocks " + pblock + "]";
            String bracedRef = "[get_pblocks {" + pblock + "}]";
            if (!cmd.contains(ref) && !cmd.contains(bracedRef)) continue;
            if (cmd.startsWith("add_cells_to_pblock") || cmd.startsWith("resize_pblock")
                    || cmd.startsWith("remove_cells_from_pblock") || cmd.startsWith("delete_pblock")) {
                return true;
            }
            if (cmd.startsWith("set_property") && (cmd.endsWith(ref) || cmd.endsWith(bracedRef))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites a circuit's constraints to apply inside the black box it filled, as described in
     * {@link #addBlackBoxConstraints(Design, Map)}.
     *
     * @param cellName   The hierarchical name of the black box.
     * @param netlist    The design's netlist, with the black box filled.
     * @param topPorts   The names of the design's top-level ports, and of each of their bits.
     * @param ownPblocks The pblocks that the circuit adds its top cell to.
     * @param xdc        The circuit's constraints.
     * @param count      Its first element is incremented for each command kept.
     * @return The scoped constraints.
     */
    private static List<String> scopeBlackBoxConstraints(String cellName, EDIFNetlist netlist,
            Set<String> topPorts, Set<String> ownPblocks, List<String> xdc, int[] count) {
        List<String> out = new ArrayList<>(xdc.size() + 2);
        out.add("current_instance " + cellName);
        boolean inForeignScope = false;
        boolean inNestedScope = false;
        String pendingSrcInfo = null;
        StringBuilder cmd = new StringBuilder();
        List<String> cmdLines = new ArrayList<>();
        for (String line : xdc) {
            cmdLines.add(line);
            cmd.append(line).append('\n');
            // A command continues onto the next line
            if (line.endsWith("\\")) continue;
            String t = cmd.toString().trim();
            cmd.setLength(0);
            List<String> lines = new ArrayList<>(cmdLines);
            cmdLines.clear();

            // The src_info of a command precedes it
            if (t.startsWith("set_property src_info ")) {
                pendingSrcInfo = String.join("\n", lines);
                continue;
            }
            String srcInfo = pendingSrcInfo;
            pendingSrcInfo = null;
            if (t.equals("current_instance -quiet") || t.equals("current_instance")) {
                // Back to the circuit's top, which is the black box
                inForeignScope = false;
                inNestedScope = false;
                out.add("current_instance -quiet");
                out.add("current_instance " + cellName);
                continue;
            }
            if (t.startsWith("current_instance ") && !t.substring(17).trim().startsWith("-")) {
                String path = t.substring(17).trim();
                inForeignScope = netlist.getHierCellInstFromName(cellName + "/" + path) == null;
                inNestedScope = true;
                if (!inForeignScope) {
                    out.add("current_instance -quiet");
                    out.add("current_instance " + cellName + "/" + path);
                }
                continue;
            }
            boolean drop = inForeignScope
                    || (srcInfo != null && srcInfo.contains("save:NONE"))
                    || t.startsWith("set_property SRC_FILE_INFO ")
                    || modifiesPblock(t, ownPblocks)
                    || (t.contains("HD.RECONFIGURABLE") && t.contains("[current_design]"));
            if (drop) continue;
            count[0]++;
            boolean atTop = !inNestedScope && refersOnlyToTopPorts(t, topPorts);
            if (atTop) {
                out.add("current_instance -quiet");
            }
            if (srcInfo != null) {
                out.add(srcInfo);
            }
            out.addAll(lines);
            if (atTop) {
                out.add("current_instance " + cellName);
            }
        }
        out.add("current_instance -quiet");
        return out;
    }
}
