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

package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

public class TestEDIFCellInst {
    @ParameterizedTest
    @ValueSource(strings = {
            "picoblaze_ooc_X10Y235.dcp",
            "optical-flow.dcp",
            "bnn.dcp",
    })
    public void testIsUniquified(String path) {
        Design design = RapidWrightDCP.loadDCP(path, true);
        EDIFNetlist netlist = design.getNetlist();

        for (EDIFLibrary library : netlist.getLibraries()) {
            if (library.isHDIPrimitivesLibrary()) {
                continue;
            }
            for (EDIFCell cell : library.getCells()) {
                for (EDIFCellInst eci : cell.getCellInsts()) {
                    Assertions.assertTrue(eci.isUniquified() || eci.getCellType().getLibrary().isHDIPrimitivesLibrary());
                }
            }
        }
    }

    @Test
    public void testIsUniquifiedFalse() {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Assertions.assertTrue(netlist.getTopCell().isUniquified());

        for (String name : Arrays.asList(
                "picoblaze_0_12",
                "picoblaze_0_13",
                "picoblaze_1_12",
                "picoblaze_1_13"
        )) {
            EDIFCellInst eci = netlist.getTopCell().getCellInst(name);
            Assertions.assertFalse(eci.isUniquified());
        }

        EDIFCell picoblazeTop = netlist.getCell("picoblaze_top");
        Assertions.assertFalse(picoblazeTop.isUniquified());
        for (String name : Arrays.asList(
                "processor",
                "your_program"
        )) {
            EDIFCellInst eci = picoblazeTop.getCellInst(name);
            // Only checks that this cell instance is unique (e.g. that there is only
            // one instantiation) but does not check that any parents on a full
            // hierarchical path (e.g. "picoblaze_{0,1}_{12,13}/processor") is also
            // unique --- use EDIFHierCellInst.isUniquified() for that
            Assertions.assertTrue(eci.isUniquified());
        }

        // Test that removing all but one instance makes the remaining one unique
        EDIFCell topCell = netlist.getTopCell();
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_13"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_12"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_13"));
        Assertions.assertTrue(topCell.getCellInst("picoblaze_0_12").isUniquified());

        // Check that creating an EDIFCellInst without a parent cell does not make it unique
        EDIFCellInst eci = new EDIFCellInst("picoblaze_1_13", picoblazeTop, null);
        Assertions.assertFalse(eci.isUniquified());

        // But adding it to a parent cell *does* increment instance count
        topCell.addCellInst(eci);
        Assertions.assertFalse(eci.isUniquified());
        Assertions.assertSame(eci, topCell.getCellInst("picoblaze_1_13"));
        // ... and removing the other cell instantiation makes this one unique
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_12"));
        Assertions.assertTrue(eci.isUniquified());
    }
}
