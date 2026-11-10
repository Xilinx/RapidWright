/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.lec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Matching terminals by a recorded origin ({@code --key-property}) rather than
 * by hierarchical path.
 *
 * A transform that relocates a terminal between partitions, or moves a whole
 * sub-hierarchy containing one, changes the terminal's path. If it records the
 * original path in a property, the two netlists can still be compared. The key
 * must only rename: it must never make different logic look equivalent.
 */
public class TestKeyProperty {

    private static final String KEY = "TEST_ORIGIN";

    /** Flip flop in PART_2, fed by a look up table in PART_2. */
    private static EDIFNetlist reference() {
        return LecNetlistFixtures.twoPartitionDesign("4'hE", false, true);
    }

    /** The same function with the flip flop moved to PART_1; the look up table stays. */
    private static EDIFNetlist relocated(String lutBInit) {
        return LecNetlistFixtures.twoPartitionDesign(lutBInit, false, false);
    }

    private static EDIFCellInst inst(EDIFNetlist netlist, String partition, String name) {
        return netlist.getTopCell().getCellInst(partition).getCellType().getCellInst(name);
    }

    private static LecHarness check(EDIFNetlist ref, EDIFNetlist imp, String keyProperty) {
        LecConfig config = new LecConfig();
        config.setKeyProperty(keyProperty);
        return LecHarness.run(ref, imp, config);
    }

    /** Without a key a relocated terminal is, correctly, a terminal set difference. */
    @Test
    public void testRelocatedTerminalFailsWithoutKey() {
        LecHarness result = check(reference(), relocated("4'hE"), null);
        result.assertFail();
        Assertions.assertNotNull(result.findDifference(LecDifferenceType.TERMINAL_MISSING_IN_IMP));
        Assertions.assertNotNull(result.findDifference(LecDifferenceType.TERMINAL_MISSING_IN_REF));
    }

    /**
     * With the original path recorded on the moved terminal it is matched, and
     * the recording property itself is not reported as a configuration change.
     */
    @Test
    public void testRelocatedTerminalPassesWithKey() {
        EDIFNetlist imp = relocated("4'hE");
        LecNetlistFixtures.setProperty(inst(imp, "PART_1", "ff"), KEY, "PART_2/ff");
        LecHarness result = check(reference(), imp, KEY);
        result.assertPass();
        Assertions.assertTrue(result.getReport().getConesChecked() > 0, "No cones were checked");
        Assertions.assertNull(result.findDifference(LecDifferenceType.TERMINAL_PROPERTY_DIFFERS));
    }

    /** A key on an ancestor renames everything below it, as for a moved sub-hierarchy. */
    @Test
    public void testKeyOnAncestorRenamesDescendants() {
        EDIFNetlist imp = relocated("4'hE");
        LecNetlistFixtures.setProperty(imp.getTopCell().getCellInst("PART_1"), KEY, "PART_2");
        check(reference(), imp, KEY).assertPass();
    }

