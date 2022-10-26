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
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulation of RWRoute's routing resource graph.
 */
public class RouteNodeGraph {

    /**
     * A map of nodes to created rnodes
     */
    final protected Map<Tile, RouteNode[]> nodesMap;
    private int nodesMapSize;

    /**
     * A map of preserved nodes to their nets
     */
    private final Map<Tile, Net[]> preservedMap;
    private final AtomicInteger preservedMapSize;

    /**
     * A synchronization object tracking the number of outstanding calls to
     * asyncPreserve()
     */
    final private CountUpDownLatch asyncPreserveOutstanding;

    /**
     * Visited rnodes data during connection routing
     */
    protected final BitSet visited;
    protected final BitSet visitedBack;

    final protected RuntimeTracker setChildrenTimer;
    final protected RuntimeTracker setParentsTimer;

    final Design design;

    public static final short SUPER_LONG_LINE_LENGTH_IN_TILES = 60;

    /** Array mapping an INT tile's Y coordinate, to its SLR index */
    final int[] intYToSLRIndex;
    public final int[] nextLagunaColumn;
    public final int[] prevLagunaColumn;
    public Set<Integer> lagunaWireIsVcc;



    protected class RouteNodeImpl extends RouteNode {

        final private int index;

        protected RouteNodeImpl(Node node, RouteNodeType type) {
            super(node, type);
            index = numNodes();
            assert(!isVisited(true));
            assert(!isVisited(false));
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
            boolean preserved = RouteNodeGraph.this.isPreserved(node);
            if (forward) {
                return preserved;
            }

            // When building the routing graph backwards, we can't tell if a preserved node
            // belongs to our net or not, however, we can tell if it belongs to any net that
            // needs routing by checking to see if a rnode exists.
            // Defer to routing expansion to check for the correct net before pushing it onto
            // the queue.
            return RouteNodeGraph.this.isPreserved(node) && RouteNodeGraph.this.getNode(node) == null;
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
        public boolean isVisited(boolean forward) {
            return (forward ? visited : visitedBack).get(index);
        }

        @Override
        public void setVisited(boolean forward) {
            BitSet v = (forward) ? visited : visitedBack;
            assert(!v.get(index));
            v.set(index);
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
                if (wires.length == 2) {
                    Tile endTile = wires[1].getTile();
                    assert(endTile.getTileTypeEnum() == baseTile.getTileTypeEnum() && endTile != baseTile);
                    endTileYCoordinate = (short) endTile.getTileYCoordinate();
                    length = SUPER_LONG_LINE_LENGTH_IN_TILES;
                    assert(Math.abs(endTileYCoordinate - baseTile.getTileYCoordinate()) == length);
                } else {
                    // A dummy SLL at the top or bottom edge of device
                    assert(wires.length == 1);
                    endTileYCoordinate = (short) baseTile.getTileYCoordinate();
                    length = 0;
                }
            } else {
                endTileYCoordinate = (short) baseTile.getTileYCoordinate();
                length = 0;
            }
        }

        @Override
        public short getBeginTileYCoordinate() {
            boolean reverseSLL = (next != null &&
                    getType() == RouteNodeType.SUPER_LONG_LINE &&
                    next.getBeginTileYCoordinate() == super.getBeginTileYCoordinate());
            return reverseSLL ? super.getEndTileYCoordinate() : super.getBeginTileYCoordinate();
        }

        @Override
        public short getEndTileYCoordinate() {
            boolean reverseSLL = (prev != null &&
                    getType() == RouteNodeType.SUPER_LONG_LINE &&
                    prev.getEndTileYCoordinate() == super.getEndTileYCoordinate());
            return reverseSLL ? super.getBeginTileYCoordinate() : super.getEndTileYCoordinate();
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
        nodesMap = new HashMap<>();
        nodesMapSize = 0;
        preservedMap = new ConcurrentHashMap<>();
        preservedMapSize = new AtomicInteger();
        asyncPreserveOutstanding = new CountUpDownLatch();
        visited = new BitSet();
        visitedBack = new BitSet();
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
    }

    protected Net preserve(Node node, Net net) {
        Net oldNet = preserve(node.getTile(), node.getWire(), net);
        if (oldNet == null) {
            preservedMapSize.incrementAndGet();
        }
        return oldNet;
    }

    private Net preserve(Tile tile, int wireIndex, Net net) {
        // Assumes that tile/wireIndex describes the base wire on the node
        // No need to synchronize access to 'nets' since collisions are not expected
        Net[] nets = preservedMap.computeIfAbsent(tile, (t) -> new Net[t.getWireCount()]);
        Net oldNet = nets[wireIndex];
        nets[wireIndex] = net;
        return oldNet;
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
        boolean unpreserved = unpreserve(node.getTile(), node.getWire());
        if (unpreserved) {
            preservedMapSize.decrementAndGet();
        }
        return unpreserved;
    }

