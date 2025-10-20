/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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
    public final short endTileXCoordinate;
    public final short endTileYCoordinate;
    public final short length;

    private RouteNodeInfo(RouteNodeType type,
                          short endTileXCoordinate,
                          short endTileYCoordinate,
                          short length) {
        this.type = type;
        this.endTileXCoordinate = endTileXCoordinate;
        this.endTileYCoordinate = endTileYCoordinate;
        this.length = length;
    }

    public static RouteNodeInfo get(Node node, RouteNodeGraph routingGraph) {
        Wire[] wires = node.getAllWiresInNode();
        assert(wires[0].getTile() == node.getTile() && wires[0].getWireIndex() == node.getWireIndex());
        Tile baseTile = node.getTile();
        TileTypeEnum endTileType;
        if (Utils.isLaguna(baseTile.getTileTypeEnum())) {
            endTileType = baseTile.getTileTypeEnum();
        } else {
            endTileType = TileTypeEnum.INT;
        }
        Tile endTile = baseTile;
        for (int i = 1; i < wires.length; i++) {
            Wire w = wires[i];
            Tile tile = w.getTile();
            TileTypeEnum tileType = tile.getTileTypeEnum();
            if (tileType != endTileType) {
                continue;
            }
            endTile = tile;
            break;
        }

        RouteNodeType type = getType(node, routingGraph);
        short endTileXCoordinate = getEndTileXCoordinate(node, (short) endTile.getTileXCoordinate());
        short endTileYCoordinate = (short) endTile.getTileYCoordinate();
        short length = getLength(baseTile, type, endTileXCoordinate, endTileYCoordinate);

        return new RouteNodeInfo(type, endTileXCoordinate, endTileYCoordinate, length);
    }

    private static short getLength(Tile baseTile, RouteNodeType type, short endTileXCoordinate, short endTileYCoordinate) {
        TileTypeEnum tileType = baseTile.getTileTypeEnum();
        short length = (short) Math.abs(endTileYCoordinate - baseTile.getTileYCoordinate());
        if (tileType == TileTypeEnum.LAG_LAG) {
            // Nodes in LAGUNA tiles must have no X distance
            assert(baseTile.getTileXCoordinate() == endTileXCoordinate - 1);
        } else {
            length += Math.abs(endTileXCoordinate - baseTile.getTileXCoordinate());
        }
        switch (tileType) {
            case LAG_LAG:
            case LAGUNA_TILE:
                assert(length == RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES ||
                       // U-turn
                       length == 0);
                break;
            case INT:
                if (type.leadsToLaguna()) {
                    assert(length <= 1); // 1 only if INODE_[EW]_\d+_FT[01]
                }
                break;
        }
        return length;
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

                            BitSet[] eastWestWires = routingGraph.eastWestWires.get(tileTypeEnum);
                            boolean eastNotWest;
                            if (eastWestWires[0].get(node.getWireIndex())) {
                                eastNotWest = true;
                            } else {
                                assert(eastWestWires[1].get(node.getWireIndex()));
                                eastNotWest = false;
                            }

                            // INODE
                            return northbound ? (eastNotWest ? RouteNodeType.LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA
                                                             : RouteNodeType.LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA)
                                              : (eastNotWest ? RouteNodeType.LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA
                                                             : RouteNodeType.LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA);
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
                BitSet[] eastWestWires = routingGraph.eastWestWires.get(tileTypeEnum);
                if (eastWestWires[0].get(node.getWireIndex())) {
                    return RouteNodeType.LOCAL_EAST;
                } else if (eastWestWires[1].get(node.getWireIndex())) {
                    return RouteNodeType.LOCAL_WEST;
                }
                return RouteNodeType.LOCAL_BOTH;
            }

            case NODE_SLL_INPUT: // Versal only
                return RouteNodeType.LOCAL_BOTH;

            case NODE_PINFEED:
                if (routingGraph.isVersal) {
                    return RouteNodeType.LOCAL_BOTH;
                }

                if (routingGraph.wireIndicesLeadingToLaguna != null) {
                    // Check for IMUX that leads to a Laguna
                    BitSet[] bs2 = routingGraph.wireIndicesLeadingToLaguna.get(node.getTile());
                    if (bs2 != null && bs2[0].get(node.getWireIndex())) {
                        BitSet[] eastWestWires = routingGraph.eastWestWires.get(tileTypeEnum);
                        boolean eastNotWest;
                        if (eastWestWires[0].get(node.getWireIndex())) {
                            eastNotWest = true;
                        } else {
                            assert(eastWestWires[1].get(node.getWireIndex()));
                            eastNotWest = false;
                        }

                        boolean northbound = routingGraph.intYToNorthboundLaguna[node.getTile().getTileYCoordinate()];
                        return northbound ? (eastNotWest ? RouteNodeType.LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA
                                                         : RouteNodeType.LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA)
                                          : (eastNotWest ? RouteNodeType.LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA
                                                         : RouteNodeType.LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA);
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
                BitSet[] eastWestWires = routingGraph.eastWestWires.get(tileTypeEnum);
                if (eastWestWires[0].get(node.getWireIndex())) {
                    return RouteNodeType.LOCAL_EAST;
                } else if (eastWestWires[1].get(node.getWireIndex())) {
                    return RouteNodeType.LOCAL_WEST;
                }
                assert(!routingGraph.isVersal && node.getWireName().startsWith("CTRL_"));
                return RouteNodeType.LOCAL_BOTH;

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
                return RouteNodeType.LOCAL_BOTH;
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

        return RouteNodeType.NON_LOCAL;
    }
}
