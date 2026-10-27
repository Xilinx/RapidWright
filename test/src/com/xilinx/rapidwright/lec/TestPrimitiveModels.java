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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Checks each primitive model against the equations in the golden simulation
 * models, and separately checks the input support each output declares.
 *
 * The support assertions matter as much as the function ones and are easier to
 * get wrong. A model that computes the right value but over-declares its
 * dependencies still produces correct logic; what it breaks is the property
 * loop detection relies on, that a carry chain is acyclic at bit level. The symptom
 * would be a spurious combinational loop on an ordinary adder, with no hint
 * that the cause was a support set.
 */
public class TestPrimitiveModels {

    private LecNetlistFixtures fixture;

    private EDIFCell holder;

    private final LecLog log = new LecLog(false, new PrintStream(new ByteArrayOutputStream()));

    @BeforeEach
    public void setUp() {
        CellIndex.clearCache();
        fixture = new LecNetlistFixtures("top");
        holder = fixture.getTop();
    }

    private EDIFCellInst instanceOf(EDIFCell type, String name) {
        return LecNetlistFixtures.add(holder, name, type);
    }

    /** @return The pin names an output declares as its support. */
    private static TreeSet<String> supportNames(PrimitiveModel model, CellIndex index,
            EDIFCellInst inst, String outPin) {
        int outBit = index.portBitIndexOfPinName(outPin);
        Assertions.assertTrue(outBit >= 0, "No output pin named " + outPin);
        int[] support = model.supportOf(index, inst, outBit);
        Assertions.assertNotNull(support, "No model for output " + outPin);
        TreeSet<String> names = new TreeSet<>();
        for (int bit : support) {
            names.add(index.getPortBitName(bit));
        }
        return names;
    }

    // ---------------------------------------------------------------- CARRY8

    /**
     * The carry out of bit {@code i} must depend on the carry in and on the sum
     * and data inputs of bits 0 to {@code i}, and on nothing above. That strict
     * ordering is what makes a carry chain acyclic at bit level.
     */
    @Test
    public void testCarry8SupportIsExactAndOrdered() {
        EDIFCell cell = fixture.carry8();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "c0");
        PrimitiveModel model = new Carry8Model();

