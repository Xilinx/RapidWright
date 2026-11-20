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

package com.xilinx.rapidwright.timing.sta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.timing.clock.Clock;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.clock.ClockNetwork;
import com.xilinx.rapidwright.timing.clock.ClockRelations;
import com.xilinx.rapidwright.timing.clock.TimingConstraints;
import com.xilinx.rapidwright.timing.clock.UncertaintyModel;
import com.xilinx.rapidwright.timing.clock.XdcObject;

/**
 * Binds the clocks of a design to the pins of its {@link StaGraph}, for {@link StaClockAnalysis}:
 * <ul>
 * <li>the clocks, their {@link ClockRelations}, {@link UncertaintyModel} and {@link ClockModel}
 * (insertion delays and CRPR of the clock pins);</li>
 * <li>the clock edges of each clock pin (see {@link ClockNetwork#getClockPinEdges(int)});</li>
 * <li>the launch clock pin of each startpoint, and the capture clock pin, setup time and hold time
 * of each endpoint (the timing checks, kept apart from the data-path CSR of the graph);</li>
 * <li>the path exceptions (false paths and multicycle paths), resolved to clocks and graph pins.</li>
 * </ul>
 * It is built from a design ({@link #build}), for a single clock ({@link #singleClock}), or by
 * hand (e.g. in tests).
 */
public final class StaClockBinding {

    /** The setup and hold times of the timing checks of endpoints */
    public interface CheckTimes {
        /**
         * @param dataPin The endpoint pin.
         * @param clockPin Its clock pin.
         * @return The setup time in ps (slow corner).
         */
        float getSetup(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin);

        /**
         * @param dataPin The endpoint pin.
         * @param clockPin Its clock pin.
         * @return The hold time in ps (fast corner).
         */
        float getHold(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin);

        /**
         * @param dataPin The endpoint pin.
         * @param clockPin Its clock pin.
         * @return The setup and hold times in ps, as {@link #getSetup} and {@link #getHold} give them.
         */
        default float[] getSetupHold(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
            return new float[]{getSetup(dataPin, clockPin), getHold(dataPin, clockPin)};
        }
    }

    /** No setup or hold times */
    public static final CheckTimes NO_CHECK_TIMES = new CheckTimes() {
        @Override
        public float getSetup(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
            return 0f;
        }

        @Override
        public float getHold(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
            return 0f;
        }
    };

    /** A false path or multicycle path, resolved to clocks and graph pins */
    static final class PathException {
        /** False path (true) or multicycle path */
        final boolean falsePath;
        final boolean setup;
        final boolean hold;
        final int multiplier;
        /** -start (true), -end (false) or default (null) */
        final Boolean start;
        /** -from clocks, null if none */
        final BitSet fromClocks;
        /** -from startpoints (graph pins), null if none */
        final Set<Integer> fromPins;
        /** -to clocks, null if none */
        final BitSet toClocks;
        /** -to endpoints (graph pins), null if none */
        final Set<Integer> toPins;
        /** Index in the constraints (later ones win ties) */
        final int index;

        PathException(boolean falsePath, boolean setup, boolean hold, int multiplier, Boolean start,
                             BitSet fromClocks, Set<Integer> fromPins, BitSet toClocks, Set<Integer> toPins,
                             int index) {
            this.falsePath = falsePath;
            this.setup = setup;
            this.hold = hold;
            this.multiplier = multiplier;
            this.start = start;
            this.fromClocks = fromClocks;
            this.fromPins = fromPins;
            this.toClocks = toClocks;
            this.toPins = toPins;
            this.index = index;
        }

        boolean anyFrom() {
            return fromClocks == null && fromPins == null;
        }

        boolean anyTo() {
            return toClocks == null && toPins == null;
        }

        /** Specificity, for precedence among multicycle paths: pins/cells over clocks over none */
        int specificity() {
            int f = fromPins != null ? 2 : fromClocks != null ? 1 : 0;
            int t = toPins != null ? 2 : toClocks != null ? 1 : 0;
            return f * 3 + t;
        }
    }

