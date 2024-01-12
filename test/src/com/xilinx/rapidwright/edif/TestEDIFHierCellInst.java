/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.python.google.common.base.Strings;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFHierCellInst {

    @Test
    public void testIsAncestor() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        topInst.addChildren(q);
        while (!q.isEmpty()) {
            EDIFHierCellInst curr = q.poll();
            Assertions.assertTrue(curr.isDescendantOf(topInst));
            Assertions.assertFalse(topInst.isDescendantOf(curr));
            curr.addChildren(q);
        }
    }

    @Test
    public void testGetCommonAncestor() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        String name0 = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/"
                        + "Data_Flow_Logic_I/Gen_Bits[22].MEM_EX_Result_Inst/Using_FPGA.Native";
        String name1 = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Decode_I/"
                + "PreFetch_Buffer_I1/Instruction_Prefetch_Mux[9].Gen_Instr_DFF/EX_Op3[22]_i_2";

        EDIFHierCellInst inst0 = netlist.getHierCellInstFromName(name0);
        EDIFHierCellInst inst1 = netlist.getHierCellInstFromName(name1);

        EDIFHierCellInst commonAncestor = inst0.getCommonAncestor(inst1);

        String commonPrefix = Strings.commonPrefix(name0, name1);
        Assertions.assertEquals(commonAncestor.getFullHierarchicalInstName(),
                commonPrefix.substring(0, commonPrefix.lastIndexOf('/')));

        String name2 = "u_ila_0/inst/ila_core_inst/basic_trigger_reg";

        EDIFHierCellInst inst2 = netlist.getHierCellInstFromName(name2);
        commonAncestor = inst1.getCommonAncestor(inst2);
        Assertions.assertEquals(commonAncestor, netlist.getTopHierCellInst());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "picoblaze_ooc_X10Y235.dcp",
            "optical-flow.dcp",
            "bnn.dcp",
    })
    public void testIsUniquified(String path) {
        Design design = RapidWrightDCP.loadDCP(path, true);
        EDIFNetlist netlist = design.getNetlist();

        for (EDIFHierCellInst ehci : netlist.getAllDescendants("", null, false)) {
            Assertions.assertTrue(ehci.isUniquified() || ehci.getCellType().getLibrary().isHDIPrimitivesLibrary());
        }
    }

    @Test
    public void testIsUniquifiedFalse() {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Assertions.assertTrue(netlist.getTopCell().isUniquified());

        for (String path : Arrays.asList(
                "picoblaze_0_12",
                "picoblaze_0_13",
                "picoblaze_1_12",
                "picoblaze_1_13",
                "picoblaze_1_13/processor",
                "picoblaze_1_13/processor/active_interrupt_lut",        // This is a LUT6_2 macro
                "picoblaze_1_13/processor/active_interrupt_lut/LUT5"    // This is a LUT5 primitive
        )) {
            EDIFHierCellInst ehci = netlist.getHierCellInstFromName(path);
            Assertions.assertFalse(ehci.isUniquified());
        }

        // Test that removing all but one instance makes the remaining one unique
        EDIFCell topCell = netlist.getTopCell();
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_12"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_13"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_12"));
        Assertions.assertTrue(netlist.getHierCellInstFromName("picoblaze_1_13/processor").isUniquified());

        // Check that creating an EDIFCellInst *with* a parent cell *does* increment instance count
        EDIFCell picoblazeTop = netlist.getCell("picoblaze_top");
        new EDIFCellInst("picoblaze_0_12", picoblazeTop, topCell);
        EDIFHierCellInst ehci = netlist.getHierCellInstFromName("picoblaze_0_12/processor");
        Assertions.assertFalse(ehci.isUniquified());
        // ... and removing the other cell instantiation makes this one unique
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_13"));
        Assertions.assertTrue(ehci.isUniquified());
    }
}
