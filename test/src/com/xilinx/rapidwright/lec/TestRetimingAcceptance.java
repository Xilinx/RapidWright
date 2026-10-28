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
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Comparisons of retimed netlists with a retiming certificate: the retimings
 * that must be proven, and the wrong retimings and wrong certificates that must
 * not be.
 *
 * Every design here has inputs {@code a b c en rst clk clk2} and registers
 * clocked by {@code clk} unless stated, with the retimed logic between input
 * registers and an output register driving port {@code q}.
 */
public class TestRetimingAcceptance {

    @BeforeAll
    public static void loadSolver() {
        SatMiter.isAvailable();
    }

    // ---------------------------------------------------------------- designs

    /**
     * AND of two registered inputs, registered again:
     * {@code ra, rb -> f -> out}. Forward retiming moves {@code ra, rb} past
     * {@code f}, giving {@code f -> r -> out}.
     */
    private static Circuit andBeforeRetiming(String ce, String reset) {
        return new Circuit()
                .flop("FDRE", "ra", "a", "qa", ce, reset)
                .flop("FDRE", "rb", "b", "qb", ce, reset)
                .lut("f", "4'h8", "fo", "qa", "qb")
                .flop("FDRE", "out", "fo", "qo", "vcc", "gnd")
                .out("q", "qo");
    }

    private static Circuit andAfterForward(String type, String name, String init, String ce,
            String reset, String clock) {
        return new Circuit()
                .lut("f", "4'h8", "fo", "a", "b")
                .flop(type, name, "fo", "qr", ce, reset, clock, init)
                .flop("FDRE", "out", "qr", "qo", "vcc", "gnd")
                .out("q", "qo");
    }

    private static Circuit andAfterForward() {
        return andAfterForward("FDRE", "r", null, "vcc", "gnd", "clk");
    }

    private static RetimingMap map(String... lines) {
        return RetimingMap.parse(Arrays.asList(lines), "test");
    }

    private static LecHarness check(Circuit ref, Circuit imp, RetimingMap map) {
        return LecHarness.run(ref.netlist(), imp.netlist(), new LecConfig(), map);
    }

    private static void assertFinding(LecHarness result, LecDifferenceType type) {
        result.assertFail();
        Assertions.assertNotNull(result.findDifference(type),
                "Expected " + type + System.lineSeparator() + result.getOutput());
    }

    private static void assertRefused(Circuit ref, Circuit imp, RetimingMap map,
            String expectedText) {
        LecException e = Assertions.assertThrows(LecException.class,
                () -> check(ref, imp, map));
        Assertions.assertTrue(e.getMessage().contains(expectedText), e.getMessage());
    }

    // -------------------------------------------------------- valid retimings

    /** Without a certificate a retiming is a terminal difference, as in LEC. */
    @Test
    public void testRetimingWithoutCertificateFails() {
        LecHarness plain = check(andBeforeRetiming("vcc", "gnd"), andAfterForward(), null);
        assertFinding(plain, LecDifferenceType.TERMINAL_MISSING_IN_IMP);
        LecHarness empty = check(andBeforeRetiming("vcc", "gnd"), andAfterForward(), map());
        assertFinding(empty, LecDifferenceType.TERMINAL_MISSING_IN_IMP);
    }

    @Test
    public void testForwardRetimingPasses() {
        LecHarness result = check(andBeforeRetiming("vcc", "gnd"), andAfterForward(),
                map("IMP r = REF f/O"));
        result.assertPass();
        Assertions.assertTrue(result.getOutput().contains("1 defined, 2 retimed away"),
                result.getOutput());
    }

