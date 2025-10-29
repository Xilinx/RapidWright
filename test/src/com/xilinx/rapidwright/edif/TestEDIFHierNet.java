/*
 * Copyright (c) 2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.edif;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFHierNet {

    private <T> void assertEqualsAnyOrder(Collection<T> expected, Collection<T> actual) {
        Assertions.assertEquals(new HashSet<>(expected), new HashSet<>(actual));
    }

    @Test
    public void testGetLeafHierPortInsts() {
        //////// Test using existing DCP ////////
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");

        EDIFNetlist netlist = design.getNetlist();

        for (EDIFHierNet parentNet : netlist.getParentNetMap().values()) {
            Set<EDIFHierPortInst> goldSet = new HashSet<>(netlist.getPhysicalPins(parentNet));
            Set<EDIFHierPortInst> testSet = new HashSet<>(parentNet.getLeafHierPortInsts());
            Assertions.assertEquals(goldSet.size(), testSet.size());

            for (EDIFHierPortInst portInst : goldSet) {
                Assertions.assertTrue(testSet.remove(portInst));
            }
            Assertions.assertTrue(testSet.isEmpty());
        }

        //////// Test with from-scratch netlist ////////
        EDIFNetlist testTopLevelPinsNetlist = EDIFTools.createNewNetlist("topLevelPins");

        ///// Create inner
        EDIFCell inner = new EDIFCell(testTopLevelPinsNetlist.getTopCell().getLibrary(), "inner");
        EDIFPort inner_i = new EDIFPort("i", EDIFDirection.INPUT, 1);
        EDIFPort inner_o1 = new EDIFPort("o1", EDIFDirection.OUTPUT, 1);
        EDIFPort inner_o2 = new EDIFPort("o2", EDIFDirection.OUTPUT, 1);
        inner.addPort(inner_i);
        inner.addPort(inner_o1);
        inner.addPort(inner_o2);
        EDIFCellInst inv = new EDIFCellInst("inv", Design.getUnisimCell(Unisim.LUT1), inner);
        EDIFTools.ensureCellInLibraries(testTopLevelPinsNetlist, inv.getCellType());
        LUTTools.configureLUT(inv, "O=!I0");
        inner.addCellInst(inv);
        EDIFNet inner_i_to_o1_and_inv = new EDIFNet("inner_i_to_o1_and_inv", inner);
        EDIFNet inner_inv_to_o2 = new EDIFNet("inner_inv_to_o2", inner);

        // First net conns
        new EDIFPortInst(inner_i, inner_i_to_o1_and_inv, null);
        new EDIFPortInst(inner_o1, inner_i_to_o1_and_inv, null);
        new EDIFPortInst(inv.getPort("I0"), inner_i_to_o1_and_inv, inv);

        // Second net conns
        new EDIFPortInst(inv.getPort("O"), inner_inv_to_o2, inv);
        new EDIFPortInst(inner_o2, inner_inv_to_o2, null);

        ///// Create top
        EDIFCell top = testTopLevelPinsNetlist.getTopCell();
        EDIFPort top_i = new EDIFPort("i", EDIFDirection.INPUT, 1);
        EDIFPort top_o1 = new EDIFPort("o1", EDIFDirection.OUTPUT, 1);
        EDIFPort top_o2 = new EDIFPort("o2", EDIFDirection.OUTPUT, 1);
        top.addPort(top_i);
        top.addPort(top_o1);
        top.addPort(top_o2);
        EDIFCellInst innerInst = new EDIFCellInst("inner", inner, top);
        top.addCellInst(innerInst);

        EDIFNet top_i_to_i = new EDIFNet("top_i_to_i", top);
        EDIFNet top_o1_to_o1 = new EDIFNet("top_o1_to_o1", top);
        EDIFNet top_o2_to_o2 = new EDIFNet("top_o2_to_o2", top);

        // First net conns
        new EDIFPortInst(top_i, top_i_to_i, null);
        new EDIFPortInst(inner_i, top_i_to_i, innerInst);

        // Second net conns
        new EDIFPortInst(top_o1, top_o1_to_o1, null);
        new EDIFPortInst(inner_o1, top_o1_to_o1, innerInst);

        // Third net conns
        new EDIFPortInst(top_o2, top_o2_to_o2, null);
        new EDIFPortInst(inner_o2, top_o2_to_o2, innerInst);

        EDIFHierNet h_top_i_to_i = testTopLevelPinsNetlist.getTopHierCellInst().getNet("top_i_to_i");
        EDIFHierNet h_top_o1_to_o1 = testTopLevelPinsNetlist.getTopHierCellInst().getNet("top_o1_to_o1");
        EDIFHierNet h_top_o2_to_o2 = testTopLevelPinsNetlist.getTopHierCellInst().getNet("top_o2_to_o2");
        EDIFHierNet h_inner_i_to_o1_and_inv = testTopLevelPinsNetlist.getTopHierCellInst().getChild("inner").getNet("inner_i_to_o1_and_inv");
        EDIFHierNet h_inner_inv_to_o2 = testTopLevelPinsNetlist.getTopHierCellInst().getChild("inner").getNet("inner_inv_to_o2");

        assertEqualsAnyOrder(
            Arrays.asList("i", "o1", "inner/inv/I0"),
            h_top_i_to_i.getLeafHierPortInsts(true, true, true).stream().map(hPI -> hPI.toString()).collect(Collectors.toList())
        );

        assertEqualsAnyOrder(
            Arrays.asList("inner/inv/I0"),
            h_top_i_to_i.getLeafHierPortInsts().stream().map(hPI -> hPI.toString()).collect(Collectors.toList())
        );

        assertEqualsAnyOrder(
            h_top_i_to_i.getLeafHierPortInsts(true, true, true),
            h_inner_i_to_o1_and_inv.getLeafHierPortInsts(true, true, true)
        );

        assertEqualsAnyOrder(
            h_top_i_to_i.getLeafHierPortInsts(true, true, true),
            h_top_o1_to_o1.getLeafHierPortInsts(true, true, true)
        );

        assertEqualsAnyOrder(
            Arrays.asList("o2", "inner/inv/O"),
            h_inner_inv_to_o2.getLeafHierPortInsts(true, true, true).stream().map(hPI -> hPI.toString()).collect(Collectors.toList())
        );

        assertEqualsAnyOrder(
            h_inner_inv_to_o2.getLeafHierPortInsts(true, true, true),
            h_top_o2_to_o2.getLeafHierPortInsts(true, true, true)
        );

        assertEqualsAnyOrder(
            Arrays.asList("inner/inv/I0", "o1"),
            h_top_i_to_i.getLeafHierPortInsts(false, true, true).stream().map(hPI -> hPI.toString()).collect(Collectors.toList())
        );

        assertEqualsAnyOrder(
            h_top_i_to_i.getLeafHierPortInsts(false, true, true),
            h_top_o1_to_o1.getLeafHierPortInsts(false, true, true)
        );

        assertEqualsAnyOrder(
            Arrays.asList("inner/inv/O"),
            h_inner_inv_to_o2.getLeafHierPortInsts(true, false, true).stream().map(hPI -> hPI.toString()).collect(Collectors.toList())
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetLeafHierPortInstsInout(boolean blackbox) {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");

        EDIFCell cell;
        EDIFCell top = netlist.getTopCell();
        String portName = "IO";
        if (blackbox) {
            cell = new EDIFCell(netlist.getWorkLibrary(), "blackbox");
            cell.createPort(portName, EDIFDirection.INOUT, 1);
        } else {
            cell = Design.getUnisimCell(Unisim.IOBUF);
        }
        Assertions.assertTrue(cell.isLeafCellOrBlackBox());

        EDIFCellInst inst = top.createChildCellInst("inst", cell);

        EDIFNet net = top.createNet("net");
        EDIFPort port = cell.getPort(portName);
        // INOUT ports return false for both
        Assertions.assertFalse(port.isInput());
        Assertions.assertFalse(port.isOutput());

        EDIFPortInst epi = net.createPortInst(port);
        // INOUT port insts return false for both
        Assertions.assertFalse(epi.isInput());
        Assertions.assertFalse(epi.isOutput());

        Assertions.assertEquals(1, net.getPortInsts().size());

        EDIFHierNet ehn = netlist.getHierNetFromName("net");
        List<EDIFHierPortInst> leaves = ehn.getLeafHierPortInsts(true, true);

        // INOUT leaf ports insts are not currently collected
        Assertions.assertTrue(leaves.isEmpty());
    }

    @Test
    public void testGetPortInsts() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        HashSet<EDIFPortInst> portInsts = new HashSet<>();
        for (EDIFHierNet parentNet : netlist.getParentNetMap().values()) {
            EDIFHierCellInst parentInst = parentNet.getHierarchicalInst();
            portInsts.addAll(parentNet.getNet().getPortInsts());
            for (EDIFHierPortInst portInst : parentNet.getPortInsts()) {
                Assertions.assertEquals(parentInst, portInst.getHierarchicalInst());
                Assertions.assertTrue(portInsts.remove(portInst.getPortInst()));
            }
            Assertions.assertTrue(portInsts.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetSourcePortInsts(boolean includeTopLevelPorts) {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        HashSet<EDIFPortInst> portInsts = new HashSet<>();
        for (EDIFHierNet parentNet : netlist.getParentNetMap().values()) {
            EDIFHierCellInst parentInst = parentNet.getHierarchicalInst();
            portInsts.addAll(parentNet.getNet().getSourcePortInsts(includeTopLevelPorts));
            for (EDIFHierPortInst portInst : parentNet.getSourcePortInsts(includeTopLevelPorts)) {
                Assertions.assertTrue(portInst.isOutput() || parentInst.isTopLevelInst());
                Assertions.assertEquals(parentInst, portInst.getHierarchicalInst());
                Assertions.assertTrue(portInsts.remove(portInst.getPortInst()));
            }
            Assertions.assertTrue(portInsts.isEmpty());
        }
    }

    @Test
    public void testIsAlias() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        Map<EDIFHierNet, EDIFHierNet> parentNetMap = netlist.getParentNetMap();

        for (Entry<EDIFHierNet, EDIFHierNet> e : parentNetMap.entrySet()) {
            Assertions.assertTrue(e.getKey().isAlias(e.getValue()));
            Assertions.assertTrue(e.getValue().isAlias(e.getKey()));
            for (Entry<EDIFHierNet, EDIFHierNet> e2 : parentNetMap.entrySet()) {
                if (e.getValue().equals(e2.getValue())) {
                    continue;
                }
                Assertions.assertFalse(e.getKey().isAlias(e2.getKey()));
            }
        }
    }
}
