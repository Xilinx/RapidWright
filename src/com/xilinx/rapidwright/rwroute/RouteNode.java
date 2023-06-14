/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A RouteNode Object corresponds to a vertex of the routing resource graph.
 * Each RouteNode instance is associated with a {@link Node} instance. It is denoted as "rnode".
 * The routing resource graph is built "lazily", i.e., RouteNode Objects (rnodes) are created when needed.
 */
abstract public class RouteNode implements Comparable<RouteNode> {
    /** Each RouteNode Object can be legally used by one net only */
    public static final short capacity = 1;
    /** Memoized static array for use by Collection.toArray() or similar */
    public static final RouteNode[] EMPTY_ARRAY = new RouteNode[0];
    public static final int initialPresentCongestionCost = 1;
    public static final int initialHistoricalCongestionCost = 1;

    /** The associated {@link Node} instance */
    protected Node node;
    /** The type of a rnode*/
    private final RouteNodeType type;
    /** The tileXCoordinate and tileYCoordinate of the INT tile that the associated node stops at */
    private final short endTileXCoordinate;
    private final short endTileYCoordinate;
    /** The wirelength of a rnode */
    private final short length;
    /** The base cost of a rnode */
    private float baseCost;
    /** A flag to indicate if this rnode is the target */
    private boolean isTarget;
    /** Byte for the use as general purpose flags */
    private byte flags;
    /** The children (downhill rnodes) of this rnode */
    protected RouteNode[] children;

    /** Present congestion cost */
    private float presentCongestionCost;
    /** Historical congestion cost */
    private float historicalCongestionCost;
    /** Upstream path cost */
    private float upstreamPathCost;
    /** Lower bound of the total path cost */
    private float lowerBoundTotalPathCost;
    /** A variable indicating which id this rnode was last visited by during the expansion */
    private int visited;
    /** A variable that stores the parent of a rnode during expansion to facilitate tracing back */
    private RouteNode prev;
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
    private Map<RouteNode, Integer> driversCounts;

    protected RouteNode(Node node, RouteNodeType type) {
        this.node = node;
        RouteNodeInfo nodeInfo = RouteNodeInfo.get(node);
        this.type = (type == RouteNodeType.WIRE) ? nodeInfo.type : type;
        endTileXCoordinate = nodeInfo.endTileXCoordinate;
        endTileYCoordinate = nodeInfo.endTileYCoordinate;
        length = nodeInfo.length;
        children = null;
        setBaseCost();
        presentCongestionCost = initialPresentCongestionCost;
        historicalCongestionCost = initialHistoricalCongestionCost;
        usersConnectionCounts = null;
        driversCounts = null;
        visited = 0;
        assert(prev == null);
        assert(!isTarget);
        flags = 0;
    }

    @Override
    public int compareTo(RouteNode that) {
        // Do not use Float.compare() since it also compares NaN, which we'll assume is unreachable
        // return Float.compare(this.lowerBoundTotalPathCost, that.lowerBoundTotalPathCost);
        return (int) Math.signum(this.lowerBoundTotalPathCost - that.lowerBoundTotalPathCost);
    }

    abstract protected RouteNode getOrCreate(Node node, RouteNodeType type);

    protected void setChildren(RuntimeTracker setChildrenTimer) {
        if (children != null)
            return;
        setChildrenTimer.start();
        List<Node> allDownHillNodes = node.getAllDownhillNodes();
        List<RouteNode> childrenList = new ArrayList<>(allDownHillNodes.size());
        for (Node downhill: allDownHillNodes) {
            if (!mustInclude(node, downhill)) {
                if (isPreserved(downhill) || isExcluded(node, downhill))
                    continue;
            }

            final RouteNodeType type = RouteNodeType.WIRE;
            RouteNode child = getOrCreate(downhill, type);
            childrenList.add(child);//the sink rnode of a target connection has been created up-front
        }
        if (!childrenList.isEmpty()) {
            children = childrenList.toArray(EMPTY_ARRAY);
        } else {
            children = EMPTY_ARRAY;
        }
        setChildrenTimer.stop();
    }

