/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.rwroute;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestGlobalSignalRouting {
    @ParameterizedTest
    @CsvSource({
            "CLKBWRCLK,",
            "RSTRAMB,",
            "WEBWE[0],WEBL0",
            "ADDRENA,ADDRENAL",
            "ADDRENB,ADDRENBU"
    })
    public void testRAMB36(String logicalPinName, String erroringSitePinName) throws Throwable {
        Design design = new Design("design", "xcvu3p");
        Cell bufg = design.createAndPlaceCell("test_bufg", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");
        Net globalNet = design.createNet("clk");
        globalNet.connect(bufg,"O");

        Cell target = design.createAndPlaceCell("test_ram", Unisim.RAMB36E2, "RAMB36_X0Y0/RAMB36E2");
        if (logicalPinName.equals("CLKBWRCLK") || logicalPinName.equals("RSTRAMB") ||
                logicalPinName.equals("ADDRENA") || logicalPinName.equals("ADDRENB")) {
            target.addPinMapping(logicalPinName + "L", logicalPinName);
            target.addPinMapping(logicalPinName + "U", logicalPinName);
        } else if (logicalPinName.equals("WEBWE[0]")) {
            target.addPinMapping("WEBWEL0", logicalPinName);
            target.addPinMapping("WEBWEU0", logicalPinName);
        }
        globalNet.connect(target, logicalPinName);

        // FIXME: Currently, Net.connect() only connects the first physical pin to the net
        //        This is a canary assertion that will light up when this gets fixed.
        Assertions.assertEquals(2 /* 3 */, globalNet.getPins().size());

        Executable e = () -> GlobalSignalRouting.symmetricClkRouting(globalNet, design.getDevice(),
                (n) -> NodeStatus.AVAILABLE, null);
        if (erroringSitePinName == null) {
            e.execute();
        } else {
            // FIXME: Known broken -- see https://github.com/Xilinx/RapidWright/issues/756
            RuntimeException ex = Assertions.assertThrows(RuntimeException.class, e, "true");
            Assertions.assertEquals("ERROR: No mapped LCB to SitePinInst IN RAMB36_X0Y0." + erroringSitePinName,
                    ex.getMessage());
        }
    }

    // NOTE: This method does not avoid any existing routing (static or signal),
    //       only their pins
    NodeStatus getNodeState(Design design, NetType netType, Node n) {
        SitePin sitePin = n.getSitePin();
        SiteInst site = (sitePin != null) ? design.getSiteInstFromSite(sitePin.getSite()) : null;
        SitePinInst spi = (site != null) ? site.getSitePinInst(sitePin.getPinName()) : null;
        Net net = (spi != null) ? spi.getNet() : null;
        return net == null ? NodeStatus.AVAILABLE :
                net.getType() == netType ? NodeStatus.INUSE : NodeStatus.UNAVAILABLE;
    }

    @Test
    public void testRouteStaticNet() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");

        RWRoute.preprocess(design);

        Net gndNet = design.getGndNet();
        Net vccNet = design.getVccNet();
        Assertions.assertFalse(gndNet.hasPIPs());
        Assertions.assertFalse(vccNet.hasPIPs());

        List<SitePinInst> gndPins = gndNet.getPins();
        List<SitePinInst> vccPins = vccNet.getPins();

        boolean invertLutInputs = true;
        RouterHelper.invertPossibleGndPinsToVccPins(design, gndPins, invertLutInputs);

        Assertions.assertEquals(19010, gndPins.size());
        Assertions.assertEquals(23099, vccPins.size());

        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());

        GlobalSignalRouting.routeStaticNet(gndPins, (n) -> getNodeState(design, NetType.GND, n), design, routeThruHelper);
        gndPins = gndNet.getPins();
        Assertions.assertEquals(737, gndPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(19010, gndPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(33429, gndNet.getPIPs().size());

        GlobalSignalRouting.routeStaticNet(vccPins, (n) -> getNodeState(design, NetType.VCC, n), design, routeThruHelper);
        vccPins = vccNet.getPins();
        Assertions.assertEquals(0, vccPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(23099, vccPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(27491, vccNet.getPIPs().size());

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(2, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    public void testRouteStaticNetOnVersalDevice(boolean createStaticPins, @TempDir Path tempDir) {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        design.unrouteDesign();

        Net gndNet = design.getGndNet();
        Net vccNet = design.getVccNet();
        Assertions.assertFalse(gndNet.hasPIPs());
        Assertions.assertFalse(vccNet.hasPIPs());

        List<SitePinInst> gndPins;
        List<SitePinInst> vccPins;

        if (createStaticPins) {
            // Simulate a DCP with no static routing (and thus no static sink pins)
            // by refreshing the unrouted design
            Path tempCheckpoint = tempDir.resolve("checkpoint.dcp");
            design.writeCheckpoint(tempCheckpoint);
            design = Design.readCheckpoint(tempCheckpoint);

            gndNet = design.getGndNet();
            vccNet = design.getVccNet();
            gndPins = gndNet.getPins();
            vccPins = vccNet.getPins();

            Assertions.assertEquals(0, vccPins.size());
            Assertions.assertEquals(0, gndPins.size());

            DesignTools.createMissingSitePinInsts(design, gndNet);
            DesignTools.createMissingSitePinInsts(design, vccNet);

            Assertions.assertEquals(140, gndPins.size());
            Assertions.assertEquals(165, vccPins.size());
        } else {
            gndPins = gndNet.getPins();
            vccPins = vccNet.getPins();

            // Remove all existing output pins so that we can count how many new ones were created
            for (SitePinInst spi : new ArrayList<>(gndPins)) {
                if (spi.isOutPin()) {
                    gndNet.removePin(spi);
                    spi.detachSiteInst();
                }
            }
            for (SitePinInst spi : new ArrayList<>(vccPins)) {
                if (spi.isOutPin()) {
                    vccNet.removePin(spi);
                    spi.detachSiteInst();
                }
            }

            Assertions.assertEquals(123, gndPins.size());
            Assertions.assertEquals(230, vccPins.size());
        }

        // Even though we may be starting from a fully-routed design, Versal designs still need
        // some preprocessing to discover all SLICE.CE pins
        DesignTools.createPossiblePinsToStaticNets(design);

        Assertions.assertEquals(123, gndPins.size());
        Assertions.assertEquals(232, vccPins.size());

        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());

        Design finalDesign = design;
        GlobalSignalRouting.routeStaticNet(gndPins, (n) -> getNodeState(finalDesign, NetType.GND, n), design, routeThruHelper);
        Assertions.assertEquals(8, gndPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(123, gndPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(436, gndNet.getPIPs().size());

        GlobalSignalRouting.routeStaticNet(vccPins, (n) -> getNodeState(finalDesign, NetType.VCC, n), design, routeThruHelper);
        Assertions.assertEquals(0, vccPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(232, vccPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(464, vccNet.getPIPs().size());

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(2, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
    }

    /**
     * Whether the slice output is a static source, checked separately for GND and for VCC, which must agree.
     */
    private static boolean isStaticSource(Design design, Site site, String sitePinName) {
        Node node = site.getConnectedNode(sitePinName);
        Assertions.assertEquals(IntentCode.NODE_CLE_OUTPUT, node.getIntentCode());
        SitePin gndSitePin = GlobalSignalRouting.getStaticSourceSitePin(design, node, NetType.GND);
        SitePin vccSitePin = GlobalSignalRouting.getStaticSourceSitePin(design, node, NetType.VCC);
        Assertions.assertEquals(gndSitePin != null, vccSitePin != null,
                sitePinName + ": GND source " + gndSitePin + ", VCC source " + vccSitePin);
        for (SitePin sitePin : Arrays.asList(gndSitePin, vccSitePin)) {
            if (sitePin != null) {
                Assertions.assertEquals(site, sitePin.getSite());
                Assertions.assertEquals(sitePinName, sitePin.getPinName());
            }
        }
        return gndSitePin != null;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLICE_X44Y62" /* SLICEL */, "SLICE_X45Y62" /* SLICEM */})
    public void testVersalStaticSourceSkipsSliceOutputOfUsedFF(String siteName) {
        Design design = new Design("versal_static_source_ff", "xcvc1902-vsva2197-2MP-e-S");
        Site site = design.getDevice().getSite(siteName);
        SiteInst si = design.createSiteInst(site);
        Assertions.assertTrue(isStaticSource(design, site, "AQ"));
        Assertions.assertTrue(isStaticSource(design, site, "A_O"));

        // FDRE on AFF with D from AX through the BYP input of FFMUXA1 and Q unconnected:
        // the AQ site wire carries no net, but OUTMUXA1 must still be able to select AFF.Q
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, siteName + "/AFF");
        Net d = design.createNet("d");
        si.routeIntraSiteNet(d, si.getBELPin("AX", "AX"), si.getBELPin("AX_IMR", "D"));
        si.routeIntraSiteNet(d, si.getBELPin("AX_IMR", "Q"), ff.getBEL().getPin("D"));
        Assertions.assertEquals(d, si.getNetFromSiteWire("FFMUXA1_OUT1"));
        Assertions.assertNull(si.getNetFromSiteWire("AQ"));

        Assertions.assertFalse(isStaticSource(design, site, "AQ"));
        // The A LUTs remain unused, so A_O is still a valid source, as are the other letters
        Assertions.assertTrue(isStaticSource(design, site, "A_O"));
        Assertions.assertTrue(isStaticSource(design, site, "BQ"));
        Assertions.assertTrue(isStaticSource(design, site, "B_O"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLICE_X44Y62" /* SLICEL */, "SLICE_X45Y62" /* SLICEM */})
    public void testVersalStaticSourceSkipsSliceOutputOfUsed5LUT(String siteName) {
        Design design = new Design("versal_static_source_lut5", "xcvc1902-vsva2197-2MP-e-S");
        Site site = design.getDevice().getSite(siteName);
        SiteInst si = design.createSiteInst(site);

        // Only the 5LUT is used: its O5 output needs A6 to be held high, so the 6LUT's O6
        // output (A_O, also reaching AQ through FFMUXA1 and OUTMUXA1) is not a free constant
        design.createAndPlaceCell("lut", Unisim.LUT2, siteName + "/A5LUT");
        Assertions.assertNull(si.getCell("A6LUT"));

        Assertions.assertFalse(isStaticSource(design, site, "A_O"));
        Assertions.assertFalse(isStaticSource(design, site, "AQ"));
        Assertions.assertTrue(isStaticSource(design, site, "B_O"));
        Assertions.assertTrue(isStaticSource(design, site, "BQ"));
    }

    // This is a minimized testcase from the result of GlobalSignalRouter
    @ParameterizedTest
    @CsvSource({
            "false,false",
            "true,false",
            "false,true"
    })
    public void testMuxOutPinAsStaticSourceEvenWithLut6(boolean setCmuxCtag, boolean fullIntraSiteRouting) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");
        Device device = design.getDevice();
        Net gndNet = design.getGndNet();
        boolean srcToSinkOrder = true;
        gndNet.setPIPs(RouterHelper.getPIPsFromNodes(Arrays.asList(
                device.getNode("CLEM_X52Y123/CLE_CLE_M_SITE_0_CMUX"),
                device.getNode("INT_X52Y123/INT_NODE_SDQ_90_INT_OUT1"),
                device.getNode("INT_X52Y123/WW1_W_BEG7"),
                device.getNode("INT_X51Y124/INODE_E_1_FT1"),
                device.getNode("INT_X51Y123/IMUX_E15")
        ), srcToSinkOrder));
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X81Y123");
        Cell lut6 = si.getCell("C6LUT");
        Assertions.assertEquals("LUT6", lut6.getType());
        String const0 = "<const0>";
        if (setCmuxCtag) {
            if (!fullIntraSiteRouting) {
                // Setting just the CMUX cTag
                si.routeIntraSiteNet(gndNet, si.getBELPin("CMUX", "CMUX"), si.getBELPin("CMUX", "CMUX"));
            } else {
                // Full O5 -> OUTMUXC -> CMUX intra-site routing
                si.routeIntraSiteNet(gndNet, si.getBELPin("C5LUT", "O5"), si.getBELPin("CMUX", "CMUX"));
            }
        }

        String status = VivadoTools.reportRouteStatus(design, const0);
        if (!setCmuxCtag) {
            // Not setting the CMUX cTag correctly causes SLICE_X81Y123 to have invalid site programming
            Assertions.assertEquals("CONFLICTS", status);
        } else {
            // In both cases, SLICE_X81Y123 does not exhibit invalid site programming and thus net appears partially routed
            Assertions.assertEquals("PARTIAL", status);
        }
    }

    // This is a minimized testcase from the result of GlobalSignalRouter
    @ParameterizedTest
    @CsvSource({
            "false,false",
            "true,false",
            "false,true"
    })
    public void testMuxOutPinAsStaticSourceEvenWithLutRam(boolean setFmuxCtag, boolean fullIntraSiteRouting) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        Device device = design.getDevice();
        Net gndNet = design.getGndNet();
        boolean srcToSinkOrder = true;
        gndNet.setPIPs(RouterHelper.getPIPsFromNodes(Arrays.asList(
                device.getNode("CLEM_X52Y218/CLE_CLE_M_SITE_0_FMUX"),
                device.getNode("INT_X52Y218/SDQNODE_W_2_FT1"),
                device.getNode("INT_X52Y218/EE2_W_BEG0"),
                device.getNode("INT_X53Y218/INODE_W_1_FT1"),
                device.getNode("INT_X53Y217/IMUX_W46"),
                device.getNode("BRAM_X53Y215/BRAM_BRAM_CORE_3_ADDRENAU_PIN")
        ), srcToSinkOrder));
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X81Y218");
        Cell lutRam = si.getCell("F6LUT");
        Assertions.assertEquals("RAMS64E", lutRam.getType());
        String const0 = "bd_0_i/hls_inst/inst/<const0>";
        if (setFmuxCtag) {
            if (!fullIntraSiteRouting) {
                // Setting just the FMUX cTag
                si.routeIntraSiteNet(gndNet, si.getBELPin("FMUX", "FMUX"), si.getBELPin("FMUX", "FMUX"));
            } else {
                // Full O5 -> OUTMUXF -> FMUX intra-site routing
                si.routeIntraSiteNet(gndNet, si.getBELPin("F5LUT", "O5"), si.getBELPin("FMUX", "FMUX"));
            }
        }
        String status = VivadoTools.reportRouteStatus(design, const0);
        if (!setFmuxCtag) {
            // Not setting the FMUX cTag correctly causes SLICE_X81Y218 to have invalid site programming
            Assertions.assertEquals("CONFLICTS", status);
        } else {
            // In both cases, SLICE_X81Y218 does not exhibit invalid site programming and thus net appears partially routed
            Assertions.assertEquals("PARTIAL", status);
        }
    }

    @Test
    public void testSymmetricClkRoutingPS8ClockInputs() {
        // The PS8 tile of an UltraScale+ device is outside the clock regions: its clock inputs are reached through
        // leaf clock buffers of the clock regions of their INT tiles
        Design design = new Design("ps8_clk", "xczu3eg-sfvc784-2-e");
        Device device = design.getDevice();
        Net clk = design.createNet("clk");
        clk.connect(design.createAndPlaceCell("bufgce", Unisim.BUFGCE, "BUFGCE_X0Y2/BUFCE"), "O");
        SiteInst ps8 = design.createSiteInst(device.getSite("PS8_X0Y0"));
        Assertions.assertNull(ps8.getTile().getClockRegion());
        List<SitePinInst> sinks = new ArrayList<>();
        for (String pin : new String[]{"PL_GP0_CLOCKIN", "PL_GP2_CLOCKIN"}) {
            sinks.add(clk.createPin(pin, ps8));
        }

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        for (SitePinInst sink : sinks) {
            Assertions.assertTrue(sink.isRouted(), sink.toString());
        }
    }

    @Test
    public void testSymmetricClkRoutingBitsliceFifoClk() {
        // The CLB2PHY_FIFO_CLK input of an UltraScale+ BITSLICE_RX_TX is only reached from its INT tile node through
        // a route-thru of a site of the XIPHY_BYTE tile (as Vivado routes it)
        Design design = new Design("bitslice_fifo_clk", "xcvu3p-ffvc1517-2-e");
        Device device = design.getDevice();
        Net clk = design.createNet("clk");
        clk.connect(design.createAndPlaceCell("bufgce", Unisim.BUFGCE, "BUFGCE_X0Y21/BUFCE"), "O");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X55Y30/AFF");
        clk.connect(ff, "C");
        design.getVccNet().connect(ff, "CE");
        design.getGndNet().connect(ff, "D");
        design.getGndNet().connect(ff, "R");
        SiteInst bitslice = design.createSiteInst(device.getSite("BITSLICE_RX_TX_X0Y23"));
        SitePinInst fifoClk = clk.createPin("CLB2PHY_FIFO_CLK", bitslice);
        design.routeSites();

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        Assertions.assertTrue(fifoClk.isRouted());
        Assertions.assertTrue(clk.getPIPs().stream().anyMatch((pip) -> pip.isRouteThru() &&
                pip.getTile() == fifoClk.getTile()));
    }

    @Test
    public void testSymmetricClkRouting() {
        Design design = RapidWrightDCP.loadDCP("two_clk_check_NetTools.dcp");
        design.unrouteDesign();
        // Simulate the preserve method
        Set<Node> used = new HashSet<>();

        Map<Integer, Set<ClockRegion>> usedRoutingTracks = new HashMap<>();
        for (String netName : Arrays.asList("clk1_IBUF_BUFG", "clk2_IBUF_BUFG", "rst1", "rst2")) {
            Net net = design.getNet(netName);
            Assertions.assertTrue(NetTools.isGlobalClock(net));
            GlobalSignalRouting.symmetricClkRouting(net, design.getDevice(),
                    (n) -> used.contains(n) ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE,
                    usedRoutingTracks);
            for (PIP pip: net.getPIPs()) {
                for (Node node: Arrays.asList(pip.getStartNode(), pip.getEndNode())) {
                    if (node != null) used.add(node);
                }
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(net));
            for (SitePinInst spi : net.getPins()) {
                Assertions.assertTrue(spi.isRouted());
            }
        }

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(4, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        } else {
            System.err.println("WARNING: vivado not on PATH");
        }
    }

    @Test
    public void testSymmetricClkRoutingUltraScalePlusMultiSLR() {
        // A clock spanning most of the clock region rows of a 4-SLR device, with two clock
        // regions per row: each row buffer of the vertical distribution column must be driven
        // only once, even when a row is also passed through to reach the rows beyond it
        Design design = new Design("us_clk_multi_slr", "xcvu13p-fsga2577-1-i");
        Device device = design.getDevice();
        Net clk = design.createNet("clk");
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        Cell bufgce = design.createAndPlaceCell("bufgceInst", Unisim.BUFGCE, "BUFGCE_X0Y120/BUFCE");
        clk.connect(bufgce, "O");

        // One flip-flop in each of 17 clock regions spanning rows 2 to 12 (SLRs 0 to 3); the order
        // matters, as it determines the order in which the clock regions are routed
        String[] sinkSites = {"SLICE_X117Y570", "SLICE_X114Y575", "SLICE_X116Y621", "SLICE_X113Y724",
                "SLICE_X117Y720", "SLICE_X116Y717", "SLICE_X116Y489", "SLICE_X116Y352", "SLICE_X118Y352",
                "SLICE_X113Y183", "SLICE_X117Y183", "SLICE_X117Y166", "SLICE_X110Y163", "SLICE_X117Y240",
                "SLICE_X116Y298", "SLICE_X116Y361", "SLICE_X117Y369"};
        int i = 0;
        for (String site : sinkSites) {
            Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, site + "/AFF");
            clk.connect(ff, "C");
            vcc.connect(ff, "CE");
            gnd.connect(ff, "D");
            gnd.connect(ff, "R");
        }
        design.routeSites();

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Map<Node, PIP> driver = new HashMap<>();
        Set<Node> starts = new HashSet<>();
        for (PIP pip : clk.getPIPs()) {
            Node start = pip.isReversed() ? pip.getEndNode() : pip.getStartNode();
            Node end = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
            starts.add(start);
            PIP other = driver.put(end, pip);
            Assertions.assertNull(other, "Node " + end + " driven by " + pip + " and " + other);
        }
        // No undriven routing (an island) other than at the clock source
        Node source = clk.getSource().getConnectedNode();
        for (Node start : starts) {
            Assertions.assertTrue(start.equals(source) || driver.containsKey(start), "Undriven node " + start);
        }
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        for (SitePinInst spi : clk.getPins()) {
            Assertions.assertTrue(spi.isRouted(), spi.toString());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "CLKFBIN,''",
            "CLKIN1,SLICE_X50Y30 SLICE_X50Y100",
            // The only flip-flop above the centroid: no downward branch of the clock tree
            "CLKIN1,SLICE_X50Y250"
    })
    public void testSymmetricClkRoutingUltraScalePlusMMCMInput(String mmcmPinName, String flipFlopSites) {
        // A clock driving the clock input or the feedback clock input of an MMCM: such an input is reached from
        // the horizontal distribution through the clock muxes of the CMT tile, not from an INT tile through a
        // leaf clock buffer
        Design design = new Design("us_clk_mmcm_input", "xcvu3p");
        Device device = design.getDevice();
        Net clk = design.createNet("clk");
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        Cell bufgce = design.createAndPlaceCell("bufgceInst", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");
        clk.connect(bufgce, "O");
        Cell mmcm = design.createAndPlaceCell("mmcmInst", Unisim.MMCME4_ADV, "MMCM_X0Y0/MMCM");
        clk.connect(mmcm, mmcmPinName);
        int i = 0;
        for (String site : flipFlopSites.split(" ")) {
            if (site.isEmpty()) continue;
            Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, site + "/AFF");
            clk.connect(ff, "C");
            vcc.connect(ff, "CE");
            gnd.connect(ff, "D");
            gnd.connect(ff, "R");
        }
        design.routeSites();
        SitePinInst mmcmPin = mmcm.getSiteInst().getSitePinInst(mmcmPinName);
        Assertions.assertNotNull(mmcmPin);
        Assertions.assertEquals(clk, mmcmPin.getNet());

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        for (SitePinInst spi : clk.getPins()) {
            Assertions.assertTrue(spi.isRouted(), spi.toString());
        }
        // No dangling routing (an antenna): each node driven by the clock drives another one or is a sink
        Set<Node> driving = new HashSet<>();
        Set<Node> driven = new HashSet<>();
        for (PIP pip : clk.getPIPs()) {
            driving.add(pip.isReversed() ? pip.getEndNode() : pip.getStartNode());
            driven.add(pip.isReversed() ? pip.getStartNode() : pip.getEndNode());
        }
        for (SitePinInst spi : clk.getPins()) {
            driving.add(spi.getConnectedNode());
        }
        for (Node node : driven) {
            Assertions.assertTrue(driving.contains(node), "Antenna " + node);
        }
    }

    /**
     * A clock spanning the clock regions of three SLRs of a multi-SLR Versal device, for which
     * the vertical distribution trees lack paths for some clock region rows: those must still
     * get a vertical distribution line.
     */
    @Test
    public void testVersalClockRoutingMultiSLRMissingVDistrPaths() {
        Design design = new Design("versal_clk_multi_slr", "xcvp1802-lsvc4072-2MP-e-S");
        EDIFCell top = design.getTopEDIFCell();
        Device device = design.getDevice();
        Net vcc = design.getVccNet();

        Net clkIn = design.createNet("clkIn");
        clkIn.getLogicalNet().createPortInst(top.createPort(clkIn.getName(), EDIFDirection.INPUT, 1));
        Net clk = design.createNet("clk");
        Cell bufgce = createBUFGCE(design, top, "bufgce", device.getSite("BUFGCE_X12Y23"));
        clkIn.connect(bufgce, "I");
        clk.connect(bufgce, "O");
        vcc.connect(bufgce, "CE");

        // One flip-flop in each of these clock regions (rows 2 to 11, in SLRs 0 to 3)
        Set<String> clockRegions = new HashSet<>(Arrays.asList("X5Y5", "X5Y8", "X5Y11", "X7Y2", "X7Y3",
                "X6Y3", "X5Y3"));
        int i = 0;
        for (Site site : device.getAllSitesOfType(SiteTypeEnum.SLICEL)) {
            if (clockRegions.remove(site.getTile().getClockRegion().getName())) {
                Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, site.getName() + "/AFF");
                clk.connect(ff, "C");
            }
        }
        Assertions.assertTrue(clockRegions.isEmpty(), clockRegions.toString());
        design.routeSites();

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Map<Node, PIP> driver = new HashMap<>();
        Set<Node> starts = new HashSet<>();
        for (PIP pip : clk.getPIPs()) {
            Node end = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
            PIP other = driver.put(end, pip);
            Assertions.assertNull(other, "Node " + end + " driven by " + pip + " and " + other);
            starts.add(pip.isReversed() ? pip.getEndNode() : pip.getStartNode());
        }
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        Set<Node> sinkNodes = new HashSet<>();
        for (SitePinInst spi : clk.getPins()) {
            Assertions.assertTrue(spi.isOutPin() || spi.isRouted(), spi.toString());
            sinkNodes.add(spi.getConnectedNode());
        }
        // No dangling routing (e.g. to the vertical distribution of clock region rows without sinks)
        for (Node end : driver.keySet()) {
            Assertions.assertTrue(starts.contains(end) || sinkNodes.contains(end), "Dangling node " + end);
        }
        // A single clock root: one transition from the vertical routing, through clock muxes, onto the vertical
        // distribution
        Map<Node, List<Node>> fanouts = new HashMap<>();
        for (PIP pip : driver.values()) {
            Node start = pip.isReversed() ? pip.getEndNode() : pip.getStartNode();
            fanouts.computeIfAbsent(start, (k) -> new ArrayList<>()).add(pip.isReversed() ? pip.getStartNode() : pip.getEndNode());
        }
        List<Node> roots = new ArrayList<>();
        for (Map.Entry<Node, List<Node>> e : fanouts.entrySet()) {
            if (e.getKey().getIntentCode() != IntentCode.NODE_GLOBAL_VROUTE) {
                continue;
            }
            for (Node n : e.getValue()) {
                Node mux = n;
                while (mux.getIntentCode() == IntentCode.NODE_GLOBAL_GCLK && fanouts.getOrDefault(mux, Collections.emptyList()).size() == 1) {
                    mux = fanouts.get(mux).get(0);
                }
                if (mux.getIntentCode().name().startsWith("NODE_GLOBAL_VDISTR")) {
                    roots.add(n);
                }
            }
        }
        Assertions.assertEquals(1, roots.size(), roots.toString());
    }
  
    /**
     * Clocks driving the clock inputs of hard blocks on xcvc1902 that are not reached from a leaf
     * clock buffer (LCB) of their own clock region: the APB3CLK input of a GTY_QUAD (through a
     * routethru of the neighboring XPIPE_QUAD site, from a clock driven by a BUFG_GT) and the DCLK
     * inputs of CMT_MMCM and CMT_DPLL sites (from the LCBs of the clock region row above them).
     */
    @ParameterizedTest
    @CsvSource({
            "BUFG_GT_X0Y30,GTY_QUAD_SINGLE_X0Y192/GTY_QUAD_SITE_0_APB3CLK_PIN,XPIPE_QUAD",
            "BUFGCE_X4Y0,CMT_MMCM_X23Y0/CMT_MMCM_TOP_0_DCLK_PIN,",
            "BUFGCE_X4Y0,CMT_DPLL_X37Y0/CMT_DPLL_TOP_0_DCLK_PIN,"
    })
    public void testVersalClockToHardBlockClockInput(String sourceSiteName, String sinkNodeName,
                                                     String routeThruTilePrefix) {
        Design design = new Design("versal_hard_block_clk", "xcvc1902-vsva2197-2MP-e-S");
        Device device = design.getDevice();
        Net clk = design.createNet("clk");
        clk.createPin("O", design.createSiteInst(device.getSite(sourceSiteName)));
        SitePin sinkSitePin = device.getNode(sinkNodeName).getSitePin();
        SitePinInst sink = clk.createPin(sinkSitePin.getPinName(), design.createSiteInst(sinkSitePin.getSite()));

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        Assertions.assertTrue(sink.isRouted());
        if (routeThruTilePrefix != null) {
            Assertions.assertTrue(clk.getPIPs().stream().anyMatch((pip) -> pip.isRouteThru()
                    && pip.getTile().getName().startsWith(routeThruTilePrefix)));
        }
    }

    /**
     * Versal clocks whose clock regions do not overlap may share a routing track: the clock regions of
     * all the clocks using a track must be kept, so that a later clock overlapping any of them avoids it.
     */
    @Test
    public void testVersalClocksSharingRoutingTrack() {
        Design design = new Design("versal_shared_track", "xcvc1902-vsva2197-2MP-e-S");
        Device device = design.getDevice();
        Set<Node> used = new HashSet<>();
        Map<Integer, Set<ClockRegion>> usedRoutingTracks = new HashMap<>();
        // Clocks 0 and 1 (in clock regions X1Y1 and X9Y1) can share a track; clock 2 overlaps clock 0
        String[][] sourceAndSinkSites = {
                {"BUFGCE_X1Y0", "SLICE_X44Y62"},
                {"BUFGCE_X9Y0", "SLICE_X332Y79"},
                {"BUFGCE_X1Y1", "SLICE_X46Y64"}
        };
        List<ClockRegion> sinkClockRegions = new ArrayList<>();
        for (int i = 0; i < sourceAndSinkSites.length; i++) {
            Net clk = design.createNet("clk" + i);
            Cell bufgce = design.createAndPlaceCell("bufgce" + i, Unisim.BUFGCE, sourceAndSinkSites[i][0] + "/BUFCE");
            clk.connect(bufgce, "O");
            Cell ff = design.createAndPlaceCell("ff" + i, Unisim.FDRE, sourceAndSinkSites[i][1] + "/AFF");
            clk.connect(ff, "C");
            sinkClockRegions.add(ff.getTile().getClockRegion());

            GlobalSignalRouting.symmetricClkRouting(clk, device,
                    (n) -> used.contains(n) ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE,
                    usedRoutingTracks);
            for (PIP pip : clk.getPIPs()) {
                used.add(pip.getStartNode());
                used.add(pip.getEndNode());
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        }
        Assertions.assertNotEquals(sinkClockRegions.get(0), sinkClockRegions.get(1));
        Assertions.assertEquals(sinkClockRegions.get(0), sinkClockRegions.get(2));

        // Clocks 0 and 1 share a track that keeps both of their clock regions; clock 2 uses another track
        Assertions.assertEquals(2, usedRoutingTracks.size());
        Set<ClockRegion> sharedTrackClockRegions = new HashSet<>(sinkClockRegions.subList(0, 2));
        Assertions.assertTrue(usedRoutingTracks.containsValue(sharedTrackClockRegions), usedRoutingTracks.toString());
        Assertions.assertTrue(usedRoutingTracks.containsValue(new HashSet<>(sinkClockRegions.subList(2, 3))),
                usedRoutingTracks.toString());
    }
  
    @Test
    public void testSymmetricClkRoutingSharedClockBufferMuxes() {
        // The two BUFGCE_HDIOs of a RCLK_HDIO tile reach the clock routing tracks through the same muxes: the
        // second clock must avoid the tracks of the first one
        Design design = new Design("hdio_clks", "xcku9p-ffve900-2-e");
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        List<Net> clks = new ArrayList<>();
        int i = 0;
        for (String bufgce : new String[]{"BUFGCE_HDIO_X0Y2", "BUFGCE_HDIO_X0Y3"}) {
            Net clk = design.createNet("clk" + clks.size());
            clk.connect(design.createAndPlaceCell("bufgce" + clks.size(), Unisim.BUFGCE, bufgce + "/BUFCE"), "O");
            for (int y : new int[]{275, 247, 230, 380}) {
                String slice = "SLICE_X" + (32 + clks.size()) + "Y" + y;
                Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, slice + "/AFF");
                clk.connect(ff, "C");
                vcc.connect(ff, "CE");
                gnd.connect(ff, "D");
                gnd.connect(ff, "R");
            }
            clks.add(clk);
        }
        design.routeSites();

        // Simulate the preserve method
        Map<Node, Net> used = new HashMap<>();
        for (Net clk : clks) {
            GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(),
                    (n) -> used.containsKey(n) && used.get(n) != clk ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE,
                    new HashMap<>());
            for (PIP pip : clk.getPIPs()) {
                for (Node node : Arrays.asList(pip.getStartNode(), pip.getEndNode())) {
                    Net other = used.putIfAbsent(node, clk);
                    Assertions.assertTrue(other == null || other == clk, "Node " + node + " used by " + other + " and " + clk);
                }
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        }
    }

    @Test
    public void testSymmetricClkRoutingBlockedBeyondCentroid() {
        // Routing tracks keep their index: a track that reaches the centroid can still be blocked by another clock
        // further on, on the vertical routing track up to the clock region above the centroid or on the vertical
        // distribution track that the vertical routing tracks transition onto. Block each of these nodes in turn
        // (as another clock would) and check that the clock is routed on another track instead
        Design design = new Design("blocked", "xcku9p-ffve900-2-e");
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        Net clk = design.createNet("clk");
        clk.connect(design.createAndPlaceCell("bufgce", Unisim.BUFGCE, "BUFGCE_HDIO_X0Y2/BUFCE"), "O");
        int i = 0;
        for (int y : new int[]{275, 247, 230, 380}) {
            Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, "SLICE_X32Y" + y + "/AFF");
            clk.connect(ff, "C");
            vcc.connect(ff, "CE");
            gnd.connect(ff, "D");
            gnd.connect(ff, "R");
        }
        design.routeSites();

        GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(), (n) -> NodeStatus.AVAILABLE, new HashMap<>());
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        Set<Node> nodesToBlock = new HashSet<>();
        for (PIP pip : clk.getPIPs()) {
            IntentCode startIntent = pip.getStartNode().getIntentCode();
            IntentCode endIntent = pip.getEndNode().getIntentCode();
            if (endIntent == IntentCode.NODE_GLOBAL_VROUTE ||
                    (startIntent == IntentCode.NODE_GLOBAL_VROUTE && endIntent == IntentCode.NODE_GLOBAL_VDISTR)) {
                nodesToBlock.add(pip.getEndNode());
            }
        }
        Assertions.assertFalse(nodesToBlock.isEmpty());

        for (Node blocked : nodesToBlock) {
            clk.unroute();
            GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(),
                    (n) -> n.equals(blocked) ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE, new HashMap<>());
            for (PIP pip : clk.getPIPs()) {
                Assertions.assertNotEquals(blocked, pip.getStartNode(), pip.toString());
                Assertions.assertNotEquals(blocked, pip.getEndNode(), pip.toString());
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk), "Blocked " + blocked);
        }
    }

    private static Net createClockWithFFs(Design design, String name, String bufgceSite, String... slices) {
        Net clk = design.createNet(name);
        clk.connect(design.createAndPlaceCell(name + "_bufgce", Unisim.BUFGCE, bufgceSite + "/BUFCE"), "O");
        int i = 0;
        for (String slice : slices) {
            Cell ff = design.createAndPlaceCell(name + "_ff" + i++, Unisim.FDRE, slice + "/AFF");
            clk.connect(ff, "C");
            design.getVccNet().connect(ff, "CE");
            design.getGndNet().connect(ff, "D");
            design.getGndNet().connect(ff, "R");
        }
        return clk;
    }

    @Test
    public void testSymmetricClkRoutingBlockedHorizontalDistribution() {
        // Distribution tracks keep the index of the routing track too: a track can be blocked by another clock as
        // late as on the horizontal distribution track of one of the sinks' clock regions. Block each of these
        // nodes in turn (as another clock would) and check that the clock is routed on another track instead
        Design design = new Design("blocked_hdistr", "xcku9p-ffve900-2-e");
        Net clk = createClockWithFFs(design, "clk", "BUFGCE_HDIO_X0Y2",
                "SLICE_X32Y275", "SLICE_X32Y247", "SLICE_X32Y230", "SLICE_X32Y380", "SLICE_X2Y250", "SLICE_X60Y250");
        design.routeSites();

        GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(), (n) -> NodeStatus.AVAILABLE, new HashMap<>());
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        Set<Node> nodesToBlock = new HashSet<>();
        for (PIP pip : clk.getPIPs()) {
            if (pip.getEndNode().getIntentCode() == IntentCode.NODE_GLOBAL_HDISTR) {
                nodesToBlock.add(pip.getEndNode());
            }
        }
        Assertions.assertFalse(nodesToBlock.isEmpty());

        for (Node blocked : nodesToBlock) {
            clk.unroute();
            GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(),
                    (n) -> n.equals(blocked) ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE, new HashMap<>());
            for (PIP pip : clk.getPIPs()) {
                Assertions.assertNotEquals(blocked, pip.getStartNode(), pip.toString());
                Assertions.assertNotEquals(blocked, pip.getEndNode(), pip.toString());
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk), "Blocked " + blocked);
        }
    }

    @Test
    public void testSymmetricClkRoutingDistributionWithinSinkRows() {
        // At the clock root, the row of the root is driven from the vertical distribution track going up from it
        // (as Vivado does) and the one going down is only taken for rows below the root: a clock needs no vertical
        // distribution track reaching the rows above and below those of its sinks, which can be taken by another
        // clock on the same track (e.g. a BUFG_GT, whose track is fixed by its site). Block them all
        Design design = new Design("rows", "xcvu3p-ffvc1517-2-e");
        Net clk = design.createNet("clk");
        clk.connect(design.createAndPlaceCell("bufg_gt", Unisim.BUFG_GT, "BUFG_GT_X0Y70/BUFG_GT"), "O");
        int i = 0;
        for (String slice : new String[]{"SLICE_X0Y90", "SLICE_X0Y150"}) { // in clock regions X0Y1 and X0Y2
            Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, slice + "/AFF");
            clk.connect(ff, "C");
            design.getVccNet().connect(ff, "CE");
            design.getGndNet().connect(ff, "D");
            design.getGndNet().connect(ff, "R");
        }
        design.routeSites();

        Function<Node, NodeStatus> getNodeStatus = (n) -> {
            if (n.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR) {
                for (Wire w : n.getAllWiresInNode()) {
                    ClockRegion cr = w.getTile().getClockRegion();
                    if (cr != null && (cr.getRow() < 1 || cr.getRow() > 2)) {
                        return NodeStatus.UNAVAILABLE;
                    }
                }
            }
            return NodeStatus.AVAILABLE;
        };
        GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(), getNodeStatus, new HashMap<>());
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        for (PIP pip : clk.getPIPs()) {
            Assertions.assertEquals(NodeStatus.AVAILABLE, getNodeStatus.apply(pip.getStartNode()), pip.toString());
            Assertions.assertEquals(NodeStatus.AVAILABLE, getNodeStatus.apply(pip.getEndNode()), pip.toString());
        }
    }

    @Test
    public void testSymmetricClkRoutingUnreachableCentroid() {
        // The routing tracks turn from horizontal to vertical only once and the clock regions in the lower left
        // corner of this device have no clock routing: from a clock buffer in the bottom row, the centroid of
        // sinks in the leftmost column cannot be reached and a clock root next to it is used instead
        Design design = new Design("unreachable_centroid", "xcku9p-ffve900-2-e");
        Net clk = createClockWithFFs(design, "clk", "BUFGCE_HDIO_X0Y0", // in clock region X3Y0
                "SLICE_X10Y200", "SLICE_X10Y260", "SLICE_X10Y320");
        design.routeSites();

        GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(), (n) -> NodeStatus.AVAILABLE, new HashMap<>());
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
    }

    @Test
    public void testSymmetricClkRoutingUnreachableDistributionLine() {
        // Every horizontal distribution node of the clock region row of a sink is used by other nets (the nodes
        // of the neighboring clock regions of the row can reach it too): routing must fail rather than search the
        // clock distribution network, whose wires are linked both ways, indefinitely
        Design design = new Design("unreachable", "xcku9p-ffve900-2-e");
        Net clk = design.createNet("clk");
        clk.connect(design.createAndPlaceCell("bufgce", Unisim.BUFGCE, "BUFGCE_HDIO_X0Y2/BUFCE"), "O");
        int i = 0;
        for (int y : new int[]{275, 247, 230, 380}) {
            clk.connect(design.createAndPlaceCell("ff" + i++, Unisim.FDRE, "SLICE_X32Y" + y + "/AFF"), "C");
        }
        ClockRegion blocked = design.getDevice().getSite("SLICE_X32Y380").getTile().getClockRegion();

        RuntimeException e = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () ->
                Assertions.assertThrows(RuntimeException.class, () ->
                        GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(),
                                (n) -> n.getIntentCode() == IntentCode.NODE_GLOBAL_HDISTR &&
                                        n.getTile().getClockRegion() != null &&
                                        n.getTile().getClockRegion().getRow() == blocked.getRow() ?
                                        NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE,
                                new HashMap<>())));
        Assertions.assertTrue(e.getMessage().startsWith("ERROR: Unable to route clock " + clk), e.getMessage());
    }

    private static Cell createBUFGCE(Design design, EDIFCell parent, String name, Site location) {
        // TODO - An improved version of this method is coming in ArrayBuilder
        Cell bufgce = design.createAndPlaceCell(parent, name, Unisim.BUFGCE, location, location.getBEL("BUFCE"));

        bufgce.addProperty("CE_TYPE", "ASYNC", EDIFValueType.STRING);

        // Ensure a VCC cell source in the current cell
        EDIFTools.getStaticNet(NetType.VCC, parent, design.getNetlist());

        bufgce.getSiteInst().addSitePIP("CEINV", "CE_PREINV");
        bufgce.getSiteInst().addSitePIP("IINV", "I_PREINV");

        BEL ceinv = bufgce.getSite().getBEL("CEINV");
        bufgce.getSiteInst().routeIntraSiteNet(design.getVccNet(), ceinv.getPin("CE"), ceinv.getPin("CE_PREINV"));
        design.getVccNet().addPin(new SitePinInst(false, "CE", bufgce.getSiteInst()));

        // Remove CE:VCC entry for CE:CE
        bufgce.removePinMapping("CE");
        bufgce.addPinMapping("CE", "CE");

        return bufgce;
    }

    private Design genTestDesignForVersalClockRouting() { 
        Design design = new Design("versal_clk", "xcv80-lsva4737-2MHP-e-S");
        EDIFCell top = design.getTopEDIFCell();
        
        Net clk = design.createNet("clk");
        Net clkIn = design.createNet("clkIn");
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        
        clkIn.getLogicalNet().createPortInst(top.createPort(clkIn.getName(), EDIFDirection.INPUT, 1));
        Cell bufgce = createBUFGCE(design, top, "bufgceInst", design.getDevice().getSite("BUFGCE_X2Y0"));
        
        clkIn.connect(bufgce, "I");
        clk.connect(bufgce, "O");
        vcc.connect(bufgce, "CE");
        
        int i=0; 
        for (String loc : new String[] {"SLICE_X98Y8", "SLICE_X86Y96", "SLICE_X86Y236", "SLICE_X86Y284"}) {
            Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, loc + "/AFF");
            clk.connect(ff, "C");
            vcc.connect(ff, "CE");
            gnd.connect(ff, "D");
            gnd.connect(ff, "R");
        }
        
        design.addXDCConstraint("create_clock -period 10.0 [get_ports clk_in]");
        design.routeSites();
        design.setAutoIOBuffers(false);
        design.setDesignOutOfContext(true);
        
        return design;
    }

    @Test
    public void testVersalNocClockRouting() {
        // Test for NOC clock routing compatibility as LCBs might be in neighboring CRs
        Design design = new Design("versal_noc_clk", "xcv80-lsva4737-2MHP-e-S");
        EDIFCell top = design.getTopEDIFCell();

        Net clk = design.createNet("clk");
        Net clkIn = design.createNet("clkIn");
        Net vcc = design.getVccNet();

        clkIn.getLogicalNet().createPortInst(top.createPort(clkIn.getName(), EDIFDirection.INPUT, 1));
        Cell bufgce = createBUFGCE(design, top, "bufgceInst", design.getDevice().getSite("BUFGCE_X2Y0"));
        clkIn.connect(bufgce, "I");
        clk.connect(bufgce, "O");
        vcc.connect(bufgce, "CE");

        Site nocSite = design.getDevice().getSite("NOC_NSU512_X3Y18");
        Cell nsu = design.createAndPlaceCell("nsu", Unisim.NOC_NSU512, nocSite.getName() + "/NSU");
        clk.connect(nsu, "CLK");

        design.routeSites();
        design.setAutoIOBuffers(false);
        design.setDesignOutOfContext(true);

        GlobalSignalRouting.symmetricClkRouting(clk, design.getDevice(),
                (n) -> NodeStatus.AVAILABLE, null);

        SitePinInst nocClkPin = nsu.getSiteInst().getSitePinInst("CLK");
        Assertions.assertNotNull(nocClkPin, "NOC CLK SitePinInst should exist");
        Assertions.assertFalse(clk.getPIPs().isEmpty(), "Clock net should have PIPs after routing");
    }

    @Test
    public void testVersalVDistrClockRouting() {
        Design d = genTestDesignForVersalClockRouting();

        RWRoute.routeDesignFullNonTimingDriven(d);

        // Check for key vertical distribution patterns from template
        boolean foundVDistrLVL2InX3Y2 = false;
        boolean foundVDistrLVL1InX3Y3 = false;
        boolean foundVDistrInX3Y1 = false;
        boolean foundVDistrInX3Y2 = false;
        for (PIP p : d.getNet("clk").getPIPs()) {
            Node n = p.getEndNode();
            if (n != null) {
                if (n.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR_LVL2
                        && n.getTile().getClockRegion().getName().equals("X3Y2")) {
                    foundVDistrLVL2InX3Y2 = true;
                } else if (n.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR_LVL1
                        && n.getTile().getClockRegion().getName().equals("X3Y3")) {
                    foundVDistrLVL1InX3Y3 = true;
                } else if (n.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR
                        && n.getTile().getClockRegion().getName().equals("X3Y1")) {
                    foundVDistrInX3Y1 = true;
                } else if (n.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR
                        && n.getTile().getClockRegion().getName().equals("X3Y2")) {
                    foundVDistrInX3Y2 = true;
                }
            }
        }
        Assertions.assertTrue(foundVDistrLVL2InX3Y2);
        Assertions.assertTrue(foundVDistrLVL1InX3Y3);
        Assertions.assertTrue(foundVDistrInX3Y1);
        Assertions.assertTrue(foundVDistrInX3Y2);

        VivadoToolsHelper.assertFullyRouted(d);
    }

    /**
     * An XPIO output buffer with a constant input: its OP_S site pin is on a dedicated node that is
     * only reached from the fabric through routethrus of the XPIO logic (OPFF D1->Q) and XPHY
     * (D*->O0*) sites, optionally marked by &lt;LOCKED&gt; cells as left by unrouting a routed design.
     */
    @ParameterizedTest
    @CsvSource({"GND,false", "GND,true", "VCC,false", "VCC,true"})
    public void testRouteStaticNetToVersalXpioOutputBuffer(NetType netType, boolean lockedRoutethruCell) {
        Design design = new Design("versal_xpio_const", "xcvc1902-vsva2197-2MP-e-S");
        Cell obuf = design.createAndPlaceCell("obuf", Unisim.OBUF, "IOB_X24Y1/IOB_S");
        Net staticNet = design.getStaticNet(netType);
        SitePinInst opS = staticNet.createPin("OP_S", obuf.getSiteInst());
        Assertions.assertEquals(IntentCode.NODE_DEDICATED, opS.getConnectedNode().getIntentCode());
        if (lockedRoutethruCell) {
            Site xpioLogic = design.getDevice().getSite("XPIOLOGIC_X23Y1");
            SiteInst si = design.createSiteInst(xpioLogic);
            si.addCell(new Cell(Cell.LOCKED, xpioLogic.getBEL("OPFF_SLAVE")));
        }

        List<SitePinInst> staticPins = new ArrayList<>(staticNet.getPins());
        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());
        GlobalSignalRouting.routeStaticNet(staticPins, (n) -> getNodeState(design, netType, n), design, routeThruHelper);

        Assertions.assertTrue(opS.isRouted());
        // GND is driven from a LUT output (a new source pin), VCC from a VCC tie-off of an INT tile (no pin)
        Assertions.assertEquals(netType == NetType.GND ? 1 : 0, staticNet.getPins().stream().filter(SitePinInst::isOutPin).count());
        Set<String> routethrus = new HashSet<>();
        for (PIP pip : staticNet.getPIPs()) {
            if (pip.isRouteThru()) {
                routethrus.add(pip.getStartWireName() + "->" + pip.getEndWireName());
            }
        }
        Assertions.assertTrue(routethrus.contains("XPIO_LOGICPAIR_1_OPFFS_D1_PIN->XPIO_LOGICPAIR_1_OPFFS_Q_PIN"), routethrus.toString());
        Assertions.assertTrue(routethrus.contains("XPHY_CORE_3_D3_0__PIN->XPHY_CORE_3_O0_3__PIN"), routethrus.toString());
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(staticNet));
    }

    /**
     * An UltraScale+ HPIOB output buffer with a constant input: its OP site pin is only reached
     * from the fabric through a TX routethru of the XIPHY BITSLICE_RX_TX site.
     */
    @ParameterizedTest
    @EnumSource(value = NetType.class, names = {"GND", "VCC"})
    public void testRouteStaticNetToUltraScalePlusHpioOutputBuffer(NetType netType) {
        Design design = new Design("usplus_hpio_const", "xcvu3p");
        Cell obuf = design.createAndPlaceCell("obuf", Unisim.OBUF, "IOB_X0Y128/OUTBUF");
        Net staticNet = design.getStaticNet(netType);
        SitePinInst op = staticNet.createPin("OP", obuf.getSiteInst());

        List<SitePinInst> staticPins = new ArrayList<>(staticNet.getPins());
        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());
        GlobalSignalRouting.routeStaticNet(staticPins, (n) -> getNodeState(design, netType, n), design, routeThruHelper);

        Assertions.assertTrue(op.isRouted());
        // GND is driven from a LUT output (a new source pin), VCC from a VCC tie-off of an INT tile (no pin)
        Assertions.assertEquals(netType == NetType.GND ? 1 : 0, staticNet.getPins().stream().filter(SitePinInst::isOutPin).count());
        Set<String> routethrus = new HashSet<>();
        for (PIP pip : staticNet.getPIPs()) {
            if (pip.isRouteThru()) {
                routethrus.add(pip.getStartWire().getSitePin() + "->" + pip.getEndWire().getSitePin());
            }
        }
        Assertions.assertTrue(routethrus.contains("BITSLICE_RX_TX_X0Y128/TX_D0->BITSLICE_RX_TX_X0Y128/TX_Q"), routethrus.toString());
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(staticNet));
    }

    /**
     * The routethrus onto the dedicated nodes that lead to an IO output buffer's input are only used if they are
     * free: with another net on the site wire of the routethru's input pin, or with a cell other than a
     * &lt;LOCKED&gt; placeholder on the BEL that this pin drives, the routethru is not used. These routethrus are
     * the only path from the fabric to the buffer's input, which is therefore left unrouted.
     */
    @ParameterizedTest
    @CsvSource({
            "xcvc1902-vsva2197-2MP-e-S,IOB_X24Y1/IOB_S,OP_S,XPIOLOGIC_X23Y1,OPFFS_D1,true",
            "xcvc1902-vsva2197-2MP-e-S,IOB_X24Y1/IOB_S,OP_S,XPIOLOGIC_X23Y1,OPFFS_D1,false",
            "xcvc1902-vsva2197-2MP-e-S,IOB_X24Y1/IOB_S,OP_S,XPHY_X23Y0,D3_0_,true",
            "xcvc1902-vsva2197-2MP-e-S,IOB_X24Y1/IOB_S,OP_S,XPHY_X23Y0,D3_0_,false",
            "xcvu3p,IOB_X0Y128/OUTBUF,OP,BITSLICE_RX_TX_X0Y128,TX_D0,true",
            "xcvu3p,IOB_X0Y128/OUTBUF,OP,BITSLICE_RX_TX_X0Y128,TX_D0,false",
    })
    public void testRouteStaticNetToIoOutputBufferSkipsUsedRouteThru(String partName, String obufLocation,
                                                                    String obufPinName, String routeThruSiteName,
                                                                    String routeThruInPinName, boolean cellNotNet) {
        Design design = new Design("io_const_used_routethru", partName);
        Cell obuf = design.createAndPlaceCell("obuf", Unisim.OBUF, obufLocation);
        Net gndNet = design.getGndNet();
        SitePinInst obufPin = gndNet.createPin(obufPinName, obuf.getSiteInst());

        Site routeThruSite = design.getDevice().getSite(routeThruSiteName);
        SiteInst si = design.createSiteInst(routeThruSite);
        BELPin routeThruInPin = new SitePin(routeThruSite, routeThruInPinName).getBELPin();
        if (cellNotNet) {
            BEL bel = routeThruInPin.getSiteConns().get(0).getBEL();
            si.addCell(new Cell("user", bel));
        } else {
            si.routeIntraSiteNet(design.createNet("user"), routeThruInPin, routeThruInPin);
        }

        List<SitePinInst> gndPins = new ArrayList<>(gndNet.getPins());
        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());
        GlobalSignalRouting.routeStaticNet(gndPins, (n) -> getNodeState(design, NetType.GND, n), design, routeThruHelper);

        Node routeThruInNode = routeThruSite.getConnectedNode(routeThruInPinName);
        for (PIP pip : gndNet.getPIPs()) {
            Assertions.assertNotEquals(routeThruInNode, pip.getStartNode(), pip.toString());
        }
        Assertions.assertFalse(obufPin.isRouted());
    }

    /**
     * On Versal, a constant reaches an XPIO output buffer input from the fabric through routethrus of IRI_QUAD
     * and BLI sites. These are only used if their site is free: with another net on the site wire of the
     * routethru's input pin, or with a cell on the BEL that this pin drives, the routethru is not used. Each of
     * them is on the input's only path from the fabric, which is therefore left unrouted. The static net itself
     * on that site wire (as left by unrouting it in a routed design) does not block the routethru.
     */
    @ParameterizedTest
    @CsvSource({
            "IRI_QUAD_X28Y6,IMUX_IN8,cell",
            "IRI_QUAD_X28Y6,IMUX_IN8,otherNet",
            "IRI_QUAD_X28Y6,IMUX_IN8,sameNet",
            "BLI_B_GRP2_X24Y0,INT_IMUX_B_24_,cell",
            "BLI_B_GRP2_X24Y0,INT_IMUX_B_24_,otherNet",
            "BLI_B_GRP2_X24Y0,INT_IMUX_B_24_,sameNet",
    })
    public void testRouteStaticNetSkipsUsedVersalIriRouteThru(String routeThruSiteName, String routeThruInPinName,
                                                               String blocker) {
        for (boolean block : new boolean[]{false, true}) {
            Design design = new Design("versal_iri_routethru", "xcvc1902-vsva2197-2MP-e-S");
            Cell obuf = design.createAndPlaceCell("obuf", Unisim.OBUF, "IOB_X24Y1/IOB_S");
            Net gndNet = design.getGndNet();
            SitePinInst opS = gndNet.createPin("OP_S", obuf.getSiteInst());

            Site routeThruSite = design.getDevice().getSite(routeThruSiteName);
            if (block) {
                SiteInst si = design.createSiteInst(routeThruSite);
                BELPin routeThruInPin = new SitePin(routeThruSite, routeThruInPinName).getBELPin();
                if (blocker.equals("cell")) {
                    si.addCell(new Cell("user", routeThruInPin.getSiteConns().get(0).getBEL()));
                } else {
                    Net net = blocker.equals("sameNet") ? gndNet : design.createNet("user");
                    si.routeIntraSiteNet(net, routeThruInPin, routeThruInPin);
                }
            }

            List<SitePinInst> gndPins = new ArrayList<>(gndNet.getPins());
            RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());
            GlobalSignalRouting.routeStaticNet(gndPins, (n) -> getNodeState(design, NetType.GND, n), design, routeThruHelper);

            Node routeThruInNode = routeThruSite.getConnectedNode(routeThruInPinName);
            boolean usesRouteThru = gndNet.getPIPs().stream().anyMatch((pip) -> pip.isRouteThru()
                    && pip.getStartNode().equals(routeThruInNode));
            String routeThrus = gndNet.getPIPs().stream().filter(PIP::isRouteThru).map(PIP::toString)
                    .collect(Collectors.joining(", "));
            boolean blocked = block && !blocker.equals("sameNet");
            Assertions.assertEquals(!blocked, usesRouteThru, "block=" + block + " " + routeThrus);
            Assertions.assertEquals(!blocked, opS.isRouted(), "block=" + block + " " + routeThrus);
            Assertions.assertEquals(blocked ? 1 : 0, DesignTools.updatePinsIsRouted(gndNet));
        }
    }

    /**
     * A routed design's static source pin remains on its SiteInst when RWRoute removes all output
     * pins from the static net before rerouting it (see RWRoute.addStaticNetRoutingTargets()):
     * rerouting must add that same pin back to the net exactly once and mark it routed.
     */
    @Test
    public void testRouteStaticNetReattachesExistingSourcePin() {
        Design design = new Design("static_source", "xcvu3p");
        Device device = design.getDevice();
        Net gndNet = design.getGndNet();
        SitePinInst sink = gndNet.createPin("A1", design.createSiteInst(device.getSite("SLICE_X0Y0")));
        // As in RWRoute, only the static net's sink pins are preserved
        Node sinkNode = sink.getConnectedNode();
        Function<Node, NodeStatus> nodeStatus = (n) -> n.equals(sinkNode) ? NodeStatus.INUSE : NodeStatus.AVAILABLE;
        Function<Net, List<SitePinInst>> outPins = (net) -> net.getPins().stream()
                .filter(SitePinInst::isOutPin).collect(Collectors.toList());
        RouteThruHelper routeThruHelper = new RouteThruHelper(device);

        // Find the LUT output used as the GND source ...
        GlobalSignalRouting.routeStaticNet(new ArrayList<>(gndNet.getSinkPins()), nodeStatus, design, routeThruHelper);
        List<SitePinInst> sources = outPins.apply(gndNet);
        Assertions.assertEquals(1, sources.size());
        Site sourceSite = sources.get(0).getSite();
        String sourcePinName = sources.get(0).getName();
        gndNet.unroute();
        gndNet.removePin(sources.get(0));
        sources.get(0).detachSiteInst();

        // ... and route again with that site used by the design, as in a routed design
        if (design.getSiteInstFromSite(sourceSite) == null) {
            design.createSiteInst(sourceSite);
        }
        GlobalSignalRouting.routeStaticNet(new ArrayList<>(gndNet.getSinkPins()), nodeStatus, design, routeThruHelper);
        SitePinInst source = design.getSiteInstFromSite(sourceSite).getSitePinInst(sourcePinName);
        Assertions.assertNotNull(source);
        Assertions.assertEquals(Arrays.asList(source), outPins.apply(gndNet));

        // RWRoute.addStaticNetRoutingTargets() removes the source pin from the net, but it stays
        // on its SiteInst
        List<SitePinInst> sinks = gndNet.getSinkPins();
        gndNet.unroute();
        gndNet.setPins(sinks);
        Assertions.assertEquals(Arrays.asList(sink), gndNet.getPins());
        Assertions.assertSame(gndNet, source.getNet());
        Assertions.assertFalse(source.isRouted());

        GlobalSignalRouting.routeStaticNet(new ArrayList<>(sinks), nodeStatus, design, routeThruHelper);
        Assertions.assertTrue(sink.isRouted());
        List<SitePinInst> restored = outPins.apply(gndNet);
        Assertions.assertEquals(1, restored.size());
        Assertions.assertSame(source, restored.get(0));
        Assertions.assertSame(source, design.getSiteInstFromSite(sourceSite).getSitePinInst(sourcePinName));
        Assertions.assertTrue(source.isRouted());
    }
  
    /**
     * A BUFGCE driving the input of a second BUFGCE: the cascaded input must be
     * routed through the global clock network rather than from a leaf clock buffer,
     * and the second clock spans the top clock region row, whose vertical
     * distribution must avoid dead-end VDISTR nodes.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testVersalBUFGCECascade(boolean firstClockDrivesFabric) {
        Design design = new Design("versal_bufgce_cascade", "xcvc1902-vsva2197-2MP-e-S");
        EDIFCell top = design.getTopEDIFCell();
        Device device = design.getDevice();
        Net vcc = design.getVccNet();

        Net clkIn = design.createNet("clkIn");
        clkIn.getLogicalNet().createPortInst(top.createPort(clkIn.getName(), EDIFDirection.INPUT, 1));
        Net clk0 = design.createNet("clk0");
        Net clk1 = design.createNet("clk1");
        Cell bufgce0 = createBUFGCE(design, top, "bufgce0", device.getSite("BUFGCE_X0Y23"));
        Cell bufgce1 = createBUFGCE(design, top, "bufgce1", device.getSite("BUFGCE_X4Y0"));
        clkIn.connect(bufgce0, "I");
        clk0.connect(bufgce0, "O");
        vcc.connect(bufgce0, "CE");
        clk0.connect(bufgce1, "I");
        clk1.connect(bufgce1, "O");
        vcc.connect(bufgce1, "CE");

        List<String> clk0Loads = firstClockDrivesFabric ? Arrays.asList("SLICE_X44Y62") : Arrays.asList();
        List<String> clk1Loads = Arrays.asList("SLICE_X66Y68", "SLICE_X332Y79", "SLICE_X334Y185",
                "SLICE_X22Y203", "SLICE_X258Y207", "SLICE_X334Y229", "SLICE_X148Y284");
        int i = 0;
        for (Net clk : Arrays.asList(clk0, clk1)) {
            for (String loc : clk == clk0 ? clk0Loads : clk1Loads) {
                Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, loc + "/AFF");
                clk.connect(ff, "C");
            }
        }
        design.routeSites();

        SitePinInst cascadePin = bufgce1.getSiteInst().getSitePinInst("I");
        Assertions.assertNotNull(cascadePin);
        Assertions.assertTrue(clk0.getPins().contains(cascadePin));

        Set<Node> used = new HashSet<>();
        Map<Integer, Set<ClockRegion>> usedRoutingTracks = new HashMap<>();
        for (Net clk : Arrays.asList(clk0, clk1)) {
            GlobalSignalRouting.symmetricClkRouting(clk, device,
                    (n) -> used.contains(n) ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE,
                    usedRoutingTracks);
            for (PIP pip : clk.getPIPs()) {
                used.add(pip.getStartNode());
                used.add(pip.getEndNode());
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        }

        // The cascaded input is only reached through global clock nodes
        Node cascadeNode = cascadePin.getConnectedNode();
        Map<Node, Node> driver = new HashMap<>();
        for (PIP pip : clk0.getPIPs()) {
            if (pip.isReversed()) {
                driver.put(pip.getStartNode(), pip.getEndNode());
            } else {
                driver.put(pip.getEndNode(), pip.getStartNode());
            }
        }
        Node sourceNode = clk0.getSource().getConnectedNode();
        Node n = driver.get(cascadeNode);
        while (!sourceNode.equals(n)) {
            Assertions.assertNotNull(n);
            Assertions.assertTrue(n.getIntentCode().name().startsWith("NODE_GLOBAL_"), n + " " + n.getIntentCode());
            n = driver.get(n);
        }
    }

    /**
     * Clocks driven by BUFGCTRL and BUFGCE_DIV sites, which share the clock tiles of the BUFGCE sites
     * at the bottom of the device. The BUFGCTRL clocks also drive an input of a neighboring BUFGCTRL,
     * which must be reached straight from the output of the source (a cascade within the clock tile).
     */
    @ParameterizedTest
    @CsvSource({
            "BUFGCTRL_X0Y0,BUFGCTRL_X0Y1,I1",
            "BUFGCTRL_X4Y1,BUFGCTRL_X4Y0,I0",
            "BUFGCE_DIV_X4Y0,,"
    })
    public void testVersalBUFGCTRLAndBUFGCEDIVClockSource(String sourceSiteName, String cascadeSiteName,
                                                          String cascadePinName) {
        Design design = new Design("versal_bufgctrl_clk", "xcvc1902-vsva2197-2MP-e-S");
        Device device = design.getDevice();
        Net clk = design.createNet("clk");
        // A top-level port stands in for the logical driver of the clock
        clk.getLogicalNet().createPortInst(design.getTopEDIFCell().createPort(clk.getName(),
                EDIFDirection.INPUT, 1));
        int i = 0;
        for (String loc : new String[] {"SLICE_X66Y68", "SLICE_X332Y79", "SLICE_X22Y203", "SLICE_X148Y284"}) {
            Cell ff = design.createAndPlaceCell("ff" + i++, Unisim.FDRE, loc + "/AFF");
            clk.connect(ff, "C");
        }
        design.routeSites();
        SitePinInst source = clk.createPin("O", design.createSiteInst(device.getSite(sourceSiteName)));
        SitePinInst cascadePin = null;
        if (cascadeSiteName != null) {
            cascadePin = clk.createPin(cascadePinName, design.createSiteInst(device.getSite(cascadeSiteName)));
        }

        GlobalSignalRouting.symmetricClkRouting(clk, device, (n) -> NodeStatus.AVAILABLE, new HashMap<>());

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(clk));
        Assertions.assertNotNull(clk.getLogicalNet().getProperty("CLOCK_ROOT"));
        if (cascadePin != null) {
            Assertions.assertTrue(cascadePin.isRouted());
            List<Node> outputNodes = source.getConnectedNode().getAllDownhillNodes();
            List<Node> cascadeNodes = cascadePin.getConnectedNode().getAllUphillNodes();
            Assertions.assertEquals(1, outputNodes.size());
            Assertions.assertEquals(1, cascadeNodes.size());
            Node outputNode = outputNodes.get(0);
            Node cascadeNode = cascadeNodes.get(0);
            Assertions.assertTrue(clk.getPIPs().stream().anyMatch((pip) -> pip.getStartNode().equals(outputNode)
                    && pip.getEndNode().equals(cascadeNode)));
        }
    }
}
