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

    /** Length of a super long line on UltraScale and UltraScale+, in INT tiles */
    public static final short ULTRASCALE_SUPER_LONG_LINE_LENGTH_IN_TILES = 60;
    /** Length of a super long line on Versal, in INT tiles */
    public static final short VERSAL_SUPER_LONG_LINE_LENGTH_IN_TILES = 75;

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
     * Map indicating which wire indices within a tile lead to a super long line that crosses into another SLR, or
     * null if the device has no such super long lines. Indexed by tile, each entry holds two BitSets:
     * <ul>
     * <li>UltraScale/UltraScale+ (Laguna-adjacent INT tiles and their Laguna tiles; see
     * {@link #populateLagunaLookups}): [0] IMUX and INODE wires in an INT tile, or TXOUT (LAG_MUX_ATOM_*_TXOUT) wires
     * in a Laguna tile (LOCAL_LEADING_TO_SLL), [1] INT_INT and SDQNODE wires in an INT tile, and in the INT tile on
     * the other side of the Laguna column, horizontal singles into it and the SDQNODE wires that drive those
     * (NON_LOCAL_LEADING_TO_SLL).</li>
     * <li>Versal (SLL and INT tiles, each computed when first needed; see {@link #getWireIndicesLeadingToSLL(Tile)}):
     * in an SLL tile, [0] SLL inputs whose SLL crosses SLRs, [1] BNODEs that drive such an SLL input; in an INT tile,
     * [0] PINBOUNCEs that drive such a BNODE (in the SLL tile beside their side of the INT tile, in the same row) and
     * the INODEs that drive such a PINBOUNCE, [1] nothing (all LOCAL_LEADING_TO_SLL).</li>
     * </ul>
     * Keyed by INT and Laguna tiles on UltraScale/UltraScale+ and by SLL and INT tiles on Versal, so users that are
     * specific to one series check the series rather than whether this is null.
     */
    protected final Map<Tile, BitSet[]> wireIndicesLeadingToSLL;

    /**
     * What RWRoute knows about the wires of one tile type, indexed by (base) wire index, as all tiles of a type share
     * their wires: held together (see {@link #tileTypeInfos}) so that one lookup by tile type serves every property,
     * rather than one map per property. Each BitSet is null where no wire of the type has that property.
     */
    protected static final class TileTypeInfo {
        /**
         * (UltraScale/UltraScale+ INT only, whose entry always has it) The subset of NODE_LOCAL wires that RWRoute
         * should assign a LOCAL_* type, e.g. excluding INT_NODE_SDQ_*.
         */
        BitSet localWires;

        /**
         * Local nodes that serve only one side (half) of interconnect tiles (that side being given by
         * {@link #eastSideWires}).
         */
        BitSet sidedLocalWires;

        /**
         * Nodes (by their base wire) that end on the east half of an interconnect tile; all other nodes are
         * considered to end on its west half. This includes local nodes serving the east side, interconnect wires
         * (e.g. singles and doubles) arriving at the east side, CTRL_E* pins, and INT_NODE_GLOBAL_* nodes driving
         * INODEs on the east side (UltraScale/UltraScale+), as well as CTRL_L_B* pins in CLE_BC_CORE/SLL tiles
         * (Versal). Nodes of the tiles beside one side of their INT tile (e.g. CLE outputs) are placed by tile type
         * instead (see {@link RouteNodeGraph#SITE_TILE_TYPE_ON_EAST_SIDE}).
         */
        BitSet eastSideWires;

        /**
         * Non-local nodes (by their base wire) that serve both sides of the INT tile that they end at, and so are given
         * the {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} type: UltraScale longs and INT_NODE_QUAD_LONG_* /
         * QLND* (which drive a long), and Versal longs.
         */
        BitSet inheritPrevSideWires;

        /**
         * (Versal only) INTF_* tile CNODEs that only drive that tile's CTRL pins (and thus are only of use to reach
         * those pins).
         */
        BitSet intfCtrlOnlyWires;

        /**
         * (UltraScale/UltraScale+ CLB tiles only, with LUT routethrus) The [A-H]MUX outputs to be blocked during LUT
         * routethrus.
         */
        BitSet muxWiresToBlockWhenLutRoutethru;

        /** (UltraScale/UltraScale+ INT only, whose entry always has it) CTRL_* pins. */
        BitSet ctrlWires;

        /**
         * Sets a bit in a BitSet, creating it if null.
         * @param bs The BitSet, or null.
         * @param wireIndex The bit to set.
         * @return The BitSet.
         */
        static BitSet set(BitSet bs, int wireIndex) {
            if (bs == null) {
                bs = new BitSet();
            }
            bs.set(wireIndex);
            return bs;
        }

        /**
         * Gets a bit of a BitSet that may be null.
         * @param bs The BitSet, or null.
         * @param wireIndex The bit to get.
         * @return False if bs is null, else the bit.
         */
        static boolean get(BitSet bs, int wireIndex) {
            return bs != null && bs.get(wireIndex);
        }
    }

    /**
     * What RWRoute knows about the wires of each tile type that has any wire with one of the properties of
     * {@link TileTypeInfo}; a tile type with none has no entry (see {@link #getTileTypeInfo(TileTypeEnum)}). Entries
     * are created (see {@link #getOrCreateTileTypeInfo(TileTypeEnum)}) only during construction, which is
     * single-threaded, and the map is never modified after that, so it may be read by several threads at once.
     */
    protected final Map<TileTypeEnum, TileTypeInfo> tileTypeInfos = new EnumMap<>(TileTypeEnum.class);

    /** Flag for whether LUT routethrus are to be considered */
    protected final boolean lutRoutethru;

    /** Flag for whether LUT pin swapping is to be considered */
    protected final boolean lutPinSwapping;

    /**
     * Side of their INT tile (true for east) that tiles of these types are beside, and so that the nodes in them that
     * RWRoute routes from (CLE outputs, and the INT_INTERFACE_* / INT_INTF_* / INTF_* / BLI_CLE_* nodes that all other
     * site outputs are projected onto; see RouterHelper.projectOutputPinToINTNode()) enter that INT tile on, and drive
     * nodes on (other than UltraScale INT_NODE_QUAD_LONG_* / QLND*, which serve both sides): as found for every such
     * node on xcku040, xcvu3p, xcvu9p, xcu50 and xczu7ev, and in samples of each type on xcku115, xcvu440, xcvu095,
     * xcvu13p, xczu9eg, xcu250, xcvu37p, xcvc1902 and xcv80. Neither the suffix of a type's name (CLEM_R and CLE_M_R
     * are beside the west side) nor, on Versal, the LOGIC_OUTS_[EW]* wire in the node (most CLE_W_CORE outputs on
     * xcv80 contain a LOGIC_OUTS_W*) always says which. Every node of these types is placed on that side (see
     * isEastSideWire()).
     */
    protected static final Map<TileTypeEnum, Boolean> SITE_TILE_TYPE_ON_EAST_SIDE = new EnumMap<>(TileTypeEnum.class);
    static {
        for (TileTypeEnum tte : EnumSet.of(
                // UltraScale and UltraScale+
                TileTypeEnum.CLEL_R,
                // UltraScale+
                TileTypeEnum.INT_INTF_R, TileTypeEnum.INT_INTF_R_PCIE4, TileTypeEnum.INT_INTF_R_TERM_GT,
                TileTypeEnum.INT_INTF_RIGHT_TERM_IO,
                // UltraScale
                TileTypeEnum.INT_INTERFACE_R, TileTypeEnum.INT_INTERFACE_PCIE_R, TileTypeEnum.INT_INTERFACE_GT_R,
                // Versal
                TileTypeEnum.CLE_W_CORE, TileTypeEnum.BLI_CLE_BOT_CORE, TileTypeEnum.BLI_CLE_TOP_CORE,
                TileTypeEnum.INTF_LOCF_TR_TILE, TileTypeEnum.INTF_LOCF_BR_TILE,
                TileTypeEnum.INTF_ROCF_TR_TILE, TileTypeEnum.INTF_ROCF_BR_TILE,
                TileTypeEnum.INTF_GT_TR_TILE, TileTypeEnum.INTF_GT_BR_TILE,
                TileTypeEnum.INTF_HB_LOCF_TR_TILE, TileTypeEnum.INTF_HB_LOCF_BR_TILE,
                TileTypeEnum.INTF_HB_ROCF_TR_TILE, TileTypeEnum.INTF_HB_ROCF_BR_TILE,
                TileTypeEnum.INTF_HDIO_LOCF_TR_TILE, TileTypeEnum.INTF_HDIO_ROCF_TR_TILE)) {
            SITE_TILE_TYPE_ON_EAST_SIDE.put(tte, true);
        }
        for (TileTypeEnum tte : EnumSet.of(
                // UltraScale and UltraScale+
                TileTypeEnum.CLEL_L,
                // UltraScale+
                TileTypeEnum.CLEM, TileTypeEnum.CLEM_R,
                TileTypeEnum.INT_INTF_L, TileTypeEnum.INT_INTF_L_CMT, TileTypeEnum.INT_INTF_L_IO,
                TileTypeEnum.INT_INTF_L_PCIE4, TileTypeEnum.INT_INTF_L_TERM_GT,
                TileTypeEnum.INT_INTF_LEFT_TERM_IO_FT, TileTypeEnum.INT_INTF_LEFT_TERM_PSS,
                // UltraScale
                TileTypeEnum.CLE_M, TileTypeEnum.CLE_M_R,
                TileTypeEnum.INT_INTERFACE_L, TileTypeEnum.INT_INTERFACE_PCIE_L,
                TileTypeEnum.INT_INT_INTERFACE_XIPHY_FT, TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_FT,
                // Versal
                TileTypeEnum.CLE_E_CORE, TileTypeEnum.BLI_CLE_BOT_CORE_MY, TileTypeEnum.BLI_CLE_TOP_CORE_MY,
                TileTypeEnum.INTF_LOCF_TL_TILE, TileTypeEnum.INTF_LOCF_BL_TILE,
                TileTypeEnum.INTF_ROCF_TL_TILE, TileTypeEnum.INTF_ROCF_BL_TILE,
                TileTypeEnum.INTF_GT_TL_TILE, TileTypeEnum.INTF_GT_BL_TILE,
                TileTypeEnum.INTF_HB_LOCF_TL_TILE, TileTypeEnum.INTF_HB_LOCF_BL_TILE,
                TileTypeEnum.INTF_HB_ROCF_TL_TILE, TileTypeEnum.INTF_HB_ROCF_BL_TILE,
                TileTypeEnum.INTF_HDIO_LOCF_TL_TILE, TileTypeEnum.INTF_HDIO_ROCF_TL_TILE,
                TileTypeEnum.INTF_CFRM_TL_TILE, TileTypeEnum.INTF_CFRM_BL_TILE,
                TileTypeEnum.INTF_PSS_TL_TILE, TileTypeEnum.INTF_PSS_BL_TILE)) {
            SITE_TILE_TYPE_ON_EAST_SIDE.put(tte, false);
        }
    }

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

            TileTypeInfo intInfo = getOrCreateTileTypeInfo(tile.getTileTypeEnum());
            intInfo.localWires = localWires;
            intInfo.ctrlWires = new BitSet();

            eastWestPattern = Pattern.compile("(((BOUNCE|BYPASS|IMUX|INODE(_[12])?)_(?<eastwest>[EW]))|INT_NODE_IMUX_(?<inode>\\d+)_).*");

            // Longs (and US quads) have no side in their name
            intTileSideNamedIntentCodes = EnumSet.of(
                    IntentCode.NODE_SINGLE,
                    IntentCode.NODE_DOUBLE,
                    IntentCode.NODE_HQUAD,
                    IntentCode.NODE_VQUAD);

            SUPER_LONG_LINE_LENGTH_IN_TILES = ULTRASCALE_SUPER_LONG_LINE_LENGTH_IN_TILES;
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

            eastWestPattern = Pattern.compile("(((BOUNCE|IMUX_B|[BC]NODE_OUTS)_(?<eastwest>[EW]))|INT_NODE_IMUX_ATOM_(?<inode>\\d+)_).*");

            // Longs have no side in their name
            intTileSideNamedIntentCodes = EnumSet.of(
                    IntentCode.NODE_HSINGLE,
                    IntentCode.NODE_VSINGLE,
                    IntentCode.NODE_HDOUBLE,
                    IntentCode.NODE_VDOUBLE,
                    IntentCode.NODE_HQUAD,
                    IntentCode.NODE_VQUAD);

            SUPER_LONG_LINE_LENGTH_IN_TILES = VERSAL_SUPER_LONG_LINE_LENGTH_IN_TILES;
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
                    TileTypeInfo info = getOrCreateTileTypeInfo(baseNode.getTile().getTileTypeEnum());
                    info.inheritPrevSideWires = TileTypeInfo.set(info.inheritPrevSideWires, baseNode.getWireIndex());
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
                                    // INT_NODE_QUAD_LONG_* / QLND* each drive two quads and a long (with no side), and
                                    // so serve both sides even when both quads are on the same side (32 of 128 per INT
                                    // tile on xcku040): the long that they drive inherits their side, and the quads
                                    // land where their own names say
                                    assert(baseNode.getAllDownhillNodes().stream().anyMatch((downhill) ->
                                            downhill.getIntentCode() == IntentCode.NODE_HLONG ||
                                            downhill.getIntentCode() == IntentCode.NODE_VLONG));
                                    TileTypeInfo info = getOrCreateTileTypeInfo(baseTile.getTileTypeEnum());
                                    info.inheritPrevSideWires = TileTypeInfo.set(info.inheritPrevSideWires,
                                            baseNode.getWireIndex());
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
                    TileTypeInfo info = getOrCreateTileTypeInfo(baseTileType);
                    info.sidedLocalWires = TileTypeInfo.set(info.sidedLocalWires, baseNode.getWireIndex());
                    if (east) {
                        setEastSide(baseNode);
                    }
                } else {
                    assert((isUltraScale || isUltraScalePlus) && baseWireName.matches("CTRL_[EW](_B)?\\d+|INT_NODE_GLOBAL_\\d+(_INT)?_OUT[01]?"));
                    // CTRL_* pins are on the side in their name (even though they are reachable from
                    // INT_NODE_GLOBAL_* which can serve both sides)
                    if (baseWireName.startsWith("CTRL_")) {
                        assert(baseNode.getTile().getTileTypeEnum() == TileTypeEnum.INT);
                        getTileTypeInfo(TileTypeEnum.INT).ctrlWires.set(baseNode.getWireIndex());
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
                        TileTypeInfo info = getOrCreateTileTypeInfo(tte);
                        info.eastSideWires = TileTypeInfo.set(info.eastSideWires, wireIndex);
                    }
                }
            }

            // INTF_* tiles serve only one side of their INT tile (see SITE_TILE_TYPE_ON_EAST_SIDE), and their BNODEs and
            // CNODEs are sided local wires. Unlike those in CLE_BC_CORE tiles, CNODEs in INTF_* tiles (on xcv80, at
            // least) do not drive any INODEs, only CTRL pins
            for (TileTypeEnum tte : SITE_TILE_TYPE_ON_EAST_SIDE.keySet()) {
                Tile intfTile = device.getArbitraryTileOfType(tte);
                if (intfTile == null) continue;
                for (int wireIndex = 0; wireIndex < intfTile.getWireCount(); wireIndex++) {
                    IntentCode baseIntentCode = intfTile.getWireIntentCode(wireIndex);
                    if (baseIntentCode != IntentCode.NODE_INTF_BNODE && baseIntentCode != IntentCode.NODE_INTF_CNODE) {
                        continue;
                    }
                    TileTypeInfo info = getOrCreateTileTypeInfo(tte);
                    info.sidedLocalWires = TileTypeInfo.set(info.sidedLocalWires, wireIndex);
                    if (baseIntentCode != IntentCode.NODE_INTF_CNODE) {
                        continue;
                    }
                    Node baseNode = Node.getNode(intfTile, wireIndex);
                    assert(baseNode.getTile() == intfTile);
                    boolean ctrlOnly = true;
                    for (Node downhill : baseNode.getAllDownhillNodes()) {
                        if (downhill.getIntentCode() != IntentCode.NODE_INTF_CTRL) {
                            ctrlOnly = false;
                            break;
                        }
                    }
                    if (ctrlOnly) {
                        info.intfCtrlOnlyWires = TileTypeInfo.set(info.intfCtrlOnlyWires, wireIndex);
                    }
                }
            }
        }

        if (lutRoutethru) {
            assert(isUltraScalePlus || isUltraScale);

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
                getOrCreateTileTypeInfo(tileTypeEnum).muxWiresToBlockWhenLutRoutethru = localWires;
            }
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
                wireIndicesLeadingToSLL = new IdentityHashMap<>();
                populateLagunaLookups(device, lagunaTiles, crossingHalfColumns);
            } else if (isVersal) {
                populateSllCrossingHalfColumns(device, crossingHalfColumns);
                boolean anyCrossing = false;
                for (boolean crossing : crossingHalfColumns) {
                    anyCrossing |= crossing;
                }
                // Filled in for each SLL and INT tile when first needed (see getWireIndicesLeadingToSLL())
                wireIndicesLeadingToSLL = anyCrossing ? new ConcurrentHashMap<>() : null;
            } else {
                wireIndicesLeadingToSLL = null;
            }
        } else {
            wireIndicesLeadingToSLL = null;
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
     * Checks whether a (Versal) SLL input drives an SLL that crosses into another SLR: one with a wire in the same
     * column of SLL tiles but in another SLR. This varies from tile to tile, rather than with the wire name (e.g.
     * those in SLL_X16Y335 on xcv80 lead to SLL_X16Y410, except for one that leads to SLL_X16Y260, and none of those
     * in SLL_X15Y335 leave their tile). Walks the SLL's wires: use {@link #wireIndicesLeadingToSLL} instead.
     * @param sllInput NODE_SLL_INPUT node in question.
     * @return True if so.
     */
    boolean isCrossingSLLInput(Node sllInput) {
        Tile tile = sllInput.getTile();
        for (Node ubump : sllInput.getAllDownhillNodes()) {
            assert(ubump.getIntentCode() == IntentCode.NODE_SLL_DATA);
            for (Wire wire : ubump.getAllWiresInNode()) {
                Tile wireTile = wire.getTile();
                if (wireTile.getTileXCoordinate() == tile.getTileXCoordinate() &&
                        wireTile.getSLR().getId() != tile.getSLR().getId()) {
                    // Crossing towards the nearer SLR boundary (see isNorthboundSLLRow())
                    assert((wireTile.getTileYCoordinate() > tile.getTileYCoordinate()) ==
                            isNorthboundSLLRow(tile.getTileYCoordinate()));
                    return true;
                }
            }
        }
        return false;
    }

    /** A (Versal) SLL or INT tile's entry in {@link #wireIndicesLeadingToSLL} when it has none (never modified) */
    private static final BitSet[] NO_WIRE_INDICES_LEADING_TO_SLL = {new BitSet(), new BitSet()};

    /**
     * Gets a tile's entry in {@link #wireIndicesLeadingToSLL}, which must not be null. On Versal, an SLL or INT tile's
     * entry is computed when first needed (see {@link #computeSllLookup(Tile)} and {@link #computeIntLookup(Tile)}).
     * @param tile Tile in question.
     * @return Its two BitSets, or null if it has none (on UltraScale/UltraScale+).
     */
    BitSet[] getWireIndicesLeadingToSLL(Tile tile) {
        if (!isVersal) {
            return wireIndicesLeadingToSLL.get(tile);
        }
        TileTypeEnum tileTypeEnum = tile.getTileTypeEnum();
        assert(tileTypeEnum == TileTypeEnum.SLL || tileTypeEnum == TileTypeEnum.INT);
        // SLL inputs whose SLL crosses SLRs are only found within one SLL length of the SLR boundary that they
        // cross, and so in the half of their SLR nearer to it (see isNorthboundSLLRow()); as are the nodes in INT tiles
        // that lead to them, which are in the same row
        int y = tile.getTileYCoordinate();
        int slr = intYToSLRIndex[y];
        if (isNorthboundSLLRow(y) ? (slr == slrLastIntY.length - 1 || y <= slrLastIntY[slr] - SUPER_LONG_LINE_LENGTH_IN_TILES)
                                  : (slr == 0 || y >= slrFirstIntY[slr] + SUPER_LONG_LINE_LENGTH_IN_TILES)) {
            return NO_WIRE_INDICES_LEADING_TO_SLL;
        }
        if (tileTypeEnum == TileTypeEnum.SLL) {
            return wireIndicesLeadingToSLL.computeIfAbsent(tile, this::computeSllLookup);
        }
        // Only an INT tile with a half on a half column beside an SLL column whose SLLs cross SLRs (see
        // nextCrossingHalfColumn) has a PINBOUNCE that can drive a BNODE leading to one
        int x = tile.getTileXCoordinate();
        if (nextCrossingHalfColumn[2 * x] > 2 * x + 1) {
            return NO_WIRE_INDICES_LEADING_TO_SLL;
        }
        BitSet[] bs = wireIndicesLeadingToSLL.get(tile);
        if (bs == null) {
            // Not computeIfAbsent(), whose mapping function must not itself update the map (as computeIntLookup()
            // does, for the SLL tiles beside this one); another thread may compute the same entry meanwhile
            bs = computeIntLookup(tile);
            BitSet[] prev = wireIndicesLeadingToSLL.putIfAbsent(tile, bs);
            if (prev != null) {
                bs = prev;
            }
        }
        return bs;
    }

    /**
     * Computes a (Versal) INT tile's entry in {@link #wireIndicesLeadingToSLL}: the wire indices of its PINBOUNCEs that
     * drive a BNODE leading to an SLL crossing SLRs (see {@link #computeSllLookup(Tile)}), and of its INODEs that drive
     * such a PINBOUNCE: the sending chain INODE -&gt; PINBOUNCE -&gt; BNODE -&gt; SLL input, whose PINBOUNCE drives the
     * BNODEs of the SLL tile beside its side of its INT tile, in the same row (e.g. INT_X95Y620/INT_NODE_IMUX_ATOM_117_INT_OUT0
     * -&gt; INT_X95Y620/BOUNCE_W28 -&gt; SLL_X95Y620/BNODE_OUTS_E26 -&gt; SLL_X95Y620/LAG_CASCOUT_TXI1 on xcv80). Found by
     * walking uphill from those BNODEs.
     * @param intTile INT tile in question.
     * @return Its two BitSets.
     */
    private BitSet[] computeIntLookup(Tile intTile) {
        BitSet[] bs = null;
        for (int side = 0; side < 2; side++) {
            boolean eastSide = (side == 1);
            Tile sllTile = getSllTileBeside(intTile, eastSide);
            if (sllTile == null) {
                continue;
            }
            BitSet bnodes = getWireIndicesLeadingToSLL(sllTile)[1];
            for (int wireIndex = bnodes.nextSetBit(0); wireIndex >= 0; wireIndex = bnodes.nextSetBit(wireIndex + 1)) {
                Node bnode = Node.getNode(sllTile, wireIndex);
                for (Node bounce : bnode.getAllUphillNodes()) {
                    if (bounce.getTile() != intTile || bounce.getIntentCode() != IntentCode.NODE_PINBOUNCE) {
                        // e.g. a PINBOUNCE of the INT tile on the other side of the SLL tile
                        continue;
                    }
                    assert(isEastSideWire(TileTypeEnum.INT, bounce.getWireIndex()) == eastSide);
                    if (bs == null) {
                        bs = new BitSet[]{new BitSet(), new BitSet()};
                    }
                    if (bs[0].get(bounce.getWireIndex())) {
                        continue;
                    }
                    bs[0].set(bounce.getWireIndex());
                    for (Node inode : bounce.getAllUphillNodes()) {
                        if (inode.getIntentCode() != IntentCode.NODE_INODE) {
                            continue;
                        }
                        assert(inode.getTile() == intTile);
                        bs[0].set(inode.getWireIndex());
                    }
                }
            }
        }
        return (bs != null) ? bs : NO_WIRE_INDICES_LEADING_TO_SLL;
    }

    /**
     * Computes a (Versal) SLL tile's entry in {@link #wireIndicesLeadingToSLL}: the wire indices of its SLL inputs
     * whose SLL crosses into another SLR (see {@link #isCrossingSLLInput(Node)}), and of its BNODEs that drive at
     * least one of those. BNODEs in an SLL tile only drive the SLL inputs of that tile, so all the SLL inputs that
     * such a BNODE leads to are in the same row and cross in the same direction.
     * @param tile SLL tile in question.
     * @return Its two BitSets.
     */
    private BitSet[] computeSllLookup(Tile tile) {
        BitSet[] bs = null;
        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
            if (tile.getWireIntentCode(wireIndex) != IntentCode.NODE_SLL_INPUT) {
                continue;
            }
            Node sllInput = Node.getNode(tile, wireIndex);
            if (sllInput == null || sllInput.getTile() != tile || sllInput.getWireIndex() != wireIndex) {
                // Not the base wire of its node
                continue;
            }
            if (!isCrossingSLLInput(sllInput)) {
                continue;
            }
            if (bs == null) {
                bs = new BitSet[]{new BitSet(), new BitSet()};
            }
            bs[0].set(wireIndex);
            for (Node uphill : sllInput.getAllUphillNodes()) {
                if (uphill.getIntentCode() != IntentCode.NODE_CLE_BNODE) {
                    continue;
                }
                assert(uphill.getTile() == tile);
                bs[1].set(uphill.getWireIndex());
            }
        }
        return (bs != null) ? bs : NO_WIRE_INDICES_LEADING_TO_SLL;
    }

    /**
     * Checks whether a (Versal) node is a BNODE in an SLL tile that drives an SLL input whose SLL crosses into
     * another SLR (see {@link #wireIndicesLeadingToSLL}), and so is of type
     * {@link RouteNodeType#LOCAL_LEADING_TO_SLL} (see RouteNodeInfo.getType()).
     * @param node NODE_CLE_BNODE node in question.
     * @return True if so.
     */
    boolean isBNodeLeadingToSLL(Node node) {
        assert(isVersal && node.getIntentCode() == IntentCode.NODE_CLE_BNODE);
        if (wireIndicesLeadingToSLL == null || node.getTile().getTileTypeEnum() != TileTypeEnum.SLL) {
            return false;
        }
        boolean leads = getWireIndicesLeadingToSLL(node.getTile())[1].get(node.getWireIndex());
        assert(leads == isBNodeLeadingToSLLByWalk(node));
        return leads;
    }

    /**
     * Checks {@link #isBNodeLeadingToSLL(Node)} by walking the node's downhill nodes, without creating any
     * {@link RouteNode}, for use in assertions. Also checks that every SLL input whose SLL crosses SLRs that it
     * drives is in its own tile, and ends there.
     */
    private boolean isBNodeLeadingToSLLByWalk(Node node) {
        if (node.getTile().getTileTypeEnum() != TileTypeEnum.SLL) {
            return false;
        }
        boolean leads = false;
        for (Node downhill : node.getAllDownhillNodes()) {
            if (downhill.getIntentCode() == IntentCode.NODE_SLL_INPUT && isCrossingSLLInput(downhill)) {
                assert(downhill.getTile() == node.getTile());
                // ... and ends there (see RWRoute.evaluateCostAndPush())
                assert(RouteNodeInfo.get(downhill, null).endHalfTileXCoordinate ==
                        2 * RouteNodeInfo.getWestIntTileXCoordinate(node.getTile()) + 1);
                assert(RouteNodeInfo.get(downhill, null).endTileYCoordinate == node.getTile().getTileYCoordinate());
                leads = true;
            }
        }
        return leads;
    }

    /**
     * Checks whether a (Versal) node is a PINBOUNCE in an INT tile that drives a BNODE leading to an SLL crossing into
     * another SLR (see {@link #isBNodeLeadingToSLL(Node)}), or an INODE that drives such a PINBOUNCE (see
     * {@link #wireIndicesLeadingToSLL}), and so is of type {@link RouteNodeType#LOCAL_LEADING_TO_SLL} (see
     * RouteNodeInfo.getType()).
     * @param node NODE_INODE or NODE_PINBOUNCE node in question.
     * @return True if so.
     */
    boolean isINodeOrBounceLeadingToSLL(Node node) {
        assert(isVersal && (node.getIntentCode() == IntentCode.NODE_INODE || node.getIntentCode() == IntentCode.NODE_PINBOUNCE));
        if (wireIndicesLeadingToSLL == null || node.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
            return false;
        }
        BitSet bs = getWireIndicesLeadingToSLL(node.getTile())[0];
        boolean leads = bs.get(node.getWireIndex());
        // Only walk (as a check) where this tile has any such node
        assert(bs.isEmpty() || leads == isINodeOrBounceLeadingToSLLByWalk(node));
        return leads;
    }

    /**
     * Checks {@link #isINodeOrBounceLeadingToSLL(Node)} by walking the node's downhill nodes, without creating any
     * {@link RouteNode}, for use in assertions.
     */
    private boolean isINodeOrBounceLeadingToSLLByWalk(Node node) {
        for (Node downhill : node.getAllDownhillNodes()) {
            IntentCode ic = downhill.getIntentCode();
            if (node.getIntentCode() == IntentCode.NODE_INODE) {
                if (ic == IntentCode.NODE_PINBOUNCE && isINodeOrBounceLeadingToSLLByWalk(downhill)) {
                    return true;
                }
            } else if (ic == IntentCode.NODE_CLE_BNODE && isBNodeLeadingToSLL(downhill)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the (Versal) SLL tile beside one side (half) of an INT tile, in the same row: the reverse of
     * {@link RouteNodeInfo#getWestIntTileXCoordinate(Tile)}.
     * @param intTile INT tile in question.
     * @param eastSide True for the SLL tile to its east, else to its west.
     * @return That SLL tile, or null if there is another INT tile (or none) first.
     */
    private static Tile getSllTileBeside(Tile intTile, boolean eastSide) {
        // On xcv80, for example, SLL_X114Y461 is 3 columns to the east of INT_X113Y461, and 2 to the west of
        // INT_X114Y461
        final int maxColumns = 8;
        for (int dx = 1; dx <= maxColumns; dx++) {
            Tile tile = intTile.getTileNeighbor(eastSide ? dx : -dx, 0);
            if (tile == null) {
                break;
            }
            TileTypeEnum tileTypeEnum = tile.getTileTypeEnum();
            if (tileTypeEnum == TileTypeEnum.SLL) {
                return tile;
            }
            if (tileTypeEnum == TileTypeEnum.INT) {
                break;
            }
        }
        return null;
    }

    /**
     * Checks whether nodes leading to a super long line (see {@link RouteNodeType#LOCAL_LEADING_TO_SLL}) in the given row lead
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

                    // Examine all wires in each Laguna tile. Record those TXOUT, IMUX and INODE uphill of a Super Long
                    // Line that originates in an INT tile
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

                            // The TXOUT itself (LAG_MUX_ATOM_*_TXOUT, of intent NODE_LAGUNA_OUTPUT on UltraScale+ and
                            // INTENT_DEFAULT on UltraScale), keyed by its own Laguna tile. Each super long line here
                            // crosses into the next SLR (towards the boundary nearer to the TXOUTs at either end, which
                            // are in the same row, and so the same direction, as the IMUX that drives them)
                            Tile txOutTile = txOut.getTile();
                            assert(Utils.isLaguna(txOutTile.getTileTypeEnum()));
                            assert(txOutTile.getTileYCoordinate() == imuxTile.getTileYCoordinate());
                            assert(!enableComprehensiveAssertions || txOut.getWireName().matches("LAG_MUX_ATOM_\\d+_TXOUT"));
                            wireIndicesLeadingToSLL.computeIfAbsent(txOutTile, k -> new BitSet[]{new BitSet(), new BitSet()})[0]
                                    .set(txOut.getWireIndex());

                            BitSet[] bs = wireIndicesLeadingToSLL.computeIfAbsent(imuxTile, k -> new BitSet[]{new BitSet(), new BitSet()});
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
                                        populateNeighbourLagunaLookups(intInt, inode.getTile());
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

    /**
     * For the INT tile on the other side of the Laguna column (the west neighbour of an INT tile with a Laguna column),
     * which has no TXOUTs of its own: records a horizontal single from it (in the same row) that drives an INODE leading
     * to that Laguna, and the SDQ nodes that drive such a single and end in that tile, keyed by that tile, as
     * NON_LOCAL_LEADING_TO_SLL. Otherwise, a connection there would see the crossing discount only on reaching the
     * INODE, and would first ride away on vertical wires (each lowering its lower bound more than the step across).
     * (Not its SDQNODE_*_FT* that end in the row above or below, which are one node further from the Laguna.)
     * @param single Node driving an INODE that leads to a Laguna, in another tile.
     * @param inodeTile The INT tile of that INODE.
     */
    private void populateNeighbourLagunaLookups(Node single, Tile inodeTile) {
        Tile singleTile = single.getTile();
        if (single.getIntentCode() != IntentCode.NODE_SINGLE ||
                singleTile.getTileTypeEnum() != inodeTile.getTileTypeEnum() ||
                singleTile.getTileYCoordinate() != inodeTile.getTileYCoordinate() ||
                singleTile.getTileXCoordinate() != inodeTile.getTileXCoordinate() - 1) {
            return;
        }
        BitSet[] bs = wireIndicesLeadingToSLL.computeIfAbsent(singleTile, k -> new BitSet[]{new BitSet(), new BitSet()});
        bs[1].set(single.getWireIndex());
        for (Node sdq : single.getAllUphillNodes()) {
            if (sdq.isTiedToVcc() || sdq.getTile() != singleTile || sdq.getIntentCode() != IntentCode.NODE_LOCAL) {
                continue;
            }
            // Its end tile: the first INT tile among its other wires, if any
            boolean endsInSingleTile = true;
            Wire[] wires = sdq.getAllWiresInNode();
            for (int i = 1; i < wires.length; i++) {
                if (wires[i].getTile().getTileTypeEnum() == singleTile.getTileTypeEnum()) {
                    endsInSingleTile = wires[i].getTile() == singleTile;
                    break;
                }
            }
            if (endsInSingleTile) {
                bs[1].set(sdq.getWireIndex());
            }
        }
    }

    public void initialize() {
    }

    /**
     * Gets what RWRoute knows about the wires of a tile type.
     * @param tileTypeEnum The tile type.
     * @return Its entry in {@link #tileTypeInfos}, or null if it has none (no wire of the type has any of the
     * properties of {@link TileTypeInfo}).
     */
    protected TileTypeInfo getTileTypeInfo(TileTypeEnum tileTypeEnum) {
        return tileTypeInfos.get(tileTypeEnum);
    }

    /**
     * Gets the entry of a tile type in {@link #tileTypeInfos}, creating it if it has none: only to be called during
     * construction (see {@link #tileTypeInfos}), to give a wire of the type one of the properties of
     * {@link TileTypeInfo}.
     * @param tileTypeEnum The tile type.
     * @return Its entry.
     */
    private TileTypeInfo getOrCreateTileTypeInfo(TileTypeEnum tileTypeEnum) {
        return tileTypeInfos.computeIfAbsent(tileTypeEnum, k -> new TileTypeInfo());
    }

    /**
     * Checks if a wire is in one of the wire sets of {@link TileTypeInfo}.
     * @param tileTypeEnum Tile type of the wire.
     * @param wireIndex The (base) wire index.
     * @param wires Gives that set from the entry of the tile type in {@link #tileTypeInfos} (null if no wire of the
     * type is in it).
     * @return False if the tile type has no entry, or the wire is not in the set.
     */
    private boolean isWireIn(TileTypeEnum tileTypeEnum, int wireIndex, Function<TileTypeInfo, BitSet> wires) {
        final TileTypeInfo info = getTileTypeInfo(tileTypeEnum);
        return info != null && TileTypeInfo.get(wires.apply(info), wireIndex);
    }

    /**
     * Checks if a local node serves only one side (half) of its interconnect tile.
     * @param tileTypeEnum Tile type of the node's base wire.
     * @param wireIndex Wire index of the node's base wire.
     * @return True if so.
     */
    public boolean isSidedLocalWire(TileTypeEnum tileTypeEnum, int wireIndex) {
        return isWireIn(tileTypeEnum, wireIndex, info -> info.sidedLocalWires);
    }

    /**
     * Checks if a node ends on the east side of an interconnect tile (otherwise, it is considered to end on the
     * west side): every node of a tile type beside the east side of its INT tile (see
     * {@link #SITE_TILE_TYPE_ON_EAST_SIDE}), or one given by {@link TileTypeInfo#eastSideWires}.
     * @param tileTypeEnum Tile type of the node's base wire.
     * @param wireIndex Wire index of the node's base wire.
     * @return True if so.
     */
    public boolean isEastSideWire(TileTypeEnum tileTypeEnum, int wireIndex) {
        Boolean siteTileOnEastSide = SITE_TILE_TYPE_ON_EAST_SIDE.get(tileTypeEnum);
        if (siteTileOnEastSide != null) {
            return siteTileOnEastSide;
        }
        return isWireIn(tileTypeEnum, wireIndex, info -> info.eastSideWires);
    }

    /**
     * Checks if a node is to be a {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} node (see
     * {@link TileTypeInfo#inheritPrevSideWires}).
     * @param tileTypeEnum Tile type of the node's base wire.
     * @param wireIndex Wire index of the node's base wire.
     * @return True if so.
     */
    public boolean isInheritPrevSideWire(TileTypeEnum tileTypeEnum, int wireIndex) {
        return isWireIn(tileTypeEnum, wireIndex, info -> info.inheritPrevSideWires);
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
     * Places a (base) node on the east side (half) of an INT tile (see {@link TileTypeInfo#eastSideWires}).
     * @param baseNode Base node in question.
     */
    private void setEastSide(Node baseNode) {
        TileTypeInfo info = getOrCreateTileTypeInfo(baseNode.getTile().getTileTypeEnum());
        info.eastSideWires = TileTypeInfo.set(info.eastSideWires, baseNode.getWireIndex());
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
        if (sinkRnode.getTile().getTileTypeEnum() == TileTypeEnum.INT &&
                getTileTypeInfo(TileTypeEnum.INT).ctrlWires.get(sinkRnode.getWireIndex())) {
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
                if (isWireIn(child.getTile().getTileTypeEnum(), child.getWireIndex(), info -> info.intfCtrlOnlyWires)) {
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
                    if (wireIndicesLeadingToSLL == null) {
                        // No Laguna on this device
                        return true;
                    }

                    BitSet[] bs2 = wireIndicesLeadingToSLL.get(child.getTile());
                    if (bs2 == null || !bs2[0].get(child.getWireIndex())) {
                        // Doesn't lead to Laguna -- skip it
                        return true;
                    }
                }
            }
        }

        if (wireIndicesLeadingToSLL == null && Utils.isLaguna(child.getTile().getTileTypeEnum())) {
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
        assert(parentRnode != null || (childRnode.getType().isAnyLocal() &&
                (!isVersal || childRnode.getIntentCode() != IntentCode.NODE_PINFEED)));

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
                            // IMUX_[EW]\\d+ -> LAG_MUX_ATOM_\\d+_TXOUT whose super long line does not cross SLRs (at
                            // the top or bottom edge of the device): those that do are of type LOCAL_LEADING_TO_SLL
                            // (and are accessible by (b) below), and the IMUXes driving these are not in the routing
                            // graph unless LUT routethrus are considered (see isExcluded())
                            assert(lutRoutethru && !parentType.isLocalLeadingToSLL());
                            RouteNode sinkRnode = connection.getSinkRnode();
                            if (!connection.isCrossSLR() ||
                                childRnode.getSLRIndex(this) == sinkRnode.getSLRIndex(this)) {
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
                // UltraScale/UltraScale+ TXOUTs (LAG_MUX_ATOM_*_TXOUT) leading to an SLR crossing
                (!isVersal && Utils.isLaguna(childTileType) && type.isLocalLeadingToSLL()) ||
                (isVersal && EnumSet.of(TileTypeEnum.INTF_LOCF_TR_TILE, TileTypeEnum.INTF_LOCF_BR_TILE, TileTypeEnum.INTF_ROCF_TR_TILE, TileTypeEnum.INTF_ROCF_BR_TILE,
                                        TileTypeEnum.INTF_LOCF_TL_TILE, TileTypeEnum.INTF_LOCF_BL_TILE, TileTypeEnum.INTF_ROCF_TL_TILE, TileTypeEnum.INTF_ROCF_BL_TILE,
                                      TileTypeEnum.CLE_BC_CORE, TileTypeEnum.SLL)
                       .contains(childTileType)) ||
                (isVersal && Utils.isCLB(childTileType) && parentRnode != null && parentRnode.getIntentCode() == IntentCode.NODE_SLL_OUTPUT && childRnode.getIntentCode() == IntentCode.NODE_PINFEED) ||
                // Allow NODE_PINFEEDs in RCLK tiles that are preserved (belonging to a partially routed net)
                (Utils.isClocking(childTileType) && childRnode.getIntentCode() == IntentCode.NODE_PINFEED && isPreserved(childRnode))
        );

        if (lutRoutethru && (isVersal || !type.isLocalLeadingToSLL())) {
            // (a) considering LUT routethrus, other than (on UltraScale/UltraScale+ only) through local nodes leading to
            // an SLL, which can only lead into (or, for TXOUTs, are in) a Laguna tile and never to a LUT input. (On
            // Versal, where LUT routethrus are not yet supported, these include INODEs, PINBOUNCEs and BNODEs in SLL
            // tiles, which also drive IMUXes, other BNODEs or INODEs.)
            return true;
        }

        // (b) needs to cross an SLR and this is a local node leading to an SLL that crosses SLRs (including, on Versal, the
        // INODEs and PINBOUNCEs leading to one and a BNODE in an SLL tile, which can end on the other side of that tile
        // to what drives it, and which, being on the sending side of a chain, is not restricted to the side of the sink
        // by (c); and an UltraScale/UltraScale+ TXOUT, in the Laguna tile in the same row as the IMUX that drives it, and
        // so leading in the same direction). Outside the sink's SLR, these (and the other BNODEs of their SLL tiles;
        // see (b2)) are the only local nodes that such a connection can use on Versal (see below)
        Tile childTile = childRnode.getTile();
        RouteNode sinkRnode = connection.getSinkRnode();
        int childX = childRnode.getEndTileXCoordinate();
        if (type.isLocalLeadingToSLL()) {
            if (connection.isCrossSLR() && childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this)) {
                assert(isVersal ? EnumSet.of(IntentCode.NODE_INODE, IntentCode.NODE_PINBOUNCE, IntentCode.NODE_CLE_BNODE,
                                             IntentCode.NODE_SLL_INPUT).contains(childRnode.getIntentCode())
                                : wireIndicesLeadingToSLL.get(childTile) != null);
                assert(childTile != sinkRnode.getTile());
                if (isNorthboundSLLRow(childTile.getTileYCoordinate()) ? !connection.isCrossSLRnorth() : !connection.isCrossSLRsouth()) {
                    // Does not lead to an SLL in the direction that we want
                    return false;
                }
                assert(getNextCrossingColumn(childX) == childX);
                return true;
            }

            if (!isVersal && Utils.isLaguna(childTileType)) {
                // An UltraScale/UltraScale+ TXOUT (LAG_MUX_ATOM_*_TXOUT, always of this type) that (b) did not
                // accept: its super long line leaves this SLR, which a connection that does not need to cross SLRs
                // from here never wants, whatever its side (it is driven by an IMUX that (c) to (e) below may accept,
                // e.g. one Y +/- 1 from the sink)
                return false;
            }
        } else if (isVersal && type == RouteNodeType.LOCAL && childTileType == TileTypeEnum.SLL &&
                childRnode.getIntentCode() == IntentCode.NODE_CLE_BNODE &&
                connection.isCrossSLR() && childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this)) {
            // (b2) as (b), on Versal, for any other BNODE in an SLL tile whose BNODEs lead to an SLL crossing SLRs in
            // the direction that we want: such a BNODE drives the INODEs on the side of the INT tile beside that SLL
            // tile (at which it ends), and so can still reach one of those (through INODE -> PINBOUNCE -> BNODE, in
            // the same row), whatever the side of the sink (e.g. a CLE output -> SLL_X16Y410/BNODE_OUTS_E3 -> INODE ->
            // BOUNCE_W17 -> SLL_X16Y410/BNODE_OUTS_E1 -> SLL input on xcv80). Being LOCAL rather than
            // LOCAL_LEADING_TO_SLL, it gets no crossing discount itself (see RWRoute.evaluateCostAndPush()). All
            // other BNODEs outside the sink's SLR are rejected below (being outside the sink's row)
            if (getWireIndicesLeadingToSLL(childTile)[1].isEmpty()) {
                return false;
            }
            return isNorthboundSLLRow(childTile.getTileYCoordinate()) ? connection.isCrossSLRnorth()
                                                                      : connection.isCrossSLRsouth();
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
                                // Versal PINFEEDs (only CLE_SLICE[LM]_TOP_*_LAG_*_PIN, downhill of an SLL output in
                                // another SLR to the sink) are only sided for their distance to the sink, not to
                                // restrict access
                                childRnode.getIntentCode() != IntentCode.NODE_PINFEED) {
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

            // In an SLR other than the sink's, a connection that crosses SLRs may only use the local nodes of the sending
            // chain INODE -> PINBOUNCE -> BNODE -> NODE_SLL_INPUT of an SLL that crosses SLRs (of type
            // LOCAL_LEADING_TO_SLL), which (b) above handled, and the other BNODEs of the SLL tiles of those chains,
            // which (b2) above handled: all others are rejected below (being outside the sink's tile and row)
            IntentCode childIntentCode = childRnode.getIntentCode();
            switch (childIntentCode) {
                case NODE_INODE:
                    // Block access to all INODEs outside the sink tile, since NODE_INODE -> NODE_IMUX -> NODE_PINFEED (or NODE_INODE -> NODE_PINBOUNCE)
                    // (other than those that (b) accepted)
                    return false;
                case NODE_CLE_BNODE:
                case NODE_INTF_BNODE:
                case NODE_CLE_CNODE:
                case NODE_INTF_CNODE:
                    // Only allow [BC]NODEs in the sink's row within one half tile of the sink. Each is placed at the
                    // half of the INT tile whose INODEs it drives (see RouteNodeInfo.get()), so with (c) this admits
                    // only those driving the INODEs of the sink tile's half
                    if (childTile.getTileYCoordinate() != sinkTile.getTileYCoordinate() ||
                        Math.abs(childRnode.getEndHalfTileXCoordinate() - sinkRnode.getEndHalfTileXCoordinate()) > 1) {
                        assert(parentRnode == null || parentRnode.getIntentCode() != IntentCode.NODE_INODE);
                        return false;
                    }
                    return true;
                case NODE_PINBOUNCE:
                    // PINBOUNCEs are only accessible through an INODE, so arriving here means that this must be a
                    // Versal inter-SLR connection, at an INODE leading to an SLL crossing SLRs; but this PINBOUNCE
                    // does not lead to one (those that do were handled by (b) above)
                    assert(connection.isCrossSLR() &&
                            childRnode.getSLRIndex(this) != sinkRnode.getSLRIndex(this));
                    assert(!type.isLocalLeadingToSLL());
                    return false;
                case NODE_PINFEED:
                    if (connection.isCrossSLR()) {
                        // Allow NODE_SLL_OUTPUT -> NODE_PINFEED
                        return parentRnode.getIntentCode() == IntentCode.NODE_SLL_OUTPUT;
                    }
                    // Expected to be projected away
                    throw new RuntimeException("ERROR: Unexpected NODE_PINFEED: " + childRnode);
                case NODE_IMUX:
                    // IMUXes that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph unless
                    // LUT routethrus are enabled (which would have already returned true above)
                case NODE_CLE_CTRL:
                case NODE_INTF_CTRL:
                    // CTRL pins that are not our target EXCLUSIVE_SINK will have been isExcluded() from the graph
                    throw new RuntimeException("ERROR: Unexpected " + childIntentCode + ": " + childRnode);
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

        if (isWireIn(tail.getTile().getTileTypeEnum(), tail.getWireIndex(),
                info -> info.muxWiresToBlockWhenLutRoutethru)) {
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