    private void setBaseCost() {
        baseCost = 0.4f;
        switch (type) {
            case LAGUNA_I:
                // Make all approaches to SLLs zero-cost to encourage exploration
                // Assigning a base cost of zero would normally break congestion resolution
                // (since RWroute.getNodeCost() would return zero) but doing it here should be
                // okay because this node only leads to a SLL which will have a non-zero base cost
                baseCost = 0.0f;
                break;
            case SUPER_LONG_LINE:
                assert(length == RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);
                baseCost = 0.3f * length;
                break;
            case WIRE:
                // NOTE: IntentCode is device-dependent
                IntentCode ic = node.getIntentCode();
                switch(ic) {
                    case NODE_OUTPUT:       // LUT route-thru
                    case NODE_CLE_OUTPUT:
                    case NODE_LAGUNA_OUTPUT:
                    case NODE_LAGUNA_DATA:  // US+: U-turn SLL at the boundary of the device
                        assert(length == 0);
                        break;
                    case NODE_LOCAL:
                    case INTENT_DEFAULT:
                        assert(length <= 1);
                        break;
                    case NODE_SINGLE:
                        assert(length <= 2);
                        if (length == 2) baseCost *= length;
                        break;
                    case NODE_DOUBLE:
                        if (endTileXCoordinate != node.getTile().getTileXCoordinate()) {
                            assert(length <= 2);
                            // Typically, length = 1 (since tile X is not equal)
                            // In US, have seen length = 2, e.g. VU440's INT_X171Y827/EE2_E_BEG7.
                            if (length == 2) baseCost *= length;
                        } else {
                            // Typically, length = 2 except for horizontal U-turns (length = 0)
                            // or vertical U-turns (length = 1).
                            // In US, have seen length = 3, e.g. VU440's INT_X171Y827/NN2_E_BEG7.
                            assert(length <= 3);
                        }
                        break;
                    case NODE_HQUAD:
                        assert (length != 0 || node.getAllDownhillNodes().isEmpty());
                        baseCost = 0.35f * length;
                        break;
                    case NODE_VQUAD:
                        // In case of U-turn nodes
                        if (length != 0) baseCost = 0.15f * length;// VQUADs have length 4 and 5
                        break;
                    case NODE_HLONG:
                        assert (length != 0 || node.getAllDownhillNodes().isEmpty());
                        baseCost = 0.15f * length;// HLONGs have length 6 and 7
                        break;
                    case NODE_VLONG:
                        baseCost = 0.7f;
                        break;
                    default:
                        throw new RuntimeException(ic.toString());
                }
                break;
            case PINFEED_I:
            case PINBOUNCE:
                break;
            case PINFEED_O:
                baseCost = 1f;
                break;
            default:
                throw new RuntimeException(type.toString());
        }
    }

    /**
     * Checks if a RouteNode Object has been used by more than one users.
     * @return true, if a RouteNode Object has been used by multiple users.
     */
    public boolean isOverUsed() {
        return RouteNode.capacity < getOccupancy();
    }

    public boolean willOverUse(NetWrapper netWrapper) {
        int occ = getOccupancy();
        return occ > RouteNode.capacity || (occ == RouteNode.capacity && countConnectionsOfUser(netWrapper) == 0);
    }

    /**
     * Checks if a RouteNode Object has been used.
     * @return true, if a RouteNode Object has been used.
     */
    public boolean isUsed() {
        return getOccupancy() > 0;
    }

    /**
     * Checks if a RouteNode Object are illegally driven by multiple drivers.
     * @return true, if a RouteNode Object has multiple drivers.
     */
    public boolean hasMultiDrivers() {
        return RouteNode.capacity < uniqueDriverCount();
    }

    public static short getLength(Node node) {
        return RouteNodeInfo.get(node).length;
    }

