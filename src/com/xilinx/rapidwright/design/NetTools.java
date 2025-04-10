/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;

public class NetTools {
    private static Set<SiteTypeEnum> clkSrcSiteTypeEnums = EnumSet.noneOf(SiteTypeEnum.class);
    static {
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCE);       // All supported series
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCTRL);     // All supported series
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG);         // All supported series
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCE_DIV);   // US/US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG_GT);      // US/US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG_PS);      // US/US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG_FABRIC);  // Versal
    }

    public static boolean isGlobalClock(Net net) {
        SitePinInst srcSpi = net.getSource();
        if (srcSpi == null)
            return false;        
        
        return clkSrcSiteTypeEnums.contains(srcSpi.getSiteTypeEnum());
    }

    public static class NodeTree extends Node {
        public List<NodeTree> fanouts = Collections.emptyList();
        public NodeTree(Node node) {
            super(node);
        }

        public void addFanout(NodeTree node) {
            if (fanouts.isEmpty()) {
                fanouts = new ArrayList<>(1);
            }
            fanouts.add(node);
        }

        private void buildString(StringBuilder sb,
                                 boolean subtreeStart,
                                 boolean branchStart,
                                 boolean branchEndIfNoFanouts,
                                 boolean subTreeEndIfNoFanouts) {
            // Adopt the same spacing as Vivado's report_route_status
            sb.append("    ");
            sb.append(subtreeStart ? "[" : " ");
            sb.append(branchStart ? "{" : " ");
            sb.append("   ");
            boolean branchEnd = branchEndIfNoFanouts && fanouts.isEmpty();
            sb.append(branchEnd ? "}" : " ");
            boolean subtreeEnd = subTreeEndIfNoFanouts && branchEnd;
            sb.append(subtreeEnd ? "]" : " ");
            sb.append(String.format("  %30s", super.toString()));
            sb.append("\n");

            subtreeStart = false;
            for (int i = 0; i < fanouts.size(); i++) {
                NodeTree fanout = fanouts.get(i);
                boolean lastFanout = (i == fanouts.size() - 1);
                branchStart = !lastFanout && (fanouts.size() > 1);
                branchEndIfNoFanouts = lastFanout || branchStart;
                fanout.buildString(sb, subtreeStart, branchStart, branchEndIfNoFanouts,
                        subTreeEndIfNoFanouts && !branchStart && lastFanout);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            boolean subtreeStart = true;
            boolean branchStart = true;
            boolean branchEndIfNoFanouts = true;
            boolean subTreeEndIfNoFanouts = true;
            buildString(sb, branchStart, subtreeStart, branchEndIfNoFanouts, subTreeEndIfNoFanouts);
            return sb.toString();
        }
    }

    /**
     * Compute the node routing tree of the given Net by examining its PIPs.
     * Note that this method: (a) assumes that no loops are present, (b) only discovers subtrees that start at an
     * output SitePinInst or a node tied to VCC/GND (i.e. gaps and islands will be ignored).
     * @param net Net to analyze
     * @return A list of NodeTree objects, corresponding to the root of each subtree.
     */
    public static List<NodeTree> getNodeTrees(Net net) {
        List<NodeTree> subtrees = new ArrayList<>();
        Map<Node, NodeTree> nodeMap = new HashMap<>();
        for (PIP pip : net.getPIPs()) {
            if (pip.isEndWireNull()) {
                continue;
            }
            boolean isReversed = pip.isReversed();
            NodeTree startNode = nodeMap.computeIfAbsent(isReversed ? pip.getEndNode() : pip.getStartNode(), NodeTree::new);
            NodeTree endNode = nodeMap.computeIfAbsent(isReversed ? pip.getStartNode() : pip.getEndNode(), NodeTree::new);
            startNode.addFanout(endNode);
            if (!pip.isBidirectional()) {
                if ((net.getType() == NetType.GND && startNode.isTiedToGnd()) ||
                    (net.getType() == NetType.VCC && startNode.isTiedToVcc())) {
                    subtrees.add(startNode);
                }
            }
        }

        for (SitePinInst spi : net.getPins()) {
            if (!spi.isOutPin()) {
                continue;
            }
            Node node = spi.getConnectedNode();
            NodeTree nodeTree = nodeMap.computeIfAbsent(node, NodeTree::new);
            subtrees.add(nodeTree);
        }

        return subtrees;
    }
    
    /**
     * Checks if the provided net drives a clock site pin input.
     * 
     * @param net The net to examine.
     * @return True if the net has a site pin clock input, false otherwise.
     */
    public static boolean hasClockSinks(Net net) {
        for (SitePinInst sink : net.getPins()) { 
            if (sink.isOutPin()) continue;
            if (sink.getName().contains("CLK")) return true;
        }
        return false;
    }

    /**
     * Unroutes routed nets that have one or more overlapping or conflicting nodes
     * with another route in the design. The choice between which of two or more
     * nets gets unrouted is arbitrary and nets are unrouted until the set of routed
     * nets do not overlap.
     * 
     * @param design The design to evaluate for conflicting nodes.
     * @return The list of nets that were unrouted.
     */
    public static List<Net> unrouteNetsWithOverlappingNodes(Design design) {
        List<Net> unroutedNets = new ArrayList<>();
        Map<Node, Net> used = new HashMap<>();
        for (Net net : design.getNets()) {
            for (PIP pip : net.getPIPs()) {
                for (Node node : new Node[] { pip.getStartNode(), pip.getEndNode() }) {
                    if (node == null)
                        continue;
                    Net existing = used.putIfAbsent(node, net);
                    if (existing != null && existing != net) {
                        for (PIP oldPip : new ArrayList<>(existing.getPIPs())) {
                            for (Node oldNode : new Node[] { oldPip.getStartNode(), oldPip.getEndNode() }) {
                                used.remove(oldNode);
                            }
                        }
                        existing.unroute();
                        unroutedNets.add(existing);
                    }
                }
            }
        }
        return unroutedNets;
    }
}
