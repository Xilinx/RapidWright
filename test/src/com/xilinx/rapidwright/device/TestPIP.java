/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
}
