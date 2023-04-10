
/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

public class TestEDIFTools {

    public static final String UNIQUE_SUFFIX = "TestEDIFToolsWasHere";

    public static final String TEST_SRC = "base_mb_i/microblaze_0/U0/"
            + "MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Data_Flow_Logic_I/Gen_Bits[22]."
            + "MEM_EX_Result_Inst/Using_FPGA.Native/Q";
    public static final String TEST_SNK = "u_ila_0/inst/PROBE_PIPE."
            + "shift_probes_reg[0][7]/D";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testConnectPortInstsThruHier(boolean netToPin) {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        EDIFHierPortInst srcPortInst = netlist.getHierPortInstFromName(TEST_SRC);
        EDIFHierPortInst snkPortInst = netlist.getHierPortInstFromName(TEST_SNK);

        // Disconnect sink in anticipation of connecting to another net
        snkPortInst.getNet().removePortInst(snkPortInst.getPortInst());

        if (netToPin) {
            EDIFTools.connectPortInstsThruHier(srcPortInst.getHierarchicalNet(), snkPortInst, UNIQUE_SUFFIX);
        } else {
            EDIFTools.connectPortInstsThruHier(srcPortInst, snkPortInst, UNIQUE_SUFFIX);
        }

        netlist.resetParentNetMap();


        List<EDIFHierNet> netAliases = netlist.getNetAliases(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(netAliases.size(), 16);
        boolean containsSnkNet = false;
        for (EDIFHierNet net : netAliases) {
            if (net.getHierarchicalNetName().equals(snkPortInst.getHierarchicalNetName())) {
                containsSnkNet = true;
            }
        }
        Assertions.assertTrue(containsSnkNet);


        List<EDIFHierPortInst> portInsts = netlist.getPhysicalPins(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(portInsts.size(), 6);
        boolean containsSnk = false;
        for (EDIFHierPortInst sink : portInsts) {
            if (sink.toString().equals(snkPortInst.toString())) {
                containsSnk = true;
            }
        }
        Assertions.assertTrue(containsSnk);
    }

    @Test
    public void testCreateNewNetlist() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("bnn.dcp"), true);
        EDIFHierCellInst inst = d.getNetlist().getHierCellInstFromName("bd_0_i/hls_inst/inst/dmem_V_U");

        EDIFNetlist newNetlist = EDIFTools.createNewNetlist(inst.getInst());
        EDIFTools.ensureCorrectPartInEDIF(newNetlist, d.getPartName());
        Design d2 = new Design(newNetlist);
        d2.setAutoIOBuffers(false);
        d2.setDesignOutOfContext(true);

        List<EDIFHierCellInst> goldChildren = d.getNetlist().getAllLeafDescendants(inst);
        List<EDIFHierCellInst> testChildren = d2.getNetlist().getAllLeafDescendants("");

        Assertions.assertEquals(goldChildren.size(), testChildren.size());
    }

    @Test
    void testRename() {
        //This test string contains multi-byte characters. We cannot encode it directly as a string here, because
        //source code encoding varies between platforms.
        byte[] special = new byte[]{
                (byte)0x65, (byte)0x6d, (byte)0x6f, (byte)0x6a, (byte)0x69, (byte)0x5f, (byte)0xf0,
                (byte)0x9f, (byte)0x98, (byte)0x8b, (byte)0xf0, (byte)0x9f, (byte)0x8e, (byte)0x9b,
                (byte)0xef, (byte)0xb8, (byte)0x8f
        };
        String unicodeStr = new String(special, StandardCharsets.UTF_8);
        Assertions.assertEquals("emoji______", EDIFTools.makeNameEDIFCompatible(unicodeStr));
        Assertions.assertEquals("&_", EDIFTools.makeNameEDIFCompatible(" "));
    }

    @Test
    public void testUniqueifyNetlist() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        Design design = new Design("test", Device.PYNQ_Z1);
        design.setNetlist(netlist);

