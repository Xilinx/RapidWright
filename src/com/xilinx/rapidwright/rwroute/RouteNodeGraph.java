/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
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
     * A map of nodes to created rnodes. Assume that only a single
     * thread will operate on each tile (first dimension) simultaneously
     * (so no need for AtomicReferenceArray)
     */
    protected final RouteNode[][] nodesMap;
    private final AtomicInteger nodesMapSize;

    /**
     * A map of preserved nodes to their nets
     */
    private final AtomicReferenceArray<Net[]> preservedMap;
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
     * Map indicating which IMUX, INODE, INT_INT, or SDQNODE wire indices
     * within a Laguna-adjacent INT tile service a Laguna-crossing
     */
    protected final Map<Tile, BitSet[]> wireIndicesLeadingToLaguna;

    /** For one of the above IMUX/INODE wires, indicate whether it leads to an SLL travelling northbound (else southbound) **/
    public final boolean[] intYToNorthboundLaguna;

    /** Map indicating (for UltraScale/UltraScale+ only) the wire indices corresponding to the [A-H]MUX output
     * to be blocked during LUT routethrus
     */
    protected final Map<TileTypeEnum, BitSet> ultraScalesMuxWiresToBlockWhenLutRoutethru;

    /** Flag for whether LUT routethrus are to be considered */
    protected final boolean lutRoutethru;

    /** Flag for whether LUT pin swapping is to be considered */
    protected final boolean lutPinSwapping;

    /** Map indicating (for UltraScale/UltraScale+ only) the subset wire indices of a NODE_LOCAL that are
     *  what RWRoute should assign a LOCAL_* type, e.g. excluding INT_NODE_SDQ_*
     */
    protected final Map<TileTypeEnum, BitSet> ultraScalesLocalWires;

    /** Map indicating the wire indices corresponding to the east/west side of interconnect tiles */
    protected final Map<TileTypeEnum, BitSet[]> eastWestWires;

    /** Flag for whether design targets the Versal series */
    protected final boolean isVersal;

    protected final Map<TileTypeEnum, Integer> baseWireCounts;

    protected final static int MAX_OCCUPANCY = 256;
    protected final float[] presentCongestionCosts;

    protected static int getTileCount(Design design) {
        Device device = design.getDevice();
        return device.getColumns() * device.getRows();
    }

    protected final Set<Tile> allowedTiles;

    public RouteNodeGraph(Design design, RWRouteConfig config) {
        this.design = design;
        lutRoutethru = config.isLutRoutethru();
        lutPinSwapping = config.isLutPinSwapping();

        this.nodesMap = new RouteNode[getTileCount(design)][];
        nodesMapSize = new AtomicInteger();
        preservedMap = new AtomicReferenceArray<>(getTileCount(design));
        preservedMapSize = new AtomicInteger();
        asyncPreserveOutstanding = new CountUpDownLatch();
        createRnodeTime = 0;
        baseWireCounts = new ConcurrentHashMap<>();

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
        final Set<IntentCode> intTileIntentCodeCareSet;
        Pattern eastWestPattern;
        eastWestWires = new EnumMap<>(TileTypeEnum.class);
        BitSet localWires = new BitSet();
        if (isUltraScale || isUltraScalePlus) {
            intTile = device.getArbitraryTileOfType(TileTypeEnum.INT);
            // Device.getArbitraryTileOfType() typically gives you the North-Western-most
            // tile (with minimum X, maximum Y). Analyze the tile just below that.
            intTile = intTile.getTileXYNeighbor(0, -1);
            intTileIntentCodeCareSet = EnumSet.of(
                    IntentCode.NODE_PINFEED,
                    IntentCode.NODE_PINBOUNCE,
                    IntentCode.NODE_LOCAL);

            ultraScalesLocalWires = new EnumMap<>(TileTypeEnum.class);
            ultraScalesLocalWires.put(intTile.getTileTypeEnum(), localWires);

            eastWestPattern = Pattern.compile("(((BOUNCE|BYPASS|IMUX|INODE(_[12])?)_(?<eastwest>[EW]))|INT_NODE_IMUX_(?<inode>\\d+)_).*");
        } else {
            assert(isVersal);

            // Find an INT tile adjacent to a CLE_BC_CORE tile since Versal devices may contain AIEs on their northern edge
            Tile bcCoreTile = device.getArbitraryTileOfType(TileTypeEnum.CLE_BC_CORE);
            // Device.getArbitraryTileOfType() typically gives you the North-Western-most
            // tile (with minimum X, maximum Y). Analyze the tile just below that.
            intTile = bcCoreTile.getTileNeighbor(2, 0);
            assert(intTile.getTileTypeEnum() == TileTypeEnum.INT);
            intTileIntentCodeCareSet = EnumSet.of(
                    IntentCode.NODE_IMUX,
                    IntentCode.NODE_PINBOUNCE,
                    IntentCode.NODE_INODE,
                    IntentCode.NODE_CLE_BNODE,
                    IntentCode.NODE_CLE_CNODE);

            ultraScalesLocalWires = null;

            eastWestPattern = Pattern.compile("(((BOUNCE|IMUX_B|[BC]NODE_OUTS)_(?<eastwest>[EW]))|INT_NODE_IMUX_ATOM_(?<inode>\\d+)_).*");
        }

        for (int wireIndex = 0; wireIndex < intTile.getWireCount(); wireIndex++) {
            Node baseNode = Node.getNode(intTile, wireIndex);
            if (baseNode == null) {
                continue;
            }

            IntentCode baseIntentCode = baseNode.getIntentCode();
            if (!intTileIntentCodeCareSet.contains(baseIntentCode)) {
                continue;
            }

            String baseWireName = baseNode.getWireName();
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
                            continue;
                        }
                    }
                } else {
                    assert(baseIntentCode == IntentCode.NODE_PINFEED || baseIntentCode == IntentCode.NODE_PINBOUNCE);
                }
                localWires.set(baseNode.getWireIndex());
            } else {
                assert(isVersal);
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
                    // [BC]NODEs connect to INODEs opposite to their wire name
                    if (baseIntentCode == IntentCode.NODE_CLE_BNODE || baseIntentCode == IntentCode.NODE_CLE_CNODE) {
                        ew = ew.equals("E") ? "W" : "E";
                    }
                    if (ew.equals("E")) {
                        eastWires.set(baseNode.getWireIndex());
                    } else {
                        assert(ew.equals("W"));
                        westWires.set(baseNode.getWireIndex());
                    }
                } else {
                    if ((inode = m.group("inode")) != null) {
                        int i = Integer.parseInt(inode);
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

        if (isVersal) {
            // With NODE_CLE_[BC]NODEs being handled as part of the INT tile above, compute east/west wires
            // in INTF_* tiles here
            BiConsumer<List<TileTypeEnum>, Boolean> lambda = (types, east) -> {
                for (TileTypeEnum tte : types) {
                    Tile intfTile = device.getArbitraryTileOfType(tte);
                    BitSet eastWestWires = this.eastWestWires.computeIfAbsent(tte,
                            k -> new BitSet[]{new BitSet(), new BitSet()})[east ? 0 : 1];
                    for (int wireIndex = 0; wireIndex < intfTile.getWireCount(); wireIndex++) {
                        IntentCode baseIntentCode = intfTile.getWireIntentCode(wireIndex);
                        if (baseIntentCode != IntentCode.NODE_INTF_BNODE && baseIntentCode != IntentCode.NODE_INTF_CNODE) {
                            continue;
                        }
                        assert(Node.getNode(intfTile, wireIndex).getTile() == intfTile);

                        eastWestWires.set(wireIndex);
                    }
                }
            };

            lambda.accept(Arrays.asList(
                    TileTypeEnum.INTF_LOCF_TR_TILE,
                    TileTypeEnum.INTF_LOCF_BR_TILE,
                    TileTypeEnum.INTF_ROCF_TR_TILE,
                    TileTypeEnum.INTF_ROCF_BR_TILE), true);
            lambda.accept(Arrays.asList(
                    TileTypeEnum.INTF_LOCF_TL_TILE,
                    TileTypeEnum.INTF_LOCF_BL_TILE,
                    TileTypeEnum.INTF_ROCF_TL_TILE,
                    TileTypeEnum.INTF_ROCF_BL_TILE), false);
        }

        if (lutRoutethru) {
            assert(isUltraScalePlus || isUltraScale);

            ultraScalesMuxWiresToBlockWhenLutRoutethru = new EnumMap<>(TileTypeEnum.class);
            for (TileTypeEnum tileTypeEnum : Utils.getCLBTileTypes()) {
                Tile clbTile = device.getArbitraryTileOfType(tileTypeEnum);
                if (clbTile == null) {
                    continue;
                }
                localWires = new BitSet();
                for (int wireIndex = 0; wireIndex < clbTile.getWireCount(); wireIndex++) {
                    String wireName = clbTile.getWireName(wireIndex);
                    if (wireName.endsWith("MUX")) {
                        assert(Node.getNode(clbTile, wireIndex).getTile() == clbTile &&
                               Node.getNode(clbTile, wireIndex).getWireIndex() == wireIndex);
                        localWires.set(wireIndex);
                    }
                }
                if (localWires.isEmpty()) {
                    continue;
                }
                ultraScalesMuxWiresToBlockWhenLutRoutethru.put(tileTypeEnum, localWires);
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
            wireIndicesLeadingToLaguna = new IdentityHashMap<>();
            intYToNorthboundLaguna = new boolean[device.getRows()];
            final int clockRegionHeight = 60;
            final int slrHeight = device.getNumOfClockRegionRows() * clockRegionHeight / device.getSLRs().length;
            Arrays.fill(nextLagunaColumn, Integer.MAX_VALUE);
            Arrays.fill(prevLagunaColumn, Integer.MIN_VALUE);
            for (int y = 0; y < lagunaTiles.length; y++) {
                Tile[] lagunaTilesAtY = lagunaTiles[y];
                final int lagunaTileYModSlrHeight = y % slrHeight;
                boolean northbound = (lagunaTileYModSlrHeight >= (slrHeight - clockRegionHeight));
                for (int x = 0; x < lagunaTilesAtY.length; x++) {
                    Tile tile = lagunaTilesAtY[x];
                    if (tile != null) {
                        assert(y == tile.getTileYCoordinate());
                        assert(northbound || lagunaTileYModSlrHeight < clockRegionHeight);
                        intYToNorthboundLaguna[y] = northbound;

                        // For LAGUNA tiles on the first SLR boundary
                        if (nextLagunaColumn[x] == Integer.MAX_VALUE) {
                            assert(x == tile.getTileXCoordinate());
                            // Looks like (on US+) LAGUNA tiles are always on the left side of an INT tile,
                            // with tile X coordinate one smaller
                            final int intTileXCoordinate = (isUltraScalePlus) ? x + 1 : x;

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

                        Pattern inodePattern = Pattern.compile(isUltraScalePlus ? "INT_NODE_IMUX_\\d+_INT_OUT[01]|INODE_[EW]_\\d+_FT[01]"
                                                                                : "INT_NODE_IMUX_\\d+_INT_OUT|INODE_[12]_[EW]_\\d+_FT[NS]");
                        Pattern intIntPattern = Pattern.compile(isUltraScalePlus ? "INT_INT_SDQ_\\d+_INT_OUT[01]|WW1_E_7_FT0"
                                                                                 : "INT_INT_SINGLE_\\d+_INT_OUT|EE1_W_0_FTS");
                        Pattern singlePattern = Pattern.compile("(NN|EE|SS|WW)1_[EW]_BEG[0-7]");
                        Pattern sdqNodeFtPattern = Pattern.compile(isUltraScalePlus ? "SDQNODE_[EW]_0_FT1"
                                                                                    : "SDND[NS]W_E_0_FTS");
                        Pattern sdqNodePattern = Pattern.compile(isUltraScalePlus ? "INT_NODE_SDQ_\\d+_INT_OUT[01]|SDQNODE_(W_91_FT1|E_93_FT0)"
                                                                                  : "INT_NODE_SINGLE_DOUBLE_\\d+_INT_OUT|SDND[NS]W_E_15_FTN");

                        // Examine all wires in each Laguna tile. Record those IMUX and INODE uphill of a Super Long Line
                        // that originates in an INT tile
                        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
                            if (!tile.getWireName(wireIndex).startsWith("UBUMP")) {
                                continue;
                            }
                            Node sllNode = Node.getNode(tile, wireIndex);
                            for (Node txOut : sllNode.getAllUphillNodes()) {
                                List<Node> uphillTxout = txOut.getAllUphillNodes();
                                if (uphillTxout.isEmpty()) {
                                    assert((isUltraScalePlus && txOut.isTiedToVcc()) || (isUltraScale && txOut.getWireName().startsWith("VCC_WIRE")));
                                    continue;
                                }
                                assert(uphillTxout.size() == 2);
                                assert(uphillTxout.get(1).getTile().getTileTypeEnum() == sllNode.getTile().getTileTypeEnum());
                                Node imux = uphillTxout.get(0);
                                assert(imux.getIntentCode() == IntentCode.NODE_PINFEED);
                                Tile imuxTile = imux.getTile();
                                assert(Utils.isInterConnect(imuxTile.getTileTypeEnum()));

                                BitSet[] bs = wireIndicesLeadingToLaguna.computeIfAbsent(imuxTile, k -> new BitSet[]{new BitSet(), new BitSet()});
                                bs[0].set(imux.getWireIndex());
                                for (Node inode : imux.getAllUphillNodes()) {
                                    if (inode.isTiedToVcc()) {
                                        continue;
                                    }
                                    assert(inode.getIntentCode() == IntentCode.NODE_LOCAL);
                                    bs[0].set(inode.getWireIndex());

                                    if (inode.getTile() != imux.getTile()) {
                                        continue;
                                    }
                                    assert(inodePattern.matcher(inode.getWireName()).matches());

                                    for (Node intInt : inode.getAllUphillNodes()) {
                                        if (intInt.getTile() != inode.getTile()) {
                                            continue;
                                        }
                                        if (intInt.getIntentCode() != IntentCode.NODE_SINGLE) {
                                            continue;
                                        }
                                        if (!intIntPattern.matcher(intInt.getWireName()).matches()) {
                                            assert(singlePattern.matcher(intInt.getWireName()).matches());
                                            continue;
                                        }
                                        bs[1].set(intInt.getWireIndex());

                                        for (Node sdq : intInt.getAllUphillNodes()) {
                                            if (isUltraScale && sdq.isTiedToVcc()) {
                                                continue;
                                            }
                                            assert(sdq.getIntentCode() == IntentCode.NODE_LOCAL);

                                            if (sdq.getTile() != intInt.getTile()) {
                                                assert(sdqNodeFtPattern.matcher(sdq.getWireName()).matches());
                                                continue;
                                            }
                                            assert(sdqNodePattern.matcher(sdq.getWireName()).matches());
                                            bs[1].set(sdq.getWireIndex());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            nextLagunaColumn = null;
            prevLagunaColumn = null;
            wireIndicesLeadingToLaguna = null;
            intYToNorthboundLaguna = null;
        }

        presentCongestionCosts = new float[MAX_OCCUPANCY];

        String pblockString = config.getPBlock();
        if (pblockString != null) {
            PBlock pblock = new PBlock(design.getDevice(), pblockString);
            allowedTiles = Collections.newSetFromMap(new IdentityHashMap<>());
            allowedTiles.addAll(pblock.getAllTiles());
        } else {
            allowedTiles = null;
        }
    }

    public void initialize() {
    }

    /*
     * Return the maximum base wire index across all Nodes in this tile
     */
    protected int getBaseWireCount(Tile tile, int startWireIndex) {
        return baseWireCounts.computeIfAbsent(tile.getTileTypeEnum(), (e) -> {
            // Check all wires in tile to find the index of the last base wire
            int lastBaseWire = startWireIndex;
            for (int i = lastBaseWire + 1; i < tile.getWireCount(); i++) {
                Node node = Node.getNode(tile, i);
                if (node != null && node.getTile() == tile && node.getWireIndex() == i) {
                    lastBaseWire = i;
                }
            }
            return lastBaseWire + 1;
        });
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
        int tileAddress = tile.getUniqueAddress();
        Net[] nets = preservedMap.get(tileAddress);
        if (nets == null) {
            int baseWireCount = getBaseWireCount(tile, wireIndex);
            nets = new Net[baseWireCount];
            if (!preservedMap.compareAndSet(tileAddress, null, nets)) {
                // Another thread must have beat us to a compareAndSet, use that result
                nets = preservedMap.get(tileAddress);
            }
        }
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
                String otherPinName;
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
        Net[] nets = preservedMap.get(tile.getUniqueAddress());
        if (nets == null || nets[wireIndex] == null)
            return false;
        nets[wireIndex] = null;
        return true;
    }

    public boolean isPreserved(Node node) {
        Tile tile = node.getTile();
        int wireIndex = node.getWireIndex();
        Net[] nets = preservedMap.get(tile.getUniqueAddress());
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

    public boolean isAllowedTile(Node child) {
        return allowedTiles == null || allowedTiles.contains(child.getTile());
    }

    protected boolean isExcluded(RouteNode parent, Node child) {
        if (isPreserved(child)) {
            return true;
        }

        if (isExcludedTile(child)) {
            if (!allowRoutethru(parent, child)) {
                return true;
            }
        } else if (!isAllowedTile(child)) {
            return true;
        }

        RouteNode childRnode = getNode(child);
        IntentCode ic = child.getIntentCode();
        if (isVersal) {
            assert(ic != IntentCode.NODE_PINFEED); // This intent code should have been projected away

            if ((!lutRoutethru && ic == IntentCode.NODE_IMUX) || ic == IntentCode.NODE_CLE_CTRL || ic == IntentCode.NODE_INTF_CTRL) {
                // Disallow these site pin projections if they aren't already in the routing graph (as a potential sink)
                return childRnode == null;
            }
        } else {
            assert(design.getSeries() == Series.UltraScale || design.getSeries() == Series.UltraScalePlus);

            if (ic == IntentCode.NODE_PINFEED) {
                // PINFEEDs can lead to a site pin, or into a Laguna tile
                if (childRnode != null) {
                    assert(childRnode.getType().isAnyExclusiveSink() ||
                           childRnode.getType().isLocalLeadingToLaguna() ||
                           ((lutRoutethru || lutPinSwapping) && childRnode.getType().isAnyLocal()));
                } else if (!lutRoutethru) {
                    // child does not already exist in our routing graph, meaning it's not a used site pin
                    // in our design, but it could be a IMUX that leads to a Laguna
                    if (wireIndicesLeadingToLaguna == null) {
                        // No Laguna on this device
                        return true;
                    }

                    BitSet[] bs2 = wireIndicesLeadingToLaguna.get(child.getTile());
                    if (bs2 == null || !bs2[0].get(child.getWireIndex())) {
                        // Doesn't lead to Laguna -- skip it
                        return true;
                    }
                }
            }
        }

        if (childRnode != null && childRnode.isArcLocked() && childRnode.getPrev() != parent) {
            // Downhill is a locked node that doesn't point back to this node, skip
            return true;
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
        Net[] nets = preservedMap.get(tile.getUniqueAddress());
        return nets != null ? nets[wireIndex] : null;
    }

    public RouteNode getNode(Node node) {
        Tile tile = node.getTile();
        int wireIndex = node.getWireIndex();
        return getNode(tile, wireIndex);
    }

    private RouteNode getNode(Tile tile, int wireIndex) {
        // Assumes that tile/wireIndex describes the base wire on its node
        RouteNode[] rnodes = nodesMap[tile.getUniqueAddress()];
        return rnodes != null ? rnodes[wireIndex] : null;
    }

    public Iterable<RouteNode> getRnodes() {
        return new Iterable<RouteNode>() {
            int tileAddress = -1; // Start at -1 so that pre-increment advances
            int wireIndex;
            RouteNode[] curr;
            int count = 0;

            private boolean findNextWireInNextTile() {
                while(++tileAddress < nodesMap.length) {
                    curr = nodesMap[tileAddress];
                    if (curr == null) {
                        continue;
                    }
                    wireIndex = -1; // Start at -1 so that pre-increment advances
                    if (findNextWireInSameTile()) {
                        return true;
                    }
                }
                assert(curr == null);
                return false;
            }

            private boolean findNextWireInSameTile() {
                assert(curr != null);
                assert(wireIndex < curr.length);
                while(++wireIndex < curr.length) {
                    if (curr[wireIndex] != null) {
                        return true;
                    }
                }
                curr = null;
                return false;
            }

            @Override
            public Iterator<RouteNode> iterator() {
                return new Iterator<RouteNode>() {
                    @Override
                    public boolean hasNext() {
                        if (curr != null && findNextWireInSameTile()) {
                            count++;
                            return true;
                        }
                        assert(curr == null);
                        if (findNextWireInNextTile()) {
                            count++;
                            return true;
                        }
                        assert(count == nodesMapSize.get());
                        return false;
                    }

                    @Override
                    public RouteNode next() {
                        assert(curr != null);
                        RouteNode routeNode = curr[wireIndex];
                        assert(routeNode != null);
                        return routeNode;
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
        int tileAddress = tile.getUniqueAddress();
        RouteNode[] rnodes = nodesMap[tileAddress];
        if (rnodes == null) {
            int baseWireCount = getBaseWireCount(tile, wireIndex);
            rnodes = new RouteNode[baseWireCount];
            nodesMap[tileAddress] = rnodes;
        }
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
        assert(!childRnode.isTarget());

        RouteNodeType type = childRnode.getType();
        if (type == RouteNodeType.NON_LOCAL) {
            RouteNode parentRnode = childRnode.getPrev();
            if (parentRnode.getType() == RouteNodeType.SUPER_LONG_LINE && parentRnode.getPrev().getTile() == childRnode.getTile()) {
                // With an SLL being bidrectional, do not lookahead back the way we came from
                return false;
            }
            return true;
        }

        // Only consider LOCAL nodes when:
        // (a) considering LUT routethrus
        if (!type.isAnyLocal() || lutRoutethru) {
            return true;
        }

        // (b) needs to cross an SLR and this is an INT tile that services a Laguna
        Tile childTile = childRnode.getTile();
        RouteNode sinkRnode = connection.getSinkRnode();
        int childX = childTile.getTileXCoordinate();
        if (connection.isCrossSLR() &&
                childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this) &&
                wireIndicesLeadingToLaguna.get(childTile) != null /*&&
                childRnode.getType().leadsToLaguna()*/) {
            assert((!childRnode.getType().leadsToNorthboundLaguna() || connection.isCrossSLRnorth()) &&
                   (!childRnode.getType().leadsToSouthboundLaguna() || connection.isCrossSLRsouth()));
            assert(nextLagunaColumn[childX] == childX);
            return true;
        }

        // (c) on the same side as the sink
        Tile sinkTile = sinkRnode.getTile();
        switch (sinkRnode.getType()) {
            case LOCAL_EAST:
                assert(connection.hasAltSinks());
                // Fall-through
            case EXCLUSIVE_SINK_EAST:
                if (type == RouteNodeType.LOCAL_WEST || type == RouteNodeType.LOCAL_RESERVED) {
                    // West wires can never reach an east sink
                    return false;
                }
                break;
            case LOCAL_WEST:
                assert(connection.hasAltSinks());
                // Fall-through
            case EXCLUSIVE_SINK_WEST:
                if (type == RouteNodeType.LOCAL_EAST || type == RouteNodeType.LOCAL_RESERVED) {
                    // East wires can never reach a west sink
                    return false;
                }
                break;
            case EXCLUSIVE_SINK_BOTH:
                // This must be a CTRL sink that can be accessed from both east/west sides

                if (isVersal) {
                    assert(sinkRnode.getIntentCode() == IntentCode.NODE_CLE_CTRL || sinkRnode.getIntentCode() == IntentCode.NODE_INTF_CTRL);

                    if (childTile == sinkTile) {
                        // CTRL sinks can be only accessed directly from LOCAL_RESERVED nodes in the sink CLE_BC_CORE/INTF_* tile ...
                        if (type != RouteNodeType.LOCAL_RESERVED) {
                            return false;
                        }
                    } else {
                        // ... or via LOCAL nodes in the two INT tiles either side
                        if (childTile.getTileYCoordinate() != sinkTile.getTileYCoordinate() ||
                                Math.abs(childTile.getTileXCoordinate() - sinkTile.getTileXCoordinate()) > 1) {
                            return false;
                        }
                        if (childTile.getTileTypeEnum() != TileTypeEnum.INT) {
                            // e.g. CLE_BC_CORE_X50Y4 and CLE_BC_CORE_1_X50Y4 on xcvc1502
                            return false;
                        }
                        // Allow use of INODE + PINBOUNCEs in the two INT tiles on either side of sink
                        assert(childRnode.getIntentCode() == IntentCode.NODE_INODE || childRnode.getIntentCode() == IntentCode.NODE_PINBOUNCE);
                        return true;
                    }
                } else {
                    assert(design.getSeries() == Series.UltraScale || design.getSeries() == Series.UltraScalePlus);
                    assert(sinkRnode.getWireName().startsWith("CTRL_"));

                    // CTRL sinks can only be accessed from LOCAL nodes in the sink tile (rather than Y +/- 1 below)
                    if (childTile != sinkTile) {
                        return false;
                    }

                    // Only both-sided wires (e.g. INT_NODE_GLOBAL_*) can reach a both-sided sink (CTRL_*)
                    if (type != RouteNodeType.LOCAL_BOTH) {
                        return false;
                    }
                }
                assert(childTile == sinkTile);
                break;
            case EXCLUSIVE_SINK_NON_LOCAL:
                if (type.isAnyLocal()) {
                    // Local wires can never reach non-local when LUT routethrus are disabled
                    return false;
                }
                break;
            default:
                throw new RuntimeException("ERROR: Unexpected sink type " + sinkRnode.getType());
        }

        // (d) in the sink tile
        if (childTile == sinkTile) {
            return true;
        }

        if (isVersal) {
            assert(sinkRnode.getType() != RouteNodeType.EXCLUSIVE_SINK_BOTH);
            assert(sinkRnode.getIntentCode() == IntentCode.NODE_IMUX || sinkRnode.getIntentCode() == IntentCode.NODE_PINBOUNCE);

            IntentCode childIntentCode = childRnode.getIntentCode();
            switch (childIntentCode) {
                case NODE_INODE:
                    // Block access to all INODEs outside the sink tile, since NODE_INODE -> NODE_IMUX -> NODE_PINFEED (or NODE_INODE -> NODE_PINBOUNCE)
                    assert(childTile != sinkTile);
                    return false;
                case NODE_CLE_BNODE:
                case NODE_INTF_BNODE:
                case NODE_CLE_CNODE:
                case NODE_INTF_CNODE:
                    // Only allow [BC]NODEs that reach into the sink tile
                    return childTile.getTileYCoordinate() == sinkTile.getTileYCoordinate() &&
                           childRnode.getEndTileXCoordinate() == sinkTile.getTileXCoordinate();
                case NODE_PINBOUNCE:
                    // BOUNCEs are only accessible through INODEs, so transitively this intent code is unreachable
                    break;
                case NODE_IMUX:
                    // IMUXes that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph unless
                    // LUT routethrus are enabled (which would have already returned true above)
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
        if (!lutRoutethru) {
            return false;
        }

        if (!Utils.isCLB(tail.getTile().getTileTypeEnum())) {
            return false;
        }

        if (tail.getIntentCode() == IntentCode.NODE_PINFEED) {
            assert(isVersal);
            assert(!lutRoutethru);
            assert(head.getIntentCode() == IntentCode.NODE_IMUX ||
                   head.getIntentCode() == IntentCode.NODE_PINBOUNCE);
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

    public void updatePresentCongestionCosts(float presentCongestionFactor) {
        for (int occupancy = 0; occupancy < presentCongestionCosts.length; occupancy++) {
            int overuse = occupancy - RouteNode.capacity;
            if (overuse < 0) {
                presentCongestionCosts[occupancy] = RouteNode.initialPresentCongestionCost;
            } else {
                presentCongestionCosts[occupancy] = RouteNode.initialPresentCongestionCost + (overuse + 1) * presentCongestionFactor;
            }
        }
    }

    public float getPresentCongestionCost(int occupancy) {
        return presentCongestionCosts[occupancy];
    }
}
