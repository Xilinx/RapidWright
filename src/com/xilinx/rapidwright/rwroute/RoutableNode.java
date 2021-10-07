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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;

/**
 * A RoutableNode Object, denoted as rnode, is a vertex of the routing resource graph.
 * It implements {@link Routable} and is created based on a {@link Node} Object.
 */
public class RoutableNode implements Routable{
	/** A unique index of a rnode */
	private int index;
	/** The associated {@link Node} instance */
	private Node node;
	/** The type of a rnode*/
	private RoutableType type;
	/** The tileXCoordinate and tileYCoordinate of the INT tile that the associated node stops at */
	private short endTileXCoordinate;
	private short endTileYCoordinate;
	/** The wirelength of a rnode */
	private short length;
	/** The base cost of a rnode */
	private float baseCost;
	/** The delay of this rnode computed based on the timing model */
	private short delay;
	/** A flag to indicate if this rnode is the target */
	private boolean target;
	/** The children (downhill rnodes) of this rnode */
	private List<Routable> children;
	
	/** Present congestion cost */
	private float presentCongesCost;
	/** Historical congestion cost */
	private float historicalCongesCost;
	/** Upstream path cost */
	private float upstreamPathCost;
	/** Lower bound of the total path cost */
	private float lowerBoundTotalPathCost;
	/** A flag to indicate if the rnode has been visited or not during the expansion */
	private boolean visited;
	/** A variable that stores the parent of a rnode during expansion to facilitate tracing back */
	private Routable prev;
	/**
	 * A map that records users of a rnode based on all routed connections.
	 * Each user is a {@link Net} instance represented by its source.
	 * It is often the case that multiple connections of the net are using a same rnode.
	 * We count the number of connections from a net.
	 * The number is used for the sharing mechanism of RWRoute.
	 */
	private Map<SitePinInst, Integer> usersConnectionCounts;
	/**
	 * A map that records all the driver rnodes of a rnode based on all routed connections.
	 * It is possible that a rnode are driven by different rnodes after routing of all connections of a net.
	 * We count the drivers of a rnode to facilitate the route fixer at the end of routing.
	 */
	private Map<Routable, Integer> driversCounts;
	
	/** Static variable to indicate if the routing is timing-driven */
	static boolean timingDriven;
	/** The instantiated delayEstimator to compute delays */
	static DelayEstimatorBase delayEstimator;
	/** A flag to indicate if the routing resource exclusion should disable exclusion of nodes cross RCLK */
	static boolean maskNodesCrossRCLK;
	
	public static void setTimingDriven(boolean isTimingDriven, DelayEstimatorBase estimator) {
		timingDriven = isTimingDriven;
		if(timingDriven) {
			delayEstimator = estimator;
		}
	}
	
	public static void setMaskNodesCrossRCLK(boolean mask) {
		maskNodesCrossRCLK = mask;
	}
	
	public RoutableNode(int index, Node node, RoutableType type){
		this.index = index;
		this.type = type;
		this.node = node;
		this.children = null;
		this.target = false;
		this.setEndTileXYCoordinates();
		this.setBaseCost();
		this.presentCongesCost = 1;
    	this.historicalCongesCost = 1;
    	this.setVisited(false);
		this.usersConnectionCounts = null;
		this.driversCounts = null;
		this.prev = null;
		if(timingDriven){
			this.setDelay(RouterHelper.computeNodeDelay(delayEstimator, node));
		}
	}
	
	public int setChildren(int globalIndex, Map<Node, Routable> createdRoutable, Set<Node> reserved, RouteThruHelper routethruHelper){
		this.children = new ArrayList<>();
		List<Node> allDownHillNodes = this.node.getAllDownhillNodes();
		
		for(Node node:allDownHillNodes){		
			if(reserved.contains(node)) continue;		
			if(isExcluded(node, timingDriven)) continue;
			if(routethruHelper.isRouteThru(this.node, node)) continue;
			
			Routable child = createdRoutable.get(node);
			if(child == null) {
				RoutableType type = RoutableType.WIRE;		
				child = new RoutableNode(globalIndex++, node, type);
				createdRoutable.put(node, child);
			}
			this.children.add(child);//the sink rnode of a target connection has been created up-front
		}
		return globalIndex;
	}
	