    /** {@code f -> r -> out} retimed backward to {@code ra, rb -> f -> out}. */
    @Test
    public void testBackwardRetimingPasses() {
        for (String init : new String[] { "1'b0", "1'b1" }) {
            Circuit ref = new Circuit()
                    .lut("f", "4'h8", "fo", "a", "b")
                    .flop("FDRE", "r", "fo", "qr", "vcc", "gnd", "clk", init)
                    .flop("FDRE", "out", "qr", "qo", "vcc", "gnd")
                    .out("q", "qo");
            Circuit imp = new Circuit()
                    .flop("FDRE", "ra", "a", "qa", "vcc", "gnd", "clk", init)
                    .flop("FDRE", "rb", "b", "qb", "vcc", "gnd", "clk", init)
                    .lut("f", "4'h8", "fo", "qa", "qb")
                    .flop("FDRE", "out", "fo", "qo", "vcc", "gnd")
                    .out("q", "qo");
            check(ref, imp, map("REF r = IMP f/O")).assertPass();
        }
    }

    /** Registers moved forward across two levels of logic at once. */
    @Test
    public void testMultiLevelRetimingPasses() {
        Circuit ref = new Circuit()
                .flop("FDRE", "ra", "a", "qa", "vcc", "gnd")
                .flop("FDRE", "rb", "b", "qb", "vcc", "gnd")
                .flop("FDRE", "rc", "c", "qc", "vcc", "gnd")
                .lut("f1", "4'h8", "f1o", "qa", "qb")
                .lut("f2", "4'hE", "f2o", "f1o", "qc")
                .flop("FDRE", "out", "f2o", "qo", "vcc", "gnd")
                .out("q", "qo");
        Circuit imp = new Circuit()
                .lut("f1", "4'h8", "f1o", "a", "b")
                .lut("f2", "4'hE", "f2o", "f1o", "c")
                .flop("FDRE", "r", "f2o", "qr", "vcc", "gnd")
                .flop("FDRE", "out", "qr", "qo", "vcc", "gnd")
                .out("q", "qo");
        check(ref, imp, map("IMP r = REF f2/O")).assertPass();
    }

    /**
     * A register renamed ({@code p} to {@code q}) and a register retimed past
     * logic that reads it, so one definition reads another defined register.
     */
    @Test
    public void testRenameAndForwardRetimingPasses() {
        Circuit ref = new Circuit()
                .flop("FDRE", "p", "a", "qp", "vcc", "gnd")
                .lut("g", "2'h1", "go", "qp")
                .flop("FDRE", "x", "go", "qx", "vcc", "gnd")
                .out("q", "qx")
                .out("q2", "qp");
        Circuit imp = new Circuit()
                .flop("FDRE", "q", "a", "qq", "vcc", "gnd")
                .lut("g", "2'h1", "go", "a")
                // NOT of p, which starts at 0, so r starts at 1.
                .flop("FDRE", "r", "go", "qr", "vcc", "gnd", "clk", "1'b1")
                .flop("FDRE", "x", "qr", "qx", "vcc", "gnd")
                .out("q", "qx")
                .out("q2", "qq");
        check(ref, imp, map("REF p = IMP q/Q", "IMP r = REF g/O")).assertPass();
    }

    /** A register duplicated to split its loads. */
    @Test
    public void testReplicationPasses() {
        Circuit ref = new Circuit()
                .lut("f", "4'h8", "fo", "a", "b")
                .flop("FDRE", "r", "fo", "qr", "vcc", "gnd")
                .out("q", "qr")
                .out("q2", "qr");
        Circuit imp = new Circuit()
                .lut("f", "4'h8", "fo", "a", "b")
                .flop("FDRE", "r", "fo", "qr", "vcc", "gnd")
                .flop("FDRE", "r_rep", "fo", "qrep", "vcc", "gnd")
                .out("q", "qr")
                .out("q2", "qrep");
        check(ref, imp, map("IMP r_rep = REF r/Q")).assertPass();
    }

    /** Clock enable and synchronous reset retime when they are shared. */
    @Test
    public void testRetimingWithEnableAndResetPasses() {
        check(andBeforeRetiming("en", "rst"),
                andAfterForward("FDRE", "r", null, "en", "rst", "clk"),
                map("IMP r = REF f/O")).assertPass();
    }

