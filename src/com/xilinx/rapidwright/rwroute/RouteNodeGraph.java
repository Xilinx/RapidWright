/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
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
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.Function;
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
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
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
     * Parent of the tasks of all calls to preserveAsync() since the last call to awaitPreserve(),
     * which completes once they all have and awaitPreserve() is called (and is then reinitialized for reuse)
     */
    private final CountedCompleter<Void> asyncPreserveOutstanding;

    private long createRnodeTime;

    public final short SUPER_LONG_LINE_LENGTH_IN_TILES;

    /** Array mapping an INT tile's Y coordinate to its SLR index */
    public final int[] intYToSLRIndex;
    /** Array mapping an SLR index to the (lowest) Y coordinate of its first row of INT tiles */
    public final int[] slrFirstIntY;
    /** Array mapping an SLR index to the (highest) Y coordinate of its last row of INT tiles */
    public final int[] slrLastIntY;
    /**
     * Arrays mapping a half-tile X coordinate (see {@link RouteNode#getEndHalfTileXCoordinate()}) to that of the
     * next (at or above) or previous (at or below) INT tile half from which super long lines can cross into another
     * SLR: the west half of an INT tile with a Laguna column (UltraScale/UltraScale+), or either half beside an SLL
     * column with SLLs that cross SLRs (Versal). Integer.MAX_VALUE/Integer.MIN_VALUE if there is none; null if the
     * device has no such columns. Every SLR boundary has the same such columns (on all multi-SLR devices examined).
     */
    public final int[] nextCrossingHalfColumn;
    public final int[] prevCrossingHalfColumn;

    /**
     * Map indicating which IMUX, INODE, INT_INT, or SDQNODE wire indices
     * within a Laguna-adjacent INT tile service a Laguna-crossing
     */
    protected final Map<Tile, BitSet[]> wireIndicesLeadingToLaguna;

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

    /**
     * Map indicating the wire indices corresponding to local nodes that serve only one side (half) of
     * interconnect tiles (that side being given by {@link #eastSideWires})
     */
    protected final Map<TileTypeEnum, BitSet> sidedLocalWires;

    /**
     * Map indicating the wire indices (of the base wire of a node) corresponding to nodes that end on the east
     * half of an interconnect tile; all other nodes are considered to end on its west half. This includes
     * local nodes serving the east side, interconnect wires (e.g. singles and doubles) arriving at the east side,
     * CTRL_E* pins, and INT_NODE_GLOBAL_* nodes driving INODEs on the east side (UltraScale/UltraScale+), as
     * well as CTRL_L_B* pins in CLE_BC_CORE/SLL tiles, the CTRL pins of INTF_* tiles serving the east side, and site
     * pins (NODE_PINFEED) in CLE_W_CORE tiles (Versal).
     */
    protected final Map<TileTypeEnum, BitSet> eastSideWires;

    /** BitSet indicating (for UltraScale/UltraScale+ only) the wire indices of CTRL_* pins in INT tiles */
    protected final BitSet ultraScalesCtrlWires;

    /**
     * Map indicating the wire indices (of the base wire of a node) corresponding to non-local nodes that serve both
     * sides of the INT tile that they end at, and so are given the {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE}
     * type: UltraScale longs and those INT_NODE_QUAD_LONG_* / QLND* that drive quads on both sides, and Versal longs.
     */
    protected final Map<TileTypeEnum, BitSet> inheritPrevSideWires;

    /**
     * Map indicating (for Versal only) the wire indices of INTF_* tile CNODEs that only drive that tile's CTRL pins
     * (and thus are only of use to reach those pins)
     */
    protected final Map<TileTypeEnum, BitSet> versalIntfCtrlOnlyWires;

    /** Flag for whether design targets the Versal series */
    protected final boolean isVersal;

    /** Map of the TileTypeEnum to the highest base wire index */
    protected final Map<TileTypeEnum, Integer> highestBaseWireIndex;

    protected final static int MAX_OCCUPANCY = 256;
    protected final float[] presentCongestionCosts;

    protected static int getTileCount(Design design) {
        Device device = design.getDevice();
        return device.getColumns() * device.getRows();
    }

    protected final Set<Tile> allowedTiles;

    /** Flag to enable really comprehensive (but performance-impacting) assertions */
    protected final static boolean enableComprehensiveAssertions = false;

    public RouteNodeGraph(Design design, RWRouteConfig config) {
        this.design = design;
        lutRoutethru = config.isLutRoutethru();
        lutPinSwapping = config.isLutPinSwapping();

        this.nodesMap = new RouteNode[getTileCount(design)][];
        nodesMapSize = new AtomicInteger();
        preservedMap = new AtomicReferenceArray<>(getTileCount(design));
        preservedMapSize = new AtomicInteger();
        asyncPreserveOutstanding = new CountedCompleter<Void>() {
            @Override
            public void compute() {
                // Never run: completed by its children and awaitPreserve()
            }
        };
        createRnodeTime = 0;
        highestBaseWireIndex = new ConcurrentHashMap<>();

        Device device = design.getDevice();
        intYToSLRIndex = new int[device.getRows()];
        slrFirstIntY = new int[device.getNumOfSLRs()];
        slrLastIntY = new int[device.getNumOfSLRs()];
        Arrays.fill(slrFirstIntY, Integer.MAX_VALUE);
        Arrays.fill(slrLastIntY, Integer.MIN_VALUE);
        Tile[][] intTiles = device.getTilesByRootName("INT");
        for (int y = 0; y < intTiles.length; y++) {
            Tile[] intTilesAtY = intTiles[y];
            for (Tile tile : intTilesAtY) {
                if (tile != null) {
                    int slr = tile.getSLR().getId();
                    intYToSLRIndex[y] = slr;
                    slrFirstIntY[slr] = Math.min(slrFirstIntY[slr], y);
                    slrLastIntY[slr] = Math.max(slrLastIntY[slr], y);
                    break;
                }
            }
        }
        for (int slr = 1; slr < slrFirstIntY.length; slr++) {
            // SLR indices are expected to increase with Y
            assert(slrFirstIntY[slr] > slrFirstIntY[slr - 1]);
        }

        Series series = device.getSeries();
        boolean isUltraScale = series == Series.UltraScale;
        boolean isUltraScalePlus = series == Series.UltraScalePlus;
        isVersal = series == Series.Versal;
        final Set<IntentCode> intTileIntentCodeCareSet;
        Pattern eastWestPattern;
        // Interconnect wires (non-local) whose wire name indicates which side of the INT tile they arrive at
        final Set<IntentCode> intTileSideNamedIntentCodes;
        // e.g. EE2_E_BEG0 (US/US+) or OUT_EE2_E_BEG0 (Versal)
        final Pattern sideNamedPattern = Pattern.compile("(OUT_)?(?<dir>NN|EE|SS|WW)(?<len>\\d+)_(?<eastwest>[EW])_.*");
        // Side (true for east) of the INT tile that an interconnect wire leaves from, as given by its name (e.g.
        // SS4_E_BEG6), or null if it has none: each node driving such wires (INT_NODE_SDQ_* on UltraScale+,
        // INT_NODE_SINGLE_DOUBLE_* on UltraScale, NODE_SDQNODEs on Versal) drives those leaving from one side only
        // (in INT_X43Y306 on xcvu9p and INT_X30Y120 on xcku040, at least)
        final Function<Node, Boolean> sideFromName = (downhill) -> {
            Matcher m = sideNamedPattern.matcher(downhill.getWireName());
            return m.matches() ? m.group("eastwest").equals("E") : null;
        };
        sidedLocalWires = new EnumMap<>(TileTypeEnum.class);
        eastSideWires = new EnumMap<>(TileTypeEnum.class);
        ultraScalesCtrlWires = isVersal ? null : new BitSet();
        inheritPrevSideWires = new EnumMap<>(TileTypeEnum.class);
        versalIntfCtrlOnlyWires = isVersal ? new EnumMap<>(TileTypeEnum.class) : null;
        BitSet localWires = new BitSet();
        List<Tile> intTilesToExamine = null;
        if (isUltraScale || isUltraScalePlus) {
            Tile tile = device.getArbitraryTileOfType(TileTypeEnum.INT);
            // Device.getArbitraryTileOfType() typically gives you the North-Western-most
            // tile (with minimum X, maximum Y). Analyze the tile just below that.
            intTilesToExamine = Collections.singletonList(tile.getTileXYNeighbor(0, -1));
            intTileIntentCodeCareSet = EnumSet.of(
                    IntentCode.NODE_PINFEED,
                    IntentCode.NODE_PINBOUNCE,
                    IntentCode.NODE_LOCAL);

            ultraScalesLocalWires = new EnumMap<>(TileTypeEnum.class);
            ultraScalesLocalWires.put(tile.getTileTypeEnum(), localWires);

            eastWestPattern = Pattern.compile("(((BOUNCE|BYPASS|IMUX|INODE(_[12])?)_(?<eastwest>[EW]))|INT_NODE_IMUX_(?<inode>\\d+)_).*");

            // Longs (and US quads) have no side in their name
            intTileSideNamedIntentCodes = EnumSet.of(
                    IntentCode.NODE_SINGLE,
                    IntentCode.NODE_DOUBLE,
                    IntentCode.NODE_HQUAD,
                    IntentCode.NODE_VQUAD);

            SUPER_LONG_LINE_LENGTH_IN_TILES = 60;
        } else {
            assert(isVersal);

            // Find an INT tile adjacent to a CLE_BC_CORE tile since Versal devices may contain AIEs on their northern edge
            Tile bcCoreTile = device.getArbitraryTileOfType(TileTypeEnum.CLE_BC_CORE);
            Tile tile = bcCoreTile.getTileNeighbor(2, 0);
            assert(tile.getTileTypeEnum() == TileTypeEnum.INT);

            intTilesToExamine = new ArrayList<>(2);
            intTilesToExamine.add(tile);

            Tile sllTile = device.getArbitraryTileOfType(TileTypeEnum.SLL);
            if (sllTile != null) {
                tile = sllTile.getTileNeighbor(2, 0);
                assert(tile.getTileTypeEnum() == TileTypeEnum.INT);
                intTilesToExamine.add(tile);
            }

            intTileIntentCodeCareSet = EnumSet.of(
                    IntentCode.NODE_IMUX,
                    IntentCode.NODE_PINBOUNCE,
                    IntentCode.NODE_INODE,
                    IntentCode.NODE_CLE_BNODE,
                    IntentCode.NODE_CLE_CNODE);

            ultraScalesLocalWires = null;

            eastWestPattern = Pattern.compile("(((BOUNCE|IMUX_B|[BC]NODE_OUTS)_(?<eastwest>[EW]))|INT_NODE_IMUX_ATOM_(?<inode>\\d+)_).*");

            // Longs have no side in their name
            intTileSideNamedIntentCodes = EnumSet.of(
                    IntentCode.NODE_HSINGLE,
                    IntentCode.NODE_VSINGLE,
                    IntentCode.NODE_HDOUBLE,
                    IntentCode.NODE_VDOUBLE,
                    IntentCode.NODE_HQUAD,
                    IntentCode.NODE_VQUAD);

            SUPER_LONG_LINE_LENGTH_IN_TILES = 75;
        }

        // UltraScale+ longs and UltraScale quads, e.g. EE12_BEG0 (UltraScale+) or NN5_BEG0 (UltraScale)
        final Pattern indexNamedPattern = Pattern.compile("(?<dir>NN|EE|SS|WW)(?<len>\\d+)_BEG(?<index>\\d+)");
        // INT_INT_SDQ_* (UltraScale+), INT_INT_SINGLE_* (UltraScale), INT_SDQ_RED_ATOM_* (Versal)
        List<Node> intraTileSingles = new ArrayList<>();
        for (Tile intTile : intTilesToExamine) {
            for (int wireIndex = 0; wireIndex < intTile.getWireCount(); wireIndex++) {
                Node baseNode = Node.getNode(intTile, wireIndex);
                if (baseNode == null) {
                    continue;
                }

                IntentCode baseIntentCode = baseNode.getIntentCode();
                if ((isUltraScale && (baseIntentCode == IntentCode.NODE_HLONG || baseIntentCode == IntentCode.NODE_VLONG)) ||
                        (isVersal && (baseIntentCode == IntentCode.NODE_HLONG6 || baseIntentCode == IntentCode.NODE_HLONG10 ||
                                      baseIntentCode == IntentCode.NODE_VLONG7 || baseIntentCode == IntentCode.NODE_VLONG12))) {
                    // UltraScale longs only drive INT_NODE_QUAD_LONG_* / QLND*, which mostly drive quads on both sides
                    // (on xcku040); Versal longs drive INT_NODE_SDQ_ATOM_* on both sides (on xcvc1902)
                    inheritPrevSideWires.computeIfAbsent(baseNode.getTile().getTileTypeEnum(), k -> new BitSet())
                            .set(baseNode.getWireIndex());
                }
                if ((isUltraScalePlus && (baseIntentCode == IntentCode.NODE_HLONG || baseIntentCode == IntentCode.NODE_VLONG)) ||
                        (isUltraScale && (baseIntentCode == IntentCode.NODE_HQUAD || baseIntentCode == IntentCode.NODE_VQUAD))) {
                    // These have no side in their name, but (other than longs/quads, which serve both sides) only drive
                    // INT_NODE_SDQ_* (UltraScale+) / INT_NODE_SINGLE_DOUBLE_* (UltraScale) nodes on one side, as given by
                    // their direction and index (on xcvu3p and xcku040, away from column breaks):
                    //   UltraScale+ longs: EE12_BEG<even> and {WW,NN,SS}12_BEG<odd> are east
                    //   UltraScale quads:  EE4_BEG<even>, WW4_BEG<odd>, NN5_BEG* and SS4_BEG* are east
                    Matcher m = indexNamedPattern.matcher(baseNode.getWireName());
                    if (m.matches() && isEastSideByIndexName(isUltraScalePlus, m)) {
                        setEastSide(baseNode);
                    }
                }
                if (isVersal && baseIntentCode == IntentCode.NODE_SDQNODE) {
                    // INT_NODE_SDQ_ATOM_* and OUT_[ENSW]NODE_[EW]_* (which drive wires in the INT tile above/below):
                    // not LOCAL nodes, but sided (in the sense of their half-tile X coordinate)
                    setSideFromDownhill(baseNode, sideFromName);
                    continue;
                }
                if (intTileSideNamedIntentCodes.contains(baseIntentCode)) {
                    Matcher m = sideNamedPattern.matcher(baseNode.getWireName());
                    // Wires without a side in their name are not sided here: UltraScale quads were sided by their
                    // index above, and intra-tile singles are sided below
                    if (baseNode.getWireName().startsWith("INT_INT_") || baseNode.getWireName().startsWith("INT_SDQ_RED_ATOM_")) {
                        intraTileSingles.add(baseNode);
                    }
                    if (m.matches()) {
                        boolean east = m.group("eastwest").equals("E");
                        String dir = m.group("dir");
                        if (m.group("len").equals("1") && (dir.equals("EE") || dir.equals("WW"))) {
                            // Horizontal singles always arrive on the opposite side of the INT tile from which
                            // they leave (e.g. EE1_E_BEG0 leaves the east side of X and arrives at the west side of X+1)
                            east = !east;
                        }
                        if (east) {
                            setEastSide(baseNode);
                        }
                    }
                    continue;
                }
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
                                // Not a LOCAL node, but sided (in the sense of its half-tile X coordinate)
                                setSideFromDownhill(baseNode, sideFromName);
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
                                if (baseWireName.startsWith("INT_NODE_SINGLE_DOUBLE_") || baseWireName.startsWith("SDND")) {
                                    // Not a LOCAL node, but sided (in the sense of its half-tile X coordinate)
                                    setSideFromDownhill(baseNode, sideFromName);
                                } else {
                                    // INT_NODE_QUAD_LONG_* / QLND* each drive two quads and a long (with no side): sided
                                    // only when both quads are on the same side (32 of 128 per INT tile on xcku040),
                                    // otherwise left on the west side
                                    Boolean east = null;
                                    boolean bothSides = false;
                                    for (Node downhill : baseNode.getAllDownhillNodes()) {
                                        IntentCode ic = downhill.getIntentCode();
                                        if (ic != IntentCode.NODE_HQUAD && ic != IntentCode.NODE_VQUAD) {
                                            continue;
                                        }
                                        Matcher m = indexNamedPattern.matcher(downhill.getWireName());
                                        if (!m.matches()) {
                                            continue;
                                        }
                                        boolean downhillEast = isEastSideByIndexName(false, m);
                                        bothSides |= (east != null && east != downhillEast);
                                        east = downhillEast;
                                    }
                                    if (bothSides) {
                                        inheritPrevSideWires.computeIfAbsent(baseTile.getTileTypeEnum(), k -> new BitSet())
                                                .set(baseNode.getWireIndex());
                                    } else if (east != null && east) {
                                        setEastSide(baseNode);
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
                    TileTypeEnum baseTileType = baseNode.getTile().getTileTypeEnum();
                    String ew = m.group("eastwest");
                    String inode;
                    boolean east;
                    if (ew != null) {
                        // [BC]NODEs connect to INODEs opposite to their wire name
                        if (baseIntentCode == IntentCode.NODE_CLE_BNODE || baseIntentCode == IntentCode.NODE_CLE_CNODE) {
                            ew = ew.equals("E") ? "W" : "E";
                        }
                        assert(ew.equals("E") || ew.equals("W"));
                        east = ew.equals("E");
                    } else {
                        inode = m.group("inode");
                        int i = Integer.parseInt(inode);
                        if (i < 32 || ((isUltraScale || isVersal) && i >= 64 && i < 96)) {
                            east = true;
                        } else {
                            assert(i < 64 || (isUltraScale || isVersal && i >= 96 && i < 128));
                            east = false;
                        }
                    }
                    sidedLocalWires.computeIfAbsent(baseTileType, k -> new BitSet()).set(baseNode.getWireIndex());
                    if (east) {
                        setEastSide(baseNode);
                    }
                } else {
                    assert((isUltraScale || isUltraScalePlus) && baseWireName.matches("CTRL_[EW](_B)?\\d+|INT_NODE_GLOBAL_\\d+(_INT)?_OUT[01]?"));
                    // CTRL_* pins are on the side in their name (even though they are reachable from
                    // INT_NODE_GLOBAL_* which can serve both sides)
                    if (baseWireName.startsWith("CTRL_")) {
                        assert(baseNode.getTile().getTileTypeEnum() == TileTypeEnum.INT);
                        ultraScalesCtrlWires.set(baseNode.getWireIndex());
                    }
                    if (baseWireName.startsWith("CTRL_E")) {
                        setEastSide(baseNode);
                    }
                }
            }
        }

        // Intra-tile singles swap sides: each is driven from one side of its INT tile and drives (other than GNODEs and
        // nodes with no side) the other side only. Place each on the side that it drives, as given by the sided local
        // nodes (INODEs) that it drives.
        for (Node baseNode : intraTileSingles) {
            setSideFromDownhill(baseNode, (downhill) -> {
                TileTypeEnum downhillTileType = downhill.getTile().getTileTypeEnum();
                return isSidedLocalWire(downhillTileType, downhill.getWireIndex())
                        ? isEastSideWire(downhillTileType, downhill.getWireIndex()) : null;
            });
        }

        if (isUltraScale || isUltraScalePlus) {
            // INT_NODE_GLOBAL_* (GNODEs) serve CTRL_* pins on both sides, but each serves INODEs on only one side:
            // place each GNODE on that side
            for (Tile intTile : intTilesToExamine) {
                for (int wireIndex = localWires.nextSetBit(0); wireIndex >= 0; wireIndex = localWires.nextSetBit(wireIndex + 1)) {
                    Node baseNode = Node.getNode(intTile, wireIndex);
                    if (baseNode == null || baseNode.getIntentCode() != IntentCode.NODE_LOCAL ||
                            isSidedLocalWire(baseNode.getTile().getTileTypeEnum(), baseNode.getWireIndex())) {
                        continue;
                    }
                    assert(baseNode.getWireName().startsWith("INT_NODE_GLOBAL_"));
                    Boolean east = setSideFromDownhill(baseNode, (downhill) -> {
                        TileTypeEnum downhillTileType = downhill.getTile().getTileTypeEnum();
                        // Ignoring e.g. CTRL_* pins
                        return (downhill.getIntentCode() == IntentCode.NODE_LOCAL &&
                                isSidedLocalWire(downhillTileType, downhill.getWireIndex()))
                                ? isEastSideWire(downhillTileType, downhill.getWireIndex()) : null;
                    });
                    assert(east != null);
                }
            }
        }

        if (isVersal) {
            // CTRL_[LR]_B* pins in CLE_BC_CORE (and SLL) tiles are reachable from the [BC]NODEs serving the INT tiles
            // on both sides, but each feeds a CLE core tile on one side only: CTRL_L_B* feeds the CLE_W_CORE beside
            // the east side of the INT tile to the west, CTRL_R_B* the CLE_E_CORE beside the west side of the INT tile
            // to the east (with RouteNodeInfo placing both pins at their CLE core tile)
            for (TileTypeEnum tte : Arrays.asList(TileTypeEnum.CLE_BC_CORE, TileTypeEnum.SLL)) {
                Tile tile = device.getArbitraryTileOfType(tte);
                if (tile == null) continue;
                for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
                    if (tile.getWireIntentCode(wireIndex) != IntentCode.NODE_CLE_CTRL) {
                        continue;
                    }
                    String wireName = tile.getWireName(wireIndex);
                    assert(wireName.matches("CTRL_[LR]_B\\d+"));
                    if (wireName.startsWith("CTRL_L")) {
                        eastSideWires.computeIfAbsent(tte, k -> new BitSet()).set(wireIndex);
                    }
                }
            }

            // Site pins (NODE_PINFEED; in the routing graph only as the CLE_SLICE[LM]_TOP_*_LAG_*_PIN downhill of an SLL
            // output, since all others are projected onto their INT tile) are on the side of the INT tile that their
            // CLE core is beside: CLE_W_CORE_X<n> is beside the east side of INT_X<n>, and CLE_E_CORE_X<n> beside the
            // west side (where they would otherwise be placed)
            Tile cleWCoreTile = device.getArbitraryTileOfType(TileTypeEnum.CLE_W_CORE);
            if (cleWCoreTile != null) {
                BitSet eastWires = eastSideWires.computeIfAbsent(TileTypeEnum.CLE_W_CORE, k -> new BitSet());
                for (int wireIndex = 0; wireIndex < cleWCoreTile.getWireCount(); wireIndex++) {
                    if (cleWCoreTile.getWireIntentCode(wireIndex) == IntentCode.NODE_PINFEED) {
                        eastWires.set(wireIndex);
                    }
                }
            }

            // With NODE_CLE_[BC]NODEs being handled as part of the INT tile above, compute east/west wires
            // in INTF_* tiles here; INTF_* tiles serve only one side of their INT tile, including their CTRL pins
            // (which, like those in CLE_BC_CORE tiles, are not considered to be sided local wires)
            BiConsumer<List<TileTypeEnum>, Boolean> lambda = (types, east) -> {
                for (TileTypeEnum tte : types) {
                    Tile intfTile = device.getArbitraryTileOfType(tte);
                    if (intfTile == null) continue;
                    BitSet sidedWires = sidedLocalWires.computeIfAbsent(tte, k -> new BitSet());
                    BitSet eastWires = eastSideWires.computeIfAbsent(tte, k -> new BitSet());
                    for (int wireIndex = 0; wireIndex < intfTile.getWireCount(); wireIndex++) {
                        IntentCode baseIntentCode = intfTile.getWireIntentCode(wireIndex);
                        if (baseIntentCode == IntentCode.NODE_INTF_CTRL) {
                            if (east) {
                                eastWires.set(wireIndex);
                            }
                            continue;
                        }
                        if (baseIntentCode != IntentCode.NODE_INTF_BNODE && baseIntentCode != IntentCode.NODE_INTF_CNODE) {
                            continue;
                        }
                        Node baseNode = Node.getNode(intfTile, wireIndex);
                        assert(baseNode.getTile() == intfTile);

                        sidedWires.set(wireIndex);
                        if (east) {
                            eastWires.set(wireIndex);
                        }

                        if (baseIntentCode == IntentCode.NODE_INTF_CNODE) {
                            // Unlike those in CLE_BC_CORE tiles, CNODEs in INTF_* tiles (on xcv80, at least) do not
                            // drive any INODEs, only CTRL pins
                            boolean ctrlOnly = true;
                            for (Node downhill : baseNode.getAllDownhillNodes()) {
                                if (downhill.getIntentCode() != IntentCode.NODE_INTF_CTRL) {
                                    ctrlOnly = false;
                                    break;
                                }
                            }
                            if (ctrlOnly) {
                                versalIntfCtrlOnlyWires.computeIfAbsent(tte, k -> new BitSet()).set(wireIndex);
                            }
                        }
                    }
                }
            };

            lambda.accept(Arrays.asList(
                    TileTypeEnum.INTF_LOCF_TR_TILE,
                    TileTypeEnum.INTF_LOCF_BR_TILE,
                    TileTypeEnum.INTF_ROCF_TR_TILE,
                    TileTypeEnum.INTF_ROCF_BR_TILE,
                    TileTypeEnum.INTF_GT_TR_TILE,
                    TileTypeEnum.INTF_GT_BR_TILE,
                    TileTypeEnum.INTF_HB_LOCF_TR_TILE,
                    TileTypeEnum.INTF_HB_LOCF_BR_TILE,
                    TileTypeEnum.INTF_HB_ROCF_TR_TILE,
                    TileTypeEnum.INTF_HB_ROCF_BR_TILE), true /* east */);
            lambda.accept(Arrays.asList(
                    TileTypeEnum.INTF_LOCF_TL_TILE,
                    TileTypeEnum.INTF_LOCF_BL_TILE,
                    TileTypeEnum.INTF_ROCF_TL_TILE,
                    TileTypeEnum.INTF_ROCF_BL_TILE,
                    TileTypeEnum.INTF_CFRM_TL_TILE,
                    TileTypeEnum.INTF_CFRM_BL_TILE,
                    TileTypeEnum.INTF_GT_TL_TILE,
                    TileTypeEnum.INTF_GT_BL_TILE,
                    TileTypeEnum.INTF_HB_LOCF_TL_TILE,
                    TileTypeEnum.INTF_HB_LOCF_BL_TILE,
                    TileTypeEnum.INTF_HB_ROCF_TL_TILE,
                    TileTypeEnum.INTF_HB_ROCF_BL_TILE,
                    TileTypeEnum.INTF_PSS_BL_TILE,
                    TileTypeEnum.INTF_PSS_TL_TILE), false /* west */);
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

        boolean[] crossingHalfColumns = null;
        if (device.getNumOfSLRs() > 1) {
            // An over-approximation since this isn't in tiles
            crossingHalfColumns = new boolean[2 * device.getColumns()];
            Tile[][] lagunaTiles;
            if (isUltraScalePlus) {
                lagunaTiles = device.getTilesByRootName("LAG_LAG");
            } else if (isUltraScale) {
                lagunaTiles = device.getTilesByRootName("LAGUNA_TILE");
            } else {
                lagunaTiles = null;
            }

            if (lagunaTiles != null) {
                wireIndicesLeadingToLaguna = new IdentityHashMap<>();
                populateLagunaLookups(device, lagunaTiles, crossingHalfColumns);
            } else {
                wireIndicesLeadingToLaguna = null;
                if (isVersal) {
                    populateSllCrossingHalfColumns(device, crossingHalfColumns);
                }
            }
        } else {
            wireIndicesLeadingToLaguna = null;
        }
        int[] next = null;
        int[] prev = null;
        if (crossingHalfColumns != null) {
            next = new int[crossingHalfColumns.length];
            prev = new int[crossingHalfColumns.length];
            int nextHalfX = Integer.MAX_VALUE;
            for (int halfX = crossingHalfColumns.length - 1; halfX >= 0; halfX--) {
                if (crossingHalfColumns[halfX]) {
                    nextHalfX = halfX;
                }
                next[halfX] = nextHalfX;
            }
            int prevHalfX = Integer.MIN_VALUE;
            for (int halfX = 0; halfX < crossingHalfColumns.length; halfX++) {
                if (crossingHalfColumns[halfX]) {
                    prevHalfX = halfX;
                }
                prev[halfX] = prevHalfX;
            }
            if (prevHalfX == Integer.MIN_VALUE) {
                // No columns found (e.g. a Versal device without SLL tiles)
                next = null;
                prev = null;
            }
        }
        nextCrossingHalfColumn = next;
        prevCrossingHalfColumn = prev;

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

    /**
     * Marks the INT tile halves beside the (Versal) SLL columns whose SLLs cross SLRs, examining only the first SLR
     * boundary: each SLL tile sits between the east half of the INT tile to its west and the west half of the next.
     * SLLs that do not leave their tile, or that stay within an SLR, do not count; nor do "horizontal" SLLs (to an SLL
     * tile in another column), which are excluded from the routing graph (see {@link #isExcluded(RouteNode, Node)}).
     * @param device Device in question.
     * @param crossingHalfColumns Array, indexed by half-tile X coordinate, to mark.
     */
    private static void populateSllCrossingHalfColumns(Device device, boolean[] crossingHalfColumns) {
        final int numUbumps = 6; // UBUMP0..5 in each SLL tile
        for (Tile tile : device.getAllTiles()) {
            if (tile.getTileTypeEnum() != TileTypeEnum.SLL || tile.getSLR().getId() != 0) {
                continue;
            }
            int westX = RouteNodeInfo.getWestIntTileXCoordinate(tile);
            if (crossingHalfColumns[2 * westX + 1]) {
                continue;
            }
            for (int i = 0; i < numUbumps; i++) {
                Node ubump = Node.getNode(tile, "UBUMP" + i);
                if (ubump == null) {
                    continue;
                }
                for (Wire wire : ubump.getAllWiresInNode()) {
                    Tile wireTile = wire.getTile();
                    if (wireTile.getTileXCoordinate() == tile.getTileXCoordinate() && wireTile.getSLR().getId() != 0) {
                        crossingHalfColumns[2 * westX + 1] = true;
                        crossingHalfColumns[2 * westX + 2] = true;
                    }
                }
            }
        }
    }

    /**
     * Checks if a (Versal) SLL is "horizontal": connecting SLL tiles in different columns, rather than in the same
     * column and different rows.
     * @param node NODE_SLL_DATA node in question.
     * @return True if so.
     */
    private static boolean isHorizontalSLL(Node node) {
        int x = node.getTile().getTileXCoordinate();
        for (Wire wire : node.getAllWiresInNode()) {
            if (wire.getTile().getTileXCoordinate() != x) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether nodes leading to a super long line (see {@link RouteNodeType#leadsToSLL()}) in the given row lead
     * to one crossing into the SLR to the north (else south): such nodes are only found in the band of rows next to an
     * SLR boundary, so those in the upper half of their SLR lead north, and those in the lower half south.
     * @param y INT tile Y coordinate of the node's base tile.
     * @return True if northbound.
     */
    public boolean isNorthboundSLLRow(int y) {
        int slr = intYToSLRIndex[y];
        return 2 * y > slrFirstIntY[slr] + slrLastIntY[slr];
    }

    /**
     * Gets the X coordinate of the next (at or above the given one) INT tile column from which super long lines can
     * cross into another SLR (see {@link #nextCrossingHalfColumn}).
     * @param x INT tile X coordinate.
     * @return That X coordinate, or Integer.MAX_VALUE if there is none.
     */
    public int getNextCrossingColumn(int x) {
        int halfX = nextCrossingHalfColumn[2 * x];
        return (halfX == Integer.MAX_VALUE) ? halfX : halfX >> 1;
    }

    /**
     * Gets the X coordinate of the previous (at or below the given one) INT tile column from which super long lines
     * can cross into another SLR (see {@link #prevCrossingHalfColumn}).
     * @param x INT tile X coordinate.
     * @return That X coordinate, or Integer.MIN_VALUE if there is none.
     */
    public int getPrevCrossingColumn(int x) {
        int halfX = prevCrossingHalfColumn[2 * x + 1];
        return (halfX == Integer.MIN_VALUE) ? halfX : halfX >> 1;
    }

    private void populateLagunaLookups(Device device, Tile[][] lagunaTiles, boolean[] crossingHalfColumns) {
        final Series series = device.getSeries();
        final boolean isUltraScale = series == Series.UltraScale;
        final boolean isUltraScalePlus = series == Series.UltraScalePlus;
        final int clockRegionHeight = series.getCLEHeight();
        final int slrHeight = device.getNumOfClockRegionRows() * clockRegionHeight / device.getSLRs().length;
        final Pattern inodePattern = Pattern.compile(isUltraScalePlus ? "INT_NODE_IMUX_\\d+_INT_OUT[01]|INODE_[EW]_\\d+_FT[01]"
                                                                      : "INT_NODE_IMUX_\\d+_INT_OUT|INODE_[12]_[EW]_\\d+_FT[NS]");
        final Pattern intIntPattern = Pattern.compile(isUltraScalePlus ? "INT_INT_SDQ_\\d+_INT_OUT[01]|WW1_E_7_FT0"
                                                                       : "INT_INT_SINGLE_\\d+_INT_OUT|EE1_W_0_FTS");
        final Pattern singlePattern = Pattern.compile("(NN|EE|SS|WW)1_[EW]_BEG[0-7]");
        final Pattern sdqNodeFtPattern = Pattern.compile(isUltraScalePlus ? "SDQNODE_[EW]_0_FT1"
                                                                          : "SDND[NS]W_E_0_FTS");
        final Pattern sdqNodePattern = Pattern.compile(isUltraScalePlus ? "INT_NODE_SDQ_\\d+_INT_OUT[01]|SDQNODE_(W_91_FT1|E_93_FT0)"
                                                                        : "INT_NODE_SINGLE_DOUBLE_\\d+_INT_OUT|SDND[NS]W_E_15_FTN");

        for (int slr = 0; slr < device.getNumOfSLRs() - 1; slr++) {
            for (int yModSlrHeight = slrHeight - clockRegionHeight; yModSlrHeight < slrHeight; yModSlrHeight++) {
                int y = slr * slrHeight + yModSlrHeight;
                // Examine the top clock region of every SLR
                Tile[] lagunaTilesAtY = lagunaTiles[y];
                for (int x = 0; x < lagunaTilesAtY.length; x++) {
                    Tile tile = lagunaTilesAtY[x];
                    if (tile == null) {
                        continue;
                    }
                    assert(x == tile.getTileXCoordinate());
                    assert(y == tile.getTileYCoordinate());

                    // Laguna tiles in the top clock region of an SLR lead north (see isNorthboundSLLRow())
                    assert(isNorthboundSLLRow(y));

                    // Looks like (on US+) LAGUNA tiles are always on the left side of an INT tile,
                    // with tile X coordinate one smaller; consider them to be on the west half of that INT tile
                    final int intTileXCoordinate = isUltraScalePlus ? x + 1 : x;
                    crossingHalfColumns[2 * intTileXCoordinate] = true;

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
                                assert(!enableComprehensiveAssertions || inodePattern.matcher(inode.getWireName()).matches());

                                for (Node intInt : inode.getAllUphillNodes()) {
                                    if (intInt.getTile() != inode.getTile()) {
                                        continue;
                                    }
                                    if (intInt.getIntentCode() != IntentCode.NODE_SINGLE) {
                                        continue;
                                    }
                                    if (!intIntPattern.matcher(intInt.getWireName()).matches()) {
                                        assert(!enableComprehensiveAssertions || singlePattern.matcher(intInt.getWireName()).matches());
                                        continue;
                                    }
                                    bs[1].set(intInt.getWireIndex());

                                    for (Node sdq : intInt.getAllUphillNodes()) {
                                        if (isUltraScale && sdq.isTiedToVcc()) {
                                            continue;
                                        }
                                        assert(sdq.getIntentCode() == IntentCode.NODE_LOCAL);

                                        if (sdq.getTile() != intInt.getTile()) {
                                            assert(!enableComprehensiveAssertions || sdqNodeFtPattern.matcher(sdq.getWireName()).matches());
                                            continue;
                                        }
                                        // The following assertion is expected to hold, but commented out for performance reasons
                                        assert(!enableComprehensiveAssertions || sdqNodePattern.matcher(sdq.getWireName()).matches());
                                        bs[1].set(sdq.getWireIndex());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void initialize() {
    }

    /**
     * Checks if a local node serves only one side (half) of its interconnect tile.
     * @param tileTypeEnum Tile type of the node's base wire.
     * @param wireIndex Wire index of the node's base wire.
     * @return True if so.
     */
    public boolean isSidedLocalWire(TileTypeEnum tileTypeEnum, int wireIndex) {
        BitSet sidedLocalWires = this.sidedLocalWires.get(tileTypeEnum);
        return sidedLocalWires != null && sidedLocalWires.get(wireIndex);
    }

    /**
     * Checks if a node ends on the east side of an interconnect tile (otherwise, it is considered to end on the
     * west side).
     * @param tileTypeEnum Tile type of the node's base wire.
     * @param wireIndex Wire index of the node's base wire.
     * @return True if so.
     */
    public boolean isEastSideWire(TileTypeEnum tileTypeEnum, int wireIndex) {
        BitSet eastSideWires = this.eastSideWires.get(tileTypeEnum);
        return eastSideWires != null && eastSideWires.get(wireIndex);
    }

    /**
     * Checks if a node is to be a {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} node (see {@link #inheritPrevSideWires}).
     * @param tileTypeEnum Tile type of the node's base wire.
     * @param wireIndex Wire index of the node's base wire.
     * @return True if so.
     */
    public boolean isInheritPrevSideWire(TileTypeEnum tileTypeEnum, int wireIndex) {
        BitSet inheritPrevSideWires = this.inheritPrevSideWires.get(tileTypeEnum);
        return inheritPrevSideWires != null && inheritPrevSideWires.get(wireIndex);
    }

    /**
     * Checks if an UltraScale+ long or UltraScale quad (which have no side in their name) serves the east side, as
     * given by its direction and index (on xcvu3p and xcku040): UltraScale+ EE12_BEG&lt;even&gt; and
     * {WW,NN,SS}12_BEG&lt;odd&gt;; UltraScale EE4_BEG&lt;even&gt;, WW4_BEG&lt;odd&gt;, NN5_BEG* and SS4_BEG*.
     * @param isUltraScalePlus True for UltraScale+ (longs), false for UltraScale (quads).
     * @param m Matcher (that matches) with "dir", "len" and "index" groups, e.g. for EE12_BEG0.
     * @return True if so.
     */
    private static boolean isEastSideByIndexName(boolean isUltraScalePlus, Matcher m) {
        String dir = m.group("dir");
        if (isUltraScalePlus || dir.equals("EE") || dir.equals("WW")) {
            return dir.equals("EE") == (Integer.parseInt(m.group("index")) % 2 == 0);
        }
        String len = m.group("len");
        assert(len.equals("4") || len.equals("5"));
        return dir.equals("NN") == len.equals("5");
    }

    /**
     * Places a node on the side (half) of its INT tile that the nodes it drives are on, all of which (that have a side)
     * must be on the same side. Nodes driving none with a side are left on the west side.
     * @param baseNode Base node in question.
     * @param downhillSide Gives the side of a downhill node (true for east), or null if it has none.
     * @return True if placed on the east side, false if on the west side, or null if no downhill node has a side.
     */
    private Boolean setSideFromDownhill(Node baseNode, Function<Node, Boolean> downhillSide) {
        Boolean east = null;
        for (Node downhill : baseNode.getAllDownhillNodes()) {
            Boolean downhillEast = downhillSide.apply(downhill);
            if (downhillEast == null) {
                continue;
            }
            assert(east == null || east.equals(downhillEast));
            east = downhillEast;
        }
        if (east != null && east) {
            setEastSide(baseNode);
        }
        return east;
    }

    /**
     * Places a (base) node on the east side (half) of an INT tile (see {@link #eastSideWires}).
     * @param baseNode Base node in question.
     */
    private void setEastSide(Node baseNode) {
        eastSideWires.computeIfAbsent(baseNode.getTile().getTileTypeEnum(), k -> new BitSet()).set(baseNode.getWireIndex());
    }

    /**
     * Checks if a node is not on either side of an INT tile but between two (on the east side of the INT tile at
     * {@link RouteNode#getEndTileXCoordinate()} and the west side of the next), and is thus considered to be on both:
     * Versal SLL inputs, outputs and data, in SLL tiles (see {@link RouteNodeInfo#isOnBothSides(IntentCode)}).
     * @param rnode Node in question.
     * @return True if so.
     */
    public boolean isOnBothSides(RouteNode rnode) {
        return isVersal && RouteNodeInfo.isOnBothSides(rnode.getIntentCode());
    }

    /**
     * Gets the type that a (single) sink is to be given: {@link RouteNodeType#EXCLUSIVE_SINK_CTRL} for CTRL_* pins on
     * UltraScale/UltraScale+ and CTRL pins on Versal, {@link RouteNodeType#EXCLUSIVE_SINK_SLL_INPUT} for SLL inputs on
     * Versal, otherwise {@link RouteNodeType#EXCLUSIVE_SINK}.
     * @param sinkRnode Sink node in question.
     * @return Its type.
     */
    public RouteNodeType getExclusiveSinkType(RouteNode sinkRnode) {
        if (isVersal) {
            IntentCode ic = sinkRnode.getIntentCode();
            if (ic == IntentCode.NODE_CLE_CTRL || ic == IntentCode.NODE_INTF_CTRL) {
                return RouteNodeType.EXCLUSIVE_SINK_CTRL;
            }
            if (ic == IntentCode.NODE_SLL_INPUT) {
                return RouteNodeType.EXCLUSIVE_SINK_SLL_INPUT;
            }
            return RouteNodeType.EXCLUSIVE_SINK;
        }
        if (sinkRnode.getTile().getTileTypeEnum() == TileTypeEnum.INT && ultraScalesCtrlWires.get(sinkRnode.getWireIndex())) {
            return RouteNodeType.EXCLUSIVE_SINK_CTRL;
        }
        return RouteNodeType.EXCLUSIVE_SINK;
    }

    /*
     * Return the highest base wire index across all Nodes in the given tile.
     * If its TileTypeEnum has not been seen already, compute this highest index,
     * and assume that is same for all tiles with this TileTypeEnum.
     * If this assumption fails, recompute with the given tile.
     */
    protected int getHighestBaseWireIndex(Tile tile, int startWireIndex) {
        return highestBaseWireIndex.compute(tile.getTileTypeEnum(), (tte, highestBaseWire) -> {
            if (highestBaseWire == null || highestBaseWire <= startWireIndex) {
                final boolean isSLLType = (tte == TileTypeEnum.SLL); // Versal only
                // Check all wires in tile to find the index of the last base wire
                int lastBaseWire = startWireIndex;
                for (int i = lastBaseWire + 1; i < tile.getWireCount(); i++) {
                    Node node = Node.getNode(tile, i);
                    if (node == null) {
                        continue;
                    }
                    if ((node.getTile() == tile && node.getWireIndex() == i) ||
                            // Count Versal SLLs even if they're not based in this tile, since they are bidir
                            (isSLLType && node.getTile().getTileTypeEnum() == tte && node.getIntentCode() == IntentCode.NODE_SLL_DATA)) {
                        lastBaseWire = i;
                    }
                }
                highestBaseWire = lastBaseWire + 1;
            }
            return highestBaseWire;
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
        int tileAddress = tile.getUniqueAddress();
        final Net[] oldNet = new Net[1];
        preservedMap.updateAndGet(tileAddress, (current) -> {
            if (current == null) {
                // First time
                int baseWireCount = getHighestBaseWireIndex(tile, wireIndex);
                current = new Net[baseWireCount];
            } else if (wireIndex >= current.length) {
                int baseWireCount = getHighestBaseWireIndex(tile, wireIndex);
                current = Arrays.copyOf(current, baseWireCount);
            }

            oldNet[0] = current[wireIndex];
            // Do not clobber the old value
            if (oldNet[0] == null) {
                current[wireIndex] = net;
            }
            return current;
        });

        return oldNet[0];
    }

    public void preserve(Net net, List<SitePinInst> pins) {
        boolean isStaticNet = net.isStaticNet();
        for (SitePinInst pin : pins) {
            Node preserveNode = pin.getConnectedNode();
            if (isVersal && !pin.isOutPin()) {
                // On Versal, spiNode gives the "*_PIN" node. Preserve the one and only
                // node uphill of that, which is the "IMUX_*" or "BOUNCE_*"
                List<Node> uphillNodes = preserveNode.getAllUphillNodes();
                assert(uphillNodes.size() == 1);
                preserveNode = uphillNodes.get(0);
            }
            preserve(preserveNode, net);

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
            Node startNode = pip.getStartNode();
            if (isStaticNet && startNode.isTied()) {
                // No need to preserve tied nodes (e.g. VCC_WIRE) since they can't conflict
            } else {
                preserve(startNode, net);
            }
            preserve(pip.getEndNode(), net);
        }
    }

    public void preserveAsync(Net net, List<SitePinInst> pins) {
        ParallelismTools.submit(asyncPreserveOutstanding, () -> {
            try {
                preserve(net, pins);
            } catch (Throwable t) {
                t.printStackTrace();
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
        asyncPreserveOutstanding.tryComplete();
        // Uninterruptibly (as before). First runs any of those calls' tasks not yet started on this thread, rather than
        // only blocking, then blocks for those already started: it helps only that once, which is enough, as the
        // calling thread submitted all of them before this and none of them submits any more (unlike
        // ParallelismTools.join(CountedCompleter), which keeps helping). Never completes exceptionally, as each task
        // catches what it throws
        asyncPreserveOutstanding.quietlyJoin();
        // Only once done (all of its children have completed, and this was its only join), so that it can be reused
        // for any later calls to preserveAsync()
        asyncPreserveOutstanding.reinitialize();
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
        allowedTileEnums.add(TileTypeEnum.SLL);
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
            if (isVersal) {
                IntentCode parentIc = parent.getIntentCode();
                if (parentIc == IntentCode.NODE_SLL_OUTPUT) {
                    // Allow NODE_SLL_OUTPUT -> CLE/*LAG*_PIN
                    return false;
                }
                if (parentIc == IntentCode.NODE_CLE_OUTPUT) {
                    // Allow NODE_CLE_OUTPUT -> NODE_CLE_OUTPUT (e.g. [A-H]Q_PIN -> [A-H]Q)
                    assert(child.getIntentCode() == IntentCode.NODE_CLE_OUTPUT);
                    return false;
                }
            }
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

            if (ic == IntentCode.NODE_INTF_CNODE) {
                BitSet ctrlOnlyWires = versalIntfCtrlOnlyWires.get(child.getTile().getTileTypeEnum());
                if (ctrlOnlyWires != null && ctrlOnlyWires.get(child.getWireIndex())) {
                    // Likewise for CNODEs that only drive CTRL pins, unless already in the routing graph (as a
                    // LOCAL_RESERVED node uphill of a CTRL sink)
                    return childRnode == null;
                }
            }

            if (ic == IntentCode.NODE_SLL_INPUT && parent.getIntentCode() == IntentCode.NODE_SLL_OUTPUT) {
                // Disallow NODE_SLL_OUTPUT -> NODE_SLL_INPUT arcs (why are you able to come off an SLL just to go back onto one?)
                return true;
            }

            if (ic == IntentCode.NODE_SLL_DATA && isHorizontalSLL(child)) {
                // Horizontal SLLs are not currently supported
                return true;
            }
        } else {
            assert(design.getSeries() == Series.UltraScale || design.getSeries() == Series.UltraScalePlus);

            if (ic == IntentCode.NODE_PINFEED) {
                // PINFEEDs can lead to a site pin, or into a Laguna tile
                if (childRnode != null) {
                    assert(childRnode.getType().isAnyExclusiveSink() ||
                           childRnode.getType().isLocalLeadingToSLL() ||
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

        if (wireIndicesLeadingToLaguna == null && Utils.isLaguna(child.getTile().getTileTypeEnum())) {
            // Exclude Laguna nodes on single SLR devices
            return true;
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
            int baseWireCount = getHighestBaseWireIndex(tile, wireIndex);
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

    public boolean isAccessible(RouteNode childRnode, RouteNode parentRnode, Connection connection) {
        assert(!childRnode.isTarget());

        // Only consider LOCAL nodes when:
        RouteNodeType type = childRnode.getType();
        if (!type.isAnyLocal()) {
            switch (type) {
                case NON_LOCAL:
                case NON_LOCAL_INHERIT_PREV_SIDE:
                    RouteNodeType parentType = parentRnode.getType();
                    if (parentType.isAnyLocal()) {
                        // LOCAL -> NON_LOCAL
                        assert(parentRnode.getIntentCode() == IntentCode.NODE_PINFEED);

                        TileTypeEnum childTileType = childRnode.getTile().getTileTypeEnum();
                        if (Utils.isLaguna(childTileType)) {
                            assert(parentType.isLocalLeadingToSLL());
                            // IMUX_[EW]\\d+ -> LAG_MUX_ATOM_\\d+_TXOUT
                            RouteNode sinkRnode = connection.getSinkRnode();
                            if (!connection.isCrossSLR() ||
                                childRnode.getSLRIndex(this) == sinkRnode.getSLRIndex(this)) {
                                assert(lutRoutethru ||
                                        // Inadvertently approaching an SLL because we are Y +/- 1 to the sink tile
                                        (childRnode.getEndTileXCoordinate() == sinkRnode.getBeginTileXCoordinate() &&
                                         Math.abs(childRnode.getEndTileYCoordinate() - sinkRnode.getBeginTileYCoordinate()) <= 1));
                                return false;
                            }
                        } else {
                            assert(lutRoutethru ||
                                    isVersal && connection.isCrossSLR() && isVersalLagOutRoutethru(parentRnode, childRnode));
                            assert(Utils.isCLB(childTileType));
                            // IMUX_[EW]\\d+ -> CLE_CLE_L_SITE_0_[A-H]_O
                            assert(childRnode.getIntentCode() == IntentCode.NODE_CLE_OUTPUT);
                        }
                    } else if (parentType == RouteNodeType.SUPER_LONG_LINE && parentRnode.getPrev().getTile() == childRnode.getTile()) {
                        // UBUMP -> RXD: with an SLL being bidirectional, do not go back the way we came from
                        return false;
                    }
                    break;
                case NON_LOCAL_LEADING_TO_SLL:
                    break;
                default:
                    throw new RuntimeException("ERROR: Unrecognized rnode type: " + type);
            }
            return true;
        }

        TileTypeEnum childTileType = childRnode.getTile().getTileTypeEnum();
        assert(childTileType == TileTypeEnum.INT ||
                (isVersal && EnumSet.of(TileTypeEnum.INTF_LOCF_TR_TILE, TileTypeEnum.INTF_LOCF_BR_TILE, TileTypeEnum.INTF_ROCF_TR_TILE, TileTypeEnum.INTF_ROCF_BR_TILE,
                                        TileTypeEnum.INTF_LOCF_TL_TILE, TileTypeEnum.INTF_LOCF_BL_TILE, TileTypeEnum.INTF_ROCF_TL_TILE, TileTypeEnum.INTF_ROCF_BL_TILE,
                                      TileTypeEnum.CLE_BC_CORE, TileTypeEnum.SLL)
                       .contains(childTileType)) ||
                (isVersal && Utils.isCLB(childTileType) && parentRnode.getIntentCode() == IntentCode.NODE_SLL_OUTPUT && childRnode.getIntentCode() == IntentCode.NODE_PINFEED) ||
                // Allow NODE_PINFEEDs in RCLK tiles that are preserved (belonging to a partially routed net)
                (Utils.isClocking(childTileType) && childRnode.getIntentCode() == IntentCode.NODE_PINFEED && isPreserved(childRnode))
        );

        if (lutRoutethru && !type.leadsToSLL()) {
            // (a) considering LUT routethrus (that do not lead to an SLL)
            return true;
        }

        // (b) needs to cross an SLR and this is a local node leading to an SLL that crosses SLRs
        Tile childTile = childRnode.getTile();
        RouteNode sinkRnode = connection.getSinkRnode();
        int childX = childRnode.getEndTileXCoordinate();
        if (connection.isCrossSLR() && type.isLocalLeadingToSLL() &&
                childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this)) {
            assert(isVersal ? childRnode.getIntentCode() == IntentCode.NODE_SLL_INPUT
                            : wireIndicesLeadingToLaguna.get(childTile) != null);
            assert(childTile != sinkRnode.getTile());
            if (isNorthboundSLLRow(childTile.getTileYCoordinate()) ? !connection.isCrossSLRnorth() : !connection.isCrossSLRsouth()) {
                // Does not lead to an SLL in the direction that we want
                return false;
            }
            assert(getNextCrossingColumn(childX) == childX);
            return true;
        }

        // (c) on the same side (half) as the sink
        Tile sinkTile = sinkRnode.getTile();
        RouteNodeType sinkType = sinkRnode.getType();
        switch (sinkType) {
            case LOCAL:
                // An alternate sink (e.g. a swappable LUT pin)
                assert(connection.hasAltSinks());
                // Fall-through
            case EXCLUSIVE_SINK:
                if (type == RouteNodeType.LOCAL_RESERVED) {
                    // Only reserved for (Versal) CTRL sinks
                    return false;
                }
                if (childRnode.isOnEastSide() != sinkRnode.isOnEastSide()) {
                    if (type.isSidedLocal()) {
                        if (!isVersal ||
                                // Versal SLL inputs are not on any specific side, and Versal PINFEEDs (only
                                // CLE_SLICE[LM]_TOP_*_LAG_*_PIN, downhill of an SLL output in another SLR to the
                                // sink) are only sided for their distance to the sink, not to restrict access
                                (childRnode.getIntentCode() != IntentCode.NODE_SLL_INPUT &&
                                 childRnode.getIntentCode() != IntentCode.NODE_PINFEED)) {
                            // Local wires on one side (half) of an INT tile can never reach a sink on the other side
                            return false;
                        }
                    }
                    if (type == RouteNodeType.LOCAL_SIDE_DEPENDS_ON_SINK) {
                        // Nor can INT_NODE_GLOBAL_*, which only serve the INODEs on their side
                        return false;
                    }
                }
                break;
            case EXCLUSIVE_SINK_SLL_INPUT:
                assert(sinkRnode.getIntentCode() == IntentCode.NODE_SLL_INPUT);
                // Fall-through
            case EXCLUSIVE_SINK_CTRL:
                // A CTRL sink (or SLL input) that can be accessed from both east/west sides
                if (isVersal) {
                    assert(sinkType != RouteNodeType.EXCLUSIVE_SINK_CTRL ||
                            sinkRnode.getIntentCode() == IntentCode.NODE_CLE_CTRL ||
                            sinkRnode.getIntentCode() == IntentCode.NODE_INTF_CTRL);

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

                    // Only INT_NODE_GLOBAL_* (on either side) can reach a CTRL_* sink
                    if (type != RouteNodeType.LOCAL_SIDE_DEPENDS_ON_SINK) {
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
                throw new RuntimeException("ERROR: Unexpected sink type " + sinkType);
        }

        // (d) in the sink tile
        if (childTile == sinkTile) {
            return true;
        }

        if (isVersal) {
            assert(!sinkType.isExclusiveSinkCtrlOrSllInput());
            assert(sinkRnode.getIntentCode() == IntentCode.NODE_IMUX || sinkRnode.getIntentCode() == IntentCode.NODE_PINBOUNCE);

            IntentCode childIntentCode = childRnode.getIntentCode();
            switch (childIntentCode) {
                case NODE_INODE:
                    if (connection.isCrossSLR() &&
                            childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this)) {
                        // Allow INODEs needed for PINBOUNCE -> BNODE -> NODE_SLL_INPUT
                        // TODO: Only allow those that lead to a SLR crossing
                        return true;
                    }
                    // Block access to all INODEs outside the sink tile, since NODE_INODE -> NODE_IMUX -> NODE_PINFEED (or NODE_INODE -> NODE_PINBOUNCE)
                    assert(childTile != sinkTile);
                    return false;
                case NODE_CLE_BNODE:
                    if (connection.isCrossSLR() &&
                            childTile.getTileTypeEnum() == TileTypeEnum.SLL &&
                            childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this)) {
                        // Allow CLE BNODEs since these are used to reach NODE_SLL_INPUT nodes
                        // TODO: Only allow those that lead to a SLR crossing
                        return true;
                    }
                case NODE_INTF_BNODE:
                case NODE_CLE_CNODE:
                case NODE_INTF_CNODE:
                    if (childTile.getTileYCoordinate() != sinkTile.getTileYCoordinate() ||
                        childRnode.getEndTileXCoordinate() != sinkTile.getTileXCoordinate()) {
                        assert(parentRnode.getIntentCode() != IntentCode.NODE_INODE);
                        return false;
                    }
                    // Only allow [BC]NODEs that reach into the sink tile
                    return true;
                case NODE_PINBOUNCE:
                    // PINBOUNCEs are only accessible through an INODE, so arriving here means that this must be a
                    // Versal inter-SLR connection
                    assert(connection.isCrossSLR() &&
                            childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this));
                    return true;
                case NODE_IMUX:
                    // IMUXes that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph unless
                    // LUT routethrus are enabled (which would have already returned true above)
                    break;
                case NODE_PINFEED:
                    if (connection.isCrossSLR()) {
                        // Allow NODE_SLL_OUTPUT -> NODE_PINFEED
                        return parentRnode.getIntentCode() == IntentCode.NODE_SLL_OUTPUT;
                    }
                    // Expected to be projected away
                    break;
                case NODE_CLE_CTRL:
                case NODE_INTF_CTRL:
                    // CTRL pins that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph
                    break;
                case NODE_SLL_INPUT:
                    // SLL inputs leading to an SLL that crosses SLRs were handled by (b) above; others (whose SLL
                    // does not leave their tile, stays within their SLR, or is horizontal) are only explored if
                    // they are the sink
                    assert(!type.isLocalLeadingToSLL() || !connection.isCrossSLR() ||
                           childRnode.getSLRIndex(this) == sinkRnode.getSLRIndex(this));
                    return childRnode == sinkRnode;
            }
            throw new RuntimeException("ERROR: Unhandled IntentCode: " + childIntentCode);
        }

        // (e) when in same X as the sink tile, but Y +/- 1
        return childX == sinkRnode.getBeginTileXCoordinate() &&
               Math.abs(childRnode.getEndTileYCoordinate() - sinkRnode.getBeginTileYCoordinate()) <= 1;
    }

    protected boolean allowRoutethru(RouteNode head, Node tail) {
        final boolean isCLB = Utils.isCLB(tail.getTile().getTileTypeEnum());

        if (isVersal) {
            if (tail.getIntentCode() == IntentCode.NODE_CLE_OUTPUT &&
                    head.getIntentCode() == IntentCode.NODE_PINFEED &&
                    head.getPrev().getIntentCode() == IntentCode.NODE_SLL_OUTPUT) {
                // Ths sequence NODE_SLL_OUTPUT -> NODE_PINFEED -> NODE_CLE_OUTPUT must be a slice routethru the Laguna pin
                assert(isVersalLagOutRoutethru(head, tail));
                // Allow CLE/*LAG*_PIN -> CLE/*[A-H]Q2?_PIN routethru
                return true;
            }
        }

        if (!lutRoutethru) {
            return false;
        }

        if (!isCLB) {
            return false;
        }

        if (tail.getIntentCode() == IntentCode.NODE_PINFEED) {
            assert(isVersal);
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

    /**
     * Gets the delay estimator that this graph computes its node delays with.
     * @return That delay estimator, or null since this graph is not timing driven.
     */
    public DelayEstimatorBase<InterconnectInfo> getDelayEstimator() {
        return null;
    }

    /**
     * Determine if the given nodes represent a SLICE routethru from an input site pin dedicated for SLLs
     * to the [A-H]Q or [A-H]Q2 output.
     * @param parent Start node of PIP
     * @param child End node of PIP
     * @return True if routethru
     */
    public boolean isVersalLagOutRoutethru(Node parent, Node child) {
        assert(isVersal);
        return parent.getIntentCode() == IntentCode.NODE_PINFEED &&
                child.getIntentCode() == IntentCode.NODE_CLE_OUTPUT &&
                parent.getWireName().matches("CLE_SLICE[LM]_TOP_[01]_LAG_([NS]|[EW][12])_PIN") &&
                child.getWireName().matches("CLE_SLICE[LM]_TOP_[01]_[A-H]Q2?_PIN");
    }
}
