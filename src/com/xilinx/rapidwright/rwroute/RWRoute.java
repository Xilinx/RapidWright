/*
 * 
 * Copyright (c) 2021 Ghent University. 
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.ClkRouteTiming;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

/**
 * RWRoute class provides the main methods for routing a design.
 * Creating a RWRoute Object needs a {@link Design} Object and a {@link RWRouteConfig} Object.
 */
public class RWRoute{
	/** The design to route */
	protected Design design;
	/** A flag to indicate if the device has multiple SLRs, for the sake of avoiding unnecessary check for single-SLR devices */
	private boolean multiSLRDevice;
	/** Created NetWrappers */
	protected Map<Net,NetWrapper> nets;
	/** A list of indirect connections that will go through iterative routing */
	private List<Connection> indirectConnections;
	/** A list of direct connections that are easily routed through dedicated resources */
	private List<Connection> directConnections;
	/** Sorted indirect connections */
	private List<Connection> sortedIndirectConnections;
	/** A list of global clock nets */
	private List<Net> clkNets;
	/** Static nets */
	private Map<Net, List<SitePinInst>> staticNetAndRoutingTargets;
	/** Nets with conflicting nodes that should be added to the routing targets */
	protected Set<Net> conflictNets;
	/** Several integers to indicate the netlist info */
	private int numPreservedRoutableNets;
	private int numPreservedClks;
	private int numPreservedStaticNets;
	private int numPreservedWire;
	private int numWireNetsToRoute;
	private int numConnectionsToRoute;
	private int numNotNeedingRoutingNets;
	private int numUnrecognizedNets;
	
	/** A {@link RWRouteConfig} instance consisting of a list of routing parameters */
	protected RWRouteConfig config;
	/** The present congestion cost factor */
	private float presentCongestionFactor;
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
	
	/** The current routing iteration */
	private int routeIteration;
	/** Timers to store runtime of different phases */
	private RuntimeTrackerTree routerTimer;
	private RuntimeTracker rnodesTimer;
	private RuntimeTracker updateTimingTimer;
	private RuntimeTracker updateCongestionCosts;
	/** An instantiation of RouteThruHelper to avoid route-thrus in the routing resource graph */
	private RouteThruHelper routethruHelper;
	
	/** A set of indices of overused rondes */
	private Set<Routable> overUsedRnodes;
	/** TODO */
	RoutableGraph routingGraph;
	/** The queue to store candidate nodes to route a connection */
	private PriorityQueue<Routable> queue;

	/** Total wirelength of the routed design */
	private int totalWL;
	/** Total used INT tile nodes */
	private long totalINTNodes;
	/** A map from node types to the node usage of the types */
	private Map<IntentCode, Long> nodeTypeUsage;
	/** A map from node types to the total wirelength of used nodes of the types */
	private Map<IntentCode, Long> nodeTypeLength;
	/** The total number of connections that are routed */
	private int connectionsRouted;
	/** The total number of connections routed in an iteration */
	private int connectionsRoutedIteration;
	/** Total number of nodes popped from the queue */
	private long nodesPopped;

	/** The maximum criticality constraint of connection */
	final private static float MAX_CRITICALITY = 0.99f;
	/** The minimum criticality of connections that should be re-routed, updated after each iteration */
	private float minRerouteCriticality;
	/** The list of critical connections */
	private List<Connection> criticalConnections;
	/** A {@link TimingManager} instance to use that handles timing related tasks */
	private TimingManager timingManager;
	/** A map from nodes to delay values, used for timing update after fixing routes */
	private Map<Node, Float> nodesDelays;
	/** The maximum delay and associated timing vertex */
	private Pair<Float, TimingVertex> maxDelayAndTimingVertex;
	
	/** A map storing routes from CLK_OUT to different INT tiles that connect to sink pins of a global clock net */
	private Map<String, List<String>> routesToSinkINTTiles;
	
	public RWRoute(Design design, RWRouteConfig config){
		this.design = design;
		multiSLRDevice = design.getDevice().getSLRs().length > 1;
		
		this.config = config;
		routerTimer = new RuntimeTrackerTree("Route design", config.isVerbose());
		rnodesTimer = routerTimer.createStandAloneRuntimeTracker("rnodes creation");
		updateTimingTimer = routerTimer.createStandAloneRuntimeTracker("update timing");
		updateCongestionCosts = routerTimer.createStandAloneRuntimeTracker("update congestion costs");
		routerTimer.createRuntimeTracker("Initialization", routerTimer.getRootRuntimeTracker()).start();

		minRerouteCriticality = config.getMinRerouteCriticality();
		criticalConnections = new ArrayList<>();

		queue = new PriorityQueue<>((r1,r2) -> Float.compare(r1.getLowerBoundTotalPathCost(), r2.getLowerBoundTotalPathCost()));
		if(config.isTimingDriven()) {
			/* An instantiated delay estimator that is used to calculate delay of routing resources */
			DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
			routingGraph = new RoutableGraphTimingDriven(rnodesTimer, estimator, config.isMaskNodesCrossRCLK());
			nodesDelays = new HashMap<>();
		} else {
			routingGraph = new RoutableGraph(rnodesTimer);
		}

		routerTimer.createRuntimeTracker("determine route targets", "Initialization").start();
		determineRoutingTargets();
		routerTimer.getRuntimeTracker("determine route targets").stop();
		
		if(config.isTimingDriven()) {
			ClkRouteTiming clkTiming = createClkTimingData(config);
			routesToSinkINTTiles = clkTiming == null? null : clkTiming.getRoutesToSinkINTTiles();
			Collection<Net> timingNets;
			if (config.isResolveConflictNets()) {
				timingNets = conflictNets;
			} else {
				timingNets = indirectConnections.stream().map((c) -> c.getNetWrapper().getNet()).collect(Collectors.toSet());
			}
			timingManager = new TimingManager(design, true, routerTimer, config, clkTiming, timingNets);
			timingManager.setTimingEdgesOfConnections(indirectConnections);
		}
		
		sortedIndirectConnections = new ArrayList<>();		
		routethruHelper = new RouteThruHelper(design.getDevice());		
		connectionsRouted = 0;
		connectionsRoutedIteration = 0;
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
		if(clkRouteTimingFile != null) {
			return new ClkRouteTiming(clkRouteTimingFile);
		}
		return null;
	}
	
	/**
	 * Classifies {@link Net} Objects into different categories: clocks, static nets,
	 * and regular signal nets (i.e. {@link NetType}.WIRE) and determines routing targets.
	 */
	protected void determineRoutingTargets(){
		categorizeNets();

		// Wait for all outstanding RoutingGraph.asyncPreserve() calls to complete
		routingGraph.awaitPreserve();
	}
	
