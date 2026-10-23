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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
     * In the other commands at the circuit's top scope, {@code get_ports} names the circuit's own
     * ports, which are the pins of the black box in the design: it is given
     * {@code -scoped_to_current_instance}, as Vivado writes it, so that it finds them. A
     * {@code get_ports} that names only device pins is left as it is, since it finds them as
     * top-level ports from inside the black box too, and the black box's pins are not valid timing
     * startpoints or endpoints. A {@code get_ports} that names both is split into one for each,
     * joined with {@code concat}. A pattern ({@code *} and {@code ?}, or under {@code -regexp})
     * counts by the top-level ports and black box pins it matches; split, it is replaced by their
     * names. A {@code get_ports} that already has the option, or names no ports (such as one given
     * only {@code -of_objects}), is taken to refer to the circuit's ports. Options are told apart
     * from port names as Vivado does: in any order, abbreviated, with values, and over continued
     * lines.
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
        // The design's top-level ports by their own name, the name of their bus and the name of
        // each bit, each to the names of its bits
        Map<String, List<String>> topPorts = new HashMap<>();
        for (EDIFPort port : netlist.getTopCell().getPorts()) {
            if (!port.isBus()) {
                topPorts.computeIfAbsent(port.getName(), k -> new ArrayList<>()).add(port.getName());
                continue;
            }
            for (int i : port.getBitBlastedIndices()) {
                String bit = port.getBusName() + "[" + i + "]";
                topPorts.computeIfAbsent(bit, k -> new ArrayList<>()).add(bit);
                topPorts.computeIfAbsent(port.getBusName(), k -> new ArrayList<>()).add(bit);
                topPorts.computeIfAbsent(port.getName(), k -> new ArrayList<>()).add(bit);
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

    private static final Pattern GET_PORTS = Pattern.compile("\\[\\s*get_ports(?=[\\s\\]\\\\])");

    private static final String SCOPED_TO_CURRENT_INSTANCE = "-scoped_to_current_instance";

    /** The options of get_ports, as Vivado lists them. */
    private static final List<String> GET_PORTS_OPTIONS = Arrays.asList("-regexp", "-nocase", "-filter",
            "-of_objects", "-match_style", SCOPED_TO_CURRENT_INSTANCE, "-no_traverse", "-prop_thru_buffers",
            "-quiet", "-verbose");

    private static final Pattern BACKSLASH_NEWLINE = Pattern.compile("\\\\\n\\s*");

    /** The options of get_ports that take a value. */
    private static final Set<String> GET_PORTS_VALUE_OPTIONS =
            new HashSet<>(Arrays.asList("-filter", "-of_objects", "-match_style"));

    /** Queries for objects named relative to the current instance */
    private static final Pattern GET_SCOPED_OBJECTS =
            Pattern.compile("\\b(?:get_cells|get_nets|get_pins|all_(?!clocks\\b|inputs\\b|outputs\\b)\\w+)\\b");

    private static final Pattern PBLOCK_COMMAND = Pattern.compile("^(?:create_pblock|add_cells_to_pblock"
            + "|resize_pblock|remove_cells_from_pblock|delete_pblock)\\b|^set_property\\b.*\\[get_pblocks\\s[^\\]]*\\]$");

    /**
     * @return Where the Tcl word that starts at the given index ends: a braced or quoted word after
     *         its closing brace or quote, any other word at the first space or unmatched ] outside
     *         brackets.
     */
    private static int tclWordEnd(String s, int start) {
        char first = s.charAt(start);
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                // A backslash and newline that continue the command separate words
                if (first != '{' && depth == 0 && i + 1 < s.length() && s.charAt(i + 1) == '\n') return i;
                i++;
            } else if (first == '{') {
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return i + 1;
                }
            } else if (first == '"') {
                if (c == '"' && i > start) return i + 1;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                if (depth == 0) return i;
                depth--;
            } else if (depth == 0 && Character.isWhitespace(c)) {
                return i;
            }
        }
        return s.length();
    }

    /**
     * @return The index of the first character from the given one that is neither a space nor a
     *         backslash and newline that continue the command.
     */
    private static int skipTclSpace(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                i += 2;
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * @return The option of get_ports that a word names, which Vivado lets be abbreviated as long as
     *         it is unambiguous, or the word itself if it names none.
     */
    private static String getPortsOption(String word) {
        String option = null;
        for (String o : GET_PORTS_OPTIONS) {
            if (o.equals(word)) return o;
            if (o.startsWith(word)) {
                if (option != null) return word;
                option = o;
            }
        }
        return option != null ? option : word;
    }

    /** A get_ports in a command. */
    private static class GetPorts {
        /** The index of the [ that starts it. */
        final int start;
        /** The index just after the name of the command, where its arguments start. */
        final int argsStart;
        /** The index just after the ] that ends it, or -1 if it has none. */
        int end = -1;
        /** Whether it has -scoped_to_current_instance. */
        boolean scoped;
        /** Whether it has -regexp, and -nocase. */
        boolean regexp;
        boolean nocase;
        /** Whether it has -of_objects, or a port list given by a command, which can't be read. */
        boolean indirect;
        /** Its other options, each followed by its value if it takes one, as written. */
        final List<String> options = new ArrayList<>();
        /** The port names or patterns it is given. */
        final List<String> patterns = new ArrayList<>();

        GetPorts(int start, int argsStart) {
            this.start = start;
            this.argsStart = argsStart;
        }
    }

    /**
     * @param cmd A command, either joined into one line, or with the lines it continues onto
     *            separated by newlines.
     * @return Each get_ports in the command, with its options and patterns told apart.
     */
    private static List<GetPorts> findGetPorts(String cmd) {
        List<GetPorts> found = new ArrayList<>();
        Matcher m = GET_PORTS.matcher(cmd);
        while (m.find()) {
            GetPorts getPorts = new GetPorts(m.start(), m.end());
            boolean isValue = false;
            int i = skipTclSpace(cmd, m.end());
            while (i < cmd.length() && cmd.charAt(i) != ']') {
                int end = tclWordEnd(cmd, i);
                String word = cmd.substring(i, end);
                i = skipTclSpace(cmd, end);
                if (isValue) {
                    getPorts.options.add(word);
                    isValue = false;
                } else if (word.startsWith("-")) {
                    String option = getPortsOption(word);
                    if (option.equals(SCOPED_TO_CURRENT_INSTANCE)) {
                        getPorts.scoped = true;
                    } else if (option.equals("-regexp")) {
                        getPorts.regexp = true;
                    } else if (option.equals("-nocase")) {
                        getPorts.nocase = true;
                    } else {
                        getPorts.options.add(word);
                    }
                    getPorts.indirect |= option.equals("-of_objects");
                    isValue = GET_PORTS_VALUE_OPTIONS.contains(option);
                } else if (word.startsWith("[")) {
                    getPorts.indirect = true;
                } else {
                    // Tcl replaces a backslash and newline with a space, even inside braces
                    word = BACKSLASH_NEWLINE.matcher(word).replaceAll(" ");
                    for (String name : XDCTools.unquotedName(word).trim().split("\\s+")) {
                        if (!name.isEmpty()) {
                            getPorts.patterns.add(name);
                        }
                    }
                }
            }
            if (i < cmd.length()) {
                getPorts.end = i + 1;
            }
            found.add(getPorts);
        }
        return found;
    }

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
        /** The design's top-level ports, by name, to the names of their bits */
        private final Map<String, List<String>> topPorts;
        /** The pblocks the circuit adds its top cell to */
        private final Set<String> ownPblocks = new HashSet<>();
        /** The names of the black box's pins, by their own name and by the name of their bus */
        private final Map<String, List<String>> pins = new HashMap<>();

        BlackBoxScope(EDIFNetlist netlist, String boxName, Map<String, List<String>> topPorts,
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
                if (scope.equals(boxName) && !c.text.startsWith("#")) {
                    // At the circuit's top scope, get_ports names the circuit's own ports. Inside
                    // the design, those are the pins of the black box, which get_ports only
                    // returns when told to look in the current instance
                    out.lines.addAll(scopeGetPorts(c.lines));
                } else {
                    out.lines.addAll(c.lines);
                }
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
            List<GetPorts> found = findGetPorts(cmd);
            for (GetPorts getPorts : found) {
                if (!namesOnlyDevicePins(getPorts)) return false;
            }
            return !found.isEmpty();
        }

        /**
         * @return True if a get_ports names ports, all of them device pins. One that has
         *         -scoped_to_current_instance, or names no ports (as with only -of_objects), refers
         *         to the circuit's ports.
         */
        private boolean namesOnlyDevicePins(GetPorts getPorts) {
            if (getPorts.scoped || getPorts.indirect || getPorts.patterns.isEmpty()) return false;
            return sortPorts(getPorts).circuitPorts == null;
        }

        /** The ports a get_ports names, sorted into device pins and ports of the circuit */
        private static final class SortedPorts {
            /** The device pins, or null if it names none */
            List<String> devicePins;
            /** The circuit's ports, or null if it names none */
            List<String> circuitPorts;

            void add(boolean devicePin, Collection<String> names) {
                if (devicePin) {
                    devicePins = devicePins == null ? new ArrayList<>() : devicePins;
                    devicePins.addAll(names);
                } else {
                    circuitPorts = circuitPorts == null ? new ArrayList<>() : circuitPorts;
                    circuitPorts.addAll(names);
                }
            }
        }

        /**
         * Sorts the ports a get_ports names into device pins and ports of the circuit. A name is
         * sorted as a whole. A pattern (with * or ?, or any name under -regexp) is sorted by the
         * bits it matches, of the design's top-level ports and of the black box's pins, as Vivado
         * matches them: * and ? are its only wildcards, a regular expression must match a whole
         * name, and a bus's name matches each of its bits. A pattern that matches no device pin
         * names ports of the circuit.
         */
        private SortedPorts sortPorts(GetPorts getPorts) {
            SortedPorts sorted = new SortedPorts();
            for (String pattern : getPorts.patterns) {
                Pattern regex = portPattern(pattern, getPorts.regexp, getPorts.nocase);
                if (regex == null) {
                    sorted.add(isDevicePin(pattern), Collections.singletonList(pattern));
                    continue;
                }
                Set<String> bits = new TreeSet<>();
                for (Map<String, List<String>> ports : Arrays.asList(topPorts, pins)) {
                    for (Map.Entry<String, List<String>> e : ports.entrySet()) {
                        if (regex.matcher(e.getKey()).matches()) {
                            bits.addAll(e.getValue());
                        }
                    }
                }
                List<String> devicePins = new ArrayList<>();
                List<String> circuitPorts = new ArrayList<>();
                for (String bit : bits) {
                    (isDevicePin(bit) ? devicePins : circuitPorts).add(bit);
                }
                if (devicePins.isEmpty()) {
                    sorted.add(false, circuitPorts);
                } else {
                    sorted.add(true, devicePins);
                    if (!circuitPorts.isEmpty()) {
                        sorted.add(false, circuitPorts);
                    }
                }
            }
            return sorted;
        }

        /**
         * @return The regular expression that a get_ports pattern matches names with, or null if
         *         it is a plain name (or not a valid regular expression).
         */
        private static Pattern portPattern(String pattern, boolean regexp, boolean nocase) {
            if (regexp) {
                try {
                    return Pattern.compile(pattern, nocase ? Pattern.CASE_INSENSITIVE : 0);
                } catch (PatternSyntaxException e) {
                    return null;
                }
            }
            if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) return null;
            StringBuilder sb = new StringBuilder();
            int from = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '*' && c != '?') continue;
                if (i > from) {
                    sb.append(Pattern.quote(pattern.substring(from, i)));
                }
                sb.append(c == '*' ? ".*" : ".");
                from = i + 1;
            }
            if (from < pattern.length()) {
                sb.append(Pattern.quote(pattern.substring(from)));
            }
            return Pattern.compile(sb.toString());
        }

        /**
         * @return A get_ports of the given names, with the given options.
         */
        private static String getPorts(List<String> options, List<String> names) {
            StringBuilder sb = new StringBuilder("[get_ports");
            for (String option : options) {
                sb.append(' ').append(option);
            }
            sb.append(' ');
            boolean plain = true;
            for (String name : names) {
                plain &= !name.isEmpty() && name.chars().noneMatch(c -> Character.isWhitespace(c)
                        || c == '{' || c == '}' || c == '\\');
            }
            if (names.size() == 1) {
                sb.append(XDCTools.quotedName(names.get(0)));
            } else if (plain) {
                sb.append('{').append(String.join(" ", names)).append('}');
            } else {
                sb.append("[list");
                for (String name : names) {
                    sb.append(' ').append(XDCTools.quotedName(name));
                }
                sb.append(']');
            }
            return sb.append(']').toString();
        }

        /**
         * @return The lines of a command at the circuit's top scope, with
         *         -scoped_to_current_instance given to each get_ports that refers to the circuit's
         *         ports and does not have it. A get_ports of only device pins is left as it is: it
         *         finds them as top-level ports from any current instance, and the black box's pins
         *         of those names are not valid timing startpoints or endpoints. A get_ports of both
         *         is split into one of each, joined with concat, each given the names (or the bits
         *         matched by patterns) it finds.
         */
        private List<String> scopeGetPorts(List<String> lines) {
            String cmd = String.join("\n", lines);
            List<GetPorts> found = findGetPorts(cmd);
            StringBuilder sb = new StringBuilder(cmd);
            for (int i = found.size() - 1; i >= 0; i--) {
                GetPorts getPorts = found.get(i);
                if (getPorts.scoped) continue;
                SortedPorts sorted = getPorts.indirect || getPorts.patterns.isEmpty() ? null : sortPorts(getPorts);
                if (sorted != null && sorted.circuitPorts == null) continue;
                if (sorted == null || sorted.devicePins == null || getPorts.end < 0) {
                    sb.insert(getPorts.argsStart, " " + SCOPED_TO_CURRENT_INSTANCE);
                    continue;
                }
                // The names are given as they are, so -regexp and -nocase no longer apply
                List<String> scopedOptions = new ArrayList<>();
                scopedOptions.add(SCOPED_TO_CURRENT_INSTANCE);
                scopedOptions.addAll(getPorts.options);
                String split = "[concat " + getPorts(getPorts.options, sorted.devicePins) + " "
                        + getPorts(scopedOptions, sorted.circuitPorts) + "]";
                sb.replace(getPorts.start, getPorts.end, split);
            }
            return Arrays.asList(sb.toString().split("\n", -1));
        }

        /**
         * @return True if a port named in the circuit's constraints is a top-level port of the
         *         design: one of that name that the circuit has no port of the same name for, or
         *         whose port of that name the black box connects to it.
         */
        private boolean isDevicePin(String name) {
            if (!topPorts.containsKey(name)) return false;
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
