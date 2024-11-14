/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A RouteNode Object corresponds to a vertex of the routing resource graph.
 * Each RouteNode instance is associated with a {@link Node} instance. It is denoted as "rnode".
 * The routing resource graph is built "lazily", i.e., RouteNode Objects (rnodes) are created when needed.
 */
public class RouteNode extends Node implements Comparable<RouteNode> {
    /** Each RouteNode Object can be legally used by one net only */
    public static final short capacity = 1;
    /** Memoized static array for use by Collection.toArray() or similar */
    public static final RouteNode[] EMPTY_ARRAY = new RouteNode[0];
    public static final int initialPresentCongestionCost = 1;
    public static final int initialHistoricalCongestionCost = 1;

    /** The type of a rnode*/
    private byte type;
    /** The tileXCoordinate and tileYCoordinate of the INT tile that the associated node stops at */
    private final short endTileXCoordinate;
    private final short endTileYCoordinate;
    /** The wirelength of a rnode */
    private final short length;
    /** The base cost of a rnode */
    private float baseCost;
    /** A flag to indicate if this rnode is the target */
    private boolean isTarget;
    /** The children (downhill rnodes) of this rnode */
    protected RouteNode[] children;

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

    protected RouteNode(RouteNodeGraph routingGraph, Node node, RouteNodeType type) {
        super(node);
        RouteNodeInfo nodeInfo = RouteNodeInfo.get(node, routingGraph);
        this.type = (byte) ((type == null) ? nodeInfo.type : type).ordinal();
        endTileXCoordinate = nodeInfo.endTileXCoordinate;
        endTileYCoordinate = nodeInfo.endTileYCoordinate;
        length = nodeInfo.length;
        children = null;
        setBaseCost(routingGraph.design.getSeries());
        historicalCongestionCost = initialHistoricalCongestionCost;
        usersConnectionCounts = null;
        visited = 0;
        assert(prev == null);
        assert(!isTarget);
    }

    @Override
    public int compareTo(RouteNode that) {
        // Do not use Float.compare() since it also compares NaN, which we'll assume is unreachable
        // return Float.compare(this.lowerBoundTotalPathCost, that.lowerBoundTotalPathCost);
        return (int) Math.signum(this.lowerBoundTotalPathCost - that.lowerBoundTotalPathCost);
    }