	public void setBaseCost(){
		if(this.type == RoutableType.WIRE){
			baseCost = 0.4f;
			// NOTE: IntentCode is device-dependent
			IntentCode ic = node.getIntentCode();
			switch(ic) {
			case NODE_PINBOUNCE:
				type = RoutableType.PINBOUNCE;
				break;
			case NODE_PINFEED:
				type = RoutableType.PINFEED_I;
				break;
			
			case NODE_DOUBLE:
				if(this.endTileXCoordinate != this.getNode().getTile().getTileXCoordinate()) {
					baseCost = 0.4f*this.length;
				}
				break;
			case NODE_HQUAD:
				baseCost = 0.35f*this.length;
				break;
			case NODE_VQUAD:
				baseCost = 0.15f*this.length;// VQUADs have length 4 and 5
				break;
			case NODE_HLONG:
				baseCost = 0.15f*this.length;// HLONGs have length 6 and 7
				break;
			case NODE_VLONG:
				baseCost = 0.7f;
				break;	
			default:
				if(this.length != 0) baseCost *= this.length;
				type = RoutableType.WIRE;
				break;
			}	
		}else if(this.type == RoutableType.PINFEED_I){
			baseCost = 0.4f;
		}else if(this.type == RoutableType.PINFEED_O){
			baseCost = 1f;
		}
	}

	@Override
	public boolean isOverUsed() {
		return Routable.capacity < this.getOccupancy();
	}
	
	@Override
	public boolean isUsed(){
		return this.getOccupancy() > 0;
	}
	
	@Override
	public boolean hasMultiDrivers(){
		return Routable.capacity < this.uniqueDriverCount();
	}

	@Override
	public void setEndTileXYCoordinates() {
		Wire[] wires = this.node.getAllWiresInNode();
		List<Tile> intTiles = new ArrayList<>();
		for(Wire w : wires) {
			if(w.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				intTiles.add(w.getTile());
			}
		}
		Tile endTile = null;
		if(intTiles.size() > 1) {
			endTile = intTiles.get(1);
		}else if(intTiles.size() == 1) {
			endTile = intTiles.get(0);
		}else {
			endTile = this.getNode().getTile();
		}
		this.endTileXCoordinate = (short) endTile.getTileXCoordinate();
		this.endTileYCoordinate = (short) endTile.getTileYCoordinate();
		Tile base = this.getNode().getTile();
		this.length = (short) (Math.abs(this.endTileXCoordinate - base.getTileXCoordinate()) 
				+ Math.abs(this.endTileYCoordinate - base.getTileYCoordinate()));
	}
	
