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
import java.util.Arrays;
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

/**
 * A RoutableNode Object, denoted as rnode, is a vertex of the routing resource graph.
 * It implements {@link Routable} and is created based on a {@link Node} Object.
 */
public abstract class RoutableNode implements Routable{
	/** The associated {@link Node} instance */
	protected Node node;
	/** The type of a rnode*/
	private RoutableType type;
	/** The tileXCoordinate and tileYCoordinate of the INT tile that the associated node stops at */
	private short endTileXCoordinate;
	private short endTileYCoordinate;
	/** The wirelength of a rnode */
	private short length;
	/** The base cost of a rnode */
	private float baseCost;
	/** A flag to indicate if this rnode is the target */
	private boolean isTarget;
	/** The children (downhill rnodes) of this rnode */
	protected Routable[] children;
	
	/** Present congestion cost */
	private float presentCongestionCost;
	/** Historical congestion cost */
	private float historicalCongestionCost;
	/** Upstream path cost */
	private float upstreamPathCost;
	/** Lower bound of the total path cost */
	private float lowerBoundTotalPathCost;
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
	
	public RoutableNode(Node node, RoutableType type){
		this.node = node;
		setRoutableType(type);
		children = null;
		isTarget = false;
		setEndTileXYCoordinates();
		setBaseCost(type);
		presentCongestionCost = 1;
    	historicalCongestionCost = 1;
    	setVisited(false);
		usersConnectionCounts = null;
		driversCounts = null;
		setPrev(null);
	}

	abstract protected Routable getOrCreate(Node node, RoutableType type);
	
	protected void setChildren(/*RouteThruHelper routethruHelper*/){
		if (children != null)
			return;

		List<Node> allDownHillNodes = node.getAllDownhillNodes();
		List<Routable> childrenList = new ArrayList<>(allDownHillNodes.size());
		for(Node node:allDownHillNodes){
			if(isExcluded(node)) continue;
			// FIXME: What is the meaning of checking that a node routethru-s to itself?
			// if(routethruHelper.isRouteThru(node, node)) continue;

			RoutableType type = RoutableType.WIRE;
			Routable child = getOrCreate(node, type);
			childrenList.add(child);//the sink rnode of a target connection has been created up-front
		}
		children = childrenList.toArray(new Routable[0]);
	}
	
	private void setBaseCost(RoutableType type){
		// TODO: Why does enabling the following line cause unroutability?
		//       setRoutableType() is called before this, and it setting
		//       this.type disrupts the base cost such that
		//       testNonTimingDrivenPartialRouting becomes unroutable.
		//       The `type` parameter fed to this method is the original
		//       value provided to the constructor
		// type = this.type;
		if(type == RoutableType.WIRE){
			baseCost = 0.4f;
			// NOTE: IntentCode is device-dependent
			IntentCode ic = node.getIntentCode();
			switch(ic) {
			case NODE_PINBOUNCE:
			case NODE_PINFEED:
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
	public boolean hasMultiDrivers() {
		return Routable.capacity < uniqueDriverCount();
	}

	private void setEndTileXYCoordinates() {
		Wire[] wires = node.getAllWiresInNode();
		List<Tile> intTiles = new ArrayList<>();
		for(Wire w : wires) {
			if(w.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				intTiles.add(w.getTile());
			}
		}
		Tile endTile;
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
		StringBuilder s = new StringBuilder();
		s.append("node " + node.toString());
		s.append(", ");
		s.append("(" + endTileXCoordinate + "," + endTileYCoordinate + ")");
		s.append(", ");
		s.append(String.format("type = %s", type));
		s.append(", ");
		s.append(String.format("ic = %s", getNode().getIntentCode()));
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
	public boolean isInConnectionBoundingBox(Connection connection) {		
		return endTileXCoordinate > connection.getXMinBB() && endTileXCoordinate < connection.getXMaxBB() && endTileYCoordinate > connection.getYMinBB() && endTileYCoordinate < connection.getYMaxBB();
	}
	
	@Override
	public Node getNode() {
		return node;
	}

	@Override
	public boolean isTarget() {
		return isTarget;
	}

	@Override
	public void setTarget(boolean isTarget) {
		this.isTarget = isTarget;
	}

	@Override
	public RoutableType getRoutableType() {
		return type;
	}

	@Override
	public float getDelay() {
		return 0;
	}
	@Override
	public short getEndTileXCoordinate() {
		return endTileXCoordinate;
	}

	@Override
	public short getEndTileYCoordinate() {
		return endTileYCoordinate;
	}
	
	@Override
	public float getBaseCost() {
		return baseCost;
	}

	@Override
	public Routable[] getChildren() {
		return children != null ? children : new Routable[0];
	}

	@Override
	public void addChild(Routable rnode) {
		// FIXME: This is inefficient, but is currently only used by
		//        RWRoute.unrouteReservedNetsToReleaseResources()
		//        which is due for an overhaul
		children = Arrays.copyOf(children, children.length+1);
		children[children.length-1] = rnode;
	}

	private void setRoutableType(RoutableType type) {
		this.type = type;
		if(type == RoutableType.WIRE) {
			// NOTE: IntentCode is device-dependent
			IntentCode ic = node.getIntentCode();
			switch (ic) {
				case NODE_PINBOUNCE:
					this.type = RoutableType.PINBOUNCE;
					break;
				case NODE_PINFEED:
					this.type = RoutableType.PINFEED_I;
					break;
			}
		}
	}

	@Override
	public short getLength() {
		return length;
	}
	
	@Override
	public void setLowerBoundTotalPathCost(float totalPathCost) {
		lowerBoundTotalPathCost = totalPathCost;
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
		usersConnectionCounts.merge(source, 1, Integer::sum);
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
		usersConnectionCounts.compute(user, (k,v) -> (v == 1) ? null : v - 1);
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
		driversCounts.merge(parent, 1, Integer::sum);
	}
	
	@Override
	public void decrementDriver(Routable parent) {
		driversCounts.compute(parent, (k,v) -> (v == 1) ? null : v - 1);
	}
	
	@Override
	public int getOccupancy() {
		return uniqueUserCount();
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
		return getPrev() != null;
	}

	@Override
	public void setVisited(boolean visited) {
		assert(!visited);
		setPrev(null);
	}
	
	/**
	 * Checks if a node is an exit node of a NodeGroup
	 * @param node The node in question
	 * @return true, if the node is a S/D/Q/L node or a local node with a GLOBAL and CTRL wire
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
	 * @return true, if the node should be excluded from the routing resource graph.
	 */
	public boolean isExcluded(Node node) {
		Tile tile = node.getTile();
		TileTypeEnum tileType = tile.getTileTypeEnum();
		if (tileType == TileTypeEnum.INT) {
			return false;
		}
		// return !tile.getName().startsWith("LAG");
		// TODO: Is this equivalent to the above?
		//       (i.e. do not allow anything except INT and LAG* tiles)
		return !lagunaTileEnums.contains(tileType);
	}
	 
	final private static Set<TileTypeEnum> lagunaTileEnums;
	static {
		lagunaTileEnums = new HashSet<>();
		for (TileTypeEnum e : TileTypeEnum.values()) {
			if (e.toString().startsWith("LAG")) {
				lagunaTileEnums.add(e);
			}
		}
	}
	
}
