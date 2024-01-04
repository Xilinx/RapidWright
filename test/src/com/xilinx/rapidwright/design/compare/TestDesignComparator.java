/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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
package com.xilinx.rapidwright.design.compare;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.support.RapidWrightDCP;

/**
 * Tests various design diffs using DesignComparator.
 *
 */
public class TestDesignComparator {

    private void compareDesign(int expectedTotalDiffs, int specificDiff, DesignDiffType type, 
            DesignComparator dc, Design gold, Design test) {
        int diffs = dc.compareDesigns(gold, test);
        Assertions.assertEquals(expectedTotalDiffs, diffs);
        Assertions.assertEquals(specificDiff, dc.getDiffList(type).size());
    }


    @Test
    public void testDesignComparator() {
        Design gold = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Device device = gold.getDevice();

        DesignComparator dc = new DesignComparator();
        int diffs = dc.compareDesigns(gold, gold);
        Assertions.assertEquals(0, diffs);

        Design test = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        diffs = dc.compareDesigns(gold, test);
        Assertions.assertEquals(0, diffs);

        // Same device, different package
        Design test2 = new Design(test.getName(), "xcvc1502-vsva2197-2MP-e-S");
        test2.setNetlist(test.getNetlist());
        SiteInst siteInst = null;
        for (SiteInst si : test.getSiteInsts()) {
            test2.addSiteInst(si);
            if (si.getSiteName().equals("SLICE_X142Y0")) {
                siteInst = si;
            }
        }
        Assertions.assertNotNull(siteInst);

        for (Net net : test.getNets()) {
            test2.addNet(net);
        }

        compareDesign(1, 1, DesignDiffType.DESIGN_PARTNAME, dc, gold, test2);
        
        test2.removeSiteInst(siteInst, true);

        compareDesign(2, 1, DesignDiffType.SITEINST_MISSING, dc, gold, test2);
        
        Site s = device.getSite("SLICE_X56Y0");
        new SiteInst(s.getName(), test2, SiteTypeEnum.SLICEL, s);

        compareDesign(3, 1, DesignDiffType.SITEINST_EXTRA, dc, gold, test2);

        String oldName = siteInst.getName();
        siteInst.setName("anotherNamedSiteInst");
        test2.addSiteInst(siteInst);

        compareDesign(3, 1, DesignDiffType.SITEINST_NAME, dc, gold, test2);

        siteInst.setName(oldName);
        Cell removedCell = siteInst.removeCell(siteInst.getBEL("AFF"));

        compareDesign(3, 1, DesignDiffType.PLACED_CELL_MISSING, dc, gold, test2);

        test2.createAndPlaceCell("extra", Unisim.FDRE, siteInst.getSiteName() + "/BFF");

        compareDesign(4, 1, DesignDiffType.PLACED_CELL_EXTRA, dc, gold, test2);

        test2.placeCell(removedCell, siteInst.getSite(), siteInst.getBEL("AFF"));
        String oldType = removedCell.getType();
        removedCell.setType("MISMATCHEDTYPE");

        compareDesign(4, 1, DesignDiffType.PLACED_CELL_TYPE, dc, gold, test2);

        removedCell.setType(oldType);
        removedCell.updateName("NEWNAME");

        compareDesign(4, 1, DesignDiffType.PLACED_CELL_NAME, dc, gold, test2);

        SitePIP sitePIP = siteInst.getUsedSitePIP("CLKINV");
        Net clk = siteInst.getNetFromSiteWire(sitePIP.getInputPin().getSiteWireName());
        siteInst.unrouteIntraSiteNet(sitePIP.getInputPin(), sitePIP.getOutputPin());
        siteInst.routeIntraSiteNet(clk, sitePIP.getInputPin(), sitePIP.getInputPin());
        siteInst.routeIntraSiteNet(clk, sitePIP.getOutputPin(), sitePIP.getOutputPin());

        compareDesign(5, 1, DesignDiffType.SITEPIP_MISSING, dc, gold, test2);

        siteInst.addSitePIP("FFMUXC1", "D6");

        compareDesign(6, 1, DesignDiffType.SITEPIP_EXTRA, dc, gold, test2);

        sitePIP = siteInst.getUsedSitePIP("FFMUXA1");
        Net net = siteInst.getNetFromSiteWire(sitePIP.getInputPin().getSiteWireName());
        siteInst.unrouteIntraSiteNet(sitePIP.getInputPin(), sitePIP.getOutputPin());
        siteInst.routeIntraSiteNet(net, sitePIP.getInputPin(), sitePIP.getInputPin());
        siteInst.routeIntraSiteNet(net, sitePIP.getOutputPin(), sitePIP.getOutputPin());
        siteInst.addSitePIP("FFMUXA1", "D6");

        compareDesign(8, 1, DesignDiffType.SITEPIP_INPIN_NAME, dc, gold, test2);
        Assertions.assertEquals(1, dc.getDiffList(DesignDiffType.SITEWIRE_NET_EXTRA).size());

        siteInst.unrouteIntraSiteNet(sitePIP.getInputPin(), sitePIP.getInputPin());

        compareDesign(9, 1, DesignDiffType.SITEWIRE_NET_MISSING, dc, gold, test2);

        siteInst.routeIntraSiteNet(new Net("mismatch"), sitePIP.getInputPin(), sitePIP.getInputPin());

        compareDesign(9, 1, DesignDiffType.SITEWIRE_NET_NAME, dc, gold, test2);

        Assertions.assertTrue(net.hasPIPs());
        test2.removeNet(net);

        compareDesign(22, 1, DesignDiffType.NET_MISSING, dc, gold, test2);

        Net extra = new Net("extraNet");
        for (PIP p : net.getPIPs()) {
            extra.addPIP(p);
        }
        test2.addNet(extra);

        compareDesign(23, 1, DesignDiffType.NET_EXTRA, dc, gold, test2);

        Assertions.assertTrue(extra.hasPIPs());
        clk.addPIP(extra.getPIPs().get(0));

        compareDesign(24, 1, DesignDiffType.PIP_EXTRA, dc, gold, test2);

        clk.getPIPs().remove(clk.getPIPs().size() - 1);
        clk.getPIPs().remove(clk.getPIPs().size() - 1);

        compareDesign(24, 1, DesignDiffType.PIP_MISSING, dc, gold, test2);

        if (dc.comparePIPFlags()) {
            clk.getPIPs().get(clk.getPIPs().size() - 1).setIsStub(true);

            compareDesign(25, 1, DesignDiffType.PIP_FLAGS, dc, gold, test2);
        }
    }
}
