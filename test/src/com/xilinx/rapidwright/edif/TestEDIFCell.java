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
        Assertions.assertEquals(4, picoblazeTop.getNonHierInstantiationCount());
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_13"));
        Assertions.assertEquals(3, picoblazeTop.getNonHierInstantiationCount());
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_12"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_13"));
        Assertions.assertTrue(picoblazeTop.isUniquified());
        assertAllCellsAreUniquified(netlist);
    }

    @Test
    public void testIsUniquifiedRemoveAndAdd() {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();
        EDIFCell picoblazeTop = netlist.getCell("picoblaze_top");

        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_13"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_12"));
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_13"));

        // Check that creating an EDIFCellInst without a parent cell does not increment instance count
        EDIFCellInst eci = new EDIFCellInst("picoblaze_1_13", picoblazeTop, null);
        Assertions.assertTrue(picoblazeTop.isUniquified());

        // But adding it to a parent cell *does* increment instance count
        topCell.addCellInst(eci);
        Assertions.assertFalse(picoblazeTop.isUniquified());

        // Establish that the "processor" instance's cell type is only instantiated once
        EDIFCell kcpsm6 = netlist.getCell("kcpsm6"); // Cell type of "processor" instance
        Assertions.assertEquals(1, kcpsm6.getNonHierInstantiationCount());

        // Remove all remaining instances
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_0_12"));
        Assertions.assertEquals(1, kcpsm6.getNonHierInstantiationCount());
        Assertions.assertNotNull(topCell.removeCellInst("picoblaze_1_13"));
        Assertions.assertEquals(0, picoblazeTop.getNonHierInstantiationCount());
        // Note that the "processor" cellType is still instantiated once, even though its parent
        // "picoblaze_top" is no longer attached to the netlist
        Assertions.assertEquals(1, kcpsm6.getNonHierInstantiationCount());

        // Add one back again
        new EDIFCellInst("picoblaze_0_12", picoblazeTop, topCell);
        Assertions.assertTrue(picoblazeTop.isUniquified());
        Assertions.assertEquals(1, picoblazeTop.getNonHierInstantiationCount());
        Assertions.assertEquals(1, kcpsm6.getNonHierInstantiationCount());

        // And another
        new EDIFCellInst("picoblaze_0_13", picoblazeTop, topCell);
        Assertions.assertFalse(picoblazeTop.isUniquified());
        Assertions.assertEquals(2, picoblazeTop.getNonHierInstantiationCount());
        Assertions.assertEquals(1, kcpsm6.getNonHierInstantiationCount());
    }

    @Test
    public void testRenamePort() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        topCell.renamePort("dmem_i_V_ce0", "test_port");

        Assertions.assertNull(topCell.getPort("dmem_i_V_ce0"));

        EDIFPort newPort = topCell.getPort("test_port");
        Assertions.assertNotNull(newPort);
        Assertions.assertEquals("test_port", newPort.getInternalPortInst().getName());
        Assertions.assertEquals("dmem_i_V_ce0", newPort.getInternalNet().getName());

        // Create port `my_signal[5:2]` and associated port instances/nets
        topCell.addPort(new EDIFPort("my_signal[5:2]", EDIFDirection.INPUT, 4));
        EDIFNet mySignal2Net = topCell.addNet(new EDIFNet("my_signal[2]", topCell));
        mySignal2Net.createPortInst("my_signal[3]", topCell);
        EDIFNet mySignal3Net = topCell.addNet(new EDIFNet("my_signal[3]", topCell));
        mySignal3Net.createPortInst("my_signal[2]", topCell);
        EDIFNet mySignal4Net = topCell.addNet(new EDIFNet("my_signal[4]", topCell));
        mySignal4Net.createPortInst("my_signal[1]", topCell);
        EDIFNet mySignal5Net = topCell.addNet(new EDIFNet("my_signal[5]", topCell));
        mySignal5Net.createPortInst("my_signal[0]", topCell);

        topCell.renamePort("my_signal[5:2]", "my_new_signal[5:2]");

        EDIFPort myNewSignal = topCell.getPort("my_new_signal[");
        Assertions.assertNotNull(myNewSignal);

        for (int i : myNewSignal.getBitBlastedIndicies()) {
            EDIFPortInst portInst = myNewSignal.getInternalPortInstFromIndex(i);
            Assertions.assertNotNull(portInst);
        }

        // Create port `test_zero[0:0]` and associated port instances/nets
        topCell.addPort(new EDIFPort("test_zero[0:0]", EDIFDirection.INPUT, 1));
        EDIFNet testZeroNet = topCell.addNet(new EDIFNet("test_zero[0]", topCell));
        testZeroNet.createPortInst("test_zero[0]", topCell);

        topCell.renamePort("test_zero[0:0]", "new_test_zero[0:0]");

        EDIFPort newTestZero = topCell.getPort("new_test_zero[");
        Assertions.assertNotNull(newTestZero);

        for (int i : newTestZero.getBitBlastedIndicies()) {
            EDIFPortInst portInst = newTestZero.getInternalPortInstFromIndex(i);
            Assertions.assertNotNull(portInst);
        }

        // Create port `test_five[5:5]` and associated port instances/nets
        topCell.addPort(new EDIFPort("test_five[5:5]", EDIFDirection.INPUT, 1));
        EDIFNet testFiveNet = topCell.addNet(new EDIFNet("test_five[0]", topCell));
        testFiveNet.createPortInst("test_five[0]", topCell);

        topCell.renamePort("test_five[5:5]", "new_test_five[5:5]");

        EDIFPort newTestFive = topCell.getPort("new_test_five[");
        Assertions.assertNotNull(newTestFive);

        for (int i : newTestFive.getBitBlastedIndicies()) {
            EDIFPortInst portInst = newTestFive.getInternalPortInstFromIndex(i);
            Assertions.assertNotNull(portInst);
        }
    }
}
