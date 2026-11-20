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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

/**
 * The clocks of a design and the clock pins they reach, traced through its netlist from the clock
 * sources of its {@link TimingConstraints}:
 * <ul>
 * <li>Primary clocks start at their ports, pins or nets; clocks propagate through clock buffers
 * (IBUF*, BUFG*, BUFGCE*, BUFGCTRL, BUFG_GT, BUFG_PS, BUFCE_ROW/LEAF, ...), inverters, LUTs and
 * MUXF* (up to {@link #MAX_COMBINATIONAL_DEPTH} levels), and stop at the clock pins of other cells
 * (sequential cells, hard blocks), which they are recorded on.</li>
 * <li>Clock generators (MMCME3/4/5, PLLE3/4, XPLL, X5PLL, DPLL) derive one clock per output, master clock
 * and connected output from CLKFBOUT_MULT(_F), DIVCLK_DIVIDE, CLKOUTn_DIVIDE(_F) (CLKOUT4 also by
 * CLKOUT6_DIVIDE with CLKOUT4_CASCADE), CLKOUTn_DUTY_CYCLE and CLKOUTn_PHASE (a source latency in
 * PHASESHIFT_MODE LATENCY, else a waveform shift, see {@link #isLatencyPhaseShift}, in units of the output
 * period before the XPLL's extra divide by 4 (DIV4_CLKOUT012 / DIV4_CLKOUT3); XPLL CLKOUTPHY_* outputs
 * divide the VCO by CLKOUTPHY_DIVIDE), from the falling edges of an inverted input (IS_CLKINn_INVERTED),
 * and from the selected input only if CLKINSEL is constant; BUFGCE_DIV
 * derives a clock divided by BUFGCE_DIVIDE (if more than 1), MBUFGCE* one clock per O1..O4 output,
 * divided by 1, 2, 4 and 8 (times the BUFGCE_DIVIDE of an MBUFGCE_DIV, the constant DIV + 1 of an
 * MBUFG_GT), BUFG_GT one divided by its constant DIV + 1, IBUFDS_GTE* one on ODIV2
 * divided by 2 if REFCLK_HROW_CK_SEL is 1. The TXOUTCLK / RXOUTCLK (and TXOUTCLKPCS / RXOUTCLKPCS) of
 * GTH/GTY channels derive from their reference clock through their PLL and dividers (see
 * {@link GtClocks}), the tie-offs and set_case_analysis values selecting them. A Versal XPHY nibble derives its
 * read clock on FIFO_WR_CLK from the strobe at its DATAIN[0] (the strobe divided by 4, named
 * {@code <cell>/RX_DQS_DIV4_0} as Vivado names it), in the configurations of {@code deriveXphyReadClock}.
 * Derived clocks take Vivado's names when the design stores them
 * (TOOL_DERIVED_CLK_NAMES), else the name of the net they drive (suffixed _1, _2, ... if taken).
 * A {@code create_generated_clock} without -source on a generator output renames its clock.</li>
 * <li>User generated clocks start at their pins, from the clock at their -source (or their
 * -master_clock). A pin with a user clock blocks the clocks propagating to it (unless -add).</li>
 * </ul>
 * A clock pin can have several clocks; each one comes with its sense, as a clock edge id
 * {@code 2*clock + (inverted ? 1 : 0)}, so that falling-edge clock pins (an IS_*_INVERTED
 * property of the pin, or an odd number of inversions on the clock path: inverters and the input
 * inversions of clock buffers, IS_I_INVERTED, IS_I0_INVERTED, ...) launch and capture on the falling
 * edges of their clocks. Latch gates are not clock pins (latches are not timed, with a warning).
 * <p>
 * The network also keeps the stages of the clock paths (a net driven by a clock source or buffer
 * output, see {@code Stage}), which {@link ClockModel} times.
 */
public final class ClockNetwork {

    /**
     * Maximum number of combinational cells (LUTs, MUXF*) a clock propagates through: a limit of this tracing
     * (Vivado has none), whose clocks stop there
     */
    public static final int MAX_COMBINATIONAL_DEPTH = 8;

    /** Kind of a stage's driver */
    enum StageKind {
        /** A top-level port */
        PORT,
        /** A pin with a user clock (create_clock on a pin, or a user generated clock) */
        USER_PIN,
        /** The output of a clock buffer, inverter or combinational cell */
        BUFFER,
        /** The output of a clock divider (BUFGCE_DIV, MBUFGCE) */
        DIVIDER,
        /** An output of a clock generator (MMCM, PLL, ...) */
        GENERATOR
    }

    /**
     * A stage of the clock paths: a net driven by a clock source or a clock buffer's output, the
     * pin the clock enters the driving cell by, and the stage of that input pin.
     */
    static final class Stage {
        private final int id;
        private final StageKind kind;
        private final EDIFHierPortInst driver;
        private final String portName;
        private final EDIFHierNet net;
        private final int parentStage;
        private final EDIFHierPortInst parentPin;
        private final List<EDIFHierPortInst> sinks = new ArrayList<>();
        /** The names of the sinks, while tracing */
        private Set<String> sinkNames = new HashSet<>();
        /**
         * For a clock on a hierarchical pin: the cell's hierarchical name and '/', the stage's loads being
         * those inside the cell (an input pin) or outside it (an output pin); else null
         */
        private String scope;
        private boolean scopeInside;
        /** The auto-derived clock the stage was created for (e.g. a generator output's), -1 if none */
        private int derivedClock = -1;

        Stage(int id, StageKind kind, EDIFHierPortInst driver, String portName, EDIFHierNet net, int parentStage,
              EDIFHierPortInst parentPin) {
            this.id = id;
            this.kind = kind;
            this.driver = driver;
            this.portName = portName;
            this.net = net;
            this.parentStage = parentStage;
            this.parentPin = parentPin;
        }

        public int getId() {
            return id;
        }

        public StageKind getKind() {
            return kind;
        }

        /** @return The output pin driving the stage's net, null for a top-level port. */
        public EDIFHierPortInst getDriver() {
            return driver;
        }

        /** @return The (logical) net of the stage. */
        public EDIFHierNet getNet() {
            return net;
        }

        /** @return The stage of the input pin the clock enters the driving cell by, -1 if none. */
        public int getParentStage() {
            return parentStage;
        }

        /** @return The input pin of the driving cell the clock comes from, null if none. */
        public EDIFHierPortInst getParentPin() {
            return parentPin;
        }

        /** @return The sinks of the net that the clocks reach (clock pins, inputs of buffers and generators). */
        public List<EDIFHierPortInst> getSinks() {
            return Collections.unmodifiableList(sinks);
        }

        /**
         * @return The auto-derived clock of the stage's driver that the stage was created for (e.g. the clock
         * of a generator output derived from the clock of one of its inputs), -1 if none.
         */
        int getDerivedClock() {
            return derivedClock;
        }

        @Override
        public String toString() {
            return kind + ":" + (driver != null ? driver.toString() : portName);
        }
    }

    private final Design design;
    private final EDIFNetlist netlist;
    private final TimingConstraints constraints;

    private final List<Clock> clocks = new ArrayList<>();
    private final Map<String, Clock> byName = new LinkedHashMap<>();
    /** Clock edge ids (2*clock + inverted) on every pin the clocks reach, by pin name */
    private final Map<String, List<Integer>> pinEdges = new HashMap<>();

    private final List<EDIFHierPortInst> clockPins = new ArrayList<>();
    private final Map<String, Integer> clockPinIndex = new HashMap<>();
    private final List<int[]> clockPinEdges = new ArrayList<>();
    private final Map<String, List<Integer>> cellClockPins = new HashMap<>();

    private final List<Stage> stages = new ArrayList<>();
    private final Map<String, Integer> stageByDriver = new HashMap<>();
    /** The stage each clock reaches a clock pin through, by "pin|clock" */
    private final Map<String, Integer> pinClockStage = new HashMap<>();
    /** The stage each clock pin is a sink of */
    private final List<Integer> clockPinStage = new ArrayList<>();

    /** Pins with user clocks (no -add), which block propagated clocks */
    private final Set<String> userClockPins = new HashSet<>();
    /** Auto-derived clock renames, by generator output pin name */
    private final Map<String, TimingConstraints.GeneratedClockDef> renames = new HashMap<>();
    private final Set<String> derived = new HashSet<>();
    private final Map<String, Integer> nameCounters = new HashMap<>();
    /**
     * The settings of each clock generator output pin from the clocks reaching the generator's inputs: {VCO
     * frequency in MHz, feedback multiplier, output divide}, by pin name (the first clock's) and by "pin|clock id",
     * also where a user clock replaces the auto-derived one
     */
    private final Map<String, double[]> generatorOutputSettings = new HashMap<>();
    /** The output pin of each user generated clock defined on a clock generator output */
    private final Map<Clock, String> userGeneratorOutputs = new LinkedHashMap<>();
    /** The generator output pin of each primary clock defined downstream of one through clock buffers */
    private final Map<Clock, String> tracedGeneratorOutputs = new LinkedHashMap<>();
    private final Map<String, Integer> warnings = new LinkedHashMap<>();
    /** Source keys of the primary clocks, by clock id */
    private final Map<Integer, Set<String>> rootSources = new HashMap<>();

    private ClockNetwork(Design design, EDIFNetlist netlist, TimingConstraints constraints) {
        this.design = design;
        this.netlist = netlist;
        this.constraints = constraints;
    }

    /**
     * Traces the clocks of a design.
     * @param design The design.
     * @param constraints Its timing constraints (see {@link TimingConstraints#fromDesign(Design)}).
     * @return The clock network.
     */
    public static ClockNetwork build(Design design, TimingConstraints constraints) {
        ClockNetwork cn = new ClockNetwork(design, design.getNetlist(), constraints);
        cn.trace();
        return cn;
    }

    /**
     * Traces the clocks of a netlist (see {@link #build(Design, TimingConstraints)}).
     */
    public static ClockNetwork build(EDIFNetlist netlist, TimingConstraints constraints) {
        ClockNetwork cn = new ClockNetwork(null, netlist, constraints);
        cn.trace();
        return cn;
    }

    public Design getDesign() {
        return design;
    }

    public EDIFNetlist getNetlist() {
        return netlist;
    }

    public TimingConstraints getConstraints() {
        return constraints;
    }

    // ------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------

    public int getNumClocks() {
        return clocks.size();
    }

    public List<Clock> getClocks() {
        return Collections.unmodifiableList(clocks);
    }

    public Clock getClock(int id) {
        return clocks.get(id);
    }

    /** @return The clock of a name, null if none. */
    public Clock getClock(String name) {
        return byName.get(name);
    }

    public int getNumClockPins() {
        return clockPins.size();
    }

    /** @return A clock pin (an input pin of a cell on which clocks end). */
    public EDIFHierPortInst getClockPin(int i) {
        return clockPins.get(i);
    }

    /**
     * @return The clock edges of a clock pin: 2*clock id + 1 if the pin is clocked on the falling
     * edge of that clock, else 2*clock id.
     */
    public int[] getClockPinEdges(int i) {
        return clockPinEdges.get(i);
    }

    /** @return The stage (net) a clock pin is a sink of. */
    public int getClockPinStage(int i) {
        return clockPinStage.get(i);
    }

    /**
     * Gets the stage a clock reaches a clock pin through: that of the clock's own path when clocks reach
     * the pin by several paths (e.g. the inputs of a clock mux), else {@link #getClockPinStage(int)}.
     * @param i The clock pin.
     * @param clock The clock id.
     * @return The stage id.
     */
    public int getClockPinStage(int i, int clock) {
        Integer s = pinClockStage.get(clockPins.get(i).toString() + "|" + clock);
        return s != null ? s : clockPinStage.get(i);
    }

    /** @return The index of a clock pin by its full name (cell name '/' port name), -1 if none. */
    public int getClockPinIndex(String pinName) {
        Integer i = clockPinIndex.get(pinName);
        return i == null ? -1 : i;
    }

    /** @return The clock pins of a cell by its full hierarchical name (empty if none). */
    public List<Integer> getClockPinsOfCell(String cellName) {
        List<Integer> l = cellClockPins.get(cellName);
        return l == null ? Collections.emptyList() : l;
    }

    /** @return The clock edge ids reaching a pin (of any kind) by its full name, empty if none. */
    public List<Integer> getPinClockEdges(String pinName) {
        List<Integer> l = pinEdges.get(pinName);
        return l == null ? Collections.emptyList() : l;
    }

    List<Stage> getStages() {
        return Collections.unmodifiableList(stages);
    }

    /**
     * @return The warnings of the tracing with their number of occurrences: a kind, followed by ": " and the
     * object it is about where that is known (see {@link #printWarnings(String, Map, boolean)}).
     */
    public Map<String, Integer> getWarnings() {
        return Collections.unmodifiableMap(warnings);
    }

    /** Prints the warnings, one line per kind, see {@link #printWarnings(String, Map, boolean)}. */
    public void printWarnings() {
        printWarnings("Clock network", warnings, false);
    }

