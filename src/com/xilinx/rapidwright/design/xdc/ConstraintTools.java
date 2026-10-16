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
import java.util.Collection;
import java.util.EnumMap;
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
import com.xilinx.rapidwright.design.xdc.parser.XDCTools;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

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
     * Adds the constraints of the circuits that filled black boxes to the design, as Vivado does
     * when it reads a checkpoint into a cell ({@code read_checkpoint -cell}) or links a
     * reconfigurable module into its partition.
     * <p>
     * {@link com.xilinx.rapidwright.design.DesignTools#populateBlackBox(Design, Map, boolean)}
     * already calls this once the boxes are filled, so do not call it for boxes filled that way:
     * it does not check what the design already has, and would add every command again. It is
     * only for a design whose black boxes were filled some other way.
     * <p>
     * Each circuit's {@link ConstraintGroup#EARLY} and {@link ConstraintGroup#LATE} constraints are
     * added to the same group of the design, scoped to the circuit's cell with
     * {@code current_instance}. Its {@link ConstraintGroup#IN_CONTEXT} constraints, which describe
     * the shell it was implemented in, are not added. Nor are its {@link ConstraintGroup#NORMAL}
     * constraints, which in a module checkpoint create its own pblock and repeat the context's; a
     * warning is printed if they hold anything else, as a design built in memory can keep all of
     * its constraints there.
     * <p>
     * A checkpoint of a reconfigurable module written in the context of a shell, such as an
     * abstract shell, also carries constraints of that context, which the design already has. These
     * are left out:
     * <ul>
     * <li>commands whose {@code src_info} has {@code save:NONE}: constraints of the context, or that
     * Vivado added itself, such as the shell's pblocks and site {@code PROHIBIT}s;</li>
     * <li>commands under a {@code current_instance} that is not the circuit's cell or a cell inside
     * it;</li>
     * <li>{@code HD.RECONFIGURABLE} on the circuit's design;</li>
     * <li>commands that modify the pblock the circuit adds its top cell to
     * ({@code add_cells_to_pblock [get_pblocks <name>] -top}), which is the partition's pblock and is
     * defined by the design;</li>
     * <li>the {@code SRC_FILE_INFO} table.</li>
     * </ul>
     * A command at the circuit's top scope is applied at the design's top scope instead if its
     * {@code get_ports} only name device pins, and it names no cells, nets or pins, which are
     * relative to the circuit. A device pin is a top-level port of the design that the circuit
     * either has no port of the same name for, or whose port of that name the black box connects
     * to it.
     * <p>
     * {@code current_instance} is followed as Vivado does: a path is relative to the current
     * instance, {@code ..} goes up a level, and no path (with or without {@code -quiet}) goes back
     * to the circuit's top. Each scope is written out as a {@code current_instance} of its full
     * name, and only before a command that is kept.
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
     * @return The number of the circuits' commands kept. The {@code current_instance} commands that
     *         scope them, and the {@code src_info} that precedes each, are not counted.
     */
    public static int addBlackBoxConstraints(Design design, Map<String, Design> blackBoxes) {
        EDIFNetlist netlist = design.getNetlist();
        Set<String> topPorts = new HashSet<>();
        for (EDIFPort port : netlist.getTopCell().getPorts()) {
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
            Map<ConstraintGroup, List<XDCCommand>> groups = new EnumMap<>(ConstraintGroup.class);
            for (ConstraintGroup cg : ConstraintGroup.values()) {
                groups.put(cg, XDCCommand.split(cell.getXDCConstraints(cg)));
            }
            BlackBoxScope scope = new BlackBoxScope(netlist, e.getKey(), topPorts, groups.values());

            List<XDCCommand> normal = groups.get(ConstraintGroup.NORMAL);
            if (!normal.isEmpty()) {
                int ignored = scope.apply(normal, true, true).kept;
                if (ignored > 0) {
                    System.out.println("WARNING: " + ignored + " " + ConstraintGroup.NORMAL
                            + " constraint command(s) of the circuit in black box " + e.getKey()
                            + " were not added to the design: only its " + ConstraintGroup.EARLY
                            + " and " + ConstraintGroup.LATE + " constraints are.");
                }
            }
            for (ConstraintGroup cg : new ConstraintGroup[] {ConstraintGroup.EARLY, ConstraintGroup.LATE}) {
                List<XDCCommand> cmds = groups.get(cg);
                if (cmds.isEmpty()) continue;
                // The design's own constraints can leave a cell as the current instance
                List<String> existing = design.getXDCConstraints(cg);
                boolean atTop = existing.isEmpty() || isReturnToTop(existing.get(existing.size() - 1));
                ScopedConstraints scoped = scope.apply(cmds, atTop, false);
                if (!scoped.lines.isEmpty()) {
                    design.addXDCConstraint(cg, scoped.lines.toArray(new String[0]));
                }
                added += scoped.kept;
            }
        }
        return added;
    }

    private static final Pattern ADD_TOP_TO_PBLOCK =
            Pattern.compile("^add_cells_to_pblock\\s+\\[get_pblocks\\s+\\{?([^\\]\\s}]+)\\}?\\]\\s+-top\\b");

    private static final Pattern GET_PORTS =
            Pattern.compile("get_ports\\s+(?:-quiet\\s+)?(?:\\{([^}]*)\\}|([^\\]\\s]+))");

    /** Queries for objects named relative to the current instance */
    private static final Pattern GET_SCOPED_OBJECTS =
            Pattern.compile("\\b(?:get_cells|get_nets|get_pins|all_(?!clocks\\b|inputs\\b|outputs\\b)\\w+)\\b");

    private static final Pattern PBLOCK_COMMAND = Pattern.compile("^(?:create_pblock|add_cells_to_pblock"
            + "|resize_pblock|remove_cells_from_pblock|delete_pblock)\\b|^set_property\\b.*\\[get_pblocks\\s[^\\]]*\\]$");

    /**
     * @return True if a constraint line returns to the top of the design.
     */
    private static boolean isReturnToTop(String line) {
        String t = line.trim();
        return t.equals("current_instance -quiet") || t.equals("current_instance");
    }

    /**
     * A command of a constraint group, with the lines that it continues onto by ending in a
     * backslash, and the {@code set_property src_info} command that precedes it, if any.
     */
    private static final class XDCCommand {
        /** The command's lines, as written */
        final List<String> lines;
        /** The command on one line, each continuation replaced by a space as Tcl does */
        final String text;
        /** The lines of the src_info command that precedes it, or null */
        final List<String> srcInfo;
        /** True if its src_info has save:NONE */
        final boolean saveNone;

        XDCCommand(List<String> lines, String text, List<String> srcInfo) {
            this.lines = lines;
            this.text = text;
            this.srcInfo = srcInfo;
            this.saveNone = srcInfo != null && join(srcInfo).contains("save:NONE");
        }

        static String join(List<String> lines) {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line.endsWith("\\")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(line.trim());
            }
            return sb.toString();
        }

        /**
         * Splits a constraint group into commands, each src_info joined to the command it
         * precedes.
         */
        static List<XDCCommand> split(List<String> xdc) {
            List<XDCCommand> cmds = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            List<String> srcInfo = null;
            for (int i = 0; i < xdc.size(); i++) {
                String line = xdc.get(i);
                lines.add(line);
                if (line.endsWith("\\") && i < xdc.size() - 1) continue;
                String text = join(lines);
                if (text.startsWith("set_property src_info ")) {
                    srcInfo = lines;
                } else {
                    cmds.add(new XDCCommand(lines, text, srcInfo));
                    srcInfo = null;
                }
                lines = new ArrayList<>();
            }
            return cmds;
        }
    }

    /**
     * The constraints of a circuit, scoped to the black box it filled.
     */
    private static final class ScopedConstraints {
        final List<String> lines = new ArrayList<>();
        /** The number of the circuit's commands kept */
        int kept;
    }

    /**
     * Rewrites the constraints of the circuit that filled a black box to apply inside it, as
     * described in {@link #addBlackBoxConstraints(Design, Map)}.
     */
    private static final class BlackBoxScope {
        private final EDIFNetlist netlist;
        private final EDIFHierCellInst box;
        private final String boxName;
        private final Set<String> topPorts;
        /** The pblocks the circuit adds its top cell to */
        private final Set<String> ownPblocks = new HashSet<>();
        /** The names of the black box's pins, by their own name and by the name of their bus */
        private final Map<String, List<String>> pins = new HashMap<>();

        BlackBoxScope(EDIFNetlist netlist, String boxName, Set<String> topPorts,
                Collection<List<XDCCommand>> groups) {
            this.netlist = netlist;
            this.box = netlist.getHierCellInstFromName(boxName);
            this.boxName = box.getFullHierarchicalInstName();
            this.topPorts = topPorts;
            for (List<XDCCommand> cmds : groups) {
                for (XDCCommand c : cmds) {
                    Matcher m = ADD_TOP_TO_PBLOCK.matcher(c.text);
                    if (m.find()) {
                        ownPblocks.add(m.group(1));
                    }
                }
            }
            // From the ports of the box's cell, since an unconnected pin has no port instance
            for (EDIFPort port : box.getCellType().getPorts()) {
                if (!port.isBus()) {
                    pins.computeIfAbsent(port.getName(), k -> new ArrayList<>()).add(port.getName());
                    continue;
                }
                for (int i : port.getBitBlastedIndices()) {
                    String bit = port.getBusName() + "[" + i + "]";
                    pins.computeIfAbsent(bit, k -> new ArrayList<>()).add(bit);
                    pins.computeIfAbsent(port.getBusName(), k -> new ArrayList<>()).add(bit);
                }
            }
        }

        /**
         * @param cmds        A constraint group of the circuit.
         * @param atTop       True if the design's top is the current instance where these are added.
         * @param skipPblocks True to also leave out every pblock command.
         * @return The scoped constraints.
         */
        ScopedConstraints apply(List<XDCCommand> cmds, boolean atTop, boolean skipPblocks) {
            ScopedConstraints out = new ScopedConstraints();
            // The current instance in the circuit's constraints, or null if it is not inside the box
            String current = boxName;
            // The current instance in the lines written so far: "" for the design's top, null if
            // unknown
            String written = atTop ? "" : null;
            for (XDCCommand c : cmds) {
                if (c.text.equals("current_instance") || c.text.startsWith("current_instance ")) {
                    current = currentInstance(current, c.text);
                    continue;
                }
                boolean drop = current == null
                        || c.saveNone
                        || c.text.startsWith("set_property SRC_FILE_INFO ")
                        || modifiesPblock(c.text)
                        || (skipPblocks && PBLOCK_COMMAND.matcher(c.text).find())
                        || (c.text.contains("HD.RECONFIGURABLE") && c.text.contains("[current_design]"));
                if (drop) continue;
                out.kept++;
                String scope = current.equals(boxName) && namesOnlyDevicePins(c.text) ? "" : current;
                if (!scope.equals(written)) {
                    if (!"".equals(written)) {
                        out.lines.add("current_instance -quiet");
                    }
                    if (!scope.isEmpty()) {
                        out.lines.add("current_instance " + XDCTools.quotedName(scope));
                    }
                    written = scope;
                }
                if (c.srcInfo != null) {
                    out.lines.addAll(c.srcInfo);
                }
                out.lines.addAll(c.lines);
            }
            if (written != null && !written.isEmpty()) {
                out.lines.add("current_instance -quiet");
            }
            return out;
        }

        /**
         * Follows a current_instance command of the circuit's constraints.
         *
         * @param current The full name of the current instance before it, or null if that is not
         *                inside the box.
         * @param cmd     The command.
         * @return The full name of the current instance after it, or null if that is not inside
         *         the box.
         */
        private String currentInstance(String current, String cmd) {
            String path = null;
            for (String word : cmd.substring("current_instance".length()).trim().split("\\s+")) {
                if (word.isEmpty() || word.equals("-quiet") || word.equals("-verbose")) continue;
                path = XDCTools.unquotedName(word);
            }
            if (path == null) {
                // Back to the circuit's top, which is the black box
                return boxName;
            }
            if (path.startsWith("/")) {
                current = boxName;
                path = path.substring(1);
            }
            if (current == null) {
                return null;
            }
            String name = current;
            StringBuilder relative = new StringBuilder();
            for (String level : path.split("/")) {
                if (level.isEmpty() || level.equals(".")) continue;
                if (level.equals("..")) {
                    if (relative.length() > 0) {
                        relative.setLength(relative.lastIndexOf("/") < 0 ? 0 : relative.lastIndexOf("/"));
                        continue;
                    }
                    if (name.equals(boxName)) {
                        // Out of the box
                        return null;
                    }
                    EDIFHierCellInst parent = netlist.getHierCellInstFromName(name).getParent();
                    name = parent.getFullHierarchicalInstName();
                    continue;
                }
                if (relative.length() > 0) {
                    relative.append('/');
                }
                relative.append(level);
            }
            if (relative.length() == 0) {
                return name;
            }
            EDIFHierCellInst inst = netlist.getHierCellInstFromName(name + EDIFTools.EDIF_HIER_SEP + relative);
            return inst == null ? null : inst.getFullHierarchicalInstName();
        }

        /**
         * @return True if a command modifies the pblock the circuit adds its top cell to, rather
         *         than merely naming it.
         */
        private boolean modifiesPblock(String cmd) {
            for (String pblock : ownPblocks) {
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
         * @return True if a command names ports with get_ports, all of them device pins, and names
         *         no cells, nets or pins.
         */
        private boolean namesOnlyDevicePins(String cmd) {
            if (GET_SCOPED_OBJECTS.matcher(cmd).find()) return false;
            Matcher m = GET_PORTS.matcher(cmd);
            boolean any = false;
            while (m.find()) {
                String names = m.group(1) != null ? m.group(1) : m.group(2);
                for (String name : names.trim().split("\\s+")) {
                    if (name.isEmpty()) continue;
                    if (!isDevicePin(name)) return false;
                    any = true;
                }
            }
            return any;
        }

        /**
         * @return True if a port named in the circuit's constraints is a top-level port of the
         *         design: one of that name that the circuit has no port of the same name for, or
         *         whose port of that name the black box connects to it.
         */
        private boolean isDevicePin(String name) {
            if (!topPorts.contains(name)) return false;
            List<String> boxPins = pins.get(name);
            if (boxPins == null) return true;
            for (String pinName : boxPins) {
                EDIFPortInst pin = box.getInst().getPortInst(pinName);
                if (pin == null || !reachesTopPort(box, pin, pinName)) return false;
            }
            return true;
        }

        /**
         * @return True if a pin of a cell instance connects, through the hierarchy above it, to
         *         the design's top-level port of the given name.
         */
        private static boolean reachesTopPort(EDIFHierCellInst inst, EDIFPortInst pin, String topName) {
            EDIFNet net = pin.getNet();
            if (net == null) return false;
            EDIFHierCellInst parent = inst.getParent();
            for (EDIFPortInst p : net.getPortInsts()) {
                if (!p.isTopLevelPort()) continue;
                if (parent.isTopLevelInst()) {
                    if (p.getName().equals(topName)) return true;
                    continue;
                }
                EDIFPortInst up = parent.getInst().getPortInst(p.getName());
                if (up != null && reachesTopPort(parent, up, topName)) return true;
            }
            return false;
        }
    }
}
