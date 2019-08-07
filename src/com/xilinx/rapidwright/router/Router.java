/* 
 * Copyright (c) 2017 Xilinx, Inc. 
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;


/**
 * Basic router for routing inter-site nets.
 * 
 * Created on: Aug 13, 2015
 */
public class Router extends AbstractRouter {

	private static HashSet<String> allLongLines;
	private static HashSet<String> intNodeQuadLongs;
	private static HashSet<String> allExclusiveIntSinks;
	private static HashMap<String,String> clkSitePIPNames;
	private static HashSet<String> lutOutputPinNames;
	
	private static boolean allowWireOverlap = false;
	
	public static boolean ENABLE_RIPUP = false; // TODO - This mode is WIP
	
	public static boolean ENABLE_LUT_INPUT_SWAP = true;

	
	private PBlock routingPblock;
	
	private ArrayList<RouteNode> pathFromSinkToSwitchBox = null;
	private boolean isCurrNetClk;
	
	private ArrayList<SitePinInst> failedRoutes = new ArrayList<SitePinInst>();

	private boolean supressWarningsErrors = false;
	
	/** Nets found to conflict with a particular net that will be ripped-up and re-routed */
	private HashSet<RouteNode> conflictNodes;
	
	/** The additional min cost of adding a node to the queue when compared with the head */
	int minCeilingCost = 20;
	protected PriorityQueue<RouteNode> clockQueue;
	static {
		allLongLines = new HashSet<String>();
		for(int i=0; i < 4; i++){
			for(String endPoint : new String[]{"BEG", "END"}){
				allLongLines.add("NN12_"+endPoint+i);
				allLongLines.add("NN16_"+endPoint+i);
				allLongLines.add("SS12_"+endPoint+i);
				allLongLines.add("SS16_"+endPoint+i);
				
				allLongLines.add("WW12_"+endPoint+i);
				allLongLines.add("WW12_"+endPoint+(i+4));
				allLongLines.add("EE12_"+endPoint+i);
				allLongLines.add("EE12_"+endPoint+(i+4));
			}
		}
		
		//INT_NODE_QUAD_LONG_#_INT_OUT -> drives Long
		intNodeQuadLongs = new HashSet<String>();
		for(int i=0; i < 128; i++){
			intNodeQuadLongs.add("INT_NODE_QUAD_LONG_"+i+"_INT_OUT");
		}
		
		// INT SINKS
		allExclusiveIntSinks = new HashSet<String>();
		for(int i=0; i < 48; i++){
			allExclusiveIntSinks.add("IMUX_E" + i);
			allExclusiveIntSinks.add("IMUX_W" + i);

			if(i < 8){
				allExclusiveIntSinks.add("CTRL_E_B" + i);
				allExclusiveIntSinks.add("CTRL_W_B" + i);
			}
		}
		
		clkSitePIPNames = new HashMap<String, String>();
		// UltraScale
		clkSitePIPNames.put("CLK_B1", "CLK1INV");
		clkSitePIPNames.put("CLK_B2", "CLK2INV");
		clkSitePIPNames.put("LCLK_B", "LCLKINV");
		clkSitePIPNames.put("CLKAL_X", "CLK_OPTINV_CLKA_L");
		clkSitePIPNames.put("CLKAU_X", "CLK_OPTINV_CLKA_U");
		clkSitePIPNames.put("CLKBL_X", "CLK_OPTINV_CLKB_L");
		clkSitePIPNames.put("CLKBU_X", "CLK_OPTINV_CLKB_U");
		clkSitePIPNames.put("CLKFBIN", "");
		clkSitePIPNames.put("CLK_IN", "");
		clkSitePIPNames.put("REGCLKAL_X","REGCLK_OPTINV_CLKA_L");
		clkSitePIPNames.put("REGCLKAU_X","REGCLK_OPTINV_CLKA_U");
		clkSitePIPNames.put("REGCLKBL_X","REGCLK_OPTINV_CLKB_L");
		clkSitePIPNames.put("REGCLKBU_X","REGCLK_OPTINV_CLKB_U");
		clkSitePIPNames.put("CLK_B", "CLKINV");
		// Series 7
		clkSitePIPNames.put("CLK", "CLKINV");
		
		lutOutputPinNames = new HashSet<String>();
		for(String cle : new String[]{"L", "M"}){
			for(String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}){
				lutOutputPinNames.add("CLE_CLE_"+cle+"_SITE_0_"+pin+"_O");
			}
		}
	}

	
	public Router(Design design){
		super();
		this.design = design;
		dev = design.getDevice();
		clockQueue = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
	}
	
	public PBlock getRoutingPblock() {
		return routingPblock;
	}

	public void setRoutingPblock(PBlock routingPblock) {
		this.routingPblock = routingPblock;
	}

	private boolean canUseNode(RouteNode n){
		if(usedNodes.contains(n)){
			// Only allow over subscribed if the net is routed with this router
			//   We don't want to rip-up nets from pre-compiled blocks, these have
			//   already satisfied a timing constraint and should remain intact.
			return allowWireOverlap && usedNodesMap.get(n)!= null;
		}
		if(routingPblock != null){
			return routingPblock.getAllTiles().contains(n.getTile());
		}
		return true;
	}
	
	
	public static final int LONG_LINE_THRESHOLD = 11;
	
	/**
	 * Prepares the class variables for the route() method. Sets everything up
	 * for each connection to be made. This method is called for each connection
	 * in a net by routeNet(). It calls route() once the variables are ready
	 * for routing.
	 * 
	 */
	protected void routeConnection(){
		prepareForRoutingConnection();

		// Check if we should route on just longs
		RouteNode bestSrc = queue.peek();
		int x = Math.abs(bestSrc.getTile().getTileXCoordinate() - currSink.getTile().getTileXCoordinate());
		int y = Math.abs(bestSrc.getTile().getTileYCoordinate() - currSink.getTile().getTileYCoordinate());
		if(!isCurrSinkAClkWire && (x > LONG_LINE_THRESHOLD || y > LONG_LINE_THRESHOLD)){
			ArrayList<RouteNode> longLineNodes = getLongLinePath(bestSrc);
			/*if(currSinkPin.getSiteInstName().equals("microblaze_0_local_memory/lmb_bram/RAMB36_X2Y9") && currSinkPin.getName().equals("ADDRAL3")){
				System.out.println("Long lines:");
				for(RouteNode n : longLineNodes){
					System.out.println(n.toString());
				}
			}*/
			for(RouteNode ll : longLineNodes){
				if(ll.getConnections() != null){
					setCost(ll, false);
					// Long line router doesn't null out parent refs
					if(currSources.contains(ll)){
						ll.setParent(null);
					}
					queue.add(ll);
				}
			}
		}
		
		// Do the actual routing
		route();
		totalNodesProcessed += nodesProcessed;
	}
	
	public RouteNode findSwitchBoxInput(RouteNode src){		
		RouteNode curr = src;
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		while(!isSwitchBox(curr.getTile())){
			if(curr.getConnections() != null){
				for(Wire conn : curr.getConnections()){
					q.add(new RouteNode(conn.getTile(),conn.getWireIndex(), curr, curr.getLevel()+1));
				}				
			}
			curr = q.remove();
		}
		
		return curr;
	}
	
	private ArrayList<RouteNode> findNearestLongLines(RouteNode src, RouteNode snk){
		ArrayList<RouteNode> longLines = new ArrayList<RouteNode>();
		boolean isUltraScale = design.getPart().isUltraScale(); 
		int x = src.getTile().getTileXCoordinate() - snk.getTile().getTileXCoordinate();
		int y = src.getTile().getTileYCoordinate() - snk.getTile().getTileYCoordinate();
		int absY = Math.abs(y);
		boolean use16LongLines = absY > 14 && isUltraScale;
		int absX = Math.abs(x);
		String longLineName = null;
		String longLineNameAlt = null;
		int range = 4;
		int rangeAlt = 4;
		if(absX > absY){
			if(x < 0){
				longLineName = "EE12_BEG";
				range = 8;
			}
			else{
				longLineName = "WW12_BEG";
				range = 8;
			}
		}
		else{
			if(y < 0){
				longLineName = use16LongLines ? "NN16_BEG" : "NN12_BEG";
			}
			else{
				longLineName = use16LongLines ? "SS16_BEG" : "SS12_BEG";
			}
		}
		if(absX > LONG_LINE_THRESHOLD && absY > LONG_LINE_THRESHOLD){
			if(absX < absY){
				if(x < 0){
					longLineNameAlt = "EE12_BEG";
					rangeAlt = 8;
				}
				else{
					longLineNameAlt = "WW12_BEG";
					rangeAlt = 8;
				}
			}
			else{
				if(y < 0){
					longLineNameAlt = use16LongLines ? "NN16_BEG" : "NN12_BEG";
				}
				else{
					longLineNameAlt = use16LongLines ? "SS16_BEG" : "SS12_BEG";
				}
			}
		}else{
			rangeAlt = 0;
		}
		
		
		RouteNode longLineStart = new RouteNode();
		RouteNode longLineStartAlt = new RouteNode();

		
		int tileX = src.getTile().getTileXCoordinate();
		int tileY = src.getTile().getTileYCoordinate();
		
		int diffX = tileX - snk.getTile().getTileXCoordinate();
		int diffY = tileY - snk.getTile().getTileYCoordinate();
		
		for(int i = 0; i < 5; i++){
			for(int j = 0; j < 5; j++){
				String tileName = null;
				if(diffX < 0 && diffY < 0) tileName = "INT_X" + (tileX + i) + "Y" + (tileY + j);
				if(diffX >= 0 && diffY < 0) tileName = "INT_X" + (tileX - i) + "Y" + (tileY + j);
				if(diffX < 0 && diffY >= 0) tileName = "INT_X" + (tileX + i) + "Y" + (tileY - j);
				if(diffX >= 0 && diffY >= 0) tileName = "INT_X" + (tileX - i) + "Y" + (tileY - j);
				Tile tile = dev.getTile(tileName);
				if(tile != null){
					longLineStart.setTile(tile);
					for(int k=0; k < range; k++){
						
						longLineStart.setWire(longLineStart.getWireIndex(longLineName + k));
						if(canUseNode(longLineStart)){
							longLines.add(new RouteNode(tile, longLineStart.getWire()));
						}					
					}
					longLineStartAlt.setTile(tile);
					for(int k=0; k < rangeAlt; k++){
						longLineStartAlt.setWire(longLineStart.getWireIndex(longLineNameAlt + k));
						if(canUseNode(longLineStartAlt)){
							longLines.add(new RouteNode(tile, longLineStartAlt.getWire()));
						}						
					}
				}
			}
		}
		
		return longLines;
	}
	
	private RouteNode routeToLongLine(RouteNode src, RouteNode snk, HashSet<RouteNode> allNearestLongLines){
		int[] distCost = {2, 3, 3, 3, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6};
		RouteNode tmp = new RouteNode();
		HashSet<RouteNode> visited = new HashSet<RouteNode>();
		PriorityQueue<RouteNode> longlineQueue = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		longlineQueue.add(src);
		RouteNode currNode = longlineQueue.remove();
		visited.add(currNode);
		int nodeCount = 0;
		int limit = distCost[src.getManhattanDistance(snk)] + 2;
		boolean debug = false;
		
		while(!allNearestLongLines.contains(currNode)/*currNode.equals(snk)*/){
			if(debug) System.out.println(" CurrNode: " + currNode.toString());
			List<Wire> conns = currNode.getConnections();
			if(conns != null && currNode.getLevel() <= limit){
				for(Wire wc : conns){
					if(IntentCode.NODE_PINFEED == wc.getIntentCode()) continue;
					tmp.setTileAndWire(wc.getTile(), wc.getWireIndex());
					
					if(visited.contains(tmp)) continue;
					if(!canUseNode(tmp)) continue;
					RouteNode n = new RouteNode(wc.getTile(),wc.getWireIndex(), currNode, currNode.getLevel()+1); 
					
					n.setCost(n.getManhattanDistance(snk)*2 + n.getLevel());
					if(allNearestLongLines.contains(n)/*n.equals(snk)*/){
						n.setCost(-1000);
					}
					longlineQueue.add(n);
					if(debug) System.out.println("   -> " + n.toString());
					visited.add(n);
					nodeCount++;
				}				
			}
			if(longlineQueue.isEmpty() || nodeCount > 1000) {
				return null;
			}
			currNode = longlineQueue.remove();
			//System.out.println(MessageGenerator.makeWhiteSpace(currNode.level) + currNode.toString(we));
		}
		if(debug){
			System.out.println("Return Path:");
			RouteNode n = currNode;
			while(n != null){
				System.out.println("  " + n.toString());
				n = n.getParent();
			}
		}
		
		return currNode;
	}
	
	private RouteNode getOtherEndOfLongLine(RouteNode longLineStart){
		int otherWireEnd = 0;
		String startWireName = longLineStart.getWireName(); 

		// UltraScale specific
		otherWireEnd = longLineStart.getTile().getWireIndex(startWireName.replace("BEG", "END"));
		
		for(Wire wc : longLineStart.getConnections()){
			if(wc.getWireIndex() == otherWireEnd && (!wc.getTile().equals(longLineStart.getTile()))){
				return new RouteNode(wc.getTile(),wc.getWireIndex(), longLineStart, longLineStart.getLevel()+1);
			}
		}
		
		return null;
	}
	
	private RouteNode routeLongLines(RouteNode currLongLine, RouteNode snk){
		RouteNode end = getOtherEndOfLongLine(currLongLine);
		if(end == null){
			return currLongLine;
		}
		int x = end.getTile().getTileXCoordinate() - snk.getTile().getTileXCoordinate();
		int y = end.getTile().getTileYCoordinate() - snk.getTile().getTileYCoordinate();
		int watchDog = 100; // TODO - change later
		RouteNode tmp = new RouteNode();
		HashSet<RouteNode> visited = new HashSet<RouteNode>();
		RouteNode closest = new RouteNode();
		
		boolean debug = false;

		// Keep following long lines until we get within the long line 
		// threshold limit
		PriorityQueue<RouteNode> longLineQueue = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		end.setCost(end.getTile().getManhattanDistance(snk.getTile()));
		longLineQueue.add(end);
		closest = end;
		if(debug) System.out.println(" SRC: " + end.getTile().getName());
		if(debug) System.out.println("SINK: " + snk.getTile().getName());
		while((Math.abs(x) > LONG_LINE_THRESHOLD || Math.abs(y) > LONG_LINE_THRESHOLD) && watchDog > 0 && !longLineQueue.isEmpty()){
			if(debug) System.out.println(MessageGenerator.makeWhiteSpace(end.getLevel()) + end.toString());
			watchDog--;
			
			end = longLineQueue.remove();
			if(end.getCost() < closest.getCost()){
				closest = end;
			}
			
			tmp.setTile(end.getTile());
			for(Wire wc : end.getConnections()){
				// TODO - Categorize UltraScale Long Line Wires
				//if(we.getWireType(wc.getWire()).equals(WireType.LONG)){// && (wireName.contains("0") || wireName.contains("18"))){
				String wireName = wc.getWireName();
				if(intNodeQuadLongs.contains(wireName) || allLongLines.contains(wireName)){
					tmp.setTileAndWire(wc);
					if(debug) System.out.println(MessageGenerator.makeWhiteSpace(end.getLevel()) +" -> "+ tmp.toString() +" "+ visited.contains(tmp) +" "+ usedNodes.contains(tmp));
					if(visited.contains(tmp)) continue;
					if(!canUseNode(tmp)) continue;
					
					int tmpX = tmp.getTile().getTileXCoordinate() - snk.getTile().getTileXCoordinate();
					int tmpY = tmp.getTile().getTileYCoordinate() - snk.getTile().getTileYCoordinate();
					
					
					if(((tmpX < 0 && x > 0) || (tmpX > 0 && x < 0)) || (tmpY < 0 && y > 0) || (tmpY > 0 && y < 0)){
						continue;
					}
					
					if(tmp.getTile().getManhattanDistance(snk.getTile()) > end.getTile().getManhattanDistance(snk.getTile())){
						continue;
					}
					
					
					RouteNode start = new RouteNode(wc.getTile(),wc.getWireIndex());
					start.setParent(end);
					start.setCost(start.getTile().getManhattanDistance(snk.getTile()));
					longLineQueue.add(start);
					visited.add(start);
				}
			}
			if(debug) System.out.println(MessageGenerator.makeWhiteSpace(end.getLevel()) + "NEXT: " + end.toString() + ": CLOSEST=" + closest.toString());
			x = end.getTile().getTileXCoordinate() - snk.getTile().getTileXCoordinate();
			y = end.getTile().getTileYCoordinate() - snk.getTile().getTileYCoordinate();
		}
		//System.out.println((Math.abs(x) > LONG_LINE_THRESHOLD || Math.abs(y) > LONG_LINE_THRESHOLD) +" " + (watchDog > 0) +" " + (!queue.isEmpty()));
		return closest;
	}
	
	protected ArrayList<RouteNode> getLongLinePath(RouteNode src){
		RouteNode sbInput = findSwitchBoxInput(src);
		
		ArrayList<RouteNode> longLines = findNearestLongLines(sbInput, switchMatrixSink);
		HashSet<RouteNode> allNearestLongLines = new HashSet<RouteNode>(longLines);
		RouteNode currLongLine = null;
		RouteNode closestNode = null;
		
		
		for(RouteNode longLine : longLines){
			currLongLine = routeToLongLine(sbInput, longLine, allNearestLongLines);
			if(currLongLine != null){
				closestNode = routeLongLines(currLongLine, switchMatrixSink);
				RouteNode start = getOtherEndOfLongLine(closestNode);
				int x = Math.abs(closestNode.getTile().getTileXCoordinate() - currSink.getTile().getTileXCoordinate());
				int y = Math.abs(closestNode.getTile().getTileYCoordinate() - currSink.getTile().getTileYCoordinate());
				if(currLongLine.equals(start) && (x > LONG_LINE_THRESHOLD || y > LONG_LINE_THRESHOLD)){
					continue;
				}
				else{
					//finalLongLine = closestNode;
					break;					 
				}
			}
		}
		
		ArrayList<RouteNode> path = new ArrayList<RouteNode>();
		
		if(currLongLine == null){
			return path;
		}
		
		while(closestNode != null){
			path.add(closestNode);
			closestNode = closestNode.getParent();
		}
		
		return path;
	}
	
	
	/**
	 * The heart of the router, it does the actual routing by consuming nodes on
	 * the priority queue and determining how to proceed to the sink. It is
	 * called by routeConnection().
	 */
	protected void route(){	
		int ceilingCost = (isCurrSinkAClkWire || currSinkPin.getSiteTypeEnum().equals(SiteTypeEnum.BUFGCTRL)) ? 2000 : minCeilingCost;
		
		// Iterate through all of the nodes in the queue, adding potential candidate nodes 
		// as we go along. We are finished when we find the sink node.
		boolean debug = false;
		
		while(!queue.isEmpty()){
			if(nodesProcessed > 100000){
				// If we haven't found a route by now, we probably never will
				return;
			}
			RouteNode currNode = queue.remove();
			if(debug) System.out.println(MessageGenerator.makeWhiteSpace(currNode.getLevel()) + currNode.toString() + " " + currNode.getIntentCode() + " *DQ*");
			nodesProcessed++;
			nextNode: for(Wire w : currNode.getConnections()){
				if(currNode.equals(currSink) || (w.getWireIndex() == this.currSink.getWire() && w.getTile().equals(currSink.getTile()))){
					
					// We've found the sink, lets retrace our steps
					RouteNode currPathNode = null;
					if(currNode.equals(currSink)){
						// The currNode itself is the sink
						currPathNode = currNode;
					}else{
						// The currNode's child wire is the sink
						currPathNode = new RouteNode(w.getTile(), w.getWireIndex(), currNode, currNode.getLevel()+1);
					}

					if(allowWireOverlap){
						conflictNodes = new HashSet<RouteNode>();
					}
					
					// Add this connection as a PIP, and follow it back to the source
					while(currPathNode.getParent() != null){
						if(allowWireOverlap){
							if(usedNodes.contains(currPathNode)){
								conflictNodes.add(currPathNode);
							}
						}
						for(Wire w1 : currPathNode.getParent().getTile().getWireConnections(currPathNode.getParent().getWire())){
							if(w1.getWireIndex() == currPathNode.getWire()){
								if(w1.isEndPIPWire() && currPathNode.getParent().getTile().equals(currPathNode.getTile())){
									PIP newPIP = new PIP(currPathNode.getTile(), currPathNode.getParent().getWire(), 
														 currPathNode.getWire());
									pipList.add(newPIP);
									break;
								}
							}
						}
						// Update the current node to the parent
						// this way we can traverse backwards to the source
						currPathNode = currPathNode.getParent();
					}
					// Include path (if any) from last switch box pin 
					if(pathFromSinkToSwitchBox != null && pathFromSinkToSwitchBox.size() > 1){
						RouteNode prev = null;
						for(RouteNode n : pathFromSinkToSwitchBox){
							if(prev != null && prev.getTile().equals(n.getTile())) {
								PIP newPIP = prev.getTile().getPIP(prev.getWire(), n.getWire());
								pipList.add(newPIP);
							}
							prev = n;
						}						
					}
					
					// We are now done with the routing of this connection
					successfulRoute = true;
					if(debug) {
						System.out.println("=========" + currNet.getName() + "::" + currSink.toString());
						for(PIP p : pipList) System.out.println(p.toString());
						System.out.println();
					}
					return;
				} 
				else{						
					// This is not the sink, but is this wire one we should look at in the future?
					Tile currTile = w.getTile();
					int currWire = w.getWireIndex();

					// Check if is a routethru, check if the site is consumed by something else
					// If a cell has been placed next to the first Site pin connection, we'll
					// assume this is not available
					if(currNode.getTile().equals(w.getTile()) && !currNode.getTile().getName().startsWith("INT")){
						// Look for possible route-thru conflict
						SitePin sp = currNode.getTile().getSitePinFromWire(currNode.getWire());
						if(sp != null){
							for(BELPin p : sp.getBELPin().getSiteConns()){
								SiteInst si = design.getSiteInstFromSite(sp.getSite());
								if(si != null){
									if( si.getCell(p.getBEL().getName()) != null ) continue nextNode; 
								}
							}
						}
					}
					if(w.isRouteThru()){
						SitePin wsp = w.getSitePin();
						
						// TODO Let's not support LUT route-thrus for now
						if(wsp != null && Utils.isSLICE(wsp.getSite().getSiteTypeEnum())){
							continue nextNode;
						}
						SitePin pin = w.getSitePin();
						if(pin != null){
							SiteInst si = design.getSiteInstFromSite(pin.getSite());
							if(si != null){
								for(BELPin epin : pin.getBELPin().getSiteConns()){
									BEL et = epin.getBEL();
									if(et.getBELClass() == BELClass.RBEL){
										SitePIP sp = si.getUsedSitePIP(epin);
										if(sp != null){
											for(BELPin src : sp.getInputPin().getSiteConns()){
												if(!src.isOutput()) continue;
												Cell possibleCell = si.getCell(sp.getBELName());
												if(possibleCell != null) continue nextNode;
											}
										}
									}else{
										if(et != null && si.getCell(et.getName()) != null) continue nextNode;
									}
								}
							}							
						}
					}

					// Don't follow INT tile sinks 
					if(allExclusiveIntSinks.contains(currTile.getWireName(currWire)) && 
						switchMatrixSink != null && 
						!currTile.equals(switchMatrixSink.getTile())){
						continue;
					}
					
					RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
					// Check if this node has already been visited, if so don't add it
					if(!visitedNodes.contains(tmp) && canUseNode(tmp)){
						// Make sure we haven't used this node already
						if(tmp.getTile().getWireCount() > 0 && tmp.getConnections() != null){
							// This looks like a possible candidate for our next node, we'll add it
							setCost(tmp, w.isRouteThru());
							if(debug){ 
								System.out.println(MessageGenerator.makeWhiteSpace(currNode.getLevel()) 
										+ " -> " + tmp + " " + tmp.getIntentCode());
							}
							if(queue.isEmpty() || tmp.getCost() < (queue.peek().getCost() + ceilingCost)){
								visitedNodes.add(tmp);
								queue.add(tmp);
								if(currSources.contains(tmp)){
									tmp.setParent(null);
								}									
							}
						}
					} 
				}
			}
		}
	}
	

	
	private void prepareSwitchBoxSink(SitePinInst currPin){
		// For the input, find the entry point into its switch box
		switchMatrixSink = null;
		pathFromSinkToSwitchBox = findInputPinFeed(currPin); 
		if(pathFromSinkToSwitchBox != null){
			switchMatrixSink = pathFromSinkToSwitchBox.get(0);
			currSink = switchMatrixSink;
		}		
	}
	
	private void checkAndAddClockPinSitePIP(SitePinInst currSource, SitePinInst currPin){
		boolean currNetOutputFromBUF = currSource != null && currSource.isPinOnABuf();
		isCurrSinkAClkWire = (isClkPin(currSinkPin) || currSinkPin.getName().equals("C")) &&
							  (currNetOutputFromBUF || currSinkPin.isPinOnABuf()) && 
							  !currSource.getSiteTypeEnum().equals(SiteTypeEnum.CONFIG_SITE);
		if(isCurrSinkAClkWire){
			// Some clock pins need a site PIP to get fully routed
			String rBelName = clkSitePIPNames.get(currPin.getName());
			if(rBelName == null){
				if(!supressWarningsErrors) MessageGenerator.briefError("Warning unsupported clock pin: " + currPin);
			}else if(!rBelName.equals("")){
				SitePIP existingPIP = currPin.getSiteInst().getUsedSitePIP(rBelName);
				if(existingPIP == null) {
					//SitePIP p = new SitePIP(dev, currPin.getSiteInst(), rBelName, "CLK", "OUT");
					currPin.getSiteInst().addSitePIP(rBelName, "CLK");					
				}
			}
		}
	}
	
	/**
	 * Updates class members with the current route information
	 * @param currSource The source pin for this net
	 * @param currPin The current sink pin to be routed 
	 */
	public void prepareSinkPinsForRouting(SitePinInst currSource, SitePinInst currPin){
		// Set the appropriate variables
		prepareSink(currPin);
		
		// For the input, find the entry point into its switch box
		prepareSwitchBoxSink(currPin);
			
		// Some clock pins need a site PIP to get fully routed
		checkAndAddClockPinSitePIP(currSource,currPin);
	}
	
	/**
	 * Looks backwards from an input pin depth number of hops to see if there
	 * exists at least one free path.
	 * @param p Input pin to check routability
	 * @param depth Number of PIP hops to check
	 * @return True if there exists at least one path depth PIP hops free, false otherwise.
	 */
	public boolean isRoutable(RouteNode rn, int depth){
		if(usedNodes.contains(rn) || rn.getWireName().contains("LOGIC_OUT")){
			return false;
		}else if(rn.getLevel() == depth){
			return true;
		}
		RouteNode base = rn.getBaseWire();
		for(PIP pip : base.getBackwardPIPs()){
			RouteNode start = new RouteNode(base.getTile(),pip.getStartWireIndex());
			start.setLevel(rn.getLevel()+1);
			if(isRoutable(start, depth)) return true;
		}
		
		return false;
	}
	
	private static String[] lutIndices = new String[]{"1","2","3","4","5","6"}; 
	private static String[] lutBELSuffixes = new String[]{"5LUT", "6LUT"};
	
	public void swapLUTPinForUnused(SitePinInst p){
		String lutName = Character.toString(p.getName().charAt(0));
		String unusedLutPinIndex = null;
		for(String i : lutIndices){
			if(p.getSiteInst().getSitePinInst(lutName + i) == null){
				unusedLutPinIndex = i;
				break;
			}
		}
		if(unusedLutPinIndex != null){
			SiteInst i = p.getSiteInst();
			// All LUT BEL pin mappings (A5 and A6)
			for(String belName : lutBELSuffixes){
				Cell c = i.getCell(lutName + belName);
				if(c == null) continue;
				String logPin = c.removePinMapping("A" + p.getName().charAt(1));
				c.addPinMapping("A" + unusedLutPinIndex, logPin);
			}
			p.movePin(lutName + unusedLutPinIndex);
		}
	}
	
	/**
	 * Checks the current sink LUT to see if there are any alternative LUT input pins
	 * that could be used instead
	 * @return A list of available lut input pins that can be swapped
	 */
	public static List<String> getAlternativeLUTInputs(SitePinInst currSink){
		if(!currSink.isLUTInputPin()) return Collections.emptyList();
		Cell lut = DesignTools.getConnectedCells(currSink).iterator().next();
		String currLutType = lut.getBELName();
		String otherLutType = currLutType.endsWith("6LUT") ? currLutType.replace("6", "5") : currLutType.replace("5", "6");

		// If both LUT5 and LUT6 are occupied, let's not try to be fancy
		if(currSink.getSiteInst().getCell(otherLutType) != null) return Collections.emptyList();
		
		ArrayList<String> alternatives = new ArrayList<>();
		int size = currLutType.charAt(1) - 48;
		for(int i=size; i > 0; i--){
			String physName = "A" + i;
			String logPin = lut.getLogicalPinMapping(physName);
			if(logPin == null) alternatives.add(physName);
		}
		return alternatives;
	}
	
	/**
	 * Changes the physical pin mapping of lutInput to an alternate physical pin
	 * on a LUT in provide for an alternative routing solution.
	 * @param lutInput The physical pin on the site to be swapped
	 * @param newPinName The new physical BEL pin on the lut to serve as the new input.
	 */
	public static void swapLUTInputPins(SitePinInst lutInput, String newPinName){
		Cell lut = DesignTools.getConnectedCells(lutInput).iterator().next();
		String existingName = "A" + lutInput.getName().charAt(1);
		
		String logPin = lut.removePinMapping(existingName);
		lut.addPinMapping(newPinName, logPin);
		
		lutInput.movePin(Character.toString(lutInput.getName().charAt(0)) + newPinName.charAt(1));
	}
	
	
	/**
	 * This method routes all the connections within a net.  
	 */
	public void routeNet(){	
		SitePinInst currSource = currNet.getSource();
		currSources = new HashSet<RouteNode>();
		boolean firstSinkToRouteInNet = true;
				
		// Check for LUT inputs that will need to be swapped
		ArrayList<SitePinInst> pinsToSwap = null;
		for(SitePinInst currPin : currNet.getPins()){
			if(!currPin.isLUTInputPin()) continue;
			int wire = currPin.getSiteInst().getSite().getTileWireIndexFromPinName(currPin.getName());
			RouteNode rn = new RouteNode(currPin.getTile(),wire);
			if(!isRoutable(rn, 2)){
				if(pinsToSwap == null) pinsToSwap = new ArrayList<SitePinInst>();
				pinsToSwap.add(currPin);
				System.out.println(" WILL ATTEMPT TO SWAP LUT INPUT: " + currPin.getNet().getName() + " " + currPin.getName());
			}
		}
		if(pinsToSwap != null){
			for(SitePinInst curr : pinsToSwap){
				swapLUTPinForUnused(curr);
			}
		}
		
		// Route each pin by itself
		for(SitePinInst currPin : currNet.getPins()){
			// Ignore the source pin
			if (currPin.isOutPin()) continue; 

			prepareSinkPinsForRouting(currSource, currPin);
			
			if(firstSinkToRouteInNet){
				// just add the original source
				addInitialSourceForRouting(currSource);
			}
			else{
				// Leverage previous routings to offer additional starting points for this route 
				getSourcesFromPIPs(pipList, currSources/* TODO - prune this list to make it faster*/);
			}
			
			// Route the current sink node
			totalConnections++;
			routeConnection();
			
			
			// If initial route fails, see if we can swap a LUT input
			if(!successfulRoute){
				String origPinName = "A" + currSinkPin.getName().charAt(1);
				for(String alternate : getAlternativeLUTInputs(currSinkPin)){
					swapLUTInputPins(currSinkPin, alternate);
					prepareSinkPinsForRouting(currSource, currSinkPin);
					routeConnection();
					if(successfulRoute) break;
				}
				if(!successfulRoute) {
					// If we couldn't route by swapping, return pin to original location
					swapLUTInputPins(currSinkPin, origPinName);
				}
			}
			

			// Check if it was a successful routing
			if(successfulRoute){
				// Add these PIPs to the rest used in the net
				netPIPs.addAll(pipList);
				currPin.setRouted(true);
			} 
			else{
				if(ENABLE_RIPUP){
					failedRoutes.add(currPin);					
				}else{
					failedConnections++;
					String switchMatrixString = switchMatrixSink != null ? switchMatrixSink.getTile().getName() + " " + switchMatrixSink.getWireName() : "null";
					if(!supressWarningsErrors){ 
						MessageGenerator.briefError("\tFAILED TO ROUTE: net: " +
							currNet.getName() + " inpin: " + currSinkPin.getName() +
							" (" + currSink.getTile().getName() + " "+  currSink.getWireName() +
							" / "+switchMatrixString+") on instance: " + currSinkPin.getSiteInstName());
					}
				}
			}
			firstSinkToRouteInNet = false;
		}
	}
	
	public boolean isSupressWarningsErrors() {
		return supressWarningsErrors;
	}

	public void setSupressWarningsErrors(boolean supressWarningsErrors) {
		this.supressWarningsErrors = supressWarningsErrors;
	}

	/**
     * Creates sources from a list of PIPs
	 * @param pips The pips of the net to examine.
	 * @return The list of sources gathered from the pips list.
	 */
	public HashSet<RouteNode> getSourcesFromPIPs(List<PIP> pips, HashSet<RouteNode> sources){
		if(isCurrNetClk){
			for(PIP pip : pips){
				if(isSwitchBox(pip.getTile())){
					if(!allExclusiveIntSinks.contains(pip.getStartWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getStartWireIndex(), null, 0));
					if(!allExclusiveIntSinks.contains(pip.getEndWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getEndWireIndex(), null, 0));
				}
			}
		}else{
			for(PIP pip : pips){
				if(isSwitchBox(pip.getTile())){
					if(!allExclusiveIntSinks.contains(pip.getStartWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getStartWireIndex(), null, 0));
					if(!allExclusiveIntSinks.contains(pip.getEndWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getEndWireIndex(), null, 0));
				}
			}			
		}
		return sources;
	}
	
	public static boolean isSwitchBox(Tile t){
		TileTypeEnum tt = t.getTileTypeEnum();
		if(t.getDevice().getSeries() == Series.Series7){
			return tt == TileTypeEnum.INT_L || tt == TileTypeEnum.INT_R;
		}
		return tt == TileTypeEnum.INT;
	}
	
	/**
	 * Certain input pins in a switch box can also serve as a bounce.  We need to
	 * prevent the usage of the bounce if the pin will be need to route an input pin.
	 * This method reserves those wires by marking them used.  They are later released
	 * as the routing for that particular net is starting.
	 */
	public void reserveCriticalNodes(){
		for(Net n : design.getNets()){
			if(n.getPins().size() == 0) continue;
			if(n.isStaticNet()){
				// TODO - Right now, we are just un-routing the entire GND/VDD nets
				// - it might be better to leverage parts of it
				n.unroute();
			}
			ArrayList<RouteNode> routeNodes = new ArrayList<RouteNode>();
			for(SitePinInst p : n.getPins()){
				if(p.isRouted()) continue;
				if(p.isOutPin()) continue;
				ArrayList<RouteNode> reserveMe = findInputPinFeed(p);
				if(reserveMe == null) continue;
				routeNodes.add(reserveMe.get(0));
				markNodeUsed(reserveMe.get(0));
			}
			if(routeNodes.size() > 0) reservedNodes.put(n, routeNodes);
		}
	}
	
	public void reserveCriticalNodes(ArrayList<SitePinInst> sitePinInsts){
		nextPin: for(SitePinInst p : sitePinInsts){
			ArrayList<RouteNode> routeNodes = new ArrayList<RouteNode>();
			ArrayList<RouteNode> reserveMe = findInputPinFeed(p);
			if(reserveMe == null) continue;
			for(RouteNode rn : reserveMe){
				if(usedNodes.contains(rn)){
					System.err.println("WARNING: Unable to reserve node " + rn + 
						" for net "+p.getNet().getName()+" as it is already in use." + 
						" This could lead to an unroutable situation.");
					continue nextPin;
				}
			}
			routeNodes.add(reserveMe.get(0));
			markNodeUsed(reserveMe.get(0));
			ArrayList<RouteNode> existingReserved = reservedNodes.get(p.getNet());
			if(existingReserved != null){
				routeNodes.addAll(existingReserved);
			}
			reservedNodes.put(p.getNet(), routeNodes);
		}
	}
	
	/**
	 * This router will preserve all existing routes (even partials) intact.
	 * This method will mark them as used to avoid route conflicts.
	 */
	public void markExistingRouteResourcesUsed(){
		for(Net n : design.getNets()){
			if(!n.hasPIPs()) continue; 
			for(PIP p : n.getPIPs()){
				markNodeUsed(new RouteNode(p.getTile(),p.getStartWireIndex()));
				markNodeUsed(new RouteNode(p.getTile(),p.getEndWireIndex()));
			}
		}
	}
	
	public void identifyMissingPins(){
		// Let's just look at GND/VCC for CTAGs
		// Also, reserve site output pins that are tagged GLOBAL_LOGIC*
		//   This is to avoid conflicts and safeguard internal site nets where the LUT
		//   is supplying VCC/GND to the CARRY BEL for example 
		for(SiteInst i : design.getSiteInsts()){
			for(Entry<String,Net> e : i.getNetSiteWireMap().entrySet()){
				Net n = e.getValue();
				if(e.getKey().equals(Net.GND_WIRE_NAME)) continue;
				if(n.getType() == NetType.GND || n.getType() == NetType.VCC){
					if(i.getSitePinInst(e.getKey()) == null && i.getSite().hasPin(e.getKey())){
						if(i.getSite().isOutputPin(e.getKey())){
							// Reserve this node for future route
							int idx = i.getSite().getTileWireIndexFromPinName(e.getKey());
							RouteNode reserveMe = new RouteNode(i.getTile(),idx);
							ArrayList<RouteNode> currReserved = reservedNodes.get(n);
							if(currReserved == null){
								currReserved = new ArrayList<RouteNode>();
								reservedNodes.put(n, currReserved);
							}
							currReserved.add(reserveMe);
							markNodeUsed(reserveMe);
						}else{
							// Add a new sink to the GLOBAL_LOGIC* net
							n.addPin(new SitePinInst(false, e.getKey(), i));
						}
						
					}
				}
			}
		}
	}
	
	public void markAndUpdateNetPIPsAsUsed(){
		// Mark these used PIPs as used in the data structures
		for (PIP pip : netPIPs){
			setWireAsUsed(pip.getTile(), pip.getStartWireIndex(), currNet);
			setWireAsUsed(pip.getTile(), pip.getEndWireIndex(), currNet);
			markIntermediateNodesAsUsed(pip, currNet);
		}
		// Let's add these PIPs to the actual net, to be included in the design
		currNet.setPIPs(netPIPs);
	}
	
	/**
	 * This method handles queries during the static source routing process. 
	 * It determines if the node in question can be used as a source for the current
	 * NetType.
	 * @param n The node in question
	 * @param type The NetType to indicate what kind of static source we need (GND/VCC)
	 * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source
	 */
	private boolean isNodeUsableStaticSource(RouteNode n, NetType type){
		// We should look for 3 different potential sources
		// before we stop:
		// (1) GND_WIRE 
		// (2) VCC_WIRE 
		// (3) Unused LUT Outputs (A_0, B_0,...,H_0)
		String pinName = type == NetType.VCC ? Net.VCC_WIRE_NAME : Net.GND_WIRE_NAME;
		if(n.getWireName().startsWith(pinName)){
			return true;
		}else if(lutOutputPinNames.contains(n.getWireName())){
			// If lut is unused, we can re-purpose it for a static source
			Site slice = n.getTile().getSites()[0];
			SiteInst i = design.getSiteInstFromSite(slice);			
			if(i == null) return true; // Site is not used
			char uniqueId = n.getWireName().charAt(n.getWireName().length()-3);
			Net currNet = i.getNetFromSiteWire(uniqueId + "_O");
			if(currNet == null) return true;
			if(currNet.getType() == type) return true;
			return false;
			/*String proposedLutName = uniqueId + "6LUT";
			for(Cell c : i.getCells()){
				if(proposedLutName.equals(c.getBel().getName())){
					return false;
				}
			}
			return true;*/
		}
		return false;
	}
	
	/**
	 * Determines if the given node can serve as our sink and updates the net PIPs respectively
	 * if they can be used.
	 * @param n RouteNode in question
	 * @param type The net type to designate the static source type
	 * @return true if this sources is useable and updates the netPIPs accordingly, false otherwise. 
	 */
	private boolean isThisOurStaticSource(RouteNode n, NetType type, boolean debug){
		boolean usable = isNodeUsableStaticSource(n, type);
		if(!usable) return false;
		RouteNode currPathNode = n;
		// Add this connection as a PIP, and follow it back to the source
		while(currPathNode.getParent() != null){
			for(Wire w : currPathNode.getConnections()){
				if(w.getWireIndex() == currPathNode.getParent().getWire() && w.isEndPIPWire()){
					PIP p = new PIP(currPathNode.getTile(),currPathNode.getWire(),currPathNode.getParent().getWire(),w.getPIPType());
					if(debug) {
						System.out.println("  " + p.toString());
					}
					netPIPs.add(p);
					break;
				}
			}
			currPathNode = currPathNode.getParent();
		}
		return true;
	}
	
	public RouteNode getRAMSink(RouteNode sink){
		for(Wire w : sink.getConnections()){
			if (w.getTile().getName().contains("BRAM")){
				RouteNode outSink = new RouteNode(w.getTile(),w.getWireIndex(), sink, sink.getLevel()+1);
				currSink = outSink;
				return outSink;
			}
		}
		return null;
	}
				
	public RouteNode clkToSink(RouteNode clkHDistNode, boolean debug){
		PriorityQueue<RouteNode> tmpQueue = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		setClkCostDistance(clkHDistNode, currSink);
		tmpQueue.add(clkHDistNode);
		while(!tmpQueue.isEmpty()){
			RouteNode currNode = tmpQueue.poll();
			Tile currTile = currNode.getTile();
			if(currTile.getColumn() != currSink.getTile().getColumn() && (currTile.getTileTypeEnum() == TileTypeEnum.RCLK_INT_L || currTile.getTileTypeEnum() == TileTypeEnum.RCLK_INT_R)){
				continue;
			}
			List<Wire> connections = currNode.getConnections();
			for(Wire w : connections){
				RouteNode test = new RouteNode(w.getTile(),w.getWireIndex(), currNode, currNode.getLevel()+1);
				if(debug) System.out.println("clk->sink: "+ test.toString());
				if(!(visitedNodes.contains(test))){
					//if(test.getConnections() != null && canUseNode(test)){
					if(test.getConnections() != null){
						if(test.equals(currSink)){
							currSink.setParent(currNode);
							return currSink;
						}
						
						if(isSwitchBox(test.getTile())){
							if(test.getParent().getIntentCode()!=IntentCode.NODE_GLOBAL_LEAF){
								if(checkSink(test)!=null){
									currSink.setParent(test);
									return currSink;
								}else
									continue;
							}
						}
						setClkCostDistance(test, currSink);
						visitedNodes.add(test);
						tmpQueue.add(test);
					} 
						
				}
			}
		}
		if (debug) System.out.println(clkHDistNode.getTile()+""+clkHDistNode.getWireName()+"--"+clkHDistNode.getIntentCode());
		throw new RuntimeException("ERROR: We could not reach to a Clock Sink of Net " + currNet.getName() + currSinkPin.getName());
	}
	public boolean checkClkResource(RouteNode clkNode){
		if (getClkNumber(clkNode)==-1){
			return false;
		}
		if (usedClkResources.contains(getClkNumber(clkNode))){
			return true;
		}
		return false;
	}
		
	public int getClkNumber(RouteNode clkNode){
		String[] tokens = clkNode.getWireName().split("_");
		int clkNumber;
		try {
			clkNumber = Integer.parseInt(tokens[tokens.length-1]);
		} catch (NumberFormatException e) {
		    clkNumber = -1;
		}
		return clkNumber;
	}
		
	public RouteNode clkBufToClkRoutes(RouteNode src, boolean debug){
		if (debug) System.out.println(currSink);
		ClockRegion crSink = calcClockTreeCentroid();
		ClockRegion crSource = src.getTile().getClockRegion();
		queue.clear();
		queue.add(src);
		//It means that we can use XIPHYs to reach to our destination
		if(crSink.getColumn()==crSource.getColumn()){
			while(!queue.isEmpty()){	
				RouteNode currRouteNode = queue.remove();
				if (currRouteNode.getIntentCode()==IntentCode.NODE_GLOBAL_HDISTR||currRouteNode.getIntentCode()==IntentCode.NODE_GLOBAL_VDISTR){
					// TODO - Find a better way to check these conditions, Also making sure that "XIPHY_L" covers all the clock buffer tiles that we need!
					if ((currRouteNode.getWireName().contains("CLK_H")||currRouteNode.getWireName().contains("CLK_V"))&&currRouteNode.getTile().getTileTypeEnum()==TileTypeEnum.XIPHY_L&&!checkClkResource(currRouteNode)){
						ClockRegion crNode = currRouteNode.getTile().getClockRegion();
						if(crNode.getRow()==crSink.getRow()){
							if (currRouteNode.getIntentCode()==IntentCode.NODE_GLOBAL_HDISTR){
								usedClkResources.add(getClkNumber(currRouteNode));
								return currRouteNode;
							}
						}
						usedClkResources.add(getClkNumber(currRouteNode));
						return currRouteNode;
					}
				} 
				for(Wire w : currRouteNode.getConnections())
				{
					Tile currTile = w.getTile();
					int currWire = w.getWireIndex();
					RouteNode tmp = new RouteNode(currTile, currWire, currRouteNode, currRouteNode.getLevel()+1);
					if(!(visitedNodes.contains(tmp))){
						if(tmp.getConnections() != null && canUseNode(tmp)){
							setClkCostLevel(tmp);
							visitedNodes.add(tmp);
							if (debug)System.out.println(MessageGenerator.makeWhiteSpace(currRouteNode.getLevel()) + " -> " + tmp + " " + tmp.getIntentCode());
							queue.add(tmp);
						}
					}
				}
			}
		}else {
			while(!queue.isEmpty()){	
				RouteNode currRouteNode = queue.remove();
				if ((currRouteNode.getIntentCode()==IntentCode.NODE_GLOBAL_HROUTE||currRouteNode.getIntentCode()==IntentCode.NODE_GLOBAL_VROUTE||currRouteNode.getIntentCode()==IntentCode.NODE_GLOBAL_VDISTR)&&!checkClkResource(currRouteNode)){
					ClockRegion crNode = currRouteNode.getTile().getClockRegion();
					// TODO - Find a exact type or Intent code that can just cover these conditions
					if (crNode.getColumn()>crSink.getColumn()){
						if(currRouteNode.getWireName().contains("HROUTE_0_")){
							usedClkResources.add(getClkNumber(currRouteNode));
							return currRouteNode;
						}
						
					} else {
						if(currRouteNode.getWireName().contains("HROUTE_1_")){
							usedClkResources.add(getClkNumber(currRouteNode));
							return currRouteNode;
						}
					}
				}
				for(Wire w : currRouteNode.getConnections())
				{
					Tile currTile = w.getTile();
					int currWire = w.getWireIndex();
					RouteNode tmp = new RouteNode(currTile, currWire, currRouteNode, currRouteNode.getLevel()+1);
					if(!(visitedNodes.contains(tmp))){
						if(tmp.getConnections() != null && canUseNode(tmp)){
							setClkCostLevel(tmp);
							visitedNodes.add(tmp);
							if (debug)System.out.println(MessageGenerator.makeWhiteSpace(currRouteNode.getLevel()) + " -> " + tmp + " " + tmp.getIntentCode());
							queue.add(tmp);
						}
					}
				}
			}
		}
		throw new RuntimeException("ERROR: We could not reach to a Clock Route from the Clock Buffer of Net " + currNet.getName());
	}
	
	public boolean isClockResource(RouteNode tmpRoute){
		IntentCode c = tmpRoute.getIntentCode(); 
		return c==IntentCode.NODE_GLOBAL_HDISTR ||
			   c==IntentCode.NODE_GLOBAL_VDISTR ||
			   c==IntentCode.NODE_GLOBAL_HROUTE ||
			   c==IntentCode.NODE_GLOBAL_VROUTE ||
			   c==IntentCode.INTENT_DEFAULT;
	}
		
	public RouteNode routeToCentroid(RouteNode clkRoute, boolean debug){
		ClockRegion crAvgSink = calcClockTreeCentroid();
		clockQueue.clear();
		clockQueue.add(clkRoute);
		while(!clockQueue.isEmpty()){
			RouteNode currNode = clockQueue.remove();
			List<Wire> connections = currNode.getWireConnections();
			for(Wire w : connections){
				Tile currTile = w.getTile();
				int currWire = w.getWireIndex();
				RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
				if (!isClockResource(tmp)) continue;
				if(!(visitedNodes.contains(tmp))){
					if(tmp.getConnections() != null && canUseNode(tmp)){
						ClockRegion crTmp = tmp.getTile().getClockRegion();
						if(crTmp == null) continue; // TODO - Farnaz investigate
						if(crTmp.getColumn()==crAvgSink.getColumn()){
							if(crTmp.getRow()==crAvgSink.getRow()){
								// TODO - Find a exact type or Intent code that can just cover these conditions
								if(tmp.getIntentCode()==IntentCode.NODE_GLOBAL_HROUTE && tmp.getWireName().contains("CLK_HROUTE_CORE_OPT")){
									return tmp;
								}
							}
						}
						setClkCostDistance(tmp, avgSink);
						visitedNodes.add(tmp);
						clockQueue.add(tmp);
					}
				}
			}
		}
		return null;
	}
	
	public RouteNode getHDfromBUF(RouteNode routeNode, boolean debug){
		clockQueue.clear();
		ClockRegion crSink = currSink.getTile().getClockRegion();
		clockQueue.add(routeNode);
		while(!clockQueue.isEmpty()){
			RouteNode currNode = clockQueue.remove();
			List<Wire> connections = currNode.getWireConnections();
			for(Wire w : connections){
				Tile currTile = w.getTile();
				int currWire = w.getWireIndex();
				RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
				if (!isClockResource(tmp)) continue;
				if(!(visitedNodes.contains(tmp))){
					// TODO - It is better If we can find exact tile Type that covers these conditions
					ClockRegion crTmp = tmp.getTile().getClockRegion();
					if(tmp.getConnections() != null && canUseNode(tmp) && crSink.getRow()==crTmp.getRow()){
						// TODO - check for CLK_TEST_BUF_SITE_1_CLK_IN as well????
						if(tmp.getWireName().contains("CLK_HDISTR")){
							return tmp;	
						}
						
						setClkCostDistance(tmp, currSink);
						visitedNodes.add(tmp);
						clockQueue.add(tmp);
					}
				}
			}
		}
		throw new RuntimeException("ERROR: getHDfromBUF of Net " + currNet.getName());
	}
	
	public RouteNode getHDISTRfromVHROUTE(RouteNode clkRoute, boolean debug){
		clockQueue.clear();
		ClockRegion crSink = currSink.getTile().getClockRegion();
		clockQueue.add(clkRoute);
		while(!clockQueue.isEmpty()){
			RouteNode currNode = clockQueue.remove();
			List<Wire> connections = currNode.getWireConnections();
			if (connections==null) continue;
			for(Wire w : connections){
				Tile currTile = w.getTile();
				int currWire = w.getWireIndex();
				RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
				if(!(visitedNodes.contains(tmp))){
					// TODO - It is better If we can find exact tile Type that covers these conditions
					if(tmp.getConnections() != null && canUseNode(tmp) && (tmp.getTile().getName().contains("RCLK_CLEL")||tmp.getTile().getName().contains("RCLK_RCLK_BRAM"))){
						ClockRegion crTmp = tmp.getTile().getClockRegion();
						if(tmp.getIntentCode()==IntentCode.NODE_GLOBAL_HDISTR && crSink.getRow()==crTmp.getRow()){
								return getHDfromBUF(tmp, debug);
						}
						setClkCostDistance(tmp, currSink);
						visitedNodes.add(tmp);
						clockQueue.add(tmp);
					}
				}
			}
		}
		throw new RuntimeException("ERROR: getHDISTRfromVHROUTE of Net " + currNet.getName());
	}
		
	public RouteNode getHDISTRCol(RouteNode hDistr, boolean debug){
		clockQueue.clear();
		ClockRegion crSink = currSink.getTile().getClockRegion();
		clockQueue.add(hDistr);
		while(!clockQueue.isEmpty()){
			RouteNode currNode = clockQueue.remove();
			for(Wire w : currNode.getWireConnections()){
				Tile currTile = w.getTile();
				int currWire = w.getWireIndex();
				RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
				if (!isClockResource(tmp)) continue;
				if(!(visitedNodes.contains(tmp))){
					// TODO - It is better If we can find exact tile Type that covers these conditions
					ClockRegion crTmp = tmp.getTile().getClockRegion();
					if(tmp.getConnections() != null && canUseNode(tmp) && crSink.getRow()==crTmp.getRow()){
						if(tmp.getIntentCode()==IntentCode.NODE_GLOBAL_HDISTR && crSink.getColumn()==crTmp.getColumn()){
							if(tmp.getWiresInNode().length > 40){
								return tmp;
							}
							
						}
						setClkCostDistance(tmp, currSink);
						visitedNodes.add(tmp);
						clockQueue.add(tmp);
					}
				}
			}
		}
		throw new RuntimeException("ERROR: getHDISTRCOl of Net " + currNet.getName());
	}
	
	public RouteNode routeCentroidToSinkClkRegion(RouteNode clkRoute, boolean debug){
		ClockRegion crSink = currSink.getTile().getClockRegion();
		if(debug) System.out.println("clkRoutestoClkRegion"+clkRoute.toString()+"->"+currSink.toString());
		clockQueue.clear();
		clockQueue.add(clkRoute);
		while(!clockQueue.isEmpty()){
			RouteNode currNode = clockQueue.remove();
			for(Wire w : currNode.getWireConnections()){
				if (currNode.getWireName().contains("VCC_WIRE")) continue;
				
				Tile currTile = w.getTile();
				int currWire = w.getWireIndex();
				
				ClockRegion crWire = currTile.getClockRegion();
				if (crWire==null) continue;
				RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
				if (!isClockResource(tmp)) continue;
				if(!(visitedNodes.contains(tmp))){
					if(tmp.getConnections() != null && canUseNode(tmp)){
						ClockRegion crTmp = tmp.getTile().getClockRegion();
						if (isClockResource(tmp)){
								if(clkRoute.getIntentCode()==IntentCode.NODE_GLOBAL_HROUTE){
									RouteNode tmpVR = getHDISTRfromVHROUTE(tmp, debug);
									return tmpVR;
								}
								if (crTmp.getRow()==crSink.getRow()){
									if((tmp.getIntentCode()==IntentCode.NODE_GLOBAL_VDISTR||tmp.getIntentCode()==IntentCode.NODE_GLOBAL_VROUTE) && tmp.getTile().getTileTypeEnum()!=TileTypeEnum.XIPHY_L){
										RouteNode tmpVR = getHDISTRfromVHROUTE(tmp, debug);
										if (tmpVR.getIntentCode()==IntentCode.NODE_GLOBAL_HDISTR){
											return tmpVR;
										}
									}else if(tmp.getIntentCode()==IntentCode.NODE_GLOBAL_HDISTR){
										return getHDfromBUF(tmp, debug);
									}
								}
						} 
						if (debug) System.out.println(MessageGenerator.makeWhiteSpace(currNode.getLevel()) + " -> " + tmp + " " + tmp.getIntentCode());
						setClkCostDistance(tmp, currSink);
						visitedNodes.add(tmp);
						clockQueue.add(tmp);
					}
				}// Visited node check
			}// end of wire connections
		}
		throw new RuntimeException("ERROR: We could not reach to a Clock Region from the Clock Route of Net " + currNet.getName());
	}

	public ClockRegion calcClockTreeCentroid(){
		int xStart = dev.getNumOfClockRegionsColumns()+1;
		int xEnd = -1;
		int yStart = dev.getNumOfClockRegionRows()+1;
		int yEnd = -1;
		if(currNet.getPins().size()<3){
			for(SitePinInst currPin : currNet.getPins()){
				if (currPin.isOutPin()) continue;
				avgSink.setTile(currPin.getSiteInst().getTile());
				avgSink.setWire(currPin.getSiteExternalWireIndex());
				return currPin.getTile().getClockRegion();
			}
		} else{
			for(SitePinInst currPin : currNet.getPins()){
				if (currPin.isOutPin()) continue; 
				ClockRegion crTmp = currPin.getTile().getClockRegion();
				if(crTmp.getColumn()>xEnd){
					xEnd = crTmp.getColumn();
				}else if(crTmp.getColumn()<xStart){
					xStart = crTmp.getColumn();
				}
				if(crTmp.getRow()>yEnd){
					yEnd = crTmp.getRow();
				}else if(crTmp.getRow()<yStart){
					yStart = crTmp.getRow();
				}
			}
			// We have the Sink window now, We are finding one of the Sinks in that Region
			int xAvg =(int)(xEnd+xStart)/2;
			int yAvg = (int)(yEnd+yStart)/2;
			for(SitePinInst currPin : currNet.getPins()){
				if (currPin.isOutPin()) continue; 
				ClockRegion crTmp = currPin.getTile().getClockRegion();
				if(crTmp == dev.getClockRegion(yAvg, xAvg)){
					// Populate the current sink node
					avgSink.setTile(currPin.getSiteInst().getTile());
					avgSink.setWire(currPin.getSiteExternalWireIndex());
				}
			}
			return dev.getClockRegion(yAvg, xAvg);
		}
		throw new RuntimeException("ERROR: Could not find Clock Centroid " + currNet.getName());
	}
	
				
	public Wire checkSink(RouteNode myNode){
		for(Wire w : myNode.getConnections()){
			if (w.getTile().equals(currSink.getTile())&&w.getWireIndex()==currSink.getWire()){
				return w;
			}
		}
		return null;
	}
		
	public void setClkCostLevel(RouteNode src){
		src.setCost(src.getLevel());
	}
		
	public void setClkCostDistance(RouteNode src, RouteNode sink){
		int x = Math.abs(sink.getTile().getTileXCoordinate()-src.getTile().getTileXCoordinate());
		int y = Math.abs(sink.getTile().getTileYCoordinate()-src.getTile().getTileYCoordinate());
		src.setCost(x+y);
	}
		
		
	public void printClkNodeInfo(RouteNode myNode, String myString){
		ClockRegion crRegion = myNode.getTile().getClockRegion();
		System.out.println(myString +": "+myNode.toString()+"#"+crRegion.getName());
	}
		
	public void	routeClockTrees(boolean debug, SitePinInst currSource){
		boolean firstSinkToRouteInNet = true;
		RouteNode clkRoot = null;
		RouteNode clkRegion = null;
		if (currNet.getName().contains("mdm_1/U0/Dbg_Clk_31")){
			//return;
			System.out.println("reached to mdm_1/U0/Dbg_Clk_31 clock");
		}
		for(SitePinInst currPin : currNet.getPins()){
			// Ignore the source pin
			if (currPin.isOutPin()) continue;
			pipList = new ArrayList<PIP>();
			visitedNodes = new HashSet<RouteNode>();
			prepareSinkPinsForRouting(currSource, currPin);
			if(firstSinkToRouteInNet){
				addInitialSourceForRouting(currSource);
				firstSinkToRouteInNet = false;
				if(currSources.size()!=1){
					throw new RuntimeException("ERROR: We have more than one Source for the Net " + currNet.getName());
				}
				// get source Node of Net
				RouteNode src = currSources.iterator().next();
				// finds a clock resource track from buffer based on the design
				clkRoot = clkBufToClkRoutes(src, debug);
				visitedNodes.clear();
				if(clkRoot.getIntentCode()==IntentCode.NODE_GLOBAL_HROUTE){
					clkRegion = routeToCentroid(clkRoot, debug);
				} else {
					clkRegion = clkRoot;
				}
				
			}
			else{// If NOT first sink to route
				getSourcesFromPIPs(pipList, currSources/* TODO - prune this list to make it faster*/);
			}
			if(clkRegion == null) {
				// TODO - Farnaz to examine
				if(!supressWarningsErrors) System.out.println("WARNING: Failed to route clock: " + currNet.getName());
				return;
			}
			if(debug) printClkNodeInfo(clkRoot, "clkRoute");
			if(debug) printClkNodeInfo(clkRegion, "clkRegion");
			visitedNodes.clear();
			// Reaching to Sink Clock Region
			RouteNode rowNode = routeCentroidToSinkClkRegion(clkRegion, debug);
			visitedNodes.clear();
			RouteNode colNode = getHDISTRCol(rowNode, debug);
			visitedNodes.clear();
			RouteNode clkSink = clkToSink(colNode, debug);	
			// going backward to set the pips
			if(clkSink!=null){
				RouteNode currPathNode = clkSink;
				if(allowWireOverlap){
					conflictNodes = new HashSet<RouteNode>();
				}
				while(currPathNode.getParent() != null){
					if(debug) System.out.println("CL: " + MessageGenerator.makeWhiteSpace(currPathNode.getLevel()) + currPathNode.toString());
					if(allowWireOverlap){
						if(usedNodes.contains(currPathNode)){
							conflictNodes.add(currPathNode);
						}
					}
					for(Wire w1 : currPathNode.getParent().getTile().getWireConnections(currPathNode.getParent().getWire())){
						if(w1.getWireIndex() == currPathNode.getWire()){
							if(w1.isEndPIPWire() && currPathNode.getParent().getTile().equals(currPathNode.getTile())){
								if (currPathNode.getParent().getWireName().contains("VCC_WIRE")){
									continue;
								}
								// This is a bidirectional wire, we need to reverse the wire0 and wire1 
								// so Vivado can interpret the PIPs correctly
								int wire0 = currPathNode.getParent().getWire();
								int wire1 = currPathNode.getWire();
								if(w1.getPIPType().isBidirectional()){
									List<PIP> pipArray = currPathNode.getTile().getPIPs(currPathNode.getParent().getWire());
									for(PIP tp: pipArray){
										if (tp.getEndWireIndex()==currPathNode.getWire()){
											// TODO - This is a bidirectional PIP, Vivado always prefers 
											// one direction 
										}
									}
								}
								
								PIP tmpPIP = new PIP(currPathNode.getTile(), wire0, wire1, w1.getPIPType());
								if(debug) System.out.println("PIP-"+tmpPIP.getTile()+"/"+tmpPIP.getStartWireName()+"->"+tmpPIP.getTile()+"/"+tmpPIP.getEndWireName());
								pipList.add(tmpPIP);
								break;
							}
						}
					}
					// Update the current node to the parent, this way we can traverse backwards to the source
					currPathNode = currPathNode.getParent();
				}
				if(pathFromSinkToSwitchBox != null && pathFromSinkToSwitchBox.size() > 1){
					RouteNode prev = null;
					for(RouteNode n : pathFromSinkToSwitchBox){
						if(prev != null && prev.getTile().equals(n.getTile())) {
							pipList.add(prev.getTile().getPIP(prev.getWire(), n.getWire()));
						}
						prev = n;
					}						
				}
				// We are now done with the routing of this connection
				successfulRoute = true;
				netPIPs.addAll(pipList);
				currPin.setRouted(true);
			}else {
				throw new RuntimeException("ERROR: Did not reach the sink: "+currSink.getTile()+"/"+currSink.getWireName());
			}
		}// end of sinks
	}
		
	public void routeClockNet(){
		boolean debug = false;
		SitePinInst currSource = currNet.getSource();
		currSources = new HashSet<RouteNode>();
		if(currNet.needsClockNetworkResources()){
			//routeClockTrees(debug, currSource);
			System.out.println("NOTE: Clock net " + currNet.getName() + " was not routed, further developement on clock router needed.");
			return;
		}
		
		boolean firstSinkToRouteInNet = true;
		for(SitePinInst currPin : currNet.getPins()){
			if(currPin.isOutPin()) continue;
			prepareSink(currPin);
			pathFromSinkToSwitchBox = null;
			checkAndAddClockPinSitePIP(currSource,currPin);
			
			if(firstSinkToRouteInNet){
				// just add the original source
				addInitialSourceForRouting(currSource);
			}
			else{
				// Leverage previous routings to offer additional starting points for this route 
				getSourcesFromPIPs(pipList, currSources);
			}
			
			// Route the current clock sink node
			totalConnections++;
			prepareForRoutingConnection();
			route();
			
			totalNodesProcessed += nodesProcessed;
			
			
			// Check if it was a successful routing
			if(successfulRoute){
				// Add these PIPs to the rest used in the net
				netPIPs.addAll(pipList);
				currPin.setRouted(true);
			} 
			else{
				if(ENABLE_RIPUP){
					failedRoutes.add(currPin);					
				}else{
					failedConnections++;
					String switchMatrixString = switchMatrixSink != null ? switchMatrixSink.getTile().getName() + " " + switchMatrixSink.getWireName() : "null";
					MessageGenerator.briefError("\tFAILED TO ROUTE: net: " + currNet.getName() + " inpin: " + currSinkPin.getName() +
	                   " (" + currSink.getTile().getName() + " "+  currSink.getWireName() + " / "+switchMatrixString+") on instance: " + currSinkPin.getSiteInstName());
				}
			}
			firstSinkToRouteInNet = false;
		}
		
	}
	
	public void routeStaticNet(){
		NetType netType = currNet.getType();
		// Assume the net is completely un-routed 
		// For each pin, route backward from the input pin
		for(SitePinInst sink : currNet.getPins()){
			boolean debug = false;
			if(sink.isOutPin()) continue;
			int watchdog = 10000;
			int wire = sink.getSiteInst().getSite().getTileWireIndexFromPinName(sink.getName());
			
			if(wire == -1) {
				throw new RuntimeException("ERROR: Problem while trying to route static sink " + sink);
			}
			Tile t = sink.getTile();
			if(debug) {
				System.out.println("SINK: " + t.getName() + " " + t.getWireName(wire));
			}
			
			Node node = new Node(t,wire);
			RouteNode n = new RouteNode(node.getTile(),node.getWire());
			Queue<RouteNode> q = new LinkedList<RouteNode>();
			visitedNodes = new HashSet<RouteNode>();
			q.add(n);
			boolean success = false;
			while(!q.isEmpty()){
				n = q.poll();
				visitedNodes.add(n);
				if(debug) System.out.println("DEQUEUE:" + n);
				if(success = isThisOurStaticSource(n, netType, debug)) break;
				for(Wire w : n.getBackwardConnections()){
					if(w.isRouteThru()) continue;
					RouteNode nParent = new RouteNode(w.getTile(),w.getWireIndex(), n, n.getLevel()+1);
					if(!pruneNode(nParent)) q.add(nParent);
				}
				watchdog--;
				if(watchdog < 0) {
					break;
				}
			}
			if(!success){
				System.out.println("FAILED to route " + netType + " pin " + sink.toString());
			}else{
				sink.setRouted(true);
			}
			
			currNet.setPIPs(netPIPs);
		}
		
	}
	
	
	private boolean pruneNode(RouteNode routeNode){
		switch (routeNode.getIntentCode()){
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
		if(usedNodes.contains(routeNode)) return true;
		if(visitedNodes.contains(routeNode)) return true;
		return false;
	}
	
	/**
	 * This is a specialized routing function that will only route the pins given.  
	 * It is assumed that each pin provided already belongs to a net.  If any PIPs have 
	 * already been used on the net, they can be leveraged on this route.
	 * @param sitePinInsts The sink pins to be routed.
	 */
	public void routePinsReEntrant(ArrayList<SitePinInst> sitePinInsts, boolean routeUnroutedNets){
		markExistingRouteResourcesUsed();
		reserveCriticalNodes(sitePinInsts);
		
		for(SitePinInst currPin : sitePinInsts){
			successfulRoute = false;
			
			if(currPin.isOutPin()){
				throw new RuntimeException("ERROR: Cannot perform "
					+ "re-entrant routing on a pin that is not a sink: " + currPin.toString());
			}
			if(currPin.getNet() == null){
				throw new RuntimeException("ERROR: Cannot route pin " +
					currPin.toString() + " because it is not part of a net.");
			}
			currNet = currPin.getNet();
			if(currNet.isStaticNet()){
				continue;
			}
			
			SitePinInst currSource = currNet.getSource();
			currSources = new HashSet<RouteNode>();
			netPIPs = new HashSet<PIP>(currNet.getPIPs());
			
			// release some reservedNodes
			ArrayList<RouteNode> rNodes = reservedNodes.remove(currNet);
			if(rNodes != null){
				usedNodes.removeAll(rNodes);
			}
			
			prepareSinkPinsForRouting(currSource, currPin);
			
			if(currNet.getPIPs().size() == 0){
				// just add the original source
				addInitialSourceForRouting(currSource);
			}
			else{
				// Leverage previous routings to offer additional starting points for this route 
				getSourcesFromPIPs(currNet.getPIPs(), currSources);
			}

			if(switchMatrixSink != null && usedNodes.contains(switchMatrixSink)){
				System.err.println("Sink input already used: " +switchMatrixSink);
			}else{
				// Route the current sink node
				totalConnections++;
				routeConnection();
			}

			// Check if it was a successful routing
			if(successfulRoute){
				// Add these PIPs to the rest used in the net
				netPIPs.addAll(pipList);
				currPin.setRouted(true);
			} 
			else{
				if(ENABLE_RIPUP){
					failedRoutes.add(currPin);					
				}else{
					failedConnections++;
					String switchMatrixString = switchMatrixSink != null ? switchMatrixSink.getTile().getName() + " " + switchMatrixSink.getWireName() : "null";
					MessageGenerator.briefError("\tFAILED TO ROUTE: net: " + currNet.getName() + " inpin: " + currSinkPin.getName() +
	                   " (" + currSink.getTile().getName() + " "+  currSink.getWireName() + " / "+switchMatrixString+") on instance: " + currSinkPin.getSiteInstName());
				}
			}

			markAndUpdateNetPIPsAsUsed();
		}
		
		if(routeUnroutedNets){
			// Route any leftover nets with no routing
			for(Net n : design.getNets()){
				boolean needsToBeRouted = n.getPIPs().size() == 0 || n.needsClockNetworkResources();
				if(n.getPins().size() > 1 && n.getSource() != null && needsToBeRouted){
					currNet = n;
					//if(currNet.needsClockNetworkResources()){
						//continue; // TODO
					//}
					// release some reservedNodes
					ArrayList<RouteNode> rNodes = reservedNodes.remove(currNet);
					
					if(rNodes != null){
						usedNodes.removeAll(rNodes);
					}
					
					netPIPs = new HashSet<PIP>();
					
					if(currNet.isClockNet()){
						// Unroute clock nets, release their previously used nodes
						for(PIP p : currNet.getPIPs()){                        
							setWireAsUnused(p.getTile(), p.getStartWireIndex(), currNet);
							setWireAsUnused(p.getTile(), p.getEndWireIndex(), currNet);
						}
						currNet.unroute();
						routeClockNet();
					}else{
						routeNet();
					}
					System.out.println("Routing unrouted net: " + currNet.getName());
					markAndUpdateNetPIPsAsUsed();
				}
			}
			
			// Re-route GND and VCC
			routeStaticNets();
		}
	}
	
	public void routeStaticNets(){
		for(String staticNetName : new String[]{Net.GND_NET, Net.VCC_NET} ){
			Net staticNet = design.getNet(staticNetName); 
			if(staticNet != null && staticNet.getPIPs().size() == 0){
				currNet = staticNet;
				// release some reservedNodes
				ArrayList<RouteNode> rNodes = reservedNodes.remove(currNet);
				if(rNodes != null){
					usedNodes.removeAll(rNodes);
				}
				netPIPs = new HashSet<PIP>(currNet.getPIPs());
				routeStaticNet();	
				markAndUpdateNetPIPsAsUsed();
			}			
		}		
	}
	
	/**
	 * Assumes design is fully placed and that all site nets are routed
	 * but that not all physical nets ({@link Net}) or physical pins 
	 * ({@link SitePinInst}) have been created. TODO - Experimental stage
	 */
	public void elaboratePhysicalNets() {
		Design d = getDesign();
		EDIFNetlist n = d.getNetlist();
		d.getNetlist().resetParentNetMap();
		Map<String,String> parentNetMap = getDesign().getNetlist().getParentNetMap();
		
		// Build a reverse net (Parent Net -> Net Aliases)
		Map<String,HashSet<String>> reverseNetMap = new HashMap<>();
		for(Entry<String,String> e : parentNetMap.entrySet()){
			HashSet<String> aliases = reverseNetMap.get(e.getValue());
			if(aliases == null) {
				aliases = new HashSet<>();
				reverseNetMap.put(e.getValue(), aliases);
			}
			aliases.add(e.getKey());
		}
		
		// For each aliased set of nets, find all primitive cell pins and ensure
		// SitePinInsts exist on the net
		for(Entry<String,HashSet<String>> e : reverseNetMap.entrySet()) {
			Net parentNet = d.getNet(e.getKey());
			if(parentNet == null) {
				EDIFHierNet logicalNet = n.getHierNetFromName(e.getKey());
				parentNet = new Net(logicalNet);
			}
			for(String alias : e.getValue()) {
				EDIFHierNet aliasNet = n.getHierNetFromName(alias);
				if(aliasNet == null) continue; // TODO - handle transformed prims
				for(EDIFPortInst p : aliasNet.getNet().getPortInsts()) {
					if(p.getCellInst() == null) continue; // Top-level/hier port
					if(p.getCellInst().getCellType().isPrimitive()) {
						// Create/ensure SitePinInst 
						String cellName = aliasNet.getHierarchicalInstName(p);
						Cell c = d.getCell(cellName);
						if(c == null) continue; // TODO - Figure out why...
						String sitePinName = c.getCorrespondingSitePinName(p.getName());
						if(sitePinName == null) continue; //TODO - failed to figure out site pin
						SitePinInst spi = c.getSiteInst().getSitePinInst(sitePinName);
						if(spi == null) {
							spi = parentNet.createPin(p.isOutput(), sitePinName, c.getSiteInst());
						}
					}
				}
			}
			
		}
		
	}
	
	
	/**
	 * This the central method for routing the design in this class.  This prepares
	 * the nets for routing.
	 * @return The final routed design.
	 */
	public Design routeDesign(){
		identifyMissingPins();
		reserveCriticalNodes();
		markExistingRouteResourcesUsed();
		
		// Start Routing
		for (Net nn : design.getNets()){
			currNet = nn;

			// Ignore nets with no pins
			if(currNet.getPins().size() == 0){
				continue;
			}
			
			// Consider all nets as fully routed except static nets (TODO - add support to analyze all nets)
			if(currNet.getPIPs().size() > 0) continue;
		
			if(currNet.getSource() == null && !currNet.isStaticNet()){
				if(!supressWarningsErrors) MessageGenerator.briefError("WARNING: " + currNet.getName() + " does not have a source pin associated with it.");
				continue;
			}
			
			// release some reservedNodes
			ArrayList<RouteNode> rNodes = reservedNodes.remove(currNet);
			
			if(rNodes != null){
				usedNodes.removeAll(rNodes);
			}
			
			// netPIPs are the pips that belong to a particular net
			netPIPs = new HashSet<PIP>(currNet.getPIPs());
			long start = System.currentTimeMillis();
			if(currNet.isStaticNet()){
				routeStaticNet();
			}else if(currNet.isClockNet()){
				routeClockNet();
			}else{
				routeNet();
			}
			
			if(netPIPs.size() == 0 && rNodes != null){
				usedNodes.addAll(rNodes);
				reservedNodes.put(nn, rNodes);
			}
			
			long stop = System.currentTimeMillis();
			//if(stop-start > 100) System.out.println((stop-start) + "ms : " + currNet.getName());
			
			markAndUpdateNetPIPsAsUsed();
		}

		// Resolve congestion issues
		for(SitePinInst sink : failedRoutes){
			currNet = sink.getNet();
			SitePinInst currSource = currNet.getSource();
			currSources = new HashSet<RouteNode>();
			prepareSinkPinsForRouting(currSource, sink);
			
			// Prepare starting points for the route
			if(currNet.getPIPs().size() == 0){
				// just add the original source
				addInitialSourceForRouting(currSource);
			}
			else{
				// Leverage previous routings to offer additional starting points for this route 
				getSourcesFromPIPs(pipList, currSources/* TODO - prone this list to make it faster pipList*/);
			}

			// Route the current sink node without regard for already used resources
			totalConnections++;
			allowWireOverlap = true;
			routeConnection();
			allowWireOverlap = false;

			if(successfulRoute){
				HashSet<Net> netsToRipUpAndReroute = new HashSet<Net>();
				// Accumulate nets which are sharing resources
				for(RouteNode conflictNode : conflictNodes){
					netsToRipUpAndReroute.addAll(usedNodesMap.get(conflictNode));
				}
				
				// Rip up nets using conflicting resources
				for(Net net : netsToRipUpAndReroute){
					for(PIP p : net.getPIPs()){
						setWireAsUnused(p.getTile(), p.getStartWireIndex(), net);
						setWireAsUnused(p.getTile(), p.getEndWireIndex(), net);
					}
					net.unroute();
				}
				
				// Accept routing of current net
				netPIPs.addAll(pipList);
				markAndUpdateNetPIPsAsUsed();
				
				// Re-route ripped-up nets
				for(Net net : netsToRipUpAndReroute){
					currNet = net;
					ENABLE_RIPUP = false;
					routeNet();
					ENABLE_RIPUP = true;
				}

			}else{
				failedConnections++;
				String switchMatrixString = switchMatrixSink != null ? switchMatrixSink.getTile().getName() + " " + switchMatrixSink.getWireName() : "null";
				MessageGenerator.briefError("\tFAILED TO ROUTE: net: " + currNet.getName() + " inpin: " + currSinkPin.getName() +
                   " (" + currSink.getTile().getName() + " "+  currSink.getWireName() + " / "+switchMatrixString+") on instance: " + currSinkPin.getSiteInstName());				
				continue;
			}
		}
		return design;
	}	
	
	protected static void printTimeHelper(String timedOperation, long start) {
		System.out.printf("%s %8.3fs\n", timedOperation,
				(System.nanoTime() - start) / 1000000000.0);
	}
	
	private static HashSet<String> ignoreInputs;
	
	static {
		ignoreInputs = new HashSet<String>();
		ignoreInputs.add("CIN");
		for(int i=0; i < 16; i++){
			ignoreInputs.add("CLK_IN" + i);
			ignoreInputs.add("CE_INT" + i);
		}
	}
	
	/**
	 * Attempts to find a switch box wire that will drive the site pin provided.
	 * @param p The pin to start from
	 * @return A wire (tile and wire) of a switch box that can drive the site pin, or null
	 * if none could be found.
	 */
	public static ArrayList<RouteNode> findInputPinFeed(SitePinInst p){
		Site site = p.getSiteInst().getSite();
		String pinName = p.getName();
		Tile t = site.getTile();
		if(site.isOutputPin(pinName)) return null;
		if(ignoreInputs.contains(pinName)) return null;
		int watchdog = 1000;
		int wire = site.getTileWireIndexFromPinName(pinName);
		if(wire == -1) return null; // Pin is not connected to anything (unbonded IO)
		Node node = new Node(t,wire);
		RouteNode n = new RouteNode(node.getTile(),node.getWire());
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		q.add(n);
		while(!q.isEmpty()){
			n = q.poll(); 
			if(isSwitchBox(n.getTile())){
				ArrayList<RouteNode> path = new ArrayList<RouteNode>();
				while(n != null){
					path.add(n);
					n = n.getParent();
				}
				return path;
			}
			for(PIP pip : n.getBackwardPIPs()){
				Wire tmp = new Wire(n.getTile(),pip.getStartWireIndex());
				RouteNode newNode = new RouteNode(tmp.getTile(),tmp.getWireIndex(),n,n.getLevel()+1); 
				q.add(newNode);
				Wire nodeHead = tmp.getStartWire();
				if(!nodeHead.equals(tmp)){
					q.add(new RouteNode(nodeHead.getTile(),nodeHead.getWireIndex(),newNode,newNode.getLevel()+1));
				}
				
			}
			watchdog--;
			if(watchdog < 0) break;
		}
		return null;
	}
	
	public static void main(String[] args) {
		if(args.length != 2){
			System.out.println("USAGE: <input.dcp> <output.dcp>");
		}
		CodePerfTracker t = new CodePerfTracker("Router", true);
		Router r = new Router(Design.readCheckpoint(args[0],t));
		t.start("Route Design");
		r.routeDesign();
		t.stop();
		r.getDesign().writeCheckpoint(args[1],t);
	}
}