    final StaGraph g;
    final List<Clock> clocks;
    final ClockRelations relations;
    final UncertaintyModel uncertainty;
    ClockModel clockModel;
    /** The inter-SLR compensation factor of the checks, see {@link #setInterSlrProrate(float)} */
    private float interSlrProrate;
    final int[][] clockPinEdges;
    final String[] clockPinNames;
    /** The clock pins whose clock-to-output arcs are triggered by the falling edge, see {@link #setFallLaunch} */
    final boolean[] fallLaunch;

    /** Launch clock pin of each graph pin (startpoints), -1 if none */
    final int[] launchCpin;
    /** Capture clock pin of each graph pin (endpoints), -1 if none */
    final int[] captureCpin;
    /** Setup times of the endpoints, by graph pin (only read where captureCpin >= 0), null while all are 0 */
    private float[] setupTimes;
    /** Hold times of the endpoints, by graph pin, null while all are 0 */
    private float[] holdTimes;
    /** The hold times of the endpoints at the slow corner, by graph pin, null if not set */
    private float[] slowHoldTimes;
    final List<PathException> exceptions = new ArrayList<>();
    private final Map<String, Integer> warnings = new LinkedHashMap<>();
    /** Whether this binds a single ideal clock (see {@link #singleClock}), and the requirement given for it */
    private boolean single;
    private float singleClockRequirement;

    /**
     * Creates an empty binding (no startpoint or endpoint is clocked until set).
     * @param g The graph.
     * @param clocks The clocks, by id.
     * @param relations Their relations.
     * @param uncertainty Their uncertainties.
     * @param clockModel The insertion delays and CRPR of the clock pins.
     * @param clockPinEdges The clock edge ids of each clock pin (2 * clock + falling).
     */
    StaClockBinding(StaGraph g, List<Clock> clocks, ClockRelations relations, UncertaintyModel uncertainty,
                           ClockModel clockModel, int[][] clockPinEdges) {
        this.g = g;
        this.clocks = Collections.unmodifiableList(new ArrayList<>(clocks));
        this.relations = relations;
        this.uncertainty = uncertainty;
        this.clockModel = clockModel;
        this.clockPinEdges = clockPinEdges;
        this.fallLaunch = new boolean[clockPinEdges.length];
        this.clockPinNames = new String[clockPinEdges.length];
        launchCpin = new int[g.getNumPins()];
        captureCpin = new int[g.getNumPins()];
        Arrays.fill(launchCpin, -1);
        Arrays.fill(captureCpin, -1);
    }

