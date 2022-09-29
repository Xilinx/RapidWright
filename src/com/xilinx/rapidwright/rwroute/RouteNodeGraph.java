/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.CountUpDownLatch;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
    final protected Collection<RouteNode> visitedBack;

    final protected RuntimeTracker setChildrenTimer;
    final protected RuntimeTracker setParentsTimer;

    private long totalVisited;

    final Design design;

    public static final short SUPER_LONG_LINE_LENGTH_IN_TILES = 60;

    /** Array mapping an INT tile's Y coordinate, to its SLR index */
    final int[] intYToSLRIndex;

    /** Maximum X distance between any two Laguna tiles */
    final int maxXBetweenLaguna;

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
        public boolean isExcludedBack(Node parent, Node child) {
            return RouteNodeGraph.this.isExcludedBack(parent, child);
        }

        @Override
        public RouteNode[] getChildren() {
            setChildren(setChildrenTimer);
            return super.getChildren();
        }

        @Override
        public RouteNode[] getParents() {
            setParents(setParentsTimer);
            return super.getParents();
        }

        @Override
        public int getSLRIndex() {
             return intYToSLRIndex[getEndTileYCoordinate()];
        }
    }

    public RouteNodeGraph(RuntimeTracker setChildrenTimer, /*RuntimeTracker setParentsTimer,*/ Design design) {
        nodesMap = new ConcurrentHashMap<>();
        preservedMap = new ConcurrentHashMap<>();
        asyncPreserveOutstanding = new CountUpDownLatch();
        visited = new ArrayList<>();
        visitedBack = new ArrayList<>();
        this.setChildrenTimer = setChildrenTimer;
        this.setParentsTimer = setChildrenTimer;
        this.design = design;

        Device device = design.getDevice();
        intYToSLRIndex = new int[device.getRows()];
        Tile[][] intTiles = device.getTilesByNameRoot("INT");
        for (int y = 0; y < intTiles.length; y++) {
            Tile[] intTilesAtY = intTiles[y];
            for (int x = 0; x < intTilesAtY.length; x++) {
                Tile tile = intTilesAtY[x];
                if (tile != null) {
                    intYToSLRIndex[y] = tile.getSLR().getId();
                    break;
                }
            }
        }

        Tile[][] lagunaTiles;
        if (device.getSeries() == Series.UltraScalePlus) {
            lagunaTiles = device.getTilesByNameRoot("LAG_LAG");
        } else if (device.getSeries() == Series.UltraScale) {
            lagunaTiles = device.getTilesByNameRoot("LAGUNA_TILE");
        } else {
            lagunaTiles = null;
        }

        int currentMaxXBetweenLaguna = 0;
        if (lagunaTiles != null) {
            for (int y = 0; y < lagunaTiles.length; y++) {
                Tile[] lagunaTilesAtY = lagunaTiles[y];
                Tile lastTile = null;
                for (int x = 0; x < lagunaTilesAtY.length; x++) {
                    Tile tile = lagunaTilesAtY[x];
                    if (tile != null) {
                        if (lastTile != null) {
                            int distFromLastTile = tile.getTileXCoordinate() - lastTile.getTileXCoordinate();
                            currentMaxXBetweenLaguna = Math.max(currentMaxXBetweenLaguna, distFromLastTile);
                        }
                        lastTile = tile;
                    }
                }
            }
        }
        maxXBetweenLaguna = currentMaxXBetweenLaguna;
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
            for (SitePinInst pin : net.getPins()) {
                // SitePinInst.isRouted() is meaningless for output pins
                if (!pin.isRouted() && !pin.isOutPin()) {
                    continue;
                }

                preserve(pin.getConnectedNode(), net);
            }

            for (PIP pip : net.getPIPs()) {
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

    protected boolean isExcludedBack(Node parent, Node child) {
        Tile tile = parent.getTile();
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

    public void visit(boolean forward, RouteNode rnode) {
        if (forward) {
            visited.add(rnode);
        } else {
            visitedBack.add(rnode);
        }
    }

    /**
     * Resets the expansion history.
     */
    public void resetExpansion() {
        for (Collection<RouteNode> c : Arrays.asList(visited, visitedBack)) {
            for (RouteNode node : c) {
                node.reset();
            }
            totalVisited += c.size();
            c.clear();
        }
    }

    public long getTotalVisited() {
        return totalVisited;
    }

    public int averageChildren() {
        int sum = 0;
        for (Map.Entry<Node, RouteNode> e : getNodeEntries()) {
            RouteNodeImpl rnode = (RouteNodeImpl) e.getValue();
            sum += rnode.everExpanded() ? rnode.getChildren().length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

    public int getMaxXBetweenLaguna() {
        return maxXBetweenLaguna;
    }
}
