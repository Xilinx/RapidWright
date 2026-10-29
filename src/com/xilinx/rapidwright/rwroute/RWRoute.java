/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.ClkRouteTiming;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.util.Utils;

/**
 * RWRoute class provides the main methods for routing a design.
 * Creating a RWRoute Object needs a {@link Design} Object and a {@link RWRouteConfig} Object.
 */
public class RWRoute {
    /** The design to route */
    protected Design design;
    /** Created NetWrappers */
    protected Map<Net,NetWrapper> nets;
    /** A list of indirect connections that will go through iterative routing */
    protected List<Connection> indirectConnections;
    /** A list of direct connections that are easily routed through dedicated resources */
    private List<Connection> directConnections;
    /** Sorted indirect connections */
    protected List<Connection> sortedIndirectConnections;
    /** A list of global clock nets */
    protected List<Net> clkNets;
    /** Static nets */
    protected Map<Net, List<SitePinInst>> staticNetAndRoutingTargets;
    /** Several integers to indicate the netlist info */
    protected int numPreservedRoutableNets;
    protected int numPreservedClks;
    protected int numPreservedStaticNets;
    protected int numPreservedWire;
    private int numWireNetsToRoute;
    private int numConnectionsToRoute;
    protected int numNotNeedingRoutingNets;
    private int numUnrecognizedNets;

    /** A {@link RWRouteConfig} instance consisting of a list of routing parameters */
    protected RWRouteConfig config;
    /** The present congestion cost factor */
    protected float presentCongestionFactor;
    /** The historical congestion cost factor */
    private float historicalCongestionFactor;
    /** Wirelength-driven weighting factor */
    private float wlWeight;
    /** 1 - wlWeight */
    private float oneMinusWlWeight;
    /** Timing-driven weighting factor */
    private float timingWeight;
    /** 1 - timingWeight */
    private float oneMinusTimingWeight;
    /** Flag for whether LUT pin swaps are to be considered */
    private boolean lutPinSwapping;

    /** Flag for use of Hybrid Updating Strategy (HUS) */
    private boolean hus;
    /** Flag (computed at end of iteration 1) to indicate design is congested enough to consider HUS */
    private boolean husInitialCongested;

    /** The current routing iteration */
    protected int routeIteration;
    /** Timers to store runtime of different phases */
    protected RuntimeTrackerTree routerTimer;
    protected RuntimeTracker rnodesTimer;
    private RuntimeTracker updateTimingTimer;
    private RuntimeTracker updateCongestionCosts;
    /** An instantiation of RouteThruHelper to avoid route-thrus in the routing resource graph */
    protected RouteThruHelper routethruHelper;

    /** A set of indices of overused rondes */
    private Set<RouteNode> overUsedRnodes;
    /** Class encapsulating the routing resource graph */
    protected RouteNodeGraph routingGraph;
    /** Count of rnodes created in the current routing iteration */
    protected long rnodesCreatedThisIteration;
    /** State necessary to route the included connection */
    private ConnectionState connectionState;

    /** Total wirelength of the routed design, in half INT tiles (see {@link RouteNodeInfo#halfTileWirelength}) */
    private int totalWL;
    /** Total used INT tile nodes */
    private long totalINTNodes;
    /** A map from node types to the node usage of the types */
    private Map<IntentCode, Long> nodeTypeUsage;
    /** A map from node types to the total wirelength of used nodes of the types */
    private Map<IntentCode, Long> nodeTypeLength;
    /** The total number of connections that are routed */
    private final AtomicInteger connectionsRouted;
    /** The total number of connections routed in an iteration */
    private final AtomicInteger connectionsRoutedThisIteration;
    /** Total number of nodes pushed/popped from the queue */
    private final AtomicLong nodesPushed;
    private final AtomicLong nodesPopped;

    /** The maximum criticality constraint of connection */
    private static final float MAX_CRITICALITY = 0.99f;
    /** The minimum criticality of connections that should be re-routed, updated after each iteration */
    protected float minRerouteCriticality;
    /** The list of critical connections */
    private List<Connection> criticalConnections;
    /** A {@link TimingManager} instance to use that handles timing related tasks */
    protected TimingManager timingManager;
    /** A map from nodes to delay values, used for timing update after fixing routes */
    private Map<Node, Float> nodesDelays;
    /** The maximum delay and associated timing vertex */
    private Pair<Float, TimingVertex> maxDelayAndTimingVertex;

    /** A map storing routes from CLK_OUT to different INT tiles that connect to sink pins of a global clock net */
    protected Map<String, List<String>> routesToSinkINTTiles;

    public static final EnumSet<Series> SUPPORTED_SERIES = EnumSet.of(
                Series.UltraScale,
                Series.UltraScalePlus,
                Series.Versal);

    /** For connections that require SLR crossing(s), snap back to the previous Laguna column if the (horizontal) detour
     *  is no more than this number of tiles */
    protected int maxDetourToSnapBackToPrevLagunaColumn = 4;

    /**
     * The number of nodes in the receiving chain of a super long line, up to and including its landing node (see
     * remainingCrossingCost()): LAG_LAGUNA_SITE_*_RXD*, RXD*, SDQ node on UltraScale/UltraScale+; LAG_OUT*, CLE
     * LAG_*_PIN, [A-H]Q*_PIN, [A-H]Q* on Versal.
     */
    private final int sllReceivingChainNodes;

    public RWRoute(Design design, RWRouteConfig config) {
        this.design = design;
        this.config = config;
        sllReceivingChainNodes = (design.getSeries() == Series.Versal) ? 4 : 3;
        connectionsRouted = new AtomicInteger();
        connectionsRoutedThisIteration = new AtomicInteger();
        nodesPushed = new AtomicLong();
        nodesPopped = new AtomicLong();

        if (design.getSeries() == Series.Versal) {
            if (config.isLutPinSwapping()) {
                throw new RuntimeException("ERROR: '--lutPinSwapping' not yet supported on Versal.");
            }
            if (config.isLutRoutethru()) {
                throw new RuntimeException("ERROR: '--lutRoutethru' not yet supported on Versal.");
            }
        }
    }

    protected static String getUnsupportedSeriesMessage(Part part) {
        return "ERROR: RWRoute does not support routing the " + part.getName() + " from the " 
                + part.getSeries() + " series. Please re-target the design to a part from a "
                + "supported series: " + SUPPORTED_SERIES;
    }

    /**
     * Pre-process the design to ensure that only the physical {@link Net}-s corresponding to
     * the parent logical {@link EDIFHierNet} exists, and that such {@link Net}-s contain
     * all necessary {@link SitePinInst} objects.
     * @param design Design to preprocess
     */
    public static void preprocess(Design design) {
        Series series = design.getPart().getSeries();
        if (!SUPPORTED_SERIES.contains(series)) {
            throw new RuntimeException(getUnsupportedSeriesMessage(design.getPart()));
        }

        // Pre-processing of the design regarding physical net names pins
        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createPossiblePinsToStaticNets(design);
        DesignTools.createMissingSitePinInsts(design);
        if (series == Series.Versal) {
            DesignTools.updateVersalXPHYPinsForDMC(design);
        }
    }

    protected void preprocess() {
        preprocess(design);
    }

    protected void initialize() {
        routerTimer = new RuntimeTrackerTree("Route design", config.isVerbose());
        rnodesTimer = routerTimer.createStandAloneRuntimeTracker("rnodes creation");
        updateTimingTimer = routerTimer.createStandAloneRuntimeTracker("update timing");
        updateCongestionCosts = routerTimer.createStandAloneRuntimeTracker("update congestion costs");
        routerTimer.createRuntimeTracker("Initialization", routerTimer.getRootRuntimeTracker()).start();

        minRerouteCriticality = config.getMinRerouteCriticality();
        criticalConnections = new ArrayList<>();

        connectionState = new ConnectionState();
        routingGraph = createRouteNodeGraph();
        if (config.isTimingDriven()) {
            nodesDelays = new HashMap<>();
        }
        routethruHelper = new RouteThruHelper(design.getDevice());
        presentCongestionFactor = config.getInitialPresentCongestionFactor();
        lutPinSwapping = config.isLutPinSwapping();

        routerTimer.createRuntimeTracker("determine route targets", "Initialization").start();
        determineRoutingTargets();
        ensureSinkRoutability();
        routerTimer.getRuntimeTracker("determine route targets").stop();

        if (config.isTimingDriven()) {
            ClkRouteTiming clkTiming = createClkTimingData(config);
            routesToSinkINTTiles = clkTiming == null? null : clkTiming.getRoutesToSinkINTTiles();
            Collection<Net> timingNets = getTimingNets();
            timingManager = createTimingManager(clkTiming, timingNets);
            timingManager.setTimingEdgesOfConnections(indirectConnections);
        }

        sortedIndirectConnections = new ArrayList<>(indirectConnections.size());
        connectionsRouted.set(0);
        connectionsRoutedThisIteration.set(0);
        nodesPushed.set(0);
        nodesPopped.set(0);
        overUsedRnodes = new HashSet<>();

        hus = config.isHus();
        husInitialCongested = false;

        routerTimer.getRuntimeTracker("Initialization").stop();
    }

    /**
     * Creates clock routing related inputs based on the {@link RWRouteConfig} instance.
     * @param config The {@link RWRouteConfig} instance to use.
     */
    public static ClkRouteTiming createClkTimingData(RWRouteConfig config) {
        String clkRouteTimingFile = config.getClkRouteTiming();
        if (clkRouteTimingFile != null) {
            return new ClkRouteTiming(clkRouteTimingFile);
        }
        return null;
    }

    protected RouteNodeGraph createRouteNodeGraph() {
        if (config.isTimingDriven()) {
            /* An instantiated delay estimator that is used to calculate delay of routing resources */
            DelayEstimatorBase<InterconnectInfo> estimator = new DelayEstimatorBase<InterconnectInfo>(
                    design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphTimingDriven(design, config, estimator);
        } else {
            return new RouteNodeGraph(design, config);
        }
    }

    protected Collection<Net> getTimingNets() {
        return design.getNets();
    }

    protected TimingManager createTimingManager(ClkRouteTiming clkTiming, Collection<Net> timingNets) {
        final boolean isPartialRouting = false;
        return new TimingManager(design, routerTimer, config, clkTiming, timingNets, isPartialRouting,
                routingGraph.getDelayEstimator());
    }

    /**
     * Classifies {@link Net} Objects into different categories: clocks, static nets,
     * and regular signal nets (i.e. {@link NetType}.WIRE) and determines routing targets.
     */
    protected void determineRoutingTargets() {
        categorizeNets();

        // Since createNetWrapperAndConnections() both creates the primary sink node and
        // computes alternate sinks (e.g. for LUT pin swaps), it is possible that
        // an alternate sink for one net later becomes an exclusive sink for another net.
        // Examine for all connections for this case and remove such alternate sinks.
        for (Connection connection : indirectConnections) {
            connection.getAltSinkRnodes().removeIf(RouteNode::isUsed);
        }

        // Wait for all outstanding RouteNodeGraph.preserveAsync() calls to complete
        routingGraph.awaitPreserve();
    }

    protected Set<Net> ensureSinkRoutability() {
        // RWRoute routes designs from scratch -- all sinks must be reachable
        return Collections.emptySet();
    }

    private void categorizeNets() {
        numWireNetsToRoute = 0;
        numConnectionsToRoute = 0;
        numPreservedRoutableNets = 0;
        numNotNeedingRoutingNets = 0;
        numUnrecognizedNets = 0;

        nets = new IdentityHashMap<>();
        indirectConnections = new ArrayList<>();
        directConnections = new ArrayList<>();
        clkNets = new ArrayList<>();
        staticNetAndRoutingTargets = new HashMap<>();

        for (Net net : design.getNets()) {
            if (NetTools.isGlobalClock(net)) {
                addGlobalClkRoutingTargets(net);

            } else if (net.isStaticNet()) {
                addStaticNetRoutingTargets(net);

            } else if (net.getType().equals(NetType.WIRE)) {
                if (RouterHelper.isDriverLessOrLoadLessNet(net) ||
                        RouterHelper.isInternallyRoutedNet(net) ||
                        net.getName().equals(Net.Z_NET)) {
                    preserveNet(net, true);
                    numNotNeedingRoutingNets++;
                } else if (RouterHelper.isRoutableNetWithSourceSinks(net)) {
                    addNetConnectionToRoutingTargets(net);
                } else {
                    numNotNeedingRoutingNets++;
                }
            } else {
                numUnrecognizedNets++;
                System.err.println("ERROR: Unknown net " + net);
            }
        }
    }

    /**
     * A helper method for profiling the routing runtime v.s. average span of connections.
     */
    protected void printConnectionSpanStatistics() {
        System.out.println("Connection Span Info:");
        if (config.isPrintConnectionSpan()) System.out.println(" Span" + "\t" + "# Connections" + "\t" + "Percent");

        long sumSpan = 0;
        short max = 0;
        for (Entry<Short, Integer> spanCount : connectionSpan.entrySet()) {
            Short span = spanCount.getKey();
            Integer count = spanCount.getValue();
            if (config.isPrintConnectionSpan()) {
                System.out.printf("%5d \t%12d \t%7.2f\n", span, count, (float)count / indirectConnections.size() * 100);
            }
            sumSpan += span * count;
            if (span > max) max = span;
        }

        if (config.isPrintConnectionSpan()) System.out.println();
        long avg = (long) (sumSpan / ((float) indirectConnections.size()));
        System.out.println("INFO: Max span of connections: " + max);
        System.out.println("INFO: Avg span of connections: " + avg);
        int numConnectionsLongerThanAvg = 0;
        for (Entry<Short, Integer> spanCount : connectionSpan.entrySet()) {
            if (spanCount.getKey() >= avg) numConnectionsLongerThanAvg += spanCount.getValue();
        }

        System.out.printf("INFO: # connections longer than avg span: %d\n", numConnectionsLongerThanAvg);
        System.out.printf("(%5.2f%%)\n", (float)numConnectionsLongerThanAvg / indirectConnections.size() * 100);
        System.out.println("------------------------------------------------------------------------------");
    }

    /**
     * Adds the clock net to the list of clock routing targets, if the clock has source and sink {@link SitePinInst} instances.
     * Any existing routing on such nets will be unrouted.
     * @param clk The clock net in question.
     */
    protected void addGlobalClkRoutingTargets(Net clk) {
        if (RouterHelper.isRoutableNetWithSourceSinks(clk)) {
            clk.unroute();
            // Preserve all pins (e.g. in case of BOUNCE nodes that may serve as a site pin)
            preserveNet(clk, true);
            clkNets.add(clk);
        } else {
            numNotNeedingRoutingNets++;
            System.err.println("ERROR: Incomplete clock net " + clk);
        }
    }

    /**
     * Returns whether a node is (a) available for use,
     * (b) already in used by this net, (c) unavailable
     * @return NodeStatus result.
     */
    protected NodeStatus getGlobalRoutingNodeStatus(Net net, Node node) {
        if (!routingGraph.isAllowedTile(node)) {
            // Outside of PBlock
            return NodeStatus.UNAVAILABLE;
        }

        Net preservedNet = routingGraph.getPreservedNet(node);
        if (preservedNet == net) {
            return NodeStatus.INUSE;
        }
        if (preservedNet != null) {
            return NodeStatus.UNAVAILABLE;
        }

        RouteNode rnode = routingGraph.getNode(node);
        if (rnode != null) {
            // A RouteNode will only be created if the net is necessary for
            // a to-be-routed connection
            return NodeStatus.UNAVAILABLE;
        }
        return NodeStatus.AVAILABLE;
    }

    /**
     * Routes clock nets by default or in a different way when corresponding timing info supplied.
     * NOTE: For an unrouted design, its clock nets must not contain any PIPs or nodes, i.e, completely unrouted.
     * Otherwise, there could be a critical warning of clock routing results, when loading the routed design into Vivado.
     * Vivado will unroute the global clock nets immediately when there is such warning.
     * TODO: fix the potential issue.
     */
    protected void routeGlobalClkNets() {
        Map<Integer, Set<ClockRegion>> usedRoutingTracks = new HashMap<>();
        for (Net clk : clkNets) {
            routeGlobalClkNet(clk, usedRoutingTracks);
        }
    }

    protected void routeGlobalClkNet(Net clk, Map<Integer, Set<ClockRegion>> usedRoutingTracks) {
        // Since we preserved all pins in addGlobalClkRoutingTargets(), unpreserve them here
        for (SitePinInst spi : clk.getPins()) {
            routingGraph.unpreserve(spi.getConnectedNode());
        }
        Function<Node, NodeStatus> gns = (node) -> getGlobalRoutingNodeStatus(clk, node);
        if (routesToSinkINTTiles != null) {
            // routes clock nets with references of partial routes
            System.out.println("INFO: Routing " + clk.getPins().size() + " pins of clock " + clk + " (timing-driven)");
            GlobalSignalRouting.routeClkWithPartialRoutes(clk, routesToSinkINTTiles, design.getDevice(), gns);
        } else {
            // routes clock nets from scratch
            System.out.println("INFO: Routing " + clk.getPins().size() + " pins of clock " + clk + " (non timing-driven)");
            GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(), gns, usedRoutingTracks);
        }
        preserveNet(clk, false);

        if (clk.hasPIPs()) {
            clk.getSource().setRouted(true);
            assert(clk.getAlternateSource() == null);
        }
    }

