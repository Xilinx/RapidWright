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

package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestTile {
    @ParameterizedTest
    @CsvSource({
            "xcku025,true",
            "xcku035,false"
    })
    public void testGetWireConnections(String partName, boolean expectThrow) {
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
}
