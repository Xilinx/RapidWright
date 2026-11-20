/*
 * Copyright (c) 2019-2022, Xilinx, Inc.
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing;

import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.rwroute.RouteNode;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.timing.clock.ClockData;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.clock.ClockNetwork;
import com.xilinx.rapidwright.timing.clock.ClockNodeDelayModel;
import com.xilinx.rapidwright.timing.clock.ClockRelations;
import com.xilinx.rapidwright.timing.clock.TimingConstraints;
import com.xilinx.rapidwright.timing.clock.UncertaintyModel;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;
import com.xilinx.rapidwright.timing.sta.FamilyArcProvider;
import com.xilinx.rapidwright.timing.sta.FittedCheckTimes;
import com.xilinx.rapidwright.timing.sta.StaClockAnalysis;
import com.xilinx.rapidwright.timing.sta.StaClockBinding;
import com.xilinx.rapidwright.timing.sta.StaClockReport;
import com.xilinx.rapidwright.timing.sta.StaGraph;
import com.xilinx.rapidwright.timing.sta.StaGraphBuilder;
import com.xilinx.rapidwright.timing.sta.TimingReport;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;


/**
 * A TimingManager sets up the timing analysis of a Design: the timing graph of the array-based static
 * timing analysis engine ({@link StaGraph}, built with the fitted cell arcs and the net delays of the
 * device's timing family, see {@link FamilyArcProvider}) and the analysis itself: the multi-clock
 * {@link StaClockAnalysis} with the clocks that {@link TimingFamilies#CLOCKS_PROPERTY} selects
 * (modeled by default; a design without clocks, or with at most one clock and ideal clocks, is timed against a
 * single requirement, see {@link StaClockBinding#singleClock(StaGraph, float)}). {@link TimingReport} reports the
 * critical path and the slacks of the analysis ({@link #getStaClockAnalysis()}).
 * <p>
 * With modeled clocks, the paths between unrelated clocks (no common primary clock or source) are
 * false paths, not timed, unlike in Vivado (see {@link ClockRelations#setUnrelatedFalse(boolean)}); the
 * clock report of {@link #getCriticalPathInfo()} says so when the design has such clocks.
 */
public class TimingManager {
    private Design design;
    private Device device;

    /** The timing graph */
    private final StaGraph staGraph;
    /** The timing analysis of the router: setup times and criticalities, with the design's clocks */
    private StaClockAnalysis analysis;
    /** The clocks of {@link #analysis} */
    private StaClockBinding binding;
    /** Whether {@link #binding} is a single ideal clock of the timing requirement's period */
    private boolean singleClock;
    /** The timing requirement that the single clock's period comes from */
    private float singleClockRequirement;
    /** Whether the clocks are modeled (see {@link TimingFamilies#CLOCKS_PROPERTY}) */
    private final boolean clocksModeled = TimingFamilies.isModeledClocksSelected();
    /** The design's clock network, null for a single ideal clock */
    private ClockNetwork clockNetwork;
    /** Times the clock routes of modeled clocks, null if ideal */
    private ClockNodeDelayModel clockNodeModel;
    /** The design's timing constraints, parsed once by the router's constructor (null otherwise) */
    private TimingConstraints designConstraints;
    /** The provider of the timing graph's arcs */
    private FamilyArcProvider arcProvider;
    /**
     * Whether the clock model (insertion delays and CRPR) is built when first needed (see
     * {@link #refreshClockTiming()}) rather than with the analysis, and whether it is still to be built
     */
    private boolean deferClockModel;
    private boolean clockModelPending;
    /** The number of passes of {@link #analysis} when its criticalities were last computed, and with which values */
    private int critPasses = -1;
    private float critMax;
    private float critExponent;
    /** The router's connections by connection id of the timing graph, for the critical path breakdown */
    private Connection[] connectionOfId;
    /**
     * The router connections that map to the same connection of the timing graph (to site pins that have none
     * of their own, see {@link StaGraph#getConnectionId(SitePinInst)}), by connection id; null where only one
     * does. The connection's route delay is the largest of theirs.
     */
    private Connection[][] sharedConnectionsOfId;
    /** Scratch of {@link #setTimingEdgesOfConnections}: a count per connection of the timing graph, all 0 */
    private int[] numMappedOfId;
    /**
     * With LUT pin swapping: the intra-site delays from the site pin that the connection is routed to that the
     * timing edges into LUT inputs charge, by edge, where set by {@link #getLutLogicDelay}
     */
    private final Map<Integer, float[]> sinkIntraSiteDelaysOfEdge = new HashMap<>();

    public RuntimeTrackerTree routerTimer;
    private boolean verbose;

    private float timingRequirement;
    private float pessimismA = (float) 1.03;
    private float pessimismB = 100;

    /** Delay estimator of the router's graph, also used to break the critical path delay down per node; null when not routing */
    private final DelayEstimator estimator;
    /** Delay estimator of the timing graph's route delays: the router's, unless it masks U-turn nodes */
    private DelayEstimator routeEstimator;

    /** Format of one line of that breakdown: its delay, what kind of hop it is, and the hop itself */
    private static final String DELAY_LINE_FORMAT = "\tdelay = %4d, %-18s, %s\n";

    /**
     * Default constructor: creates the TimingManager object of a design, with its timing graph and
     * analysis.
     * @param design RapidWright Design object.
     * @throws UnsupportedOperationException If the design's series has no timing model (see
     * {@link TimingFamilies#getSupportedSeries()}).
     */
    public TimingManager(Design design) {
        TimingFamilies.checkSupported(design.getDevice());
        this.design = design;
        device = design.getDevice();
        // No critical path breakdown is printed through this constructor, since it leaves verbose off
        estimator = null;
        staGraph = build(null, false);
        createAnalysis();
    }

