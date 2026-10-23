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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PblockProperty;
import com.xilinx.rapidwright.design.xdc.parser.XDCTools;
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
        circuit.addXDCConstraint(ConstraintGroup.LATE,
                "set_property IOSTANDARD LVCMOS12 [get_ports local]",
                "set_false_path -from [get_clocks -of [get_ports clk]] -through [get_pins sub/y/Q]",
                "set_property KEEP true [get_ports -scoped_to_current_instance already]",
                "current_instance sub",
                "set_false_path -through [get_ports -scoped_to_current_instance -no_traverse src_arst]",
                "set_property DONT_TOUCH true [get_ports p]",
                "current_instance -quiet");
        circuit.addXDCConstraint(ConstraintGroup.IN_CONTEXT, "create_clock -period 10 [get_ports clk]");

        // populateBlackBox() adds the constraints itself
        Map<String, Design> blackBoxes = Collections.singletonMap("rp", circuit);
        DesignTools.populateBlackBox(design, blackBoxes, false);

        List<String> early = Arrays.asList(
                // On the design's top-level ports, so at its top scope
                "set_property src_info {type:XDC file:2 line:3 export:INPUT save:INPUT scope:NONE} [current_design]",
                "set_property IOSTANDARD LVCMOS18 [get_ports {pad bus[1]}]",
                "current_instance rp",
                "set_property MAX_FANOUT 10 [get_nets n]",
                // The block scoped to shell_ip, which is not in the black box, is gone, scope and all
                "current_instance -quiet",
                "current_instance rp/sub",
                "set_property DONT_TOUCH true [get_cells y]",
                "current_instance -quiet",
                "current_instance rp",
                "set_property MAX_FANOUT 5 \\",
                "    [get_nets m]",
                "current_instance -quiet");
        // At the circuit's top scope, get_ports of the circuit's own ports is told to look in the
        // current instance; in a scope nested below it, get_ports is left as it is
        List<String> late = Arrays.asList(
                "current_instance rp",
                "set_property IOSTANDARD LVCMOS12 [get_ports -scoped_to_current_instance local]",
                "set_false_path -from [get_clocks -of [get_ports -scoped_to_current_instance clk]] -through [get_pins sub/y/Q]",
                "set_property KEEP true [get_ports -scoped_to_current_instance already]",
                "current_instance -quiet",
                "current_instance rp/sub",
                "set_false_path -through [get_ports -scoped_to_current_instance -no_traverse src_arst]",
                "set_property DONT_TOUCH true [get_ports p]",
                "current_instance -quiet");
        Assertions.assertEquals(early, design.getXDCConstraints(ConstraintGroup.EARLY));
        Assertions.assertEquals(late, design.getXDCConstraints(ConstraintGroup.LATE));
        Assertions.assertTrue(design.getXDCConstraints(ConstraintGroup.NORMAL).isEmpty());
        Assertions.assertTrue(design.getXDCConstraints(ConstraintGroup.IN_CONTEXT).isEmpty());

        // For a design whose black boxes were filled some other way, called directly, it adds the
        // same, counting the circuits' commands it kept
        design.getXDCConstraints(ConstraintGroup.EARLY).clear();
        design.getXDCConstraints(ConstraintGroup.LATE).clear();
        Assertions.assertEquals(9, ConstraintTools.addBlackBoxConstraints(design, blackBoxes));
        Assertions.assertEquals(early, design.getXDCConstraints(ConstraintGroup.EARLY));
        Assertions.assertEquals(late, design.getXDCConstraints(ConstraintGroup.LATE));
    }

    /**
     * current_instance is relative to the current instance, as in Vivado.
     */
    @Test
    public void testAddBlackBoxConstraintsRelativeScopes() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell innerType = new EDIFCell(circuit.getNetlist().getWorkLibrary(), "innerType");
        innerType.createChildCellInst("z", circuit.getNetlist().getHDIPrimitive(Unisim.FDRE));
        EDIFCell subType = new EDIFCell(circuit.getNetlist().getWorkLibrary(), "subType");
        subType.createChildCellInst("y", circuit.getNetlist().getHDIPrimitive(Unisim.FDRE));
        innerType.createCellInst("inner", subType);
        subType.createCellInst("sub", circuit.getTopEDIFCell());

        circuit.addXDCConstraint(ConstraintGroup.EARLY,
                "current_instance sub",
                "current_instance inner",
                "set_property KEEP true [get_cells z]",
                "current_instance ..",
                "set_property DONT_TOUCH true [get_cells y]",
                // Up to the circuit's top, then out of it
                "current_instance ..",
                "current_instance ..",
                "set_property KEEP true [get_cells q]",
                "current_instance -quiet",
                "current_instance -quiet sub",
                "set_property ASYNC_REG true [get_cells y]",
                "current_instance",
                "set_property MAX_FANOUT 2 [get_nets n]");

        DesignTools.populateBlackBox(design, Collections.singletonMap("rp", circuit), false);

        Assertions.assertEquals(Arrays.asList(
                "current_instance rp/sub/inner",
                "set_property KEEP true [get_cells z]",
                "current_instance -quiet",
                "current_instance rp/sub",
                "set_property DONT_TOUCH true [get_cells y]",
                // Still in rp/sub, so no scope is written
                "set_property ASYNC_REG true [get_cells y]",
                "current_instance -quiet",
                "current_instance rp",
                "set_property MAX_FANOUT 2 [get_nets n]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.EARLY));
    }

    /**
     * Only a command on device pins alone is applied at the design's top scope.
     */
    @Test
    public void testAddBlackBoxConstraintsDevicePins() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        top.createPort("pad", EDIFDirection.OUTPUT, 1);
        top.createPort("clk", EDIFDirection.INPUT, 1);
        top.createPort("din", EDIFDirection.INPUT, 1);
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("clk", EDIFDirection.INPUT, 1);
        rpType.createPort("din", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);
        // The box's din is on the device pin din; its clk is not on the device pin clk
        EDIFNet din = top.createNet("din");
        din.createPortInst(top.getPort("din"));
        din.createPortInst("din", rp);

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("clk", EDIFDirection.INPUT, 1);
        circuit.getTopEDIFCell().createPort("din", EDIFDirection.INPUT, 1);
        circuit.addXDCConstraint(ConstraintGroup.EARLY,
                "set_property IOSTANDARD LVCMOS18 [get_ports pad]",
                // Also names a cell, which is in the circuit
                "set_false_path -from [get_ports pad] -to [get_cells x]",
                // The circuit's clk port, not the device pin of the same name
                "set_input_delay 1 [get_ports clk]",
                "set_property IOSTANDARD LVCMOS18 [get_ports din]",
                // A device pin the circuit also has a port of, with a pin of the circuit
                "set_max_delay 3 -from [get_ports din] -to [get_pins y/D]",
                // A device pin and a port of the circuit
                "set_false_path -from [get_ports din] -to [get_ports clk]");

        DesignTools.populateBlackBox(design, Collections.singletonMap("rp", circuit), false);

        Assertions.assertEquals(Arrays.asList(
                "set_property IOSTANDARD LVCMOS18 [get_ports pad]",
                "current_instance rp",
                // In the box, but get_ports of device pins still finds them, and the box's pins
                // would not be valid timing startpoints
                "set_false_path -from [get_ports pad] -to [get_cells x]",
                "set_input_delay 1 [get_ports -scoped_to_current_instance clk]",
                "current_instance -quiet",
                "set_property IOSTANDARD LVCMOS18 [get_ports din]",
                "current_instance rp",
                "set_max_delay 3 -from [get_ports din] -to [get_pins y/D]",
                "set_false_path -from [get_ports din] -to [get_ports -scoped_to_current_instance clk]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.EARLY));
    }

    /**
     * A get_ports that names both device pins and ports of the circuit is split in two, and
     * patterns count by the ports they match.
     */
    @Test
    public void testAddBlackBoxConstraintsMixedGetPorts() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        top.createPort("pad", EDIFDirection.OUTPUT, 1);
        top.createPort("clk", EDIFDirection.INPUT, 1);
        top.createPort("din", EDIFDirection.INPUT, 1);
        top.createPort("bus[1:0]", EDIFDirection.INPUT, 2);
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("clk", EDIFDirection.INPUT, 1);
        rpType.createPort("din", EDIFDirection.INPUT, 1);
        rpType.createPort("clk0", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);
        // The box's din is on the device pin din; its clk is not on the device pin clk
        EDIFNet din = top.createNet("din");
        din.createPortInst(top.getPort("din"));
        din.createPortInst("din", rp);

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("clk", EDIFDirection.INPUT, 1);
        circuit.getTopEDIFCell().createPort("din", EDIFDirection.INPUT, 1);
        circuit.getTopEDIFCell().createPort("clk0", EDIFDirection.INPUT, 1);
        circuit.addXDCConstraint(ConstraintGroup.EARLY,
                // Both kinds in one get_ports
                "set_max_delay 2 -from [get_ports {pad clk}] -to [get_pins y/D]",
                "set_false_path -from [get_ports -quiet {din clk}]",
                // Patterns that only match device pins
                "set_max_delay 3 -from [get_ports pa*] -to [get_pins y/D]",
                "set_max_delay 4 -from [get_ports -regexp {pa.}] -to [get_pins y/D]",
                // Patterns that match both
                "set_false_path -to [get_ports *]",
                "set_false_path -to [get_ports -regexp {[cp].*}]",
                // A pattern that only matches the circuit's port
                "set_property KEEP true [get_ports -regexp -nocase {C.K}]",
                // A list continued onto the next line
                "set_max_delay 5 -from [get_ports {pad \\",
                "    din}] -to [get_pins y/D]",
                // Tcl's own reading: a list with a braced element, a quoted option, and a command
                // name inside a braced pattern, which is not a command
                "set_max_delay 6 -from [get_ports {{pad} clk}] -to [get_pins y/D]",
                "set_max_delay 7 -from [get_ports \"-regexp\" {pa.}] -to [get_pins y/D]",
                "set_false_path -from [get_ports -regexp {[get_ports]}] -to [get_pins y/D]",
                // A bus's declared name, bus[1:0], is not matched by patterns
                "set_false_path -to [get_ports *0*]",
                // Without . [ * + or ?, Vivado takes a -regexp pattern as a name, and with an
                // alternation outside parentheses, it matches nothing
                "set_false_path -to [get_ports -regexp {^pad$ clk}]",
                "set_false_path -to [get_ports -regexp {pa.|c.k}]",
                "set_property IOSTANDARD LVCMOS18 [get_ports pa?]");

        DesignTools.populateBlackBox(design, Collections.singletonMap("rp", circuit), false);

        Assertions.assertEquals(Arrays.asList(
                "current_instance rp",
                "set_max_delay 2 -from [concat [get_ports pad] [get_ports -scoped_to_current_instance clk]] -to [get_pins y/D]",
                "set_false_path -from [concat [get_ports -quiet din] [get_ports -scoped_to_current_instance -quiet clk]]",
                "set_max_delay 3 -from [get_ports pa*] -to [get_pins y/D]",
                "set_max_delay 4 -from [get_ports -regexp {pa.}] -to [get_pins y/D]",
                // Split, a pattern is replaced by the names it matches, and -regexp is dropped
                "set_false_path -to [concat [get_ports {bus[0] bus[1] din pad}] [get_ports -scoped_to_current_instance {clk clk0}]]",
                "set_false_path -to [concat [get_ports pad] [get_ports -scoped_to_current_instance {clk clk0}]]",
                "set_property KEEP true [get_ports -scoped_to_current_instance -regexp -nocase {C.K}]",
                "set_max_delay 5 -from [get_ports {pad \\",
                "    din}] -to [get_pins y/D]",
                "set_max_delay 6 -from [concat [get_ports pad] [get_ports -scoped_to_current_instance clk]] -to [get_pins y/D]",
                "set_max_delay 7 -from [get_ports \"-regexp\" {pa.}] -to [get_pins y/D]",
                "set_false_path -from [get_ports -scoped_to_current_instance -regexp {[get_ports]}] -to [get_pins y/D]",
                "set_false_path -to [concat [get_ports {bus[0]}] [get_ports -scoped_to_current_instance clk0]]",
                "set_false_path -to [get_ports -scoped_to_current_instance -regexp {^pad$ clk}]",
                "set_false_path -to [get_ports -scoped_to_current_instance -regexp {pa.|c.k}]",
                "current_instance -quiet",
                "set_property IOSTANDARD LVCMOS18 [get_ports pa?]"), design.getXDCConstraints(ConstraintGroup.EARLY));
    }

    /**
     * NORMAL constraints are not added, with a warning if they hold more than pblocks and the
     * context's constraints.
     */
    @Test
    public void testAddBlackBoxConstraintsNormalWarning() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("I", EDIFDirection.INPUT, 1);
        circuit.addXDCConstraint(ConstraintGroup.NORMAL,
                "set_property src_info {type:XDC file:1 line:1 export:INPUT save:NONE scope:NONE} [current_design]",
                "create_pblock pblock_rp",
                "add_cells_to_pblock [get_pblocks pblock_rp] [get_cells -quiet [list a b]]",
                "resize_pblock [get_pblocks pblock_rp] -add CLOCKREGION_X0Y0",
                "set_property IS_SOFT FALSE [get_pblocks pblock_rp]",
                "create_clock -period 10 [get_ports I]");

        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            DesignTools.populateBlackBox(design, Collections.singletonMap("rp", circuit), false);
        } finally {
            System.setOut(stdout);
        }
        Assertions.assertTrue(captured.toString().contains("WARNING: 1 NORMAL constraint command(s)"),
                captured.toString());
        for (ConstraintGroup cg : ConstraintGroup.values()) {
            Assertions.assertTrue(design.getXDCConstraints(cg).isEmpty());
        }
    }

    @Test
    public void testQuotedName() {
        Assertions.assertEquals("a/b_0", XDCTools.quotedName("a/b_0"));
        Assertions.assertEquals("{a/b[0].c}", XDCTools.quotedName("a/b[0].c"));
        Assertions.assertEquals("a\\{b\\[0\\]", XDCTools.quotedName("a{b[0]"));
        for (String name : Arrays.asList("a/b_0", "a/b[0].c", "a{b[0]", "a b")) {
            Assertions.assertEquals(name, XDCTools.unquotedName(XDCTools.quotedName(name)));
        }
    }

    /**
     * The design's own constraints can end with a cell as the current instance. The black box's
     * constraints then start by returning to the top, since its name is relative to the design's top.
     */
    @Test
    public void testAddBlackBoxConstraintsAfterDesignScope() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);
        design.addXDCConstraint(ConstraintGroup.EARLY,
                "current_instance shell_ip",
                "set_property KEEP true [get_cells x]");

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("I", EDIFDirection.INPUT, 1);
        circuit.addXDCConstraint(ConstraintGroup.EARLY, "set_property MAX_FANOUT 10 [get_nets n]");
        circuit.addXDCConstraint(ConstraintGroup.LATE, "set_property MAX_FANOUT 5 [get_nets m]");

        DesignTools.populateBlackBox(design, Collections.singletonMap("rp", circuit), false);

        Assertions.assertEquals(Arrays.asList(
                "current_instance shell_ip",
                "set_property KEEP true [get_cells x]",
                "current_instance -quiet",
                "current_instance rp",
                "set_property MAX_FANOUT 10 [get_nets n]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.EARLY));
        // Nothing to return from in a group the design has no constraints in
        Assertions.assertEquals(Arrays.asList(
                "current_instance rp",
                "set_property MAX_FANOUT 5 [get_nets m]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.LATE));
    }

    @Test
    public void testAddBlackBoxConstraintsTclNames() {
        Design design = new Design("shell", "xcvu3p");
        EDIFCell top = design.getTopEDIFCell();
        EDIFCell rpType = new EDIFCell(top.getLibrary(), "rpType");
        rpType.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCellInst rp = rpType.createCellInst("rp[0]", top);
        rp.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);

        Design circuit = new Design("circuit", "xcvu3p");
        circuit.getTopEDIFCell().createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell subType = new EDIFCell(circuit.getNetlist().getWorkLibrary(), "subType");
        subType.createChildCellInst("y", circuit.getNetlist().getHDIPrimitive(Unisim.FDRE));
        subType.createCellInst("sub[1].u", circuit.getTopEDIFCell());

        circuit.addXDCConstraint(ConstraintGroup.EARLY,
                // Continued over two lines, but still the partition's pblock
                "add_cells_to_pblock [get_pblocks pblock_rp] \\",
                "    -top",
                "resize_pblock [get_pblocks pblock_rp] -add CLOCKREGION_X0Y0",
                "current_instance {sub[1].u}",
                "set_property DONT_TOUCH true [get_cells y]",
                "current_instance -quiet",
                "set_property MAX_FANOUT 10 [get_nets n]");

        DesignTools.populateBlackBox(design, Collections.singletonMap("rp[0]", circuit), false);

        // Names with [ ] in them are braced, as Vivado writes them, so that Tcl takes them as is
        Assertions.assertEquals(Arrays.asList(
                "current_instance {rp[0]/sub[1].u}",
                "set_property DONT_TOUCH true [get_cells y]",
                "current_instance -quiet",
                "current_instance {rp[0]}",
                "set_property MAX_FANOUT 10 [get_nets n]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.EARLY));
    }

    /**
     * get_ports is told apart from its options in any order, abbreviated, with values, and over
     * continued lines.
     */
    @Test
    public void testAddBlackBoxConstraintsGetPortsOptions() {
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
        circuit.addXDCConstraint(ConstraintGroup.LATE,
                "set_property KEEP true [get_ports -quiet -scoped_to_current_instance a]",
                "set_property KEEP true [get_ports -sco b]",
                "set_property IOSTANDARD LVCMOS18 [get_ports -nocase pad]",
                "set_property KEEP true [get_ports -of_objects [get_nets n]]",
                "set_property PULLDOWN true [get_ports -filter {DIRECTION == OUT} -quiet {bus[0]}]",
                "set_property KEEP true [get_ports \\",
                "    -scoped_to_current_instance d]",
                "set_false_path -to [get_ports \\",
                "    e]");

        DesignTools.populateBlackBox(design, Collections.singletonMap("rp", circuit), false);

        Assertions.assertEquals(Arrays.asList(
                "current_instance rp",
                // Already scoped, even if not first or abbreviated
                "set_property KEEP true [get_ports -quiet -scoped_to_current_instance a]",
                "set_property KEEP true [get_ports -sco b]",
                // Options before the names of top-level ports
                "current_instance -quiet",
                "set_property IOSTANDARD LVCMOS18 [get_ports -nocase pad]",
                "current_instance rp",
                // No names, so the circuit's ports
                "set_property KEEP true [get_ports -scoped_to_current_instance -of_objects [get_nets n]]",
                // An option's value is not a name
                "current_instance -quiet",
                "set_property PULLDOWN true [get_ports -filter {DIRECTION == OUT} -quiet {bus[0]}]",
                "current_instance rp",
                // Options on the next line
                "set_property KEEP true [get_ports \\",
                "    -scoped_to_current_instance d]",
                "set_false_path -to [get_ports -scoped_to_current_instance \\",
                "    e]",
                "current_instance -quiet"), design.getXDCConstraints(ConstraintGroup.LATE));
    }
}
