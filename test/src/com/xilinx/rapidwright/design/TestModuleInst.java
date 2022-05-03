/*
 * Copyright (c) 2022 Xilinx, Inc.
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

import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;

public class TestModuleInst {
    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    public void testModulePlacesStaticNets(boolean placeOnOriginalAnchor) {
        String dcpPath = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        Design design = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        Assertions.assertTrue(design.getVccNet().hasPIPs());
        Assertions.assertTrue(design.getGndNet().hasPIPs());

        HashSet<PIP> oldVccPips = new HashSet<>(design.getVccNet().getPIPs());
        HashSet<PIP> oldGndPips = new HashSet<>(design.getGndNet().getPIPs());

        Design emptyDesign = new Design("emptyDesign", design.getPartName());

        Module module = new Module(design, false);
        design = null;

        ModuleInst mi = emptyDesign.createModuleInst("inst", module);
        mi.placeOnOriginalAnchor();

        Assertions.assertTrue(emptyDesign.getVccNet().hasPIPs());
        Assertions.assertTrue(emptyDesign.getGndNet().hasPIPs());
        Assertions.assertFalse(emptyDesign.getVccNet().getPins().isEmpty());
        Assertions.assertFalse(emptyDesign.getGndNet().getPins().isEmpty());

        if (!placeOnOriginalAnchor) {
            final int xOffset = 0;
            final int yOffset = -5;

            SiteInst si = module.getAnchor();
            Site ss = si.getSite();
            Tile st = si.getTile();
            Tile dt = st.getTileXYNeighbor(xOffset, yOffset);
            Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);

            mi.unplace();

            Assertions.assertFalse(emptyDesign.getVccNet().hasPIPs());
            Assertions.assertFalse(emptyDesign.getGndNet().hasPIPs());

            boolean skipIncompatible = true; // Otherwise it fails when trying to move
                                             // the gap routing in the clock net
            mi.place(ds, skipIncompatible);
        }

        HashSet<PIP> newVccPips = new HashSet<>(emptyDesign.getVccNet().getPIPs());
        HashSet<PIP> newGndPips = new HashSet<>(emptyDesign.getGndNet().getPIPs());
        Assertions.assertFalse(emptyDesign.getVccNet().getPins().isEmpty());
        Assertions.assertFalse(emptyDesign.getGndNet().getPins().isEmpty());

        if (placeOnOriginalAnchor) {
            // Check that all static PIPs were same as the original module design
            Assertions.assertEquals(oldVccPips, newVccPips);
            Assertions.assertEquals(oldGndPips, newGndPips);
        } else {
            // Number of static PIPs expected to be the same, but not the contents
            Assertions.assertEquals(oldVccPips.size(), newVccPips.size());
            Assertions.assertEquals(oldGndPips.size(), newGndPips.size());

            Assertions.assertNotEquals(oldVccPips, newVccPips);
            Assertions.assertNotEquals(oldGndPips, newGndPips);

            // Relocate the PIPs manually
            HashSet<PIP> expectedVccPips = new HashSet<>(oldVccPips.size());
            for (PIP pip : oldVccPips) {
                Tile st = pip.getTile();
                Tile dt = module.getCorrespondingTile(st, mi.getAnchor().getTile(), module.getAnchor().getTile());
                pip.setTile(dt);
                expectedVccPips.add(pip);
            }

            HashSet<PIP> expectedGndPips = new HashSet<>(oldGndPips.size());
            for (PIP pip : oldGndPips) {
                Tile st = pip.getTile();
                Tile dt = module.getCorrespondingTile(st, mi.getAnchor().getTile(), module.getAnchor().getTile());
                pip.setTile(dt);
                expectedGndPips.add(pip);
            }

            // Now they should be equal
            Assertions.assertEquals(expectedVccPips, newVccPips);
            Assertions.assertEquals(expectedGndPips, newGndPips);
        }
    }
}
