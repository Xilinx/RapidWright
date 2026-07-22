/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestPBlockStaticNetFixer {

    private static final String DCP = "PicoBlazeArray/pblock0.dcp";

    private static PBlock getPBlock(Design design) {
        Map<String, PBlock> pblocks = ConstraintTools.getPBlocksFromXDC(design);
        PBlock pblock = pblocks.get("pe_pblock_1");
        Assertions.assertNotNull(pblock);
        return pblock;
    }

    /**
     * Recreates the fault PBlockStaticNetFixer targets: a static-net source
     * placed outside the pblock with routing walking away from it. Creates a
     * SiteInst on an unused out-of-pblock SLICE, adds one of its output site
     * pins to the GND net, and grows a PIP chain downhill from that pin's
     * node, never entering the pblock.
     *
     * @return The PIPs added to the GND net.
     */
    private static List<PIP> injectOutOfPblockGndSource(Design design, Set<Tile> pblockTiles) {
        Site fakeSite = null;
        for (Site site : design.getDevice().getAllCompatibleSites(SiteTypeEnum.SLICEL)) {
            if (pblockTiles.contains(site.getTile())) continue;
            if (design.getSiteInstFromSite(site) != null) continue;
            fakeSite = site;
            break;
        }
        Assertions.assertNotNull(fakeSite);
        SiteInst si = design.createSiteInst(fakeSite);

        Net gnd = design.getGndNet();
        SitePinInst sourcePin = null;
        for (int i = 0; i < fakeSite.getSitePinCount(); i++) {
            if (!fakeSite.isOutputPin(i)) continue;
            SitePinInst spi = gnd.createPin(fakeSite.getPinName(i), si);
            if (spi.getConnectedNode() != null && !spi.getConnectedNode().getAllDownhillPIPs().isEmpty()) {
                sourcePin = spi;
                break;
            }
            gnd.removePin(spi);
        }
        Assertions.assertNotNull(sourcePin);

        List<PIP> fakePips = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        Node curr = sourcePin.getConnectedNode();
        visited.add(curr);
        for (int hop = 0; hop < 8 && curr != null; hop++) {
            PIP next = null;
            for (PIP pip : curr.getAllDownhillPIPs()) {
                Node end = pip.getEndNode();
                if (end == null || end.getTile() == null) continue;
                if (pblockTiles.contains(end.getTile())) continue;
                if (!visited.add(end)) continue;
                next = pip;
                break;
            }
            if (next == null) break;
            gnd.addPIP(next);
            fakePips.add(next);
            curr = next.getEndNode();
        }
        Assertions.assertFalse(fakePips.isEmpty());
        return fakePips;
    }

    @Test
    public void testStripIsNoOpOnCleanDesign() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        PBlock pblock = getPBlock(design);

        int gndPips = design.getGndNet().getPIPs().size();
        int vccPips = design.getVccNet().getPIPs().size();
        int siteInsts = design.getSiteInsts().size();

        int removed = PBlockStaticNetFixer.stripOutOfPBlockStaticRouting(design,
                Collections.singletonList(pblock));

        Assertions.assertEquals(0, removed);
        Assertions.assertEquals(gndPips, design.getGndNet().getPIPs().size());
        Assertions.assertEquals(vccPips, design.getVccNet().getPIPs().size());
        Assertions.assertEquals(siteInsts, design.getSiteInsts().size());
    }

    @Test
    public void testStripRemovesOutOfPblockSourceAndRouting() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        PBlock pblock = getPBlock(design);
        Set<Tile> pblockTiles = new HashSet<>(pblock.getAllTiles());

        Net gnd = design.getGndNet();
        Set<PIP> originalGndPips = new HashSet<>(gnd.getPIPs());
        Set<PIP> originalVccPips = new HashSet<>(design.getVccNet().getPIPs());
        int originalGndPins = gnd.getPins().size();

        List<PIP> fakePips = injectOutOfPblockGndSource(design, pblockTiles);
        SiteInst fakeSource = null;
        for (SiteInst si : design.getSiteInsts()) {
            if (!pblockTiles.contains(si.getSite().getTile())) {
                fakeSource = si;
            }
        }
        Assertions.assertNotNull(fakeSource);
        Site fakeSite = fakeSource.getSite();

        int removed = PBlockStaticNetFixer.stripOutOfPBlockStaticRouting(design,
                Collections.singletonList(pblock));

        // Exactly the injected routing is stripped; Vivado's in-pblock static
        // routing survives untouched.
        Assertions.assertEquals(fakePips.size(), removed);
        Assertions.assertEquals(originalGndPips, new HashSet<>(gnd.getPIPs()));
        Assertions.assertEquals(originalVccPips, new HashSet<>(design.getVccNet().getPIPs()));

        // The out-of-pblock source SiteInst and its pin are gone.
        Assertions.assertNull(design.getSiteInstFromSite(fakeSite));
        Assertions.assertEquals(originalGndPins, gnd.getPins().size());
        for (SitePinInst spi : gnd.getPins()) {
            Assertions.assertNotEquals(fakeSite, spi.getSite());
        }

        // Every in-pblock static sink is still routed.
        DesignTools.updatePinsIsRouted(design);
        for (Net net : new Net[] { design.getGndNet(), design.getVccNet() }) {
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin()) continue;
                Assertions.assertTrue(spi.isRouted(),
                        net.getName() + " sink " + spi + " lost its routing");
            }
        }
    }

    @Test
    public void testFixReroutesOrphanedInPblockSinks() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        PBlock pblock = getPBlock(design);
        Set<Tile> pblockTiles = new HashSet<>(pblock.getAllTiles());

        injectOutOfPblockGndSource(design, pblockTiles);

        // Orphan an in-pblock GND sink by deleting the terminal PIP into its
        // connected node, as if the stripped out-of-pblock route had been
        // serving it.
        Net gnd = design.getGndNet();
        SitePinInst orphan = null;
        List<PIP> keep = null;
        for (SitePinInst spi : gnd.getPins()) {
            if (spi.isOutPin()) continue;
            Node sinkNode = spi.getConnectedNode();
            if (sinkNode == null) continue;
            keep = new ArrayList<>();
            boolean found = false;
            for (PIP pip : gnd.getPIPs()) {
                if (!found && sinkNode.equals(pip.getEndNode())) {
                    found = true;
                    continue;
                }
                keep.add(pip);
            }
            if (found) {
                orphan = spi;
                break;
            }
        }
        Assertions.assertNotNull(orphan);
        gnd.setPIPs(keep);

        // fix() reads the pblock from the design's XDC, strips the injected
        // out-of-pblock routing, and reroutes the orphaned in-pblock sink.
        PBlockStaticNetFixer.fix(design);

        DesignTools.updatePinsIsRouted(design);
        Assertions.assertTrue(orphan.isRouted());
        for (Net net : new Net[] { design.getGndNet(), design.getVccNet() }) {
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin()) continue;
                SiteInst si = spi.getSiteInst();
                if (si == null || !pblockTiles.contains(si.getSite().getTile())) continue;
                Assertions.assertTrue(spi.isRouted(),
                        net.getName() + " in-pblock sink " + spi + " is unrouted after fix()");
            }
        }
    }

    @Test
    public void testMergeOrphanStaticTieNetsIntoGlobals() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();

        // Physical-only orphans with no logical counterpart, as produced by
        // Module relocation / readCheckpoint.
        SitePinInst vccPin = vcc.getPins().stream().filter(p -> !p.isOutPin()).findFirst().get();
        vcc.removePin(vccPin, true);
        Net vccOrphan = new Net("fake_cell/VCC_1");
        design.addNet(vccOrphan);
        vccOrphan.addPin(vccPin);

        SitePinInst gndPin = gnd.getPins().stream().filter(p -> !p.isOutPin()).findFirst().get();
        gnd.removePin(gndPin, true);
        Net gndOrphan = new Net("fake_cell/GND_0");
        design.addNet(gndOrphan);
        gndOrphan.addPin(gndPin);

        // Physical-only net whose tail does not match VCC_*/GND_*; must survive.
        design.addNet(new Net("fake_cell/VCCX"));

        int moved = PBlockStaticNetFixer.mergeOrphanStaticTieNetsIntoGlobals(design);

        Assertions.assertEquals(2, moved);
        Assertions.assertNull(design.getNet("fake_cell/VCC_1"));
        Assertions.assertNull(design.getNet("fake_cell/GND_0"));
        Assertions.assertNotNull(design.getNet("fake_cell/VCCX"));
        Assertions.assertEquals(vcc, vccPin.getNet());
        Assertions.assertEquals(gnd, gndPin.getNet());
        Assertions.assertTrue(vcc.getPins().contains(vccPin));
        Assertions.assertTrue(gnd.getPins().contains(gndPin));
    }
}
