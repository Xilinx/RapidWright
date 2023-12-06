/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.eco;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class TestECOPlacementHelper {
    @Test
    public void testGetUnusedLUT() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        ECOPlacementHelper eph = new ECOPlacementHelper(design, null);
        int i = 0;
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X15Y239");
            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedLUT(si)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.LUT6, si.getSiteName() + "/" + bel.getName());
            }
            // BELs C, D, G, H are occupied
            // BELs A, E, F are static sources
            Assertions.assertEquals("[B6LUT(BEL)]", bels.toString());
        }
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X15Y238");
            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedLUT(si)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.LUT6, si.getSiteName() + "/" + bel.getName());
            }
            // All BELs occupied
            Assertions.assertEquals("[]", bels.toString());
        }
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X14Y239");
            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedLUT(si)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.LUT6, si.getSiteName() + "/" + bel.getName());
            }
            // BEL D, is occupied
            // BELs B, C, G are static sources
            Assertions.assertEquals("[A6LUT(BEL), E6LUT(BEL), F6LUT(BEL), H6LUT(BEL)]", bels.toString());
        }
    }

    @Test
    public void testGetUnusedFlop() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Net clk = design.getNet("clk");
        DesignTools.createMissingSitePinInsts(design, clk);
        ECOPlacementHelper eph = new ECOPlacementHelper(design, null);
        int i = 0;
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X15Y239");
            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedFlop(si, clk)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.FDRE, si.getSiteName() + "/" + bel.getName());
            }
            // BFF, CFF, DFF not available due to PINBOUNCE blockage; all others occupied
            Assertions.assertEquals("[]", bels.toString());
        }
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X15Y238");
            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedFlop(si, clk)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.FDRE, si.getSiteName() + "/" + bel.getName());
            }
            // AFF2, BFF2, CFF2, EFF2 not available due to PINBOUNCE blockage; all others occupied
            Assertions.assertEquals("[]", bels.toString());
        }
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X14Y239");
            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedFlop(si, clk)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.FDRE, si.getSiteName() + "/" + bel.getName());
            }
            Assertions.assertEquals("[BFF(BEL), DFF(BEL), GFF(BEL), FFF(BEL), HFF(BEL)]", bels.toString());
        }
        {
            SiteInst si = design.getSiteInstFromSiteName("SLICE_X14Y238");
            Net incompatibleClk = design.getNet("reset");
            Assertions.assertNotNull(incompatibleClk);
            Assertions.assertNull(eph.getUnusedFlop(si, incompatibleClk));

            List<BEL> bels = new ArrayList<>();
            BEL bel;
            while ((bel = eph.getUnusedFlop(si, clk)) != null) {
                bels.add(bel);
                design.createAndPlaceCell("cell" + (i++), Unisim.FDRE, si.getSiteName() + "/" + bel.getName());
            }
            // FF2 is not blocked by PINBOUNCE, but SR is incompatible
            Assertions.assertEquals("[]", bels.toString());
        }
    }

    @Test
    public void testSpiralOutFrom() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Site home = design.getDevice().getSite("SLICE_X15Y239");
        Set<Site> sites = new HashSet<>();
        List<Site> sitesList = new ArrayList<>();
        for (Site site : ECOPlacementHelper.spiralOutFrom(home)) {
            // Test that site are not duplicated
            Assertions.assertTrue(sites.add(site));
            if (sitesList.size() < 10) {
                sitesList.add(site);
            }
        }
        // Test that all SLICE sites are ultimately visited
        Assertions.assertEquals(49260, sites.size());
        // Test that the first 10 are as expected
        Assertions.assertEquals("[SLICE_X15Y239, SLICE_X14Y239, " +
                "SLICE_X14Y238, SLICE_X15Y238, SLICE_X16Y238, " +
                "SLICE_X16Y239, " +
                "SLICE_X16Y240, SLICE_X15Y240, SLICE_X14Y240, SLICE_X13Y240]", sitesList.toString());
    }
}
