/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestRouteNodeInfo {

    @ParameterizedTest
    @CsvSource({
            // Horizontal singles arrive at the opposite side to which they leave
            "xcvu3p,INT_X40Y150,EE1_E_BEG0,NON_LOCAL,W",
            "xcvu3p,INT_X40Y150,WW1_W_BEG0,NON_LOCAL,E",
            "xcvu3p,INT_X40Y150,WW1_E_7_FT0,NON_LOCAL,W",
            // Vertical singles, and doubles and quads in any direction, keep their side
            "xcvu3p,INT_X40Y150,NN1_E_BEG0,NON_LOCAL,E",
            "xcvu3p,INT_X40Y150,SS1_W_BEG0,NON_LOCAL,W",
            "xcvu3p,INT_X40Y150,EE2_W_BEG0,NON_LOCAL,W",
            "xcvu3p,INT_X40Y150,WW2_E_BEG0,NON_LOCAL,E",
            "xcvu3p,INT_X40Y150,NN2_E_BEG0,NON_LOCAL,E",
            "xcvu3p,INT_X40Y150,EE4_E_BEG0,NON_LOCAL,E",
            "xcvu3p,INT_X40Y150,NN4_W_BEG0,NON_LOCAL,W",
            // Wires without a side in their name: longs are sided by direction and index (EE12_BEG<odd> is west),
            // intra-tile singles by the side of the INODEs that they drive
            "xcvu3p,INT_X40Y150,EE12_BEG1,NON_LOCAL,W",
            "xcvu3p,INT_X40Y150,INT_INT_SDQ_73_INT_OUT0,NON_LOCAL,W",
            // Local wires
            "xcvu3p,INT_X40Y150,IMUX_E0,LOCAL,E",
            "xcvu3p,INT_X40Y150,IMUX_W0,LOCAL,W",
            // GNODEs can serve either side (CTRL_* pins on both sides) but are on the side of the INODEs they drive:
            // INT_NODE_IMUX_48_INT_OUT[01] (west) and INT_NODE_IMUX_16_INT_OUT[01] (east)
            "xcvu3p,INT_X40Y150,INT_NODE_GLOBAL_0_INT_OUT0,LOCAL_SIDE_DEPENDS_ON_SINK,W",
            "xcvu3p,INT_X40Y150,INT_NODE_GLOBAL_0_INT_OUT1,LOCAL_SIDE_DEPENDS_ON_SINK,E",
            // CTRL_* pins are on the side in their name
            "xcvu3p,INT_X40Y150,CTRL_E6,LOCAL,E",
            "xcvu3p,INT_X40Y150,CTRL_W6,LOCAL,W",

            // UltraScale GNODEs: INT_NODE_IMUX_[34]_INT_OUT (east) and INT_NODE_IMUX_5[89]_INT_OUT (west)
            "xcku040,INT_X30Y120,INT_NODE_GLOBAL_0_OUT0,LOCAL_SIDE_DEPENDS_ON_SINK,E",
            "xcku040,INT_X30Y120,INT_NODE_GLOBAL_0_OUT1,LOCAL_SIDE_DEPENDS_ON_SINK,W",
            "xcku040,INT_X30Y120,CTRL_E_B4,LOCAL,E",
            "xcku040,INT_X30Y120,CTRL_W_B4,LOCAL,W",
            "xcku040,INT_X30Y120,EE1_E_BEG0,NON_LOCAL,W",
            "xcku040,INT_X30Y120,NN2_E_BEG0,NON_LOCAL,E",
            // UltraScale quads have no side in their name, but are sided by direction and index (EE4_BEG<even> is east)
            "xcku040,INT_X30Y120,EE4_BEG0,NON_LOCAL,E",

            "xcvc1902,INT_X40Y100,OUT_EE1_E_BEG0,NON_LOCAL,W",
            "xcvc1902,INT_X40Y100,OUT_WW1_W_BEG0,NON_LOCAL,E",
            "xcvc1902,INT_X40Y100,OUT_NN1_W_BEG0,NON_LOCAL,W",
            "xcvc1902,INT_X40Y100,OUT_EE2_E_BEG0,NON_LOCAL,E",
            "xcvc1902,INT_X40Y100,OUT_EE4_W_BEG0,NON_LOCAL,W",
            "xcvc1902,INT_X40Y100,OUT_SS4_E_BEG0,NON_LOCAL,E",
            // Intra-tile singles are on the side of the INODEs that they drive; longs serve both sides (and are left on
            // the west half, but considered to be on the side of the node that the path arrived at them from)
            "xcvc1902,INT_X40Y100,INT_SDQ_RED_ATOM_16_INT_OUT0,NON_LOCAL,E",
            "xcvc1902,INT_X40Y100,OUT_EE6_BEG0,NON_LOCAL_INHERIT_PREV_SIDE,W",
            "xcvc1902,INT_X40Y100,OUT_NN12_BEG0,NON_LOCAL_INHERIT_PREV_SIDE,W",
            // Site pins (only LAG_* pins, downhill of SLL outputs, are not projected) are on the side of the INT tile
            // that their CLE core is beside: CLE_W_CORE_X16Y410 is east of INT_X16Y410, CLE_E_CORE_X17Y410 west of INT_X17Y410
            "xcv80,CLE_W_CORE_X16Y410,CLE_SLICEL_TOP_0_LAG_E1_PIN,LOCAL,E",
            "xcv80,CLE_E_CORE_X17Y410,CLE_SLICEL_TOP_0_LAG_E1_PIN,LOCAL,W",
    })
    public void testTypeAndHalfTile(String partName, String tileName, String wireName,
                                    RouteNodeType expectedType, String expectedHalf) {
        Design design = new Design("test", partName);
        RouteNodeGraph routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[]{}));
        Node node = Node.getNode(design.getDevice().getTile(tileName), wireName);
        Assertions.assertNotNull(node);
        RouteNodeInfo info = RouteNodeInfo.get(node, routingGraph);
        Assertions.assertEquals(expectedType, info.type);
        Assertions.assertEquals(expectedHalf.equals("E") ? 1 : 0, info.endHalfTileXCoordinate & 1);
    }

    // Site pin inputs on the east/west side of an INT tile
    private static final Pattern pinPattern = Pattern.compile("(IMUX|BYPASS|IMUX_B)_(?<eastwest>[EW])\\d+");

    // Interconnect wires with a side in their name
    private static final Pattern sideNamedPattern = Pattern.compile("(OUT_)?(NN|EE|SS|WW)\\d+_[EW]_.*");

    /**
     * Gets the fewest number of hops from the given node to reach a site pin input on each side of the
     * given INT tile, without leaving the neighborhood of that tile (except via the given node).
     * @return Array of {east, west} hop counts, Integer.MAX_VALUE if not reachable.
     */
    private static int[] getHopsToEachSide(Node start, Tile endTile, int maxHops) {
        int[] hops = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
        Set<Node> visited = new HashSet<>();
        Queue<Node> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        for (int depth = 1; depth <= maxHops && !queue.isEmpty(); depth++) {
            Queue<Node> nextQueue = new ArrayDeque<>();
            for (Node node : queue) {
                for (Node downhill : node.getAllDownhillNodes()) {
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    Tile tile = downhill.getTile();
                    if (tile == endTile) {
                        Matcher m = pinPattern.matcher(downhill.getWireName());
                        if (m.matches()) {
                            int i = m.group("eastwest").equals("E") ? 0 : 1;
                            hops[i] = Math.min(hops[i], depth);
                            continue;
                        }
                    }
                    // Only explore the neighborhood of the end tile (e.g. Versal's CLE_BC_CORE CNODE/BNODEs)
                    if (tile.getTileYCoordinate() != endTile.getTileYCoordinate() ||
                            Math.abs(tile.getTileXCoordinate() - endTile.getTileXCoordinate()) > 1) {
                        continue;
                    }
                    // ... and only those wires that stay within it (e.g. intra-tile singles)
                    if (RouteNode.isSingleDoubleQuadLong(downhill.getIntentCode())) {
                        RouteNodeInfo info = RouteNodeInfo.get(downhill, null);
                        if ((info.endHalfTileXCoordinate >> 1) != tile.getTileXCoordinate() ||
                                info.endTileYCoordinate != tile.getTileYCoordinate()) {
                            continue;
                        }
                    }
                    nextQueue.add(downhill);
                }
            }
            queue = nextQueue;
        }
        return hops;
    }

    /**
     * Check that every interconnect wire whose name gives the side of the INT tile at which it arrives
     * (singles and doubles; quads too on Versal), as well as every INT_NODE_GLOBAL_* (on UltraScale/UltraScale+),
     * reaches a site pin on the half of the INT tile that it is placed on in strictly fewer hops than it does
     * the other half.
     * (UltraScale+ quads are excluded since they can reach both halves at equal cost via INT_NODE_GLOBAL_*.)
     */
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X40Y150",
            "xcvu3p,INT_X30Y60",
            "xcku040,INT_X30Y120",
            "xcku040,INT_X20Y60",
            "xcvc1902,INT_X40Y100",
            "xcvc1902,INT_X60Y150",
    })
    public void testHalfTileReachesOwnSideFirst(String partName, String tileName) {
        Design design = new Design("test", partName);
        Device device = design.getDevice();
        RouteNodeGraph routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[]{}));
        boolean isVersal = routingGraph.isVersal;
        Set<IntentCode> sideNamedIntentCodes = isVersal
                ? EnumSet.of(IntentCode.NODE_HSINGLE, IntentCode.NODE_VSINGLE, IntentCode.NODE_HDOUBLE,
                             IntentCode.NODE_VDOUBLE, IntentCode.NODE_HQUAD, IntentCode.NODE_VQUAD)
                : EnumSet.of(IntentCode.NODE_SINGLE, IntentCode.NODE_DOUBLE);
        Tile tile = device.getTile(tileName);
        int numSideNamed = 0;
        int numGnodes = 0;
        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
            Node node = Node.getNode(tile, wireIndex);
            if (node == null || node.getTile() != tile || node.getWireIndex() != wireIndex) {
                // Not the base wire of this node
                continue;
            }
            String wireName = node.getWireName();
            boolean isGnode = !isVersal && wireName.startsWith("INT_NODE_GLOBAL_");
            boolean isSideNamed = sideNamedIntentCodes.contains(node.getIntentCode()) &&
                    sideNamedPattern.matcher(wireName).matches();
            if (!isGnode && !isSideNamed) {
                continue;
            }
            RouteNodeInfo info = RouteNodeInfo.get(node, routingGraph);
            Assertions.assertEquals(isGnode ? RouteNodeType.LOCAL_SIDE_DEPENDS_ON_SINK : RouteNodeType.NON_LOCAL, info.type);
            Assertions.assertTrue(isGnode || info.halfTileWirelength > 0, node.toString());
            Tile endTile = device.getTile("INT_X" + (info.endHalfTileXCoordinate >> 1) + "Y" + info.endTileYCoordinate);
            Assertions.assertNotNull(endTile, node.toString());

            boolean east = (info.endHalfTileXCoordinate & 1) != 0;
            int[] hops = getHopsToEachSide(node, endTile, 6);
            int ownHops = hops[east ? 0 : 1];
            int otherHops = hops[east ? 1 : 0];
            Assertions.assertTrue(ownHops < otherHops,
                    node + " (" + (east ? "E" : "W") + ") reaches own half of " + endTile + " in " + ownHops +
                            " hops but other half in " + otherHops);
            if (isGnode) {
                numGnodes++;
            } else {
                numSideNamed++;
            }
        }
        // Singles and doubles (and on Versal, quads) in all four directions on both sides
        Assertions.assertTrue(numSideNamed >= 64, "Only " + numSideNamed + " side-named nodes found");
        Assertions.assertEquals(isVersal ? 0 : 32, numGnodes);
    }

    private static final Set<IntentCode> versalBCNodeIntentCodes = EnumSet.of(
            IntentCode.NODE_CLE_BNODE, IntentCode.NODE_CLE_CNODE, IntentCode.NODE_INTF_BNODE, IntentCode.NODE_INTF_CNODE);

    /**
     * Check, for the Versal tiles beside INT tiles, that:
     * (a) every [BC]NODE is placed at the same INT tile half as all the INODEs that it drives (except for INTF_* tile
     *     CNODEs, which must drive only CTRL pins), and
     * (b) every CTRL pin is placed at an INT tile half whose [BC]NODEs drive it: for CTRL_L_B* in CLE_BC_CORE/SLL
     *     tiles (which are driven from the INT tiles on both sides), the western of the two halves; for CTRL_R_B*,
     *     the eastern; and since INTF_* tiles serve only one side of one INT tile, the half of their [BC]NODEs.
     */
    @ParameterizedTest
    @CsvSource({
            // CLE_BC_CORE_X25Y177 is between INT_X24Y177 and INT_X25Y177; CLE_BC_CORE_1_X25Y177 is between
            // INT_X25Y177 and INT_X26Y177
            "xcvc1902,CLE_BC_CORE_X25Y177",
            "xcvc1902,CLE_BC_CORE_1_X25Y177",
            "xcv80,CLE_BC_CORE_X10Y734",
            // SLL_X114Y461 is between INT_X113Y461 and INT_X114Y461
            "xcv80,SLL_X114Y461",
            "xcv80,INTF_LOCF_TR_TILE_X8Y599",
            "xcv80,INTF_LOCF_TL_TILE_X9Y599",
            "xcv80,INTF_ROCF_TR_TILE_X21Y431",
            "xcv80,INTF_ROCF_TL_TILE_X22Y431",
            "xcv80,INTF_ROCF_BR_TILE_X114Y461",
    })
    public void testVersalHalfTileBesideInt(String partName, String tileName) {
        Design design = new Design("test", partName);
        RouteNodeGraph routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[]{}));
        Tile tile = design.getDevice().getTile(tileName);
        Assertions.assertNotNull(tile);
        int numBCNodes = 0;
        int numCtrlPins = 0;
        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
            Node node = Node.getNode(tile, wireIndex);
            if (node == null || node.getTile() != tile || node.getWireIndex() != wireIndex) {
                // Not the base wire of this node
                continue;
            }
            IntentCode ic = node.getIntentCode();
            if (versalBCNodeIntentCodes.contains(ic)) {
                RouteNodeInfo info = RouteNodeInfo.get(node, routingGraph);
                int numInodes = 0;
                for (Node downhill : node.getAllDownhillNodes()) {
                    if (ic == IntentCode.NODE_INTF_CNODE) {
                        // INTF_* tile CNODEs only drive that tile's CTRL pins
                        Assertions.assertEquals(IntentCode.NODE_INTF_CTRL, downhill.getIntentCode(), node + " -> " + downhill);
                        continue;
                    }
                    if (downhill.getIntentCode() != IntentCode.NODE_INODE) {
                        continue;
                    }
                    Assertions.assertEquals(RouteNodeInfo.get(downhill, routingGraph).endHalfTileXCoordinate,
                            info.endHalfTileXCoordinate, node + " -> " + downhill);
                    numInodes++;
                }
                Assertions.assertTrue(ic == IntentCode.NODE_INTF_CNODE || numInodes > 0, node.toString());
                numBCNodes++;
            } else if (ic == IntentCode.NODE_CLE_CTRL || ic == IntentCode.NODE_INTF_CTRL) {
                RouteNodeInfo info = RouteNodeInfo.get(node, routingGraph);
                Assertions.assertEquals(0, info.halfTileWirelength, node.toString());
                Set<Short> uphillHalfTileXs = new HashSet<>();
                for (Node uphill : node.getAllUphillNodes()) {
                    if (versalBCNodeIntentCodes.contains(uphill.getIntentCode())) {
                        uphillHalfTileXs.add(RouteNodeInfo.get(uphill, routingGraph).endHalfTileXCoordinate);
                    }
                }
                short expected;
                if (ic == IntentCode.NODE_INTF_CTRL) {
                    Assertions.assertEquals(1, uphillHalfTileXs.size(), node.toString());
                    expected = uphillHalfTileXs.iterator().next();
                } else {
                    // Driven from the adjacent halves of the INT tiles on either side
                    Assertions.assertEquals(2, uphillHalfTileXs.size(), node.toString());
                    short west = Collections.min(uphillHalfTileXs);
                    Assertions.assertEquals(west + 1, (int) Collections.max(uphillHalfTileXs), node.toString());
                    expected = node.getWireName().startsWith("CTRL_L") ? west : (short) (west + 1);
                }
                Assertions.assertEquals(expected, info.endHalfTileXCoordinate, node.toString());
                numCtrlPins++;
            }
        }
        Assertions.assertTrue(numBCNodes >= 24, "Only " + numBCNodes + " [BC]NODEs found");
        Assertions.assertTrue(numCtrlPins >= 8, "Only " + numCtrlPins + " CTRL pins found");
    }

    /**
     * Check that Versal SLL inputs, which are driven by the [BC]NODEs serving the INT tiles on either side of their SLL
     * tile, are placed between those two INT tile halves: at the eastern half of the INT tile to the west, with
     * RouteNodeGraph.isOnBothSides() also covering the western half of the next; and that super long lines are placed
     * at the same position as the SLL inputs of the SLL tile that they land in.
     */
    @ParameterizedTest
    @CsvSource({
            // SLL_X114Y461 is between INT_X113Y461 and INT_X114Y461
            "xcv80,SLL_X114Y461",
            // SLL_X16Y335 is between INT_X16Y335 and INT_X17Y335
            "xcv80,SLL_X16Y335",
    })
    public void testVersalSllNodesOnBothSides(String partName, String tileName) {
        Design design = new Design("test", partName);
        RouteNodeGraph routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[]{}));
        Tile tile = design.getDevice().getTile(tileName);
        Assertions.assertNotNull(tile);
        int numSllInputs = 0;
        int numSllData = 0;
        for (int wireIndex = 0; wireIndex < tile.getWireCount(); wireIndex++) {
            Node node = Node.getNode(tile, wireIndex);
            if (node == null || node.getTile() != tile || node.getWireIndex() != wireIndex) {
                // Not the base wire of this node
                continue;
            }
            IntentCode ic = node.getIntentCode();
            if (ic == IntentCode.NODE_SLL_INPUT) {
                RouteNodeInfo info = RouteNodeInfo.get(node, routingGraph);
                Assertions.assertEquals(0, info.halfTileWirelength, node.toString());
                Set<Short> uphillHalfTileXs = new HashSet<>();
                for (Node uphill : node.getAllUphillNodes()) {
                    if (versalBCNodeIntentCodes.contains(uphill.getIntentCode())) {
                        uphillHalfTileXs.add(RouteNodeInfo.get(uphill, routingGraph).endHalfTileXCoordinate);
                    }
                }
                Set<Short> expected = new HashSet<>();
                expected.add(info.endHalfTileXCoordinate);
                expected.add((short) (info.endHalfTileXCoordinate + 1));
                Assertions.assertEquals(expected, uphillHalfTileXs, node.toString());
                numSllInputs++;
            } else if (ic == IntentCode.NODE_SLL_DATA) {
                RouteNodeInfo info = RouteNodeInfo.get(node, routingGraph);
                Assertions.assertEquals(2 * routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES, info.halfTileWirelength,
                        node.toString());
                if (info.endTileYCoordinate == node.getTile().getTileYCoordinate()) {
                    // U-turn
                    continue;
                }
                Tile landingTile = null;
                for (Wire w : node.getAllWiresInNode()) {
                    if (w.getTile().getTileYCoordinate() == info.endTileYCoordinate) {
                        landingTile = w.getTile();
                        break;
                    }
                }
                Assertions.assertNotNull(landingTile, node.toString());
                Short landingSllInputHalfTileX = null;
                for (int i = 0; i < landingTile.getWireCount() && landingSllInputHalfTileX == null; i++) {
                    Node n = Node.getNode(landingTile, i);
                    if (n != null && n.getTile() == landingTile && n.getIntentCode() == IntentCode.NODE_SLL_INPUT) {
                        landingSllInputHalfTileX = RouteNodeInfo.get(n, routingGraph).endHalfTileXCoordinate;
                    }
                }
                Assertions.assertNotNull(landingSllInputHalfTileX, landingTile.toString());
                Assertions.assertEquals((short) landingSllInputHalfTileX, info.endHalfTileXCoordinate, node.toString());
                numSllData++;
            }
        }
        Assertions.assertTrue(numSllInputs >= 4, "Only " + numSllInputs + " SLL inputs found");
        Assertions.assertTrue(numSllData >= 1, "Only " + numSllData + " super long lines found");
    }
}
