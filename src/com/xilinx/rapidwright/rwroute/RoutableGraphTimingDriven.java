/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileType;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.HashSet;
import java.util.Set;

public class RoutableGraphTimingDriven extends RoutableGraph {
    /** The instantiated delayEstimator to compute delays */
    protected final DelayEstimatorBase delayEstimator;
    /** A flag to indicate if the routing resource exclusion should disable exclusion of nodes cross RCLK */
    protected final boolean maskNodesCrossRCLK;

    private final static Set<String> excludeAboveRclkString;
    private final static Set<String> excludeBelowRclkString;
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

    public RoutableGraphTimingDriven(RuntimeTracker rnodesTimer, Design design, DelayEstimatorBase delayEstimator, boolean maskNodesCrossRCLK) {
        super(rnodesTimer, design);
        this.delayEstimator = delayEstimator;
        this.maskNodesCrossRCLK = maskNodesCrossRCLK;

        excludeAboveRclk = new HashSet<>();
        excludeBelowRclk = new HashSet<>();
        Device device = design.getDevice();
        TileType intType = device.getTileType(TileTypeEnum.INT);
        String[] wireNames = intType.getWireNames();
        for (int wireIndex = 0; wireIndex < intType.getWireCount(); wireIndex++) {
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

    protected class RoutableNodeImpl extends RoutableGraph.RoutableNodeImpl {

        /** The delay of this rnode computed based on the timing model */
        private final float delay;

        public RoutableNodeImpl(Node node, RoutableType type) {
            super(node, type);
            delay = RouterHelper.computeNodeDelay(delayEstimator, node);
        }

        @Override
        public boolean isExcluded(Node parent, Node child) {
            if (super.isExcluded(parent, child))
                return true;
            if (maskNodesCrossRCLK) {
                Tile tile = child.getTile();
                if(tile.getTileTypeEnum() == TileTypeEnum.INT) {
                    int y = tile.getTileYCoordinate();
                    if ((y-30)%60 == 0) { // above RCLK
                        return excludeAboveRclk.contains(child.getWire());
                    } else if ((y-29)%60 == 0) { // below RCLK
                        return excludeBelowRclk.contains(child.getWire());
                    }
                }
            }
            return false;
        }

        @Override
        public float getDelay() {
            return delay;
        }

        @Override
        public String toString(){
            StringBuilder s = new StringBuilder();
            s.append("node " + node.toString());
            s.append(", ");
            s.append("(" + getEndTileXCoordinate() + "," + getEndTileYCoordinate() + ")");
            s.append(", ");
            s.append(String.format("type = %s", getRoutableType()));
            s.append(", ");
            s.append(String.format("ic = %s", node.getIntentCode()));
            s.append(", ");
            s.append(String.format("dly = %d", delay));
            s.append(", ");
            s.append(String.format("user = %s", getOccupancy()));
            s.append(", ");
            s.append(getUsersConnectionCounts());
            return s.toString();
        }
    }

    @Override
    protected Routable create(Node node, RoutableType type) {
        return new RoutableNodeImpl(node, type);
    }
}
