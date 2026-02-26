/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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


import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

public class TestCell {
    @ParameterizedTest
    @CsvSource({
            // Input pins (many site pin options for single logical pin)
            "xcvu3p,SLICE_X0Y0,CARRY8,S[4],S4,'[E1, E2, E3, E4, E5, E6]'",  // SLICEL
            "xcvu3p,SLICE_X0Y0,CARRY8,DI[2],DI2,'[C1, C2, C3, C4, C5]'",
            "xcvu3p,SLICE_X1Y0,CARRY8,S[7],S7,'[H1, H2, H3, H4, H5, H6]'",  // SLICEM
            "xcvu3p,SLICE_X1Y0,CARRY8,DI[3],DI3,'[D1, D2, D3, D4, D5]'",
            // Versal input pins
            "xcvp1502,SLICE_X148Y0,B6LUT,I1,A1,'[B1]'",

            // Output pins (single logical pin has options to drive many site pins)
            "xcvu3p,SLICE_X0Y0,E6LUT,O,O6,'[E_O, EMUX]'",
            "xcvu3p,SLICE_X0Y0,CARRY8,O[7],O7,'[HMUX]'",
            "xcvu3p,SLICE_X0Y0,CARRY8,CO[7],CO7,'[COUT, HMUX]'",
            "xcvu3p,SLICE_X1Y0,A5LUT,O,O5,'[AMUX]'",
    })
    public void testGetAllCorrespondingSitePinNames(String deviceName,
                                                    String siteName,
                                                    String belName,
                                                    String logicalPinName,
                                                    String physicalPinName,
                                                    String expectedSitePins) {
        Device device = Device.getDevice(deviceName);
        Cell cell = new Cell("cell", device.getSite(siteName).getBEL(belName));
        cell.addPinMapping(physicalPinName, logicalPinName);
        final boolean considerLutRoutethru = true;
        List<String> sitePinNames = cell.getAllCorrespondingSitePinNames(logicalPinName, considerLutRoutethru);
        Assertions.assertEquals(expectedSitePins, sitePinNames.toString());
    }

