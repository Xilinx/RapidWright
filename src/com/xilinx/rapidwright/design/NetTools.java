/*
 * Copyright (c) 2024-2025, Advanced Micro Devices, Inc.
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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class NetTools {
    public static final String CONTINUE_ELBOW = "\u251c\u2500 ";
    public static final String LAST_ELBOW = "\u2514\u2500 ";
    public static final String VERTICAL_BAR = "\u2502  ";
    public static final String WHITE_SPACE = "   ";

    private static Set<SiteTypeEnum> clkSrcSiteTypeEnums = EnumSet.noneOf(SiteTypeEnum.class);

    static {
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCE);       // All supported series
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCTRL);     // All supported series
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG);         // All supported series
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCE_DIV);   // US/US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG_GT);      // US/US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG_PS);      // US/US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFGCE_HDIO);  // US+ and Versal
        clkSrcSiteTypeEnums.add(SiteTypeEnum.BUFG_FABRIC);  // Versal
    }

    public static boolean isGlobalClock(Net net) {
        SitePinInst srcSpi = net.getSource();
        if (srcSpi == null)
            return false;

        return clkSrcSiteTypeEnums.contains(srcSpi.getSiteTypeEnum());
    }

    public static class NodeTree extends Node {
        private static final long serialVersionUID = 8522818939039826954L;
        public List<NodeTree> fanouts = Collections.emptyList();

        public NodeTree(Node node) {
            super(node);
        }

        public boolean multiplyDriven = false;

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
            buildString(sb, subtreeStart, branchStart, branchEndIfNoFanouts, subTreeEndIfNoFanouts,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        private void buildString(StringBuilder sb,
                                 boolean subtreeStart,
                                 boolean branchStart,
                                 boolean branchEndIfNoFanouts,
                                 boolean subTreeEndIfNoFanouts,
                                 Set<NetTools.NodeTree> multiplyDrivenNodesVisited) {
            // Adopt the same spacing as Vivado's report_route_status
            sb.append("    ");
            sb.append(subtreeStart ? "[" : " ");
            sb.append(branchStart ? "{" : " ");
            sb.append("   ");
            boolean notFirstTimeVisitingThisMultiplyDrivenNode = multiplyDriven && !multiplyDrivenNodesVisited.add(this);
            boolean branchEnd = (branchEndIfNoFanouts && fanouts.isEmpty()) || notFirstTimeVisitingThisMultiplyDrivenNode;
            sb.append(branchEnd ? "}" : " ");
            boolean subtreeEnd = subTreeEndIfNoFanouts && branchEnd;
            sb.append(subtreeEnd ? "]" : " ");
            sb.append(String.format("  %30s", super.toString()));
            sb.append("\n");

            if (notFirstTimeVisitingThisMultiplyDrivenNode) {
                return;
            }

            subtreeStart = false;
            for (int i = 0; i < fanouts.size(); i++) {
                NodeTree fanout = fanouts.get(i);
                boolean lastFanout = (i == fanouts.size() - 1);
                branchStart = !lastFanout && (fanouts.size() > 1);
                branchEndIfNoFanouts = lastFanout || branchStart;
                fanout.buildString(sb, subtreeStart, branchStart, branchEndIfNoFanouts,
                        subTreeEndIfNoFanouts && !branchStart && lastFanout,
                        multiplyDrivenNodesVisited);
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

        /**
         * Returns a string representation of this NodeTree using tree characters (├─, └─).
         *
         * @return String representation with tree characters.
         */
        public String toTreeString() {
            StringBuilder sb = new StringBuilder();
            buildTreeString(sb, "", "", new HashSet<>(), n -> n.toString());
            return sb.toString();
        }

        /**
         * Returns a string representation of this NodeTree using tree characters (├─, └─).
         *
         * @param customToString Allows a custom toString() method to be applied to the Node when
         *                       generating the String.
         * @return String representation with tree characters.
         */
        public String toTreeString(Function<Node, String> customToString) {
            StringBuilder sb = new StringBuilder();
            buildTreeString(sb, "", "", new HashSet<>(), customToString);
            return sb.toString();
        }

        private void buildTreeString(StringBuilder sb, String prefix, String childPrefix,
                                     Set<NetTools.NodeTree> visited, Function<Node, String> customToString) {
            sb.append(prefix);
            sb.append(customToString.apply(this));
            if (multiplyDriven && !visited.add(this)) {
                sb.append(" (multiply driven, already visited)\n");
                return;
            }
            sb.append("\n");
            for (int i = 0; i < fanouts.size(); i++) {
                NodeTree fanout = fanouts.get(i);
                boolean isLast = i == fanouts.size() - 1;
                String newPrefix = childPrefix + (isLast ? LAST_ELBOW : CONTINUE_ELBOW);
                String newChildPrefix = childPrefix + (isLast ? WHITE_SPACE : VERTICAL_BAR);
                fanout.buildTreeString(sb, newPrefix, newChildPrefix, visited, customToString);
            }
        }
    }

    /**
     * Compute the node routing tree of the given Net by examining its PIPs.
     * Note that this method only discovers subtrees that start at an output SitePinInst or a node tied to VCC/GND
     * (i.e. gaps and islands will be ignored).
     * Nodes that are multiply-driven (indicative of routing loops) will have their NodeTree.multiplyDriven flag set.
     *
     * @param net Net to analyze.
     * @return A list of NodeTree objects, corresponding to the root of each subtree.
     */
    public static List<NodeTree> getNodeTrees(Net net) {
        return getNodeTrees(net, n -> false);
    }

    /**
     * Compute the node routing tree of the given Net by examining its PIPs.
     * Note that this method only discovers subtrees that start at an output SitePinInst or a node tied to VCC/GND
     * (i.e. gaps and islands will be ignored).
     * Nodes that are multiply-driven (indicative of routing loops) will have their NodeTree.multiplyDriven flag set.
     *
     * @param net    Net to analyze.
     * @param filter A function that when is applied to a node, if it returns true will be excluded from the tree.
     * @return A list of NodeTree objects, corresponding to the root of each subtree.
     */
    public static List<NodeTree> getNodeTrees(Net net, Function<Node, Boolean> filter) {
        List<NodeTree> subtrees = new ArrayList<>();
        Map<Node, NodeTree> nodeMap = new HashMap<>();
        for (PIP pip : net.getPIPs()) {
            if (pip.isEndWireNull()) {
                continue;
            }

            Node start = pip.getStartNode();
            if (filter.apply(start)) continue;
            Node end = pip.getEndNode();
            if (filter.apply(end)) continue;

            boolean isReversed = pip.isReversed();
            NodeTree startNode = nodeMap.computeIfAbsent(isReversed ? end : start, NodeTree::new);
            NodeTree endNode = nodeMap.compute(isReversed ? start : end, (k, v) -> {
                if (v == null) {
                    v = new NodeTree(k);
                } else {
                    // This node already exists in our map thus must be multiply-driven
                    v.multiplyDriven = true;
                }
                return v;
            });
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
        List<Net> overlappingNets = getNetsWithOverlappingNodes(design);
        for (Net net : overlappingNets) {
            net.unroute();
        }
        return overlappingNets;
    }

    /**
     * Returns a list of nets with overlapping nodes without unrouting them.
     *
     * @param design The design to evaluate for conflicting nodes.
     * @return The list of nets that overlap.
     */
    public static List<Net> getNetsWithOverlappingNodes(Design design) {
        List<Net> overlappingNets = new ArrayList<>();
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
                        overlappingNets.add(existing);
                    }
                }
            }
        }
        return overlappingNets;
    }

    /**
     * Returns a string representation of the net's routing tree using tree
     * characters (├─, └─).
     *
     * @param net The net to generate the tree of nodes from.
     * @return String representation of the net's routing tree with tree characters.
     */
    public static String getNetTreeString(Net net) {
        return getNetTreeString(net, n -> false);
    }

    /**
     * Returns a string representation of the net's routing tree using tree
     * characters (├─, └─).
     *
     * @param net    The net to generate the tree of nodes from.
     * @param filter A function that when returns true for a node, the node should
     *               be excluded.
     * @return String representation of the net's routing tree with tree characters.
     */
    public static String getNetTreeString(Net net, Function<Node, Boolean> filter) {
        return getNetTreeString(net, filter, n -> n.toString());
    }

    /**
     * Returns a string representation of the net's routing tree using tree
     * characters (├─, └─).
     *
     * @param net            The net to generate the tree of nodes from.
     * @param filter         A function that when returns true for a node, the node
     *                       should be excluded.
     * @param customToString A custom toString() function to be applied to the Node
     *                       when printed.
     * @return String representation of the net's routing tree with tree characters.
     */
    public static String getNetTreeString(Net net, Function<Node, Boolean> filter,
                                          Function<Node, String> customToString) {
        List<NodeTree> subtrees = getNodeTrees(net, filter);
        if (subtrees.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subtrees.size(); i++) {
            sb.append("\nSubtree ");
            sb.append(i);
            sb.append(":\n");
            sb.append(subtrees.get(i).toTreeString(customToString));
        }
        return sb.toString();
    }

    /**
     * Generates the clock tree up to the horizontal distribution nodes of the
     * provided clock net up to the NODE_PINFEED nodes.
     *
     * @param net The clock net
     * @return A string tree representation from the source of the clock out to all
     * horizontal distribution nodes.
     */
    public static String getClockTreeSpine(Net net) {
        Function<Node, String> customToString =
                n -> n.getTileName() + "/" + n.getWireName()
                        + " (" + n.getIntentCode() + ") CR="
                        + n.getTile().getClockRegion();
        Function<Node, Boolean> excludeFilter = n -> n.getIntentCode() == IntentCode.NODE_PINFEED;
        return getNetTreeString(net, excludeFilter, customToString);
    }

    /**
     * Gets a string representation of the vertical clock routing spine for the given net.
     * This method is similar to getClockTreeSpine but only includes vertical distribution nodes.
     * Only the clock root VROUTE node is included; all other VROUTE nodes are filtered out.
     *
     * @param net The net to analyze
     * @return A string tree representation showing only vertical clock routing nodes
     */
    public static String getVerticalClockTreeSpine(Net net) {
        Function<Node, String> customToString =
                n -> n.getIntentCode() + " CR=" + n.getTile().getClockRegion();

        // Build the full tree without filtering (except PINFEED)
        Function<Node, Boolean> minimalFilter = n -> n.getIntentCode() == IntentCode.NODE_PINFEED;
        List<NodeTree> subtrees = getNodeTrees(net, minimalFilter);

        if (subtrees.isEmpty()) {
            return "";
        }

        // Find the clock root VROUTE node
        Node clockRoot = findClockRootVRoute(net);

        // Define the set of nodes we want to display
        Set<IntentCode> displayIntentCodes = EnumSet.of(
                IntentCode.NODE_GLOBAL_VDISTR_SHARED,
                IntentCode.NODE_GLOBAL_VROUTE,
                IntentCode.NODE_GLOBAL_VDISTR_LVL1,
                IntentCode.NODE_GLOBAL_VDISTR,
                IntentCode.NODE_GLOBAL_VDISTR_LVL2,
                IntentCode.NODE_GLOBAL_VDISTR_LVL21,
                IntentCode.NODE_GLOBAL_VDISTR_LVL3
        );

        if (subtrees.size() > 1) {
            throw new RuntimeException("Clock route might have multiple clock roots");
        }

        Function<Node, Boolean> includeFilter = n -> {
            IntentCode intentCode = n.getIntentCode();
            if (displayIntentCodes.contains(intentCode)) {
                if (intentCode == IntentCode.NODE_GLOBAL_VROUTE) {
                    return n.equals(clockRoot);
                }
                return true;
            }
            return false;
        };

        StringBuilder sb = new StringBuilder();
        NodeTree subtree = subtrees.get(0);
        NodeTree filteredSubtree = buildFilteredTree(subtree, includeFilter);
        sb.append(filteredSubtree.toTreeString(customToString));
        return sb.toString();
    }

    /**
     * Builds a tree that only includes the nodes that pass the filter while maintaining connectivity.
     */
    private static NodeTree buildFilteredTree(NodeTree nodeTree, Function<Node, Boolean> includeFilter) {
        class WorkItem {
            final NodeTree original;
            final NodeTree parentInFilteredTree;

            WorkItem(NodeTree original, NodeTree parentInFilteredTree) {
                this.original = original;
                this.parentInFilteredTree = parentInFilteredTree;
            }
        }

        NodeTree filteredRoot = nodeTree;

        while (!includeFilter.apply(filteredRoot)) {
            if (filteredRoot.fanouts.size() != 1) {
                throw new RuntimeException("Failed to find clock root");
            }
            filteredRoot = filteredRoot.fanouts.get(0);
        }

        List<WorkItem> worklist = new ArrayList<>();
        NodeTree filteredNodeTree = new NodeTree(filteredRoot);

        for (NodeTree fanout : filteredRoot.fanouts) {
            worklist.add(new WorkItem(fanout, filteredNodeTree));
        }

        // Process worklist for the normal case (root passed filter)
        while (!worklist.isEmpty()) {
            WorkItem item = worklist.remove(0);
            NodeTree curr = item.original;
            NodeTree filteredParent = item.parentInFilteredTree;

            if (includeFilter.apply(curr)) {
                NodeTree filteredNode = new NodeTree(curr);
                filteredNode.multiplyDriven = curr.multiplyDriven;
                filteredParent.addFanout(filteredNode);

                for (NodeTree fanout : curr.fanouts) {
                    worklist.add(new WorkItem(fanout, filteredNode));
                }
            } else {
                for (NodeTree fanout : curr.fanouts) {
                    worklist.add(new WorkItem(fanout, filteredParent));
                }
            }
        }

        return filteredNodeTree;
    }

    /**
     * Finds the clock root VROUTE node.
     * The clock root is the deepest (furthest from source) NODE_GLOBAL_VROUTE node in the tree.
     *
     * @param net The net to analyze
     * @return The clock root VROUTE node, or null if none found
     */
    public static Node findClockRootVRoute(Net net) {
        List<NodeTree> subtrees = getNodeTrees(net);

        if (subtrees.size() > 1) {
            throw new RuntimeException("Clock route potentially has multiple clock roots");
        }

        // Find the last GLOBAL_VROUTE node before transitioning VDISTR nodes
        NodeTree curr = subtrees.get(0);
        NodeTree deepestVRoute = null;

        while (curr.fanouts.size() == 1) {
            if (curr.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE) {
                deepestVRoute = curr;
            }
            curr = curr.fanouts.get(0);
        }

        if (curr.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE) {
            deepestVRoute = curr;
        }

        return deepestVRoute;
    }

    /**
     * Unroute top-level port nets that have PIPs outside the specified PBlock. Vivado can potentially create designs
     * where the top-level port nets leave the PBlock if the InlineFlopTools flop harness was used and the top-level
     * port net also has a sink inside the PBlock.
     *
     * @param d The design to unroute nets from.
     * @param pBlock The bounding box for the placed and routed design.
     */
    public static void unrouteTopLevelNetsThatLeavePBlock(Design d, PBlock pBlock) {
        DesignTools.makePhysNetNamesConsistent(d);
        d.getNetlist().resetParentNetMap();
        d.getNetlist().expandMacroUnisims();
        for (EDIFPort p : d.getNetlist().getTopCell().getPorts()) {
            int[] indices = p.isBus() ? p.getBitBlastedIndicies() : new int[]{0};
            for (int i : indices) {
                EDIFPortInst portInst = p.getInternalPortInstFromIndex(i);
                if (portInst == null) {
                    continue;
                }
                EDIFHierNet hierNet = new EDIFHierNet(d.getNetlist().getTopHierCellInst(), portInst.getNet());
                EDIFHierNet parentNet = d.getNetlist().getParentNet(hierNet);
                Net net = d.getNet(parentNet.getHierarchicalNetName());

                boolean leavesPBlock = false;
                for (PIP pip : net.getPIPs()) {
                    if (!pBlock.containsTile(pip.getTile())) {
                        leavesPBlock = true;
                        break;
                    }
                }
                if (leavesPBlock) {
                    net.unroute();
                }
            }
        }
    }
}
