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

public class TestEDIFCell {
    private static void assertAllCellsAreUniquified(EDIFNetlist netlist) {
        for (EDIFLibrary library : netlist.getLibraries()) {
            if (library.isHDIPrimitivesLibrary()) {
                continue;
            }
            for (EDIFCell cell : library.getCells()) {
                Assertions.assertTrue(cell.isUniquified());
            }
        }
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

        assertAllCellsAreUniquified(netlist);
    }

    @Test
    public void testIsUniquifiedFalse() {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();
        Assertions.assertTrue(topCell.isUniquified());
        EDIFCell picoblazeTop = netlist.getCell("picoblaze_top");
        Assertions.assertFalse(picoblazeTop.isUniquified());

        // Test that removing a EDIFCellInst reduces the instance count
        Assertions.assertEquals(4, picoblazeTop.getInstanceCount());
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_13"));
        Assertions.assertEquals(3, picoblazeTop.getInstanceCount());
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_12"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_13"));
        Assertions.assertTrue(picoblazeTop.isUniquified());

        assertAllCellsAreUniquified(netlist);
    }
}