	@Override
	public void updatePresentCongesCost(float pres_fac) {
		int occ = this.getOccupancy();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			this.setPresentCongesCost(1);
		} else {
			this.setPresentCongesCost(1 + (occ - cap + 1) * pres_fac);
		}
	}
	
	@Override
	public String toString(){
		String coordinate = "";	
		coordinate = "(" + this.endTileXCoordinate + "," + this.endTileYCoordinate + ")";
		StringBuilder s = new StringBuilder();
		s.append("id = " + this.index);
		s.append(", ");
		s.append("node " + this.node.toString());
		s.append(", ");
		s.append(coordinate);
		s.append(", ");
		s.append(String.format("type = %s", this.type));
		s.append(", ");
		s.append(String.format("ic = %s", this.getNode().getIntentCode()));
		s.append(", ");
		s.append(String.format("dly = %d", this.delay));
		s.append(", ");
		s.append(String.format("user = %s", this.getOccupancy()));
		s.append(", ");
		s.append(this.getUsersConnectionCounts());
		
		return s.toString();
	}
	
	@Override
	public int hashCode(){
		return this.node.hashCode();
	}
	
	@Override
	public int getIndex() {
		return this.index;
	}
	
	@Override
	public boolean isInConnectionBoundingBox(Connection connection) {		
		return this.endTileXCoordinate > connection.getXMinBB() && this.endTileXCoordinate < connection.getXMaxBB() && this.endTileYCoordinate > connection.getYMinBB() && this.endTileYCoordinate < connection.getYMaxBB();
	}
	
	@Override
	public Node getNode() {
		return this.node;
	}

	@Override
	public boolean isTarget() {
		return this.target;
	}

	@Override
	public void setTarget(boolean isTarget) {
		this.target = isTarget;	
	}

	@Override
	public RoutableType getRoutableType() {
		return this.type;
	}

	@Override
	public float getDelay() {
		return this.delay;
	}
	@Override
	public short getEndTileXCoordinate() {
		return this.endTileXCoordinate;
	}

	@Override
	public void setEndTileXCoordinate(short endTileXCoordinate) {
		this.endTileXCoordinate = endTileXCoordinate;
	}

	@Override
	public void setEndTileYCoordinate(short endTileYCoordinate) {
		this.endTileYCoordinate = endTileYCoordinate;
	}

	@Override
	public short getEndTileYCoordinate() {
		return this.endTileYCoordinate;
	}
	
	@Override
	public float getBaseCost() {
		return this.baseCost;
	}

	public boolean childrenNotSet() {
		return this.children == null;
	}

	@Override
	public List<Routable> getChildren() {
		return this.children;
	}

	@Override
	public void setDelay(short delay) {
		this.delay = delay;
	}

	@Override
	public int manhattanDistToSink(Routable sink) {
		return Math.abs(this.getEndTileXCoordinate() - sink.getEndTileXCoordinate()) + Math.abs(this.getEndTileYCoordinate() - sink.getEndTileYCoordinate());
	}

	@Override
	public void setRoutableType(RoutableType type) {
		this.type = type;
	}

	@Override
	public short getLength() {
		return this.length;
	}
	
	@Override
	public void setLowerBoundTotalPathCost(float totalPathCost) {
		this.lowerBoundTotalPathCost = totalPathCost;
		this.setVisited(true);
	}
	
	@Override
	public void setUpstreamPathCost(float newPartialPathCost) {
		this.upstreamPathCost = newPartialPathCost;
	}
	
	@Override
	public float getLowerBoundTotalPathCost() {
		return this.lowerBoundTotalPathCost;
	}
	
	@Override
	public float getUpstreamPathCost() {
		return this.upstreamPathCost;
	}

	@Override
	public Map<SitePinInst, Integer> getUsersConnectionCounts() {
		return usersConnectionCounts;
	}
	
	@Override
	public void incrementUser(SitePinInst source) {
		if(this.usersConnectionCounts == null) {
			this.usersConnectionCounts = new HashMap<>();
		}
		Integer connectionCount = this.usersConnectionCounts.getOrDefault(source, 0);
		this.usersConnectionCounts.put(source, connectionCount + 1);
	}
	
	@Override
	public int uniqueUserCount() {
		if(this.usersConnectionCounts == null) {
			return 0;
		}
		return this.usersConnectionCounts.size();
	}
	
	@Override
	public void decrementUser(SitePinInst source) {
		Integer count = this.usersConnectionCounts.getOrDefault(source, 0);
		if(count == 1) {
			this.usersConnectionCounts.remove(source);
		}else if(count > 1) {
			this.usersConnectionCounts.put(source, count - 1);
		}
	}
	
	@Override
	public int countConnectionsOfUser(SitePinInst source) {
		if(this.usersConnectionCounts == null) {
			return 0;
		}
		return this.usersConnectionCounts.getOrDefault(source, 0);
	}
	
	@Override
	public int uniqueDriverCount() {
		if(this.driversCounts == null) {
			return 0;
		}
		return this.driversCounts.size();
	}
	
	@Override
	public void incrementDriver(Routable parent) {
		if(this.driversCounts == null) {
			this.driversCounts = new HashMap<>();
		}
		Integer drivers = this.driversCounts.getOrDefault(parent, 0);
		this.driversCounts.put(parent, drivers + 1);
	}
	
	@Override
	public void decrementDriver(Routable parent) {
		Integer count = this.driversCounts.getOrDefault(parent, 0);
		if(count == 1) {
			this.driversCounts.remove(parent);
		}else if(count > 1) {
			this.driversCounts.put(parent, count - 1);
		}
	}
	
	@Override
	public int getOccupancy() {
		return this.uniqueUserCount();
	}
	
	@Override
	public Routable getPrev() {
		return prev;
	}

	@Override
	public void setPrev(Routable prev) {
		this.prev = prev;
	}
	
	@Override
	public float getPresentCongesCost() {
		return presentCongesCost;
	}

	@Override
	public void setPresentCongesCost(float presentCongesCost) {
		this.presentCongesCost = presentCongesCost;
	}

	@Override
	public float getHistoricalCongesCost() {
		return historicalCongesCost;
	}

	@Override
	public void setHistoricalCongesCost(float historicalCongesCost) {
		this.historicalCongesCost = historicalCongesCost;
	}

	@Override
	public boolean isVisited() {
		return visited;
	}

	@Override
	public void setVisited(boolean visited) {
		this.visited = visited;
	}
	
	/**
	 * Checks if a node is an exit node of a NodeGroup
	 * @param node The node in question
	 * @return true, if the node is a S/D/Q/L node or a local node with a GLOBA and CTRL wire 
	 */
	public static boolean isExitNode(Node node) {
		switch(node.getIntentCode()) {
			case NODE_SINGLE:
			case NODE_DOUBLE:
			case NODE_HQUAD:
			case NODE_VQUAD:
			case NODE_VLONG:
			case NODE_HLONG:
			case NODE_PINBOUNCE:
			case NODE_PINFEED:
				return true;
			case NODE_LOCAL:
				if(node.getWireName().contains("GLOBAL") || node.getWireName().contains("CTRL")) {
					return true;
				}
			default:
		}
		return false;
	}
	
	/**
	 * Checks if some routing resources are prevented from being used.
	 * @param node The routing resource in question.
	 * @param timingDriven To indicate if it targets timing-driven routing.
	 * @return true, if the node should be excluded from the routing resource graph.
	 */
	public static boolean isExcluded(Node node, boolean timingDriven) {
		Tile tile = node.getTile();
    	if(tile.getTileTypeEnum() == TileTypeEnum.INT) {
	        if(timingDriven && maskNodesCrossRCLK) {
	        	int y = tile.getTileYCoordinate();
		        if ((y-30)%60 == 0) { // above RCLK
		        	return excludeAboveRclk.contains(node.getWireName());
		        } else if ((y-29)%60 == 0) { // below RCLK
		        	return excludeBelowRclk.contains(node.getWireName());
		        }
	        }
	        return false;
        }else {
        	if(tile.getName().startsWith("LAG")) {
        		return false;
        	}
        	return true;
        }
	}
	 
	private static Set<String> excludeAboveRclk;
	private static Set<String> excludeBelowRclk;
	static {
	        // these nodes are bleeding down
		excludeAboveRclk = new HashSet<String>() {{
			add("SDQNODE_E_0_FT1");
			add("SDQNODE_E_2_FT1");
			add("SDQNODE_W_0_FT1");
			add("SDQNODE_W_2_FT1");
			add("EE12_BEG0");
			add("WW2_E_BEG0");
			add("WW2_W_BEG0");
	     }};
	        // these nodes are bleeding up
	     excludeBelowRclk = new HashSet<String>() {{
	    	 add("SDQNODE_E_91_FT0");
	    	 add("SDQNODE_E_93_FT0");
	    	 add("SDQNODE_E_95_FT0");
	    	 add("SDQNODE_W_91_FT0");
	    	 add("SDQNODE_W_93_FT0");
	    	 add("SDQNODE_W_95_FT0");
	    	 add("EE12_BEG7");
	    	 add("WW1_W_BEG7");
	     }};
	}
}
