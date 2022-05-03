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
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.CountUpDownLatch;
import com.xilinx.rapidwright.util.Pair;
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

public class RouteNodeGraph {

    protected class RouteNodeImpl extends RouteNode {

        public RouteNodeImpl(Node node, RouteNodeType type) {
            super(node, type);
        }

        @Override
        protected RouteNode getOrCreate(Node node, RouteNodeType type) {
            return RouteNodeGraph.this.getOrCreate(node, type).getFirst();
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

    /**
     * A map of nodes to created rnodes
     */
    final protected Map<Node, RouteNode> nodesMap;

    /**
     * A map of preserved nodes to their nets
     */
    final private Map<LightweightNode, Net> preservedMap;

    final private CountUpDownLatch preservedMapOutstanding;

    /**
     * Visited rnodes data during connection routing
     */
    final protected Collection<RouteNode> visited;

    final protected RuntimeTracker setChildrenTimer;

    private long totalVisited;

    final Design design;

    public RouteNodeGraph(RuntimeTracker setChildrenTimer, Design design) {
        nodesMap = new HashMap<>();
        preservedMap = new ConcurrentHashMap<>();
        preservedMapOutstanding = new CountUpDownLatch();
        visited = new ArrayList<>();
        this.setChildrenTimer = setChildrenTimer;
        this.design = design;
    }

    public void initialize() {
        totalVisited = 0;
        visited.clear();
    }

    public Net preserve(Node node, Net net) {
        return preserve(new LightweightNode(node), net);
    }

    private Net preserve(LightweightNode node, Net net) {
        return preservedMap.putIfAbsent(node, net);
    }

    public void asyncPreserve(Collection<Node> nodes, Net net) {
        preservedMapOutstanding.countUp();
        ParallelismTools.submit(() -> {
            nodes.forEach((node) -> preserve(node, net));
            preservedMapOutstanding.countDown();
        });
    }

    public void asyncPreserve(Net net) {
        preservedMapOutstanding.countUp();
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

                preserve(new LightweightNode(pin), net);
            }

            for(PIP pip : net.getPIPs()) {
                preserve(pip.getStartNode(), net);
                preserve(pip.getEndNode(), net);
            }

            preservedMapOutstanding.countDown();
        });
    }

    public void awaitPreserve() {
        try {
            preservedMapOutstanding.await();
        } catch (InterruptedException e) {
            throw new RuntimeException();
        }
    }

    public void unpreserve(Node node) {
        preservedMap.remove(new LightweightNode(node));
    }

    public boolean isPreserved(Node node) {
        return preservedMap.containsKey(new LightweightNode(node));
    }

    protected boolean isPreserved(Node parent, Node child) {
        return isPreserved(child);
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

    protected boolean isExcluded(Node parent, Node child) {
        if (isPreserved(parent, child))
            return true;

        Tile tile = child.getTile();
        TileTypeEnum tileType = tile.getTileTypeEnum();
        return !allowedTileEnums.contains(tileType);
    }

    public Collection<Node> getPreservedNodes(Device device) {
        List<Node> nodes = new ArrayList<>(preservedMap.size());
        preservedMap.keySet().forEach((k) -> nodes.add(Node.getNode(device.getTile(k.tileID), k.wireID)));
        return nodes;
    }

    public Net getPreservedNet(Node node) {
        return preservedMap.get(new LightweightNode(node));
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

    public Pair<RouteNode,Boolean> getOrCreate(Node node, RouteNodeType type) {
        final boolean[] inserted = {false};
        RouteNode rnode = nodesMap.compute(node, (k, v) -> {
            if (v == null) {
                // this is for initializing sources and sinks of those to-be-routed nets' connections
                v = create(node, type);
                inserted[0] = true;
            }
            return v;
        });
        return new Pair<>(rnode, inserted[0]);
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
            sum += (rnode.children != null) ? rnode.children.length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

}