    /**
     * Creates the timing analysis of a design for a router.
     * @param design The design.
     * @param timer The router's runtime tracker, or null.
     * @param config The router's configuration.
     * @param targetNets The nets to time.
     * @param isPartialRouting Whether only the cells that the target nets connect get timing arcs
     *                         (see StaGraphBuilder.setTimedNets()), as when partially routing a
     *                         design without timing all its nets.
     * @param estimator The delay estimator of the router's graph.
     * @throws UnsupportedOperationException If the design's series has no timing model (see
     * {@link TimingFamilies#getSupportedSeries()}).
     */
    @SuppressWarnings("deprecation") // The pessimism factors of the critical path printout
    public TimingManager(Design design,
                         RuntimeTrackerTree timer,
                         RWRouteConfig config,
                         Collection<Net> targetNets,
                         boolean isPartialRouting,
                         DelayEstimator estimator) {
        TimingFamilies.checkSupported(design.getDevice());
        this.design = design;
        // The fallback (and reported) requirement: the largest period of the design's clocks, from its
        // constraints as the timing analysis reads them
        try {
            designConstraints = TimingConstraints.fromDesign(design);
            setTimingRequirementPs(getTimingRequirement(design, designConstraints) * 1000);
        } catch (RuntimeException e) {
            // Reported by createAnalysis()
            setTimingRequirementPs(0f);
        }
        verbose = config.isVerbose();
        setPessimismFactors(config.getPessimismA(), config.getPessimismB());
        routerTimer = timer;
        device = design.getDevice();
        this.estimator = estimator;
        // The router routes the clock nets first, then calls refreshClockTiming(), which builds the clock model
        deferClockModel = true;
        staGraph = build(targetNets, isPartialRouting);
        createAnalysis();
    }

    /**
     * Builds the timing graph.
     * @param targetNets The nets to time, null for all.
     * @param isPartialRouting Whether only the cells that the target nets connect get timing arcs.
     * @return The timing graph.
     */
    private StaGraph build(Collection<Net> targetNets, boolean isPartialRouting) {
        if (routerTimer != null) routerTimer.createRuntimeTracker("build timing graph", "Initialization").start();
        arcProvider = createArcProvider();
        StaGraphBuilder builder = new StaGraphBuilder(design, arcProvider);
        builder.setTimedNets(targetNets, isPartialRouting);
        StaGraph g = builder.build();
        // (Drops the routing of the last net timed)
        arcProvider.invalidateRouting();
        if (g.getNumCutEdges() > 0) {
            System.out.println("INFO: Removed " + g.getNumCutEdges() + " timing edge(s) to break combinational cycles");
        }
        if (routerTimer != null) routerTimer.getRuntimeTracker("build timing graph").stop();
        return g;
    }

    /**
     * Creates the provider of the arcs and delays of the timing graph: with modeled clocks, its min delays
     * are those of the FAST_MIN corner, and its second min delays those of the SLOW_MIN corner, for the hold
     * analysis at both process corners; with ideal clocks, its min delays are its max delays.
     */
    private FamilyArcProvider createArcProvider() {
        // The router's estimator, unless it masks U-turn nodes (then not delays for the timing of
        // existing routes, which may use them)
        DelayEstimator maxEstimator = estimator != null && !estimator.masksUTurnNodes() ? estimator
                : DelayEstimator.create(design, true);
        routeEstimator = maxEstimator;
        if (clocksModeled) {
            // (Every timing family has the delays of the four corners)
            return new FamilyArcProvider(design, maxEstimator, Corner.SLOW_MAX, null, Corner.FAST_MIN,
                    Corner.SLOW_MIN);
        }
        return new FamilyArcProvider(design, maxEstimator, Corner.SLOW_MAX, maxEstimator, Corner.SLOW_MAX);
    }

    /**
     * Creates the analysis with the design's clocks (see {@link TimingFamilies#CLOCKS_PROPERTY}).
     */
    private void createAnalysis() {
        if (routerTimer != null) routerTimer.createRuntimeTracker("bind clocks", "Initialization").start();
        TimingConstraints constraints = null;
        ClockNetwork cn = null;
        try {
            constraints = designConstraints != null ? designConstraints : TimingConstraints.fromDesign(design);
            cn = ClockNetwork.build(design, constraints);
        } catch (RuntimeException e) {
            System.out.println("WARNING: Unable to trace the clocks of the design (" + e
                    + "), timing against a single requirement");
        }
        int numClocks = cn == null ? 0 : cn.getNumClocks();
        if (numClocks == 0 || numClocks == 1 && !clocksModeled) {
            // All startpoints and endpoints, against one requirement
            createSingleClockAnalysis();
        } else {
            singleClock = false;
            clockNetwork = cn;
            ClockModel clockModel = ClockModel.ideal(cn.getNumClockPins());
            UncertaintyModel uncertainty = UncertaintyModel.none(numClocks);
            StaClockBinding.CheckTimes checkTimes = StaClockBinding.NO_CHECK_TIMES;
            float interSlrProrate = 0f;
            if (clocksModeled) {
                ClockData data = ClockData.get(device, getSpeedGrade(TimingFamilies.of(device)));
                if (data != null) {
                    clockNodeModel = data.createNodeDelayModel();
                    // (A deferred model's stand-in has the SLRs of the clock pins, as the model)
                    clockModel = deferClockModel ? ClockModel.ideal(design, cn)
                            : ClockModel.build(design, cn, clockNodeModel);
                    clockModelPending = deferClockModel;
                    uncertainty = data.createUncertaintyModel(cn, constraints);
                    interSlrProrate = data.getInterSlrProrate();
                } else {
                    System.out.println("INFO: No clock data for " + device.getName()
                            + ": ideal clocks (no insertion delay or skew)");
                    uncertainty = new UncertaintyModel(cn.getClocks(), cn, constraints,
                            new UncertaintyModel.Coefficients());
                }
                checkTimes = new FittedCheckTimes(design);
            }
            binding = StaClockBinding.build(staGraph, cn, ClockRelations.build(cn), uncertainty, clockModel,
                    checkTimes);
            binding.setInterSlrProrate(interSlrProrate);
            if (checkTimes instanceof FittedCheckTimes && staGraph.hasSecondMinCorner()) {
                // The hold times of the slow-corner hold analysis
                binding.setSlowHoldTimes(cn, new FittedCheckTimes(design, Corner.SLOW_MAX, Corner.SLOW_MIN));
            }
            analysis = new StaClockAnalysis(binding);
            analysis.setHoldDelayRefresher(this::updateHoldDelays);
            System.out.println("INFO: Timing " + numClocks + " clocks (" + (clocksModeled ? "modeled" : "ideal") + ")");
        }
        printClockWarnings(constraints, cn);
        critPasses = -1;
        if (routerTimer != null) routerTimer.getRuntimeTracker("bind clocks").stop();
    }

