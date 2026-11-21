/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extends {@link RouteNodeGraph} with timing-driven capabilities.
 */
public class RouteNodeGraphTimingDriven extends RouteNodeGraph {
    /** The instantiated delayEstimator to compute delays */
    protected final DelayEstimator delayEstimator;

    protected RouteNodeGraphTimingDriven(Design design,
                                         RWRouteConfig config,
                                         DelayEstimator delayEstimator) {
        super(design, config);
        this.delayEstimator = delayEstimator;
    }

    @Override
    public DelayEstimator getDelayEstimator() {
        return delayEstimator;
    }

    /**
     * Gets the LUT logic delay with a connection arriving at the given sink rnode (one of its
     * candidate LUT input pins, with LUT pin swapping): the worst of the timing analysis' LUT arcs
     * from the LUT input(s) that the connection feeds, with the input on that pin, or zero if there
     * are none (an input that the LUT's function does not depend on has no arc), or if the
     * connection's sink pin cannot be swapped. The delays are those of the timing analysis' arc
     * provider (the fitted cell arcs by default), looked up as needed rather than stored per
     * connection.
     * @param timingManager The timing manager.
     * @param connection The connection.
     * @param sinkRnode One of the connection's sink rnodes.
     * @param updateTiming True to also set those LUT arcs' delays in the timing analysis.
     * @return The logic delay in ps.
     */
    @Override
    protected float getLutLogicDelay(TimingManager timingManager, Connection connection, RouteNode sinkRnode,
                                     boolean updateTiming) {
        // Only LUT pin swapping gives a connection alternate sinks
        if (!connection.hasAltSinks()) {
            return 0;
        }
        String pinName = RouterHelper.getLutInputSitePin(sinkRnode).getPinName();
        return timingManager.getLutLogicDelay(connection, pinName, updateTiming);
    }

    /**
     * Sets, for each of the connection's targets (its candidate LUT input pins), the delay that the
     * timing analysis charges for arriving there in excess of that of the fastest target: its LUT
     * logic delay (see {@link #getLutLogicDelay}) plus the delay of the sink pin (see
     * {@link DelayEstimator#getPinDelay(SitePinInst, Site, String)}).
     * @param state State from the connection that is being routed, with its targets marked.
     * @param timingManager The timing manager.
     */
    @Override
    protected void setTargetExcessLogicDelays(RWRoute.ConnectionState state, TimingManager timingManager) {
        List<RouteNode> targets = state.targets;
        float[] excess = state.targetExcessLogicDelays;
        assert(targets.size() <= excess.length);
        Connection connection = state.connection;
        if (!connection.hasAltSinks()) {
            // A single target
            Arrays.fill(excess, 0, targets.size(), 0f);
            return;
        }
        float fastest = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            SitePin sitePin = RouterHelper.getLutInputSitePin(targets.get(i));
            excess[i] = timingManager.getLutLogicDelay(connection, sitePin.getPinName(), false)
                    + delayEstimator.getPinDelay(connection.getSource(), sitePin.getSite(), sitePin.getPinName());
            fastest = Math.min(fastest, excess[i]);
        }
        for (int i = 0; i < targets.size(); i++) {
            excess[i] -= fastest;
        }
    }

    @Override
    protected float getTargetExcessLogicDelay(RWRoute.ConnectionState state, RouteNode target) {
        return state.targetExcessLogicDelays[state.targets.indexOf(target)];
    }

    /**
     * Checks if routing a connection may terminate early on pushing the given (uncongested) target:
     * if its excess LUT logic delay costs nothing (no other candidate LUT input pin has a lower
     * logic delay, or the connection is not critical) and no other target is already queued. As
     * for any other connection, this does not bound the routing cost to the other candidates.
     * @param state State from the connection that is being routed.
     * @param target The target being pushed.
     * @return True if early termination is allowed.
     */
    @Override
    protected boolean isEarlyTerminable(RWRoute.ConnectionState state, RouteNode target) {
        List<RouteNode> targets = state.targets;
        for (int i = 0; i < targets.size(); i++) {
            RouteNode rnode = targets.get(i);
            if (rnode == target) {
                if (state.dlyWeight * state.targetExcessLogicDelays[i] != 0) {
                    return false;
                }
            } else if (rnode.isVisited(state.sequence)) {
                return false;
            }
        }
        return true;
    }

    protected static class RouteNodeTimingDriven extends RouteNode {

        /** The delay of this rnode computed based on the timing model */
        private final float delay;
        /** The class of this rnode's node for {@link DelayEstimator#getExtraDelay(int, int)} */
        private final byte delayClass;
        /**
         * Scratch of the fanout delay update of the router: the stamp of the net whose routing tree
         * last visited this rnode, and the first child of this rnode in that tree
         */
        int fanoutStamp;
        RouteNode fanoutChild;

        protected RouteNodeTimingDriven(RouteNodeGraphTimingDriven routingGraph, Node node, RouteNodeType type) {
            this(routingGraph, node, type, node.getAllWiresInNode());
        }

        private RouteNodeTimingDriven(RouteNodeGraphTimingDriven routingGraph, Node node, RouteNodeType type,
                                      Wire[] wires) {
            super(routingGraph, node, type, wires);
            DelayEstimator estimator = routingGraph.delayEstimator;
            // As every other user of the estimator (e.g. the timing report)
            delay = RouterHelper.computeNodeDelay(estimator, node, wires);
            delayClass = (byte) estimator.getExtraDelayClass(node);
        }

        @Override
        public float getDelay() {
            return delay;
        }

        @Override
        int getDelayClass() {
            return delayClass;
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            s.append("node " + super.toString());
            s.append(", ");
            s.append("(" + getEndTileXCoordinate() + "," + getEndTileYCoordinate() + ")");
            s.append(", ");
            s.append(String.format("type = %s", getType()));
            s.append(", ");
            s.append(String.format("ic = %s", getIntentCode()));
            s.append(", ");
            s.append(String.format("dly = %f", delay));
            s.append(", ");
            s.append(String.format("user = %s", getOccupancy()));
            s.append(", ");
            s.append(getUsersConnectionCounts());
            return s.toString();
        }
    }

    @Override
    protected RouteNode create(Node node, RouteNodeType type) {
        return new RouteNodeTimingDriven(this, node, type);
    }
}
