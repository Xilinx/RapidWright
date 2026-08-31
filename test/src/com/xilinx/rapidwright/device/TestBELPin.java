/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
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
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.util.Pair;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestBELPin {

    private Pair<Site,BEL> getSiteBELUnderTest() {
        Device d = Device.getDevice("xczu7ev-ffvc1156-2-e");
        Site s = d.getSite("BITSLICE_RX_TX_X0Y305");
        BEL b = s.getBEL("RXTX_BITSLICE");
        return new Pair<>(s,b);
    }

    @Test
    public void testForNullSiteWire() {
        Pair<Site,BEL> siteBEL = getSiteBELUnderTest();
        BELPin pin = siteBEL.getSecond().getPin("RX_DIV2_CLK_Q");
        Assertions.assertEquals(pin.getSiteWireIndex(), -1);
        Assertions.assertNull(pin.getSiteWireName());
        Assertions.assertEquals(siteBEL.getFirst().getBELPins(pin.getSiteWireIndex()).length, 0);
        Assertions.assertEquals(siteBEL.getFirst().getBELPins(pin.getSiteWireName()).length, 0);
        Assertions.assertEquals(pin.getSiteConns().size(), 0);
    }

    @Test
    public void testForNonNullSiteWire() {
        Pair<Site,BEL> siteBEL = getSiteBELUnderTest();
        BELPin pin = siteBEL.getSecond().getPin("TX_LOAD");
        Assertions.assertTrue(pin.getSiteWireIndex() > -1);
        Assertions.assertNotNull(pin.getSiteWireName());
        Assertions.assertTrue(siteBEL.getFirst().getBELPins(pin.getSiteWireIndex()).length > 0);
        Assertions.assertTrue(siteBEL.getFirst().getBELPins(pin.getSiteWireName()).length > 0);
        Assertions.assertTrue(pin.getSiteConns().size() > 0);
    }

    @ParameterizedTest
    @CsvSource({
            // Versal
            "xcvp1202,SLICE_X64Y105,AFF/SR,null",   // Hits RSTINV behind SR_IMR
            "xcvp1202,SLICE_X64Y105,AFF/CE,CKEN1",
            "xcvp1202,SLICE_X64Y105,BFF2/CE,CKEN1",
            "xcvp1202,SLICE_X64Y105,CFF/CE,CKEN2",
            "xcvp1202,SLICE_X64Y105,DFF2/CE,CKEN2",
            "xcvp1202,SLICE_X64Y105,EFF/CE,CKEN3",
            "xcvp1202,SLICE_X64Y105,HFF2/CE,CKEN4",
            "xcvp1202,SLICE_X64Y105,AFF/CLK,null",   // Hits CLKINV behind FF_CLK_MOD
            "xcvp1202,SLICE_X64Y105,A6LUT/A1,A1",
            "xcvp1202,SLICE_X64Y105,A5LUT/A2,A2",
            "xcvp1202,SLICE_X64Y105,B6LUT/A3,B3",
            "xcvp1202,SLICE_X64Y105,D5LUT/A4,D4",
            "xcvp1202,SLICE_X64Y105,G6LUT/A5,G5",
            "xcvp1202,SLICE_X64Y105,H6LUT/A6,H6",

            // US+ (no IMRs)
            "xcvu3p,SLICE_X0Y0,A6LUT/A1,A1",
            "xcvu3p,SLICE_X0Y0,A5LUT/A2,A2",
            "xcvu3p,SLICE_X0Y0,B6LUT/A3,B3",
            "xcvu3p,SLICE_X0Y0,D5LUT/A4,D4",
            "xcvu3p,SLICE_X0Y0,G6LUT/A5,G5",
            "xcvu3p,SLICE_X0Y0,H6LUT/A6,H6",
    })
    public void testGetConnectedSitePin(String deviceName, String siteName, String belPinName, String sitePinName) {
        Device device = Device.getDevice(deviceName);
        Site site = device.getSite(siteName);
        BELPin bp = site.getBELPin(belPinName);
        Assertions.assertEquals(sitePinName, String.valueOf(bp.getConnectedSitePinName()));
    }
}
