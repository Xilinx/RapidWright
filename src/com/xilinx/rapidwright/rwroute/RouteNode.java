/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.util.Utils;

import java.util.ArrayList;
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
    /**
     * The X coordinate, in half tiles, of the INT tile half that the associated node stops at: twice its
     * tileXCoordinate for its west half, plus one for its east half.
     * Not final: {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} nodes serve both sides of their INT tile, so
     * their side (the lowest bit) is not fixed but set during routing to that of the node they are reached from on
     * the current path (see {@link #setInheritedSide(RouteNode)}). This lets each such node's side be read directly
     * (rather than recomputed by walking back along the path), and is safe since, as with {@link #prev}, it is
     * written once per search (when the node is pushed) before being read by its children.
     */
    private short endHalfTileXCoordinate;
    /** The tileYCoordinate of the INT tile that the associated node stops at */
    private final short endTileYCoordinate;
    /** The wirelength of a rnode, in half INT tiles (see {@link RouteNodeInfo#halfTileWirelength}) */
    private final short halfTileWirelength;
    /** The base cost of a rnode */
    private float baseCost;
    /*
     * Cost model. With RWRoute's default weights, for a connection with criticality 0 (as all connections are when not
     * timing-driven) and on nodes not already used by its net, a node's lower bound is its path cost plus 0.8 per INT
     * tile still to travel to the sink, and its path cost includes a wirelength cost of 0.2 per tile travelled (see
     * getHalfTileWirelength()); so every single/double/quad/long travelling towards the sink reduces the lower bound by
     * g = 0.8 - 0.2 - BASE_COST_PER_TILE = 0.5 per tile, in either axis. (All of these, but not PENDING_NODE_COST, are
     * divided by a sharing factor on nodes already used by the net; dividing it too was measured to cost wirelength.)
     */
    /**
     * Base cost per INT tile of the nominal distance travelled by singles, doubles, quads and longs, in either axis
     * (see setBaseCost())
     */
    public static final float BASE_COST_PER_TILE = 0.1f;
    /**
     * Base cost of nodes that do not travel (e.g. SDQ nodes, INODEs, IMUXes and bounces) and other nodes that are not
     * singles/doubles/quads/longs or super long lines; chosen empirically: lower values make chains of such nodes
     * cheaper but cost wirelength and runtime on whole designs. A chain of three of them (1.2) costs less than the least
     * increase in lower bound of a single/double/quad/long leaving the half of the INT tile that the sink is on (a
     * horizontal or intra-tile single: 0.5 * BASE_COST_PER_TILE, plus a wirelength cost of 0.1, plus half a tile (0.4)
     * further from the sink, plus PENDING_NODE_COST, i.e. 1.3).
     */
    public static final float LOCAL_BASE_COST = 0.4f;
    /**
     * Added by RWRoute to the lower bound (but not the path cost) of single/double/quad/long nodes: a bias, more than
     * LOCAL_BASE_COST, that is removed again by the node that follows (since every single/double/quad/long must next
     * pass through a node that does not travel, e.g. an SDQ node or INODE, or another long), so as to explore depth-first
     * after a single/double/quad/long lands. Having landed one (with lower bound L), each of the nodes that it drives
     * (at L + LOCAL_BASE_COST - this) is explored before any other node with a lower bound of at least L, and from the
     * first of them, a vertical double, a horizontal quad or longer travelling towards the sink (at no more than
     * L + LOCAL_BASE_COST - 2g) is explored before the others of them, but not a vertical single or horizontal double
     * (at L + LOCAL_BASE_COST - g) nor a horizontal single. That requires max(LOCAL_BASE_COST plus any bias cost, g)
     * < this < 2g, i.e. about 0.5-0.6 < this < 1.0; this is the middle of that window.
     */
    public static final float PENDING_NODE_COST = 0.75f;
    /**
     * The least base cost that congestion is applied to (see RWRoute.getNodeCost()), so that nodes with a smaller base
     * cost (e.g. singles, which travel a shorter distance) are not discouraged so much less by the same congestion;
     * chosen empirically.
     */
    public static final float MIN_CONGESTED_BASE_COST = 0.2f;
    /**
     * Added by RWRoute to the lower bound of a node on either side of a super long line, once for each node of that
     * chain that remains (see RWRoute.remainingCrossingCost()), so that the lower bound falls slightly along the chain
     * and ties between chains are broken depth-first. Small compared with LOCAL_BASE_COST, so as not to reorder
     * anything else.
     */
    public static final float SLL_CHAIN_TIE_BREAK = 0.03f;
    /**
     * Added by RWRoute to the lower bound of the children of the last node on the receiving side of a super long line
     * (the landing node: an SDQ node on UltraScale/UltraScale+, an [A-H]Q* CLE output on Versal; see
     * RWRoute.remainingCrossingCost()), so that all the landing nodes driven by the same super long line are explored
     * before any of their children. Otherwise a vertical double from the first landing node explored, whose lower bound
     * falls by 2g - PENDING_NODE_COST = 0.25 (and which on UltraScale+ may land on the same SDQ node again, two tiles
     * on), would be followed before a sibling landing node's longer wire, whose lower bound falls by at least
     * 3g - PENDING_NODE_COST = 0.75 (Versal's HLONG6; 4g - PENDING_NODE_COST = 1.25 for an UltraScale(+) quad). This
     * must lie between those drops.
     */
    public static final float SLL_LANDING_TIE_BREAK = 0.3f;
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

    /** Flag to enable really comprehensive (but performance-impacting) assertions */
    protected final static boolean enableComprehensiveAssertions = false;

    protected RouteNode(RouteNodeGraph routingGraph, Node node, RouteNodeType type) {
        super(node);
        RouteNodeInfo nodeInfo = RouteNodeInfo.get(node, routingGraph);
        this.type = (byte) ((type == null) ? nodeInfo.type : type).ordinal();
        endHalfTileXCoordinate = nodeInfo.endHalfTileXCoordinate;
        endTileYCoordinate = nodeInfo.endTileYCoordinate;
        halfTileWirelength = nodeInfo.halfTileWirelength;
        children = null;
        setBaseCost();
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

    /**
     * Checks if this node ends in the INT tile that it begins in.
     * @return True if so.
     */
    private boolean endsInBeginTile() {
        return getBeginTileXCoordinate() == getEndTileXCoordinate() && getBeginTileYCoordinate() == endTileYCoordinate;
    }

    /**
     * Sets the base cost of this node: {@link #BASE_COST_PER_TILE} for each tile of its nominal wirelength (see
     * {@link RouteNodeInfo#halfTileWirelength}) for singles, doubles, quads, longs and super long lines, or else
     * {@link #LOCAL_BASE_COST}.
     */
    private void setBaseCost() {
        baseCost = LOCAL_BASE_COST;
        switch (getType()) {
            case EXCLUSIVE_SOURCE:
            case EXCLUSIVE_SINK:
            case EXCLUSIVE_SINK_CTRL:
            case EXCLUSIVE_SINK_SLL_INPUT:
            case LOCAL_SIDE_DEPENDS_ON_SINK:
            case LOCAL:
            case LOCAL_RESERVED:
            case LOCAL_LEADING_TO_SLL:
                // Including INODE/BOUNCE feedthroughs, which are charged wirelength (see
                // RouteNodeInfo.halfTileWirelength) but not costed as wires
                assert(halfTileWirelength == 0 || getIntentCode() == IntentCode.NODE_LOCAL ||
                        getIntentCode() == IntentCode.NODE_PINBOUNCE);
                break;
            case SUPER_LONG_LINE:
                // Nominally travel the whole length of the super long line (even when U-turning at the edge of the
                // device), costed as other wires are
                baseCost = BASE_COST_PER_TILE * 0.5f * halfTileWirelength;
                break;
            case NON_LOCAL_LEADING_TO_SLL:
            case NON_LOCAL:
            case NON_LOCAL_INHERIT_PREV_SIDE:
                // NOTE: IntentCode is device-dependent
                IntentCode ic = getIntentCode();
                switch(ic) {
                    case NODE_OUTPUT:        // CLE/LAGUNA_TILE/BRAM/etc. outputs (US)
                                             // LAG_LAG.LAG_LAGUNA_SITE_*_{T,R}XQ* (US+)
                    case NODE_CLE_OUTPUT:    // CLE outputs (US+ and Versal)
                    case NODE_LAGUNA_OUTPUT: // LAG_LAG.{LAG_MUX_ATOM_*_TXOUT,RXD*} (US+)
                    case NODE_LAGUNA_DATA:   // LAG_LAG.UBUMP* super long lines for u-turns at the boundary of the device (US+)
                    case NODE_SLL_INPUT:     // Versal only
                    case NODE_SLL_OUTPUT:    // Versal only
                    case INTENT_DEFAULT:     // INT.VCC_WIRE
                    case NODE_SDQNODE:       // Versal only: INT.INT_NODE_SDQ_ATOM_*_OUT[01]
                                             //              INT.OUT_[NESW]NODE_[EW]_*
                        assert(halfTileWirelength == 0);
                        break;
                    case NODE_LOCAL:         // US and US+
                        break;

                    case NODE_VDOUBLE: // Versal only
                    case NODE_HDOUBLE: // Versal only
                        if (getAllWiresInNode().length == 1) {
                            // e.g. INT_X2Y382/OUT_NN2_W_BEG2 and INT_X18Y384/OUT_WW2_W_BEG4 on vp1002
                            assert(getAllDownhillPIPs().isEmpty());
                            // This node has no downhill PIPs, mark these as inaccessible so that it will never be queued
                            type = (byte) RouteNodeType.INACCESSIBLE.ordinal();
                        }
                        // Fall through
                    case NODE_SINGLE:  // US and US+
                    case NODE_DOUBLE:  // US and US+
                    case NODE_HSINGLE: // Versal only
                    case NODE_VSINGLE: // Versal only
                    case NODE_VQUAD:   // US/US+/Versal
                    case NODE_VLONG:   // US/US+
                    case NODE_HLONG6:  // Versal only
                    case NODE_HLONG10: // Versal only
                    case NODE_VLONG7:  // Versal only
                    case NODE_VLONG12: // Versal only
                        // All singles, doubles, quads and longs are costed by their nominal length (see
                        // RouteNodeInfo.getNominalHalfTileWirelength()), whatever their measured length: including
                        // U-turns at the edge of the device, intra-tile singles between the halves of their INT tile,
                        // and the feedthrough member of each family (one BEG index per INT tile on UltraScale(+), which
                        // carries an extra *_BLN_*/*_BLS_* wire one tile further on, as measured on xcvu440 and xcvu3p):
                        //  - vertical ones (UltraScale SS1/NN2/NN5, UltraScale+ NN4/SS4): their END wire at the nominal
                        //    distance has no downhill PIPs, so they can only be left one tile further on (UltraScale's
                        //    NN16 can be left at both);
                        //  - horizontal ones (UltraScale EE2/EE4/WW12, UltraScale+ EE12/WW1_W): their END wire at the
                        //    nominal distance can still be left, with the extra wire one row over as another exit;
                        //  - EE1_W_0_FTS (UltraScale) and WW1_E_7_FT0 (UltraScale+), the only wires of their names: no
                        //    horizontal reach, but exits in their own tile and one tile south/north respectively
                        baseCost = BASE_COST_PER_TILE * 0.5f * halfTileWirelength;
                        break;
                    case NODE_HQUAD: // US/US+/Versal
                    case NODE_HLONG: // US/US+
                        if (endsInBeginTile()) {
                            // Since this node does not leave its tile (and asserted to have no downhill PIPs)
                            // mark it as being inacccessible so that it will never be queued
                            assert(getAllDownhillPIPs().isEmpty());
                            type = (byte) RouteNodeType.INACCESSIBLE.ordinal();
                        }
                        baseCost = BASE_COST_PER_TILE * 0.5f * halfTileWirelength;
                        break;

                    // These nodes can appear as a result of the Vivado high-fanout net optimization that
                    // routes-thru leaf clock buffers; currently, they can only appear on preserved
                    // portions of partially-routed nets
                    case NODE_GLOBAL_LEAF:
                    case NODE_GLOBAL_HDISTR:
                    case NODE_PINFEED: // BUFCE_{LEAF,ROW_FSR}.CE routethru input
                        // Assume this block is only reached for a preserved net -- it's not straightforward
                        // to assert this since this net has not yet been preserved (and may even do asynchronously)
                        // nor is the prev pointer set yet
                        assert(RouteNodeGraph.isExcludedTile(this));
                        assert(Utils.isClocking(getTile().getTileTypeEnum()));
                        break;
                    default:
                        throw new RuntimeException("ERROR: Unexpected IntentCode: " + ic + " for " + super.toString());
                }
                break;
            default:
                throw new RuntimeException(getType().toString());
        }
        assert(baseCost > 0);
    }

    /**
     * Checks if an intent code is that of a single, double, quad or long (on UltraScale, UltraScale+ or Versal).
     * @param ic Intent code in question.
     * @return True if so.
     */
    public static boolean isSingleDoubleQuadLong(IntentCode ic) {
        switch (ic) {
            case NODE_SINGLE:   // US and US+
            case NODE_DOUBLE:   // US and US+
            case NODE_HSINGLE:  // Versal only
            case NODE_VSINGLE:  // Versal only
            case NODE_HDOUBLE:  // Versal only
            case NODE_VDOUBLE:  // Versal only
            case NODE_HQUAD:
            case NODE_VQUAD:
            case NODE_HLONG:    // US and US+
            case NODE_VLONG:    // US and US+
            case NODE_HLONG6:   // Versal only
            case NODE_HLONG10:  // Versal only
            case NODE_VLONG7:   // Versal only
            case NODE_VLONG12:  // Versal only
                return true;
            default:
                return false;
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

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("node " + super.toString());
        s.append(", ");
        s.append("(" + getEndTileXCoordinate() + (isOnEastSide() ? "E" : "W") + "," + getEndTileYCoordinate() + ")");
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
        short endTileXCoordinate = getEndTileXCoordinate();
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
     * Checks if this RouteNode object ends on the east side of its INT tile (otherwise, the west side).
     * For {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} nodes, this is the side inherited on the path that most
     * recently reached them (see {@link #setInheritedSide(RouteNode)}).
     * @return True if so.
     */
    public boolean isOnEastSide() {
        return (endHalfTileXCoordinate & 1) != 0;
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
                (RouteNodeType.isAnyLocal(this.type) && type.isAnyExclusiveSink()) ||
                // Or promotion for any LOCAL to a LOCAL_RESERVED (by determineRoutingTargets() for uphills of CTRL
                // sinks, before any routing)
                (RouteNodeType.isAnyLocal(this.type) && type == RouteNodeType.LOCAL_RESERVED && visited == 0) ||
                // Or promotions to EXCLUSIVE_SINK_NON_LOCAL from NON_LOCAL (by PartialRouter.determineRoutingTargets()
                // for the begin node of a locked path to sinks, before any routing)
                ((this.type == RouteNodeType.NON_LOCAL.ordinal() || this.type == RouteNodeType.NON_LOCAL_INHERIT_PREV_SIDE.ordinal()) &&
                        type == RouteNodeType.EXCLUSIVE_SINK_NON_LOCAL && visited == 0)
        );
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
        return (short) (endHalfTileXCoordinate >> 1);
    }

    /**
     * Gets the x coordinate, in half tiles, of the INT {@link Tile} half
     * that the associated {@link Node} instance stops at (for {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE}
     * nodes, the half inherited on the path that most recently reached them; see {@link #setInheritedSide(RouteNode)}).
     * @return Twice the tileXCoordinate of the INT tile that the associated {@link Node} instance stops at,
     * plus one if it stops at the east half of that tile.
     */
    public short getEndHalfTileXCoordinate() {
        return endHalfTileXCoordinate;
    }

    /**
     * Sets the side (half) of its INT tile that a {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} node (which serves
     * both sides) is considered to end on, as inherited from the node that it is reached from on the current path.
     * Like the prev pointer, this is only valid within the search that set it.
     * @param prev Node that this node is reached from (whose own side has already been set, if it is also such a node).
     */
    public void setInheritedSide(RouteNode prev) {
        assert(getType() == RouteNodeType.NON_LOCAL_INHERIT_PREV_SIDE);
        endHalfTileXCoordinate = (short) ((endHalfTileXCoordinate & ~1) | (prev.endHalfTileXCoordinate & 1));
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
     * Gets the wirelength, in half INT tiles (see {@link RouteNodeInfo#halfTileWirelength}).
     * @return The wirelength in half INT tiles.
     */
    public short getHalfTileWirelength() {
        return halfTileWirelength;
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
