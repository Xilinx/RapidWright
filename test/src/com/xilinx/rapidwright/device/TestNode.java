/*
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.device;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.util.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestNode {
    @ParameterizedTest
    @CsvSource({
            "xcvu19p-fsva3824-1-e,INT_X182Y338/WW12_BEG5",
            "xcvu19p-fsva3824-1-e,INT_X182Y338/NN12_BEG5",
    })
    public void testGetDownhillUphillNodesUnique(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);

        List<Node> downhill = node.getAllDownhillNodes();
        if (node.getWireName().equals("WW12_BEG5")) {
            Assertions.assertNotEquals(downhill.size(), new HashSet<>(downhill).size());
        } else {
            Assertions.assertEquals(downhill.size(), new HashSet<>(downhill).size());
        }

        Collection<Node> downhillUnique = node.getAllDownhillNodes(new HashSet<>());
        Assertions.assertEquals(downhillUnique.size(), new HashSet<>(downhillUnique).size());

        List<Node> uphill = node.getAllUphillNodes();
        Assertions.assertEquals(uphill.size(), new HashSet<>(uphill).size());
        
        Collection<Node> uphillUnique = node.getAllUphillNodes(new HashSet<>());
        Assertions.assertEquals(uphillUnique.size(), new HashSet<>(uphillUnique).size());
    }
    
    @Test
    public void testNullNode() {
        Device d = Device.getDevice("xcvm1802-vfvc1760-1LHP-i-L");
        PIP p = d.getPIP("BLI_LS_CORE_X90Y335/BLI_LS_CORE_R180.HSR_GRP1_A_BLI_LOGIC_OUTS0->>BLI_GRP1_A_BLI_LOGIC_OUTS0");
        Assertions.assertNull(p.getStartNode());
    }

    @Test
    public void testUphillNodeIsInvalid() {
        // link_design -part [lindex [get_parts xcvu440*] 0]
        // get_nodes -uphill -of [get_nodes INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0]
        // WARNING: [Vivado 12-2683] No nodes matched 'get_nodes -uphill -of [get_nodes INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0]'
        Device d = Device.getDevice("xcvu440");
        Node n = d.getNode("INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0");
        Assertions.assertNotNull(n);
        Assertions.assertTrue(n.getAllUphillNodes().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            // UltraScale+ part
            "xcvu3p,INT_X37Y220,BOUNCE_.*,true",
            "xcvu3p,INT_X37Y220,BYPASS_.*,true",
            "xcvu3p,INT_X37Y220,INT_NODE_GLOBAL_.*,true",
            "xcvu3p,INT_X37Y220,INT_NODE_IMUX_.*,true",
            "xcvu3p,INT_X37Y220,INODE_.*,true",
            "xcvu3p,INT_X37Y220,INT_INT_SDQ_.*,false", // IntentCode.NODE_SINGLE
            "xcvu3p,INT_X37Y220,INT_NODE_SDQ_.*,false",
            "xcvu3p,INT_X37Y220,SDQNODE_.*,false",
            "xcvu3p,INT_X37Y220,IMUX_.*,true",
            "xcvu3p,INT_X37Y220,CTRL_.*,true",
            "xcvu3p,INT_X37Y220,(NN|EE|SS|WW)1_.*,false",
            "xcvu3p,INT_X37Y220,(NN|EE|SS|WW)2_.*,false",
            "xcvu3p,INT_X37Y220,(NN|EE|SS|WW)4_.*,false",
            "xcvu3p,INT_X37Y220,(NN|EE|SS|WW)12_.*,false",
            "xcvu3p,CLEM_X37Y220,CLE_CLE_M_SITE_0_[A-H](_O|Q|Q2|MUX),false",

            // UltraScale part
            "xcvu065,INT_X38Y220,BOUNCE_.*,true",
            "xcvu065,INT_X38Y220,BYPASS_.*,true",
            "xcvu065,INT_X38Y220,INT_NODE_GLOBAL_.*,true",
            "xcvu065,INT_X38Y220,INT_NODE_IMUX_.*,true",
            "xcvu065,INT_X38Y220,INODE_.*,true",
            "xcvu065,INT_X38Y220,INT_INT_SINGLE_.*,false", // IntentCode.NODE_SINGLE
            "xcvu065,INT_X38Y220,INT_NODE_SINGLE_DOUBLE_.*,false",
            "xcvu065,INT_X38Y220,INT_NODE_QUAD_LONG_.*,false",
            "xcvu065,INT_X38Y220,IMUX_.*,true",
            "xcvu065,INT_X38Y220,CTRL_.*,true",
            "xcvu065,INT_X37Y220,(NN|EE|SS|WW)1_.*,false",
            "xcvu065,INT_X37Y220,(NN|EE|SS|WW)2_.*,false",
            "xcvu065,INT_X37Y220,(NN|EE|SS|WW)4_.*,false",
            "xcvu065,INT_X37Y220,(NN|EE|SS|WW)5_.*,false",
            "xcvu065,INT_X37Y220,(NN|EE|SS|WW)12_.*,false",
            "xcvu065,INT_X37Y220,(NN|EE|SS|WW)16_.*,false",
            "xcvu065,INT_X38Y220,QLND.*,false",
            "xcvu065,INT_X38Y220,SDND.*,false",
            "xcvu065,CLE_M_X38Y220,CLE_CLE_M_SITE_0_[A-H](_O|Q|Q2|MUX),false",
    })
    public void testNodeReachabilityUltraScale(String partName, String tileName, String wireRegex, boolean local) {
        Device device = Device.getDevice(partName);
        Tile baseTile = device.getTile(tileName);
        Queue<Node> queue = new ArrayDeque<>();
        Set<IntentCode> intentCodes = EnumSet.noneOf(IntentCode.class);
        for (String wireName : baseTile.getWireNames()) {
            if (!wireName.matches(wireRegex)) {
                continue;
            }
            Node node = Node.getNode(baseTile, wireName);
            queue.add(node);
            intentCodes.add(node.getIntentCode());
        }
        System.out.println("Initial queue.size() = " + queue.size());
        System.out.println("Intent codes = " + intentCodes);

        // Print out the prefixes of nodes that are immediately uphill of these wire prefixes
        // (i.e. "BOUNCE_E_0_FT1" -> "BOUNCE_")
        System.out.println("Immediately uphill:");
        boolean ultraScalePlus = partName.endsWith("p");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream)
                .map(n -> n.getWireName() + " (" + n.getIntentCode() + ")")
                .map(s -> s.replaceFirst("((BOUNCE|BYPASS|CTRL|INT_NODE_[^_]+|INODE|IMUX|SDQNODE)_)[^\\(]+", "$1"))
                .map(s -> s.replaceFirst(
                                       // UltraScale+
                        ultraScalePlus ? "((CLE_CLE_[LM]_SITE_0|CLK_LEAF_SITES|INT_INT_SDQ|(NN|EE|SS|WW)(1|2|4|12))_)[^\\(]+"
                                       // UltraScale
                                       : "((CLE_CLE_[LM]_SITE_0|CLK_BUFCE_LEAF_X16_1_CLK|EE[124]|INT_INT_SINGLE|(NN|SS)(1|2|4|5|12|16)|(EE|WW)(1|2|4|12)|QLND(NW|SE|SW)|SDND[NS]W)_)[^\\(]+",
                        "$1"))
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        // Print out the prefixes of nodes that are immediately downhill of these wire prefixes
        System.out.println("Immediately downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream)
                .map(n -> n.getWireName() + " (" + n.getIntentCode() + ")")
                .map(s -> s.replaceFirst("((BOUNCE|BYPASS|CTRL|IMUX|INT_NODE_[^_]+|INODE|SDQNODE)_)[^\\(]+", "$1"))
                .map(s -> s.replaceFirst(
                                       // UltraScale+
                        ultraScalePlus ? "((CLE_CLE_[LM]_SITE_0|INT_INT_SDQ|(NN|EE|SS|WW)(1|2|4|12))_)[^\\(]+"
                                       // UltraScale
                                       : "((CLE_CLE_[LM]_SITE_0|INT_INT_SINGLE|(NN|SS)(1|2|4|5|12|16)|(EE|WW)(1|2|4|12)|QLND(NW|S[EW])|SDND[NS]W)_)[^\\(]+",
                        "$1"))
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        Set<Node> visited = new HashSet<>();
        boolean exitsBaseTile = false;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            String wireName = node.getWireName();
            if ((ultraScalePlus && wireName.matches("(INT_NODE_SDQ|SDQNODE)_.*")) ||                                        // UltraScale+
                (!ultraScalePlus && wireName.matches("(INT_NODE_(SINGLE_DOUBLE|QUAD_LONG)|QLND(NW|SE|SW)|SDND[NS]W)_.*") || // UltraScale
                EnumSet.of(IntentCode.NODE_SINGLE, IntentCode.NODE_DOUBLE, IntentCode.NODE_HQUAD, IntentCode.NODE_VQUAD, IntentCode.NODE_HLONG, IntentCode.NODE_VLONG)
                        .contains(node.getIntentCode()))
            ) {
                // Do not descend into SDQNODEs or SDQLs
                exitsBaseTile = true;
                continue;
            }
            for (Node downhill : node.getAllDownhillNodes()) {
                if (!visited.add(downhill)) {
                    continue;
                }
                if (!Utils.isInterConnect(downhill.getTile().getTileTypeEnum())) {
                    continue;
                }
                // Check that they are all in the same column
                Assertions.assertEquals(baseTile.getTileXCoordinate(),
                        downhill.getTile().getTileXCoordinate());
                queue.add(downhill);
            }
        }
        System.out.println("visited.size() = " + visited.size());

        // Local nodes should never exit the tile
        Assertions.assertNotEquals(local, exitsBaseTile);
    }

    @ParameterizedTest
    @CsvSource({
            "xcvp1002,INT_X38Y220,NODE_PINBOUNCE,true",
            "xcvp1002,INT_X38Y220,NODE_INODE,true",
            "xcvp1002,INT_X38Y220,NODE_IMUX,true",
            "xcvp1002,INT_X38Y220,NODE_SDQNODE,false",
            "xcvp1002,INT_X38Y220,NODE_HSINGLE,false",
            "xcvp1002,INT_X38Y220,NODE_VSINGLE,false",
            "xcvp1002,INT_X38Y220,NODE_HDOUBLE,false",
            "xcvp1002,INT_X38Y220,NODE_VDOUBLE,false",
            "xcvp1002,INT_X38Y220,NODE_HQUAD,false",
            "xcvp1002,INT_X38Y220,NODE_VQUAD,false",
            "xcvp1002,INT_X38Y220,NODE_HLONG6,false",
            "xcvp1002,INT_X38Y220,NODE_HLONG10,false",
            "xcvp1002,INT_X38Y220,NODE_VLONG7,false",
            "xcvp1002,INT_X38Y220,NODE_VLONG12,false",
            "xcvp1002,CLE_BC_CORE_X37Y220,NODE_CLE_BNODE,true",
            "xcvp1002,CLE_BC_CORE_X37Y220,NODE_CLE_CNODE,true",
            "xcvp1002,CLE_BC_CORE_X37Y220,NODE_CLE_CTRL,true",
            "xcvp1002,CLE_W_CORE_X38Y220,NODE_PINFEED,true",
            "xcvp1002,CLE_E_CORE_X38Y220,NODE_CLE_OUTPUT,false",
            "xcvp1002,CLE_W_CORE_X38Y220,NODE_CLE_OUTPUT,false",
    })
    public void testNodeReachabilityVersal(String partName, String tileName, String intentCodeName, boolean local) {
        Device device = Device.getDevice(partName);
        Tile baseTile = device.getTile(tileName);
        IntentCode baseIntentCode = IntentCode.valueOf(intentCodeName);
        Queue<Node> queue = new ArrayDeque<>();
        for (int wireIdx = 0; wireIdx < baseTile.getWireCount(); wireIdx++) {
            if (baseTile.getWireIntentCode(wireIdx) != baseIntentCode) {
                continue;
            }
            queue.add(Node.getNode(baseTile, wireIdx));
        }
        System.out.println("Initial queue.size() = " + queue.size());
        System.out.println("Initial queue = " + queue);

        // Print out the intent code of nodes that are immediately uphill of this intent code
        System.out.println("Immediately uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        // Print out the intent code of nodes that are immediately downhill of this intent code
        System.out.println("Immediately downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        TileTypeEnum baseTileTypeEnum = baseTile.getTileTypeEnum();
        Set<Node> visited = new HashSet<>();
        boolean exitsBaseTile = false;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (EnumSet.of(IntentCode.NODE_SDQNODE, IntentCode.NODE_HSINGLE, IntentCode.NODE_VSINGLE,
                            IntentCode.NODE_HDOUBLE, IntentCode.NODE_VDOUBLE,
                            IntentCode.NODE_HQUAD, IntentCode.NODE_VQUAD,
                            IntentCode.NODE_HLONG10, IntentCode.NODE_HLONG6,
                            IntentCode.NODE_VLONG12, IntentCode.NODE_VLONG7).contains(node.getIntentCode())) {
                // Do not descend into SDQNODEs or any SDQLs
                exitsBaseTile = true;
                continue;
            }
            for (Node downhill : node.getAllDownhillNodes()) {
                if (!visited.add(downhill)) {
                    continue;
                }
                if (!Utils.isInterConnect(downhill.getTile().getTileTypeEnum())) {
                    continue;
                }
                if (Utils.isCLB(baseTileTypeEnum)) {
                    if (EnumSet.of(IntentCode.NODE_VLONG7, IntentCode.NODE_VLONG12, IntentCode.NODE_HLONG10, IntentCode.NODE_HLONG6).contains(downhill.getIntentCode())) {
                        // Ignore long wires that obviously leave the tile
                    } else {
                        // All other wires should have the same X coordinate, if not the same Y
                        Assertions.assertEquals(baseTile.getTileXCoordinate(), downhill.getTile().getTileXCoordinate());
                        if (baseTile.getTileYCoordinate() != downhill.getTile().getTileYCoordinate()) {
                            // Only exception for Y are specific NODE_SDQNODEs with the following wire name
                            Assertions.assertTrue(downhill.getWireName().matches("OUT_[NEWS]NODE_[EW]_\\d+"));
                            Assertions.assertEquals(IntentCode.NODE_SDQNODE, downhill.getIntentCode());
                            Assertions.assertTrue(1 >= Math.abs(baseTile.getTileYCoordinate() - downhill.getTile().getTileYCoordinate()));
                        }
                    }
                }
                // All INT-to-INT connections should be to the same tile
                else if (baseTileTypeEnum == TileTypeEnum.CLE_BC_CORE) {
                    // Except CLE_BC_CORE tiles which spans two adjacent INT tiles
                    if (baseTile != downhill.getTile()) {
                        Assertions.assertTrue(1 >= Math.abs(baseTile.getTileXCoordinate() - downhill.getTile().getTileXCoordinate()));
                        Assertions.assertEquals(TileTypeEnum.INT, downhill.getTile().getTileTypeEnum());
                    }
                } else {
                    Assertions.assertEquals(baseTile, downhill.getTile());
                }
                queue.add(downhill);
            }
        }
        System.out.println("visited.size() = " + visited.size());

        // Local nodes should never exit the tile
        Assertions.assertNotEquals(local, exitsBaseTile);
    }

    @ParameterizedTest
    @CsvSource({
            // https://github.com/Xilinx/RapidWright/issues/983
            "xcvh1782,GTYP_QUAD_SINGLE_X0Y240/GTYP_QUAD_SITE_0_APB3PRDATA_0_"
    })
    public void testWireNodeMismatch(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);
        Assertions.assertNotNull(node);

        Wire[] allWiresInNode = node.getAllWiresInNode();
        Assertions.assertNotEquals(0, allWiresInNode.length);
        for (Wire wire : allWiresInNode) {
            Assertions.assertEquals(node, wire.getNode());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X0Y0/BYPASS_W14,INT_X0Y0/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14",
            "xcvu3p,INT_X0Y0/INT_NODE_IMUX_50_INT_OUT0,",
    })
    public void testGetAllDownhillPIPsReversed(String deviceName, String startNodeName, String reversedPIPString) {
        Device d = Device.getDevice(deviceName);
        Node startNode = d.getNode(startNodeName);
        for (PIP pip : startNode.getAllDownhillPIPs()) {
            if (pip.toString().equals(reversedPIPString)) {
                Assertions.assertTrue(pip.isReversed());
            } else {
                Assertions.assertFalse(pip.isReversed());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X0Y0/INT_NODE_IMUX_50_INT_OUT0,INT_X0Y0/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14",
            "xcvu3p,INT_X0Y0/BYPASS_W14,",
    })
    public void testGetAllUphillPIPsReversed(String deviceName, String endNodeName, String reversedPIPString) {
        Device d = Device.getDevice(deviceName);
        Node endNode = d.getNode(endNodeName);
        for (PIP pip : endNode.getAllUphillPIPs()) {
            if (pip.toString().equals(reversedPIPString)) {
                Assertions.assertTrue(pip.isReversed());
            } else {
                Assertions.assertFalse(pip.isReversed());
            }
        }
    }
}