    /**
     * Retiming a reset across a NAND: the reset registers read zero, the NAND
     * of which is one, so the new register must be a set register.
     */
    @Test
    public void testResetToSetRetimingPasses() {
        Circuit imp = new Circuit()
                .lut("f", "4'h7", "fo", "a", "b")
                .flop("FDSE", "r", "fo", "qr", "en", "rst", "clk", "1'b1")
                .flop("FDRE", "out", "qr", "qo", "vcc", "gnd")
                .out("q", "qo");
        Circuit nandRef = new Circuit()
                .flop("FDRE", "ra", "a", "qa", "en", "rst")
                .flop("FDRE", "rb", "b", "qb", "en", "rst")
                .lut("f", "4'h7", "fo", "qa", "qb")
                .flop("FDRE", "out", "fo", "qo", "vcc", "gnd")
                .out("q", "qo");
        check(nandRef, imp, map("IMP r = REF f/O")).assertPass();
    }

    /**
     * A retimed register that keeps the name of a register it replaced must not
     * be mistaken for it.
     */
    @Test
    public void testRetimedRegisterKeepingAnOldNamePasses() {
        check(andBeforeRetiming("vcc", "gnd"),
                andAfterForward("FDRE", "ra", null, "vcc", "gnd", "clk"),
                map("IMP ra = REF f/O")).assertPass();
    }

    // ------------------------------------------------------ wrong retimings

    @Test
    public void testWrongDefinitionFails() {
        assertFinding(check(andBeforeRetiming("vcc", "gnd"), andAfterForward(),
                map("IMP r = REF ra/Q")), LecDifferenceType.RETIMING_STEP_DIFFERS);
    }

    @Test
    public void testWrongInitialValueFails() {
        assertFinding(check(andBeforeRetiming("vcc", "gnd"),
                andAfterForward("FDRE", "r", "1'b1", "vcc", "gnd", "clk"),
                map("IMP r = REF f/O")), LecDifferenceType.RETIMING_INIT_DIFFERS);
    }

    @Test
    public void testDroppedEnableFails() {
        assertFinding(check(andBeforeRetiming("en", "gnd"),
                andAfterForward("FDRE", "r", null, "vcc", "gnd", "clk"),
                map("IMP r = REF f/O")), LecDifferenceType.RETIMING_STEP_DIFFERS);
    }

    /** The NAND retiming above with a reset register where a set one is needed. */
    @Test
    public void testResetWhereSetIsNeededFails() {
        Circuit ref = new Circuit()
                .flop("FDRE", "ra", "a", "qa", "en", "rst")
                .flop("FDRE", "rb", "b", "qb", "en", "rst")
                .lut("f", "4'h7", "fo", "qa", "qb")
                .flop("FDRE", "out", "fo", "qo", "vcc", "gnd")
                .out("q", "qo");
        Circuit imp = new Circuit()
                .lut("f", "4'h7", "fo", "a", "b")
                .flop("FDRE", "r", "fo", "qr", "en", "rst", "clk", "1'b1")
                .flop("FDRE", "out", "qr", "qo", "vcc", "gnd")
                .out("q", "qo");
        assertFinding(check(ref, imp, map("IMP r = REF f/O")),
                LecDifferenceType.RETIMING_STEP_DIFFERS);
    }

    @Test
    public void testOtherClockFails() {
        assertFinding(check(andBeforeRetiming("vcc", "gnd"),
                andAfterForward("FDRE", "r", null, "vcc", "gnd", "clk2"),
                map("IMP r = REF f/O")), LecDifferenceType.RETIMING_CLOCK_DIFFERS);
    }

    /** A wrong LUT in the retimed logic is caught where it is read. */
    @Test
    public void testChangedRetimedLogicFails() {
        Circuit imp = new Circuit()
                .lut("f", "4'hE", "fo", "a", "b")
                .flop("FDRE", "r", "fo", "qr", "vcc", "gnd")
                .flop("FDRE", "out", "qr", "qo", "vcc", "gnd")
                .out("q", "qo");
        assertFinding(check(andBeforeRetiming("vcc", "gnd"), imp, map("IMP r = REF f/O")),
                LecDifferenceType.RETIMING_STEP_DIFFERS);
    }

