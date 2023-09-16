/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

public class TestCell {
    @ParameterizedTest
    @CsvSource({
            // Input pins (many site pin options for single logical pin)
            "xcvu3p,SLICE_X0Y0,CARRY8,S[4],S4,'[E1, E2, E3, E4, E5, E6]'",  // SLICEL
            "xcvu3p,SLICE_X0Y0,CARRY8,DI[2],DI2,'[C1, C2, C3, C4, C5]'",
            "xcvu3p,SLICE_X1Y0,CARRY8,S[7],S7,'[H1, H2, H3, H4, H5, H6]'",  // SLICEM
            "xcvu3p,SLICE_X1Y0,CARRY8,DI[3],DI3,'[D1, D2, D3, D4, D5]'",

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
    
    @Test
    public void testGetCorrespondingSitePinName() {
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
}
