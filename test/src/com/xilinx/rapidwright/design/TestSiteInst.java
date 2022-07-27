/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.design;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestSiteInst {

    @Test
    public void testRouteIntraSiteNet() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X15Y237");
        Net net = d.createNet("dummy_test_net");
        d.createAndPlaceCell("dummy_flop", Unisim.FDRE, "SLICE_X15Y237/EFF");
        
        BELPin src = si.getBEL("E3").getPin("E3");
        BELPin snk = si.getBEL("EFF").getPin("D");
        Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
        
        Assertions.assertEquals(si.getSiteWiresFromNet(net).size(), 3);
        
        Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
        
        Assertions.assertEquals(si.getSiteWiresFromNet(net).size(), 0);
    }

    private void routeLUTRouteThruHelper(Design d, SiteInst si, char letter, boolean primary) {
        BEL bel;
        if(d.getDevice().getSeries() == Series.Series7) {
            bel = si.getBEL(letter + (primary ? "" : "5") + "FF");
        } else {
            bel = si.getBEL(letter + "FF" + (primary ? "" : "2"));
        }
        d.createAndPlaceCell(d.getTopEDIFCell(), bel.getName() + "_inst", Unisim.FDRE,
                si.getSiteName() + "/" + bel.getName());
        Net net = d.createNet(bel.getName() + "_net");
        BELPin src = si.getSite().getBELPin(letter + (primary ? "5": "4"));
        BELPin snk = bel.getPin("D");
        Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
        Cell lut = si.getCell(letter + (primary ? "6": "5") + "LUT");
        Assertions.assertNotNull(lut);
        Assertions.assertTrue(lut.isRoutethru());
    }


    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testRouteLUTRouteThru(String deviceName) {
        Design d = new Design("testRouteLUTRT", deviceName);
        
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));
        
        for(char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelper(d, si, letter, true);
            routeLUTRouteThruHelper(d, si, letter, false);
            if(d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testUnrouteLUTRouteThru(String deviceName) {
        Design d = new Design("testUnrouteLUTRT", deviceName);

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        for(char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelper(d, si, letter, true);
            routeLUTRouteThruHelper(d, si, letter, false);

            // Unroute 6LUT
            {
                BELPin src = si.getSite().getBELPin(letter + "5");
                BELPin snk = si.getBEL(letter + "FF").getPin("D");
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                Cell lut6 = si.getCell(letter + "6LUT");
                Assertions.assertNull(lut6);
                Cell lut5 = si.getCell(letter + "5LUT");
                Assertions.assertNotNull(lut5);
            }
            // Unroute 5LUT
            {
                BELPin src = si.getSite().getBELPin(letter + "4");
                BELPin snk;
                if(d.getDevice().getSeries() == Series.Series7) {
                    snk = si.getBEL(letter + "5FF").getPin("D");
                } else {
                    snk = si.getBEL(letter + "FF2").getPin("D");
                }
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                Cell lut5 = si.getCell(letter + "5LUT");
                Assertions.assertNull(lut5);
            }

            if(d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }

    @Test
    public void testUnrouteIntraSiteNet() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");

        SiteInst si = design.getSiteInstFromSiteName("SLICE_X78Y212");

        // Test cross product of {LUT opin, SitePIP ipin} x {FF ipin, SitePIP opin}
        for(BELPin src : new BELPin[] {si.getBELPin("D5LUT","O5"), si.getBELPin("FFMUXD2","D5")} ) {
            for(BELPin snk : new BELPin[] {si.getBELPin("DFF2","D"), si.getBELPin("FFMUXD2","OUT2")} ) {
                Net net = si.getNetFromSiteWire("D5LUT_O5");
                Assertions.assertNotNull(net);
                Assertions.assertEquals(si.getUsedSitePIP("FFMUXD2").getInputPinName(), "D5");
                Assertions.assertNotNull(si.getNetFromSiteWire("FFMUXD2_OUT2"));
                
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                
                Assertions.assertNull(si.getNetFromSiteWire("D5LUT_O5"));
                Assertions.assertNull(si.getNetFromSiteWire("FFMUXD2_OUT2"));
                
                Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
            }
        }

        Assertions.assertNotNull(si.getNetFromSiteWire("D5LUT_O5"));
        Assertions.assertEquals(si.getUsedSitePIP("FFMUXD2").getInputPinName(), "D5");
        Assertions.assertNotNull(si.getNetFromSiteWire("FFMUXD2_OUT2"));
    }  
}
