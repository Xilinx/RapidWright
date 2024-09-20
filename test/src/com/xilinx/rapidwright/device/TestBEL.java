/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestBEL {
    @ParameterizedTest
    @CsvSource({
            "LAGUNA_X0Y598,TX_OPTINV_SR",
            "LAGUNA_X0Y598,RX_OPTINV_SR",
            "DSP48E2_X0Y358,CLKINV",
    })
    public void testCanInvert(String siteName, String belName) {
        Device d = Device.getDevice(Device.AWS_F1);
        Site s = d.getSite(siteName);
        BEL b = s.getBEL(belName);
        Assertions.assertTrue(b.canInvert());

    }

    @ParameterizedTest
    @CsvSource({
        "xc7z020clg400-1,SLICE_X10Y10",
        "xcku040-ffva1156-2-e,SLICE_X10Y10",
        "xcau10p-ffvb676-1-i,SLICE_X10Y10",
        "xcve1752-vsva2197-1LP-i-S,SLICE_X40Y0"
    })
    public void testIsFF(String deviceName, String siteName) {
        Device d = Device.getDevice(deviceName);

        Site s = d.getSite(siteName);
        for (BEL bel : s.getBELs()) {
            if (bel.isFF()) {
                Assertions.assertEquals(bel.getBELClass(), BELClass.BEL);
            }
        }
    }

    @Test
    public void testIsSliceFFClkMod() {
        Device d = Device.getDevice("xcvc1902");
        Site s = d.getSite("SLICE_X290Y265");
        BEL b = s.getBEL("FF_CLK_MOD");
        Assertions.assertNotNull(b);
        Assertions.assertTrue(b.isSliceFFClkMod());
        Assertions.assertFalse(b.isFF());
    }

    @ParameterizedTest
    @CsvSource({
            "xcvc1902,IOB_X0Y0,DIFFRXTX",
            "xcvu3p,HPIOBDIFFINBUF_X0Y0,DIFFINBUF",
            "xcvu3p,HPIOBDIFFOUTBUF_X0Y0,DIFFOUTBUF"
    })
    public void testDIFFsAreNotFF(String partName, String siteName, String belName) {
        Device d = Device.getDevice(partName);
        Site s = d.getSite(siteName);
        BEL b = s.getBEL(belName);
        Assertions.assertNotNull(b);
        Assertions.assertFalse(b.isFF());
    }
}
