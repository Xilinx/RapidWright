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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.DelayModel;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extends {@link RouteNodeGraph} with timing-driven capabilities.
 */
public class RouteNodeGraphTimingDriven extends RouteNodeGraph {
    /** The instantiated delayEstimator to compute delays */
    protected final DelayEstimatorBase<InterconnectInfo> delayEstimator;
    /** A flag to indicate if the routing resource exclusion should disable exclusion of nodes cross RCLK */
    protected final boolean maskNodesCrossRCLK;

    private static final Set<String> excludeAboveRclkString;
    private static final Set<String> excludeBelowRclkString;
    static {
        // these nodes are bleeding down
        excludeAboveRclkString = new HashSet<String>() {{
            add("SDQNODE_E_0_FT1");
            add("SDQNODE_E_2_FT1");
            add("SDQNODE_W_0_FT1");
            add("SDQNODE_W_2_FT1");
            add("EE12_BEG0");
            add("WW2_E_BEG0");
            add("WW2_W_BEG0");
        }};
        // these nodes are bleeding up
        excludeBelowRclkString = new HashSet<String>() {{
            add("SDQNODE_E_91_FT0");
            add("SDQNODE_E_93_FT0");
            add("SDQNODE_E_95_FT0");
            add("SDQNODE_W_91_FT0");
            add("SDQNODE_W_93_FT0");
            add("SDQNODE_W_95_FT0");
            add("EE12_BEG7");
            add("WW1_W_BEG7");
        }};
    }

    protected RouteNodeGraphTimingDriven(Design design,
                                         RWRouteConfig config,
                                         DelayEstimatorBase<InterconnectInfo> delayEstimator) {
        super(design, config);
        this.delayEstimator = delayEstimator;
        this.maskNodesCrossRCLK = config.isMaskNodesCrossRCLK();

        excludeAboveRclk = new HashSet<>();
        excludeBelowRclk = new HashSet<>();
        Device device = design.getDevice();
        Tile intTile = device.getArbitraryTileOfType(TileTypeEnum.INT);
        String[] wireNames = intTile.getWireNames();
        for (int wireIndex = 0; wireIndex < intTile.getWireCount(); wireIndex++) {
            String wireName = wireNames[wireIndex];
            if (excludeAboveRclkString.contains(wireName)) {
                excludeAboveRclk.add(wireIndex);
            }
            if (excludeBelowRclkString.contains(wireName)) {
                excludeBelowRclk.add(wireIndex);
            }
        }
    }

    private final Set<Integer> excludeAboveRclk;
    private final Set<Integer> excludeBelowRclk;

    @Override
    public DelayEstimatorBase<InterconnectInfo> getDelayEstimator() {
        return delayEstimator;
    }

    /**
     * Gets the LUT logic delay with a connection arriving at the given sink rnode (one of its
     * candidate LUT input pins, with LUT pin swapping): the worst of the timing graph's logic edges
     * from the LUT input(s) that the connection feeds, or zero if there are none (the timing graph
     * has no logic edge from an input that the LUT's function does not depend on), or if the
     * connection's sink pin cannot be swapped. These delays depend only on the LUT BEL and its
     * pins, so they are looked up in the delay model rather than stored per connection.
     * @param timingGraph The timing graph.
     * @param connection The connection.
     * @param sinkRnode One of the connection's sink rnodes.
     * @param updateTimingGraph True to also set those logic edges' delays in the timing graph.
     * @return The logic delay in ps.
     */
    @Override
    protected float getLutLogicDelay(TimingGraph timingGraph, Connection connection, RouteNode sinkRnode,
                                     boolean updateTimingGraph) {
        // Only LUT pin swapping gives a connection alternate sinks
        if (!connection.hasAltSinks()) {
            return 0;
        }
        DelayModel delayModel = timingGraph.getintraSiteAndLogicDelayModel();
        String sitePinName = sinkRnode.getSitePin().getPinName();
        float worst = 0;
        for (TimingEdge edge : connection.getTimingEdges()) {
            TimingVertex input = edge.getDst();
            String inputName = input.getName();
            Cell cell = design.getCell(inputName.substring(0, inputName.lastIndexOf('/')));
            BEL bel = cell.getBEL();
            assert(bel.isLUT());
            String inputPin = null;
            for (BELPin belPin : bel.getPins()) {
                if (belPin.isInput() && sitePinName.equals(belPin.getConnectedSitePinName())) {
                    inputPin = belPin.getName();
                    break;
                }
            }
            if (inputPin == null) {
                throw new RuntimeException("ERROR: Site pin " + sitePinName + " does not drive an input of " + bel);
            }
            short belIdx = delayModel.getBELIndex(bel.getName());
            for (TimingEdge logic : timingGraph.outgoingEdgesOf(input)) {
                String outputName = logic.getDst().getName();
                String outputPin = cell.getPhysicalPinMapping(outputName.substring(outputName.lastIndexOf('/') + 1));
                short delay = delayModel.getLogicDelay(belIdx, inputPin, outputPin);
                if (delay < 0) {
                    throw new RuntimeException("ERROR: No logic delay from " + bel + "." + inputPin + " to " + outputPin);
                }
                if (updateTimingGraph) {
                    logic.setLogicDelay(delay);
                }
                worst = Math.max(worst, delay);
            }
        }
        return worst;
    }

    /**
     * Sets, for each of the connection's targets (its candidate LUT input pins), its LUT logic
     * delay in excess of that of the fastest target.
     * @param state State from the connection that is being routed, with its targets marked.
     * @param timingManager The timing manager.
     */
    @Override
    protected void setTargetExcessLogicDelays(RWRoute.ConnectionState state, TimingManager timingManager) {
        TimingGraph timingGraph = timingManager.getTimingGraph();
        List<RouteNode> targets = state.targets;
        float[] excess = state.targetExcessLogicDelays;
        assert(targets.size() <= excess.length);
        float fastest = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            excess[i] = getLutLogicDelay(timingGraph, state.connection, targets.get(i), false);
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

        protected RouteNodeTimingDriven(RouteNodeGraphTimingDriven routingGraph, Node node, RouteNodeType type) {
            super(routingGraph, node, type);
            delay = RouterHelper.computeNodeDelay(routingGraph.delayEstimator, node);
        }

        @Override
        public float getDelay() {
            return delay;
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

    @Override
    protected boolean isExcluded(RouteNode parent, Node child) {
        if (super.isExcluded(parent, child))
            return true;
        if (maskNodesCrossRCLK) {
            Tile tile = child.getTile();
            if (tile.getTileTypeEnum() == TileTypeEnum.INT) {
                int y = tile.getTileYCoordinate();
                if ((y-30)%60 == 0) { // above RCLK
                    return excludeAboveRclk.contains(child.getWireIndex());
                } else if ((y-29)%60 == 0) { // below RCLK
                    return excludeBelowRclk.contains(child.getWireIndex());
                }
            }
        }
        return false;
    }
}
