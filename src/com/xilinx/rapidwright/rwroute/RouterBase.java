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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.rwroute.TimerTree.Timer;
import com.xilinx.rapidwright.timing.CERouteTiming;
import com.xilinx.rapidwright.timing.CLKSkewRouteDelay;
import com.xilinx.rapidwright.timing.DSPTimingData;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

public class RouterBase{
	/** The design to route */
	protected Design design;
	/** Variable to indicate if the devide of the design is multi-die */
	protected boolean multiSLRDevice;
	/** Created NetWrappers */
	protected List<NetWrapper> nets;
	/** A list of indirect connections that will go through iterative routing */
	protected List<Connection> indirectConnections;
	/** A list of direct connections that are easily routed through dedicated resources */
	protected List<Connection> directConnections;
	/** Sorted indirect connections */
	protected List<Connection> sortedIndirectConnections;
	/** A list of global clock nets */
	protected List<Net> clkNets;
	/** Static nets */
	protected Map<Net, List<SitePinInst>> staticNetAndRoutingTargets;
	/** Several integers to indicate the netlist info */
	protected int numRoutableNets;
	protected int numPreservedRoutableNets;
	protected int numPreservedClks;
	protected int numPreservedStaticNets;
	protected int numPreservedWire;
	protected int numWireNetsToRoute;
	protected int numConnectionsToRoute;
	protected int numNotNeedingRoutingNets;
	protected int numUnrecognizedNets;
	
	/** An instantiation of the router configuration consisting of a list of routing parameters */
	protected Configuration config;
	/** The present congestion cost factor */
	protected float presentCongesFac;
	/** The historical congestion cost factor */
	protected float historicalCongesFac;
	/** Wirelength-driven weighting factor */
	protected float wlWeight;
	/** 1 - wlWeight */
	protected float oneMinusWlWeight;
	/** Timing-driven weighting factor */
	protected float timingWeight;
	/** 1 - timingWeight */
	protected float oneMinusTimingWeight;
	
	/** A design-wise index to indicate total created routing resource graph nodes */
	protected int rnodeId;
	/** The current routing iteration */
	protected int routeIteration;
	/** A timer to store runtime of different phases */
	protected TimerTree routerTimer;
	protected Timer rnodesTimer;
	/** The start time of the current routing iteration */
	protected long iterationStartTime;
	/** The end time of the current routing iteration, including routing of connections and timing update */
	protected long iterationEndTime;
	/** An instantiation of RouteThruHelper to avoid route-thrus in the routing resource graph */
	protected RouteThruHelper routethruHelper;
	
	/** A set of indices of overused rondes */
	protected Set<Integer> overUsedRnodes;
	/** A map of preserved nodes to their nets */
	protected Map<Node, Net> preservedNodes;
	/** A map of nodes to created rnodes */
	protected Map<Node, Routable> rnodesCreated;
	/** Visted rnodes data during connection routing */
	protected Collection<RoutableData> rnodesVisited;
	/** The queue to store candinate nodes to route a connection */
	protected PriorityQueue<Pair<Routable, Float>> queue;
	
	/** Total wirelength of the routed design */
	protected int totalWL;
	/** Total used INT tile nodes */
	protected long totalINTNodes;
	/** A map from node types to the node usage of the types */
	protected Map<IntentCode, Long> nodeTypeUsage;
	/** A map from node types to the total wirelength of used nodes of the types */
	protected Map<IntentCode, Long> nodeTypeLength;
	/** Average number of downhill rnodes in the created routing resource graph */
	protected float avgChildrenOfRnodes;
	/** The total number of connections that are routed */
	protected int connectionsRouted;
	protected long nodesEvaluated;
	/** The total number of nodes pushed into the queue */
	protected long nodesPushed;
	/** The total number of connections routed in an iteration */
	protected int connectionsRoutedIteration;
	/** Total number of nodes popped from the queue */
	protected long nodesPopped;
	
	/** The maximum criticality constraint of connection */
	protected static float MAX_CRITICALITY = 0.99f;
	/** The minimum criticality of connections that should be re-routed, updated after each iteration */
	protected float minRerouteCriticality;
	/** The list of critical connections */
	protected List<Connection> criticalConnections;
	/** An instantiated delay estimator that is used to calculate delay of routing resources */
	protected DelayEstimatorBase estimator;
	/** TimingManager */
	protected TimingManager timingManager;
	/** A map from nodes to delay values, used for timing update after fixing routes */
	protected Map<Node, Float> nodesDelays;
	/** The maximum delay and associated TimingVertex */
	protected Pair<Float, TimingVertex> maxDelayAndTimingVertex;
	/** A map from TimingEdges to connections */
	protected Map<TimingEdge, Connection> timingEdgeConnectionMap;
	
	public RouterBase(Design design, Configuration config){
		this.design = design;
		this.multiSLRDevice = this.design.getDevice().getSLRs().length > 1;
		
		this.config = config;
		this.routerTimer = new TimerTree("Route design", this.config.isVerbose());
		this.rnodesTimer = TimerTree.createStandAloneTimer("rnodes creation");
		this.routerTimer.createAddTimer("Initialization", this.routerTimer.getRootTimer()).start();
		
		RoutableNode.setMaskNodesCrossRCLK(this.config.isMaskNodesCrossRCLK());

		if(this.config.isTimingDriven()) {
			this.setTimingData();			
			this.timingManager = new TimingManager(this.design, true, this.routerTimer, this.config);		
		    this.estimator = new DelayEstimatorBase(this.design.getDevice(), new InterconnectInfo(), this.config.isUseUTurnNodes(), 0);
			RoutableNode.setTimingDriven(true, estimator);
			this.nodesDelays = new HashMap<>();
		}
		
		this.minRerouteCriticality = config.getMinRerouteCriticality();
		this.criticalConnections = new ArrayList<>();
		
		this.queue = new PriorityQueue<>(new Comparator<Pair<Routable, Float>>() {
			@Override
			public int compare(Pair<Routable, Float> r1, Pair<Routable, Float> r2) {
				if(r1.getSecond().floatValue() < r2.getSecond().floatValue()) {
					return -1;
				}else {
					return 1;
				}
			}
		});
		this.rnodesVisited = new ArrayList<>();
		this.preservedNodes = new HashMap<>();
		this.rnodesCreated = new HashMap<>();		
		this.rnodeId = 0;
		
		this.routerTimer.createAddTimer("determine route targets", "Initialization").start();
		this.determineRoutingTargets();
		this.routerTimer.createAddTimer("determine route targets", "Initialization").stop();
		
		if(this.config.isTimingDriven()) {
			this.timingEdgeConnectionMap = new HashMap<>();
			//set timing edges of connections
			this.setTimingEdgesOfCons(this.indirectConnections);
		}
		
		this.sortedIndirectConnections = new ArrayList<>();		
		this.routethruHelper = new RouteThruHelper(this.design.getDevice());		
		this.connectionsRouted = 0;
		this.connectionsRoutedIteration = 0;
		this.nodesPushed = 0;
		this.nodesPopped = 0;		
		this.overUsedRnodes = new HashSet<>();
		
		this.routerTimer.createAddTimer("Initialization", this.routerTimer.getRootTimer()).stop();
	}
	
	/**
	 * Sets timing-driven routing related inputs
	 */
	protected void setTimingData() {
		DSPTimingData.setDSPTimingFolder(this.config.getDspTimingDataFolder());
		String clkSkew = this.config.getClkSkew();
		String ceRouteTiming = this.config.getCeRouteTiming();
		
		if(clkSkew != null) {
			CLKSkewRouteDelay.setClkTiming(clkSkew);
			CLKSkewRouteDelay clkTimingData = new CLKSkewRouteDelay(clkSkew);
			TimingGraph.setClkTiming(clkTimingData);
			GlobalSignalRouting.setRouteMap(clkTimingData.getRoute(), clkTimingData.getDelay());
		}
		
		if(ceRouteTiming != null) {
			CERouteTiming.setCERouteTiming(ceRouteTiming);
			CERouteTiming ceTiming = new CERouteTiming(ceRouteTiming);
			TimingGraph.setCERouteTiming(ceTiming);
			GlobalSignalRouting.setCERouteTiming(ceTiming);
		}
	}
	