    @ParameterizedTest
    @CsvSource({
            "false,[G5]",
            "true,'[G5, G1, G2, G3, G4, G6]'",
    })
    public void testGetAllCorrespondingSitePinNamesLUTRouteThru(boolean considerLutRoutethru, String expectedSitePins) {
        Design d = new Design("testGetAllCorrespondingSitePinNamesLUTRouteThru", Device.KCU105);
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));
        Cell cell = d.createAndPlaceCell("f7mux", Unisim.MUXF7, si.getSiteName() + "/F7MUX_GH");

        Net netS = d.createNet("netS");
        Assertions.assertTrue(si.routeIntraSiteNet(netS, si.getBELPin("GX", "GX"),
                si.getBELPin("F7MUX_GH", "S0")));
        List<String> sitePinNames = cell.getAllCorrespondingSitePinNames("S", considerLutRoutethru);
        Assertions.assertEquals("[GX]", sitePinNames.toString());

        Net net1 = d.createNet("net1");
        Assertions.assertTrue(si.routeIntraSiteNet(net1, si.getBELPin("G5", "G5"),
                si.getBELPin("F7MUX_GH", "1")));
        sitePinNames = cell.getAllCorrespondingSitePinNames("I1", considerLutRoutethru);
        Assertions.assertEquals(expectedSitePins, sitePinNames.toString());
    }

    @ParameterizedTest
    @CsvSource({
            // Versal input pins
            "xcvp1502,SLICE_X148Y0,BFF,D,D,BX",
    })
    public void testGetCorrespondingSitePinName(String deviceName,
                                                String siteName,
                                                String belName,
                                                String logicalPinName,
                                                String physicalPinName,
                                                String expectedSitePin) {
        Device device = Device.getDevice(deviceName);
        Cell cell = new Cell("cell", device.getSite(siteName).getBEL(belName));
        cell.addPinMapping(physicalPinName, logicalPinName);
        String sitePinName = cell.getCorrespondingSitePinName(logicalPinName);
        Assertions.assertEquals(expectedSitePin, sitePinName);
    }
    
    @Test
    public void testGetCorrespondingSitePinNameDualLut() {
        Device device = Device.getDevice("xcvu3p");
        Design design = new Design("testDesign", device.getName());
        Cell cell = design.createAndPlaceCell("testFF", Unisim.FDRE, "SLICE_X10Y10/GFF");
        SiteInst si = cell.getSiteInst();
        Net net = design.createNet("testNet");
        SitePinInst pin = net.createPin("G3", si);
        
        // Force the site router to use the LUT5
        design.createAndPlaceCell("dummyG6LUT", Unisim.LUT4, "SLICE_X10Y10/G6LUT");
        
        si.routeIntraSiteNet(net, pin.getBELPin(), cell.getBEL().getPin("D"));
        
        Assertions.assertNotNull(si.getCell("G5LUT"));
        Assertions.assertTrue(si.getCell("G5LUT").isRoutethru());
        Assertions.assertEquals("D5", si.getUsedSitePIP("FFMUXG1").getInputPinName());
        
        Assertions.assertEquals(pin.getName(), cell.getCorrespondingSitePinName("D"));
    }

    @Test
    public void testGetPropertyNoEDIFCellInst() {
        Cell cell = new Cell("cell");
        Assertions.assertNull(cell.getEDIFCellInst());
        Assertions.assertNull(cell.getProperty("any_property"));
    }

    @Test
    public void testFFRoutethruCell() {
        Design d = RapidWrightDCP.loadDCP("optical-flow.dcp");
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X72Y144");
        Cell rtCell = si.getCell("CFF");
        Assertions.assertNotNull(rtCell);
        Assertions.assertSame(si, rtCell.getSiteInst());
        Assertions.assertTrue(rtCell.isRoutethru());
        Assertions.assertTrue(rtCell.isFFRoutethruCell());
        Assertions.assertEquals("D", rtCell.getLogicalPinMapping("D"));
        Assertions.assertEquals("Q", rtCell.getLogicalPinMapping("Q"));

        Assertions.assertNull(d.getCell(rtCell.getName()));

        if (FileTools.isVivadoOnPath()) {
            Assertions.assertEquals(0, VivadoTools.reportRouteStatus(d).netsWithRoutingErrors);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testGetSitePinFromLogicalPin(boolean createAX) {
        Design design = new Design("top", Device.PYNQ_Z1);
        SiteInst si = design.createSiteInst("SLICE_X1Y0");
        Net net = design.createNet("net");
        SitePinInst A3 = net.createPin("A3", si);
        SitePinInst AX = (createAX) ? net.createPin("AX", si) : null;

        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X1Y0/AFF");

        BELPin ffD = ff.getBEL().getPin("D");
        for (SitePinInst spi : Arrays.asList(AX, A3)) {
            if (spi == null)
                continue;

            Assertions.assertNull(DesignTools.getRoutedSitePin(ff, net, "D"));
            if (createAX) {
                // FIXME: Known broken -- see https://github.com/Xilinx/RapidWright/issues/473
                Assertions.assertEquals("IN SLICE_X1Y0.AX", ff.getSitePinFromLogicalPin("D", null).toString());
            } else {
                Assertions.assertNull(ff.getSitePinFromLogicalPin("D", null));
            }

            BELPin bp = spi.getBELPin();
            Assertions.assertTrue(si.routeIntraSiteNet(net, bp, ffD));

            Assertions.assertEquals(bp.getName(), DesignTools.getRoutedSitePin(ff, net, "D"));
            Assertions.assertEquals(spi, ff.getSitePinFromLogicalPin("D", null));

            Assertions.assertTrue(si.unrouteIntraSiteNet(bp, ffD));
        }
    }
}
