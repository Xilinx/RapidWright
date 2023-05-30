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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.Unisim;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestEDIFHierNet {

    @Test
    public void testGetLeafHierPortInsts() {
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
}