	/**
	 * Classifies nets into different categories: clocks, static nets,
	 * regular signals (i.e. NetType.WIRE) and determine routing targets
	 */
	protected void determineRoutingTargets(){
		this.numWireNetsToRoute = 0;
		this.numConnectionsToRoute = 0;
		this.numPreservedRoutableNets = 0;
		this.numNotNeedingRoutingNets = 0;
		this.numUnrecognizedNets = 0;
		this.numRoutableNets = 0;
		
		this.nets = new ArrayList<>();
		this.indirectConnections = new ArrayList<>();
		this.directConnections = new ArrayList<>();
		this.clkNets = new ArrayList<>();
		this.staticNetAndRoutingTargets = new HashMap<>();
		
		for(Net net:this.design.getNets()){	
			if(net.isClockNet()){
				this.addGlobalClkRoutingTargets(net);
				
			}else if(net.isStaticNet()){
				this.addStaticNetRoutingTargets(net);
				
			}else if (net.getType().equals(NetType.WIRE)){
				if(RouterHelper.isRoutableNetWithSourceSinks(net)){
					this.addNetConnectionToRoutingTargets(net, this.multiSLRDevice);
				}else if(RouterHelper.isDriverLessOrLoadLessNet(net)){
					this.preserveNet(net);
					this.numNotNeedingRoutingNets++;
				}else if(RouterHelper.isInternallyRoutedNets(net)){
					this.preserveNet(net);
					this.numNotNeedingRoutingNets++;
				}else {
					this.numNotNeedingRoutingNets++;
				}
			}else {
				this.numUnrecognizedNets++;
				System.err.println("ERROR: Unknown net " + net.toString());
			}
		}
	}
	
	/**
	 * Sets a list of timing edges to a connection
	 * @param connection The target connection
	 * @param spiAndTimingEdges Mapping between each pair of SitePinInsts and a list of timing edges recognized by the timing graph
	 * @param hportSpiMap Mapping between a logic pin and a SitePinInst recognized by the timing graph builder
	 */
	protected void setConTimingEdges(Connection connection, Map<Pair<SitePinInst, SitePinInst>, List<TimingEdge>> spiAndTimingEdges, Map<EDIFHierPortInst, SitePinInst> hportSpiMap) {	
		List<EDIFHierPortInst> hportsFromSitePinInsts = DesignTools.getPortInstsFromSitePinInst(connection.getSink());
		if(hportsFromSitePinInsts.isEmpty()) {
			throw new RuntimeException("ERROR: Unable to find hierarchical logical cell pins from: " + connection.getSink());
		}
		EDIFHierPortInst hportSink = hportsFromSitePinInsts.get(0);
		SitePinInst mappedSink = hportSpiMap.get(hportSink);
		SitePinInst mappedSource = connection.getSource();	
		if(connection.getNet().getNet().getSource().getName().contains("COUT")) {
			EDIFHierPortInst hportSource = DesignTools.getPortInstsFromSitePinInst(connection.getSource()).get(0);
			mappedSource = hportSpiMap.get(hportSource);
		}
		
		List<TimingEdge> timingEdges = spiAndTimingEdges.get(new Pair<>(mappedSource, mappedSink));	
		if(timingEdges == null) {
			throw new RuntimeException("ERROR: No timing edges for connection from: " + connection.getSource() + " to " + connection.getSink());
		}
		connection.setTimingEdges(timingEdges);
		for(TimingEdge e : connection.getTimingEdges()){
			this.timingEdgeConnectionMap.put(e, connection); // for timing info of critical path delay
		}
	}
	
	/**
	 * Assigns timing edges to each connection in the list
	 * @param connections The connections that should be associated with timing edges
	 */
	protected void setTimingEdgesOfCons(List<Connection> connections) {
		Map<Pair<SitePinInst, SitePinInst>, List<TimingEdge>> spiAndTimingEdges = this.timingManager.getSpiAndTimingEdgesMap();
		Map<EDIFHierPortInst, SitePinInst> hportSpiMap = this.timingManager.getTimingGraph().getEDIFHportSpiMap();
		
		for(Connection c : connections) {
			if(c.isDirect()) continue;
			this.setConTimingEdges(c, spiAndTimingEdges, hportSpiMap);
		}
	}
	
	/**
	 * Adds the clock net to the list of clock routing targets, if the clock has source and sink SitePinInsts
	 * @param clk The clock net in question
	 */
	protected void addGlobalClkRoutingTargets(Net clk) {
		if(RouterHelper.isRoutableNetWithSourceSinks(clk)) {
			clk.unroute();
			this.clkNets.add(clk);
			this.numRoutableNets++;
		}else {
			this.numNotNeedingRoutingNets++;
			System.err.println("ERROR: Incomplete clock net " + clk);
		}		
	}
	
	/**
	 * Routes clock nets by default or in a different way when corresponding timing info supplied
	 */
	protected void routeGlobalClkNets() {
 		if(this.clkNets.size() > 0) System.out.println("INFO: Route clock nets");
 		for(Net clk : this.clkNets) {
 			System.out.println(clk.getName());
 			if(GlobalSignalRouting.crRoutes != null || GlobalSignalRouting.dstINTtileRoutes != null) {
 				if(GlobalSignalRouting.dstINTtileRoutes == null) {
 					System.out.println("INFOR: Route with clock skew data reference");
 					GlobalSignalRouting.clkRouteWithClkSkewRouteDelays(clk, this.design.getDevice());
 				}else {
 					System.out.println("INFO: Route with clock route and timing data");
 					GlobalSignalRouting.clkEnableRoute(clk, this.design.getDevice());
 				}
 			}else {
 				if(this.config.isSymmetricClkRouting()) {
 					System.out.println("INFO: Route with symmetric non-timing-driven clock router");
 	 				GlobalSignalRouting.symmetricClkRouting(clk, this.design.getDevice());
 				}else {
 					System.out.println("INFO: Route with default non-timing-driven clock router");
 	 				GlobalSignalRouting.defaultClkRoute(clk, this.design.getDevice());
 				}
 			}
			this.preserveNet(clk);
 		}
	}
	
	/**
	 * Adds and initialize a regular signal net to the list of routing targets
	 * @param net The net to be added for routing
	 * @param multiSLR A flag to indicate if the device has multiple SLRs, for the sake of avoiding unnecessary check for single-SLR devices
	 */
	protected void addNetConnectionToRoutingTargets(Net net, boolean multiSLR) {
		net.unroute();
		this.numWireNetsToRoute++;
		this.numRoutableNets++;
		this.initializeNetAndCons(net, this.config.getBoundingBoxExtension(), multiSLR);
	}
	
	/**
	 * Adds a static net to the static net routing target list
	 * @param staticNet The static net in question, i.e. VCC or GND
	 */
	protected void addStaticNetRoutingTargets(Net staticNet){
		List<SitePinInst> sinks = new ArrayList<>();
		for(SitePinInst sink : staticNet.getPins()){
			if(sink.isOutPin()) continue;
			sinks.add(sink);
		}
		
		if(sinks.size() > 0 ) {
			for(SitePinInst sink : sinks) {
				this.addReservedNode(sink.getConnectedNode(), staticNet);
			}
			this.staticNetAndRoutingTargets.put(staticNet, sinks);
			this.numRoutableNets++;	
		}else {
			this.preserveNet(staticNet);
			this.numNotNeedingRoutingNets++;	
		}
	}
	
