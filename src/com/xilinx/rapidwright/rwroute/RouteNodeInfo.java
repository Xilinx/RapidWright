/*
 * Copyright (c) 2023-2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.Utils;

import java.util.BitSet;

public class RouteNodeInfo {
    public final RouteNodeType type;
    /**
     * X coordinate, in half tiles, of the INT tile half that the node ends at: twice the X coordinate
     * of that INT tile for its west half, plus one for its east half
     */
    public final short endHalfTileXCoordinate;
    public final short endTileYCoordinate;
    /**
     * Wirelength of the node in half INT tiles: the nominal length given by its intent code and name (see
     * {@link #getNominalHalfTileWirelength}), whatever the number of INT tiles it spans (which differs for U-turns at
     * the device edge and for feedthroughs); zero for nodes other than singles, doubles, quads, longs, super long
     * lines and UltraScale(+) INODE/BOUNCE feedthroughs.
     */
    public final short halfTileWirelength;

    /**
     * Gets the nominal wirelength, in half INT tiles, of a single, double, quad, long, super long line or UltraScale(+)
     * INODE/BOUNCE feedthrough, from its intent code and name: 1 for a horizontal or intra-tile single (half a tile,
     * from one half of an INT tile to the next), 2 for a vertical single, horizontal double or INODE/BOUNCE
     * feedthrough, 4 for a vertical double or HQUAD, 8 for a VQUAD (10 for UltraScale's NN5/SS5), 12 for an HLONG, 24
     * for a VLONG (32 for UltraScale's NN16/SS16), and on Versal 6/10 for HLONG6/HLONG10 and 14/24 for
     * VLONG7/VLONG12; for a super long line, twice its length in tiles.
     * @param ic Intent code of the node's base wire.
     * @param wireName Name of the node's base wire.
     * @param tileType Type of the node's base tile.
     * @return That wirelength, or 0 if the node is none of these.
     */
    private static short getNominalHalfTileWirelength(IntentCode ic, String wireName, TileTypeEnum tileType) {
        switch (ic) {
            case NODE_LAGUNA_DATA: // UltraScale+ super long line (see getType())
                return 2 * RouteNodeGraph.ULTRASCALE_SUPER_LONG_LINE_LENGTH_IN_TILES;
            case INTENT_DEFAULT:
                // UltraScale super long line (see getType())
                return (short) ((tileType == TileTypeEnum.LAGUNA_TILE && wireName.startsWith("UBUMP")) ?
                        2 * RouteNodeGraph.ULTRASCALE_SUPER_LONG_LINE_LENGTH_IN_TILES : 0);
            case NODE_SLL_DATA:    // Versal super long line (see getType())
                return 2 * RouteNodeGraph.VERSAL_SUPER_LONG_LINE_LENGTH_IN_TILES;
            case NODE_LOCAL:       // US and US+
                // INODE feedthroughs (INODE_[EW]_*_FT[01] on UltraScale+, INODE_[12]_[EW]_*_FT[NS] on UltraScale)
                // travel one tile vertically, as measured on xcvu3p and xcku040 (but for a few at the device edge)
                return (short) ((wireName.startsWith("INODE_") && isFeedthroughName(wireName)) ? 2 : 0);
            case NODE_PINBOUNCE:   // US/US+/Versal
                // BOUNCE feedthroughs (BOUNCE_[EW]_*_FT[01] on UltraScale+, BOUNCE_[EW]_*_FT[NS] on UltraScale) travel
                // one tile vertically, as measured on xcvu3p and xcku040 (but for a few at the device edge); all other
                // PINBOUNCEs (BYPASS_[EW]* on UltraScale(+), BOUNCE_[EW]* on Versal) stay within their tile
                return (short) (isFeedthroughName(wireName) ? 2 : 0);
            case NODE_SINGLE:  // US and US+
            case NODE_HSINGLE: // Versal
            case NODE_VSINGLE: // Versal
                return (short) (isVertical(ic, wireName) ? 2 : 1);
            case NODE_DOUBLE:  // US and US+
            case NODE_HDOUBLE: // Versal
            case NODE_VDOUBLE: // Versal
                return (short) (isVertical(ic, wireName) ? 4 : 2);
            case NODE_HQUAD:
                return 4;
            case NODE_VQUAD:
                // UltraScale's NN5/SS5 (UltraScale+ NN4_*/SS4_* and Versal OUT_* names have no '5' there)
                return (short) (wireName.charAt(2) == '5' ? 10 : 8);
            case NODE_HLONG:   // US and US+
                return 12;
            case NODE_VLONG:   // US and US+
                // UltraScale's NN16/SS16
                return (short) (wireName.startsWith("16", 2) ? 32 : 24);
            case NODE_HLONG6:  // Versal
                return 6;
            case NODE_HLONG10: // Versal
                return 10;
            case NODE_VLONG7:  // Versal
                return 14;
            case NODE_VLONG12: // Versal
                return 24;
            default:
                // Not a single, double, quad, long, super long line or INODE/BOUNCE feedthrough: nominally travels
                // nowhere
                return 0;
        }
    }

    /**
     * Checks if a wire name ends with _FT followed by one character (e.g. INODE_E_5_FT1, BOUNCE_W_0_FTN).
     * @param wireName Wire name in question.
     * @return True if so.
     */
    private static boolean isFeedthroughName(String wireName) {
        // Equivalent to endsWith("_FT?"), for any one character '?'
        return wireName.startsWith("_FT", wireName.length() - 4);
    }

    /**
     * Checks if a single or double is vertical: from its intent code on Versal, or else (NODE_SINGLE and NODE_DOUBLE
     * being used for both axes on UltraScale/UltraScale+) its wire name (e.g. NN1_E_BEG0 or SS2_W_BEG7, rather than
     * EE1_E_BEG0 or the intra-tile INT_INT_SDQ_32_INT_OUT1).
     * @param ic Intent code of the node's base wire.
     * @param wireName Name of the node's base wire.
     * @return True if so.
     */
    public static boolean isVertical(IntentCode ic, String wireName) {
        switch (ic) {
            case NODE_VSINGLE:
            case NODE_VDOUBLE:
                return true;
            case NODE_HSINGLE:
            case NODE_HDOUBLE:
                return false;
            default:
                char c = wireName.charAt(0);
                return c == 'N' || c == 'S';
        }
    }

    private RouteNodeInfo(RouteNodeType type,
                          short endHalfTileXCoordinate,
                          short endTileYCoordinate,
                          short halfTileWirelength) {
        this.type = type;
        this.endHalfTileXCoordinate = endHalfTileXCoordinate;
        this.endTileYCoordinate = endTileYCoordinate;
        this.halfTileWirelength = halfTileWirelength;
    }

    /**
     * Gets the information that a {@link RouteNode} is built out of, for a given node.
     * @param node Node to describe.
     * @param routingGraph Graph the node is to be a part of, or null to describe the node on its
     *                     own; the returned {@link #type} is then null, since a type only has
     *                     meaning within a graph, and the returned {@link #endHalfTileXCoordinate}
     *                     is that of the west half (other than for Versal SLL inputs, outputs and data,
     *                     which are always placed on the east half; see {@link #isOnBothSides(IntentCode)}).
     * @return That information.
     */
    public static RouteNodeInfo get(Node node, RouteNodeGraph routingGraph) {
        Wire[] wires = node.getAllWiresInNode();
        assert(wires[0].getTile() == node.getTile() && wires[0].getWireIndex() == node.getWireIndex());
        Tile baseTile = node.getTile();
        TileTypeEnum baseTileType = baseTile.getTileTypeEnum();
        IntentCode baseIntentCode = node.getIntentCode();
        TileTypeEnum endTileType;
        if (baseIntentCode == IntentCode.NODE_CLE_CTRL) {
            // Versal CTRL_[LR]_B* pins (in CLE_BC_CORE or SLL tiles) end at the CLE core tile that they serve, which
            // has the same X coordinate as the INT tile that it is beside (see RouteNodeGraph.eastSideWires)
            assert(baseTileType == TileTypeEnum.CLE_BC_CORE || baseTileType == TileTypeEnum.SLL);
            endTileType = null;
        } else if (baseIntentCode == IntentCode.NODE_CLE_BNODE || baseIntentCode == IntentCode.NODE_CLE_CNODE ||
                !(Utils.isLaguna(baseTileType) || Utils.isInterConnect(baseTileType))) {
            // Includes Versal [BC]NODEs in SLL tiles (which are considered interconnect), which end at the INT
            // tile whose INODEs they drive
            endTileType = TileTypeEnum.INT;
        } else {
            endTileType = baseTileType;
        }
        Tile endTile = baseTile;
        for (int i = 1; i < wires.length; i++) {
            Wire w = wires[i];
            Tile tile = w.getTile();
            if (endTileType != null ? tile.getTileTypeEnum() != endTileType : tile == baseTile) {
                continue;
            }
            endTile = tile;
            break;
        }
        assert(baseIntentCode != IntentCode.NODE_CLE_CTRL || endTile != baseTile);

        // Without a routing graph there is no type to be determined
        RouteNodeType type = (routingGraph != null) ? getType(node, routingGraph) : null;
        short endTileXCoordinate;
        boolean endsOnEastSide;
        boolean isOnBothSides = isOnBothSides(baseIntentCode);
        if (isOnBothSides) {
            // Versal SLL inputs, outputs and data (super long lines) are not on either side of an INT tile, since
            // they are in SLL tiles that sit between the east side of one INT tile and the west side of the next.
            // Record the former, as the lower end of that two-half span: RWRoute.evaluateCostAndPush() then takes
            // whichever of the two is nearer to the sink (see RouteNodeGraph.isOnBothSides())
            assert(baseTileType == TileTypeEnum.SLL && endTile.getTileTypeEnum() == TileTypeEnum.SLL);
            endTileXCoordinate = getWestIntTileXCoordinate(endTile);
            endsOnEastSide = true;
        } else {
            endTileXCoordinate = getEndTileXCoordinate(node, (short) endTile.getTileXCoordinate());
            // Nodes that do not end on the east side of an INT tile (including those that are not specific
            // to any one side, e.g. longs) are considered to end on its west side
            endsOnEastSide = routingGraph != null && routingGraph.isEastSideWire(baseTileType, node.getWireIndex());
        }
        short endHalfTileXCoordinate = (short) (2 * endTileXCoordinate + (endsOnEastSide ? 1 : 0));
        short endTileYCoordinate = (short) endTile.getTileYCoordinate();
        // Nodes in LAGUNA tiles must have no X distance
        assert(baseTileType != TileTypeEnum.LAG_LAG || baseTile.getTileXCoordinate() == endTileXCoordinate - 1);

        short halfTileWirelength = getNominalHalfTileWirelength(baseIntentCode, node.getWireName(), baseTileType);

        return new RouteNodeInfo(type, endHalfTileXCoordinate, endTileYCoordinate, halfTileWirelength);
    }

    /**
     * Checks if nodes of the given intent code are not on either side of an INT tile, but between two INT tiles
     * (on the east side of one and the west side of the next): Versal SLL inputs, outputs, and data
     * @param ic Intent code of the node in question.
     * @return True if so.
     */
    public static boolean isOnBothSides(IntentCode ic) {
        return ic == IntentCode.NODE_SLL_INPUT || ic == IntentCode.NODE_SLL_OUTPUT || ic == IntentCode.NODE_SLL_DATA;
    }

    /**
     * Gets the X coordinate of the nearest INT tile to the west of the given tile, in the same row.
     * @param tile Tile in question (e.g. a Versal SLL tile).
     * @return That X coordinate.
     */
    static short getWestIntTileXCoordinate(Tile tile) {
        // On xcv80, for example, INT_X113Y461 is 3 columns to the west of SLL_X114Y461
        final int maxColumns = 8;
        for (int dx = 1; dx <= maxColumns; dx++) {
            Tile west = tile.getTileNeighbor(-dx, 0);
            if (west == null) {
                break;
            }
            if (west.getTileTypeEnum() == TileTypeEnum.INT) {
                return (short) west.getTileXCoordinate();
            }
        }
        throw new RuntimeException("ERROR: No INT tile found within " + maxColumns + " columns west of " + tile);
    }

    private static short getEndTileXCoordinate(Node node, short endTileXCoordinate) {
        Tile baseTile = node.getTile();
        TileTypeEnum baseType = baseTile.getTileTypeEnum();
        switch (baseType) {
            case LAG_LAG: // UltraScale+ only
                // Correct the X coordinate of all Laguna nodes since they are accessed by the INT
                // tile to its right, yet the LAG tile has a tile X coordinate one less than this.
                // Do not apply to VCC_WIREs since their end tiles are INT tiles.
                IntentCode ic = node.getIntentCode();
                if (baseTile.getTileXCoordinate() == endTileXCoordinate) {
                    assert(ic == IntentCode.NODE_LAGUNA_OUTPUT ||                       // LAG_MUX_ATOM_\\d+_TXOUT (but not RXD\\d+)
                           ic == IntentCode.NODE_LAGUNA_DATA ||                         // UBUMP\\d+
                           ic == IntentCode.NODE_OUTPUT ||                              // LAG_LAGUNA_SITE_[0-3]_TXQ[0-5]
                           (ic == IntentCode.INTENT_DEFAULT && !node.isTiedToVcc()));   // LAG_LAGUNA_SITE_[0-3]_RXD[0-5]
                    endTileXCoordinate++;
                } else {
                    assert(ic == IntentCode.NODE_LAGUNA_OUTPUT);
                    assert(node.getWireName().matches("RXD\\d+"));
                }
                break;
            case LAGUNA_TILE: // UltraScale only
                // In UltraScale, Laguna tiles have the same X as the base INT tile
                assert(baseTile.getTileXCoordinate() == endTileXCoordinate);
                break;
        }
        return endTileXCoordinate;
    }

    public static RouteNodeType getType(Node node, RouteNodeGraph routingGraph) {
        // NOTE: IntentCode is device-dependent
        IntentCode ic = node.getIntentCode();
        TileTypeEnum tileTypeEnum = node.getTile().getTileTypeEnum();
        switch (ic) {
            case NODE_LOCAL: { // US/US+
                assert(tileTypeEnum == TileTypeEnum.INT);

                if (routingGraph.wireIndicesLeadingToSLL != null) {
                    // Check for INODE or SDQNODE that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToSLL.get(node.getTile());
                    if (bs2 != null) {
                        if (bs2[0].get(node.getWireIndex())) {
                            BitSet bs = routingGraph.ultraScalesLocalWires.get(tileTypeEnum);
                            assert(bs.get(node.getWireIndex()));
                            assert(routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex()));

                            // INODE
                            return RouteNodeType.LOCAL_LEADING_TO_SLL;
                        } else if (bs2[1].get(node.getWireIndex())) {
                            // SDQNODE
                            return RouteNodeType.NON_LOCAL_LEADING_TO_SLL;
                        }
                    }
                }

                BitSet bs = routingGraph.ultraScalesLocalWires.get(tileTypeEnum);
                if (!bs.get(node.getWireIndex())) {
                    break;
                }
                if (routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex())) {
                    return RouteNodeType.LOCAL;
                }
                assert(node.getWireName().startsWith("INT_NODE_GLOBAL_"));
                return RouteNodeType.LOCAL_SIDE_DEPENDS_ON_SINK;
            }

            case NODE_SLL_INPUT: { // Versal only
                // Only those SLL inputs driving an SLL that crosses into another SLR lead to an SLR crossing (see
                // RouteNodeGraph.isCrossingSLLInput(), as looked up in RouteNodeGraph.wireIndicesLeadingToSLL);
                // either way, between two INT tiles rather than on either side of one (see isOnBothSides())
                BitSet[] bs = (routingGraph.wireIndicesLeadingToSLL != null)
                        ? routingGraph.getWireIndicesLeadingToSLL(node.getTile()) : null;
                boolean leadsToSLL = bs != null && bs[0].get(node.getWireIndex());
                assert(leadsToSLL == routingGraph.isCrossingSLLInput(node));
                return leadsToSLL ? RouteNodeType.LOCAL_LEADING_TO_SLL : RouteNodeType.LOCAL;
            }

            case NODE_PINFEED:
                if (routingGraph.isVersal ||
                        // For BUFCE_{LEAF,ROW_FSR}.CE inputs appearing on already-routed (preserved) nets
                        // as a route-thru from Vivado's high fanout net optimization
                        Utils.isClocking(tileTypeEnum)) {
                    // Not restricted to any specific side (see RouteNodeGraph.isAccessible()), though Versal ones
                    // are placed on the side of the INT tile that their CLE core is beside (see RouteNodeGraph.eastSideWires)
                    return RouteNodeType.LOCAL;
                }

                if (routingGraph.wireIndicesLeadingToSLL != null) {
                    // Check for IMUX that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToSLL.get(node.getTile());
                    if (bs2 != null && bs2[0].get(node.getWireIndex())) {
                        assert(routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex()));
                        return RouteNodeType.LOCAL_LEADING_TO_SLL;
                    }
                }
                // Fall through
            case NODE_PINBOUNCE:
            case NODE_INODE:        // INT.INT_NODE_IMUX_ATOM_*_INT_OUT[01]          (Versal only)
            case NODE_IMUX:         // INT.IMUX_B_[EW]*                              (Versal only)
            case NODE_CLE_CNODE:    // CLE_BC_CORE*.CNODE_OUTS_[EW]*                 (Versal only)
            case NODE_CLE_BNODE:    // CLE_BC_CORE*/SLL.BNODE_OUTS_[EW]*             (Versal only)
            case NODE_INTF_BNODE:   // INTF_[LR]OCF_[TB][LR]_TILE.IF_INT_BNODE_OUTS* (Versal only)
            case NODE_INTF_CNODE:   // INTF_[LR]OCF_[TB][LR]_TILE.IF_INT_CNODE_OUTS* (Versal only)
                // Other than CTRL_* pins (which are reachable from INT_NODE_GLOBAL_* on either side but are
                // still on a specific side; see RouteNodeType.EXCLUSIVE_SINK_CTRL), these nodes serve one side only
                assert(routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex()) ||
                       (!routingGraph.isVersal && node.getWireName().startsWith("CTRL_")));
                if (ic == IntentCode.NODE_CLE_BNODE && routingGraph.isBNodeLeadingToSLL(node)) {
                    // A BNODE in an SLL tile that drives an SLL input whose SLL crosses into another SLR (as looked
                    // up in RouteNodeGraph.wireIndicesLeadingToSLL): still on the side of the INODEs that it drives
                    return RouteNodeType.LOCAL_LEADING_TO_SLL;
                }
                return RouteNodeType.LOCAL;

            case NODE_SINGLE:
                if (routingGraph.wireIndicesLeadingToSLL != null) {
                    // Check for INT_INT_ that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToSLL.get(node.getTile());
                    if (bs2 != null && bs2[1].get(node.getWireIndex())) {
                        assert(node.getWireName().matches("INT_INT_SDQ_\\d+_INT_OUT[01]|WW1_E_7_FT0") ||    // UltraScale+
                               node.getWireName().matches("INT_INT_SINGLE_\\d+_INT_OUT|EE1_W_0_FTS"));      // UltraScale
                        return RouteNodeType.NON_LOCAL_LEADING_TO_SLL;
                    }
                }
                break;

            // Versal only
            case NODE_CLE_CTRL:     // CLE_BC_CORE*.CTRL_[LR]_B*
            case NODE_INTF_CTRL:    // INTF_[LR]OCF_[TB][LR]_TILE.INTF_IRI*
                // On one side of an INT tile (see RouteNodeGraph.eastSideWires), though reachable from both sides.
                // LOCAL rather than RouteNodeType.EXCLUSIVE_SINK_CTRL, since this is the type that the node is
                // created with: only once it is found to be the single sink of a connection does RWRoute make it an
                // exclusive sink (see RouteNodeGraph.getExclusiveSinkType()), as for all other sinks
                return RouteNodeType.LOCAL;
            case NODE_SLL_DATA:
                return RouteNodeType.SUPER_LONG_LINE;

            case NODE_LAGUNA_DATA: // UltraScale+ only
                assert(tileTypeEnum == TileTypeEnum.LAG_LAG);
                return RouteNodeType.SUPER_LONG_LINE;

            case NODE_LAGUNA_OUTPUT: // UltraScale+: LAG_LAG.{LAG_MUX_ATOM_*_TXOUT,RXD*}
                if (isTxOutLeadingToSLL(node, routingGraph)) {
                    return RouteNodeType.LOCAL_LEADING_TO_SLL;
                }
                break;

            case INTENT_DEFAULT:
                if (tileTypeEnum == TileTypeEnum.LAGUNA_TILE) { // UltraScale only
                    String wireName = node.getWireName();
                    if (wireName.startsWith("UBUMP")) {
                        return RouteNodeType.SUPER_LONG_LINE;
                    }
                    if (isTxOutLeadingToSLL(node, routingGraph)) {
                        return RouteNodeType.LOCAL_LEADING_TO_SLL;
                    }
                }
                break;
        }

        if (routingGraph.isInheritPrevSideWire(tileTypeEnum, node.getWireIndex())) {
            return RouteNodeType.NON_LOCAL_INHERIT_PREV_SIDE;
        }
        return RouteNodeType.NON_LOCAL;
    }

    /**
     * Checks whether an UltraScale/UltraScale+ node is a TXOUT (LAG_MUX_ATOM_*_TXOUT, in a Laguna tile) that drives a
     * super long line crossing into another SLR, as looked up in RouteNodeGraph.wireIndicesLeadingToSLL (see
     * RouteNodeGraph.populateLagunaLookups()). Such a TXOUT is the last node of the sending chain, after the IMUX that
     * drives it; others (whose super long line stays within its SLR, at the top or bottom edge of the device) are not.
     * @param node Node in question.
     * @param routingGraph Graph the node is to be a part of.
     * @return True if so.
     */
    private static boolean isTxOutLeadingToSLL(Node node, RouteNodeGraph routingGraph) {
        if (routingGraph.wireIndicesLeadingToSLL == null || !Utils.isLaguna(node.getTile().getTileTypeEnum())) {
            return false;
        }
        BitSet[] bs = routingGraph.wireIndicesLeadingToSLL.get(node.getTile());
        return bs != null && bs[0].get(node.getWireIndex());
    }
}
