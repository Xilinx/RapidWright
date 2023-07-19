/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.UtilizationType;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Pair;

public class TestDesignTools {

    private Pair<String,String> inputSiteWire1 = new Pair<>("SLICE_X16Y238","A2");

    private Pair<String,String> inputSiteWire2 = new Pair<>("SLICE_X13Y237","F5");

    private Map<Pair<String,String>,String> mimicInContextInputPortNetSiteRouting(Design design) {
        Map<Pair<String,String>,String> initialState = new HashMap<>();

        for (Pair<String,String> siteWire : Arrays.asList(inputSiteWire1, inputSiteWire2)) {
            SiteInst i = design.getSiteInstFromSiteName(siteWire.getFirst());
            Net net = i.getNetFromSiteWire(siteWire.getSecond());
            initialState.put(siteWire, net.getName());
            BELPin pin = i.getSiteWirePins(siteWire.getSecond())[0];
            i.unrouteIntraSiteNet(pin, pin);
            i.routeIntraSiteNet(design.getVccNet(), pin, pin);
        }

        return initialState;
    }

    @Test
    public void testResolveSiteRoutingFromInContextPorts() {
        String dcpPath = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        Design design = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        // Convert DCP to introduce test scenario
        Map<Pair<String,String>,String> initialSiteRoutes = mimicInContextInputPortNetSiteRouting(design);

        DesignTools.resolveSiteRoutingFromInContextPorts(design);

        for (Entry<Pair<String,String>,String> e : initialSiteRoutes.entrySet()) {
            SiteInst i = design.getSiteInstFromSiteName(e.getKey().getFirst());
            Net net = i.getNetFromSiteWire(e.getKey().getSecond());
            Assertions.assertEquals(net.getName(), e.getValue());
        }
    }

    @Test
    public void testCopyImplementationRouteThruVCCPinCheck() {
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design srcDesign = Design.readCheckpoint(dcpPath);
        Design dstDesign = Design.readCheckpoint(dcpPath, true);
        DesignTools.copyImplementation(srcDesign, dstDesign, "bd_0_i/hls_inst/inst");

        SiteInst srcSiteInst = srcDesign.getSiteInstFromSiteName("SLICE_X73Y155");
        SiteInst dstSiteInst = dstDesign.getSiteInstFromSiteName(srcSiteInst.getSiteName());
        List<Pair<String,Boolean>> routeThrus = new ArrayList<>();
        routeThrus.add(new Pair<>("A6LUT", true)); // It has VCC pin
        routeThrus.add(new Pair<>("B6LUT", false)); // It does not have a VCC pin

        for (Pair<String, Boolean> routeThru : routeThrus) {
            Cell rtCell = dstSiteInst.getCell(routeThru.getFirst());
            Assertions.assertTrue(rtCell.isRoutethru());
            String siteWireName = rtCell.getBEL().getPin("A6").getSiteWireName();

            Assertions.assertEquals(srcSiteInst.getNetFromSiteWire(siteWireName).getName(),
                                    dstSiteInst.getNetFromSiteWire(siteWireName).getName());

            if (routeThru.getSecond()) {
                Assertions.assertEquals(dstSiteInst.getNetFromSiteWire(siteWireName),
                                        dstDesign.getVccNet());
            } else {
                Assertions.assertNotEquals(dstSiteInst.getNetFromSiteWire(siteWireName),
                                        dstDesign.getVccNet());
            }
        }
    }

    private void testCopyImplementationHelper(boolean keepStaticRouting, HashMap<String, Integer> numPIPs) {
        String dcpPath = RapidWrightDCP.getString("testCopyImplementation.dcp");
        String srcCellName = "clock_isolation";

        Design src = Design.readCheckpoint(dcpPath);

        List<EDIFHierCellInst> srcCell = src.getNetlist().findCellInsts("*"+ srcCellName);
        String cellName = srcCell.get(0).getFullHierarchicalInstName();
        EDIFNetlist srcCellNetlist = EDIFTools.createNewNetlist(src.getNetlist().getHierCellInstFromName(cellName).getInst());
        EDIFTools.ensureCorrectPartInEDIF(srcCellNetlist, src.getPartName());
        Design d2 = new Design(srcCellNetlist);
        d2.setAutoIOBuffers(false);
        d2.setDesignOutOfContext(true);

        Map<String, String> cellMap = Collections.singletonMap(cellName, "");
        DesignTools.copyImplementation(src, d2,  keepStaticRouting, true, true, true, cellMap);

        Net vccNet = d2.getVccNet();
        Assertions.assertEquals(numPIPs.get(vccNet.getName()), vccNet.getPIPs().size());
        Net gndNet = d2.getGndNet();
        Assertions.assertEquals(numPIPs.get(gndNet.getName()), gndNet.getPIPs().size());
    }

    @Test
    public void testCopyImplementationWithCopyStaticNets() {
        boolean keepStaticRouting = true;
        HashMap<String, Integer> numPIPs = new HashMap<String, Integer>()
        {{
            put(Net.GND_NET,  201);
            put(Net.VCC_NET,  601);
        }};

        testCopyImplementationHelper(keepStaticRouting, numPIPs);
    }

    @Test
    public void testCopyImplementation() {
        boolean keepStaticRouting = false;
        HashMap<String, Integer> numPIPs = new HashMap<String, Integer>()
        {{
            put(Net.GND_NET,  0);
            put(Net.VCC_NET,  0);
        }};

        testCopyImplementationHelper(keepStaticRouting, numPIPs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"picoblaze_ooc_X10Y235.dcp",
                            "picoblaze_partial.dcp",        // contains a routed clock net, with (many) bidir PIPs
    })
    public void testBatchRemoveSitePins(String path) {
        Design design = RapidWrightDCP.loadDCP(path);

        SiteInst si = design.getSiteInstFromSiteName("SLICE_X14Y238");
        Assertions.assertNotNull(si);

        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();
        for (SitePinInst spi : si.getSitePinInsts()) {
            Net net = spi.getNet();
            Assertions.assertNotNull(net);
            deferredRemovals.computeIfAbsent(net, ($) -> new HashSet<>()).add(spi);
        }

        DesignTools.batchRemoveSitePins(deferredRemovals, true);

        Assertions.assertTrue(si.getSitePinInstMap().isEmpty());

        Map<String, Net> netSiteWireMap = si.getSiteWireToNetMap();
        for (Map.Entry<Net, Set<SitePinInst>> e : deferredRemovals.entrySet()) {
            for (SitePinInst spi : e.getValue()) {
                Assertions.assertFalse(netSiteWireMap.containsKey(spi.getSiteWireName()));
            }
        }
    }

