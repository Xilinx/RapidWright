/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.interchange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.capnproto.MessageBuilder;
import org.capnproto.StructList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;

public class TestConstantDefinitions {

    /**
     * Writes the tied wire/node constants of a device and reads them back, checking that every
     * tied node resolves to the same constant as the device model.
     *
     * In xcau10p, INT_IBRK_FSR2IO/IO_TO_CTR_FT0_3 is the base wire of GND-tied nodes in several
     * tiles (e.g. INT_IBRK_FSR2IO_X0Y140), but INT_IBRK_FSR2IO_X0Y174/IO_TO_CTR_FT0_3 belongs to the
     * VCC-tied node HPIO_AUX_IO_TERM_L_TOP_FT_X0Y150/VCC_WIRE0, which must be written as an exception.
     */
    @ParameterizedTest
    @CsvSource({
            "xcau10p,HPIO_AUX_IO_TERM_L_TOP_FT_X0Y150/VCC_WIRE0,INT_IBRK_FSR2IO_X0Y140/IO_TO_CTR_FT0_3",
            "xc7a15t,,",
    })
    public void testTiedNodes(String deviceName, String vccNodeName, String gndNodeName) {
        Device device = Device.getDevice(deviceName);
        StringEnumerator allStrings = new StringEnumerator();
        MessageBuilder message = new MessageBuilder();
        DeviceResources.Device.Builder devBuilder = message.initRoot(DeviceResources.Device.factory);

        Set<TileTypeEnum> tileTypeSet = new LinkedHashSet<>();
        for (Tile tile : device.getAllTiles()) {
            tileTypeSet.add(tile.getTileTypeEnum());
        }
        List<TileTypeEnum> tileTypeEnums = new ArrayList<>(tileTypeSet);
        StructList.Builder<TileType.Builder> tileTypeList = devBuilder.initTileTypeList(tileTypeEnums.size());
        Map<TileTypeEnum, TileType.Builder> tileTypeBuilders = new HashMap<>();
        for (int i = 0; i < tileTypeEnums.size(); i++) {
            tileTypeBuilders.put(tileTypeEnums.get(i), tileTypeList.get(i));
        }

        DeviceResources.Device.Constants.Builder constantsBuilder = devBuilder.initConstants();
        ConstantDefinitions.writeTiedWires(allStrings, device, constantsBuilder, tileTypeBuilders);

        Map<TileTypeEnum, TileType.Reader> tileTypeReaders = new HashMap<>();
        for (Map.Entry<TileTypeEnum, TileType.Builder> e : tileTypeBuilders.entrySet()) {
            tileTypeReaders.put(e.getKey(), e.getValue().asReader());
        }
        ConstantDefinitions constants = new ConstantDefinitions(allStrings, constantsBuilder.asReader(),
                tileTypeReaders);

        if (vccNodeName != null) {
            Node vccNode = device.getNode(vccNodeName);
            Assertions.assertTrue(vccNode.isTiedToVcc());
            Assertions.assertTrue(constants.isNodeTiedVcc(vccNode));
            Assertions.assertFalse(constants.isNodeTiedGnd(vccNode));
        }
        if (gndNodeName != null) {
            Node gndNode = device.getNode(gndNodeName);
            Assertions.assertTrue(gndNode.isTiedToGnd());
            Assertions.assertTrue(constants.isNodeTiedGnd(gndNode));
            Assertions.assertFalse(constants.isNodeTiedVcc(gndNode));
        }

        for (Tile tile : device.getAllTiles()) {
            for (int i = 0; i < tile.getWireCount(); i++) {
                Node node = Node.getNode(tile, i);
                if (node == null || !node.isTied() || node.getTile() != tile || node.getWireIndex() != i) {
                    continue;
                }
                Assertions.assertEquals(node.isTiedToGnd(), constants.isNodeTiedGnd(node), node.toString());
                Assertions.assertEquals(node.isTiedToVcc(), constants.isNodeTiedVcc(node), node.toString());
            }
        }
    }
}
