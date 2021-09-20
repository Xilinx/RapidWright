package com.xilinx.rapidwright.design;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.design.tools.RelocationTools;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.util.Pair;
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

        String metaPath = "RapidWrightDCP/picoblaze_ooc_X9Y235.metadata";
        if (hierarchyPrefix.isEmpty()) {
            Design design2 = new Design("design2", design1.getPartName());
            Module module = new Module(Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT), metaPath);
            ModuleInst mi = design2.createModuleInst("inst", module);
            mi.placeOnOriginalAnchor();
            Collection<ModuleInst> moduleInsts = Arrays.asList(mi);

            relocateModuleInstsAndCompare(colOffset, rowOffset, expectSuccess, design1, design2, moduleInsts);
        }
    }

    private void relocateModuleInstsAndCompare(int colOffset, int rowOffset, boolean expectSuccess, Design design1, Design design2, Collection<ModuleInst> moduleInsts) {
        List<Pair<ModuleInst,Site>> newSite = new ArrayList<>();
        for (ModuleInst mi : moduleInsts) {
            Assertions.assertTrue(mi.isPlaced());

            SiteInst si = mi.getAnchor();
            Site ss = si.getSite();
            Tile st = si.getTile();
            Tile dt = st.getTileXYNeighbor(colOffset, rowOffset);
            Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);
            newSite.add(new Pair<>(mi, ds));

            if (!mi.getModule().isValidPlacement(ds, null /* Do not check existing placements */)) {
                Assertions.assertFalse(expectSuccess);
            }
        }

        for (Pair<ModuleInst,Site> e : newSite) {
            ModuleInst mi = e.getFirst();
            Site ds = e.getSecond();

            if (expectSuccess) {
                Assertions.assertEquals(mi.place(ds), expectSuccess);

                for (SiteInst si : mi.getSiteInsts()) {
                    for (Cell c2 : si.getCells()) {
                        Cell c1 = design1.getCell(c2.getName());
                        if (c1 == null && c2.getName().startsWith(mi.getName() + "/")) {
                            // Retry without ModuleInst hierarchy in case it was flattened
                            c1 = design1.getCell(c2.getName().substring(mi.getName().length() + 1));
                        }
                        Assertions.assertNotNull(c1);
                        Assertions.assertEquals(c1.getSite(), c2.getSite());
                    }
                }

                for (Net n2 : mi.getNets()) {
                    Net n1 = design1.getNet(n2.getName());
                    if (n1 == null && n2.getName().startsWith(mi.getName() + "/")) {
                        System.out.println(n2.getName().substring(mi.getName().length() + 1));
                        // Retry without ModuleInst hierarchy in case it was flattened
                        n1 = design1.getNet(n2.getName().substring(mi.getName().length() + 1));
                    }
                    if (n1 == null && (n2.isStaticNet() || n2.getName() == Net.USED_NET)) {
                        // Module relocation does not propagate static nets nor USED_NETs
                        continue;
                    }
                    System.out.println(mi.getName() + " : " + n2);
                    Assertions.assertNotNull(n1);

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
                , Arguments.of("", 0, -5, true)
                , Arguments.of("", 9, -5, true)
                , Arguments.of("", 0, 0, true)
                , Arguments.of("", 1, 0, false)     // Incompatible tile
                , Arguments.of("", 0, 1, false)     // Incompatible tile
                , Arguments.of("", -100, 0, false)  // Out of X range
                , Arguments.of("", 0, 200, false)   // Out of Y range
                , Arguments.of("processor/", 9, 0, false) // SiteInsts contains matching and non-matching cells
        );
    }

    @ParameterizedTest(name = "Relocate PicoBlaze4 OOC '{0}' ({1},{2})")
    @MethodSource()
    @CheckOpenFiles
    public void testPicoblaze4OOC(String hierarchyPrefix, int colOffset, int rowOffset, boolean expectSuccess) {
        String dcpPath = "RapidWrightDCP/picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp";
        Design design1 = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        Assertions.assertEquals(RelocationTools.relocate(design1, hierarchyPrefix, colOffset, rowOffset),
                expectSuccess);

        Design design2 = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);
        Collection<ModuleInst> moduleInsts;
        if (hierarchyPrefix.isEmpty()) {
            moduleInsts = design2.getModuleInsts();
        } else {
            moduleInsts = new ArrayList<>();
            // Strip trailing slashes
            ModuleInst mi = design2.getModuleInst(hierarchyPrefix.replaceAll("/$", ""));
            Assertions.assertNotNull(mi);
            moduleInsts = Arrays.asList(mi);
        }
        // TODO: Workaround ModuleInst-s not having their anchor saved
        moduleInsts.forEach((mi) -> {
            if (mi.getAnchor() == null) {
                for (SiteInst si : mi.getSiteInsts()) {
                    if (si.getSite().getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36) {
                        mi.setAnchor(si);
                        break;
                    }
                }
                Assertions.assertTrue(mi.isPlaced());
            }
        });

        relocateModuleInstsAndCompare(colOffset, rowOffset, expectSuccess, design1, design2, moduleInsts);
    }

    public static Stream<Arguments> testPicoblaze4OOC() {
        return Stream.of(
                  Arguments.of("", 0, 5, true)
                , Arguments.of("picoblaze_6_65/", 0, 5, true)
                , Arguments.of("picoblaze_6_60/", 0, 5, false) // placement conflict
        );
    }

}
