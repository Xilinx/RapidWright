/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;


/**
 * A common class to serve as the place for common router-related methods.
 * @author clavin
 *
 */
public abstract class AbstractRouter{

	/** The input design to route */
	protected Design design;
	/** This is the device database */
	protected Device dev;
	/** This keeps track of all the used nodes in the chip during routing */
	protected HashSet<RouteNode> usedNodes;
	/** Keeps track for each used node by which net it is used by */
	protected HashMap<RouteNode,LinkedList<Net>> usedNodesMap; // TODO - Does this really need to have multiple values, resources can't be used by multiple nets
	/** This keeps track of all the visited nodes in the chip during routing */
	protected HashSet<RouteNode> visitedNodes;
	/** This keeps track of Clock resource number that is used during routing */
	protected HashSet<Integer> usedClkResources;
	/** A Priority Queue for nodes to be processed */
	protected PriorityQueue<RouteNode> queue;
	/** Some nodes are reserved for particular routes to minimize routing conflicts later */
	protected HashMap<Net,ArrayList<RouteNode>> reservedNodes;

	/** PIPs that are part of the most recently routed connection */
	protected ArrayList<PIP> pipList;
	
	/** Keeps track of all current sources for a given net (to avoid the RUG CREATION PROBLEM) */
	protected HashSet<RouteNode> currSources;
	/** Current sink node to be routed */
	protected RouteNode currSink;
	/** Average sink node for clock routing*/
	protected RouteNode avgSink;
	/** Current net to be routed */
	protected Net currNet;
	/** Current sink pin to be routed */ 
	protected SitePinInst currSinkPin;
	/** PIPs of the current net being routed */
	protected HashSet<PIP> netPIPs;
	
	protected RouteNode tempNode;

	protected boolean foundSwitchMatrixSink = false;

	protected RouteNode switchMatrixSink = null;
	
	/** A flag indicating if the current connection was routed successfully */
	protected boolean successfulRoute;
	/** A flag which determines if the current sink is a clock wire */
	protected boolean isCurrSinkAClkWire;
	
	// Statistic variables
	/** Total number of connections in design */
	protected int totalConnections;
	/** Counts the total number of nodes that were examined in routing */
	protected int totalNodesProcessed;
	/** Counts number of nodes processed during a route */
	protected int nodesProcessed;
	/** Counts the number of times the router failed to route a connection */
	protected int failedConnections;
	
