/* 
 * Copyright (c) 2021-2022, Xilinx, Inc. 
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestDesignTools {

    private Pair<String,String> inputSiteWire1 = new Pair<>("SLICE_X16Y238","A2"); 
    
    private Pair<String,String> inputSiteWire2 = new Pair<>("SLICE_X13Y237","F5");
    
    private Map<Pair<String,String>,String> mimicInContextInputPortNetSiteRouting(Design design) {
        Map<Pair<String,String>,String> initialState = new HashMap<>();
        
        for(Pair<String,String> siteWire : Arrays.asList(inputSiteWire1, inputSiteWire2)) {
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
        
        for(Entry<Pair<String,String>,String> e : initialSiteRoutes.entrySet()) {
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
        
        for(Pair<String, Boolean> routeThru : routeThrus) {
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

    @Test
    public void testBatchRemoveSitePins() {
        Path dcpPath = RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp");
        Design design = Design.readCheckpoint(dcpPath);

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

        Map<String,Net> netSiteWireMap = si.getNetSiteWireMap();
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
            add("picoblaze_2_25/processor/alu_result_1");
            add("picoblaze_2_25/processor/alu_result_2");
            add("picoblaze_8_43/processor/pc_move_is_valid");
            add("picoblaze_0_43/processor/E[0]");
        }};

        for (Net net : design.getNets()) {
            Collection<SitePinInst> pins = net.getPins();
            if (net.getSource() != null) {
                Assertions.assertTrue(pins.contains(net.getSource()));
            }
            if (net.getAlternateSource() != null) {
                Assertions.assertTrue(pins.contains(net.getAlternateSource()));
            }

            if (dualOutputNets.contains(net.getName())) {
                Assertions.assertTrue(net.getSource() != null && net.getAlternateSource() != null);
            }
        }
    }

    @Test
    public void testBlackBoxCreation() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        String hierCellName = "bd_0_i/hls_inst/inst/dmem_V_U";
        List<EDIFHierCellInst> leafCells = design.getNetlist().getAllLeafDescendants(hierCellName);
        Set<String> placedCellsInBlackBox = new HashSet<>();
        for(EDIFHierCellInst inst : leafCells) {
            String name = inst.getFullHierarchicalInstName();
            Cell cell = design.getCell(name);
            if (cell != null && cell.isPlaced()) {
                placedCellsInBlackBox.add(name);
            }
        }
        Set<String> allOtherPlacedCells = new HashSet<>();
        for(Cell cell : design.getCells()) {
            if (placedCellsInBlackBox.contains(cell.getName())) continue;
            allOtherPlacedCells.add(cell.getName());
        }
        
        DesignTools.makeBlackBox(design, hierCellName);
        Assertions.assertTrue(design.getNetlist().getCellInstFromHierName(hierCellName).isBlackBox());
        for(String cellName : placedCellsInBlackBox) {
            Assertions.assertNull(design.getCell(cellName));
        }
        for(String cellName : allOtherPlacedCells) {
            Assertions.assertNotNull(design.getCell(cellName));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DX", "D_I"})
    public void testGetTrimmablePIPsFromPins(String pinName) {
        Design design = new Design("top", "xcau10p");
        Device device = design.getDevice();
        Net net = createTestNet(design, "net", new String[]{
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

        Net net = createTestNet(design, "net", new String[]{
                "INT_X102Y428/INT.LOGIC_OUTS_W30->>INT_NODE_IMUX_60_INT_OUT1",  // EQ output
                "INT_X102Y428/INT.INT_NODE_IMUX_60_INT_OUT1->>BYPASS_W14",
                "INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14",    // (bidir PIP!)
                "INT_X102Y428/INT.INT_NODE_IMUX_50_INT_OUT0->>BOUNCE_W_13_FT0",
                "INT_X102Y429/INT.BOUNCE_W_BLN_13_FT1->>INT_NODE_IMUX_62_INT_OUT0",
                "INT_X102Y429/INT.INT_NODE_IMUX_62_INT_OUT0->>BYPASS_W5",       // B_I input
                "INT_X102Y428/INT.BYPASS_W14->>INT_NODE_IMUX_49_INT_OUT1",
                "INT_X102Y428/INT.INT_NODE_IMUX_49_INT_OUT1->>BYPASS_W8",       // EX input
                "INT_X102Y428/INT.LOGIC_OUTS_W30->>INODE_W_60_FT0",
                "INT_X102Y429/INT.INODE_W_BLN_60_FT1->>IMUX_W2",                // E1 input
        });

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

    public static Net createTestNet(Design design, String netName, String[] pips) {
        Net net = design.createNet(netName);
        Device device = design.getDevice();
        for (String pip : pips) {
            net.addPIP(device.getPIP(pip));
        }
        return net;
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
        Net net1 = createTestNet(design, "net1", new String[]{
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
        for(SitePinInst pin : net1.getPins()) {
            Assertions.assertFalse(pin.isRouted());
        }

        
        // Net with one output (HMUX) and one input (SRST_B2)
        Net net2 = createTestNet(design, "net2", new String[]{
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
        for(SitePinInst pin : net2.getPins()) {
            Assertions.assertFalse(pin.isRouted());
        }

        net2.removePin(net2.getSource());
        net2.removePin(net2.getPins().get(0));
        design.removeSiteInst(design.getSiteInstFromSiteName("SLICE_X65Y158"));
        design.removeSiteInst(design.getSiteInstFromSiteName("SLICE_X64Y158"));
        
        
        // Net with two outputs (HMUX primary and H_O alternate) and two sinks (SRST_B2 & B2)
        Net net3 = createTestNet(design, "net3", new String[]{
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
        Assertions.assertTrue(net3.getAlternateSource().getName().equals("H_O"));
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
}