    private boolean unpreserve(Tile tile, int wireIndex) {
        // Assumes that tile/wireIndex describes the base wire on its node
        Net[] nets = preservedMap.get(tile);
        if (nets == null || nets[wireIndex] == null)
            return false;
        nets[wireIndex] = null;
        return true;
    }

    public boolean isPreserved(Node node) {
        Tile tile = node.getTile();
        int wireIndex = node.getWire();
        Net[] nets = preservedMap.get(tile);
        return nets != null && nets[wireIndex] != null;
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
            // Backward router: exclude non-CLE outputs (like Laguna) since they don't support
            // routethru-s, and VCC_WIRE-s
            if (tail.getIntentCode() != IntentCode.NODE_OUTPUT && !tail.isTiedToVcc() &&
                    // See https://github.com/Xilinx/RapidWright/pull/553 for an example of a
                    // VCC_WIRE not being marked as such
                    (!RouteNode.lagunaTileTypes.contains(tileType) || !lagunaWireIsVcc.contains(tail.getWire())))
                return false;
        }
        return true;
    }

    public List<Node> getPreservedNodes() {
        awaitPreserve();
        // TODO: Return a custom Interable to save on creating a new List
        int size = preservedMapSize.get();
        List<Node> nodes = new ArrayList<>(size);
        for (Map.Entry<Tile,Net[]> e : preservedMap.entrySet()) {
            Tile tile = e.getKey();
            Net[] nets = e.getValue();
            for (int wireIndex = 0; wireIndex < nets.length; wireIndex++) {
                if (nets[wireIndex] != null) {
                    nodes.add(Node.getNode(tile, wireIndex));
                }
            }
        }
        assert(nodes.size() == size);
        return nodes;
    }

    public Net getPreservedNet(Node node) {
        return getPreservedNet(node.getTile(), node.getWire());
    }

    private Net getPreservedNet(Tile tile, int wireIndex) {
        // Assumes that tile/wireIndex describes the base wire on its node
        Net[] nets = preservedMap.get(tile);
        return nets != null ? nets[wireIndex] : null;
    }

    public RouteNode getNode(Node node) {
        Tile tile = node.getTile();
        int wireIndex = node.getWire();
        return getNode(tile, wireIndex);
    }

    private RouteNode getNode(Tile tile, int wireIndex) {
        // Assumes that tile/wireIndex describes the base wire on its node
        RouteNode[] rnodes = nodesMap.get(tile);
        return rnodes != null ? rnodes[wireIndex] : null;
    }

    public List<Node> getNodes() {
        // TODO: Return a custom Interable to save on creating a new List
        List<Node> nodes = new ArrayList<>(nodesMapSize);
        for (Map.Entry<Tile,RouteNode[]> e : nodesMap.entrySet()) {
            Tile tile = e.getKey();
            RouteNode[] rnodes = e.getValue();
            for (int wireIndex = 0; wireIndex < rnodes.length; wireIndex++) {
                RouteNode rnode = rnodes[wireIndex];
                if (rnode != null) {
                    nodes.add(Node.getNode(tile, wireIndex));
                }
            }
        }
        assert(nodes.size() == nodesMapSize);
        return nodes;
    }

    public List<RouteNode> getRnodes() {
        // TODO: Return a custom Interable to save on creating a new List
        List<RouteNode> rnodes = new ArrayList<>(nodesMapSize);
        for (Map.Entry<?,RouteNode[]> e : nodesMap.entrySet()) {
            RouteNode[] array = e.getValue();
            for (int wireIndex = 0; wireIndex < array.length; wireIndex++) {
                RouteNode rnode = array[wireIndex];
                if (rnode != null) {
                    rnodes.add(rnode);
                }
            }
        }
        assert(rnodes.size() == nodesMapSize);
        return rnodes;
    }

    public int numNodes() {
        return nodesMapSize;
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
        Tile tile = node.getTile();
        int wireIndex = node.getWire();
        RouteNode[] rnodes = nodesMap.computeIfAbsent(tile, (t) -> new RouteNode[t.getWireCount()]);
        RouteNode rnode = rnodes[wireIndex];
        if (rnode == null) {
            rnode = create(node, type);
            rnodes[wireIndex] = rnode;
            nodesMapSize++;
        }
        return rnode;
    }

    /**
     * Resets the expansion history.
     */
    public void resetExpansion() {
        visited.clear();
        visitedBack.clear();
    }

    public int averageChildren() {
        int sum = 0;
        for (RouteNode rnode : getRnodes()) {
            sum += rnode.everExpanded() ? rnode.getChildrenParents(true).length : 0;
        }
        return Math.round((float) sum / numNodes());
    }
}