	public AbstractRouter() {
		// Initialize variables
		tempNode = new RouteNode();
		usedNodes = new HashSet<RouteNode>();
		usedClkResources = new HashSet<Integer>();
		usedNodesMap = new HashMap<RouteNode, LinkedList<Net>>();
		reservedNodes = new HashMap<Net, ArrayList<RouteNode>>();
		// Create a compare function based on node's cost
		queue = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});

		totalConnections = 0;
		totalNodesProcessed = 0;
		nodesProcessed = 0;
		failedConnections = 0;
		currSink = new RouteNode();
		avgSink = new RouteNode();
	}
	
	public Design getDesign(){
		return design;
	}
	
	/**
	 * Sets a node (combined tile and wire) as used and maps 
	 * the usage to the given net.
	 * @param t The tile specifier for the node to be marked as used.
	 * @param wire The wire specifier for the node to be marked as used.
	 * @param net The net using the node.
	 * @return The node that was set as used.
	 */
	protected RouteNode setWireAsUsed(Tile t, int wire, Net net){
		RouteNode n = new RouteNode(t, wire, null, 0);
		markNodeUsed(n);
		addUsedWireMapping(net, n);	
		return n;
	}
	
	/**
	 * Sets a node (combined tile and wire) as unused and unmaps 
	 * the usage to the given net.
	 * @param t The tile specifier for the node to be marked as unused.
	 * @param wire The wire specifier for the node to be marked as unused.
	 * @param net The net currently using the node.
	 * @return The node that was set as unused.
	 */
	protected RouteNode setWireAsUnused(Tile t, int wire, Net net){
		RouteNode n = new RouteNode(t, wire, null, 0);
		usedNodes.remove(n);
		removeUsedWireMapping(net, n);		
		return n;
	}
	
	/**
	 * This method allows a router to keep track of which nets use which
	 * nodes.
	 * @param net The net using node n.
	 * @param n The node used by the given net
	 */
	protected void addUsedWireMapping(Net net, RouteNode n){
		LinkedList<Net> list = usedNodesMap.get(n);
		if(list == null){ 
			list = new LinkedList<Net>();
			usedNodesMap.put(n, list);
		}
		if(!list.contains(net)){ 
			list.add(net);
		}
	}
	
	/**
	 * This method removes a node usage mapping to a net when it is being
	 * marked as unused.
	 * @param net The net currently using the node.
	 * @param n The node to be removed.
	 */
	protected void removeUsedWireMapping(Net net, RouteNode n){
		LinkedList<Net> list = usedNodesMap.get(n);
		if(list == null){ 
			return; 
		}
		if(list.remove(net)){
			if(list.isEmpty()){
				usedNodesMap.remove(n);
			}
		}
	}
	
	/**
	 * @return the reserved Nodes Map
	 */
	public HashMap<Net, ArrayList<RouteNode>> getReservedNodes() {
		return reservedNodes;
	}

	/**
	 * Gets are returns a list of reserved nodes for the provide net.
	 * @param net The net to get reserved nodes for.
	 * @return A list of reserved nodes for the net, or null if no 
	 * nodes are reserved.
	 */
	public ArrayList<RouteNode> getReservedNodesForNet(Net net){
		return reservedNodes.get(net);
	}
	
	public boolean isNodeUsed(Tile tile, int wire){
		tempNode.setTileAndWire(tile, wire);
		return usedNodes.contains(tempNode);
	}
	
	public boolean isNodeUsed(RouteNode routeNode){
		return usedNodes.contains(routeNode);
	}
	
	/**
	 * Examines the pips in the list and marks all of the resources
	 * as used.
	 * @param pips The PIPs to mark as used.
	 */
	public void markPIPsAsUsed(ArrayList<PIP> pips){
		for (PIP pip : pips){
			setWireAsUsed(pip.getTile(), pip.getStartWireIndex(), currNet);
			setWireAsUsed(pip.getTile(), pip.getEndWireIndex(), currNet);
			markIntermediateNodesAsUsed(pip, currNet);
		}
	}
	
	/**
     * Creates sources from a list of PIPs
	 * @param pips The pips of the net to examine.
	 * @return The list of sources gathered from the pips list.
	 */
	public ArrayList<RouteNode> getSourcesFromPIPs(ArrayList<PIP> pips){
		ArrayList<RouteNode> sources = new ArrayList<RouteNode>(pips.size()*2);
		for(PIP pip : pips){
			sources.add(new RouteNode(pip.getTile(), pip.getStartWireIndex(), null, 0));
			sources.add(new RouteNode(pip.getTile(), pip.getEndWireIndex(), null, 0));
		}
		return sources;
	}
	
	public void markNodeUsed(RouteNode n){
		usedNodes.add(n);
	}
	
	/**
	 * This will add the sole source of the net to the set of sources to be used by the router.
	 * It also updates the Router's currSources with the sole source of the net.
	 * @param sources The set of sources for this net to be used for routing
	 * @param currSource The source pin of the net
	 */
	public void addInitialSourceForRouting(SitePinInst currSource){
		RouteNode n = new RouteNode(currSource.getSiteInst().getTile(), 
				currSource.getSiteExternalWireIndex(), null, 0);
		currSources.add(n);
	}
	
	protected void prepareSink(SitePinInst currPin){
		currSinkPin = currPin;
		
		// Populate the current sink node
		currSink.setTileAndWire(currSinkPin.getSiteInst().getTile(),currSinkPin.getSiteExternalWireIndex());	
	}
	
	protected void prepareForRoutingConnection(){
		// Reset Variable for a new route
		pipList = new ArrayList<PIP>();
		visitedNodes = new HashSet<RouteNode>();
		queue.clear();
		nodesProcessed = 0;
		successfulRoute = false;
		foundSwitchMatrixSink = false;
		// Setup the source nodes for starting the routing process
		for(RouteNode src : currSources){
			// Add the source nodes to the queue
			if(src.getConnections() != null){
				// Set the cost of the source
				setCost(src, false);
				this.queue.add(src);
			}
		}
	}
	
	/**
	 * Cost function, used to set each node's cost to be prioritized by the queue 
	 * @param routeNode The node to calculate and set its cost based on currSink.
	 */
	public void setCost(RouteNode routeNode, boolean isRouteThrough){
		// Calculate Manhattan distance between node and sink
		int x;
		int y;
		if(switchMatrixSink == null || foundSwitchMatrixSink){
			x = currSink.getTile().getTileXCoordinate() - routeNode.getTile().getTileXCoordinate();
			y = currSink.getTile().getTileYCoordinate() - routeNode.getTile().getTileYCoordinate();
		}else{
			x = switchMatrixSink.getTile().getTileXCoordinate() - routeNode.getTile().getTileXCoordinate();
			y = switchMatrixSink.getTile().getTileYCoordinate() - routeNode.getTile().getTileYCoordinate();			
		}
		
		// ABS
		if(x < 0) x = -x;
		if(y < 0) y = -y;

		routeNode.setCost(((x + y) << 1) + routeNode.getLevel() + routeNode.getHistory());
		
		// Favor clock wires when routing the clock tree
		if(isCurrSinkAClkWire && routeNode.getWireName().contains("CLK") && !isRouteThrough){
			//if(switchMatrixSink != null && !node.getTile().equals(switchMatrixSink.getTile())){
			if(switchMatrixSink != null && (routeNode.getTile().getTileTypeEnum().equals(TileTypeEnum.INT) && !routeNode.getTile().equals(switchMatrixSink.getTile()))){
				routeNode.setCost(routeNode.getCost()+1000);
			}
			routeNode.setCost(routeNode.getCost()-1000);
			if(routeNode.getWireName().contains("GCLK") && switchMatrixSink != null &&  routeNode.getTile().equals(switchMatrixSink.getTile())){
				queue.clear();
			}
		}
		
		if(routeNode.equals(switchMatrixSink)){
			foundSwitchMatrixSink = true;
			queue.clear();
			routeNode.setCost(routeNode.getCost() - 1100);
			routeNode.setLevel(-15);
		}
	}

	
	/**
	 * Checks each node in a PIP to see if there are other nodes that should be
	 * marked as used. These are wires external to a tile such as
	 * doubles/pents/hexes/longlines.
	 * @param pip The pip to check intermediate used nodes for
	 * @param currentNet The net to associate with the intermediate nodes, null if 
	 * the usedNodesMap should not be updated
	 */
	protected void markIntermediateNodesAsUsed(PIP pip, Net currentNet){
		List<Wire> wires = pip.getTile().getWireConnections(pip.getEndWireIndex());
		if(wires != null && wires.size() > 1){
			for(Wire w : wires){
				if(!w.getTile().equals(pip.getTile())){
					RouteNode tmp = setWireAsUsed(w.getTile(), w.getWireIndex(), currentNet);
					if(currentNet != null) addUsedWireMapping(currentNet, tmp);
				}
			}
		}
		
		if(IntentCode.isLongWire(pip.getTile(), pip.getStartWireIndex()) && IntentCode.isLongWire(pip.getTile(), pip.getEndWireIndex())){
			wires = pip.getTile().getWireConnections(pip.getStartWireIndex());
			if(wires != null && wires.size() > 1){
				for(Wire w : wires){
					if(!w.getTile().equals(pip.getTile())){
						RouteNode tmp = setWireAsUsed(w.getTile(), w.getWireIndex(), currentNet);
						if(currentNet != null) addUsedWireMapping(currentNet, tmp);
					}
				}
			}
		}
	}

	public static boolean isClkPin(SitePinInst sinkPin){
		return sinkPin.getName().contains("CLK");
	}
	
}
