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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;

/**
 * A collection of methods for routing global signals, i.e. GLOBAL_CLOCK, VCC and GND.
 * Adapted from RapidWright APIs.
 */
public class GlobalSignalRouting {	
	private static HashSet<String> lutOutputPinNames;
	static {
		lutOutputPinNames = new HashSet<String>();
		for(String cle : new String[]{"L", "M"}){
			for(String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}){
				lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "_O");
			}
		}
	}
	
	/**
	 * Routes a clk enable net with input data.
	 * @param clk The net to be routed.
	 * @param routesToSinkINTTiles A map storing routes from CLK_OUT to different INT tiles that 
	 * connect to sink pins of a global clock net.
	 * @param device The target device needed to get routing path representation with nodes from names.
	 */
	public static void routeClkWithPartialRoutes(Net clk, Map<String, List<String>> routesToSinkINTTiles, Device device) {
		Map<String, List<Node>> dstINTtilePaths = getListOfNodesFromRoutes(device, routesToSinkINTTiles);
		// Not import path after HDSTR
		Set<PIP> clkPIPs = new HashSet<>();
		Map<String, RouteNode> horDistributionLines = new HashMap<>();
		
		for(List<Node> nodes : dstINTtilePaths.values()) {
			Collections.reverse(nodes); // HDISTR to CLK_OUT
			Node hDistr = nodes.get(0);
			RouteNode hdistr = new RouteNode(hDistr.getTile(), hDistr.getWire());
			
			clkPIPs.addAll(RouterHelper.getPIPsFromListOfReversedNodes(nodes));
			
			horDistributionLines.put(getDominateClockRegionOfNode(hDistr), hdistr);
		}
		clk.setPIPs(clkPIPs);
		
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk);
		
		UltraScaleClockRouting.routeToLCBs(clk, getStartingPoint(horDistributionLines, device), lcbMappings.keySet());
		
		// route LCBs to sink pins
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clk.getPIPs());
		clk.setPIPs(clkPIPsWithoutDuplication);	
	}
	
	private static Map<ClockRegion, Set<RouteNode>> getStartingPoint(Map<String, RouteNode> crDistLines, Device dev) {
		Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
		for(Entry<String, RouteNode> crRouteNode : crDistLines.entrySet()) {
			String crName = crRouteNode.getKey();
			ClockRegion cr = dev.getClockRegion(crName);
			Set<RouteNode> routeNodes = startingPoints.get(cr);
			if(routeNodes == null){
				routeNodes = new HashSet<>();
				startingPoints.put(cr, routeNodes);
			}
			routeNodes.add(crRouteNode.getValue());
		}
		return startingPoints;
	}
	
	private static String getDominateClockRegionOfNode(Node node){
		// This is needed because a HDISTR for clock region X3Y2 can have a base tile in clock region X2Y2, 
		// observed with clock routing of the optical-flow design.
		Map<String, Integer> crCounts = new HashMap<>();
		for(Wire wire : node.getAllWiresInNode()) {
			ClockRegion cr = wire.getTile().getClockRegion();
			if(cr == null) {
				continue;
			}
			Integer count = crCounts.get(cr.getName());
			if(count == null) {
				count = 1;
			}else {
				count++;
			}
			crCounts.put(cr.getName(), count);
		}
		
		String dominate = null;
		int max = 0;
		for(Entry<String, Integer> crCount : crCounts.entrySet()) {
			String cr = crCount.getKey();
			Integer count = crCount.getValue();
			if(count > max) {
				max = count;
				dominate = cr;
			}
		}
		
		return dominate;
	}
	
	/**
	 * Gets a list of nodes for each destination, e.g. each clock region or sink INT tile, based on a list of the node names.
	 * @param device The target device.
	 * @param routes The given routes consisting of node names.
	 * @return A map storing a list of nodes for each destination.
	 */
	private static Map<String, List<Node>> getListOfNodesFromRoutes(Device device, Map<String, List<String>> routes){
		Map<String, List<Node>> dstPaths = new HashMap<>();
		for(Entry<String, List<String>> dstRoute : routes.entrySet()) {
			String dst = dstRoute.getKey();
			List<Node> pathNodes = new ArrayList<>();
			for(String nodeName : dstRoute.getValue()) {
				Node node = Node.getNode(nodeName, device);
				if(node != null) {
					pathNodes.add(node);
				}else {
					System.err.println("ERROR: Null Node found under name: " + nodeName);
				}
			}
			dstPaths.put(dst, pathNodes);
		}
		return dstPaths;
	}
	
	/**
	 * Routes a clock net by dividing the target clock regions into two groups and routes to the two groups with different centroid nodes.
	 * @param clk The clock to be routed.
	 * @param device The design device.
	 */
	public static void symmetricClkRouting(Net clk, Device device) {
		List<ClockRegion> clockRegions = getClockRegionsOfNet(clk);
		ClockRegion centroid = findCentroid(clk, device);
		RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);// first HROUTE
		
		RouteNode centroidHRouteNode = UltraScaleClockRouting.routeToCentroid(clk, clkRoutingLine, centroid, true, true);
		
		RouteNode vrouteUp;
		RouteNode vrouteDown;	
		// Two VROUTEs going up and down
		vrouteUp = UltraScaleClockRouting.routeToCentroid(clk, centroidHRouteNode, centroid.getNeighborClockRegion(1, 0), true, false);	
		vrouteDown = UltraScaleClockRouting.routeToCentroid(clk, centroidHRouteNode, centroid.getNeighborClockRegion(0, 0), true, false);
		
		List<ClockRegion> upClockRegions = new ArrayList<>();
		List<ClockRegion> downClockRegions = new ArrayList<>();
		// divides clock regions into two groups
		divideClockRegions(clockRegions, centroid, upClockRegions, downClockRegions);
		
		List<RouteNode> upDownDistLines = new ArrayList<>();
		List<RouteNode> upLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clk, vrouteUp, upClockRegions, false);
		if(upLines != null) upDownDistLines.addAll(upLines);
		
		List<RouteNode> downLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clk, vrouteDown, downClockRegions, true);//TODO this is where the antenna node shows up
		if(downLines != null) upDownDistLines.addAll(downLines);
		
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk);
		UltraScaleClockRouting.routeDistributionToLCBs(clk, upDownDistLines, lcbMappings.keySet());
		
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);
		
		Set<PIP> clkPIPsWithoutDuplication = new HashSet<>();
		clkPIPsWithoutDuplication.addAll(clk.getPIPs());
		clk.getPIPs().clear();
		clk.setPIPs(clkPIPsWithoutDuplication);
	}
	
	/**
	 * Gets clock regions of a net's sink pins.
	 * @param clk The net in question.
	 * @return A list of clock regions of the net's sink pins.
	 */
	private static List<ClockRegion> getClockRegionsOfNet(Net clk) {
		List<ClockRegion> clockRegions = new ArrayList<>();
		for(SitePinInst pin : clk.getPins()) {
			if(pin.isOutPin()) continue;
			Tile t = pin.getTile();
			ClockRegion cr = t.getClockRegion();
			if(!clockRegions.contains(cr)) clockRegions.add(cr);
		}
		return clockRegions;
	}
	
	private static void divideClockRegions(List<ClockRegion> clockRegions, ClockRegion centroid, List<ClockRegion> upClockRegions,
			List<ClockRegion> downClockRegions){
		for(ClockRegion cr : clockRegions) {
			if(cr.getInstanceY() >= centroid.getInstanceY()) {
				upClockRegions.add(cr);
			}else {
				downClockRegions.add(cr);
			}
		}
	}
	
	/**
	 * Maps each sink SitePinInsts of a clock net to a leaf clock buffer node.
	 * @param clk The clock net in question.
	 * @return A map between leaf clock buffer nodes and sink SitePinInsts.
	 */
	private static Map<RouteNode, ArrayList<SitePinInst>> getLCBPinMappings(Net clk){
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = new HashMap<>();
		for(SitePinInst p : clk.getPins()){
			if(p.isOutPin()) continue;
			Node n = null;// n should be a node whose name ends with "CLK_LEAF"
			for(Node prev : p.getConnectedNode().getAllUphillNodes()) {
				if(prev.getTile().equals(p.getSite().getIntTile())) {
					for(Node prevPrev : prev.getAllUphillNodes()) {
						if(prevPrev.getIntentCode() == IntentCode.NODE_GLOBAL_LEAF) {
							n = prevPrev;
							break;
						}
					}
				}
			}
			
			RouteNode rn = n != null? new RouteNode(n.getTile(), n.getWire()):null;
			if(rn == null) throw new RuntimeException("ERROR: No mapped LCB to SitePinInst " + p);
			ArrayList<SitePinInst> sinks = lcbMappings.get(rn);
			if(sinks == null){
				sinks = new ArrayList<>();
				lcbMappings.put(rn, sinks);
			}
			sinks.add(p);	
		}
		
		return lcbMappings;
	}
	
	/**
	 * Finds the centroid clock region of a clock net.
	 * @param clk The clock net of a design.
	 * @param device The device of the design.
	 * @return The centroid clock region of a clock net.
	 */
	private static ClockRegion findCentroid(Net clk, Device device) {
		HashSet<Point> sitePinInstTilePoints = new HashSet<>();	
		for(SitePinInst spi : clk.getPins()) {
			if(spi.isOutPin()) continue;
			ClockRegion c = spi.getTile().getClockRegion();
			sitePinInstTilePoints.add(new Point(c.getColumn(),c.getRow()));
		}	
		Point center = SmallestEnclosingCircle.getCenterPoint(sitePinInstTilePoints);
		ClockRegion c = device.getClockRegion(center.y, center.x);		
		return c;
	}
	
	/**
	 * Routes a static net (GND or VCC).
	 * @param currNet The current static net to be routed.
	 * @param unavailableNodes A set of unavailable nodes.
	 * @param design The {@link Design} instance to use.
	 * @param routeThruHelper The {@link RouteThruHelper} instance to use.
	 */
	public static Map<SitePinInst, List<Node>> routeStaticNet(Net currNet, Set<Node> unavailableNodes, Design design, RouteThruHelper routeThruHelper){
		NetType netType = currNet.getType();
		Set<PIP> netPIPs = new HashSet<>();
		Map<SitePinInst, List<Node>> sinkPathNodes = new HashMap<>();
		Queue<RoutingNode> q = new LinkedList<>();
		Set<RoutingNode> visitedRoutingNodes = new HashSet<>();
		Set<RoutingNode> usedRoutingNodes = new HashSet<>();
		Map<Node, RoutingNode> createdRoutingNodes = new HashMap<>();
		
		boolean debug = false;
		if(debug) {
			System.out.println("Net: " + currNet.getName());
		}
		
		for(SitePinInst sink : currNet.getPins()) {
			if(sink.isOutPin()) continue;
			int watchdog = 10000;	
			if(debug) {
				System.out.println("SINK: TILE = " + sink.getTile().getName() + " NODE = " + sink.getConnectedNode().toString());
			}
			q.clear();
			visitedRoutingNodes.clear();
			List<Node> pathNodes = new ArrayList<>();			
			Node node = sink.getConnectedNode();
			if(debug) System.out.println(node);
			RoutingNode sinkRNode = RouterHelper.createRoutingNode(node, createdRoutingNodes);
			sinkRNode.setPrev(null);	
			q.add(sinkRNode);
			boolean success = false;
			while(!q.isEmpty()){
				RoutingNode routingNode = q.poll();
				visitedRoutingNodes.add(routingNode);		
				if(debug) System.out.println("DEQUEUE:" + routingNode);
				if(debug) System.out.println(", PREV = " + routingNode.getPrev() == null ? " null" : routingNode.getPrev());		
				if(success = isThisOurStaticSource(design, routingNode, netType, usedRoutingNodes)){		
					//trace back for a complete path
					if(debug){
						System.out.println("SINK: TILE = " + sink.getTile().getName() + " NODE = " + sink.getConnectedNode().toString());
						System.out.println("SOURCE " + routingNode.toString() + " found");
					}	
					while(routingNode != null){
						usedRoutingNodes.add(routingNode);// use routed RNodes as the source
						pathNodes.add(routingNode.getNode());
						
						if(debug) System.out.println("  " + routingNode.toString());
						routingNode = routingNode.getPrev();
					}
					Collections.reverse(pathNodes);
					sinkPathNodes.put(sink, pathNodes);
					if(debug){
						for(Node pathNode:pathNodes){
							System.out.println(pathNode.toString());
						}
					}
					break;
				}
				if(debug){
					System.out.println("KEEP LOOKING FOR A SOURCE...");
				}
				for(Node uphillNode : routingNode.getNode().getAllUphillNodes()){
					if(routeThruHelper.isRouteThru(uphillNode, routingNode.getNode())) continue;
					RoutingNode nParent = RouterHelper.createRoutingNode(uphillNode, createdRoutingNodes);
					if(nParent == null) continue;
					if(!pruneNode(nParent, unavailableNodes, visitedRoutingNodes)) {
						nParent.setPrev(routingNode);
						q.add(nParent);
					}
				}
				watchdog--;
				if(watchdog < 0) {
					break;
				}
			}
			if(!success){
				System.err.println("ERROR: Failed to route " + currNet.getName() + " pin " + sink.toString());
			}else{
				sink.setRouted(true);
			}
		}
		
		for(List<Node> nodes:sinkPathNodes.values()){
			netPIPs.addAll(RouterHelper.getPIPsFromListOfReversedNodes(nodes));
		}
		
		currNet.setPIPs(netPIPs);
		return sinkPathNodes;
	}
	
	/**
	 * Checks if a {@link RoutingNode} instance that represents a {@link Node} object should be pruned.
	 * @param routingNode The RoutingNode in question.
	 * @param unavailableNodes A set of unavailable Node instances.
	 * @param visitedRoutingNodes RoutingNode instances that have been visited.
	 * @return true, if the RoutingNode instance should not be considered as an available resource.
	 */
	private static boolean pruneNode(RoutingNode routingNode, Set<Node> unavailableNodes, Set<RoutingNode> visitedRoutingNodes){
		Node node = routingNode.getNode();
		IntentCode ic = node.getTile().getWireIntentCode(node.getWire());
		switch(ic){
			case NODE_GLOBAL_VDISTR:
			case NODE_GLOBAL_HROUTE:
			case NODE_GLOBAL_HDISTR:
			case NODE_HLONG:
			case NODE_VLONG:
			case NODE_GLOBAL_VROUTE:
			case NODE_GLOBAL_LEAF:
			case NODE_GLOBAL_BUFG:
				return true;
			default:
		}
		if(unavailableNodes.contains(node)) return true;
		if(visitedRoutingNodes.contains(routingNode)) return true;
		return false;
	}
	
	/**
	 * Determines if the given {@link RoutingNode} instance that represents a {@link Node} instance can serve as our sink.
	 * @param routingNode The {@link RoutingNode} instance in question.
	 * @param type The net type to designate the static source type.
	 * @param usedRoutingNodes The used RoutingNode instances by of the given net type representing the VCC or GND net.
	 * @return true if this sources is usable, false otherwise. 
	 */
	private static boolean isThisOurStaticSource(Design design, RoutingNode routingNode, NetType type, Set<RoutingNode> usedRoutingNodes){
		if(usedRoutingNodes != null && usedRoutingNodes.contains(routingNode))
			return true;
		Node node = routingNode.getNode();
		return isNodeUsableStaticSource(node, type, design);
	}
	
	/**
	 * This method handles queries during the static source routing process. 
	 * It determines if the node in question can be used as a source for the current NetType.
	 * @param node The node in question.
	 * @param type The {@link NetType} instance to indicate what kind of static source we need (GND/VCC).
	 * @param design The design instance to use for getting corresponding {@link SiteInst} instance info. 
	 * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source.
	 */
	private static boolean isNodeUsableStaticSource(Node node, NetType type, Design design){
		// We should look for 3 different potential sources
		// before we stop:
		// (1) GND_WIRE 
		// (2) VCC_WIRE 
		// (3) Unused LUT Outputs (A_0, B_0,...,H_0)
		String pinName = type == NetType.VCC ? Net.VCC_WIRE_NAME : Net.GND_WIRE_NAME;		
		if(node.getWireName().startsWith(pinName)){
			return true;
		}else if(lutOutputPinNames.contains(node.getWireName())){
			Site slice = node.getTile().getSites()[0];
			SiteInst i = design.getSiteInstFromSite(slice);			
			if(i == null) return true; // Site is not used
			char uniqueId = node.getWireName().charAt(node.getWireName().length()-3);
			Net currNet = i.getNetFromSiteWire(uniqueId + "_O");
			if(currNet == null) return true;
			if(currNet.getType() == type) return true;
			return false;
		}
		return false;
	}
	
}
