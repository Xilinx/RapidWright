/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Coherent Ho, Synopsys, Inc.
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

package com.xilinx.rapidwright.design.xdc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PblockProperty;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

public class TestConstraintTools {

    @ParameterizedTest
    @EnumSource(TestXDCParser.RoundtripMode.class)
    public void testGetPBlockFromXDCConstraints(TestXDCParser.RoundtripMode roundtripMode) {
        Design d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        d.getXDCConstraints(ConstraintGroup.LATE).add("set_property " + PblockProperty.IS_SOFT + " 1 [get_pblocks pblock_dbg_hub]");
        d.getXDCConstraints(ConstraintGroup.LATE).add("set_property " + PblockProperty.EXCLUDE_PLACEMENT + " 1 [get_pblocks pblock_u_ila_0]");
        roundtripMode.doRoundtrip(d);

        Map<String, PBlock> pblockMap = ConstraintTools.getPBlocksFromXDC(d);
        Assertions.assertEquals(3, pblockMap.size());
        Assertions.assertTrue(pblockMap.containsKey("pblock_dbg_hub"));
        Assertions.assertTrue(pblockMap.containsKey("pblock_base_mb_i"));
        Assertions.assertTrue(pblockMap.containsKey("pblock_u_ila_0"));

        // Check for the property and cooresponding TclConstraints
        String TclConstraints;
        PBlock dbgHub = pblockMap.get("pblock_dbg_hub");
        Assertions.assertTrue(dbgHub.containRouting());
        Assertions.assertTrue(dbgHub.isSoft());
        Assertions.assertFalse(dbgHub.excludePlacement());
        TclConstraints = String.join(" ", dbgHub.getTclConstraints());
        Assertions.assertTrue(
            TclConstraints.contains(PblockProperty.CONTAIN_ROUTING.toString())
            && TclConstraints.contains(PblockProperty.IS_SOFT.toString())
            && !TclConstraints.contains(PblockProperty.EXCLUDE_PLACEMENT.toString())
        );

        PBlock baseMb = pblockMap.get("pblock_base_mb_i");
        Assertions.assertTrue(baseMb.containRouting());
        Assertions.assertFalse(baseMb.isSoft());
        Assertions.assertFalse(baseMb.excludePlacement());
        TclConstraints = String.join(" ", baseMb.getTclConstraints());
        Assertions.assertTrue(
            TclConstraints.contains(PblockProperty.CONTAIN_ROUTING.toString())
            && !TclConstraints.contains(PblockProperty.IS_SOFT.toString())
            && !TclConstraints.contains(PblockProperty.EXCLUDE_PLACEMENT.toString())
        );

        PBlock uila0 = pblockMap.get("pblock_u_ila_0");
        Assertions.assertTrue(uila0.containRouting());
        Assertions.assertFalse(uila0.isSoft());
        Assertions.assertTrue(uila0.excludePlacement());
        TclConstraints = String.join(" ", uila0.getTclConstraints());
        Assertions.assertTrue(
            TclConstraints.contains(PblockProperty.CONTAIN_ROUTING.toString())
            && !TclConstraints.contains(PblockProperty.IS_SOFT.toString())
            && TclConstraints.contains(PblockProperty.EXCLUDE_PLACEMENT.toString())
        );
    }

    @Test
    public void testAddBlackBoxConstraints() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        top.createPort("pad", EDIFDirection.OUTPUT, 1);
        top.createPort("bus[1:0]", EDIFDirection.OUTPUT, 2);
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell subType = new EDIFCell(circuit.getNetlist().getWorkLibrary(), "subType");
        subType.createChildCellInst("y", circuit.getNetlist().getHDIPrimitive(Unisim.FDRE));
        subType.createCellInst("sub", circuit.getTopEDIFCell());

        circuit.addXDCConstraint(ConstraintGroup.NORMAL, "add_cells_to_pblock [get_pblocks pblock_rp] -top");
        circuit.addXDCConstraint(ConstraintGroup.EARLY,
                "set_property src_info {type:XDC file:1 line:1 export:INPUT save:NONE scope:NONE} [current_design]",
                "create_pblock pblock_ctx",
                "set_property src_info {type:XDC file:2 line:3 export:INPUT save:INPUT scope:NONE} [current_design]",
                "set_property IOSTANDARD LVCMOS18 [get_ports {pad bus[1]}]",
                "set_property MAX_FANOUT 10 [get_nets n]",
                "add_cells_to_pblock [get_pblocks pblock_rp] -top",
                "resize_pblock [get_pblocks pblock_rp] -add CLOCKREGION_X0Y0",
                "set_property HD.RECONFIGURABLE true [current_design]",
                "current_instance shell_ip",
                "set_property KEEP true [get_cells x]",
                "current_instance -quiet",
                "current_instance sub",
                "set_property DONT_TOUCH true [get_cells y]",
                "current_instance -quiet",
                "set_property SRC_FILE_INFO {cfile:a.xdc rfile:a.xdc id:1} [current_design]",
                "set_property MAX_FANOUT 5 \\",
                "    [get_nets m]");
        circuit.addXDCConstraint(ConstraintGroup.LATE, "set_property IOSTANDARD LVCMOS12 [get_ports local]");
        circuit.addXDCConstraint(ConstraintGroup.IN_CONTEXT, "create_clock -period 10 [get_ports clk]");

        Map<String, Design> blackBoxes = Collections.singletonMap("rp", circuit);
        DesignTools.populateBlackBox(design, blackBoxes, false);
        Assertions.assertEquals(5, ConstraintTools.addBlackBoxConstraints(design, blackBoxes));

        List<String> early = Arrays.asList(
                "current_instance rp",
                // On the design's top-level ports, so at its top scope
                "current_instance -quiet",
                "set_property src_info {type:XDC file:2 line:3 export:INPUT save:INPUT scope:NONE} [current_design]",
                "set_property IOSTANDARD LVCMOS18 [get_ports {pad bus[1]}]",
                "current_instance rp",
                "set_property MAX_FANOUT 10 [get_nets n]",
                // The block scoped to shell_ip, which is not in the black box, is gone
                "current_instance -quiet",
                "current_instance rp",
                "current_instance -quiet",
                "current_instance rp/sub",
                "set_property DONT_TOUCH true [get_cells y]",
                "current_instance -quiet",
                "current_instance rp",
                "set_property MAX_FANOUT 5 \\",
                "    [get_nets m]",
                "current_instance -quiet");
        Assertions.assertEquals(early, design.getXDCConstraints(ConstraintGroup.EARLY));
        Assertions.assertEquals(Arrays.asList(
                "current_instance rp",
                "set_property IOSTANDARD LVCMOS12 [get_ports local]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.LATE));
        Assertions.assertTrue(design.getXDCConstraints(ConstraintGroup.NORMAL).isEmpty());
        Assertions.assertTrue(design.getXDCConstraints(ConstraintGroup.IN_CONTEXT).isEmpty());
    }
}
