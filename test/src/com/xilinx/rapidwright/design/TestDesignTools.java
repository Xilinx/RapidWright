/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
            
            if(routeThru.getSecond()) {
                Assertions.assertEquals(dstSiteInst.getNetFromSiteWire(siteWireName), 
                                        dstDesign.getVccNet());
            }else {
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
            if(cell != null && cell.isPlaced()) {
                placedCellsInBlackBox.add(name);
            }
        }
        Set<String> allOtherPlacedCells = new HashSet<>();
        for(Cell cell : design.getCells()) {
            if(placedCellsInBlackBox.contains(cell.getName())) continue;
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
        Net net = design.createNet("net");
        String[] pips = new String[]{
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
        };
        for (String pip : pips) {
            net.addPIP(device.getPIP(pip));
        }
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
}
