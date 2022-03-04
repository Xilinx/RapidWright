package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

        if (!placeOnOriginalAnchor) {
            final int xOffset = 0;
            final int yOffset = -5;

            SiteInst si = module.getAnchor();
            Site ss = si.getSite();
            Tile st = si.getTile();
            Tile dt = st.getTileXYNeighbor(xOffset, yOffset);
            Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);
            boolean skipIncompatible = true; // Since it attempts to move the gap routing in the clock net

            Assertions.assertTrue(emptyDesign.getVccNet().hasPIPs());
            Assertions.assertTrue(emptyDesign.getGndNet().hasPIPs());

            mi.unplace();

            // FIXME
            // Assertions.assertFalse(emptyDesign.getVccNet().hasPIPs());
            // Assertions.assertFalse(emptyDesign.getGndNet().hasPIPs());

            mi.place(ds, skipIncompatible);
        }

        HashSet<PIP> newVccPips = new HashSet<>(emptyDesign.getVccNet().getPIPs());
        HashSet<PIP> newGndPips = new HashSet<>(emptyDesign.getGndNet().getPIPs());

        if (placeOnOriginalAnchor) {
            // Check that all static PIPs were same as the original module design
            Assertions.assertEquals(newVccPips, oldVccPips);
            Assertions.assertEquals(newGndPips, oldGndPips);
        } else {
            // Number of static PIPs expected to be the same, but not the contents
            Assertions.assertEquals(newVccPips.size(), oldVccPips.size());
            Assertions.assertEquals(newGndPips.size(), oldGndPips.size());

            Assertions.assertNotEquals(newVccPips, oldVccPips);
            Assertions.assertNotEquals(newGndPips, oldGndPips);

            // Relocate the PIPs manually
            HashSet<PIP> expectedVccPips = new HashSet<>(oldVccPips);
            for (PIP pip : expectedVccPips) {
                Tile st = pip.getTile();
                Tile dt = module.getCorrespondingTile(st, mi.getAnchor().getTile(), module.getAnchor().getTile());
                pip.setTile(dt);
            }

            HashSet<PIP> expectedGndPips = new HashSet<>(oldGndPips);
            for (PIP pip : expectedGndPips) {
                Tile st = pip.getTile();
                Tile dt = module.getCorrespondingTile(st, mi.getAnchor().getTile(), module.getAnchor().getTile());
                pip.setTile(dt);
            }

            // Now they should be equal
            Assertions.assertEquals(newVccPips, expectedVccPips);
            Assertions.assertEquals(newGndPips, expectedGndPips);
        }
    }
}
