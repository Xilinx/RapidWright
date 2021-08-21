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

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.router.RouteThruHelper;

/**
 * A Routable Object corresponds to a vertex of the routing resource graph. It is denoted as rnode.
 * The routing resource graph is built "lazily", i.e., Routable Objects (rnodes) are created when needed.
 */

public interface Routable {
	/** Each Routable Object can be legally used by one net only */
	public static final short capacity = 1;
	/** 
	 * Checks if a Routable Object has been used by any net.
	 * @return true, if a Routable Object has been used.
	 */
	public boolean used();
	/**
	 * Checks if a Routable Object has been used by more than one net.
	 * @return true, if a Routable Object has been used by multiple nets.
	 */
	public boolean overUsed();
	/**
	 * Checks if a Routable Object are illegally driven by multiple drivers.
	 * @return true, if a Routable Object has multiple drivers.
	 */
	public boolean hasMultiFanin();
	/**
	 * Gets the number of nets that are using a Routable Object.
	 * @return The number of nets using a Routable Object.
	 */
	public int getOccupancy();
	/**
	 * Sets the x and y coordinates of the Interconnect (INT) tile that a Routable Object stops at.
	 */
	public void setEndTileXYCoordinates();
	/**
	 * Sets x coordinate of the end Interconnect (INT) tile.
	 * @param x The tileXCoordinate of the INT tile that a Routable Object stops at.
	 */
	public void setEndTileXCoordinate(short x);
	/**
	 * Sets y coordinate of the end Interconnect (INT) tile.
	 * @param y The tileYCoordinate of the INT tile that a Routable Object stops at.
	 */
	public void setEndTileYCoordinate(short y);
	/**
	 * Gets x coordinate of the end Interconnect (INT) tile.
	 * @return The tileXCoordinate of the INT tile that a Routable Object stops at.
	 */
	public short getEndTileXCoordinate();
	/**
	 * Gets y coordinate of the end Interconnect (INT) tile.
	 * @return The tileYCoordinate of the INT tile that a Routable Object stops at.
	 */
	public short getEndTileYCoordinate();
	/**
	 * Gets the wirelength of a Routable Object.
	 * @return The wirelength of a Routable Object, i.e. the number of INT tiles.
	 */
	public short getLength();
	/**
	 * Checks if a Routable Object is the current routing target.
	 * @return true, if a Routable Object is the current routing target.
	 */
	public boolean isTarget();
	/**
	 * Sets the boolean value of target.
	 * @param isTarget The value to be set.
	 */
	public void setTarget(boolean isTarget);
	/**
	 * Updates the present congestion cost based on the present congestion penalty factor.
	 * @param presentCongesFac The present congestion penalty factor .
	 */
	public void updatePresentCongesCost(float presentCongesFac);
	/**
	 * Gets the base cost of a Routable Object.
	 * @return The base cost of a Routable Object.
	 */
	public float getBaseCost();
	/**
	 * Gets the present congestion cost of a Routable Object.
	 * @return The present congestion of a Routable Object.
	 */
	public float getPresentCongesCost();
	/**
	 * Sets the present congestion cost of a Routable Object.
	 * @param presentCongesCost The present congestion cost to be set.
	 */
	public void setPresentCongesCost(float presentCongesCost);
	/**
	 * Gets the historical congestion cost of a Routable Object.
	 * @return The historical congestion cost of a Routable Object.
	 */
	public float getHistoricalCongesCost();
	/**
	 * Gets the historical congestion cost of a Routable Object.
	 * @param historicalCongesCost The historical congestion cost to be set.
	 */
	public void setHistoricalCongesCost(float historicalCongesCost);
	/**
	 * Checks if a Routable Object is within the conncetion's bounding box.
	 * @param connetion The connection that is being routed.
	 * @return true, if a Routable is within the connection's bounding box.
	 */
	public boolean isInConBoundingBox(Connection connection);
	/**
	 * Overrides the toString()
	 * @return A String contains the information of a Routable Object.
	 */
	public String toString();
	/**
	 * Overrides the hashCode()
	 * @return The hash code of a Routable Object, which is equal to its unique index.
	 */
	public int hashCode();
	/**
	 * Gets the type of a Routable Object.
	 * @return The RoutableType of a Routable Object.
	 */
	public RoutableType getRoutableType();
	/**
	 * Sets the type of a Routable Object.
	 * @param type The type to be set.
	 */
	public void setRoutableType(RoutableType type);
	/**
	 * Gets the associated Node of a Routable Object.
	 * @return The associated Node of a Routable Object.
	 */
	public Node getNode();
	/**
	 * Sets the delay of a Routable Object.
	 * @param delay The delay value to be set.
	 */
	public void setDelay(short delay);
	/**
	 * Gets the delay of a Routable Object.
	 * @return The delay of a Routable Object.
	 */
	public float getDelay();
	/**
	 * Sets a boolean value to indicate if the children (i.e. downhill Routable Objects) of a Routable Object have been set.
	 * @param childrenSet The boolean value to be set.
	 */
	public void setChildrenSet(boolean childrenSet);
	/**
	 * Checks if the children of a Routable Object have been set.
	 * @return true, if the children have been set.
	 */
	public boolean isChildrenSet();
	/**
	 * Gets the Manhattan distance from a Routable Object to the target Routable Object , typically the sink Routable Object of a connection.
	 * @param sink The target Routable Object.
	 * @return The Manhattan distance from a Routable Object to the target Routable Object.
	 */
	public int manhattanDistToSink(Routable sink);
	/**
	 * Sets the children of a Routable Object.
	 * @param rnodeId The starting unique index .
	 * @param rnodesCreated The map of nodes to created Routable Objects.
	 * @param reservedNodes The set of all preserved nodes.
	 * @param routethruHelper The routethru helper to check if the Node of a Routable Object and a downhill node of it makes up a routetru.
	 * @return The updated rnodeId after all children have been created.
	 */
	public int setChildren(int rnodeId, Map<Node, Routable> rnodesCreated,
			Set<Node> reservedNodes, RouteThruHelper routethruHelper);
	/**
	 * Gets the children of a Routable Object.
	 * @return A list of Routable Objects.
	 */
	public List<Routable> getChildren();
	
