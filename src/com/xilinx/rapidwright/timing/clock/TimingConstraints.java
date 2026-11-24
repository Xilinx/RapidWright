/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * The timing constraints of a design that the timing analysis supports, parsed from its XDC
 * constraints (the EARLY, NORMAL and LATE groups, in Vivado's processing order). The supported set
 * ("core set") is:
 * <ul>
 * <li>{@code create_clock -name -period -waveform -add} on ports, pins or nets (or virtual);</li>
 * <li>{@code create_generated_clock -name -source -master_clock -divide_by -multiply_by -edges
 * -edge_shift -duty_cycle -invert -add};</li>
 * <li>{@code set_clock_groups -asynchronous|-logically_exclusive|-physically_exclusive -group};</li>
 * <li>{@code set_false_path} and {@code set_multicycle_path} with {@code -from}/{@code -to}
 * (clocks, cells, pins, ports) and {@code -setup}/{@code -hold}/{@code -start}/{@code -end};</li>
 * <li>{@code set_max_delay -datapath_only}, only for its effect on hold: a false path for hold with the
 * same {@code -from}/{@code -to}/{@code -through} (the maximum delay is not modeled);</li>
 * <li>{@code set_clock_uncertainty} ({@code -setup}/{@code -hold}, {@code -from}/{@code -to} or a
 * list of clocks, {@code -add}), {@code set_system_jitter} and {@code set_input_jitter};</li>
 * <li>{@code set_clock_latency [-source] [-min|-max] [-early|-late]} on clocks: a source latency adds to
 * the insertion delays of the clock (and of the clocks generated from it), a network latency makes
 * the clock ideal (its latency replacing the insertion delays); -min/-early sets the min corners'
 * value, -max/-late the max corners', neither both;</li>
 * <li>{@code set_case_analysis 0|1} on pins, only to derive clocks (see {@link #getCaseValue(String)});</li>
 * <li>object queries {@code get_clocks [-of_objects] [-include_generated_clocks]},
 * {@code get_cells [-hier]}, {@code get_pins}, {@code get_ports}, {@code get_nets},
 * {@code all_clocks}, {@code all_fanin -flat -startpoints_only [-only_cells]}, {@code -of_objects}
 * and simple {@code -filter} expressions, nested in brackets; {@code current_instance};
 * {@code list}; Tcl variables set by {@code set}.</li>
 * </ul>
 * Any other command gets one warning per kind (see {@link #getWarnings()}) and is ignored. A
 * command that Vivado would reject (e.g. with an unknown or ambiguous option, an undefined Tcl
 * variable or a {@code -filter} that does not parse) or that uses a Tcl command the reader does not
 * evaluate is ignored as a whole, with a warning. An exception whose meaning would change without an
 * unsupported option (e.g. {@code -rise_from}, {@code -rise_through}) is ignored as a whole. The
 * {@code -through} objects
 * of an exception are kept ({@link PathExceptionDef#through}); what the analysis does with them is
 * up to it (see StaClockBinding). The Vivado names of auto-derived
 * clocks, stored in the design as the TOOL_DERIVED_CLK_NAMES property, are read too.
 * <p>
 * Times are in ns, as in the XDC.
 */
public final class TimingConstraints {

    /** A primary clock: {@code create_clock} */
    public static final class ClockDef {
        public final String name;
        /** Period in ns */
        public final double period;
        /** Edge times in ns (rise, fall, ...), within the period */
        public final double[] waveform;
        /** Source objects (ports, pins, nets); empty for a virtual clock */
        public final List<XdcObject> sources;
        public final boolean add;
        public final int index;

        ClockDef(String name, double period, double[] waveform, List<XdcObject> sources, boolean add, int index) {
            this.name = name;
            this.period = period;
            this.waveform = waveform;
            this.sources = sources;
            this.add = add;
            this.index = index;
        }
    }

    /** A user generated clock: {@code create_generated_clock} */
    public static final class GeneratedClockDef {
        /** Its name, or null to name it after its first source object */
        public final String name;
        /** The master source pin (or port) */
        public final XdcObject source;
        /** Name of the master clock, or null (see {@link #masterClockQuery}) */
        public final String masterClock;
        /** The get_clocks query of -master_clock (a pattern or -of_objects), or null */
        public final XdcObject masterClockQuery;
        public final int divideBy;
        public final int multiplyBy;
        /** Master edges (1-based), or null */
        public final int[] edges;
        /** Edge shifts in ns, or null */
        public final double[] edgeShift;
        /** Duty cycle in percent, NaN if not given */
        public final double dutyCycle;
        public final boolean invert;
        public final boolean add;
        public final boolean combinational;
        /** Objects the clock is defined on */
        public final List<XdcObject> targets;
        public final int index;

        GeneratedClockDef(String name, XdcObject source, String masterClock, XdcObject masterClockQuery,
                          int divideBy, int multiplyBy, int[] edges, double[] edgeShift, double dutyCycle,
                          boolean invert, boolean add, boolean combinational, List<XdcObject> targets, int index) {
            this.name = name;
            this.source = source;
            this.masterClock = masterClock;
            this.masterClockQuery = masterClockQuery;
            this.divideBy = divideBy;
            this.multiplyBy = multiplyBy;
            this.edges = edges;
            this.edgeShift = edgeShift;
            this.dutyCycle = dutyCycle;
            this.invert = invert;
            this.add = add;
            this.combinational = combinational;
            this.targets = targets;
            this.index = index;
        }

        /** Whether this only renames an auto-derived clock (no -source and no waveform options). */
        public boolean isRename() {
            return source == null && divideBy == 0 && multiplyBy == 0 && edges == null && !invert && !combinational;
        }
    }

    /** Kind of clock groups */
    public enum GroupKind {
        ASYNCHRONOUS, LOGICALLY_EXCLUSIVE, PHYSICALLY_EXCLUSIVE
    }

    /** {@code set_clock_groups}: the clocks of different groups are not timed against each other */
    public static final class ClockGroupsDef {
        public final GroupKind kind;
        /** The groups, as clock queries; with a single group, all other clocks form the second one */
        public final List<List<XdcObject>> groups;
        public final int index;

        ClockGroupsDef(GroupKind kind, List<List<XdcObject>> groups, int index) {
            this.kind = kind;
            this.groups = groups;
            this.index = index;
        }
    }

    /** Kind of path exception */
    public enum ExceptionKind {
        FALSE_PATH, MULTICYCLE_PATH
    }

    /**
     * {@code set_false_path} or {@code set_multicycle_path}, or the false path for hold of a
     * {@code set_max_delay -datapath_only}
     */
    public static final class PathExceptionDef {
        public final ExceptionKind kind;
        /** Whether it applies to setup checks */
        public final boolean setup;
        /** Whether it applies to hold checks */
        public final boolean hold;
        /** The path multiplier of a multicycle path */
        public final int multiplier;
        /** -start (true), -end (false), or the default (null): -end for setup, -start for hold */
        public final Boolean start;
        /** -from objects (clocks, cells, pins, ports), null for any startpoint */
        public final List<XdcObject> from;
        /** -to objects (clocks, cells, pins, ports), null for any endpoint */
        public final List<XdcObject> to;
        /**
         * -through objects (cells, pins, nets, ports), one list per -through option in their order (a path must
         * pass through an object of each list, in that order), null if none
         */
        public final List<List<XdcObject>> through;
        public final int index;

        PathExceptionDef(ExceptionKind kind, boolean setup, boolean hold, int multiplier, Boolean start,
                         List<XdcObject> from, List<XdcObject> to, List<List<XdcObject>> through, int index) {
            this.kind = kind;
            this.setup = setup;
            this.hold = hold;
            this.multiplier = multiplier;
            this.start = start;
            this.from = from;
            this.to = to;
            this.through = through;
            this.index = index;
        }
    }

    /** {@code set_clock_uncertainty} */
    public static final class UncertaintyDef {
        /** The uncertainty, in ns */
        public final double value;
        public final boolean setup;
        public final boolean hold;
        /** -from clocks (inter-clock uncertainty), else null */
        public final List<XdcObject> from;
        /** -to clocks (inter-clock uncertainty), else null */
        public final List<XdcObject> to;
        /** The clocks of a simple uncertainty (applied where they capture), else null */
        public final List<XdcObject> clocks;
        /** -add: adds to the uncertainty set before (of the same kind, simple or inter-clock), else replaces it */
        public final boolean add;
        /**
         * The clock edge of an inter-clock uncertainty between the same edges of the two clocks (-rise_from/-rise_to:
         * {@link #RISE}, -fall_from/-fall_to: {@link #FALL}), else {@link #ANY_EDGE}. A clock pair gets the larger of
         * its rise and fall values.
         */
        public final int edge;
        public final int index;

        public static final int ANY_EDGE = 0;
        public static final int RISE = 1;
        public static final int FALL = 2;

        UncertaintyDef(double value, boolean setup, boolean hold, List<XdcObject> from, List<XdcObject> to,
                       List<XdcObject> clocks, boolean add, int edge, int index) {
            this.add = add;
            this.edge = edge;
            this.value = value;
            this.setup = setup;
            this.hold = hold;
            this.from = from;
            this.to = to;
            this.clocks = clocks;
            this.index = index;
        }
    }

    /** {@code set_clock_latency} on clocks */
    public static final class ClockLatencyDef {
        /** The latency, in ns */
        public final double value;
        /** A source latency, else a network latency */
        public final boolean source;
        /** Whether it sets the min (early) corners' and the max (late) corners' value */
        public final boolean min;
        public final boolean max;
        public final List<XdcObject> clocks;
        public final int index;

        ClockLatencyDef(double value, boolean source, boolean min, boolean max, List<XdcObject> clocks, int index) {
            this.value = value;
            this.source = source;
            this.min = min;
            this.max = max;
            this.clocks = clocks;
            this.index = index;
        }
    }

    /** {@code set_input_jitter} */
    public static final class InputJitterDef {
        public final List<XdcObject> clocks;
        /** The jitter, in ns */
        public final double value;
        public final int index;

        InputJitterDef(List<XdcObject> clocks, double value, int index) {
            this.clocks = clocks;
            this.value = value;
            this.index = index;
        }
    }

    private final List<ClockDef> clocks = new ArrayList<>();
    private final List<GeneratedClockDef> generatedClocks = new ArrayList<>();
    private final List<ClockGroupsDef> clockGroups = new ArrayList<>();
    private final List<PathExceptionDef> exceptions = new ArrayList<>();
    private final List<UncertaintyDef> uncertainties = new ArrayList<>();
    private final List<InputJitterDef> inputJitters = new ArrayList<>();
    private final List<ClockLatencyDef> clockLatencies = new ArrayList<>();
    private double systemJitter = Double.NaN;
    /** Vivado's names of auto-derived clocks, by "cell|outPin|masterClock" */
    private final Map<String, String> derivedClockNames = new HashMap<>();
    /** Constant values (0 or 1) of pins set by set_case_analysis, by pin name */
    private final Map<String, Integer> caseValues = new HashMap<>();
    private final Map<String, Integer> warnings = new LinkedHashMap<>();
    private int numCommands;

    /** The netlist that object names given as text are resolved in (null: by name only) */
    private EDIFNetlist netlist;
    /** The interpreter reading the constraints, while they are parsed */
    private XdcInterpreter interp;

    private TimingConstraints() {
    }

    /**
     * Parses the constraints of a design: its EARLY, NORMAL and LATE XDC constraint groups.
     * @param design The design.
     * @return The constraints.
     */
    public static TimingConstraints fromDesign(Design design) {
        List<String> lines = new ArrayList<>();
        for (ConstraintGroup g : new ConstraintGroup[]{ConstraintGroup.EARLY, ConstraintGroup.NORMAL,
                ConstraintGroup.LATE}) {
            List<String> c = design.getXDCConstraints(g);
            if (c != null) {
                lines.addAll(c);
            }
        }
        return parse(design.getNetlist(), lines);
    }

    /**
     * Parses constraints.
     * @param netlist The netlist the object queries search, or null to make objects by name only.
     * @param lines The XDC lines.
     * @return The constraints.
     */
    public static TimingConstraints parse(EDIFNetlist netlist, List<String> lines) {
        TimingConstraints tc = new TimingConstraints();
        tc.netlist = netlist;
        XdcObjectFinder finder = new XdcObjectFinder(netlist);
        XdcInterpreter interp = new XdcInterpreter(finder, tc::handle, tc.warnings);
        tc.interp = interp;
        // The object queries of the commands that are ignored are not run: on large designs, those of pblock,
        // waiver, bus skew and max delay commands and properties can take most of the time of the read
        interp.setEvaluatedCommands(TimingConstraints::isHandled);
        interp.evalScript(String.join("\n", lines));
        tc.numCommands = interp.getCommandIndex();
        // The interpreter holds the netlist index and the query results: only needed while parsing
        tc.interp = null;
        return tc;
    }

    /** Parses constraints given as one script (see {@link #parse(EDIFNetlist, List)}). */
    public static TimingConstraints parse(EDIFNetlist netlist, String script) {
        return parse(netlist, Collections.singletonList(script));
    }

    public List<ClockDef> getClocks() {
        return clocks;
    }

    public List<GeneratedClockDef> getGeneratedClocks() {
        return generatedClocks;
    }

    public List<ClockGroupsDef> getClockGroups() {
        return clockGroups;
    }

    public List<PathExceptionDef> getExceptions() {
        return exceptions;
    }

    public List<UncertaintyDef> getUncertainties() {
        return uncertainties;
    }

    public List<InputJitterDef> getInputJitters() {
        return inputJitters;
    }

    /** @return The set_clock_latency constraints on clocks, in order. */
    public List<ClockLatencyDef> getClockLatencies() {
        return clockLatencies;
    }

    /** @return The system jitter set by set_system_jitter in ns, NaN if not set. */
    public double getSystemJitter() {
        return systemJitter;
    }

    /**
     * Gets Vivado's name of an auto-derived clock, from the design's TOOL_DERIVED_CLK_NAMES.
     * @param cellName Full hierarchical name of the generating cell (e.g. the MMCM).
     * @param outPin Output pin name of the cell, e.g. CLKOUT0.
     * @param masterClock Name of the master clock.
     * @return The name, or null if unknown.
     */
    public String getDerivedClockName(String cellName, String outPin, String masterClock) {
        return derivedClockNames.get(cellName + "|" + outPin + "|" + masterClock);
    }

    /**
     * Gets the constant value of a pin set by {@code set_case_analysis}. It is only used to derive
     * clocks (e.g. the PLL and divider selections of GT channels and BUFG_GT), not to propagate
     * constants through the data paths.
     * @param pinName Full name of the pin (cell name '/' port instance name).
     * @return 0 or 1, or -1 if not set.
     */
    public int getCaseValue(String pinName) {
        Integer v = caseValues.get(pinName);
        return v == null ? -1 : v;
    }

    /**
     * @return The warnings, by kind (e.g. an ignored command), with the number of occurrences.
     */
    public Map<String, Integer> getWarnings() {
        return warnings;
    }

    /** @return The number of commands evaluated. */
    public int getNumCommands() {
        return numCommands;
    }

    private void warn(String kind) {
        warnings.merge(kind, 1, Integer::sum);
    }

    // ------------------------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------------------------

    /**
     * @param command A command name, or "set_property &lt;property name&gt;".
     * @return Whether {@link #handle} reads the command's arguments (of set_property, only those that set
     * TOOL_DERIVED_CLK_NAMES).
     */
    static boolean isHandled(String command) {
        switch (command) {
            case "create_clock":
            case "create_generated_clock":
            case "set_clock_groups":
            case "set_false_path":
            case "set_multicycle_path":
            case "set_max_delay":
            case "set_clock_uncertainty":
            case "set_system_jitter":
            case "set_input_jitter":
            case "set_clock_latency":
            case "set_case_analysis":
            case "set_property":
            case "set_property TOOL_DERIVED_CLK_NAMES":
                return true;
            default:
                return false;
        }
    }

    private void handle(String name, List<Object> args, int index) {
        switch (name) {
            case "create_clock":
                createClock(args, index);
                break;
            case "create_generated_clock":
                createGeneratedClock(args, index);
                break;
            case "set_clock_groups":
                setClockGroups(args, index);
                break;
            case "set_false_path":
                setPathException(ExceptionKind.FALSE_PATH, name, args, index);
                break;
            case "set_multicycle_path":
                setPathException(ExceptionKind.MULTICYCLE_PATH, name, args, index);
                break;
            case "set_max_delay":
                setMaxDelay(args, index);
                break;
            case "set_clock_uncertainty":
                setClockUncertainty(args, index);
                break;
            case "set_system_jitter":
                setSystemJitter(args);
                break;
            case "set_input_jitter":
                setInputJitter(args, index);
                break;
            case "set_clock_latency":
                setClockLatency(args, index);
                break;
            case "set_property":
                setProperty(args);
                break;
            case "set_case_analysis":
                setCaseAnalysis(args);
                break;
            default:
                if (UNSUPPORTED_TIMING_COMMANDS.contains(name)) {
                    warn(name + " (not supported, ignored)");
                }
                // Other commands (e.g. physical constraints such as create_pblock) are no timing constraints
                break;
        }
    }

    /** The timing constraint commands (SDC and XDC) that {@link #handle} does not read, which it warns about */
    private static final Set<String> UNSUPPORTED_TIMING_COMMANDS = new HashSet<>(Arrays.asList(
            "set_input_delay", "set_output_delay", "set_min_delay", "set_max_time_borrow",
            "set_bus_skew", "set_disable_timing", "set_data_check", "set_clock_sense", "set_propagated_clock",
            "set_external_delay", "group_path", "set_clock_gating_check", "set_input_transition", "set_load",
            "set_logic_dc", "set_logic_one", "set_logic_zero", "set_timing_derate", "config_timing_analysis",
            "config_timing_pessimism", "set_operating_conditions", "set_delay_model"));

    /**
     * Whether a waveform is valid for a period: an even number of increasing edge times (rise, fall, ...),
     * the first not negative, all within one period of the first.
     */
    static boolean isValidWaveform(double[] w, double period) {
        if (w.length < 2 || w.length % 2 != 0 || !(w[0] >= 0)) {
            return false;
        }
        for (int i = 1; i < w.length; i++) {
            if (!(w[i] > w[i - 1])) {
                return false;
            }
        }
        return w[w.length - 1] - w[0] < period;
    }

    private static double toDouble(Object v) {
        return Double.parseDouble(XdcInterpreter.toText(v).trim());
    }

    /**
     * Warns of the unknown (or ambiguous) options of a command, which Vivado rejects: the command is not applied, as
     * its other arguments may not mean what they would with the intended option.
     * @return Whether there are any.
     */
    private boolean hasUnknownOptions(String cmd, XdcInterpreter.Options o) {
        for (String u : o.unknown) {
            warn(cmd + " with an unknown or ambiguous option " + u + " (ignored)");
        }
        return !o.unknown.isEmpty();
    }

    /**
     * Objects of the positional arguments: object lists, or names of objects/clocks given as text (also as elements
     * of a list of objects and names).
     */
    private List<XdcObject> positionalObjects(List<Object> positional, XdcObject.Kind textKind, int index) {
        List<XdcObject> out = new ArrayList<>();
        for (Object p : positional) {
            if (p instanceof List) {
                for (Object e : (List<?>) p) {
                    if (e instanceof XdcObject) {
                        out.add((XdcObject) e);
                    } else {
                        out.add(textKind == XdcObject.Kind.CLOCK ? XdcObject.ofClockPattern(String.valueOf(e), false,
                                index) : textObject(String.valueOf(e)));
                    }
                }
            } else {
                for (String s : XdcInterpreter.splitList(XdcInterpreter.toText(p))) {
                    out.add(textKind == XdcObject.Kind.CLOCK ? XdcObject.ofClockPattern(s, false, index)
                            : textObject(s));
                }
            }
        }
        return out;
    }

    /**
     * An object of a clock command named as text (e.g. {@code create_generated_clock -name n u0/mmcm/CLKOUT1}),
     * resolved as Vivado does: a top-level port of that name, else a pin, else a net (pins and nets relative to the
     * current instance). Without a netlist, or if the netlist has none of them, it is a port of that name.
     */
    private XdcObject textObject(String s) {
        if (netlist == null || netlist.getTopCell().getPortByPortInstName(s) != null) {
            return XdcObject.ofPort(s);
        }
        String ci = interp == null ? "" : interp.getCurrentInstance();
        String name = ci.isEmpty() ? s : ci + "/" + s;
        try {
            if (name.indexOf('/') >= 0) {
                EDIFHierPortInst pin = netlist.getHierPortInstFromName(name);
                if (pin != null) {
                    return XdcObject.ofPin(name, pin);
                }
            }
            EDIFHierNet net = netlist.getHierNetFromName(name);
            if (net != null) {
                return XdcObject.ofNet(net);
            }
        } catch (RuntimeException e) {
            // Not the name of a pin or net
        }
        return XdcObject.ofPort(s);
    }

    /** Objects of an option value: an object list, or clock names given as text (also in a list of objects). */
    private static List<XdcObject> optionObjects(Object v, int index) {
        List<XdcObject> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object e : (List<?>) v) {
                out.add(e instanceof XdcObject ? (XdcObject) e : XdcObject.ofClockPattern(String.valueOf(e), false,
                        index));
            }
            return out;
        }
        for (String s : XdcInterpreter.splitList(XdcInterpreter.toText(v))) {
            out.add(XdcObject.ofClockPattern(s, false, index));
        }
        return out;
    }

    private void createClock(List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-add", "-quiet", "-verbose"}, new String[]{"-period", "-name", "-waveform"});
        if (hasUnknownOptions("create_clock", o)) {
            return;
        }
        if (!o.has("-period")) {
            warn("create_clock without -period (ignored)");
            return;
        }
        double period = toDouble(o.get("-period"));
        if (!(period > 0) || Double.isInfinite(period)) {
            warn("create_clock with a period that is not positive (ignored)");
            return;
        }
        double[] waveform;
        if (o.has("-waveform")) {
            List<String> w = XdcInterpreter.splitList(XdcInterpreter.toText(o.get("-waveform")));
            waveform = new double[w.size()];
            for (int i = 0; i < w.size(); i++) {
                waveform[i] = Double.parseDouble(w.get(i));
            }
            if (!isValidWaveform(waveform, period)) {
                warn("create_clock with an invalid waveform (ignored)");
                return;
            }
        } else {
            waveform = new double[]{0, period / 2};
        }
        List<XdcObject> sources = positionalObjects(o.positional, XdcObject.Kind.PORT, index);
        if (sources.isEmpty() && !o.positional.isEmpty()) {
            // Objects were given but none was found: not a virtual clock
            warn("create_clock on objects matching nothing (ignored)");
            return;
        }
        String name = o.has("-name") ? XdcInterpreter.toText(o.get("-name")) : null;
        if (name == null) {
            if (sources.isEmpty()) {
                warn("create_clock without -name nor source (ignored)");
                return;
            }
            name = sources.get(0).getName();
        }
        clocks.add(new ClockDef(name, period, waveform, sources, o.has("-add"), index));
    }

    private void createGeneratedClock(List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-add", "-invert", "-combinational", "-quiet", "-verbose"},
                new String[]{"-name", "-source", "-master_clock", "-divide_by", "-multiply_by", "-edges",
                    "-edge_shift", "-duty_cycle"});
        if (hasUnknownOptions("create_generated_clock", o)) {
            return;
        }
        List<XdcObject> targets = positionalObjects(o.positional, XdcObject.Kind.PORT, index);
        if (targets.isEmpty()) {
            warn("create_generated_clock without objects (ignored)");
            return;
        }
        XdcObject source = null;
        if (o.has("-source")) {
            List<XdcObject> s = XdcInterpreter.objects(o.get("-source"));
            if (s.isEmpty()) {
                s = positionalObjects(Collections.singletonList(o.get("-source")), XdcObject.Kind.PORT, index);
            }
            if (s.isEmpty()) {
                warn("create_generated_clock -source matching no object (ignored)");
                return;
            }
            source = s.get(0);
        }
        String master = null;
        XdcObject masterQuery = null;
        if (o.has("-master_clock")) {
            Object m = o.get("-master_clock");
            List<XdcObject> mo = XdcInterpreter.objects(m);
            master = mo.isEmpty() ? XdcInterpreter.toText(m) : mo.get(0).getName();
            masterQuery = mo.isEmpty() || mo.get(0).getKind() != XdcObject.Kind.CLOCK ? null : mo.get(0);
        }
        int[] edges = null;
        if (o.has("-edges")) {
            List<String> e = XdcInterpreter.splitList(XdcInterpreter.toText(o.get("-edges")));
            edges = new int[e.size()];
            try {
                for (int i = 0; i < edges.length; i++) {
                    edges[i] = Integer.parseInt(e.get(i));
                }
            } catch (NumberFormatException ex) {
                edges = null;
            }
            if (!isValidEdges(edges)) {
                warn("create_generated_clock -edges not an odd number (>= 3) of increasing master edges (ignored)");
                return;
            }
        }
        double[] shifts = null;
        if (o.has("-edge_shift")) {
            List<String> e = XdcInterpreter.splitList(XdcInterpreter.toText(o.get("-edge_shift")));
            shifts = new double[e.size()];
            try {
                for (int i = 0; i < shifts.length; i++) {
                    shifts[i] = Double.parseDouble(e.get(i));
                }
            } catch (NumberFormatException ex) {
                shifts = null;
            }
            if (shifts == null || edges == null || shifts.length != edges.length) {
                warn("create_generated_clock -edge_shift without one shift per -edges edge (ignored)");
                return;
            }
        }
        int divideBy = o.has("-divide_by") ? (int) Math.round(toDouble(o.get("-divide_by"))) : 0;
        int multiplyBy = o.has("-multiply_by") ? (int) Math.round(toDouble(o.get("-multiply_by"))) : 0;
        double duty = o.has("-duty_cycle") ? toDouble(o.get("-duty_cycle")) : Double.NaN;
        String name = o.has("-name") ? XdcInterpreter.toText(o.get("-name")) : null;
        generatedClocks.add(new GeneratedClockDef(name, source, master, masterQuery, divideBy, multiplyBy, edges,
                shifts, duty, o.has("-invert"), o.has("-add"), o.has("-combinational"), targets, index));
    }

    /**
     * Whether the -edges of a generated clock are valid: an odd number (at least 3) of increasing master
     * edges (1 is the first rising edge), alternating rise and fall, the last one ending the period.
     */
    static boolean isValidEdges(int[] edges) {
        if (edges == null || edges.length < 3 || edges.length % 2 == 0 || edges[0] < 1) {
            return false;
        }
        for (int i = 1; i < edges.length; i++) {
            if (edges[i] <= edges[i - 1]) {
                return false;
            }
        }
        return true;
    }

    private void setClockGroups(List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-asynchronous", "-logically_exclusive", "-physically_exclusive", "-quiet",
                    "-verbose", "-allow_paths"}, new String[]{"-name", "-group"});
        if (hasUnknownOptions("set_clock_groups", o)) {
            return;
        }
        GroupKind kind = o.has("-logically_exclusive") ? GroupKind.LOGICALLY_EXCLUSIVE
                : o.has("-physically_exclusive") ? GroupKind.PHYSICALLY_EXCLUSIVE : GroupKind.ASYNCHRONOUS;
        if (o.has("-allow_paths")) {
            warn("set_clock_groups -allow_paths (ignored)");
            return;
        }
        List<List<XdcObject>> groups = new ArrayList<>();
        for (Map.Entry<String, Object> e : o.sequence) {
            if (e.getKey().equals("-group")) {
                groups.add(optionObjects(e.getValue(), index));
            }
        }
        if (groups.isEmpty()) {
            warn("set_clock_groups without -group (ignored)");
            return;
        }
        clockGroups.add(new ClockGroupsDef(kind, groups, index));
    }

    private static final String[] UNSUPPORTED_PATH_OPTIONS = {"-rise_from", "-fall_from", "-rise_to",
        "-fall_to", "-rise_through", "-fall_through", "-rise", "-fall", "-reset_path"};

    /**
     * The valued options of the path exceptions: -from, -to, -through and the unsupported ones (-rise, -fall and
     * -reset_path are flags, but treating them as valued options is harmless as the whole exception is ignored when
     * they are given)
     */
    private static final String[] PATH_VALUED_OPTIONS = {"-from", "-to", "-through", "-rise_from", "-fall_from",
        "-rise_to", "-fall_to", "-rise_through", "-fall_through", "-rise", "-fall", "-reset_path"};

    private void setPathException(ExceptionKind kind, String cmd, List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-setup", "-hold", "-start", "-end", "-quiet", "-verbose"}, PATH_VALUED_OPTIONS);
        if (hasUnknownOptions(cmd, o)) {
            return;
        }
        for (String u : UNSUPPORTED_PATH_OPTIONS) {
            if (o.has(u)) {
                warn(cmd + " " + u + " (constraint ignored)");
                return;
            }
        }
        int multiplier = 1;
        if (kind == ExceptionKind.MULTICYCLE_PATH) {
            if (o.positional.isEmpty()) {
                warn(cmd + " without a path multiplier (ignored)");
                return;
            }
            multiplier = (int) Math.round(toDouble(o.positional.get(0)));
        }
        boolean setup = o.has("-setup");
        boolean hold = o.has("-hold");
        if (!setup && !hold) {
            // A false path applies to both; a multicycle path to setup (hold following it)
            setup = true;
            hold = kind == ExceptionKind.FALSE_PATH;
        }
        Boolean start = o.has("-start") ? Boolean.TRUE : (o.has("-end") ? Boolean.FALSE : null);
        List<XdcObject> from = o.has("-from") ? optionObjects(o.get("-from"), index) : null;
        List<XdcObject> to = o.has("-to") ? optionObjects(o.get("-to"), index) : null;
        if ((from != null && from.isEmpty()) || (to != null && to.isEmpty())) {
            warn(cmd + " with -from or -to matching no object (ignored)");
            return;
        }
        List<List<XdcObject>> through = null;
        for (Map.Entry<String, Object> e : o.sequence) {
            if (!e.getKey().equals("-through")) continue;
            List<XdcObject> objs = optionObjects(e.getValue(), index);
            if (objs.isEmpty()) {
                // As Vivado: no path passes through nothing
                warn(cmd + " with -through matching no object (ignored)");
                return;
            }
            if (through == null) through = new ArrayList<>(1);
            through.add(objs);
        }
        exceptions.add(new PathExceptionDef(kind, setup, hold, multiplier, start, from, to, through, index));
    }

    /**
     * set_max_delay: with -datapath_only, Vivado does not check hold on the paths it covers (UG903: they are false
     * paths for hold), which is read as a hold-only false path with the same -from, -to and -through. The maximum delay
     * itself is not modeled (warned). A set_max_delay without -datapath_only leaves hold unchanged and is ignored
     * (warned), as is one with edge-specific options (-rise, -fall, -rise_from, ...) or -reset_path.
     */
    private void setMaxDelay(List<Object> args, int index) {
        String cmd = "set_max_delay";
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-datapath_only", "-quiet", "-verbose"}, PATH_VALUED_OPTIONS);
        if (hasUnknownOptions(cmd, o)) {
            return;
        }
        if (!o.has("-datapath_only")) {
            warn(cmd + " (not supported, ignored)");
            return;
        }
        for (String u : UNSUPPORTED_PATH_OPTIONS) {
            if (o.has(u)) {
                warn(cmd + " " + u + " (constraint ignored)");
                return;
            }
        }
        if (!o.has("-from")) {
            // Vivado requires -from with -datapath_only (UG835) and rejects the command without it
            warn(cmd + " -datapath_only without -from (ignored)");
            return;
        }
        List<XdcObject> from = optionObjects(o.get("-from"), index);
        List<XdcObject> to = o.has("-to") ? optionObjects(o.get("-to"), index) : null;
        if (from.isEmpty() || (to != null && to.isEmpty())) {
            warn(cmd + " with -from or -to matching no object (ignored)");
            return;
        }
        List<List<XdcObject>> through = null;
        for (Map.Entry<String, Object> e : o.sequence) {
            if (!e.getKey().equals("-through")) continue;
            List<XdcObject> objs = optionObjects(e.getValue(), index);
            if (objs.isEmpty()) {
                warn(cmd + " with -through matching no object (ignored)");
                return;
            }
            if (through == null) through = new ArrayList<>(1);
            through.add(objs);
        }
        warn(cmd + " -datapath_only (maximum delay not modeled; read as a false path for hold)");
        exceptions.add(new PathExceptionDef(ExceptionKind.FALSE_PATH, false, true, 1, null, from, to, through, index));
    }

    private void setClockUncertainty(List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-setup", "-hold", "-quiet", "-verbose", "-add"},
                new String[]{"-from", "-to", "-rise_from", "-fall_from", "-rise_to", "-fall_to"});
        if (hasUnknownOptions("set_clock_uncertainty", o)) {
            return;
        }
        // Edge-specific inter-clock uncertainty (-rise_from/-fall_from, -rise_to/-fall_to): the analysis keeps one
        // value per clock pair, so the same-edge entries (rise to rise, fall to fall: the paths between registers
        // clocked on the same edge, nearly all of them) set it, the larger of the two if both are given, and the
        // opposite-edge entries (half-cycle paths) are not modeled (those paths get the same-edge value)
        Object fromV = o.has("-from") ? o.get("-from") : null;
        Object toV = o.has("-to") ? o.get("-to") : null;
        String fromEdge = null;
        String toEdge = null;
        for (String e : new String[]{"rise", "fall"}) {
            if (o.has("-" + e + "_from")) {
                fromV = o.get("-" + e + "_from");
                fromEdge = e;
            }
            if (o.has("-" + e + "_to")) {
                toV = o.get("-" + e + "_to");
                toEdge = e;
            }
        }
        if (fromEdge != null && toEdge != null && !fromEdge.equals(toEdge)) {
            warn("set_clock_uncertainty between opposite clock edges (not modeled: same-edge value applies)");
            return;
        }
        if (o.positional.isEmpty()) {
            warn("set_clock_uncertainty without a value (ignored)");
            return;
        }
        double value = toDouble(o.positional.get(0));
        boolean setup = o.has("-setup");
        boolean hold = o.has("-hold");
        if (!setup && !hold) {
            setup = true;
            hold = true;
        }
        List<XdcObject> from = fromV != null ? optionObjects(fromV, index) : null;
        List<XdcObject> to = toV != null ? optionObjects(toV, index) : null;
        List<XdcObject> clks = null;
        if (from == null && to == null) {
            clks = positionalObjects(o.positional.subList(1, o.positional.size()), XdcObject.Kind.CLOCK, index);
            for (XdcObject c : clks) {
                if (c.getKind() != XdcObject.Kind.CLOCK) {
                    warn("set_clock_uncertainty on non-clock objects (ignored)");
                    return;
                }
            }
            if (clks.isEmpty()) {
                warn("set_clock_uncertainty without clocks (ignored)");
                return;
            }
        }
        String edge = fromEdge != null ? fromEdge : toEdge;
        int edgeKind = edge == null ? UncertaintyDef.ANY_EDGE : edge.equals("rise") ? UncertaintyDef.RISE
                : UncertaintyDef.FALL;
        uncertainties.add(new UncertaintyDef(value, setup, hold, from, to, clks, o.has("-add"), edgeKind, index));
    }

    private void setSystemJitter(List<Object> args) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args, new String[]{"-quiet", "-verbose"},
                new String[0]);
        if (hasUnknownOptions("set_system_jitter", o)) {
            return;
        }
        if (o.positional.isEmpty()) {
            warn("set_system_jitter without a value (ignored)");
            return;
        }
        systemJitter = toDouble(o.positional.get(0));
    }

    private void setInputJitter(List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args, new String[]{"-quiet", "-verbose"},
                new String[0]);
        if (hasUnknownOptions("set_input_jitter", o)) {
            return;
        }
        if (o.positional.size() < 2) {
            warn("set_input_jitter without a clock and a value (ignored)");
            return;
        }
        List<XdcObject> clks = positionalObjects(o.positional.subList(0, 1), XdcObject.Kind.CLOCK, index);
        inputJitters.add(new InputJitterDef(clks, toDouble(o.positional.get(1)), index));
    }

    private void setClockLatency(List<Object> args, int index) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args,
                new String[]{"-source", "-min", "-max", "-early", "-late", "-rise", "-fall", "-quiet", "-verbose"},
                new String[]{"-clock"});
        if (hasUnknownOptions("set_clock_latency", o)) {
            return;
        }
        if (o.has("-clock")) {
            warn("set_clock_latency -clock (latency on pins or ports, ignored)");
            return;
        }
        if (o.has("-rise") || o.has("-fall")) {
            warn("set_clock_latency -rise/-fall (applied to both edges)");
        }
        if (o.positional.size() < 2) {
            warn("set_clock_latency without a value and clocks (ignored)");
            return;
        }
        double value = toDouble(o.positional.get(0));
        List<XdcObject> clks = positionalObjects(o.positional.subList(1, o.positional.size()), XdcObject.Kind.CLOCK,
                index);
        for (XdcObject c : clks) {
            if (c.getKind() != XdcObject.Kind.CLOCK) {
                warn("set_clock_latency on pins or ports (ignored)");
                return;
            }
        }
        boolean min = o.has("-min") || o.has("-early");
        boolean max = o.has("-max") || o.has("-late");
        if (!min && !max) {
            min = true;
            max = true;
        }
        clockLatencies.add(new ClockLatencyDef(value, o.has("-source"), min, max, clks, index));
    }

    private void setCaseAnalysis(List<Object> args) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args, new String[]{"-quiet", "-verbose"},
                new String[0]);
        if (hasUnknownOptions("set_case_analysis", o)) {
            return;
        }
        if (o.positional.size() < 2) {
            warn("set_case_analysis without a value and objects (ignored)");
            return;
        }
        String v = XdcInterpreter.toText(o.positional.get(0)).trim().toLowerCase();
        int value = v.equals("0") || v.equals("zero") || v.equals("1'b0") ? 0
                : v.equals("1") || v.equals("one") || v.equals("1'b1") ? 1 : -1;
        if (value < 0) {
            warn("set_case_analysis " + v + " (only 0 or 1, ignored)");
            return;
        }
        int n = 0;
        for (XdcObject obj : XdcInterpreter.objects(o.positional.get(1))) {
            if (obj.getKind() == XdcObject.Kind.PIN) {
                caseValues.put(obj.getName(), value);
                n++;
            }
        }
        warn(n > 0 ? "set_case_analysis (used to derive clocks only)" : "set_case_analysis on no pin (ignored)");
    }

    private void setProperty(List<Object> args) {
        XdcInterpreter.Options o = XdcInterpreter.parseOptions(args, new String[]{"-quiet", "-verbose"},
                new String[]{"-dict"});
        if (hasUnknownOptions("set_property", o)) {
            return;
        }
        if (o.positional.size() < 2 || !"TOOL_DERIVED_CLK_NAMES".equals(XdcInterpreter.toText(o.positional.get(0)))) {
            // Not a timing constraint
            return;
        }
        String value = XdcInterpreter.toText(o.positional.get(1));
        List<XdcObject> objs = o.positional.size() > 2 ? XdcInterpreter.objects(o.positional.get(2))
                : Collections.emptyList();
        if (objs.size() != 1 || objs.get(0).getKind() != XdcObject.Kind.CELL) {
            // On the current design: derived clocks of other primitives (e.g. ISERDES), not supported
            return;
        }
        String cell = objs.get(0).getName();
        // Entries "1:0:<inPin>:<outPin>:<master>:<name>" separated by "::"
        for (String entry : value.split("::")) {
            String[] f = entry.split(":");
            if (f.length >= 6 && f[1].equals("0")) {
                derivedClockNames.put(cell + "|" + f[3] + "|" + f[4], f[5]);
            }
        }
    }
}