    @Test
    public void testCreateMissingSitePinInstsInPins() {
        String dcpPath = RapidWrightDCP.getString("picoblaze_partial.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        DesignTools.createMissingSitePinInsts(design);

        final Set<String> dualOutputNets = new HashSet<String>() {{
            add("picoblaze_2_25/processor/alu_result_0");
            add("picoblaze_2_25/processor/alu_result_2");
            add("picoblaze_0_43/processor/E[0]");
        }};

        final Set<String> possibleDualOutputNets = new HashSet<String>() {{
            add("picoblaze_2_25/processor/alu_result_1");
            add("picoblaze_8_43/processor/pc_move_is_valid");
        }};

        for (Net net : design.getNets()) {
            Collection<SitePinInst> pins = net.getPins();
            SitePinInst source = net.getSource();
            if (source != null) {
                Assertions.assertTrue(pins.contains(source));
            }
            SitePinInst altSource = net.getAlternateSource();
            if (altSource != null) {
                Assertions.assertTrue(pins.contains(altSource));
            }

            if (dualOutputNets.contains(net.getName())) {
                Assertions.assertNotNull(source);
                Assertions.assertNotNull(altSource);
            } else if (possibleDualOutputNets.contains(net.getName())) {
                Assertions.assertNotNull(source);
                Assertions.assertNull(altSource);
                altSource = DesignTools.getLegalAlternativeOutputPin(net);
                Assertions.assertNotNull(altSource);
                Assertions.assertNotEquals(altSource, source);
            }
        }
    }

    @Test
    public void testCreateMissingSitePinInstsMultiPinMap() {
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        DesignTools.createMissingSitePinInsts(design);

        Net net = design.getNet("bd_0_i/hls_inst/inst/grp_bin_conv_fu_485/grp_process_word_fu_2716/grp_conv_word_fu_523/conv_out_buffer_V_address0[1]");
        // Net connects to logical pins that map onto more than one physical pin
        // (H2 and [^H]2); make sure those non H2 pins are present
        String[] pins = new String[]{
                "SLICE_X81Y218/A2",
                "SLICE_X81Y218/B2",
                "SLICE_X81Y218/C2",
                "SLICE_X81Y218/D2",
                "SLICE_X81Y218/E2",
                "SLICE_X81Y218/F2",
                "SLICE_X81Y218/G2",

                "SLICE_X81Y218/H2"
        };

        Set<SitePinInst> netPins = new HashSet<>(net.getPins());
        for (String pin : pins) {
            String[] split = pin.split("/");
            SiteInst si = design.getSiteInstFromSiteName(split[0]);
            SitePinInst spi = si.getSitePinInst(split[1]);
            Assertions.assertNotNull(spi);
            Assertions.assertTrue(netPins.contains(spi));
        }
    }

    @Test
    public void testCreateMissingSitePinInstsNoConnectedNode() {
        Device device = Device.getDevice("xcvu3p");
        Design design = new Design("testDesign", device.getName());
        Cell cell = design.createAndPlaceCell("cell", Unisim.INBUF, "IOB_X0Y116/INBUF");

        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFPort pi = topCell.createPort("pi", EDIFDirection.INPUT, 1);
        EDIFNet edifNet = topCell.createNet("net");
        edifNet.createPortInst(pi);
        EDIFPortInst pad = edifNet.createPortInst("PAD", cell);

        Net net = design.createNet(edifNet.getName());
        BELPin bp = cell.getBELPin(pad);
        cell.getSiteInst().routeIntraSiteNet(net, bp, bp);
        DesignTools.createMissingSitePinInsts(design, net);

        Assertions.assertTrue(net.getPins().isEmpty());
    }

    @Test
    public void testBlackBoxCreation() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        String hierCellName = "bd_0_i/hls_inst/inst/dmem_V_U";
        List<EDIFHierCellInst> leafCells = design.getNetlist().getAllLeafDescendants(hierCellName);
        Set<String> placedCellsInBlackBox = new HashSet<>();
        for (EDIFHierCellInst inst : leafCells) {
            String name = inst.getFullHierarchicalInstName();
            Cell cell = design.getCell(name);
            if (cell != null && cell.isPlaced()) {
                placedCellsInBlackBox.add(name);
            }
        }
        Set<String> allOtherPlacedCells = new HashSet<>();
        for (Cell cell : design.getCells()) {
            if (placedCellsInBlackBox.contains(cell.getName())) continue;
            allOtherPlacedCells.add(cell.getName());
        }

        DesignTools.makeBlackBox(design, hierCellName);
        Assertions.assertTrue(design.getNetlist().getCellInstFromHierName(hierCellName).isBlackBox());
        for (String cellName : placedCellsInBlackBox) {
            Assertions.assertNull(design.getCell(cellName));
        }
        for (String cellName : allOtherPlacedCells) {
            Assertions.assertNotNull(design.getCell(cellName));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DX", "D_I"})
    public void testGetTrimmablePIPsFromPins(String pinName) {
        Design design = new Design("top", "xcau10p");
        Device device = design.getDevice();
        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                "INT_X24Y92/INT.LOGIC_OUTS_E27->INT_NODE_SDQ_41_INT_OUT1",            // Output pin
                "INT_X24Y92/INT.INT_NODE_SDQ_41_INT_OUT1->>SS1_E_BEG7",
                "INT_X24Y91/INT.SS1_E_END7->>INT_NODE_IMUX_25_INT_OUT1",
                "INT_X24Y91/INT.INT_NODE_IMUX_25_INT_OUT1->>BOUNCE_E_13_FT0",
                "INT_X24Y92/INT.BOUNCE_E_BLN_13_FT1->>INT_NODE_IMUX_30_INT_OUT0",
                "INT_X24Y92/INT.INT_NODE_IMUX_30_INT_OUT0->>BYPASS_E4",
                "INT_X24Y92/INT.BYPASS_E4->>INT_NODE_IMUX_0_INT_OUT0",
                "INT_X24Y92/INT.INT_NODE_IMUX_0_INT_OUT0->>BYPASS_E3",                // DX input pin
                "INT_X24Y92/INT.BYPASS_E3->>INT_NODE_IMUX_12_INT_OUT1",
                "INT_X24Y92/INT.INT_NODE_IMUX_12_INT_OUT1->>BYPASS_E7",               // D_I input pin
        });

        SiteInst si = design.createSiteInst("SLICE_X38Y92");
        net.createPin("DQ2", si);
        net.createPin("D_I", si);
        net.createPin("DX", si);
        SitePinInst pin = si.getSitePinInst(pinName);
        Assertions.assertNotNull(pin);

        Set<PIP> trimmable = DesignTools.getTrimmablePIPsFromPins(net, Arrays.asList(pin));
        if (pinName.equals("DX")) {
            Assertions.assertTrue(trimmable.isEmpty());
        } else if (pinName.equals("D_I")) {
            Assertions.assertEquals(2, trimmable.size());
            Assertions.assertTrue(trimmable.containsAll(Arrays.asList(
                    device.getPIP("INT_X24Y92/INT.BYPASS_E3->>INT_NODE_IMUX_12_INT_OUT1"),
                    device.getPIP("INT_X24Y92/INT.INT_NODE_IMUX_12_INT_OUT1->>BYPASS_E7")
            )));
        } else {
            Assertions.fail();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testGetTrimmablePIPsFromPinsBidir(boolean unrouteAll) {
        Design design = new Design("test", "xcvu19p-fsva3824-1-e");
        Device device = design.getDevice();

        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                "INT_X102Y428/INT.LOGIC_OUTS_W30->>INT_NODE_IMUX_60_INT_OUT1",  // EQ output
                "INT_X102Y428/INT.INT_NODE_IMUX_60_INT_OUT1->>BYPASS_W14",
                "INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14",    // (reversed PIP)
                "INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0->>BOUNCE_W_13_FT0",
                "INT_X102Y429/INT.BOUNCE_W_BLN_13_FT1->>INT_NODE_IMUX_62_INT_OUT0",
                "INT_X102Y429/INT.INT_NODE_IMUX_62_INT_OUT0->>BYPASS_W5",       // B_I input
                "INT_X102Y428/INT.BYPASS_W14->>INT_NODE_IMUX_49_INT_OUT1",
                "INT_X102Y428/INT.INT_NODE_IMUX_49_INT_OUT1->>BYPASS_W8",       // EX input
                "INT_X102Y428/INT.LOGIC_OUTS_W30->>INODE_W_60_FT0",
                "INT_X102Y429/INT.INODE_W_BLN_60_FT1->>IMUX_W2",                // E1 input
        });

        for (PIP pip : net.getPIPs()) {
            if (pip.toString().equals("INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14"))
                pip.setIsReversed(true);
        }

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X196Y428"));
        SitePinInst EQ = net.createPin("EQ", si);
        EQ.setRouted(true);
        SitePinInst EX = net.createPin("EX", si);
        EX.setRouted(true);

        si = design.createSiteInst(design.getDevice().getSite("SLICE_X196Y429"));
        SitePinInst B_I = net.createPin("B_I", si);
        B_I.setRouted(true);
        SitePinInst E1 = net.createPin("E1", si);
        E1.setRouted(true);

        List<SitePinInst> pinsToUnroute = new ArrayList<>(3);
        pinsToUnroute.add(B_I);
        pinsToUnroute.add(E1);
        if (unrouteAll)
            pinsToUnroute.add(EX);
        Set<PIP> trimmable = DesignTools.getTrimmablePIPsFromPins(net, pinsToUnroute);
        if (unrouteAll) {
            Assertions.assertEquals(net.getPIPs().size(), trimmable.size());
        } else {
            Assertions.assertEquals(6, trimmable.size());
            Assertions.assertTrue(trimmable.containsAll(Arrays.asList(
                    device.getPIP("INT_X102Y428/INT.LOGIC_OUTS_W30->>INODE_W_60_FT0"),
                    device.getPIP("INT_X102Y429/INT.INODE_W_BLN_60_FT1->>IMUX_W2"),
                    device.getPIP("INT_X102Y429/INT.INT_NODE_IMUX_62_INT_OUT0->>BYPASS_W5"),
                    device.getPIP("INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14"),
                    device.getPIP("INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0->>BOUNCE_W_13_FT0"),
                    device.getPIP("INT_X102Y429/INT.BOUNCE_W_BLN_13_FT1->>INT_NODE_IMUX_62_INT_OUT0")
            )));
        }
    }

