/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 * 
 */
package com.xilinx.rapidwright.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteNode;

/**
 * A collection of utility methods for routing clocks on
 * the UltraScale architecture.
 * 
 * Created on: Feb 1, 2018
 */
public class UltraScaleClockRouting {
	
	public static RouteNode routeBUFGToNearestRoutingTrack(Net clk){
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		q.add(new RouteNode(clk.getSource()));
		int watchDog = 300;
		while(!q.isEmpty()){
			RouteNode curr = q.poll();
			IntentCode c = curr.getIntentCode(); 
			if(c == IntentCode.NODE_GLOBAL_HROUTE){
				clk.getPIPs().addAll(curr.getPIPsBackToSource());
				return curr;
			}
			for(Wire w : curr.getWireConnections()){
				q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
			}
			if(watchDog-- == 0) break;
		}
		return null;
	}

	/**
	 * Routes a clock from a routing track to a transition point called the centroid
	 * where the clock fans out and transitions from clock routing tracks to clock distribution 
	 * tracks 
	 * @param clk The current clock net to contribute routing
	 * @param clkRoutingLine The intermediate start point of the clock route
	 * @param centroid ClockRegion/FSR considered to be the centroid target
	 */
	public static RouteNode routeToCentroid(Net clk, RouteNode clkRoutingLine, ClockRegion centroid) {
		Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		HashSet<RouteNode> visited = new HashSet<>();
		clkRoutingLine.setParent(null);
		q.add(clkRoutingLine);
		Tile approxTarget = centroid.getApproximateCenter();
		int watchDog = 10000;
		while(!q.isEmpty()){
			RouteNode curr = q.poll();
			visited.add(curr);

			for(Wire w : curr.getWireConnections()){
				RouteNode parent = curr.getParent(); 
				if(parent != null){
					if(w.getIntentCode()      == IntentCode.NODE_GLOBAL_VDISTR &&
					   curr.getIntentCode()   == IntentCode.NODE_GLOBAL_VROUTE && 
					   parent.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE && 
					   centroid.equals(w.getTile().getClockRegion()) &&
					   centroid.equals(curr.getTile().getClockRegion()) &&
					   centroid.equals(parent.getTile().getClockRegion()) && 
					   parent.getWireName().contains("BOT")){
						clk.getPIPs().addAll(curr.getPIPsBackToSource());
						return curr;
					}
				}
				
				
				
				// Only using routing lines to get to centroid
				if(!w.getIntentCode().isUltraScaleClockRouting()) continue;
				RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
				if(visited.contains(rn)) continue;
				rn.setCost(rn.getTile().getManhattanDistance(approxTarget));
				q.add(rn);
			}
			if(watchDog-- == 0) {
				break;
			}
		}
		return null;		
	}

	/**
	 * Routes the centroid route track to a vertical distribution track to realize
	 * the centroid and root of the clock.
	 * @param clk Clock net to route 
	 * @param centroidRouteLine The current routing track found in the centroid
	 * @return The vertical distribution track for the centroid clock region
	 */
	public static RouteNode transitionCentroidToDistributionLine(Net clk, RouteNode centroidRouteLine){
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		centroidRouteLine.setParent(null);
		if(centroidRouteLine.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR){
			return centroidRouteLine;
		}
		q.add(centroidRouteLine);
		ClockRegion currCR = centroidRouteLine.getTile().getClockRegion();
		int watchDog = 1000;
		while(!q.isEmpty()){
			RouteNode curr = q.poll();
			IntentCode c = curr.getIntentCode(); 
			if(curr.getTile().getClockRegion().equals(currCR) && c == IntentCode.NODE_GLOBAL_VDISTR){
				clk.getPIPs().addAll(curr.getPIPsBackToSource());
				return curr;
			}
			for(Wire w : curr.getWireConnections()){
				// Stay in this clock region to transition from 
				if(!currCR.equals(w.getTile().getClockRegion())) continue;
				if(!w.getIntentCode().isUltraScaleClocking()) continue;
				q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
			}
			if(watchDog-- == 0) break;
		}
		return null;		
		
	}
	
	/**
	 * Routes the vertical distribution path and generates a map between each target clock region and the vertical distribution line to 
	 * start from.
	 * @param clk The clock net.
	 * @param centroidDistNode Starting point vertical distribution line
	 * @param clockRegions The target clock regions.
	 * @return A map of target clock regions and their respective vertical distribution lines
	 */
	public static Map<ClockRegion, RouteNode> routeCentroidToVerticalDistributionLines(Net clk,	RouteNode centroidDistNode, List<ClockRegion> clockRegions) {
		Map<ClockRegion, RouteNode> crToVdist = new HashMap<>();
		centroidDistNode.setParent(null);
		Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		HashSet<RouteNode> visited = new HashSet<>();
		Set<PIP> allPIPs = new HashSet<>();
		Set<RouteNode> startingPoints = new HashSet<>();
		startingPoints.add(centroidDistNode);
		nextClockRegion: for(ClockRegion cr : clockRegions){
			q.clear();
			visited.clear();
			q.addAll(startingPoints);
			//q.add(centroidDistNode);
			Tile crTarget = cr.getApproximateCenter();
			while(!q.isEmpty()){
				RouteNode curr = q.poll();
				visited.add(curr);
				IntentCode c = curr.getIntentCode();
				ClockRegion currCR = curr.getTile().getClockRegion();
				if(currCR != null && cr.getRow() == currCR.getRow() && c == IntentCode.NODE_GLOBAL_VDISTR){
					List<PIP> pips = curr.getPIPsBackToSource();
					allPIPs.addAll(pips);
					for(PIP p : pips){
						startingPoints.add(new RouteNode(p.getTile(),p.getStartWireIndex()));
						startingPoints.add(new RouteNode(p.getTile(),p.getEndWireIndex()));
					}
					crToVdist.put(cr, curr);
					continue nextClockRegion;
				}
				for(Wire w : curr.getWireConnections()){
					if(w.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR) continue;
					RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
					if(visited.contains(rn)) continue;
					rn.setCost(w.getTile().getManhattanDistance(crTarget));
					q.add(rn);
				}
			}
			throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
		}
		clk.getPIPs().addAll(allPIPs);
		return crToVdist;
	}
	