	/**
	 * Sets the lower bound total path cost.
	 * @param totalPathCost The cost value to be set.
	 */
	public void setLowerBoundTotalPathCost(float totalPathCost);
	
	/**
	 * Sets the upstream path cost.
	 * @param newPartialPathCost The new value to be set.
	 */
	public void setUpstreamPathCost(float newPartialPathCost);
	
	/**
	 * Gets the lower bound total path cost.
	 * @return The lower bound total path cost.
	 */
	public float getLowerBoundTotalPathCost();
	
	/**
	 * Gets the upstream path cost.
	 * @return The upstream path cost.
	 */
	public float getUpstreamPathCost();

	/**
	 * Gets a HashMultiset of the sources of nets that are using the associated rnode.
	 * @return The HashMultiset of the sources of nets that are using the associated rnode.
	 * {@link SitePinInst}
	 */
	public Map<SitePinInst, Integer> getSourceSet();

	/**
	 * Adds a source SitePinInst to the source set.
	 * @param source The source of a net to be added.
	 */
	public void addSource(SitePinInst source);
	
	/**
	 * Gets the number of unique sources in the source set.
	 * @return The number of unique sources in the source set.
	 */
	public int numUniqueSources();
	
	/**
	 * Removes a source from the source set.
	 * @param source The source {@link SitePinInst} to be removed from the set.
	 */
	public void removeSource(SitePinInst source);

	/**
	 * Counts the total number of a source included in the source set, 
	 * which equals to the number of connections driven by the source that are using the rnode.
	 * @param source The source {@link SitePinInst}.
	 * @return The total number of a source included in the source set.
	 */
	public int countSourceUses(SitePinInst source);
	
	/**
	 * Gets the number of unique drivers of the rnode.
	 * @return The number of unique drivers of the rnode.
	 */
	public int numUniqueParents();
	
	/**
	 * Adds a driver to the parent set of the associated rnode.
	 * @param parent The driver to be added.
	 */
	public void addParent(Routable parent);
	
	/**
	 * Removes a parent from the parent set.
	 * @param parent The parent to be removed.
	 */
	public void removeParent(Routable parent);
	
	/**
	 * Gets the driving Routable instance of a Routable instance for routing a connection.
	 * @return The driving Routable instance.
	 */
	public Routable getPrev();

	/**
	 * Sets the driving Routable instance of a Routable instance for routing a connection.
	 * @param prev The driving Routable instance to set.
	 */
	public void setPrev(Routable prev);

	/**
	 * Checks if a Routable instance has been visited before when routing a connection.
	 * @return true, if a Routable instance has been visited before.
	 */
	public boolean isVisited();

	/**
	 * Sets visited to indicate if a Routable instance has been visited before when routing a connection.
	 * @param A boolean value to set.
	 */
	public void setVisited(boolean visited);
	
	/**
	 * Gets the unique index of a Routable instance.
	 * @return
	 */
	public int getIndex();
}
