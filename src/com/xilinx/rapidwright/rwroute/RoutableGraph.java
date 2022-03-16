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

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class RoutableGraph {

    protected class RoutableNodeImpl extends RoutableNode {

        public RoutableNodeImpl(Node node, RoutableType type) {
            super(node, type);
        }

        @Override
        protected Routable getOrCreate(Node node, RoutableType type) {
            return RoutableGraph.this.getOrCreate(node, type).getFirst();
        }

        @Override
        public boolean isExcluded(Node node) {
            return preservedMap.containsKey(node) || super.isExcluded(node);
        }

        @Override
        public Routable[] getChildren() {
            setChildren(setChildrenTimer);
            return super.getChildren();
        }
    }

    /**
     * A map of nodes to created rnodes
     */
    final protected Map<Node, Routable> nodesMap;

    /**
     * A map of preserved nodes to their nets
     */
    final protected Map<Node, Net> preservedMap;

    /**
     * Visited rnodes data during connection routing
     */
    final protected Collection<Routable> visited;

    final protected RuntimeTracker setChildrenTimer;

    private long totalVisited;

    public RoutableGraph(RuntimeTracker setChildrenTimer) {
        nodesMap = new HashMap<>();
        preservedMap = new HashMap<>();
        visited = new ArrayList<>();
        this.setChildrenTimer = setChildrenTimer;
    }

    public void initialize() {
        totalVisited = 0;
        visited.clear();
    }

    public Net preserve(Node node, Net net) {
        return preservedMap.putIfAbsent(node, net);
    }

    public void unpreserve(Node node) {
        preservedMap.remove(node);
    }

    public Set<Node> getPreservedNodes() {
        return Collections.unmodifiableSet(preservedMap.keySet());
    }

    public Net getPreservedNet(Node node) {
        return preservedMap.get(node);
    }

    public Routable getNode(Node node) {
        return nodesMap.get(node);
    }

    public Set<Node> getNodes() {
        return Collections.unmodifiableSet(nodesMap.keySet());
    }

    public Set<Map.Entry<Node,Routable>> getNodeEntries() {
        return Collections.unmodifiableSet(nodesMap.entrySet());
    }

    public int numNodes() {
        return nodesMap.size();
    }

    protected Routable create(Node node, RoutableType type) {
        return new RoutableNodeImpl(node, type);
    }

    public Pair<Routable,Boolean> getOrCreate(Node node, RoutableType type) {
        final boolean[] inserted = {false};
        Routable rnode = nodesMap.compute(node, (k, v) -> {
            if (v == null) {
                // this is for initializing sources and sinks of those to-be-routed nets' connections
                v = create(node, type);
                inserted[0] = true;
            }
            return v;
        });
        return new Pair<>(rnode, inserted[0]);
    }

    public void visit(Routable rnode) {
        visited.add(rnode);
    }

    /**
     * Resets the expansion history.
     */
    public void resetExpansion() {
        for (Routable node : visited) {
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
        for(Map.Entry<Node,Routable> e : getNodeEntries()){
            RoutableNodeImpl rnode = (RoutableNodeImpl) e.getValue();
            sum += (rnode.children != null) ? rnode.children.length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

}
