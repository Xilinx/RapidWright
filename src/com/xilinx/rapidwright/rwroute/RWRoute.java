/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
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
public class RWRoute{
    /** The design to route */
    protected Design design;
    /** Created NetWrappers */
    protected Map<Net,NetWrapper> nets;
    /** A list of indirect connections that will go through iterative routing */
    protected List<Connection> indirectConnections;
    /** A list of direct connections that are easily routed through dedicated resources */
    private List<Connection> directConnections;
    /** Sorted indirect connections */
    private List<Connection> sortedIndirectConnections;
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
    protected boolean lutPinSwapping;
    /** Flag for whether LUT routethrus are to be considered */
    protected boolean lutRoutethru;

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
    /** The queue to store candidate nodes to route a connection */
    private PriorityQueue<RouteNode> queue;

    /** Total wirelength of the routed design */
    private int totalWL;
    /** Total used INT tile nodes */
    private long totalINTNodes;
    /** A map from node types to the node usage of the types */
    private Map<IntentCode, Long> nodeTypeUsage;
    /** A map from node types to the total wirelength of used nodes of the types */
    private Map<IntentCode, Long> nodeTypeLength;
    /** The total number of connections that are routed */
    protected int connectionsRouted;
    /** The total number of connections routed in an iteration */
    private int connectionsRoutedIteration;
    /** Total number of nodes pushed/popped from the queue */
    private long nodesPushed;
    private long nodesPopped;

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

    public static final EnumSet<Series> SUPPORTED_SERIES;

    static {
        SUPPORTED_SERIES = EnumSet.of(Series.UltraScale, Series.UltraScalePlus);
    }

