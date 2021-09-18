package com.xilinx.rapidwright.design;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.design.tools.RelocationTools;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestRelocationTools {

    @ParameterizedTest(name = "Relocate PicoBlaze OOC '{0}' ({1},{2})")
    @MethodSource()
    @CheckOpenFiles
    public void testPicoblazeOOC(String hierarchyPrefix, int colOffset, int rowOffset, boolean expectSuccess) {
        String dcpPath = "RapidWrightDCP/picoblaze_ooc_X9Y235.dcp";
        Design design1 = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        Assertions.assertEquals(RelocationTools.relocate(design1, hierarchyPrefix, colOffset, rowOffset),
                expectSuccess);

        Design design2 = new Design("design2", design1.getPartName());
        String metaPath = "RapidWrightDCP/picoblaze_ooc_X9Y235.metadata";

        if (hierarchyPrefix.isEmpty()) {
            Module module = new Module(Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT), metaPath);
            SiteInst si = module.getAnchor();
            Site ss = si.getSite();
            Tile st = si.getTile();
            Tile dt = st.getTileXYNeighbor(colOffset, rowOffset);
            Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);
            Assertions.assertEquals(module.isValidPlacement(ds, design2), expectSuccess);

            if (expectSuccess) {
                ModuleInst mi = design2.createModuleInst("inst", module);
                Assertions.assertEquals(mi.place(ds), expectSuccess);

                for (Cell c1 : design1.getCells()) {
                    Cell c2 = design2.getCell(mi.getName() + "/" + c1.getName());
                    Assertions.assertNotNull(c2);
                    Assertions.assertEquals(c1.getSite(), c2.getSite());
                }

                for (Net n1 : design1.getNets()) {
                    Net n2 = design2.getNet(mi.getName() + "/" + n1.getName());
                    if (n2 == null && (n1.isStaticNet() || n1.getName() == Net.USED_NET)) {
                        // Module relocation does not propagate static nets nor USED_NETs
                        continue;
                    }
                    Assertions.assertNotNull(n2);

                    if (n1.isClockNet() || n1.hasGapRouting()) {
                        // Module relocation unroutes all clock nets?
                        Assertions.assertFalse(n2.hasPIPs());
                        continue;
                    }

                    Set<PIP> p1 = new HashSet<>(n1.getPIPs());
                    Set<PIP> p2 = new HashSet<>(n2.getPIPs());
                    Assertions.assertEquals(p1, p2);
                }
            }
        }
    }

    public static Stream<Arguments> testPicoblazeOOC() {
        return Stream.of(
                  Arguments.of("", 0, 5, true)
                , Arguments.of("", 0, 5, true)
                , Arguments.of("", 9, 5, true)
                , Arguments.of("", 0, 0, true)
                , Arguments.of("", 1, 0, false)     // Incompatible tile
                , Arguments.of("", 0, 1, false)     // Incompatible tile
                , Arguments.of("", -100, 0, false)  // Out of X range
                , Arguments.of("", 0, 200, false)   // Out of Y range
                , Arguments.of("processor/", 9, 0, false) // SiteInsts contains matching and non-matching cells
        );
    }

}