    /**
     * Adds and initialize a regular signal net to the list of routing targets.
     * @param net The net to be added for routing.
     */
    protected void addNetConnectionToRoutingTargets(Net net) {
        net.unroute();
        createNetWrapperAndConnections(net);
    }

    /**
     * Adds a static net to the static net routing target list, unrouting it
     * if any routing exists.
     * @param staticNet The static net in question, i.e. VCC or GND.
     */
    protected void addStaticNetRoutingTargets(Net staticNet) {
        List<SitePinInst> sinks = staticNet.getSinkPins();
        if (!sinks.isEmpty()) {
            staticNet.unroute();
            // Remove all output pins from unrouted net as those used will be repopulated
            staticNet.setPins(sinks);

            staticNetAndRoutingTargets.put(staticNet, new ArrayList<>(sinks));
        } else {
            numNotNeedingRoutingNets++;
        }
    }

    /**
     * Routes static nets.
     */
    protected void routeStaticNets() {
        Net vccNet = design.getVccNet();
        Net gndNet = design.getGndNet();

        boolean noStaticRouting = staticNetAndRoutingTargets.isEmpty();
        if (!noStaticRouting) {
            List<SitePinInst> gndPins = staticNetAndRoutingTargets.get(gndNet);
            if (gndPins != null) {
                boolean invertGndToVccForLutInputs = config.isInvertGndToVccForLutInputs();
                Set<SitePinInst> newVccPins = RouterHelper.invertPossibleGndPinsToVccPins(design, gndPins, invertGndToVccForLutInputs);
                if (!newVccPins.isEmpty()) {
                    gndPins.removeAll(newVccPins);
                    staticNetAndRoutingTargets.computeIfAbsent(vccNet, (net) -> new ArrayList<>())
                            .addAll(newVccPins);
                }
            }

            Iterator<Map.Entry<Net,List<SitePinInst>>> it = staticNetAndRoutingTargets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Net,List<SitePinInst>> e = it.next();
                Net staticNet = e.getKey();
                List<SitePinInst> pins = e.getValue();
                // For some encrypted designs, it's possible that RapidWright cannot infer all SitePinInst-s leading to
                // some site pins (e.g. CKEN) defaulting those to static nets. Detect such cases -- when signal nets are
                // already routed to and preserved at those uninferrable SitePinInst-s -- and remove them from being a
                // static net sink
                pins.removeIf(spi -> {
                    Node node = spi.getConnectedNode();
                    if (!routingGraph.isAllowedTile(node)) {
                        // If sink is not in an allowed tile (e.g. outside routing PBlock) drop it silently
                        return true;
                    }

                    Net preservedNet = routingGraph.getPreservedNet(node);
                    if (preservedNet == null) {
                        // This sink is not preserved by any net, allow
                        return false;
                    }
                    // Sink preserved by another net, abandon; check that it cannot have been preserved by this static net
                    assert(preservedNet != staticNet);
                    return true;
                });

                // Remove from map if empty
                if (pins.isEmpty()) {
                    it.remove();
                }
            }
        }

        // Preserve all static nets' sink pins regardless of whether any routing is necessary
        for (Net staticNet : Arrays.asList(vccNet, gndNet)) {
            for (SitePinInst spi : staticNet.getPins()) {
                if (spi.isOutPin()) {
                    continue;
                }
                routingGraph.preserve(spi.getConnectedNode(), staticNet);
            }
        }

        if (noStaticRouting) {
            // Now that all static nets have been fully preserved, return if no work to be done
            return;
        }

