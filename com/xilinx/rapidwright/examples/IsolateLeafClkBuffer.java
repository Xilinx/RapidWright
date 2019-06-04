/*
 * 
 * Copyright (c) 2019 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 * 
 */
/**
 * 
 */
package com.xilinx.rapidwright.examples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteNode;

/**
 * Created on: May 24, 2019
 */
public class IsolateLeafClkBuffer {

	private static Set<Integer> topLCBIndices;
	private static Set<Integer> botLCBIndices;
	static {
		Integer[] topIndices = {2,3,8,9,10,11,16,17,18,19,24,25,26,27,30,31};
		Integer[] botIndices = {0,1,4,5,6,7,12,13,14,15,20,21,22,23,28,29};
		topLCBIndices = new HashSet<>(Arrays.asList(topIndices));
		botLCBIndices = new HashSet<>(Arrays.asList(botIndices));
	}
	
	private static boolean isLCBPIPInTop(PIP pip){
		int lcbIndex = Integer.parseInt(pip.getEndWireName()
				.replace("CLK_LEAF_SITES_", "")
				.replace("_CLK_LEAF", "")
				.replace("_CLK_IN", ""));
		return topLCBIndices.contains(lcbIndex);
	}
	
	private static List<PIP> findRoute(RouteNode src, RouteNode snk, Set<RouteNode> used){
		Set<RouteNode> visited = new HashSet<>();
		Queue<RouteNode> q = RouteNode.getPriorityQueue();
		
		RouteNode curr = null;
		q.add(src);
		while(!q.isEmpty()){
			curr = q.poll();
			if(curr.equals(snk)){
				return curr.getPIPsBackToSource();
			}
			visited.add(curr);
			for(Wire w : curr.getConnections()){
				RouteNode nextNode = new RouteNode(w,curr);
				if(visited.contains(nextNode)) continue;
				if(used.contains(nextNode)) continue;
				nextNode.setCost(nextNode.getManhattanDistance(snk) + curr.getLevel());
				q.add(nextNode);
			}
		}
		
		return null;
	}
	
	public static List<PIP> routeNewLCB(SitePinInst clkPin){		
		Node sink = clkPin.getConnectedNode();
		Net net = clkPin.getNet();
		Map<Node, PIP> reversePaths = new HashMap<Node, PIP>();
		for(PIP p : net.getPIPs()){
			reversePaths.put(p.getEndNode(), p);
		}
		Node curr = sink;
		//RCLK_INT_L_X49Y449/RCLK_INT_L.CLK_LEAF_SITES_10_CLK_IN->>CLK_LEAF_SITES_10_CLK_LEAF
		while(!curr.getWireName().startsWith("CLK_LEAF_SITES")){
			curr = reversePaths.get(curr).getStartNode();
		}
		PIP currLCB = reversePaths.get(curr);
		boolean isTopLCB = isLCBPIPInTop(currLCB);
		
		PIP drivingPIP = reversePaths.get(currLCB.getStartNode());
		
		
		Net clk = clkPin.getNet();
		if(!clk.removePin(clkPin, true)){
			throw new RuntimeException("ERROR: Couldn't disconnect clk pin " +
				clkPin + " on site " + clkPin.getSite());
		}
		
		
		RouteNode src = drivingPIP.getStartRouteNode();
		RouteNode snk = new RouteNode(sink);
		Set<RouteNode> used = new HashSet<>();
		for(Net n : net.getSource().getSiteInst().getDesign().getNets()){
			for(PIP p : n.getPIPs()){
				used.add(p.getStartRouteNode());
				used.add(p.getEndRouteNode());
			}
		}

		List<PIP> route = findRoute(src, snk, used);

		if(route == null){
			throw new RuntimeException("ERROR: Couldn't find new LCB path");
		}
		
		System.out.println("New Clock Path PIPs:");
		for(PIP p : route){
			System.out.println("\t" + p);
		}
		
		net.getPIPs().addAll(route);
		return route;
	}
	
	public static void main(String[] args) {
		if(args.length != 3){
			System.out.println("USAGE: <input.dcp> <cell_clk_pin_name> <output.dcp>");
		}
		Design d = Design.readCheckpoint(args[0]);

		int idx = args[1].lastIndexOf('/');
		String cellName = args[1].substring(0, idx);
		String logPinName = args[1].substring(idx+1);
		Cell c = d.getCell(cellName);
		
		SitePinInst clkPin = c.getSitePinFromLogicalPin(logPinName, null);
		Net clk = clkPin.getNet();

		routeNewLCB(clkPin);
		
		clk.addPin(clkPin);
		
		d.writeCheckpoint(args[2]);
		
	}
}
