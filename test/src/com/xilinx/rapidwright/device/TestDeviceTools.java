/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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
import org.junit.jupiter.api.Test;

public class TestDeviceTools {

    @Test
    public void testGetIntTileVersal() {
        Device d = Device.getDevice("xcve2002-sbva484-1LHP-i-L");
        Assertions.assertEquals("INT_X7Y44", DeviceTools.getIntTile(d.getSite("SLICE_X20Y40")).getName());
        Assertions.assertEquals("INT_X7Y44", DeviceTools.getIntTile(d.getSite("SLICE_X21Y40")).getName());
        Assertions.assertEquals("INT_X9Y45", DeviceTools.getIntTile(d.getSite("DSP_X0Y20")).getName());
        Assertions.assertEquals("INT_X9Y45", DeviceTools.getIntTile(d.getSite("DSP58_CPLX_X0Y20")).getName());
        Assertions.assertEquals("INT_X13Y45", DeviceTools.getIntTile(d.getSite("URAM288_X1Y11")).getName());
        Assertions.assertEquals("INT_X12Y45", DeviceTools.getIntTile(d.getSite("RAMB36_X1Y11")).getName());
        Assertions.assertEquals("INT_X12Y41", DeviceTools.getIntTile(d.getSite("RAMB18_X1Y20")).getName());
        Assertions.assertEquals("INT_X12Y43", DeviceTools.getIntTile(d.getSite("RAMB18_X1Y21")).getName());
        Assertions.assertEquals("INT_X15Y41", DeviceTools.getIntTile(d.getSite("IRI_QUAD_X31Y176")).getName());
        Assertions.assertEquals("INT_X15Y41", DeviceTools.getIntTile(d.getSite("IRI_QUAD_X31Y177")).getName());
    }
}
