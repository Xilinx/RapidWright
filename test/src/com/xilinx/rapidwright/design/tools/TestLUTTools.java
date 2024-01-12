/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.rwroute.TestRWRoute;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        cell6.getPinMappingsP2L().clear(); // Clear default pin mappings
        Cell cell5 = design.createAndPlaceCell("lut5", Unisim.LUT2, "SLICE_X0Y0/A5LUT");
        cell5.getPinMappingsP2L().clear();

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
            TestRWRoute.assertVivadoFullyRouted(design);
        } finally {
            System.setProperty("rapidwright.rwroute.lutPinSwapping.deferIntraSiteRoutingUpdates", "false");
        }
    }
}
