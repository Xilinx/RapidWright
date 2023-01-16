/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;

/**
 * A graph-based tool based on Depth-first Search to fix illegal routes,
 * i.e. routed nets with path cycles or multi-driver nodes.
 */
public class RouteFixer{
    private NetWrapper netp;
    private Map<Node, NodeWithDelay> nodeMap;
    private Set<NodeWithDelay> sources;
    private int vertexId;

    public RouteFixer(NetWrapper netp, RouteNodeGraph routingGraph) {
        this.netp = netp;
        nodeMap = new HashMap<>();
        sources = new HashSet<>();
        vertexId = 0;
        buildGraph(netp, routingGraph);
    }

    private void buildGraph(NetWrapper netWrapper, RouteNodeGraph routingGraph) {
        for (Connection connection:netWrapper.getConnections()) {
            List<Node> nodes = connection.getNodes();
            // nodes of connections are in the order from sink to source
            int vertexSize = nodes.size();
            for (int i = vertexSize - 1; i > 0; i--) {
                Node cur = nodes.get(i);
                Node next = nodes.get(i - 1);

                RouteNode currRnode = routingGraph.getNode(cur);
                RouteNode nextRnode = routingGraph.getNode(next);
                float currDly = currRnode == null? 0f : currRnode.getDelay();
                float nextDly = nextRnode == null? 0f : nextRnode.getDelay();

                NodeWithDelay newCur = nodeMap.computeIfAbsent(cur, (k) -> new NodeWithDelay(vertexId++, cur, currDly));
                NodeWithDelay newNext = nodeMap.computeIfAbsent(next, (k) -> new NodeWithDelay(vertexId++, next, nextDly));
                if (i == 1) {
                    newNext.setSink(true);
                }
                if (i == vertexSize - 1) {
                    sources.add(newCur);
                }
                newCur.addChildren(newNext);
            }
        }
    }

    /**
     * Finalizes the route of each connection based on the delay-aware path merging.
     */
    public void finalizeRoutesOfConnections() {
        setShortestPathToEachVertex();

        for (Connection connection : netp.getConnections()) {
            List<Node> nodes = connection.getNodes();
            if (nodes.isEmpty()) {
                continue;
            }
            NodeWithDelay csink = nodeMap.get(nodes.get(0));
            nodes.clear();
            nodes.add(csink.getNode());
            NodeWithDelay prev = csink.getPrev();
            while (prev != null) {
                nodes.add(prev.getNode());
                prev = prev.getPrev();
            }
        }
    }

    private void setShortestPathToEachVertex() {
        PriorityQueue<NodeWithDelay> queue = new PriorityQueue<>(NodeWithDelayComparator);

        queue.clear();
        for (NodeWithDelay source : sources) {
            source.cost = source.delay;
            source.setPrev(null);
            queue.add(source);
        }

        while (!queue.isEmpty()) {
            NodeWithDelay cur = queue.poll();
            Set<NodeWithDelay> nexts = cur.children;
            if (nexts == null || nexts.isEmpty()) continue;
            for (NodeWithDelay next : nexts) {
                float newCost = cur.cost + next.getDelay()
                        + DelayEstimatorBase.getExtraDelay(next.getNode(), DelayEstimatorBase.isLong(cur.getNode()));
                if (!next.isVisited() || (next.isVisited() && newCost < next.cost)) {
                    // The second condition is necessary,
                    // because a smaller path delay from the source to the current "next" could be achieved later.
                    next.cost = newCost;
                    next.setPrev(cur);
                    next.setVisited(true);
                    queue.add(next);
                }
            }
        }
    }

    private static final Comparator<NodeWithDelay> NodeWithDelayComparator = (a, b) -> Float.compare(a.getDelay(), b.getDelay());

    static class NodeWithDelay{
        private int id;
        private Node node;
        private float delay;
        private boolean isSink;
        private NodeWithDelay prev;
        private float cost;
        private boolean visited;
        private Set<NodeWithDelay> children;

        public NodeWithDelay(int id, Node node, float delay) {
            this.id = id;
            this.node = node;
            this.delay = delay;
            isSink = false;
            prev = null;
            cost = Short.MAX_VALUE;
            visited = false;
            children = new HashSet<>();
        }

        public void addChildren(NodeWithDelay child) {
            children.add(child);
        }

        public boolean isVisited() {
            return visited;
        }

        public void setVisited(boolean visited) {
            this.visited = visited;
        }

        public NodeWithDelay getPrev() {
            return prev;
        }

        public void setPrev(NodeWithDelay driver) {
            if (prev == null) {
                prev = driver;
            } else if (driver.cost < prev.cost) {
                prev = driver;
            }
        }

        public boolean isSink() {
            return isSink;
        }

        public void setSink(boolean isSink) {
            this.isSink = isSink;
        }

        public float getDelay() {
            return delay;
        }

        public Node getNode() {
            return node;
        }

        public void setDelay(float f) {
            delay = f;
        }

        @Override
        public int hashCode() {
            return node.hashCode();
        }

        @Override
        public String toString() {
            return id + ", " + node.toString() + ", delay = " + delay + ", sink? " + isSink;
        }
    }

}
