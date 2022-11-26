/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.RuntimeTracker;

/**
 * A RouteNode Object corresponds to a vertex of the routing resource graph.
 * Each RouteNode instance is associated with a {@link Node} instance. It is denoted as "rnode".
 * The routing resource graph is built "lazily", i.e., RouteNode Objects (rnodes) are created when needed.
 */
abstract public class RouteNode {
    /** Each RouteNode Object can be legally used by one net only */
    public static final short capacity = 1;
    /** Memoized static array for use by Collection.toArray() or similar */
    public static final RouteNode[] EMPTY_ARRAY = new RouteNode[0];

    /** The associated {@link Node} instance */
    protected Node node;
    /** The type of a rnode*/
    protected RouteNodeType type;
    /** The tileXCoordinate and tileYCoordinate of the INT tile that the associated node stops at */
    protected short endTileXCoordinate;
    protected short endTileYCoordinate;
    /** The wirelength of a rnode */
    protected short length;
    /** The base cost of a rnode */
    protected float baseCost;
    /** The children (downhill rnodes) of this rnode */
    protected RouteNode[] children;
    /** The parents (uphill rnodes) of this rnode */
    protected RouteNode[] parents;

    private float presentCongestionCost;
    private float historicalCongestionCost;
    private float knownCostFromSource;
    private float knownCostFromSink;
    private float totalCostToSink;
    private float totalCostToSource;
    /** A variable that stores the parent of a rnode during expansion to facilitate tracing back */
    protected RouteNode prev;
    protected RouteNode next;
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
        setType(type);
        children = null;
        setEndTileXYCoordinates();
        setBaseCost(type);
        presentCongestionCost = 1;
        historicalCongestionCost = 1;
        usersConnectionCounts = null;
        driversCounts = null;
        assert(prev == null);
        assert(next == null);
    }

    public int compareTo(RouteNode that) {
        // Do not use Float.compare() since it also compares NaN, which we'll assume is unreachable
        // return Float.compare(this.totalCostToSink, that.totalCostToSink);
        float signum = Math.signum(this.totalCostToSink - that.totalCostToSink);
        // Tie break according to larger known cost (thus smaller estimated cost to target)
        return (int) ((signum != 0) ? signum : Math.signum(that.knownCostFromSource - this.knownCostFromSource));
    }

    public int compareToBack(RouteNode that) {
        // Do not use Float.compare() since it also checks NaN, which we'll assume is unreachable
        // return Float.compare(this.lowerBoundTotalPathCost, that.lowerBoundTotalPathCost);
        // return (int) Math.signum(this.totalCostToSource - that.totalCostToSource);
        float signum = Math.signum(this.totalCostToSource - that.totalCostToSource);
        // Tie break according to larger known cost (thus smaller estimated cost to target)
        return (int) ((signum != 0) ? signum : Math.signum(that.knownCostFromSink - this.knownCostFromSink));
    }

    abstract protected RouteNode getOrCreate(Node node, RouteNodeType type);

    protected void setChildrenParents(boolean forward, RuntimeTracker timer) {
        if ((forward && children != null) || (!forward && parents != null))
            return;
        timer.start();
        Node head = node;
        List<Node> tails = (forward) ? head.getAllDownhillNodes() :
                head.getAllUphillNodes();
        List<RouteNode> list = new ArrayList<>(tails.size());
        for (Node tail : tails) {
            if (!mustInclude(forward, head, tail)) {
                if (isPreserved(forward, tail) || isExcluded(forward, head, tail))
                    continue;
            }

            final RouteNodeType type = RouteNodeType.WIRE;
            RouteNode tailRnode = getOrCreate(tail, type);
            list.add(tailRnode);
        }
        RouteNode[] array = (list.isEmpty()) ? EMPTY_ARRAY : list.toArray(EMPTY_ARRAY);
        if (forward)
            children = array;
        else
            parents = array;
        timer.stop();
    }

    protected void setBaseCost(RouteNodeType type) {
        // TODO: Why does enabling the following line cause unroutability?
        //       setRouteType() is called before this, and it setting
        //       this.type disrupts the base cost such that
        //       testNonTimingDrivenPartialRouting becomes unroutable.
        //       The `type` parameter fed to this method is the original
        //       value provided to the constructor
        // type = this.type;
        if (this.type == RouteNodeType.LAGUNA_I || this.type == RouteNodeType.LAGUNA_O) {
            // Make all approaches to SLLs zero-cost to encourage exploration
            // Assigning a base cost of zero would break congestion resolution for most nodes
            // (since RWroute.getNodeCost() would return zero) but doing it here should be
            // okay because this node only leads to a SLL which will have a non-zero base cost
            baseCost = 0.0f;
        } else if (this.type == RouteNodeType.SUPER_LONG_LINE) {
            if (length != 0) {
                baseCost = 0.3f * length;
            } else {
                // U-turn node at the boundary of the device; must have a non-zero cost for
                // congestion resolution to work
                baseCost = 0.4f;
            }
        } else if (type == RouteNodeType.WIRE) {
            baseCost = 0.4f;
            // NOTE: IntentCode is device-dependent
            IntentCode ic = node.getIntentCode();
            switch(ic) {
            case NODE_PINBOUNCE:
            case NODE_PINFEED:
                break;
            case NODE_DOUBLE:
                if (endTileXCoordinate != node.getTile().getTileXCoordinate()) {
                    baseCost = 0.4f*length;
                }
                break;
            case NODE_HQUAD:
                assert(length != 0 || node.getAllDownhillNodes().isEmpty());
                baseCost = 0.35f*length;
                break;
            case NODE_VQUAD:
                // In case of U-turn nodes
                if (length != 0) baseCost = 0.15f*length;// VQUADs have length 4 and 5
                break;
            case NODE_HLONG:
                assert(length != 0 || node.getAllDownhillNodes().isEmpty());
                baseCost = 0.15f*length;// HLONGs have length 6 and 7
                break;
            case NODE_VLONG:
                // Not true for UltraScale? (e.g. seen on VU440)
                // assert(length != 0);
                baseCost = 0.7f;
                break;
            default:
                if (length != 0) baseCost *= length;
                break;
            }
        } else if (type == RouteNodeType.PINFEED_I) {
            baseCost = 0.4f;
        } else if (type == RouteNodeType.PINFEED_O) {
            baseCost = 1f;
        }

        // Node.getNode("INT_X182Y535/WW4_W_BEG7", Device.getDevice("xcvu19p")).getAllWiresInNode()
        // returns two wires, both X182Y535
        // assert(baseCost != 0);
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

    public static final EnumSet<TileTypeEnum> lagunaTileTypes = EnumSet.of(
              TileTypeEnum.LAG_LAG      // UltraScale+
            , TileTypeEnum.LAGUNA_TILE  // UltraScale
    );

    public static int getLength(Node node, RouteNode that) {
        Wire[] wires = node.getAllWiresInNode();
        Tile baseTile = node.getTile();
        Tile endTile = null;
        boolean pinfeedToLagunaI = false;
        for (Wire w : wires) {
            Tile tile = w.getTile();
            TileTypeEnum tileType = tile.getTileTypeEnum();
            if (tileType == TileTypeEnum.INT ||
                    (pinfeedToLagunaI = (that != null && that.type == RouteNodeType.PINFEED_I && lagunaTileTypes.contains(tileType)))) {
                boolean endTileWasNotNull = (endTile != null);
                endTile = tile;
                // Break if this is the second INT tile
                if (endTileWasNotNull) break;
            }
        }
        if (endTile == null) {
            endTile = node.getTile();
        }

        int endTileXCoordinate = endTile.getTileXCoordinate();
        int endTileYCoordinate = endTile.getTileYCoordinate();

        if (pinfeedToLagunaI) {
            assert(node.getIntentCode() == IntentCode.NODE_PINFEED);
            // This is an IntentCode.NODE_PINFEED originating from an INT but going into a Laguna tile;
            // amend it to be RouteNodeType.LAGUNA_I so it can benefit from a base cost discount
            that.type = RouteNodeType.LAGUNA_I;
            if (endTile.getTileTypeEnum() == TileTypeEnum.LAG_LAG) {// UltraScale+
                // Correct the fact that US+ have their Laguna tiles off by one compared to the INT tile
                // they are attached to
                endTileXCoordinate++;
            } else {
                assert(endTile.getTileTypeEnum() == TileTypeEnum.LAGUNA_TILE);
            }
            assert(endTileYCoordinate == baseTile.getTileYCoordinate());
        }

        int length = Math.abs(endTileXCoordinate - baseTile.getTileXCoordinate())
                + Math.abs(endTileYCoordinate - baseTile.getTileYCoordinate());

        if (that != null) {
            that.endTileXCoordinate = (short) endTileXCoordinate;
            that.endTileYCoordinate = (short) endTileYCoordinate;
            that.length = (short) length;
        }

        return length;
    }

    protected void setEndTileXYCoordinates() {
        getLength(node, this);
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
        return toString(true);
    }

    public String toString(boolean forward) {
        StringBuilder s = new StringBuilder();
        s.append("node " + node.toString());
        s.append(", ");
        s.append("(" + getTileXCoordinate(forward) + "," + getTileYCoordinate(forward) + ")");
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

    public boolean isIntersection() {
        return isVisited(true) && isVisited(false);
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

    public short getTileXCoordinate(boolean end) {
        return end ? getEndTileXCoordinate() : getBeginTileXCoordinate();
    }

    public short getTileYCoordinate(boolean end) {
        return end ? getEndTileYCoordinate() : getBeginTileYCoordinate();
    }

    public short getBeginTileXCoordinate() {
        return (short) node.getTile().getTileXCoordinate();
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
     * @return The tileYCoordinate of the INT tile that the associated {@link Node} instance stops at.
     */
    public short getEndTileYCoordinate() {
        return endTileYCoordinate;
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
    private RouteNode[] getChildren() {
        return children != null ? children : EMPTY_ARRAY;
    }

    /**
     * Gets the parents of a RouteNode Object.
     * @return A list of RouteNode Objects.
     */
    private RouteNode[] getParents() {
        return parents != null ? parents : EMPTY_ARRAY;
    }

    public RouteNode[] getChildrenParents(boolean forward) {
        return forward ? getChildren() : getParents();
    }

    /**
     * Clears the children of this node so that it can be regenerated.
     */
    public void resetChildren() {
        children = null;
    }

    /**
     * Clears the parents of this node so that it can be regenerated.
     */
    public void resetParents() {
        parents = null;
    }

    protected void setType(RouteNodeType type) {
        this.type = type;
        if (type == RouteNodeType.WIRE) {
            // NOTE: IntentCode is device-dependent
            IntentCode ic = node.getIntentCode();
            switch (ic) {
                case NODE_PINBOUNCE:
                    this.type = RouteNodeType.PINBOUNCE;
                    break;

                // Could be PINFEED into SLICE or PINFEED into a Laguna
                // (the latter case is updated by getLength())
                case NODE_PINFEED:
                    this.type = RouteNodeType.PINFEED_I;
                    break;
            }
        }
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
     * @param cost The cost value to be set.
     */
    public void setTotalCost(boolean forward, float cost) {
        if (forward) {
            totalCostToSink = cost;
        } else {
            totalCostToSource = cost;
        }
    }

    /**
     * Gets the lower bound total path cost.
     * @return The lower bound total path cost.
     */
    public float getTotalCost(boolean forward) {
        return forward ? totalCostToSink : totalCostToSource;
    }

    /**
     * Gets the upstream path cost.
     * @return The upstream path cost.
     */
    public float getKnownCost(boolean forward) {
        return forward ? knownCostFromSource : knownCostFromSink;
    }

    public void setKnownCost(boolean forward, float cost) {
        if (forward) {
            knownCostFromSource = cost;
        } else {
            knownCostFromSink = cost;
        }
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
            usersConnectionCounts = new HashMap<>();
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
            driversCounts = new HashMap<>();
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

    public RouteNode getNext() {
        return next;
    }

    /**
     * Sets the parent RouteNode instance for routing a connection.
     * @param prev The driving RouteNode instance to set.
     */
    public void setPrev(RouteNode prev) {
        assert(prev != null);
        this.prev = prev;
    }

    public void setNext(RouteNode next) {
        assert(next != null);
        this.next = next;
    }

    public void setPrevNext(boolean forward, RouteNode prevNext) {
        if (forward) {
            setPrev(prevNext);
        } else {
            setNext(prevNext);
        }
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
     * Checks if a RouteNode instance has been visited before when routing a connection.
     * @return true, if a RouteNode instance has been visited before.
     */
    abstract public boolean isVisited(boolean forward);

    abstract public void setVisited(boolean forward);

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
    abstract public boolean mustInclude(boolean forward, Node parent, Node child);

    /**
     * Checks if a node has been preserved and thus cannot be used.
     * @param node The node in question.
     * @return True, if the arc should be excluded from the routing resource graph.
     */
    abstract public boolean isPreserved(boolean forward, Node node);

    /**
     * Checks if a routing arc has been excluded thus cannot be used.
     * @param parent The routing arc's parent node.
     * @param child The routing arc's parent node.
     * @return True, if the arc should be excluded from the routing resource graph.
     */
    abstract public boolean isExcluded(boolean forward, Node parent, Node child);

    abstract public int getSLRIndex(boolean forward);

    public int getSLRDistance(boolean forward, RouteNode that) {
        final int thisSLR = getSLRIndex(forward);
        final int thatSLR = that.getSLRIndex(forward);
        return Math.abs(thatSLR - thisSLR);
    }
}
