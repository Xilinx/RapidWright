/*
 * Copyright (c) 2023-2025, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.rwroute.PartialRouter;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.CodeGenerator;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

        // *** Externally routed many-pin net (input pin of LUT6_2 macro)
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

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(design));

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

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(design));

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

    /**
     * Generated with CodeGenerator.genCodeForTestSite()
     * From https://github.com/Xilinx/RapidWright/discussions/1198#discussioncomment-13335689
     */
    private Design genDiscussion1198TestCase() {
        Design design = new Design("test", "xc7a100tftg256-2");
        Device device = design.getDevice();
        SiteInst si = design.createSiteInst("SLICE_X1Y97", SiteTypeEnum.SLICEL,
                device.getSite("SLICE_X1Y97"));
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();
        Cell cell0 = CodeGenerator.genCell(si, "cell0", false, "LUT1", "C5LUT", "A1:null", "A2:I0",
                "A3:null", "A4:null", "A5:null", "O5:O");
        cell0.addProperty("INIT", "2'h2", EDIFValueType.STRING);
        Cell cell1 = CodeGenerator.genCell(si, "cell1", false, "LUT2", "C6LUT", "A1:null", "A2:I0",
                "A3:null", "A4:null", "A5:null", "A6:I1", "O6:O");
        cell1.addProperty("INIT", "2'h1", EDIFValueType.STRING);
        Cell cell2 = CodeGenerator.genCell(si, "cell2", false, "LUT1", "A6LUT", "A1:null", "A2:null",
                "A3:null", "A4:null", "A5:null", "A6:I0", "O6:O");
        cell2.addProperty("INIT", "2'h1", EDIFValueType.STRING);

        CodeGenerator.addSitePIPs(si, "CUSED:0", "AUSED:0", "COUTMUX:O5");

        Net net3 = design.createNet("net3");
        net3.getLogicalNet().createPortInst("O", cell1);
        top.getNet("net3").createPortInst(top.createPort("port4", EDIFDirection.OUTPUT, 1));
        net3.createPin("C", si);
        CodeGenerator.routeSiteNet(si, net3, "C6LUT_O6", "C");
        Net usedNet = design.createNet(Net.USED_NET);
        CodeGenerator.routeSiteNet(si, usedNet, "CARRY4_CO0", "CARRY4_CO1", "CARRY4_CO2",
                "CARRY4_CO3", "CARRY4_O0", "CARRY4_O1", "CARRY4_O2", "CARRY4_O3", "F7BMUX_OUT",
                "F8MUX_OUT", "CARRY4_DMUX_OUT", "CARRY4_CMUX_OUT", "CARRY4_CXOR_O", "CARRY4_DXOR_O");
        Net vcc = design.getVccNet();
        vcc.createPin("C6", si);
        CodeGenerator.routeSiteNet(si, vcc, "C6");
        Net net5 = design.createNet("net5");
        net5.getLogicalNet().createPortInst("I0", cell0);
        net5.getLogicalNet().createPortInst("I0", cell2);
        net5.getLogicalNet().createPortInst("I0", cell1);
        top.getNet("net5").createPortInst(top.createPort("port6", EDIFDirection.INPUT, 1));
        net5.createPin("C2", si);
        net5.createPin("A6", si);
        CodeGenerator.routeSiteNet(si, net5, "A6", "C2");
        Net net7 = design.createNet("net7");
        net7.getLogicalNet().createPortInst("O", cell0);
        top.getNet("net7").createPortInst(top.createPort("port8", EDIFDirection.OUTPUT, 1));
        net7.createPin("CMUX", si);
        CodeGenerator.routeSiteNet(si, net7, "C5LUT_O5", "CMUX");
        Net net9 = design.createNet("net9");
        net9.getLogicalNet().createPortInst("O", cell2);
        top.getNet("net9").createPortInst(top.createPort("port10", EDIFDirection.OUTPUT, 1));
        net9.createPin("A", si);
        CodeGenerator.routeSiteNet(si, net9, "A6LUT_O6", "A");
        design.setDesignOutOfContext(true);
        design.setAutoIOBuffers(false);
        return design;
    }
    
    @Test 
    public void testConnectNetFailsWithoutDisconnect() { 
        Design design = genDiscussion1198TestCase();
        Cell testCell = design.getCell("cell1");
        Net testNet = design.getNet("net7");
        
        // Ensure error is thrown if pin is still connected to a net
        Assertions.assertThrows(RuntimeException.class, () -> {ECOTools.connectNet(design, testCell, "I0", testNet);},
                "ERROR: Pin cell1/I0 already connected to net net5 please run ECOTools.disconnectNet() first.");
    }
    
    @Test
    public void testConnectNetSwitchLUTInput() {
        Design design = genDiscussion1198TestCase();
        Cell cell1 = design.getCell("cell1");
        Net origNet = design.getNet("net5");
        Net targetNet = design.getNet("net7");

        // Both cell0.I0 and cell1.I0 are connected to the physical A2 pin
        // Disconnect and connect the cell1.I0 pin to a new net
        String pin = "I0";
        Assertions.assertEquals("A2", cell1.getPhysicalPinMapping(pin));
        Cell cell0 = LUTTools.getCompanionLUTCell(cell1);
        Assertions.assertNotNull(cell0);
        Assertions.assertEquals("cell0", cell0.getName());
        Assertions.assertEquals("A2", cell0.getPhysicalPinMapping(pin));

        EDIFHierPortInst ehpi = cell1.getEDIFHierCellInst().getPortInst(pin);
        SitePinInst spiA2 = cell1.getSitePinFromPortInst(ehpi.getPortInst(), null);
        Assertions.assertEquals("C2", spiA2.getName());
        Assertions.assertSame(origNet, spiA2.getNet());

        ECOTools.disconnectNet(design, ehpi);
        ECOTools.connectNet(design, cell1, pin, targetNet);
        
        // Because both LUT sites are occupied, A2 is being used by cell0 so we cannot use it anymore
        Assertions.assertEquals("A5", cell1.getPhysicalPinMapping(pin));
        Assertions.assertEquals(targetNet.getName(),
                ehpi.getHierarchicalNetName());
        SitePinInst spiA5 = cell1.getSitePinFromPortInst(ehpi.getPortInst(), null);
        Assertions.assertEquals("C5", spiA5.getName());
        Assertions.assertSame(targetNet, spiA5.getNet());
        Assertions.assertSame(origNet, spiA2.getNet());

        // Now disconnect from cell0 too
        ECOTools.disconnectNet(design, cell0.getEDIFHierCellInst().getPortInst(pin));
        Assertions.assertNull(spiA2.getNet());
        // Connect that to targetNet too
        ECOTools.connectNet(design, cell0, pin, targetNet);
        // Check that it overwrites its existing A2 input
        // TODO: Expect it to re-use A5
        Assertions.assertEquals("A2", cell0.getPhysicalPinMapping(pin));
        Assertions.assertEquals(targetNet.getName(),
                cell0.getEDIFHierCellInst().getPortInst(pin).getHierarchicalNetName());
        Assertions.assertSame(/*spiA5*/ spiA2, cell0.getSitePinFromPortInst(ehpi.getPortInst(), null));
        Assertions.assertSame(targetNet, /*spiA5*/spiA2.getNet());
    }

    @Test
    @Disabled("Currently, ECOTools.removeCell() does not work for hierarchical cells. Specifically, for this testcase " +
            "exclusively intra-site routes (e.g. 'processor/data_path_loop[4].small_spm.small_spm_ram.spm_ram/DOA') " +
            "are not removed and appear in the DCP causing Vivado to emit 'placement information for XX sites failed to" +
            "restore' warnings and cells (e.g. 'your_program/ram_4096x8') to be unplaced.")
    public void testRemoveCellHier() {
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

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(design));

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

        VivadoToolsHelper.assertFullyRouted(d);
    }

    @Test
    public void testCreateNet() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(design));

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

    @Test
    public void testRemoveCellVersal() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");

        SiteInst si = d.getSiteInst("SLICE_X145Y0");

        Assertions.assertNotNull(si.getUsedSitePIP("OUTMUXB2"));
        Assertions.assertNotNull(si.getUsedSitePIP("OUTMUXA2"));
        Assertions.assertNotNull(si.getUsedSitePIP("FFMUXB2"));
        Assertions.assertNotNull(si.getUsedSitePIP("FFMUXA2"));
        Assertions.assertEquals("in_port[0]", si.getNetFromSiteWire("BFF2_Q").getName());
        Assertions.assertEquals("in_port[0]", si.getNetFromSiteWire("BQ2").getName());

        Assertions.assertEquals("in_port[1]", si.getNetFromSiteWire("AFF2_Q").getName());
        Assertions.assertEquals("in_port[1]", si.getNetFromSiteWire("AQ2").getName());
        Assertions.assertEquals("processor/D[0]", si.getNetFromSiteWire("FFMUXB2_OUT2").getName());
        Assertions.assertEquals("processor/D[1]", si.getNetFromSiteWire("FFMUXA2_OUT2").getName());

        Assertions.assertEquals(Net.GND_NET, si.getNetFromSiteWire("SR_IMR_Q").getName());
        Assertions.assertEquals(Net.VCC_NET, si.getNetFromSiteWire("CE1_IMR_Q").getName());

        Assertions.assertEquals("clk_IBUF", si.getNetFromSiteWire("FF_CLK_MOD_CLK_OUT").getName());

        Cell rt = si.getCell("SR_IMR");
        Assertions.assertNotNull(rt);
        Assertions.assertTrue(rt.isFFRoutethruCell());
        Assertions.assertEquals(Net.GND_NET, si.getNetFromSiteWire("RST").getName());
        Assertions.assertNotNull(si.getUsedSitePIP("RSTINV"));

        rt = si.getCell("CE1_IMR");
        Assertions.assertNotNull(rt);
        Assertions.assertTrue(rt.isFFRoutethruCell());
        Assertions.assertEquals(Net.VCC_NET, si.getNetFromSiteWire("CKEN1").getName());

        Assertions.assertEquals("clk_IBUF", si.getNetFromSiteWire("CLKINV_OUT").getName());
        Assertions.assertEquals("clk_IBUF", si.getNetFromSiteWire("CLK").getName());
        Assertions.assertNotNull(si.getUsedSitePIP("CLKINV"));

        Cell cell = d.getCell("in_port_reg[0]");
        ECOTools.removeCell(d, Collections.singletonList(cell.getEDIFHierCellInst()), null);

        Assertions.assertNull(si.getUsedSitePIP("OUTMUXB2"));
        Assertions.assertNotNull(si.getUsedSitePIP("OUTMUXA2"));
        Assertions.assertNotNull(si.getUsedSitePIP("FFMUXA2"));
        Assertions.assertNull(si.getNetFromSiteWire("BFF2_Q"));
        Assertions.assertNull(si.getNetFromSiteWire("BQ2"));

        Assertions.assertEquals("in_port[1]", si.getNetFromSiteWire("AFF2_Q").getName());
        Assertions.assertEquals("in_port[1]", si.getNetFromSiteWire("AQ2").getName());
        Assertions.assertEquals("processor/D[1]", si.getNetFromSiteWire("FFMUXA2_OUT2").getName());

        Assertions.assertEquals(Net.GND_NET, si.getNetFromSiteWire("SR_IMR_Q").getName());
        Assertions.assertEquals(Net.VCC_NET, si.getNetFromSiteWire("CE1_IMR_Q").getName());

        Assertions.assertEquals("clk_IBUF", si.getNetFromSiteWire("FF_CLK_MOD_CLK_OUT").getName());

        rt = si.getCell("SR_IMR");
        Assertions.assertNotNull(rt);
        Assertions.assertTrue(rt.isFFRoutethruCell());
        Assertions.assertEquals(Net.GND_NET, si.getNetFromSiteWire("RST").getName());
        Assertions.assertNotNull(si.getUsedSitePIP("RSTINV"));

        rt = si.getCell("CE1_IMR");
        Assertions.assertNotNull(rt);
        Assertions.assertTrue(rt.isFFRoutethruCell());
        Assertions.assertEquals(Net.VCC_NET, si.getNetFromSiteWire("CKEN1").getName());

        Assertions.assertEquals("clk_IBUF", si.getNetFromSiteWire("CLKINV_OUT").getName());
        Assertions.assertEquals("clk_IBUF", si.getNetFromSiteWire("CLK").getName());
        Assertions.assertNotNull(si.getUsedSitePIP("CLKINV"));

        cell = d.getCell("in_port_reg[1]");
        ECOTools.removeCell(d, Collections.singletonList(cell.getEDIFHierCellInst()), null);

        Assertions.assertNull(si.getUsedSitePIP("OUTMUXB2"));
        Assertions.assertNull(si.getUsedSitePIP("OUTMUXA2"));
        Assertions.assertNull(si.getNetFromSiteWire("BFF2_Q"));
        Assertions.assertNull(si.getNetFromSiteWire("BQ2"));

        Assertions.assertNull(si.getNetFromSiteWire("AFF2_Q"));
        Assertions.assertNull(si.getNetFromSiteWire("AQ2"));

        Assertions.assertNull(si.getNetFromSiteWire("SR_IMR_Q"));
        Assertions.assertNull(si.getNetFromSiteWire("CE1_IMR_Q"));

        Assertions.assertNull(si.getNetFromSiteWire("FF_CLK_MOD_CLK_OUT"));

        rt = si.getCell("SR_IMR");
        Assertions.assertNull(rt);
        Assertions.assertNull(si.getNetFromSiteWire("RST"));
        Assertions.assertNull(si.getUsedSitePIP("RSTINV"));

        rt = si.getCell("CE1_IMR");
        Assertions.assertNull(rt);
        Assertions.assertNull(si.getNetFromSiteWire("CKEN1"));

        Assertions.assertNull(si.getNetFromSiteWire("CLKINV_OUT"));
        Assertions.assertNull(si.getNetFromSiteWire("CLK"));
        Assertions.assertNull(si.getUsedSitePIP("CLKINV"));

    }

    @Test
    public void testRemoveCellNeighborRouteThru() {
        // This is a corner case that involves a CARRY8 in US+ and a LUT removal from
        // A6LUT that would prevent the routethru removal later when the CARRY8 was
        // removed.
        Design d = new Design("test", "xcku9p-ffve900-2-e");
        String siteName = "SLICE_X96Y231";
        Cell carry = d.createAndPlaceCell("carry", Unisim.CARRY8, siteName + "/CARRY8");
        SiteInst si = carry.getSiteInst();
        Cell lut1 = d.createAndPlaceCell("lut1", Unisim.LUT1, siteName + "/A6LUT");
        Cell ff = d.createAndPlaceCell("ff", Unisim.FDRE, siteName + "/HFF");
        Cell ff2 = d.createAndPlaceCell("ff2", Unisim.FDRE, siteName + "/AFF");
        Net lut1Input = d.createNet("lut1Input");
        lut1Input.connect(lut1, "I0");
        lut1Input.connect(ff2, "Q");
        si.routeIntraSiteNet(lut1Input, lut1.getBEL().getPin("A6"), si.getBELPin("A6", "A6"));
        Net carryS0 = d.createNet("carryS0");
        carryS0.connect(lut1, "O");
        carryS0.connect(carry, "S[0]");
        si.routeIntraSiteNet(carryS0, lut1.getBEL().getPin("O6"), carry.getBEL().getPin("S0"));
        Net routethruNet = d.createNet("routethruNet");
        routethruNet.connect(ff, "Q");
        routethruNet.connect(carry, "S[7]");
        si.routeIntraSiteNet(routethruNet, ff.getBEL().getPin("Q"), si.getBELPin("HQ", "HQ"));
        si.routeIntraSiteNet(routethruNet, si.getBELPin("H6", "H6"), carry.getBEL().getPin("S7"));

        List<EDIFHierCellInst> remove = new ArrayList<>();
        remove.add(lut1.getEDIFHierCellInst());
        ECOTools.removeCell(d, remove, null);

        // Ensure the net stays routed after LUT1 is removed from A6LUT
        Assertions.assertEquals(si.getNetFromSiteWire("H6"), routethruNet);
    }

    private void testRefactorCellHelper(Design d, String cellName, String newParentName) {
        EDIFHierCellInst cell = d.getNetlist().getHierCellInstFromName(cellName);
        EDIFHierCellInst newParent = d.getNetlist().getHierCellInstFromName(newParentName);

        Map<String, Set<EDIFHierPortInst>> map = new HashMap<>();
        for (EDIFHierPortInst i : cell.getHierPortInsts()) {
            String portInstName = i.getPortInst().getName();
            Set<EDIFHierPortInst> pins = new HashSet<>();
            for (EDIFHierPortInst leafPin : i.getHierarchicalNet().getLeafHierPortInsts()) {
                if (leafPin.equals(i)
                        || leafPin.getFullHierarchicalInstName().equals(i.getFullHierarchicalInstName())) {
                    continue;
                }
                pins.add(leafPin);
            }
            map.put(portInstName, pins);
        }

        EDIFHierCellInst refactored = ECOTools.refactorCell(d, cell, newParent);

        Assertions.assertEquals(newParent, refactored.getParent());

        for (EDIFHierPortInst i : refactored.getHierPortInsts()) {
            String portInstName = i.getPortInst().getName();
            Set<EDIFHierPortInst> leafPins = map.get(portInstName);
            for (EDIFHierPortInst leafPin : i.getHierarchicalNet().getLeafHierPortInsts()) {
                if (leafPin.equals(i)
                        || leafPin.getFullHierarchicalInstName().equals(i.getFullHierarchicalInstName())) {
                    continue;
                }
                Assertions.assertTrue(leafPins.remove(leafPin));
            }
            Assertions.assertEquals(0, leafPins.size());
        }

    }

    /**
     * Generated with CodeGenerator.genCodeForTestSite()
     */
    public Design genTestDesign() {
        Design design = new Design("test", "xcku060-ffva1517-2-i");
        Device device = design.getDevice();
        SiteInst si = design.createSiteInst("SLICE_X134Y49", SiteTypeEnum.SLICEL, device.getSite("SLICE_X134Y49"));
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();
        Cell cell0 = CodeGenerator.genCell(si, "cell0", false, "FDRE", "AFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell0.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell1 = CodeGenerator.genCell(si, "cell1", false, "FDRE", "CFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell1.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell2 = CodeGenerator.genCell(si, "cell2", false, "FDRE", "BFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell2.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell3 = CodeGenerator.genCell(si, "cell3", false, "FDRE", "EFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell3.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell4 = CodeGenerator.genCell(si, "cell4", false, "FDRE", "DFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell4.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell5 = CodeGenerator.genCell(si, "cell5", false, "FDRE", "GFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell5.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell6 = CodeGenerator.genCell(si, "cell6", false, "FDRE", "FFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell6.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell7 = CodeGenerator.genCell(si, "cell7", false, "FDRE", "HFF", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell7.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell8_B6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "B6LUT", "A6:S[1]");
        Cell cell9 = CodeGenerator.genCell(si, "cell9", false, "FDRE", "FFF2", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell9.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell10 = CodeGenerator.genCell(si, "cell10", false, "FDRE", "EFF2", "Q:Q", "CLK:C", "D:D", "SR:R");
        cell10.addProperty("INIT", "1'b0", EDIFValueType.STRING);
        Cell cell8_H6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "H6LUT", "A6:S[7]");
        Cell cell8_D6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "D6LUT", "A6:S[3]");
        Cell cell9_F6LUT = CodeGenerator.genCell(si, "cell9", true, "FDRE", "F6LUT", "A6:D");
        cell9_F6LUT.addAltPinMapping("A6", new AltPinMapping("S[5]", "cell8", "CARRY8"));
        Cell cell8_C6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "C6LUT", "A6:S[2]");
        Cell cell8 = CodeGenerator.genCell(si, "cell8", false, "CARRY8", "CARRY8", "S3:S[3]", "S4:S[4]", "O0:O[0]",
                "S5:S[5]", "O1:O[1]", "S6:S[6]", "O2:O[2]", "S7:S[7]", "CO1:CO[1]", "O3:O[3]", "CO0:CO[0]", "O4:O[4]",
                "CO3:CO[3]", "O5:O[5]", "CO2:CO[2]", "O6:O[6]", "CO5:CO[5]", "O7:O[7]", "CO4:CO[4]", "DI0:DI[0]",
                "CO7:CO[7]", "CO6:CO[6]", "HX:DI[7]", "FX:DI[5]", "DX:DI[3]", "BX:DI[1]", "GX:DI[6]", "EX:DI[4]",
                "CX:DI[2]", "AX:CI", "S0:S[0]", "S1:S[1]", "S2:S[2]");
        cell8.addProperty("CARRY_TYPE", "SINGLE_CY8", EDIFValueType.STRING);
        Cell cell8_A6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "A6LUT", "A2:S[0]");
        Cell cell8_E6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "E6LUT", "A6:S[4]");
        Cell cell8_G6LUT = CodeGenerator.genCell(si, "cell8", true, "CARRY8", "G6LUT", "A6:S[6]");

        CodeGenerator.addSitePIPs(si, "CLK1INV:CLK", "CLK2INV:CLK", "FFMUXA1:XORIN", "FFMUXB1:XORIN", "FFMUXC1:XORIN",
                "FFMUXD1:XORIN", "FFMUXE1:XORIN", "FFMUXE2:BYP", "FFMUXF1:XORIN", "FFMUXF2:D6", "FFMUXG1:XORIN",
                "FFMUXH1:XORIN", "RST_ABCDINV:RST", "RST_EFGHINV:RST");

        Net net11 = design.createNet("net11");
        net11.getLogicalNet().createPortInst("Q", cell9);
        top.getNet("net11").createPortInst(top.createPort("port12", EDIFDirection.OUTPUT, 1));
        net11.createPin("FQ2", si);
        CodeGenerator.routeSiteNet(si, net11, "FQ2");
        Net net13 = design.createNet("net13");
        net13.getLogicalNet().createPortInst("C", cell10);
        net13.getLogicalNet().createPortInst("C", cell9);
        net13.getLogicalNet().createPortInst("C", cell0);
        net13.getLogicalNet().createPortInst("C", cell2);
        net13.getLogicalNet().createPortInst("C", cell1);
        net13.getLogicalNet().createPortInst("C", cell4);
        net13.getLogicalNet().createPortInst("C", cell3);
        net13.getLogicalNet().createPortInst("C", cell6);
        net13.getLogicalNet().createPortInst("C", cell5);
        net13.getLogicalNet().createPortInst("C", cell7);
        top.getNet("net13").createPortInst(top.createPort("port14", EDIFDirection.INPUT, 1));
        net13.createPin("CLK_B1", si);
        net13.createPin("CLK_B2", si);
        CodeGenerator.routeSiteNet(si, net13, "CLK1INV_OUT", "CLK2INV_OUT", "CLK_B1", "CLK_B2");
        Net vcc = design.getVccNet();
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell0);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell1);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell2);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell3);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell4);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell5);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell6);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell7);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell9);
        EDIFTools.getStaticNet(NetType.VCC, top, netlist).createPortInst("CE", cell10);
        vcc.createPin("A6", si);
        CodeGenerator.routeSiteNet(si, vcc, "A6");
        Net net15 = design.createNet("net15");
        net15.getLogicalNet().createPortInst("D", cell5);
        net15.getLogicalNet().createPortInst("O[6]", cell8);
        CodeGenerator.routeSiteNet(si, net15, "CARRY8_O6", "FFMUXG1_OUT1");
        Net net16 = design.createNet("net16");
        net16.getLogicalNet().createPortInst("S[1]", cell8);
        top.getNet("net16").createPortInst(top.createPort("port17", EDIFDirection.INPUT, 1));
        net16.createPin("B6", si);
        CodeGenerator.routeSiteNet(si, net16, "B6", "B_O");
        Net net18 = design.createNet("net18");
        net18.getLogicalNet().createPortInst("R", cell10);
        net18.getLogicalNet().createPortInst("R", cell9);
        net18.getLogicalNet().createPortInst("R", cell0);
        net18.getLogicalNet().createPortInst("R", cell2);
        net18.getLogicalNet().createPortInst("R", cell1);
        net18.getLogicalNet().createPortInst("R", cell4);
        net18.getLogicalNet().createPortInst("R", cell3);
        net18.getLogicalNet().createPortInst("R", cell6);
        net18.getLogicalNet().createPortInst("R", cell5);
        net18.getLogicalNet().createPortInst("R", cell7);
        top.getNet("net18").createPortInst(top.createPort("port19", EDIFDirection.INPUT, 1));
        net18.createPin("SRST_B1", si);
        net18.createPin("SRST_B2", si);
        CodeGenerator.routeSiteNet(si, net18, "RST_ABCDINV_OUT", "RST_EFGHINV_OUT", "SRST_B1", "SRST_B2");
        Net net20 = design.createNet("net20");
        net20.getLogicalNet().createPortInst("D", cell3);
        net20.getLogicalNet().createPortInst("O[4]", cell8);
        CodeGenerator.routeSiteNet(si, net20, "CARRY8_O4", "FFMUXE1_OUT1");
        Net net21 = design.createNet("net21");
        net21.getLogicalNet().createPortInst("CI", cell8);
        top.getNet("net21").createPortInst(top.createPort("port22", EDIFDirection.INPUT, 1));
        net21.createPin("AX", si);
        CodeGenerator.routeSiteNet(si, net21, "AX");
        Net gnd = design.getGndNet();
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("CI_TOP", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[0]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[1]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[2]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[3]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[4]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[5]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[6]", cell8);
        EDIFTools.getStaticNet(NetType.GND, top, netlist).createPortInst("DI[7]", cell8);
        gnd.createPin("BX", si);
        gnd.createPin("CX", si);
        gnd.createPin("DX", si);
        gnd.createPin("HX", si);
        gnd.createPin("GX", si);
        gnd.createPin("FX", si);
        gnd.createPin("EX", si);
        CodeGenerator.routeSiteNet(si, gnd, "A5LUT_O5", "BX", "CX", "DX", "EX", "FX", "GND_WIRE", "GX", "HX");
        Net net23 = design.createNet("net23");
        net23.getLogicalNet().createPortInst("D", cell1);
        net23.getLogicalNet().createPortInst("O[2]", cell8);
        CodeGenerator.routeSiteNet(si, net23, "CARRY8_O2", "FFMUXC1_OUT1");
        Net net24 = design.createNet("net24");
        net24.getLogicalNet().createPortInst("S[7]", cell8);
        top.getNet("net24").createPortInst(top.createPort("port25", EDIFDirection.INPUT, 1));
        net24.createPin("H6", si);
        CodeGenerator.routeSiteNet(si, net24, "H6", "H_O");
        Net net26 = design.createNet("net26");
        net26.getLogicalNet().createPortInst("D", cell0);
        net26.getLogicalNet().createPortInst("O[0]", cell8);
        CodeGenerator.routeSiteNet(si, net26, "CARRY8_O0", "FFMUXA1_OUT1");
        Net net27 = design.createNet("net27");
        net27.getLogicalNet().createPortInst("D", cell9);
        net27.getLogicalNet().createPortInst("S[5]", cell8);
        top.getNet("net27").createPortInst(top.createPort("port28", EDIFDirection.INPUT, 1));
        net27.createPin("F6", si);
        CodeGenerator.routeSiteNet(si, net27, "F6", "FFMUXF2_OUT2", "F_O");
        Net net29 = design.createNet("net29");
        net29.getLogicalNet().createPortInst("S[3]", cell8);
        top.getNet("net29").createPortInst(top.createPort("port30", EDIFDirection.INPUT, 1));
        net29.createPin("D6", si);
        CodeGenerator.routeSiteNet(si, net29, "D6", "D_O");
        Net net31 = design.createNet("net31");
        net31.getLogicalNet().createPortInst("Q", cell4);
        top.getNet("net31").createPortInst(top.createPort("port32", EDIFDirection.OUTPUT, 1));
        net31.createPin("DQ", si);
        CodeGenerator.routeSiteNet(si, net31, "DQ");
        Net net33 = design.createNet("net33");
        net33.getLogicalNet().createPortInst("Q", cell6);
        top.getNet("net33").createPortInst(top.createPort("port34", EDIFDirection.OUTPUT, 1));
        net33.createPin("FQ", si);
        CodeGenerator.routeSiteNet(si, net33, "FQ");
        Net net35 = design.createNet("net35");
        net35.getLogicalNet().createPortInst("Q", cell10);
        top.getNet("net35").createPortInst(top.createPort("port36", EDIFDirection.OUTPUT, 1));
        net35.createPin("EQ2", si);
        CodeGenerator.routeSiteNet(si, net35, "EQ2");
        Net net37 = design.createNet("net37");
        CodeGenerator.routeSiteNet(si, net37, "AQ2", "BQ2", "CARRY8_CO0", "CARRY8_CO1", "CARRY8_CO2", "CARRY8_CO3",
                "CARRY8_CO4", "CARRY8_CO5", "CARRY8_CO6", "CQ2", "DQ2", "F7MUX_AB_OUT", "F7MUX_CD_OUT", "F7MUX_EF_OUT",
                "F7MUX_GH_OUT", "F8MUX_BOT_OUT", "F8MUX_TOP_OUT", "F9MUX_OUT", "GQ2", "HQ2");
        Net net38 = design.createNet("net38");
        net38.getLogicalNet().createPortInst("Q", cell2);
        top.getNet("net38").createPortInst(top.createPort("port39", EDIFDirection.OUTPUT, 1));
        net38.createPin("BQ", si);
        CodeGenerator.routeSiteNet(si, net38, "BQ");
        Net net40 = design.createNet("net40");
        net40.getLogicalNet().createPortInst("Q", cell7);
        top.getNet("net40").createPortInst(top.createPort("port41", EDIFDirection.OUTPUT, 1));
        net40.createPin("HQ", si);
        CodeGenerator.routeSiteNet(si, net40, "HQ");
        Net net42 = design.createNet("net42");
        net42.getLogicalNet().createPortInst("D", cell7);
        net42.getLogicalNet().createPortInst("O[7]", cell8);
        CodeGenerator.routeSiteNet(si, net42, "CARRY8_O7", "FFMUXH1_OUT1");
        Net net43 = design.createNet("net43");
        net43.getLogicalNet().createPortInst("D", cell6);
        net43.getLogicalNet().createPortInst("O[5]", cell8);
        CodeGenerator.routeSiteNet(si, net43, "CARRY8_O5", "FFMUXF1_OUT1");
        Net net44 = design.createNet("net44");
        net44.getLogicalNet().createPortInst("S[2]", cell8);
        top.getNet("net44").createPortInst(top.createPort("port45", EDIFDirection.INPUT, 1));
        net44.createPin("C6", si);
        CodeGenerator.routeSiteNet(si, net44, "C6", "C_O");
        Net net46 = design.createNet("net46");
        net46.getLogicalNet().createPortInst("D", cell10);
        top.getNet("net46").createPortInst(top.createPort("port47", EDIFDirection.INPUT, 1));
        net46.createPin("E_I", si);
        CodeGenerator.routeSiteNet(si, net46, "E_I", "FFMUXE2_OUT2");
        Net net48 = design.createNet("net48");
        net48.getLogicalNet().createPortInst("CO[7]", cell8);
        top.getNet("net48").createPortInst(top.createPort("port49", EDIFDirection.OUTPUT, 1));
        net48.createPin("COUT", si);
        CodeGenerator.routeSiteNet(si, net48, "COUT");
        Net net50 = design.createNet("net50");
        net50.getLogicalNet().createPortInst("D", cell4);
        net50.getLogicalNet().createPortInst("O[3]", cell8);
        CodeGenerator.routeSiteNet(si, net50, "CARRY8_O3", "FFMUXD1_OUT1");
        Net net51 = design.createNet("net51");
        net51.getLogicalNet().createPortInst("S[0]", cell8);
        top.getNet("net51").createPortInst(top.createPort("port52", EDIFDirection.INPUT, 1));
        net51.createPin("A2", si);
        CodeGenerator.routeSiteNet(si, net51, "A2", "A_O");
        Net net53 = design.createNet("net53");
        net53.getLogicalNet().createPortInst("D", cell2);
        net53.getLogicalNet().createPortInst("O[1]", cell8);
        CodeGenerator.routeSiteNet(si, net53, "CARRY8_O1", "FFMUXB1_OUT1");
        Net net54 = design.createNet("net54");
        net54.getLogicalNet().createPortInst("S[6]", cell8);
        top.getNet("net54").createPortInst(top.createPort("port55", EDIFDirection.INPUT, 1));
        net54.createPin("G6", si);
        CodeGenerator.routeSiteNet(si, net54, "G6", "G_O");
        Net net56 = design.createNet("net56");
        net56.getLogicalNet().createPortInst("S[4]", cell8);
        top.getNet("net56").createPortInst(top.createPort("port57", EDIFDirection.INPUT, 1));
        net56.createPin("E6", si);
        CodeGenerator.routeSiteNet(si, net56, "E6", "E_O");
        Net net58 = design.createNet("net58");
        net58.getLogicalNet().createPortInst("Q", cell3);
        top.getNet("net58").createPortInst(top.createPort("port59", EDIFDirection.OUTPUT, 1));
        net58.createPin("EQ", si);
        CodeGenerator.routeSiteNet(si, net58, "EQ");
        Net net60 = design.createNet("net60");
        net60.getLogicalNet().createPortInst("Q", cell5);
        top.getNet("net60").createPortInst(top.createPort("port61", EDIFDirection.OUTPUT, 1));
        net60.createPin("GQ", si);
        CodeGenerator.routeSiteNet(si, net60, "GQ");
        Net net62 = design.createNet("net62");
        net62.getLogicalNet().createPortInst("Q", cell0);
        top.getNet("net62").createPortInst(top.createPort("port63", EDIFDirection.OUTPUT, 1));
        net62.createPin("AQ", si);
        CodeGenerator.routeSiteNet(si, net62, "AQ");
        Net net64 = design.createNet("net64");
        net64.getLogicalNet().createPortInst("Q", cell1);
        top.getNet("net64").createPortInst(top.createPort("port65", EDIFDirection.OUTPUT, 1));
        net64.createPin("CQ", si);
        CodeGenerator.routeSiteNet(si, net64, "CQ");
        return design;
    }

    @Test
    public void testRefactorCell() {
        // Test generated site instance design
        Design d = genTestDesign();
        d.getNetlist().getTopCell().createChildCellInst("dummy_parent_inst",
                new EDIFCell(d.getNetlist().getWorkLibrary(), "dummy_parent"));

        testRefactorCellHelper(d, "cell8", "dummy_parent_inst");

        // Test microblaze design
        d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks_2024.1.dcp");

        String cellName = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Decode_I/PreFetch_Buffer_I1/Instruction_Prefetch_Mux[9].Gen_Instr_DFF/EX_Op3[2]_i_2";
        String newParentName = "dbg_hub/inst";

        testRefactorCellHelper(d, cellName, newParentName);

        cellName = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Use_DLMB.wb_dlmb_valid_read_data_reg[1]";
        newParentName = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Decode_I/PreFetch_Buffer_I1/Instruction_Prefetch_Mux[9].Gen_Instr_DFF";

        testRefactorCellHelper(d, cellName, newParentName);

        cellName = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Zero_Detect_I/Part_Of_Zero_Carry_Start/Using_FPGA.Native_CARRY4_CARRY8";
        newParentName = "";

        testRefactorCellHelper(d, cellName, newParentName);

        VivadoToolsHelper.assertFullyRouted(d);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xcvu19p", "xcvp1502"})
    public void testDiscussion1245(String device) {
        // Create a test design
        Design test = new Design("test_design", device);

        // Place two luts at 2 arbitrarily chosen sites
        Cell lut_1 = test.createAndPlaceCell("lut_1", Unisim.LUT6, "SLICE_X148Y0/A6LUT");
        LUTTools.configureLUT(lut_1, "O!=I1");

        Cell lut_2 = test.createAndPlaceCell("lut_2", Unisim.LUT6, "SLICE_X148Y1/B6LUT");
        LUTTools.configureLUT(lut_2, "O=I1");

        // Create a net
        Net net = test.createNet("test_net");

        // Using ECOTools
        ECOTools.connectNet(test, lut_1, "O", net);        // Source
        ECOTools.connectNet(test, lut_2, "I1", net);       // Sinks

        Assertions.assertEquals("[IN SLICE_X148Y0.B2]", PartialRouter.getUnroutedPins(test).toString());
    }
}
