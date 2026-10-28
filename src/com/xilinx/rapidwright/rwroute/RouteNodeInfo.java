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
    public final short length;

    private RouteNodeInfo(RouteNodeType type,
                          short endHalfTileXCoordinate,
                          short endTileYCoordinate,
                          short length) {
        this.type = type;
        this.endHalfTileXCoordinate = endHalfTileXCoordinate;
        this.endTileYCoordinate = endTileYCoordinate;
        this.length = length;
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

        // Without a routing graph there is no type to be determined, nor anything to check the
        // length against; only the length itself is recoverable
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
        short length = (short) Math.abs(endTileYCoordinate - baseTile.getTileYCoordinate());
        if (baseTileType == TileTypeEnum.LAG_LAG) {
            // Nodes in LAGUNA tiles must have no X distance
            assert(baseTile.getTileXCoordinate() == endTileXCoordinate - 1);
        } else if (baseIntentCode == IntentCode.NODE_CLE_CTRL || isOnBothSides) {
            // Nor do (Versal) CTRL pins or nodes in SLL tiles, whose end tile X coordinate only serves to locate them
        } else {
            length += Math.abs(endTileXCoordinate - baseTile.getTileXCoordinate());
        }
        if (routingGraph != null) {
            switch (baseTileType) {
                case LAG_LAG:
                case LAGUNA_TILE:
                    assert(length == routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES ||
                           // U-turn
                           length == 0);
                    break;
                case INT:
                    if (type.leadsToLaguna()) {
                        assert(length <= 1); // 1 only if INODE_[EW]_\d+_FT[01]
                    }
                    break;
            }
        }

        return new RouteNodeInfo(type, endHalfTileXCoordinate, endTileYCoordinate, length);
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
    private static short getWestIntTileXCoordinate(Tile tile) {
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

                if (routingGraph.wireIndicesLeadingToLaguna != null) {
                    // Check for INODE or SDQNODE that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToLaguna.get(node.getTile());
                    if (bs2 != null) {
                        boolean northbound = routingGraph.intYToNorthboundLaguna[node.getTile().getTileYCoordinate()];
                        if (bs2[0].get(node.getWireIndex())) {
                            BitSet bs = routingGraph.ultraScalesLocalWires.get(tileTypeEnum);
                            assert(bs.get(node.getWireIndex()));
                            assert(routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex()));

                            // INODE
                            return northbound ? RouteNodeType.LOCAL_LEADING_TO_NORTHBOUND_LAGUNA
                                              : RouteNodeType.LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA;
                        } else if (bs2[1].get(node.getWireIndex())) {
                            // SDQNODE
                            return northbound ? RouteNodeType.NON_LOCAL_LEADING_TO_NORTHBOUND_LAGUNA
                                              : RouteNodeType.NON_LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA;
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

            case NODE_SLL_INPUT: // Versal only
                // Not considered to be on any specific side (see RouteNodeGraph.isAccessible())
                return RouteNodeType.LOCAL;

            case NODE_PINFEED:
                if (routingGraph.isVersal ||
                        // For BUFCE_{LEAF,ROW_FSR}.CE inputs appearing on already-routed (preserved) nets
                        // as a route-thru from Vivado's high fanout net optimization
                        Utils.isClocking(tileTypeEnum)) {
                    // Not restricted to any specific side (see RouteNodeGraph.isAccessible()), though Versal ones
                    // are placed on the side of the INT tile that their CLE core is beside (see RouteNodeGraph.eastSideWires)
                    return RouteNodeType.LOCAL;
                }

                if (routingGraph.wireIndicesLeadingToLaguna != null) {
                    // Check for IMUX that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToLaguna.get(node.getTile());
                    if (bs2 != null && bs2[0].get(node.getWireIndex())) {
                        assert(routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex()));
                        boolean northbound = routingGraph.intYToNorthboundLaguna[node.getTile().getTileYCoordinate()];
                        return northbound ? RouteNodeType.LOCAL_LEADING_TO_NORTHBOUND_LAGUNA
                                          : RouteNodeType.LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA;
                    }
                }
                // Fall through
            case NODE_PINBOUNCE:
            case NODE_INODE:        // INT.INT_NODE_IMUX_ATOM_*_INT_OUT[01]          (Versal only)
            case NODE_IMUX:         // INT.IMUX_B_[EW]*                              (Versal only)
            case NODE_CLE_CNODE:    // CLE_BC_CORE*.CNODE_OUTS_[EW]*                 (Versal only)
            case NODE_CLE_BNODE:    // CLE_BC_CORE*.BNODE_OUTS_[EW]*                 (Versal only)
            case NODE_INTF_BNODE:   // INTF_[LR]OCF_[TB][LR]_TILE.IF_INT_BNODE_OUTS* (Versal only)
            case NODE_INTF_CNODE:   // INTF_[LR]OCF_[TB][LR]_TILE.IF_INT_CNODE_OUTS* (Versal only)
                // Other than CTRL_* pins (which are reachable from INT_NODE_GLOBAL_* on either side but are
                // still on a specific side; see RouteNodeType.EXCLUSIVE_SINK_CTRL), these nodes serve one side only
                assert(routingGraph.isSidedLocalWire(tileTypeEnum, node.getWireIndex()) ||
                       (!routingGraph.isVersal && node.getWireName().startsWith("CTRL_")));
                return RouteNodeType.LOCAL;

            case NODE_SINGLE:
                if (routingGraph.wireIndicesLeadingToLaguna != null) {
                    // Check for INT_INT_ that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToLaguna.get(node.getTile());
                    if (bs2 != null && bs2[1].get(node.getWireIndex())) {
                        assert(node.getWireName().matches("INT_INT_SDQ_\\d+_INT_OUT[01]|WW1_E_7_FT0") ||    // UltraScale+
                               node.getWireName().matches("INT_INT_SINGLE_\\d+_INT_OUT|EE1_W_0_FTS"));      // UltraScale
                        boolean northbound = routingGraph.intYToNorthboundLaguna[node.getTile().getTileYCoordinate()];
                        return northbound ? RouteNodeType.NON_LOCAL_LEADING_TO_NORTHBOUND_LAGUNA
                                          : RouteNodeType.NON_LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA;
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

            case INTENT_DEFAULT:
                if (tileTypeEnum == TileTypeEnum.LAGUNA_TILE) { // UltraScale only
                    String wireName = node.getWireName();
                    if (wireName.startsWith("UBUMP")) {
                        return RouteNodeType.SUPER_LONG_LINE;
                    }
                }
                break;
        }

        if (routingGraph.isInheritPrevSideWire(tileTypeEnum, node.getWireIndex())) {
            return RouteNodeType.NON_LOCAL_INHERIT_PREV_SIDE;
        }
        return RouteNodeType.NON_LOCAL;
    }
}
