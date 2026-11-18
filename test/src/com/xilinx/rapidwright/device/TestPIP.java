/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class TestPIP {

    @ParameterizedTest
    @ValueSource(strings = {"xczu3eg","xc7a12t"})
    public void testGetArbitraryPIP(String deviceName) {
        Device d = Device.getDevice(deviceName);

        for (Tile t : d.getAllTiles()) {
            for (PIP p : t.getPIPs()) {
                Node start = p.getStartNode();
                if (start == null) continue;
                Node end = p.getEndNode();
                if (end == null) continue;

                PIP pip = PIP.getArbitraryPIP(start, end);
                Assertions.assertEquals(start, pip.getStartNode());
                Assertions.assertEquals(end, pip.getEndNode());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X0Y0/BYPASS_W14,INT_X0Y0/INT_NODE_IMUX_50_INT_OUT0,true",
            "xcvu3p,INT_X9Y9/INT_NODE_IMUX_50_INT_OUT0,INT_X9Y9/BYPASS_W14,false"
    })
    public void testGetArbitraryPIPReversed(String deviceName, String startNodeName, String endNodeName, boolean isReversed) {
        Device d = Device.getDevice(deviceName);
        Node startNode = d.getNode(startNodeName);
        Node endNode = d.getNode(endNodeName);
        PIP pip = PIP.getArbitraryPIP(startNode, endNode);
        Assertions.assertEquals(isReversed, pip.isReversed());
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X21Y240,BYPASS_E14,INT_NODE_IMUX_18_INT_OUT0,true",
            "xcvu3p,INT_X21Y240,INT_NODE_IMUX_18_INT_OUT0,BYPASS_E14,false"
    })
    public void testPIP(String deviceName, String tileName, String startWireName, String endWireName, boolean isReversed) {
        Device d = Device.getDevice(deviceName);
        Tile t = d.getTile(tileName);
        int startWireIndex = t.getWireIndex(startWireName);
        int endWireIndex = t.getWireIndex(endWireName);
        PIP p = new PIP(t, startWireIndex, endWireIndex);
        if (isReversed) {
            Assertions.assertTrue(p.isReversed());
            Assertions.assertEquals(startWireName, p.getEndWireName());
            Assertions.assertEquals(endWireName, p.getStartWireName());
        } else {
            Assertions.assertFalse(p.isReversed());
            Assertions.assertEquals(startWireName, p.getStartWireName());
            Assertions.assertEquals(endWireName, p.getEndWireName());
        }

        Assertions.assertTrue(p.deepEquals(new PIP(t, startWireName, endWireName)));
        Assertions.assertTrue(p.deepEquals(new PIP(d, tileName, startWireName, endWireName)));
    }
}
