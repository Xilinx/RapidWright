/*
 * Copyright (c) 2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.design.tools;

import java.util.HashSet;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
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
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testFixPinSwapsWithRWRoute(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        System.setProperty("rapidwright.rwroute.lutPinSwapping.deferIntraSiteRoutingUpdates", "true");
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--lutPinSwapping", "--verbose"});
        Assertions.assertTrue(LUTTools.fixPinSwaps(design) > 0);
        TestRWRoute.assertAllSourcesRoutedFlagSet(design);
        TestRWRoute.assertAllPinsRouted(design);
        TestRWRoute.assertVivadoFullyRouted(design);
    }
}