    /**
     * Creates the binding of a single ideal clock of a given period to all the startpoints and
     * endpoints of a graph, with no insertion delay, uncertainty, setup or hold time: the arrival time
     * of a pin is the largest delay to it from a startpoint, an endpoint's setup slack the requirement
     * less its arrival time, and the criticalities are normalized to the larger of the requirement and
     * the largest arrival time. Without a requirement (a period of 0 or less, or NaN), the requirement is
     * virtual: the endpoints' required time is the largest arrival time (the clock's period is then the
     * smallest positive float, below any arrival time).
     * @param g The graph.
     * @param periodPs The clock period, in ps; 0 or less for none.
     * @return The binding.
     */
    public static StaClockBinding singleClock(StaGraph g, float periodPs) {
        float requirement = periodPs;
        if (!(periodPs > 0)) {
            // A zero period would leave no setup relation (and no criticality): the normalization
            // max(requirement, max arrival) makes the smallest positive period a virtual requirement
            periodPs = Float.MIN_VALUE;
        }
        Clock clk = Clock.createPrimary(0, "clk", periodPs, new double[]{0, periodPs / 2.0}, "clk");
        List<Clock> clocks = Collections.singletonList(clk);
        StaClockBinding b = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(1),
                ClockModel.ideal(1), new int[][]{{0}});
        b.clockPinNames[0] = "clk";
        b.single = true;
        b.singleClockRequirement = requirement;
        for (int p = 0; p < g.getNumPins(); p++) {
            if (g.isPinKind(p, StaGraph.PIN_START)) {
                b.launchCpin[p] = 0;
            }
            if (g.isPinKind(p, StaGraph.PIN_END)) {
                b.setCaptureClockPin(p, 0, 0f, 0f);
            }
        }
        return b;
    }

    /**
     * Binds the clocks of a design to its graph.
     * @param g The graph (built with a cell table, see {@link StaGraphBuilder}).
     * @param cn The design's clock network.
     * @param relations The relations of its clocks.
     * @param uncertainty The uncertainties of its clocks.
     * @param clockModel The insertion delays of its clock pins.
     * @param checkTimes The setup and hold times of the endpoints.
     * @return The binding.
     */
    public static StaClockBinding build(StaGraph g, ClockNetwork cn, ClockRelations relations,
                                        UncertaintyModel uncertainty, ClockModel clockModel, CheckTimes checkTimes) {
        int n = cn.getNumClockPins();
        int[][] edges = new int[n][];
        for (int i = 0; i < n; i++) {
            edges[i] = cn.getClockPinEdges(i);
        }
        StaClockBinding b = new StaClockBinding(g, cn.getClocks(), relations, uncertainty, clockModel, edges);
        for (int i = 0; i < n; i++) {
            b.clockPinNames[i] = cn.getClockPin(i).toString();
            EDIFHierPortInst cpin = cn.getClockPin(i);
            b.fallLaunch[i] = isFallTriggeredClockPin(cpin.getCellType().getName(), cpin.getPortInst().getName());
        }
        // The startpoints and endpoints of each cell by name, for the exceptions that name cells or pins
        Map<String, List<Integer>> pinsByCell = needsPinsByCell(cn) ? new HashMap<>() : null;
        boolean[] timedCells = getCellsWithArcs(g);
        // (The name of each cell, built once)
        String[] cellNames = g.cells == null ? null : new String[g.cells.length];
        for (int p = 0; p < g.getNumPins(); p++) {
            boolean start = g.isPinKind(p, StaGraph.PIN_START);
            // The endpoints: those of the graph, whose inputs with a setup check the arc provider marked
            boolean end = g.isPinKind(p, StaGraph.PIN_END);
            if (!start && !end) continue;
            EDIFHierCellInst cell = g.getCell(p);
            if (cell == null) continue;
            int cellIdx = StaGraph.getCellIndex(g.getPinKey(p));
            if (timedCells[cellIdx]) {
                // A cell with timing arcs: its inputs without fanout and its outputs without fanin are unused
                // (e.g. DSP inputs that a constant OPMODE or the register settings leave out), not timed as
                // Vivado does not; only its clock pins (with clock-to-output arcs) launch and its graph
                // endpoints capture
                start &= hasClockToOutArc(g, p);
                if (!start && !end) continue;
            } else if (start && drivesDedicatedOnly(g, p)) {
                // A cell without arcs (a hard block the arc data has none for) launches into the fabric,
                // not on its dedicated connections to other hard blocks (e.g. a DDR memory controller to
                // its PHY), which Vivado has no timing arcs for
                start = false;
                if (!end) continue;
            }
            EDIFPortInst pi = g.getPortInst(p);
            String portName = pi == null ? "" : pi.getName();
            String cellName = cellNames[cellIdx];
            if (cellName == null) {
                cellName = cell.getFullHierarchicalInstName();
                cellNames[cellIdx] = cellName;
            }
            // An endpoint's clock: that of its check if the arc provider gave it (a hard block's interface clock),
            // else by the pin's name
            String checkClock = end && !start ? g.getCheckClockPort(p) : null;
            int cp = checkClock == null ? -1 : cn.getClockPinOf(cellName, checkClock);
            if (cp < 0 || !cn.getClockPin(cp).getPortInst().getName().equals(checkClock)) {
                cp = cn.getClockPinOf(cellName, portName);
            }
            if (cp < 0) {
                if (end && g.isPinKind(p, StaGraph.PIN_END)) b.warn("endpoint without a clock (unconstrained)");
                if (start) b.warn("startpoint without a clock (unconstrained)");
                continue;
            }
            if (start) {
                b.launchCpin[p] = cp;
            }
            if (end) {
                EDIFHierPortInst dataPin = g.getHierPortInst(p);
                EDIFHierPortInst clkPin = cn.getClockPin(cp);
                float[] t = dataPin == null ? null : checkTimes.getSetupHold(dataPin, clkPin);
                b.setCaptureClockPin(p, cp, t == null ? 0f : t[0], t == null ? 0f : t[1]);
            }
            if (pinsByCell != null) {
                pinsByCell.computeIfAbsent(cellName, k -> new ArrayList<>()).add(p);
            }
        }
        b.resolveExceptions(cn, pinsByCell == null ? Collections.emptyMap() : pinsByCell);
        return b;
    }

    /** Whether an exception of a clock network's constraints may name cells or pins (see resolveExceptions()) */
    private static boolean needsPinsByCell(ClockNetwork cn) {
        for (TimingConstraints.PathExceptionDef e : cn.getConstraints().getExceptions()) {
            if (e.kind != TimingConstraints.ExceptionKind.FALSE_PATH || !ClockRelations.isClockOnly(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a pin only drives dedicated connections: routes from a site pin on a dedicated node (hard-wired
     * between hard blocks, e.g. XPHY to DDRMC), not through the programmable interconnect.
     */
    private static boolean drivesDedicatedOnly(StaGraph g, int p) {
        boolean any = false;
        for (int o = g.outStart[p]; o < g.outStart[p + 1]; o++) {
            int conn = g.getEdgeConnection(g.outEdge[o]);
            SitePinInst sink = conn < 0 ? null : g.getConnectionSink(conn);
            Net net = sink == null ? null : sink.getNet();
            SitePinInst source = net == null ? null : net.getSource();
            Node node = source == null ? null : source.getConnectedNode();
            if (node == null || node.getIntentCode() != IntentCode.NODE_DEDICATED) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Whether the clock-to-output arcs of a clock pin are triggered by its falling edge: the inverted clock
     * pins of the UltraScale+ input DDR registers and deserializers (IDDRE1 CB, ISERDESE3 CLK_B), whose
     * falling edge is the rising edge of their true clock (Vivado's fall-triggered CB -> Q1/Q2 arcs).
     */
    static boolean isFallTriggeredClockPin(String cellType, String port) {
        return cellType.equals("IDDRE1") && port.equals("CB") || cellType.equals("ISERDESE3") && port.equals("CLK_B");
    }

    /**
     * Sets whether the clock-to-output arcs of a clock pin are triggered by its falling edge (its launches
     * at the opposite edge of each of its clock edges).
     * @param clockPin The clock pin.
     * @param fall True for the falling edge.
     */
    void setFallLaunch(int clockPin, boolean fall) {
        fallLaunch[clockPin] = fall;
    }

    /** Whether a pin has a clock-to-output arc */
    private static boolean hasClockToOutArc(StaGraph g, int p) {
        for (int o = g.outStart[p]; o < g.outStart[p + 1]; o++) {
            if (g.getEdgeKind(g.outEdge[o]) == StaGraph.CLK_TO_Q) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cells that have timing arcs (combinational or clock-to-output) in a graph.
     * @return Whether each cell has, by cell index (empty if the graph has no cell table).
     */
    private static boolean[] getCellsWithArcs(StaGraph g) {
        boolean[] cells = new boolean[g.cells == null ? 0 : g.cells.length];
        if (g.cells == null) {
            return cells;
        }
        for (int e = 0; e < g.getNumEdges(); e++) {
            byte kind = g.getEdgeKind(e);
            if (kind == StaGraph.CELL_COMB || kind == StaGraph.CLK_TO_Q) {
                cells[StaGraph.getCellIndex(g.getPinKey(g.getEdgeFrom(e)))] = true;
            }
        }
        return cells;
    }

    /** Whether this is the binding of a single ideal clock (see {@link #singleClock(StaGraph, float)}) */
    boolean isSingleClock() {
        return single;
    }

    /** The requirement of a single ideal clock binding, as given to {@link #singleClock(StaGraph, float)} */
    float getSingleClockRequirement() {
        return singleClockRequirement;
    }

    private void warn(String kind) {
        warnings.merge(kind, 1, Integer::sum);
    }

    public Map<String, Integer> getWarnings() {
        return warnings;
    }

    private void resolveExceptions(ClockNetwork cn, Map<String, List<Integer>> pinsByCell) {
        for (TimingConstraints.PathExceptionDef e : cn.getConstraints().getExceptions()) {
            boolean fp = e.kind == TimingConstraints.ExceptionKind.FALSE_PATH;
            if (fp && ClockRelations.isClockOnly(e)) {
                // Folded into the clock relations
                continue;
            }
            BitSet fromClocks = null;
            Set<Integer> fromPins = null;
            BitSet toClocks = null;
            Set<Integer> toPins = null;
            if (e.from != null) {
                for (XdcObject o : e.from) {
                    if (o.getKind() == XdcObject.Kind.CLOCK) {
                        if (fromClocks == null) fromClocks = new BitSet();
                        for (int c : cn.resolveClocks(Collections.singletonList(o))) fromClocks.set(c);
                    } else {
                        if (fromPins == null) fromPins = new HashSet<>();
                        addPins(o, pinsByCell, true, fromPins);
                    }
                }
            }
            if (e.to != null) {
                for (XdcObject o : e.to) {
                    if (o.getKind() == XdcObject.Kind.CLOCK) {
                        if (toClocks == null) toClocks = new BitSet();
                        for (int c : cn.resolveClocks(Collections.singletonList(o))) toClocks.set(c);
                    } else {
                        if (toPins == null) toPins = new HashSet<>();
                        addPins(o, pinsByCell, false, toPins);
                    }
                }
            }
            if (e.through != null) {
                // -through: the objects that are endpoints without fanout (a path through such a pin ends there) apply
                // as -to pins (with the -to pins, their common part); a path through the others is not told apart
                // (they are not applied, with a warning)
                if (e.through.size() > 1) {
                    warn("exception with several -through options (ignored)");
                    continue;
                }
                if (toClocks != null) {
                    warn("exception with -through and -to clocks (ignored)");
                    continue;
                }
                Set<Integer> ends = new HashSet<>();
                boolean all = true;
                for (XdcObject o : e.through.get(0)) {
                    all &= addThroughEndpoints(o, pinsByCell, ends);
                }
                if (!all) {
                    warn("exception -through objects other than endpoints without fanout (not applied to them)");
                }
                if (toPins != null) {
                    ends.retainAll(toPins);
                }
                if (ends.isEmpty()) continue;
                toPins = ends;
            }
            exceptions.add(new PathException(fp, e.setup, e.hold, e.multiplier, e.start, fromClocks, fromPins,
                    toClocks, toPins, e.index));
        }
    }

    /**
     * Adds the endpoints of a -through object that ends every path through it: a leaf pin that is an endpoint without
     * fanout, or a net whose leaf loads all are.
     * <p>
     * Every path to an added pin passes through the object, so applying the exception -to the added pins is exact
     * for the paths that end there, even when the object has other paths through it.
     * @return False if paths may pass through the object other than into the added pins (those paths are not told
     *         apart and get no exception).
     */
    private boolean addThroughEndpoints(XdcObject o, Map<String, List<Integer>> pinsByCell, Set<Integer> out) {
        switch (o.getKind()) {
            case PIN: {
                String name = o.getName();
                int slash = name.lastIndexOf('/');
                String cellName = slash < 0 ? "" : name.substring(0, slash);
                String port = name.substring(slash + 1);
                EDIFHierPortInst hpi = o.getPin();
                if (hpi != null && !hpi.getCellType().isLeafCellOrBlackBox()) {
                    // A hierarchical input pin: the paths through it end at the leaf loads below it
                    List<EDIFHierPortInst> loads = getLeafLoadsBelow(hpi);
                    if (loads == null) return false;
                    for (EDIFHierPortInst load : loads) {
                        if (!addThroughEndpoints(XdcObject.ofPin(load), pinsByCell, out)) return false;
                    }
                    return true;
                }
                List<Integer> pins = pinsByCell.get(cellName);
                if (pins == null) return false;
                boolean found = false;
                for (int p : pins) {
                    EDIFPortInst pi = g.getPortInst(p);
                    if (pi == null || !pi.getName().equals(port)) continue;
                    if (captureCpin[p] < 0 || g.getOutDegree(p) != 0) {
                        return false;
                    }
                    out.add(p);
                    found = true;
                }
                return found;
            }
            case NET: {
                EDIFHierNet hn = o.getNet();
                if (hn == null) return true;
                for (EDIFHierPortInst load : hn.getLeafHierPortInsts(false, true)) {
                    if (!addThroughEndpoints(XdcObject.ofPin(load), pinsByCell, out)) return false;
                }
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * Gets the leaf loads of the net branch below a hierarchical input pin: the leaf input pins reached from the pin's
     * internal net by descending into the cell's hierarchy only, so that every path to them passes through the pin.
     * @param pin A pin of a hierarchical cell instance.
     * @return The loads, or null if the pin is not an input or not every path through it ends at them (the branch
     *         leaves the cell through another port, or has a driver inside the cell).
     */
    static List<EDIFHierPortInst> getLeafLoadsBelow(EDIFHierPortInst pin) {
        EDIFHierNet first = pin.isInput() ? pin.getInternalNet() : null;
        if (first == null) return null;
        List<EDIFHierPortInst> loads = new ArrayList<>();
        // Nets of the branch, each with the name of the port of its cell through which the branch entered it
        List<EDIFHierNet> nets = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        nets.add(first);
        entries.add(pin.getPortInst().getName());
        for (int i = 0; i < nets.size(); i++) {
            EDIFHierNet hn = nets.get(i);
            for (EDIFPortInst pi : hn.getNet().getPortInsts()) {
                if (pi.isTopLevelPort()) {
                    // A port of the cell the net is in: the one the branch came in by, or a way out
                    if (!pi.getName().equals(entries.get(i))) return null;
                    continue;
                }
                if (pi.isOutput()) {
                    // A driver inside
                    return null;
                }
                EDIFHierPortInst child = new EDIFHierPortInst(hn.getHierarchicalInst(), pi);
                if (pi.getCellInst().getCellType().isLeafCellOrBlackBox()) {
                    loads.add(child);
                } else {
                    EDIFHierNet inner = child.getInternalNet();
                    if (inner == null) continue;
                    nets.add(inner);
                    entries.add(pi.getName());
                }
            }
        }
        return loads;
    }

    /** Adds the startpoints (or endpoints) of an exception's -from (or -to) object. */
    private void addPins(XdcObject o, Map<String, List<Integer>> pinsByCell, boolean from, Set<Integer> out) {
        switch (o.getKind()) {
            case CELL: {
                List<Integer> pins = pinsByCell.get(o.getName());
                if (pins == null) return;
                for (int p : pins) {
                    if (from ? launchCpin[p] >= 0 && g.isPinKind(p, StaGraph.PIN_START)
                            : captureCpin[p] >= 0) {
                        out.add(p);
                    }
                }
                break;
            }
            case PIN: {
                String name = o.getName();
                int slash = name.lastIndexOf('/');
                String cellName = slash < 0 ? "" : name.substring(0, slash);
                String port = name.substring(slash + 1);
                List<Integer> pins = pinsByCell.get(cellName);
                if (pins == null) return;
                for (int p : pins) {
                    EDIFPortInst pi = g.getPortInst(p);
                    String pn = pi == null ? "" : pi.getName();
                    if (from) {
                        // A clock pin (e.g. reg/C) stands for the startpoints it launches
                        if (launchCpin[p] < 0 || !g.isPinKind(p, StaGraph.PIN_START)) continue;
                        if (pn.equals(port) || clockPinNames[launchCpin[p]] != null
                                && clockPinNames[launchCpin[p]].equals(name)) {
                            out.add(p);
                        }
                    } else if (captureCpin[p] >= 0 && pn.equals(port)) {
                        out.add(p);
                    }
                }
                break;
            }
            case NET: {
                // A net: its driver's startpoints (-from), its loads (-to)
                EDIFHierNet hn = o.getNet();
                if (hn == null) return;
                if (from) {
                    EDIFHierPortInst src = hn.getLeafSourcePortInst();
                    if (src != null) {
                        addPins(XdcObject.ofCell(src.getHierarchicalInst().getChild(src.getPortInst().getCellInst())),
                                pinsByCell, true, out);
                    }
                } else {
                    for (EDIFHierPortInst load : hn.getLeafHierPortInsts(false, true)) {
                        addPins(XdcObject.ofPin(load), pinsByCell, false, out);
                    }
                }
                break;
            }
            case PORT:
                // Paths from input ports and to output ports are not timed (no input/output delays)
                warn("exception -from/-to on ports (paths from/to ports are not timed, no match)");
                break;
            default:
                warn("exception -from/-to on " + o.getKind() + " objects (not supported, no match)");
                break;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hand building
    // ------------------------------------------------------------------------------------------

    /** Sets the launch clock pin of a startpoint. */
    void setLaunchClockPin(int pin, int clockPin) {
        launchCpin[pin] = clockPin;
    }

    /**
     * Sets the capture clock pin and check times of an endpoint.
     * @param pin The endpoint.
     * @param clockPin Its clock pin.
     * @param setup Setup time in ps.
     * @param hold Hold time in ps.
     */
    void setCaptureClockPin(int pin, int clockPin, float setup, float hold) {
        captureCpin[pin] = clockPin;
        if (setup == 0f && hold == 0f) {
            // (No check times: +0)
            setup = 0f;
            hold = 0f;
        }
        if (setupTimes == null && (setup != 0f || hold != 0f)) {
            setupTimes = new float[captureCpin.length];
            holdTimes = new float[captureCpin.length];
        }
        if (setupTimes != null) {
            setupTimes[pin] = setup;
            holdTimes[pin] = hold;
        }
    }

    /** Adds a resolved path exception. */
    void addException(PathException e) {
        exceptions.add(e);
    }

    /**
     * Sets the inter-SLR compensation factor of the checks whose launch and capture clock pins are in
     * different SLRs (see {@link ClockModel#getInterSlrCompensation}); 0 (the default) for none.
     * @param prorate The factor PF.
     */
    public void setInterSlrProrate(float prorate) {
        interSlrProrate = prorate;
    }

    /** @return The inter-SLR compensation factor, see {@link #setInterSlrProrate(float)}. */
    float getInterSlrProrate() {
        return interSlrProrate;
    }

    /** Sets the clock model, e.g. after clock routing changed (the analysis must then be rebuilt). */
    public void setClockModel(ClockModel model) {
        clockModel = model;
    }

    StaGraph getGraph() {
        return g;
    }

    public List<Clock> getClocks() {
        return clocks;
    }

    ClockRelations getRelations() {
        return relations;
    }

    public UncertaintyModel getUncertainty() {
        return uncertainty;
    }

    public ClockModel getClockModel() {
        return clockModel;
    }

    int getLaunchClockPin(int pin) {
        return launchCpin[pin];
    }

    int getCaptureClockPin(int pin) {
        return captureCpin[pin];
    }

    /** @return The name of a clock pin, or null if unknown. */
    String getClockPinName(int clockPin) {
        return clockPinNames[clockPin];
    }

    int getNumClockPins() {
        return clockPinEdges.length;
    }

    List<PathException> getExceptions() {
        return exceptions;
    }

    float getSetupTime(int pin) {
        return setupTimes == null ? 0f : setupTimes[pin];
    }

    float getHoldTime(int pin) {
        return holdTimes == null ? 0f : holdTimes[pin];
    }

    /** @return The hold time of an endpoint at the fast corner, or at the slow corner (if set, else the fast). */
    float getHoldTime(int pin, boolean slow) {
        if (slow && slowHoldTimes != null) {
            return slowHoldTimes[pin];
        }
        return getHoldTime(pin);
    }

    /**
     * Sets the hold times of the endpoints at the slow corner (SLOW_MIN data, for the slow-corner hold
     * analysis, see {@link StaClockAnalysis#hasSlowHold()}).
     * @param cn The clock network the binding was built with.
     * @param slowCheckTimes The check times whose hold times are those of the slow corner.
     */
    public void setSlowHoldTimes(ClockNetwork cn, CheckTimes slowCheckTimes) {
        float[] times = new float[g.getNumPins()];
        for (int p = 0; p < g.getNumPins(); p++) {
            int cp = captureCpin[p];
            if (cp < 0) continue;
            EDIFHierPortInst dataPin = g.getHierPortInst(p);
            float ho = dataPin == null ? 0f : slowCheckTimes.getHold(dataPin, cn.getClockPin(cp));
            times[p] = ho == 0f ? 0f : ho;
        }
        slowHoldTimes = times;
    }
}
