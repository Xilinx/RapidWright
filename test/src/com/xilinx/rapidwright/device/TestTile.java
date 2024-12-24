/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

public class TestTile {
    @ParameterizedTest
    @CsvSource({
            "xcku025,true",
            "xcku035,false"
    })
    public void testGetWireConnectionsThrows(String partName, boolean expectThrow) {
        Device dev = Device.getDevice(partName);
        Tile tile = dev.getTile("RCLK_CLE_M_L_X31Y149");
        Executable e = () -> tile.getWireConnections(8);
        if (expectThrow) {
            // xcku025 is known to fail
            Assertions.assertThrows(NullPointerException.class, e);
        } else {
            Assertions.assertDoesNotThrow(e);
        }
    }

    @ParameterizedTest
    @CsvSource({
            // get_nodes -downhill -of [get_nodes -of [get_wires CLK_REBUF_VERT_VNOC_BAO_TILE_X30Y471/IF_WRAP_CLK_V_BOT_CLK_VDISTR21]]
            "xcvp1002,CLK_REBUF_VERT_VNOC_BAO_TILE_X30Y471,IF_WRAP_CLK_V_BOT_CLK_VDISTR21,[]",
            // get_nodes -downhill -of [get_nodes -of [get_wires CLK_REBUF_VERT_VNOC_ACO_TILE_X30Y279/IF_WRAP_CLK_V_BOT_CLK_VDISTR21]]
            "xcvp1002,CLK_REBUF_VERT_VNOC_ACO_TILE_X30Y279,IF_WRAP_CLK_V_BOT_CLK_VDISTR21," +
                    "'[RCLK_BRAM_CLKBUF_CORE_X24Y239/IF_HCLK_R_CLK_HDISTR21, CLK_VNOC_AAO_TILE_X30Y239/CLKE2_PD_OPT_DELAY_SSIT_142_I]'",
    })
    public void testGetWireConnections(String partName, String tileName, String wireName, String wireConnections) {
        Device dev = Device.getDevice(partName);
        Tile tile = dev.getTile(tileName);
        Assertions.assertEquals(wireConnections, tile.getWireConnections(wireName).toString());
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu5p,LAG_LAG_X30Y250,'[LAGUNA_X6Y140, LAGUNA_X6Y141, LAGUNA_X7Y140, LAGUNA_X7Y141]',true",

            // FIXME: Known broken -- see https://github.com/Xilinx/RapidWright/issues/745
            "xcvu3p,LAG_LAG_X30Y50,'[]',false",
            "xcvu3p,LAG_LAG_X30Y250,'[]',false",
            "xcvu5p,LAG_LAG_X30Y50,'[]',false",
    })
    public void testGetSites(String partName, String tileName, String expectedSites, boolean expectPass) {
        Device dev = Device.getDevice(partName);
        Tile tile = dev.getTile(tileName);
        Assertions.assertEquals(expectPass, expectedSites.equals(Arrays.toString(tile.getSites())));
    }
}
