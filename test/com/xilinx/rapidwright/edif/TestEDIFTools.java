
/*
 * Copyright (c) 2022 Xilinx, Inc.
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFTools {

    public static final String UNIQUE_SUFFIX = "TestEDIFToolsWasHere";
    
    public static final String TEST_SRC = "base_mb_i/microblaze_0/U0/"
            + "MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Data_Flow_Logic_I/Gen_Bits[22]."
            + "MEM_EX_Result_Inst/Using_FPGA.Native/Q"; 
    public static final String TEST_SNK = "u_ila_0/inst/PROBE_PIPE."
            + "shift_probes_reg[0][7]/D"; 

    @Test
    public void testConnectPortInstsThruHier() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        EDIFHierPortInst srcPortInst = netlist.getHierPortInstFromName(TEST_SRC);
        EDIFHierPortInst snkPortInst = netlist.getHierPortInstFromName(TEST_SNK);

        // Disconnect sink in anticipation of connecting to another net
        snkPortInst.getNet().removePortInst(snkPortInst.getPortInst());

        EDIFTools.connectPortInstsThruHier(srcPortInst, snkPortInst, netlist, UNIQUE_SUFFIX);

        netlist.resetParentNetMap();


        List<EDIFHierNet> netAliases = netlist.getNetAliases(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(netAliases.size(), 16);
        boolean containsSnkNet = false;
        for(EDIFHierNet net : netAliases) {
            if(net.getHierarchicalNetName().equals(snkPortInst.getHierarchicalNetName())) {
                containsSnkNet = true;
            }
        }
        Assertions.assertTrue(containsSnkNet);


        List<EDIFHierPortInst> portInsts = netlist.getPhysicalPins(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(portInsts.size(), 6);
        boolean containsSnk = false;
        for(EDIFHierPortInst sink : portInsts) {
            if(sink.toString().equals(snkPortInst.toString())) {
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
}
