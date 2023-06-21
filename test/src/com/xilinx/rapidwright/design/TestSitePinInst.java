/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
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
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSitePinInst {
    // https://github.com/Xilinx/RapidWright/issues/454
    @Test
    public void testSetSameSiteInst() {
        Design d = new Design("Test1", Device.PYNQ_Z1);
        Device dev = d.getDevice();
        Net net0 = d.createNet("net0");
        net0.addPIP(dev.getPIP("CLBLM_L_X2Y0/CLBLM_L.CLBLM_CLK1->CLBLM_M_CLK"));

        Assertions.assertEquals(1, net0.getPIPs().size());

        SiteInst siteInst = d.createSiteInst("SLICE_X0Y0");
        SitePinInst spi = net0.createPin("CLK", siteInst);
        spi.setSiteInst(siteInst);

        Assertions.assertEquals(1, net0.getPIPs().size());
    }

    @Test
    public void testGetRouteNode() {
        Design d = RapidWrightDCP.loadDCP("bug635.dcp");
        SiteInst si = d.getSiteInstFromSiteName("RAMB36_X8Y14");
        SitePinInst spi = si.getSitePinInst("RSTRAMARSTRAML");
        RouteNode rn = spi.getRouteNode();
        Assertions.assertNotEquals(-1, rn.getWire());
    }

    @Test
    public void testDetachSiteInst() {
        Design d = new Design("testDetachSiteInst", Device.AWS_F1);
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        Net net1 = d.createNet("net1");
        SitePinInst spi1 = net1.createPin("A_O", si);
        Assertions.assertEquals(net1, si.getNetFromSiteWire(spi1.getSiteWireName()));

        // Forcibly detach SitePinInst from SiteInst but without removing any
        // intra-site routing, so that new pin can be added
        si.getSitePinInstMap().remove(spi1.getName());

        Net net2 = d.createNet("net2");
        SitePinInst spi2 = net2.createPin("A_O", si);
        Assertions.assertEquals(net2, si.getNetFromSiteWire(spi2.getSiteWireName()));

        spi1.detachSiteInst();

        // Check that the sitewire is untouched
        Assertions.assertEquals(net2, si.getNetFromSiteWire(spi2.getSiteWireName()));
    }

}
