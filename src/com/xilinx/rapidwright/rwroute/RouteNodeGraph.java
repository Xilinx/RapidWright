/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.util.CountUpDownLatch;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulation of RWRoute's routing resource graph.
 */
public class RouteNodeGraph {

    protected final Design design;

    /**
     * A map of nodes to created rnodes
     */
    protected final Map<Tile, RouteNode[]> nodesMap;
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
    private final CountUpDownLatch asyncPreserveOutstanding;

    /**
     * Visited rnodes data during connection routing
     */
    protected final Collection<RouteNode> targets;

    protected final RuntimeTracker setChildrenTimer;

    public static final short SUPER_LONG_LINE_LENGTH_IN_TILES = 60;

    /** Array mapping an INT tile's Y coordinate, to its SLR index */
    private final int[] intYToSLRIndex;
    public final int[] nextLagunaColumn;
    public final int[] prevLagunaColumn;

    /** 
     * Map indicating which wire indices within a Laguna-adjacent INT tile have
     * IntentCode.NODE_PINFEED that lead into the Laguna tile.
     */
    protected final Map<Tile, BitSet> lagunaI;

    /** Map indicating which wire indices within an INT tile should be considered
     * accessible only if it is within the same column (same X tile coordinate) as
     * the target tile.
     */
    protected final Map<TileTypeEnum, BitSet> accessibleWireOnlyIfAboveBelowTarget;

    /** Map indicating the wire indices corresponding to the [A-H]MUX output */
    protected final Map<TileTypeEnum, BitSet> muxWires;

    /** Flag for whether LUT routethrus are to be considered
     */
    protected final boolean lutRoutethru;

    protected class RouteNodeImpl extends RouteNode {
        protected RouteNodeImpl(Node node, RouteNodeType type) {
            super(node, type, lagunaI);
        }

        @Override
        protected RouteNode getOrCreate(Node node, RouteNodeType type) {
            return RouteNodeGraph.this.getOrCreate(node, type);
        }

        @Override
        public boolean isExcluded(Node child) {
            return RouteNodeGraph.this.isExcluded(this, child);
        }

        @Override
        public void setTarget(boolean isTarget) {
            if (isTarget) {
                assert(!isTarget());
                targets.add(this);
            }
            super.setTarget(isTarget);
        }

        @Override
        public RouteNode[] getChildren() {
            setChildren(setChildrenTimer);
            return super.getChildren();
        }

        @Override
        public int getSLRIndex() {
             return intYToSLRIndex[getEndTileYCoordinate()];
        }
    }

