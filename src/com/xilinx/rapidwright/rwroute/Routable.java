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

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.router.RouteThruHelper;

/**
 * A Routable Object corresponds to a vertex of the routing resource graph. It is also denoted as rnode.
 * The routing resource graph is built "lazily", i.e. Routable Objects are created when needed.
 */

public interface Routable {
	/** Each Routable Object can be legally used by one net only */
	public static final short capacity = 1;
	/** 
	 * Gets the RoutableData Object of this Routable Object.
	 * @return The RoutableData Object of this Routable Object.
	 */
	public RoutableData getRoutableData();
	/** 
	 * Checks if this Routable Object has been used by any net.
	 * @return true, if this Routable Object has been used.
	 */
	public boolean used();
	/**
	 * Checks if this Routable Object has been used by more than one net.
	 * @return true, if this Routable Object has been used by multiple nets.
	 */
	public boolean overUsed();
	/**
	 * Checks if this Routable Object are illegally driven by multiple drivers.
	 * @return true, if this Routable Object has multiple drivers.
	 */
	public boolean hasMultiFanin();
	/**
	 * Gets the number of nets that are using this Routable Object.
	 * @return The number of nets using this Routable Object.
	 */
	public int getOccupancy();
	/**
	 * Sets the x and y coordinates of the Interconnect (INT) tile that this Routable Object stops at.
	 */
	public void setXY();
	/**
	 * Sets x coordinate of the exit Interconnect (INT) tile.
	 * @param x The tileXCoordinate of the INT tile that this Routable Object stops at.
	 */
	public void setX(short x);
	/**
	 * Sets y coordinate of the exit Interconnect (INT) tile.
	 * @param y The tileYCoordinate of the INT tile that this Routable Object stops at.
	 */
	public void setY(short y);
	/**
	 * Gets x coordinate of the exit Interconnect (INT) tile.
	 * @return The tileXCoordinate of the INT tile that this Routable Object stops at.
	 */
	public short getX();
	/**
	 * Gets y coordinate of the exit Interconnect (INT) tile.
	 * @return The tileYCoordinate of the INT tile that this Routable Object stops at.
	 */
	public short getY();
	/**
	 * Gets the wirelength of this Routable Object.
	 * @return The wirelength of this Routable Object, i.e. the number of INT tiles.
	 */
	public short getLength();
	/**
	 * Checks if this Routable Object is the current routing target.
	 * @return true, if this Routable Object is the current routing target.
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
	 * Gets the base cost of this Routable Object.
	 * @return The base cost of this Routable Object.
	 */
	public float getBaseCost();
	/**
	 * Gets the present congestion cost of this Routable Object.
	 * @return The present congestion of this Routable Object.
	 */
	public float getPresentCongesCost();
	/**
	 * Sets the present congestion cost of this Routable Object.
	 * @param presentCongesCost The present congestion cost to be set.
	 */
	public void setPresentCongesCost(float presentCongesCost);
	/**
	 * Gets the historical congestion cost of this Routable Object.
	 * @return The historical congestion cost of this Routable Object.
	 */
	public float getHistoricalCongesCost();
	/**
	 * Gets the historical congestion cost of this Routable Object.
	 * @param historicalCongesCost The historical congestion cost to be set.
	 */
	public void setHistoricalCongesCost(float historicalCongesCost);
	/**
	 * Checks if this Routable Object is within the conncetion's bounding box.
	 * @param connetion The connection that is being routed.
	 * @return true, if this Routable is within the connection's bounding box.
	 */
	public boolean isInConBoundingBox(Connection connection);
	/**
	 * Overrides the toString()
	 * @return A String contains the information of this Routable Object.
	 */
	public String toString();
	/**
	 * Overrides the hashCode()
	 * @return The hash code of this Routable Object, which is equal to its unique index.
	 */
	public int hashCode();
	/**
	 * Gets the type of this Routable Object.
	 * @return The RoutableType of this Routable Object.
	 */
	public RoutableType getRoutableType();
	/**
	 * Sets the type of this Routable Object.
	 * @param type The type to be set.
	 */
	public void setRoutableType(RoutableType type);
	/**
	 * Gets the associated Node of this Routable Object.
	 * @return The associated Node of this Routable Object.
	 */
	public Node getNode();
	/**
	 * Sets the delay of this Routable Object.
	 * @param delay The delay value to be set.
	 */
	public void setDelay(short delay);
	/**
	 * Gets the delay of this Routable Object.
	 * @return The delay of this Routable Object.
	 */
	public float getDelay();
	/**
	 * Sets a boolean value to indicate if the children (i.e. downhill Routable Objects) of this Routable Object have been set.
	 * @param childrenSet The boolean value to be set.
	 */
	public void setChildrenSet(boolean childrenSet);
	/**
	 * Checks if the children of this Routable Object have been set.
	 * @return true, if the children have been set.
	 */
	public boolean isChildrenSet();
	/**
	 * Gets the Manhattan distance from this Routable Object to the target Routable Object , typically the sink Routable Object of a connection.
	 * @param sink The target Routable Object.
	 * @return The Manhattan distance from this Routable Object to the target Routable Object.
	 */
	public int manhattanDistToSink(Routable sink);
	/**
	 * Sets the children of this Routable Object.
	 * @param rnodeId The starting unique index .
	 * @param rnodesCreated The map of nodes to created Routable Objects.
	 * @param reservedNodes The set of all preserved nodes.
	 * @param routethruHelper The routethru helper to check if the Node of this Routable Object and a downhill node of it makes up a routetru.
	 * @return The updated rnodeId after all children have been created.
	 */
	public int setChildren(int rnodeId, Map<Node, Routable> rnodesCreated,
			Set<Node> reservedNodes, RouteThruHelper routethruHelper);
	/**
	 * Gets the children of this Routable Object.
	 * @return A list of Routable Objects.
	 */
	public List<Routable> getChildren();
}
