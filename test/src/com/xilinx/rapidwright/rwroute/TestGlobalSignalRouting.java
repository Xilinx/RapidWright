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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
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
