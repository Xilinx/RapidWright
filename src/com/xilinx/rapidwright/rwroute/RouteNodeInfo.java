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
        Tile baseTile = node.getTile();
        TileTypeEnum baseTileType = baseTile.getTileTypeEnum();
        Tile endTile = null;
        for (Wire w : wires) {
            Tile tile = w.getTile();
            TileTypeEnum tileType = tile.getTileTypeEnum();
            if (tileType == TileTypeEnum.INT || tileType == baseTileType) {
                boolean endTileWasNotNull = (endTile != null);
                endTile = tile;
                // Break if this is the second non-null tile
                if (endTileWasNotNull) break;
            }
        }
        if (endTile == null) {
            endTile = node.getTile();
        }

        RouteNodeType type = getType(node, endTile, routingGraph);
        short endTileXCoordinate = getEndTileXCoordinate(node, type, (short) endTile.getTileXCoordinate());
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
                if (type == RouteNodeType.SUPER_LONG_LINE) {
                    assert(length == RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);
                } else {
                    assert(length == 0);
                }
                break;
            case INT:
                if (type == RouteNodeType.LAGUNA_PINFEED) {
                    assert(length == 0);
                }
                break;
        }
        return length;
    }

    private static short getEndTileXCoordinate(Node node, RouteNodeType type, short endTileXCoordinate) {
        Tile baseTile = node.getTile();
        TileTypeEnum baseType = baseTile.getTileTypeEnum();
        switch (baseType) {
            case LAG_LAG: // UltraScale+ only
                // Correct the X coordinate of all Laguna nodes since they are accessed by the INT
                // tile to its right, yet the LAG tile has a tile X coordinate one less than this.
                // Do not apply this correction for NODE_LAGUNA_OUTPUT nodes (which the fanin and
                // fanout nodes of the SLL are marked as) unless it is a fanin (LAGUNA_I)
                // (i.e. do not apply it to the fanout nodes).
                // Nor apply it to VCC_WIREs since their end tiles are INT tiles.
                if ((node.getIntentCode() != IntentCode.NODE_LAGUNA_OUTPUT || type == RouteNodeType.LAGUNA_PINFEED) &&
                        !node.isTiedToVcc()) {
                    assert(baseTile.getTileXCoordinate() == endTileXCoordinate);
                    endTileXCoordinate++;
                }
                break;
            case LAGUNA_TILE: // UltraScale only
                // In UltraScale, Laguna tiles have the same X as the base INT tile
                assert(baseTile.getTileXCoordinate() == endTileXCoordinate);
                break;
        }
        return endTileXCoordinate;
    }

    private static RouteNodeType getType(Node node, Tile endTile, RouteNodeGraph routingGraph) {
        // NOTE: IntentCode is device-dependent
        IntentCode ic = node.getIntentCode();
        TileTypeEnum tileTypeEnum = node.getTile().getTileTypeEnum();
        switch (ic) {
            case NODE_LOCAL: { // US/US+
                assert(tileTypeEnum == TileTypeEnum.INT);
                if (routingGraph != null) {
                    BitSet bs = routingGraph.ultraScalesNonLocalWires.get(tileTypeEnum);
                    if (!bs.get(node.getWireIndex())) {
                        BitSet[] eastWestWires = routingGraph.eastWestWires.get(tileTypeEnum);
                        if (eastWestWires[0].get(node.getWireIndex())) {
                            return RouteNodeType.LOCAL_EAST;
                        } else if (eastWestWires[1].get(node.getWireIndex())) {
                            return RouteNodeType.LOCAL_WEST;
                        }
                        return RouteNodeType.LOCAL;
                    }
                    break;
                }
            }

            case NODE_PINFEED:
                if (routingGraph == null || routingGraph.isVersal) {
                    return RouteNodeType.LOCAL;
                }
                if (routingGraph.lagunaI != null) {
                    BitSet bs = routingGraph.lagunaI.get(node.getTile());
                    if (bs != null && bs.get(node.getWireIndex())) {
                        return RouteNodeType.LAGUNA_PINFEED;
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
                if (routingGraph != null) {
                    BitSet[] eastWestWires = routingGraph.eastWestWires.get(tileTypeEnum);
                    if (eastWestWires[0].get(node.getWireIndex())) {
                        return RouteNodeType.LOCAL_EAST;
                    } else if (eastWestWires[1].get(node.getWireIndex())) {
                        return RouteNodeType.LOCAL_WEST;
                    }
                    assert(!routingGraph.isVersal && node.getWireName().startsWith("CTRL_"));
                }
                return RouteNodeType.LOCAL;

            // Versal only
            case NODE_CLE_CTRL:     // CLE_BC_CORE*.CTRL_[LR]_B*
            case NODE_INTF_CTRL:    // INTF_[LR]OCF_[TB][LR]_TILE.INTF_IRI*
                return RouteNodeType.LOCAL;

            case NODE_LAGUNA_OUTPUT: // UltraScale+ only
                assert(tileTypeEnum == TileTypeEnum.LAG_LAG);
                if (node.getWireName().endsWith("_TXOUT")) {
                    return RouteNodeType.LAGUNA_PINFEED;
                }
                break;

            case NODE_LAGUNA_DATA: // UltraScale+ only
                assert(tileTypeEnum == TileTypeEnum.LAG_LAG);
                if (node.getTile() != endTile) {
                    return RouteNodeType.SUPER_LONG_LINE;
                }

                // U-turn node at the boundary of the device
                break;

            case INTENT_DEFAULT:
                if (tileTypeEnum == TileTypeEnum.LAGUNA_TILE) { // UltraScale only
                    String wireName = node.getWireName();
                    if (wireName.startsWith("UBUMP")) {
                        assert(node.getTile() != endTile);
                        return RouteNodeType.SUPER_LONG_LINE;
                    } else if (wireName.endsWith("_TXOUT")) {
                        // This is the inner LAGUNA_I, mark it so it gets a base cost discount
                        return RouteNodeType.LAGUNA_PINFEED;
                    }
                }
                break;
        }

        return RouteNodeType.NON_LOCAL;
    }
}
