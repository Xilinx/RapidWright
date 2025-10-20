/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestSiteInst {

    @Test
    public void testRouteIntraSiteNet() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X15Y237");
        Net net = d.createNet("dummy_test_net");
        d.createAndPlaceCell("dummy_flop", Unisim.FDRE, "SLICE_X15Y237/EFF");

        BELPin src = si.getBEL("E3").getPin("E3");
        BELPin snk = si.getBEL("EFF").getPin("D");
        Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));

        Assertions.assertEquals(si.getSiteWiresFromNet(net).size(), 3);

        Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));

        Assertions.assertEquals(si.getSiteWiresFromNet(net).size(), 0);
    }

    private void routeLUTRouteThruHelper(Design d, SiteInst si, char letter, boolean lutPrimary, BELPin snk, Unisim cellType) {
        BEL bel = snk.getBEL();
        String cellName = bel.getName() + "_inst";
        if (d.getCell(cellName) == null) {
            d.createAndPlaceCell(d.getTopEDIFCell(), cellName, cellType,
                    si.getSiteName() + "/" + bel.getName());
        }
        BELPin src = si.getSite().getBELPin(letter + (lutPrimary ? "5": "4"));
        Net net = d.createNet(src.getName() + "_net");
        Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
        Cell lut = si.getCell(letter + (lutPrimary ? "6": "5") + "LUT");
        Assertions.assertNotNull(lut);
        Assertions.assertTrue(lut.isRoutethru());
    }

    private void routeLUTRouteThruHelperFF(Design d, SiteInst si, char letter, boolean lutPrimary, boolean ffPrimary) {
        BEL bel;
        if (d.getDevice().getSeries() == Series.Series7) {
            bel = si.getBEL(letter + (ffPrimary ? "" : "5") + "FF");
        } else {
            bel = si.getBEL(letter + "FF" + (ffPrimary ? "" : "2"));
        }
        routeLUTRouteThruHelper(d, si, letter, lutPrimary, bel.getPin("D"), Unisim.FDRE);
    }

    private void routeLUTRouteThruHelperCarry(Design d, SiteInst si, char letter, boolean primary) {
        BEL bel;
        Unisim cellType;
        if (d.getDevice().getSeries() == Series.Series7) {
            bel = si.getBEL("CARRY4");
            cellType = Unisim.CARRY4;
        } else {
            bel = si.getBEL("CARRY8");
            cellType = Unisim.CARRY8;
        }
        char index = Character.forDigit(letter - 'A', 10);
        routeLUTRouteThruHelper(d, si, letter, primary, bel.getPin((primary ? "S" : "DI") + index), cellType);
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testRouteLUTRouteThru(String deviceName) {
        Design d = new Design("testRouteLUTRT", deviceName);

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        for (char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelperFF(d, si, letter, true, true);
            routeLUTRouteThruHelperFF(d, si, letter, false, false);
            if (d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105})
    public void testRouteLUTRouteThruFFandFF2(String deviceName) {
        Design d = new Design("testRouteLUTRTFFandFF2", deviceName);

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        for (char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelperFF(d, si, letter, true, true);
            routeLUTRouteThruHelperFF(d, si, letter, true, false);
            Assertions.assertNull(si.getCell(letter + "5LUT"));
            if (d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testUnrouteLUTRouteThru(String deviceName) {
        Design d = new Design("testUnrouteLUTRT", deviceName);

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        for (char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelperFF(d, si, letter, true, true);
            routeLUTRouteThruHelperFF(d, si, letter, false, false);

            // Unroute 6LUT
            {
                BELPin src = si.getSite().getBELPin(letter + "5");
                BELPin snk = si.getBEL(letter + "FF").getPin("D");
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                Cell lut6 = si.getCell(letter + "6LUT");
                Assertions.assertNull(lut6);
                Cell lut5 = si.getCell(letter + "5LUT");
                Assertions.assertNotNull(lut5);
            }
            // Unroute 5LUT
            {
                BELPin src = si.getSite().getBELPin(letter + "4");
                BELPin snk;
                if (d.getDevice().getSeries() == Series.Series7) {
                    snk = si.getBEL(letter + "5FF").getPin("D");
                } else {
                    snk = si.getBEL(letter + "FF2").getPin("D");
                }
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                Cell lut5 = si.getCell(letter + "5LUT");
                Assertions.assertNull(lut5);
            }

            if (d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }

    @Test
    public void testUnrouteFFRouteThru() {
        Design d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X58Y84");
        Cell ffCell = si.getCell("AFF");
        Assertions.assertTrue(ffCell.isFFRoutethruCell());

        // Check all intra-site routing present
        Net net = si.getNetFromSiteWire("AQ");
        Assertions.assertSame(net, si.getNetFromSiteWire("FFMUXA1_OUT1"));
        Assertions.assertEquals("D5", si.getUsedSitePIP("FFMUXA1").getInputPinName());
        Assertions.assertSame(net, si.getNetFromSiteWire("A5LUT_O5"));

        // Unroute intra-site net
        BELPin o5 = si.getBELPin("A5LUT", "O5");
        BELPin aq = si.getBELPin("AFF", "Q");
        Assertions.assertTrue(si.unrouteIntraSiteNet(o5, aq));

        // Check all intra-site net (incl. routethru cell) is gone
        Assertions.assertNull(si.getNetFromSiteWire("AQ"));
        Assertions.assertNull(si.getNetFromSiteWire("FFMUXA1_OUT1"));
        Assertions.assertNull(si.getUsedSitePIP("FFMUXA1"));
        Assertions.assertNull(si.getNetFromSiteWire("A5LUT_O5"));
        Assertions.assertNull(si.getCell("AFF"));
    }

    @Test
    public void testUnrouteIntraSiteNet() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");

        SiteInst si = design.getSiteInstFromSiteName("SLICE_X78Y212");

        // Test cross product of {LUT opin, SitePIP ipin} x {FF ipin, SitePIP opin}
        for (BELPin src : new BELPin[] {si.getBELPin("D5LUT","O5"), si.getBELPin("FFMUXD2","D5")} ) {
            for (BELPin snk : new BELPin[] {si.getBELPin("DFF2","D"), si.getBELPin("FFMUXD2","OUT2")} ) {
                Net net = si.getNetFromSiteWire("D5LUT_O5");
                Assertions.assertNotNull(net);
                Assertions.assertEquals(si.getUsedSitePIP("FFMUXD2").getInputPinName(), "D5");
                Assertions.assertNotNull(si.getNetFromSiteWire("FFMUXD2_OUT2"));

                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));

                Assertions.assertNull(si.getNetFromSiteWire("D5LUT_O5"));
                Assertions.assertNull(si.getNetFromSiteWire("FFMUXD2_OUT2"));

                Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
            }
        }

        Assertions.assertNotNull(si.getNetFromSiteWire("D5LUT_O5"));
        Assertions.assertEquals(si.getUsedSitePIP("FFMUXD2").getInputPinName(), "D5");
        Assertions.assertNotNull(si.getNetFromSiteWire("FFMUXD2_OUT2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testRouteLUTRouteThruToCarry(String deviceName) {
        Design d = new Design("testRouteLutRtCarry", deviceName);

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        for (char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelperCarry(d, si, letter, true);
            routeLUTRouteThruHelperCarry(d, si, letter, false);
            if (d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testRouteLUTRouteThruToCarryO5(String deviceName) {
        Design d = new Design("testRouteLutRtCarryO5", deviceName);
        Net net = d.createNet("net");
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));
        Unisim unisim = d.getDevice().getSeries() == Series.Series7 ? Unisim.CARRY4 : Unisim.CARRY8;
        d.createAndPlaceCell("carry", unisim, si.getSiteName() + "/" + unisim);
        Assertions.assertTrue(si.routeIntraSiteNet(net, si.getBELPin("A1", "A1"),
                si.getBELPin(unisim.toString(), "DI0")));
    }

    @Test
    public void testRouteLUTRouteThruTwice() {
        Design d = new Design("testRouteLUTRouteThruTwice", Device.KCU105);
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));
        d.createAndPlaceCell("f7mux", Unisim.MUXF7, si.getSiteName() + "/F7MUX_GH");
        d.createAndPlaceCell("fdre", Unisim.FDRE, si.getSiteName() + "/GFF");

        Net netS = d.createNet("netS");
        Assertions.assertTrue(si.routeIntraSiteNet(netS, si.getBELPin("GX", "GX"),
                si.getBELPin("F7MUX_GH", "S0")));

        Net net1 = d.createNet("net1");
        Assertions.assertTrue(si.routeIntraSiteNet(net1, si.getBELPin("G5", "G5"),
                si.getBELPin("F7MUX_GH", "1")));

        Net netD = d.createNet("netD");
        Assertions.assertTrue(si.routeIntraSiteNet(netD, si.getBELPin("G2", "G2"),
                si.getBELPin("GFF", "D")));

        Assertions.assertTrue(si.getCell("G6LUT").isRoutethru());
        Assertions.assertSame(net1, si.getNetFromSiteWire("G_O"));
        Assertions.assertTrue(si.getCell("G5LUT").isRoutethru());
        Assertions.assertSame(netD, si.getNetFromSiteWire("G5LUT_O5"));
        Assertions.assertEquals("D5", si.getUsedSitePIP("FFMUXG1").getInputPinName());
    }

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testUnrouteLUTRouteThruToCarry(String deviceName) {
        Design d = new Design("testUnrouteLutRtCarry", deviceName);

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        BEL carry;
        if (d.getDevice().getSeries() == Series.Series7) {
            carry = si.getBEL("CARRY4");
        } else {
            carry = si.getBEL("CARRY8");
        }

        for (char letter : LUTTools.lutLetters) {
            routeLUTRouteThruHelperCarry(d, si, letter, true);
            routeLUTRouteThruHelperCarry(d, si, letter, false);
            if (d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;

            char index = Character.forDigit(letter - 'A', 10);
            // Unroute 6LUT
            {
                BELPin src = si.getSite().getBELPin(letter + "5");
                BELPin snk = carry.getPin("S" + index);
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                Cell lut6 = si.getCell(letter + "6LUT");
                Assertions.assertNull(lut6);
                Cell lut5 = si.getCell(letter + "5LUT");
                Assertions.assertNotNull(lut5);
            }
            // Unroute 5LUT
            {
                BELPin src = si.getSite().getBELPin(letter + "4");
                BELPin snk = carry.getPin("DI" + index);
                Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
                Cell lut5 = si.getCell(letter + "5LUT");
                Assertions.assertNull(lut5);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"C1","D2"})
    public void testSiteRoutingToF7MUX(String inputPin) {
        Design d = new Design("testSiteRoutingToF7MUX", Device.KCU105);
        Cell c = d.createAndPlaceCell("testFMUX", Unisim.MUXF7, "SLICE_X32Y73/F7MUX_CD");
        SiteInst si = c.getSiteInst();

        Net n = d.createNet("muxf7_input");
        String muxInput = (inputPin.charAt(0) == 'C') ? "1" : "0";
        n.getLogicalNet().createPortInst("I" + muxInput, c.getEDIFCellInst());
        n.createPin(inputPin, si);

        BELPin src = si.getBELPin(inputPin, inputPin);
        BELPin snk = si.getBELPin("F7MUX_CD", muxInput);
        Assertions.assertTrue(si.routeIntraSiteNet(n, src, snk));

        // Check that routethru cells have been placed
        Cell lut = si.getCell(si.getBEL(inputPin.charAt(0) + "6LUT"));
        Assertions.assertNotNull(lut);
        Assertions.assertTrue(lut.isRoutethru());

        // Check that inserting this RT cells hasn't clobbered the non-RT cell
        Assertions.assertEquals(c, d.getCell(c.getName()));

        String[] siteWires = new String[] {inputPin, inputPin.charAt(0)+ "_O"};

        for (String siteWire : siteWires) {
            Assertions.assertEquals(n, si.getNetFromSiteWire(siteWire));
        }

        // Now unroute
        Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));

        lut = si.getCell(si.getBEL(inputPin.charAt(0) + "6LUT"));
        Assertions.assertNull(lut);
    }

    @ParameterizedTest
    @ValueSource(strings = {"F6","E6"})
    public void testSiteRoutingToF8MUX(String inputPin) {
        Design d = new Design("testSiteRoutingToF8MUX", Device.KCU105);
        Cell c = d.createAndPlaceCell("testFMUX", Unisim.MUXF8, "SLICE_X32Y73/F8MUX_TOP");
        SiteInst si = c.getSiteInst();

        Net n = d.createNet("muxf8_input");
        n.getLogicalNet().createPortInst("I1", c.getEDIFCellInst());
        n.createPin(inputPin, si);

        BELPin src = si.getBELPin(inputPin, inputPin);
        BELPin snk = si.getBELPin("F8MUX_TOP", "1");
        Assertions.assertTrue(si.routeIntraSiteNet(n, src, snk));

        // Check that both routethru cells have been placed
        Cell lut = si.getCell(si.getBEL(inputPin.charAt(0) + "6LUT"));
        Assertions.assertNotNull(lut);
        Assertions.assertTrue(lut.isRoutethru());
        Assertions.assertTrue(lut.getType().equals("MUXF8"));

        Cell f7mux = si.getCell(si.getBEL("F7MUX_EF"));
        Assertions.assertNotNull(f7mux);
        Assertions.assertTrue(f7mux.isRoutethru());
        Assertions.assertTrue(f7mux.getType().equals("MUXF8"));

        // Check that inserting either of these RT cells hasn't clobbered the
        // non-RT cell
        Assertions.assertEquals(c, d.getCell(c.getName()));

        String[] siteWires = new String[] {inputPin, "F7MUX_EF_OUT", inputPin.charAt(0)+ "_O"};

        for (String siteWire : siteWires) {
            Assertions.assertEquals(n, si.getNetFromSiteWire(siteWire));
        }
        Net staticSelectNet = inputPin.equals("F6") ? d.getGndNet() : d.getVccNet();
        Assertions.assertEquals(staticSelectNet, si.getNetFromSiteWire("EX"));
        Assertions.assertEquals(staticSelectNet, si.getSitePinInst("EX").getNet());

        // Now unroute
        Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));

        lut = si.getCell(si.getBEL(inputPin.charAt(0) + "6LUT"));
        Assertions.assertNull(lut);

        f7mux = si.getCell(si.getBEL("F7MUX_EF"));
        Assertions.assertNull(f7mux);

        for (String siteWire : siteWires) {
            Assertions.assertNull(si.getNetFromSiteWire(siteWire));
        }
        Assertions.assertNull(si.getNetFromSiteWire("EX"));
        Assertions.assertNull(si.getSitePinInst("EX"));
    }

    @Test
    public void testUnrouteSiteUpdatesNetSiteInsts() {
        Design d = new Design("testUnrouteSiteUpdatesNetSiteInsts", Device.AWS_F1);
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        Net net = d.createNet("net");
        net.createPin("A_O", si);
        net.createPin("AMUX", si);

        Assertions.assertIterableEquals(net.getSiteInsts(), Arrays.asList(si));

        si.unrouteSite();

        Assertions.assertTrue(net.getSiteInsts().isEmpty());
    }

    @Test
    public void testSiteRouting(@TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp");
        design.routeSites();
        VivadoToolsHelper.assertRoutedSuccessfullyByVivado(design, dir);
    }

    @Test
    public void testIsEmpty() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        for (SiteInst si : d.getSiteInsts()) {
            Assertions.assertFalse(si.isEmpty());
        }

        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X40Y10"));
        Assertions.assertTrue(si.isEmpty());
    }

    @Test
    public void testRouteSiteVersal() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");

        _testRouteSiteVersal(d, "SLICE_X138Y0", 40, 34);
        _testRouteSiteVersal(d, "SLICE_X139Y0", 65, 34);
        _testRouteSiteVersal(d, "SLICE_X142Y4", 40, 24);

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(d);
            // The original design had 14 pre-existing routing errors
            Assertions.assertEquals(14, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(276, rrs.fullyRoutedNets);
        }

    }

    private void _testRouteSiteVersal(Design d, String siteName, int expectedSiteNetMappings,
            int expectedUsedSitePIPs) {
        SiteInst si = d.getSiteInstFromSiteName(siteName);
        Map<Net, List<String>> origSiteNetMap = si.getNetToSiteWiresMap();
        List<SitePIP> origUsedSitePIPs = si.getUsedSitePIPs();

        Assertions.assertEquals(expectedSiteNetMappings, origSiteNetMap.size());
        Assertions.assertEquals(expectedUsedSitePIPs, origUsedSitePIPs.size());

        si.unrouteSite();

        // Remove routethru cells
        Set<String> belsWithRouteThrus = new HashSet<>();
        for (Cell c : new ArrayList<>(si.getCells())) {
            if (c.isRoutethru()) {
                belsWithRouteThrus.add(c.getBELName());
                si.removeCell(c.getBEL());
            }
        }

        // TODO Not going to reproduce these for now
        origSiteNetMap.remove(d.getNet(Net.USED_NET));

        Assertions.assertTrue(si.getNetToSiteWiresMap().isEmpty());
        Assertions.assertTrue(si.getUsedSitePIPs().isEmpty());

        si.routeSite();

        // Ensure routethru cells restored
        for (Cell c : si.getCells()) {
            if (c.isRoutethru()) {
                Assertions.assertTrue(belsWithRouteThrus.contains(c.getBELName()));
            } else {
                Assertions.assertFalse(belsWithRouteThrus.contains(c.getBELName()));
            }
        }

        Map<Net, List<String>> siteNetMap = si.getNetToSiteWiresMap();
        List<SitePIP> usedSitePIPs = si.getUsedSitePIPs();

        Assertions.assertEquals(origSiteNetMap.size(), siteNetMap.size());
        Assertions.assertEquals(origUsedSitePIPs.size(), usedSitePIPs.size());

        for (Entry<Net, List<String>> e : origSiteNetMap.entrySet()) {
            List<String> origSiteWires = e.getValue();
            List<String> siteWires = siteNetMap.get(e.getKey());
            Collections.sort(origSiteWires);
            Collections.sort(siteWires);
            Assertions.assertEquals(origSiteWires.toString(), siteWires.toString());
        }

        List<String> origUsedSitePIPNames = getSortedStringList(origUsedSitePIPs);
        List<String> usedSitePIPNames = getSortedStringList(usedSitePIPs);
        Assertions.assertEquals(origUsedSitePIPNames, usedSitePIPNames);
    }

    private List<String> getSortedStringList(List<SitePIP> sitePIPs) {
        List<String> strings = new ArrayList<>();
        for (SitePIP p : sitePIPs) {
            strings.add(p.toString());
        }
        Collections.sort(strings);
        return strings;
    }

    @Test
    public void testRemoveSitePinInst() {
        Design d = new Design("test", "xc7a200tsbg484-1");
        Cell c = d.createAndPlaceCell("testRAMB18E1", Unisim.RAMB18E1, "RAMB18_X0Y58/RAMB18E1");
        Assertions.assertEquals(SiteTypeEnum.RAMB18E1, c.getSiteInst().getSiteTypeEnum());
        SitePinInst spi = new SitePinInst(true, "DO18", c.getSiteInst());
        Net n = d.createNet("debug_net");
        n.addPin(spi);
        Assertions.assertTrue(spi.getSiteInst().removePin(spi));
    }
}
