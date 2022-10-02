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
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
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
    public final int[] nextLagunaColumn;
    public final int[] prevLagunaColumn;
    public Set<Integer> lagunaWireIsVcc;

    protected class RouteNodeImpl extends RouteNode {

        protected RouteNodeImpl(Node node, RouteNodeType type) {
            super(node, type);
        }

        @Override
        protected RouteNode getOrCreate(Node node, RouteNodeType type) {
            return RouteNodeGraph.this.getOrCreate(node, type);
        }

        @Override
        public boolean mustInclude(boolean forward, Node parent, Node child) {
            return RouteNodeGraph.this.mustInclude(forward, parent, child);
        }

        @Override
        public boolean isPreserved(boolean forward, Node node) {
            if (!forward) {
                // When building the routing graph backwards, we can't tell if
                // a preserved node belongs to our net or not; pretend it doesn't
                // (expansion will check this before it gets pushed onto the queue)
                return false;
            }
            return RouteNodeGraph.this.isPreserved(node);
        }

        @Override
        public boolean isExcluded(boolean forward, Node head, Node tail) {
            return RouteNodeGraph.this.isExcluded(forward, head, tail);
        }

        @Override
        public RouteNode[] getChildrenParents(boolean forward) {
            setChildrenParents(forward, (forward) ? setChildrenTimer : setParentsTimer);
            return super.getChildrenParents(forward);
        }

        @Override
        public int getSLRIndex() {
             return intYToSLRIndex[getEndTileYCoordinate()];
        }
    }

    // Class to hold methods common to RouteNodeLagLagImpl and RouteNodeLagunaImpl
    protected abstract class RouteNodeLagunaBase extends RouteNodeImpl {

        protected RouteNodeLagunaBase(Node node, RouteNodeType type) {
            super(node, type);
        }

        @Override
        protected void setBaseCost(RouteNodeType type) {
            assert(type == RouteNodeType.WIRE);
            switch(this.type) {
                case LAGUNA_I:
                case LAGUNA_O:
                    // Make all approaches to SLLs zero-cost to encourage exploration
                    // Assigning a base cost of zero would break congestion resolution for most nodes
                    // (since RWroute.getNodeCost() would return zero) but doing it here should be
                    // okay because this node only leads to a SLL which will have a non-zero base cost
                    baseCost = 0.0f;
                    break;

                case SUPER_LONG_LINE:
                    // Do not allow the cost of SLLs to be scaled with length
                    baseCost = 0.4f;
                    break;

                default:
                    throw new RuntimeException();
            }
        }

        @Override
        public short getBeginTileYCoordinate() {
            boolean reverseSLL = (next != null &&
                    getType() == RouteNodeType.SUPER_LONG_LINE &&
                    next.getBeginTileYCoordinate() == super.getBeginTileYCoordinate());
            return reverseSLL ? getEndTileYCoordinate() : super.getBeginTileYCoordinate();
        }

        @Override
        public short getEndTileYCoordinate() {
            boolean reverseSLL = (prev != null &&
                    getType() == RouteNodeType.SUPER_LONG_LINE &&
                    prev.getEndTileYCoordinate() == super.getEndTileYCoordinate());
            return reverseSLL ? getBeginTileYCoordinate() : super.getEndTileYCoordinate();
        }
    }

    // TileTypeEnum.LAG_LAG only present in UltraScale+
    protected class RouteNodeLagLagImpl extends RouteNodeLagunaBase {

        protected RouteNodeLagLagImpl(Node node, RouteNodeType type) {
            super(node, type);
            assert(node.getTile().getTileTypeEnum() == TileTypeEnum.LAG_LAG);
        }

        @Override
        protected void setType(RouteNodeType type) {
            assert(type == RouteNodeType.WIRE);
            // NOTE: IntentCode is device-dependent
            IntentCode ic = node.getIntentCode();
            String wireName;
            switch (ic) {
                case NODE_LAGUNA_OUTPUT:
                    // TODO: Collect wire indices to save on string comparison
                    wireName = node.getWireName();
                    if (wireName.endsWith("_TXOUT")) {
                        // This is the inner LAGUNA_I, mark it so it gets a base cost discount
                        this.type = RouteNodeType.LAGUNA_I;
                    } else if (wireName.startsWith("RXD")) {
                        this.type = RouteNodeType.LAGUNA_O;
                    } else {
                        throw new RuntimeException();
                    }
                    break;

                case NODE_LAGUNA_DATA:
                    assert (node.getTile().getTileTypeEnum() == TileTypeEnum.LAG_LAG);
                    this.type = RouteNodeType.SUPER_LONG_LINE;
                    break;

                case INTENT_DEFAULT:
                    // TODO: Collect wire indices to save on string comparison
                    wireName = node.getWireName();
                    if (wireName.contains("_RXD")) {
                        // This is the inner LAGUNA_O, mark it so it gets a base cost discount
                        this.type = RouteNodeType.LAGUNA_O;
                    } else {
                        throw new RuntimeException();
                    }
                    break;

                default:
                    throw new RuntimeException();
            }
        }

        protected void setLength() {
            length = (type == RouteNodeType.SUPER_LONG_LINE) ? SUPER_LONG_LINE_LENGTH_IN_TILES : 0;
        }

        @Override
        protected void setEndTileXYCoordinates() {
            setLength();

            Tile baseTile = node.getTile();
            // Correct the X coordinate of all Laguna nodes since they are accessed by the INT
            // tile to its right, yet has the LAG tile has a tile X coordinate one less
            endTileXCoordinate = (short) (baseTile.getTileXCoordinate() + 1);

            if (type == RouteNodeType.SUPER_LONG_LINE) {
                Wire[] wires = node.getAllWiresInNode();
                assert(wires.length == 2);
                Tile endTile = wires[1].getTile();
                assert(endTile.getTileTypeEnum() == TileTypeEnum.LAG_LAG && endTile != baseTile);
                endTileYCoordinate = (short) endTile.getTileYCoordinate();
                length = SUPER_LONG_LINE_LENGTH_IN_TILES;
                assert(Math.abs(endTileYCoordinate - baseTile.getTileYCoordinate()) == length);
            } else {
                endTileYCoordinate = (short) baseTile.getTileYCoordinate();
                length = 0;
            }
        }

        @Override
        public short getBeginTileXCoordinate() {
            // Use end tile coordinate as that's already been correct (see setEndTileXYCoordinates())
            return getEndTileXCoordinate();
        }
    }

    // TileTypeEnum.LAGUNA_TILE only present in UltraScale
    protected class RouteNodeLagunaImpl extends RouteNodeLagunaBase {

        protected RouteNodeLagunaImpl(Node node, RouteNodeType type) {
            super(node, type);
            assert(node.getTile().getTileTypeEnum() == TileTypeEnum.LAGUNA_TILE);
        }

        @Override
        protected void setType(RouteNodeType type) {
            assert(type == RouteNodeType.WIRE);
            // NOTE: IntentCode is device-dependent
            IntentCode ic = node.getIntentCode();
            String wireName;
            switch (ic) {
                case INTENT_DEFAULT:
                    // TODO: Collect wire indices to save on string comparison
                    wireName = node.getWireName();
                    if (wireName.startsWith("UBUMP")) {
                        this.type = RouteNodeType.SUPER_LONG_LINE;
                    } else if (wireName.endsWith("_TXOUT")) {
                        // This is the inner LAGUNA_I, mark it so it gets a base cost discount
                        this.type = RouteNodeType.LAGUNA_I;
                    } else if (wireName.startsWith("RXD")) {
                        this.type = RouteNodeType.LAGUNA_O;
                    } else if (wireName.contains("_RXD")) {
                        // This is the inner LAGUNA_O, mark it so it gets a base cost discount
                        this.type = RouteNodeType.LAGUNA_O;
                    } else {
                        throw new RuntimeException();
                    }
                    break;

                default:
                    throw new RuntimeException();
            }
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
        int tileXCorrection = 0;
        if (device.getSeries() == Series.UltraScalePlus) {
            // Looks like on UltraScale+ only Laguna tiles are always on the left side of an INT tile,
            // with the Laguna tile X coordinate one smaller than the INT. Correct this.
            lagunaTiles = device.getTilesByNameRoot("LAG_LAG");
            tileXCorrection = 1;
        } else if (device.getSeries() == Series.UltraScale) {
            lagunaTiles = device.getTilesByNameRoot("LAGUNA_TILE");
        } else {
            lagunaTiles = null;
        }

        if (lagunaTiles != null) {
            final int maxTileColumns = device.getColumns(); // An over-approximation since this isn't in tiles
            nextLagunaColumn = new int[maxTileColumns];
            prevLagunaColumn = new int[maxTileColumns];
            Arrays.fill(nextLagunaColumn, Integer.MAX_VALUE);
            Arrays.fill(prevLagunaColumn, Integer.MIN_VALUE);
            lagunaWireIsVcc = new HashSet<>();
            Tile[] lagunaTilesAtY = lagunaTiles[0];
            for (int x = 0; x < lagunaTilesAtY.length; x++) {
                Tile tile = lagunaTilesAtY[x];
                if (tile != null) {
                    assert(x == tile.getTileXCoordinate());
                    final int intTileXCoordinate = x + tileXCorrection;

                    // Go backwards til beginning
                    for (int i = intTileXCoordinate; i >= 0; i--) {
                        if (nextLagunaColumn[i] != Integer.MAX_VALUE)
                            break;
                        nextLagunaColumn[i] = intTileXCoordinate;
                    }
                    // Go forwards til end
                    for (int i = intTileXCoordinate; i < prevLagunaColumn.length; i++) {
                        prevLagunaColumn[i] = intTileXCoordinate;
                    }

                    for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
                        String wireName = tile.getWireName(wireIndex);
                        if (wireName.startsWith(Net.VCC_WIRE_NAME)) {
                            lagunaWireIsVcc.add(wireIndex);
                        }
                    }
                }
            }
        } else {
            nextLagunaColumn = null;
            prevLagunaColumn = null;
        }
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
            try {
                nodes.forEach((node) -> preserve(node, net));
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                asyncPreserveOutstanding.countDown();
            }
        });
    }

    public void asyncPreserve(Net net) {
        asyncPreserveOutstanding.countUp();
        ParallelismTools.submit(() -> {
            try {
                List<SitePinInst> pins = net.getPins();
                SitePinInst sourcePin = net.getSource();
                assert (sourcePin == null || pins.contains(sourcePin));
                SitePinInst altSourcePin = net.getAlternateSource();
                assert (altSourcePin == null || pins.contains(altSourcePin));
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
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                asyncPreserveOutstanding.countDown();
            }
        });
    }

    public void awaitPreserve() {
        asyncPreserveOutstanding.await();
    }

    public boolean unpreserve(Node node) {
        return preservedMap.remove(node) != null;
    }

    public boolean isPreserved(Node node) {
        return preservedMap.containsKey(node);
    }

    protected final static Set<TileTypeEnum> allowedTileEnums;
    static {
        allowedTileEnums = new HashSet<>();
        allowedTileEnums.add(TileTypeEnum.INT);
        for (TileTypeEnum e : TileTypeEnum.values()) {
            if (e.toString().startsWith("LAG")) {
                allowedTileEnums.add(e);
            }
        }
    }

    protected boolean mustInclude(boolean forward, Node parent, Node child) {
        return false;
    }

    protected boolean isExcluded(boolean forward, Node head, Node tail) {
        Tile tile = tail.getTile();
        TileTypeEnum tileType = tile.getTileTypeEnum();
        if (allowedTileEnums.contains(tileType)) {
            if (forward)
                return false;
            // Backward router: exclude non-CLE outputs (like Laguna) and VCC_WIRE-s
            if (tail.getIntentCode() != IntentCode.NODE_OUTPUT &&
                    /*!tail.isTiedToVcc() &&*/ (!RouteNode.lagunaTileTypes.contains(tileType) || !lagunaWireIsVcc.contains(tail.getWire())))
                return false;
        }
        return true;
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
        TileTypeEnum tileType = node.getTile().getTileTypeEnum();
        switch (tileType) {
            case LAG_LAG: // UltraScale+
                return new RouteNodeLagLagImpl(node, type);

            case LAGUNA_TILE: // UltraScale
                return new RouteNodeLagunaImpl(node, type);
        }
        return new RouteNodeImpl(node, type);
    }

    public RouteNode getOrCreate(Node node, RouteNodeType type) {
        return nodesMap.computeIfAbsent(node, ($) -> create(node, type));
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
            for (RouteNode rn : c) {
                rn.reset(isPreserved(rn.getNode()));
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
            sum += rnode.everExpanded() ? rnode.getChildrenParents(true).length : 0;
        }
        return Math.round((float) sum / numNodes());
    }
}
