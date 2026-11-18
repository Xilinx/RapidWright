/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestSite {
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,BUFG_GT_X0Y96,INT_X0Y256"
    })
    public void testGetIntTile(String deviceName, String siteName, String intTileName) {
        Device device = Device.getDevice(deviceName);
        Site site = device.getSite(siteName);
        Assertions.assertEquals(intTileName, site.getIntTile().getName());
    }

    @ParameterizedTest
    @CsvSource({
            "xcvp1902,SLICE_S0X36Y0,SLR0",
            "xcvp1902,SLICE_S1X36Y0,SLR1",
            "xcvp1902,SLICE_S2X36Y0,SLR2",
            "xcvp1902,SLICE_S3X36Y0,SLR3",
    })
    public void testGetSLR(String deviceName, String siteName, String slrName) {
        Device device = Device.getDevice(deviceName);
        Site site = device.getSite(siteName);
        Assertions.assertEquals(slrName, site.getTile().getSLR().getName());
    }
}
