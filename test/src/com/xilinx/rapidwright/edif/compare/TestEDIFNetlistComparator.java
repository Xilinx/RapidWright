/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.edif.compare;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFNetlistComparator {

    private void checkSingleDiffType(EDIFNetlist gold, EDIFNetlist test, EDIFDiffType type,
            EDIFNetlistComparator comparator) {
        int diffs = comparator.compareNetlists(gold, test);
        Assertions.assertEquals(1, diffs);
        Assertions.assertEquals(1, comparator.getDiffMap().size());
        Assertions.assertEquals(type, comparator.getDiffMap().keySet().iterator().next());
    }

    private void checkNetlistMatch(EDIFNetlist gold, EDIFNetlist test,
            EDIFNetlistComparator comparator) {
        int diffs = comparator.compareNetlists(gold, test);
        Assertions.assertEquals(0, diffs);
    }

    @Test
    public void testEDIFNetlistComparator() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Design design2 = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");

        EDIFNetlist gold = design.getNetlist();
        EDIFNetlist test = design2.getNetlist();
        EDIFCell testTop = test.getTopCell();

        EDIFNetlistComparator comparator = new EDIFNetlistComparator();
        checkNetlistMatch(gold, test, comparator);

        EDIFHierPortInst portInst = test.getHierPortInstFromName(
                "processor/data_path_loop[0].lsb_arith_logical.arith_logical_muxcy_CARRY4_CARRY8_LUT6CY_0/I4");
        EDIFNet net = portInst.getNet();
        net.removePortInst(portInst.getPortInst());
        checkSingleDiffType(gold, test, EDIFDiffType.NET_PORT_INST_MISSING, comparator);

        net.addPortInst(portInst.getPortInst());
        checkNetlistMatch(gold, test, comparator);

        EDIFPortInst extraPortInst = new EDIFPortInst(testTop.getPort("clk"), net);
        checkSingleDiffType(gold, test, EDIFDiffType.NET_PORT_INST_EXTRA, comparator);

        net.removePortInst(extraPortInst);
        checkNetlistMatch(gold, test, comparator);

        net.getParentCell().removeNet(net);
        checkSingleDiffType(gold, test, EDIFDiffType.NET_MISSING, comparator);

        net.getParentCell().addNet(net);
        checkNetlistMatch(gold, test, comparator);

        EDIFNet extraNet = test.getTopCell().createNet("extra");
        checkSingleDiffType(gold, test, EDIFDiffType.NET_EXTRA, comparator);

        test.getTopCell().removeNet(extraNet);
        checkNetlistMatch(gold, test, comparator);

        test.getWorkLibrary().removeCell(testTop);
        checkSingleDiffType(gold, test, EDIFDiffType.CELL_MISSING, comparator);

        test.getWorkLibrary().addCell(testTop);
        checkNetlistMatch(gold, test, comparator);

        EDIFCell extraCell = new EDIFCell(testTop.getLibrary(), testTop, "extraCell");
        checkSingleDiffType(gold, test, EDIFDiffType.CELL_EXTRA, comparator);

        test.getWorkLibrary().removeCell(extraCell);
        checkNetlistMatch(gold, test, comparator);

        EDIFCellInst inst = testTop.removeCellInst("your_program");
        checkSingleDiffType(gold, test, EDIFDiffType.INST_MISSING, comparator);

        testTop.addCellInst(inst);
        checkNetlistMatch(gold, test, comparator);

        EDIFCellInst extraInst = test.getHDIPrimitivesLibrary().getCell("LUT6")
                .createCellInst("extraLUTInst", testTop);
        checkSingleDiffType(gold, test, EDIFDiffType.INST_EXTRA, comparator);

        testTop.removeCellInst(extraInst);
        checkNetlistMatch(gold, test, comparator);

        EDIFPort port = testTop.getPort("clk");
        testTop.removePort(port);
        checkSingleDiffType(gold, test, EDIFDiffType.PORT_MISSING, comparator);

        testTop.addPort(port);
        checkNetlistMatch(gold, test, comparator);

        EDIFPort extraPort = testTop.createPort("extraPort", EDIFDirection.OUTPUT, 1);
        checkSingleDiffType(gold, test, EDIFDiffType.PORT_EXTRA, comparator);

        testTop.removePort(extraPort);
        checkNetlistMatch(gold, test, comparator);

        comparator.filterVivadoChanges = false;
        String propKey = testTop.getPropertiesMap().keySet().iterator().next();
        EDIFPropertyValue prop = testTop.removeProperty(propKey);
        checkSingleDiffType(gold, test, EDIFDiffType.PROPERTY_MISSING, comparator);

        testTop.addProperty(propKey, prop);
        checkNetlistMatch(gold, test, comparator);

        propKey = "extraProp";
        testTop.addProperty(propKey, true);
        checkSingleDiffType(gold, test, EDIFDiffType.PROPERTY_EXTRA, comparator);
        comparator.filterVivadoChanges = true;

        testTop.removeProperty(propKey);
        checkNetlistMatch(gold, test, comparator);
    }

}