        TreeSet<String> co3 = supportNames(model, index, inst, "CO[3]");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("CI", "S[0]", "S[1]", "S[2]", "S[3]",
                "DI[0]", "DI[1]", "DI[2]", "DI[3]")), co3);

        TreeSet<String> co0 = supportNames(model, index, inst, "CO[0]");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("CI", "S[0]", "DI[0]")), co0);
    }

    /**
     * A sum output sees the carry arriving at its bit but not the data input of
     * its own bit, which only affects the carry leaving it.
     */
    @Test
    public void testCarry8SumDoesNotDependOnItsOwnDataInput() {
        EDIFCell cell = fixture.carry8();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "c0");
        TreeSet<String> o3 = supportNames(new Carry8Model(), index, inst, "O[3]");
        Assertions.assertFalse(o3.contains("DI[3]"),
                "O[3] must not depend on DI[3]; only the carry out of bit 3 does");
        Assertions.assertTrue(o3.contains("DI[2]"));
        Assertions.assertTrue(o3.contains("S[3]"));
    }

    /**
     * In single chain mode the upper carry in is functionally ignored, so it
     * must not appear in any support set. If it did, a netlist that ties it to
     * ground would appear to differ from one that leaves it dangling.
     */
    @Test
    public void testCarry8IgnoresUpperCarryInSingleMode() {
        EDIFCell cell = fixture.carry8();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "c0");
        LecNetlistFixtures.setProperty(inst, "CARRY_TYPE", "SINGLE_CY8");
        PrimitiveModel model = new Carry8Model();
        for (int bit = 0; bit < 8; bit++) {
            Assertions.assertFalse(supportNames(model, index, inst, "CO[" + bit + "]")
                    .contains("CI_TOP"), "CO[" + bit + "] must ignore CI_TOP in single mode");
        }
        Assertions.assertTrue(supportNames(model, index, inst, "CO[7]").contains("CI"));
    }

    /**
     * In dual mode the upper four bits form their own chain starting from
     * {@code CI_TOP}, so they must not reach the lower half at all.
     */
    @Test
    public void testCarry8DualModeSplitsTheChain() {
        EDIFCell cell = fixture.carry8();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "c0");
        LecNetlistFixtures.setProperty(inst, "CARRY_TYPE", "DUAL_CY4");
        PrimitiveModel model = new Carry8Model();

        TreeSet<String> co5 = supportNames(model, index, inst, "CO[5]");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("CI_TOP", "S[4]", "S[5]",
                "DI[4]", "DI[5]")), co5);
        Assertions.assertFalse(co5.contains("CI"));

        TreeSet<String> co2 = supportNames(model, index, inst, "CO[2]");
        Assertions.assertTrue(co2.contains("CI"));
        Assertions.assertFalse(co2.contains("CI_TOP"));
    }

    /**
     * The function itself, checked against the golden equations
     * {@code O = S ^ CO_fb} and {@code CO = (S & CO_fb) | (~S & DI)} with
     * {@code CO_fb = {CO[6:4], CI_TOP_in, CO[2:0], CI}}.
     */
    @Test
    public void testCarry8Function() {
        for (String carryType : new String[] { "SINGLE_CY8", "DUAL_CY4" }) {
            setUp();
            EDIFCell cell = fixture.carry8();
            CellIndex index = CellIndex.get(cell);
            EDIFCellInst inst = instanceOf(cell, "c0");
            LecNetlistFixtures.setProperty(inst, "CARRY_TYPE", carryType);
            checkCarry8Function(index, inst, carryType.equals("DUAL_CY4"));
        }
    }

    private void checkCarry8Function(CellIndex index, EDIFCellInst inst, boolean dual) {
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        PrimitiveModel model = new Carry8Model();
        int[] sumLits = new int[8];
        int[] carryLits = new int[8];
        for (int bit = 0; bit < 8; bit++) {
            sumLits[bit] = buildOutput(aig, model, index, inst, pins, "O[" + bit + "]");
            carryLits[bit] = buildOutput(aig, model, index, inst, pins, "CO[" + bit + "]");
        }

        long[] piValues = randomInputWords(aig, 12345);
        AigEvaluation evaluation = AigEvaluation.of(aig, piValues);
        long ci = wordOf(aig, piValues, pins, "CI");
        long ciTop = wordOf(aig, piValues, pins, "CI_TOP");
        long[] s = new long[8];
        long[] di = new long[8];
        for (int bit = 0; bit < 8; bit++) {
            s[bit] = wordOf(aig, piValues, pins, "S[" + bit + "]");
            di[bit] = wordOf(aig, piValues, pins, "DI[" + bit + "]");
        }

        long[] co = new long[8];
        for (int bit = 0; bit < 8; bit++) {
            long carryIn;
            if (bit == 0) {
                carryIn = ci;
            } else if (bit == 4 && dual) {
                carryIn = ciTop;
            } else {
                carryIn = co[bit - 1];
            }
            long expectedSum = s[bit] ^ carryIn;
            co[bit] = (s[bit] & carryIn) | (~s[bit] & di[bit]);
            Assertions.assertEquals(expectedSum, evaluation.valueOf(sumLits[bit]),
                    "O[" + bit + "] is wrong in " + (dual ? "dual" : "single") + " mode");
            Assertions.assertEquals(co[bit], evaluation.valueOf(carryLits[bit]),
                    "CO[" + bit + "] is wrong in " + (dual ? "dual" : "single") + " mode");
        }
    }

    // ---------------------------------------------------------------- CARRY4

    /**
     * The carry into bit 0 is the disjunction of {@code CI} and {@code CYINIT},
     * not a selection between them. Only one is ever driven in practice, so a
     * multiplexer would usually agree, which is exactly what would make the
     * mistake hard to find.
     */
    @Test
    public void testCarry4CarryInIsADisjunction() {
        EDIFCell cell = carry4Cell();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "c4");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        PrimitiveModel model = new Carry4Model();
        int co0 = buildOutput(aig, model, index, inst, pins, "CO[0]");

        long[] piValues = randomInputWords(aig, 777);
        AigEvaluation evaluation = AigEvaluation.of(aig, piValues);
        long ci = wordOf(aig, piValues, pins, "CI");
        long cyinit = wordOf(aig, piValues, pins, "CYINIT");
        long s0 = wordOf(aig, piValues, pins, "S[0]");
        long di0 = wordOf(aig, piValues, pins, "DI[0]");
        long carryIn = ci | cyinit;
        Assertions.assertEquals((s0 & carryIn) | (~s0 & di0), evaluation.valueOf(co0),
                "CARRY4 bit 0 must take the disjunction of CI and CYINIT");
    }

    /** The four bit chain has the same strictly increasing dependency order. */
    @Test
    public void testCarry4Support() {
        EDIFCell cell = carry4Cell();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "c4");
        TreeSet<String> co2 = supportNames(new Carry4Model(), index, inst, "CO[2]");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("CI", "CYINIT", "S[0]", "S[1]",
                "S[2]", "DI[0]", "DI[1]", "DI[2]")), co2);
        Assertions.assertFalse(supportNames(new Carry4Model(), index, inst, "O[2]")
                .contains("DI[2]"));
    }

    private EDIFCell carry4Cell() {
        EDIFCell cell = new EDIFCell(fixture.getNetlist()
                .getLibrary(com.xilinx.rapidwright.edif.EDIFTools
                        .EDIF_LIBRARY_HDI_PRIMITIVES_NAME), "CARRY4");
        cell.createPort("CI", com.xilinx.rapidwright.edif.EDIFDirection.INPUT, 1);
        cell.createPort("CYINIT", com.xilinx.rapidwright.edif.EDIFDirection.INPUT, 1);
        cell.createPort("DI[3:0]", com.xilinx.rapidwright.edif.EDIFDirection.INPUT, 4);
        cell.createPort("S[3:0]", com.xilinx.rapidwright.edif.EDIFDirection.INPUT, 4);
        cell.createPort("O[3:0]", com.xilinx.rapidwright.edif.EDIFDirection.OUTPUT, 4);
        cell.createPort("CO[3:0]", com.xilinx.rapidwright.edif.EDIFDirection.OUTPUT, 4);
        return cell;
    }

    // ---------------------------------------------------------------- LUT6CY

    /**
     * The propagate output is a function of the low four inputs only. Excluding
     * the fifth, which is where the carry arrives, is precisely what makes a
     * Versal carry chain acyclic.
     */
    @Test
    public void testLut6cyPropagateExcludesTheCarryInput() {
        EDIFCell cell = fixture.primitive("LUT6CY",
                new String[] { "I0", "I1", "I2", "I3", "I4" },
                new String[] { "O51", "O52", "PROP", "GE" });
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "lc");
        // Parity of all six INIT index bits, so every slice depends on every input
        // it can see and the support is decided by the pin alone.
        LecNetlistFixtures.setProperty(inst, "INIT", "64'h6996966996696996");
        PrimitiveModel model = new Lut6cyModel(log);

        Assertions.assertEquals(new TreeSet<>(Arrays.asList("I0", "I1", "I2", "I3")),
                supportNames(model, index, inst, "PROP"),
                "PROP must not depend on I4");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("I0", "I1", "I2", "I3", "I4")),
                supportNames(model, index, inst, "O51"));
        Assertions.assertNull(model.supportOf(index, inst, index.portBitIndexOfPinName("GE")),
                "GE is never driven in the golden model, so it has no function to model");
    }

    /**
     * A bus inversion attribute is a mask: only the bits set in it are inverted.
     */
    @Test
    public void testBusInversionMaskIsPerBit() {
        EDIFCell cell = fixture.primitive("DSP48E2", new String[] { "A" }, new String[] { "P" });
        EDIFCellInst inst = instanceOf(cell, "dsp");
        LecNetlistFixtures.setProperty(inst, "IS_ALUMODE_INVERTED", "4'b0010");
        for (int bit = 0; bit < 4; bit++) {
            Assertions.assertEquals(bit == 1, ModelUtils.isPinBitInverted(inst, "ALUMODE", bit),
                    "ALUMODE[" + bit + "]");
        }
    }

    /**
     * A boolean attribute that is present but unreadable is an error, not its
     * default: guessing could build the same function from two settings.
     */
    @Test
    public void testUnreadableBooleanPropertyIsAnError() {
        EDIFCell cell = lookahead8Cell();
        EDIFCellInst inst = instanceOf(cell, "la");
        LecNetlistFixtures.setProperty(inst, "LOOKB", "SOMETIMES");
        Assertions.assertThrows(LecException.class,
                () -> ModelUtils.booleanProperty(inst, "LOOKB", false));
    }

    /**
     * As for a plain look up table, an input the INIT ignores is not support.
     * Otherwise feedback into that input would be reported as a loop.
     */
    @Test
    public void testLut6cySupportFollowsTheInit() {
        EDIFCell cell = fixture.primitive("LUT6CY",
                new String[] { "I0", "I1", "I2", "I3", "I4" },
                new String[] { "O51", "O52", "PROP", "GE" });
        EDIFCellInst inst = instanceOf(cell, "lc");
        LecNetlistFixtures.setProperty(inst, "INIT", "64'hAAAAAAAAAAAAAAAA");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("I0")),
                supportNames(new Lut6cyModel(log), CellIndex.get(cell), inst, "O51"),
                "INIT 0xAAAA... is I0 alone");
    }

    /**
     * The three driven outputs read different slices of INIT:
     * {@code O51} the low 32 bits, {@code O52} the high 32, and {@code PROP} the
     * low 16.
     */
    @Test
    public void testLut6cyReadsTheRightInitSlices() {
        EDIFCell cell = fixture.primitive("LUT6CY",
                new String[] { "I0", "I1", "I2", "I3", "I4" },
                new String[] { "O51", "O52", "PROP", "GE" });
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "lc");
        long init = 0xFFF00F000F0FF0F0L;
        LecNetlistFixtures.setProperty(inst, "INIT", "64'h" + Long.toHexString(init));

        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        PrimitiveModel model = new Lut6cyModel(log);
        int o51 = buildOutput(aig, model, index, inst, pins, "O51");
        int o52 = buildOutput(aig, model, index, inst, pins, "O52");
        int prop = buildOutput(aig, model, index, inst, pins, "PROP");

        AigEvaluation evaluation = evaluateInPinOrder(aig, pins, "I0", "I1", "I2", "I3", "I4");
        Assertions.assertEquals(init & 0xFFFFFFFFL, evaluation.tableOf(o51, 5));
        Assertions.assertEquals(init >>> 32, evaluation.tableOf(o52, 5));
        Assertions.assertEquals(init & 0xFFFFL, evaluation.tableOf(prop, 4));
    }

    // ------------------------------------------------------------ LOOKAHEAD8

    /**
     * With lookahead enabled, the four carry outputs form a strictly increasing
     * chain, and the group enable inputs have no effect at all in the golden
     * model.
     */
    @Test
    public void testLookahead8SupportGrowsAndIgnoresGroupEnables() {
        EDIFCell cell = lookahead8Cell();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "la");
        for (String look : new String[] { "LOOKB", "LOOKD", "LOOKF", "LOOKH" }) {
            LecNetlistFixtures.setProperty(inst, look, "TRUE");
        }
        PrimitiveModel model = new Lookahead8Model();

        TreeSet<String> coutB = supportNames(model, index, inst, "COUTB");
        TreeSet<String> coutH = supportNames(model, index, inst, "COUTH");
        Assertions.assertTrue(coutH.containsAll(coutB),
                "Each stage must see everything the stage below it sees");
        Assertions.assertTrue(coutB.size() < coutH.size(),
                "Later stages must see strictly more inputs");
        for (String name : new TreeSet<>(coutH)) {
            Assertions.assertFalse(name.startsWith("GE"),
                    "The group enable inputs have no function and must not be in any support");
        }
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("CIN", "PROPA", "PROPB", "CYA",
                "CYB")), coutB);
    }

    /**
     * Support follows the {@code LOOK} attributes: with {@code LOOKB} false,
     * {@code COUTB} is {@code CYB} alone, so feedback into {@code CIN} is not a
     * loop.
     */
    @Test
    public void testLookahead8SupportFollowsLookAttributes() {
        EDIFCell cell = lookahead8Cell();
        EDIFCellInst inst = instanceOf(cell, "la");
        LecNetlistFixtures.setProperty(inst, "LOOKB", "FALSE");
        Assertions.assertEquals(new TreeSet<>(Arrays.asList("CYB")),
                supportNames(new Lookahead8Model(), CellIndex.get(cell), inst, "COUTB"));
    }

    /**
     * With every lookahead attribute at its default of false, the cell reduces
     * to passing each pair's carry straight through.
     */
    @Test
    public void testLookahead8DefaultsToPassThrough() {
        EDIFCell cell = lookahead8Cell();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "la");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        PrimitiveModel model = new Lookahead8Model();
        Assertions.assertEquals(pins.get("CYB").intValue(),
                buildOutput(aig, model, index, inst, pins, "COUTB"),
                "With LOOKB false, COUTB is simply CYB");
        Assertions.assertEquals(pins.get("CYD").intValue(),
                buildOutput(aig, model, index, inst, pins, "COUTD"),
                "With LOOKD false, COUTD is simply CYD");
    }

    /** With lookahead enabled the carry can bypass a propagating pair. */
    @Test
    public void testLookahead8PropagatesWhenEnabled() {
        EDIFCell cell = lookahead8Cell();
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "la");
        LecNetlistFixtures.setProperty(inst, "LOOKB", "TRUE");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        int coutB = buildOutput(aig, new Lookahead8Model(), index, inst, pins, "COUTB");

        long[] piValues = randomInputWords(aig, 4242);
        AigEvaluation evaluation = AigEvaluation.of(aig, piValues);
        long cin = wordOf(aig, piValues, pins, "CIN");
        long propA = wordOf(aig, piValues, pins, "PROPA");
        long propB = wordOf(aig, piValues, pins, "PROPB");
        long cyA = wordOf(aig, piValues, pins, "CYA");
        long cyB = wordOf(aig, piValues, pins, "CYB");
        long expected = (~propB & cyB) | (propB & ((~propA & cyA) | (propA & cin)));
        Assertions.assertEquals(expected, evaluation.valueOf(coutB));
    }

    private EDIFCell lookahead8Cell() {
        List<String> inputs = new ArrayList<>();
        inputs.add("CIN");
        for (char letter = 'A'; letter <= 'H'; letter++) {
            inputs.add("PROP" + letter);
            inputs.add("CY" + letter);
            inputs.add("GE" + letter);
        }
        return fixture.primitive("LOOKAHEAD8", inputs.toArray(new String[0]),
                new String[] { "COUTB", "COUTD", "COUTF", "COUTH" });
    }

    // ------------------------------------------------------------ soft gates

    /**
     * The inversion suffix inverts the <i>leading</i> inputs. The retarget
     * models pin this down: {@code AND2B1} is a look up table with INIT 4'h4,
     * that is {@code !I0 & I1}, and {@code AND3B2} is 8'h10.
     */
    @Test
    public void testSoftGateInversionSuffix() {
        assertGateTable("AND2", 2, 0x8L);
        assertGateTable("AND2B1", 2, 0x4L);
        assertGateTable("AND3B2", 3, 0x10L);
        assertGateTable("XOR2", 2, 0x6L);
        assertGateTable("NAND2B1", 2, 0xBL);
        assertGateTable("OR3", 3, 0xFEL);
        assertGateTable("XNOR2", 2, 0x9L);
    }

    /**
     * Builds a soft gate and checks its truth table over its inputs in
     * {@code I0} upwards order. The expected values are the INIT constants the
     * retarget models use for the same cells, which is what pins the meaning of
     * the inversion suffix down.
     */
    private void assertGateTable(String typeName, int arity, long expected) {
        setUp();
        String[] inputs = new String[arity];
        for (int i = 0; i < arity; i++) {
            inputs[i] = "I" + i;
        }
        EDIFCell cell = fixture.primitive(typeName, inputs, new String[] { "O" });
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "g");
        PrimitiveModel model = SimpleGateModel.forType(typeName);
        Assertions.assertNotNull(model, "No model for " + typeName);
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        int out = buildOutput(aig, model, index, inst, pins, "O");
        AigEvaluation evaluation = evaluateInPinOrder(aig, pins, inputs);
        Assertions.assertEquals(expected, evaluation.tableOf(out, arity),
                typeName + " computes the wrong function");
    }

    /**
     * The two set and reset gates are not symmetric with each other, and both
     * honour the inversion attribute on their second input.
     */
    @Test
    public void testSetResetGates() {
        for (boolean inverted : new boolean[] { false, true }) {
            setUp();
            EDIFCell andCell = fixture.primitive("AND2B1L", new String[] { "DI", "SRI" },
                    new String[] { "O" });
            CellIndex index = CellIndex.get(andCell);
            EDIFCellInst inst = instanceOf(andCell, "g");
            if (inverted) {
                LecNetlistFixtures.setProperty(inst, "IS_SRI_INVERTED", "1'b1");
            }
            Aig aig = new Aig();
            Map<String, Integer> pins = declareInputs(aig, index);
            int out = buildOutput(aig, SimpleGateModel.forType("AND2B1L"), index, inst, pins, "O");
            AigEvaluation evaluation = evaluateInPinOrder(aig, pins, "DI", "SRI");
            long di = TruthTable.VAR_MASK[0];
            long sri = inverted ? ~TruthTable.VAR_MASK[1] : TruthTable.VAR_MASK[1];
            Assertions.assertEquals((di & ~sri) & 0xFL, evaluation.tableOf(out, 2),
                    "AND2B1L negates its set and reset input");
        }
    }

    @Test
    public void testOr2LDoesNotNegate() {
        EDIFCell cell = fixture.primitive("OR2L", new String[] { "DI", "SRI" },
                new String[] { "O" });
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "g");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        int out = buildOutput(aig, SimpleGateModel.forType("OR2L"), index, inst, pins, "O");
        AigEvaluation evaluation = evaluateInPinOrder(aig, pins, "DI", "SRI");
        Assertions.assertEquals((TruthTable.VAR_MASK[0] | TruthTable.VAR_MASK[1]) & 0xFL,
                evaluation.tableOf(out, 2),
                "OR2L does not negate its set and reset input");
    }

    /** Multiplexers and the carry multiplexer and exclusive or. */
    @Test
    public void testMuxesAndCarryGates() {
        EDIFCell muxf = fixture.primitive("MUXF7", new String[] { "I0", "I1", "S" },
                new String[] { "O" });
        checkThreeInput(muxf, "MUXF7", "O", "S", "I1", "I0");

        setUp();
        EDIFCell muxcy = fixture.primitive("MUXCY", new String[] { "CI", "DI", "S" },
                new String[] { "O" });
        checkThreeInput(muxcy, "MUXCY", "O", "S", "CI", "DI");

        setUp();
        EDIFCell xorcy = fixture.primitive("XORCY", new String[] { "CI", "LI" },
                new String[] { "O" });
        CellIndex index = CellIndex.get(xorcy);
        EDIFCellInst inst = instanceOf(xorcy, "x");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        int out = buildOutput(aig, SimpleGateModel.forType("XORCY"), index, inst, pins, "O");
        AigEvaluation evaluation = evaluateInPinOrder(aig, pins, "CI", "LI");
        Assertions.assertEquals((TruthTable.VAR_MASK[0] ^ TruthTable.VAR_MASK[1]) & 0xFL,
                evaluation.tableOf(out, 2));
    }

    private void checkThreeInput(EDIFCell cell, String typeName, String outPin,
            String selectPin, String truePin, String falsePin) {
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "m");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        int out = buildOutput(aig, SimpleGateModel.forType(typeName), index, inst, pins, outPin);
        AigEvaluation evaluation = evaluateInPinOrder(aig, pins, falsePin, truePin, selectPin);
        long onFalse = TruthTable.VAR_MASK[0];
        long onTrue = TruthTable.VAR_MASK[1];
        long select = TruthTable.VAR_MASK[2];
        Assertions.assertEquals(((select & onTrue) | (~select & onFalse)) & 0xFFL,
                evaluation.tableOf(out, 3), typeName + " computes the wrong function");
    }

    /** Buffers are transparent, and a differential output is the complement. */
    @Test
    public void testBuffers() {
        EDIFCell obufds = fixture.primitive("OBUFDS", new String[] { "I" },
                new String[] { "O", "OB" });
        CellIndex index = CellIndex.get(obufds);
        EDIFCellInst inst = instanceOf(obufds, "b");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        PrimitiveModel model = SimpleGateModel.forType("OBUFDS");
        int o = buildOutput(aig, model, index, inst, pins, "O");
        int ob = buildOutput(aig, model, index, inst, pins, "OB");
        Assertions.assertEquals(pins.get("I").intValue(), o, "OBUFDS.O must be its input");
        Assertions.assertEquals(Aig.not(pins.get("I")), ob,
                "OBUFDS.OB must be the complement of its input");
    }

    /** A look up table's INIT is already a truth table in the tool's ordering. */
    @Test
    public void testLutReadsInitDirectly() {
        EDIFCell cell = fixture.lut(4);
        CellIndex index = CellIndex.get(cell);
        EDIFCellInst inst = instanceOf(cell, "l");
        LecNetlistFixtures.setProperty(inst, "INIT", "16'h8E3F");
        Aig aig = new Aig();
        Map<String, Integer> pins = declareInputs(aig, index);
        int out = buildOutput(aig, new LutModel(log), index, inst, pins, "O");
        AigEvaluation evaluation = evaluateInPinOrder(aig, pins, "I0", "I1", "I2", "I3");
        Assertions.assertEquals(0x8E3FL, evaluation.tableOf(out, 4));
    }

    // ------------------------------------------------------------- utilities

    /** Creates one primary input per input port bit, named after the pin. */
    private Map<String, Integer> declareInputs(Aig aig, CellIndex index) {
        Map<String, Integer> pins = new HashMap<>();
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            EDIFPort port = index.getPortOfBit(bit);
            if (port.isOutput()) {
                continue;
            }
            String name = index.getPortBitName(bit);
            pins.put(name, aig.newInput(name, true));
        }
        return pins;
    }

    /** Builds one output, wiring the model's declared support to the inputs. */
    private static int buildOutput(Aig aig, PrimitiveModel model, CellIndex index,
            EDIFCellInst inst, Map<String, Integer> pins, String outPin) {
        int outBit = index.portBitIndexOfPinName(outPin);
        int[] support = model.supportOf(index, inst, outBit);
        int[] lits = new int[support.length];
        for (int i = 0; i < support.length; i++) {
            Integer lit = pins.get(index.getPortBitName(support[i]));
            Assertions.assertNotNull(lit, "Support names a pin with no input: "
                    + index.getPortBitName(support[i]));
            lits[i] = lit;
        }
        return model.build(aig, index, inst, outBit, lits);
    }

    /**
     * Evaluates with variable {@code k} assigned to the named pin, so that a
     * resulting truth table is expressed in the caller's pin order.
     *
     * This is necessary because a cell's ports are not enumerated in the order
     * they were declared, so the graph's input indices are a permutation of the
     * pin order. The tool itself is unaffected, since every model binds its
     * inputs by pin name, but a test that compared raw tables would be
     * comparing a permuted function.
     */
    private static AigEvaluation evaluateInPinOrder(Aig aig, Map<String, Integer> pins,
            String... orderedPins) {
        long[] values = new long[aig.getPiCount()];
        for (int i = 0; i < orderedPins.length; i++) {
            values[pinVar(aig, pins, orderedPins[i])] = TruthTable.VAR_MASK[i];
        }
        return AigEvaluation.of(aig, values);
    }

    private static long[] randomInputWords(Aig aig, long seed) {
        Random random = new Random(seed);
        long[] values = new long[aig.getPiCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextLong();
        }
        return values;
    }

    private static long wordOf(Aig aig, long[] piValues, Map<String, Integer> pins, String pin) {
        return piValues[aig.getPiIndex(Aig.nodeOf(pins.get(pin)))];
    }

    private static int pinVar(Aig aig, Map<String, Integer> pins, String pin) {
        return aig.getPiIndex(Aig.nodeOf(pins.get(pin)));
    }
}
