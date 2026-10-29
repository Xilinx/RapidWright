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

import com.xilinx.rapidwright.device.Node;

/**
 * Type of a {@link RouteNode}. Which side (half) of its INT tile a node is on is not part of its type,
 * but is described by the parity of its half-tile X coordinate (see {@link RouteNode#getEndHalfTileXCoordinate()}).
 */
public enum RouteNodeType {
    EXCLUSIVE_SOURCE,
    EXCLUSIVE_SINK,
    /**
     * Exclusive sink that is a CTRL pin, which can be reached from either side of its INT tile: on
     * UltraScale/UltraScale+, CTRL_* pins (only via INT_NODE_GLOBAL_*, see {@link #LOCAL_SIDE_DEPENDS_ON_SINK});
     * on Versal, CTRL pins (via {@link #LOCAL_RESERVED} nodes). Even so, the pin itself is on one side.
     */
    EXCLUSIVE_SINK_CTRL,
    /**
     * Exclusive sink that is a Versal SLL input, which is reached in the same way as a Versal
     * {@link #EXCLUSIVE_SINK_CTRL}, but is not on either side: it is between two INT tiles (on the east side of one,
     * and the west side of the next).
     */
    EXCLUSIVE_SINK_SLL_INPUT,
    EXCLUSIVE_SINK_NON_LOCAL,

    /**
     * Denotes {@link RouteNode} objects that correspond to a super long line {@link Node},
     * i.e. nodes that span two SLRs.
     */
    SUPER_LONG_LINE,

    /**
     * Denotes local {@link RouteNode} objects that correspond to {@link Node} objects leading to a SUPER_LONG_LINE
     * that crosses into another SLR: on UltraScale/UltraScale+, the INODEs and IMUXes that lead into a Laguna tile
     * (see RouteNodeGraph.populateLagunaLookups()); on Versal, SLL inputs (NODE_SLL_INPUT) that drive such a
     * SUPER_LONG_LINE (other SLL inputs are of type {@link #LOCAL}). Such nodes are only found in the band of rows next
     * to an SLR boundary, which gives the direction they cross in (see RouteNodeGraph.isNorthboundSLLRow()); they are
     * only accessible to connections that must cross in that direction (see RouteNodeGraph.isAccessible()), and are
     * otherwise treated as {@link #LOCAL} nodes, other than for their lower bounds (see
     * RWRoute.remainingCrossingCost()).
     */
    LOCAL_LEADING_TO_SLL,
    /**
     * Denotes non-local {@link RouteNode} objects that lead to {@link #LOCAL_LEADING_TO_SLL} nodes on
     * UltraScale/UltraScale+: the SDQ nodes and intra-tile singles that drive their INODEs (see
     * RouteNodeGraph.populateLagunaLookups()). Treated as {@link #NON_LOCAL} nodes, other than for their lower bounds
     * (see RWRoute.remainingCrossingCost()).
     */
    NON_LOCAL_LEADING_TO_SLL,

    NON_LOCAL,
    /**
     * Non-local nodes that serve both sides (halves) of the INT tile that they end at: UltraScale longs and those
     * INT_NODE_QUAD_LONG_* that drive quads on both sides, and Versal longs. Rather than being placed at either side,
     * these are considered to be on the same side as the node that the path arrived at them from (see
     * RWRoute.evaluateCostAndPush()), so that entering them neither gains nor loses distance to the sink in X.
     */
    NON_LOCAL_INHERIT_PREV_SIDE,

    /** Local nodes that serve only the side (half) of their INT tile that they are on */
    LOCAL,
    /**
     * Local nodes whose side depends on the sink: INT_NODE_GLOBAL_* (GNODEs, UltraScale/UltraScale+ only) serve
     * the INODEs on the side (half) of their INT tile that they are on, but the CTRL_* pins on both sides
     */
    LOCAL_SIDE_DEPENDS_ON_SINK,

    /**
     * Local nodes reserved for reaching {@link #EXCLUSIVE_SINK_CTRL} and {@link #EXCLUSIVE_SINK_SLL_INPUT} sinks
     * (Versal only): the [BC]NODEs uphill of
     * those sinks, which otherwise serve the INODEs on one side (half) of an INT tile; when in the same tile as
     * such a sink, these are considered to be on its side (as is {@link #LOCAL_SIDE_DEPENDS_ON_SINK})
     */
    LOCAL_RESERVED,

    /**
     * Denotes {@link RouteNode} objects that should be treated as being inaccessible and
     * never queued for exploration during routing. Typically, these are routing nodes that
     * have already been created but later discovered to not be needed (e.g. is a dead-end node).
     */
    INACCESSIBLE;

    public static final RouteNodeType[] values = values();

    public boolean isAnyExclusiveSink() {
        return this == EXCLUSIVE_SINK || this == EXCLUSIVE_SINK_CTRL || this == EXCLUSIVE_SINK_SLL_INPUT ||
               this == EXCLUSIVE_SINK_NON_LOCAL;
    }

    public static boolean isAnyExclusiveSink(int ordinal) {
        return ordinal == EXCLUSIVE_SINK.ordinal() || ordinal == EXCLUSIVE_SINK_CTRL.ordinal() ||
               ordinal == EXCLUSIVE_SINK_SLL_INPUT.ordinal() || ordinal == EXCLUSIVE_SINK_NON_LOCAL.ordinal();
    }

    /**
     * Checks if this is an exclusive sink that is only reachable from specific nodes in its tile (or its adjacent
     * INT tiles on Versal): {@link #EXCLUSIVE_SINK_CTRL} or {@link #EXCLUSIVE_SINK_SLL_INPUT}.
     * @return True if so.
     */
    public boolean isExclusiveSinkCtrlOrSllInput() {
        return this == EXCLUSIVE_SINK_CTRL || this == EXCLUSIVE_SINK_SLL_INPUT;
    }

    public boolean isAnyLocal() {
        return this == LOCAL || this == LOCAL_SIDE_DEPENDS_ON_SINK || this == LOCAL_RESERVED || this == LOCAL_LEADING_TO_SLL;
    }

    public static boolean isAnyLocal(int ordinal) {
        return ordinal == LOCAL.ordinal() || ordinal == LOCAL_SIDE_DEPENDS_ON_SINK.ordinal() || ordinal == LOCAL_RESERVED.ordinal() ||
               ordinal == LOCAL_LEADING_TO_SLL.ordinal();
    }

    /**
     * Checks if this is a local type whose nodes serve only the side (half) of their INT tile that they are on.
     * @return True if so.
     */
    public boolean isSidedLocal() {
        return this == LOCAL || this == LOCAL_LEADING_TO_SLL;
    }

    public boolean isLocalLeadingToSLL() {
        return this == LOCAL_LEADING_TO_SLL;
    }
}