        EDIFCell top = netlist.getTopCell();
        EDIFCell foo = new EDIFCell(netlist.getWorkLibrary(), "foo");
        EDIFCell bar = new EDIFCell(netlist.getWorkLibrary(), "bar");
        EDIFCell baz = new EDIFCell(netlist.getWorkLibrary(), "baz");

        bar.createChildCellInst("baz1", baz);
        bar.createChildCellInst("baz2", baz);

        foo.createChildCellInst("bar1", bar);
        foo.createChildCellInst("bar2", bar);

        top.createChildCellInst("foo1", foo);
        top.createChildCellInst("foo2", foo);

        Assertions.assertTrue(EDIFTools.uniqueifyNetlist(design));

        for (Entry<EDIFLibrary, Map<EDIFCell, List<EDIFHierCellInst>>> e :
                                            EDIFTools.createCellInstanceMap(netlist).entrySet()) {
            if (e.getKey().isHDIPrimitivesLibrary()) continue;
            for (Entry<EDIFCell, List<EDIFHierCellInst>> e2 : e.getValue().entrySet()) {
                Assertions.assertEquals(e2.getValue().size(), 1);
            }
        }

        Assertions.assertFalse(EDIFTools.uniqueifyNetlist(design));
    }

    @Test
    public void testCreateUniqueNet() {
        Design design = new Design("test", Device.AWS_F1);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();

        String netName = "foo";
        Assertions.assertEquals(netName, EDIFTools.createUniqueNet(top, netName).getName());

        String newNet1 = EDIFTools.createUniqueNet(top, netName).getName();
        Assertions.assertNotEquals(newNet1, netName);
        Assertions.assertTrue(newNet1.matches(netName + "_rw_created\\d+"));
        String newNet2 = EDIFTools.createUniqueNet(top, netName).getName();
        Assertions.assertNotEquals(newNet2, netName);
        Assertions.assertNotEquals(newNet2, newNet1);
        Assertions.assertTrue(newNet2.matches(netName + "_rw_created\\d+"));

        // Check that creating a net with the same name as an existing port is allowed.
        String portName = "bar";
        top.createPort(portName, EDIFDirection.INPUT, 1);
        Assertions.assertEquals(portName, EDIFTools.createUniqueNet(top, portName).getName());

        // Canary to check that creating a net with the same name as the root name of an existing bus net
        // -- designating by the existence of at least one bus[\d+] -- is allowed.
        // (Even though doing so may cause Vivado an issue.)
        String busNetName = "baz";
        top.createNet(busNetName + "[999]");
        Assertions.assertEquals(busNetName, EDIFTools.createUniqueNet(top, busNetName).getName());
    }

    @Test
    public void testCreateUniquePort() {
        Design design = new Design("test", Device.AWS_F1);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();

        // Single-bit port
        String portName = "foo";
        Assertions.assertEquals(portName, EDIFTools.createUniquePort(top, portName, EDIFDirection.INPUT, 1).getName());

        // Multi-bit bus port
        int busPortWidth = 16;
        String busPortBaseName = "bar";
        String busPortName = busPortBaseName + "[" + (busPortWidth-1) + ":0]";
        Assertions.assertEquals(busPortName, EDIFTools.createUniquePort(top, busPortName, EDIFDirection.INPUT, 16).getName());

        // Check that creating a new port with the same basename as a port gets uniquified
        String slicedPortName = busPortBaseName + "[17]";
        String newPort1 = EDIFTools.createUniquePort(top, slicedPortName, EDIFDirection.INPUT, 1).getName();
        Assertions.assertNotEquals(newPort1, slicedPortName);
        Assertions.assertTrue(newPort1.matches(Pattern.quote(slicedPortName) + "_rw_created\\d+"));
        String newPort2 = EDIFTools.createUniquePort(top, slicedPortName, EDIFDirection.OUTPUT, 1).getName();
        Assertions.assertNotEquals(newPort2, slicedPortName);
        Assertions.assertNotEquals(newPort2, newPort1);
        Assertions.assertTrue(newPort2.matches(Pattern.quote(slicedPortName) + "_rw_created\\d+"));
    }
}
