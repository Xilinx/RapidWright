/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


public class TestTimingModel {
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,2,0,61,309",
            "xck26,2,0,169,247",
            "xczu7ev,27,240,161,123",
            "vu19p,1,0,58,1242",
    })
    public void checkFindReferenceTile (String deviceName,
                                                int expectedTileX, int expectedTileY,
                                                int expectedCol, int expectedRow) {
        TimingModel model = new TimingModel(Device.getDevice(deviceName));
        model.build();
        Tile tile = model.getRefIntTile();
        System.out.println(deviceName + " " + tile.getTileXCoordinate() + " " + tile.getTileYCoordinate() + " " + tile.getColumn() + " " + tile.getRow());
        // check tile coor that can be visually checked in Vivado gui
        Assertions.assertEquals(expectedTileX,tile.getTileXCoordinate());
        Assertions.assertEquals(expectedTileY,tile.getTileYCoordinate());
        // check tile consistent with what specify in the file
        Assertions.assertEquals(expectedCol,tile.getColumn());
        Assertions.assertEquals(expectedRow,tile.getRow());
    }
}
