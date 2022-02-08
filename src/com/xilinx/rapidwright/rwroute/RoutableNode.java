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
	private float presentCongestionCost;
	/** Historical congestion cost */
	private float historicalCongestionCost;
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
	 * Each user is a {@link NetWrapper} instance that corresponds to a {@link Net} instance.
	 * It is often the case that multiple connections of the user are using a same rnode.
	 * We count the number of connections from the net.
	 * The number is used for the sharing mechanism of RWRoute.
	 */
	private Map<NetWrapper, Integer> usersConnectionCounts;
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
		children = null;
		target = false;
		setEndTileXYCoordinates();
		setBaseCost();
		presentCongestionCost = 1;
    	historicalCongestionCost = 1;
    	setVisited(false);
		usersConnectionCounts = null;
		driversCounts = null;
		prev = null;
		if(timingDriven){
			setDelay(RouterHelper.computeNodeDelay(delayEstimator, node));
		}
	}
	
	public int setChildren(int globalIndex, Map<Node, Routable> createdRoutable, Set<Node> reserved, RouteThruHelper routethruHelper){
		children = new ArrayList<>();
		List<Node> allDownHillNodes = node.getAllDownhillNodes();
		
		for(Node node:allDownHillNodes){		
			if(reserved.contains(node)) continue;		
			if(isExcluded(node, timingDriven)) continue;
			if(routethruHelper.isRouteThru(node, node)) continue;
			
			Routable child = createdRoutable.get(node);
			if(child == null) {
				RoutableType type = RoutableType.WIRE;		
				child = new RoutableNode(globalIndex++, node, type);
				createdRoutable.put(node, child);
			}
			children.add(child);//the sink rnode of a target connection has been created up-front
		}
		return globalIndex;
	}
	
	public void setBaseCost(){
		if(type == RoutableType.WIRE){
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
				if(endTileXCoordinate != getNode().getTile().getTileXCoordinate()) {
					baseCost = 0.4f*length;
				}
				break;
			case NODE_HQUAD:
				baseCost = 0.35f*length;
				break;
			case NODE_VQUAD:
				baseCost = 0.15f*length;// VQUADs have length 4 and 5
				break;
			case NODE_HLONG:
				baseCost = 0.15f*length;// HLONGs have length 6 and 7
				break;
			case NODE_VLONG:
				baseCost = 0.7f;
				break;	
			default:
				if(length != 0) baseCost *= length;
				type = RoutableType.WIRE;
				break;
			}	
		}else if(type == RoutableType.PINFEED_I){
			baseCost = 0.4f;
		}else if(type == RoutableType.PINFEED_O){
			baseCost = 1f;
		}
	}

	@Override
	public boolean isOverUsed() {
		return Routable.capacity < getOccupancy();
	}
	
	@Override
	public boolean isUsed(){
		return getOccupancy() > 0;
	}
	
	@Override
	public boolean hasMultiDrivers(){
		return Routable.capacity < uniqueDriverCount();
	}

	@Override
	public void setEndTileXYCoordinates() {
		Wire[] wires = node.getAllWiresInNode();
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
			endTile = getNode().getTile();
		}
		endTileXCoordinate = (short) endTile.getTileXCoordinate();
		endTileYCoordinate = (short) endTile.getTileYCoordinate();
		Tile base = getNode().getTile();
		length = (short) (Math.abs(endTileXCoordinate - base.getTileXCoordinate()) 
				+ Math.abs(endTileYCoordinate - base.getTileYCoordinate()));
	}
	
	@Override
	public void updatePresentCongestionCost(float pres_fac) {
		int occ = getOccupancy();
		int cap = Routable.capacity;
		
		if (occ < cap) {
			setPresentCongestionCost(1);
		} else {
			setPresentCongestionCost(1 + (occ - cap + 1) * pres_fac);
		}
	}
	
	@Override
	public String toString(){
		String coordinate = "";	
		coordinate = "(" + endTileXCoordinate + "," + endTileYCoordinate + ")";
		StringBuilder s = new StringBuilder();
		s.append("id = " + index);
		s.append(", ");
		s.append("node " + node.toString());
		s.append(", ");
		s.append(coordinate);
		s.append(", ");
		s.append(String.format("type = %s", type));
		s.append(", ");
		s.append(String.format("ic = %s", getNode().getIntentCode()));
		s.append(", ");
		s.append(String.format("dly = %d", delay));
		s.append(", ");
		s.append(String.format("user = %s", getOccupancy()));
		s.append(", ");
		s.append(getUsersConnectionCounts());
		
		return s.toString();
	}
	
	@Override
	public int hashCode(){
		return node.hashCode();
	}
	
	@Override
	public int getIndex() {
		return index;
	}
	
	@Override
	public boolean isInConnectionBoundingBox(Connection connection) {		
		return endTileXCoordinate > connection.getXMinBB() && endTileXCoordinate < connection.getXMaxBB() && endTileYCoordinate > connection.getYMinBB() && endTileYCoordinate < connection.getYMaxBB();
	}
	
	@Override
	public Node getNode() {
		return node;
	}

	@Override
	public boolean isTarget() {
		return target;
	}

	@Override
	public void setTarget(boolean isTarget) {
		this.target = isTarget;	
	}

	@Override
	public RoutableType getRoutableType() {
		return type;
	}

	@Override
	public float getDelay() {
		return delay;
	}
	@Override
	public short getEndTileXCoordinate() {
		return endTileXCoordinate;
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
		return endTileYCoordinate;
	}
	
	@Override
	public float getBaseCost() {
		return baseCost;
	}

	public boolean isChildrenUnset() {
		return children == null;
	}

	@Override
	public List<Routable> getChildren() {
		return children;
	}

	@Override
	public void setDelay(short delay) {
		this.delay = delay;
	}

	@Override
	public int manhattanDistToSink(Routable sink) {
		return Math.abs(this.getEndTileXCoordinate() - sink.getEndTileXCoordinate()) + Math.abs(getEndTileYCoordinate() - sink.getEndTileYCoordinate());
	}

	@Override
	public void setRoutableType(RoutableType type) {
		this.type = type;
	}

	@Override
	public short getLength() {
		return length;
	}
	
	@Override
	public void setLowerBoundTotalPathCost(float totalPathCost) {
		lowerBoundTotalPathCost = totalPathCost;
		setVisited(true);
	}
	
	@Override
	public void setUpstreamPathCost(float newPartialPathCost) {
		this.upstreamPathCost = newPartialPathCost;
	}
	
	@Override
	public float getLowerBoundTotalPathCost() {
		return lowerBoundTotalPathCost;
	}
	
	@Override
	public float getUpstreamPathCost() {
		return upstreamPathCost;
	}

	@Override
	public Map<NetWrapper, Integer> getUsersConnectionCounts() {
		return usersConnectionCounts;
	}
	
	@Override
	public void incrementUser(NetWrapper source) {
		if(usersConnectionCounts == null) {
			usersConnectionCounts = new HashMap<>();
		}
		Integer connectionCount = usersConnectionCounts.getOrDefault(source, 0);
		usersConnectionCounts.put(source, connectionCount + 1);
	}
	
	@Override
	public int uniqueUserCount() {
		if(usersConnectionCounts == null) {
			return 0;
		}
		return usersConnectionCounts.size();
	}
	
	@Override
	public void decrementUser(NetWrapper user) {
		Integer count = usersConnectionCounts.getOrDefault(user, 0);
		if(count == 1) {
			usersConnectionCounts.remove(user);
		}else if(count > 1) {
			usersConnectionCounts.put(user, count - 1);
		}
	}
	
	@Override
	public int countConnectionsOfUser(NetWrapper user) {
		if(usersConnectionCounts == null) {
			return 0;
		}
		return usersConnectionCounts.getOrDefault(user, 0);
	}
	
	@Override
	public int uniqueDriverCount() {
		if(driversCounts == null) {
			return 0;
		}
		return driversCounts.size();
	}
	
	@Override
	public void incrementDriver(Routable parent) {
		if(driversCounts == null) {
			driversCounts = new HashMap<>();
		}
		Integer count = driversCounts.getOrDefault(parent, 0);
		driversCounts.put(parent, count + 1);
	}
	
	@Override
	public void decrementDriver(Routable parent) {
		Integer count = driversCounts.getOrDefault(parent, 0);
		if(count == 1) {
			driversCounts.remove(parent);
		}else if(count > 1) {
			driversCounts.put(parent, count - 1);
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
	public float getPresentCongestionCost() {
		return presentCongestionCost;
	}

	@Override
	public void setPresentCongestionCost(float presentCongestionCost) {
		this.presentCongestionCost = presentCongestionCost;
	}

	@Override
	public float getHistoricalCongestionCost() {
		return historicalCongestionCost;
	}

	@Override
	public void setHistoricalCongestionCost(float historicalCongestionCost) {
		this.historicalCongestionCost = historicalCongestionCost;
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