	private void categorizeNets() {
		numWireNetsToRoute = 0;
		numConnectionsToRoute = 0;
		numPreservedRoutableNets = 0;
		numNotNeedingRoutingNets = 0;
		numUnrecognizedNets = 0;
		
		nets = new HashMap<>();
		indirectConnections = new ArrayList<>();
		directConnections = new ArrayList<>();
		clkNets = new ArrayList<>();
		staticNetAndRoutingTargets = new HashMap<>();
		conflictNets = new HashSet<>();

		for(Net net : design.getNets()){	
			if(net.isClockNet()){
				addGlobalClkRoutingTargets(net);
				
			}else if(net.isStaticNet()){
				addStaticNetRoutingTargets(net);
				
			}else if (net.getType().equals(NetType.WIRE)){
				if(RouterHelper.isRoutableNetWithSourceSinks(net)){
					addNetConnectionToRoutingTargets(net);
				}else if(RouterHelper.isDriverLessOrLoadLessNet(net)){
					preserveNet(net);
					numNotNeedingRoutingNets++;
				}else if(RouterHelper.isInternallyRoutedNet(net)){
					preserveNet(net);
					numNotNeedingRoutingNets++;
				}else {
					numNotNeedingRoutingNets++;
				}
			}else {
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
		if(config.isPrintConnectionSpan()) System.out.println(" Span" + "\t" + "# Connections" + "\t" + "Percent");
		
		long sumSpan = 0;
		short max = 0;
		for(Entry<Short, Integer> spanCount : connectionSpan.entrySet()) {
			Short span = spanCount.getKey();
			Integer count = spanCount.getValue();
			if(config.isPrintConnectionSpan()) {
				System.out.printf("%5d \t%12d \t%7.2f\n", span, count, (float)count / indirectConnections.size() * 100);
			}
			sumSpan += span * count;
			if(span > max) max = span;
		}
		
		if(config.isPrintConnectionSpan()) System.out.println();
		long avg = (long) (sumSpan / ((float) indirectConnections.size()));
		System.out.println("INFO: Max span of connections: " + max);
		System.out.println("INFO: Avg span of connections: " + avg);
		int numConnectionsLongerThanAvg = 0;
		for(Entry<Short, Integer> spanCount : connectionSpan.entrySet()) {
			if(spanCount.getKey() >= avg) numConnectionsLongerThanAvg += spanCount.getValue();
		}
		
		System.out.printf("INFO: # connections longer than avg span: %d\n", numConnectionsLongerThanAvg);
		System.out.printf("(%5.2f%%)\n", (float)numConnectionsLongerThanAvg / indirectConnections.size() * 100);
		System.out.println("------------------------------------------------------------------------------");
	}
	
	/**
	 * Adds the clock net to the list of clock routing targets, if the clock has source and sink {@link SitePinInst} instances.
	 * @param clk The clock net in question.
	 */
	protected void addGlobalClkRoutingTargets(Net clk) {
		if(RouterHelper.isRoutableNetWithSourceSinks(clk)) {
			clk.unroute();
			clkNets.add(clk);
		}else {
			numNotNeedingRoutingNets++;
			System.err.println("ERROR: Incomplete clock net " + clk);
		}
	}
	
	/**
	 * Routes clock nets by default or in a different way when corresponding timing info supplied.
	 * NOTE: For an unrouted design, its clock nets must not contain any PIPs or nodes, i.e, completely unrouted.
	 * Otherwise, there could be a critical warning of clock routing results, when loading the routed design into Vivado.
	 * Vivado will unroute the global clock nets immediately when there is such warning.
	 * TODO: fix the potential issue.
	 */
	private void routeGlobalClkNets() {
 		if(clkNets.size() > 0) System.out.println("INFO: Route clock nets");
 		for(Net clk : clkNets) {
 			if(routesToSinkINTTiles != null) {
 				// routes clock nets with references of partial routes
				System.out.println("INFO: Route with clock route and timing data");
				GlobalSignalRouting.routeClkWithPartialRoutes(clk, routesToSinkINTTiles, design.getDevice());
 			}else {
 				// routes clock nets from scratch
				System.out.println("INFO: Route with symmetric non-timing-driven clock router");
 				GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice());
 			}
			preserveNet(clk);
 		}
	}
	
	/**
	 * Adds and initialize a regular signal net to the list of routing targets.
	 * @param net The net to be added for routing.
	 */
	protected void addNetConnectionToRoutingTargets(Net net) {
		net.unroute();
		createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), multiSLRDevice);
	}
	
	/**
	 * Adds a static net to the static net routing target list.
	 * @param staticNet The static net in question, i.e. VCC or GND.
	 */
	protected void addStaticNetRoutingTargets(Net staticNet){
		List<SitePinInst> sinks = staticNet.getSinkPins();
		if(sinks.size() > 0 ) {
			List<Node> sinkNodes = new ArrayList<>(sinks.size());
			sinks.forEach((p) -> sinkNodes.add(p.getConnectedNode()));
			addPreservedNodes(sinkNodes, staticNet);
			addStaticNetRoutingTargets(staticNet, sinks);
		}else {
			preserveNet(staticNet);
			numNotNeedingRoutingNets++;	
		}
	}
	
	protected void addStaticNetRoutingTargets(Net staticNet, List<SitePinInst> sinks) {
		staticNetAndRoutingTargets.put(staticNet, sinks);
	}
	
	/**
	 * Routes static nets with preserved resources list supplied to avoid conflicting nodes.
	 */
	private void routeStaticNets(){
		if (staticNetAndRoutingTargets.isEmpty())
			return;

		// FIXME
		throw new RuntimeException();

		// for(List<SitePinInst> netRouteTargetPins : staticNetAndRoutingTargets.values()) {
		// 	for(SitePinInst sink : netRouteTargetPins) {
		// 		routingGraph.unpreserve(sink.getConnectedNode());
		// 	}
		// }
		//
		// RouterHelper.invertPossibleGndPinsToVccPins(design, design.getGndNet());
		//
		// // If connections of other nets are routed first, used resources should be preserved.
		// Set<Node> unavailableNodes = getAllUsedNodesOfRoutedConnections();
		// unavailableNodes.addAll(routingGraph.getPreservedNodes());
		// // If the connections of other nets are not routed yet,
		// // the nodes connected to pins of other nets must be preserved.
		// unavailableNodes.addAll(routingGraph.getNodes());
		//
		// for(Net net : staticNetAndRoutingTargets.keySet()){
		// 	System.out.println("INFO: Route " + net.getSinkPins().size() + " pins of " + net);
		// 	Map<SitePinInst, List<Node>> sinksRoutingPaths = GlobalSignalRouting.routeStaticNet(net, unavailableNodes, design, routethruHelper);
		//
		// 	for(Entry<SitePinInst, List<Node>> sinkPath : sinksRoutingPaths.entrySet()) {
		// 		addPreservedNodes(sinkPath.getValue(), net);
		// 		unavailableNodes.addAll(sinkPath.getValue());
		// 	}
		// }
	}
	
	/**
	 * Gets a set of nodes used by all the routed connections.
	 * @return A set of used nodes.
	 */
	private Set<Node> getAllUsedNodesOfRoutedConnections(){
		Set<Node> nodes = new HashSet<>();
		for(Connection connection : sortedIndirectConnections){
			if(connection.getNodes() != null) nodes.addAll(connection.getNodes());
		}	
		return nodes;
	}
	
	/**
	 * Preserves a net by preserving all nodes use by the net.
	 * @param net The net to be preserved.
	 */
	protected void preserveNet(Net net){
		routingGraph.asyncPreserve(net);
	}
	
	protected void increaseNumNotNeedingRouting() {
		numNotNeedingRoutingNets++;
	}
	
	protected void increaseNumPreservedClks() {
		numPreservedClks++;
		numPreservedRoutableNets++;
	}
	
	protected void increaseNumPreservedStaticNets() {
		numPreservedStaticNets++;
		numPreservedRoutableNets++;
	}
	
	protected void increaseNumPreservedWireNets() {
		numPreservedWire++;
		numPreservedRoutableNets++;
	}
	
	private Map<Short, Integer> connectionSpan = new HashMap<>();

	/**
	 * Creates a unique {@link NetWrapper} instance and {@link Connection} instances based on a {@link Net} instance.
	 * @param net The net to be initialized.
	 * @param boundingBoxExtensionX The bounding box extension factor for restricting accessible routing resource of a connection in the horizontal direction.
	 * @param boundingBoxExtensionY The bounding box extension factor for restricting accessible routing resource of a connection in the vertical direction.
	 * @param multiSLR The flag to indicate if the device has multiple SLRs.
	 * @return A {@link NetWrapper} instance.
	 */
	protected NetWrapper createsNetWrapperAndConnections(Net net, short boundingBoxExtensionX, short boundingBoxExtensionY, boolean multiSLR) {
		NetWrapper netWrapper = new NetWrapper(numWireNetsToRoute++, net);
		NetWrapper existingNetWrapper = nets.put(net, netWrapper);
		assert(existingNetWrapper == null);
		SitePinInst source = net.getSource();
		int indirect = 0;
		Node sourceINTNode = null;
		
		for(SitePinInst sink : net.getSinkPins()){
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = net.getAlternateSource();
				if(source == null){
					String errMsg = "Null alternate source is for COUT-CIN connection: " + net.toStringFull();
					 throw new IllegalArgumentException(errMsg);
				}
			}
			Connection connection = new Connection(numConnectionsToRoute++, source, sink, netWrapper);
			
			List<Node> nodes = RouterHelper.projectInputPinToINTNode(sink);
			if(nodes.isEmpty()) {
				directConnections.add(connection);
				connection.setDirect(true);
			}else {
				Node sinkINTNode = nodes.get(0);
				indirectConnections.add(connection);

				connection.setSinkRnode(createAddRoutableNode(connection.getSink(), sinkINTNode, RoutableType.PINFEED_I));
				if(sourceINTNode == null) {
					sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
					if(sourceINTNode == null) {
						throw new RuntimeException("ERROR: Null projected INT node for the source of net " + net.toStringFull());
					}
				}
				connection.setSourceRnode(createAddRoutableNode(connection.getSource(), sourceINTNode, RoutableType.PINFEED_O));
				connection.setDirect(false);

				indirect++;
				connection.computeHpwl();
				addConnectionSpanInfo(connection);
			}
		}
		
		if(indirect > 0) {
			netWrapper.computeHPWLAndCenterCoordinates();
			if(config.isUseBoundingBox()) {
				for(Connection connection : netWrapper.getConnections()) {
					if(connection.isDirect()) continue;
					connection.computeConnectionBoundingBox(boundingBoxExtensionX, boundingBoxExtensionY,multiSLR);
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
	 * Adds preserved nodes. Duplicated nodes (more specifically,
	 * nodes already preserved with the same net) have no effect.
	 * @param nodes A collection of nodes to be preserved.
	 * @param netToPreserve The net that uses those nodes.
	 */
	protected void addPreservedNodes(Collection<Node> nodes, Net netToPreserve) {
		// for(Node node : nodes) {
		// 	Net reserved = routingGraph.preserve(node, netToPreserve);
		// 	if (reserved == null)
		// 		continue;
		// 	// Nodes already preserved by the same net are ignored
		// 	if (reserved.equals(netToPreserve))
		// 		continue;
		// 	if (reserved.getSource() != null && netToPreserve.getSource() != null) {
		// 		boolean generateWarning = conflictNets.size() < 5;
		// 		EDIFNet reservedLogical = reserved.getLogicalNet();
		// 		EDIFNet toReserveLogical = netToPreserve.getLogicalNet();
		// 		if(reservedLogical != null && toReserveLogical != null) {
		// 			if(!toReserveLogical.equals(reservedLogical)) {
		// 				if(generateWarning) generateConflictInfo(node, reserved, netToPreserve);
		// 			}
		// 		}else {
		// 			if(generateWarning) generateConflictInfo(node, reserved, netToPreserve);
		// 		}
		// 		conflictNets.add(reserved);
		// 		conflictNets.add(netToPreserve);
		// 	}
		// }
		routingGraph.asyncPreserve(nodes, netToPreserve);
	}

	private void generateConflictInfo(Node node, Net reserved, Net netToPreserve) {
		System.out.println("WARNING: Conflicting node " + node + ":");
		System.out.println("         " + netToPreserve.getName() + " \n         " + reserved.getName());
	}
	
	public boolean isMultiSLRDevice() {
		return multiSLRDevice;
	}

	protected void removeNetNodesFromPreservedNodes(Net net) {
		Collection<Node> netNodes = RouterHelper.getNodesOfNet(net);
		for(Node node : netNodes) {
			routingGraph.unpreserve(node);
		}
		numPreservedWire--;
	}

	/**
	 * Creates a {@link RoutableNode} Object based on a {@link Node} instance and avoids duplicates,
	 * used for creating the source and sink rnodes of {@link Connection} instances.
	 * NOTE: This method does not consider the preserved nodes.
	 * @param sitePinInst The source or sink {@link SitePinInst} instance.
	 * @param node The node associated to the {@link SitePinInst} instance.
	 * @param type The {@link RoutableType} of the {@link RoutableNode} Object.
	 * @return The created {@link RoutableNode} instance.
	 */
	protected Routable createAddRoutableNode(SitePinInst sitePinInst, Node node, RoutableType type){
		Pair<Routable,Boolean> ret = routingGraph.getOrCreate(node, type);
		Routable rnode = ret.getFirst();
		boolean inserted = ret.getSecond();
		if (!inserted) {
			// this is for checking preserved routing resource conflicts among routed nets */
			if(rnode.getRoutableType() == type && type == RoutableType.PINFEED_I) {
				System.out.println("WARNING: Conflicting node: " + node + ", connected to sink " + sitePinInst);
			}
		}
		return rnode;
	}
	
	/**
	 * Initializes routing.
	 */
	private void initializeRouting(){
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
	public void route(){
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
		
		// Prints routing statistics, e.g. total wirelength, runtime and timing report
		printRoutingStatistics();
	}
	
	/**
	 * Calculates initial criticality for each connection based on a simple estimation.
	 */
	private void preRoutingEstimation() {
		if(config.isTimingDriven()) {
			estimateDelayOfConnections();
			maxDelayAndTimingVertex = timingManager.calculateArrivalRequireTimes();
			timingManager.calculateCriticality(indirectConnections, MAX_CRITICALITY, config.getCriticalityExponent(), maxDelayAndTimingVertex.getFirst());
			System.out.printf("INFO: Estimated pre-routing max delay: %4d\n", (short) maxDelayAndTimingVertex.getFirst().floatValue());
		}
	}
	
	/**
	 * A simple approach to estimate delay of each connection and update route delay of its timing edges.
	 */
	private void estimateDelayOfConnections() {	
		for(Connection connection : indirectConnections) {
			Routable source = connection.getSourceRnode();
			if(source.getChildren().length == 0) {
				// output pin is blocked
				swapOutputPin(connection);
				source = connection.getSourceRnode();
			}
			short estDelay = (short) 10000;
			for(Routable child : source.getChildren()) {				
				short tmpDelay = 113;
				tmpDelay += child.getDelay();
				if(tmpDelay < estDelay) {
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
		for(Connection connection : directConnections) {
			boolean success = RouterHelper.routeDirectConnection(connection);
			// no need to update route delay of direct connection, because it would not be changed
			if(!success) System.err.println("ERROR: Failed to route direct connection " + connection);
		}
	}
	
	/**
	 * Routes indirect connections iteratively.
	 */
	public void routeIndirectConnections(){
		sortConnections();
		initializeRouting();
		long lastIterationRnodeCount = 0;
		long lastIterationRnodeTime = 0;
		boolean unrouted = false;
		
		while(routeIteration < config.getMaxIterations()){
			long startIteration = System.nanoTime();
			connectionsRoutedIteration = 0;
			if(config.isTimingDriven()) {
				setRerouteCriticality();
			}
			for(Connection connection : sortedIndirectConnections) {
				if(shouldRoute(connection)){
					routeConnection(connection);
				}
			}
			if(config.isTimingDriven()) {
				updateTiming();
			}
			
			updateCostFactors();

			long generatedRnodes = routingGraph.numNodes() - lastIterationRnodeCount;
			printRoutingIterationStatisticsInfo(System.nanoTime() - startIteration, generatedRnodes,
					(float) ((rnodesTimer.getTime() - lastIterationRnodeTime) * 1e-9), config.isTimingDriven());

			if(overUsedRnodes.size() == 0) {
				List<Connection> unroutedConnections = getUnroutedConnections();
				if(unroutedConnections.isEmpty()) {
					break;
				}else {
					if(routeIteration == config.getMaxIterations() - 1) {
						System.err.println("ERROR: Unroutable connections: " + unroutedConnections.size());
					}
				}
			}

			if (routeIteration > 1 && generatedRnodes == 0) {
				Set<NetWrapper> unroutableNets = new HashSet<>();
				for (Connection connection : getUnroutedConnections()) {
					// unroutableNets.add(connection.getNetWrapper());
					unrouteReservedNetsToReleaseResources(connection);
				}
				// for (Routable rnode : overUsedRnodes) {
                // 	unroutableNets.addAll(rnode.getUsersConnectionCounts().keySet());
				// }

				// for (NetWrapper netWrapper : unroutableNets) {
				// 	Net net = netWrapper.getNet();
				// 	List<Node> netNodes = RouterHelper.getNodesOfNet(net);
				//
				// 	System.out.println("INFO: Unpreserving " + netNodes.size() + " nodes on " + net.getName());
				// 	// System.out.println("INFO: Releasing nodes within 1-hop of the " + netNodes.size() + " nodes on " + net.getName());
				// 	for (Node node : netNodes) {
				// 		Routable rnode = routingGraph.getNode(node);
				//
				// 		if (node != net.getSource().getConnectedNode()) {
				// 			Net preservedNet = routingGraph.getPreservedNet(node);
				// 			uphill: for(Node parentNode : node.getAllUphillNodes()) {
				// 				// if (routingGraph.isPreserved(parentNode)) {
				// 				// 	continue;
				// 				// }
				//
				// 				Net preservedParentNet = routingGraph.getPreservedNet(parentNode);
				// 				if (preservedParentNet != null) {
				// 					if (preservedParentNet == net) continue;
				// 					if (preservedParentNet.isClockNet() || preservedParentNet.isStaticNet()) continue;
				// 				}
				//
				// 				Routable parentRnode = createAddRoutableNode(null, parentNode, RoutableType.WIRE);
				// 				for (Routable childRnode : parentRnode.getChildren()) {
				// 					if (childRnode == rnode) {
				// 						continue uphill;
				// 					}
				// 				}
				//
				// 				if (!parentRnode.isUsed()) {
				// 					// Mark this node with a placeholder (null) user so that
				// 					// it can be recognized as being over-used; the preserved net
				// 					// that actually uses this node will only be inserted when
				// 					// necessary by updateCost()
				// 					parentRnode.incrementUser(null);
				// 				}
				//
				// 				System.out.println("\t" + node + " <- " + parentNode);
				// 				parentRnode.addChild(rnode);
				// 			}
				// 			assert(preservedNet == null || preservedNet == net);
				// 			// if (preservedNet != null && preservedNet != net)
				// 			// 	System.out.println(node + " :" + preservedNet + " != " + net);
				// 			routingGraph.unpreserve(node);
				// 			rnode.setVisited(false);
				// 		}
				//
				// 		// for(Node childNode : node.getAllDownhillNodes()) {
				// 		// 	Net preservedNet = routingGraph.getPreservedNet(childNode);
				// 		// 	if (preservedNet == null) continue;
				// 		// 	if (preservedNet == net) continue;
				// 		// 	if (preservedNet.isClockNet() || preservedNet.isStaticNet()) continue;
				// 		//
				// 		// 	Routable childRnode = createAddRoutableNode(null, childNode, RoutableType.WIRE);
				// 		// 	if (Arrays.stream(rnode.getChildren()).noneMatch(childRnode::equals)) {
				// 		// 		rnode.addChild(childRnode);
				// 		//
				// 		// 		if (!childRnode.isUsed()) {
				// 		// 			// Mark this node with a placeholder (null) user so that
				// 		// 			// it can be recognized as being over-used; the preserved net
				// 		// 			// that actually uses this node will only be inserted when
				// 		// 			// necessary by updateCost()
				// 		// 			childRnode.incrementUser(null);
				// 		// 		}
				// 		//
				// 		// 		System.out.println("\t" + node + " -> " + childNode + " from " + preservedNet);
				// 		// 	}
				// 		// }
				// 	}
				// }
			}

			routeIteration++;
			lastIterationRnodeCount = routingGraph.numNodes();
			lastIterationRnodeTime = rnodesTimer.getTime();
		}
		if(routeIteration == config.getMaxIterations()) {
			System.out.println("\nERROR: Routing terminated after " + (routeIteration -1 ) + " iterations.");
			System.out.println("       Unrouted connections: " + getUnroutedConnections().size());
			System.out.println("       Conflicting nodes: " + overUsedRnodes.size());
			for (Routable rnode : overUsedRnodes) {
				System.out.println(rnode.getNode());
				for (Entry<NetWrapper,Integer> e : rnode.getUsersConnectionCounts().entrySet())
				System.out.println("\t" + e.getKey().getNet() + " = " + e.getValue());
			}
		}
	}
	
	/**
	 * Gets unrouted connections.
	 * @return A list of unrouted connections.
	 */
	private List<Connection> getUnroutedConnections() {
		List<Connection> unroutedConnections = new ArrayList<>();
		for(Connection connection : sortedIndirectConnections) {
			if(!connection.getSink().isRouted()) {
				unroutedConnections.add(connection);
			}
		}
		return unroutedConnections;
	}
	
	/**
	 * Assigns a list of nodes to each connection and fix net routes if there are cycles and / or multi-driver nodes.
	 */
	private void postRouteProcess() {
		if(routeIteration <= config.getMaxIterations()){
			assignNodesToConnections();
			// fix routes with cycles and / or multi-driver nodes
			Set<NetWrapper> routes = fixRoutes();
			if(config.isTimingDriven()) updateTimingAfterFixingRoutes(routes);
		}
	}
	
	/**
	 * Checks if a connection should be routed.
	 * @param connection The connection in question.
	 * @return true, if the connection should be routed.
	 */
	private boolean shouldRoute(Connection connection) {
		if(routeIteration == 1) {
			// In the partial routing case, it is possible that the
			// connection exists but does not need routing
			return !connection.getSink().isRouted();
		}else {
			if(connection.getCriticality() > minRerouteCriticality) {
				return true;
			}
			if(connection.isCongested() || !connection.getSink().isRouted()) {
				if(config.isEnlargeBoundingBox()) {
					connection.enlargeBoundingBox(config.getExtensionXIncrement(), config.getExtensionYIncrement());
				}
				return true;
			}else {
				return false;
			}
		}
	}
	
	/**
	 * Computes and sets the minimum reroute criticality for re-routing critical connections.
	 */
	private void setRerouteCriticality() {
		// Limit the number of critical connections to be routed based on minRerouteCriticality and reroutePercentage
		minRerouteCriticality = config.getMinRerouteCriticality();
		criticalConnections.clear();
    	
		int maxNumberOfCriticalConnections = (int) (indirectConnections.size() * 0.01 * config.getReroutePercentage());
		for(Connection connection : indirectConnections) {
			if(connection.getCriticality() > minRerouteCriticality) {
				criticalConnections.add(connection);
			}
		}
    	
		if(criticalConnections.size() > maxNumberOfCriticalConnections) {
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
		maxDelayAndTimingVertex = timingManager.calculateArrivalRequireTimes();
		timingManager.calculateCriticality(sortedIndirectConnections,
				MAX_CRITICALITY, config.getCriticalityExponent(), maxDelayAndTimingVertex.getFirst());
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
	private void assignNodesToConnections() {
		for(Entry<Net,NetWrapper> e : nets.entrySet()) {
			NetWrapper netWrapper = e.getValue();
			for(Connection connection : netWrapper.getConnections()){
				List<Node> nodes = new ArrayList<>();
				SitePinInst sink = connection.getSink();

				List<Node> switchBoxToSink = RouterHelper.findPathBetweenNodes(connection.getSinkRnode().getNode(), sink.getConnectedNode());
				if(switchBoxToSink.size() >= 2) {
					for(int i = 0; i < switchBoxToSink.size() -1; i++) {
						nodes.add(switchBoxToSink.get(i));
					}
				}

				List<Routable> rnodes = connection.getRnodes();

				String sinkPinName = sink.getName();
				if (rnodes.size() >= 2 && Pattern.matches("[A-H](X|_I)", sinkPinName)) {
					Routable prevRnode = rnodes.get(1);
					Node prevNode = (prevRnode != null) ? prevRnode.getNode() : null;
					SitePin prevPin = (prevNode != null) ? prevNode.getSitePin() : null;
					if (prevPin != null && Pattern.matches("[A-H][1-6]", prevPin.getPinName())) {
						rnodes = rnodes.subList(1, rnodes.size());
						// TODO: Update site routing
						System.out.println(prevPin.getPinName() + " -> " + sinkPinName + " for " + connection.getNetWrapper().getNet());
					}
				}

				for(Routable rnode : rnodes){
					nodes.add(rnode.getNode());
				}

				List<Node> sourceToSwitchBox = RouterHelper.findPathBetweenNodes(connection.getSource().getConnectedNode(), connection.getSourceRnode().getNode());
				if(sourceToSwitchBox.size() >= 2) {
					for(int i = 1; i <= sourceToSwitchBox.size() - 1; i++) {
						nodes.add(sourceToSwitchBox.get(i));
					}
				}

				connection.setNodes(nodes);
			}
		}
	}
	
	/**
	 * Sorts indirect connections for routing.
	 */
	private void sortConnections(){
		sortedIndirectConnections = new ArrayList<>();
		sortedIndirectConnections.addAll(indirectConnections);
		sortedIndirectConnections.sort((connection1, connection2) -> {
			int comp = connection2.getNetWrapper().getConnections().size() - connection1.getNetWrapper().getConnections().size();
			if(comp == 0) {
				return Short.compare(connection1.getHpwl(), connection2.getHpwl());
			}else {
				return comp;
			}
		});
	}
	
	private void printIterationHeader(boolean timingDriven) {
		System.out.printf("------------------------------------------------------------------------------\n");
        if(timingDriven) {
        	System.out.printf("%9s  %12s  %8s   %11s  %10s   %5s  %9s\n",
            		"         ", "Generated", "  RRG",    "  Routed",   "Nodes With", "CPD", "Total Run");
            System.out.printf("%9s  %12s  %8s   %11s  %10s   %5s  %9s\n",
            		"Iteration", "RRG Nodes", "Time (s)", "Connections", "Overlaps", "(ps)", "Time (s)");
            System.out.printf("---------  ----------------------   -----------  ----------   -----  ---------\n");
        }else {
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
	 * total iteratin runtime, number of congested rnodes and the critical path delay achieved after the routing iteration.
	 * @param iterationRuntime
	 * @param numRnodes Generated routing resource graph nodes.
	 * @param rnodesCreationTime The runtime of generating routing resource graph nodes.
	 */
	private void printRoutingIterationStatisticsInfo(float iterationRuntime, long numRnodes, float rnodesCreationTime,
			boolean timingDriven){
		long overUsed = overUsedRnodes.size();
		if(timingDriven) {
			System.out.printf("%4d       %12d  %8.2f   %11d  %10d   %5d  %9.2f\n",
					routeIteration,
					numRnodes,
					rnodesCreationTime,
					connectionsRoutedIteration,
					overUsed,
					(short)(maxDelayAndTimingVertex == null? 0 : maxDelayAndTimingVertex.getFirst()),
					iterationRuntime * 1e-9);
		}else {
			System.out.printf("%4d       %12d  %8.2f   %11d  %10d   %5s  %9.2f\n",
					routeIteration,
					numRnodes,
					rnodesCreationTime,
					connectionsRoutedIteration,
					overUsed,
					"",
					iterationRuntime * 1e-9);
		}
		if(overUsed == 0) System.out.printf("------------------------------------------------------------------------------\n");
	}
	
	/**
	 * Updates the congestion cost factors.
	 */
	private void updateCostFactors(){
		updateCongestionCosts.start();
		if (routeIteration == 1) {
			presentCongestionFactor = config.getInitialPresentCongestionFactor();
		} else {
			presentCongestionFactor *= config.getPresentCongestionMultiplier();
		}
		updateCost();
		updateCongestionCosts.stop();
	}
	
	/**
	 * Updates present congestion cost and historical congestion cost of rnodes.
	 */
	private void updateCost() {
		overUsedRnodes.clear();
		Set<Net> overUsedPreservedNets = new HashSet<>();
		for(Entry<Node,Routable> e : routingGraph.getNodeEntries()){
			Routable rnode = e.getValue();
			int overuse = rnode.getOccupancy() - Routable.capacity;
			if(overuse == 0) {
				rnode.setPresentCongestionCost(1 + presentCongestionFactor);
			} else if (overuse > 0) {
				overUsedRnodes.add(rnode);
				rnode.setPresentCongestionCost(1 + (overuse + 1) * presentCongestionFactor);
				rnode.setHistoricalCongestionCost(rnode.getHistoricalCongestionCost() + overuse * historicalCongestionFactor);

				// Extract all overused preserved nets
				Net preservedNet = routingGraph.getPreservedNet(rnode.getNode());
				if (preservedNet != null) {
					overUsedPreservedNets.add(preservedNet);
				}
			}
		}

		// for (Net net : overUsedPreservedNets) {
		// 	NetWrapper netWrapper = createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), multiSLRDevice);
		//
		// 	Set<Routable> rnodes = new HashSet<>();
		// 	for (PIP pip : net.getPIPs()) {
		// 		Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
		// 		Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
		// 		Routable rstart = createAddRoutableNode(null, start, RoutableType.WIRE);
		// 		Routable rend = createAddRoutableNode(null, end, RoutableType.WIRE);
		//
		// 		rnodes.add(rstart);
		// 		rnodes.add(rend);
		//
		// 		// assert(rend.getPrev() == null);
		// 		rend.setPrev(rstart);
		// 	}
		//
		// 	for (Routable rnode : rnodes) {
		// 		routingGraph.computePreserved(rnode.getNode(),
		// 				($,preservedNet) -> {
		// 					if (preservedNet != null) {
		// 						assert(preservedNet == net);
		//
		// 						// If it happens to be a node with a placeholder (null) user
		// 						// then decrement the occupancy as it will be properly
		// 						// incremented by correctly finishRouteConnection()
		// 						int count = rnode.countConnectionsOfUser(null);
		// 						if (count > 0) {
		// 							rnode.decrementUser(null);
		// 							// System.out.println("DECREMENTING " + rnode.getNode());
		// 							// for (Entry<NetWrapper,Integer> e : rnode.getUsersConnectionCounts().entrySet()) {
		// 							// 	System.out.println("\t" + e.getKey().getNet() + " = " + e.getValue());
		// 							// }
		// 						}
		//
		// 						// Fall-through and unpreserve by returning null
		// 						// TODO: Maybe just unpreserve relevant connection(s)?
		// 					}
		// 					return null;
		// 				});
		// 		routingGraph.visit(rnode);
		// 	}
		//
		// 	for (Connection connection : netWrapper.getConnections()) {
		// 		finishRouteConnection(connection);
		// 		// if (connection.isCongested()) {
		// 		// 	System.out.println(net.getName() + " 's " + connection + " is congested");
		// 		// }
		// 	}
		//
		// 	routingGraph.resetExpansion();
		// }
	}
	
	/**
	 * Computes node usage of each type and the total wirelength of the design.
	 */
	private void computesNodeUsageAndTotalWirelength() {
		totalWL = 0;
		totalINTNodes = 0;
		nodeTypeUsage = new HashMap<>();
		nodeTypeLength = new HashMap<>();	

		Set<Node> netNodes = new HashSet<>();
		for(Entry<Net,NetWrapper> e : nets.entrySet()){
			NetWrapper netWrapper = e.getValue();
			for(Connection connection:netWrapper.getConnections()){
				netNodes.addAll(connection.getNodes());
			}		
			for(Node node:netNodes){
				if(node.getTile().getTileTypeEnum() != TileTypeEnum.INT) continue;
				totalINTNodes++;
				int wl = RouterHelper.getLengthOfNode(node);
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
	}
	
	/**
	 * Fixes routes of nets with routing path cycles and multi-driver nodes.
	 * @return A set of nets that have been fixed.
	 */
	private Set<NetWrapper> fixRoutes() {
		Set<NetWrapper> illegalRoutes = findIllegalRoutes();
		// fix routes with cycles and / or multi-driver nodes
		for(NetWrapper route:illegalRoutes){
			for(Connection connection : route.getConnections()){
				try{
					if(!connection.isDirect()) ripUp(connection);
				}catch (Exception e){
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
	private Set<NetWrapper> findIllegalRoutes(){
		Set<NetWrapper> illegalRoutes = new HashSet<>();
		for(Entry<Net,NetWrapper> e : nets.entrySet()) {
			NetWrapper netWrapper = e.getValue();
			buildDriverCountsOfRnodes(netWrapper);
			for(Connection connection : netWrapper.getConnections()) {
				if(shouldMergePath(connection)) {
					illegalRoutes.add(netWrapper);
					if(config.isTimingDriven()) addNodesDelays(netWrapper);
					break;
				}
			}
		}
		return illegalRoutes;
	}
	
	/**
	 * Builds the driversCounts map of each {@link Routable} instance that is used by a net.
	 * @param netWrapper A NetWrapper instance that represents a net.
	 */
	private void buildDriverCountsOfRnodes(NetWrapper netWrapper) {
		for(Connection connection : netWrapper.getConnections()) {
			Routable driver = null;
			for(int i = connection.getRnodes().size() - 1; i >= 0; i--){
				Routable rnode = connection.getRnodes().get(i);
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
	private void addNodesDelays(NetWrapper net){	
		for(Connection connection:net.getConnections()){
			for(Routable rnode : connection.getRnodes()){
				nodesDelays.put(rnode.getNode(), rnode.getDelay());
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
	private void ripUp(Connection connection){
		for(Routable rnode : connection.getRnodes()) {
			rnode.decrementUser(connection.getNetWrapper());
			rnode.updatePresentCongestionCost(presentCongestionFactor);
		}
	}
	
	/**
	 * Updates the users and present congestion cost of rnodes used by a routed connection.
	 * @param connection The routed connection.
	 */
	private void updateUsersAndPresentCongestionCost(Connection connection){
		for(Routable rnode : connection.getRnodes()) {
			rnode.incrementUser(connection.getNetWrapper());
			rnode.updatePresentCongestionCost(presentCongestionFactor);
		}
	}
	
	/**
	 * Sets a list of {@link PIP} instances of each {@link Net} instance and checks if there is any PIP overlaps.
	 */
	private void setPIPsOfNets(){
		for(Entry<Net,NetWrapper> e : nets.entrySet()){
			NetWrapper netWrapper = e.getValue();
			Set<PIP> oldPIPs = new HashSet<>(netWrapper.getNet().getPIPs());
			// Preserve the order of existing PIPs, e.g. the first PIP could be a logical driver
			Set<PIP> newPIPs = new /*Linked*/HashSet<>(/*oldPIPs*/);
			for(Connection connection:netWrapper.getConnections()){
				newPIPs.addAll(RouterHelper.getConnectionPIPs(connection));
			}

			// Skip if new and old PIPs are completely identical
			// (meaning net was unpreserved but never re-routed)
			if (oldPIPs.equals(newPIPs))
				continue;

			netWrapper.getNet().setPIPs(new ArrayList<>(newPIPs));

			if (!newPIPs.containsAll(oldPIPs)) {
				System.out.println("PIP delta for '" + netWrapper.getNet() + "':");
				oldPIPs.removeAll(newPIPs);
				for (PIP pip : oldPIPs) {
					System.out.println("\t- " + pip);
				}
			}
		}

		// FIXME: Temporarily disable for runtime
		// checkPIPsUsage();
	}
	
	/**
	 * Checks if there are PIP overlaps among routed nets.
	 */
	private void checkPIPsUsage(){
		Map<PIP, Set<Net>> pipsUsage = new HashMap<>();
		for(Net net : design.getNets()){
			for(PIP pip:net.getPIPs()){
				pipsUsage.computeIfAbsent(pip, (k) -> new HashSet<>()).add(net);
			}
		}
		int pipsError = 0;
		for(Entry<PIP, Set<Net>> pipNets : pipsUsage.entrySet()){
			if(pipNets.getValue().size() > 1){
				if(pipsError < 10) {
					System.out.println("pip " + pipNets.getKey() + " users = " + pipsUsage.get(pipNets.getKey()));
				}
				pipsError++;
			}
		}
		if(pipsError > 0)
			System.err.println("ERROR: PIPs overused error: " + pipsError);
		else
			System.out.println("\nINFO: No PIP overlaps\n");
	}

	/**
	 * Routes a connection.
	 * @param connection The connection to route.
	 */
	private void routeConnection(Connection connection){
		prepareRouteConnection(connection);
		
		float rnodeCostWeight = 1 - connection.getCriticality();
		float shareWeight = (float) (Math.pow(rnodeCostWeight, config.getShareExponent()));
		float rnodeWLWeight = rnodeCostWeight * oneMinusWlWeight;
		float estWlWeight = rnodeCostWeight * wlWeight;
		float dlyWeight = connection.getCriticality() * oneMinusTimingWeight;
		float estDlyWeight = connection.getCriticality() * timingWeight;

		boolean successRoute = false;
		while(!queue.isEmpty()){
			Routable rnode = queue.poll();
			if (rnode.isTarget()) {
				successRoute = true;
				break;
			}
			nodesPopped++;
			exploreAndExpand(rnode, connection, shareWeight, rnodeCostWeight,
					rnodeWLWeight, estWlWeight, dlyWeight, estDlyWeight);
		}
		
		if(successRoute) {
			finishRouteConnection(connection);
			connection.getSink().setRouted(true);
			if(config.isTimingDriven()) connection.updateRouteDelay();	
		}else {
			connection.getSink().setRouted(false);
			connection.getSinkRnode().setTarget(false);
			routingGraph.resetExpansion();
			System.out.printf("CRITICAL WARNING: Unroutable connection in iteration #%d\n", routeIteration);
			System.out.println("                 " + connection);
			handleUnroutableConnection(connection);
		}
	}
	
	/**
	 * Deals with a failed connection by possible output pin swapping and unrouting preserved nets if the router is in the soft preserve mode.
	 * @param connection The failed connection.
	 */
	private void handleUnroutableConnection(Connection connection) {
		if (routeIteration == 1) {		
			boolean hasAltOutput = swapOutputPin(connection);
			if(!hasAltOutput && config.isSoftPreserve()) {
				unrouteReservedNetsToReleaseResources(connection);
			}
		} else if(routeIteration == 2) {
			if (config.isSoftPreserve()) unrouteReservedNetsToReleaseResources(connection);
		}
	}
	
	/**
	 * Swaps the output pin of a connection, if its net has an alternative output pin.
	 * @param connection The connection in question.
	 * @return true, if the output pin has been swapped.
	 */
	private boolean swapOutputPin(Connection connection) {	
		NetWrapper netWrapper = connection.getNetWrapper();
		Net net = netWrapper.getNet();
		SitePinInst altSource = DesignTools.getLegalAlternativeOutputPin(net);
		if(altSource == null) {
			System.out.println("INFO: No alternative source to swap");	
			return false;
		}
		
		System.out.println("INFO: Swap source from " + net.getSource() + " to " + altSource + "\n");
		
		Node source = connection.getSource().getConnectedNode();
		if (!routingGraph.isPreserved(source)) {
			// Net.replaceSource() calls Net.removePin() (which in turn calls
			// Net.unroute()) -- only do this if the net is not a partial net
			// as determined by whether the source node is in the preserved set
			net.replaceSource(altSource);
			net.setAlternateSource(connection.getSource());
		} else {
			net.setAlternateSource(altSource);
		}

		DesignTools.routeAlternativeOutputSitePin(net, altSource);

		Node sourceINTNode = RouterHelper.projectOutputPinToINTNode(altSource);
		Routable sourceR = createAddRoutableNode(altSource, sourceINTNode, RoutableType.PINFEED_O);
		for(Connection otherConnectionOfNet : netWrapper.getConnections()) {
			otherConnectionOfNet.setSource(altSource);
			otherConnectionOfNet.setSourceRnode(sourceR);
		}
			
		return true;
	}
	
	/**
	 * Unroutes preserved nets to release routing resource to resolve congestion that blocks the routablity of a connection.
	 * NOTE: This is a primary method to enable the experimental soft preserve feature of partial routing. 
	 * It only unroutes nets that are using resources immediately downhill of the source and 
	 * immediately uphill of the sink of a connection.
	 * @param connection The connection in question.
	 * @return The number of unrouted nets.
	 */
	private int unrouteReservedNetsToReleaseResources(Connection connection) {
		// Find those reserved signals that are using uphill nodes of the target pin node
		Set<Net> toRouteNets = new HashSet<>();
		for(Node node : connection.getSinkRnode().getNode().getAllUphillNodes()) {
			Net toRoute = routingGraph.getPreservedNet(node);
			if(toRoute == null) continue;
			if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
			toRouteNets.add(toRoute);
		}

		// TODO: Only nets immediately downhill of the source node is unrouted,
		//       do we need to do more?
		// Find those preserved nets that are using downhill nodes of the source pin node
		for(Node node : connection.getSourceRnode().getNode().getAllDownhillNodes()) {
			Net toRoute = routingGraph.getPreservedNet(node);
			if(toRoute == null) continue;
			if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
			toRouteNets.add(toRoute);
		}
		
		if(!toRouteNets.isEmpty()) {
			System.out.println("INFO: Unroute " + toRouteNets.size() + " preserved nets");
		}
		
		for(Net n : toRouteNets) {
			System.out.println("\t" + n);

			Set<Routable> rnodes = new HashSet<>();
			NetWrapper netnew = nets.get(n);
			if (netnew != null) {
				// Net already exists

				for(Node toBuild : RouterHelper.getNodesOfNet(n)) {
					// Since net already exists, all the nodes it uses will already
					// have been created
					Routable rnode = routingGraph.getNode(toBuild);
					assert(rnode != null);

					rnodes.add(rnode);
				}
			} else {
				// Net needs to be created
				netnew = createsNetWrapperAndConnections(n, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), multiSLRDevice);

				// Collect all nodes used by this net
				for (PIP pip : n.getPIPs()) {
					Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
					Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
					Routable rstart = createAddRoutableNode(null, start, RoutableType.WIRE);
					Routable rend = createAddRoutableNode(null, end, RoutableType.WIRE);

					rnodes.add(rstart);
					rnodes.add(rend);

					// Also set the prev pointer according to the PIP
					rend.setPrev(rstart);
				}

				// Use the prev pointers to update the routing for each connection
				for (Connection netnewConnection : netnew.getConnections()) {
					finishRouteConnection(netnewConnection);
					assert(netnewConnection.getSink().isRouted());
				}
			}

			// Set<Node> pinNodes = new HashSet<>();
			// for (SitePinInst pin : n.getPins()) {
			// 	pinNodes.add(pin.getConnectedNode());
			// }

			for (Routable rnode : rnodes) {
				Node toBuild = rnode.getNode();
				/*if (!pinNodes.contains(toBuild))*/ {
					routingGraph.unpreserve(toBuild);
				}
				// Each rnode should be added a child to any of its parents
				// that already exist, unless it was already present
				uphill: for(Node uphill : toBuild.getAllUphillNodes()) {
					// Without this routethru check, there will be Invalid Programming for Site error shown in Vivado.
					// Do not use those nodes, because we do not know if the routethru is available or not
					if(routethruHelper.isRouteThru(uphill, toBuild)) continue;
					Routable parent = routingGraph.getNode(uphill);
					if (parent == null)
						continue;
					for (Routable child : parent.getChildren()) {
						if (child == rnode)
							continue uphill;
					}
					parent.addChild(rnode);
				}

				// Clear the prev pointer (as it is also used to track
				// whether a node has been visited during expansion)
				rnode.setPrev(null);
			}

			if(config.isTimingDriven()) timingManager.setTimingEdgesOfConnections(netnew.getConnections());
		}
		
		sortConnections();
		return toRouteNets.size();
	}
	
	/**
	 * Checks if a NODE_PINBOUNCE is suitable to be used for routing to a target.
	 * @param pinBounce The PINBOUNCE rnode in question.
	 * @param target The target rnode to reach.
	 * @return true, if the PINBOUNCE rnode is in the same column as the target and within one INT tile of the target.
	 */
	private boolean usablePINBounce(Routable pinBounce, Routable target){
		Tile bounce = pinBounce.getNode().getTile();
		Tile sink = target.getNode().getTile();
		return bounce.getTileXCoordinate() == sink.getTileXCoordinate() && Math.abs(bounce.getTileYCoordinate() - sink.getTileYCoordinate()) <= 1;
	}
	
	/**
	 * Completes the routing process of a connection.
	 * @param connection The routed target connection.
	 */
	protected void finishRouteConnection(Connection connection){
		saveRouting(connection);	
		connection.getSinkRnode().setTarget(false);
		routingGraph.resetExpansion();
		updateUsersAndPresentCongestionCost(connection);
	}
	
	/**
	 * Traces back for a connection from its sink rnode to its source, in order to build and store the routing path.
	 * @param connection: The connection that is being routed.
	 */
	private void saveRouting(Connection connection){
		Routable rnode = connection.getSinkRnode();
		while (rnode != null) {
			connection.addRnode(rnode);
			rnode = rnode.getPrev();
		}
	}
	
	/**
	 * Explores children (downhill rnodes) of a rnode for routing a connection and pushes the child into the queue,
	 * if it is the target or is an accessible routing resource.
	 * @param rnode The parent rnode popped out from the queue.
	 * @param connection The connection that is being routed.
	 * @param shareWeight The criticality-aware share weight for a new sharing factor.
	 * @param rnodeCostWeight The cost weight of the childRnode
	 * @param rnodeLengthWeight The wirelength weight of childRnode's exact wirelength.
	 * @param rnodeEstWlWeight The weight of estimated wirelength from childRnode to the connection's sink.
	 * @param rnodeDelayWeight The weight of childRnode's exact delay.
	 * @param rnodeEstDlyWeight The weight of estimated delay to the target.
	 */
	private void exploreAndExpand(Routable rnode, Connection connection, float shareWeight, float rnodeCostWeight,
			float rnodeLengthWeight, float rnodeEstWlWeight, float rnodeDelayWeight, float rnodeEstDlyWeight){
		boolean longParent = DelayEstimatorBase.isLong(rnode.getNode());
		for(Routable childRNode:rnode.getChildren()){
			if(childRNode.isVisited()) continue;
			if(childRNode.isTarget()){
				queue.clear();
			}else if(childRNode.getRoutableType() == RoutableType.WIRE) {
				if(childRNode.getDelay() > 10000) {
					// To filter out those nodes that are considered to be excluded with the masking resource approach,
					// such as U-turn shape nodes near the boundary and some node cross RCLK
					continue;
				}
				if(!isAccessible(childRNode, connection)) {
					continue;
				}
			}else if(childRNode.getRoutableType() == RoutableType.PINBOUNCE) {
				if(!isAccessible(childRNode, connection)) {
					continue;
				}
				if(!usablePINBounce(childRNode, connection.getSinkRnode())) {
					continue;
				}
			}else if(childRNode.getRoutableType() == RoutableType.PINFEED_I) {
				if(!connection.isCrossSLR()) {
					continue;
				}
			} else {
				continue;
			}

			evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, rnodeCostWeight,
					rnodeLengthWeight, rnodeEstWlWeight, rnodeDelayWeight, rnodeEstDlyWeight);
			if (childRNode.isTarget())
				break;
		}
	}

	/**
	 * Checks if a routing resource is accessible.
	 * @param child The routing resource in question.
	 * @param connection The connection to route.
	 * @return true, if no bounding box constraints, or if the routing resource is within the connection's bounding box when use the bounding box constraint.
	 */
	private boolean isAccessible(Routable child, Connection connection) {
		if(config.isUseBoundingBox()) {
			return child.isInConnectionBoundingBox(connection);
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
	private void evaluateCostAndPush(Routable rnode, boolean longParent, Routable childRnode, Connection connection, float sharingWeight, float rnodeCostWeight,
			float rnodeLengthWeight, float rnodeEstWlWeight, float rnodeDelayWeight, float rnodeEstDlyWeight) {
		int countSourceUses = childRnode.countConnectionsOfUser(connection.getNetWrapper());
		float sharingFactor = 1 + sharingWeight* countSourceUses;
		float newPartialPathCost = rnode.getUpstreamPathCost() + rnodeCostWeight * getRoutableCost(childRnode, connection, countSourceUses, sharingFactor)
								+ rnodeLengthWeight * childRnode.getLength() / sharingFactor
								+ rnodeDelayWeight * (childRnode.getDelay() + DelayEstimatorBase.getExtraDelay(childRnode.getNode(), longParent)) / 100f;
		int deltaX = Math.abs(childRnode.getEndTileXCoordinate() - connection.getSinkRnode().getEndTileXCoordinate());
		int deltaY = Math.abs(childRnode.getEndTileYCoordinate() - connection.getSinkRnode().getEndTileYCoordinate());
		int distanceToSink = deltaX + deltaY;
		float newTotalPathCost = (float) (newPartialPathCost + rnodeEstWlWeight * distanceToSink / sharingFactor
				+ rnodeEstDlyWeight * (deltaX * 0.32 + deltaY * 0.16));
		push(childRnode, rnode, newPartialPathCost, newTotalPathCost);
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
	private float getRoutableCost(Routable rnode, Connection connection, int countSameSourceUsers, float sharingFactor) {		
		boolean hasSameSourceUsers = countSameSourceUsers!= 0;	
		float presentCongestionCost;
		
		if(hasSameSourceUsers) {// the rnode is used by other connection(s) from the same net
			int overoccupancy = rnode.getOccupancy() - Routable.capacity;
			// make the congestion cost less for the current connection
			presentCongestionCost = 1 + overoccupancy * presentCongestionFactor;
		}else{
			presentCongestionCost = rnode.getPresentCongestionCost();
		}
		
		float biasCost = 0;
		if(!rnode.isTarget()) {
			NetWrapper net = connection.getNetWrapper();
			biasCost = rnode.getBaseCost() / net.getConnections().size() *
					(Math.abs(rnode.getEndTileXCoordinate() - net.getXCenter()) + Math.abs(rnode.getEndTileYCoordinate() - net.getYCenter())) / net.getDoubleHpwl();
		}
		
		return rnode.getBaseCost() * rnode.getHistoricalCongestionCost() * presentCongestionCost / sharingFactor + biasCost;
	}
	
	/**
	 * Sets the costs of a rnode and pushes it to the queue.
	 * @param childRnode A child rnode.
	 * @param rnode The parent rnode of the childRnode.
	 * @param newPartialPathCost The upstream path cost from childRnode to the source.
	 * @param newTotalPathCost Total path cost of childRnode.
	 */
	private void push(Routable childRnode, Routable rnode, float newPartialPathCost, float newTotalPathCost) {
		childRnode.setLowerBoundTotalPathCost(newTotalPathCost);
		childRnode.setUpstreamPathCost(newPartialPathCost);
		childRnode.setPrev(rnode);
		routingGraph.visit(childRnode);
		queue.add(childRnode);
	}
	
	/**
	 * Prepares for routing a connection.
	 * @param connection The target connection to be routed.
	 */
	private void prepareRouteConnection(Connection connection){
		// Rips up the connection
		ripUp(connection);
		
		connectionsRouted++;
		connectionsRoutedIteration++;
		// Clears previous route of the connection
		connection.resetRoute();
		queue.clear();	
		
		// Sets the sink rnode of the connection as the target
		connection.getSinkRnode().setTarget(true);
		
		// Adds the source rnode to the queue
		push(connection.getSourceRnode(), null, 0, 0);
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
	
	private int getNumSitePinOfStaticNets() {
		int totalSitePins = 0;
		for(List<SitePinInst> pins : staticNetAndRoutingTargets.values()) {
			totalSitePins += pins.size();
		}
		return totalSitePins;
	}
	
	private void printDesignNetsAndConfigurationInfo(boolean verbose) {
		printDesignInfo(verbose);
		if(config.isVerbose()) printConnectionSpanStatistics();
		printConfiguration(verbose);
	}
	
	private void printConfiguration(boolean verbose){
		if(verbose) System.out.println(config);
	}
	
	private void printTimingInfo(){
		if(sortedIndirectConnections.size() > 0) {
			timingManager.getCriticalPathInfo(maxDelayAndTimingVertex, false, routingGraph);
		}
	}
	
	public static void printNodeTypeUsageAndWirelength(boolean verbose, Map<IntentCode, Long> nodeTypeUsage, Map<IntentCode, Long> nodeTypeLength) {
		if(verbose) {
			System.out.println("Node Usage Per Type\n");
			System.out.printf(" %-15s  %14s  %12s\n", "Node Type", "Usage", "Length");
			for(IntentCode ic : nodeTypes) {
				long usage = nodeTypeUsage.getOrDefault(ic, 0L);
				long length = nodeTypeLength.getOrDefault(ic, 0L);
				System.out.printf(" %-15s  %14d  %12d\n", ic, usage, length);
			}
			System.out.println();
		}
	}
	
	private void printDesignInfo(boolean verbose){
		if(!verbose) return;
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
		for(Net clk : clkNets) {
			clkPins += clk.getSinkPins().size();
		}
		printFormattedString("  All site pins to be routed: ", (indirectConnections.size() + getNumSitePinOfStaticNets() + clkPins));	
		printFormattedString("    Connections to be routed: ", indirectConnections.size());
		printFormattedString("    Static net pins: ", getNumSitePinOfStaticNets());
		printFormattedString("    Clock pins: ", clkPins);
		printFormattedString("Nets not needing routing: ", numNotNeedingRoutingNets);
		if(numUnrecognizedNets != 0)
			printFormattedString("Nets unrecognized: ", numUnrecognizedNets);
		System.out.printf("------------------------------------------------------------------------------\n");
	}
	
	private static void printFormattedString(String s, int value) {
		System.out.print(MessageGenerator.formatString(s, value));
	}
	
	private static void printFormattedString(String s, long value) {
		System.out.print(MessageGenerator.formatString(s, value));
	}
	
	private void printRoutingStatistics(){
		MessageGenerator.printHeader("Statistics");
		computesNodeUsageAndTotalWirelength();
		printNodeTypeUsageAndWirelength(config.isVerbose(), nodeTypeUsage, nodeTypeLength);
		printFormattedString("Total wirelength:", totalWL);
		if(config.isVerbose()) {
			printFormattedString("Total INT tile nodes:", totalINTNodes);
			printFormattedString("Total rnodes created:", routingGraph.numNodes());
			printFormattedString("Average #children per node:", routingGraph.averageChildren());
			System.out.printf("------------------------------------------------------------------------------\n");	
			printFormattedString("Num iterations:", routeIteration);
			printFormattedString("Connections routed:", connectionsRouted);
			printFormattedString("Nodes pushed:", routingGraph.getTotalVisited());
			printFormattedString("Nodes popped:", nodesPopped);
			System.out.printf("------------------------------------------------------------------------------\n");
		}
		
		System.out.print(routerTimer);
		if(config.isTimingDriven()) {
			MessageGenerator.printHeader("Timing Report");
			printTimingInfo();
		}
		System.out.printf("==============================================================================\n");
		
	}
	
	/**
	 * Routes a design in the full timing-driven routing mode.
	 * @param design The {@link Design} instance to be routed.
	 */
	public static Design routeDesignFullTimingDriven(Design design) {
		return routeDesign(design, new RWRouteConfig(null));
	}
	
	/**
	 * Routes a design in the full non-timing-driven routing mode.
	 * @param design The {@link Design} instance to be routed.
	 */
	public static Design routeDesignFullNonTimingDriven(Design design) {
		return routeDesign(design, new RWRouteConfig(new String[] {"--nonTimingDriven", "--verbose"}));
	}
	
	/**
	 * Routes a design in the partial non-timing-driven routing mode.
	 * @param design The {@link Design} instance to be routed.
	 */
	public static Design routeDesignPartialNonTimingDriven(Design design) {
		return routeDesign(design, new RWRouteConfig(new String[] {"--partialRouting", "--fixBoundingBox", "--nonTimingDriven", "--verbose"}));
	}

	/**
	 * Routes a design in the partial timing-driven routing mode.
	 * @param design The {@link Design} instance to be routed.
	 */
	public static Design routeDesignPartialTimingDriven(Design design) {
		return routeDesign(design, new RWRouteConfig(new String[] {"--partialRouting", "--fixBoundingBox", "--verbose"}));
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
	
	/**
	 * Routes a design after pre-processing.
	 * @param design The {@link Design} instance to be routed.
	 * @param config A {@link RWRouteConfig} instance consisting of customizable parameters to use.
	 */
	private static Design routeDesign(Design design, RWRouteConfig config) {
		return routeDesign(design, config, () -> {
			if(config.isPartialRouting()) {
				return new PartialRouter(design, config);
			}
			DesignTools.createMissingSitePinInsts(design);
			return new RWRoute(design, config);
		});
	}
	
	/**
	 * Routes a design after pre-processing.
	 * @param design The {@link Design} instance to be routed.
	 * @param config A {@link RWRouteConfig} instance consisting of customizable parameters to use.
	 * @param newRouter Supplier lambda for constructing a new RWRoute object.
	 */
	protected static Design routeDesign(Design design, RWRouteConfig config, Supplier<RWRoute> newRouter) {
		// Pre-processing of the design regarding physical net names pins
		DesignTools.makePhysNetNamesConsistent(design);
		if(!config.isPartialRouting() || (!design.getVccNet().hasPIPs() && !design.getGndNet().hasPIPs())) {
			DesignTools.createPossiblePinsToStaticNets(design);
		}

		// Instantiates router object
		RWRoute router = newRouter.get();
		
		// Routes the design
		router.route();
		
		return router.getDesign();
	}
	
	/**
	 * The main interface of {@link RWRoute} that reads in a {@link Design} checkpoint, 
	 * and parses the arguments for the {@link RWRouteConfig} Object of the router.
	 * It also instantiates a {@link RWRoute} Object or a {@link PartialRouter}
	 * based on the partialRouting parameter and calls the route method to route the design.
	 * @param args An array of strings that are used to create a {@link RWRouteConfig} Object for the router.
	 */
	public static void main(String[] args) {
		if(args.length < 2){
			System.out.println("USAGE: <input.dcp> <output.dcp>");
			return;
		}
		// Reads the output directory and set the output design checkpoint file name
		String routedDCPfileName = args[1];
		
		CodePerfTracker t = new CodePerfTracker("RWRoute", true);
		
		// Reads in a design checkpoint and routes it		
		Design routed = RWRoute.routeDesignWithUserDefinedArguments(Design.readCheckpoint(args[0]), args);
		
		// Writes out the routed design checkpoint
		routed.writeCheckpoint(routedDCPfileName,t);
		System.out.println("\nINFO: Write routed design\n " + routedDCPfileName + "\n");	
	}
	
}