	/**
	 * Routes from a vertical distribution centroid to destination horizontal distribution lines 
	 * in the clock regions provided.  
	 * @param clk
	 * @param centroidDistLine
	 * @param clockRegions
	 * @return
	 */
	public static List<RouteNode> routeCentroidToHorizontalDistributionLines(Net clk, RouteNode centroidDistLine, Map<ClockRegion,RouteNode> crMap) {
		List<RouteNode> distLines = new ArrayList<>();
		centroidDistLine.setParent(null);
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		Set<PIP> allPIPs = new HashSet<>();
		nextClockRegion: for(Entry<ClockRegion,RouteNode> e : crMap.entrySet()){
			q.clear();
			q.add(e.getValue());
			while(!q.isEmpty()){
				RouteNode curr = q.poll(); 
				IntentCode c = curr.getIntentCode();
				if(e.getKey().equals(curr.getTile().getClockRegion()) && c == IntentCode.NODE_GLOBAL_HDISTR){
					List<PIP> pips = curr.getPIPsBackToSource();
					allPIPs.addAll(pips);
					distLines.add(curr);
					continue nextClockRegion;
				}
				for(Wire w : curr.getWireConnections()){ 
					if(!w.getIntentCode().isUltraScaleClocking()) continue;
					q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
				}
			}
			throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + e.getKey());
		}
		clk.getPIPs().addAll(allPIPs);
		return distLines;
	}

	/**
	 * @param clk
	 * @param lcbTargets
	 * @return
	 */
	public static void routeDistributionToLCBs(Net clk, List<RouteNode> distLines, Set<RouteNode> lcbTargets) {
		Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
		for(RouteNode rn : distLines){
			ClockRegion cr = rn.getTile().getClockRegion();
			Set<RouteNode> routeNodes = startingPoints.get(cr);
			if(routeNodes == null){
				routeNodes = new HashSet<>();
				startingPoints.put(cr, routeNodes);
			}
			routeNodes.add(rn);
		}
		
		Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		Set<PIP> allPIPs = new HashSet<>();
		
		nextLCB: for(RouteNode lcb : lcbTargets){
			q.clear();
			ClockRegion currCR = lcb.getTile().getClockRegion();
			q.addAll(startingPoints.get(currCR));
			while(!q.isEmpty()){
				RouteNode curr = q.poll(); 
				if(lcb.equals(curr)){
					List<PIP> pips = curr.getPIPsBackToSource();
					allPIPs.addAll(pips);
					
					Set<RouteNode> s = startingPoints.get(currCR);
					for(PIP p : pips){
						s.add(new RouteNode(p.getTile(),p.getStartWireIndex()));
						s.add(new RouteNode(p.getTile(),p.getEndWireIndex()));
					}
					continue nextLCB;
				}
				for(Wire w : curr.getWireConnections()){
					// Stay in this clock region
					if(!currCR.equals(w.getTile().getClockRegion())) continue;
					if(!w.getIntentCode().isUltraScaleClocking()){
						// Final node will not be clocking intent code
						SitePin p = w.getSitePin(); 
						if(p == null) continue;
						if(p.getSite().getSiteTypeEnum() != SiteTypeEnum.BUFCE_LEAF) continue;
					}
					RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
					rn.setCost(rn.getManhattanDistance(lcb));
					q.add(rn);
				}
			}
			throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + lcb);
		}
		clk.getPIPs().addAll(allPIPs);
	}

	/**
	 * @param clk
	 * @param lcbMappings
	 */
	public static void routeLCBsToSinks(Net clk, Map<RouteNode, ArrayList<SitePinInst>> lcbMappings) {
		Set<Wire> used = new HashSet<>();
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		
		for(Entry<RouteNode,ArrayList<SitePinInst>> e : lcbMappings.entrySet()){
			Set<PIP> currPIPs = new HashSet<>();
			
			nextPin: for(SitePinInst sink : e.getValue()){
				RouteNode target = sink.getRouteNode();
				q.clear();
				q.add(e.getKey());
			
				while(!q.isEmpty()){
					RouteNode curr = q.poll(); 
					if(target.equals(curr)){
						List<PIP> pips = curr.getPIPsBackToSource();
						currPIPs.addAll(pips);
						continue nextPin;
					}
					for(Wire w : curr.getWireConnections()){
						if(used.contains(w)) continue;
						q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
					}
				}
				throw new RuntimeException("ERROR: Couldn't route LCB " + e.getKey() + "to Pin " + sink);
			}

			List<PIP> clkPIPs = clk.getPIPs();
			for(PIP p : currPIPs){
				used.add(new Wire(p.getTile(),p.getStartWireIndex()));
				used.add(new Wire(p.getTile(),p.getEndWireIndex()));
				clkPIPs.add(p);
			}
		}
	}
}
