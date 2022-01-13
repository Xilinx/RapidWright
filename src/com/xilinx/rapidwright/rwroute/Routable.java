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

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.router.RouteThruHelper;

/**
 * A Routable Object corresponds to a vertex of the routing resource graph.
 * Each Routable instance is associated with a {@link Node} instance. It is denoted as "rnode".
 * The routing resource graph is built "lazily", i.e., Routable Objects (rnodes) are created when needed.
 */

public interface Routable {
	/** Each Routable Object can be legally used by one net only */
	public static final short capacity = 1;
	/** 
	 * Checks if a Routable Object has been used.
	 * @return true, if a Routable Object has been used.
	 */
	public boolean isUsed();
	/**
	 * Checks if a Routable Object has been used by more than one users.
	 * @return true, if a Routable Object has been used by multiple users.
	 */
	public boolean isOverUsed();
	/**
	 * Checks if a Routable Object are illegally driven by multiple drivers.
	 * @return true, if a Routable Object has multiple drivers.
	 */
	public boolean hasMultiDrivers();
	/**
	 * Gets the number of users.
	 * @return The number of users.
	 */
	public int getOccupancy();
	/**
	 * Sets the x and y coordinates of the INT {@link Tile} instance
	 * that the associated {@link Node} instance stops at.
	 */
	public void setEndTileXYCoordinates();
	/**
	 * Sets the x coordinate of the INT {@link Tile} instance
	 * that the associated {@link Node} instance stops at.
	 * @param x The tileXCoordinate of the INT tile that the associated {@link Node} instance stops at.
	 */
	public void setEndTileXCoordinate(short x);
	/**
	 * Sets the Y coordinate of the INT {@link Tile} instance
	 * that the associated {@link Node} instance stops at.
	 * @param y The tileYCoordinate of the INT tile that the associated {@link Node} instance stops at.
	 */
	public void setEndTileYCoordinate(short y);
	/**
	 * Gets the x coordinate of the INT {@link Tile} instance
	 * that the associated {@link Node} instance stops at.
	 * @return The tileXCoordinate of the INT tile that the associated {@link Node} instance stops at.
	 */
	public short getEndTileXCoordinate();
	/**
	 * Gets the Y coordinate of the INT {@link Tile} instance
	 * that the associated {@link Node} instance stops at.
	 * @return The tileYCoordinate of the INT tile that the associated {@link Node} instance stops at.
	 */
	public short getEndTileYCoordinate();
	/**
	 * Gets the wirelength.
	 * @return The wirelength, i.e. the number of INT tiles that the associated {@link Node} instance spans.
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
	 * @param presentCongestionFactor The present congestion penalty factor .
	 */
	public void updatePresentCongestionCost(float presentCongestionFactor);
	/**
	 * Gets the base cost of a Routable Object.
	 * @return The base cost of a Routable Object.
	 */
	public float getBaseCost();
	/**
	 * Gets the present congestion cost of a Routable Object.
	 * @return The present congestion of a Routable Object.
	 */
	public float getPresentCongestionCost();
	/**
	 * Sets the present congestion cost of a Routable Object.
	 * @param presentCongestionCost The present congestion cost to be set.
	 */
	public void setPresentCongestionCost(float presentCongestionCost);
	/**
	 * Gets the historical congestion cost of a Routable Object.
	 * @return The historical congestion cost of a Routable Object.
	 */
	public float getHistoricalCongestionCost();
	/**
	 * Gets the historical congestion cost of a Routable Object.
	 * @param historicalCongestionCost The historical congestion cost to be set.
	 */
	public void setHistoricalCongestionCost(float historicalCongestionCost);
	/**
	 * Checks if coordinates of a Routable Object is within the connection's bounding box.
	 * @param connection The connection that is being routed.
	 * @return true, if coordinates of a Routable is within the connection's bounding box.
	 */
	public boolean isInConnectionBoundingBox(Connection connection);
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
	 * Checks if the children of a Routable Object have been set or not.
	 * @return true, if the children have not been set.
	 */
	public boolean isChildrenUnset();
	/**
	 * Gets the Manhattan distance from a Routable Object to the target Routable Object, typically the sink Routable Object of a connection.
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
	 * Gets a map that records users of a {@link Routable} instance based on all routed connections.
	 * Each user is a {@link NetWrapper} instance representing a {@link Net} instance.
	 * It is often the case that multiple connections of a net are using a same rnode.
	 * So we count connections of each user to facilitate the sharing mechanism of RWRoute.
	 * @return A map between users, i.e., {@link NetWrapper} instances representing by {@link Net} instances,
	 *  and numbers of connections from different users.
	 */
	public Map<NetWrapper, Integer> getUsersConnectionCounts();

	/**
	 * Adds an user {@link NetWrapper} instance to the user map, of which a key is a {@link NetWrapper} instance and
	 * the value is the number of connections that are using a rnode.
	 * If the user is already stored in the map, increment the connection count of the user by 1. Otherwise, put the user
	 * into the map and initialize the connection count as 1. 
	 * @param user The user in question.
	 */
	public void incrementUser(NetWrapper user);
	
	/**
	 * Gets the number of unique users.
	 * @return The number of unique {@link NetWrapper} instances in the user map, i.e, the key set size of the user map.
	 */
	public int uniqueUserCount();
	
	/**
	 * Decrements the connection count of a user that is represented by a
	 * {@link NetWrapper} instance corresponding to a {@link Net} instance.
	 * If there is only one connection of the user that is using a Routable instance, remove the user from the map.
	 * Otherwise, decrement the connection count by 1.
	 * @param user The user to be decremented from the user map.
	 */
	public void decrementUser(NetWrapper user);

	/**
	 * Counts the connections of a user that are using a rnode.
	 * @param user The user in question indicated by a {@link NetWrapper} instance.
	 * @return The total number of connections of the user.
	 */
	public int countConnectionsOfUser(NetWrapper user);
	
	/**
	 * Gets the number of unique drivers.
	 * @return The number of unique drivers of a rnode, i.e., the key set size of the driver map
	 */
	public int uniqueDriverCount();
	
	/**
	 * Adds a driver to the driver map.
	 * @param parent The driver to be added.
	 */
	public void incrementDriver(Routable parent);
	
	/**
	 * Decrements the driver count of a Routable instance.
	 * @param parent The driver that should have its count reduced by 1.
	 */
	public void decrementDriver(Routable parent);
	
	/**
	 * Gets the parent Routable instance for routing a connection.
	 * @return The driving Routable instance.
	 */
	public Routable getPrev();

	/**
	 * Sets the parent Routable instance for routing a connection.
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
	 * @param visited boolean value to set.
	 */
	public void setVisited(boolean visited);
	
	/**
	 * Gets the unique index of a rnode.
	 * @return
	 */
	public int getIndex();
	
}
