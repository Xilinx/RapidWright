/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class TestNet {
    @Test
    void testSetPinsMultiSrc() {
        Design d = new Design("testSetPinsMultiSrc", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        Net net = new Net("foo");
        List<SitePinInst> pins = Arrays.asList(
                new SitePinInst(true, "A_O", si),
                new SitePinInst(true, "AMUX", si)
        );

        Assertions.assertTrue(net.setPins(pins));
        Assertions.assertEquals(pins.get(0), net.getSource());
        Assertions.assertEquals(pins.get(1), net.getAlternateSource());
    }

    @Test
    void testSetPinsMultiSrcStatic() {
        Design d = new Design("testSetPinsMultiSrcStatic", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        Net net = d.getVccNet();
        List<SitePinInst> pins = Arrays.asList(
                new SitePinInst(true, "A_O", si),
                new SitePinInst(true, "B_O", si),
                new SitePinInst(true, "C_O", si)
        );

        Assertions.assertTrue(net.setPins(pins));
        Assertions.assertNull(net.getSource());
        Assertions.assertNull(net.getAlternateSource());
    }
}