	/**
	 * Routes static nets with preserved resources list supplied to avoid conflicting nodes
	 * @param preservedConnectedNodesToPins The list of preserved nodes for other nets
	 */
	protected void routeStaticNets(List<Node> preservedConnectedNodesToPins){
		GlobalSignalRouting.setDesignRoutethruHelper(this.design, this.routethruHelper);
		
		Set<Node> unavailableNodes = getAllUsedNodesOfRoutedConnections();
		for(Net n : this.staticNetAndRoutingTargets.keySet()){ // when static nets should be routed after connections
			for(SitePinInst sink : this.staticNetAndRoutingTargets.get(n)) {
				this.preservedNodes.remove(sink.getConnectedNode());
			}
		}
		
		RouterHelper.invertPossibleGndPinsToVccPins(this.design, this.design.getGndNet());
		
		unavailableNodes.addAll(this.preservedNodes.keySet());
		unavailableNodes.addAll(getAllUsedNodesOfRoutedConnections());//if connections are routed first, used resources should be preserved
		if(preservedConnectedNodesToPins != null) unavailableNodes.addAll(preservedConnectedNodesToPins);
		unavailableNodes.addAll(this.rnodesCreated.keySet()); //when route static nets first, created rnodes are for connections to be routed
		
		for(Net n : this.staticNetAndRoutingTargets.keySet()){
			System.out.println("INFO: Route " + n.getSinkPins().size() + " pins of " + n);
			Map<SitePinInst, List<Node>> spiRoutedNodes = GlobalSignalRouting.routeStaticNet(n, unavailableNodes);
			for(SitePinInst spi : spiRoutedNodes.keySet()) {
				Set<Node> sinkPathNodes = new HashSet<>();
				sinkPathNodes.addAll(spiRoutedNodes.get(spi));
				this.addPreservedNode(sinkPathNodes, n);
				unavailableNodes.addAll(sinkPathNodes);
			}
		}
	}
	
	/**
	 * Gets a set of nodes used by all the connections
	 * @return A set of used nodes
	 */
	protected Set<Node> getAllUsedNodesOfRoutedConnections(){
		Set<Node> nodes = new HashSet<>();
		for(Connection c:this.sortedIndirectConnections){
			if(c.getNodes() != null) nodes.addAll(c.getNodes());
		}	
		return nodes;
	}
	
	/**
	 * Preserves a net by preserving all nodes use by the net
	 * @param net The net to be preserved
	 */
	protected void preserveNet(Net net){
		// reservePipsOfNet(n) means if the net has any PIPs, resources used by it will be preserved
		// and the net will not be considered as to-be-routed.
		// TODO detect partially routed nets
		this.addPreservedNode(RouterHelper.getUsedNodesOfNet(net), net);
	}
	
	/**
	 * Initializes a net by creating a unique NetWrapper and setting a list of connections to the NetWrapper
	 * @param net The net to be initialized
	 * @param boundingBoxExtension The bounding box extension factor for restricting accessible routing resource of a connection
	 * @param multiSLR The flag to indicate if the device has multiple SLRs
	 * @return A NetWrapper
	 */
	protected NetWrapper initializeNetAndCons(Net net, short boundingBoxExtension, boolean multiSLR) {
		NetWrapper netWrapper = new NetWrapper(this.numWireNetsToRoute, boundingBoxExtension, net);
		this.nets.add(netWrapper);
		
		SitePinInst source = net.getSource();
		int indirect = 0;
		Node sourceINTNode = null;
		boolean sourceINTNodeSet = false;
		
		for(SitePinInst sink:net.getSinkPins()){
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = net.getAlternateSource();
				if(source == null){
					String errMsg = "Null alternate source is for COUT-CIN connection: " + net.toStringFull();
					 throw new IllegalArgumentException(errMsg);
				}
			}
			
			Connection c = new Connection(this.numConnectionsToRoute, source, sink);
			c.setNet(netWrapper);
			netWrapper.addCons(c);
			this.numConnectionsToRoute++;
			//direct connection
			List<Node> nodes = RouterHelper.projectInputPinToINTNode(sink);
			if(nodes.isEmpty()) {
				this.directConnections.add(c);
				c.setDirect(true);
			}else {
				Node sinkINTNode = nodes.get(0);
				this.indirectConnections.add(c);
				Routable sinkR = this.createAddRoutableNode(this.rnodeId, c.getSink(), sinkINTNode, RoutableType.PINFEED_I);
				c.setSinkRnode(sinkR);
				if(!sourceINTNodeSet) {
					sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
					if(sourceINTNode == null) {
						throw new RuntimeException("ERROR: Null projected INT node for the source of net " + net.toStringFull());
					}
					sourceINTNodeSet = true;
				}
				Routable sourceR = this.createAddRoutableNode(this.rnodeId, c.getSource(), sourceINTNode, RoutableType.PINFEED_O);
				c.setSourceRnode(sourceR);
				c.setDirect(false);
				indirect++;
				c.computeHpwl();
			}
		}
		
