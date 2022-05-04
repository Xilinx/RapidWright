/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
}
