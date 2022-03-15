package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.HashSet;
import java.util.Set;

public class RoutableGraphTimingDriven extends RoutableGraph {
    /** The instantiated delayEstimator to compute delays */
    protected DelayEstimatorBase delayEstimator;
    /** A flag to indicate if the routing resource exclusion should disable exclusion of nodes cross RCLK */
    protected boolean maskNodesCrossRCLK;

    public RoutableGraphTimingDriven(RuntimeTracker rnodesTimer, DelayEstimatorBase delayEstimator, boolean maskNodesCrossRCLK) {
        super(rnodesTimer);
        this.delayEstimator = delayEstimator;
        this.maskNodesCrossRCLK = maskNodesCrossRCLK;
    }

    final private static Set<String> excludeAboveRclk;
    final private static Set<String> excludeBelowRclk;
    static {
        // these nodes are bleeding down
        excludeAboveRclk = new HashSet<String>() {{
            add("SDQNODE_E_0_FT1");
            add("SDQNODE_E_2_FT1");
            add("SDQNODE_W_0_FT1");
            add("SDQNODE_W_2_FT1");
            add("EE12_BEG0");
            add("WW2_E_BEG0");
            add("WW2_W_BEG0");
        }};
        // these nodes are bleeding up
        excludeBelowRclk = new HashSet<String>() {{
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

    protected class RoutableNodeImpl extends RoutableGraph.RoutableNodeImpl {

        /** The delay of this rnode computed based on the timing model */
        private float delay;

        public RoutableNodeImpl(Node node, RoutableType type) {
            super(node, type);
            delay = RouterHelper.computeNodeDelay(delayEstimator, node);
        }

        @Override
        public boolean isExcluded(Node node) {
            if (super.isExcluded(node))
                return true;
            Tile tile = node.getTile();
            if(tile.getTileTypeEnum() == TileTypeEnum.INT) {
                if (maskNodesCrossRCLK) {
                    int y = tile.getTileYCoordinate();
                    if ((y-30)%60 == 0) { // above RCLK
                        return excludeAboveRclk.contains(node.getWireName());
                    } else if ((y-29)%60 == 0) { // below RCLK
                        return excludeBelowRclk.contains(node.getWireName());
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
    protected Routable newNode(Node node, RoutableType type) {
        return new RoutableNodeImpl(node, type);
    }
}
