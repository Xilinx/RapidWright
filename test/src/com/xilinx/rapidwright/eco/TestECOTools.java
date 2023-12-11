/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.eco;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.rwroute.TestRWRoute;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TestECOTools {
    @Test
    public void testDisconnectNet() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        // *** Internally routed net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/parity_muxcy_CARRY4_CARRY8/S[1]");
            EDIFPortInst epi = ehpi.getPortInst();
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(epi));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(epi));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals(0, deferredRemovals.size());
        }
        deferredRemovals.clear();


        // *** Internally routed net (output pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/read_strobe_lut/LUT6/O");
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals(0, deferredRemovals.size());
        }
        deferredRemovals.clear();

        // *** Externally routed 2-pin net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/t_state1_flop/D");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X13Y237.E_I]", deferredRemovals.get(net).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed 2-pin net (output pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("your_program/ram_4096x8/DOUTBDOUT[3]");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN RAMB36_X1Y47.DIBU1, OUT RAMB36_X1Y47.DOBU1]",
                    deferredRemovals.get(net).stream().map(Object::toString).sorted().collect(Collectors.toList()).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed many-pin net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/stack_loop[4].upper_stack.stack_pointer_lut/I0");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X13Y238.E1]", deferredRemovals.get(net).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed many-pin net (output pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/alu_mux_sel0_flop/Q");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X15Y235.G6, IN SLICE_X15Y235.H2, IN SLICE_X15Y237.G5, IN SLICE_X15Y239.H5, IN SLICE_X16Y235.F6, IN SLICE_X16Y235.G4, IN SLICE_X16Y238.D4, IN SLICE_X16Y239.B6, OUT SLICE_X16Y239.EQ]",
                    deferredRemovals.get(net).stream().map(Object::toString).sorted().collect(Collectors.toList()).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed global net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/address_loop[10].output_data.pc_vector_mux_lut/I0");
            Net net = design.getGndNet();
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X13Y237.G1]", deferredRemovals.get(net).toString());
        }
        deferredRemovals.clear();
    }

    @Test
    public void testConnectNetSwapSinks() {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        DesignTools.updatePinsIsRouted(design);

        // Disconnect the ILA inputs
        List<EDIFHierPortInst> disconnectPins = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("u_ila_0/probe0[" + i + "]");
            Assertions.assertNotNull(ehpi);
            Assertions.assertTrue(ehpi.isInput());
            disconnectPins.add(ehpi);
        }
        ECOTools.disconnectNet(design, disconnectPins, deferredRemovals);
        Assertions.assertEquals(14, deferredRemovals.size());

        // Re-connect those inputs to some other nets
        final Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        for (int i = 0; i < 14; i++) {
            int busIdx = (74 + i);
            EDIFHierNet ehn = netlist.getHierNetFromName("base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Data_Addr[0][" + busIdx + "]");
            EDIFHierPortInst ehpi = disconnectPins.get(i);

            // Check that leaves of net and pin are disjoint
            List<EDIFHierPortInst> ehpiLeaves = ehpi.getInternalNet().getLeafHierPortInsts(false, true);
            Assertions.assertFalse(ehn.getLeafHierPortInsts(false, true).stream().anyMatch(ehpiLeaves::contains));

            netToPortInsts.put(ehn, new ArrayList(){{ add(ehpi); }});
        }
        ECOTools.connectNet(design, netToPortInsts, deferredRemovals);
        Assertions.assertEquals(0, deferredRemovals.size());

        // Check that leaves of net and pin are one and the same now
        List<SitePinInst> unroutedPins = new ArrayList<>();
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : netToPortInsts.entrySet()) {
            EDIFHierNet ehn = e.getKey();
            List<EDIFHierPortInst> ehnLeaves = ehn.getLeafHierPortInsts(false, true);
            for (EDIFHierPortInst ehpi : e.getValue()) {
                List<EDIFHierPortInst> ehpiLeaves = ehpi.getInternalNet().getLeafHierPortInsts(false, true);
                Assertions.assertEquals(ehnLeaves.size(), ehpiLeaves.size());
                Assertions.assertTrue(ehnLeaves.containsAll(ehpiLeaves));
            }

            EDIFHierNet parentEhn = netlist.getParentNet(ehn);
            Net parentNet = design.getNet(parentEhn.getHierarchicalNetName());
            for (SitePinInst spi : parentNet.getPins()) {
                if (!spi.isOutPin() && !spi.isRouted()) {
                    unroutedPins.add(spi);
                }
            }
        }

        Assertions.assertEquals("[IN SLICE_X51Y84.G_I, IN SLICE_X49Y84.EX, IN SLICE_X49Y87.EX, IN SLICE_X51Y84.H_I, IN SLICE_X49Y86.FX, IN SLICE_X49Y86.E_I, IN SLICE_X49Y88.EX, IN SLICE_X50Y82.EX, IN SLICE_X49Y86.EX, IN SLICE_X49Y84.F_I, IN SLICE_X49Y85.EX, IN SLICE_X50Y84.EX, IN SLICE_X49Y84.FX, IN SLICE_X49Y84.E_I]",
                unroutedPins.toString());

        if (FileTools.isVivadoOnPath()) {
            // Check that Vivado shows 14 unrouted nets
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(14, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(14, rrs.netsWithSomeUnroutedPins);
        }
    }

    @Test
    public void testConnectNetSwapSource() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        DesignTools.updatePinsIsRouted(design);

        // Disconnect the outputs
        List<EDIFHierPortInst> disconnectPins = new ArrayList<>();
        List<EDIFHierNet> disconnectedNets = new ArrayList<>();
        List<Set<String>> sourceSitePinInsts = new ArrayList<>();
        Map<Net, Set<SitePinInst>> sinkSitePinInsts = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/data_path_loop[" + i + "].alu_mux_lut/O");
            EDIFHierNet ehn = ehpi.getHierarchicalNet();
            disconnectedNets.add(ehn);
            Net net = design.getNet(ehn.getHierarchicalNetName());
            Set<String> sourcePins = new HashSet<>();
            Set<SitePinInst> sinkPins = new HashSet<>();
            for (SitePinInst spi : net.getPins()) {
                Assertions.assertTrue(spi.isRouted());
                if (!spi.isOutPin()) {
                    sinkPins.add(spi);
                } else {
                    sourcePins.add(spi.getSitePinName());
                }
            }
            sourceSitePinInsts.add(sourcePins);
            sinkSitePinInsts.put(net, sinkPins);

            Assertions.assertNotNull(ehpi);
            Assertions.assertTrue(ehpi.isOutput());
            disconnectPins.add(ehpi);
        }
        ECOTools.disconnectNet(design, disconnectPins, deferredRemovals);
        Assertions.assertEquals(2, deferredRemovals.size());

        // Swap those output pins
        Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        netToPortInsts.put(disconnectedNets.get(0), new ArrayList() {{ add(disconnectPins.get(1)); }});
        netToPortInsts.put(disconnectedNets.get(1), new ArrayList() {{ add(disconnectPins.get(0)); }});

        ECOTools.connectNet(design, netToPortInsts, deferredRemovals);
        Assertions.assertEquals(0, deferredRemovals.size());

        List<Net> physNets = new ArrayList<>();
        for (EDIFHierNet ehn : disconnectedNets) {
            Net net = design.getNet(ehn.getHierarchicalNetName());
            physNets.add(net);
            Assertions.assertFalse(net.hasPIPs());
            Set<SitePinInst> sinkPins = sinkSitePinInsts.get(net);
            for (SitePinInst spi : net.getPins()) {
                Assertions.assertFalse(spi.isRouted());
                // Check sink SPIs are not swapped
                Assertions.assertTrue(spi.isOutPin() || sinkPins.contains(spi));
            }
        }

        // Check source SPI is swapped
        Assertions.assertTrue(sourceSitePinInsts.get(0).contains(physNets.get(1).getSource().getSitePinName()));
        Assertions.assertTrue(sourceSitePinInsts.get(1).contains(physNets.get(0).getSource().getSitePinName()));

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(2, rrs.unroutedNets);
        }
    }

    @Test
    @Disabled("Currently, ECOTools.removeCell() does not work for hierarchical cells. Specifically, for this testcase " +
            "exclusively intra-site routes (e.g. 'processor/data_path_loop[4].small_spm.small_spm_ram.spm_ram/DOA') " +
            "are not removed and appear in the DCP causing Vivado to emit 'placement information for XX sites failed to" +
            "restore' warnings and cells (e.g. 'your_program/ram_4096x8') to be unplaced.")
    public void testRemoveCell() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        EDIFHierCellInst ehciToDelete = netlist.getHierCellInstFromName("processor");
        List<EDIFHierCellInst> leavesToDelete = netlist.getAllLeafDescendants(ehciToDelete);

        ECOTools.removeCell(design, Collections.singletonList(ehciToDelete), deferredRemovals);

        for (EDIFHierCellInst ehci : leavesToDelete) {
            String instName = ehci.getFullHierarchicalInstName();
            // Logical leaf cell not present
            Assertions.assertNull(netlist.getHierCellInstFromName(instName));
            // Physical cell not present
            Assertions.assertNull(design.getCell(instName));
        }

        // Logical hierarchical cell not present
        Assertions.assertNull(netlist.getHierCellInstFromName(ehciToDelete.getFullHierarchicalInstName()));

        DesignTools.batchRemoveSitePins(deferredRemovals, true);

        design.writeCheckpoint("/group/zircon2/eddieh/pb.dcp");

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0 /* TODO */, rrs.netsWithRoutingErrors);
        }
    }

    @Test
    public void testRemoveCellLeaf() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        EDIFHierCellInst ehciToDelete = netlist.getHierCellInstFromName("your_program/ram_4096x8");
        Assertions.assertTrue(ehciToDelete.getCellType().isLeafCellOrBlackBox());

        ECOTools.removeCell(design, Collections.singletonList(ehciToDelete), deferredRemovals);

        String instName = ehciToDelete.getFullHierarchicalInstName();

        // Logical leaf cell not present
        Assertions.assertNull(netlist.getHierCellInstFromName(instName));
        // Physical cell not present
        Assertions.assertNull(design.getCell(instName));

        DesignTools.batchRemoveSitePins(deferredRemovals, true);

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(8, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(8, rrs.netsWithNoDriver);
            Assertions.assertEquals(8, rrs.netsWithSomeUnroutedPins);
        }
    }

    @Test
    public void testCreateCell() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        DesignTools.updatePinsIsRouted(design);

        EDIFCell reference = netlist.getCell("kcpsm6");
        List<String> instNames = Arrays.asList("processor2", "processor3");
        ECOTools.createCell(design, reference, instNames);

        List<EDIFHierCellInst> goldenLeaves = netlist.getAllLeafDescendants("processor");

        for (String instName : instNames) {
            // Logical hierarchical cell is present
            EDIFHierCellInst ehci = netlist.getHierCellInstFromName(instName);
            Assertions.assertNotNull(ehci);

            // Physical leaf cells are present and unplaced
            List<EDIFHierCellInst> leaves = netlist.getAllLeafDescendants(ehci);
            Assertions.assertEquals(goldenLeaves.size(), leaves.size());
            for (EDIFHierCellInst leaf : leaves) {
                String cellName = leaf.getCellName();
                if (cellName.equals("VCC") || cellName.equals("GND")) {
                    continue;
                }
                String leafName = leaf.getFullHierarchicalInstName();
                Cell leafCell = design.getCell(leafName);
                Assertions.assertNotNull(leafCell);
                Assertions.assertFalse(leafCell.isPlaced());
            }
        }

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(1135, rrs.logicalNets);
            Assertions.assertEquals(728, rrs.netsWithNoPlacedPins);
            Assertions.assertEquals(2, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(2, rrs.netsWithSomeUnplacedPins);
        }
    }

    @Test
    public void testCreateAndPlaceInlineCellOnInputPin() {
        Design d = new Design("Test", Device.KCU105);

        Cell and2 = d.createAndPlaceCell("and2", Unisim.AND2, "SLICE_X100Y100/A6LUT");
        Cell button0 = d.createAndPlaceIOB("button0", PinType.IN, "AE10", "LVCMOS18");
        Cell button1 = d.createAndPlaceIOB("button1", PinType.IN, "AF9", "LVCMOS18");
        Cell led0 = d.createAndPlaceIOB("led0", PinType.OUT, "AP8", "LVCMOS18");

        // Connect Button 0 to the LUT2 input I0
        EDIFHierCellInst hierButton0 = button0.getEDIFHierCellInst().getParent();
        Net net0 = d.createNet(new EDIFHierNet(hierButton0, hierButton0.getCellType().getNet("O")));
        ECOTools.connectNet(d, and2, "I0", net0);

        // Connect Button 1 to the LUT2 input I1
        EDIFHierCellInst hierButton1 = button1.getEDIFHierCellInst().getParent();
        Net net1 = d.createNet(new EDIFHierNet(hierButton1, hierButton1.getCellType().getNet("O")));
        ECOTools.connectNet(d, and2, "I1", net1);

        // Connect the LUT2 (AND2) to the LED IO
        Net net2 = d.createNet("and2");
        net2.connect(and2, "O");
        net2.connect(led0, "I");

        // Route site internal nets
        d.routeSites();

        // Insert a LUT1 in between 'and2.I0' and its source, 'button0.O'
        EDIFHierPortInst input = and2.getEDIFHierCellInst().getPortInst("I0");
        Site site = d.getDevice().getSite("SLICE_X100Y101");
        BEL bel = site.getBEL("A6LUT");
        Unisim lut1Type = Unisim.LUT1;
        ECOTools.createAndPlaceInlineCellOnInputPin(d, input, lut1Type, site, bel, "I0", "O");

        // Route nets between sites
        new Router(d).routeDesign();

        Cell lut1 = d.getSiteInstFromSite(site).getCell(bel);
        Assertions.assertNotNull(lut1);
        Assertions.assertEquals(lut1Type.name(), lut1.getEDIFHierCellInst().getCellType().getName());
        Assertions.assertEquals(net0, lut1.getSitePinFromLogicalPin("I0", null).getNet());
        Assertions.assertNotEquals(net0, lut1.getSitePinFromLogicalPin("O", null).getNet());

        TestRWRoute.assertVivadoFullyRouted(d);
    }

    @Test
    public void testCreateNet() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        DesignTools.updatePinsIsRouted(design);

        List<String> netNames = Arrays.asList("processor/foo", "your_program/bar");
        ECOTools.createNet(design, netNames);

        for (String netName : netNames) {
            // Logical net is present
            EDIFHierNet ehn = netlist.getHierNetFromName(netName);
            Assertions.assertNotNull(ehn);

            // Physical nets are also present
            Assertions.assertNotNull(design.getNet(netName));
        }
    }
}