    /** A correct key must not hide a logic change in the relocated cone. */
    @Test
    public void testKeyDoesNotHideWrongLogic() {
        EDIFNetlist imp = relocated("4'h6");
        LecNetlistFixtures.setProperty(inst(imp, "PART_1", "ff"), KEY, "PART_2/ff");
        LecHarness result = check(reference(), imp, KEY);
        result.assertFail();
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference, "The differing cone was not reported");
        Assertions.assertEquals("PART_2/ff/D", difference.getSubject());
    }

    /** A key naming the wrong original is a terminal set difference, never a match. */
    @Test
    public void testMislabelledTerminalFails() {
        EDIFNetlist imp = relocated("4'hE");
        LecNetlistFixtures.setProperty(inst(imp, "PART_1", "ff"), KEY, "PART_2/elsewhere");
        LecHarness result = check(reference(), imp, KEY);
        result.assertFail();
        Assertions.assertNotNull(result.findDifference(LecDifferenceType.TERMINAL_MISSING_IN_IMP));
    }

    /** Two terminals claiming one identity would share a variable: refuse to compare. */
    @Test
    public void testDuplicateKeyIsRejected() {
        EDIFNetlist imp = relocated("4'hE");
        EDIFCellInst ff = inst(imp, "PART_1", "ff");
        EDIFCell part1 = ff.getParentCell();
        EDIFCellInst twin = LecNetlistFixtures.add(part1, "ff_twin", ff.getCellType());
        for (String pin : new String[] { "C", "CE", "D", "R" }) {
            EDIFNet net = ff.getPortInst(pin).getNet();
            net.createPortInst(pin, twin);
        }
        LecNetlistFixtures.setProperty(ff, KEY, "PART_2/ff");
        LecNetlistFixtures.setProperty(twin, KEY, "PART_2/ff");
        Assertions.assertThrows(LecException.class, () -> check(reference(), imp, KEY));
    }

    /** An empty key would name a flip flop's pins like top level ports: refuse it. */
    @Test
    public void testEmptyKeyIsRejected() {
        EDIFNetlist imp = relocated("4'hE");
        LecNetlistFixtures.setProperty(inst(imp, "PART_1", "ff"), KEY, "");
        Assertions.assertThrows(LecException.class, () -> check(reference(), imp, KEY));
    }

    /**
     * A key is left out of the attribute comparison, so a parameter such as
     * INIT must not be one, or a changed initial value would go unreported.
     */
    @Test
    public void testParameterAsKeyIsRejected() {
        Assertions.assertThrows(LecException.class,
                () -> check(reference(), relocated("4'hE"), "INIT"));
    }

    /**
     * A flip flop whose D input is constant 0, or, in the implementation, the
     * XOR of two black boxes that both claim the same key.
     */
    private static EDIFNetlist xorOfBlackBoxes(boolean imp) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        LecNetlistFixtures.port(top, "a", EDIFDirection.INPUT);
        LecNetlistFixtures.port(top, "clk", EDIFDirection.INPUT);
        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = LecNetlistFixtures.add(top, "PART_1", part);
        LecNetlistFixtures.port(part, "a", EDIFDirection.INPUT);
        LecNetlistFixtures.port(part, "clk", EDIFDirection.INPUT);
        LecNetlistFixtures.connect(top, "a", top, "a", partInst, "a");
        LecNetlistFixtures.connect(top, "clk", top, "clk", partInst, "clk");
        EDIFCellInst gnd = LecNetlistFixtures.add(part, "GND", fixture.gnd());
        EDIFCellInst vcc = LecNetlistFixtures.add(part, "VCC", fixture.vcc());
        EDIFCellInst ff = LecNetlistFixtures.add(part, "ff", fixture.fdre());
        LecNetlistFixtures.connect(part, "clk_i", part, "clk", ff, "C");
        LecNetlistFixtures.connect(part, "vcc", vcc, "P", ff, "CE");
        LecNetlistFixtures.connect(part, "gnd", gnd, "G", ff, "R");
        if (!imp) {
            LecNetlistFixtures.connect(part, "gnd", ff, "D");
            return fixture.getNetlist();
        }
        EDIFCell blackBox = fixture.newHierCell("opaque_ip");
        LecNetlistFixtures.port(blackBox, "I", EDIFDirection.INPUT);
        LecNetlistFixtures.port(blackBox, "O", EDIFDirection.OUTPUT);
        EDIFCellInst xor = LecNetlistFixtures.add(part, "xor", fixture.lut(2));
        LecNetlistFixtures.setProperty(xor, "INIT", "4'h6");
        String[] lutPins = { "I0", "I1" };
        LecNetlistFixtures.connect(part, "a_i", part, "a");
        for (int i = 0; i < 2; i++) {
            EDIFCellInst bb = LecNetlistFixtures.add(part, "bb" + i, blackBox);
            LecNetlistFixtures.setProperty(bb, KEY, "PART_1/bb");
            LecNetlistFixtures.connect(part, "a_i", bb, "I");
            LecNetlistFixtures.connect(part, "o" + i, bb, "O", xor, lutPins[i]);
        }
        LecNetlistFixtures.connect(part, "d", xor, "O", ff, "D");
        return fixture.getNetlist();
    }

    /**
     * Strict black box outputs are variables private to one netlist, named by
     * pin. Two black boxes sharing a key must still get two variables, or
     * {@code bb0.O XOR bb1.O} would simplify to 0 and pass.
     */
    @Test
    public void testDuplicateKeyOnStrictBlackBoxesIsNotMerged() {
        LecConfig config = new LecConfig();
        config.setKeyProperty(KEY);
        config.setStrictBlackBoxes(true);
        LecHarness result = LecHarness.run(xorOfBlackBoxes(false), xorOfBlackBoxes(true), config);
        result.assertFail();
        Assertions.assertNull(result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS),
                "Black box outputs are unknown, so the cone is inconclusive, not refuted");
    }

    /** Naming a property nobody carries changes nothing. */
    @Test
    public void testAbsentKeyIsNoOp() {
        check(reference(), reference(), KEY).assertPass();
        check(reference(), relocated("4'hE"), KEY).assertFail();
    }
}
