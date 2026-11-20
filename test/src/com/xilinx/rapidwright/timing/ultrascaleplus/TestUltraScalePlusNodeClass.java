/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.ultrascaleplus;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.CrossingIndex;
import com.xilinx.rapidwright.timing.family.InterconnectParams;

public class TestUltraScalePlusNodeClass {

    /** Column classes, in the order of the crossing counts */
    private static final List<String> COL_CLASSES = Arrays.asList(
            "^LAG_LAG$",                                                        // 0 LAG
            "^BRAM$",                                                           // 1 BRAM
            "^DSP$",                                                            // 2 DSP
            "^URAM_URAM_(DELAY_)?FT$",                                          // 3 URAM
            "^(XIPHY_BYTE_|HPIO_[LR]$|HRIO_[LR]$|CMT_[LR]$|INT_IBRK_)",         // 4 IO
            "^HDIO_(TOP|BOT)_",                                                 // 5 HDIO
            "^(PCIE4|PCIE4C|CMAC|ILKN)",                                        // 6 HB
            "^(CFG_CONFIG|CFRM_CONFIG|CFRM_AMS_CFGIO|CFGIO_IOB|CFRM_[BT]$|AMS$)", // 7 CFG
            "^PSS_",                                                            // 8 PS
            "^(GTH|GTY|GTM|GTFY)_",                                             // 9 GT
            "^(RFADC|RFDAC|HSADC|HSDAC|RFAMS)",                                 // 10 RF
            "^(CFRM_CBRK|CFG_M12BUF|AMS_M12BUF)");                              // 11 CBRK
    private static final List<String> ROW_CLASSES = Arrays.asList(
            "^RCLK_INT_",                                                       // 12 RCLK
            "^INT_RBRK",                                                        // 13 RBRK
            "^INT_TERM_[TB]$");                                                 // 14 SLR

    private static List<String> toStrings(List<Pattern> patterns) {
        return patterns.stream().map(Pattern::pattern).collect(Collectors.toList());
    }

    @Test
    public void testNoInterconnectWireIsOther() {
        Device device = Device.getDevice("xcvu3p");
        byte[][] classes = UltraScalePlusNodeClass.getWireClasses(device);
        Tile tile = device.getTile("INT_X40Y120");
        byte[] intClasses = classes[TileTypeEnum.INT.ordinal()];
        Assertions.assertEquals(tile.getWireCount(), intClasses.length);
        int numRouting = 0;
        for (int i = 0; i < tile.getWireCount(); i++) {
            IntentCode ic = tile.getWireIntentCode(i);
            if (ic == IntentCode.INTENT_DEFAULT) {
                // VCC_WIRE
                continue;
            }
            numRouting++;
            Assertions.assertNotEquals(UltraScalePlusNodeClass.OTHER, UltraScalePlusNodeClass.values()[intClasses[i]],
                    tile.getWireName(i) + " " + ic);
        }
        Assertions.assertTrue(numRouting > 700);
    }

    /**
     * In all tiles, the only wires with an interconnect intent code that carry no delay (OTHER) are the clock pin
     * feeds of the RCLK_INT tiles; the INT_NODE_IMUX nodes of the RCLK_INT tiles, which feed the clock enables of the
     * clock leaf sites, are INODEs like those of the INT tiles.
     */
    @ParameterizedTest
    @CsvSource({"xcvu3p", "xczu3eg"})
    public void testNoInterconnectWireOfAnyTileIsOther(String deviceName) {
        Device device = Device.getDevice(deviceName);
        byte[][] classes = UltraScalePlusNodeClass.getWireClasses(device);
        Set<IntentCode> interconnect = EnumSet.of(IntentCode.NODE_SINGLE, IntentCode.NODE_DOUBLE, IntentCode.NODE_HQUAD,
                IntentCode.NODE_VQUAD, IntentCode.NODE_HLONG, IntentCode.NODE_VLONG, IntentCode.NODE_LOCAL,
                IntentCode.NODE_PINFEED, IntentCode.NODE_PINBOUNCE);
        Set<TileTypeEnum> seen = EnumSet.noneOf(TileTypeEnum.class);
        int numRclkInodes = 0;
        for (Tile tile : device.getAllTiles()) {
            if (!seen.add(tile.getTileTypeEnum())) {
                continue;
            }
            byte[] tileClasses = classes[tile.getTileTypeEnum().ordinal()];
            for (int i = 0; i < tile.getWireCount(); i++) {
                String name = tile.getWireName(i);
                UltraScalePlusNodeClass c = UltraScalePlusNodeClass.values()[tileClasses[i]];
                if (tile.getTileTypeEnum().name().startsWith("RCLK_INT_") && name.startsWith("INT_NODE_IMUX_")) {
                    Assertions.assertEquals(UltraScalePlusNodeClass.INODE, c, tile + "/" + name);
                    numRclkInodes++;
                }
                if (c == UltraScalePlusNodeClass.OTHER && interconnect.contains(tile.getWireIntentCode(i))) {
                    Assertions.assertTrue(tile.getTileTypeEnum().name().startsWith("RCLK_INT_")
                            && (name.startsWith("CLK_LEAF_SITES_") || name.startsWith("INT_RCLK_TO_CLK_")),
                            tile + "/" + name + " " + tile.getWireIntentCode(i));
                }
            }
        }
        Assertions.assertTrue(numRclkInodes > 0);
    }

