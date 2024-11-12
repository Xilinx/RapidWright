/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

import java.util.Arrays;
import java.util.BitSet;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
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
import com.xilinx.rapidwright.util.Utils;

/**
 * Encapsulation of RWRoute's routing resource graph.
 */
public class RouteNodeGraph {

    protected final Design design;

    /**
     * A map of nodes to created rnodes
     */
    protected final Map<Tile, RouteNode[]> nodesMap;
    private final AtomicInteger nodesMapSize;

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

    private long createRnodeTime;

    public static final short SUPER_LONG_LINE_LENGTH_IN_TILES = 60;

    /** Array mapping an INT tile's Y coordinate, to its SLR index */
    public final int[] intYToSLRIndex;
    public final int[] nextLagunaColumn;
    public final int[] prevLagunaColumn;

    /** 
     * Map indicating which wire indices within a Laguna-adjacent INT tile have
     * IntentCode.NODE_PINFEED that lead into the Laguna tile.
     */
    protected final Map<Tile, BitSet> lagunaI;

    /** Map indicating (for UltraScale/UltraScale+ only) the wire indices corresponding to the [A-H]MUX output
     * to be blocked during LUT routethrus
     */
    protected final Map<TileTypeEnum, BitSet> ultraScalesMuxWiresToBlockWhenLutRoutethru;

    /** Flag for whether LUT routethrus are to be considered */
    protected final boolean lutRoutethru;

    /** Map indicating (for UltraScale/UltraScale+ only) the wire indices that have a local intent code,
     *  but is what RWRoute will consider to be non-local, e.g. INT_NODE_SDQ_*
     */
    protected final Map<TileTypeEnum, BitSet> ultraScalesNonLocalWires;

    /** Map indicating the wire indices corresponding to the east/west side of interconnect tiles */
    protected final Map<TileTypeEnum, BitSet[]> eastWestWires;

    /** Flag for whether design targets the Versal series */
    protected final boolean isVersal;

    public RouteNodeGraph(Design design, RWRouteConfig config) {
        this(design, config, new HashMap<>());
    }