    private void setBaseCost(Series series) {
        baseCost = 0.4f;
        switch (getType()) {
            case EXCLUSIVE_SOURCE:
                assert(length == 0 ||
                        (length <= 3 && (getIntentCode() == IntentCode.NODE_INTF2 || getIntentCode() == IntentCode.NODE_INTF4)));
                break;
            case EXCLUSIVE_SINK_BOTH:
            case EXCLUSIVE_SINK_EAST:
            case EXCLUSIVE_SINK_WEST:
                assert(length == 0 ||
                       (length == 1 && (series == Series.UltraScalePlus || series == Series.UltraScale) && getIntentCode() == IntentCode.NODE_PINBOUNCE));
                break;
            case LOCAL_BOTH:
                assert(length == 0);
                break;
            case LOCAL_EAST:
            case LOCAL_WEST:
            case LOCAL_RESERVED:
                assert(length == 0 ||
                       (length == 1 && (
                               ((series == Series.UltraScalePlus || series == Series.UltraScale) && getIntentCode() == IntentCode.NODE_PINBOUNCE) ||
                               (series == Series.UltraScalePlus && getWireName().matches("INODE_[EW]_\\d+_FT[01]")) ||
                               (series == Series.UltraScale && getWireName().matches("INODE_[12]_[EW]_\\d+_FT[NS]")) ||
                               (series == Series.Versal && EnumSet.of(IntentCode.NODE_CLE_BNODE, IntentCode.NODE_CLE_CNODE).contains(getIntentCode()))
                       ))
                   );
                break;
            case LAGUNA_PINFEED:
                // Make all approaches to SLLs zero-cost to encourage exploration
                // Assigning a base cost of zero would normally break congestion resolution
                // (since RWroute.getNodeCost() would return zero) but doing it here should be
                // okay because this node only leads to a SLL which will have a non-zero base cost
                baseCost = 0.0f;
                return;
            case SUPER_LONG_LINE:
                assert(getLength() == RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);
                baseCost = 0.3f * RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES;
                break;
            case NON_LOCAL:
                short length = getLength();
                // NOTE: IntentCode is device-dependent
                IntentCode ic = getIntentCode();
                switch(ic) {
                    case NODE_OUTPUT:        // CLE/LAGUNA_TILE/BRAM/etc. outputs (US)
                                             // LAG_LAG.LAG_LAGUNA_SITE_*_{T,R}XQ* (US+)
                    case NODE_CLE_OUTPUT:    // CLE outputs (US+ and Versal)
                    case NODE_LAGUNA_OUTPUT: // LAG_LAG.{LAG_MUX_ATOM_*_TXOUT,RXD*} (US+)
                    case NODE_LAGUNA_DATA:   // LAG_LAG.UBUMP* super long lines for u-turns at the boundary of the device (US+)
                    case NODE_PINFEED:
                    case INTENT_DEFAULT:     // INT.VCC_WIRE
                        assert(length == 0);
                        break;
                    case NODE_LOCAL: // US and US+
                        assert(length <= 1);
                        break;
                    case NODE_VSINGLE: // Versal-only
                    case NODE_HSINGLE: // Versal-only
                    case NODE_SINGLE:  // US and US+
                        if (length <= 1) {
                            assert(!getAllDownhillPIPs().isEmpty());
                        } else {
                            assert(length == 2);
                            baseCost *= length;
                        }
                        break;
                    case NODE_VDOUBLE: // Versal only
                    case NODE_HDOUBLE: // Versal only
                    case NODE_DOUBLE:  // US and US+
                        if (length == 0) {
                            assert(!getAllDownhillPIPs().isEmpty());
                        } else {
                            if (getBeginTileXCoordinate() != getEndTileXCoordinate()) {
                                assert(length <= 2);
                                // Typically, length = 1 (since tile X is not equal)
                                // In US, have seen length = 2, e.g. VU440's INT_X171Y827/EE2_E_BEG7.
                                if (length == 2) {
                                    baseCost *= length;
                                }
                            } else {
                                // Typically, length = 2 except for horizontal U-turns (length = 0)
                                // or vertical U-turns (length = 1).
                                // In US, have seen length = 3, e.g. VU440's INT_X171Y827/NN2_E_BEG7.
                                assert(length <= 3);
                            }
                        }
                        break;
                    case NODE_HQUAD:
                        if (length == 0) {
                            // Since this node has zero length (and by extension no downhill PIPs)
                            // mark it as being inacccessible so that it will never be queued
                            assert(getAllDownhillPIPs().isEmpty());
                            type = (byte) RouteNodeType.INACCESSIBLE.ordinal();
                        } else {
                            baseCost = 0.35f * length;
                        }
                        break;
                    case NODE_VQUAD:
                        if (length == 0) {
                            assert(!getAllDownhillPIPs().isEmpty());
                        } else {
                            // VQUADs have length 4 and 5
                            baseCost = 0.15f * length;
                        }
                        break;

                    case NODE_HLONG6:  // Versal only
                    case NODE_HLONG10: // Versal only
                        baseCost = 0.15f * (length == 0 ? 1 : length);
                        break;
                    case NODE_HLONG: // US/US+
                        if (length == 0) {
                            // Since this node has zero length (and by extension no downhill PIPs)
                            // mark it as being inacccessible so that it will never be queued
                            assert(getAllDownhillPIPs().isEmpty());
                            type = (byte) RouteNodeType.INACCESSIBLE.ordinal();
                        } else {
                            // HLONGs have length 6 and 7
                            baseCost = 0.15f * length;
                        }
                        break;
                    case NODE_VLONG7:  // Versal only
                    case NODE_VLONG12: // Versal only
                        baseCost = 0.15f * (length == 0 ? 1 : length);
                        break;
                    case NODE_VLONG:   // US/US+
                        if (length == 0) {
                            assert(!getAllDownhillPIPs().isEmpty());
                        }
                        baseCost = 0.7f;
                        break;

                    // Versal only
                    case NODE_SDQNODE:      // INT.INT_NODE_SDQ_ATOM_*_OUT[01]
                                            // INT.OUT_[NESW]NODE_[EW]_*
                        assert(length == 0 ||
                               // Feedthrough nodes to reach tiles immediately above/below
                               (length == 1 && getWireName().matches("OUT_[NESW]NODE_[EW]_\\d+")));
                        break;
                    default:
                        throw new RuntimeException(ic.toString());
                }
                break;
            default:
                throw new RuntimeException(getType().toString());
        }
        assert(baseCost > 0);
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

    public static short getLength(Node node) {
        return RouteNodeInfo.get(node, null).length;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("node " + super.toString());
        s.append(", ");
        s.append("(" + endTileXCoordinate + "," + getEndTileYCoordinate() + ")");
        s.append(", ");
        s.append(String.format("type = %s", getType()));
        s.append(", ");
        s.append(String.format("ic = %s", getIntentCode()));
        s.append(", ");
        s.append(String.format("user = %s", getOccupancy()));
        s.append(", ");
        s.append(getUsersConnectionCounts());
        return s.toString();
    }

    /**
     * Checks if coordinates of a RouteNode Object is within the connection's bounding box.
     * @param connection The connection that is being routed.
     * @return true, if coordinates of a RouteNode is within the connection's bounding box.
     */
    public boolean isInConnectionBoundingBox(Connection connection) {
        return endTileXCoordinate > connection.getXMinBB() && endTileXCoordinate < connection.getXMaxBB() &&
               endTileYCoordinate > connection.getYMinBB() && endTileYCoordinate < connection.getYMaxBB();
    }

    /**
     * Returns a deep copy of the Node associated with this RouteNode Object.
     * @return New Node deep copy.
     */
    public Node getNode() {
        return new Node(this);
    }

    /**
     * Checks if a RouteNode Object is the current routing target.
     * @return true, if a RouteNode Object is the current routing target.
     */
    public boolean isTarget() {
        return isTarget;
    }

    /**
     * Marks this node as a target, and adds it to state's targets list.
     * @param state State from the connection that is being routed.
     */
    public void markTarget(RWRoute.ConnectionState state) {
        isTarget = true;
        state.targets.add(this);
    }

    /*
     * Clears the target state on this node.
     */
    public void clearTarget() {
        assert(isTarget);
        isTarget = false;
    }

    /**
     * Gets the type of a RouteNode object.
     * @return The RouteNodeType of a RouteNode Object.
     */
    public RouteNodeType getType() {
        return RouteNodeType.values[type];
    }

    /**
     * Sets the type of a RouteNode object.
     * @param type New RouteNodeType value.
     */
    public void setType(RouteNodeType type) {
        assert(this.type == type.ordinal() ||
                // Support demotion from EXCLUSIVE_SINK to LOCAL since they have the same base cost
                (RouteNodeType.isAnyExclusiveSink(this.type) && type.isAnyLocal()) ||
                // Or promotion from LOCAL to EXCLUSIVE_SINK (by PartialRouter when NODE_PINBOUNCE on
                // a newly unpreserved net becomes a sink)
                (RouteNodeType.isAnyLocal(this.type) && type.isAnyExclusiveSink())) ||
                // Or promotion for any LOCAL to a LOCAL_RESERVED (by determineRoutingTargets()
                // for uphills of CTRL sinks)
                (RouteNodeType.isAnyLocal(this.type) && type == RouteNodeType.LOCAL_RESERVED);
        this.type = (byte) type.ordinal();
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
        Tile tile = getTile();
        return (tile.getTileTypeEnum() == TileTypeEnum.LAG_LAG) ? getEndTileXCoordinate()
                : (short) tile.getTileXCoordinate();
    }

    public short getBeginTileYCoordinate() {
        return (short) getTile().getTileYCoordinate();
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
        return reverseSLL ? (short) getTile().getTileYCoordinate() : endTileYCoordinate;
    }

    /**
     * Gets the base cost of a RouteNode Object.
     * @return The base cost of a RouteNode Object.
     */
    public float getBaseCost() {
        assert(getType() != RouteNodeType.EXCLUSIVE_SOURCE);
        return baseCost;
    }

    /**
     * Gets the children of a RouteNode Object.
     * @return A list of RouteNode Objects.
     */
    public RouteNode[] getChildren(RouteNodeGraph routingGraph) {
        if (children == null) {
            long start = RuntimeTracker.now();
            List<Node> allDownHillNodes = getAllDownhillNodes();
            List<RouteNode> childrenList = new ArrayList<>(allDownHillNodes.size());
            for (Node downhill : allDownHillNodes) {
                if (isExcluded(routingGraph, downhill)) {
                    continue;
                }

                RouteNode child = routingGraph.getOrCreate(downhill);
                if (child.getType() != RouteNodeType.INACCESSIBLE) {
                    childrenList.add(child);
                }
            }
            if (!childrenList.isEmpty()) {
                children = childrenList.toArray(EMPTY_ARRAY);
            } else {
                children = EMPTY_ARRAY;
            }
            long time = RuntimeTracker.elapsed(start);
            routingGraph.addCreateRnodeTime(time);
        }
        return children;

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
     * Gets the present congestion cost of a RouteNode Object.
     * @return The present congestion of a RouteNode Object.
     */
    public float getPresentCongestionCost(RouteNodeGraph routingGraph) {
        return routingGraph.getPresentCongestionCost(getOccupancy());
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
     * Get the number of children on this node without expanding.
     * @return Number of children on this node.
     */
    public int numChildren() {
        return children != null ? children.length : 0;
    }

    /**
     * Checks if a RouteNode instance has been visited by a specific connection sequence.
     * @param seq Connection sequence int.
     * @return true, if a RouteNode instance has been visited before.
     */
    public boolean isVisited(int seq) {
        return visited == seq;
    }

    /**
     * Gets the connection sequence that this RouteNode instance has been visited by.
     * @return Connection sequence int.
     */
    public int getVisited() {
        return visited;
    }

    /**
     * Mark a RouteNode instance as being visited by a specific integer identifier.
     * @param seq Integer identifier.
     */
    public void setVisited(int seq) {
        assert(seq > 0);
        visited = seq;
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
     * Checks if a downhill node has been excluded should not be present in the routing graph.
     * @param child The downhill node.
     * @return True, if the arc should be excluded from the routing resource graph.
     */
    public boolean isExcluded(RouteNodeGraph routingGraph, Node child) {
        return routingGraph.isExcluded(this, child);
    }

    public int getSLRIndex(RouteNodeGraph routingGraph) {
        return routingGraph.intYToSLRIndex[getEndTileYCoordinate()];
    }
}
