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
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
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
     * Number of columns of tiles in the device: the unique addresses (indices into {@link #nodesMap}) of two tiles in
     * the same column differ by this times the number of rows between them (as checked by
     * {@link #buildSinkDriverTables(Device)} for each sink driver in another tile)
     */
    private final int deviceColumns;

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
         * Sink wires (see {@link RouteNodeGraph#SINK_INTENT_CODES}) all of whose drivers are given by
         * {@link #sinkDriverWires} (see {@link RouteNodeGraph#buildSinkDriverTables(Device)}): those whose drivers
         * (direct uphill nodes, other than those tied to VCC and, for a Versal NODE_SLL_INPUT, its NODE_SLL_OUTPUTs,
         * which RWRoute.setFinishCosts() ignores) are all INODEs, GNODEs, BNODEs or CNODEs, which it may mark.
         */
        BitSet markableSinkWires;

        /**
         * Indexed by sink wire (in {@link #markableSinkWires}, else null): the base wire indices of its drivers, in the
         * order that Node.getAllUphillNodes() gives them (less repeats), so that RWRoute.setFinishCosts() can find
         * their rnodes without asking the device.
         */
        int[][] sinkDriverWires;

        /**
         * Indexed by sink wire (in {@link #markableSinkWires}): how many device rows below (positive) or above
         * (negative) the sink's tile, in the same column, the tile of each of its drivers is; null where they are all
         * in the sink's tile. Holds only for a sink in one of {@link #cleanRows}.
         */
        int[][] sinkDriverRowOffsets;

        /**
         * Device rows in which every tile of this type has a tile of this type directly above and below it; null
         * unless some sink wire of this type has a driver in another tile (UltraScale/UltraScale+ INT, whose
         * feedthrough INODEs drive sinks in the INT tile above or below). Only in these rows do all sinks of a wire
         * have the same drivers relative to their tile (as found for every sink of xcvu9p, xcku115, xcvu3p, xcku040,
         * xcvu440 and xczu7ev): beside a row of another type (an RCLK row between clock regions, or INT_TERM rows at
         * an SLR boundary or the edge of the device), a feedthrough driver is two rows away, or is replaced by one in
         * the sink's own tile (as seen on xcvu9p and xcku115).
         */
        BitSet cleanRows;

        /**
         * Gets the drivers of a sink of this tile type, if given by {@link #sinkDriverWires}.
         * @param sinkWire The sink's (base) wire index.
         * @param sinkRow The device row of the sink's tile.
         * @param otherTilesAllowed False if drivers in another tile may not be given (see
         * {@link RouteNodeGraph#getSinkDriverWires(RouteNode)}).
         * @return Base wire indices of its drivers, or null if they are not given.
         */
        int[] getSinkDriverWires(int sinkWire, int sinkRow, boolean otherTilesAllowed) {
            if (!get(markableSinkWires, sinkWire)) {
                return null;
            }
            if (sinkDriverRowOffsets[sinkWire] != null && (!otherTilesAllowed || !cleanRows.get(sinkRow))) {
                return null;
            }
            return sinkDriverWires[sinkWire];
        }

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
     * RWRoute routes from (CLE outputs, and the INT_INTERFACE_* / INT_INTF_* / INTF_* / BLI_CLE_* / Laguna nodes that
     * all other site outputs are projected onto; see RouterHelper.projectOutputPinToINTNode()) enter that INT tile on,
     * and drive nodes on (other than UltraScale INT_NODE_QUAD_LONG_* / QLND*, which serve both sides): as found for
     * every such node on xcku040, xcvu3p, xcvu9p, xcu50 and xczu7ev, and in samples of each type on xcku115, xcvu440,
     * xcvu095, xcvu13p, xczu9eg, xcu250, xcvu37p, xcvc1902 and xcv80; and, for every such node of every type here,
     * from both the sides of the INT nodes that it drives and the LOGIC_OUTS_[EW]* wire in it, on xcsu35p, xcvu9p,
     * xcvu440, xcku115, xcve2302, xcvp1902 and xc2ve3858. Neither the suffix of a type's name (CLEM_R and CLE_M_R are
     * beside the west side) nor, on Versal, the LOGIC_OUTS_[EW]* wire in the node (most CLE_W_CORE outputs on xcv80
     * contain a LOGIC_OUTS_W*) always says which. Every node of these types is placed on that side (see
     * isEastSideWire()). Every site output (other than of leaf clock buffers, which only the clock router routes from)
     * is projected onto a node whose side is known on xcku035, xcku040, xcku060, xcku115, xcvu080, xcvu095, xcvu440
     * (UltraScale); xcau10p, xcau15p, xcku9p, xcsu35p, xck26, xczu1cg, xczu3eg, xczu4ev, xczu7ev, xczu9eg, xcvu3p,
     * xcvu5p, xcvu7p, xcvu9p, xcvu13p, xcvu19p, xcu50, xcu250, xcvu37p (UltraScale+); xcvc1502, xcvc1902, xcvc2802,
     * xcve2002, xcve2302, xcvm2152, xcvp1002, xcvp1202, xcvp1502, xcvp1802, xcvp1902, xcv80 and xc2ve3858 (Versal).
     * A net's source of a tile type that is not listed here, and that has no east-side wires of its own, is placed on
     * the west side, with a warning (see checkSourceSideKnown()).
     */
    protected static final Map<TileTypeEnum, Boolean> SITE_TILE_TYPE_ON_EAST_SIDE = new EnumMap<>(TileTypeEnum.class);
    static {
        for (TileTypeEnum tte : EnumSet.of(
                // UltraScale and UltraScale+
                TileTypeEnum.CLEL_R,
                // UltraScale+
                TileTypeEnum.INT_INTF_R, TileTypeEnum.INT_INTF_R_PCIE4, TileTypeEnum.INT_INTF_R_TERM_GT,
                TileTypeEnum.INT_INTF_RIGHT_TERM_IO, TileTypeEnum.INT_INTF_RIGHT_TERM_HDIO_FT,
                // UltraScale
                TileTypeEnum.INT_INTERFACE_R, TileTypeEnum.INT_INTERFACE_PCIE_R, TileTypeEnum.INT_INTERFACE_GT_R,
                // Versal
                TileTypeEnum.CLE_W_CORE, TileTypeEnum.BLI_CLE_BOT_CORE, TileTypeEnum.BLI_CLE_TOP_CORE,
                TileTypeEnum.INTF_LOCF_TR_TILE, TileTypeEnum.INTF_LOCF_BR_TILE,
                TileTypeEnum.INTF_ROCF_TR_TILE, TileTypeEnum.INTF_ROCF_BR_TILE,
                TileTypeEnum.INTF_GT_TR_TILE, TileTypeEnum.INTF_GT_BR_TILE,
                TileTypeEnum.INTF_HB_LOCF_TR_TILE, TileTypeEnum.INTF_HB_LOCF_BR_TILE,
                TileTypeEnum.INTF_HB_ROCF_TR_TILE, TileTypeEnum.INTF_HB_ROCF_BR_TILE,
                TileTypeEnum.INTF_HDIO_LOCF_TR_TILE, TileTypeEnum.INTF_HDIO_LOCF_BR_TILE,
                TileTypeEnum.INTF_HDIO_ROCF_TR_TILE, TileTypeEnum.INTF_HDIO_ROCF_BR_TILE,
                // Versal Gen 2
                TileTypeEnum.CLE_W_VR_CORE)) {
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
                // UltraScale+ Laguna (onto whose nodes RX and TX flop outputs, RXQ* and TXQ*, are projected), beside
                // the west side of the INT tile to their east
                TileTypeEnum.LAG_LAG,
                // UltraScale
                TileTypeEnum.CLE_M, TileTypeEnum.CLE_M_R,
                TileTypeEnum.INT_INTERFACE_L, TileTypeEnum.INT_INTERFACE_PCIE_L,
                TileTypeEnum.INT_INT_INTERFACE_XIPHY_FT, TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_FT,
                // UltraScale Laguna (as for UltraScale+, but beside the INT tile with the same X coordinate)
                TileTypeEnum.LAGUNA_TILE,
                // Versal
                TileTypeEnum.CLE_E_CORE, TileTypeEnum.BLI_CLE_BOT_CORE_MY, TileTypeEnum.BLI_CLE_TOP_CORE_MY,
                TileTypeEnum.INTF_LOCF_TL_TILE, TileTypeEnum.INTF_LOCF_BL_TILE,
                TileTypeEnum.INTF_ROCF_TL_TILE, TileTypeEnum.INTF_ROCF_BL_TILE,
                TileTypeEnum.INTF_GT_TL_TILE, TileTypeEnum.INTF_GT_BL_TILE,
                TileTypeEnum.INTF_HB_LOCF_TL_TILE, TileTypeEnum.INTF_HB_LOCF_BL_TILE,
                TileTypeEnum.INTF_HB_ROCF_TL_TILE, TileTypeEnum.INTF_HB_ROCF_BL_TILE,
                TileTypeEnum.INTF_HDIO_LOCF_TL_TILE, TileTypeEnum.INTF_HDIO_ROCF_TL_TILE,
                TileTypeEnum.INTF_CFRM_TL_TILE, TileTypeEnum.INTF_CFRM_BL_TILE,
                TileTypeEnum.INTF_PSS_TL_TILE, TileTypeEnum.INTF_PSS_BL_TILE,
                // Versal Gen 2
                TileTypeEnum.CLE_E_VR_CORE)) {
            SITE_TILE_TYPE_ON_EAST_SIDE.put(tte, false);
        }
    }

    /**
     * Tile types of source nodes whose side is unknown that have already been warned about (see
     * {@link #checkSourceSideKnown(Node)}).
     */
    private final Set<TileTypeEnum> sourceTileTypesOfUnknownSide = EnumSet.noneOf(TileTypeEnum.class);

    /** Flag for whether design targets the Versal series */
    protected final boolean isVersal;

    /** Map of the TileTypeEnum to the highest base wire index */
    protected final Map<TileTypeEnum, Integer> highestBaseWireIndex;

    /** Present congestion factor, as last set by setPresentCongestionFactor() */
    protected float presentCongestionFactor;

    protected static int getTileCount(Design design) {
        Device device = design.getDevice();
        return device.getColumns() * device.getRows();
    }

    protected final Set<Tile> allowedTiles;

    /**
     * The SDQ nodes of an INT tile and their child wires, for RWRoute's SDQ lookahead (see
     * RWRoute.getSdqLookaheadFloor()), which keys an SDQ node by the least lower bound that one of its child wires
     * could have. Indexed by the base wire index of a node in an INT tile: null unless that node is an SDQ node (one
     * whose children are all singles, doubles, quads or longs: INT_NODE_SDQ_* on UltraScale+, NODE_SDQNODEs on
     * Versal), else its child wires, {@link #SDQ_LOOKAHEAD_FIELDS} ints each (see SDQ_LOOKAHEAD_*), less those that go
     * nowhere or that RWRoute never enters. Measured from one interior INT tile (see
     * {@link #buildSdqLookahead(Tile[][])}) and taken to hold for every INT tile, so that a child wire need not exist
     * (as an rnode) to be priced; null if no INT tile was found to measure. Kept in its own field rather than in the
     * INT entry of {@link #tileTypeInfos}, as it is read on every push of a node in an INT tile.
     * Note: not every INT tile is like the measured one. For example, on xcv80, in INT_X16Y167 the OUT_WW4_E_BEG* child
     * wires of 64 of its 256 SDQ nodes end one INT tile west rather than the two that the table gives, so that a floor
     * there can be above the lower bound of that child wire. And on xcvu3p, in the bottom row of INT tiles (e.g.
     * INT_X63Y0 and INT_X64Y0), the feedthrough SDQ nodes SDQNODE_[EW]_0_FT1 drive wires of their own tile (NN1, SS1,
     * WW2 and WW4_[EW]_BEG0) rather than the ones of the row below that the table gives, which does not exist there.
     * Nor, likewise, are the INT tiles near the edge of an SLR, where wires that would cross it are cut short or
     * missing: their tables differ from the measured one in the first and last row of each SLR (on every device
     * checked: xcku040, xcku115, xcvu440, xcvu3p, xcvu9p, xcvc1902, xcv80 and xcvp1802), and up to 16 rows in on
     * UltraScale or 4 on UltraScale+ and Versal. E.g. on xcku115, in SLR1's bottom row (INT_X13Y300), EE1_W_0_FTS ends
     * in its own row, where the table gives a row south (in SLR0, so that SDQ lookahead skips it and its SDQ node's
     * floor is above that wire's lower bound; e.g. TestRWRoute.testCostWithinLowerBoundNonTimingDriven()'s xcku115
     * row SLICE_X20Y240.A_O -&gt; SLICE_X20Y300.A6 costs 0.2 above its bound for it). Giving the first and last
     * row of each SLR tables of their own was measured to fix that, with little effect on routing elsewhere, and was not
     * done.
     */
    protected final int[][] sdqLookahead;
    /**
     * Fields of a child wire in {@link #sdqLookahead}: the offset in INT tile X and Y of the tile of its base wire, and
     * that base wire's index (which identify it, so that tables measured from different tiles can be compared); its
     * nominal wirelength in half tiles; the offset in half tiles and rows of where it ends (relative to twice the SDQ
     * node's tile X, and its tile Y); 1 if it is of type {@link RouteNodeType#NON_LOCAL_INHERIT_PREV_SIDE} (it then
     * ends on the side of its parent), else 0; and its base cost (as float bits).
     */
    public static final int SDQ_LOOKAHEAD_TILE_X = 0;
    public static final int SDQ_LOOKAHEAD_TILE_Y = 1;
    public static final int SDQ_LOOKAHEAD_WIRE = 2;
    public static final int SDQ_LOOKAHEAD_WIRELENGTH = 3;
    public static final int SDQ_LOOKAHEAD_END_HALF_X = 4;
    public static final int SDQ_LOOKAHEAD_END_Y = 5;
    public static final int SDQ_LOOKAHEAD_INHERIT_SIDE = 6;
    public static final int SDQ_LOOKAHEAD_BASE_COST = 7;
    public static final int SDQ_LOOKAHEAD_FIELDS = 8;

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
        // Whether some row of INT tiles has tiles of more than one SLR: on xcvp1902, whose four SLRs each have their own
        // tile coordinates, intYToSLRIndex only gives one of the SLRs of each row
        boolean slrsShareRows = false;
        for (int y = 0; y < intTiles.length; y++) {
            Tile[] intTilesAtY = intTiles[y];
            int rowSLR = -1;
            for (Tile tile : intTilesAtY) {
                if (tile != null) {
                    int slr = tile.getSLR().getId();
                    if (rowSLR == -1) {
                        rowSLR = slr;
                        intYToSLRIndex[y] = slr;
                        slrFirstIntY[slr] = Math.min(slrFirstIntY[slr], y);
                        slrLastIntY[slr] = Math.max(slrLastIntY[slr], y);
                        if (device.getNumOfSLRs() == 1) {
                            break;
                        }
                    } else if (slr != rowSLR) {
                        slrsShareRows = true;
                        break;
                    }
                }
            }
        }
        for (int slr = 1; slr < slrFirstIntY.length; slr++) {
            // SLR indices are expected to increase with Y
            assert(slrsShareRows || slrFirstIntY[slr] > slrFirstIntY[slr - 1]);
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

            // Find an INT tile adjacent to each type of tile hosting CLE [BC]NODEs (see BC_NODE_TILE_TYPES), since
            // Versal devices may contain AIEs on their northern edge. Some devices (e.g. xcvp1902, where all such tiles
            // are SLL2) have no CLE_BC_CORE tiles at all.
            intTilesToExamine = new ArrayList<>(BC_NODE_TILE_TYPES.size());
            for (TileTypeEnum bcTileType : BC_NODE_TILE_TYPES) {
                Tile tile = getIntTileBeside(device, bcTileType);
                if (tile != null) {
                    intTilesToExamine.add(tile);
                }
            }
            if (intTilesToExamine.isEmpty()) {
                throw new RuntimeException("ERROR: No INT tile beside a tile of type " + BC_NODE_TILE_TYPES +
                        " found on device " + device.getName());
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
            // CTRL_[LR]_B* pins in BC_NODE_TILE_TYPES tiles are reachable from the [BC]NODEs serving the INT tiles
            // on both sides, but each feeds a CLE core tile on one side only: CTRL_L_B* feeds the CLE_W_CORE beside
            // the east side of the INT tile to the west, CTRL_R_B* the CLE_E_CORE beside the west side of the INT tile
            // to the east (with RouteNodeInfo placing both pins at their CLE core tile)
            for (TileTypeEnum tte : BC_NODE_TILE_TYPES) {
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
                // Filled in for each SLL and INT tile when first needed (see getWireIndicesLeadingToSLL())
                wireIndicesLeadingToSLL = populateSllCrossingHalfColumns(device, crossingHalfColumns) ?
                        new ConcurrentHashMap<>() : null;
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

        String pblockString = config.getPBlock();
        if (pblockString != null) {
            PBlock pblock = new PBlock(design.getDevice(), pblockString);
            allowedTiles = Collections.newSetFromMap(new IdentityHashMap<>());
            allowedTiles.addAll(pblock.getAllTiles());
        } else {
            allowedTiles = null;
        }

        deviceColumns = device.getColumns();
        buildSinkDriverTables(device);

        sdqLookahead = buildSdqLookahead(intTiles);
    }

    /**
     * Builds {@link #sdqLookahead} from one interior INT tile, whose child wires are not cut short by the edge of the
     * device or an SLR boundary: of up to seven candidates, the first INT tile at or after 1/8, 2/8, ..., 7/8 of the
     * width of the middle row of SLR 0, the table that most of them share (the first such, on a tie), so that one
     * beside an unusual column is outvoted.
     * @param intTiles INT tiles of the device, as given by Device.getTilesByRootName("INT").
     * @return The table, or null if no candidate was found.
     */
    private int[][] buildSdqLookahead(Tile[][] intTiles) {
        final int midY = (slrFirstIntY[0] + slrLastIntY[0]) / 2;
        final Tile[] row = intTiles[midY];
        final int candidates = 7;
        List<int[][]> tables = new ArrayList<>();
        int bestCount = 0;
        int bestIndex = -1;
        for (int k = 1; k <= candidates; k++) {
            Tile tile = null;
            for (int x = k * row.length / (candidates + 1); x < row.length && tile == null; x++) {
                if (row[x] != null && row[x].getTileTypeEnum() == TileTypeEnum.INT) {
                    tile = row[x];
                }
            }
            if (tile == null || tile.getTileXCoordinate() >= row.length || row[tile.getTileXCoordinate()] != tile ||
                    tile.getTileYCoordinate() != midY) {
                // None, or not indexed by its own INT tile coordinates
                continue;
            }
            int[][] table = measureSdqLookahead(tile);
            int count = 1;
            for (int[][] other : tables) {
                if (Arrays.deepEquals(table, other)) {
                    count++;
                }
            }
            tables.add(table);
            if (count > bestCount) {
                bestCount = count;
                bestIndex = tables.size() - 1;
            }
        }
        return (bestIndex < 0) ? null : tables.get(bestIndex);
    }

    /**
     * Measures the SDQ nodes of one INT tile and their child wires (see {@link #sdqLookahead}).
     * @param tile The INT tile.
     * @return Its table.
     */
    private int[][] measureSdqLookahead(Tile tile) {
        int[][] table = new int[tile.getWireCount()][];
        final int x2 = 2 * tile.getTileXCoordinate();
        final int y = tile.getTileYCoordinate();
        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
            Node node = Node.getNode(tile, wireIndex);
            if (node == null || !node.getTile().equals(tile) || node.getWireIndex() != wireIndex ||
                    RouteNode.isSingleDoubleQuadLong(node.getIntentCode())) {
                // Not a base wire, or a wire itself
                continue;
            }
            List<Node> downhill = node.getAllDownhillNodes();
            if (downhill.isEmpty()) {
                continue;
            }
            int[] entry = new int[SDQ_LOOKAHEAD_FIELDS * downhill.size()];
            int n = 0;
            boolean onlyWires = true;
            for (Node child : downhill) {
                if (!RouteNode.isSingleDoubleQuadLong(child.getIntentCode())) {
                    onlyWires = false;
                    break;
                }
                Tile childTile = child.getTile();
                if (childTile.getTileTypeEnum() != TileTypeEnum.INT || child.getAllDownhillPIPs().isEmpty()) {
                    // A dead end (e.g. a Versal double with only one wire)
                    continue;
                }
                // A throwaway RouteNode (not in nodesMap), for its base cost, end and type as RWRoute sees them
                RouteNode rnode = new RouteNode(this, child, null);
                if (rnode.getType() == RouteNodeType.INACCESSIBLE) {
                    continue;
                }
                assert(n % SDQ_LOOKAHEAD_FIELDS == 0);
                entry[n + SDQ_LOOKAHEAD_TILE_X] = childTile.getTileXCoordinate() - tile.getTileXCoordinate();
                entry[n + SDQ_LOOKAHEAD_TILE_Y] = childTile.getTileYCoordinate() - y;
                entry[n + SDQ_LOOKAHEAD_WIRE] = child.getWireIndex();
                entry[n + SDQ_LOOKAHEAD_WIRELENGTH] = rnode.getHalfTileWirelength();
                entry[n + SDQ_LOOKAHEAD_END_HALF_X] = rnode.getEndHalfTileXCoordinate() - x2;
                entry[n + SDQ_LOOKAHEAD_END_Y] = rnode.getEndTileYCoordinate() - y;
                entry[n + SDQ_LOOKAHEAD_INHERIT_SIDE] =
                        (rnode.getType() == RouteNodeType.NON_LOCAL_INHERIT_PREV_SIDE) ? 1 : 0;
                entry[n + SDQ_LOOKAHEAD_BASE_COST] = Float.floatToIntBits(rnode.getBaseCost());
                n += SDQ_LOOKAHEAD_FIELDS;
            }
            if (onlyWires && n > 0) {
                table[wireIndex] = Arrays.copyOf(entry, n);
            }
        }
        return table;
    }

    /**
     * Gets the child wires of an SDQ node, as listed in {@link #sdqLookahead}.
     * @param rnode The node in question.
     * @return Its entry, or null if it is not an SDQ node of an INT tile (or there is no table).
     */
    public int[] getSdqLookahead(RouteNode rnode) {
        final int[][] table = sdqLookahead;
        if (table == null || rnode.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
            return null;
        }
        final int wireIndex = rnode.getWireIndex();
        return wireIndex < table.length ? table[wireIndex] : null;
    }

    /**
     * Intent codes of the sinks whose drivers RWRoute.setFinishCosts() may mark (and so that the tables of
     * {@link #buildSinkDriverTables(Device)} cover): those of the nodes that RouterHelper.projectInputPinToINTNode()
     * gives as sinks, other than the few (e.g. UltraScale NODE_SINGLE wires) whose drivers are never all markable
     */
    private static final Set<IntentCode> SINK_INTENT_CODES = EnumSet.of(
            IntentCode.NODE_PINFEED,    // UltraScale/UltraScale+ IMUX_* and CTRL_*
            IntentCode.NODE_PINBOUNCE,  // BOUNCE_* and BYPASS_*
            IntentCode.NODE_IMUX,       // Versal IMUX_B_*
            IntentCode.NODE_CLE_CTRL,   // Versal CTRL_[LR]_B*
            IntentCode.NODE_INTF_CTRL,  // Versal INTF_IRI*
            IntentCode.NODE_SLL_INPUT); // Versal

    /**
     * Checks if a driver of a sink could be marked by RWRoute.setFinishCosts() once it has an rnode of the type that
     * it is created with (see RWRoute.isMarkableDriver() and RouteNodeInfo.getType()): a Versal INODE, BNODE or CNODE,
     * or an UltraScale/UltraScale+ NODE_LOCAL that is given a LOCAL_* type (an INODE or a GNODE), in a tile whose type
     * is not excluded (as setFinishCosts() requires of a driver that it creates).
     * @param driver The driver in question.
     * @return True if so.
     */
    private boolean isMarkableSinkDriver(Node driver) {
        if (isExcludedTile(driver)) {
            return false;
        }
        switch (driver.getIntentCode()) {
            case NODE_INODE:
            case NODE_CLE_BNODE:
            case NODE_CLE_CNODE:
            case NODE_INTF_BNODE:
            case NODE_INTF_CNODE:
                return true;
            case NODE_LOCAL:
                return isWireIn(driver.getTile().getTileTypeEnum(), driver.getWireIndex(), info -> info.localWires);
            default:
                return false;
        }
    }

    /**
     * Builds the tables of sink drivers ({@link TileTypeInfo#markableSinkWires} and those that it refers to) of each
     * tile type that a sink with markable drivers may be in (those not excluded, see {@link #allowedTileEnums}), so
     * that RWRoute.setFinishCosts() need not ask the device for the drivers of each connection's sinks each time it
     * is routed. All tiles of a type have the same drivers for each sink wire, relative to their tile (as found for
     * every sink of xcvu9p, xcku115, xcvu3p, xcku040, xcvu440, xczu7ev, xcv80 and xcvc1902), except beside a row of
     * another type where a driver is in another tile (see {@link TileTypeInfo#cleanRows}): so each type's tables are
     * built from one of its tiles in a clean row (see {@link #findSinkDriverTableTile(Tile[][], TileTypeEnum)}), not
     * from Device.getArbitraryTileOfType(), which gives a tile at the edge of the device (whose tables would differ
     * from those of most of its tiles on xcvu9p and xcku115).
     * A driver in another tile is allowed only in the same column, at a unique address that differs by a whole number
     * of rows (see {@link #deviceColumns}); any other makes its sink wire unmarkable. A type with no markable sink
     * wire is given no tables, and so no entry in {@link #tileTypeInfos} if it has none already.
     * @param device The device.
     */
    private void buildSinkDriverTables(Device device) {
        final Tile[][] tiles = device.getTiles();
        for (TileTypeEnum tte : allowedTileEnums) {
            if (device.getArbitraryTileOfType(tte) == null) {
                continue;
            }
            final Tile tableTile = findSinkDriverTableTile(tiles, tte);
            // Its entry, once it is found to have a markable sink wire
            TileTypeInfo info = null;
            final int wireCount = tableTile.getWireCount();
            boolean anyRowOffsets = false;
            for (int wireIndex = 0; wireIndex < wireCount; wireIndex++) {
                final IntentCode sinkIntentCode = tableTile.getWireIntentCode(wireIndex);
                if (!SINK_INTENT_CODES.contains(sinkIntentCode)) {
                    continue;
                }
                final Node sink = Node.getNode(tableTile, wireIndex);
                if (sink == null || sink.getTile() != tableTile || sink.getWireIndex() != wireIndex) {
                    // Not a base wire
                    continue;
                }
                assert(sink.getIntentCode() == sinkIntentCode);
                final List<Node> uphills = sink.getAllUphillNodes();
                int[] driverWires = new int[uphills.size()];
                int[] driverRowOffsets = new int[uphills.size()];
                int numDrivers = 0;
                boolean markable = true;
                for (Node uphill : uphills) {
                    if (uphill.isTiedToVcc()) {
                        continue;
                    }
                    if (isVersal && uphill.getIntentCode() == IntentCode.NODE_SLL_OUTPUT &&
                            sinkIntentCode == IntentCode.NODE_SLL_INPUT) {
                        // Excluded from the routing graph (see isExcluded())
                        continue;
                    }
                    final Tile tile = uphill.getTile();
                    final int rowOffset = tile.getRow() - tableTile.getRow();
                    if (!isMarkableSinkDriver(uphill) || tile.getColumn() != tableTile.getColumn() ||
                            tile.getUniqueAddress() - tableTile.getUniqueAddress() != rowOffset * deviceColumns) {
                        markable = false;
                        break;
                    }
                    final int driverWire = uphill.getWireIndex();
                    boolean repeat = false;
                    for (int i = 0; i < numDrivers && !repeat; i++) {
                        repeat = driverWires[i] == driverWire && driverRowOffsets[i] == rowOffset;
                    }
                    if (repeat) {
                        // setFinishCosts() changes nothing on meeting a driver again (it is then a target, not
                        // pushable, or no cheaper than itself)
                        continue;
                    }
                    driverWires[numDrivers] = driverWire;
                    driverRowOffsets[numDrivers] = rowOffset;
                    numDrivers++;
                }
                if (!markable || numDrivers == 0) {
                    continue;
                }
                if (info == null) {
                    info = getOrCreateTileTypeInfo(tte);
                    assert(info.markableSinkWires == null);
                    info.markableSinkWires = new BitSet();
                    info.sinkDriverWires = new int[wireCount][];
                    info.sinkDriverRowOffsets = new int[wireCount][];
                }
                info.markableSinkWires.set(wireIndex);
                info.sinkDriverWires[wireIndex] = Arrays.copyOf(driverWires, numDrivers);
                for (int i = 0; i < numDrivers; i++) {
                    if (driverRowOffsets[i] != 0) {
                        info.sinkDriverRowOffsets[wireIndex] = Arrays.copyOf(driverRowOffsets, numDrivers);
                        anyRowOffsets = true;
                        break;
                    }
                }
            }
            if (anyRowOffsets) {
                info.cleanRows = new BitSet();
                for (int row = 0; row < tiles.length; row++) {
                    if (isCleanRow(tiles, row, tte)) {
                        info.cleanRows.set(row);
                    }
                }
            }
        }
    }

    /**
     * Checks if a device row is clean for a tile type (see {@link TileTypeInfo#cleanRows}).
     * @param tiles The device's tiles, by row and column.
     * @param row The row.
     * @param tte The tile type.
     * @return True if the row has a tile of the type, and every one has a tile of the type directly above and below.
     */
    private static boolean isCleanRow(Tile[][] tiles, int row, TileTypeEnum tte) {
        if (row == 0 || row == tiles.length - 1) {
            return false;
        }
        boolean any = false;
        for (int col = 0; col < tiles[row].length; col++) {
            if (tiles[row][col].getTileTypeEnum() != tte) {
                continue;
            }
            if (tiles[row - 1][col].getTileTypeEnum() != tte || tiles[row + 1][col].getTileTypeEnum() != tte) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Finds the tile that a tile type's sink driver tables are built from (see buildSinkDriverTables()): the first of
     * the type in a clean row, scanning the rows from the middle of the device outwards (only as far as the first
     * such row, usually the middle one), each from its first column; or, if no row is clean (on a type with no sink
     * drivers in another tile, which then needs none), the first of the type in that order.
     * @param tiles The device's tiles, by row and column.
     * @param tte The tile type, which must be in the device.
     * @return That tile.
     */
    private static Tile findSinkDriverTableTile(Tile[][] tiles, TileTypeEnum tte) {
        final int rows = tiles.length;
        Tile first = null;
        for (int d = 0; d < rows; d++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                final int row = rows / 2 + sign * d;
                if (row < 0 || row >= rows || (d == 0 && sign < 0)) {
                    continue;
                }
                for (Tile tile : tiles[row]) {
                    if (tile.getTileTypeEnum() != tte) {
                        continue;
                    }
                    if (isCleanRow(tiles, row, tte)) {
                        return tile;
                    }
                    if (first == null) {
                        first = tile;
                    }
                    break;
                }
            }
        }
        assert(first != null);
        return first;
    }

    /**
     * Gets the drivers of a sink from the tables built by buildSinkDriverTables(), where they give them: if its
     * drivers are all INODEs, GNODEs, BNODEs or CNODEs and, where any is in another tile, the sink is in a clean row
     * (see {@link TileTypeInfo#cleanRows}) and there is no PBlock (as RWRoute.setFinishCosts() treats a driver outside
     * the PBlock differently depending on whether it has an rnode yet).
     * @param sinkRnode The sink.
     * @return Base wire indices of its drivers (with their tiles given by
     * {@link #getSinkDriverRowOffsets(RouteNode)}), in the order that Node.getAllUphillNodes() gives them (less
     * repeats), or null if not given (as for every sink of a tile type with no entry in {@link #tileTypeInfos}).
     */
    public int[] getSinkDriverWires(RouteNode sinkRnode) {
        final Tile tile = sinkRnode.getTile();
        final TileTypeInfo info = getTileTypeInfo(tile.getTileTypeEnum());
        if (info == null) {
            return null;
        }
        return info.getSinkDriverWires(sinkRnode.getWireIndex(), tile.getRow(), allowedTiles == null);
    }

    /**
     * Gets the offsets of the tiles of a sink's drivers (see {@link TileTypeInfo#sinkDriverRowOffsets}), for a sink
     * whose drivers are given by {@link #getSinkDriverWires(RouteNode)}.
     * @param sinkRnode The sink.
     * @return Their offsets in device rows, or null if they are all in the sink's tile.
     */
    public int[] getSinkDriverRowOffsets(RouteNode sinkRnode) {
        return getTileTypeInfo(sinkRnode.getTile().getTileTypeEnum()).sinkDriverRowOffsets[sinkRnode.getWireIndex()];
    }

    /**
     * Gets the rnode of a sink's driver given by {@link #getSinkDriverWires(RouteNode)}, creating it if it has none
     * yet, as RWRoute.setFinishCosts() does for a driver found from the device. Drivers are created only then, not
     * with their sink: an rnode makes its node unavailable to the clock and static net routers (see
     * RWRoute.getGlobalRoutingNodeStatus()), which run after the sinks are created and before signal routing.
     * @param sinkTile The sink's tile.
     * @param rowOffset The offset of the driver's tile, in device rows (see {@link TileTypeInfo#sinkDriverRowOffsets}).
     * @param wireIndex The driver's base wire index.
     * @return Its rnode.
     */
    public RouteNode getOrCreateSinkDriver(Tile sinkTile, int rowOffset, int wireIndex) {
        final RouteNode[] rnodes = nodesMap[sinkTile.getUniqueAddress() + rowOffset * deviceColumns];
        if (rnodes != null && wireIndex < rnodes.length) {
            final RouteNode rnode = rnodes[wireIndex];
            if (rnode != null) {
                return rnode;
            }
        }
        final Tile tile = (rowOffset == 0) ? sinkTile
                : design.getDevice().getTile(sinkTile.getRow() + rowOffset, sinkTile.getColumn());
        final Node node = Node.getNode(tile, wireIndex);
        assert(node.getTile() == tile && node.getWireIndex() == wireIndex);
        return getOrCreate(node);
    }

    /**
     * Marks the INT tile halves beside the (Versal) SLL columns whose SLLs cross SLRs, examining only the first SLR
     * boundary: each SLL tile sits between the east half of the INT tile to its west and the west half of the next.
     * SLLs that do not leave their tile, or that stay within an SLR, do not count; nor do "horizontal" SLLs (to an SLL
     * tile in another column), which are excluded from the routing graph (see {@link #isExcluded(RouteNode, Node)}).
     * @param device Device in question.
     * @param crossingHalfColumns Array, indexed by half-tile X coordinate, to mark.
     * @return True if any were marked.
     */
    private static boolean populateSllCrossingHalfColumns(Device device, boolean[] crossingHalfColumns) {
        final int numUbumps = 6; // UBUMP0..5 in each SLL tile
        boolean any = false;
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
                        any = true;
                    }
                }
            }
        }
        return any;
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
        if (!isSendingSLLRow(tile.getTileYCoordinate())) {
            return NO_WIRE_INDICES_LEADING_TO_SLL;
        }
        if (tileTypeEnum == TileTypeEnum.SLL) {
            // get() first: computeIfAbsent() may lock even when the entry exists
            BitSet[] bs = wireIndicesLeadingToSLL.get(tile);
            return (bs != null) ? bs : wireIndicesLeadingToSLL.computeIfAbsent(tile, this::computeSllLookup);
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
     * walking uphill from those BNODEs. Unlike on UltraScale/UltraScale+, the intra-tile singles and SDQ nodes that
     * drive those INODEs are not included, as experimental data did not support it (see
     * RouteNodeType.NON_LOCAL_LEADING_TO_SLL).
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
     * Checks whether super long lines crossing into another SLR can be taken from the given row: whether it is within
     * one super long line's length of the boundary of its SLR that it is nearer to (see isNorthboundSLLRow()), other
     * than the top and bottom edges of the device. (On UltraScale/UltraScale+, the Laguna tiles whose super long lines
     * cross SLRs are in the clock region next to that boundary, as tall as a super long line is long; see
     * populateLagunaLookups().)
     * @param y INT tile Y coordinate.
     * @return True if so.
     */
    public boolean isSendingSLLRow(int y) {
        int slr = intYToSLRIndex[y];
        return isNorthboundSLLRow(y) ? (slr < slrLastIntY.length - 1 && y > slrLastIntY[slr] - SUPER_LONG_LINE_LENGTH_IN_TILES)
                                     : (slr > 0 && y < slrFirstIntY[slr] + SUPER_LONG_LINE_LENGTH_IN_TILES);
    }

    /**
     * Gets the row from which the super long line that a node on the sending side of a chain leads to is taken (that
     * of the IMUX and Laguna tile of its chain on UltraScale/UltraScale+, or of its SLL tile on Versal):
     * <ul>
     * <li>for an UltraScale/UltraScale+ SDQ node or single (NON_LOCAL_LEADING_TO_SLL), the row of its base tile, by
     * which it is keyed (see populateLagunaLookups()), even if it is a feedthrough that ends in the next row (e.g.
     * WW1_E_7_FT0 on UltraScale+, SDND[NS]W_E_15_FTN on UltraScale);</li>
     * <li>otherwise, the row where the node ends (that of the IMUXes driven by an UltraScale+ feedthrough INODE from
     * the next row), unless super long lines crossing SLRs cannot be taken from there (see isSendingSLLRow()), when it
     * is the row of its base tile: an UltraScale feedthrough INODE (INODE_[12]_[EW]_*_FT[NS], which drives the IMUXes
     * of both its rows) in the row farthest from the SLR boundary from which they can be taken (e.g.
     * INT_X45Y359/INODE_2_W_28_FTN and INT_X37Y240/INODE_2_W_1_FTS on xcku115).</li>
     * </ul>
     * @param rnode A node of type LOCAL_LEADING_TO_SLL or NON_LOCAL_LEADING_TO_SLL.
     * @return INT tile Y coordinate of that row.
     */
    public int getSendingSLLRow(RouteNode rnode) {
        assert(rnode.getType().isLeadingToSLL());
        int y = rnode.getEndTileYCoordinate();
        if (rnode.getType() == RouteNodeType.NON_LOCAL_LEADING_TO_SLL || !isSendingSLLRow(y)) {
            y = rnode.getBeginTileYCoordinate();
        }
        assert(isSendingSLLRow(y));
        return y;
    }

    /**
     * Gets the X coordinate of the next (at or above the given one) INT tile column from which super long lines can
     * cross into another SLR (see {@link #nextCrossingHalfColumn}).
     * @param x INT tile X coordinate.
     * @return That X coordinate, or Integer.MAX_VALUE if there is none.
     */
    private int getNextCrossingColumn(int x) {
        int halfX = nextCrossingHalfColumn[2 * x];
        return (halfX == Integer.MAX_VALUE) ? halfX : halfX >> 1;
    }

    /**
     * Gets the X coordinate of the previous (at or below the given one) INT tile column from which super long lines
     * can cross into another SLR (see {@link #prevCrossingHalfColumn}).
     * @param x INT tile X coordinate.
     * @return That X coordinate, or Integer.MIN_VALUE if there is none.
     */
    private int getPrevCrossingColumn(int x) {
        int halfX = prevCrossingHalfColumn[2 * x + 1];
        return (halfX == Integer.MIN_VALUE) ? halfX : halfX >> 1;
    }

    /**
     * Gets the highest X coordinate of the horizontal span of an SLR-crossing bounding box, widened if needed so that
     * the span contains at least one INT tile column from which super long lines can cross SLRs (Laguna on
     * UltraScale/UltraScale+, SLL on Versal): the next such column at or above its lowest X coordinate, if any (see
     * also {@link #getCrossingWidenedXMin(int, int)}).
     * @param xMin Lowest INT tile X coordinate of the span.
     * @param xMax Highest INT tile X coordinate of the span.
     * @return That X coordinate (xMax if there are no such columns at all).
     */
    public int getCrossingWidenedXMax(int xMin, int xMax) {
        if (nextCrossingHalfColumn == null) {
            return xMax;
        }
        int nextColumn = getNextCrossingColumn(xMin);
        return (nextColumn != Integer.MAX_VALUE) ? Math.max(xMax, nextColumn) : xMax;
    }

    /**
     * Gets the lowest X coordinate of the horizontal span of an SLR-crossing bounding box, widened if needed so that
     * the span contains at least one INT tile column from which super long lines can cross SLRs: the previous such
     * column at or below its highest X coordinate, if any (see also {@link #getCrossingWidenedXMax(int, int)}).
     * @param xMin Lowest INT tile X coordinate of the span.
     * @param xMax Highest INT tile X coordinate of the span.
     * @return That X coordinate (xMin if there are no such columns at all).
     */
    public int getCrossingWidenedXMin(int xMin, int xMax) {
        if (prevCrossingHalfColumn == null) {
            return xMin;
        }
        int prevColumn = getPrevCrossingColumn(xMax);
        return (prevColumn != Integer.MIN_VALUE) ? Math.min(xMin, prevColumn) : xMin;
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

                                if (inode.getTile() != imuxTile) {
                                    // A feedthrough INODE from the INT tile above or below (INODE_[EW]_*_FT[01] on
                                    // UltraScale+, INODE_[12]_[EW]_*_FT[NS] on UltraScale): keyed by its own (base)
                                    // tile, by which RouteNodeInfo.getType() looks it up. (In the IMUX's tile, its wire
                                    // index is the feedthrough of the same name from there into the row after it, e.g.
                                    // INT_X5Y359/INODE_W_54_FT0, which ends in INT_X5Y360, on xcvu9p)
                                    wireIndicesLeadingToSLL.computeIfAbsent(inode.getTile(), k -> new BitSet[]{new BitSet(), new BitSet()})[0]
                                            .set(inode.getWireIndex());
                                    continue;
                                }
                                bs[0].set(inode.getWireIndex());
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
     * Checks that the side of its INT tile that a node RWRoute routes from (a net's source or alternate source, as
     * projected by RouterHelper.projectOutputPinToINTNode()) is on is known to {@link #isEastSideWire(TileTypeEnum, int)}:
     * that its tile type is listed in {@link #SITE_TILE_TYPE_ON_EAST_SIDE}, or has east-side wires of its own (e.g.
     * INT). Otherwise the node is placed on the west side, whichever side it is on, which only makes the estimates
     * of the distance from it less accurate: print a warning, once per tile type.
     * @param sourceNode The projected source node.
     * @return True if its side is known.
     */
    public boolean checkSourceSideKnown(Node sourceNode) {
        final TileTypeEnum tileTypeEnum = sourceNode.getTile().getTileTypeEnum();
        if (SITE_TILE_TYPE_ON_EAST_SIDE.containsKey(tileTypeEnum)) {
            return true;
        }
        final TileTypeInfo info = getTileTypeInfo(tileTypeEnum);
        if (info != null && info.eastSideWires != null) {
            return true;
        }
        if (sourceTileTypesOfUnknownSide.add(tileTypeEnum)) {
            System.out.println("WARNING: Side of source node " + sourceNode + " unknown, so taken to be west: add " +
                    "its tile type " + tileTypeEnum + " to RouteNodeGraph.SITE_TILE_TYPE_ON_EAST_SIDE");
        }
        return false;
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
                final boolean isSLLType = isSllTileType(tte); // Versal only
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

    /**
     * Versal tile types hosting CLE [BC]NODEs and CTRL pins: CLE_BC_CORE, CLE_BC_CORE_MX (a variant of it, e.g. at the
     * bottom edge of the fabric on xcvc1902, whose CTRL wires reach the CE, CLK and RST inputs of the IRI_QUAD sites
     * there), SLL (beside the SLL columns of devices with several SLRs) and SLL2 (every such tile on xcvp1902, which
     * has no CLE_BC_CORE tiles).
     */
    static final List<TileTypeEnum> BC_NODE_TILE_TYPES = Collections.unmodifiableList(Arrays.asList(
            TileTypeEnum.CLE_BC_CORE, TileTypeEnum.CLE_BC_CORE_MX, TileTypeEnum.SLL, TileTypeEnum.SLL2));

    /**
     * Checks if a tile type is a Versal SLL tile: SLL, or SLL2 (xcvp1902). Both host super long lines, CLE [BC]NODEs
     * and CTRL pins.
     * @param tileTypeEnum Tile type in question.
     * @return True if so.
     */
    public static boolean isSllTileType(TileTypeEnum tileTypeEnum) {
        return tileTypeEnum == TileTypeEnum.SLL || tileTypeEnum == TileTypeEnum.SLL2;
    }

    /**
     * Finds an INT tile two columns away from a tile of the given type (with a CLE core tile between them), on either
     * side, trying an arbitrary tile of that type first.
     * @param device Device in question.
     * @param tileTypeEnum Tile type (one of {@link #BC_NODE_TILE_TYPES}).
     * @return That INT tile, or null if the device has no tile of that type beside one.
     */
    private static Tile getIntTileBeside(Device device, TileTypeEnum tileTypeEnum) {
        Tile tile = device.getArbitraryTileOfType(tileTypeEnum);
        if (tile == null) {
            return null;
        }
        Tile intTile = getIntTileBeside(tile);
        if (intTile != null) {
            return intTile;
        }
        // The arbitrary tile can be at the edge of the device (e.g. an SLL2 tile on xcvp1902)
        for (Tile t : device.getAllTiles()) {
            if (t.getTileTypeEnum() == tileTypeEnum && (intTile = getIntTileBeside(t)) != null) {
                return intTile;
            }
        }
        return null;
    }

    private static Tile getIntTileBeside(Tile tile) {
        for (int dx : new int[]{2, -2}) {
            Tile neighbor = tile.getTileNeighbor(dx, 0);
            if (neighbor != null && neighbor.getTileTypeEnum() == TileTypeEnum.INT) {
                return neighbor;
            }
        }
        return null;
    }

    private static final Set<TileTypeEnum> allowedTileEnums;
    static {
        allowedTileEnums = EnumSet.noneOf(TileTypeEnum.class);
        allowedTileEnums.add(TileTypeEnum.INT);
        allowedTileEnums.addAll(Utils.getLagunaTileTypes());

        // Versal only: include tiles hosting BNODE/CNODEs
        allowedTileEnums.addAll(BC_NODE_TILE_TYPES);
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
        // Not one of the connection's sinks (but possibly a marked driver of them; see RWRoute.setFinishCosts())
        assert(!childRnode.isTarget() || (childRnode != connection.getSinkRnode() &&
                !connection.getAltSinkRnodes().contains(childRnode)));
        assert(parentRnode != null || (childRnode.getType().isAnyLocal() &&
                (!isVersal || childRnode.getIntentCode() != IntentCode.NODE_PINFEED)));

        if (isVersal && lutPinSwapping && childRnode.getIntentCode() == IntentCode.NODE_IMUX) {
            // With LUT pin swapping, the graph also holds (as non-exclusive sinks) the IMUX nodes of other connections'
            // candidate LUT input pins; one that is not a target of this connection leads nowhere it could use (an
            // IMUX only drives its site pin)
            return false;
        }

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
                                      TileTypeEnum.CLE_BC_CORE, TileTypeEnum.CLE_BC_CORE_MX, TileTypeEnum.SLL, TileTypeEnum.SLL2)
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

    public void setPresentCongestionFactor(float presentCongestionFactor) {
        this.presentCongestionFactor = presentCongestionFactor;
    }

    /**
     * Gets the present congestion cost of a node with the given occupancy: RouteNode.initialPresentCongestionCost, plus
     * the present congestion factor (see setPresentCongestionFactor()) for each user that the node would have over
     * its capacity with one more. Linear in the occupancy, so computed rather than looked up, for any occupancy.
     * @param occupancy Number of users of the node.
     * @return That cost.
     */
    public float getPresentCongestionCost(int occupancy) {
        return RouteNode.initialPresentCongestionCost +
                Math.max(0, occupancy - RouteNode.capacity + 1) * presentCongestionFactor;
    }

    /**
     * Gets the delay estimator that this graph computes its node delays with.
     * @return That delay estimator, or null since this graph is not timing driven.
     */
    public DelayEstimatorBase<InterconnectInfo> getDelayEstimator() {
        return null;
    }

    /**
     * Gets the LUT logic delay with a connection arriving at the given sink rnode (one of its
     * candidate LUT input pins, with LUT pin swapping).
     * @param timingGraph The timing graph.
     * @param connection The connection.
     * @param sinkRnode One of the connection's sink rnodes.
     * @param updateTimingGraph True to also set the corresponding logic edges' delays in the timing graph.
     * @return The logic delay in ps: zero, since this graph is not timing driven.
     */
    protected float getLutLogicDelay(TimingGraph timingGraph, Connection connection, RouteNode sinkRnode,
                                     boolean updateTimingGraph) {
        return 0;
    }

    /**
     * Sets, for each of the connection's targets, its LUT logic delay in excess of that of the
     * fastest target: nothing to do, since this graph is not timing driven.
     * @param state State from the connection that is being routed, with its targets marked.
     * @param timingManager The timing manager (null if not timing driven).
     */
    protected void setTargetExcessLogicDelays(RWRoute.ConnectionState state, TimingManager timingManager) {
    }

    /**
     * Gets the given target's LUT logic delay in excess of that of the connection's fastest target.
     * @param state State from the connection that is being routed.
     * @param target One of the connection's targets.
     * @return The excess logic delay in ps: zero, since this graph is not timing driven.
     */
    protected float getTargetExcessLogicDelay(RWRoute.ConnectionState state, RouteNode target) {
        return 0;
    }

    /**
     * Checks if routing a connection may terminate early on pushing the given (uncongested) target.
     * @param state State from the connection that is being routed.
     * @param target The target being pushed.
     * @return True, since this graph is not timing driven.
     */
    protected boolean isEarlyTerminable(RWRoute.ConnectionState state, RouteNode target) {
        return true;
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