    // ---------------------------------------------------- invalid certificates

    @Test
    public void testCyclicMapIsRefused() {
        Circuit ref = new Circuit()
                .flop("FDRE", "p", "a", "qp", "vcc", "gnd")
                .out("q", "qp");
        Circuit imp = new Circuit()
                .flop("FDRE", "s", "a", "qs", "vcc", "gnd")
                .out("q", "qs");
        assertRefused(ref, imp, map("REF p = IMP s/Q", "IMP s = REF p/Q"), "cyclic");
    }

    @Test
    public void testAsynchronousRegisterIsRefused() {
        assertRefused(andBeforeRetiming("vcc", "gnd"),
                andAfterForward("FDCE", "r", null, "vcc", "gnd", "clk"),
                map("IMP r = REF f/O"), "FDCE");
    }

    /** A register cannot hold the current value of a top level input. */
    @Test
    public void testDefinitionReadingAnInputIsRefused() {
        Circuit ref = andBeforeRetiming("vcc", "gnd").lut("h", "2'h2", "ho", "a");
        assertRefused(ref, andAfterForward(), map("IMP r = REF h/O"), "top level input");
    }

    @Test
    public void testUnknownNamesAreRefused() {
        assertRefused(andBeforeRetiming("vcc", "gnd"), andAfterForward(),
                map("IMP nosuch = REF f/O"), "nosuch");
        assertRefused(andBeforeRetiming("vcc", "gnd"), andAfterForward(),
                map("IMP r = REF nosuch/O"), "nosuch");
        assertRefused(andBeforeRetiming("vcc", "gnd"), andAfterForward(),
                map("IMP r = REF f/I0"), "not an output");
    }

    // ------------------------------------------------------------------ CLI