    /**
     * Prints warnings counted by kind, one line per kind with its number of occurrences. A warning
     * {@code "<kind>: <object>"} counts for its kind; the objects are listed below their kind if verbose.
     * @param source What the warnings are about, e.g. "Clock network".
     * @param warnings The warnings and their numbers of occurrences.
     * @param verbose Whether to list the objects of each kind.
     */
    public static void printWarnings(String source, Map<String, Integer> warnings, boolean verbose) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> objects = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : warnings.entrySet()) {
            String w = e.getKey();
            int sep = w.indexOf(": ");
            String kind = sep < 0 ? w : w.substring(0, sep);
            counts.merge(kind, e.getValue(), Integer::sum);
            if (sep >= 0) {
                objects.computeIfAbsent(kind, k -> new ArrayList<>()).add(w.substring(sep + 2)
                        + (e.getValue() > 1 ? " (" + e.getValue() + " times)" : ""));
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            List<String> objs = objects.get(e.getKey());
            System.out.println("WARNING: " + source + ": " + e.getKey()
                    + (e.getValue() > 1 ? " (" + e.getValue() + " times)" : "")
                    + (objs != null && !verbose ? " (objects listed with --verbose)" : ""));
            if (objs != null && verbose) {
                for (String o : objs) {
                    System.out.println("         " + o);
                }
            }
        }
    }

    private void warn(String kind) {
        warnings.merge(kind, 1, Integer::sum);
    }

    /**
     * Checks if two clocks are related, i.e. derive from the same primary clock, or from primary
     * clocks sharing a source object (e.g. two clocks of the same port, created with -add).
     */
    public boolean areRelated(Clock a, Clock b) {
        Clock ra = a.getRoot();
        Clock rb = b.getRoot();
        if (ra == rb) {
            return true;
        }
        Set<String> sa = rootSources.get(ra.getId());
        Set<String> sb = rootSources.get(rb.getId());
        if (sa == null || sb == null) {
            return false;
        }
        for (String s : sa) {
            if (sb.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves clock queries to clocks, as Vivado would at the time of each query: only the clocks
     * defined before it match.
     * @param objects Clock queries ({@link XdcObject.Kind#CLOCK}); other objects are ignored.
     * @return The ids of the matching clocks, in clock order.
     */
    public Set<Integer> resolveClocks(List<XdcObject> objects) {
        Set<Integer> result = new LinkedHashSet<>();
        if (objects == null) {
            return result;
        }
        for (XdcObject o : objects) {
            if (o.getKind() != XdcObject.Kind.CLOCK) continue;
            Set<Integer> matched = new LinkedHashSet<>();
            if (o.getClockOf() != null) {
                for (XdcObject of : o.getClockOf()) {
                    for (int edge : clockEdgesOfObject(of)) {
                        matched.add(edge >> 1);
                    }
                }
            } else {
                for (Clock c : clocks) {
                    if (c.getDefIndex() <= o.getQueryIndex() && o.matchesClockName(c.getName())) {
                        matched.add(c.getId());
                    }
                }
            }
            if (o.isIncludeGenerated()) {
                for (Clock c : clocks) {
                    if (c.getDefIndex() > o.getQueryIndex()) continue;
                    for (int m : new ArrayList<>(matched)) {
                        if (c.isDerivedFrom(clocks.get(m))) {
                            matched.add(c.getId());
                        }
                    }
                }
            }
            result.addAll(matched);
        }
        return result;
    }

    /** The clock edges on an object: a pin, port, net (its driver) or cell (its clock pins). */
    List<Integer> clockEdgesOfObject(XdcObject o) {
        List<Integer> out = new ArrayList<>();
        switch (o.getKind()) {
            case PIN:
                out.addAll(getPinClockEdges(o.getName()));
                if (out.isEmpty() && o.getPin() != null) {
                    out.addAll(hierarchicalPinClockEdges(o.getPin()));
                }
                break;
            case PORT:
                out.addAll(getPinClockEdges(portKey(o.getName())));
                break;
            case NET:
                if (o.getNet() != null) {
                    EDIFHierPortInst src = o.getNet().getLeafSourcePortInst();
                    if (src != null) {
                        out.addAll(getPinClockEdges(src.toString()));
                    } else {
                        for (EDIFHierPortInst p : o.getNet().getLeafHierPortInsts(false, true)) {
                            out.addAll(getPinClockEdges(p.toString()));
                            break;
                        }
                    }
                }
                break;
            case CELL:
                for (int cp : getClockPinsOfCell(o.getName())) {
                    for (int e : clockPinEdges.get(cp)) {
                        out.add(e);
                    }
                }
                break;
            default:
                break;
        }
        return out;
    }

    /**
     * Gets the clock edge a startpoint or endpoint pin of a cell is timed with: the cell's clock pin
     * itself if the pin is one, else the cell's only clock pin, else the clock pin matching the pin's
     * port (A/B, RD/WR halves of block RAMs and FIFOs), else the first one (with a warning per cell type and
     * pin name).
     * @param cellName Full hierarchical name of the cell.
     * @param portName Name of the pin's port instance.
     * @return The clock pin index, -1 if the cell has no clock pin.
     */
    public int getClockPinOf(String cellName, String portName) {
        List<Integer> cps = getClockPinsOfCell(cellName);
        if (cps.isEmpty()) {
            return -1;
        }
        if (cps.size() == 1) {
            return cps.get(0);
        }
        for (int cp : cps) {
            if (clockPins.get(cp).getPortInst().getName().equals(portName)) {
                return cp;
            }
        }
        // Dynamic reconfiguration ports of hard blocks are clocked by DCLK
        if (DRP_CLOCKED.matcher(portName).matches()) {
            for (int cp : cps) {
                if (clockPins.get(cp).getPortInst().getName().equals("DCLK")) {
                    return cp;
                }
            }
        }
        String side = portSide(portName);
        if (side != null) {
            for (int cp : cps) {
                if (side.equals(portSide(clockPins.get(cp).getPortInst().getName()))) {
                    return cp;
                }
            }
        }
        // (One warning per cell type and pin name)
        EDIFHierPortInst first = clockPins.get(cps.get(0));
        warn("pin of a cell with several clock pins timed with its first clock pin (" + first.getPortInst().getName()
                + "): " + first.getCellType().getName() + "/" + portName);
        return cps.get(0);
    }

    private static final Pattern DRP_CLOCKED = Pattern.compile("(DADDR|DI|DEN|DWE|DO|DOUT|DRDY)(\\[.*)?");
    private static final Pattern DRP_PORT = Pattern.compile("(DADDR|DI|DEN|DWE|DO|DRDY)(\\[.*)?");
    private static final Pattern PS_PORT = Pattern.compile("(PSEN|PSINCDEC|PSDONE)(\\[.*)?");
    private static final Pattern A_PORT = Pattern.compile(".*(ADDRA|DINA|DINPA|DOUTA|DOUTPA|ENA|WEA|REGCEA|RSTA"
            + "|ADDRARDADDR|DINADIN|DINPADINP|DOUTADOUT|DOUTPADOUTP|ENARDEN|WEA|REGCEAREGCE|RSTRAMARSTRAM|RSTREGARSTREG"
            + "|CASDINA|CASDOUTA|CASDINPA|CASDOUTPA|CASOREGIMUXA|CASDIMUXA|CASDOMUXA).*");
    private static final Pattern B_PORT = Pattern.compile(".*(ADDRB|DINB|DINPB|DOUTB|DOUTPB|ENB|WEB|REGCEB|RSTB"
            + "|ADDRBWRADDR|DINBDIN|DINPBDINP|DOUTBDOUT|DOUTPBDOUTP|ENBWREN|WEBWE|REGCEB|RSTRAMB|RSTREGB|CASDINB"
            + "|CASDOUTB|CASDINPB|CASDOUTPB|CASOREGIMUXB|CASDIMUXB|CASDOMUXB).*");

    /** The side (A or B, RD or WR) of a port of a dual-port block, null if unknown. */
    private static String portSide(String port) {
        String p = port.toUpperCase();
        // Dynamic reconfiguration and phase shift ports of hard blocks
        if (p.equals("DCLK") || DRP_PORT.matcher(p).matches()) return "DRP";
        if (p.equals("PSCLK") || PS_PORT.matcher(p).matches()) return "PS";
        if (p.startsWith("CLKARDCLK")) return "A";
        if (p.startsWith("CLKBWRCLK")) return "B";
        if (p.startsWith("RDCLK")) return "RD";
        if (p.startsWith("WRCLK")) return "WR";
        if (p.startsWith("RD") || p.startsWith("DOUT") && !p.startsWith("DOUTA") && !p.startsWith("DOUTB")
                || p.startsWith("EMPTY") || p.startsWith("PROG_EMPTY")) return "RD";
        if (p.startsWith("WR") || p.startsWith("DIN") && !p.startsWith("DINA") && !p.startsWith("DINB")
                || p.startsWith("FULL") || p.startsWith("PROG_FULL")) return "WR";
        if (A_PORT.matcher(p).matches()) return "A";
        if (B_PORT.matcher(p).matches()) return "B";
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Cell kinds
    // ------------------------------------------------------------------------------------------

    /**
     * Checks if a pin of a cell is a clock pin by its name: C, CB, or a name containing CLK.
     * @param pinName The pin (or bus) name.
     */
    static boolean isClockPinName(String pinName) {
        return pinName.equals("C") || pinName.equals("CB") || pinName.contains("CLK");
    }

    /** Whether a cell type is a clock generator */
    static boolean isGenerator(String type) {
        return type.startsWith("MMCME") || type.startsWith("PLLE") || type.startsWith("XPLL") || type.equals("X5PLL")
                || type.equals("DPLL") || type.equals("MMCM") || type.equals("PLL");
    }

    /** Whether a cell type is a clock buffer that passes its clock input to its outputs unchanged */
    static boolean isClockBuffer(String type) {
        return type.startsWith("BUFG") && !type.equals("BUFGCE_DIV") || type.startsWith("BUFCE_")
                || type.startsWith("BUFDIV_LEAF") || type.startsWith("IBUF") || type.equals("BUF")
                || type.equals("INBUF") || type.equals("IBUFCTRL") || type.equals("DIFFINBUF")
                || type.equals("INV") || type.equals("BUFH") || type.equals("BUFR");
    }

    private static boolean isClockDivider(String type) {
        return type.equals("BUFGCE_DIV") || type.startsWith("MBUFG");
    }

    /** Whether a cell type is a latch (LDCE, LDPE) */
    private static boolean isLatch(String type) {
        return type.equals("LDCE") || type.equals("LDPE");
    }

    private static boolean isCombinational(String type) {
        return type.startsWith("LUT") || type.startsWith("MUXF");
    }

    /** The clock data inputs of a buffer, divider or combinational cell */
    private static boolean isPropagatingInput(String type, String pin) {
        if (type.startsWith("LUT")) {
            return pin.length() == 2 && pin.charAt(0) == 'I';
        }
        if (type.startsWith("MUXF")) {
            return pin.equals("I0") || pin.equals("I1");
        }
        if (type.equals("DIFFINBUF")) {
            return pin.startsWith("DIFF_IN");
        }
        return pin.equals("I") || pin.equals("I0") || pin.equals("I1") || pin.equals("IB") || pin.equals("CLK_IN")
                || pin.equals("PAD");
    }

    private static boolean isInputPortName(String type, String pin) {
        return type.startsWith("XPLL") || type.equals("X5PLL") || type.equals("DPLL") ? pin.equals("CLKIN")
                : pin.equals("CLKIN1") || pin.equals("CLKIN2") || pin.equals("CLKIN");
    }

    /** Whether a LUT inverts: a LUT1 with INIT 2'h1 */
    private static boolean isInvertingLut(EDIFCellInst ci) {
        if (!ci.getCellType().getName().equals("LUT1")) {
            return false;
        }
        EDIFPropertyValue init = ci.getProperty("INIT");
        if (init == null) {
            return false;
        }
        String v = init.getValue().replace("\"", "").trim();
        return v.endsWith("h1") || v.endsWith("b01") || v.equals("1");
    }

    // ------------------------------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------------------------------

    /** Gets a numeric property of a cell instance (Verilog literals included), or a default. */
    static double getNumber(EDIFCellInst ci, String key, double dflt) {
        EDIFPropertyValue v = ci.getProperty(key);
        if (v == null) {
            return dflt;
        }
        return parseNumber(v.getValue(), dflt);
    }

    private static final java.util.regex.Pattern LEADING_NUMBER =
            java.util.regex.Pattern.compile("(-?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)\\s+\\S+");

    static double parseNumber(String s, double dflt) {
        s = s.replace("\"", "").trim();
        try {
            int q = s.indexOf('\'');
            if (q >= 0) {
                char base = Character.toLowerCase(s.charAt(q + 1));
                String digits = s.substring(q + 2).replace("_", "");
                int radix = base == 'h' ? 16 : base == 'b' ? 2 : base == 'o' ? 8 : 10;
                return Long.parseLong(digits, radix);
            }
            return Double.parseDouble(s);
        } catch (RuntimeException e) {
            // A number with a unit, e.g. "322.266000 MHz"
            java.util.regex.Matcher m = LEADING_NUMBER.matcher(s);
            return m.lookingAt() ? Double.parseDouble(m.group(1)) : dflt;
        }
    }

    static String getString(EDIFCellInst ci, String key) {
        EDIFPropertyValue v = ci.getProperty(key);
        return v == null ? null : v.getValue().replace("\"", "").trim();
    }

    // ------------------------------------------------------------------------------------------
    // Tracing
    // ------------------------------------------------------------------------------------------

    private static String portKey(String portName) {
        return "port:" + portName;
    }

    /** The primary clock definitions as Vivado overrides them, see {@link #resolvePrimaryClocks()} */
    private static final class PrimaryClocks {
        /** The create_clock definitions in order, null for one that a later one replaced */
        final List<TimingConstraints.ClockDef> defs = new ArrayList<>();
        /** The keys of the sources that each definition keeps */
        final List<List<String>> keys = new ArrayList<>();
        /** The sources that each definition keeps */
        final List<List<XdcObject>> sources = new ArrayList<>();
    }

    /**
     * Resolves the primary clocks (create_clock) as Vivado overrides them: a clock replaces the earlier ones on its
     * sources unless -add (an earlier clock keeps its other sources), and an earlier clock of the same name.
     */
    private PrimaryClocks resolvePrimaryClocks() {
        PrimaryClocks primary = new PrimaryClocks();
        List<TimingConstraints.ClockDef> defs = primary.defs;
        List<List<String>> defKeys = primary.keys;
        List<List<XdcObject>> defSources = primary.sources;
        for (TimingConstraints.ClockDef cd : constraints.getClocks()) {
            List<String> keys = new ArrayList<>();
            for (XdcObject o : cd.sources) {
                keys.add(sourceKey(o));
            }
            for (int p = 0; p < defs.size(); p++) {
                if (defs.get(p) == null) continue;
                if (java.util.Objects.equals(defs.get(p).name, cd.name)) {
                    defs.set(p, null);
                    continue;
                }
                List<String> pk = defKeys.get(p);
                if (cd.add || pk.isEmpty()) continue;
                for (int k = pk.size() - 1; k >= 0; k--) {
                    if (keys.contains(pk.get(k))) {
                        pk.remove(k);
                        defSources.get(p).remove(k);
                        if (pk.isEmpty()) {
                            defs.set(p, null);
                        }
                    }
                }
            }
            defs.add(cd);
            defKeys.add(keys);
            defSources.add(new ArrayList<>(cd.sources));
        }
        return primary;
    }

    /**
     * Gets the primary clocks (create_clock) of timing constraints that are not replaced by later ones, as Vivado
     * overrides them (a clock replaces the earlier ones on all of its sources unless -add, and an earlier clock of
     * the same name), without tracing the clock network.
     * @param netlist The netlist the constraints apply to.
     * @param constraints The constraints.
     * @return The clock definitions that remain, in order.
     */
    public static List<TimingConstraints.ClockDef> getPrimaryClocks(EDIFNetlist netlist, TimingConstraints constraints) {
        List<TimingConstraints.ClockDef> out = new ArrayList<>();
        for (TimingConstraints.ClockDef cd : new ClockNetwork(null, netlist, constraints).resolvePrimaryClocks().defs) {
            if (cd != null) {
                out.add(cd);
            }
        }
        return out;
    }

    /** A clock at a stage driver, with its inversion and the number of combinational cells crossed */
    private static final class Work {
        final int stage;
        final int edge;
        final int combDepth;

        Work(int stage, int edge, int combDepth) {
            this.stage = stage;
            this.edge = edge;
            this.combDepth = combDepth;
        }
    }

    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private final Set<Long> visited = new HashSet<>();
    private final Map<Integer, List<EDIFHierPortInst>> stageSinks = new HashMap<>();
    /** The stage each sink pin reached by a clock belongs to, by pin name */
    private final Map<String, Integer> sinkStage = new HashMap<>();

    /**
     * Applies the set_clock_latency constraints: source latencies (inherited by the clocks generated from a
     * clock without one of their own) and network latencies (ideal clocks).
     */
    private void applyClockLatencies() {
        boolean[][] own = new boolean[clocks.size()][2];
        for (TimingConstraints.ClockLatencyDef d : constraints.getClockLatencies()) {
            for (int id : resolveClocks(d.clocks)) {
                Clock c = clocks.get(id);
                for (int late = 0; late < 2; late++) {
                    if (late == 0 ? !d.min : !d.max) continue;
                    if (d.source) {
                        c.setUserSourceLatency(late == 1, d.value * 1000);
                        own[id][late] = true;
                    } else {
                        c.setNetworkLatency(late == 1, d.value * 1000);
                    }
                }
            }
        }
        // Clocks are numbered after their masters
        for (Clock c : clocks) {
            Clock m = c.getMaster();
            if (m == null) continue;
            for (int late = 0; late < 2; late++) {
                if (!own[c.getId()][late]) {
                    c.setUserSourceLatency(late == 1, m.getUserSourceLatency(late == 1));
                }
            }
        }
    }

    private void trace() {
        // Pins with user clocks, which block propagated clocks (those of the primary clocks below), and renames
        for (TimingConstraints.GeneratedClockDef gd : constraints.getGeneratedClocks()) {
            for (XdcObject o : gd.targets) {
                if (o.getKind() != XdcObject.Kind.PIN) continue;
                if (gd.isRename()) {
                    renames.put(o.getName(), gd);
                } else if (!gd.add) {
                    userClockPins.add(o.getName());
                }
            }
        }

        // Primary clocks, as Vivado overrides them
        PrimaryClocks primary = resolvePrimaryClocks();
        List<TimingConstraints.ClockDef> defs = primary.defs;
        List<List<String>> defKeys = primary.keys;
        List<List<XdcObject>> defSources = primary.sources;
        for (int i = 0; i < defs.size(); i++) {
            if (defs.get(i) == null || defs.get(i).add) continue;
            for (XdcObject o : defSources.get(i)) {
                if (o.getKind() == XdcObject.Kind.PIN) {
                    userClockPins.add(o.getName());
                }
            }
        }
        for (int i = 0; i < defs.size(); i++) {
            TimingConstraints.ClockDef cd = defs.get(i);
            if (cd == null) continue;
            double[] wave = new double[cd.waveform.length];
            for (int w = 0; w < wave.length; w++) {
                wave[w] = cd.waveform[w] * 1000;
            }
            Clock c = addClock(cd.name, cd.period * 1000, wave, null, false, false, defKeys.get(i), 0, cd.index,
                    null, null);
            rootSources.put(c.getId(), new HashSet<>(defKeys.get(i)));
            c.setKind(primaryKind(defSources.get(i)));
            setTracedGeneratorSettings(c, defSources.get(i));
            for (XdcObject o : defSources.get(i)) {
                startAt(o, c, StageKind.PORT);
            }
        }
        run();

        // User generated clocks, in order, retrying those whose master is not known yet
        // A generated clock replaces the earlier ones on the same targets unless -add (e.g. constraints
        // repeated in two XDC groups)
        List<TimingConstraints.GeneratedClockDef> pending = new ArrayList<>();
        Map<String, Integer> byTargets = new HashMap<>();
        for (TimingConstraints.GeneratedClockDef gd : constraints.getGeneratedClocks()) {
            if (gd.isRename()) continue;
            StringBuilder key = new StringBuilder();
            for (XdcObject t : gd.targets) {
                key.append(sourceKey(t)).append('\n');
            }
            Integer prev = byTargets.get(key.toString());
            if (prev != null && !gd.add) {
                pending.set(prev, null);
            }
            byTargets.put(key.toString(), pending.size());
            pending.add(gd);
        }
        pending.removeIf(java.util.Objects::isNull);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (int i = 0; i < pending.size(); i++) {
                if (createGenerated(pending.get(i))) {
                    pending.remove(i--);
                    progress = true;
                    run();
                }
            }
        }
        for (TimingConstraints.GeneratedClockDef gd : pending) {
            warn("create_generated_clock without a master clock at its source (ignored): "
                    + (gd.name != null ? gd.name : gd.targets.isEmpty() ? "" : gd.targets.get(0).getName()));
        }
        for (Map.Entry<String, TimingConstraints.GeneratedClockDef> e : renames.entrySet()) {
            if (!derived.contains("rename:" + e.getKey())) {
                warn("create_generated_clock renaming no auto-derived clock: " + e.getKey());
            }
        }
        warnInvertedXphyStrobes();
        stageSinks.clear();
        visited.clear();
        sinkStage.clear();
        for (Stage st : stages) {
            st.sinkNames = null;
        }
        applyClockLatencies();
        // User clocks with a source that reach no clock pin (e.g. a source that is not on a clock path)
        boolean[] reached = new boolean[clocks.size()];
        for (int[] edges : clockPinEdges) {
            for (int e : edges) {
                reached[e >> 1] = true;
            }
        }
        for (Clock c : clocks) {
            if (!reached[c.getId()] && !c.isAutoDerived() && !c.getSources().isEmpty() && !isMasterOfAnother(c)) {
                warn("clock reaching no clock pin");
            }
        }
        classifyFabricClocks();
        adoptGeneratorOutputSettings();
    }

    /**
     * Gives the primary clocks that come from a clock generator output through clock buffers (see
     * {@link #setTracedGeneratorSettings}) the settings of that output from the clock at the generator's input, if
     * a clock reaches it (whether or not a user clock replaces the clock derived on the output): Vivado computes
     * their discrete jitter from that clock's period.
     */
    private void adoptGeneratorOutputSettings() {
        for (Map.Entry<Clock, String> e : tracedGeneratorOutputs.entrySet()) {
            double[] st = generatorOutputSettings.get(e.getValue());
            if (st != null) {
                e.getKey().setGeneratorSettings(st[0], st[1], st[2]);
            }
        }
        // User generated clocks on generator outputs (after the whole trace, whatever the order of the constraints
        // that bring clocks to the generator's inputs): the settings from their master clock if it reaches an input
        for (Map.Entry<Clock, String> e : userGeneratorOutputs.entrySet()) {
            Clock m = e.getKey().getMaster();
            double[] st = m == null ? null : generatorOutputSettings.get(e.getValue() + "|" + m.getId());
            if (st == null) {
                st = generatorOutputSettings.get(e.getValue());
            }
            if (st != null) {
                e.getKey().setGeneratorSettings(st[0], st[1], st[2]);
            }
        }
    }

    /**
     * Gives the jitter kind FABRIC to the port clocks that reach none of their clock pins through a
     * clock buffer, divider or generator (e.g. an out-of-context design's clock port driving its clock
     * pins directly): on Versal, Vivado charges such clocks a large jitter.
     */
    private void classifyFabricClocks() {
        int n = clocks.size();
        boolean[] hasPins = new boolean[n];
        boolean[] buffered = new boolean[n];
        byte[] stageBuffered = new byte[stages.size()];
        for (int i = 0; i < clockPins.size(); i++) {
            for (int e : clockPinEdges.get(i)) {
                int c = e >> 1;
                hasPins[c] = true;
                if (!buffered[c]) {
                    buffered[c] = isBuffered(getClockPinStage(i, c), stageBuffered);
                }
            }
        }
        for (Clock c : clocks) {
            if ("PORT".equals(c.getKind()) && hasPins[c.getId()] && !buffered[c.getId()]) {
                c.setKind("FABRIC");
            }
        }
    }

    /** Whether a stage or one of its parent stages is the output of a clock buffer, divider or generator */
    private boolean isBuffered(int stage, byte[] memo) {
        if (stage < 0) return false;
        if (memo[stage] != 0) return memo[stage] > 0;
        Stage st = stages.get(stage);
        boolean b;
        if (st.getKind() == StageKind.GENERATOR || st.getKind() == StageKind.DIVIDER) {
            b = true;
        } else if (st.getKind() == StageKind.BUFFER && st.getDriver() != null
                && isGlobalClockBuffer(st.getDriver().getCellType().getName())) {
            b = true;
        } else {
            b = isBuffered(st.getParentStage(), memo);
        }
        memo[stage] = (byte) (b ? 1 : -1);
        return b;
    }

    /** Whether a cell type is a global clock buffer (BUFG*, MBUFG*, BUFCE_*, BUFDIV_LEAF, ...) */
    static boolean isGlobalClockBuffer(String type) {
        return type.startsWith("BUFG") || type.startsWith("MBUFG") || type.startsWith("BUFCE")
                || type.startsWith("BUFDIV") || type.startsWith("BUFR") || type.startsWith("BUFH");
    }

    private boolean isMasterOfAnother(Clock c) {
        for (Clock o : clocks) {
            if (o.getMaster() == c) return true;
        }
        return false;
    }

    /**
     * The clock edges of a hierarchical pin a clock propagates through (not one defined on it): those of
     * the leaf driver of its net, else of the first leaf load on its side with clock edges.
     */
    private List<Integer> hierarchicalPinClockEdges(EDIFHierPortInst p) {
        EDIFCellInst ci = p.getPortInst().getCellInst();
        if (ci == null || ci.getCellType().isLeafCellOrBlackBox()) {
            return Collections.emptyList();
        }
        EDIFHierNet hn = p.isInput() ? p.getInternalNet() : p.getHierarchicalNet();
        if (hn == null || hn.getNet() == null) {
            return Collections.emptyList();
        }
        EDIFHierPortInst src = hn.getLeafSourcePortInst();
        if (src != null && !getPinClockEdges(src.toString()).isEmpty()) {
            return getPinClockEdges(src.toString());
        }
        String scope = p.getHierarchicalInst().getChild(ci).getFullHierarchicalInstName() + "/";
        for (EDIFHierPortInst s : hn.getLeafHierPortInsts(false, true)) {
            if (s.toString().startsWith(scope) != p.isInput()) continue;
            List<Integer> e = getPinClockEdges(s.toString());
            if (!e.isEmpty()) {
                return e;
            }
        }
        return Collections.emptyList();
    }

    /**
     * The kind of a primary clock for its jitter: that of the block it is defined on or comes from through clock
     * buffers (see {@link #getSourceKind}), else PORT (FABRIC once propagated, if it reaches its clock pins through
     * no clock buffer)
     */
    private String primaryKind(List<XdcObject> sources) {
        EDIFHierPortInst pin = getSourceBlockPin(sources);
        return pin == null ? "PORT" : getSourceKind(pin);
    }

    /**
     * Gives a primary clock that comes from a clock generator output through clock buffers (see
     * {@link #getSourceBlockPin}) the generator's settings for its discrete jitter: the VCO frequency of the input
     * period of the generator's properties (of the input that CLKINSEL selects), which Vivado uses when no clock
     * reaches the generator's input (else that clock's, see {@link #adoptGeneratorOutputSettings()}).
     */
    private void setTracedGeneratorSettings(Clock c, List<XdcObject> sources) {
        EDIFHierPortInst pin = getSourceBlockPin(sources);
        EDIFCellInst ci = pin == null ? null : pin.getPortInst().getCellInst();
        if (ci == null || !isGenerator(ci.getCellType().getName())) return;
        tracedGeneratorOutputs.put(c, pin.toString());
        double mult = getGeneratorMultiplier(ci);
        double divclk = getNumber(ci, "DIVCLK_DIVIDE", 1);
        EDIFHierCellInst cell = pin.getHierarchicalInst().getChild(ci);
        boolean in2 = isSelectedInput(cell, ci, "CLKIN2") && !isSelectedInput(cell, ci, "CLKIN1");
        double tin = getNumber(ci, in2 ? "CLKIN2_PERIOD" : "CLKIN1_PERIOD", Double.NaN);
        if (Double.isNaN(tin) || tin <= 0) {
            tin = getNumber(ci, "CLKIN_PERIOD", Double.NaN);
        }
        String out = pin.getPortInst().getName();
        String base = out.endsWith("B") && !out.startsWith("CLKFBOUT") ? out.substring(0, out.length() - 1) : out;
        double divide = out.startsWith("CLKFBOUT") ? mult : getOutputDivide(ci, base)[0];
        if (Double.isNaN(mult) || mult <= 0 || Double.isNaN(tin) || tin <= 0 || divclk <= 0 || divide <= 0) return;
        c.setGeneratorSettings(1e3 * mult / (tin * divclk), mult, divide);
    }

    /** The pin of the block the first of a clock's source objects comes from (see {@link #getSourceKind}), or null */
    private EDIFHierPortInst getSourceBlockPin(List<XdcObject> sources) {
        for (XdcObject o : sources) {
            EDIFHierPortInst pin = null;
            if (o.getKind() == XdcObject.Kind.PIN) {
                pin = o.getPin();
            } else if (o.getKind() == XdcObject.Kind.NET && o.getNet() != null) {
                // A clock on a net is on its driver
                pin = o.getNet().getLeafSourcePortInst();
            }
            EDIFHierPortInst block = pin == null ? null : getSourceBlock(pin);
            if (block != null) return block;
        }
        return null;
    }

    /**
     * Gets the kind of the block that a clock defined on a pin comes from, for its jitter: PS or GT on pins of those
     * blocks, MMCM or PLL on pins of clock generators, and that of the block driving the clock input of a clock
     * buffer or divider, through any number of them: e.g. MMCM for a clock defined on the output of a BUFGCE that a
     * clock generator output drives, GT for one on a BUFG_GT that a transceiver output drives (Vivado charges such
     * clocks the discrete jitter of that block).
     * @param pin The pin.
     * @return The kind (see {@link Clock#getKind()}), or null if the clock comes from none of these blocks (e.g. a
     * top-level port, through input and clock buffers, or a fabric cell).
     */
    String getSourceKind(EDIFHierPortInst pin) {
        EDIFHierPortInst p = getSourceBlock(pin);
        if (p == null) {
            return null;
        }
        String type = p.getPortInst().getCellInst().getCellType().getName();
        if (type.startsWith("PS")) return "PS";
        if (type.startsWith("GT")) return "GT";
        return ClockDelayParams.getGeneratorKind(type).equals("MMCM") ? "MMCM" : "PLL";
    }

    /**
     * Gets the pin of the block that a clock defined on a pin comes from (see {@link #getSourceKind}): the pin
     * itself on a PS or GT block or a clock generator's output, else the output of such a block that drives the clock
     * input of the clock buffers and dividers the pin is downstream of: of a clock mux (BUFGMUX, BUFGCTRL), the input
     * that its constant select picks, else its only driven input; with two driven inputs and a select that is not
     * constant, the block either input comes from (that of I0 if both do), as Vivado charges it.
     * @return The pin, or null if none.
     */
    private EDIFHierPortInst getSourceBlock(EDIFHierPortInst pin) {
        return getSourceBlock(pin, 0);
    }

    private EDIFHierPortInst getSourceBlock(EDIFHierPortInst pin, int start) {
        EDIFHierPortInst p = pin;
        for (int depth = start; p != null && depth < 32; depth++) {
            EDIFCellInst ci = p.getPortInst().getCellInst();
            if (ci == null) {
                return null;
            }
            String type = ci.getCellType().getName();
            if (type.startsWith("PS") || type.startsWith("GT") || isGenerator(type) && p.isOutput()) {
                return p;
            }
            if (p.isInput()) {
                // A clock defined on an input pin comes from the net's driver
                EDIFHierNet hn = p.getHierarchicalNet();
                p = hn == null ? null : hn.getLeafSourcePortInst();
                continue;
            }
            if (!isClockBuffer(type) && !isClockDivider(type)) {
                return null;
            }
            EDIFHierCellInst cell = p.getHierarchicalInst().getChild(ci);
            String in = isClockInputDriven(cell, ci, "I") ? "I" : getSelectedMuxInput(cell, ci);
            if (in == null && isClockInputDriven(cell, ci, "I0") && isClockInputDriven(cell, ci, "I1")) {
                // A clock mux whose select is not constant
                EDIFHierPortInst b = getSourceBlock(getClockInputSource(cell, ci, "I0"), depth + 1);
                return b != null ? b : getSourceBlock(getClockInputSource(cell, ci, "I1"), depth + 1);
            }
            p = in == null ? null : getClockInputSource(cell, ci, in);
        }
        return null;
    }

    /** Whether a clock input of a clock buffer, mux or divider is connected and not constant (tie-off, case value) */
    private boolean isClockInputDriven(EDIFHierCellInst cell, EDIFCellInst ci, String in) {
        EDIFPortInst pi = ci.getPortInst(in);
        return pi != null && pi.getNet() != null && getPinConstant(cell, in) < 0;
    }

    /** The leaf cell pin driving a clock input, null if none (e.g. a top-level port) */
    private static EDIFHierPortInst getClockInputSource(EDIFHierCellInst cell, EDIFCellInst ci, String in) {
        EDIFHierNet hn = new EDIFHierPortInst(cell.getParent(), ci.getPortInst(in)).getHierarchicalNet();
        return hn == null ? null : hn.getLeafSourcePortInst();
    }

    /**
     * The input of a two-input clock mux that drives its output: the one its select picks if the select is constant
     * (BUFGMUX S: 0 for I0, 1 for I1; BUFGCTRL S0 / S1: 1 / 0 for I0, 0 / 1 for I1, with their programmable
     * inversions), else the only driven input; null if both are driven and the select is not constant, or if neither
     * is driven.
     */
    private String getSelectedMuxInput(EDIFHierCellInst cell, EDIFCellInst ci) {
        boolean d0 = isClockInputDriven(cell, ci, "I0");
        boolean d1 = isClockInputDriven(cell, ci, "I1");
        if (d0 != d1) {
            return d0 ? "I0" : "I1";
        }
        if (!d0) {
            return null;
        }
        int sel = -1;
        if (ci.getPortInst("S0") != null || ci.getPortInst("S1") != null) {
            int a = selectValue(cell, ci, "S0");
            int b = selectValue(cell, ci, "S1");
            sel = a == 1 && b == 0 ? 0 : a == 0 && b == 1 ? 1 : -1;
        } else if (ci.getPortInst("S") != null) {
            sel = selectValue(cell, ci, "S");
        }
        return sel == 0 ? "I0" : sel == 1 ? "I1" : null;
    }

    /** The constant value of a select input with its programmable inversion, -1 if not constant */
    private int selectValue(EDIFHierCellInst cell, EDIFCellInst ci, String pin) {
        int v = getPinConstant(cell, pin);
        return v < 0 ? v : v ^ (isPinInverted(ci, pin) ? 1 : 0);
    }

    /** A key identifying a clock source object */
    private String sourceKey(XdcObject o) {
        switch (o.getKind()) {
            case PORT:
                return portKey(o.getName());
            case NET:
                if (o.getNet() != null) {
                    // A clock on a net is on its driver: a leaf cell's output pin, else a top-level input port
                    EDIFHierPortInst src = o.getNet().getLeafSourcePortInst();
                    if (src != null) return src.toString();
                    EDIFHierNet root = netlist.getParentNet(o.getNet());
                    for (EDIFPortInst pi : (root != null ? root : o.getNet()).getNet().getPortInsts()) {
                        if (pi.isTopLevelPort() && pi.isInput()) return portKey(pi.getName());
                    }
                }
                return "net:" + o.getName();
            default:
                return o.getName();
        }
    }

    private Clock addClock(String name, double period, double[] waveform, Clock master, boolean generated,
                           boolean autoDerived, List<String> sources, double latency, int defIndex,
                           String generatorCell, String generatorType) {
        Clock c = new Clock(clocks.size(), name, period, waveform, master, generated, autoDerived, sources, latency,
                defIndex, generatorCell, generatorType);
        clocks.add(c);
        if (byName.containsKey(name)) {
            warn("duplicate clock name: " + name);
        }
        byName.putIfAbsent(name, c);
        return c;
    }

    /** Starts a clock at a source object (port, pin or net) */
    private void startAt(XdcObject o, Clock c, StageKind kind) {
        int edge = 2 * c.getId();
        switch (o.getKind()) {
            case PORT: {
                if (netlist == null) return;
                EDIFNet net = netlist.getTopCell().getInternalNet(o.getName());
                if (net == null) {
                    warn("clock source port without a net: " + o.getName());
                    return;
                }
                EDIFHierNet hn = new EDIFHierNet(netlist.getTopHierCellInst(), net);
                int s = getStage(portKey(o.getName()), StageKind.PORT, null, o.getName(), hn, -1, null);
                addPinEdge(portKey(o.getName()), edge);
                push(s, edge, 0);
                break;
            }
            case NET: {
                EDIFHierNet hn = o.getNet();
                if (hn == null) return;
                EDIFHierPortInst src = hn.getLeafSourcePortInst();
                String port = src == null ? topLevelInputPort(hn) : null;
                if (port != null) {
                    // The net of a top-level input port (e.g. an out-of-context design's clock): the port's
                    // clock, without a buffer
                    int s = getStage(portKey(port), StageKind.PORT, null, port, hn, -1, null);
                    addPinEdge(portKey(port), edge);
                    push(s, edge, 0);
                    break;
                }
                String key = src != null ? src.toString() : "net:" + o.getName();
                int s = getStage(key, StageKind.USER_PIN, src, null, hn, -1, null);
                addPinEdge(key, edge);
                push(s, edge, 0);
                break;
            }
            case PIN: {
                EDIFHierPortInst p = o.getPin();
                if (p == null) {
                    warn("clock source pin not found: " + o.getName());
                    return;
                }
                addPinEdge(p.toString(), edge);
                EDIFCellInst pci = p.getPortInst().getCellInst();
                if (pci != null && !pci.getCellType().isLeafCellOrBlackBox()) {
                    // A hierarchical pin: the clock enters the cell's internal net (an input pin) or leaves
                    // on its external net (an output pin), reaching the leaf pins on that side
                    EDIFHierNet hn = p.isInput() ? p.getInternalNet() : p.getHierarchicalNet();
                    if (hn == null || hn.getNet() == null) {
                        warn("clock on a hierarchical pin without a net (ignored)");
                        return;
                    }
                    int s = getStage(p.toString(), StageKind.USER_PIN, p, null, hn, -1, null);
                    Stage st = stages.get(s);
                    st.scope = p.getHierarchicalInst().getChild(pci).getFullHierarchicalInstName() + "/";
                    st.scopeInside = p.isInput();
                    push(s, edge, 0);
                } else if (p.isOutput()) {
                    EDIFHierNet hn = p.getHierarchicalNet();
                    if (hn == null || hn.getNet() == null) return;
                    // The stage of the clock entering the cell (e.g. the C pin of a register divider)
                    int parent = -1;
                    EDIFHierPortInst parentPin = null;
                    EDIFCellInst ci = p.getPortInst().getCellInst();
                    if (ci != null) {
                        String type = ci.getCellType().getName();
                        boolean generator = isGenerator(type);
                        for (EDIFPortInst in : ci.getPortInsts()) {
                            if (!in.isInput()) continue;
                            // A generator's clock input, not its feedback input
                            if (generator && !isInputPortName(type, in.getName())) continue;
                            EDIFHierPortInst hin = new EDIFHierPortInst(p.getHierarchicalInst(), in);
                            Integer st = sinkStage.get(hin.toString());
                            if (st != null) {
                                parent = st;
                                parentPin = hin;
                                break;
                            }
                        }
                    }
                    int s = getStage(p.toString(), StageKind.USER_PIN, p, null, hn, parent, parentPin);
                    push(s, edge, 0);
                } else {
                    // A clock defined on an input pin: a clock pin, or an input of a buffer
                    handleSink(p, edge, -1, 0);
                }
                break;
            }
            default:
                break;
        }
    }

    private int getStage(String key, StageKind kind, EDIFHierPortInst driver, String portName, EDIFHierNet net,
                         int parentStage, EDIFHierPortInst parentPin) {
        Integer s = stageByDriver.get(key);
        if (s != null) {
            return s;
        }
        int id = stages.size();
        stages.add(new Stage(id, kind, driver, portName, net, parentStage, parentPin));
        stageByDriver.put(key, id);
        return id;
    }

    private void push(int stage, int edge, int combDepth) {
        if (visited.add(((long) stage << 32) | edge)) {
            queue.add(new Work(stage, edge, combDepth));
        }
    }

    private void addPinEdge(String pin, int edge) {
        List<Integer> l = pinEdges.computeIfAbsent(pin, k -> new ArrayList<>(2));
        if (!l.contains(edge)) {
            l.add(edge);
        }
    }

    private void run() {
        while (!queue.isEmpty()) {
            Work w = queue.poll();
            Stage st = stages.get(w.stage);
            List<EDIFHierPortInst> sinks = stageSinks.get(w.stage);
            if (sinks == null) {
                sinks = st.net.getLeafHierPortInsts(false, true);
                if (st.scope != null) {
                    // A clock on a hierarchical pin: the loads on its side of the pin
                    List<EDIFHierPortInst> in = new ArrayList<>();
                    for (EDIFHierPortInst s : sinks) {
                        if (s.toString().startsWith(st.scope) == st.scopeInside) {
                            in.add(s);
                        }
                    }
                    sinks = in;
                }
                stageSinks.put(w.stage, sinks);
            }
            for (EDIFHierPortInst sink : sinks) {
                if (sink.isOutput() && !sink.isInput()) continue;
                handleSink(sink, w.edge, w.stage, w.combDepth);
            }
        }
    }

    private void addStageSink(int stage, EDIFHierPortInst sink) {
        if (stage < 0) return;
        // A pin is on a single net, but on several stages when clocks reach it by several paths (the inputs of
        // a clock mux); the first is its stage for the clocks it then drives
        String name = sink.toString();
        sinkStage.putIfAbsent(name, stage);
        Stage st = stages.get(stage);
        if (st.sinkNames.add(name)) {
            st.sinks.add(sink);
        }
    }

    /** Handles a clock (edge) arriving at an input pin of a leaf cell. */
    private void handleSink(EDIFHierPortInst sink, int edge, int stage, int combDepth) {
        String pinName = sink.toString();
        if (stage >= 0 && userClockPins.contains(pinName)) {
            return;
        }
        EDIFCellInst ci = sink.getPortInst().getCellInst();
        if (ci == null) {
            return;
        }
        String type = ci.getCellType().getName();
        String port = sink.getPortInst().getName();
        addPinEdge(pinName, edge);
        EDIFHierCellInst cell = sink.getHierarchicalInst().getChild(ci);
        boolean inverted = (edge & 1) != 0;
        if (isGenerator(type) && !port.equals("DCLK") && !port.equals("PSCLK")) {
            // (The clocks of the dynamic reconfiguration and phase shift interfaces are clock pins)
            if (port.equals("CLKFBIN") || isDeskewPin(port)) {
                // The end of the feedback path, whose insertion delay the generator compensates; the ends of the
                // loops of a digital deskew (Versal XPLL CLKIN1_DESKEW, CLKFB1_DESKEW, ...)
                addStageSink(stage, sink);
                return;
            }
            if (isInputPortName(type, port)) {
                addStageSink(stage, sink);
                if (!isSelectedInput(cell, ci, port)) {
                    return;
                }
                // An inverted input clock (an inverted clock, or the input's IS_CLKIN1_INVERTED, ...): the
                // outputs follow its falling edges
                boolean inputInverted = inverted ^ isPinInverted(ci, port);
                deriveGeneratorClocks(cell, ci, type, sink, clocks.get(edge >> 1), inputInverted, stage);
            }
            return;
        }
        if (GtClocks.isChannel(type) || GtClocks.isCommon(type) || GtClocks.isQuad(type)) {
            if (port.toUpperCase().contains("REFCLK")) {
                // A reference clock: the channels' TXOUTCLK / RXOUTCLK derive from it
                addStageSink(stage, sink);
                if (!inverted) {
                    if (GtClocks.isQuad(type)) {
                        deriveGtQuad(cell, ci, sink, clocks.get(edge >> 1), stage);
                    } else {
                        deriveGt(cell, ci, type, sink, clocks.get(edge >> 1), stage);
                    }
                }
                return;
            }
            // Other clock inputs (TXUSRCLK, ...) are clock pins
        } else if (type.startsWith("BUFG_GT") && !type.startsWith("BUFG_GT_SYNC") && port.equals("I")) {
            int div = getBufgGtDiv(cell, ci);
            if (div > 0) {
                addStageSink(stage, sink);
                deriveDividerClocks(cell, ci, type, sink, edge, stage);
                return;
            }
            if (div < 0) {
                warn("BUFG_GT with a DIV that is not constant (its clock is not divided)");
            }
        } else if (type.startsWith("IBUFDS_GTE") && port.equals("I") && !inverted
                && getNumber(ci, "REFCLK_HROW_CK_SEL", 0) != 0) {
            // The GT reference clock buffer's ODIV2 output: O/2 (REFCLK_HROW_CK_SEL 1) or static (2)
            addStageSink(stage, sink);
            for (EDIFPortInst out : ci.getPortInsts()) {
                if (!out.isOutput()) continue;
                if (!out.getName().equals("ODIV2")) {
                    propagateOut(cell, out, edge, stage, sink, StageKind.BUFFER, combDepth);
                }
            }
            if (getNumber(ci, "REFCLK_HROW_CK_SEL", 0) == 1) {
                deriveDividerClocks(cell, ci, type, sink, edge, stage);
            }
            return;
        }
        if (type.equals("DSP_SRCMX_OPTINV")) {
            // The clock (and reset) source mux of a Versal DSP58, optionally inverting: its CLK output clocks
            // the other parts of the DSP
            if (port.equals("CLK_IN")) {
                addStageSink(stage, sink);
                boolean inv = isPinInverted(ci, port);
                for (EDIFPortInst out : ci.getPortInsts()) {
                    if (out.isOutput() && out.getName().equals("CLK")) {
                        propagateOut(cell, out, inv ? (edge ^ 1) : edge, stage, sink, StageKind.BUFFER, combDepth);
                    }
                }
            }
            return;
        }
        if (isClockDivider(type)) {
            if (isPropagatingInput(type, port)) {
                addStageSink(stage, sink);
                // A programmable inversion of the input (IS_I_INVERTED) divides the opposite edges
                deriveDividerClocks(cell, ci, type, sink, isPinInverted(ci, port) ? edge ^ 1 : edge, stage);
            }
            return;
        }
        boolean comb = isCombinational(type);
        if (isClockBuffer(type) || comb) {
            if (!isPropagatingInput(type, port) || comb && combDepth >= MAX_COMBINATIONAL_DEPTH) {
                return;
            }
            addStageSink(stage, sink);
            // An inverter, or a programmable inversion of the buffer's input (BUFGCE IS_I_INVERTED, BUFGCTRL
            // IS_I0_INVERTED / IS_I1_INVERTED, ...)
            boolean inv = type.equals("INV") || isInvertingLut(ci) || isPinInverted(ci, port);
            for (EDIFPortInst out : ci.getPortInsts()) {
                if (!out.isOutput()) continue;
                // IBUFDS_DIFF_OUT OB carries the inverted clock
                boolean outInv = inv ^ out.getName().equals("OB");
                propagateOut(cell, out, outInv ? (edge ^ 1) : edge, stage, sink, StageKind.BUFFER,
                        comb ? combDepth + 1 : combDepth);
            }
            return;
        }
        if (type.equals("XPHY") && port.equals(XPHY_STROBE_PIN) && stage >= 0) {
            // The strobe of a Versal XPHY nibble: the read clock it derives (a data input, not a clock pin)
            deriveXphyReadClock(cell, ci, sink, edge, stage);
            return;
        }
        if (isLatch(type) && port.equals("G")) {
            // Latches are not timed: their gate is not a clock pin, and their paths are unconstrained
            warn("latch gate reached by a clock (latch paths are not timed): " + cell.getFullHierarchicalInstName());
            return;
        }
        if (sink.isInput() && isClockPinName(port)) {
            int cp = addClockPin(sink, cell, ci, port, stage);
            int[] edges = clockPinEdges.get(cp);
            int e = inverted ^ isPinInverted(ci, port) ? (edge | 1) : (edge & ~1);
            for (int x : edges) {
                if (x == e) return;
            }
            int[] n = java.util.Arrays.copyOf(edges, edges.length + 1);
            n[edges.length] = e;
            clockPinEdges.set(cp, n);
            addStageSink(stage, sink);
            if (stage >= 0) {
                pinClockStage.putIfAbsent(pinName + "|" + (e >> 1), stage);
            }
        }
    }

    /** The top-level input port a top-level net is connected to, null if none */
    private static String topLevelInputPort(EDIFHierNet hn) {
        if (!hn.getHierarchicalInst().isTopLevelInst()) {
            return null;
        }
        for (EDIFPortInst p : hn.getNet().getPortInsts()) {
            if (p.isTopLevelPort() && p.getPort().isInput()) {
                return p.getName();
            }
        }
        return null;
    }

    private static boolean isPinInverted(EDIFCellInst ci, String port) {
        String v = getString(ci, "IS_" + port + "_INVERTED");
        if (v == null) {
            return false;
        }
        return parseNumber(v, 0) != 0;
    }

    private int addClockPin(EDIFHierPortInst sink, EDIFHierCellInst cell, EDIFCellInst ci, String port, int stage) {
        String name = sink.toString();
        Integer cp = clockPinIndex.get(name);
        if (cp != null) {
            return cp;
        }
        int idx = clockPins.size();
        clockPins.add(sink);
        clockPinEdges.add(new int[0]);
        clockPinStage.add(stage);
        clockPinIndex.put(name, idx);
        cellClockPins.computeIfAbsent(cell.getFullHierarchicalInstName(), k -> new ArrayList<>(1)).add(idx);
        return idx;
    }

    /** Propagates a clock edge to an output pin of a cell, unless a user clock is defined there. */
    private void propagateOut(EDIFHierCellInst cell, EDIFPortInst out, int edge, int parentStage,
                              EDIFHierPortInst inPin, StageKind kind, int combDepth) {
        EDIFHierPortInst outPin = new EDIFHierPortInst(cell.getParent(), out);
        String key = outPin.toString();
        if (userClockPins.contains(key)) {
            return;
        }
        EDIFHierNet hn = outPin.getHierarchicalNet();
        if (hn == null || hn.getNet() == null) {
            return;
        }
        addPinEdge(key, edge);
        // A stage per path: a cell's output reached through several inputs (a clock mux) has a stage per input
        int s = getStage(key + "|" + parentStage, kind, outPin, null, hn, parentStage, inPin);
        push(s, edge, combDepth);
    }

    /** The name of the net an output pin drives, for naming derived clocks (unique with _1, _2, ...) */
    private String derivedName(EDIFHierPortInst outPin, String fallback) {
        EDIFNet net = outPin.getNet();
        String base = net != null ? net.getName() : fallback;
        // A net leaving its hierarchical cell through an output port takes the name of the net
        // outside it (e.g. a clock generator in a wrapper)
        EDIFHierNet hn = outPin.getHierarchicalNet();
        for (int guard = 0; hn != null && hn.getNet() != null && guard < 64; guard++) {
            EDIFHierCellInst parent = hn.getHierarchicalInst();
            if (parent == null || parent.isTopLevelInst()) break;
            EDIFPortInst outerPort = null;
            for (EDIFPortInst pi : hn.getNet().getPortInsts()) {
                if (pi.getCellInst() == null && pi.isOutput()) {
                    outerPort = parent.getInst().getPortInst(pi.getName());
                    break;
                }
            }
            if (outerPort == null || outerPort.getNet() == null) break;
            hn = new EDIFHierPortInst(parent.getParent(), outerPort).getHierarchicalNet();
            base = outerPort.getNet().getName();
        }
        return uniqueName(base);
    }

    /** A clock name not taken yet: the base name, else the base suffixed _1, _2, ... */
    private String uniqueName(String base) {
        if (!byName.containsKey(base)) {
            return base;
        }
        int n = nameCounters.getOrDefault(base, 0);
        String name;
        do {
            n++;
            name = base + "_" + n;
        } while (byName.containsKey(name));
        nameCounters.put(base, n);
        return name;
    }

    /** Whether an output pin drives anything (other than a given input of the same cell) */
    private static boolean drivesLoads(EDIFHierPortInst outPin, EDIFCellInst self, String selfInput) {
        EDIFHierNet hn = outPin.getHierarchicalNet();
        if (hn == null || hn.getNet() == null) {
            return false;
        }
        for (EDIFHierPortInst p : hn.getLeafHierPortInsts(false, true)) {
            if (p.getPortInst().getCellInst() == self && p.getPortInst().getName().equals(selfInput)) continue;
            return true;
        }
        return false;
    }

    /**
     * Whether a clock input of a generator is selected: CLKIN1 and CLKIN2 both are, unless CLKINSEL is constant
     * (a tie-off or set_case_analysis value), 1 selecting CLKIN1 and 0 CLKIN2, as Vivado derives the clocks of
     * the selected input only.
     */
    private boolean isSelectedInput(EDIFHierCellInst cell, EDIFCellInst ci, String port) {
        if (!port.equals("CLKIN1") && !port.equals("CLKIN2")) {
            return true;
        }
        int sel = getPinConstant(cell, "CLKINSEL");
        if (sel < 0) {
            return true;
        }
        return (sel != 0 ^ isPinInverted(ci, "CLKINSEL")) == port.equals("CLKIN1");
    }

    /**
     * Whether the phase shifts of a generator are latencies (PHASESHIFT_MODE LATENCY) rather than shifts of the
     * output waveforms (WAVEFORM): the attribute's value, else the default of the generator, WAVEFORM for the
     * MMCM and PLL of 7 series and UltraScale devices (MMCME2, PLLE2, MMCME3, PLLE3), LATENCY otherwise.
     */
    static boolean isLatencyPhaseShift(EDIFCellInst ci, String type) {
        String mode = getString(ci, "PHASESHIFT_MODE");
        if (mode != null) {
            return mode.equalsIgnoreCase("LATENCY");
        }
        return !(type.startsWith("MMCME2") || type.startsWith("PLLE2") || type.startsWith("MMCME3")
                || type.startsWith("PLLE3"));
    }

    /** Whether a pin of a clock generator is an input of its digital deskew (CLKINn_DESKEW, CLKFBn_DESKEW) */
    static boolean isDeskewPin(String port) {
        if (port.length() != 13 || !port.endsWith("_DESKEW")) {
            return false;
        }
        char n = port.charAt(5);
        return (n == '1' || n == '2') && (port.startsWith("CLKIN") || port.startsWith("CLKFB"));
    }

    /** The feedback multiplier of a clock generator: CLKFBOUT_MULT_F, else CLKFBOUT_MULT (+ CLKFBOUT_FRACT / 64) */
    private static double getGeneratorMultiplier(EDIFCellInst ci) {
        double mult = getNumber(ci, "CLKFBOUT_MULT_F", Double.NaN);
        if (Double.isNaN(mult)) {
            mult = getNumber(ci, "CLKFBOUT_MULT", Double.NaN);
            double fract = getNumber(ci, "CLKFBOUT_FRACT", 0);
            if (!Double.isNaN(mult) && fract != 0) {
                mult += fract / 64.0;
            }
        }
        return mult;
    }

    /**
     * The divide of a CLKOUTn output of a clock generator (n being the output without its B suffix):
     * CLKOUTn_DIVIDE(_F), times CLKOUT6_DIVIDE for a cascaded CLKOUT4, times 4 for the extra divide of an XPLL's
     * CLKOUT0-3.
     * @param ci The generator.
     * @param base The output, e.g. CLKOUT0.
     * @return The divide, and the divide before the XPLL's extra divide by 4 (NaN without it).
     */
    private static double[] getOutputDivide(EDIFCellInst ci, String base) {
        double divide = getNumber(ci, base + "_DIVIDE_F", Double.NaN);
        if (Double.isNaN(divide)) {
            divide = getNumber(ci, base + "_DIVIDE", 1);
        }
        if (base.equals("CLKOUT4") && "TRUE".equalsIgnoreCase(getString(ci, "CLKOUT4_CASCADE"))) {
            // The CLKOUT6 counter feeds the CLKOUT4 counter (MMCM counter cascading)
            double d6 = getNumber(ci, "CLKOUT6_DIVIDE", 1);
            divide *= d6 > 0 ? d6 : 1;
        }
        double phaseDivide = Double.NaN;
        // XPLL: optional extra divide by 4 of CLKOUT0-2 or CLKOUT3
        String div4 = base.equals("CLKOUT3") ? "DIV4_CLKOUT3" : "DIV4_CLKOUT012";
        if (base.matches("CLKOUT[0-3]") && getNumber(ci, div4, 0) != 0) {
            phaseDivide = divide;
            divide *= 4;
        }
        return new double[]{divide, phaseDivide};
    }

    private void deriveGeneratorClocks(EDIFHierCellInst cell, EDIFCellInst ci, String type, EDIFHierPortInst inPin,
                                       Clock master, boolean inputInverted, int stage) {
        String cellName = cell.getFullHierarchicalInstName();
        double mult = getGeneratorMultiplier(ci);
        if (Double.isNaN(mult) || mult <= 0) {
            warn("clock generator without CLKFBOUT_MULT (no derived clocks): " + cellName);
            return;
        }
        double divclk = getNumber(ci, "DIVCLK_DIVIDE", 1);
        boolean latencyMode = isLatencyPhaseShift(ci, type);
        // An inverted input: the outputs' edges follow the input clock's falling edge
        double[] mw = master.waveform();
        double inputEdge = inputInverted ? mw[1] - mw[0] : 0;
        for (EDIFPortInst out : ci.getPortInsts()) {
            if (!out.isOutput()) continue;
            String pin = out.getName();
            if (!pin.startsWith("CLKOUT") && !pin.startsWith("CLKFBOUT")) continue;
            EDIFHierPortInst outPin = new EDIFHierPortInst(cell.getParent(), out);
            String outKey = outPin.toString();
            boolean userClock = userClockPins.contains(outKey);
            boolean fb = pin.startsWith("CLKFBOUT");
            boolean phy = pin.startsWith("CLKOUTPHY");
            if (!drivesLoads(outPin, ci, "CLKFBIN") || pin.contains("CASC") || phy && type.startsWith("PLLE")) {
                continue;
            }
            boolean inverted = pin.endsWith("B") && !pin.equals("CLKFBOUT") ? true : pin.equals("CLKFBOUTB");
            String base = inverted ? pin.substring(0, pin.length() - 1) : pin;
            double divide;
            double duty;
            double phase;
            // The output divider the phase shift is in units of (XPLL: before the extra divide by 4)
            double phaseDivide = Double.NaN;
            if (fb) {
                divide = mult;
                duty = 0.5;
                phase = getNumber(ci, "CLKFBOUT_PHASE", 0);
            } else if (phy) {
                // XPLL CLKOUTPHY, X5PLL CLKOUTPHY_0/_90: the VCO divided by CLKOUTPHY_DIVIDE (DIVn), shifted
                // by the suffix in degrees
                String div = getString(ci, "CLKOUTPHY_DIVIDE");
                divide = div == null ? 1 : parseNumber(div.replaceAll("[^0-9.]", ""), 1);
                duty = 0.5;
                phase = pin.startsWith("CLKOUTPHY_") ? parseNumber(pin.substring("CLKOUTPHY_".length()), 0) : 0;
            } else {
                double[] d = getOutputDivide(ci, base);
                divide = d[0];
                phaseDivide = d[1];
                duty = getNumber(ci, base + "_DUTY_CYCLE", 0.5);
                phase = getNumber(ci, base + "_PHASE", 0);
            }
            if (divide <= 0) continue;
            // (The VCO frequency of the input clock's period, as Vivado computes the discrete jitter)
            double[] settings = {1e6 * mult / (master.getPeriod() * divclk), mult, divide};
            generatorOutputSettings.putIfAbsent(outKey, settings);
            generatorOutputSettings.putIfAbsent(outKey + "|" + master.getId(), settings);
            if (userClock) continue;
            String key = cellName + "|" + pin + "|" + master.getId() + (inputInverted ? "|inverted" : "");
            if (!derived.add(key)) continue;
            double period = master.getPeriod() * divclk * divide / mult;
            double shift = phase / 360.0 * (Double.isNaN(phaseDivide) ? period
                    : master.getPeriod() * divclk * phaseDivide / mult);
            double rise = inputEdge + (latencyMode ? 0 : shift);
            double[] wave = inverted ? new double[]{rise + period * duty, rise + period}
                    : new double[]{rise, rise + period * duty};
            double latency = master.getSourceLatency() + (latencyMode ? shift : 0);
            Clock c = addDerived(cellName, type, pin, outPin, outPin.getHierarchicalNet(), master, period, wave,
                    latency, StageKind.GENERATOR, inPin, stage);
            if (c != null) {
                c.setGeneratorSettings(settings[0], settings[1], settings[2]);
            }
        }
    }

    /**
     * Adds an auto-derived clock on an output pin of a cell, named by the create_generated_clock renaming it,
     * else as the design names it (TOOL_DERIVED_CLK_NAMES), else after the net it drives, and propagates it
     * from a stage below that of the cell's input pin.
     * @return The clock.
     */
    private Clock addDerived(String cellName, String type, String pin, EDIFHierPortInst outPin, EDIFHierNet hn,
                             Clock master, double period, double[] wave, double latency, StageKind kind,
                             EDIFHierPortInst inPin, int stage) {
        return addDerived(cellName, type, pin, outPin.toString(), null, outPin, hn, master, period, wave, latency,
                kind, inPin, stage);
    }

    /**
     * Adds an auto-derived clock, see {@link #addDerived(String, String, String, EDIFHierPortInst, EDIFHierNet,
     * Clock, double, double[], double, StageKind, EDIFHierPortInst, int)}.
     * @param pin The pin of the cell the clock is defined on (an output pin, or an internal pin of the cell).
     * @param pinName The full name of that pin, which a create_generated_clock renaming the clock names.
     * @param defaultName The name of the clock if neither renamed nor named by the design, made unique with _1,
     * _2, ...; null to name it after the net the output pin drives.
     * @param outPin The output pin the clock propagates from.
     */
    private Clock addDerived(String cellName, String type, String pin, String pinName, String defaultName,
                             EDIFHierPortInst outPin, EDIFHierNet hn, Clock master, double period, double[] wave,
                             double latency, StageKind kind, EDIFHierPortInst inPin, int stage) {
        String outKey = outPin.toString();
        String name = null;
        int defIndex = master.getDefIndex();
        TimingConstraints.GeneratedClockDef rename = renames.get(pinName);
        if (rename != null) {
            name = rename.name;
            defIndex = Math.max(defIndex, rename.index);
            derived.add("rename:" + pinName);
        }
        if (name == null) {
            name = constraints.getDerivedClockName(cellName, pin, master.getName());
        }
        if (name == null || byName.containsKey(name)) {
            name = defaultName != null ? uniqueName(defaultName) : derivedName(outPin, pin);
        }
        Clock c = addClock(name, period, wave, master, true, true, Collections.singletonList(outKey), latency,
                defIndex, cellName, type);
        int e = 2 * c.getId();
        addPinEdge(outKey, e);
        int s = getStage(outKey + "|" + stage, kind, outPin, null, hn, stage, inPin);
        if (stages.get(s).derivedClock < 0) {
            stages.get(s).derivedClock = c.getId();
        }
        push(s, e, 0);
        return c;
    }

    private void deriveDividerClocks(EDIFHierCellInst cell, EDIFCellInst ci, String type, EDIFHierPortInst inPin,
                                     int edge, int stage) {
        Clock master = clocks.get(edge >> 1);
        String cellName = cell.getFullHierarchicalInstName();
        for (EDIFPortInst out : ci.getPortInsts()) {
            if (!out.isOutput()) continue;
            String pin = out.getName();
            int divide;
            if (type.equals("BUFGCE_DIV")) {
                if (!pin.equals("O")) continue;
                divide = (int) getNumber(ci, "BUFGCE_DIVIDE", 1);
            } else if (type.startsWith("BUFG_GT")) {
                if (!pin.equals("O")) continue;
                divide = getBufgGtDiv(cell, ci) + 1;
            } else if (type.startsWith("IBUFDS_GTE")) {
                if (!pin.equals("ODIV2")) continue;
                divide = 2;
            } else {
                // MBUFGCE*, MBUFG_GT: O1 to O4 divide by 1, 2, 4 and 8, after the BUFGCE_DIVIDE of an MBUFGCE_DIV
                // or the DIV + 1 of an MBUFG_GT
                if (!pin.matches("O[1-4]")) continue;
                divide = 1 << (pin.charAt(1) - '1');
                if (type.equals("MBUFGCE_DIV")) {
                    divide *= Math.max(1, (int) getNumber(ci, "BUFGCE_DIVIDE", 1));
                } else if (type.equals("MBUFG_GT")) {
                    int div = getBufgGtDiv(cell, ci);
                    if (div < 0) {
                        warn("MBUFG_GT with a DIV that is not constant (its clocks are not divided by it)");
                    }
                    divide *= Math.max(0, div) + 1;
                }
            }
            if (divide <= 1 && type.equals("BUFGCE_DIV")) {
                // (MBUFGCE* derive a clock on O1 too)
                propagateOut(cell, out, edge, stage, inPin, StageKind.DIVIDER, 0);
                continue;
            }
            EDIFHierPortInst outPin = new EDIFHierPortInst(cell.getParent(), out);
            String outKey = outPin.toString();
            if (userClockPins.contains(outKey)) continue;
            String key = cellName + "|" + pin + "|" + master.getId() + "|" + (edge & 1);
            if (!derived.add(key)) continue;
            EDIFHierNet hn = outPin.getHierarchicalNet();
            if (hn == null || hn.getNet() == null) continue;
            // Divide by N: master edges {1, N+1, 2N+1} (of the edges of the clock's sense) for N = 1 or an even N,
            // {1, N, 2N+1} for an odd N > 1 (high for (N-1)/2 periods, as Vivado derives them)
            double[] mw = master.waveform();
            double rise0 = (edge & 1) == 0 ? mw[0] : mw[1];
            int fall = divide % 2 == 0 || divide == 1 ? divide + 1 : divide;
            double[] wave = new double[]{rise0, masterEdgeTime(master, fall, (edge & 1) != 0)};
            double period = master.getPeriod() * divide;
            addDerived(cellName, type, pin, outPin, hn, master, period, wave, master.getSourceLatency(),
                    StageKind.DIVIDER, inPin, stage);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Versal XPHY read clocks
    // ------------------------------------------------------------------------------------------

    /** The XPHY input the strobe of a nibble arrives by */
    private static final String XPHY_STROBE_PIN = "DATAIN[0]";
    /** The XPHY internal pin (not in the netlist) Vivado defines the read clock on, for RX_DATA_WIDTH 8 */
    private static final String XPHY_READ_CLOCK_PIN = "RX_DQS_DIV4_0";
    /** The XPHY output the read clock leaves by */
    private static final String XPHY_READ_CLOCK_OUT = "FIFO_WR_CLK";
    /** The strobes reached only by inverted clocks so far, by "XPHY cell|clock id" */
    private final Set<String> xphyInvertedStrobes = new LinkedHashSet<>();

    /**
     * A clock reaching the strobe input of a Versal XPHY nibble (DATAIN[0]): derives the nibble's read clock on
     * FIFO_WR_CLK, if that output drives loads (e.g. fabric registers through a BUFGCE). Vivado derives it from the
     * strobe's clock on the XPHY's internal pin RX_DQS_DIV4_0 (not in the netlist), which reaches FIFO_WR_CLK:
     * the strobe divided by 4, master edges {3 8 11} shifted by {T/2 0 T/2} (T the strobe's period), i.e. a period
     * of 4 T and the waveform {1.5 T, 3.5 T} for a strobe of waveform {0, T/2}, named {@code <cell>/RX_DQS_DIV4_0}.
     * The clock is derived here only for the configuration this was checked for against Vivado's clocks and
     * endpoints, and with a warning per XPHY otherwise (see {@link #xphyReadClockUnsupported}); only from the
     * rising edges of the strobe's clock (a non-inverted clock at DATAIN[0]). A clock defined on FIFO_WR_CLK by the
     * constraints replaces it, as on any pin.
     */
    private void deriveXphyReadClock(EDIFHierCellInst cell, EDIFCellInst ci, EDIFHierPortInst inPin, int edge,
                                     int stage) {
        EDIFPortInst out = ci.getPortInst(XPHY_READ_CLOCK_OUT);
        if (out == null) {
            return;
        }
        EDIFHierPortInst outPin = new EDIFHierPortInst(cell.getParent(), out);
        if (userClockPins.contains(outPin.toString()) || !drivesLoads(outPin, ci, "")) {
            return;
        }
        String cellName = cell.getFullHierarchicalInstName();
        String unsupported = xphyReadClockUnsupported(ci);
        if (unsupported != null) {
            if (derived.add(cellName + "|" + XPHY_READ_CLOCK_OUT + "|unsupported")) {
                warn("XPHY read clock not derived, configuration not supported (" + unsupported + "): " + cellName);
            }
            return;
        }
        Clock master = clocks.get(edge >> 1);
        if ((edge & 1) != 0) {
            xphyInvertedStrobes.add(cellName + "|" + master.getId());
            return;
        }
        if (!derived.add(cellName + "|" + XPHY_READ_CLOCK_OUT + "|" + master.getId())) {
            return;
        }
        double t = master.getPeriod();
        double rise = masterEdgeTime(master, 3, false) + t / 2;
        double fall = masterEdgeTime(master, 8, false);
        double period = masterEdgeTime(master, 11, false) + t / 2 - rise;
        double off = Math.floor(rise / period) * period;
        double[] wave = new double[]{rise - off, fall - off};
        addStageSink(stage, inPin);
        addDerived(cellName, ci.getCellType().getName(), XPHY_READ_CLOCK_PIN, cellName + "/" + XPHY_READ_CLOCK_PIN,
                cellName + "/" + XPHY_READ_CLOCK_PIN, outPin, outPin.getHierarchicalNet(), master, period, wave,
                master.getSourceLatency(), StageKind.DIVIDER, inPin, stage);
    }

    /**
     * Why the read clock of an XPHY is not derived: a configuration other than the one checked against Vivado,
     * where the nibble's own strobe (DQS_SRC LOCAL, no inter-nibble clocking: EN_OTHER_PCLK / EN_OTHER_NCLK FALSE)
     * clocks its read FIFOs in BYPASS mode (FIFO_MODE_0 to FIFO_MODE_5), in RX_DATA_WIDTH 8, neither in serial mode
     * nor inverted (SERIAL_MODE, INV_RXCLK FALSE). An absent SERIAL_MODE, INV_RXCLK, EN_OTHER_PCLK, EN_OTHER_NCLK
     * or RX_DATA_WIDTH is taken as its default (FALSE, 8); an absent DQS_SRC or FIFO_MODE_n is not supported (the
     * default of FIFO_MODE_n is ASYNC).
     * @return The attribute and its value, or null if supported.
     */
    private static String xphyReadClockUnsupported(EDIFCellInst ci) {
        for (String a : new String[]{"SERIAL_MODE", "INV_RXCLK", "EN_OTHER_PCLK", "EN_OTHER_NCLK"}) {
            String v = getString(ci, a);
            if (v != null && !v.equalsIgnoreCase("FALSE")) {
                return a + " " + v;
            }
        }
        String width = getString(ci, "RX_DATA_WIDTH");
        if (width != null && parseNumber(width, Double.NaN) != 8) {
            return "RX_DATA_WIDTH " + width;
        }
        for (int n = 0; n <= 5; n++) {
            String v = getString(ci, "FIFO_MODE_" + n);
            if (!"BYPASS".equalsIgnoreCase(v)) {
                return "FIFO_MODE_" + n + " " + v;
            }
        }
        String src = getString(ci, "DQS_SRC");
        if (!"LOCAL".equalsIgnoreCase(src)) {
            return "DQS_SRC " + src;
        }
        return null;
    }

    /** Warns about the XPHY strobes that only inverted clocks reach (no read clock derived from them). */
    private void warnInvertedXphyStrobes() {
        for (String s : xphyInvertedStrobes) {
            int sep = s.lastIndexOf('|');
            String cellName = s.substring(0, sep);
            if (!derived.contains(cellName + "|" + XPHY_READ_CLOCK_OUT + "|" + s.substring(sep + 1))) {
                warn("XPHY strobe reached by an inverted clock only (no read clock derived): " + cellName);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // GT transceiver clocks
    // ------------------------------------------------------------------------------------------

    /**
     * Gets the constant value of an input pin of a cell: its set_case_analysis value, else its
     * tie-off (a net from GND or VCC).
     * @return 0 or 1, or -1 if not constant.
     */
    int getPinConstant(EDIFHierCellInst cell, String pin) {
        int cv = constraints.getCaseValue(cell.getFullHierarchicalInstName() + "/" + pin);
        if (cv >= 0) {
            return cv;
        }
        EDIFPortInst pi = cell.getInst().getPortInst(pin);
        if (pi == null || pi.getNet() == null) {
            return -1;
        }
        if (pi.getNet().isGND()) return 0;
        if (pi.getNet().isVCC()) return 1;
        EDIFHierNet hn = new EDIFHierPortInst(cell.getParent(), pi).getHierarchicalNet();
        EDIFHierPortInst src = hn == null ? null : hn.getLeafSourcePortInst();
        EDIFCellInst sci = src == null ? null : src.getPortInst().getCellInst();
        String type = sci == null ? "" : sci.getCellType().getName();
        return type.equals("GND") ? 0 : type.equals("VCC") ? 1 : -1;
    }

    /** @return The value of a bus of constant input bits ({@code name[bits-1..0]}), -1 if any is not constant. */
    int getBusConstant(EDIFHierCellInst cell, String name, int bits) {
        int v = 0;
        for (int b = bits - 1; b >= 0; b--) {
            int t = getPinConstant(cell, name + "[" + b + "]");
            if (t < 0) {
                return -1;
            }
            v = (v << 1) | t;
        }
        return v;
    }

    /**
     * Gets the DIV[2:0] of a BUFG_GT from tie-offs and case analysis; bits driven by the BUFGTDIV
     * outputs of a GT channel count as 0, with a warning.
     * @return The value, -1 if a bit is not constant.
     */
    int getBufgGtDiv(EDIFHierCellInst cell, EDIFCellInst ci) {
        int v = getBusConstant(cell, "DIV", 3);
        if (v >= 0) {
            return v;
        }
        v = 0;
        boolean fromGt = false;
        for (int b = 2; b >= 0; b--) {
            int one = getPinConstant(cell, "DIV[" + b + "]");
            if (one >= 0) {
                v = (v << 1) | one;
                continue;
            }
            EDIFPortInst pi = ci.getPortInst("DIV[" + b + "]");
            EDIFHierNet hn = pi == null ? null : new EDIFHierPortInst(cell.getParent(), pi).getHierarchicalNet();
            EDIFHierPortInst src = hn == null ? null : hn.getLeafSourcePortInst();
            if (src == null || !src.getPortInst().getName().startsWith("BUFGTDIV")
                    || !GtClocks.isChannel(src.getPortInst().getCellInst().getCellType().getName())) {
                return -1;
            }
            fromGt = true;
            v <<= 1;
        }
        if (fromGt) {
            warn("BUFG_GT DIV driven by GT BUFGTDIV outputs taken as 0");
        }
        return v;
    }

    /** The cell driving a channel's QPLL0CLK / QPLL1CLK input if it is a GT common, else null */
    private static EDIFHierCellInst gtCommonOf(EDIFHierCellInst ch, int qpll) {
        EDIFPortInst pi = ch.getInst().getPortInst("QPLL" + qpll + "CLK");
        if (pi == null) return null;
        EDIFHierNet hn = new EDIFHierPortInst(ch.getParent(), pi).getHierarchicalNet();
        EDIFHierPortInst src = hn == null ? null : hn.getLeafSourcePortInst();
        if (src == null || !GtClocks.isCommon(src.getPortInst().getCellInst().getCellType().getName())) {
            return null;
        }
        return src.getHierarchicalInst().getChild(src.getPortInst().getCellInst());
    }

    /**
     * Gets the PLL of one side of a GT channel: TX/RXPLLCLKSEL (00 CPLL, 11 QPLL0, 10 QPLL1), else the
     * QPLL a common drives (QPLL0 first), else the CPLL.
     * @return 0 or 1 for QPLL0 or QPLL1, -1 for the CPLL.
     */
    private int gtPllOf(EDIFHierCellInst ch, String side) {
        int sel = getBusConstant(ch, side + "PLLCLKSEL", 2);
        if (sel == 0) return -1;
        if (sel == 3) return 0;
        if (sel == 2) return 1;
        return gtCommonOf(ch, 0) != null ? 0 : gtCommonOf(ch, 1) != null ? 1 : -1;
    }

    /**
     * The frequency (GHz) of a channel's TX or RX output clock from its reference frequency, NaN if unknown:
     * the *OUTCLKSEL selection, or the PCS parallel clock (the *OUTCLKPCS output) if pcs.
     */
    private double gtOutputFrequency(EDIFHierCellInst ch, String side, double fref, boolean pcs) {
        EDIFCellInst ci = ch.getInst();
        int pll = gtPllOf(ch, side);
        double fpll;
        if (pll < 0) {
            double n2 = getNumber(ci, "CPLL_FBDIV", Double.NaN);
            double n1 = getNumber(ci, "CPLL_FBDIV_45", Double.NaN);
            if (Double.isNaN(n2) || Double.isNaN(n1)) {
                warn("GT channel without CPLL_FBDIV / CPLL_FBDIV_45 (defaults used)");
            }
            fpll = GtClocks.pllFrequency(fref, false, n2, n1, getNumber(ci, "CPLL_REFCLK_DIV", Double.NaN), true);
        } else {
            EDIFHierCellInst cm = gtCommonOf(ch, pll);
            if (cm == null) return Double.NaN;
            double n = getNumber(cm.getInst(), "QPLL" + pll + "_FBDIV", Double.NaN);
            if (Double.isNaN(n)) return Double.NaN;
            fpll = GtClocks.pllFrequency(fref, true, n, Double.NaN,
                    getNumber(cm.getInst(), "QPLL" + pll + "_REFCLK_DIV", Double.NaN),
                    "FULL".equals(getString(cm.getInst(), "QPLL" + pll + "CLKOUT_RATE")));
        }
        return GtClocks.outputFrequency(fpll, fref, getNumber(ci, side + "OUT_DIV", Double.NaN),
                getNumber(ci, side + "_PROGDIV_CFG", Double.NaN), getNumber(ci, side + "_PROGDIV_RATE", Double.NaN),
                getNumber(ci, side + "_DATA_WIDTH", Double.NaN), getNumber(ci, side + "_INT_DATAWIDTH", Double.NaN),
                pcs ? 1 : getBusConstant(ch, side + "OUTCLKSEL", 3));
    }

    /**
     * A reference clock reaching a GT common or channel: derives the TXOUTCLK / RXOUTCLK of the channels
     * whose PLL it feeds (the common's QPLLs by QPLL0/1REFCLKSEL, a channel's CPLL by CPLLREFCLKSEL).
     */
    private void deriveGt(EDIFHierCellInst cell, EDIFCellInst ci, String type, EDIFHierPortInst inPin, Clock ref,
                          int stage) {
        String pin = inPin.getPortInst().getName().toUpperCase();
        if (GtClocks.isChannel(type)) {
            if (GtClocks.refclkFeeds(pin, getBusConstant(cell, "CPLLREFCLKSEL", 3), "")) {
                deriveGtChannel(cell, ref, -1, inPin, stage);
            }
            return;
        }
        for (int q = 0; q < 2; q++) {
            if (!GtClocks.refclkFeeds(pin, getBusConstant(cell, "QPLL" + q + "REFCLKSEL", 3), String.valueOf(q))) {
                continue;
            }
            EDIFPortInst out = ci.getPortInst("QPLL" + q + "OUTCLK");
            EDIFHierNet hn = out == null ? null : new EDIFHierPortInst(cell.getParent(), out).getHierarchicalNet();
            if (hn == null || hn.getNet() == null) continue;
            for (EDIFHierPortInst p : hn.getLeafHierPortInsts(false, true)) {
                EDIFCellInst chi = p.getPortInst().getCellInst();
                if (chi != null && GtClocks.isChannel(chi.getCellType().getName())) {
                    deriveGtChannel(p.getHierarchicalInst().getChild(chi), ref, q, inPin, stage);
                }
            }
        }
    }

    /**
     * A reference clock reaching a Versal GT quad: derives the CHn_TXOUTCLK / CHn_RXOUTCLK whose
     * CHn_*OUTCLK_REF_SOURCE is that input, their period being the reference clock's scaled by
     * CHn_*OUTCLK_REF_FREQ / CHn_*OUTCLK_FREQ.
     */
    private void deriveGtQuad(EDIFHierCellInst cell, EDIFCellInst ci, EDIFHierPortInst inPin, Clock ref,
                              int stage) {
        String in = inPin.getPortInst().getName();
        for (int n = 0; n < 4; n++) {
            for (String side : new String[]{"TX", "RX"}) {
                String attr = "CH" + n + "_" + side + "OUTCLK";
                if (!in.equals(getString(ci, attr + "_REF_SOURCE"))) continue;
                double fout = getNumber(ci, attr + "_FREQ", Double.NaN);
                double fref = getNumber(ci, attr + "_REF_FREQ", Double.NaN);
                if (!(fout > 0) || !(fref > 0)) {
                    warn("GT quad output clock without its frequencies (no clock, define it in the XDC)");
                    continue;
                }
                addGtClock(cell, attr, ref, ref.getPeriod() * fref / fout, inPin, stage);
            }
        }
    }

    /** Adds the clock of a GT output pin, of a given period, generated from a reference clock */
    private void addGtClock(EDIFHierCellInst cell, String pin, Clock ref, double period, EDIFHierPortInst inPin,
                            int stage) {
        String cellName = cell.getFullHierarchicalInstName();
        EDIFPortInst out = cell.getInst().getPortInst(pin);
        if (out == null) return;
        EDIFHierPortInst outPin = new EDIFHierPortInst(cell.getParent(), out);
        String outKey = outPin.toString();
        if (userClockPins.contains(outKey) || !drivesLoads(outPin, cell.getInst(), "")) return;
        if (!derived.add(cellName + "|" + pin + "|" + ref.getId())) return;
        Clock c = addDerived(cellName, cell.getCellType().getName(), pin, outPin, outPin.getHierarchicalNet(), ref,
                period, new double[]{0, period / 2}, ref.getSourceLatency(), StageKind.DIVIDER, inPin, stage);
        c.setKind("GT");
    }

    /**
     * Derives the TXOUTCLK / RXOUTCLK (and the TXOUTCLKPCS / RXOUTCLKPCS PCS parallel clocks) of a channel
     * whose PLL is pll (-1 CPLL, 0/1 QPLL) from a reference clock
     */
    private void deriveGtChannel(EDIFHierCellInst ch, Clock ref, int pll, EDIFHierPortInst inPin, int stage) {
        for (String side : new String[]{"TX", "RX"}) {
            if (gtPllOf(ch, side) != pll) continue;
            for (boolean pcs : new boolean[]{false, true}) {
                String pin = side + (pcs ? "OUTCLKPCS" : "OUTCLK");
                EDIFPortInst out = ch.getInst().getPortInst(pin);
                if (out == null || !drivesLoads(new EDIFHierPortInst(ch.getParent(), out), ch.getInst(), "")) continue;
                double f = gtOutputFrequency(ch, side, 1000.0 / ref.getPeriod(), pcs);
                if (!(f > 0)) {
                    warn("GT output clock whose frequency the attributes do not determine (no clock, define it in "
                            + "the XDC)");
                    continue;
                }
                addGtClock(ch, pin, ref, 1000.0 / f, inPin, stage);
            }
        }
    }

    /**
     * Gets the time of an edge of a clock: edge 1 is its first rising edge (its first falling edge if
     * inverted), edges alternating rise and fall.
     */
    static double masterEdgeTime(Clock c, int k, boolean inverted) {
        double[] w = c.waveform();
        int n = w.length;
        int i = k - 1 + (inverted ? 1 : 0);
        int cycle = i / n;
        return w[i % n] + cycle * c.getPeriod();
    }

    /**
     * The master clock of a generated clock's -master_clock: its get_clocks query (a name pattern, -regexp,
     * or -of_objects), the clock on its source if several match, null if none is known yet.
     */
    private Clock masterClockOf(TimingConstraints.GeneratedClockDef gd) {
        XdcObject q = gd.masterClockQuery;
        if (q == null) {
            return byName.get(gd.masterClock);
        }
        List<Clock> matched = new ArrayList<>();
        if (q.getClockOf() != null) {
            for (XdcObject of : q.getClockOf()) {
                for (int e : clockEdgesOfObject(of)) {
                    Clock c = clocks.get(e >> 1);
                    if (!matched.contains(c)) matched.add(c);
                }
            }
        } else {
            for (Clock c : clocks) {
                if (q.matchesClockName(c.getName())) matched.add(c);
            }
        }
        if (matched.size() > 1 && gd.source != null) {
            for (int e : clockEdgesOfObject(gd.source)) {
                Clock c = clocks.get(e >> 1);
                if (matched.contains(c)) return c;
            }
        }
        return matched.isEmpty() ? null : matched.get(0);
    }

    /** Creates a user generated clock; returns false if its master is not known yet. */
    private boolean createGenerated(TimingConstraints.GeneratedClockDef gd) {
        Clock master = null;
        boolean masterInverted = false;
        if (gd.masterClock != null) {
            master = masterClockOf(gd);
            if (master == null) {
                return false;
            }
            if (gd.source != null) {
                for (int e : clockEdgesOfObject(gd.source)) {
                    if ((e >> 1) == master.getId()) {
                        masterInverted = (e & 1) != 0;
                        break;
                    }
                }
            }
        } else if (gd.source != null) {
            List<Integer> edges = clockEdgesOfObject(gd.source);
            if (edges.isEmpty()) {
                return false;
            }
            master = clocks.get(edges.get(0) >> 1);
            masterInverted = (edges.get(0) & 1) != 0;
        } else {
            return false;
        }
        double period;
        double[] wave;
        if (gd.edges != null && gd.edges.length >= 3) {
            // An odd number of master edges: rise, fall, ..., rise; the last one starts the next period
            double[] t = new double[gd.edges.length];
            for (int i = 0; i < t.length; i++) {
                t[i] = masterEdgeTime(master, gd.edges[i], masterInverted)
                        + (gd.edgeShift != null && i < gd.edgeShift.length ? gd.edgeShift[i] * 1000 : 0);
            }
            period = t[t.length - 1] - t[0];
            wave = Arrays.copyOf(t, t.length - 1);
            if (!(period > 0)) {
                warn("create_generated_clock with -edges/-edge_shift making no period (ignored)");
                return true;
            }
        } else {
            int div = gd.divideBy > 0 ? gd.divideBy : 1;
            int mul = gd.multiplyBy > 0 ? gd.multiplyBy : 1;
            if (gd.divideBy > 0 && gd.multiplyBy <= 0) {
                period = master.getPeriod() * div;
                double r = masterEdgeTime(master, 1, masterInverted);
                wave = new double[]{r, masterEdgeTime(master, 1 + div, masterInverted)};
            } else {
                period = master.getPeriod() * div / mul;
                double r = masterEdgeTime(master, 1, masterInverted);
                double f = masterEdgeTime(master, 2, masterInverted);
                double scale = (double) div / mul;
                wave = new double[]{r * scale, f * scale};
            }
            if (!Double.isNaN(gd.dutyCycle)) {
                wave[1] = wave[0] + period * gd.dutyCycle / 100.0;
            }
        }
        if (gd.invert) {
            // The falling edges become the rising ones
            double[] w = new double[wave.length];
            System.arraycopy(wave, 1, w, 0, wave.length - 1);
            w[wave.length - 1] = wave[0] + period;
            wave = w;
        }
        // Normalize the first edge into [0, period)
        double off = Math.floor(wave[0] / period) * period;
        for (int i = 0; i < wave.length; i++) {
            wave[i] -= off;
        }
        String name = gd.name != null ? gd.name : gd.targets.get(0).getName();
        List<String> src = new ArrayList<>();
        for (XdcObject t : gd.targets) {
            src.add(sourceKey(t));
        }
        Clock c = addClock(name, period, wave, master, true, false, src, master.getSourceLatency(), gd.index,
                null, null);
        setGeneratorOutputKind(c, gd.targets);
        for (XdcObject t : gd.targets) {
            startAt(t, c, StageKind.USER_PIN);
        }
        return true;
    }

    /**
     * Gives a user generated clock defined on a clock generator output the generator's kind, and records it for the
     * settings of that output from its master clock at the generator's input (see
     * {@link #adoptGeneratorOutputSettings()}): Vivado charges it the generator's discrete jitter, as it does the
     * clock it replaces.
     */
    private void setGeneratorOutputKind(Clock c, List<XdcObject> targets) {
        for (XdcObject t : targets) {
            EDIFHierPortInst pin = t.getKind() == XdcObject.Kind.PIN ? t.getPin() : null;
            EDIFCellInst ci = pin == null ? null : pin.getPortInst().getCellInst();
            String port = pin == null ? "" : pin.getPortInst().getName();
            if (ci == null || !pin.isOutput() || !isGenerator(ci.getCellType().getName())
                    || !port.startsWith("CLKOUT") && !port.startsWith("CLKFBOUT")) continue;
            c.setKind(getSourceKind(pin));
            userGeneratorOutputs.put(c, pin.toString());
            return;
        }
    }
}