    protected RouteNodeGraph(Design design, RWRouteConfig config, Map<Tile, RouteNode[]> nodesMap) {
        this.design = design;
        lutRoutethru = config.isLutRoutethru();

        this.nodesMap = nodesMap;
        nodesMapSize = new AtomicInteger();
        preservedMap = new ConcurrentHashMap<>();
        preservedMapSize = new AtomicInteger();
        asyncPreserveOutstanding = new CountUpDownLatch();
        createRnodeTime = 0;

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

        Series series = device.getSeries();
        boolean isUltraScale = series == Series.UltraScale;
        boolean isUltraScalePlus = series == Series.UltraScalePlus;
        isVersal = series == Series.Versal;
        Tile intTile;
        Pattern eastWestPattern;
        eastWestWires = new EnumMap<>(TileTypeEnum.class);
        BitSet wires = new BitSet();
        if (isUltraScale || isUltraScalePlus) {
            intTile = device.getArbitraryTileOfType(TileTypeEnum.INT);
            // Device.getArbitraryTileOfType() typically gives you the North-Western-most
            // tile (with minimum X, maximum Y). Analyze the tile just below that.
            intTile = intTile.getTileXYNeighbor(0, -1);

            ultraScalesNonLocalWires = new EnumMap<>(TileTypeEnum.class);
            ultraScalesNonLocalWires.put(intTile.getTileTypeEnum(), wires);

            eastWestPattern = Pattern.compile("(((BOUNCE|BYPASS|IMUX|INODE(_[12])?)_(?<eastwest>[EW]))|INT_NODE_IMUX_(?<inode>\\d+)_).*");
        } else {
            assert(isVersal);

            // Find an INT tile adjacent to a CLE_BC_CORE tile since Versal devices may contain AIEs on their northern edge
            Tile bcCoreTile = device.getArbitraryTileOfType(TileTypeEnum.CLE_BC_CORE);
            // Device.getArbitraryTileOfType() typically gives you the North-Western-most
            // tile (with minimum X, maximum Y). Analyze the tile just below that.
            intTile = bcCoreTile.getTileNeighbor(2, 0);
            assert(intTile.getTileTypeEnum() == TileTypeEnum.INT);

            ultraScalesNonLocalWires = null;

            eastWestPattern = Pattern.compile("(((BOUNCE|IMUX_B|BNODE_OUTS)_(?<eastwest>[EW])(?<bounce>\\d+))|INT_NODE_IMUX_ATOM_(?<inode>\\d+)_).*");
        }

        for (int wireIndex = 0; wireIndex < intTile.getWireCount(); wireIndex++) {
            Node baseNode = Node.getNode(intTile, wireIndex);
            if (baseNode == null) {
                continue;
            }

            String baseWireName = baseNode.getWireName();
            IntentCode baseIntentCode = baseNode.getIntentCode();
            if (isUltraScale || isUltraScalePlus) {
                if (baseIntentCode == IntentCode.NODE_LOCAL) {
                    Tile baseTile = baseNode.getTile();
                    assert(baseTile.getTileTypeEnum() == intTile.getTileTypeEnum());
                    if (isUltraScalePlus) {
                        if (baseWireName.startsWith("INT_NODE_SDQ_") || baseWireName.startsWith("SDQNODE_")) {
                            if (baseTile != intTile) {
                                if (baseWireName.endsWith("_FT0")) {
                                    assert(baseTile.getTileYCoordinate() == intTile.getTileYCoordinate() - 1);
                                } else {
                                    assert(baseWireName.endsWith("_FT1"));
                                    assert(baseTile.getTileYCoordinate() == intTile.getTileYCoordinate() + 1);
                                }
                            }
                            wires.set(baseNode.getWireIndex());
                            continue;
                        }
                    } else {
                        assert(isUltraScale);
                        if (baseWireName.startsWith("INT_NODE_SINGLE_DOUBLE_") || baseWireName.startsWith("SDND") ||
                                baseWireName.startsWith("INT_NODE_QUAD_LONG") || baseWireName.startsWith("QLND")) {
                            if (baseTile != intTile) {
                                if (baseWireName.endsWith("_FTN")) {
                                    assert(baseTile.getTileYCoordinate() == intTile.getTileYCoordinate() - 1);
                                } else {
                                    assert(baseWireName.endsWith("_FTS"));
                                    assert(baseTile.getTileYCoordinate() == intTile.getTileYCoordinate() + 1);
                                }
                            }
                            wires.set(baseNode.getWireIndex());
                            continue;
                        }
                    }
                    if (baseIntentCode != IntentCode.NODE_PINFEED && baseIntentCode != IntentCode.NODE_PINBOUNCE) {
                        continue;
                    }
                }
            } else {
                assert(isVersal);

                if (!EnumSet.of(IntentCode.NODE_IMUX, IntentCode.NODE_PINBOUNCE, IntentCode.NODE_INODE,
                        IntentCode.NODE_CLE_BNODE).contains(baseIntentCode)) {
                    continue;
                }
            }

            Matcher m = eastWestPattern.matcher(baseWireName);
            if (m.matches()) {
                BitSet[] eastWestWires = this.eastWestWires.computeIfAbsent(baseNode.getTile().getTileTypeEnum(),
                        k -> new BitSet[]{new BitSet(), new BitSet()});
                BitSet eastWires = eastWestWires[0];
                BitSet westWires = eastWestWires[1];
                String ew = m.group("eastwest");
                String inode;
                if (ew != null) {
                    assert(ew.equals("E") || ew.equals("W"));
                    if (baseIntentCode == IntentCode.NODE_CLE_BNODE) {
                        ew = ew.equals("E") ? "W" : "E";
                    }
                    // Integer bounce = isVersal && baseIntentCode == IntentCode.NODE_PINBOUNCE ? Integer.valueOf(m.group("bounce")) : null;
                    if (ew.equals("E") /*&& (bounce == null || bounce < 16)*/) {
                        eastWires.set(baseNode.getWireIndex());
                    } else if (ew.equals("W") /*&& (bounce == null || bounce < 16)*/) {
                        westWires.set(baseNode.getWireIndex());
                    } else {
                        // assert(!isVersal || baseIntentCode == IntentCode.NODE_IMUX || (bounce >= 16 && bounce < 32));
                    }
                } else {
                    if ((inode = m.group("inode")) != null) {
                        int i = Integer.valueOf(inode);
                        if (i < 32 || ((isUltraScale || isVersal) && i >= 64 && i < 96)) {
                            eastWires.set(baseNode.getWireIndex());
                        } else {
                            assert(i < 64 || (isUltraScale || isVersal && i >= 96 && i < 128));
                            westWires.set(baseNode.getWireIndex());
                        }
                    }
                }
            } else {
                assert((isUltraScale || isUltraScalePlus) && baseWireName.matches("CTRL_[EW](_B)?\\d+|INT_NODE_GLOBAL_\\d+(_INT)?_OUT[01]?"));
            }
        }

        if (lutRoutethru) {
            assert(isUltraScalePlus || isUltraScale);

            ultraScalesMuxWiresToBlockWhenLutRoutethru = new EnumMap<>(TileTypeEnum.class);
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
                               Node.getNode(clbTile, wireIndex).getWireIndex() == wireIndex);
                        wires.set(wireIndex);
                    }
                }
                if (wires.isEmpty()) {
                    continue;
                }
                ultraScalesMuxWiresToBlockWhenLutRoutethru.put(tileTypeEnum, wires);
            }
        } else {
            ultraScalesMuxWiresToBlockWhenLutRoutethru = null;
        }

        Tile[][] lagunaTiles;
        if (isUltraScalePlus) {
            lagunaTiles = device.getTilesByRootName("LAG_LAG");
        } else if (isUltraScale) {
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
                        // For LAGUNA tiles on the first SLR boundary
                        if (nextLagunaColumn[x] == Integer.MAX_VALUE) {
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
                                            .set(uphill2.getWireIndex());
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
    }

    protected Net preserve(Node node, Net net) {
        Net oldNet = preserve(node.getTile(), node.getWireIndex(), net);
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
        boolean isStaticNet = net.isStaticNet();
        for (SitePinInst pin : pins) {
            preserve(pin.getConnectedNode(), net);

            if (isStaticNet && pin.isOutPin()) {
                // When a LUT output is used as a static source, also preserve the other pin
                // ([A-H]_O <-> [A-H]MUX) so that it can't be used by any other nets
                SiteInst si = pin.getSiteInst();
                if (!Utils.isSLICE(si)) {
                    continue;
                }

                String pinName = pin.getName();
                char lutLetter = pinName.charAt(0);
                String otherPinName = null;
                String otherPinNameSuffix = isVersal ? "Q" : "MUX";
                if (pinName.endsWith(otherPinNameSuffix)) {
                    otherPinName = lutLetter + "_O";
                } else if (pinName.endsWith("_O")) {
                    otherPinName = lutLetter + otherPinNameSuffix;
                } else {
                    throw new RuntimeException("ERROR: Unsupported site pin " + pin);
                }

                Node otherNode = si.getSite().getConnectedNode(otherPinName);
                preserve(otherNode, net);
            }
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
        boolean unpreserved = unpreserve(node.getTile(), node.getWireIndex());
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
        int wireIndex = node.getWireIndex();
        Net[] nets = preservedMap.get(tile);
        return nets != null && nets[wireIndex] != null;
    }

    private static final Set<TileTypeEnum> allowedTileEnums;
    static {
        allowedTileEnums = EnumSet.noneOf(TileTypeEnum.class);
        allowedTileEnums.add(TileTypeEnum.INT);
        allowedTileEnums.addAll(Utils.getLagunaTileTypes());

        // Versal only: include tiles hosting BNODE/CNODEs
        allowedTileEnums.add(TileTypeEnum.CLE_BC_CORE);
        allowedTileEnums.add(TileTypeEnum.INTF_LOCF_TL_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_LOCF_TR_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_LOCF_BL_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_LOCF_BR_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_ROCF_TL_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_ROCF_TR_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_ROCF_BL_TILE);
        allowedTileEnums.add(TileTypeEnum.INTF_ROCF_BR_TILE);
    }

    public static boolean isExcludedTile(Node child) {
        Tile tile = child.getTile();
        TileTypeEnum tileType = tile.getTileTypeEnum();
        return !allowedTileEnums.contains(tileType);
    }

    protected boolean isExcluded(RouteNode parent, Node child) {
        if (isPreserved(child)) {
            return true;
        }

        if (isExcludedTile(child)) {
            if (!allowRoutethru(parent, child)) {
                return true;
            }
        }

        IntentCode ic = child.getIntentCode();
        if (isVersal) {
            assert(ic != IntentCode.NODE_PINFEED); // This intent code should have been projected away

            if ((!lutRoutethru && ic == IntentCode.NODE_IMUX) || ic == IntentCode.NODE_CLE_CTRL || ic == IntentCode.NODE_INTF_CTRL) {
                // Disallow these site pin projections if they aren't already in the routing graph (as a potential sink)
                RouteNode childRnode = getNode(child);
                return childRnode == null;
            }
        } else {
            assert(design.getSeries() == Series.UltraScale || design.getSeries() == Series.UltraScalePlus);

            if (child.getIntentCode() == IntentCode.NODE_PINFEED) {
                // PINFEEDs can lead to a site pin, or into a Laguna tile
                RouteNode childRnode = getNode(child);
                if (childRnode != null) {
                    assert(childRnode.getType().isExclusiveSink() ||
                            childRnode.getType() == RouteNodeType.LAGUNA_PINFEED ||
                            (lutRoutethru && childRnode.getType().isLocal()));
                } else if (!lutRoutethru) {
                    // child does not already exist in our routing graph, meaning it's not a used site pin
                    // in our design, but it could be a LAGUNA_I
                    if (lagunaI == null) {
                        // No LAGUNA_Is
                        return true;
                    }
                    BitSet bs = lagunaI.get(child.getTile());
                    if (bs == null || !bs.get(child.getWireIndex())) {
                        // Not a LAGUNA_I -- skip it
                        return true;
                    }
                }
            }
        }

        return false;
    }

    protected void addCreateRnodeTime(long time) {
        createRnodeTime += time;
    }

    protected long getCreateRnodeTime() {
        return createRnodeTime;
    }

    public Net getPreservedNet(Node node) {
        return getPreservedNet(node.getTile(), node.getWireIndex());
    }

    private Net getPreservedNet(Tile tile, int wireIndex) {
        // Assumes that tile/wireIndex describes the base wire on its node
        Net[] nets = preservedMap.get(tile);
        return nets != null ? nets[wireIndex] : null;
    }

    public RouteNode getNode(Node node) {
        Tile tile = node.getTile();
        int wireIndex = node.getWireIndex();
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
        return nodesMapSize.get();
    }

    protected RouteNode create(Node node, RouteNodeType type) {
        return new RouteNode(this, node, type);
    }

    public RouteNode getOrCreate(Node node) {
        return getOrCreate(node, null);
    }

    public RouteNode getOrCreate(Node node, RouteNodeType type) {
        Tile tile = node.getTile();
        int wireIndex = node.getWireIndex();
        RouteNode[] rnodes = nodesMap.computeIfAbsent(tile, (t) -> new RouteNode[t.getWireCount()]);
        RouteNode rnode = rnodes[wireIndex];
        if (rnode == null) {
            rnode = create(node, type);
            rnodes[wireIndex] = rnode;
            nodesMapSize.incrementAndGet();
        }
        return rnode;
    }

    public int averageChildren() {
        int sum = 0;
        for (RouteNode rnode : getRnodes()) {
            sum += rnode.numChildren();
        }
        return Math.round((float) sum / numNodes());
    }

    public boolean isAccessible(RouteNode childRnode, Connection connection) {
        // Only consider LOCAL nodes when:
        // (a) considering LUT routethrus
        if (!childRnode.getType().isLocal() || lutRoutethru) {
            return true;
        }

        // (b) needs to cross an SLR and this is a Laguna column
        Tile childTile = childRnode.getTile();
        RouteNode sinkRnode = connection.getSinkRnode();
        int childX = childTile.getTileXCoordinate();
        if (connection.isCrossSLR() &&
                childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this) &&
                nextLagunaColumn[childX] == childX) {
            return true;
        }

        // (c) on the same side as the sink
        RouteNodeType type = childRnode.getType();
        Tile sinkTile = sinkRnode.getTile();
        switch (sinkRnode.getType()) {
            case EXCLUSIVE_SINK_EAST:
                if (type == RouteNodeType.LOCAL_WEST) {
                    // West wires can never reach an east sink
                    return false;
                }
                break;
            case EXCLUSIVE_SINK_WEST:
                if (type == RouteNodeType.LOCAL_EAST) {
                    // East wires can never reach a west sink
                    return false;
                }
                break;
            case EXCLUSIVE_SINK:
                // Only both-sided wires (e.g. INT_NODE_GLOBAL_*) can reach a both-sided sink (CTRL_*)
                if (type != RouteNodeType.LOCAL) {
                    return false;
                }
                // This must be a CTRL sink; these can only be accessed from the sink tile (rather than Y +/- 1 below)
                if (isVersal) {
                    assert(sinkRnode.getIntentCode() == IntentCode.NODE_CLE_CTRL ||
                           sinkRnode.getIntentCode() == IntentCode.NODE_INTF_CTRL);
                } else {
                    assert(design.getSeries() == Series.UltraScale || design.getSeries() == Series.UltraScalePlus);
                    assert(sinkRnode.getWireName().startsWith("CTRL_"));
                }
                return childTile == sinkTile;
            default:
                throw new RuntimeException("ERROR: Unexpected sink type " + sinkRnode.getType());
        }

        // (d) in the sink tile
        if (childTile == sinkTile) {
            return true;
        }

        if (isVersal) {
            IntentCode childIntentCode = childRnode.getIntentCode();
            switch (childIntentCode) {
                case NODE_INODE:
                    // Block access to all INODEs outside the sink tile, since NODE_INODE -> NODE_IMUX -> NODE_PINFEED (or NODE_INODE -> NODE_PINBOUNCE)
                    return false;
                case NODE_CLE_CNODE:
                case NODE_INTF_CNODE: {
                    // CNODEs must only be used for CTRL sinks; must not be the case if we've reached here
                    // FIXME
                    IntentCode sinkIntentCode = sinkRnode.getIntentCode();
                    assert(sinkIntentCode != IntentCode.NODE_CLE_CTRL && sinkIntentCode != IntentCode.NODE_INTF_CTRL);
                    return false;
                }
                case NODE_CLE_BNODE:
                case NODE_INTF_BNODE: {
                    // Sinks at this point must only, only allow if BNODE reaches into the sink tile
                    IntentCode sinkIntentCode = sinkRnode.getIntentCode();
                    assert(sinkIntentCode == IntentCode.NODE_IMUX || sinkIntentCode == IntentCode.NODE_PINBOUNCE);
                    return childTile.getTileYCoordinate() == sinkTile.getTileYCoordinate() &&
                           childRnode.getEndTileXCoordinate() == sinkTile.getTileXCoordinate();
                }
                case NODE_PINBOUNCE:
                    // BOUNCEs are only accessible through INODEs, so transitively this intent code is unreachable
                    break;
                case NODE_IMUX:
                    // IMUXes that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph unless LUT routethrus are enabled
                    assert(lutRoutethru);
                    break;
                case NODE_PINFEED:
                    // Expected to be projected away
                    break;
                case NODE_CLE_CTRL:
                case NODE_INTF_CTRL:
                    // CTRL pins that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph
                    break;
            }
            throw new RuntimeException("ERROR: Unhandled IntentCode: " + childIntentCode);
        }

        // (e) when in same X as the sink tile, but Y +/- 1
        return childX == sinkTile.getTileXCoordinate() &&
               Math.abs(childTile.getTileYCoordinate() - sinkTile.getTileYCoordinate()) <= 1;
    }

    protected boolean allowRoutethru(Node head, Node tail) {
        if (!Utils.isCLB(tail.getTile().getTileTypeEnum())) {
            return false;
        }

        if (!RouteThruHelper.isRouteThruPIPAvailable(design, head, tail)) {
            return false;
        }
        assert(PIP.getArbitraryPIP(head, tail).isRouteThru());

        BitSet bs = ultraScalesMuxWiresToBlockWhenLutRoutethru.get(tail.getTile().getTileTypeEnum());
        if (bs != null && bs.get(tail.getWireIndex())) {
            // Disallow * -> [A-H]MUX routethrus since Vivado does not support the LUT
            // being fractured to support more than one routethru net
            return false;
        }
        return true;
    }
}