    /**
     * Prints the warnings of the clock analysis, one line per kind (their objects too if verbose): those of the
     * constraints, the clock network, the clock model (unless deferred), the uncertainties and the binding of
     * the clocks to the timing graph.
     */
    private void printClockWarnings(TimingConstraints constraints, ClockNetwork cn) {
        if (constraints != null) {
            ClockNetwork.printWarnings("Timing constraints", constraints.getWarnings(), verbose);
        }
        if (cn != null) {
            ClockNetwork.printWarnings("Clock network", cn.getWarnings(), verbose);
        }
        if (!singleClock) {
            if (!clockModelPending) {
                ClockNetwork.printWarnings("Clock model", binding.getClockModel().getWarnings(), verbose);
            }
            ClockNetwork.printWarnings("Clock uncertainty", binding.getUncertainty().getWarnings(), verbose);
            ClockNetwork.printWarnings("Clock binding", binding.getWarnings(), verbose);
        }
    }

    /** Creates the analysis of a single ideal clock of the timing requirement's period. */
    private void createSingleClockAnalysis() {
        singleClock = true;
        clockNetwork = null;
        singleClockRequirement = timingRequirement;
        binding = StaClockBinding.singleClock(staGraph, timingRequirement);
        analysis = new StaClockAnalysis(binding);
        analysis.setHoldDelayRefresher(this::updateHoldDelays);
        critPasses = -1;
    }

    private SpeedGrade getSpeedGrade(TimingFamily family) {
        String partName = design.getPartName();
        if (partName == null || partName.isEmpty()) {
            return null;
        }
        return family.resolveSpeedGrade(PartNameTools.getPart(partName));
    }

    /**
     * Updates the insertion delays of modeled clocks after their nets were routed (e.g. by a router,
     * after its clock routing): the next update of the analysis is a full pass. Nothing to do with
     * ideal clocks. The connections of the clock nets that the timing graph times (those of clock
     * buffers used as data, see {@link FamilyArcProvider}) get the delays of their clock routes.
     */
    public void refreshClockTiming() {
        arcProvider.invalidateRouting();
        for (int c = 0; c < staGraph.getNumConnections(); c++) {
            SitePinInst sink = staGraph.getConnectionSink(c);
            Net net = sink == null ? null : sink.getNet();
            if (net == null || !net.isClockNet()) continue;
            float[] d = arcProvider.getRoutedDelays(net, net.getSource(), sink);
            if (d != null) {
                analysis.setConnectionRouteDelay(c, d[0], d[1]);
                if (d.length > 2) {
                    analysis.setConnectionRouteDelayMin2(c, d[2]);
                }
                critPasses = -1;
            }
        }
        arcProvider.invalidateRouting();
        if (singleClock || clockNodeModel == null) {
            return;
        }
        buildClockModel();
    }

    /** Builds the clock model of the clock nets' current routing, printing its warnings the first time */
    private void buildClockModel() {
        ClockModel model = ClockModel.build(design, clockNetwork, clockNodeModel);
        binding.setClockModel(model);
        analysis.refreshClockTiming();
        critPasses = -1;
        if (clockModelPending) {
            // (A model built with the analysis printed them then)
            clockModelPending = false;
            ClockNetwork.printWarnings("Clock model", model.getWarnings(), verbose);
        }
    }

    /** Builds the clock model if it was deferred and is still to be built (see {@link #refreshClockTiming()}) */
    private void ensureClockModel() {
        if (clockModelPending) {
            buildClockModel();
        }
    }

    /**
     * Updates the delay of nets after the cycle removal and delay-aware path merging, from their final
     * routing (the nodes of their connections): node and extra delays and, with a delay estimator that
     * has them, the fanout delays of the nodes where the net's routing branches.
     * @param illegalNets {@link NetWrapper} instances in question.
     * @param nodesDelays Stored nodes and their delay values.
     */
    public void updateIllegalNetsDelays(List<NetWrapper> illegalNets, Map<Node, Float> nodesDelays) {
        for (NetWrapper netWrapper : illegalNets) {
            // The children of each node in the net's routing
            Map<Node, Set<Node>> children = new HashMap<>();
            // The number of the net's connections routed through each node
            Map<Node, Integer> loads = new HashMap<>();
            if (estimator != null && estimator.hasFanoutDelays()) {
                for (Connection connection : netWrapper.getConnections()) {
                    if (connection.isDirect()) continue;
                    List<Node> nodes = connection.getNodes();
                    for (int i = nodes.size() - 2; i >= 0; i--) {
                        children.computeIfAbsent(nodes.get(i + 1), k -> new HashSet<>()).add(nodes.get(i));
                    }
                    for (Node node : new HashSet<>(nodes)) {
                        loads.merge(node, 1, Integer::sum);
                    }
                }
            }
            for (Connection connection : netWrapper.getConnections()) {
                if (connection.isDirect()) continue;
                List<Node> nodes = connection.getNodes();
                if (nodes.isEmpty()) continue;
                // The nodes from the sink rnode to the source rnode, as the router's route delays (the
                // site pins' nodes beyond them are charged with the pin delays, see setRouteDelay())
                int first = estimator == null ? -1 : nodes.indexOf(connection.getSinkRnode());
                int last = estimator == null ? -1 : nodes.lastIndexOf(connection.getSourceRnode());
                float netDelay = 0;
                if (first < 0 || last <= first) {
                    first = 0;
                    last = nodes.size() - 1;
                } else {
                    netDelay += nodesDelays.getOrDefault(nodes.get(last), 0f);
                }
                for (int i = last; i >= first; i--) {
                    Integer n = loads.get(nodes.get(i));
                    if (n != null && n > 1) {
                        netDelay += estimator.getFanoutLoadDelay(nodes.get(i), n);
                    }
                }
                for (int i = last - 1; i >= first; i--) {
                    Node child = nodes.get(i);
                    Node parent = nodes.get(i + 1);
                    netDelay += nodesDelays.getOrDefault(child, 0f) + getExtraDelay(parent, child);
                    Set<Node> c = children.get(parent);
                    if (c != null && c.size() > 1) {
                        netDelay += estimator.getFanoutDelay(parent, c.size());
                    }
                }
                setRouteDelay(connection, netDelay);
                connection.setDlyPatched(true);
            }
        }
    }

    /**
     * Gets the extra delay of a node from its driver: that of the router's delay estimator, or, without a
     * router, that of the timing graph's route delays.
     */
    private short getExtraDelay(Node parent, Node child) {
        return (estimator != null ? estimator : routeEstimator).getExtraDelay(parent, child);
    }