    /**
     * Updates the present congestion cost based on the present congestion penalty factor.
     * @param pres_fac The present congestion penalty factor.
     */
    public void updatePresentCongestionCost(float pres_fac) {
        int occ = getOccupancy();
        int cap = RouteNode.capacity;

        if (occ < cap) {
            setPresentCongestionCost(1);
        } else {
            setPresentCongestionCost(1 + (occ - cap + 1) * pres_fac);
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("node " + node.toString());
        s.append(", ");
        s.append("(" + endTileXCoordinate + "," + getEndTileYCoordinate() + ")");
        s.append(", ");
        s.append(String.format("type = %s", type));
        s.append(", ");
        s.append(String.format("ic = %s", node.getIntentCode()));
        s.append(", ");
        s.append(String.format("user = %s", getOccupancy()));
        s.append(", ");
        s.append(getUsersConnectionCounts());
        return s.toString();
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RouteNode that = (RouteNode) obj;
        return node.equals(that.node);
    }

    /**
     * Checks if coordinates of a RouteNode Object is within the connection's bounding box.
     * @param connection The connection that is being routed.
     * @return true, if coordinates of a RouteNode is within the connection's bounding box.
     */
    public boolean isInConnectionBoundingBox(Connection connection) {
        return endTileXCoordinate > connection.getXMinBB() && endTileXCoordinate < connection.getXMaxBB() && endTileYCoordinate > connection.getYMinBB() && endTileYCoordinate < connection.getYMaxBB();
    }

    /**
     * Gets the associated Node of a RouteNode Object.
     * @return The associated Node of a RouteNode Object.
     */
    public Node getNode() {
        return node;
    }

    /**
     * Checks if a RouteNode Object is the current routing target.
     * @return true, if a RouteNode Object is the current routing target.
     */
    public boolean isTarget() {
        return isTarget;
    }

    /**
     * Sets the boolean value of target.
     * @param isTarget The value to be set.
     */
    public void setTarget(boolean isTarget) {
        this.isTarget = isTarget;
    }

    /**
     * Gets the type of a RouteNode Object.
     * @return The RouteNodeType of a RouteNode Object.
     */
    public RouteNodeType getType() {
        return type;
    }

    /**
     * Gets the delay of a RouteNode Object.
     * @return The delay of a RouteNode Object.
     */
    public float getDelay() {
        return 0;
    }

    public short getBeginTileXCoordinate() {
        // For US+ Laguna tiles, use end tile coordinate as that's already been corrected
        // (see RouteNodeInfo.getEndTileXCoordinate())
        Tile tile = node.getTile();
        return (tile.getTileTypeEnum() == TileTypeEnum.LAG_LAG) ? getEndTileXCoordinate()
                : (short) tile.getTileXCoordinate();
    }

    public short getBeginTileYCoordinate() {
        return (short) node.getTile().getTileYCoordinate();
    }

    /**
     * Gets the x coordinate of the INT {@link Tile} instance
     * that the associated {@link Node} instance stops at.
     * @return The tileXCoordinate of the INT tile that the associated {@link Node} instance stops at.
     */
    public short getEndTileXCoordinate() {
        return endTileXCoordinate;
    }

    /**
     * Gets the Y coordinate of the INT {@link Tile} instance
     * that the associated {@link Node} instance stops at.
     * For bidirectional nodes, the prev member is used to determine the end node.
     * @return The tileYCoordinate of the INT tile that the associated {@link Node} instance stops at.
     */
    public short getEndTileYCoordinate() {
        boolean reverseSLL = (getType() == RouteNodeType.SUPER_LONG_LINE &&
                prev != null &&
                prev.endTileYCoordinate == endTileYCoordinate);
        return reverseSLL ? (short) node.getTile().getTileYCoordinate() : endTileYCoordinate;
    }

    /**
     * Gets the base cost of a RouteNode Object.
     * @return The base cost of a RouteNode Object.
     */
    public float getBaseCost() {
        return baseCost;
    }

    /**
     * Gets the children of a RouteNode Object.
     * @return A list of RouteNode Objects.
     */
    public RouteNode[] getChildren() {
        return children != null ? children : EMPTY_ARRAY;
    }

    /**
     * Clears the children of this node so that it can be regenerated.
     */
    public void resetChildren() {
        children = null;
    }

    /**
     * Gets the wirelength.
     * @return The wirelength, i.e. the number of INT tiles that the associated {@link Node} instance spans.
     */
    public short getLength() {
        return length;
    }

    /**
     * Sets the lower bound total path cost.
     * @param totalPathCost The cost value to be set.
     */
    public void setLowerBoundTotalPathCost(float totalPathCost) {
        lowerBoundTotalPathCost = totalPathCost;
    }

    /**
     * Sets the upstream path cost.
     * @param newPartialPathCost The new value to be set.
     */
    public void setUpstreamPathCost(float newPartialPathCost) {
        this.upstreamPathCost = newPartialPathCost;
    }

    /**
     * Gets the lower bound total path cost.
     * @return The lower bound total path cost.
     */
    public float getLowerBoundTotalPathCost() {
        return lowerBoundTotalPathCost;
    }

    /**
     * Gets the upstream path cost.
     * @return The upstream path cost.
     */
    public float getUpstreamPathCost() {
        return upstreamPathCost;
    }

    /**
     * Gets a map that records users of a {@link RouteNode} instance based on all routed connections.
     * Each user is a {@link NetWrapper} instance representing a {@link Net} instance.
     * It is often the case that multiple connections of a net are using a same rnode.
     * So we count connections of each user to facilitate the sharing mechanism of RWRoute.
     * @return A map between users, i.e., {@link NetWrapper} instances representing by {@link Net} instances,
     *  and numbers of connections from different users.
     */
    public Map<NetWrapper, Integer> getUsersConnectionCounts() {
        return usersConnectionCounts;
    }

    /**
     * Adds an user {@link NetWrapper} instance to the user map, of which a key is a {@link NetWrapper} instance and
     * the value is the number of connections that are using a rnode.
     * If the user is already stored in the map, increment the connection count of the user by 1. Otherwise, put the user
     * into the map and initialize the connection count as 1.
     * @param user The user net in question.
     */
    public void incrementUser(NetWrapper user) {
        if (usersConnectionCounts == null) {
            usersConnectionCounts = new IdentityHashMap<>();
        }
        usersConnectionCounts.merge(user, 1, Integer::sum);
    }

    /**
     * Gets the number of unique users.
     * @return The number of unique {@link NetWrapper} instances in the user map, i.e, the key set size of the user map.
     */
    public int uniqueUserCount() {
        if (usersConnectionCounts == null) {
            return 0;
        }
        return usersConnectionCounts.size();
    }

    /**
     * Decrements the connection count of a user that is represented by a
     * {@link NetWrapper} instance corresponding to a {@link Net} instance.
     * If there is only one connection of the user that is using a RouteNode instance, remove the user from the map.
     * Otherwise, decrement the connection count by 1.
     * @param user The user to be decremented from the user map.
     */
    public void decrementUser(NetWrapper user) {
        usersConnectionCounts.compute(user, (k,v) -> (v == 1) ? null : v - 1);
    }

    /**
     * Counts the connections of a user that are using a rnode.
     * @param user The user in question indicated by a {@link NetWrapper} instance.
     * @return The total number of connections of the user.
     */
    public int countConnectionsOfUser(NetWrapper user) {
        if (usersConnectionCounts == null) {
            return 0;
        }
        return usersConnectionCounts.getOrDefault(user, 0);
    }

    /**
     * Gets the number of unique drivers.
     * @return The number of unique drivers of a rnode, i.e., the key set size of the driver map
     */
    public int uniqueDriverCount() {
        if (driversCounts == null) {
            return 0;
        }
        return driversCounts.size();
    }

    /**
     * Adds a driver to the driver map.
     * @param parent The driver to be added.
     */
    public void incrementDriver(RouteNode parent) {
        if (driversCounts == null) {
            driversCounts = new IdentityHashMap<>();
        }
        driversCounts.merge(parent, 1, Integer::sum);
    }

    /**
     * Decrements the driver count of a RouteNode instance.
     * @param parent The driver that should have its count reduced by 1.
     */
    public void decrementDriver(RouteNode parent) {
        driversCounts.compute(parent, (k,v) -> (v == 1) ? null : v - 1);
    }

    /**
     * Gets the number of users.
     * @return The number of users.
     */
    public int getOccupancy() {
        return uniqueUserCount();
    }

    /**
     * Gets the parent RouteNode instance for routing a connection.
     * @return The driving RouteNode instance.
     */
    public RouteNode getPrev() {
        return prev;
    }

    /**
     * Sets the parent RouteNode instance for routing a connection.
     * @param prev The driving RouteNode instance to set. Cannot be null.
     */
    public void setPrev(RouteNode prev) {
        assert(prev != null);
        this.prev = prev;
    }

    /**
     * Clears the parent RouteNode instance.
     */
    public void clearPrev() {
        this.prev = null;
    }

    /**
     * Gets the present congestion cost of a RouteNode Object.
     * @return The present congestion of a RouteNode Object.
     */
    public float getPresentCongestionCost() {
        return presentCongestionCost;
    }

    /**
     * Sets the present congestion cost of a RouteNode Object.
     * @param presentCongestionCost The present congestion cost to be set.
     */
    public void setPresentCongestionCost(float presentCongestionCost) {
        this.presentCongestionCost = presentCongestionCost;
    }

    /**
     * Gets the historical congestion cost of a RouteNode Object.
     * @return The historical congestion cost of a RouteNode Object.
     */
    public float getHistoricalCongestionCost() {
        return historicalCongestionCost;
    }

    /**
     * Gets the historical congestion cost of a RouteNode Object.
     * @param historicalCongestionCost The historical congestion cost to be set.
     */
    public void setHistoricalCongestionCost(float historicalCongestionCost) {
        this.historicalCongestionCost = historicalCongestionCost;
    }

    /**
     * Checks if a RouteNode instance has ever been expanded, as determined
     * by whether its children member is null.
     * @return true, if a RouteNode instance has been expanded before.
     */
    public boolean everExpanded() {
        return children != null;
    }

    /**
     * Checks if a RouteNode instance has been visited by a specific integer identifier.
     * @param id Integer identifier.
     * @return true, if a RouteNode instance has been visited before.
     */
    public boolean isVisited(int id) {
        assert(id > 0);
        return visited == id;
    }

    /**
     * Mark a RouteNode instance as being visited by a specific integer identifier.
     * @param id Integer identifier.
     */
    public void setVisited(int id) {
        assert(id > 0);
        visited = id;
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
                if (node.getWireName().contains("GLOBAL") || node.getWireName().contains("CTRL")) {
                    return true;
                }
            default:
        }
        return false;
    }

    /**
     * Checks if a routing arc must be included.
     * @param parent The routing arc's parent node.
     * @param child The routing arc's parent node.
     * @return True, if the arc should be included in the routing resource graph.
     */
    abstract public boolean mustInclude(Node parent, Node child);

    /**
     * Checks if a node has been preserved and thus cannot be used.
     * @param node The node in question.
     * @return True, if the arc should be excluded from the routing resource graph.
     */
    abstract public boolean isPreserved(Node node);

    /**
     * Checks if a routing arc has been excluded thus cannot be used.
     * @param parent The routing arc's parent node.
     * @param child The routing arc's parent node.
     * @return True, if the arc should be excluded from the routing resource graph.
     */
    abstract public boolean isExcluded(Node parent, Node child);

    abstract public int getSLRIndex();

    /**
     * @param index Bit index.
     * @return True if index is set.
     */
    public boolean getFlag(int index) {
        return (flags & (1 << index)) != 0;
    }

    /**
     * @param index Bit index to change.
     * @param value True to set. False to clear.
     */
    public void setFlag(int index, boolean value) {
        if (value) {
            flags |= (1 << index);
        } else {
            flags &= ~(1 << index);
        }
    }
}