    public RWRoute(Design design, RWRouteConfig config) {
        this.design = design;
        this.config = config;
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

        queue = new PriorityQueue<>();
        routingGraph = createRouteNodeGraph();
        if (config.isTimingDriven()) {
            nodesDelays = new HashMap<>();
        }
        rnodesCreatedThisIteration = 0;
        routethruHelper = new RouteThruHelper(design.getDevice());
        presentCongestionFactor = config.getInitialPresentCongestionFactor();
        lutPinSwapping = config.isLutPinSwapping();
        lutRoutethru = config.isLutRoutethru();

        routerTimer.createRuntimeTracker("determine route targets", "Initialization").start();
        determineRoutingTargets();
        routerTimer.getRuntimeTracker("determine route targets").stop();

        if (config.isTimingDriven()) {
            ClkRouteTiming clkTiming = createClkTimingData(config);
            routesToSinkINTTiles = clkTiming == null? null : clkTiming.getRoutesToSinkINTTiles();
            Collection<Net> timingNets = getTimingNets();
            timingManager = createTimingManager(clkTiming, timingNets);
            timingManager.setTimingEdgesOfConnections(indirectConnections);
        }

        sortedIndirectConnections = new ArrayList<>(indirectConnections.size());
        connectionsRouted = 0;
        connectionsRoutedIteration = 0;
        nodesPushed = 0;
        nodesPopped = 0;
        overUsedRnodes = new HashSet<>();

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
            DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphTimingDriven(rnodesTimer, design, config, estimator);
        } else {
            return new RouteNodeGraph(rnodesTimer, design, config);
        }
    }

    protected Collection<Net> getTimingNets() {
        return indirectConnections.stream().map((c) -> c.getNetWrapper().getNet()).collect(Collectors.toSet());
    }

    protected TimingManager createTimingManager(ClkRouteTiming clkTiming, Collection<Net> timingNets) {
        final boolean isPartialRouting = false;
        return new TimingManager(design, routerTimer, config, clkTiming, timingNets, isPartialRouting);
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
            connection.getAltSinkRnodes().removeIf((rnode) -> rnode.getOccupancy() > 0);
        }

        // Wait for all outstanding RouteNodeGraph.asyncPreserve() calls to complete
        routingGraph.awaitPreserve();
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
            if (net.isClockNet()) {
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
        if (routingGraph.isPreserved(node)) {
            // Node is preserved by any net -- for base RWRoute, we don't need
            // to check which net it is nor whether it is already in use
            // because global/static nets are routed from scratch
            return NodeStatus.UNAVAILABLE;
        }

        // A RouteNode will only be created if the net is necessary for
        // a to-be-routed connection
        return routingGraph.getNode(node) == null ? NodeStatus.AVAILABLE
                                                  : NodeStatus.UNAVAILABLE;
    }

    /**
     * Routes clock nets by default or in a different way when corresponding timing info supplied.
     * NOTE: For an unrouted design, its clock nets must not contain any PIPs or nodes, i.e, completely unrouted.
     * Otherwise, there could be a critical warning of clock routing results, when loading the routed design into Vivado.
     * Vivado will unroute the global clock nets immediately when there is such warning.
     * TODO: fix the potential issue.
     */
    protected void routeGlobalClkNets() {
        for (Net clk : clkNets) {
            routeGlobalClkNet(clk);
        }
    }

    protected void routeGlobalClkNet(Net clk) {
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
            GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(), gns);
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
        if (sinks.size() > 0) {
            staticNet.unroute();
            // Preserve all pins (e.g. in case of BOUNCE nodes that may serve as a site pin)
            preserveNet(staticNet, true);
            staticNetAndRoutingTargets.put(staticNet, sinks);
        } else {
            numNotNeedingRoutingNets++;
        }
    }

    /**
     * Routes static nets.
     */
    protected void routeStaticNets() {
        if (staticNetAndRoutingTargets.isEmpty())
            return;

        List<SitePinInst> gndPins = staticNetAndRoutingTargets.get(design.getGndNet());
        if (gndPins != null) {
            Set<SitePinInst> newVccPins = RouterHelper.invertPossibleGndPinsToVccPins(design, gndPins);
            if (!newVccPins.isEmpty()) {
                gndPins.removeAll(newVccPins);
                staticNetAndRoutingTargets.computeIfAbsent(design.getVccNet(), (net) -> new ArrayList<>())
                        .addAll(newVccPins);
            }
        }

        for (Map.Entry<Net,List<SitePinInst>> e : staticNetAndRoutingTargets.entrySet()) {
            Net staticNet = e.getKey();
            List<SitePinInst> pins = e.getValue();
            // Since we preserved all pins in addStaticNetRoutingTargets(), unpreserve them here
            for (SitePinInst spi : pins) {
                routingGraph.unpreserve(spi.getConnectedNode());
            }

            System.out.println("INFO: Routing " + pins.size() + " pins of " + staticNet);

            Function<Node, NodeStatus> gns = (node) -> getGlobalRoutingNodeStatus(staticNet, node);
            GlobalSignalRouting.routeStaticNet(staticNet, gns, design, routethruHelper);

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

    private Map<Short, Integer> connectionSpan = new HashMap<>();

    /**
     * Creates a unique {@link NetWrapper} instance and {@link Connection} instances based on a {@link Net} instance.
     * @param net The net to be initialized.
     * @return A {@link NetWrapper} instance.
     */
    protected NetWrapper createNetWrapperAndConnections(Net net) {
        NetWrapper netWrapper = new NetWrapper(numWireNetsToRoute++, net);
        NetWrapper existingNetWrapper = nets.put(net, netWrapper);
        assert(existingNetWrapper == null);
        SitePinInst source = net.getSource();
        int indirect = 0;
        RouteNode sourceINTRnode = null;
        RouteNode altSourceINTRnode = null;

        for (SitePinInst sink : net.getSinkPins()) {
            Connection connection = new Connection(numConnectionsToRoute++, source, sink, netWrapper);

            List<Node> nodes = RouterHelper.projectInputPinToINTNode(sink);
            Node sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
            // Pre-emptively set up alternate source since we may expand from both sources
            SitePinInst altSource = net.getAlternateSource();
            if (altSource == null) {
                altSource = DesignTools.getLegalAlternativeOutputPin(net);
                if (altSource != null) {
                    // Add this SitePinInst to the net, but not to the SiteInst
                    net.addPin(altSource);
                    DesignTools.routeAlternativeOutputSitePin(net, altSource);
                }
            }

            Node altSourceINTNode = null;
            if (altSource != null) {
                assert(!altSource.equals(source));
                altSourceINTNode = RouterHelper.projectOutputPinToINTNode(altSource);
            }

            if (nodes.isEmpty() || (sourceINTNode == null && altSourceINTNode == null)) {
                directConnections.add(connection);
                connection.setDirect(true);
            } else {
                Node sinkINTNode = nodes.get(0);
                indirectConnections.add(connection);
                checkSinkRoutability(net, sinkINTNode);
                RouteNode sinkRnode = getOrCreateRouteNode(sinkINTNode, RouteNodeType.PINFEED_I);
                assert(sinkRnode.getType() == RouteNodeType.PINFEED_I);
                connection.setSinkRnode(sinkRnode);

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
                    RouteNode altSinkRnode = getOrCreateRouteNode(node, RouteNodeType.PINFEED_I);
                    assert(altSinkRnode.getType() == RouteNodeType.PINFEED_I);
                    connection.addAltSinkRnode(altSinkRnode);
                }

                if (connection.getAltSinkRnodes().isEmpty()) {
                    // Since this connection only has a single sink target, increment
                    // its usage here immediately
                    sinkRnode.incrementUser(netWrapper);
                    sinkRnode.updatePresentCongestionCost(presentCongestionFactor);
                }

                if (sourceINTRnode == null && altSourceINTRnode == null) {
                    if (sourceINTNode != null) {
                        sourceINTRnode = getOrCreateRouteNode(sourceINTNode, RouteNodeType.PINFEED_O);
                    }
                    if (altSourceINTNode != null) {
                        altSourceINTRnode = getOrCreateRouteNode(altSourceINTNode, RouteNodeType.PINFEED_O);
                    }

                    if (sourceINTRnode == null && altSourceINTRnode == null) {
                        throw new RuntimeException("ERROR: Null projected INT node for the source of net " + net.toStringFull());
                    }
                }
                if (sourceINTRnode != null) {
                    connection.setSourceRnode(sourceINTRnode);
                    connection.setAltSourceRnode(altSourceINTRnode);
                } else {
                    // Primary source does not reach the fabric (e.g. COUT)
                    // just use alternate source
                    assert(altSourceINTRnode != null);
                    connection.setSource(net.getAlternateSource());
                    connection.setSourceRnode(altSourceINTRnode);
                }
                connection.setDirect(false);
                indirect++;
                connection.computeHpwl();
                addConnectionSpanInfo(connection);
            }
        }

        if (indirect > 0) {
            netWrapper.computeHPWLAndCenterCoordinates(routingGraph.nextLagunaColumn, routingGraph.prevLagunaColumn);
            if (config.isUseBoundingBox()) {
                for (Connection connection : netWrapper.getConnections()) {
                    if (connection.isDirect()) continue;
                    connection.computeConnectionBoundingBox(config.getBoundingBoxExtensionX(),
                            config.getBoundingBoxExtensionY(),
                            routingGraph.nextLagunaColumn,
                            routingGraph.prevLagunaColumn);
                }
            }
        }
        return netWrapper;
    }

    protected void checkSinkRoutability(Net net, Node sinkNode) {
        Net oldNet = routingGraph.getPreservedNet(sinkNode);
        if (oldNet != null && oldNet != net) {
            throw new RuntimeException("ERROR: Sink node " + sinkNode + " of net '" + net.getName() + "' is "
                    + " preserved by net '" + oldNet.getName() + "'");
        }
    }

    /**
     * Adds span info of a connection.
     * @param connection A connection of which span info is to be added.
     */
    private void addConnectionSpanInfo(Connection connection) {
        connectionSpan.merge(connection.getHpwl(), 1, Integer::sum);
    }

    /**
     * Creates a {@link RouteNode} Object based on a {@link Node} instance and avoids duplicates,
     * used for creating the source and sink rnodes of {@link Connection} instances.
     * NOTE: This method does not consider whether returned node is preserved.
     * @param node The node associated to the {@link SitePinInst} instance.
     * @param type The {@link RouteNodeType} of the {@link RouteNode} Object.
     * @return The created {@link RouteNode} instance.
     */
    protected RouteNode getOrCreateRouteNode(Node node, RouteNodeType type) {
        return routingGraph.getOrCreate(node, type);
    }

    /**
     * Initializes routing.
     */
    private void initializeRouting() {
        routingGraph.initialize();
        queue.clear();
        routeIteration = 1;
        historicalCongestionFactor = config.getHistoricalCongestionFactor();
        presentCongestionFactor = config.getInitialPresentCongestionFactor();
        timingWeight = config.getTimingWeight();
        wlWeight = config.getWirelengthWeight();
        oneMinusTimingWeight = 1 - timingWeight;
        oneMinusWlWeight = 1 - wlWeight;
        printIterationHeader(config.isTimingDriven());
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
        routeIndirectConnections();
        // NOTE: route direct connections after indirect connection.
        // The reason is that there maybe additional direct connections in the soft preserve mode for partial routing,
        // and those direct connections should be included to be routed
        routeDirectConnections();
        routeWireNets.stop();
        // Adds child timers to "route wire nets" timer
        routeWireNets.addChild(rnodesTimer);
        // Do not time the cost evaluation method for routing connections, the timer itself takes time
        routerTimer.createRuntimeTracker("route connections", "route wire nets").setTime(routeWireNets.getTime() - rnodesTimer.getTime() - updateTimingTimer.getTime() - updateCongestionCosts.getTime());
        routeWireNets.addChild(updateTimingTimer);
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
            if (source.getChildren().length == 0) {
                // output pin is blocked
                swapOutputPin(connection);
                source = connection.getSourceRnode();
            }
            short estDelay = (short) 10000;
            for (RouteNode child : source.getChildren()) {
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
            connection.getSink().setRouted(success);
            // no need to update route delay of direct connection, because it would not be changed
            if (!success) System.err.println("ERROR: Failed to route direct connection " + connection);
        }
    }

    /**
     * Routes indirect connections iteratively.
     */
    public void routeIndirectConnections() {
        sortConnections();
        initializeRouting();
        long lastIterationRnodeCount = 0;
        long lastIterationRnodeTime = 0;

        while (routeIteration < config.getMaxIterations()) {
            long startIteration = System.nanoTime();
            connectionsRoutedIteration = 0;
            if (config.isTimingDriven()) {
                setRerouteCriticality();
            }
            for (Connection connection : sortedIndirectConnections) {
                if (shouldRoute(connection)) {
                    routeConnection(connection);
                }
            }

            updateCostFactors();

            rnodesCreatedThisIteration = routingGraph.numNodes() - lastIterationRnodeCount;
            List<Connection> unroutableConnections = getUnroutableConnections();
            boolean needsResorting = false;
            for (Connection connection : unroutableConnections) {
                System.out.printf("CRITICAL WARNING: Unroutable connection in iteration #%d\n", routeIteration);
                System.out.println("                 " + connection);
                needsResorting = handleUnroutableConnection(connection) || needsResorting;
            }
            rnodesCreatedThisIteration = routingGraph.numNodes() - lastIterationRnodeCount;
            for (Connection connection : getCongestedConnections()) {
                needsResorting = handleCongestedConnection(connection) || needsResorting;
            }
            if (needsResorting) {
                sortConnections();
            }

            if (config.isTimingDriven()) {
                updateTiming();
            }

            printRoutingIterationStatisticsInfo(System.nanoTime() - startIteration,
                    (float) ((rnodesTimer.getTime() - lastIterationRnodeTime) * 1e-9));

            if (overUsedRnodes.size() == 0) {
                if (unroutableConnections.isEmpty()) {
                    break;
                } else {
                    if (routeIteration == config.getMaxIterations() - 1) {
                        System.err.println("ERROR: Unroutable connections: " + unroutableConnections.size());
                    }
                }
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
            if (!connection.getSink().isRouted()) {
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

        // perform LUT pin mapping updates
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

                SitePin newSitePin = newSinkRnode.getNode().getSitePin();
                String existing = pinSwaps.put(oldSinkSpi, newSitePin.getPinName());
                assert(existing == null);
            }
            LUTTools.swapMultipleLutPins(pinSwaps);
        }

        if (lutRoutethru) {
            // It is possible for RWRoute to routethru a LUT that is already being used as a
            // static source through its *MUX output. By default, the 6LUT is used for this supply.
            // When LUT routethru-s are considered, examine both static nets to find
            // any cases where the *MUX output pin is used as a static source alongside
            // the *_O output being used as a routethru. In such cases, configure the
            // OUTMUX* site PIP to source from the 5LUT rather than the default 6LUT
            // so that no conflict occurs
            for (Net staticNet : Arrays.asList(design.getGndNet(), design.getVccNet())) {
                for (SitePinInst spi : staticNet.getPins()) {
                    if (!spi.isOutPin()) {
                        continue;
                    }
                    SiteInst si = spi.getSiteInst();
                    if (!Utils.isSLICE(si)) {
                        continue;
                    }

                    String pinName = spi.getName();
                    if (!pinName.endsWith("MUX")) {
                        continue;
                    }

                    Node muxNode = spi.getConnectedNode();
                    assert(routingGraph.getPreservedNet(muxNode) == staticNet);

                    Site site = si.getSite();
                    char lutLetter = pinName.charAt(0);
                    Node oNode = site.getConnectedNode(lutLetter + "_O");
                    RouteNode rnode = routingGraph.getNode(oNode);
                    if (rnode == null || rnode.getOccupancy() == 0) {
                        // No LUT6 routethru, nothing to be done
                        continue;
                    }

                    if (pinName.charAt(1) == '6') {
                        throw new RuntimeException("ERROR: Illegal LUT routethru on " + site + "/" + pinName +
                                " since the 5LUT is being used as a static source");
                    }

                    // Perform intra-site routing back to the LUT5 to not conflict with LUT6 routethru
                    BEL outmux = si.getBEL("OUTMUX" + lutLetter);
                    si.routeIntraSiteNet(staticNet, outmux.getPin("D5"), outmux.getPin("OUT"));

                    if (si.getDesign() == null) {
                        // Rename SiteInst (away from "STATIC_SOURCE_<siteName>") and
                        // attach it to the design so that intra-site routing updates take effect
                        si.setName(site.getName());
                        design.addSiteInst(si);
                    }
                }
            }
        }

        assignNodesToConnections();

        // fix routes with cycles and / or multi-driver nodes
        Set<NetWrapper> routes = fixRoutes();
        if (config.isTimingDriven()) updateTimingAfterFixingRoutes(routes);

        // Unset the routed state of all source pins
        for (Map.Entry<Net, NetWrapper> e : nets.entrySet()) {
            Net net = e.getKey();
            SitePinInst source = net.getSource();
            SitePinInst altSource = net.getAlternateSource();
            SiteInst si = source.getSiteInst();
            boolean altSourcePreviouslyRouted = altSource != null ? altSource.isRouted() : false;
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
                if (nodes.isEmpty()) {
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
                boolean sourceRouted = source.isRouted();
                altSource.getSiteInst().removePin(altSource);
                net.removePin(altSource);
                source.setRouted(sourceRouted);
                if (altSource.getName().endsWith("_O") && source.getName().endsWith("MUX") && source.isRouted()) {
                    // Add site routing back if we are keeping the MUX pin
                    source.getSiteInst().routeIntraSiteNet(net, altSource.getBELPin(), altSource.getBELPin());
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

        if (connection.getAltSinkRnodes().isEmpty()) {
            // Check that this connection's exclusive sink node is used but never overused
            RouteNode sinkRnode = connection.getSinkRnode();
            assert (sinkRnode.countConnectionsOfUser(connection.getNetWrapper()) > 0);
            assert(!sinkRnode.isOverUsed());
        }

        return !connection.getSink().isRouted() || connection.isCongested();
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
     * @param netsWithIllegalRoutes A set of nets whose routes have been fixed.
     */
    private void updateTimingAfterFixingRoutes(Set<NetWrapper> netsWithIllegalRoutes) {
        timingManager.updateIllegalNetsDelays(netsWithIllegalRoutes, nodesDelays);
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
                    for (int i = 0; i < switchBoxToSink.size() - 1; i++) {
                        nodes.add(switchBoxToSink.get(i));
                    }
                }
            } else {
                // Routing must go to an alternate sink
                assert(!connection.getAltSinkRnodes().isEmpty());

                // Assume that it doesn't need unprojecting back to the sink pin
                // since the sink node is a site pin
                assert(rnodes.get(0).getSitePin() != null);
            }

            for (RouteNode rnode : rnodes) {
                nodes.add(rnode.getNode());
            }

            List<Node> sourceToSwitchBox = RouterHelper.findPathBetweenNodes(connection.getSource().getConnectedNode(), connection.getSourceRnode());
            if (sourceToSwitchBox.size() >= 2) {
                for (int i = 1; i <= sourceToSwitchBox.size() - 1; i++) {
                    nodes.add(sourceToSwitchBox.get(i));
                }
            }
        }
    }

    /**
     * Sorts indirect connections for routing.
     */
    private void sortConnections() {
        sortedIndirectConnections.clear();
        sortedIndirectConnections.addAll(indirectConnections);
        sortedIndirectConnections.sort((connection1, connection2) -> {
            int comp = connection2.getNetWrapper().getConnections().size() - connection1.getNetWrapper().getConnections().size();
            if (comp == 0) {
                return Short.compare(connection1.getHpwl(), connection2.getHpwl());
            } else {
                return comp;
            }
        });
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
                    connectionsRoutedIteration,
                    overUsed,
                    (short)(maxDelayAndTimingVertex == null? 0 : maxDelayAndTimingVertex.getFirst()),
                    iterationRuntime * 1e-9);
        } else {
            System.out.printf("%4d       %12d  %8.2f   %11d  %10d   %5s  %9.2f\n",
                    routeIteration,
                    rnodesCreatedThisIteration,
                    rnodesCreationTime,
                    connectionsRoutedIteration,
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
            int overuse=rnode.getOccupancy() - RouteNode.capacity;
            if (overuse == 0) {
                rnode.setPresentCongestionCost(1 + presentCongestionFactor);
            } else if (overuse > 0) {
                overUsedRnodes.add(rnode);
                rnode.setPresentCongestionCost(1 + (overuse + 1) * presentCongestionFactor);
                rnode.setHistoricalCongestionCost(rnode.getHistoricalCongestionCost() + overuse * historicalCongestionFactor);
            } else {
                assert(overuse < 0);
                assert(rnode.getPresentCongestionCost() == 1);
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
            for (Connection connection:netWrapper.getConnections()) {
                netNodes.addAll(connection.getNodes());
            }
            for (Node node:netNodes) {
                TileTypeEnum tileType = node.getTile().getTileTypeEnum();
                if (tileType != TileTypeEnum.INT && !Utils.isLaguna(tileType)) {
                    continue;
                }
                totalINTNodes++;
                int wl = RouteNode.getLength(node);
                totalWL += wl;

                RouterHelper.addNodeTypeLengthToMap(node, wl, nodeTypeUsage, nodeTypeLength);
            }
            netNodes.clear();
        }
    }

    static List<IntentCode> nodeTypes = new ArrayList<>();
    static {
        nodeTypes.add(IntentCode.NODE_SINGLE);
        nodeTypes.add(IntentCode.NODE_DOUBLE);
        nodeTypes.add(IntentCode.NODE_VQUAD);
        nodeTypes.add(IntentCode.NODE_HQUAD);
        nodeTypes.add(IntentCode.NODE_VLONG);
        nodeTypes.add(IntentCode.NODE_HLONG);
        nodeTypes.add(IntentCode.NODE_LOCAL);
        nodeTypes.add(IntentCode.NODE_PINBOUNCE);
        nodeTypes.add(IntentCode.NODE_PINFEED);
        nodeTypes.add(IntentCode.NODE_LAGUNA_DATA); // UltraScale+ only
    }

    /**
     * Fixes routes of nets with routing path cycles and multi-driver nodes.
     * @return A set of nets that have been fixed.
     */
    private Set<NetWrapper> fixRoutes() {
        Set<NetWrapper> illegalRoutes = findIllegalRoutes();
        // fix routes with cycles and / or multi-driver nodes
        for (NetWrapper route:illegalRoutes) {
            for (Connection connection : route.getConnections()) {
                try {
                    if (!connection.isDirect()) ripUp(connection);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            RouteFixer graphHelper = new RouteFixer(route, routingGraph);
            graphHelper.finalizeRoutesOfConnections();
        }
        return illegalRoutes;
    }

    /**
     * Finds nets that have illegal routes by checking its connections' routes.
     * @return A set of routed {@link NetWrapper} instances whose should be fixed.
     */
    private Set<NetWrapper> findIllegalRoutes() {
        Set<NetWrapper> illegalRoutes = new HashSet<>();
        for (Entry<Net,NetWrapper> e : nets.entrySet()) {
            NetWrapper netWrapper = e.getValue();
            buildDriverCountsOfRnodes(netWrapper);
            for (Connection connection : netWrapper.getConnections()) {
                if (shouldMergePath(connection)) {
                    illegalRoutes.add(netWrapper);
                    if (config.isTimingDriven()) addNodesDelays(netWrapper);
                    break;
                }
            }
        }
        return illegalRoutes;
    }

    /**
     * Builds the driversCounts map of each {@link RouteNode} instance that is used by a net.
     * @param netWrapper A NetWrapper instance that represents a net.
     */
    private void buildDriverCountsOfRnodes(NetWrapper netWrapper) {
        for (Connection connection : netWrapper.getConnections()) {
            RouteNode driver = null;
            for (int i = connection.getRnodes().size() - 1; i >= 0; i--) {
                RouteNode rnode = connection.getRnodes().get(i);
                if (driver != null) {
                    rnode.incrementDriver(driver);
                }
                driver = rnode;
            }
        }
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
     * Checks if a connection has multi-driver nodes.
     * @param connection The connection in question.
     * @return true, if the connection has multi-driver nodes.
     */
    private boolean shouldMergePath(Connection connection) {
        return connection.useRnodesWithMultiDrivers();
    }

    /**
     * Rips up a connection.
     * @param connection The connection to be ripped up.
     */
    protected void ripUp(Connection connection) {
        List<RouteNode> rnodes = connection.getRnodes();
        if (rnodes.isEmpty()) {
            assert(!connection.getSink().isRouted());
            if (connection.getAltSinkRnodes().isEmpty()) {
                // If there is no alternate sink, decrement this one-and-only sink node
                RouteNode sinkRnode = connection.getSinkRnode();
                rnodes = Collections.singletonList(sinkRnode);
            }
        }

        for (RouteNode rnode : rnodes) {
            rnode.decrementUser(connection.getNetWrapper());
            rnode.updatePresentCongestionCost(presentCongestionFactor);
        }
    }

    /**
     * Updates the users and present congestion cost of rnodes used by a routed connection.
     * @param connection The routed connection.
     */
    private void updateUsersAndPresentCongestionCost(Connection connection) {
        for (RouteNode rnode : connection.getRnodes()) {
            rnode.incrementUser(connection.getNetWrapper());
            rnode.updatePresentCongestionCost(presentCongestionFactor);
        }
    }

    /**
     * Sets a list of {@link PIP} instances of each {@link Net} instance and checks if there is any PIP overlaps.
     */
    protected void setPIPsOfNets() {
        for (Entry<Net,NetWrapper> e : nets.entrySet()) {
            NetWrapper netWrapper = e.getValue();
            Net net = netWrapper.getNet();
            Set<PIP> newPIPs = new HashSet<>();
            for (Connection connection:netWrapper.getConnections()) {
                newPIPs.addAll(RouterHelper.getConnectionPIPs(connection));
            }
            net.setPIPs(newPIPs);
        }

        checkPIPsUsage();
    }

    /**
     * Checks if there are PIP overlaps among routed nets.
     */
    private void checkPIPsUsage() {
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
     * Routes a connection.
     * @param connection The connection to route.
     */
    protected void routeConnection(Connection connection) {
        float rnodeCostWeight = 1 - connection.getCriticality();
        float shareWeight = (float) (Math.pow(rnodeCostWeight, config.getShareExponent()));
        float rnodeWLWeight = rnodeCostWeight * oneMinusWlWeight;
        float estWlWeight = rnodeCostWeight * wlWeight;
        float dlyWeight = connection.getCriticality() * oneMinusTimingWeight / 100f;
        float estDlyWeight = connection.getCriticality() * timingWeight;

        prepareRouteConnection(connection, shareWeight, rnodeCostWeight,
                rnodeWLWeight, estWlWeight, dlyWeight, estDlyWeight);

        int nodesPoppedThisConnection = 0;
        RouteNode rnode;
        while ((rnode = queue.poll()) != null) {
            nodesPoppedThisConnection++;
            if (rnode.isTarget()) {
                break;
            }
            exploreAndExpand(rnode, connection, shareWeight, rnodeCostWeight,
                    rnodeWLWeight, estWlWeight, dlyWeight, estDlyWeight);
        }
        nodesPushed += nodesPoppedThisConnection + queue.size();
        nodesPopped += nodesPoppedThisConnection;

        if (rnode != null) {
            queue.clear();
            finishRouteConnection(connection, rnode);
            if (!connection.getSink().isRouted()) {
                throw new RuntimeException("Unable to save routing for connection " + connection);
            }
            if (config.isTimingDriven()) connection.updateRouteDelay();
            assert(connection.getSink().isRouted());
        } else {
            assert(queue.isEmpty());
            // Clears previous route of the connection
            connection.resetRoute();
            assert(connection.getRnodes().isEmpty());
            assert(!connection.getSink().isRouted());

            if (connection.getAltSinkRnodes().isEmpty()) {
                // Undo what ripUp() did for this connection which has a single exclusive sink
                RouteNode sinkRnode = connection.getSinkRnode();
                sinkRnode.incrementUser(connection.getNetWrapper());
                sinkRnode.updatePresentCongestionCost(presentCongestionFactor);
            }
        }

        routingGraph.resetExpansion();
    }

    /**
     * Deals with a failed connection by possible output pin swapping and unrouting preserved nets if the router is in the soft preserve mode.
     * @param connection The failed connection.
     */
    protected boolean handleUnroutableConnection(Connection connection) {
        if (config.isEnlargeBoundingBox()) {
            connection.enlargeBoundingBox(config.getExtensionXIncrement(), config.getExtensionYIncrement());
        }
        return routeIteration == 1 && swapOutputPin(connection);
    }

    protected boolean handleCongestedConnection(Connection connection) {
        if (config.isEnlargeBoundingBox()) {
            connection.enlargeBoundingBox(config.getExtensionXIncrement(), config.getExtensionYIncrement());
        }
        return false;
    }

    /**
     * Swaps the output pin of a connection, if its net has an alternative output pin.
     * @param connection The connection in question.
     * @return true, if the output pin has been swapped.
     */
    protected boolean swapOutputPin(Connection connection) {
        NetWrapper netWrapper = connection.getNetWrapper();
        Net net = netWrapper.getNet();

        SitePinInst altSource = net.getAlternateSource();
        if (altSource == null) {
            System.out.println("INFO: No alternative source to swap");
            return false;
        }

        SitePinInst source = connection.getSource();
        if (source.equals(altSource)) {
            altSource = net.getSource();
        }
        System.out.println("INFO: Swap source from " + source + " to " + altSource + "\n");

        RouteNode altSourceRnode = connection.getAltSourceRnode();
        if (altSourceRnode == null) {
            throw new RuntimeException("No alternate source pin on net: " + net.getName());
        }
        connection.setSource(altSource);
        connection.setSourceRnode(altSourceRnode);
        connection.getSink().setRouted(false);
        return true;
    }

    /**
     * Completes the routing process of a connection.
     * @param connection The routed target connection.
     */
    protected void finishRouteConnection(Connection connection, RouteNode rnode) {
        boolean routed = saveRouting(connection, rnode);
        if (routed) {
            connection.getSink().setRouted(routed);
            updateUsersAndPresentCongestionCost(connection);
        } else {
            connection.resetRoute();
        }
    }

    /**
     * Traces back for a connection from its sink rnode to its source, in order to build and store the routing path.
     * @param connection: The connection that is being routed.
     * @param rnode RouteNode to start backtracking from.
     * @return True if backtracking successful.
     */
    private boolean saveRouting(Connection connection, RouteNode rnode) {
        RouteNode sinkRnode = connection.getSinkRnode();
        List<RouteNode> altSinkRnodes = connection.getAltSinkRnodes();
        if (rnode != sinkRnode && !altSinkRnodes.contains(rnode)) {
            List<RouteNode> prevRouting = connection.getRnodes();
            // Check that this is the sink path marked by prepareRouteConnection()
            if (!connection.getSink().isRouted() || prevRouting.isEmpty() || !rnode.isTarget()) {
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
        if (rnodes.size() == 1) {
            // No prev pointer from sink rnode -> not routed
            return false;
        }

        RouteNode sourceRnode = rnodes.get(rnodes.size()-1);
        if (!sourceRnode.equals(connection.getSourceRnode())) {
            if (!sourceRnode.equals(connection.getAltSourceRnode())) {
                // Didn't backtrack to alternate source either -- invalid routing
                return false;
            }

            // Used source node is different to the one set on the connection
            Net net = connection.getNetWrapper().getNet();

            // Update connection's source SPI
            if (connection.getSource() == net.getSource()) {
                // Swap to alternate source
                connection.setSource(net.getAlternateSource());
            } else if (connection.getSource() == net.getAlternateSource()) {
                // Swap back to main source
                connection.setSource(net.getSource());
            } else {
                // Backtracked to neither the net's source nor its alternate source
                throw new RuntimeException("Backtracking terminated at unexpected rnode: " + rnode);
            }

            // Swap source rnode
            connection.setAltSourceRnode(connection.getSourceRnode());
            connection.setSourceRnode(sourceRnode);
        }

        return true;
    }

    /**
     * Explores children (downhill rnodes) of a rnode for routing a connection and pushes the child into the queue,
     * if it is the target or is an accessible routing resource.
     * @param rnode The rnode popped out from the queue.
     * @param connection The connection that is being routed.
     * @param shareWeight The criticality-aware share weight for a new sharing factor.
     * @param rnodeCostWeight The cost weight of the childRnode
     * @param rnodeLengthWeight The wirelength weight of childRnode's exact wirelength.
     * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
     * @param rnodeDelayWeight The weight of childRnode's exact delay.
     * @param rnodeEstDlyWeight The weight of estimated delay to the target.
     */
    private void exploreAndExpand(RouteNode rnode, Connection connection, float shareWeight, float rnodeCostWeight,
                                  float rnodeLengthWeight, float rnodeEstWlWeight,
                                  float rnodeDelayWeight, float rnodeEstDlyWeight) {
        boolean longParent = config.isTimingDriven() && DelayEstimatorBase.isLong(rnode);
        for (RouteNode childRNode:rnode.getChildren()) {
            // Targets that are visited more than once must be overused
            assert(!childRNode.isTarget() || !childRNode.isVisited(connectionsRouted) || childRNode.willOverUse(connection.getNetWrapper()));

            // If childRnode is preserved, then it must be preserved for the current net we're routing
            Net preservedNet;
            assert((preservedNet = routingGraph.getPreservedNet(childRNode)) == null ||
                    preservedNet == connection.getNetWrapper().getNet());

            if (childRNode.isVisited(connectionsRouted)) {
                // Node must be in queue already.

                // Note: it is possible this is a cheaper path to childRNode; however, because the
                // PriorityQueue class does not support (efficiently) reducing the cost of nodes
                // already in the queue, this opportunity is discarded
                continue;
            }

            if (childRNode.isTarget()) {
                boolean earlyTermination = false;
                if (childRNode == connection.getSinkRnode() && connection.getAltSinkRnodes().isEmpty()) {
                    // This sink must be exclusively reserved for this connection already
                    assert(childRNode.getOccupancy() == 0 ||
                            childRNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                    earlyTermination = true;
                } else {
                    // Target is not an exclusive sink, only early terminate if this net will not
                    // (further) overuse this node
                    earlyTermination = !childRNode.willOverUse(connection.getNetWrapper());
                }

                if (earlyTermination) {
                    assert(!childRNode.isVisited(connectionsRouted));
                    nodesPushed += queue.size();
                    queue.clear();
                }
            } else {
                if (!isAccessible(childRNode, connection)) {
                    continue;
                }
                switch (childRNode.getType()) {
                    case WIRE:
                        if (!routingGraph.isAccessible(childRNode, connection)) {
                            continue;
                        }
                        if (!config.isUseUTurnNodes() && childRNode.getDelay() > 10000) {
                            // To filter out those nodes that are considered to be excluded with the masking resource approach,
                            // such as U-turn shape nodes near the boundary
                            continue;
                        }
                        break;
                    case PINBOUNCE:
                        // A PINBOUNCE can only be a target if this connection has an alternate sink
                        assert(!childRNode.isTarget() || connection.getAltSinkRnodes().isEmpty());
                        if (!isAccessiblePinbounce(childRNode, connection)) {
                            continue;
                        }
                        break;
                    case PINFEED_I:
                        if (!isAccessiblePinfeedI(childRNode, connection)) {
                            continue;
                        }
                        break;
                    case LAGUNA_I:
                        if (!connection.isCrossSLR() ||
                            connection.getSinkRnode().getSLRIndex() == childRNode.getSLRIndex()) {
                            // Do not consider approaching a SLL if not needing to cross
                            continue;
                        }
                        break;
                    case SUPER_LONG_LINE:
                        assert(connection.isCrossSLR() &&
                                connection.getSinkRnode().getSLRIndex() != rnode.getSLRIndex());
                        break;
                    default:
                        throw new RuntimeException("Unexpected rnode type: " + childRNode.getType());
                }
            }

            evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, rnodeCostWeight,
                    rnodeLengthWeight, rnodeEstWlWeight, rnodeDelayWeight, rnodeEstDlyWeight);
            if (childRNode.isTarget() && queue.size() == 1) {
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

    /**
     * Checks if a NODE_PINBOUNCE is suitable to be used for routing to a target.
     * @param child The PINBOUNCE rnode in question.
     * @param connection The connection to route.
     * @return true, if the PINBOUNCE rnode is in the same column as the target and within one INT tile of the target.
     */
    protected boolean isAccessiblePinbounce(RouteNode child, Connection connection) {
        assert(child.getType() == RouteNodeType.PINBOUNCE);

        return routingGraph.isAccessible(child, connection);
    }

    protected boolean isAccessiblePinfeedI(RouteNode child, Connection connection) {
        // When LUT pin swapping is enabled, PINFEED_I are not exclusive anymore
        return isAccessiblePinfeedI(child, connection, !lutPinSwapping);
    }

    protected boolean isAccessiblePinfeedI(RouteNode child, Connection connection, boolean assertOnOveruse) {
        assert(child.getType() == RouteNodeType.PINFEED_I);
        assert(!assertOnOveruse || !child.isOverUsed());

        if (child.isTarget()) {
            return true;
        }

        if (child.countConnectionsOfUser(connection.getNetWrapper()) == 0 ||
            child.getIntentCode() != IntentCode.NODE_PINBOUNCE) {
            // Inaccessible if child is not a sink pin of another connection on the same
            // net, or it is not a PINBOUNCE node
            return false;
        }

        return true;
    }

    /**
     * Evaluates the cost of a child of a rnode and pushes the child into the queue after cost evaluation.
     * @param rnode The parent rnode of the child in question.
     * @param longParent A boolean value to indicate if the parent is a Long node
     * @param childRnode The child rnode in question.
     * @param connection The target connection being routed.
     * @param sharingWeight The sharing weight based on a connection's criticality and the shareExponent for computing a new sharing factor.
     * @param rnodeCostWeight The cost weight of the childRnode
     * @param rnodeLengthWeight The wirelength weight of childRnode's exact length.
     * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
     * @param rnodeDelayWeight The weight of childRnode's exact delay.
     * @param rnodeEstDlyWeight The weight of estimated delay from childRnode to the target.
     */
    protected void evaluateCostAndPush(RouteNode rnode, boolean longParent, RouteNode childRnode, Connection connection, float sharingWeight, float rnodeCostWeight,
                                       float rnodeLengthWeight, float rnodeEstWlWeight,
                                       float rnodeDelayWeight, float rnodeEstDlyWeight) {
        int countSourceUses = childRnode.countConnectionsOfUser(connection.getNetWrapper());
        float sharingFactor = 1 + sharingWeight* countSourceUses;

        // Set the prev pointer, as RouteNode.getEndTileYCoordinate() and
        // RouteNode.getSLRIndex() require this
        childRnode.setPrev(rnode);

        float newPartialPathCost = rnode.getUpstreamPathCost() + rnodeCostWeight * getNodeCost(childRnode, connection, countSourceUses, sharingFactor)
                                + rnodeLengthWeight * childRnode.getLength() / sharingFactor;
        if (config.isTimingDriven()) {
            newPartialPathCost += rnodeDelayWeight * (childRnode.getDelay() + DelayEstimatorBase.getExtraDelay(childRnode, longParent));
        }

        int childX = childRnode.getEndTileXCoordinate();
        int childY = childRnode.getEndTileYCoordinate();
        RouteNode sinkRnode = connection.getSinkRnode();
        int sinkX = sinkRnode.getBeginTileXCoordinate();
        int sinkY = sinkRnode.getBeginTileYCoordinate();
        int deltaX = Math.abs(childX - sinkX);
        int deltaY = Math.abs(childY - sinkY);
        if (connection.isCrossSLR()) {
            int deltaSLR = Math.abs(sinkRnode.getSLRIndex() - childRnode.getSLRIndex());
            if (deltaSLR != 0) {
                // Check for overshooting which occurs when child and sink node are in
                // adjacent SLRs and less than a SLL wire's length apart in the Y axis.
                if (deltaSLR == 1) {
                    int overshootByY = deltaY - RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES;
                    if (overshootByY < 0) {
                        assert(deltaY < RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);
                        deltaY = RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES - overshootByY;
                    }
                }

                // Account for any detours that must be taken to get to and back from the closest Laguna column
                int nextLagunaColumn = routingGraph.nextLagunaColumn[childX];
                int prevLagunaColumn = routingGraph.prevLagunaColumn[childX];
                int nextLagunaColumnDist = Math.abs(nextLagunaColumn - childX);
                int prevLagunaColumnDist = Math.abs(prevLagunaColumn - childX);
                if (sinkX >= childX) {
                    if (nextLagunaColumnDist <= prevLagunaColumnDist || prevLagunaColumn == Integer.MIN_VALUE) {
                        assert (nextLagunaColumn != Integer.MAX_VALUE);
                        deltaX = Math.abs(nextLagunaColumn - childX) + Math.abs(nextLagunaColumn - sinkX);
                    } else {
                        deltaX = Math.abs(childX - prevLagunaColumn) + Math.abs(sinkX - prevLagunaColumn);
                    }
                } else { // childX > sinkX
                    if (prevLagunaColumnDist <= nextLagunaColumnDist) {
                        assert (prevLagunaColumn != Integer.MIN_VALUE);
                        deltaX = Math.abs(childX - prevLagunaColumn) + Math.abs(sinkX - prevLagunaColumn);
                    } else {
                        deltaX = Math.abs(nextLagunaColumn - childX) + Math.abs(nextLagunaColumn - sinkX);
                    }
                }

                assert(deltaX >= 0);
            }
        }

        int distanceToSink = deltaX + deltaY;
        float newTotalPathCost = newPartialPathCost + rnodeEstWlWeight * distanceToSink / sharingFactor;
        if (config.isTimingDriven()) {
            newTotalPathCost += rnodeEstDlyWeight * (deltaX * 0.32 + deltaY * 0.16);
        }
        push(childRnode, newPartialPathCost, newTotalPathCost);
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
        boolean hasSameSourceUsers = countSameSourceUsers!= 0;
        float presentCongestionCost;

        if (hasSameSourceUsers) {// the rnode is used by other connection(s) from the same net
            int overoccupancy = rnode.getOccupancy() - RouteNode.capacity;
            // make the congestion cost less for the current connection
            presentCongestionCost = 1 + overoccupancy * presentCongestionFactor;
        } else {
            presentCongestionCost = rnode.getPresentCongestionCost();
        }

        float biasCost = 0;
        if (!rnode.isTarget() && rnode.getType() != RouteNodeType.SUPER_LONG_LINE) {
            NetWrapper net = connection.getNetWrapper();
            biasCost = rnode.getBaseCost() / net.getConnections().size() *
                    (Math.abs(rnode.getEndTileXCoordinate() - net.getXCenter()) + Math.abs(rnode.getEndTileYCoordinate() - net.getYCenter())) / net.getDoubleHpwl();
        }

        return rnode.getBaseCost() * rnode.getHistoricalCongestionCost() * presentCongestionCost / sharingFactor + biasCost;
    }

    /**
     * Sets the costs of a rnode and pushes it to the queue.
     * @param childRnode A child rnode.
     * @param newPartialPathCost The upstream path cost from childRnode to the source.
     * @param newTotalPathCost Total path cost of childRnode.
     */
    protected void push(RouteNode childRnode, float newPartialPathCost, float newTotalPathCost) {
        assert(childRnode.getPrev() != null || childRnode.getType() == RouteNodeType.PINFEED_O);
        childRnode.setLowerBoundTotalPathCost(newTotalPathCost);
        childRnode.setUpstreamPathCost(newPartialPathCost);
        // Use the number-of-connections-routed-so-far as the identifier for whether a rnode
        // has been visited by this connection before
        childRnode.setVisited(connectionsRouted);
        queue.add(childRnode);
    }

    /**
     * Prepares for routing a connection, including seeding the routing queue with
     * known-uncongested downstream-from-source routing segments acquired from prior
     * iterations, as well as marking known-uncongested upstream-from-sink segments
     * as targets.
     * @param connectionToRoute The target connection to be routed.
     * @param shareWeight The criticality-aware share weight for a new sharing factor.
     * @param rnodeCostWeight The cost weight of the childRnode
     * @param rnodeLengthWeight The wirelength weight of childRnode's exact wirelength.
     * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
     * @param rnodeDelayWeight The weight of childRnode's exact delay.
     * @param rnodeEstDlyWeight The weight of estimated delay to the target.
     */
    protected void prepareRouteConnection(Connection connectionToRoute, float shareWeight, float rnodeCostWeight,
                                          float rnodeLengthWeight, float rnodeEstWlWeight,
                                          float rnodeDelayWeight, float rnodeEstDlyWeight) {
        // Rips up the connection
        ripUp(connectionToRoute);

        connectionsRouted++;
        connectionsRoutedIteration++;
        assert(queue.isEmpty());

        // Sets the sink rnode(s) of the connection as the target(s)
        connectionToRoute.setAllTargets(true);

        // Adds the source rnode to the queue
        RouteNode sourceRnode = connectionToRoute.getSourceRnode();
        assert(sourceRnode.getPrev() == null);
        push(sourceRnode, 0, 0);
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
        if (sortedIndirectConnections.size() > 0) {
            timingManager.getCriticalPathInfo(maxDelayAndTimingVertex, false, routingGraph);
        }
    }

    public static void printNodeTypeUsageAndWirelength(boolean verbose, Map<IntentCode, Long> nodeTypeUsage, Map<IntentCode, Long> nodeTypeLength) {
        if (verbose) {
            System.out.println("Node Usage Per Type");
            System.out.printf(" %-16s  %13s  %12s\n", "Node Type", "Usage", "Length");
            for (IntentCode ic : nodeTypes) {
                long usage = nodeTypeUsage.getOrDefault(ic, 0L);
                long length = nodeTypeLength.getOrDefault(ic, 0L);
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

    private void printRoutingStatistics() {
        MessageGenerator.printHeader("Statistics");
        computesNodeUsageAndTotalWirelength();
        printNodeTypeUsageAndWirelength(config.isVerbose(), nodeTypeUsage, nodeTypeLength);
        printFormattedString("Total wirelength:", totalWL);
        if (config.isVerbose()) {
            printFormattedString("Total INT tile nodes:", totalINTNodes);
            printFormattedString("Total rnodes created:", routingGraph.numNodes());
            printFormattedString("Average #children per node:", routingGraph.averageChildren());
            System.out.printf("------------------------------------------------------------------------------\n");
            printFormattedString("Num iterations:", routeIteration);
            printFormattedString("Connections routed:", connectionsRouted);
            printFormattedString("Nodes pushed:", nodesPushed);
            printFormattedString("Nodes popped:", nodesPopped);
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
        if (!config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Not masking nodes across RCLK could result in delay optimism.");
        }

        return routeDesign(design, new RWRoute(design, config));
    }

    /**
     * Routes a design after pre-processing.
     * @param design The {@link Design} instance to be routed.
     * @param router A {@link RWRoute} object to be used to route the design.
     */
    protected static Design routeDesign(Design design, RWRoute router) {
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
        Design input = null;
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