        for (Map.Entry<Net,List<SitePinInst>> e : staticNetAndRoutingTargets.entrySet()) {
            Net staticNet = e.getKey();
            List<SitePinInst> pins = e.getValue();

            System.out.println("INFO: Routing " + pins.size() + " pins of " + staticNet);

            Function<Node, NodeStatus> gns = (node) -> getGlobalRoutingNodeStatus(staticNet, node);
            GlobalSignalRouting.routeStaticNet(pins, gns, design, routethruHelper);

            preserveNet(staticNet, false);
        }
    }

    /**
     * Preserves a net by preserving all nodes use by the net.
     * @param net The net to be preserved.
     */
    protected void preserveNet(Net net, boolean async) {
        if (async) {
            routingGraph.preserveAsync(net);
        } else {
            routingGraph.preserve(net);
        }
    }

    private final Map<Short, Integer> connectionSpan = new HashMap<>();

    /**
     * Creates a unique {@link NetWrapper} instance and {@link Connection} instances based on a {@link Net} instance.
     * @param net The net to be initialized.
     * @return A {@link NetWrapper} instance.
     */
    protected NetWrapper createNetWrapperAndConnections(Net net) {
        List<SitePinInst> sinkPins = net.getSinkPins();
        assert(!sinkPins.isEmpty());

        NetWrapper netWrapper = new NetWrapper(numWireNetsToRoute++, net);
        NetWrapper existingNetWrapper = nets.put(net, netWrapper);
        assert(existingNetWrapper == null);

        SitePinInst source = net.getSource();
        Node sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);

        RouteNode sourceINTRnode = null;
        int indirect = 0;
        for (SitePinInst sink : sinkPins) {
            Connection connection = new Connection(numConnectionsToRoute++, source, sink, netWrapper);
            Node sinkINTNode = RouterHelper.projectInputPinToINTNode(sink);
            if (sourceINTNode == null && sinkINTNode != null) {
                // Sink can be projected to an INT tile, but primary source (e.g. COUT)
                // cannot be; try alternate source
                Pair<SitePinInst,RouteNode> altSourceAndRnode = connection.getOrCreateAlternateSource(routingGraph);
                if (altSourceAndRnode != null) {
                    SitePinInst altSource = altSourceAndRnode.getFirst();
                    RouteNode altSourceINTRnode = altSourceAndRnode.getSecond();
                    connection.setSource(altSource);
                    connection.setSourceRnode(altSourceINTRnode);
                }
            }

            if ((sourceINTNode == null && connection.getSourceRnode() == null) || sinkINTNode == null) {
                // Direct connection if either source or sink pin cannot be projected to INT tile
                directConnections.add(connection);
                connection.setDirect(true);
            } else {
                if (connection.getSourceRnode() == null) {
                    assert(sourceINTNode != null);
                    if (sourceINTRnode == null) {
                        sourceINTRnode = routingGraph.getOrCreate(sourceINTNode, RouteNodeType.EXCLUSIVE_SOURCE);
                        // Where only a single primary source exists, always preserve
                        // its projected-to-INT source node, since it could
                        // be a projection from LAGUNA/RXQ* -> RXD* (node for INT/LOGIC_OUTS_*)
                        assert(sourceINTRnode != null);
                        routingGraph.preserve(sourceINTNode, net);
                        netWrapper.setSourceRnode(sourceINTRnode);
                    }
                    connection.setSourceRnode(sourceINTRnode);
                }

                indirectConnections.add(connection);

                RouteNode sinkRnode = routingGraph.getOrCreate(sinkINTNode);
                RouteNodeType sinkType = sinkRnode.getType();
                assert(sinkType.isAnyLocal());
                connection.setSinkRnode(sinkRnode);

                if (sinkINTNode.getTile() != sink.getTile()) {
                    TileTypeEnum sinkTileType = sink.getTile().getTileTypeEnum();
                    if (Utils.isLaguna(sinkTileType)) {
                        // Sinks in Laguna tiles must be Laguna registers (but will be projected into the INT tile)
                        // however, it's possible for another net to use the sink node as a bounce -- prevent that here
                        assert(sinkINTNode.getTile().getTileTypeEnum() == TileTypeEnum.INT);
                        routingGraph.preserve(sink.getConnectedNode(), net);
                    }
                }

                // Where appropriate, allow all 6 LUT pins to be swapped to begin with
                char lutLetter = sink.getName().charAt(0);
                int numberOfSwappablePins = (lutPinSwapping && sink.isLUTInputPin())
                        ? LUTTools.MAX_LUT_SIZE : 0;
                if (numberOfSwappablePins > 0) {
                    for (Cell cell : DesignTools.getConnectedCells(sink)) {
                        BEL bel = cell.getBEL();
                        assert(bel.isLUT());
                        String belName = bel.getName();
                        String cellType = cell.getType();
                        if (belName.charAt(0) != lutLetter) {
                            assert(cellType.startsWith("RAM"));
                            // This pin connects to other LUTs! (e.g. SLICEM.H[1-6] also serves
                            // as the WA for A-G LUTs used as distributed RAM) -- do not allow any swapping
                            // TODO: Relax this when https://github.com/Xilinx/RapidWright/issues/901 is fixed
                            numberOfSwappablePins = 0;
                            break;
                        }
                        if (bel.getName().startsWith("H") && cellType.startsWith("RAM")) {
                            // Similarly, disallow swapping of any RAMs on the "H" BELs since their
                            // "A" and "WA" inputs are shared and require extra care to keep in sync
                            numberOfSwappablePins = 0;
                            break;
                        }
                        if (cellType.startsWith("SRL")) {
                            // SRL* cells cannot support any pin swaps
                            numberOfSwappablePins = 0;
                            break;
                        }
                        if (belName.charAt(1) == '5') {
                            // Since a 5LUT cell exists, only allow bottom 5 pins to be swapped
                            numberOfSwappablePins = 5;
                        }
                    }
                }

                Site site = sink.getSite();
                for (int i = 1; i <= numberOfSwappablePins; i++) {
                    Node node = site.getConnectedNode(lutLetter + Integer.toString(i));
                    assert(node.getTile().getTileTypeEnum() == TileTypeEnum.INT);
                    if (node.equals(sinkINTNode)) {
                        continue;
                    }
                    if (routingGraph.isPreserved(node)) {
                        continue;
                    }
                    RouteNode altSinkRnode = routingGraph.getOrCreate(node, sinkType);
                    assert(altSinkRnode.getType() == sinkType);
                    assert(altSinkRnode.isOnEastSide() == sinkRnode.isOnEastSide());
                    connection.addAltSinkRnode(altSinkRnode);
                }

                if (!connection.hasAltSinks()) {
                    // Since this connection only has a single sink target, make it exclusive
                    // (it keeps the half of the INT tile that it is on)
                    assert(sinkType != RouteNodeType.LOCAL_RESERVED);
                    sinkRnode.setType(routingGraph.getExclusiveSinkType(sinkRnode));

                    // And increment its usage here immediately
                    sinkRnode.incrementUser(netWrapper);
                }

                connection.setDirect(false);
                indirect++;
                connection.computeHpwl();
                addConnectionSpanInfo(connection);
            }
        }

        if (indirect > 0) {
            netWrapper.computeHPWLAndCenterCoordinates(routingGraph);
            if (config.isUseBoundingBox()) {
                for (Connection connection : netWrapper.getConnections()) {
                    if (connection.isDirect()) continue;
                    connection.computeConnectionBoundingBox(config.getBoundingBoxExtensionX(),
                            config.getBoundingBoxExtensionY(),
                            routingGraph);
                }
            }
        }
        return netWrapper;
    }

    /**
     * Adds span info of a connection.
     * @param connection A connection of which span info is to be added.
     */
    private void addConnectionSpanInfo(Connection connection) {
        connectionSpan.merge(connection.getHpwl(), 1, Integer::sum);
    }

    /**
     * @return ConnectionState object to be used for routing.
     */
    protected ConnectionState getConnectionState() {
        return connectionState;
    }

    /**
     * Initializes routing.
     */
    private void initializeRouting() {
        routingGraph.initialize();
        routeIteration = 1;
        historicalCongestionFactor = config.getHistoricalCongestionFactor();
        presentCongestionFactor = config.getInitialPresentCongestionFactor();
        timingWeight = config.getTimingWeight();
        wlWeight = config.getWirelengthWeight();
        oneMinusTimingWeight = 1 - timingWeight;
        oneMinusWlWeight = 1 - wlWeight;
        printIterationHeader(config.isTimingDriven());

        // On Versal only, reserve all uphills of NODE_(CLE|INTF)_CTRL sinks since
        // their [BC]NODEs can also be used to reach NODE_INODEs --- not applying this
        // heuristic can lead to avoidable congestion
        if (routingGraph.isVersal) {
            for (Connection connection : indirectConnections) {
                RouteNode sinkRnode = connection.getSinkRnode();
                if (sinkRnode.getType().isExclusiveSinkCtrlOrSllInput()) {
                    IntentCode sinkIntent = sinkRnode.getIntentCode();
                    for (Node uphill : sinkRnode.getAllUphillNodes()) {
                        if (uphill.isTiedToVcc()) {
                            continue;
                        }
                        Net preservedNet = routingGraph.getPreservedNet(uphill);
                        if (preservedNet != null && preservedNet != connection.getNet()) {
                            continue;
                        }
                        if (sinkIntent == IntentCode.NODE_SLL_INPUT && uphill.getIntentCode() == IntentCode.NODE_SLL_OUTPUT) {
                            // No need to reserve NODE_SLL_OUTPUT nodes of a NODE_SLL_INPUT
                            continue;
                        }
                        assert((sinkIntent == IntentCode.NODE_CLE_CTRL &&
                                        (uphill.getIntentCode() == IntentCode.NODE_CLE_CNODE || uphill.getIntentCode() == IntentCode.NODE_CLE_BNODE)) ||
                                (sinkIntent == IntentCode.NODE_INTF_CTRL &&
                                        (uphill.getIntentCode() == IntentCode.NODE_INTF_CNODE || uphill.getIntentCode() == IntentCode.NODE_INTF_BNODE)) ||
                                (sinkIntent == IntentCode.NODE_SLL_INPUT &&
                                        uphill.getIntentCode() == IntentCode.NODE_CLE_BNODE)
                        );
                        RouteNode rnode = routingGraph.getOrCreate(uphill, RouteNodeType.LOCAL_RESERVED);
                        rnode.setType(RouteNodeType.LOCAL_RESERVED);
                    }
                }
            }
        }
    }

    /**
     * Routes the design in a few routing phases and times those phases.
     */
    public void route() {
        // Prints the design and configuration info, if "--verbose" is configured
        printDesignNetsAndConfigurationInfo(config.isVerbose());

        routerTimer.createRuntimeTracker("Routing", routerTimer.getRootRuntimeTracker()).start();
        MessageGenerator.printHeader("Route Design");

        routerTimer.createRuntimeTracker("route clock", "Routing").start();
        routeGlobalClkNets();
        routerTimer.getRuntimeTracker("route clock").stop();

        routerTimer.createRuntimeTracker("route static nets", "Routing").start();
        // Routes static nets (VCC and GND) before signals for now.
        // All the used nodes by other nets should be marked as unavailable, if static nets are routed after signals.
        routeStaticNets();
        // Connection-based router for indirectly connected pairs of output pin and input pin */
        routerTimer.getRuntimeTracker("route static nets").stop();

        RuntimeTracker routeWireNets = routerTimer.createRuntimeTracker("route wire nets", "Routing");
        routeWireNets.start();
        preRoutingEstimation();
        routeIndirectConnectionsIteratively();
        // NOTE: route direct connections after indirect connection.
        // The reason is that there maybe additional direct connections in the soft preserve mode for partial routing,
        // and those direct connections should be included to be routed
        routeDirectConnections();
        routeWireNets.stop();
        // Adds child timers to "route wire nets" timer
        routeWireNets.addChild(rnodesTimer);
        // Do not time the cost evaluation method for routing connections, the timer itself takes time
        routerTimer.createRuntimeTracker("route connections", "route wire nets").setTime(routeWireNets.getTime() - rnodesTimer.getTime() - updateTimingTimer.getTime() - updateCongestionCosts.getTime());
        if (config.isTimingDriven()) {
            routeWireNets.addChild(updateTimingTimer);
        }
        routeWireNets.addChild(updateCongestionCosts);

        routerTimer.createRuntimeTracker("finalize routes", "Routing").start();
        // Assigns a list of nodes to each direct and indirect connection that has been routed and fix illegal routes if any
        postRouteProcess();
        // Assigns net PIPs based on lists of connections
        setPIPsOfNets();
        routerTimer.getRuntimeTracker("finalize routes").stop();

        routerTimer.getRuntimeTracker("Routing").stop();

        if (config.getExportOutOfContext()) {
            getDesign().setAutoIOBuffers(false);
            getDesign().setDesignOutOfContext(true);
        }

        // Prints routing statistics, e.g. total wirelength, runtime and timing report
        printRoutingStatistics();
    }

    /**
     * Calculates initial criticality for each connection based on a simple estimation.
     */
    private void preRoutingEstimation() {
        if (config.isTimingDriven()) {
            estimateDelayOfConnections();
            maxDelayAndTimingVertex = timingManager.calculateArrivalRequiredTimes();
            timingManager.calculateCriticality(indirectConnections, MAX_CRITICALITY, config.getCriticalityExponent());
            System.out.printf("INFO: Estimated pre-routing max delay: %4d\n", (short) maxDelayAndTimingVertex.getFirst().floatValue());
        }
    }

    /**
     * A simple approach to estimate delay of each connection and update route delay of its timing edges.
     */
    private void estimateDelayOfConnections() {
        for (Connection connection : indirectConnections) {
            RouteNode source = connection.getSourceRnode();
            RouteNode[] children = source.getChildren(routingGraph);
            if (children.length == 0) {
                // output pin is blocked
                swapOutputPin(connection);
                source = connection.getSourceRnode();
            }
            short estDelay = (short) 10000;
            for (RouteNode child : children) {
                short tmpDelay = 113;
                tmpDelay += child.getDelay();
                if (tmpDelay < estDelay) {
                    estDelay = tmpDelay;
                }

            }
            estDelay += source.getDelay();
            connection.setTimingEdgesDelay(estDelay);
        }
    }

    /**
     * Routes direct connections.
     */
    private void routeDirectConnections() {
        System.out.println("\nINFO: Route " + directConnections.size() + " direct connections ");
        for (Connection connection : directConnections) {
            boolean success = RouterHelper.routeDirectConnection(connection);
            connection.setRouted(success);
            // no need to update route delay of direct connection, because it would not be changed
            if (!success) System.err.println("ERROR: Failed to route direct connection " + connection);
        }
    }

    protected void routeIndirectConnections(Collection<Connection> connections) {
        for (Connection connection : connections) {
            if (shouldRoute(connection)) {
                routeIndirectConnection(connection);
            }
        }
    }

    /**
     * Routes indirect connections iteratively.
     */
    public void routeIndirectConnectionsIteratively() {
        sortConnections();
        initializeRouting();
        long lastIterationRnodeCount = routingGraph.numNodes();
        long lastIterationRnodeTime = 0;

        boolean initialHus = this.hus;
        while (routeIteration < config.getMaxIterations()) {
            long start = RuntimeTracker.now();
            connectionsRoutedThisIteration.set(0);
            if (config.isTimingDriven()) {
                setRerouteCriticality();
            }
            routingGraph.updatePresentCongestionCosts(presentCongestionFactor);
            routeIndirectConnections(sortedIndirectConnections);
            rnodesTimer.setTime(routingGraph.getCreateRnodeTime());

            updateCostFactors();

            rnodesCreatedThisIteration = routingGraph.numNodes() - lastIterationRnodeCount;
            List<Connection> unroutableConnections = getUnroutableConnections();
            boolean needsResorting = false;
            for (Connection connection : unroutableConnections) {
                System.out.printf("CRITICAL WARNING: Unroutable connection in iteration #%d\n", routeIteration);
                System.out.println("                 " + connection);
                needsResorting = handleUnroutableConnection(connection) || needsResorting;
            }
            for (Connection connection : getCongestedConnections()) {
                needsResorting = handleCongestedConnection(connection) || needsResorting;
            }
            if (needsResorting) {
                sortConnections();
            }

            if (config.isTimingDriven()) {
                updateTiming();
            }

            long elapsed = RuntimeTracker.elapsed(start);
            printRoutingIterationStatisticsInfo(elapsed, (float) ((rnodesTimer.getTime() - lastIterationRnodeTime) * 1e-9));

            if (overUsedRnodes.isEmpty()) {
                if (unroutableConnections.isEmpty()) {
                    break;
                } else {
                    if (routeIteration == config.getMaxIterations() - 1) {
                        System.err.println("ERROR: Unroutable connections: " + unroutableConnections.size());
                    }
                }
            }

            if (initialHus && !hus) {
                System.out.println("INFO: Hybrid Updating Strategy (HUS) activated");
                initialHus = false;
            }

            routeIteration++;
            lastIterationRnodeCount = routingGraph.numNodes();
            lastIterationRnodeTime = rnodesTimer.getTime();
        }
        if (routeIteration == config.getMaxIterations()) {
            System.out.println("\nERROR: Routing terminated after " + (routeIteration -1 ) + " iterations.");
            System.out.println("       Unroutable connections: " + getUnroutableConnections().size());
            System.out.println("       Conflicting nodes: " + overUsedRnodes.size());
            for (RouteNode rnode : overUsedRnodes) {
                System.out.println("              " + rnode);
            }
        }
    }

    /**
     * Gets unrouted connections.
     * @return A list of unrouted connections.
     */
    private List<Connection> getUnroutableConnections() {
        List<Connection> unroutedConnections = new ArrayList<>();
        for (Connection connection : sortedIndirectConnections) {
            if (!connection.isRouted()) {
                unroutedConnections.add(connection);
            }
        }
        return unroutedConnections;
    }

    private List<Connection> getCongestedConnections() {
        List<Connection> congestedConnections = new ArrayList<>();
        for (Connection connection : sortedIndirectConnections) {
            if (connection.isCongested()) {
                congestedConnections.add(connection);
            }
        }
        return congestedConnections;
    }

    /**
     * Assigns a list of nodes to each connection and fix net routes if there are cycles and / or multi-driver nodes.
     */
    protected void postRouteProcess() {
        if (routeIteration > config.getMaxIterations()) {
            return;
        }

        // Perform LUT pin mapping updates
        if (lutPinSwapping &&
                !Boolean.getBoolean("rapidwright.rwroute.lutPinSwapping.deferIntraSiteRoutingUpdates")) {
            Map<SitePinInst, String> pinSwaps = new HashMap<>();
            for (Connection connection: indirectConnections) {
                SitePinInst oldSinkSpi = connection.getSink();
                if (!oldSinkSpi.isLUTInputPin() || !oldSinkSpi.isRouted()) {
                    continue;
                }

                List<RouteNode> rnodes = connection.getRnodes();
                RouteNode newSinkRnode = rnodes.get(0);
                if (newSinkRnode == connection.getSinkRnode()) {
                    continue;
                }
                connection.setSinkRnode(newSinkRnode);

                SitePin newSitePin = newSinkRnode.getSitePin();
                String existing = pinSwaps.put(oldSinkSpi, newSitePin.getPinName());
                assert(existing == null);
            }
            LUTTools.swapMultipleLutPins(pinSwaps);
        }

        assignNodesToConnections();

        // fix routes with cycles and / or multi-driver nodes
        List<NetWrapper> fixedRoutes = fixRoutes();
        if (config.isTimingDriven()) {
            updateTimingAfterFixingRoutes(fixedRoutes);
        }

        // Unset the routed state of all source pins
        for (Map.Entry<Net, NetWrapper> e : nets.entrySet()) {
            Net net = e.getKey();
            SitePinInst source = net.getSource();
            SitePinInst altSource = net.getAlternateSource();
            SiteInst si = source.getSiteInst();
            boolean altSourcePreviouslyRouted = altSource != null && altSource.isRouted();
            assert(source != null);
            boolean sourcePreviouslyRouted = source.isRouted();
            for (SitePinInst spi : Arrays.asList(source, altSource)) {
                if (spi != null) {
                    spi.setRouted(false);
                    assert(spi.getSiteInst() == si);
                }
            }

            // Set the routed state on those source pins that were actually used
            NetWrapper netWrapper = e.getValue();
            for (Connection connection : netWrapper.getConnections()) {
                // Examine getNodes() because connection.getRnodes() is empty for direct connections
                List<Node> nodes = connection.getNodes();
                if (nodes == null || nodes.isEmpty()) {
                    // Unroutable connection
                    continue;
                }

                // Set the routed state of the used source node
                // and if used and not already present, add it to the SiteInst
                Node sourceNode = nodes.get(nodes.size() - 1);
                SitePinInst usedSpi = null;
                for (SitePinInst spi : Arrays.asList(source, altSource)) {
                    if (spi != null && sourceNode.equals(spi.getConnectedNode())) {
                        usedSpi = spi;
                    }
                }
                if (usedSpi == null) {
                    throw new RuntimeException("ERROR: Unknown source node " + sourceNode + " on net " + net.getName());
                }

                // Now that we know this SitePinInst is used, make sure it exists in
                // the SiteInst
                usedSpi.setRouted(true);
                if (si.getSitePinInst(usedSpi.getName()) == null) {
                    si.addPin(usedSpi);
                }

                if (source.isRouted() && (altSource == null || altSource.isRouted())) {
                    // Break if all sources have been set to be routed
                    break;
                }
            }

            // If the alt source was previously routed, and is no longer, let's remove it
            if (altSource != null && altSourcePreviouslyRouted && !altSource.isRouted()) {
                // If altSource is not routed, then source must be routed
                assert(source.isRouted());
                altSource.getSiteInst().removePin(altSource);
                net.removePin(altSource, true);
                assert(source.isRouted());
                if (source.getName().endsWith("MUX")) {
                    assert(altSource.getName().endsWith("_O"));
                    // Add site routing back if we are keeping the MUX pin
                    si.routeIntraSiteNet(net, altSource.getBELPin(), altSource.getBELPin());
                }
            }
            // If the original source was routed and is now no longer used, it must
            // mean that the alternate source is used instead. Let's remove the main
            // source which will promote the alternate to main.
            if (sourcePreviouslyRouted && !source.isRouted()) {
                assert(altSource != null && altSource.isRouted());
                net.removePin(source, true);
                assert(net.getSource() == altSource);
                assert(net.getAlternateSource() == null);
                assert(altSource.isRouted());
                if (source.getName().endsWith("_O")) {
                    assert(altSource.getName().endsWith("MUX"));
                    // Add site routing back if we are keeping the MUX pin
                    si.routeIntraSiteNet(net, source.getBELPin(), source.getBELPin());
                }
            }
        }
    }

    /**
     * Checks if a connection should be routed.
     * @param connection The connection in question.
     * @return true, if the connection should be routed.
     */
    protected boolean shouldRoute(Connection connection) {
        if (routeIteration > 1) {
            if (connection.getCriticality() > minRerouteCriticality) {
                return true;
            }
        }

        if (!connection.hasAltSinks()) {
            // Check that this connection's exclusive sink node is used but never overused
            RouteNode sinkRnode = connection.getSinkRnode();
            assert(sinkRnode.countConnectionsOfUser(connection.getNetWrapper()) > 0);
            assert(!sinkRnode.isOverUsed());
        }

        return !connection.isRouted() || connection.isCongested();
    }

    /**
     * Computes and sets the minimum reroute criticality for re-routing critical connections.
     */
    private void setRerouteCriticality() {
        // Limit the number of critical connections to be routed based on minRerouteCriticality and reroutePercentage
        minRerouteCriticality = config.getMinRerouteCriticality();
        criticalConnections.clear();

        int maxNumberOfCriticalConnections = (int) (indirectConnections.size() * 0.01 * config.getReroutePercentage());
        for (Connection connection : indirectConnections) {
            if (connection.getCriticality() > minRerouteCriticality) {
                criticalConnections.add(connection);
            }
        }

        if (criticalConnections.size() > maxNumberOfCriticalConnections) {
            criticalConnections.sort((connection1, connection2) -> Float.compare(connection2.getCriticality(),connection1.getCriticality()));
            minRerouteCriticality = criticalConnections.get(maxNumberOfCriticalConnections).getCriticality();
        }
    }

    /**
     * Updates timing through static timing analysis and calculates connections' criticalities.
     */
    private void updateTiming() {
        updateTimingTimer.start();
        timingWeight = Math.min(timingWeight * config.getTimingMultiplier(), 1f);
        oneMinusTimingWeight = 1 - timingWeight;
        maxDelayAndTimingVertex = timingManager.calculateArrivalRequiredTimes();
        timingManager.calculateCriticality(sortedIndirectConnections,
                MAX_CRITICALITY, config.getCriticalityExponent());
        updateTimingTimer.stop();
    }

    /**
     * Updates timing after fixing routes of nets.
     * @param fixedRoutes A set of nets whose routes have been fixed.
     */
    private void updateTimingAfterFixingRoutes(List<NetWrapper> fixedRoutes) {
        timingManager.updateIllegalNetsDelays(fixedRoutes, nodesDelays);
        timingManager.patchUpDelayOfConnections(sortedIndirectConnections);
        updateTiming();
    }

    /**
     * Assigns a list nodes to each connection to complete the route path of it.
     */
    protected void assignNodesToConnections() {
        for (Connection connection : indirectConnections) {
            List<Node> nodes = new ArrayList<>();
            connection.setNodes(nodes);

            RouteNode sinkRnode = connection.getSinkRnode();
            List<RouteNode> rnodes = connection.getRnodes();
            if (rnodes.isEmpty()) {
                continue;
            }

            if (sinkRnode == rnodes.get(0)) {
                List<Node> switchBoxToSink = RouterHelper.findPathBetweenNodes(sinkRnode, connection.getSink().getConnectedNode());
                if (switchBoxToSink.size() >= 2) {
                    nodes.addAll(switchBoxToSink.subList(0, switchBoxToSink.size() - 1));
                }
            } else {
                // sinkRnode could be an alternate sink
                assert(isValidSink(connection, sinkRnode));

                // Assume that it doesn't need unprojecting back to the sink pin
                // since the sink node is a site pin
                assert(rnodes.get(0).getSitePin() != null);
            }

            nodes.addAll(rnodes);

            List<Node> sourceToSwitchBox = RouterHelper.findPathBetweenNodes(connection.getSource().getConnectedNode(), connection.getSourceRnode());
            if (sourceToSwitchBox.size() >= 2) {
                nodes.addAll(sourceToSwitchBox.subList(1, sourceToSwitchBox.size()));
            }
        }
    }

    /**
     * Sorts indirect connections for routing.
     */
    private void sortConnections() {
        sortedIndirectConnections.clear();
        sortedIndirectConnections.addAll(indirectConnections);
        Collections.sort(sortedIndirectConnections);
    }

    private void printIterationHeader(boolean timingDriven) {
        System.out.printf("------------------------------------------------------------------------------\n");
        if (timingDriven) {
            System.out.printf("%9s  %12s  %8s   %11s  %10s   %5s  %9s\n",
                    "         ", "Generated", "  RRG",    "  Routed",   "Nodes With", "CPD", "Total Run");
            System.out.printf("%9s  %12s  %8s   %11s  %10s   %5s  %9s\n",
                    "Iteration", "RRG Nodes", "Time (s)", "Connections", "Overlaps", "(ps)", "Time (s)");
            System.out.printf("---------  ----------------------   -----------  ----------   -----  ---------\n");
        } else {
            System.out.printf("%9s  %12s  %8s   %11s  %10s   %5s  %9s\n",
                    "         ", "Generated", "  RRG",    "  Routed",   "Nodes With", "    ", "Total Run");
            System.out.printf("%9s  %12s  %8s   %11s  %10s   %5s  %9s\n",
                    "Iteration", "RRG Nodes", "Time (s)", "Connections", "Overlaps", "    ", "Time (s)");
            System.out.printf("---------  ----------------------   -----------  ----------   ----------------\n");
        }
    }

    /**
     * Prints routing iteration statistics, including the iteration, number of connections routed in the iteration,
     * total runtime of the iteration, number of created rnodes, time spent in creating rnodes that is included in the
     * @param iterationRuntime Total runtime of this iteration.
     * @param rnodesCreationTime The runtime of generating routing resource graph nodes.
     */
    private void printRoutingIterationStatisticsInfo(float iterationRuntime, float rnodesCreationTime) {
        long overUsed = overUsedRnodes.size();
        if (config.isTimingDriven()) {
            System.out.printf("%4d       %12d  %8.2f   %11d  %10d   %5d  %9.2f\n",
                    routeIteration,
                    rnodesCreatedThisIteration,
                    rnodesCreationTime,
                    connectionsRoutedThisIteration.get(),
                    overUsed,
                    (short)(maxDelayAndTimingVertex == null? 0 : maxDelayAndTimingVertex.getFirst()),
                    iterationRuntime * 1e-9);
        } else {
            System.out.printf("%4d       %12d  %8.2f   %11d  %10d   %5s  %9.2f\n",
                    routeIteration,
                    rnodesCreatedThisIteration,
                    rnodesCreationTime,
                    connectionsRoutedThisIteration.get(),
                    overUsed,
                    "",
                    iterationRuntime * 1e-9);
        }
        if (overUsed == 0) System.out.printf("------------------------------------------------------------------------------\n");
    }

    /**
     * Updates the congestion cost factors.
     */
    private void updateCostFactors() {
        updateCongestionCosts.start();

        checkHus();

        // Inflate the present congestion factor
        presentCongestionFactor *= config.getPresentCongestionMultiplier();
        presentCongestionFactor = Math.min(presentCongestionFactor, config.getMaxPresentCongestionFactor());

        updateCost();

        updateCongestionCosts.stop();
    }

    /**
     * Updates present congestion cost and historical congestion cost of rnodes.
     */
    private void updateCost() {
        overUsedRnodes.clear();
        for (RouteNode rnode : routingGraph.getRnodes()) {
            int overuse = rnode.getOccupancy() - RouteNode.capacity;
            if (overuse > 0) {
                overUsedRnodes.add(rnode);
                rnode.setHistoricalCongestionCost(rnode.getHistoricalCongestionCost() + overuse * historicalCongestionFactor);
            }
        }
    }

    /**
     * Check whether to activate Hybrid Updating Strategy (HUS)
     */
    private void checkHus() {
        if (!hus) {
            return;
        }

        if (routeIteration == 1) {
            // Count the number of overused nodes
            long overUseCnt = 0;
            for (RouteNode rnode : routingGraph.getRnodes()) {
                if (rnode.isOverUsed()) {
                    overUseCnt++;
                }
            }
            husInitialCongested = (float) overUseCnt / sortedIndirectConnections.size() > config.getHusInitialCongestedThreshold();
        }

        if (husInitialCongested) {
            float congestedConnRatio = (float) connectionsRoutedThisIteration.get() / sortedIndirectConnections.size();
            if (congestedConnRatio < config.getHusActivateThreshold()) {
                // Activate HUS: slow down the present cost growth and increase historical cost growth instead
                float husAlpha = config.getHusAlpha();
                if (husAlpha >= config.getPresentCongestionMultiplier()) {
                    System.out.println("WARNING: HUS alpha is not less than the current present congestion multiplier.");
                }
                config.setPresentCongestionMultiplier(husAlpha);

                float husBeta = config.getHusBeta();
                if (husBeta <= historicalCongestionFactor) {
                    System.out.println("WARNING: HUS beta is not greater than the current historical congestion factor.");
                }
                historicalCongestionFactor = husBeta;

                // Disable HUS from being activated again
                hus = false;
            }
        }
    }

    /**
     * Computes node usage of each type and the total wirelength of the design.
     */
    private void computesNodeUsageAndTotalWirelength() {
        totalWL = 0;
        totalINTNodes = 0;
        nodeTypeUsage = new EnumMap<>(IntentCode.class);
        nodeTypeLength = new EnumMap<>(IntentCode.class);

        Set<Node> netNodes = new HashSet<>();
        for (Entry<Net,NetWrapper> e : nets.entrySet()) {
            NetWrapper netWrapper = e.getValue();
            for (Connection connection : netWrapper.getConnections()) {
                if (connection.getNodes() == null) {
                    continue;
                }
                    
                netNodes.addAll(connection.getNodes());
            }
            for (Node node : netNodes) {
                if (RouteNodeGraph.isExcludedTile(node)) {
                    continue;
                }
                totalINTNodes++;
                int wl = RouteNodeInfo.get(node, null).halfTileWirelength;
                totalWL += wl;

                RouterHelper.addNodeTypeLengthToMap(node, wl, nodeTypeUsage, nodeTypeLength);
            }
            netNodes.clear();
        }
    }

    static List<IntentCode> nodeUsageForUltraScale = new ArrayList<>();
    static {
        nodeUsageForUltraScale.add(IntentCode.NODE_SINGLE);
        nodeUsageForUltraScale.add(IntentCode.NODE_DOUBLE);
        nodeUsageForUltraScale.add(IntentCode.NODE_VQUAD);
        nodeUsageForUltraScale.add(IntentCode.NODE_HQUAD);
        nodeUsageForUltraScale.add(IntentCode.NODE_VLONG);
        nodeUsageForUltraScale.add(IntentCode.NODE_HLONG);
        nodeUsageForUltraScale.add(IntentCode.NODE_LOCAL);
        nodeUsageForUltraScale.add(IntentCode.NODE_PINBOUNCE);
        nodeUsageForUltraScale.add(IntentCode.NODE_PINFEED);
        nodeUsageForUltraScale.add(IntentCode.NODE_LAGUNA_DATA); // UltraScale+ only intent code,
                                                                 // but super long lines from UltraScale (which have
                                                                 // IntentCode.INTENT_DEFAULT are mapped to this)
    }

    static List<IntentCode> nodeUsageForVersal = new ArrayList<>();
    static {
        nodeUsageForVersal.add(IntentCode.NODE_VSINGLE);
        nodeUsageForVersal.add(IntentCode.NODE_HSINGLE);
        nodeUsageForVersal.add(IntentCode.NODE_VDOUBLE);
        nodeUsageForVersal.add(IntentCode.NODE_HDOUBLE);
        nodeUsageForVersal.add(IntentCode.NODE_VQUAD);
        nodeUsageForVersal.add(IntentCode.NODE_HQUAD);
        nodeUsageForVersal.add(IntentCode.NODE_VLONG7);
        nodeUsageForVersal.add(IntentCode.NODE_VLONG12);
        nodeUsageForVersal.add(IntentCode.NODE_HLONG6);
        nodeUsageForVersal.add(IntentCode.NODE_HLONG10);
        nodeUsageForVersal.add(IntentCode.NODE_CLE_BNODE);
        nodeUsageForVersal.add(IntentCode.NODE_INTF_BNODE);
        nodeUsageForVersal.add(IntentCode.NODE_CLE_CNODE);
        nodeUsageForVersal.add(IntentCode.NODE_INTF_CNODE);
        nodeUsageForVersal.add(IntentCode.NODE_PINBOUNCE);
        nodeUsageForVersal.add(IntentCode.NODE_IMUX);
        // NODE_PINFEED exists on Versal but is behind a NODE_IMUX
        // and gets projectInputPinToINTNode() -ed away
        nodeUsageForVersal.add(IntentCode.NODE_SLL_DATA);
    }

    /**
     * Fixes routes of nets with routing path cycles and multi-driver nodes.
     */
    private List<NetWrapper> fixRoutes() {
        List<NetWrapper> fixedRoutes = new ArrayList<>();
        int sequence = connectionsRouted.get() + 1;
        for (Entry<Net,NetWrapper> e : nets.entrySet()) {
            NetWrapper netWrapper = e.getValue();
            if (netWrapper.hasMultipleDrivers(sequence)) {
                fixedRoutes.add(netWrapper);
                if (config.isTimingDriven()) {
                    addNodesDelays(netWrapper);
                }
                for (Connection connection : netWrapper.getConnections()) {
                    if (connection.isDirect()) {
                        continue;
                    }
                    ripUp(connection);
                }
                RouteFixer graphHelper = new RouteFixer(netWrapper, routingGraph);
                graphHelper.finalizeRoutesOfConnections();
            }
            sequence++;
        }
        return fixedRoutes;
    }

    /**
     * Adds nodes and delay values of a routed to the map.
     * @param net The routed net.
     */
    private void addNodesDelays(NetWrapper net) {
        for (Connection connection:net.getConnections()) {
            for (RouteNode rnode : connection.getRnodes()) {
                nodesDelays.put(rnode, rnode.getDelay());
            }
        }
    }

    /**
     * Rips up a connection.
     * @param connection The connection to be ripped up.
     */
    protected void ripUp(Connection connection) {
        List<RouteNode> rnodes = connection.getRnodes();
        if (rnodes.isEmpty()) {
            assert(!connection.isRouted());
            return;
        }

        RouteNode sinkRnode = rnodes.get(0);
        if (sinkRnode == connection.getSinkRnode()) {
            if (!connection.hasAltSinks()) {
                // Sink is exclusive -- do not rip up
                rnodes = rnodes.subList(1, rnodes.size() - 1);
            }
        } else {
            // sinkRnode could be an alternate sink
            assert(isValidSink(connection, sinkRnode));
            // In which case it cannot be exclusive -- rip up all used nodes
        }

        NetWrapper netWrapper = connection.getNetWrapper();
        for (RouteNode rnode : rnodes) {
            rnode.decrementUser(netWrapper);
        }

        assert(sinkRnode.countConnectionsOfUser(netWrapper) > 0 ||
               (sinkRnode.countConnectionsOfUser(netWrapper) == 0 && connection.hasAltSinks()));
    }

    /**
     * Updates the users and present congestion cost of rnodes used by a routed connection.
     * @param connection The routed connection.
     */
    private void updateUsersAndPresentCongestionCost(Connection connection) {
        List<RouteNode> rnodes = connection.getRnodes();
        if (rnodes.isEmpty()) {
            assert(!connection.isRouted());
            return;
        }

        RouteNode sinkRnode = rnodes.get(0);
        if (sinkRnode == connection.getSinkRnode()) {
            if (!connection.hasAltSinks()) {
                // Sink is exclusive -- do not increment
                rnodes = rnodes.subList(1, rnodes.size() - 1);
            }
        } else {
            // sinkRnode could be an alternate sink
            assert(isValidSink(connection, sinkRnode));
            // In which case it cannot be exclusive -- increment all used nodes
        }

        NetWrapper netWrapper = connection.getNetWrapper();
        for (RouteNode rnode : rnodes) {
            rnode.incrementUser(netWrapper);
        }
        assert(sinkRnode.countConnectionsOfUser(netWrapper) == 1 ||
               (sinkRnode.countConnectionsOfUser(netWrapper) > 1 &&
                       // An alternate sink (e.g. LUT routethru to FF) that serves more than one FF
                       (sinkRnode != connection.getSinkRnode() ||
                        // A bounce node that is this sink and also used to serve a different sink
                        sinkRnode.getIntentCode() == IntentCode.NODE_PINBOUNCE)
               )
        );
    }

    /**
     * Sets a list of {@link PIP} instances of each {@link Net} instance and checks if there is any PIP overlaps.
     */
    protected void setPIPsOfNets() {
        for (Entry<Net,NetWrapper> e : nets.entrySet()) {
            NetWrapper netWrapper = e.getValue();
            Net net = netWrapper.getNet();
            assert(net.getType() == NetType.WIRE && !NetTools.isGlobalClock(net));

            Set<PIP> newPIPs = new HashSet<>();
            // Start by carrying over all fixed PIPs (even those that didn't get used)
            for (PIP pip : net.getPIPs()) {
                if (pip.isPIPFixed()) {
                    newPIPs.add(pip);
                }
            }
            for (Connection connection:netWrapper.getConnections()) {
                List<PIP> pips = RouterHelper.getConnectionPIPs(connection);
                newPIPs.addAll(pips);
            }

            net.setPIPs(newPIPs);

            // When multiple sources are used (e.g. A_O and AMUX) then
            // mark the first PIP driven by either source as a logical driver
            SitePinInst source = net.getSource();
            SitePinInst altSource = net.getAlternateSource();
            if (altSource != null && source.isRouted() && altSource.isRouted()) {
                Tile sourceTile = altSource.getTile();
                for (PIP pip : net.getPIPs()) {
                    if (pip.getTile() != sourceTile) {
                        continue;
                    }
                    if (pip.isRouteThru()) {
                        continue;
                    }
                    Node startNode = pip.getStartNode();
                    IntentCode startIntent = startNode.getIntentCode();
                    if (startIntent != IntentCode.NODE_CLE_OUTPUT &&  // US+ and Versal
                            startIntent != IntentCode.NODE_OUTPUT) {  // US
                        continue;
                    }
                    SitePin sp = startNode.getSitePin();
                    if (sp.getPinName().equals(source.getName())) {
                        pip.setIsLogicalDriver(true);
                        break;
                    }
                }
            }
        }

        checkPIPsUsage();
    }

    /**
     * Checks if there are PIP overlaps among routed nets.
     */
    protected void checkPIPsUsage() {
        Map<PIP, Set<Net>> pipsUsage = new HashMap<>();
        for (Net net : design.getNets()) {
            for (PIP pip:net.getPIPs()) {
                pipsUsage.computeIfAbsent(pip, (k) -> new HashSet<>()).add(net);
            }
        }
        int pipsError = 0;
        for (Entry<PIP, Set<Net>> pipNets : pipsUsage.entrySet()) {
            if (pipNets.getValue().size() > 1) {
                if (pipsError < 10) {
                    System.out.println("pip " + pipNets.getKey() + " users = " + pipsUsage.get(pipNets.getKey()));
                }
                pipsError++;
            }
        }
        if (pipsError > 0)
            System.err.println("ERROR: PIPs overused error: " + pipsError);
        else
            System.out.println("\nINFO: No PIP overlaps\n");
    }

    /**
     * Class encapsulating all state necessary to route the included connection
     */
    protected static class ConnectionState {
        /** Priority queue of all candidate nodes to be considered for routing this connection */
        protected final PriorityQueue<RouteNode> queue;
        /** The list of nodes marked as a target for this connection */
        protected final List<RouteNode> targets;

        /** Connection to be routed */
        protected Connection connection;
        /** Unique sequence count for this routing attempt for this connection */
        protected int sequence;

        /** Pre-computed weights specific to this connection for one routing iteration */
        protected float rnodeCostWeight;
        protected float shareWeight;
        protected float rnodeWLWeight;
        protected float estWlWeight;
        protected float dlyWeight;
        protected float estDlyWeight;

        /** Number of nodes popped during the routing of this connection */
        protected int nodesPopped;

        protected boolean earlyTermination;

        protected ConnectionState() {
            this.queue = new PriorityQueue<>();
            this.targets = new ArrayList<>();
        }
    }

    /**
     * Routes a connection.
     * @param connection The connection to route.
     */
    protected void routeIndirectConnection(Connection connection) {
        ConnectionState state = getConnectionState();
        state.connection = connection;
        state.sequence = connectionsRouted.incrementAndGet();
        connectionsRoutedThisIteration.incrementAndGet();
        state.rnodeCostWeight = 1 - connection.getCriticality();
        state.shareWeight = (float) (Math.pow(state.rnodeCostWeight, config.getShareExponent()));
        state.rnodeWLWeight = state.rnodeCostWeight * oneMinusWlWeight;
        state.estWlWeight = state.rnodeCostWeight * wlWeight;
        state.dlyWeight = connection.getCriticality() * oneMinusTimingWeight / 100f;
        state.estDlyWeight = connection.getCriticality() * timingWeight;
        state.nodesPopped = 0;
        state.earlyTermination = false;

        PriorityQueue<RouteNode> queue = state.queue;
        assert(queue.isEmpty());

        prepareRouteConnection(state);

        RouteNode rnode;
        while ((rnode = queue.poll()) != null) {
            state.nodesPopped++;
            if (rnode.isTarget()) {
                break;
            }
            exploreAndExpand(state, rnode);
        }
        nodesPushed.addAndGet(state.nodesPopped + queue.size());
        nodesPopped.addAndGet(state.nodesPopped);

        if (rnode != null) {
            queue.clear();
            finishRouteConnection(connection, rnode);
            if (!connection.isRouted()) {
                List<RouteNode> rnodes = connection.getRnodes();
                throw new RuntimeException("ERROR: Unable to save routing for connection " + connection + "\n" +
                                           "       Backtracking terminated at " + rnodes.get(rnodes.size() -1));
            }
            if (config.isTimingDriven()) {
                connection.updateRouteDelay();
            }
            assert(connection.isRouted());
        } else {
            assert(queue.isEmpty());
            // Clears previous route of the connection
            connection.resetRoute();
            connection.setRouted(false);
            assert(connection.getRnodes().isEmpty());
        }

        // Reset the nodes marked as this connection's target(s)
        List<RouteNode> targets = state.targets;
        for (RouteNode target : targets) {
            target.clearTarget();
        }
        targets.clear();
    }

    protected void enlargeBoundingBox(Connection connection) {
        if (!config.isEnlargeBoundingBox()) {
            return;
        }

        connection.enlargeBoundingBox(config.getExtensionXIncrement(), config.getExtensionYIncrement());
    }

    protected void abandonConnectionIfUnroutable(Connection connection) {
        if (!config.isUseBoundingBox() || config.isEnlargeBoundingBox()) {
            return;
        }

        System.out.println("INFO: Abandoning\n");

        // Since bounding box is never enlarged there is no hope of routing this connection so abandon it
        indirectConnections.remove(connection);
        sortedIndirectConnections.remove(connection);
    }

    /**
     * Deals with a failed connection by possible output pin swapping and unrouting preserved nets if the router is in the soft preserve mode.
     * @param connection The failed connection.
     */
    protected boolean handleUnroutableConnection(Connection connection) {
        enlargeBoundingBox(connection);
        if (routeIteration == 1 && swapOutputPin(connection)) {
            return true;
        }
        abandonConnectionIfUnroutable(connection);
        return false;
    }

    protected boolean handleCongestedConnection(Connection connection) {
        enlargeBoundingBox(connection);
        return false;
    }

    /**
     * Swaps the output pin of a connection, if its net has an alternative output pin.
     * @param connection The connection in question.
     * @return true, if the output pin has been swapped.
     */
    protected boolean swapOutputPin(Connection connection) {
        SitePinInst source = connection.getSource();
        Pair<SitePinInst,RouteNode> altSourceAndRnode = connection.getOrCreateAlternateSource(routingGraph);
        if (altSourceAndRnode == null) {
            return false;
        }

        SitePinInst altSource = altSourceAndRnode.getFirst();
        System.out.println("INFO: Swap source from " + source + " to " + altSource + "\n");
        connection.setSource(altSource);

        RouteNode altSourceRnode = altSourceAndRnode.getSecond();
        connection.setSourceRnode(altSourceRnode);

        connection.setRouted(false);
        return true;
    }

    /**
     * Completes the routing process of a connection.
     * @param connection The routed target connection.
     */
    protected void finishRouteConnection(Connection connection, RouteNode rnode) {
        boolean routed = saveRouting(connection, rnode);
        connection.setRouted(routed);
        if (routed) {
            updateUsersAndPresentCongestionCost(connection);
        }
    }

    protected boolean isValidSink(Connection connection, RouteNode rnode) {
        return connection.getSinkRnode() == rnode || connection.getAltSinkRnodes().contains(rnode);
    }

    /**
     * Traces back for a connection from its sink rnode to its source, in order to build and store the routing path.
     * @param connection: The connection that is being routed.
     * @param rnode RouteNode to start backtracking from.
     * @return True if backtracking successful.
     */
    protected boolean saveRouting(Connection connection, RouteNode rnode) {
        if (!isValidSink(connection, rnode)) {
            List<RouteNode> prevRouting = connection.getRnodes();
            // Check that this is the sink path marked by prepareRouteConnection()
            if (!connection.isRouted() || prevRouting.isEmpty() || !rnode.isTarget()) {
                throw new RuntimeException("Unexpected rnode to backtrack from: " + rnode);
            }
            // Backtrack from the sink used on that sink path
            rnode = prevRouting.get(0);
        }

        connection.resetRoute();
        do {
            connection.addRnode(rnode);
        } while ((rnode = rnode.getPrev()) != null);

        List<RouteNode> rnodes = connection.getRnodes();
        RouteNode sourceRnode = rnodes.get(rnodes.size() - 1);
        // Only successfully routed if backtracked to this connection's source node
        return sourceRnode == connection.getSourceRnode();
    }

    /**
     * Explores children (downhill rnodes) of a rnode for routing a connection and pushes the child into the queue,
     * if it is the target or is an accessible routing resource.
     * @param state State from the connection that is being routed.
     * @param rnode The rnode popped out from the queue.
     */
    private void exploreAndExpand(ConnectionState state, RouteNode rnode) {
        final boolean longParent = config.isTimingDriven() && DelayEstimatorBase.isLong(rnode);
        final Connection connection = state.connection;
        final int sequence = state.sequence;
        final PriorityQueue<RouteNode> queue = state.queue;
        final NetWrapper netWrapper = connection.getNetWrapper();
        final RouteNodeType rnodeType = rnode.getType();

        for (RouteNode childRNode : rnode.getChildren(routingGraph)) {
            if (childRNode.isVisited(sequence)) {
                // Node must be in queue already

                // Targets that are visited more than once must be overused
                assert(!childRNode.isTarget() || childRNode.willOverUse(netWrapper));

                // Note: it is possible this is a cheaper path to childRNode; however, because the
                // PriorityQueue class does not support (efficiently) reducing the cost of nodes
                // already in the queue, this opportunity is discarded
                continue;
            }

            // If childRnode is preserved, then it must be preserved for the current net we're routing
            Net preservedNet;
            assert((preservedNet = routingGraph.getPreservedNet(childRNode)) == null ||
                    preservedNet == connection.getNet());

            if (childRNode.isTarget()) {
                if (childRNode.getType().isAnyExclusiveSink()) {
                    // This sink must be exclusively reserved for this connection already
                    assert((childRNode == connection.getSinkRnode() && !connection.hasAltSinks()) ||
                           // Or be an exclusive BOUNCE sink for a different connection on the same net
                           childRNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                    assert(!childRNode.isOverUsed());
                    assert(!childRNode.willOverUse(netWrapper));
                    assert(childRNode.countConnectionsOfUser(netWrapper) == 1 ||
                           childRNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                    state.earlyTermination = true;
                } else {
                    // Target is not an exclusive sink, only early terminate if this net will not
                    // (further) overuse this node
                    state.earlyTermination = !childRNode.willOverUse(netWrapper);
                }

                if (state.earlyTermination) {
                    assert(!childRNode.isVisited(sequence));
                    nodesPushed.addAndGet(queue.size());
                    queue.clear();
                }
            } else {
                if (!isAccessible(childRNode, connection)) {
                    continue;
                }
                RouteNodeType childType = childRNode.getType();
                switch (childType) {
                    case LOCAL_LEADING_TO_SLL:
                        // Nodes leading to a super long line are not looked ahead into (expanded immediately), but get
                        // lower bounds that already account for it (see remainingCrossingCost())
                    case LOCAL:
                    case LOCAL_SIDE_DEPENDS_ON_SINK:
                    case LOCAL_RESERVED:
                        if (!routingGraph.isAccessible(childRNode, rnode, connection)) {
                            continue;
                        }
                        // Verify invariant that local wires on one side (half) stay on that side ...
                        assert(!rnodeType.isSidedLocal() ||
                                (childType.isSidedLocal() && rnode.isOnEastSide() == childRNode.isOnEastSide()) ||
                                // ... unless it's an exclusive sink using a LOCAL_RESERVED node
                                (childType == RouteNodeType.LOCAL_RESERVED && connection.getSinkRnode().getType().isExclusiveSinkCtrlOrSllInput()) ||
                                // ... or unless it's a Versal SLL input, or a BNODE in an SLL tile leading to one that
                                // crosses SLRs (which can end on the other side of the SLL tile to what drives it, and
                                // like such an SLL input is accessible from either side; see RouteNodeGraph.isAccessible())
                                childRNode.getIntentCode() == IntentCode.NODE_SLL_INPUT ||
                                (routingGraph.isVersal && childType.isLocalLeadingToSLL()));
                        break;
                    case NON_LOCAL_LEADING_TO_SLL: // UltraScale/UltraScale+ only
                    case NON_LOCAL:
                    case NON_LOCAL_INHERIT_PREV_SIDE:
                        // LOCALs cannot connect to NON_LOCALs except
                        //   (a) via a LUT routethru: IMUX (LOCAL*) -> CLE_CLE_*_SITE_0_[A-H]_O
                        //   (b) with LUT routethrus (with which every IMUX is in the routing graph): IMUX (LOCAL) ->
                        //       a LAG_MUX_ATOM_\\d+_TXOUT whose super long line does not cross SLRs (those that do are
                        //       LOCAL_LEADING_TO_SLL, as is the IMUX that drives them)
                        //   (c) on Versal, CLE/*LAG*_PIN -> CLE/*[A-H]Q2?_PIN
                        assert(!rnodeType.isAnyLocal() ||
                               (routingGraph.lutRoutethru && rnode.getIntentCode() == IntentCode.NODE_PINFEED) ||
                                // Allow CLE/*LAG*_PIN -> CLE/*[A-H]Q2?_PIN
                                (routingGraph.isVersal && connection.isCrossSLR() &&
                                       routingGraph.isVersalLagOutRoutethru(rnode, childRNode))
                        );

                        if (!routingGraph.isAccessible(childRNode, rnode, connection)) {
                            continue;
                        }
                        if (!config.isUseUTurnNodes() && childRNode.getDelay() > 10000) {
                            // To filter out those nodes that are considered to be excluded with the masking resource approach,
                            // such as U-turn shape nodes near the boundary
                            continue;
                        }
                        break;
                    case EXCLUSIVE_SINK:
                    case EXCLUSIVE_SINK_CTRL:
                    case EXCLUSIVE_SINK_SLL_INPUT:
                    case EXCLUSIVE_SINK_NON_LOCAL:
                        // An exclusive sink must be reached from a local node on the same side (half) ...
                        assert(childType != RouteNodeType.EXCLUSIVE_SINK ||
                                (rnodeType == RouteNodeType.LOCAL && rnode.isOnEastSide() == childRNode.isOnEastSide()) ||
                                // ... or must be an INODE that services Laguna but also feedsthrough above/below to a SLICE sink
                                rnodeType.isLocalLeadingToSLL());
                        // ... unless it is a CTRL sink (or SLL input) that can be reached from either side
                        assert(!childType.isExclusiveSinkCtrlOrSllInput() ||
                               rnodeType == RouteNodeType.LOCAL_SIDE_DEPENDS_ON_SINK ||
                               // [BC]NODEs are LOCAL on the east/west side since they connect to INODEs, but also service CTRL sinks
                               (routingGraph.isVersal && EnumSet.of(IntentCode.NODE_CLE_BNODE, IntentCode.NODE_CLE_CNODE,
                                                                    IntentCode.NODE_INTF_BNODE, IntentCode.NODE_INTF_CNODE)
                                       .contains(rnode.getIntentCode())));
                        if (!isAccessibleSink(childRNode, connection)) {
                            continue;
                        }
                        assert(childRNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                        assert(childRNode.countConnectionsOfUser(netWrapper) > 0);
                        assert(!childRNode.willOverUse(netWrapper));
                        break;
                    case SUPER_LONG_LINE:
                        assert(connection.isCrossSLR() &&
                               connection.getSinkRnode().getSLRIndex(routingGraph) != rnode.getSLRIndex(routingGraph));
                        break;
                    default:
                        throw new RuntimeException("Unexpected rnode type: " + childType);
                }
            }

            evaluateCostAndPush(state, rnode, longParent, childRNode);
            if (state.earlyTermination) {
                assert(queue.size() == 1 && queue.peek().isTarget() && !queue.peek().willOverUse(netWrapper));
                // Target is uncongested and the only thing in the (previously cleared) queue, abandon immediately
                break;
            }
        }
    }

    /**
     * Checks if a routing resource is accessible.
     * @param child The routing resource in question.
     * @param connection The connection to route.
     * @return true, if no bounding box constraints, or if the routing resource is within the connection's bounding box when use the bounding box constraint.
     */
    protected boolean isAccessible(RouteNode child, Connection connection) {
        return !config.isUseBoundingBox() || child.isInConnectionBoundingBox(connection);
    }

    protected boolean isAccessibleSink(RouteNode child, Connection connection) {
        assert(child.getType().isAnyExclusiveSink());
        assert(!child.isOverUsed());

        if (child.isTarget()) {
            return true;
        }

        if (child.countConnectionsOfUser(connection.getNetWrapper()) == 0 ||
            child.getIntentCode() != IntentCode.NODE_PINBOUNCE) {
            // Inaccessible if child is not a sink pin of another connection on the same
            // net, or it is not a PINBOUNCE node
            return false;
        }

        // Must be a NODE_PINBOUNCE that is an exclusive sink of some other connection on the same net
        return true;
    }

    /**
     * Evaluates the cost of a child of a rnode and pushes the child into the queue after cost evaluation.
     * For a connection that must cross SLRs, a node on the sending side of a chain leading to a super long line towards
     * the sink is given the crossing discount (see remainingCrossingCost()), unless this net would overuse it, when it
     * is priced as if it were not on a chain.
     * @param state State from the connection that is being routed.
     * @param rnode The parent rnode of the child in question.
     * @param longParent A boolean value to indicate if the parent is a Long node
     * @param childRnode The child rnode in question.
     */
    protected void evaluateCostAndPush(ConnectionState state,
                                       RouteNode rnode,
                                       boolean longParent,
                                       RouteNode childRnode) {
        final Connection connection = state.connection;
        final int countSourceUses = childRnode.countConnectionsOfUser(connection.getNetWrapper());
        final float sharingFactor = 1 + state.shareWeight * countSourceUses;
        // Set the prev pointer, as RouteNode.getEndTileYCoordinate() and
        // RouteNode.getSLRIndex() require this
        childRnode.setPrev(rnode);

        float newPartialPathCost = rnode.getUpstreamPathCost();
        newPartialPathCost += state.rnodeCostWeight * getNodeCost(childRnode, connection, countSourceUses, sharingFactor);
        newPartialPathCost += state.rnodeWLWeight * 0.5f * childRnode.getHalfTileWirelength() / sharingFactor;
        if (config.isTimingDriven()) {
            newPartialPathCost += state.dlyWeight * (childRnode.getDelay() + DelayEstimatorBase.getExtraDelay(childRnode, longParent));
        }

        int childX = childRnode.getEndTileXCoordinate();
        int childY = childRnode.getEndTileYCoordinate();
        RouteNode sinkRnode = connection.getSinkRnode();
        // Sinks begin where they end (in X), other than Versal CTRL pins in CLE_BC_CORE/SLL tiles and SLL inputs,
        // whose end is the INT tile column that they serve (see RouteNodeInfo.get())
        int sinkX = sinkRnode.getEndTileXCoordinate();
        assert(sinkX == sinkRnode.getBeginTileXCoordinate() ||
                sinkRnode.getIntentCode() == IntentCode.NODE_CLE_CTRL || routingGraph.isOnBothSides(sinkRnode));
        int sinkY = sinkRnode.getBeginTileYCoordinate();
        int deltaX = Math.abs(childX - sinkX);
        // Horizontal distance is measured in half tiles (each INT tile has a west and an east half) so that
        // nodes arriving at the opposite side of an INT tile to the sink are estimated to be further away
        int childHalfX = childRnode.getEndHalfTileXCoordinate();
        int sinkHalfX = sinkRnode.getEndHalfTileXCoordinate();
        RouteNodeType childType = childRnode.getType();
        if ((childType == RouteNodeType.LOCAL_SIDE_DEPENDS_ON_SINK || childType == RouteNodeType.LOCAL_RESERVED) &&
                sinkRnode.getType().isExclusiveSinkCtrlOrSllInput() && childRnode.getTile() == sinkRnode.getTile()) {
            // INT_NODE_GLOBAL_* (UltraScale/UltraScale+) serve the CTRL_* pins on either side of their INT tile, as do
            // the [BC]NODEs reserved for the CTRL pins and SLL inputs in Versal CLE_BC_CORE/INTF_*/SLL tiles
            childHalfX = sinkHalfX;
        }
        if (childType == RouteNodeType.NON_LOCAL_INHERIT_PREV_SIDE) {
            // Nodes that serve both sides of where they end are considered to be on the side that the path arrived
            // from, so that moving onto them neither gains nor loses distance to the sink in X (only a subsequent
            // move to a sided node does). Record that side on the node itself so that its own children can read it
            // directly (rnode, if also such a node, had its side recorded when it was pushed)
            childRnode.setInheritedSide(rnode);
            childHalfX = childRnode.getEndHalfTileXCoordinate();
        } else if (connection.isCrossSLR() && routingGraph.isOnBothSides(childRnode)) {
            // Versal SLL input/output/data: between the east side of one INT tile (childHalfX) and the west side of
            // the next; take whichever of those is nearer to the sink
            childHalfX = Math.max(childHalfX, Math.min(childHalfX + 1, sinkHalfX));
        }
        int deltaHalfX = getDeltaHalfX(sinkRnode, childHalfX, sinkHalfX);
        int deltaY = Math.abs(childY - sinkY);
        // For a connection that must still cross SLRs: how many super long lines it must still take (each crossing one
        // SLR boundary, a whole SUPER_LONG_LINE_LENGTH_IN_TILES rows towards the sink), and, if this node is on the
        // sending side of a chain of nodes leading to one towards the sink and is given the crossing discount, the
        // half-tile X coordinate of the column from which that is taken (else -1)
        int deltaSLR = 0;
        int rnodeReceivingIndex = -1;
        int remainingReceivingNodes = 0;
        int sendingHalfX = -1;
        if (connection.isCrossSLR()) {
            assert(!childRnode.getType().isLocalLeadingToSLL() ||
                    (routingGraph.isNorthboundSLLRow(childRnode.getTile().getTileYCoordinate())
                            ? connection.isCrossSLRnorth() : connection.isCrossSLRsouth()) ||
                    (deltaX == 0 && deltaY <= 1));

            rnodeReceivingIndex = getReceivingChainIndex(rnode);
            remainingReceivingNodes = getRemainingReceivingNodes(connection, childRnode, rnodeReceivingIndex);
            deltaSLR = Math.abs(sinkRnode.getSLRIndex(routingGraph) - childRnode.getSLRIndex(routingGraph));
            if (deltaSLR != 0) {
                if (remainingReceivingNodes == 0 && (childType == RouteNodeType.LOCAL_LEADING_TO_SLL ||
                        childType == RouteNodeType.NON_LOCAL_LEADING_TO_SLL)) {
                    // On the sending side of a chain (identified by type alone on all three series)
                    boolean towardsSink = routingGraph.isNorthboundSLLRow(childY) ? connection.isCrossSLRnorth()
                                                                                    : connection.isCrossSLRsouth();
                    // Only an UltraScale/UltraScale+ SDQ node or intra-tile single (NON_LOCAL_LEADING_TO_SLL), which
                    // every connection may use, can lead away from the sink: RouteNodeGraph.isAccessible() (rule (b))
                    // keeps a crossing connection off LOCAL_LEADING_TO_SLL nodes outside the sink's SLR that do
                    assert(towardsSink || childType == RouteNodeType.NON_LOCAL_LEADING_TO_SLL);
                    // A sending-chain node that this net would overuse gets no discount (priced as if not on a chain).
                    // Only this node is checked, so a node further along the chain may be discounted again; that node
                    // must still overcome this one's congestion (in its path cost), and those further along are
                    // expected to be overused too
                    if (towardsSink && !childRnode.willOverUse(connection.getNetWrapper())) {
                        // Where the super long line is taken from: on UltraScale/UltraScale+, the west half of the
                        // node's INT tile column (see RouteNodeGraph.populateLagunaLookups()); on Versal, the east half
                        // of the INT tile to the west of its SLL tile column, where an SLL input ends (see
                        // RouteNodeInfo.get()) and next to which a BNODE in an SLL tile ends (on either side, at the
                        // INT tile whose INODEs it also drives)
                        int endHalfX = childRnode.getEndHalfTileXCoordinate();
                        sendingHalfX = routingGraph.isVersal ? ((endHalfX - 1) & ~1) + 1 : endHalfX & ~1;
                        assert(!routingGraph.isVersal || childRnode.getIntentCode() != IntentCode.NODE_CLE_BNODE ||
                                (sendingHalfX == 2 * RouteNodeInfo.getWestIntTileXCoordinate(childRnode.getTile()) + 1 &&
                                 childY == childRnode.getTile().getTileYCoordinate()));
                    }
                }
                // The rows that wires other than super long lines must still cover: those on this side of the super
                // long lines, and from where the last lands to the sink (when the sink is less than their length away
                // in Y, the super long line overshoots it and they must come back)
                deltaY = Math.abs(deltaY - deltaSLR * routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);

                if (routingGraph.nextCrossingHalfColumn != null) {
                    // Account for any detours that must be taken to get to the closest column from which super long
                    // lines can cross SLRs (a Laguna column on UltraScale/UltraScale+, an SLL column on Versal) and
                    // from there onto the sink.
                    // All distances are in half tiles (see RouteNodeGraph.nextCrossingHalfColumn).
                    int nextColumn = routingGraph.nextCrossingHalfColumn[childHalfX];
                    int prevColumn = routingGraph.prevCrossingHalfColumn[childHalfX];
                    if (nextColumn == prevColumn) {
                        // On top of the column
                    } else {
                        assert(rnode.getType() != RouteNodeType.SUPER_LONG_LINE);

                        final int deltaHalfXToNextColumn;
                        final int deltaHalfXToPrevColumn;
                        final int deltaHalfXToAndFromNextColumn;
                        final int deltaHalfXToAndFromPrevColumn;
                        if (nextColumn == Integer.MAX_VALUE || (nextColumn >> 1) >= connection.getXMaxBB()) {
                            deltaHalfXToNextColumn = Integer.MAX_VALUE;
                            deltaHalfXToAndFromNextColumn = Integer.MAX_VALUE;
                        } else {
                            deltaHalfXToNextColumn = nextColumn - childHalfX;
                            deltaHalfXToAndFromNextColumn = deltaHalfXToNextColumn + Math.abs(sinkHalfX - nextColumn);
                        }
                        if (prevColumn == Integer.MIN_VALUE || (prevColumn >> 1) <= connection.getXMinBB()) {
                            deltaHalfXToPrevColumn = Integer.MAX_VALUE;
                            deltaHalfXToAndFromPrevColumn = Integer.MAX_VALUE;
                        } else {
                            deltaHalfXToPrevColumn = childHalfX - prevColumn;
                            deltaHalfXToAndFromPrevColumn = deltaHalfXToPrevColumn + Math.abs(sinkHalfX - prevColumn);
                        }
                        final int maxDetourHalfX = 2 * maxDetourToSnapBackToPrevLagunaColumn;
                        if (deltaHalfXToNextColumn == deltaHalfXToPrevColumn) {
                            // Equidistant from both columns, prefer the one closer when considering to/from the sink
                            deltaHalfX = Math.min(deltaHalfXToAndFromNextColumn, deltaHalfXToAndFromPrevColumn);
                        } else if (deltaHalfXToNextColumn < deltaHalfXToPrevColumn &&
                                deltaHalfXToAndFromNextColumn <= deltaHalfXToAndFromPrevColumn + maxDetourHalfX) {
                            // Closer to the next column and not detouring more than 4 tiles extra to/from using the prev column
                            assert(deltaHalfX <= deltaHalfXToAndFromNextColumn);
                            deltaHalfX = deltaHalfXToAndFromNextColumn;
                        } else if (deltaHalfXToPrevColumn < deltaHalfXToNextColumn &&
                                deltaHalfXToAndFromPrevColumn <= deltaHalfXToAndFromNextColumn + maxDetourHalfX) {
                            // Closer to the next column and not detouring more than 4 tiles extra to/from using the prev column
                            assert(deltaHalfX <= deltaHalfXToAndFromPrevColumn);
                            deltaHalfX = deltaHalfXToAndFromPrevColumn;
                        } else {
                            // Pretty much same distance to/from both columns; prefer the closer to column
                            deltaHalfX = (deltaHalfXToNextColumn < deltaHalfXToPrevColumn) ? deltaHalfXToAndFromNextColumn
                                                                                           : deltaHalfXToAndFromPrevColumn;
                        }
                    }
                    assert(deltaHalfX >= 0 && deltaHalfX < Integer.MAX_VALUE);
                }
            }
        }

        // Each super long line still to be taken (other than the one whose discounted sending chain this node is on) is
        // charged at its span, at the estimate's rate per row (see remainingCrossingCost())
        final int sllLength = routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES;
        float newTotalPathCost;
        if (sendingHalfX < 0) {
            newTotalPathCost = addEstimate(state, newPartialPathCost, deltaHalfX, deltaY + deltaSLR * sllLength,
                    sharingFactor);
            if (RouteNode.isSingleDoubleQuadLong(childRnode.getIntentCode())) {
                newTotalPathCost += state.rnodeCostWeight * RouteNode.PENDING_NODE_COST;
            }
            if (connection.isCrossSLR()) {
                newTotalPathCost = remainingCrossingCost(state, childRnode, rnodeReceivingIndex,
                        remainingReceivingNodes, -1, newTotalPathCost);
            }
        } else {
            // On the sending side of a crossing chain, with the discount: the rest of this chain is charged at its true
            // cost, and it is no longer the estimate that takes the node to the crossing column but the rest of the
            // chain, so estimate from where its super long line lands: on UltraScale/UltraScale+, the west half of the
            // same INT tile column (see RouteNodeGraph.populateLagunaLookups()); on Versal, either side of the same SLL
            // tile column, whichever is nearer to the sink (as for an SLL itself, above). (Not divided by the sharing
            // factor.)
            int landHalfX = sendingHalfX;
            if (routingGraph.isVersal) {
                landHalfX = Math.max(landHalfX, Math.min(landHalfX + 1, sinkHalfX));
            }
            newTotalPathCost = remainingCrossingCost(state, childRnode, rnodeReceivingIndex, remainingReceivingNodes,
                    sendingHalfX, newPartialPathCost);
            newTotalPathCost = addEstimate(state, newTotalPathCost, getDeltaHalfX(sinkRnode, landHalfX, sinkHalfX),
                    deltaY + (deltaSLR - 1) * sllLength, 1);
            // Lower than if it were charged as not being on a chain (at the span estimate), which it must be for the
            // discount to be one
            assert(newTotalPathCost <= addEstimate(state, newPartialPathCost, deltaHalfX, deltaY + deltaSLR * sllLength,
                    sharingFactor) + (RouteNode.isSingleDoubleQuadLong(childRnode.getIntentCode()) ?
                    state.rnodeCostWeight * RouteNode.PENDING_NODE_COST : 0));
        }

        push(state, childRnode, newPartialPathCost, newTotalPathCost);
    }

    /**
     * Gets the horizontal distance from a half tile to the sink, in half tiles.
     * @param sinkRnode The sink.
     * @param halfX Half-tile X coordinate (see RouteNode.getEndHalfTileXCoordinate()).
     * @param sinkHalfX Half-tile X coordinate of the sink.
     * @return That distance.
     */
    private int getDeltaHalfX(RouteNode sinkRnode, int halfX, int sinkHalfX) {
        if (sinkRnode.getType() == RouteNodeType.EXCLUSIVE_SINK_SLL_INPUT) {
            // A Versal SLL input sink is between two INT tiles (on both the east side of one, at sinkHalfX, and the
            // west side of the next): measure to whichever is nearer
            assert(routingGraph.isOnBothSides(sinkRnode));
            return (halfX <= sinkHalfX) ? sinkHalfX - halfX : Math.max(0, halfX - sinkHalfX - 1);
        }
        return Math.abs(halfX - sinkHalfX);
    }

    /**
     * Adds the estimate of the cost still to reach the sink to a lower bound.
     * @param state State from the connection that is being routed.
     * @param lowerBound Lower bound so far.
     * @param deltaHalfX Horizontal distance to the sink, in half tiles.
     * @param deltaY Vertical distance to the sink, in rows.
     * @param sharingFactor Sharing factor of the node in question.
     * @return The lower bound with the estimate added.
     */
    private float addEstimate(ConnectionState state, float lowerBound, int deltaHalfX, int deltaY, float sharingFactor) {
        // Horizontal distance counts half as much per half tile as vertical distance does per tile
        float distanceToSink = 0.5f * deltaHalfX + deltaY;
        lowerBound += state.estWlWeight * distanceToSink / sharingFactor;
        if (config.isTimingDriven()) {
            // Per half tile horizontally and per row vertically, no more than the delay of the fastest wires in each axis,
            // so as not to overestimate: on UltraScale+, as little as 15.3ps per tile (7.65ps per half tile) horizontally
            // (an HLONG on xcvu9p) and 7.2ps per row vertically (a VLONG). With the default timing weight of 0.35 (and
            // timing multiplier of 1), each unit here is worth 0.35 / ((1 - 0.35) / 100), or about 54ps, of path delay
            lowerBound += state.estDlyWeight * (deltaHalfX * 0.14f + deltaY * 0.13f);
        }
        return lowerBound;
    }

    /**
     * Gets the cost that getNodeCost() would give an uncongested node with the given base cost at the given tile,
     * for use in lower bounds.
     * @param state State from the connection that is being routed.
     * @param baseCost Base cost of the node.
     * @param x INT tile X coordinate of the node.
     * @param y INT tile Y coordinate of the node.
     * @return That cost, including the bias cost and the connection's cost weight.
     */
    private static float getUncongestedNodeCost(ConnectionState state, float baseCost, int x, int y) {
        NetWrapper net = state.connection.getNetWrapper();
        float distToCenter = Math.abs(x - net.getXCenter()) + Math.abs(y - net.getYCenter());
        float biasCost = baseCost / net.getConnections().size() * distToCenter / net.getDoubleHpwl();
        return state.rnodeCostWeight * (baseCost + Math.min(biasCost, baseCost / 2));
    }

    /**
     * Adds to the lower bound of a node of a connection that must cross SLRs the cost of what remains of the crossing
     * chains ahead of it: the super long lines (an UltraScale/UltraScale+ UBUMP or a Versal SLL) that it must still
     * take, and the chains of nodes on either side of each, so that A* order alone takes a connection through each
     * crossing depth-first, without looking ahead. Each series differs only in (its descriptor):
     * <ul>
     * <li>its sending chain: on UltraScale/UltraScale+, SDQ node, intra-tile single, INODE, IMUX, LAG_MUX_ATOM_*_TXOUT
     * (the last four of which remain from an SDQ node); on Versal, BNODE in an SLL tile, SLL input;</li>
     * <li>its receiving chain: on UltraScale/UltraScale+, LAG_LAGUNA_SITE_*_RXD*, RXD*, then an SDQ node (the landing
     * node); on Versal, LAG_OUT*, a CLE LAG_*_PIN, an [A-H]Q*_PIN, then an [A-H]Q* (the landing node), unless the sink
     * could be on it (a CLE LAG_*_PIN, of intent NODE_PINFEED), when it is not charged;</li>
     * <li>where its super long lines land: the west half of the sending INT tile column on UltraScale/UltraScale+;
     * either side of the sending SLL tile column on Versal (see evaluateCostAndPush()).</li>
     * </ul>
     * Charged, by where the node is:
     * <ul>
     * <li>Each super long line still to be taken beyond any discounted sending chain (below) is charged at its span at
     * the estimate's rate per row, as though it were any other wire, which evaluateCostAndPush() adds as rows of the
     * estimate. That is far more than the true cost of its whole chain (by more than 30 for a Versal SLL of 75
     * rows).</li>
     * <li>On the sending side of a chain (see evaluateCostAndPush()), the crossing discount: the rest of the chain
     * at its true (uncongested) cost, the super long line itself and its whole receiving chain, in place of the span
     * estimate. The super long line's benefit is thereby front-loaded onto the sending chain: the lower bound falls on
     * entering it, so that it is entered and followed depth-first, rather than first exploring further towards the
     * sink by other wires. The discount is withheld (the node is priced as if it were not on a chain) from a
     * sending-chain node that this net would overuse (see evaluateCostAndPush()), so that connections are not pulled
     * into super long lines that they would overuse.</li>
     * <li>On the receiving side of a chain (including the super long line itself): the nodes of that receiving chain
     * that remain. Otherwise, once one super long line has been popped, its onward wires would sit those nodes above
     * its sibling super long lines, which would then all be popped first.</li>
     * <li>{@link RouteNode#SLL_CHAIN_TIE_BREAK} for each node of either chain that remains, so that the lower bound
     * falls slightly along a chain and ties are broken depth-first.</li>
     * <li>The children of a landing node (the last node of the receiving chain) are charged
     * {@link RouteNode#SLL_LANDING_TIE_BREAK}, so that all the landing nodes reached from the same super long line are
     * explored before any of their children.</li>
     * </ul>
     * All nodes are charged as uncongested (see getUncongestedNodeCost()), with their bias cost taken at the sending
     * node for the sending chain, and where the super long line lands for it and its receiving chain.
     * @param state State from the connection that is being routed.
     * @param childRnode The node in question.
     * @param rnodeReceivingIndex How far along a receiving chain its parent is (see getReceivingChainIndex()).
     * @param remainingReceivingNodes How many nodes of a receiving chain remain after the node in question and are
     * charged (see getRemainingReceivingNodes()).
     * @param sendingHalfX Half-tile X coordinate of the column from which the super long line is taken, if the node in
     * question is on the sending side of a chain and given the discount (see evaluateCostAndPush()), else -1.
     * @param lowerBound Lower bound of the node in question, so far.
     * @return Its lower bound with that cost added.
     */
    private float remainingCrossingCost(ConnectionState state, RouteNode childRnode, int rnodeReceivingIndex,
                                        int remainingReceivingNodes, int sendingHalfX, float lowerBound) {
        final float tieBreak = state.rnodeCostWeight * RouteNode.SLL_CHAIN_TIE_BREAK;
        if (sendingHalfX < 0) {
            // Children of a landing node
            if (rnodeReceivingIndex == sllReceivingChainNodes) {
                lowerBound += state.rnodeCostWeight * RouteNode.SLL_LANDING_TIE_BREAK;
            }
            // Receiving side
            if (remainingReceivingNodes > 0) {
                lowerBound += remainingReceivingNodes * (getUncongestedNodeCost(state, RouteNode.LOCAL_BASE_COST,
                        childRnode.getEndTileXCoordinate(), childRnode.getEndTileYCoordinate()) + tieBreak);
            }
            return lowerBound;
        }

        // Sending side: the local nodes and intra-tile singles that remain before the super long line
        int remainingLocals;
        int remainingSingles = 0;
        final RouteNodeType childType = childRnode.getType();
        if (routingGraph.isVersal) {
            // SLL input, or BNODE in an SLL tile leading to one
            remainingLocals = (childRnode.getIntentCode() == IntentCode.NODE_SLL_INPUT) ? 0 : 1;
        } else if (childType == RouteNodeType.NON_LOCAL_LEADING_TO_SLL) {
            remainingLocals = 3;
            if (childRnode.getIntentCode() != IntentCode.NODE_SINGLE) {
                // An SDQ node, still to take its intra-tile single
                remainingSingles = 1;
            }
        } else {
            assert(childType == RouteNodeType.LOCAL_LEADING_TO_SLL);
            switch (childRnode.getIntentCode()) {
                case NODE_LOCAL:   // INODE
                    remainingLocals = 2;
                    break;
                case NODE_PINFEED: // IMUX
                    remainingLocals = 1;
                    break;
                default:           // LAG_MUX_ATOM_*_TXOUT (NODE_LAGUNA_OUTPUT on UltraScale+, INTENT_DEFAULT on UltraScale)
                    assert(Utils.isLaguna(childRnode.getTile().getTileTypeEnum()));
                    remainingLocals = 0;
                    break;
            }
        }
        final int x = sendingHalfX >> 1;
        final int y = childRnode.getEndTileYCoordinate();
        final int sllLength = routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES;
        // The super long line lands in the same column, a whole length to the north or south, in the next SLR
        final boolean north = routingGraph.isNorthboundSLLRow(y);
        final int landY = north ? y + sllLength : y - sllLength;
        assert(routingGraph.intYToSLRIndex[landY] == routingGraph.intYToSLRIndex[y] + (north ? 1 : -1));
        lowerBound += remainingLocals * (getUncongestedNodeCost(state, RouteNode.LOCAL_BASE_COST, x, y) + tieBreak);
        lowerBound += remainingSingles * (getUncongestedNodeCost(state, 0.5f * RouteNode.BASE_COST_PER_TILE, x, y) +
                state.rnodeWLWeight * 0.5f + tieBreak);
        // The super long line itself (as RouteNode.setBaseCost() and the wirelength cost in evaluateCostAndPush())
        lowerBound += getUncongestedNodeCost(state, RouteNode.BASE_COST_PER_TILE * sllLength, x, landY) +
                state.rnodeWLWeight * sllLength;
        // Its receiving side
        if (sllChargeReceivingChain(state.connection)) {
            lowerBound += sllReceivingChainNodes * (getUncongestedNodeCost(state, RouteNode.LOCAL_BASE_COST, x, landY) +
                    tieBreak);
        }
        return lowerBound;
    }

    /**
     * Gets how many nodes along the receiving chain of a super long line a node is (see remainingCrossingCost()).
     * The receiving chain has no branches other than towards the landing nodes (or the sink).
     * @param rnode The node in question.
     * @return 0 if it is the super long line itself, 1 for the next node and so on up to one beyond the landing node,
     * else -1.
     */
    private int getReceivingChainIndex(RouteNode rnode) {
        RouteNode ancestor = rnode;
        for (int i = 0; i <= sllReceivingChainNodes && ancestor != null; i++, ancestor = ancestor.getPrev()) {
            if (ancestor.getType() == RouteNodeType.SUPER_LONG_LINE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets how many nodes of the receiving chain of a super long line remain after a node, and are to be charged
     * (see remainingCrossingCost()).
     * @param connection The connection being routed.
     * @param childRnode The node in question.
     * @param rnodeReceivingIndex How far along a receiving chain its parent is (see getReceivingChainIndex()).
     * @return That number (all of them for the super long line itself), or 0.
     */
    private int getRemainingReceivingNodes(Connection connection, RouteNode childRnode, int rnodeReceivingIndex) {
        if (!sllChargeReceivingChain(connection)) {
            return 0;
        }
        if (childRnode.getType() == RouteNodeType.SUPER_LONG_LINE) {
            return sllReceivingChainNodes;
        }
        if (rnodeReceivingIndex >= 0 && rnodeReceivingIndex < sllReceivingChainNodes - 1) {
            return sllReceivingChainNodes - 1 - rnodeReceivingIndex;
        }
        return 0;
    }

    /**
     * Checks whether the receiving chain of a super long line is charged for a connection (see
     * remainingCrossingCost()): not when its sink could be on it (a Versal CLE LAG_*_PIN, of intent NODE_PINFEED).
     * @param connection The connection being routed.
     * @return True if so.
     */
    private boolean sllChargeReceivingChain(Connection connection) {
        return !routingGraph.isVersal || connection.getSinkRnode().getIntentCode() != IntentCode.NODE_PINFEED;
    }

    /**
     * Gets the congestion cost and bias cost of a rnode.
     * @param rnode The rnode in question.
     * @param connection The connection being routed.
     * @param countSameSourceUsers The number of connections from the same net that are using rnode.
     * Note: a net is represented by its source {@link SitePinInst} Object.
     * @param sharingFactor The sharing factor.
     * @return The sum of the congestion cost and the bias cost of rnode.
     */
    private float getNodeCost(RouteNode rnode, Connection connection, int countSameSourceUsers, float sharingFactor) {
        boolean hasSameSourceUsers = (countSameSourceUsers != 0);
        float presentCongestionCost;

        if (hasSameSourceUsers) {// the rnode is used by other connection(s) from the same net
            int occupancyWithoutThisNet = rnode.getOccupancy() - 1;
            // make the congestion cost less for the current connection
            presentCongestionCost = routingGraph.getPresentCongestionCost(occupancyWithoutThisNet);
        } else {
            presentCongestionCost = rnode.getPresentCongestionCost(routingGraph);
        }

        float baseCost = rnode.getBaseCost();
        NetWrapper net = connection.getNetWrapper();
        float distToCenter = Math.abs(rnode.getEndTileXCoordinate() - net.getXCenter()) + Math.abs(rnode.getEndTileYCoordinate() - net.getYCenter());
        // CRoute paper states that the bias factor cannot be more than half of the wire cost
        // (it may exceed this here because we may not be using the minimum-sized bounding box)
        float biasCost = baseCost / net.getConnections().size() * distToCenter / net.getDoubleHpwl();
        // Congestion (both multipliers being 1 when there is none) scales a base cost of no less than
        // RouteNode.MIN_CONGESTED_BASE_COST
        float congestionMultiplier = rnode.getHistoricalCongestionCost() * presentCongestionCost;
        float nodeCost = (baseCost + Math.max(baseCost, RouteNode.MIN_CONGESTED_BASE_COST) * (congestionMultiplier - 1)) / sharingFactor;
        return nodeCost + Math.min(biasCost, nodeCost / 2);
    }

    /**
     * Sets the costs of a rnode and pushes it to the queue.
     * @param state State from the connection that is being routed.
     * @param childRnode A child rnode.
     * @param newPartialPathCost The upstream path cost from childRnode to the source.
     * @param newTotalPathCost Total path cost of childRnode.
     */
    protected void push(ConnectionState state,
                        RouteNode childRnode,
                        float newPartialPathCost,
                        float newTotalPathCost) {
        // Pushed node must have a prev pointer, unless it is a source (with no upstream path cost)
        assert(childRnode.getPrev() != null || newPartialPathCost == 0);
        childRnode.setLowerBoundTotalPathCost(newTotalPathCost);
        childRnode.setUpstreamPathCost(newPartialPathCost);
        // Use the number-of-connections-routed-so-far as the identifier for whether a rnode
        // has been visited by this connection before
        childRnode.setVisited(state.sequence);
        state.queue.add(childRnode);
    }

    /**
     * Prepares for routing a connection, including seeding the routing queue with
     * known-uncongested downstream-from-source routing segments acquired from prior
     * iterations, as well as marking known-uncongested upstream-from-sink segments
     * as targets.
     * @param state State from the connection that is being routed.
     */
    protected void prepareRouteConnection(ConnectionState state) {
        final Connection connection = state.connection;

        // Rips up the connection
        ripUp(connection);
        assert(state.queue.isEmpty());

        // Sets the sink rnode(s) of the connection as the target(s)
        connection.setAllTargets(state);

        // Adds the source rnode to the queue
        RouteNode sourceRnode = connection.getSourceRnode();
        float newPartialPathCost = 0;
        float newTotalPathCost = 0;
        push(state, sourceRnode, newPartialPathCost, newTotalPathCost);

        assert(routingGraph.isAllowedTile(connection.getSinkRnode()));
    }

    /**
     * Adds a clock net to the clock net routing targets.
     * @param clk The clock net to be added.
     */
    public void addClkNet(Net clk) {
        clkNets.add(clk);
    }

    public Design getDesign() {
        return design;
    }

    protected int getNumIndirectConnectionPins() {
        return indirectConnections.size();
    }

    protected int getNumConnectionsCrossingSLRs() {
        int numCrossingSLRs = 0;
        for (Connection c : indirectConnections) {
            numCrossingSLRs += c.isCrossSLR() ? 1 : 0;
        }
        return numCrossingSLRs;
    }

    private int getNumStaticNetPins() {
        int totalSitePins = 0;
        for (Entry<Net,List<SitePinInst>> e : staticNetAndRoutingTargets.entrySet()) {
            List<SitePinInst> pins = e.getValue();
            totalSitePins += pins.size();
        }
        return totalSitePins;
    }

    private void printDesignNetsAndConfigurationInfo(boolean verbose) {
        printDesignInfo(verbose);
        if (config.isVerbose()) printConnectionSpanStatistics();
        printConfiguration(verbose);
    }

    private void printConfiguration(boolean verbose) {
        if (verbose) System.out.println(config);
    }

    private void printTimingInfo() {
        if (!sortedIndirectConnections.isEmpty()) {
            timingManager.getCriticalPathInfo(maxDelayAndTimingVertex);
        }
    }

    public static void printNodeTypeUsageAndWirelength(boolean verbose, Map<IntentCode, Long> nodeTypeUsage, Map<IntentCode, Long> nodeTypeLength, Series series) {
        if (verbose) {
            System.out.println("Node Usage Per Type");
            System.out.printf(" %-16s  %13s  %12s\n", "Node Type", "Usage", "Length");
            List<IntentCode> nodeTypeList = (series == Series.Versal) ? nodeUsageForVersal : nodeUsageForUltraScale;
            for (IntentCode ic : nodeTypeList) {
                long usage = nodeTypeUsage.getOrDefault(ic, 0L);
                // Accumulated in half INT tiles (see RouteNodeInfo.halfTileWirelength)
                long length = nodeTypeLength.getOrDefault(ic, 0L) / 2;
                System.out.printf(" %-16s  %13d  %12d\n", ic, usage, length);
            }
            System.out.println();
        }
    }

    private void printDesignInfo(boolean verbose) {
        if (!verbose) return;
        System.out.println("------------------------------------------------------------------------------");
        printFormattedString("Total nets: ", design.getNets().size());
        printFormattedString("Routable nets: ", numPreservedRoutableNets + numPreservedClks + numPreservedStaticNets + nets.size() + staticNetAndRoutingTargets.size() + clkNets.size());
        printFormattedString("  Preserved routable nets: ", numPreservedRoutableNets);
        printFormattedString("    GLOBAL_CLOCK: ", numPreservedClks);
        printFormattedString("    Static nets: ", numPreservedStaticNets);
        printFormattedString("    WIRE: ", numPreservedWire);
        printFormattedString("  Nets to be routed: ", (nets.size() +  staticNetAndRoutingTargets.size() + clkNets.size()));
        printFormattedString("    GLOBAL_CLOCK: ", clkNets.size());
        printFormattedString("    Static nets: ", staticNetAndRoutingTargets.size());
        printFormattedString("    WIRE: ", nets.size());
        int clkPins = 0;
        for (Net clk : clkNets) {
            clkPins += clk.getSinkPins().size();
        }
        int indirectPins = getNumIndirectConnectionPins();
        int staticPins = getNumStaticNetPins();
        printFormattedString("  All site pins to be routed: ", (indirectPins + staticPins + clkPins));
        printFormattedString("    Connections to be routed: ", indirectPins);
        printFormattedString("      With SLR crossings: ", getNumConnectionsCrossingSLRs());
        printFormattedString("    Static net pins: ", staticPins);
        printFormattedString("    Clock pins: ", clkPins);
        printFormattedString("Nets not needing routing: ", numNotNeedingRoutingNets);
        if (numUnrecognizedNets != 0)
            printFormattedString("Nets unrecognized: ", numUnrecognizedNets);
        System.out.printf("------------------------------------------------------------------------------\n");
    }

    private static void printFormattedString(String s, int value) {
        System.out.print(MessageGenerator.formatString(s, value));
    }

    private static void printFormattedString(String s, long value) {
        System.out.print(MessageGenerator.formatString(s, value));
    }

    protected void printRoutingStatistics() {
        MessageGenerator.printHeader("Statistics");
        computesNodeUsageAndTotalWirelength();
        printNodeTypeUsageAndWirelength(config.isVerbose(), nodeTypeUsage, nodeTypeLength, design.getSeries());
        printFormattedString("Total wirelength:", totalWL / 2);
        if (config.isVerbose()) {
            printFormattedString("Total INT tile nodes:", totalINTNodes);
            printFormattedString("Total rnodes created:", routingGraph.numNodes());
            printFormattedString("Average #children per node:", routingGraph.averageChildren());
            System.out.printf("------------------------------------------------------------------------------\n");
            printFormattedString("Num iterations:", routeIteration);
            printFormattedString("Connections routed:", connectionsRouted.get());
            printFormattedString("Nodes pushed:", nodesPushed.get());
        }
        printFormattedString("Nodes popped:", nodesPopped.get());
        if (config.isVerbose()) {
            System.out.printf("------------------------------------------------------------------------------\n");
        }

        System.out.print(routerTimer);
        if (config.isTimingDriven()) {
            MessageGenerator.printHeader("Timing Report");
            printTimingInfo();
        }
        System.out.printf("==============================================================================\n");

        // For testing
        System.setProperty("rapidwright.rwroute.nodesPopped", String.valueOf(nodesPopped));
        System.setProperty("rapidwright.rwroute.numStaticNetPins", String.valueOf(getNumStaticNetPins()));
    }

    /**
     * Routes a design in the full timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     */
    public static Design routeDesignFullTimingDriven(Design design) {
        return routeDesignWithUserDefinedArguments(design, null);
    }

    /**
     * Routes a design in the full non-timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     */
    public static Design routeDesignFullNonTimingDriven(Design design) {
        return routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven"});
    }

    /**
     * Routes a {@link Design} instance.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args) {
        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        return routeDesign(design, new RWRouteConfig(args));
    }

    private static Design routeDesign(Design design, RWRouteConfig config) {
        if (config.isTimingDriven() && !config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Not masking nodes across RCLK could result in delay optimism.");
        }

        return routeDesign(new RWRoute(design, config));
    }

    /**
     * Routes a design after pre-processing.
     * @param router A {@link RWRoute} object to be used to route the design.
     */
    protected static Design routeDesign(RWRoute router) {
        router.preprocess();

        // Initialize router object
        router.initialize();

        // Routes the design
        router.route();

        return router.getDesign();
    }

    /**
     * The main interface of {@link RWRoute} that reads in a {@link Design} design
     * (DCP or FPGA Interchange), and parses the arguments for the
     * {@link RWRouteConfig} object of the router.
     * 
     * @param args An array of strings that are used to create a
     *             {@link RWRouteConfig} object for the router.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp|input.phys> <output.dcp>");
            return;
        }
        // Reads the output directory and set the output design checkpoint file name
        String routedDCPfileName = args[1];

        CodePerfTracker t = new CodePerfTracker("RWRoute", true);

        // Reads in a design and routes it
        String[] rwrouteArgs = Arrays.copyOfRange(args, 2, args.length);
        Design input;
        if (Interchange.isInterchangeFile(args[0])) {
            input = Interchange.readInterchangeDesign(args[0]);
        } else {
            input = Design.readCheckpoint(args[0]);
        }
        Design routed = routeDesignWithUserDefinedArguments(input, rwrouteArgs);

        // Writes out the routed design checkpoint
        routed.writeCheckpoint(routedDCPfileName,t);
        System.out.println("\nINFO: Wrote routed design\n " + routedDCPfileName + "\n");
    }

}