    @Test
    public void testGetTrimmablePIPsFromPinsBidirEndNode() {
        Design design = new Design("test", "xcvu19p-fsva3824-1-e");
        Device device = design.getDevice();

        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                "INT_X126Y235/INT.LOGIC_OUTS_W27->INT_NODE_SDQ_87_INT_OUT0",    // DQ2 output
                "INT_X126Y235/INT.INT_NODE_SDQ_87_INT_OUT0->>EE4_W_BEG6",
                "INT_X128Y235/INT.EE4_W_END6->INT_NODE_SDQ_84_INT_OUT1",
                "INT_X128Y235/INT.INT_NODE_SDQ_84_INT_OUT1->>SS2_W_BEG6",
                "INT_X128Y233/INT.SS2_W_END6->INT_NODE_SDQ_85_INT_OUT0",
                "INT_X128Y233/INT.INT_NODE_SDQ_85_INT_OUT0->>WW2_W_BEG6",
                "INT_X127Y233/INT.WW2_W_END6->INT_NODE_SDQ_82_INT_OUT0",
                "INT_X127Y233/INT.INT_NODE_SDQ_82_INT_OUT0->>NN2_W_BEG5",
                "INT_X127Y235/INT.NN2_W_END5->INT_NODE_SDQ_78_INT_OUT0",
                "INT_X127Y235/INT.INT_NODE_SDQ_78_INT_OUT0->>WW2_W_BEG5",
                "INT_X126Y235/INT.WW2_W_END5->>INT_NODE_IMUX_49_INT_OUT1",
                "INT_X126Y235/INT.INT_NODE_IMUX_49_INT_OUT1->>BYPASS_W8",       // EX input
                "INT_X126Y235/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8",     // (reversed PIP)
                "INT_X126Y235/INT.INT_NODE_IMUX_37_INT_OUT0->>BYPASS_W7"        // D_I input
        });

        for (PIP pip : net.getPIPs()) {
            if (pip.toString().equals("INT_X126Y235/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"))
                pip.setIsReversed(true);
        }

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X242Y235"));
        SitePinInst DQ2 = net.createPin("DQ2", si);
        DQ2.setRouted(true);
        SitePinInst EX = net.createPin("EX", si);
        EX.setRouted(true);
        SitePinInst D_I = net.createPin("D_I", si);
        D_I.setRouted(true);

        List<SitePinInst> pinsToUnroute = new ArrayList<>(3);
        pinsToUnroute.add(D_I);
        Set<PIP> trimmable = DesignTools.getTrimmablePIPsFromPins(net, pinsToUnroute);
        Assertions.assertEquals(2, trimmable.size());
        Assertions.assertTrue(trimmable.containsAll(Arrays.asList(
                device.getPIP("INT_X126Y235/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"),
                device.getPIP("INT_X126Y235/INT.INT_NODE_IMUX_37_INT_OUT0->>BYPASS_W7")
        )));
    }

    @Test
    public void testGetTrimmablePIPsFromPinsBidirSinkNode() {
        Design design = new Design("test", "xcvu19p-fsva3824-1-e");
        Device device = design.getDevice();

        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                "INT_X115Y444/INT.LOGIC_OUTS_W30->INT_NODE_SDQ_91_INT_OUT1",                    // EQ
                "INT_X115Y444/INT.INT_NODE_SDQ_91_INT_OUT1->>INT_INT_SDQ_7_INT_OUT0",
                "INT_X115Y444/INT.INT_INT_SDQ_7_INT_OUT0->>INT_NODE_GLOBAL_10_INT_OUT0",
                "INT_X115Y444/INT.INT_NODE_GLOBAL_10_INT_OUT0->>INT_NODE_IMUX_59_INT_OUT1",
                "INT_X115Y444/INT.INT_NODE_IMUX_59_INT_OUT1->>BOUNCE_W_13_FT0",                 // F_I
                "INT_X115Y444/INT.LOGIC_OUTS_W30->INT_NODE_SDQ_91_INT_OUT0",
                "INT_X115Y444/INT.INT_NODE_SDQ_91_INT_OUT0->>EE2_W_BEG7",
                "INT_X116Y444/INT.EE2_W_END7->INT_NODE_SDQ_88_INT_OUT0",
                "INT_X116Y444/INT.INT_NODE_SDQ_88_INT_OUT0->>WW1_W_BEG6",
                "INT_X115Y444/INT.WW1_W_END6->INT_NODE_SDQ_38_INT_OUT1",
                "INT_X115Y444/INT.INT_NODE_SDQ_38_INT_OUT1->>INT_INT_SDQ_75_INT_OUT0",
                "INT_X115Y444/INT.INT_INT_SDQ_75_INT_OUT0->>INT_NODE_GLOBAL_9_INT_OUT0",
                "INT_X115Y444/INT.INT_NODE_GLOBAL_9_INT_OUT0->>INT_NODE_IMUX_37_INT_OUT0",
                "INT_X115Y444/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"                      // EX
        });

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X220Y444"));
        SitePinInst DQ2 = net.createPin("EQ", si);
        DQ2.setRouted(true);
        SitePinInst F_I = net.createPin("F_I", si);
        F_I.setRouted(true);
        SitePinInst EX = net.createPin("EX", si);
        EX.setRouted(true);

        List<SitePinInst> pinsToUnroute = new ArrayList<>(3);
        pinsToUnroute.add(EX);
        Set<PIP> trimmable = DesignTools.getTrimmablePIPsFromPins(net, pinsToUnroute);
        Assertions.assertEquals(9, trimmable.size());
        Assertions.assertTrue(trimmable.containsAll(Arrays.asList(
                device.getPIP("INT_X115Y444/INT.LOGIC_OUTS_W30->INT_NODE_SDQ_91_INT_OUT0"),
                device.getPIP("INT_X115Y444/INT.INT_NODE_SDQ_91_INT_OUT0->>EE2_W_BEG7"),
                device.getPIP("INT_X116Y444/INT.EE2_W_END7->INT_NODE_SDQ_88_INT_OUT0"),
                device.getPIP("INT_X116Y444/INT.INT_NODE_SDQ_88_INT_OUT0->>WW1_W_BEG6"),
                device.getPIP("INT_X115Y444/INT.WW1_W_END6->INT_NODE_SDQ_38_INT_OUT1"),
                device.getPIP("INT_X115Y444/INT.INT_NODE_SDQ_38_INT_OUT1->>INT_INT_SDQ_75_INT_OUT0"),
                device.getPIP("INT_X115Y444/INT.INT_INT_SDQ_75_INT_OUT0->>INT_NODE_GLOBAL_9_INT_OUT0"),
                device.getPIP("INT_X115Y444/INT.INT_NODE_GLOBAL_9_INT_OUT0->>INT_NODE_IMUX_37_INT_OUT0"),
                device.getPIP("INT_X115Y444/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8")
        )));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetTrimmablePIPsFromPinsBidirBounceNode(boolean createBounceSink) {
        Design design = new Design("test", "xcvu19p-fsva3824-1-e");
        Device device = design.getDevice();

        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                "INT_X196Y535/INT.LOGIC_OUTS_E10->INT_NODE_SDQ_12_INT_OUT1",                    // DQ
                "INT_X196Y535/INT.INT_NODE_SDQ_12_INT_OUT1->>INT_INT_SDQ_73_INT_OUT0",
                "INT_X196Y535/INT.INT_INT_SDQ_73_INT_OUT0->>INT_NODE_GLOBAL_1_INT_OUT1",
                "INT_X196Y535/INT.INT_NODE_GLOBAL_1_INT_OUT1->>INT_NODE_IMUX_5_INT_OUT0",
                "INT_X196Y535/INT.INT_NODE_IMUX_5_INT_OUT0<<->>BYPASS_E8",                      // bounce (EX)
                "INT_X196Y535/INT.BYPASS_E8->>INT_NODE_IMUX_4_INT_OUT1",
                "INT_X196Y535/INT.INT_NODE_IMUX_4_INT_OUT1->>BYPASS_E3",                        // DX
                "INT_X196Y535/INT.INT_NODE_IMUX_5_INT_OUT0->>BYPASS_E7",                        // D_I
        });

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X376Y535"));
        SitePinInst DQ = net.createPin("DQ", si);
        DQ.setRouted(true);
        SitePinInst DX = net.createPin("DX", si);
        DX.setRouted(true);
        SitePinInst D_I = net.createPin("D_I", si);
        D_I.setRouted(true);
        if (createBounceSink) {
            SitePinInst EX = net.createPin("EX", si);
            EX.setRouted(true);
        }

        List<SitePinInst> pinsToUnroute = new ArrayList<>();
        pinsToUnroute.add(DX);
        Set<PIP> trimmable = DesignTools.getTrimmablePIPsFromPins(net, pinsToUnroute);
        if (createBounceSink) {
            Assertions.assertEquals(2, trimmable.size());
            Assertions.assertTrue(trimmable.containsAll(Arrays.asList(
                    device.getPIP("INT_X196Y535/INT.BYPASS_E8->>INT_NODE_IMUX_4_INT_OUT1"),
                    device.getPIP("INT_X196Y535/INT.INT_NODE_IMUX_4_INT_OUT1->>BYPASS_E3")
            )));
        } else {
            Assertions.assertEquals(3, trimmable.size());
            Assertions.assertTrue(trimmable.containsAll(Arrays.asList(
                    device.getPIP("INT_X196Y535/INT.INT_NODE_IMUX_5_INT_OUT0<<->>BYPASS_E8"),
                    device.getPIP("INT_X196Y535/INT.BYPASS_E8->>INT_NODE_IMUX_4_INT_OUT1"),
                    device.getPIP("INT_X196Y535/INT.INT_NODE_IMUX_4_INT_OUT1->>BYPASS_E3")
            )));
        }
    }

    @Test
    public void testUnrouteSourcePinBidir() {
        Design design = new Design("test", "xcvu19p-fsva3824-1-e");

        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                "INT_X193Y606/INT.LOGIC_OUTS_W27->INT_NODE_SDQ_87_INT_OUT0",
                "INT_X193Y606/INT.INT_NODE_SDQ_87_INT_OUT0->>NN1_W_BEG6",
                "INT_X193Y607/INT.NN1_W_END6->INT_NODE_SDQ_83_INT_OUT0",
                "INT_X193Y607/INT.INT_NODE_SDQ_83_INT_OUT0->>WW1_W_BEG5",
                "INT_X192Y607/INT.WW1_W_END5->INT_NODE_SDQ_34_INT_OUT0",
                "INT_X192Y607/INT.INT_NODE_SDQ_34_INT_OUT0->>EE1_E_BEG5",
                "INT_X193Y607/INT.EE1_E_END5->INT_NODE_SDQ_79_INT_OUT0",
                "INT_X193Y607/INT.INT_NODE_SDQ_79_INT_OUT0->>SS1_W_BEG5",
                "INT_X193Y606/INT.SS1_W_END5->>INT_NODE_IMUX_49_INT_OUT1",
                "INT_X193Y606/INT.INT_NODE_IMUX_49_INT_OUT1->>BYPASS_W8",
                "INT_X193Y606/INT.BYPASS_W8->>INT_NODE_IMUX_36_INT_OUT1",
                "INT_X193Y606/INT.INT_NODE_IMUX_36_INT_OUT1->>BYPASS_W3",       // DX
                "INT_X193Y606/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8",     // (reversed PIP)
                "INT_X193Y606/INT.INT_NODE_IMUX_37_INT_OUT0->>BYPASS_W7",       // D_I
        });

        for (PIP pip : net.getPIPs()) {
            if (pip.toString().equals("INT_X193Y606/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"))
                pip.setIsReversed(true);
        }

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X369Y606"));
        SitePinInst DQ2 = net.createPin("DQ2", si);
        DQ2.setRouted(true);
        SitePinInst DX = net.createPin("DX", si);
        DX.setRouted(true);
        SitePinInst D_I = net.createPin("D_I", si);
        D_I.setRouted(true);

        DesignTools.unrouteSourcePin(DQ2);
        Assertions.assertTrue(net.getPIPs().isEmpty());
    }

    private void removeSourcePinHelper(boolean useUnroutePins, SitePinInst spi, int expectedPIPs) {
        if (useUnroutePins) {
            DesignTools.unroutePins(spi.getNet(), Arrays.asList(spi));
        } else {
            Assertions.assertEquals(expectedPIPs, DesignTools.unrouteSourcePin(spi).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRemoveSourcePin(boolean useUnroutePins) {
        Design design = new Design("test", Device.KCU105);

        // Net with one source (AQ2) and two sinks (A_I & FX) and a stub (INT_NODE_IMUX_71_INT_OUT)
        Net net1 = TestDesignHelper.createTestNet(design, "net1", new String[]{
                // Translocated from example in
                // https://github.com/Xilinx/RapidWright/pull/475#issuecomment-1188337848
                "INT_X63Y21/INT.LOGIC_OUTS_E12->>INT_NODE_SINGLE_DOUBLE_76_INT_OUT",
                "INT_X63Y21/INT.INT_NODE_SINGLE_DOUBLE_76_INT_OUT->>SS2_E_BEG3",
                "INT_X63Y19/INT.SS2_E_END3->>INT_NODE_IMUX_71_INT_OUT",
                "INT_X63Y19/INT.SS2_E_END3->>INT_NODE_SINGLE_DOUBLE_109_INT_OUT",
                "INT_X63Y19/INT.INT_NODE_SINGLE_DOUBLE_109_INT_OUT->>WW2_E_BEG4",
                "INT_X62Y19/INT.WW2_E_END4->>INT_NODE_SINGLE_DOUBLE_47_INT_OUT",
                "INT_X62Y19/INT.INT_NODE_SINGLE_DOUBLE_47_INT_OUT->>NN2_E_BEG4",
                "INT_X62Y21/INT.NN2_E_END4->>INT_NODE_SINGLE_DOUBLE_1_INT_OUT",
                "INT_X62Y21/INT.INT_NODE_SINGLE_DOUBLE_1_INT_OUT->>EE2_E_BEG5",
                "INT_X63Y21/INT.EE2_E_END5->>INT_NODE_IMUX_16_INT_OUT",
                "INT_X63Y21/INT.INT_NODE_IMUX_16_INT_OUT->>BOUNCE_E_14_FTN",
                "INT_X63Y21/INT.INT_NODE_IMUX_16_INT_OUT->>BYPASS_E13"
        });

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X97Y21"));
        net1.createPin("AQ2", si).setRouted(true);
        net1.createPin("A_I", si).setRouted(true);
        net1.createPin("FX", si).setRouted(true);

        removeSourcePinHelper(useUnroutePins, net1.getSource(), 12);
        Assertions.assertEquals(0, net1.getPIPs().size());
        for (SitePinInst pin : net1.getPins()) {
            Assertions.assertFalse(pin.isRouted());
        }


        // Net with one output (HMUX) and one input (SRST_B2)
        Net net2 = TestDesignHelper.createTestNet(design, "net2", new String[]{
            "INT_X42Y158/INT.LOGIC_OUTS_E16->>INT_NODE_SINGLE_DOUBLE_46_INT_OUT",
            "INT_X42Y158/INT.INT_NODE_SINGLE_DOUBLE_46_INT_OUT->>INT_INT_SINGLE_51_INT_OUT",
            "INT_X42Y158/INT.INT_INT_SINGLE_51_INT_OUT->>INT_NODE_GLOBAL_3_OUT1",
            "INT_X42Y158/INT.INT_NODE_GLOBAL_3_OUT1->>CTRL_W_B7"
        });

        si = design.createSiteInst(design.getDevice().getSite("SLICE_X65Y158"));
        net2.createPin("HMUX", si).setRouted(true);
        si = design.createSiteInst(design.getDevice().getSite("SLICE_X64Y158"));
        net2.createPin("SRST_B2", si).setRouted(true);

        removeSourcePinHelper(useUnroutePins, net2.getSource(), 4);
        Assertions.assertEquals(0, net2.getPIPs().size());
        for (SitePinInst pin : net2.getPins()) {
            Assertions.assertFalse(pin.isRouted());
        }

        net2.removePin(net2.getSource());
        net2.removePin(net2.getPins().get(0));
        design.removeSiteInst(design.getSiteInstFromSiteName("SLICE_X65Y158"));
        design.removeSiteInst(design.getSiteInstFromSiteName("SLICE_X64Y158"));


        // Net with two outputs (HMUX primary and H_O alternate) and two sinks (SRST_B2 & B2)
        Net net3 = TestDesignHelper.createTestNet(design, "net3", new String[]{
            // SLICE_X65Y158/HMUX-> SLICE_X64Y158/SRST_B2
            "INT_X42Y158/INT.LOGIC_OUTS_E16->>INT_NODE_SINGLE_DOUBLE_46_INT_OUT",
            "INT_X42Y158/INT.INT_NODE_SINGLE_DOUBLE_46_INT_OUT->>INT_INT_SINGLE_51_INT_OUT",
            "INT_X42Y158/INT.INT_INT_SINGLE_51_INT_OUT->>INT_NODE_GLOBAL_3_OUT1",
            "INT_X42Y158/INT.INT_NODE_GLOBAL_3_OUT1->>CTRL_W_B7",
            // Adding dual output net
            // SLICE_X65Y158/H_O-> SLICE_X64Y158/B2
            "INT_X42Y158/INT.LOGIC_OUTS_E29->>INT_NODE_QUAD_LONG_5_INT_OUT",
            "INT_X42Y158/INT.INT_NODE_QUAD_LONG_5_INT_OUT->>NN16_BEG3",
            "INT_X42Y174/INT.NN16_END3->>INT_NODE_QUAD_LONG_53_INT_OUT",
            "INT_X42Y174/INT.INT_NODE_QUAD_LONG_53_INT_OUT->>WW4_BEG14",
            "INT_X40Y174/INT.WW4_END14->>INT_NODE_QUAD_LONG_117_INT_OUT",
            "INT_X40Y174/INT.INT_NODE_QUAD_LONG_117_INT_OUT->>SS16_BEG3",
            "INT_X40Y158/INT.SS16_END3->>INT_NODE_QUAD_LONG_84_INT_OUT",
            "INT_X40Y158/INT.INT_NODE_QUAD_LONG_84_INT_OUT->>EE4_BEG12",
            "INT_X42Y158/INT.EE4_END12->>INT_NODE_GLOBAL_8_OUT1",
            "INT_X42Y158/INT.INT_NODE_GLOBAL_8_OUT1->>INT_NODE_IMUX_61_INT_OUT",
            "INT_X42Y158/INT.INT_NODE_IMUX_61_INT_OUT->>IMUX_W0",
        });

        si = design.createSiteInst(design.getDevice().getSite("SLICE_X65Y158"));
        SitePinInst src = net3.createPin("HMUX", si);
        src.setRouted(true);
        SitePinInst altSrc = net3.createPin("H_O", si);
        altSrc.setRouted(true);
        Assertions.assertNotNull(net3.getAlternateSource());
        Assertions.assertEquals("H_O", net3.getAlternateSource().getName());
        si = design.createSiteInst(design.getDevice().getSite("SLICE_X64Y158"));
        SitePinInst snk = net3.createPin("SRST_B2", si);
        snk.setRouted(true);
        SitePinInst altSnk = net3.createPin("B2", si);
        altSnk.setRouted(true);

        // Unroute just the H_O alternate source
        removeSourcePinHelper(useUnroutePins, net3.getAlternateSource(), 11);
        Assertions.assertEquals(4, net3.getPIPs().size());
        Assertions.assertTrue(src.isRouted());
        Assertions.assertFalse(altSrc.isRouted());
        Assertions.assertTrue(snk.isRouted());
        Assertions.assertFalse(altSnk.isRouted());
    }

    @ParameterizedTest
    @CsvSource({
            "true,false",
            "false,true",
            "true,true",
    })
    void testCreateA1A6ToStaticNetsFracturedLUT(boolean createLUT6, boolean createLUT5) {
        Design design = new Design("test", Device.KCU105);

        if (createLUT6) {
            design.createAndPlaceCell("lut6", Unisim.LUT6, "SLICE_X0Y0/A6LUT");
        }
        if (createLUT5) {
            design.createAndPlaceCell("lut5", Unisim.LUT5, "SLICE_X0Y0/A5LUT");
        }

        DesignTools.createA1A6ToStaticNets(design);

        if (createLUT5) {
            Assertions.assertEquals("[IN SLICE_X0Y0.A6]", design.getVccNet().getPins().toString());
        } else {
            Assertions.assertTrue(design.getVccNet().getPins().isEmpty());
        }
    }

    @Test
    public void testCreateA1A6ToStaticNetsVcc() {
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        DesignTools.createA1A6ToStaticNets(design);

        String[] pins = new String[]{
                // 5LUT used as a static source
                "SLICE_X79Y169/A6",
                "SLICE_X73Y164/A6",
                "SLICE_X82Y161/A6",
                "SLICE_X79Y159/A6",
                "SLICE_X76Y156/A6",
                "SLICE_X73Y155/A6",
                "SLICE_X83Y153/A6",
                "SLICE_X77Y150/A6",
                "SLICE_X79Y145/A6",
                "SLICE_X78Y145/A6",

                // Tied to VCC because RAMS32
                "SLICE_X87Y203/H6",
                "SLICE_X87Y202/H6"
        };

        Set<SitePinInst> vccPins = new HashSet<>(design.getVccNet().getPins());
        for (String pin : pins) {
            String[] split = pin.split("/");
            SiteInst si = design.getSiteInstFromSiteName(split[0]);
            SitePinInst spi = si.getSitePinInst(split[1]);
            Assertions.assertNotNull(spi);
            Assertions.assertTrue(vccPins.contains(spi));
        }
    }

    @Test
    public void testCreateA1A6ToStaticNetsGnd() {
        String dcpPath = RapidWrightDCP.getString("optical-flow.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        DesignTools.createA1A6ToStaticNets(design);

        String[] pins = new String[]{
                // SRLC32E transformed to SRL16E
                "SLICE_X68Y164/A6",
                "SLICE_X68Y164/D6",
                "SLICE_X68Y163/A6",
                "SLICE_X68Y163/D6",
                "SLICE_X68Y162/A6",
                "SLICE_X68Y162/D6",
                "SLICE_X68Y161/A6",
                "SLICE_X68Y161/D6",
                "SLICE_X68Y160/A6",
                "SLICE_X68Y160/D6",
        };

        Set<SitePinInst> gndPins = new HashSet<>(design.getGndNet().getPins());
        for (String pin : pins) {
            String[] split = pin.split("/");
            SiteInst si = design.getSiteInstFromSiteName(split[0]);
            SitePinInst spi = si.getSitePinInst(split[1]);
            Assertions.assertNotNull(spi);
            Assertions.assertTrue(gndPins.contains(spi));
        }
    }

    @Test
    public void testResolveNetNameFromSiteWireWithoutNetlist() {
        Design design = new Design(); // This constructor does not create a netlist
        design.setPartName(Device.KCU105);

        Site site = design.getDevice().getSite("SLICE_X0Y0");
        SiteInst si = design.createSiteInst(site);
        Assertions.assertNull(DesignTools.resolveNetNameFromSiteWire(si, site.getSiteWireIndex("A1")));

        Net net = new Net("net");
        BELPin bp = si.getBELPin("A1", "A1");
        si.routeIntraSiteNet(net, bp, bp);
        Assertions.assertEquals("net", DesignTools.resolveNetNameFromSiteWire(si, site.getSiteWireIndex("A1")));
    }

    @Test
    public void testIsNetDrivenByHierPort() {
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design design = Design.readCheckpoint(dcpPath);

        // These nets contain [A-H](X|_I) sink pins which must be identified
        // by any router lest their corresponding nodes are claimed by other
        // nets (nonHierPortNets below!)
        String[] hierPortNets = new String[]{
                "dmem_mode_V[0]",
                "n_inputs_V[13]",
                "n_inputs_V[1]",
                "n_inputs_V[3]",
                "n_inputs_V[5]",
                "n_inputs_V[7]",
                "n_inputs_V[9]",
        };

        for (String name : hierPortNets) {
            Net net = design.getNet(name);
            Assertions.assertNotNull(net);
            Assertions.assertTrue(DesignTools.isNetDrivenByHierPort(net));
        }

        String[] nonHierPortNets = new String[]{
                "bd_0_i/hls_inst/inst/ap_CS_fsm_state12",
                "bd_0_i/hls_inst/inst/grp_bin_conv_fu_485/zext_ln180_41_fu_4196_p1[3]",
                "bd_0_i/hls_inst/inst/p_0882_0_reg_394_reg[2]",
                "bd_0_i/hls_inst/inst/p_0882_0_reg_394_reg[4]",
                "bd_0_i/hls_inst/inst/zext_ln544_12_cast_fu_1208_p4[6]",
                "bd_0_i/hls_inst/inst/zext_ln879_1_reg_1396",
        };

        for (String name : nonHierPortNets) {
            Net net = design.getNet(name);
            Assertions.assertNotNull(net);
            Assertions.assertFalse(DesignTools.isNetDrivenByHierPort(net));
        }
    }

    @Test
    public void testGetPortInstsFromSitePinInstLutRoutethru() {
        Device device = Device.getDevice("xcvu3p");
        Design design = new Design("design", device.getName());

        Cell ff1 = design.createAndPlaceCell("ff1", Unisim.FDRE, "SLICE_X0Y0/AFF");
        Cell ff2 = design.createAndPlaceCell("ff2", Unisim.FDRE, "SLICE_X0Y0/AFF2");
        SiteInst si = ff1.getSiteInst();
        Net net = design.createNet("net");
        SitePinInst spi = net.createPin("A6", si);
        new EDIFPortInst(ff1.getEDIFCellInst().getPort("D"), null, ff1.getEDIFCellInst());
        new EDIFPortInst(ff2.getEDIFCellInst().getPort("D"), null, ff2.getEDIFCellInst());

        // Routethru LUT to reach both flops
        Assertions.assertTrue(si.routeIntraSiteNet(net, spi.getBELPin(), ff1.getBEL().getPin("D")));
        Assertions.assertTrue(si.routeIntraSiteNet(net, spi.getBELPin(), ff2.getBEL().getPin("D")));

        Assertions.assertEquals("[ff1/D, ff2/D]",
                DesignTools.getPortInstsFromSitePinInst(spi).toString());
    }

    @Test
    public void testCalculateUtilization() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");

        for (Entry<UtilizationType, Integer> e : DesignTools.calculateUtilization(design).entrySet()) {
            switch (e.getKey()) {
            case CLB_LUTS:
                Assertions.assertEquals(3097, e.getValue());
                break;
            case CLB_REGS:
                Assertions.assertEquals(2754, e.getValue());
                break;
            case CARRY8S:
                Assertions.assertEquals(113, e.getValue());
                break;
            case LUTS_AS_LOGIC:
                Assertions.assertEquals(3055, e.getValue());
                break;
            case LUTS_AS_MEMORY:
                Assertions.assertEquals(42, e.getValue());
                break;
            case DSPS:
                Assertions.assertEquals(4, e.getValue());
                break;
            default:
            }
        }

        PBlock pblock = new PBlock(design.getDevice(), "SLICE_X78Y145:SLICE_X80Y149 DSP48E2_X9Y58:DSP48E2_X9Y59");
        for (Entry<UtilizationType, Integer> e : DesignTools.calculateUtilization(design, pblock).entrySet()) {
            switch (e.getKey()) {
            case CLB_LUTS:
                Assertions.assertEquals(13, e.getValue());
                break;
            case CLB_REGS:
                Assertions.assertEquals(30, e.getValue());
                break;
            case CARRY8S:
                Assertions.assertEquals(4, e.getValue());
                break;
            case LUTS_AS_LOGIC:
                Assertions.assertEquals(13, e.getValue());
                break;
            case LUTS_AS_MEMORY:
                Assertions.assertEquals(0, e.getValue());
                break;
            case DSPS:
                Assertions.assertEquals(1, e.getValue());
                break;
            default:
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            // US+
            Device.AWS_F1+",SLICE_X0Y0/AFF,SRST1,true",
            Device.AWS_F1+",SLICE_X0Y0/AFF2,SRST1,false",
            Device.AWS_F1+",SLICE_X1Y1/HFF,SRST2,true",
            Device.AWS_F1+",SLICE_X1Y1/HFF2,SRST2,false",
            // US
            Device.KCU105+",SLICE_X0Y0/AFF,SRST_B1,true",
            Device.KCU105+",SLICE_X0Y0/AFF2,SRST_B1,false",
            Device.KCU105+",SLICE_X1Y1/HFF,SRST_B2,true",
            Device.KCU105+",SLICE_X1Y1/HFF2,SRST_B2,false",
            // Series7
            Device.PYNQ_Z1+",SLICE_X0Y0/AFF,SR,true",
            Device.PYNQ_Z1+",SLICE_X0Y0/A5FF,SR,false",
            Device.PYNQ_Z1+",SLICE_X1Y1/DFF,SR,true",
            Device.PYNQ_Z1+",SLICE_X1Y1/D5FF,SR,false",
    })
    public void testCreateCeSrRstPinsToVCC(String deviceName, String location, String sitePinName, boolean connectGnd) {
        Design design = new Design("test", deviceName);
        Cell c = design.createAndPlaceCell("ff", Unisim.FDRE, location);
        BELPin sr = c.getBEL().getPin("SR");
        SiteInst si = c.getSiteInst();
        Assertions.assertNull(si.getNetFromSiteWire(sr.getSiteWireName()));
        if (connectGnd) {
            Net gnd = design.getGndNet();
            Assertions.assertTrue(si.routeIntraSiteNet(gnd, sr, sr));
        }

        DesignTools.createCeSrRstPinsToVCC(design);

        SitePinInst spi = si.getSitePinInst(sitePinName);
        if (design.getDevice().getSeries() == Series.Series7) {
            // Nothing done for Series7
            Assertions.assertNull(spi);
        } else {
            Assertions.assertNotNull(spi);
            Net vcc = design.getVccNet();
            Assertions.assertEquals(vcc, spi.getNet());
        }
    }

    @Test
    public void testMakePhysNetNamesConsistentLogicalVccGnd() {
        Design design = RapidWrightDCP.loadDCP("bug701.dcp");

        // Design has no GLOBAL_LOGIC{0,1}
        Assertions.assertNull(design.getNet(Net.VCC_NET));
        Assertions.assertNull(design.getNet(Net.GND_NET));

        DesignTools.makePhysNetNamesConsistent(design);

        // Check those nets were created and all sitewires
        // were switched over correctly
        Net vcc = design.getNet(Net.VCC_NET);
        Assertions.assertNotNull(vcc);
        Assertions.assertEquals(1, vcc.getSiteInsts().size());
        int numSitewires = 0;
        for (SiteInst si : vcc.getSiteInsts()) {
            numSitewires += si.getSiteWiresFromNet(vcc).size();
        }
        Assertions.assertEquals(1, numSitewires);

        Net gnd = design.getNet(Net.GND_NET);
        Assertions.assertNotNull(gnd);
        Assertions.assertEquals(4, gnd.getSiteInsts().size());
        numSitewires = 0;
        for (SiteInst si : gnd.getSiteInsts()) {
            numSitewires += si.getSiteWiresFromNet(gnd).size();
        }
        Assertions.assertEquals(31, numSitewires);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMakePhysNetNamesConsistentStaticNets(boolean gnd) {
        Design design = new Design("design", Device.AWS_F1);
        Net staticNet = gnd ? design.getGndNet() : design.getVccNet();

        Net anotherStaticNet = design.createNet("anotherStaticNet");
        anotherStaticNet.setType(staticNet.getType());

        Cell cell = design.createAndPlaceCell("cell", Unisim.LUT1, "SLICE_X0Y0/A6LUT");
        SitePinInst spi = anotherStaticNet.connect(cell, "I0");

        Assertions.assertEquals(0, staticNet.getPins().size());
        Assertions.assertEquals(Arrays.asList(spi), anotherStaticNet.getPins());

        DesignTools.makePhysNetNamesConsistent(design);

        Assertions.assertNull(design.getNet(anotherStaticNet.getName()));
        Assertions.assertEquals(Arrays.asList(spi), staticNet.getPins());
    }

    @Test
    public void testPlaceCell() {
        //test a design that already contains a Carry4 cell
        Design d0 = RapidWrightDCP.loadDCP("bug709.dcp");
        //test a blank design
        Design d1 = new Design("blankDesign", d0.getPartName());

        Design designs[] = {d0, d1};

        for(Design d : designs) {
            // Test placing a cell created from a Unisim
            Cell c0 = d.createCell("cell0", Unisim.CARRY4);
            // Test placing a cell created from a EDIFCELL reference
            EDIFCell ec = Design.getUnisimCell(Unisim.CARRY4);
            Cell c1 = d.createCell("cell1", ec);

            Cell cells[] = {c0, c1};

            for(Cell c : cells) {
                DesignTools.placeCell(c, d);
                Assertions.assertFalse(c.getPinMappingsP2L().isEmpty());
                Assertions.assertNotNull(c.getBEL());
                Assertions.assertNotNull(c.getSiteInst());
            }
        }
    }

    @Test
    public void testUnrouteCellPinSiteRouting() {
        Design design = new Design("test", Device.KCU105);

        // Test internal routing removal - no sitepips
        Cell lut0 = design.createAndPlaceCell("lut0", Unisim.LUT5, "SLICE_X0Y0/C6LUT");
        Cell f7mux0 = design.createAndPlaceCell("f7mux0", Unisim.MUXF7, "SLICE_X0Y0/F7MUX_CD");
        SiteInst si0 = lut0.getSiteInst();
        Net net0 = design.createNet("O");
        net0.connect(lut0, "O");
        net0.getLogicalNet().createPortInst("I1", f7mux0);
        si0.routeIntraSiteNet(net0, lut0.getBEL().getPin("O6"), f7mux0.getBEL().getPin("1"));
        Assertions.assertEquals(net0, si0.getNetFromSiteWire("C_O"));

        DesignTools.unrouteCellPinSiteRouting(lut0, "O");
        Assertions.assertNull(si0.getNetFromSiteWire("C_O"));

        DesignTools.unrouteCellPinSiteRouting(f7mux0, "I1");
        Assertions.assertNull(si0.getNetFromSiteWire("C_O"));

        // Test internal routing removal - with sitepips
        Cell lut1 = design.createAndPlaceCell("lut1", Unisim.LUT6, "SLICE_X0Y1/B6LUT");
        Cell ff1 = design.createAndPlaceCell("ff1", Unisim.FDRE, "SLICE_X0Y1/BFF");
        SiteInst si1 = lut1.getSiteInst();
        Net net1 = design.createNet("O1");
        net1.connect(lut1, "O");
        net1.connect(ff1, "D");
        si1.routeIntraSiteNet(net1, lut1.getBEL().getPin("O6"), ff1.getBEL().getPin("D"));
        Assertions.assertEquals(net1, si1.getNetFromSiteWire("B_O"));
        Assertions.assertEquals(net1, si1.getNetFromSiteWire("FFMUXB1_OUT1"));
        Assertions.assertEquals("D6", si1.getUsedSitePIP("FFMUXB1").getInputPinName());

        DesignTools.unrouteCellPinSiteRouting(lut1, "O");
        Assertions.assertNull(si1.getNetFromSiteWire("B_O"));
        Assertions.assertNull(si1.getNetFromSiteWire("FFMUXB1_OUT1"));
        Assertions.assertNull(si1.getUsedSitePIP("FFMUXB1"));

        DesignTools.unrouteCellPinSiteRouting(f7mux0, "I1");
        Assertions.assertNull(si1.getNetFromSiteWire("B_O"));
        Assertions.assertNull(si1.getNetFromSiteWire("FFMUXB2_OUT2"));
        Assertions.assertNull(si1.getUsedSitePIP("FFMUXB2"));

        // Test internal routing removal - routethru
        Cell carry2 = design.createAndPlaceCell("carry2", Unisim.CARRY8, "SLICE_X0Y2/CARRY8");
        SiteInst si2 = carry2.getSiteInst();
        Net net2 = design.createNet("some_source");
        net2.getLogicalNet().createPortInst("DI[0]", carry2);
        si2.routeIntraSiteNet(net2, si2.getBELPin("A2", "A2"), carry2.getBEL().getPin("DI0"));
        net2.createPin("A2", si2);

        Assertions.assertTrue(si2.getCell("A5LUT").isRoutethru());
        Assertions.assertEquals(net2, si2.getNetFromSiteWire("A2"));
        Assertions.assertEquals(net2, si2.getNetFromSiteWire("A5LUT_O5"));

        DesignTools.unrouteCellPinSiteRouting(carry2, "DI[0]");

        Assertions.assertNull(si2.getCell("A5LUT"));
        Assertions.assertNull(si2.getNetFromSiteWire("A2"));
        Assertions.assertNull(si2.getNetFromSiteWire("A5LUT_O5"));

        // Test internal routing removal - routethru with fanout
        Cell carry3 = design.createAndPlaceCell("carry3", Unisim.CARRY8, "SLICE_X0Y3/CARRY8");
        Cell lut3 = design.createAndPlaceCell("lut3", Unisim.LUT6, "SLICE_X0Y3/A6LUT");
        lut3.addPinMapping("A2", "I2");
        SiteInst si3 = carry3.getSiteInst();
        Net net3 = design.createNet("some_source2");
        net3.getLogicalNet().createPortInst("DI[0]", carry3);
        si3.routeIntraSiteNet(net3, si3.getBELPin("A2", "A2"), carry3.getBEL().getPin("DI0"));
        net3.connect(lut3, "I2");

        Assertions.assertEquals(net3, si3.getSitePinInst("A2").getNet());
        Assertions.assertTrue(si3.getCell("A5LUT").isRoutethru());
        Assertions.assertEquals(net3, si3.getNetFromSiteWire("A2"));
        Assertions.assertEquals(net3, si3.getNetFromSiteWire("A5LUT_O5"));

        DesignTools.unrouteCellPinSiteRouting(carry3, "DI[0]");

        Assertions.assertNull(si3.getCell("A5LUT"));
        Assertions.assertEquals(net3, si3.getNetFromSiteWire("A2"));
        Assertions.assertNull(si3.getNetFromSiteWire("A5LUT_O5"));

        // Test internal routing removal - fanout
        Cell carry4 = design.createAndPlaceCell("carry4", Unisim.CARRY8, "SLICE_X0Y4/CARRY8");
        Cell lut4 = design.createAndPlaceCell("lut4", Unisim.LUT6, "SLICE_X0Y4/B6LUT");
        Cell ff4 = design.createAndPlaceCell("ff4", Unisim.FDRE, "SLICE_X0Y4/BFF");
        SiteInst si4 = lut4.getSiteInst();
        Net net4 = design.createNet("O4");
        net4.connect(lut4, "O");
        net4.connect(ff4, "D");
        carry4.addPinMapping("S1", "S[1]");
        net4.getLogicalNet().createPortInst("S[1]", carry4);

        si4.routeIntraSiteNet(net4, lut4.getBEL().getPin("O6"), ff4.getBEL().getPin("D"));

        Assertions.assertEquals(net4, si4.getNetFromSiteWire("B_O"));
        Assertions.assertEquals(net4, si4.getNetFromSiteWire("FFMUXB1_OUT1"));
        Assertions.assertEquals("D6", si4.getUsedSitePIP("FFMUXB1").getInputPinName());

        DesignTools.unrouteCellPinSiteRouting(ff4, "D");

        Assertions.assertNull(si4.getNetFromSiteWire("FFMUXB1_OUT1"));
        Assertions.assertNull(si4.getUsedSitePIP("FFMUXB1"));
        Assertions.assertEquals(net4, si4.getNetFromSiteWire("B_O"));

        DesignTools.unrouteCellPinSiteRouting(carry4, "S[1]");

        Assertions.assertNull(si4.getNetFromSiteWire("FFMUXB1_OUT1"));
        Assertions.assertNull(si4.getUsedSitePIP("FFMUXB1"));
        Assertions.assertNull(si4.getNetFromSiteWire("B_O"));
    }
}
