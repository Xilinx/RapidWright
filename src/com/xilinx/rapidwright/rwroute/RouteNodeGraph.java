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
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.CountUpDownLatch;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulation of RWRoute's routing resource graph.
 */
public class RouteNodeGraph {

    /**
     * A map of nodes to created rnodes
     */
    final protected Map<Node, RouteNode> nodesMap;

    /**
     * A map of preserved nodes to their nets
     */
    final private Map<Node, Net> preservedMap;

    /**
     * A synchronization object tracking the number of outstanding calls to
     * asyncPreserve()
     */
    final private CountUpDownLatch asyncPreserveOutstanding;

    /**
     * Visited rnodes data during connection routing
     */
    final protected Collection<RouteNode> visited;

    final protected RuntimeTracker setChildrenTimer;

    private long totalVisited;

    final Design design;

    protected class RouteNodeImpl extends RouteNode {

        public RouteNodeImpl(Node node, RouteNodeType type) {
            super(node, type);
        }

        @Override
        protected RouteNode getOrCreate(Node node, RouteNodeType type) {
            return RouteNodeGraph.this.getOrCreate(node, type);
        }

        @Override
        public boolean mustInclude(Node parent, Node child) {
            return RouteNodeGraph.this.mustInclude(parent, child);
        }

        @Override
        public boolean isPreserved(Node node) {
            return RouteNodeGraph.this.isPreserved(node);
        }

        @Override
        public boolean isExcluded(Node parent, Node child) {
            return RouteNodeGraph.this.isExcluded(parent, child);
        }

        @Override
        public RouteNode[] getChildren() {
            setChildren(setChildrenTimer);
            return super.getChildren();
        }
    }

    public RouteNodeGraph(RuntimeTracker setChildrenTimer, Design design) {
        nodesMap = new HashMap<>();
        preservedMap = new ConcurrentHashMap<>();
        asyncPreserveOutstanding = new CountUpDownLatch();
        visited = new ArrayList<>();
        this.setChildrenTimer = setChildrenTimer;
        this.design = design;
    }

    public void initialize() {
        totalVisited = 0;
        visited.clear();
    }

    public Net preserve(Node node, Net net) {
        return preservedMap.putIfAbsent(node, net);
    }

    public void asyncPreserve(Collection<Node> nodes, Net net) {
        asyncPreserveOutstanding.countUp();
        ParallelismTools.submit(() -> {
            nodes.forEach((node) -> preserve(node, net));
            asyncPreserveOutstanding.countDown();
        });
    }

    public void asyncPreserve(Net net) {
        asyncPreserveOutstanding.countUp();
        ParallelismTools.submit(() -> {
            List<SitePinInst> pins = net.getPins();
            SitePinInst sourcePin = net.getSource();
            assert(sourcePin == null || pins.contains(sourcePin));
            SitePinInst altSourcePin = net.getAlternateSource();
            assert(altSourcePin == null || pins.contains(altSourcePin));
            for(SitePinInst pin : net.getPins()) {
                // SitePinInst.isRouted() is meaningless for output pins
                if (!pin.isRouted() && !pin.isOutPin()) {
                    continue;
                }

                preserve(pin.getConnectedNode(), net);
            }

            for(PIP pip : net.getPIPs()) {
                preserve(pip.getStartNode(), net);
                preserve(pip.getEndNode(), net);
            }

            asyncPreserveOutstanding.countDown();
        });
    }

    public void awaitPreserve() {
        try {
            asyncPreserveOutstanding.await();
        } catch (InterruptedException e) {
            throw new RuntimeException();
        }
    }

    public boolean unpreserve(Node node) {
        return preservedMap.remove(node) != null;
    }

    public boolean isPreserved(Node node) {
        return preservedMap.containsKey(node);
    }

    final private static Set<TileTypeEnum> allowedTileEnums;
    static {
        allowedTileEnums = new HashSet<>();
        allowedTileEnums.add(TileTypeEnum.INT);
        for (TileTypeEnum e : TileTypeEnum.values()) {
            if (e.toString().startsWith("LAG")) {
                allowedTileEnums.add(e);
            }
        }
    }

    protected boolean mustInclude(Node parent, Node child) {
        return false;
    }

    protected boolean isExcluded(Node parent, Node child) {
        Tile tile = child.getTile();
        TileTypeEnum tileType = tile.getTileTypeEnum();
        return !allowedTileEnums.contains(tileType);
    }

    public Set<Node> getPreservedNodes() {
        return Collections.unmodifiableSet(preservedMap.keySet());
    }

    public Net getPreservedNet(Node node) {
        return preservedMap.get(node);
    }

    public RouteNode getNode(Node node) {
        return nodesMap.get(node);
    }

    public Set<Node> getNodes() {
        return Collections.unmodifiableSet(nodesMap.keySet());
    }

    public Set<Map.Entry<Node, RouteNode>> getNodeEntries() {
        return Collections.unmodifiableSet(nodesMap.entrySet());
    }

    public int numNodes() {
        return nodesMap.size();
    }

    protected RouteNode create(Node node, RouteNodeType type) {
        return new RouteNodeImpl(node, type);
    }

    public RouteNode getOrCreate(Node node, RouteNodeType type) {
        return nodesMap.computeIfAbsent(node, ($) -> create(node, type));
    }

    public void visit(RouteNode rnode) {
        visited.add(rnode);
    }

    /**
     * Resets the expansion history.
     */
    public void resetExpansion() {
        for (RouteNode node : visited) {
            node.setVisited(false);
        }
        totalVisited += visited.size();
        visited.clear();
    }

    public long getTotalVisited() {
        return totalVisited;
    }

    public int averageChildren() {
        int sum = 0;
        for(Map.Entry<Node, RouteNode> e : getNodeEntries()){
            RouteNodeImpl rnode = (RouteNodeImpl) e.getValue();
            sum += rnode.everExpanded() ? rnode.getChildren().length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

}
