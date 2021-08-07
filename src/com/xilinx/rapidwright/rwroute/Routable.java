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
 * A Routable created based on a Node, referring to a vertex of the routing resource graph 
 */

public interface Routable {
	/** Each Routable can be legally used by one net*/
	public static final short capacity = 1;
	/** 
	 * Gets the RoutableData of this Routable
	 * @return The RoutableData of this Routable
	 */
	public RoutableData getRoutableData();
	/** 
	 * Checks if this Routable has been used by any net
	 * @return true, if this Routable has been used
	 */
	public boolean used();
	/**
	 * Checks if this Routable has been used by more than one net
	 * @return true, if this Routable has been used by multiple nets
	 */
	public boolean overUsed();
	/**
	 * Checks if this Routable are illegally driven by multiple drivers
	 * @return true, if this Routable has mutilple drivers
	 */
	public boolean hasMultiFanin();
	/**
	 * Gets the number of nets that are using this Routable
	 * @return The number of nets using this Routable
	 */
	public int getOccupancy();
	/**
	 * Sets the x and y coordinates of the Interconnect (INT) tile that this Routable stops at
	 */
	public void setXY();
	/**
	 * Sets x coordinate of the exit Interconnect (INT) tile
	 * @param x The tileXCoordinate of the INT tile that this Routable stops at
	 */
	public void setX(short x);
	/**
	 * Sets y coordinate of the exit Interconnect (INT) tile
	 * @param y The tileYCoordinate of the INT tile that this Routable stops at
	 */
	public void setY(short y);
	/**
	 * Gets x coordinate of the exit Interconnect (INT) tile
	 * @return The tileXCoordinate of the INT tile that this Routable stops at
	 */
	public short getX();
	/**
	 * Gets y coordinate of the exit Interconnect (INT) tile
	 * @return The tileYCoordinate of the INT tile that this Routable stops at
	 */
	public short getY();
	/**
	 * Gets the wirelength of this Routable
	 * @return The wirelength of this Routable, i.e. the number of INT tiles
	 */
	public short getLength();
	/**
	 * Checks if this Routable is the current routing target
	 * @return true, if this Routable is the current routing target
	 */
	public boolean isTarget();
	/**
	 * Sets the boolean value of target
	 * @param isTarget The value to be set
	 */
	public void setTarget(boolean isTarget);
	/**
	 * Updates the present congestion cost based on the present congestion penalty factor
	 * @param presentCongesFac The present congestion penalty factor 
	 */
	public void updatePresentCongesCost(float presentCongesFac);
	/**
	 * Gets the base cost of this Routable
	 * @return The base cost of this Routable
	 */
	public float getBaseCost();
	/**
	 * Gets the present congestion cost of this Routable
	 * @return The present congestion of this Routable
	 */
	public float getPresentCongesCost();
	/**
	 * Sets the present congestion cost of this Routable
	 * @param presentCongesCost The present congestion cost to be set
	 */
	public void setPresentCongesCost(float presentCongesCost);
	/**
	 * Gets the historical congestion cost of this Routable
	 * @return The historical congestion cost of this Routable
	 */
	public float getHistoricalCongesCost();
	/**
	 * Gets the historical congestion cost of this Routable
	 * @param historicalCongesCost The historical congestion cost to be set
	 */
	public void setHistoricalCongesCost(float historicalCongesCost);
	/**
	 * Checks if this Routable is within the conncetion's bounding box
	 * @param connetion The connection that is being routed
	 * @return true, if this Routable is within the connection's bounding box
	 */
	public boolean isInConBoundingBox(Connection connection);
	/**
	 * Overrides the toString()
	 * @return A String contains the information of this Routable
	 */
	public String toString();
	/**
	 * Overrides the hashCode()
	 * @return The hash code of this Routable, which is equal to its unique index
	 */
	public int hashCode();
	/**
	 * Gets the type of this Routable
	 * @return The RoutableType of this Routable
	 */
	public RoutableType getRoutableType();
	/**
	 * Sets the type of this Routable
	 * @param type The type to be set
	 */
	public void setRoutableType(RoutableType type);
	/**
	 * Gets the associated Node of this Routable
	 * @return The associated Node of this Routable
	 */
	public Node getNode();
	/**
	 * Sets the delay of this Routable
	 * @param delay The delay value to be set
	 */
	public void setDelay(short delay);
	/**
	 * Gets the delay of this Routable
	 * @return The delay of this Routable
	 */
	public float getDelay();
	/**
	 * Sets a boolean value to indicate if the children (i.e. downhill Routables) of this Routable have been set
	 * @param childrenSet The boolean value to be set
	 */
	public void setChildrenSet(boolean childrenSet);
	/**
	 * Checks if the children of this Routable have been set
	 * @return true, if the children have been set
	 */
	public boolean isChildrenSet();
	/**
	 * Gets the Manhattan distance from this Routable to the target Routable, typically the sink Routable of a connction
	 * @param sink The target Routable
	 * @return The Manhattan distance from this Routable to the target Routable
	 */
	public int manhattanDistToSink(Routable sink);
	/**
	 * Sets the children of this Routable
	 * @param rnodeId The starting unique index 
	 * @param rnodesCreated The map of nodes to created Routables
	 * @param reservedNodes The set of all preserved nodes
	 * @param routethruHelper The routethru helper to check if the Node of this Routable and a downhill node of it makes up a routetru
	 * @return The updated rnodeId after all children have been created
	 */
	public int setChildren(int rnodeId, Map<Node, Routable> rnodesCreated,
			Set<Node> reservedNodes, RouteThruHelper routethruHelper);
	/**
	 * Gets the children of this Routable
	 * @return A list of Routables
	 */
	public List<Routable> getChildren();
}
