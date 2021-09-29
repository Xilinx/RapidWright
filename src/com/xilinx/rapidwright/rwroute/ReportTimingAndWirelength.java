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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.rwroute.GlobalSignalRouting.RoutingNode;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

/**
 * An example to report the critical path delay and total wirelength of a routed design.
 * It is able to reproduce the same statistics as a {@link RWRoute} Object reports after routing a design.
 */
public class ReportTimingAndWirelength{
	private Design design;
	private int rnodeId;
	private long wirelength;
	private long usedNodes;
	private int numWireNetsToRoute;
	private int numConnectionsToRoute;
	private TimingManager timingManager;
	private DelayEstimatorBase estimator;
	private Map<TimingEdge, Connection> timingEdgeConnectionMap;
	private Map<IntentCode, Long> nodeTypeUsage ;
	private Map<IntentCode, Long> nodeTypeLength;
	
	public ReportTimingAndWirelength(Design design, Configuration config) {
		this.design = design;
		RWRoute.setTimingData(config);	
		this.timingManager = new TimingManager(this.design, true, null, config);		
	    this.estimator = new DelayEstimatorBase(this.design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
		RoutableNode.setTimingDriven(true, this.estimator);
		this.wirelength = 0;
		this.usedNodes = 0;
		this.timingEdgeConnectionMap = new HashMap<>();
		this.nodeTypeUsage = new HashMap<>();
		this.nodeTypeLength = new HashMap<>();
	}
	
	/**
	 * Computes the wirelength and delay for each net and reports the total wirelength and critical path delay.
	 */
	private void computeStatisticsAndReport() {	
		this.computeWLDlyForEachNet();
		
		Pair<Float, TimingVertex> maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireTimes();
		System.out.println();
		this.timingManager.getCriticalPathInfo(maxDelayAndTimingVertex, this.timingEdgeConnectionMap, false, null);
		
		System.out.println("\n");
		System.out.println("Total nodes: " + this.usedNodes);
		System.out.println("Total wirelength: " + this.wirelength);	
		RWRoute.printNodeTypeUsageAndWirelength(true, this.nodeTypeUsage, this.nodeTypeLength);
	}
	
	/**
	 * Computes the wirelength and delay for each net.
	 */
	private void computeWLDlyForEachNet() {
		for(Net n : this.design.getNets()) {
			if (n.getType() != NetType.WIRE) continue;
			if(!RouterHelper.isRoutableNetWithSourceSinks(n)) continue;
			if(n.getSource().toString().contains("CLK")) continue;
			NetWrapper netplus = this.createNetWrapper(n);		
			List<Node> netNodes = RouterHelper.getNodesOfNet(n);			
			for(Node node:netNodes){	
				if(node.getTile().getTileTypeEnum() != TileTypeEnum.INT) continue;
				usedNodes++;	
				int wl = RouterHelper.getLengthOfNode(node);	
				this.wirelength += wl;
				RouterHelper.addNodeTypeLengthToMap(node, wl, this.nodeTypeUsage, this.nodeTypeLength);	
			}			
			RWRoute.setTimingEdgesOfConnections(netplus.getConnection(), this.timingManager, this.timingEdgeConnectionMap);
			this.setAccumulativeDelayOfEachNetNode(netplus);
		}
	}
	
	/**
	 * Creates a {@link NetWrapper} Object that consists of a list of {@link Connection} Objects, based on a net.
	 * @param net
	 * @return
	 */
	private NetWrapper createNetWrapper(Net net) {
		NetWrapper netWrapper = new NetWrapper(this.numWireNetsToRoute++, (short) 3, net);			
		SitePinInst source = net.getSource();
		Node sourceINTNode = null;
		for(SitePinInst sink:net.getSinkPins()){
			if(RouterHelper.isExternalConnectionToCout(source, sink)){
				source = net.getAlternateSource();
				if(source == null){
					String errMsg = "Null alternate source is for COUT-CIN connection: " + net.toStringFull();
					 throw new IllegalArgumentException(errMsg);
				}
			}
			Connection c = new Connection(this.numConnectionsToRoute++, source, sink, netWrapper);	
			List<Node> nodes = RouterHelper.projectInputPinToINTNode(sink);
			if(nodes.isEmpty()) {	
				c.setDirect(true);
			}else {
				c.setSinkRnode(new RoutableNode(this.rnodeId++, nodes.get(0), RoutableType.PINFEED_I));
				if(sourceINTNode == null) {
					sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
				}
				c.setSourceRnode(new RoutableNode(this.rnodeId++, sourceINTNode, RoutableType.PINFEED_O));
				c.setDirect(false);
			}
		}
		return netWrapper;
	}
	
	/**
	 * Using PIPs to calculate and set accumulative delay for each used node of a routed net that is represented by a {@link NetWrapper} Object.
	 * The delay of each node is the total route delay from the source to the node (inclusive).
	 * @param netplus
	 */
	private void setAccumulativeDelayOfEachNetNode(NetWrapper netplus) {
		List<PIP> pips = netplus.getNet().getPIPs();	
		Map<Node, RoutingNode> nodeRoutingNodeMap = new HashMap<>();
		boolean firstPIP = true;
		for(PIP pip : pips) {
			// This approach works because we observed that the PIPs are in order
			Node startNode = pip.getStartNode();
			RoutingNode startrn = RouterHelper.createRoutingNode(startNode, nodeRoutingNodeMap);
			if(firstPIP) startrn.setDelayFromSource(0);
			firstPIP = false;
			
			Node endNode = pip.getEndNode();
			RoutingNode endrn = RouterHelper.createRoutingNode(endNode, nodeRoutingNodeMap);
			endrn.setPrev(startrn);
			float delay = 0;
			if(endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				delay = RouterHelper.computeNodeDelay(this.estimator, endrn.getNode())
						+ DelayEstimatorBase.getExtraDelay(endNode, DelayEstimatorBase.isLong(startNode));
			}
			endrn.setDelayFromSource(startrn.getDelayFromSource() + delay);
		}
		
		for(Connection c : netplus.getConnection()) {
			if(c.isDirect()) continue;
			Node sinkNode = c.getSinkRnode().getNode();
			RoutingNode sinkrn = nodeRoutingNodeMap.get(sinkNode);
			if(sinkrn == null) continue;
			float cdelay = sinkrn.getDelayFromSource();
			if(c.getTimingEdges() == null) continue;
			c.setTimingEdgesDelay(cdelay);	
		}	
	}
	
	public static void main(String[] args) {
		if(args.length < 1){
			System.out.println("USAGE:\n <input.dcp>");
		}
		Design design = Design.readCheckpoint(args[0]);	
		Configuration config = new Configuration(args);
		config.setPartialRouting(false);
		config.setTimingDriven(true);
		ReportTimingAndWirelength reporter = new ReportTimingAndWirelength(design, config);	
		reporter.computeStatisticsAndReport();
	}	
}