    /** An RCLK_INT INT_NODE_IMUX node is charged the delay of an INODE */
    @Test
    public void testRclkInodeDelay() {
        Device device = Device.getDevice("xcvu3p");
        UltraScalePlusDelayEstimator estimator = new UltraScalePlusDelayEstimator(device, null);
        Node rclk = device.getNode("RCLK_INT_L_X40Y149/INT_NODE_IMUX_0_INT_OUT0");
        Node inode = device.getNode("INT_X40Y120/INT_NODE_IMUX_0_INT_OUT0");
        Assertions.assertEquals(UltraScalePlusNodeClass.INODE, UltraScalePlusNodeClass.of(rclk));
        Assertions.assertTrue(estimator.getDelayOf(rclk) > 0);
        Assertions.assertEquals(estimator.getDelayOf(inode), estimator.getDelayOf(rclk));
    }

    @ParameterizedTest
    @CsvSource({
            "INT_X40Y120/EE1_E_BEG0,SINGLE_E",
            "INT_X40Y120/WW1_W_BEG0,SINGLE_W",
            "INT_X40Y120/NN1_E_BEG0,SINGLE_N",
            "INT_X40Y120/SS1_W_BEG0,SINGLE_S",
            "INT_X40Y120/INT_INT_SDQ_32_INT_OUT1,SINGLE_INT",
            "INT_X40Y120/EE2_W_BEG0,DOUBLE_E",
            "INT_X40Y120/SS2_E_BEG0,DOUBLE_S",
            "INT_X40Y120/WW4_E_BEG0,HQUAD_W",
            "INT_X40Y120/NN4_W_BEG0,VQUAD_N",
            "INT_X40Y120/EE12_BEG0,HLONG_E",
            "INT_X40Y120/SS12_BEG0,VLONG_S",
            "INT_X40Y120/INT_NODE_SDQ_0_INT_OUT1,SDQNODE",
            "INT_X40Y120/SDQNODE_E_0_FT1,SDQNODE_FT",
            "INT_X40Y120/INT_NODE_IMUX_0_INT_OUT0,INODE",
            "INT_X40Y120/INODE_E_1_FT1,INODE_FT",
            "INT_X40Y120/INT_NODE_GLOBAL_0_INT_OUT0,GNODE",
            "INT_X40Y120/IMUX_E10,IMUX",
            "INT_X40Y120/CTRL_E0,CTRL",
            "INT_X40Y120/BYPASS_E10,BYPASS",
            "INT_X40Y120/BOUNCE_E_0_FT1,BOUNCE_FT",
            "CLEL_R_X40Y120/CLE_CLE_L_SITE_0_AQ,CLE_OUTPUT",
            "CLEL_R_X40Y120/CLE_CLE_L_SITE_0_COUT,DEDICATED",
            "DSP_X23Y100/DSP_DSP48_0_P3,OUTPUT",
            "INT_INTF_R_X23Y100/LOGIC_OUTS_L8,INT_INTF",
            "LAG_LAG_X66Y245/LAG_MUX_ATOM_3_TXOUT,LAGUNA_TXOUT",
            "LAG_LAG_X66Y245/UBUMP3,LAGUNA_DATA",
            "LAG_LAG_X66Y245/LAG_LAGUNA_SITE_0_RXD3,LAGUNA_RXD",
            "LAG_LAG_X66Y245/RXD3,LAGUNA_RX",
            "RCLK_INT_L_X40Y149/CLK_LEAF_SITES_0_CLK_LEAF,OTHER",
    })
    public void testNodeClass(String nodeName, String expected) {
        Device device = Device.getDevice("xcvu3p");
        Node node = device.getNode(nodeName);
        Assertions.assertNotNull(node, nodeName);
        Assertions.assertEquals(UltraScalePlusNodeClass.valueOf(expected), UltraScalePlusNodeClass.of(node));
        Assertions.assertEquals(UltraScalePlusNodeClass.valueOf(expected).ordinal(), UltraScalePlusNodeClass.ordinalOf(node));
    }

    @ParameterizedTest
    @CsvSource({
            // node, span length, nonzero crossing counts (class index=count)
            "INT_X40Y120/EE12_BEG0,7,3=1 11=2 13=1",   // URAM, two CBRK columns, and its tap below the CR boundary
            "INT_X22Y120/EE4_E_BEG0,2,2=1 11=1",       // DSP and CBRK columns
            "INT_X10Y60/WW4_E_BEG3,2,1=1",             // BRAM column
            "INT_X40Y28/NN2_E_BEG0,2,12=1",            // RCLK row
            "INT_X40Y120/SS12_BEG0,12,13=1",           // CR boundary (RBRK row)
            "INT_X40Y60/NN4_E_BEG0,4,",                // nothing in between
            "INT_X40Y120/INT_NODE_SDQ_0_INT_OUT1,0,",
    })
    public void testCrossings(String nodeName, int expectedLength, String expectedCounts) {
        Device device = Device.getDevice("xcvu3p");
        InterconnectParams params = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        Assertions.assertEquals(COL_CLASSES, toStrings(params.getColClassPatterns()));
        Assertions.assertEquals(ROW_CLASSES, toStrings(params.getRowClassPatterns()));
        CrossingIndex index = CrossingIndex.of(device, params);
        Assertions.assertEquals(COL_CLASSES.size() + ROW_CLASSES.size(), index.getNumCrossingClasses());
        int[] expected = new int[index.getNumCrossingClasses()];
        if (expectedCounts != null) {
            for (String kv : expectedCounts.split(" ")) {
                String[] t = kv.split("=");
                expected[Integer.parseInt(t[0])] = Integer.parseInt(t[1]);
            }
        }
        int[] counts = new int[index.getNumCrossingClasses()];
        int length = index.getCrossings(device.getNode(nodeName), counts);
        Assertions.assertEquals(expectedLength, length);
        Assertions.assertArrayEquals(expected, counts);
    }
}