    /**
     * Patches up the delay of consecutive Long nodes for connections.
     * @param connections Connections in question.
     */
    public void patchUpDelayOfConnections(List<Connection> connections) {
        if (estimator != null) {
            // The route delays that the router set with the delay estimator are complete (node, extra and
            // fanout delays): recomputing them from the rnodes would drop the fanout delays
            return;
        }
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            if (connection.isDlyPatched()) continue;
            float netDelay = 0;
            for (int i = connection.getRnodes().size() - 2; i >= 0; i--) {
                RouteNode child = connection.getRnodes().get(i);
                RouteNode parent = connection.getRnodes().get(i+1);
                netDelay += child.getDelay() + getExtraDelay(parent, child);
            }
            setRouteDelay(connection, netDelay);
            connection.setDlyPatched(true);
        }
    }

    /**
     * Sets the route delay of a connection, which the timing analysis then charges to the timing
     * edges that go through the connection. Different connections may be set from several threads
     * at once.
     * @param connection The connection, mapped by {@link #setTimingEdgesOfConnections(List)}.
     * @param routeDelay Its route delay in ps.
     */
    public void setRouteDelay(Connection connection, float routeDelay) {
        // All route delays of connections come through here: add the delays of the connection's
        // site pins, a constant per connection
        float delay = routeDelay + getPinDelay(connection);
        int id = connection.getStaConnId();
        Connection[] shared = id < 0 || sharedConnectionsOfId == null ? null : sharedConnectionsOfId[id];
        if (shared != null) {
            // Several router connections drive this connection of the timing graph: the largest of their
            // delays, whatever the order (and thread) they are routed in
            synchronized (shared) {
                connection.setTimingRouteDelay(delay);
                float max = 0f;
                for (Connection c : shared) {
                    max = Math.max(max, c.getTimingRouteDelay());
                }
                analysis.setConnectionRouteDelay(id, max, max);
            }
            analysis.markHoldDelaysStale();
            return;
        }
        connection.setTimingRouteDelay(delay);
        if (id >= 0) {
            // The min (hold) delays are only those of the max corner until updateHoldDelays(), which
            // a hold query of the analysis runs first
            analysis.setConnectionRouteDelay(id, delay, delay);
            analysis.markHoldDelaysStale();
        }
    }

    /**
     * Gets the LUT logic delay with a connection arriving at the given input site pin of its LUT (with LUT
     * pin swapping, one of the connection's candidate sink pins): the worst max delay of the timing graph's
     * LUT arcs from the LUT input(s) that the connection feeds, with the input on the BEL pin that the site
     * pin drives, as the arc provider gives it (the fitted cell arcs by default), plus the intra-site delay
     * from the site pin to that BEL pin where the arc provider charges one per pin (see
     * {@link FamilyArcProvider#getSinkIntraSiteDelays}). Zero if the timing graph has no such arc (an input that
     * the LUT's function does not depend on has none).
     * @param connection The connection, whose sink is a LUT input.
     * @param sitePinName The name of the candidate input site pin, e.g. A6.
     * @param update True to also set the delays of those arcs, and the intra-site delays of the connection's
     *               timing edges into the LUT, in the timing analysis to the ones with the input on that pin,
     *               e.g. once the connection is routed to it.
     * @return The logic delay in ps.
     */
    public float getLutLogicDelay(Connection connection, String sitePinName, boolean update) {
        int id = connection.getStaConnId();
        if (id < 0) {
            return 0f;
        }
        SitePinInst sink = connection.getSink();
        float worst = 0f;
        for (int i = 0; i < staGraph.getConnectionNumEdges(id); i++) {
            int netEdge = staGraph.getConnectionEdge(id, i);
            int pin = staGraph.getEdgeTo(netEdge);
            EDIFHierCellInst inst = staGraph.getCell(pin);
            EDIFPortInst input = staGraph.getPortInst(pin);
            Cell cell = inst == null || input == null ? null : design.getCell(inst.getFullHierarchicalInstName());
            BEL bel = cell == null ? null : cell.getBEL();
            if (bel == null || !bel.isLUT()) {
                continue;
            }
            if (cell.getSiteInst() != sink.getSiteInst() || getInputBELPinName(bel, sink.getName()) == null) {
                // A LUT that the connection's own sink pin does not drive, whose delay does not depend on
                // the candidate pin: the connection shares its timing connection with that of another
                // site pin, e.g. a distributed RAM's address input, timed through its write address pin
                // (on the H LUT's input pins of a SLICEM, which drive the RAMs on the slice's other LUTs)
                continue;
            }
            String inputBelPin = getInputBELPinName(bel, sitePinName);
            if (inputBelPin == null) {
                throw new RuntimeException("ERROR: Site pin " + sitePinName + " does not drive an input of " + bel);
            }
            float[] intraSite = arcProvider.getSinkIntraSiteDelays(sink.getSiteInst(), sitePinName,
                    bel.getPin(inputBelPin));
            if (update && intraSite != null) {
                setSinkIntraSiteDelays(netEdge, sink.getSiteInst(), bel, sink.getName(), intraSite);
            }
            for (int k = 0; k < staGraph.getOutDegree(pin); k++) {
                int edge = staGraph.getOutEdge(pin, k);
                EDIFPortInst output = staGraph.getEdgeKind(edge) == StaGraph.CELL_COMB
                        ? staGraph.getPortInst(staGraph.getEdgeTo(edge)) : null;
                if (output == null) {
                    continue;
                }
                float[] d = arcProvider.getLutArcDelays(cell, input.getName(), inputBelPin, output.getName());
                if (d == null) {
                    throw new RuntimeException("ERROR: No logic delay from " + bel + "." + inputBelPin + " to "
                            + cell.getPhysicalPinMapping(output.getName()));
                }
                if (update) {
                    analysis.setEdgeFixedDelays(edge, d[0], d[1], d[2]);
                }
                worst = Math.max(worst, d[0] + (intraSite == null ? 0f : intraSite[0]));
            }
        }
        return worst;
    }

    /**
     * Sets the intra-site delays that a timing edge into a LUT input charges (as part of its fixed delays) to
     * those from the given site pin: with LUT pin swapping, the connection may be routed to another site pin
     * of the LUT than the one the timing graph was built with.
     * @param netEdge The timing edge, of the connection, into the LUT input.
     * @param siteInst The site instance of the LUT.
     * @param bel The LUT BEL.
     * @param builtSitePinName The site pin that the timing graph was built with: the connection's sink pin,
     *                         which is only renamed once the routes are final.
     * @param intraSite The intra-site delays from the site pin that the connection is now routed to.
     */
    private void setSinkIntraSiteDelays(int netEdge, SiteInst siteInst, BEL bel, String builtSitePinName,
                                        float[] intraSite) {
        // Different connections (whose edges differ) may be set from several threads at once
        synchronized (sinkIntraSiteDelaysOfEdge) {
            float[] current = sinkIntraSiteDelaysOfEdge.get(netEdge);
            if (current == null) {
                current = arcProvider.getSinkIntraSiteDelays(siteInst, builtSitePinName,
                        bel.getPin(getInputBELPinName(bel, builtSitePinName)));
            }
            if (current[0] != intraSite[0] || current[1] != intraSite[1] || current[2] != intraSite[2]) {
                analysis.setEdgeFixedDelays(netEdge,
                        staGraph.getEdgeFixedMax(netEdge) - current[0] + intraSite[0],
                        staGraph.getEdgeFixedMin(netEdge) - current[1] + intraSite[1],
                        staGraph.getEdgeFixedMin2(netEdge) - current[2] + intraSite[2]);
                staGraph.setEdgeIntraSiteMax(netEdge,
                        staGraph.getEdgeIntraSiteMax(netEdge) - current[0] + intraSite[0]);
            }
            sinkIntraSiteDelaysOfEdge.put(netEdge, intraSite);
        }
    }

    /**
     * Gets the input pin of a BEL that a site pin drives directly.
     * @param bel The BEL, e.g. a LUT.
     * @param sitePinName The name of the input site pin, e.g. A6.
     * @return The name of the BEL pin, or null if the site pin drives no input of the BEL.
     */
    private static String getInputBELPinName(BEL bel, String sitePinName) {
        for (BELPin belPin : bel.getPins()) {
            if (belPin.isInput() && sitePinName.equals(belPin.getConnectedSitePinName())) {
                return belPin.getName();
            }
        }
        return null;
    }

    /**
     * Gets the delay of the site pins of a connection, which its route delay does not include: a
     * constant per connection (see {@link DelayEstimator#getPinDelay(SitePinInst, SitePinInst)}),
     * with the fitted arcs plus that of the nodes between its site pins and their INT nodes (see
     * {@link #getProjectionDelay(Connection)}); with LUT pin swapping, that of the candidate sink pin
     * that the connection is routed to.
     * @param connection The connection.
     * @return The delay in ps.
     */
    private float getPinDelay(Connection connection) {
        if (estimator == null) {
            return 0f;
        }
        // With LUT pin swapping, the sink pin is the candidate that the connection is routed to: only the
        // delays between the site pins and their INT nodes are cached
        boolean altSinks = connection.hasAltSinks();
        float pinDelay = connection.getTimingPinDelay();
        if (Float.isNaN(pinDelay)) {
            pinDelay = altSinks ? 0f : estimator.getPinDelay(connection.getSource(), connection.getSink());
            pinDelay += getProjectionDelay(connection);
            connection.setTimingPinDelay(pinDelay);
        }
        if (altSinks) {
            List<RouteNode> rnodes = connection.getRnodes();
            // (On Versal, the router's sink is the INT node that drives the site pin's node)
            SitePin reached = rnodes.isEmpty() ? null : RouterHelper.getLutInputSitePin(rnodes.get(0));
            pinDelay += reached == null ? estimator.getPinDelay(connection.getSource(), connection.getSink())
                    : estimator.getPinDelay(connection.getSource(), reached.getSite(), reached.getPinName());
        }
        return pinDelay;
    }

    /**
     * Gets the delay of the nodes between a connection's site pins and the INT nodes that the router
     * projects them to, which the router's route delays leave out but the fitted arc provider charges
     * (see {@link FamilyArcProvider}): from the sink's INT node to its site pin's node, and from the
     * source's site pin's node to its INT node (whose own delay the router does charge).
     * @param connection The connection.
     * @return The delay in ps, 0 for pins whose node is in the INT tile.
     */
    private float getProjectionDelay(Connection connection) {
        float delay = 0f;
        SitePinInst sink = connection.getSink();
        Node sinkPin = sink.getConnectedNode();
        Node sinkINT = RouterHelper.projectInputPinToINTNode(sink);
        if (sinkPin != null && sinkINT != null && !sinkINT.equals(sinkPin)) {
            // Sink-first: the sink pin's node, ..., the sink's INT node
            List<Node> path = RouterHelper.findPathBetweenNodes(sinkINT, sinkPin);
            for (int i = path.size() - 2; i >= 0; i--) {
                Node node = path.get(i);
                delay += RouterHelper.computeNodeDelay(estimator, node) + estimator.getExtraDelay(path.get(i + 1), node);
            }
        }
        SitePinInst source = connection.getSource();
        Node sourcePin = source == null ? null : source.getConnectedNode();
        Node sourceINT = source == null ? null : RouterHelper.projectOutputPinToINTNode(source);
        if (sourcePin != null && sourceINT != null && !sourceINT.equals(sourcePin)) {
            // Sink-first: the source's INT node, ..., the source pin's node
            List<Node> path = RouterHelper.findPathBetweenNodes(sourcePin, sourceINT);
            if (!path.isEmpty()) {
                delay += RouterHelper.computeNodeDelay(estimator, sourcePin);
                for (int i = path.size() - 2; i >= 0; i--) {
                    Node node = path.get(i);
                    delay += RouterHelper.computeNodeDelay(estimator, node)
                            + estimator.getExtraDelay(path.get(i + 1), node);
                }
                delay -= RouterHelper.computeNodeDelay(estimator, sourceINT);
            }
        }
        return delay;
    }

    /**
     * Updates the arrival and required times, unless no route delay changed since the last update.
     * The required times are normalized to the maximum of the latest arrival time and the timing
     * requirement, since negative slacks are not supported: setting them to the timing requirement
     * would mean negative slack when it is exceeded, and setting them to the latest arrival time
     * would make the router work on paths that meet the requirement anyway.
     * @return The critical path delay, in ps: with several clocks, the data path delay of the worst setup
     * check (that of the WNS, see {@link #getCriticalPathInfo()}), else the maximum arrival time.
     */
    public float computeArrivalRequiredTimes() {
        ensureClockModel();
        analysis.update();
        StaClockAnalysis.Check worst = singleClock ? null : analysis.getWorstSetupCheck();
        if (worst != null) {
            return worst.getDataPathDelay();
        }
        float max = 0f;
        for (int k = 0; k < analysis.getNumEndpoints(); k++) {
            float a = analysis.getMaxArrival(analysis.getEndpointPin(k));
            if (a > max) {
                max = a;
            }
        }
        return max;
    }

    /**
     * Sets critical path delay pessimism factors.
     */
    private void setPessimismFactors(float a, short b) {
        if (a > 1) {
            pessimismA = a;
        }
        if (b > 0) {
            pessimismB = b;
        }
    }

    /**
     * Prints the critical path: the requirement, the critical path delay and slack, then the delay
     * of each edge of the path (see {@link TimingReport#printCriticalPathInfo}), and in verbose mode
     * the delays of the nodes of the path's connections. The critical path is that of the worst setup
     * check, whose slack is the WNS; with modeled clocks, the setup and hold slacks per clock group follow.
     */
    public void getCriticalPathInfo() {
        ensureClockModel();
        TimingReport.PathDescriber describer = new TimingReport.PathDescriber() {
            @Override
            public String getNetName(int edge) {
                Net net = getNetOfEdge(edge);
                return net == null ? null : net.getName();
            }

            @Override
            public void printDetails(PrintStream out, int edge) {
                printEdgeDetails(edge);
            }
        };
        TimingReport report = new TimingReport(analysis);
        if (!singleClock && report.getWorstSetupCheck() == null) {
            // No timed setup check: the path of largest arrival time, against the timing requirement
            report = new TimingReport(new StaClockAnalysis(StaClockBinding.singleClock(staGraph, timingRequirement)));
        }
        report.printCriticalPathInfo(System.out, pessimismA, pessimismB, verbose, describer);
        if (clocksModeled && !singleClock) {
            // Setup and hold slacks per clock group (a hold query refreshes the min route delays first if needed)
            System.out.println();
            System.out.print(StaClockReport.format(analysis, verbose ? 5 : 0));
        }
    }

    /**
     * Sets the min (hold) route delays of the connections of the timing graph from the routing of
     * their nets, at the min corner of the arc provider (FAST_MIN with modeled clocks) and at its
     * second min corner (SLOW_MIN) if any.
     */
    public void updateHoldDelays() {
        FamilyArcProvider provider = arcProvider;
        int n = staGraph.getNumConnections();
        SitePinInst[] sinks = new SitePinInst[n];
        for (int c = 0; c < n; c++) {
            sinks[c] = staGraph.getConnectionSink(c);
        }
        // (Looked up in parallel, set in order)
        float[] min = new float[n];
        float[] min2 = new float[n];
        provider.getRoutedMinDelays(sinks, min, min2);
        for (int c = 0; c < n; c++) {
            if (!Float.isNaN(min[c])) {
                analysis.setConnectionRouteDelay(c, staGraph.getConnectionRouteMax(c), min[c]);
                if (!Float.isNaN(min2[c])) {
                    analysis.setConnectionRouteDelayMin2(c, min2[c]);
                }
            }
        }
    }

    /**
     * Gets the physical net of an edge of the timing graph (see {@link #getStaGraph()}).
     * @param edge The edge.
     * @return Its net, or null if the edge is not part of a net.
     */
    public Net getNetOfEdge(int edge) {
        byte kind = staGraph.getEdgeKind(edge);
        if (kind != StaGraph.NET && kind != StaGraph.NET_INTRASITE) {
            return null;
        }
        int conn = staGraph.getEdgeConnection(edge);
        SitePinInst sink = conn < 0 ? null : staGraph.getConnectionSink(conn);
        if (sink != null && sink.getNet() != null) {
            return sink.getNet();
        }
        EDIFHierPortInst portInst = staGraph.getHierPortInst(staGraph.getEdgeTo(edge));
        EDIFHierNet hierNet = portInst == null ? null : portInst.getHierarchicalNet();
        if (hierNet == null) {
            return null;
        }
        EDIFHierNet parentNet = design.getNetlist().getParentNet(hierNet);
        return design.getNet((parentNet != null ? parentNet : hierNet).getHierarchicalNetName());
    }

    /**
     * Prints the delays of a path of the timing graph, one line per edge: its net or logic delay,
     * the site pins (or BEL pins, within a site) it goes between and the cell pins it joins, with
     * the physical net of each net delay.
     * @param path The edges of the path, in order, such as {@link TimingReport#getCriticalPath()} or
     *             {@link TimingReport#getMaxDelayPath(int, int)} return.
     */
    public void prettyPrintPathDelays(int[] path) {
        if (path == null) {
            System.err.println("Cannot print path delay, path is null.");
            return;
        }
        float logicDelayTotal = 0.0f;
        float netDelayTotal = 0.0f;
        String indent = "                                                                ";
        System.out.println("\n All Delays in picoseconds (ps):\n");
        System.out.println(
                " Net | Logic | Physical Site/BEL Pins                          | Logical Pins");
        System.out.println(
                "------------------------------------------------------------------------------");
        for (int e : path) {
            int src = staGraph.getEdgeFrom(e);
            int snk = staGraph.getEdgeTo(e);
            byte kind = staGraph.getEdgeKind(e);
            boolean isNet = kind == StaGraph.NET || kind == StaGraph.NET_INTRASITE;
            EDIFHierPortInst logicalSrc = staGraph.getHierPortInst(src);
            EDIFHierPortInst logicalSnk = staGraph.getHierPortInst(snk);
            Cell physicalSrcCell = logicalSrc == null ? null : logicalSrc.getPhysicalCell(design);
            Cell physicalSnkCell = logicalSnk == null ? null : logicalSnk.getPhysicalCell(design);
            if (physicalSrcCell == null || physicalSnkCell == null) {
                // A pseudo pin, e.g. between the bus pins of a hard block, or a cell that is not placed
                float intra = staGraph.getEdgeIntraSiteMax(e);
                int conn = staGraph.getEdgeConnection(e);
                float logicDelay = isNet ? staGraph.getEdgeFixedMax(e) - intra : staGraph.getEdgeDelayMax(e);
                float netDelay = isNet ? (conn < 0 ? intra : intra + staGraph.getConnectionRouteMax(conn)) : 0f;
                logicDelayTotal += logicDelay;
                netDelayTotal += netDelay;
                System.out.printf("%6.1f %6.1f %-49s %s\n", netDelay, logicDelay, "",
                        staGraph.getPinName(src) + "->\n" + indent + staGraph.getPinName(snk));
                continue;
            }
            String logEdge = physicalSrcCell.getType() + " " + staGraph.getPinName(src) + "->\n" + indent
                    + physicalSnkCell.getType() + " " + staGraph.getPinName(snk);
            String belEdge = getBELPinName(logicalSrc, physicalSrcCell) + "->"
                    + getBELPinName(logicalSnk, physicalSnkCell);
            float netDelay = 0.0f;
            float logicDelay = 0.0f;
            String physEdge = belEdge;
            Net net = null;
            if (isNet) {
                netDelay = staGraph.getEdgeDelayMax(e);
                net = getNetOfEdge(e);
                int conn = staGraph.getEdgeConnection(e);
                SitePinInst sinkPin = conn < 0 ? null : staGraph.getConnectionSink(conn);
                if (sinkPin != null && net != null) {
                    // Inter-site delay
                    physEdge = getSitePinName(net.getSource()) + "->" + getSitePinName(sinkPin);
                }
            } else {
                logicDelay = staGraph.getEdgeDelayMax(e);
            }
            logicDelayTotal += logicDelay;
            netDelayTotal += netDelay;
            System.out.printf("%6.1f %6.1f %-49s %s\n", netDelay, logicDelay, physEdge, logEdge);
            if (isNet) {
                EDIFHierNet hierNet = logicalSnk.getHierarchicalNet();
                String netName = net != null ? net.getName() : hierNet != null ? hierNet.getNet().getName() : null;
                System.out.println("              (Net: " + netName + ")");
            }
        }
        System.out.println("---------------------------------------------------------------------");
        System.out.printf("%6.1f %6.1f | Total: %6.1f\n", netDelayTotal, logicDelayTotal,
                netDelayTotal + logicDelayTotal);
    }

    private static String getBELPinName(EDIFHierPortInst portInst, Cell cell) {
        return cell.getSiteName() + "/" + cell.getBELName() + "."
                + cell.getPhysicalPinMapping(portInst.getPortInst().getName());
    }

    private static String getSitePinName(SitePinInst spi) {
        return spi == null ? "null" : spi.getSite().getName() + "." + spi.getName();
    }

    /**
     * Prints, in verbose mode, the hops of an edge of the critical path: those of its connection's
     * route when it goes through a routed connection, otherwise its intra-site hops.
     * @param edge The edge.
     */
    private void printEdgeDetails(int edge) {
        int conn = staGraph.getEdgeConnection(edge);
        Connection connection = (conn < 0 || connectionOfId == null) ? null : connectionOfId[conn];
        if (connection != null) {
            System.out.println(connection);
            // Walk the inter-site routing in source-to-sink order. Nodes are ordered sink-first, so the driver
            // of nodes[i] is nodes[i + 1]
            List<Node> nodes = connection.getNodes();
            if (nodes.isEmpty()) {
                if (connection.getNet().hasPIPs()) {
                    // A connection only carries its nodes when a router assigned them to it; walk
                    // the PIPs of its net to recover them otherwise. Only the connections of this
                    // one path are worth paying that for, hence not doing so for every connection.
                    nodes = NetTools.getNodesToSink(connection.getSink());
                } else {
                    System.out.println("\t(no intersite routing)");
                }
            }
            for (int iGroup = nodes.size() -1; iGroup >= 0; iGroup--) {
                Node node = nodes.get(iGroup);
                short delay = RouterHelper.computeNodeDelay(estimator, node);
                if (iGroup + 1 < nodes.size()) {
                    // Account for the extra delay this node incurs from its driver, as the
                    // accumulated route delay of the connection does
                    delay += estimator.getExtraDelay(nodes.get(iGroup + 1), node);
                }
                System.out.printf(DELAY_LINE_FORMAT, delay, node.getIntentCode(), node);
            }
            // The configuration delays inside the source and sink sites, which the edge charges with the route
            printIntraSiteDelayTerm(getIntraSiteDelayTerm(edge, (short) staGraph.getEdgeIntraSiteMax(edge)));
            System.out.println();
            return;
        }
        Net net = getNetOfEdge(edge);
        if (net == null) {
            return;
        }
        // No Connection means RWRoute never routed this edge: it must be an intra-site
        // connection (e.g. ALUT6/O -> CARRY8/S[0]), or a direct one
        short intraSiteDelay = (short) staGraph.getEdgeIntraSiteMax(edge);
        System.out.printf("net = %s, %s -> %s\n", net, staGraph.getPinName(staGraph.getEdgeFrom(edge)),
                staGraph.getPinName(staGraph.getEdgeTo(edge)));
        if (intraSiteDelay != 0) {
            // The hop between the two cells the edge joins
            printIntraSiteDelayTerm(getIntraSiteDelayTerm(edge, intraSiteDelay));
        }
        System.out.println();
    }

    /**
     * Prints one intra-site hop of the critical path, in the same format used for its nodes.
     * @param term The hop and its delay; nothing is printed when null.
     */
    private static void printIntraSiteDelayTerm(Pair<String,Short> term) {
        if (term == null) {
            return;
        }
        System.out.printf(DELAY_LINE_FORMAT, term.getSecond(), "(intrasite)", term.getFirst());
    }

    /**
     * Recovers the hop that the intra-site delay of an edge accounts for from the two cells the
     * edge joins (for an edge that leaves its site, the hops from the source BEL pin to its site pin
     * and from the sink site pin to the sink BEL pin).
     * @param edge Edge to describe.
     * @param intraSiteDelay Intra-site delay of that edge, in picoseconds.
     * @return The BEL pins the hop is between paired with that delay, falling back to a
     *         placeholder description when they cannot be recovered.
     */
    private Pair<String,Short> getIntraSiteDelayTerm(int edge, short intraSiteDelay) {
        Pair<SiteInst,BELPin> source = getBELPinOfPin(staGraph.getEdgeFrom(edge));
        Pair<SiteInst,BELPin> sink = getBELPinOfPin(staGraph.getEdgeTo(edge));
        if (source != null && sink != null) {
            // The fitted arcs charge the configuration delays between these BEL pins (through their
            // site pins if the edge leaves the site)
            return new Pair<>(describeBELPin(source.getSecond()) + " -> " + describeBELPin(sink.getSecond()),
                    intraSiteDelay);
        }
        return new Pair<>("(BEL pins not recoverable)", intraSiteDelay);
    }

    /**
     * Finds where the cell pin that a pin of the timing graph stands for has been placed.
     * @param pin Pin to look up.
     * @return The site instance and BEL pin it is placed onto, or null if it is a pseudo pin, or it
     *         is not placed onto a BEL pin.
     */
    private Pair<SiteInst,BELPin> getBELPinOfPin(int pin) {
        EDIFHierPortInst portInst = staGraph.getHierPortInst(pin);
        if (portInst == null) {
            return null;
        }
        Pair<SiteInst,BELPin> belPin = portInst.getRoutedBELPin(design);
        return (belPin == null || belPin.getSecond() == null) ? null : belPin;
    }

    /**
     * Names a BEL pin.
     * @param belPin BEL pin to name.
     * @return That BEL pin named "&lt;BEL&gt;/&lt;pin&gt;".
     */
    private static String describeBELPin(BELPin belPin) {
        return belPin.getBELName() + "/" + belPin.getName();
    }

    /**
     * Set the timing requirement of the design: the largest period of its clocks, from the constraints read
     * when this was created (see {@link #getDesignTimingRequirement(Design)}).
     */
    public void setTimingRequirement() {
        setTimingRequirementPs((designConstraints != null ? getTimingRequirement(design, designConstraints)
                : getDesignTimingRequirement(design)) * 1000);
    }

    public void setTimingRequirementPs(float ps) {
        timingRequirement = ps;
        if (singleClock && binding != null && singleClockRequirement != timingRequirement) {
            // The single clock's period is the requirement
            createSingleClockAnalysis();
        }
    }

    public float getTimingRequirementPs() {
        return timingRequirement;
    }

    /**
     * Gets the timing requirement of a design: the largest period of the primary clocks of its timing
     * constraints (see {@link TimingConstraints}).
     * @param design The design.
     * @return The requirement in ns, 0 if the design has no clock.
     */
    public static float getDesignTimingRequirement(Design design) {
        try {
            return getTimingRequirement(design, TimingConstraints.fromDesign(design));
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    /**
     * Gets the largest period of the primary clocks (create_clock) of timing constraints, but for those that later
     * ones replace (see {@link ClockNetwork#getPrimaryClocks}).
     * @param design The design.
     * @param constraints Its constraints.
     * @return The period in ns, 0 if there is no clock.
     */
    private static float getTimingRequirement(Design design, TimingConstraints constraints) {
        float treq = 0;
        for (TimingConstraints.ClockDef clock : ClockNetwork.getPrimaryClocks(design.getNetlist(), constraints)) {
            treq = Math.max(treq, (float) clock.period);
        }
        return treq;
    }

    /**
     * Calculates criticality for each connection, from its slack in the timing analysis (see
     * {@link StaClockAnalysis#computeCriticality(float, float)}), updating the analysis first if a route
     * delay changed.
     * @param connections Connections in question.
     * @param maxCriticality The maximum criticality value.
     * @param criticalityExponent The criticality exponent to use. For more information, please refer to the {@link RWRouteConfig} class file.
     */
    public void calculateCriticality(List<Connection> connections, float maxCriticality, float criticalityExponent) {
        ensureClockModel();
        analysis.update();
        int passes = analysis.getNumFullPasses() + analysis.getNumIncrementalPasses();
        if (passes != critPasses || maxCriticality != critMax || criticalityExponent != critExponent) {
            // Skipped when no route delay changed since the last time
            analysis.computeCriticality(maxCriticality, criticalityExponent);
            critPasses = passes;
            critMax = maxCriticality;
            critExponent = criticalityExponent;
        }
        // A connection without timing edges has no slack (as Connection.calculateCriticality() did,
        // with the single requirement)
        float untimed = singleClock ? StaClockAnalysis.getCriticality(Float.MAX_VALUE,
                analysis.getGroupNormalization(0), maxCriticality, criticalityExponent) : 0f;
        for (Connection connection : connections) {
            int id = connection.getStaConnId();
            connection.setCriticality(id < 0 ? untimed : analysis.getCriticality(id));
        }
    }

    /**
     * Gets the timing graph of the timing analysis.
     * @return The graph.
     */
    public StaGraph getStaGraph() {
        return staGraph;
    }

    /**
     * Gets the timing analysis of the router: setup times, criticalities and, on demand, hold times,
     * with the design's clocks.
     * @return The analysis.
     */
    public StaClockAnalysis getStaClockAnalysis() {
        ensureClockModel();
        return analysis;
    }

    /**
     * Gets the clocks of the timing analysis.
     * @return The binding of the design's clocks to the timing graph.
     */
    public StaClockBinding getStaClockBinding() {
        ensureClockModel();
        return binding;
    }

    /**
     * Checks if the analysis has a single ideal clock (a design without clocks, or with at most one clock and
     * ideal clocks), see {@link StaClockBinding#singleClock(StaGraph, float)}.
     * @return True if it has a single ideal clock of the timing requirement's period.
     */
    public boolean isSingleClock() {
        return singleClock;
    }

    /**
     * Gets the corresponding design used in creating this TimingManager.
     * @return Corresponding design used in creating this TimingManager.
     */
    public Design getDesign() {
        return design;
    }

    /**
     * Gets the corresponding device used in creating this TimingManager.
     * @return Corresponding device used in creating this TimingManager.
     */
    public Device getDevice() {
        return device;
    }

    /**
     * Maps router connections to the connections of the timing graph (see
     * {@link StaGraph#getConnectionId(Connection)}), setting their id
     * ({@link Connection#getStaConnId()}). Direct connections are not mapped; their delays are
     * those estimated when the timing graph was built.
     * @param connections The connections.
     */
    public void setTimingEdgesOfConnections(List<Connection> connections) {
        int n = staGraph.getNumConnections();
        if (connectionOfId == null) {
            connectionOfId = new Connection[n];
            numMappedOfId = new int[n];
        }
        // The number of router connections of each connection of the timing graph (all 0 between calls)
        int[] count = numMappedOfId;
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            int id = staGraph.getConnectionId(connection);
            connection.setStaConnId(id);
            if (id >= 0) {
                count[id]++;
                // For the critical path breakdown
                connectionOfId[id] = connection;
            }
        }
        // Those of the connections of the timing graph that have several, in the order given (a count c >= 2 is
        // that of a connection not met yet here, -k - 1 the next position k in its array)
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            int id = connection.getStaConnId();
            if (id < 0) continue;
            int c = count[id];
            if (c >= 2) {
                if (sharedConnectionsOfId == null) {
                    sharedConnectionsOfId = new Connection[n][];
                }
                sharedConnectionsOfId[id] = new Connection[c];
                sharedConnectionsOfId[id][0] = connection;
                count[id] = -2;
            } else if (c < 0) {
                sharedConnectionsOfId[id][-c - 1] = connection;
                count[id] = c - 1;
            }
        }
        for (Connection connection : connections) {
            int id = connection.isDirect() ? -1 : connection.getStaConnId();
            if (id >= 0) {
                count[id] = 0;
            }
        }
    }
}
