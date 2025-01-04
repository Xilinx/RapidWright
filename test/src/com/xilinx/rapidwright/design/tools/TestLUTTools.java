/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.SitePIPStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.rwroute.TestRWRoute;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestLUTTools {

    @Test
    public void testGetCompanionLUTName() {
        Set<Series> tested = new HashSet<>();
        for (Part part : PartNameTools.getParts()) {
            if (tested.contains(part.getSeries())) continue;
            Device device = Device.getDevice(part);
            for (SiteTypeEnum siteType : new SiteTypeEnum[]{SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM}) {
                Site site = device.getAllCompatibleSites(siteType)[0];
                for (BEL bel : site.getBELs()) {
                    if (bel.isLUT()) {
                        String compLUTName = LUTTools.getCompanionLUTName(bel);
                        System.out.println(part + " " + siteType + " " + bel + " " + compLUTName);
                        if (bel.getName().contains("5")) {
                            Assertions.assertTrue(compLUTName.contains("6"));
                        } else if (bel.getName().contains("6")) {
                            Assertions.assertTrue(compLUTName.contains("5"));
                        }
                        Assertions.assertTrue(site.getBEL(compLUTName).isLUT());
                    } else {
                        Assertions.assertNull(LUTTools.getCompanionLUTName(bel));
                    }
                }
            }
            tested.add(part.getSeries());
            Device.releaseDeviceReferences();
        }
    }

    @Test
    public void testSwapLutPinsFromPIPs() {
        Design design = new Design("testFixPinSwaps", "xcvu3p");
        SiteInst si = design.createSiteInst("SLICE_X0Y0");
        // Create and place on both A6LUT and A5LUT
        Cell cell6 = design.createAndPlaceCell("lut6", Unisim.LUT2, "SLICE_X0Y0/A6LUT");
        // Clear default pin mappings
        for (String physPin : cell6.getUsedPhysicalPins()) {
            cell6.removePinMapping(physPin);
        }
        Cell cell5 = design.createAndPlaceCell("lut5", Unisim.LUT2, "SLICE_X0Y0/A5LUT");
        for (String physPin : cell5.getUsedPhysicalPins()) {
            cell5.removePinMapping(physPin);
        }

        // Net with a single sink pin used by both LUTs that needs swapping
        Net netNeedsPinSwap = design.createNet("netNeedsPinSwap");
        SitePinInst oldSpiSwap = netNeedsPinSwap.createPin("A1", si);
        oldSpiSwap.setSiteInst(si);
        cell6.addPinMapping("A1", "I0");
        cell5.addPinMapping("A1", "I1");
        Site site = si.getSite();
        Node newSitePinNode = site.getConnectedNode("A5");
        // .get(1) because first PIP is from VCC_WIRE
        PIP pipToNewSitePin = newSitePinNode.getAllUphillPIPs().get(1);
        netNeedsPinSwap.addPIP(pipToNewSitePin);

        // Net with a single sink pin used by just one LUT that doesn't need swapping
        Net netDoesntNeedPinSwap = design.createNet("netDoesntNeedPinSwap");
        SitePinInst oldSpiNoSwap = netDoesntNeedPinSwap.createPin("A2", si);
        cell6.addPinMapping(oldSpiNoSwap.getName(), "I1");
        oldSpiNoSwap.setSiteInst(si);
        Node oldSitePinNode = oldSpiNoSwap.getConnectedNode();
        // .get(1) because first PIP is from VCC_WIRE
        PIP pipToOldSitePin = oldSitePinNode.getAllUphillPIPs().get(1);
        netDoesntNeedPinSwap.addPIP(pipToOldSitePin);

        // Pin mapping but without a net
        cell5.addPinMapping("A3", "I0");

        Assertions.assertEquals(1, LUTTools.swapLutPinsFromPIPs(design));
        // Check A1 swapped to A5
        Assertions.assertEquals("A5", oldSpiSwap.getName());
        Assertions.assertEquals("I0", cell6.getLogicalPinMapping("A5"));
        Assertions.assertEquals(null, cell6.getLogicalPinMapping("A1"));
        Assertions.assertEquals("I1", cell5.getLogicalPinMapping("A5"));
        Assertions.assertEquals(null, cell5.getLogicalPinMapping("A1"));
        // Check other pins mappings remain unaffected
        Assertions.assertEquals("A2", oldSpiNoSwap.getName());
        Assertions.assertEquals("I1", cell6.getLogicalPinMapping("A2"));
        Assertions.assertEquals("I0", cell5.getLogicalPinMapping("A3"));
    }

    @ParameterizedTest
    @CsvSource({
            "bnn.dcp,false,false",
            "bnn.dcp,false,true",
            "bnn.dcp,true,false",
            "bnn.dcp,true,true",
            "optical-flow.dcp,false,false",
            "optical-flow.dcp,false,true",
            "optical-flow.dcp,true,false",
            "optical-flow.dcp,true,true",
    })
    @LargeTest(max_memory_gb = 8)
    public void testUpdateLutPinSwapsFromPIPsWithRWRoute(String path, boolean lutPinSwapping, boolean lutRoutethru) {
        Design design = RapidWrightDCP.loadDCP(path);
        try {
            System.setProperty("rapidwright.rwroute.lutPinSwapping.deferIntraSiteRoutingUpdates", "true");
            List<String> args = new ArrayList<>();
            args.add("--nonTimingDriven");
            if (lutPinSwapping) {
                args.add("--lutPinSwapping");
            }
            if (lutRoutethru) {
                args.add("--lutRoutethru");
            }
            RWRoute.routeDesignWithUserDefinedArguments(design, args.toArray(new String[0]));
            int numPinsSwapped = LUTTools.swapLutPinsFromPIPs(design);
            System.out.println("numPinsSwapped = " + numPinsSwapped);
            if (lutPinSwapping) {
                Assertions.assertTrue(numPinsSwapped > 0);
            } else {
                Assertions.assertEquals(0, numPinsSwapped);
            }
            TestRWRoute.assertAllSourcesRoutedFlagSet(design);
            TestRWRoute.assertAllPinsRouted(design);
            VivadoToolsHelper.assertFullyRouted(design);
        } finally {
            System.setProperty("rapidwright.rwroute.lutPinSwapping.deferIntraSiteRoutingUpdates", "false");
        }
    }

    @Test
    public void testSwapMultipleLutPinsVersal() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X140Y3");
        SitePinInst spiF6 = si.getSitePinInst("F6");
        Net f6 = spiF6.getNet();
        Assertions.assertEquals("processor/pc_mode1_lut/O5", f6.getName());
        Assertions.assertSame(f6, si.getNetFromSiteWire("F6"));
        Assertions.assertTrue(si.getCell("F6_IMR").isRoutethru());
        Assertions.assertSame(f6, si.getNetFromSiteWire("F6_IMR_Q"));

        SitePinInst spiF3 = si.getSitePinInst("F3");
        Net f3 = spiF3.getNet();
        Assertions.assertEquals("processor/address_loop[4].output_data.pc_vector_mux_lut/O6", f3.getName());
        Assertions.assertSame(f3, si.getNetFromSiteWire("F3"));
        Assertions.assertTrue(si.getCell("F3_IMR").isRoutethru());
        Assertions.assertSame(f3, si.getNetFromSiteWire("F3_IMR_Q"));

        SitePinInst spiF1 = si.getSitePinInst("F1");
        Net f1 = spiF1.getNet();
        Assertions.assertEquals("processor/address[5]", f1.getName());
        Assertions.assertSame(f1, si.getNetFromSiteWire("F1"));
        Assertions.assertTrue(si.getCell("F1_IMR").isRoutethru());
        Assertions.assertSame(f1, si.getNetFromSiteWire("F1_IMR_Q"));

        Map<SitePinInst, String> oldPinToNewPins = new HashMap<>();
        oldPinToNewPins.put(spiF6, "F3");
        oldPinToNewPins.put(spiF3, "F1");
        oldPinToNewPins.put(spiF1, "F6");
        LUTTools.swapMultipleLutPins(oldPinToNewPins);

        spiF6 = si.getSitePinInst("F6");
        Assertions.assertSame(f1, spiF6.getNet());
        Assertions.assertSame(f1, si.getNetFromSiteWire("F6"));
        Assertions.assertNull(si.getCell("F6_IMR"));
        Assertions.assertEquals(SitePIPStatus.ON, si.getSitePIPStatus(si.getSitePIP("F6_IMR", "D")));
        Assertions.assertSame(f1, si.getNetFromSiteWire("F6_IMR_Q"));

        spiF3 = si.getSitePinInst("F3");
        Assertions.assertSame(f6, spiF3.getNet());
        Assertions.assertSame(f6, si.getNetFromSiteWire("F3"));
        Assertions.assertNull(si.getCell("F3_IMR"));
        Assertions.assertEquals(SitePIPStatus.ON, si.getSitePIPStatus(si.getSitePIP("F3_IMR", "D")));
        Assertions.assertSame(f6, si.getNetFromSiteWire("F3_IMR_Q"));

        spiF1 = si.getSitePinInst("F1");
        Assertions.assertSame(f3, spiF1.getNet());
        Assertions.assertSame(f3, si.getNetFromSiteWire("F1"));
        Assertions.assertNull(si.getCell("F1_IMR"));
        Assertions.assertEquals(SitePIPStatus.ON, si.getSitePIPStatus(si.getSitePIP("F1_IMR", "D")));
        Assertions.assertSame(f3, si.getNetFromSiteWire("F1_IMR_Q"));
    }
    
    @ParameterizedTest
    @CsvSource({
            "O=I0 & !I1 & I2 & !I3 + !I0 & I1 & I2 & !I3 + I0 & !I1 & !I2 & I3 + !I0 & I1 & !I2 & I3,16'h0660,4",
            "O=I0 & !I1 + !I0 & I1,4'h6,2",
            "O=!I0 & !I1 & !I2 & !I3 + I0 & I1 & !I2 & !I3 + !I0 & !I1 & I2 & I3 + I0 & I1 & I2 & I3,16'h9009,4",
            "O=I0 & I1 & I2 & I3 & I4 & I5,64'h8000000000000000,6",
            "O=!I0 & !I1 & !I2 & !I3 & !I4 & !I5,64'h0000000000000001,6",
    })
    public void testGetLUTInitFromEquation(String equation, String init, int lutSize) {
        Assertions.assertEquals(init, LUTTools.getLUTInitFromEquation(equation, lutSize));
    }
}