		if(indirect > 0) {
			netWrapper.setBoundaryXYs(boundingBoxExtension);
			if(this.config.isUseBoundingBox()) {
				for(Connection c : netWrapper.getConnection()) {
					if(c.isDirect()) continue;
					c.computeConnectionBoundingBox(boundingBoxExtension, multiSLR);
				}
			}
		}
		sourceINTNodeSet = false;
		return netWrapper;
	}
	
	/**
	 * Adds preserved nodes
	 * @param nodes A set of nodes to be preserved
	 * @param netToPreserve The net that uses those nodes
	 */
	protected void addPreservedNode(Set<Node> nodes, Net netToPreserve) {
		for(Node node : nodes) {
			this.addReservedNode(node, netToPreserve);
		}
	}
	
	protected void addReservedNode(Node node, Net netToPreserve) {
		Net reserved = this.preservedNodes.get(node);
		if(reserved == null) {
			this.preservedNodes.put(node, netToPreserve);
		}else if(!reserved.getName().equals(netToPreserve.getName())){
			EDIFNet reservedLogical = reserved.getLogicalNet();
			EDIFNet toReserveLogical = netToPreserve.getLogicalNet();
			if(reservedLogical != null && toReserveLogical != null) {
				if(!toReserveLogical.equals(reservedLogical))
					System.out.println("WARNING: Conflicting node " + node + ":"); 
					System.out.println("         " + netToPreserve.getName() + " \n         " + reserved.getName());
			}else {
				System.out.println("WARNING: Conflicting node " + node + ":"); 
				System.out.println("         " + netToPreserve.getName() + " \n         " + reserved.getName());;
			}
		}	
	}
	
	/**
	 * Creates a RoutableNode based on a node and avoids duplicates, used for the source and sink RoutableNodes for connections
	 * @param rnodeGlobalIndex The design-wise index of created RoutableNodes
	 * @param sitePinInst The source or sink SitePinInst
	 * @param node The node associated to the sitePinInst
	 * @param type The type of the RoutableNode
	 * @return The created RoutableNode
	 */
	protected Routable createAddRoutableNode(int rnodeGlobalIndex, SitePinInst sitePinInst, Node node, RoutableType type){
		Routable rnode;
		if(!this.rnodesCreated.containsKey(node)){
			// this is for initializing sources and sinks of those to-be-routed nets's connections
			rnode = new RoutableNode(rnodeGlobalIndex, node, type);
			if(this.config.isTimingDriven()){
				rnode.setDelay(RouterHelper.computeNodeDelay(this.estimator, node));
			}
			this.rnodesCreated.put(rnode.getNode(), rnode);
			this.rnodeId++;
		}else{
			// this is for checking preserved routing resource conflicts among routed nets
			rnode = this.rnodesCreated.get(node);
			if(rnode.getRoutableType() == type && type == RoutableType.PINFEED_I) {
				System.out.println("WARNING: Conflicting node: " + node + ", connected to sink " + sitePinInst);
			}
		}
		return rnode;
	}
	
	/**
	 * Initializes parameters for the router
	 */
	protected void initializeRouting(){
		this.rnodesVisited.clear();
    	this.queue.clear(); 	
		this.routeIteration = 1;
    	this.historicalCongesFac = this.config.getHistoricalCongesFac();
    	this.presentCongesFac = this.config.getInitialPresentCongesFac();
    	this.timingWeight = this.config.getTimingWeight();
    	this.wlWeight = this.config.getWirelengthWeight();
    	this.oneMinusTimingWeight = 1 - this.timingWeight;
    	this.oneMinusWlWeight = 1 - this.wlWeight;
    	this.printIterationHeader();
	}
	
	/**
	 * Rputes the design in a few routing phases
	 */
	public void doRouting(){
		this.routerTimer.createAddTimer("Routing", this.routerTimer.getRootTimer()).start();
		
		this.routerTimer.createAddTimer("route clock", "Routing").start();
		this.routeGlobalClkNets();
		this.routerTimer.createAddTimer("route clock", "Routing").stop();
		
		// Static nets (VCC and GND) before signals for now.
		this.routerTimer.createAddTimer("route static nets", "Routing").start();
		this.routeStaticNets(null);// null could be replaced by a list of reserved nodes if static nets were routed after signals
		// Connection-based router for indirectly connected pairs of output pin and input pin
		this.routerTimer.createAddTimer("route static nets", "Routing").stop();
		
		this.routerTimer.createAddTimer("route wire nets", "Routing").start();
		this.preRoutingEstimation();
		this.routeConnections();
		// NOTE: route direct connections after indirect connection.
		// The reason is that there maybe additional direct connections in the soft preserve mode for partial routing
		// and those direct connections should be included to be routed
		this.routeDirectCons();
		this.routerTimer.createAddTimer("route wire nets", "Routing").stop();
		
		this.routerTimer.createAddTimer("finalize routes", "Routing").start();
		// Assigns a list of nodes to each direct and indirect connection that has been routed and fix illegal routes if any
		this.postRouteProcess();
		// Assigns net PIPs based on lists of connections
		this.pipsAssignment();
		this.routerTimer.createAddTimer("finalize routes", "Routing").stop();
		
		this.routerTimer.createAddTimer("Routing", this.routerTimer.getRootTimer()).stop();
	}
	
	/**
	 * Calculates initial criticality for each connection based on a simple estimation
	 */
	protected void preRoutingEstimation() {
		if(config.isTimingDriven()) {
			this.estimateDelayOfConnections();
			this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireTimes();
			this.timingManager.calculateCriticality(this.indirectConnections, MAX_CRITICALITY, this.config.getCriticalityExponent(), this.maxDelayAndTimingVertex.getFirst().floatValue());
			System.out.println(String.format("INFO: Estimated pre-routing max delay: %4d", (short) maxDelayAndTimingVertex.getFirst().floatValue()));
		}
	}
	
	/**
	 * A simple approach to estimate delay of each connection and update route delay of TimingEdges
	 */
	protected void estimateDelayOfConnections() {	
		for(Connection con : this.indirectConnections) {
			RoutableNode source = (RoutableNode) con.getSourceRnode();
			
			this.setChildrenOfRnode(source);			
			if(source.getChildren().isEmpty()) {
				// output pin blocked
				this.swapOutputPin(con);
				source = (RoutableNode) con.getSourceRnode();
				this.setChildrenOfRnode(source);
			}
						
			short estConDelay = (short) 10000;
			for(Routable child : source.getChildren()) {				
				short tmpConDelay = 113;
				tmpConDelay += child.getDelay();
				if(tmpConDelay < estConDelay) {
					estConDelay = tmpConDelay;
				}
				
			}
			estConDelay += source.getDelay();
			con.setTimingEdgesDelay(estConDelay);
		}
	}
	
	/**
	 * Routes direct connections
	 */
	protected void routeDirectCons() {
		System.out.println("\nINFO: Route " + this.directConnections.size() + " direct connections ");
		for(Connection c : this.directConnections) {
			boolean success = RouterHelper.routeDirectCon(c);
			// no need to update route delay of direct connection, because it would not be changed
			if(!success) System.err.println("ERROR: Failed to route direct connection " + c);
		}
	}
	
	/**
	 * Routes indirect connections iteratively
	 */
	public void routeConnections(){
		this.sortCons();
		this.initializeRouting();
		long overused =0;
		// do iterative routing
		while(this.routeIteration < this.config.getMaxIterations()){
			this.iterationStartTime = System.nanoTime();			
			this.connectionsRoutedIteration = 0;
			if(this.config.isTimingDriven()) {
				this.setRerouteCriticality(this.sortedIndirectConnections);
			}
			for(Connection con : this.sortedIndirectConnections) {
				if(this.shouldRoute(con)){
					this.routeCon(con);
				}
			}
			if(this.config.isTimingDriven()) {
				this.updateTiming();
			}
			
			this.updateCostFactors();
			this.iterationEndTime = System.nanoTime();
			
			overused = this.printStatisticsGetsOverusedRnodes();
			if(overused == 0) {
				Set<Connection> unroutableCons = this.getUnroutedCons();
				if(unroutableCons.isEmpty()) {
					break;
				}else {
					if(this.routeIteration == this.config.getMaxIterations() - 1) {
						System.err.println("ERROR: Unroutable connections: " + unroutableCons.size());
					}
				}
			}
			this.routeIteration++;
		}
		if(this.routeIteration == this.config.getMaxIterations()) {
			System.out.println("\nERROR: Routing terminated after " + (this.routeIteration -1 ) + " iterations.");
			System.out.println("       Unrouted connections: " + this.getUnroutedCons().size());
			System.out.println("       Conflicting nodes: " + overused);
		}
	}
	
	/**
	 * Gets unrouted connections
	 * @return A set of unrouted connections
	 */
	protected Set<Connection> getUnroutedCons() {
		Set<Connection> unroutableCons = new HashSet<>();
		for(Connection c : this.sortedIndirectConnections) {
			if(!c.getSink().isRouted()) {
				unroutableCons.add(c);
			}
		}
		return unroutableCons;
	}
	
	/**
	 * Assigns a list of nodes to each connection and fix net routes if there are cycles and / or multi-driver nodes
	 */
	protected void postRouteProcess() {
		if(this.routeIteration <= this.config.getMaxIterations()){
			this.assignNodesToCons();
			// fix routes with cycles and / or multi-driver nodes
			Set<NetWrapper> routes = this.fixRoutes();
			if(this.config.isTimingDriven()) this.updateTimingAfterFixingRoutes(routes);
		}
	}
	
	/**
	 * Checks if a connection should be routed
	 * @param connection The connection in question
	 * @return true, if the connection should be routed
	 */
	protected boolean shouldRoute(Connection con) {		
		if(this.routeIteration == 1) {
			return true;
		}else {
			if(con.getCriticality() > this.minRerouteCriticality) {
				return true;
			}
			if(con.congested() || !con.getSink().isRouted()) {
				if(this.config.isEnlargeBoundingBox()) {
					con.enlargeBoundingBox(this.config.getHorizontalINTTiles(), this.config.getVerticalINTTiles());
				}	
				return true;
			}else {
				return false;
			}
		}
	}
	
	/**
	 * Prints info of congested connection
	 * @param connection The target connection
	 */
	protected void printCongestedConnection(Connection connection) {
		System.out.println(connection);
		System.out.println("AltSource: " + DesignTools.getLegalAlternativeOutputPin(connection.getNet().getNet()));
		System.out.println();
	}
	
	/**
	 * Computes and sets the minimum reroute criticality for re-routing critical connections
	 * @param connections The list of connections
	 */
	protected void setRerouteCriticality(List<Connection> connections) {
		// Limit number of critical connections to be routed
    	this.minRerouteCriticality = this.config.getMinRerouteCriticality();
    	this.criticalConnections.clear();
    	
    	int maxNumberOfCriticalConnections = (int) (this.indirectConnections.size() * 0.01 * this.config.getReroutePercentage());
    	for(Connection con : this.indirectConnections) {
    		if(con.getCriticality() > this.minRerouteCriticality) {
    			this.criticalConnections.add(con);
    		}
    	}
    	
    	if(this.criticalConnections.size() > maxNumberOfCriticalConnections) {
    		this.criticalConnections.sort(new Comparator<Connection>() {
			@Override
			public int compare(Connection c1, Connection c2) {
				return c1.getCriticality() < c1.getCriticality()? 1 : -1;
			}});
    		this.minRerouteCriticality = this.criticalConnections.get(maxNumberOfCriticalConnections).getCriticality();
    	}	
	}
	
	/**
	 * Updates timing through static timing analysis and calculates connections' criticalities
	 */
	protected void updateTiming() {
		this.timingWeight = (float) Math.min(this.timingWeight * this.config.getTimingMultiplier(), 1f);
		this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireTimes();
		this.timingManager.calculateCriticality(this.sortedIndirectConnections,
				MAX_CRITICALITY, this.config.getCriticalityExponent(), maxDelayAndTimingVertex.getFirst().floatValue());
	}
	
	/**
	 * Updates timing after fixing routes of nets
	 * @param netsWithIllegalRoutes A set of nets whose routes have been fixed
	 */
	protected void updateTimingAfterFixingRoutes(Set<NetWrapper> netsWithIllegalRoutes) {
		this.timingManager.updateIllegalNetsDelays(netsWithIllegalRoutes, this.nodesDelays, this.indirectConnections);
		this.timingManager.patchUpDelayOfConnections(this.sortedIndirectConnections);
		this.updateTiming();
	}
	
	/**
	 * Assigns a list nodes to each connection to complete the route path of it
	 */
	protected void assignNodesToCons() {
		for(Connection con:this.sortedIndirectConnections){
			con.newNodes();
			List<Node> switchBoxToSink = RouterHelper.findPathBetweenTwoNodes(con.getSinkRnode().getNode(), con.getSink().getConnectedNode());
			if(switchBoxToSink.size() >= 2) {			
				for(int i = 0; i < switchBoxToSink.size() -1; i++) {
					con.addNode(switchBoxToSink.get(i));
				}
			}
			
			for(Routable rn:con.getRnodes()){
				con.addNode(rn.getNode());
			}
			
			List<Node> sourceToSwitchBox = RouterHelper.findPathBetweenTwoNodes(con.getSource().getConnectedNode(), con.getSourceRnode().getNode());
			if(sourceToSwitchBox.size() >= 2) {
				for(int i = 1; i <= sourceToSwitchBox.size() - 1; i++) {
					con.addNode(sourceToSwitchBox.get(i));
				}
			}
		}
	}
	
	/**
	 * Sorts indirect connections for routing
	 */
	protected void sortCons(){
		this.sortedIndirectConnections = new ArrayList<>();
		this.sortedIndirectConnections.addAll(this.indirectConnections);
		this.sortedIndirectConnections.sort(new Comparator<Connection>() {
			@Override
			public int compare(Connection c1, Connection c2) {
				int comp = c2.getNet().getConnection().size() - c1.getNet().getConnection().size();
				if(comp == 0) {
					return c1.getHpwl() > c2.getHpwl()? 1:c1.getHpwl() < c2.getHpwl() ? -1 :0;
				}else {
					return comp;
				}
			}
		});
	}
	
	/**
	 * Prints statistics of each routing iteration and gets the number of overused rnodes
	 * @return The number of overused rnodes
	 */
	protected long printStatisticsGetsOverusedRnodes() {
		return this.statisticsInfo(this.iterationStartTime, this.iterationEndTime, this.rnodeId, this.rnodesTimer.getTime());
	}
	
	/**
	 * Prints statistics output in each routing iteration and returns the number of overused rnodes
	 * @param iterationStartTime The start time of this router iteration
	 * @param iterationEndTime The end time of this router iteration
	 * @param globalRnodeId The global index of created rnodes
	 * @param rnodesTacc The accumulative runtime of rnodes creation from the first iteration
	 * @return The number of overused rnodes
	 */
	protected long statisticsInfo(long iterationStartTime, long iterationEndTime, int globalRnodeId, long rnodesTacc){		
		long overUsed = this.overUsedRnodes.size();
		System.out.printf("%4d  %10d  %8.2f  %12d  %15.2f  %8d  %9d\n",
				this.routeIteration,
				this.connectionsRoutedIteration,
				(iterationEndTime - iterationStartTime)*1e-9,
				globalRnodeId,
				rnodesTacc*1e-9,
				overUsed,
				(short)(this.maxDelayAndTimingVertex == null? 0 : this.maxDelayAndTimingVertex.getFirst()));
		return overUsed;
	}
	
	/**
	 * Updates the congestion cost factors
	 */
	protected void updateCostFactors(){
		if (this.routeIteration == 1) {
			this.presentCongesFac = this.config.getInitialPresentCongesFac();
		} else {
			this.presentCongesFac *= this.config.getPresentCongesMultiplier();
		}
		this.updateCost(this.presentCongesFac, this.historicalCongesFac);
	}
	
	/**
	 * Updates present congestion cost and historical congestion cost of rnodes
	 * @param presentCongesFac Present congestion cost factor
	 * @param historicalCongesFac Historical congestion cost factor
	 */
	protected void updateCost(float presentCongesFac, float historicalCongesFac) {
		this.overUsedRnodes.clear();
		for(Routable rnode:this.rnodesCreated.values()){
			int overuse =rnode.getOccupancy() - Routable.capacity;
			// Present congestion penalty
			if(overuse == 0) {
				rnode.setPresentCongesCost(1 + presentCongesFac);
			} else if (overuse > 0) {
				this.overUsedRnodes.add(rnode.hashCode());
				rnode.setPresentCongesCost(1 + (overuse + 1) * presentCongesFac);
				rnode.setHistoricalCongesCost(rnode.getHistoricalCongesCost() + overuse * historicalCongesFac);
			}
		}
	}
	
	/**
	 * Computes node usage of each type and the total wirelength of the design
	 */
	protected void computesNodeUsageAndTotalWirelength() {
		this.avgChildrenOfRnodes = 0;
		float sumChildren = 0;
		float sumRNodes = 0;
		for(Routable rn:this.rnodesCreated.values()){	
			if(rn.isChildrenSet()){
				sumChildren += rn.getChildren().size();
				sumRNodes++;
			}
		}
		this.avgChildrenOfRnodes = sumChildren / sumRNodes;
		
		this.totalWL = 0;
		this.totalINTNodes = 0;
		this.nodeTypeUsage = new HashMap<>();
		this.nodeTypeLength = new HashMap<>();		
		
		Set<Node> netNodes = new HashSet<>();
		for(NetWrapper net:this.nets){	
			for(Connection c:net.getConnection()){
				netNodes.addAll(c.getNodes());
			}		
			for(Node node:netNodes){
				if(node.getTile().getTileTypeEnum() != TileTypeEnum.INT) continue;
				this.totalINTNodes++;
				int wl = RouterHelper.getLengthOfNode(node);
				this.totalWL += wl;
				
				RouterHelper.putNodeTypeLength(node, wl, this.nodeTypeUsage, this.nodeTypeLength);
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
	 * Fixes routes of nets with routing path cycles and multi-driver nodes
	 * @return A set of nets that have been fixed
	 */
	protected Set<NetWrapper> fixRoutes() {
		Set<NetWrapper> illegalRoutes = this.findIllegalRoutes();
		// fix routes with multi-fanin nodes
		for(NetWrapper route:illegalRoutes){
			for(Connection c:route.getConnection()){
				try{
					if(!c.isDirect()) this.ripUp(c);
				}catch (Exception e){
					e.printStackTrace();
				}
			}
			
			RouteFixer graphHelper = new RouteFixer(route, this.rnodesCreated);
			graphHelper.finalizeRoutesOfConnections();
		}
		return illegalRoutes;
	}
	
	/**
	 * Finds nets that have illegal routes by checking its connections' routes
	 * @return Nets that should be fixed
	 */
	protected Set<NetWrapper> findIllegalRoutes(){
		Set<NetWrapper> illegalRoutes = new HashSet<>();
		for(NetWrapper net : this.nets) {
			for(Connection con : net.getConnection()) {
				if(this.shouldMergePath(con)) {
					illegalRoutes.add(net);
					if(config.isTimingDriven()) this.addNodesDelays(net);
					break;
				}
			}
		}
		return illegalRoutes;
	}
	
	/**
	 * Adds nodes and delay values of a routed to the map
	 * @param net The routed net
	 */
	protected void addNodesDelays(NetWrapper net){	
		for(Connection c:net.getConnection()){
			for(Routable group : c.getRnodes()){
				this.nodesDelays.put(group.getNode(), group.getDelay());
			}
		}
	}
	
	/**
	 * Checks if a connection has multi-driver nodes
	 * @param connection The connection in question
	 * @return true, if the connection has multi-driver nodes
	 */
	protected boolean shouldMergePath(Connection connection) {
		return connection.hasMultiFaninNode();
	}
	
	/**
	 * Rips up a connection
	 * @param connection The connection to be ripped up
	 */
	protected void ripUp(Connection connection){
		Routable parent = null;
		for(int i = connection.getRnodes().size() - 1; i >= 0; i--){
			Routable rnode = connection.getRnodes().get(i);
			RoutableData rNodeData = rnode.getRoutableData();
			
			rNodeData.removeSource(connection.getSource());
			
			rNodeData.removeSource(connection.getNet().getOldSource());
			
			if(parent == null){
				parent = rnode;
			}else{
				rNodeData.removeParent(parent);
				parent = rnode;
			}
			// Calculation of present congestion penalty
			rnode.updatePresentCongesCost(this.presentCongesFac);
		}
	}
	
	/**
	 * Updates the users and present congestion cost of rnodes used by a routed connection
	 * @param connection The routed connection
	 */
	protected void updateUsersAndPresCost(Connection connection){
		Routable parent = null;
		for(int i = connection.getRnodes().size()-1; i >= 0; i--){
			Routable rnode = connection.getRnodes().get(i);
			RoutableData rNodeData = rnode.getRoutableData();
			
			rNodeData.addSource(connection.getSource());
			
			if(parent == null){
				parent = rnode;
			}else{
				rNodeData.addParent(parent);
				parent = rnode;
			}
			// Calculation of present congestion penalty
			rnode.updatePresentCongesCost(this.presentCongesFac);
		}
	}
	
	/**
	 * Assigns PIPs to each net and sanity check of PIP usage
	 */
	protected void pipsAssignment(){
		for(NetWrapper np:this.nets){
			Set<PIP> netPIPs = new HashSet<>();
			
			for(Connection c:np.getConnection()){
				netPIPs.addAll(RouterHelper.connectionPIPs(c));				
			}
			np.getNet().setPIPs(netPIPs);
		}
		
		this.checkPIPsUsage();
	}
	
	/**
	 * Checks if there are PIP overlapps among routed nets
	 */
	protected void checkPIPsUsage(){
		Map<PIP, Set<Net>> pipsUsage = new HashMap<>();
		for(Net net:this.design.getNets()){
			for(PIP pip:net.getPIPs()){
				Set<Net> users = pipsUsage.get(pip);
				if(users == null) users = new HashSet<>();
				users.add(net);
				pipsUsage.put(pip, users);
				
			}
		}
		int pipsError = 0;
		for(PIP pip:pipsUsage.keySet()){
			if(pipsUsage.get(pip).size() > 1){
				if(pipsError < 10) {
					System.out.println("pip " + pip + " users = " + pipsUsage.get(pip));
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
	 * Checks if the peek of the queue if the target
	 * @return true, if the peek element of queue is the target
	 */
	protected boolean targetReached(){
		return this.queue.peek().getFirst().isTarget();
	}
	
	boolean successRoute = false;
	/**
	 * Routes a connection
	 * @param connection The connection to route
	 */
	protected void routeCon(Connection connection){
		this.prepareRouteCon(connection);
		
		successRoute = false;
		float shareWeight = (float) (Math.pow(1 - connection.getCriticality(), this.config.getShareExponent()));
		float nonCriti = 1 - connection.getCriticality();
		
		while(!this.queue.isEmpty()){
			if(!this.targetReached() && !successRoute) {
				Pair<Routable, Float> queueElement = this.queue.poll();
				Routable rnode = queueElement.getFirst();
				this.nodesPopped++;
				
				this.setChildrenOfRnode(rnode);
				
				this.exploreAndExpand(rnode, connection, shareWeight, nonCriti);
			}else {
				successRoute = true;
				break;
			}
		}
		
		if(successRoute) {
			this.finishRouteCon(connection);
			connection.getSink().setRouted(true);
			if(config.isTimingDriven()) connection.updateRouteDelay();	
		}else {
			connection.getSink().setRouted(false);
			connection.getSinkRnode().setTarget(false);
			this.resetExpansion();
			System.out.printf("CRITICAL WARNING: Unroutable connection in iteration #%d\n", this.routeIteration);
			System.out.println("                 " + connection);
			this.handleUnroutableCon(connection);
		}
	}
	
	/**
	 * Deals with a failed connection by possible output pin swapping and unrouting preserved nets if the router is in the soft preserve mode
	 * @param connection The failed connection
	 */
	protected void handleUnroutableCon(Connection connection) {
		if (this.routeIteration == 1) {		
			boolean hasAltOutput = this.swapOutputPin(connection);
			if(!hasAltOutput && this.config.isSoftPreserve()) {
				this.unrouteReservedNetsToReleaseResources(connection);
			}
		} else if(this.routeIteration == 2) {
			if (this.config.isSoftPreserve()) this.unrouteReservedNetsToReleaseResources(connection);
		}
	}
	
	/**
	 * Swaps the output pin of a connection, if its net has an alternative output pin
	 * @param connection The connection in question
	 * @return true, if the output pin has been swapped
	 */
	protected boolean swapOutputPin(Connection connection) {	
		NetWrapper np = connection.getNet();
		Net n = np.getNet();
		
		SitePinInst altSource = DesignTools.getLegalAlternativeOutputPin(n);//n.getAlternateSource();
		if(altSource == null) {
			System.out.println("INFO: No alternative source to swap");	
			return false;
		}
		
		System.out.println("INFO: Swap source from " + n.getSource() + " to " + altSource + "\n");
		
		n.replaceSource(altSource);
		n.setSource(altSource);
		n.setAlternateSource(connection.getSource());
		DesignTools.routeAlternativeOutputSitePin(n, altSource);
		np.setSourceChanged(true, connection.getSource());
		
		Node sourceINTNode = RouterHelper.projectOutputPinToINTNode(altSource);
		Routable sourceR = this.createAddRoutableNode(this.rnodeId, altSource, sourceINTNode, RoutableType.PINFEED_O);;
		for(Connection c : np.getConnection()) {
			c.setSource(altSource);					
			c.setSourceRnode(sourceR);
		}
			
		return true;
	}
	
	/**
	 * Unroutes preserved nets to release routing resource to resolve congestion that blocks the routablity of a connection
	 * @param connection The connection in question
	 * @return The unmber of unrouted nets
	 */
	protected int unrouteReservedNetsToReleaseResources(Connection connection) {
		// find those reserved signals that are using uphill nodes of the target pin node
		Set<Net> toRouteNets = new HashSet<>();
		for(Node node : connection.getSinkRnode().getNode().getAllUphillNodes()) {
			Net toRoute = this.preservedNodes.get(node);
			if(toRoute == null) continue;
			if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
			toRouteNets.add(toRoute);
		}
		// find those preserved nets that are using downhill nodes of the source pin node
		for(Node node : connection.getSourceRnode().getNode().getAllDownhillNodes()) {
			Net toRoute = this.preservedNodes.get(node);
			if(toRoute == null) continue;
			if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
			toRouteNets.add(toRoute);
		}
		
		if(!toRouteNets.isEmpty()) {
			System.out.println("INFO: Unroute " + toRouteNets.size() + " preserved nets");
			System.out.println(toRouteNets);
		}
		
		for(Net n : toRouteNets) {
			List<Node> reservedNetNodes = RouterHelper.getNodesOfNet(n);
			for(Node toRemove : reservedNetNodes) {
				this.preservedNodes.remove(toRemove);
			}
			
			NetWrapper netnew = this.initializeNetAndCons(n, this.config.getBoundingBoxExtension(), this.multiSLRDevice);
			
			for(int i = 0; i < reservedNetNodes.size(); i ++) {
				Node toBuild = reservedNetNodes.get(i);
				Routable rnode = this.rnodesCreated.get(toBuild);
				if(rnode == null) {
					rnode = new RoutableNode(this.rnodeId, toBuild, RoutableType.WIRE);
					this.rnodeId++;
					this.rnodesCreated.put(toBuild, rnode);
					//set delay if timing-driven
					if(this.config.isTimingDriven()) {
						rnode.setDelay(RouterHelper.computeNodeDelay(this.estimator, toBuild));
					}
				}
				// each routable created above should be added to its parent routable if parent exists
				// because children of existing parent may have been set
				for(Node uphill : toBuild.getAllUphillNodes()) {
					// without this routethruHelper check, there will be Invalid Programming for Site error in Vivado
					// because we do not know if the routethru is available or not
					if(routethruHelper.isRouteThru(uphill, toBuild)) continue;
					RoutableNode parent = (RoutableNode) this.rnodesCreated.get(uphill);
					if(parent != null && parent.isChildrenSet()) {
						if(!parent.getChildren().contains(rnode)) parent.getChildren().add(rnode);
					}
				}
			}
			if(this.config.isTimingDriven()) this.setTimingEdgesOfCons(netnew.getConnection());
		}
		
		this.sortCons();
		return toRouteNets.size();
	}
	
	/**
	 * Sets the list of children of a rnode, if it has not been set
	 * @param rnode The rnode in question
	 */
	protected void setChildrenOfRnode(Routable rnode) {
		if(!rnode.isChildrenSet()) {
			this.rnodesTimer.start();
			int rnodeCounter = rnode.setChildren(this.rnodeId, this.rnodesCreated,
					this.preservedNodes.keySet(), this.routethruHelper);
			this.rnodeId = rnodeCounter;
			this.rnodesTimer.stop();
		}
	}
	
	/**
	 * Checks if a NODE_PINBOUNCE is suitable to be used for routing to a target
	 * @param pinBounce The PINBOUNCE rnode in question
	 * @param target The target rnode to reach
	 * @return true, if the PINBOUNCE rnode is in the same column as the target and within one INT tile of the target
	 */
	protected boolean usablePINBounce(Routable pinBounce, Routable target){
		Tile bounce = pinBounce.getNode().getTile();
		Tile sink = target.getNode().getTile();
		if(bounce.getTileXCoordinate() == sink.getTileXCoordinate() && Math.abs(bounce.getTileYCoordinate() - sink.getTileYCoordinate()) <= 1){
			return true;
		}
		return false;
	}
	
	/**
	 * Completes the routing process of a connection
	 * @param connection The routed target connection
	 */
	protected void finishRouteCon(Connection connection){
		this.saveRouting(connection);
		
		connection.getSinkRnode().setTarget(false);
		
		this.resetExpansion();
		
		this.updateUsersAndPresCost(connection);
	}
	
	/**
	 * Traces back for a connection from its sink rnode to its source, in order to build the routing path
	 * Storing the path of the connection
	 * @param con: The connection that is being routed
	 */
	protected void saveRouting(Connection connection){
		Routable rnode = connection.getSinkRnode();
		while (rnode != null) {
			connection.addRnode(rnode);
			rnode = rnode.getRoutableData().getPrev();
		}
	}
	
	/**
	 * Resets the expansion history
	 */
	protected void resetExpansion() {
		for (RoutableData node : this.rnodesVisited) {
			node.setVisited(false);
		}
		this.rnodesVisited.clear();
	}
	
	/**
	 * Explores children (downhill rnodes) of a rnode for routing a connection and pushes the child into the queue,
	 * if it is the target or is an accessible routing resource
	 * @param rnode The parent rnode popped out from the queue
	 * @param connection The connection that is being routed
	 * @param shareWeight The criticality-aware share weight for a new sharing factor
	 */
	protected void exploreAndExpand(Routable rnode, Connection connection, float shareWeight, float nonCriti){	
		boolean longParent = DelayEstimatorBase.isLong(rnode.getNode());
		for(Routable childRNode:rnode.getChildren()){
			if(childRNode.isTarget()){		
				this.evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, nonCriti);
				this.successRoute = true;
				return;
				
			}else if(childRNode.getRoutableType() == RoutableType.WIRE) {
				if(childRNode.getDelay() > 10000) {
					// to filter out those nodes that are considered to be excluded with the masking resource approach,
					// such as U-turn shape nodes near the boundary and some node cross RCLK
					continue;		
				}
				if(this.isAccesible(childRNode, connection)){
					this.evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, nonCriti);
				}
			}
			else if(childRNode.getRoutableType() == RoutableType.PINBOUNCE) {			
				if(this.isAccesible(childRNode, connection)) {				
					if(this.usablePINBounce(childRNode, connection.getSinkRnode())) {
						this.evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, nonCriti);
					}					
				}
			}//SLR crossing expansion
			else if(childRNode.getRoutableType() == RoutableType.PINFEED_I) {
				if(connection.isCrossSLR()) {
					this.evaluateCostAndPush(rnode, longParent, childRNode, connection, shareWeight, nonCriti);
				}
			}
		}
	}

	/**
	 * Checks if a routing resource is accessible
	 * @param child The routing resource in question
	 * @param connection The connection to route
	 * @return true, if no bounding box constraints, or if the routing resource is within the connection's bounding box when use the bounding box constraint
	 */
	protected boolean isAccesible(Routable child, Connection connection) {
		if(this.config.isUseBoundingBox()) {
			return child.isInConBoundingBox(connection);
		}
		return true;
	}
	
	/**
	 * Evaluates the cost of a child of a rnode and pushes the child into the queue after cost evaluation
	 * @param rnode The parent rnode of the child in question
	 * @param childRnode The child rnode in question
	 * @param connection The target connection being routed
	 */
	protected void evaluateCostAndPush(Routable rnode, boolean longParent, Routable childRnode, Connection connection, float sharingWeight, float nonCriti) {
		RoutableData childData = childRnode.getRoutableData();
		int countSourceUses = childData.countSourceUses(connection.getSource());	
		float sharingFactor = 1 + sharingWeight* countSourceUses;
		
		float upstreamPathCost = rnode.getRoutableData().getUpstreamPathCost();// upstream path cost	
		float rnodeCost = this.getRoutableCost(childRnode, connection, countSourceUses, sharingFactor);
		//upstream path cost + cost of node under consideration
		float newPartialPathCost = upstreamPathCost + nonCriti * rnodeCost
								+ nonCriti * this.oneMinusWlWeight * childRnode.getLength() / sharingFactor
								+ connection.getCriticality() * this.oneMinusTimingWeight * (childRnode.getDelay()
								+ DelayEstimatorBase.getExtraDelay(childRnode.getNode(), longParent))/100f;

		float newTotalPathCost = 0f;	
		if(!childRnode.isTarget()){	
			computeDeltaXY(childRnode, connection);
			float expected_wire_cost = (float) this.distanceCostToSink() / sharingFactor;
			
			newTotalPathCost = (float) (newPartialPathCost
							+ nonCriti * this.wlWeight * expected_wire_cost
							+ connection.getCriticality() * this.timingWeight * (this.deltaX * 0.32 + this.deltaY * 0.16));
		}else{
			newTotalPathCost = newPartialPathCost;
		}
		
		this.nodesEvaluated++;
		if(!childData.isVisited() || (childData.isVisited() && newTotalPathCost < childData.getLowerBoundTotalPathCost())) {
			this.rnodesVisited.add(childData);
			this.push(childData, childRnode, rnode, newPartialPathCost, newTotalPathCost);
		}
	}
	
	protected short deltaX = 0;
	protected short deltaY = 0;
	/**
	 * Computes the distance from a childRnode to the sink of a connection in the horizontal and vertical direction
	 * @param childRNode The childRnode being evaluated
	 * @param connection The connection being routed
	 */
	protected void computeDeltaXY(Routable childRNode, Connection connection) {
		this.deltaX = (short) Math.abs(childRNode.getX() - connection.getSinkRnode().getX());
		this.deltaY = (short) Math.abs(childRNode.getY() - connection.getSinkRnode().getY());
	}
	
	/**
	 * Gets total distance to the sink based on the distance in horizontal and vertical directions
	 * @return Total distance
	 */
	protected float distanceCostToSink(){
		return (float)(this.deltaX + this.deltaY);
	}
	
	/**
	 * Gets the congestion cost and bias cost of a rnode
	 * @param rnode The rnode in question
	 * @param connection The connection being routed
	 * @param countSameSourceUsers The number of connections from the same net that are using rnode.
	 * Note: a net is represented by its source SitePinInst
	 * @param sharingFactor The sharing factor
	 * @return The sum of the congestion cost and the bias cost of rnode
	 */
	protected float getRoutableCost(Routable rnode, Connection connection, int countSameSourceUsers, float sharingFactor) {		
		boolean hasSameSourceUsers = countSameSourceUsers!= 0;	
		//Present congestion cost
		float pres_cost;
		
		if(hasSameSourceUsers) {//the "node" is used by other connection(s) from the same net
			int overoccupancy = rnode.getOccupancy() - Routable.capacity;
			pres_cost = 1 + overoccupancy * this.presentCongesFac;//making it less expensive in congestion cost for the current connection
		}else{
			pres_cost = rnode.getPresentCongesCost();
		}
		
		float bias_cost = 0;
		if(!rnode.isTarget()) {
			NetWrapper net = connection.getNet();
			bias_cost = 0.5f * rnode.getBaseCost() / net.getConnection().size() *
					(Math.abs(rnode.getX() - net.getX_geo()) + Math.abs(rnode.getY() - net.getY_geo())) / net.getHpwl();
		}
		
		return rnode.getBaseCost() * rnode.getHistoricalCongesCost() * pres_cost / sharingFactor + bias_cost*1f;
	}
	
	/**
	 * * Sets the costs of a childRnode and adds it into queue
	 * @param childData The data object of the childRnode
	 * @param childRnode The target rnode
	 * @param rnode The parent routale of childRnode
	 * @param newPartialPathCost The upstream path cost from childRnode to the source
	 * @param newLowerBoundTotalPathCost Total path cost of childRnode
	 */
	protected void push(RoutableData childData, Routable childRnode, Routable rnode, float newPartialPathCost, float newTotalPathCost) {
		childData.setLowerBoundTotalPathCost(newTotalPathCost);
		childData.setUpstreamPathCost(newPartialPathCost);
		childData.setPrev(rnode);
		this.queue.add(new Pair<Routable, Float>(childRnode, newTotalPathCost));
		this.nodesPushed++;
	}
	
	/**
	 * Prepares for routing a connection
	 * @param connection The target connection to be routed
	 */
	protected void prepareRouteCon(Connection connection){
		// Rips up the connection
		this.ripUp(connection);
		
		this.connectionsRouted++;
		this.connectionsRoutedIteration++;
		// Clears previous route of the connection
		connection.resetRoute();
		this.queue.clear();	
		
		// Sets the sink rnode of the connection as the target
		connection.getSinkRnode().setTarget(true);
		
		// Adds source to queue
		this.push(connection.getSourceRnode().getRoutableData(), connection.getSourceRnode(), null, 0, 0);
	}
	
	public Design getDesign() {
		return this.design;
	}
	
	public int getNumSitePinOfStaticNets() {
		int totalSitePins = 0;
		for(Net n:this.staticNetAndRoutingTargets.keySet()) {
			totalSitePins += this.staticNetAndRoutingTargets.get(n).size();
		}
		return totalSitePins;
	}
	
	public void printDesignNetsInfoAndConfiguration() {
		this.printDesignInfo();
		this.printConfiguration();
	}
	
	public void printConfiguration(){
		if(this.config.isVerbose()) System.out.println(this.config);
	}
	
	public void printTimingInfo(){
		if(this.sortedIndirectConnections.size() > 0) {
			this.timingManager.getCriticalPathInfo(this.maxDelayAndTimingVertex, this.timingEdgeConnectionMap, false, this.rnodesCreated);
		}
	}
	
	public void printIterationHeader() {
		System.out.printf("------------------------------------------------------------------------------\n");
        System.out.printf("%4s  %10s  %8s  %12s  %15s  %8s  %9s\n",
        		"Iter",
        		"Con routed",
        		"Time (s)",
        		"Total Rnodes",
        		"Rnodes Tacc (s)",
        		"Overused",
        		"Dmax (ps)");
        System.out.printf("----  ----------  --------  ------------  ---------------  --------  ---------\n");
	}
	
	public void printNodeTypeUsageAndWirelength() {
		if(this.config.isVerbose()) {
			System.out.println("Node Usage Per Type\n");
			System.out.print(String.format(" %-15s  %11s  %10s\n", "INT_Nodes", "Usage", "Length"));
			for(IntentCode ic : nodeTypes) {
				long usage = this.nodeTypeUsage.getOrDefault(ic, (long)0);
				long length = this.nodeTypeLength.getOrDefault(ic, (long)0);
				System.out.printf(String.format(" %-15s  %11d  %10d\n", ic, usage, length));
			}
			System.out.println();
		}
	}
	
	public void printDesignInfo(){
		if(!this.config.isVerbose()) return;
		System.out.println("------------------------------------------------------------------------------");
		System.out.printf("%-30s %10d\n", "Total nets: ", this.design.getNets().size());
		System.out.printf("%-30s %10d\n", "Routable nets: ", this.numPreservedRoutableNets + this.nets.size() +  this.staticNetAndRoutingTargets.size() + this.clkNets.size());
		System.out.printf("%-30s %10d\n", "  Preserved routble nets: ", this.numPreservedRoutableNets);
		System.out.printf("%-30s %10d\n", "    GLOBAL_CLOCK: ", this.numPreservedClks);
		System.out.printf("%-30s %10d\n", "    Static nets: ", this.numPreservedStaticNets);
		System.out.printf("%-30s %10d\n", "    WIRE: ", this.numPreservedWire);
		System.out.printf("%-30s %10d\n", "  Nets to be routed: ", (this.nets.size() +  this.staticNetAndRoutingTargets.size() + this.clkNets.size()));
		System.out.printf("%-30s %10d\n", "    GLOBAL_CLOCK: ", this.clkNets.size());
		System.out.printf("%-30s %10d\n", "    Static nets: ", this.staticNetAndRoutingTargets.size());
		System.out.printf("%-30s %10d\n", "    WIRE: ", this.nets.size());
		int clkPins = 0;
		for(Net clk : this.clkNets) {
			clkPins += clk.getSinkPins().size();
		}
		System.out.printf("%-30s %10d\n", "  All site pins to be routed: ", (this.indirectConnections.size() + this.getNumSitePinOfStaticNets() + clkPins));	
		System.out.printf("%-30s %10d\n", "    Connections to be routed: ", this.indirectConnections.size());
		System.out.printf("%-30s %10d\n", "    Static net pins: ", this.getNumSitePinOfStaticNets());
		System.out.printf("%-30s %10d\n", "    Clock pins: ", clkPins);
		System.out.printf("%-30s %10d\n", "Nets not needing routing: ", this.numNotNeedingRoutingNets);
		if(this.numUnrecognizedNets != 0)
			System.out.printf("%-30s %10d\n", "Nets unrecognized: ", this.numUnrecognizedNets);
		System.out.printf("------------------------------------------------------------------------------\n");
	}
	
	public void printRoutingStatistics(){
		MessageGenerator.printHeader("Statistics");
		this.computesNodeUsageAndTotalWirelength();
		this.printNodeTypeUsageAndWirelength();
		System.out.printf("%-30s %10d\n", "Total wirelength:", this.totalWL);
		if(this.config.isVerbose()) {
			System.out.printf("%-30s %10d\n", "Total INT tile nodes:", this.totalINTNodes);
			System.out.printf("%-30s %10d\n", "Total rnodes created:", this.rnodeId);
			System.out.printf("%-30s %10.1f\n", "Average #children per node:", this.avgChildrenOfRnodes);
			System.out.printf("------------------------------------------------------------------------------\n");	
			System.out.printf("%-30s %10d\n", "Num iterations:", this.routeIteration);
			System.out.printf("%-30s %10d\n", "Connections routed:", this.connectionsRouted);
			System.out.printf("%-30s %10d\n", "Nodes evaluated:", this.nodesEvaluated);	
			System.out.printf("%-30s %10d\n", "Nodes pushed:", this.nodesPushed);
			System.out.printf("%-30s %10d\n", "Nodes poped:", this.nodesPopped);
			System.out.printf("------------------------------------------------------------------------------\n");
		}
		
		System.out.print(this.routerTimer);
		if(config.isTimingDriven()) {
			MessageGenerator.printHeader("Timing Report");
			this.printTimingInfo();
		}
		System.out.printf("==============================================================================\n");
		
	}
}