    public RouteNodeGraph(RuntimeTracker setChildrenTimer, Design design, RWRouteConfig config) {
        this.design = design;
        lutRoutethru = config.isLutRoutethru();

        nodesMap = new HashMap<>();
        nodesMapSize = 0;
        preservedMap = new ConcurrentHashMap<>();
        preservedMapSize = new AtomicInteger();
        asyncPreserveOutstanding = new CountUpDownLatch();
        targets = new ArrayList<>();
        this.setChildrenTimer = setChildrenTimer;

        Device device = design.getDevice();
        intYToSLRIndex = new int[device.getRows()];
        Tile[][] intTiles = device.getTilesByRootName("INT");
        for (int y = 0; y < intTiles.length; y++) {
            Tile[] intTilesAtY = intTiles[y];
            for (Tile tile : intTilesAtY) {
                if (tile != null) {
                    intYToSLRIndex[y] = tile.getSLR().getId();
                    break;
                }
            }
        }

        accessibleWireOnlyIfAboveBelowTarget = new EnumMap<>(TileTypeEnum.class);
        BitSet wires = new BitSet();
        Tile intTile = device.getArbitraryTileOfType(TileTypeEnum.INT);
        // Device.getArbitraryTileOfType() typically gives you the North-Eastern-most
        // tile (with minimum X, maximum Y). Analyze the tile just below that.
        intTile = intTile.getTileXYNeighbor(0, -1);
        for (int wireIndex = 0; wireIndex < intTile.getWireCount(); wireIndex++) {
            Node baseNode = Node.getNode(intTile, wireIndex);
            if (baseNode == null) {
                continue;
            }
            Tile baseTile = baseNode.getTile();
            String wireName = baseNode.getWireName();
            if (wireName.startsWith("BOUNCE_")) {
                assert(baseNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                assert(baseTile.getTileXCoordinate() == intTile.getTileXCoordinate());
                // Uphill from INT_NODE_IMUX_* in tile above/below and INODE_* in above/target or below/target tiles
                // Downhill to INT_NODE_IMUX_* and INODE_* to above/below tile
            } else if (wireName.startsWith("BYPASS_")) {
                assert(baseNode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                assert(baseTile == intTile);
                assert(wireIndex == baseNode.getWire());
                // Uphill and downhill are INT_NODE_IMUX_* in the target tile and INODE_* to above/below tiles
            } else if (wireName.startsWith("INT_NODE_GLOBAL_")) {
                assert(baseNode.getIntentCode() == IntentCode.NODE_LOCAL);
                assert(baseTile == intTile);
                assert(wireIndex == baseNode.getWire());
                // Downhill to CTRL_* in the target tile, INODE_* to above/below tile, INT_NODE_IMUX_* in target tile
            } else if (wireName.startsWith("INT_NODE_IMUX_") &&
                    // Do not block INT_NODE_IMUX node accessibility when LUT routethrus are considered
                    !lutRoutethru) {
                assert(baseNode.getIntentCode() == IntentCode.NODE_LOCAL);
                assert(baseTile == intTile);
                assert(wireIndex == baseNode.getWire());
                // Downhill to BOUNCE_* in the above/below/target tile, BYPASS_* in the base tile, IMUX_* in target tile
            } else if (wireName.startsWith("INODE_")) {
                assert(baseNode.getIntentCode() == IntentCode.NODE_LOCAL);
                assert(baseTile.getTileXCoordinate() == intTile.getTileXCoordinate());
                // Uphill from nodes in above/target or below/target tiles
                // Downhill to BOUNCE_*/BYPASS_*/IMUX_* in above/target or below/target tiles
            } else {
                continue;
            }

            wires.set(baseNode.getWire());
        }
        accessibleWireOnlyIfAboveBelowTarget.put(intTile.getTileTypeEnum(), wires);

        if (lutRoutethru) {
            muxWires = new EnumMap<>(TileTypeEnum.class);
            for (TileTypeEnum tileTypeEnum : Utils.getCLBTileTypes()) {
                Tile clbTile = device.getArbitraryTileOfType(tileTypeEnum);
                if (clbTile == null) {
                    continue;
                }
                wires = new BitSet();
                for (int wireIndex = 0; wireIndex < clbTile.getWireCount(); wireIndex++) {
                    String wireName = clbTile.getWireName(wireIndex);
                    if (wireName.endsWith("MUX")) {
                        assert(Node.getNode(clbTile, wireIndex).getTile() == clbTile &&
                               Node.getNode(clbTile, wireIndex).getWire() == wireIndex);
                        wires.set(wireIndex);
                    }
                }
                if (wires.isEmpty()) {
                    continue;
                }
                muxWires.put(tileTypeEnum, wires);
            }
        } else {
            muxWires = null;
        }

        Tile[][] lagunaTiles;
        if (device.getSeries() == Series.UltraScalePlus) {
            lagunaTiles = device.getTilesByRootName("LAG_LAG");
        } else if (device.getSeries() == Series.UltraScale) {
            lagunaTiles = device.getTilesByRootName("LAGUNA_TILE");
        } else {
            lagunaTiles = null;
        }

        if (lagunaTiles != null) {
            final int maxTileColumns = device.getColumns(); // An over-approximation since this isn't in tiles
            nextLagunaColumn = new int[maxTileColumns];
            prevLagunaColumn = new int[maxTileColumns];
            lagunaI = new IdentityHashMap<>();
            Arrays.fill(nextLagunaColumn, Integer.MAX_VALUE);
            Arrays.fill(prevLagunaColumn, Integer.MIN_VALUE);
            for (int y = 0; y < lagunaTiles.length; y++) {
                Tile[] lagunaTilesAtY = lagunaTiles[y];
                for (int x = 0; x < lagunaTilesAtY.length; x++) {
                    Tile tile = lagunaTilesAtY[x];
                    if (tile != null) {
                        if (y == 0) {
                            assert(x == tile.getTileXCoordinate());
                            // Looks like (on US+) LAGUNA tiles are always on the left side of an INT tile,
                            // with tile X coordinate one smaller
                            final int intTileXCoordinate = x + 1;

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
                        }

                        // Examine all wires in Laguna tile. Record those uphill of a Super Long Line
                        // that originates in an INT tile (and thus must be a NODE_PINFEED).
                        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
                            if (!tile.getWireName(wireIndex).startsWith("UBUMP")) {
                                continue;
                            }
                            Node sllNode = Node.getNode(tile, wireIndex);
                            for (Node uphill1 : sllNode.getAllUphillNodes()) {
                                for (Node uphill2 : uphill1.getAllUphillNodes()) {
                                    Tile uphill2Tile = uphill2.getTile();
                                    if (!Utils.isInterConnect(uphill2Tile.getTileTypeEnum())) {
                                        continue;
                                    }
                                    assert(uphill2.getIntentCode() == IntentCode.NODE_PINFEED);
                                    lagunaI.computeIfAbsent(uphill2Tile, k -> new BitSet())
                                            .set(uphill2.getWire());
                                }
                            }
                        }
                    }
                }
            }
        } else {
            nextLagunaColumn = null;
            prevLagunaColumn = null;
            lagunaI = null;
        }
    }

    public void initialize() {
        targets.clear();
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
        // Do not clobber the old value
        if (oldNet == null) {
            nets[wireIndex] = net;
        }
        return oldNet;
    }

    public void preserve(Net net, List<SitePinInst> pins) {
        for (SitePinInst pin : pins) {
            preserve(pin.getConnectedNode(), net);
        }

        for (PIP pip : net.getPIPs()) {
            preserve(pip.getStartNode(), net);
            preserve(pip.getEndNode(), net);
        }
    }

    public void preserveAsync(Net net, List<SitePinInst> pins) {
        asyncPreserveOutstanding.countUp();
        ParallelismTools.submit(() -> {
            try {
                preserve(net, pins);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                asyncPreserveOutstanding.countDown();
            }
        });
    }

    public void preserve(Net net) {
        preserve(net, net.getPins());
    }

    public void preserveAsync(Net net) {
        preserveAsync(net, net.getPins());
    }

    public void awaitPreserve() {
        // TODO: Calling thread to do useful work when waiting
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

    private static final Set<TileTypeEnum> allowedTileEnums;
    static {
        allowedTileEnums = EnumSet.of(TileTypeEnum.INT);
        allowedTileEnums.addAll(Utils.getLagunaTileTypes());
    }

    protected boolean isExcludedTile(Node child) {
        Tile tile = child.getTile();
        TileTypeEnum tileType = tile.getTileTypeEnum();
        return !allowedTileEnums.contains(tileType);
    }

    protected boolean isExcluded(Node parent, Node child) {
        if (isPreserved(child)) {
            return true;
        }

        if (isExcludedTile(child)) {
            if (!allowRoutethru(parent, child)) {
                return true;
            }
        }

        if (child.getIntentCode() == IntentCode.NODE_PINFEED) {
            // PINFEEDs can lead to a site pin, or into a Laguna tile
            RouteNode childRnode = getNode(child);
            if (childRnode != null) {
                assert(childRnode.getType() == RouteNodeType.PINFEED_I ||
                       childRnode.getType() == RouteNodeType.LAGUNA_I ||
                        (lutRoutethru && childRnode.getType() == RouteNodeType.WIRE));
            } else if (!lutRoutethru) {
                // child does not already exist in our routing graph, meaning it's not a used site pin
                // in our design, but it could be a LAGUNA_I
                if (lagunaI == null) {
                    // No LAGUNA_Is
                    return true;
                }
                BitSet bs = lagunaI.get(child.getTile());
                if (bs == null || !bs.get(child.getWire())) {
                    // Not a LAGUNA_I -- skip it
                    return true;
                }
            }
        }

        return false;
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

    public Iterable<RouteNode> getRnodes() {
        return new Iterable<RouteNode>() {
            final Iterator<Map.Entry<Tile, RouteNode[]>> it = nodesMap.entrySet().iterator();
            RouteNode[] curr = it.hasNext() ? it.next().getValue() : null;
            int index = 0;

            @Override
            public Iterator<RouteNode> iterator() {
                return new Iterator<RouteNode>() {
                    @Override
                    public boolean hasNext() {
                        if (curr == null) {
                            return false;
                        }
                        while(true) {
                            while (index < curr.length) {
                                if (curr[index] != null) {
                                    return true;
                                }
                                index++;
                            }
                            if (!it.hasNext()) {
                                return false;
                            }
                            curr = it.next().getValue();
                            assert(curr != null);
                            index = 0;
                        }
                    }

                    @Override
                    public RouteNode next() {
                        hasNext();
                        assert(curr[index] != null);
                        return curr[index++];
                    }
                };
            }
        };
    }

    public int numNodes() {
        return nodesMapSize;
    }

    protected RouteNode create(Node node, RouteNodeType type) {
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
        for (RouteNode node : targets) {
            assert(node.isTarget());
            node.setTarget(false);
        }
        targets.clear();
    }

    public int averageChildren() {
        int sum = 0;
        for (RouteNode rnode : getRnodes()) {
            sum += rnode.everExpanded() ? rnode.getChildren().length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

    public boolean isAccessible(RouteNode childRnode, Connection connection) {
        Tile childTile = childRnode.getTile();
        TileTypeEnum childTileType = childTile.getTileTypeEnum();
        BitSet bs = accessibleWireOnlyIfAboveBelowTarget.get(childTileType);
        if (bs == null || !bs.get(childRnode.getWire())) {
            return true;
        }

        int childX = childTile.getTileXCoordinate();
        if (connection.isCrossSLR() && nextLagunaColumn[childX] == childX) {
            // Connection crosses SLR and this is a Laguna column
            return true;
        }

        Tile sinkTile = connection.getSinkRnode().getTile();
        if (childX != sinkTile.getTileXCoordinate()) {
            return false;
        }

        return Math.abs(childTile.getTileYCoordinate() - sinkTile.getTileYCoordinate()) <= 1;
    }

    protected boolean allowRoutethru(Node head, Node tail) {
        if (!Utils.isCLB(tail.getTile().getTileTypeEnum())) {
            return false;
        }

        if (!RouteThruHelper.isRouteThruPIPAvailable(design, head, tail)) {
            return false;
        }
        assert(PIP.getArbitraryPIP(head, tail).isRouteThru());

        BitSet bs = muxWires.get(tail.getTile().getTileTypeEnum());
        if (bs != null && bs.get(tail.getWire())) {
            // Disallow * -> [A-H]MUX routethrus since Vivado does not support the LUT
            // being fractured to support more than one routethru net
            return false;
        }
        return true;
    }
}