    @Test
    public void testCommandLine(@TempDir Path dir) throws IOException {
        Path ref = dir.resolve("ref.edf");
        Path imp = dir.resolve("imp.edf");
        EDIFTools.writeEDIFFile(ref, andBeforeRetiming("vcc", "gnd").netlist(),
                LecNetlistFixtures.ULTRASCALE_PLUS_PART);
        EDIFTools.writeEDIFFile(imp, andAfterForward().netlist(),
                LecNetlistFixtures.ULTRASCALE_PLUS_PART);
        Path good = dir.resolve("good.map");
        Files.write(good, Collections.singletonList("IMP r = REF f/O"));
        Path wrong = dir.resolve("wrong.map");
        Files.write(wrong, Collections.singletonList("IMP r = REF ra/Q"));
        Path broken = dir.resolve("broken.map");
        Files.write(broken, Collections.singletonList("IMP r REF f/O"));
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_PASS, run(ref, imp, good));
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_FAIL, run(ref, imp, wrong));
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_TOOL_ERROR, run(ref, imp, broken));
    }

    /**
     * The mutation campaign on a retimed pair: every retiming case must find a
     * site in this design, and every expectation must be met.
     */
    @Test
    public void testSelfCheckCampaign(@TempDir Path dir) throws IOException {
        Path ref = dir.resolve("ref.edf");
        Path imp = dir.resolve("imp.edf");
        // A second retiming, c through a NOT, so two definitions can be swapped,
        // and a register on clk2, so a register can be moved to another clock.
        EDIFTools.writeEDIFFile(ref, andBeforeRetiming("en", "rst")
                .flop("FDRE", "rd", "c", "qd", "vcc", "gnd")
                .lut("g", "2'h1", "go", "qd")
                .flop("FDRE", "out2", "go", "qo2", "vcc", "gnd")
                .out("q2", "qo2")
                .flop("FDRE", "z", "c", "qz", "vcc", "gnd", "clk2", null)
                .out("q3", "qz").netlist(), LecNetlistFixtures.ULTRASCALE_PLUS_PART);
        EDIFTools.writeEDIFFile(imp, andAfterForward("FDRE", "r", null, "en", "rst", "clk")
                .lut("g", "2'h1", "go", "c")
                .flop("FDRE", "r2", "go", "qr2", "vcc", "gnd", "clk", "1'b1")
                .flop("FDRE", "out2", "qr2", "qo2", "vcc", "gnd")
                .out("q2", "qo2")
                .flop("FDRE", "z", "c", "qz", "vcc", "gnd", "clk2", null)
                .out("q3", "qz").netlist(), LecNetlistFixtures.ULTRASCALE_PLUS_PART);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(buffer));
            exit = new LecSelfCheck(ref, imp, map("IMP r = REF f/O", "IMP r2 = REF g/O")).run();
        } finally {
            System.setOut(original);
        }
        String output = buffer.toString();
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_PASS, exit, output);
        for (String line : output.split("\\R")) {
            if (line.startsWith("retimed ") || line.startsWith("certificate ")
                    || line.startsWith("look up table feeding")) {
                Assertions.assertFalse(line.contains("SKIPPED"), line);
            }
        }
    }

    private static int run(Path ref, Path imp, Path map) {
        return LogicEquivalenceChecker.run(new String[] { ref.toString(), imp.toString(),
            "--retiming-map", map.toString() }, new PrintStream(new ByteArrayOutputStream()));
    }

    // ---------------------------------------------------------------- builder

    /** A flat design built from named nets, with the common ports and ties. */
    private static final class Circuit {

        private final LecNetlistFixtures fixture = new LecNetlistFixtures("top");

        private final EDIFCell top = fixture.getTop();

        Circuit() {
            for (String input : new String[] { "a", "b", "c", "en", "rst", "clk", "clk2" }) {
                LecNetlistFixtures.port(top, input, EDIFDirection.INPUT);
                LecNetlistFixtures.connect(top, input, top, input);
            }
            LecNetlistFixtures.connect(top, "vcc",
                    LecNetlistFixtures.add(top, "VCC", fixture.vcc()), "P");
            LecNetlistFixtures.connect(top, "gnd",
                    LecNetlistFixtures.add(top, "GND", fixture.gnd()), "G");
        }

        Circuit out(String port, String net) {
            LecNetlistFixtures.port(top, port, EDIFDirection.OUTPUT);
            LecNetlistFixtures.connect(top, net, top, port);
            return this;
        }

        Circuit lut(String name, String init, String out, String... inputs) {
            EDIFCellInst lut = LecNetlistFixtures.add(top, name, fixture.lut(inputs.length));
            LecNetlistFixtures.setProperty(lut, "INIT", init);
            for (int i = 0; i < inputs.length; i++) {
                LecNetlistFixtures.connect(top, inputs[i], lut, "I" + i);
            }
            LecNetlistFixtures.connect(top, out, lut, "O");
            return this;
        }

        Circuit flop(String type, String name, String d, String q, String ce, String reset) {
            return flop(type, name, d, q, ce, reset, "clk", null);
        }

        Circuit flop(String type, String name, String d, String q, String ce, String reset,
                String clock, String init) {
            String resetPin = type.equals("FDSE") ? "S" : type.equals("FDCE") ? "CLR" : "R";
            EDIFCell cell = fixture.primitive(type, new String[] { "C", "CE", "D", resetPin },
                    new String[] { "Q" });
            EDIFCellInst flop = LecNetlistFixtures.add(top, name, cell);
            if (init != null) {
                LecNetlistFixtures.setProperty(flop, "INIT", init);
            }
            LecNetlistFixtures.connect(top, d, flop, "D");
            LecNetlistFixtures.connect(top, q, flop, "Q");
            LecNetlistFixtures.connect(top, ce, flop, "CE");
            LecNetlistFixtures.connect(top, reset, flop, resetPin);
            LecNetlistFixtures.connect(top, clock, flop, "C");
            return this;
        }

        EDIFNetlist netlist() {
            return fixture.getNetlist();
        }
    }
}
