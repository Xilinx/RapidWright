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
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;

/**
 * Global clock buffers inserted or replicated between two netlists, as a
 * placer does on high fanout nets: which buffers are transparent, which stay
 * terminals, and that a transparent buffer hides no change of the logic around
 * it.
 */
public class TestClockBuffers {

    private static final int LOADS = 9;

    private static final String VERSAL_PART = "xcvc1902-vsva2197-2MP-e-S";

    private static final String[] DATA = { "a", "b", "c", "d" };

    @BeforeAll
    public static void loadSolver() {
        SatMiter.isAvailable();
    }

    // ---------------------------------------------------------------- designs

    /**
     * The register bank with each load's clock enable and reset taken from the
     * given nets.
     */
    private static Circuit bank(Circuit circuit, String[] enNets, String[] rstNets) {
        for (int i = 0; i < LOADS; i++) {
            circuit.flop("FDRE", "ff" + i, DATA[i % DATA.length], "q" + i, enNets[i],
                    rstNets[i], "clk", null).out("q" + i, "q" + i);
        }
        return circuit;
    }

    private static Circuit reference() {
        return reference(LecNetlistFixtures.ULTRASCALE_PLUS_PART);
    }

    private static Circuit reference(String part) {
        return bank(new Circuit(false, part), fill("en"), fill("rst"));
    }

    private static String[] fill(String net) {
        String[] nets = new String[LOADS];
        Arrays.fill(nets, net);
        return nets;
    }

    /**
     * Splits the loads into {@code replicas} disjoint, contiguous groups, each
     * fed from its own buffer of {@code net}, named {@code <net>_buf<k>}.
     */
    private static String[] regional(Circuit circuit, String type, String net, int replicas) {
        String[] nets = new String[LOADS];
        for (int k = 0; k < replicas; k++) {
            circuit.buffer(type, net + "_buf" + k, net, net + "_g" + k);
        }
        for (int i = 0; i < LOADS; i++) {
            nets[i] = net + "_g" + (i * replicas / LOADS);
        }
        return nets;
    }

    private static LecHarness check(Circuit ref, Circuit imp) {
        return LecHarness.run(ref.netlist(), imp.netlist());
    }

    private static LecHarness check(Circuit ref, Circuit imp, RetimingMap map) {
        return LecHarness.run(ref.netlist(), imp.netlist(), new LecConfig(), map);
    }

    private static RetimingMap map(String... lines) {
        return RetimingMap.parse(Arrays.asList(lines), "test");
    }

    private static void assertFinding(LecHarness result, LecDifferenceType type,
            String subject) {
        result.assertFail();
        LecDifference found = null;
        for (LecDifference difference : result.getReport().getDifferences()) {
            if (difference.getType() == type
                    && (subject == null || difference.getSubject().equals(subject))) {
                found = difference;
            }
        }
        Assertions.assertNotNull(found, "Expected " + type + " on " + subject
                + System.lineSeparator() + result.getOutput());
    }

    // ------------------------------------------------------- global buffers

    /** One global buffer inserted on each high fanout net, and on the clock. */
    @Test
    public void testSingleBufferInsertionPasses() {
        for (String type : new String[] { "BUFG", "BUFG_FABRIC" }) {
            // BUFG_FABRIC exists on Versal only
            String part = type.equals("BUFG") ? LecNetlistFixtures.ULTRASCALE_PLUS_PART
                    : VERSAL_PART;
            Circuit imp = new Circuit(false, part);
            bank(imp, regional(imp, type, "en", 1), regional(imp, type, "rst", 1));
            check(reference(part), imp).assertPass();
        }
        // Clock pins are checked cones too; a buffered clock is the same clock.
        Circuit imp = new Circuit().buffer("BUFG", "clk_buf", "clk", "clk_g");
        for (int i = 0; i < LOADS; i++) {
            imp.flop("FDRE", "ff" + i, DATA[i % DATA.length], "q" + i, "en", "rst", "clk_g",
                    null).out("q" + i, "q" + i);
        }
        check(reference(), imp).assertPass();
    }

    /** Three regional replicas per net, each driving a disjoint load subset. */
    @Test
    public void testRegionalReplicasPass() {
        for (String part : new String[] { VERSAL_PART, LecNetlistFixtures.ULTRASCALE_PLUS_PART }) {
            // BUFG_FABRIC exists on Versal only
            String type = part.equals(VERSAL_PART) ? "BUFG_FABRIC" : "BUFG";
            Circuit imp = new Circuit(false, part);
            bank(imp, regional(imp, type, "en", 3), regional(imp, type, "rst", 3));
            LecHarness result = check(reference(part), imp);
            result.assertPass();
            Assertions.assertFalse(result.getOutput().contains(type),
                    "A transparent buffer should raise no warning" + System.lineSeparator()
                            + result.getOutput());
        }
    }

    /**
     * A load moved to a replica of a different net: ff4 takes its clock
     * enable from a replica of the reset.
     */
    @Test
    public void testLoadOnReplicaOfOtherNetFails() {
        Circuit imp = new Circuit(false, VERSAL_PART);
        String[] en = regional(imp, "BUFG_FABRIC", "en", 3);
        String[] rst = regional(imp, "BUFG_FABRIC", "rst", 3);
        en[4] = rst[4];
        bank(imp, en, rst);
        LecHarness result = check(reference(VERSAL_PART), imp);
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff4/CE");
        Assertions.assertEquals(1, result.getReport().getDifferences().size(),
                result.getOutput());
    }

    /** {@code BUFGCE} with its enable tied high or left at its default. */
    @Test
    public void testBufgceWithEnableTiedPasses() {
        for (String ce : new String[] { "vcc", null }) {
            Circuit imp = new Circuit();
            String[] en = new String[LOADS];
            for (int k = 0; k < 3; k++) {
                imp.bufgce("en_buf" + k, "en", "en_g" + k, ce);
            }
            for (int i = 0; i < LOADS; i++) {
                en[i] = "en_g" + (i % 3);
            }
            bank(imp, en, fill("rst"));
            check(reference(), imp).assertPass();
        }
        // Inverted twice is still enabled.
        Circuit imp = new Circuit().bufgce("en_buf", "en", "en_g", "gnd")
                .prop("en_buf", "IS_CE_INVERTED", "1'b1");
        check(reference(), bank(imp, fill("en_g"), fill("rst"))).assertPass();
    }

    /**
     * A {@code BUFGCE} that can gate is not a buffer. It stays a terminal, so
     * one present in IMP only is a difference, and the reason is reported.
     */
    @Test
    public void testBufgceWithLiveEnableIsUnsupported() {
        Circuit dynamic = new Circuit().bufgce("en_buf", "en", "en_g", "a");
        LecHarness result = check(reference(), bank(dynamic, fill("en_g"), fill("rst")));
        assertFinding(result, LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
        Assertions.assertTrue(result.getOutput().contains(CellClassifier.CLOCK_BUFFER_KEPT
                + ": BUFGCE whose CE is not tied active"), result.getOutput());

        Circuit inverted = new Circuit().bufgce("en_buf", "en", "en_g", "vcc")
                .prop("en_buf", "IS_CE_INVERTED", "1'b1");
        assertFinding(check(reference(), bank(inverted, fill("en_g"), fill("rst"))),
                LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");

        Circuit low = new Circuit().bufgce("en_buf", "en", "en_g", "gnd");
        assertFinding(check(reference(), bank(low, fill("en_g"), fill("rst"))),
                LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
    }

    /**
     * A gated buffer in both netlists is still compared as a terminal, as it was
     * before buffers were modelled.
     */
    @Test
    public void testMatchedGatedBufferPasses() {
        Circuit ref = new Circuit().bufgce("en_buf", "en", "en_g", "a");
        Circuit imp = new Circuit().bufgce("en_buf", "en", "en_g", "a");
        check(bank(ref, fill("en_g"), fill("rst")), bank(imp, fill("en_g"), fill("rst")))
                .assertPass();
    }

    /** The input inversion attribute is honoured. */
    @Test
    public void testInvertedBufferInputFails() {
        Circuit imp = new Circuit().bufgce("en_buf", "en", "en_g", "vcc")
                .prop("en_buf", "IS_I_INVERTED", "1'b1");
        assertFinding(check(reference(), bank(imp, fill("en_g"), fill("rst"))),
                LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff0/CE");
    }

    /** {@code BUFGCE_DIV} is a buffer only when it divides by one. */
    @Test
    public void testBufgceDiv() {
        for (String divide : new String[] { null, "1" }) {
            Circuit imp = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "gnd");
            if (divide != null) {
                imp.prop("en_buf", "BUFGCE_DIVIDE", divide);
            }
            check(reference(), bank(imp, fill("en_g"), fill("rst"))).assertPass();
        }
        Circuit divided = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "gnd")
                .prop("en_buf", "BUFGCE_DIVIDE", "2");
        assertFinding(check(reference(), bank(divided, fill("en_g"), fill("rst"))),
                LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
        Circuit cleared = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "rst");
        assertFinding(check(reference(), bank(cleared, fill("en_g"), fill("rst"))),
                LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
    }

    // ----------------------------------------------- with register replicas

    /**
     * A register {@code r} computing {@code a & b} and feeding a bank of
     * loads through look up tables, optionally inside block {@code u}.
     *
     * @param drivers For each load, the register net it reads.
     */
    private static Circuit registerDriven(boolean hierarchical, String[] drivers,
            String... registers) {
        Circuit circuit = new Circuit(hierarchical, LecNetlistFixtures.ULTRASCALE_PLUS_PART)
                .lut("f", "4'h8", "fo", "a", "b");
        for (String register : registers) {
            circuit.flop("FDRE", register, "fo", register + "_q", "en", "rst", "clk", null);
        }
        for (int i = 0; i < LOADS; i++) {
            circuit.lut("g" + i, "4'h6", "go" + i, drivers[i], DATA[i % DATA.length])
                    .flop("FDRE", "ff" + i, "go" + i, "q" + i, "vcc", "gnd", "clk", null)
                    .out("q" + i, "q" + i);
        }
        return circuit;
    }

    private static String[] split(String... nets) {
        String[] drivers = new String[LOADS];
        for (int i = 0; i < LOADS; i++) {
            drivers[i] = nets[i * nets.length / LOADS];
        }
        return drivers;
    }

    /**
     * Replication and buffering together, as a placer does them: replicas of
     * {@code r} whose enable and clock come through regional buffers.
     */
    @Test
    public void testReplicationBehindBuffers() {
        Circuit ref = registerDriven(false, split("r_q"), "r");
        Circuit imp = new Circuit(false, VERSAL_PART)
                .buffer("BUFG_FABRIC", "en_buf0", "en", "en_g0")
                .buffer("BUFG_FABRIC", "en_buf1", "en", "en_g1")
                .buffer("BUFG", "clk_buf", "clk", "clk_g")
                .lut("f", "4'h8", "fo", "a", "b")
                .flop("FDRE", "r", "fo", "r_q", "en_g0", "rst", "clk", null)
                .flop("FDRE", "r_rep", "fo", "r_rep_q", "en_g1", "rst", "clk_g", null);
        String[] drivers = split("r_q", "r_rep_q");
        for (int i = 0; i < LOADS; i++) {
            imp.lut("g" + i, "4'h6", "go" + i, drivers[i], DATA[i % DATA.length])
                    .flop("FDRE", "ff" + i, "go" + i, "q" + i, "vcc", "gnd", "clk", null)
                    .out("q" + i, "q" + i);
        }
        ref.setPart(VERSAL_PART);
        check(ref, imp, map("IMP r_rep = REF r/Q")).assertPass();
    }

    // ------------------------------------------------------ further limits

    /**
     * A buffer on one branch of a net only: half of the loads read the
     * buffered copy, the others still read the net itself.
     */
    @Test
    public void testBufferOnOneBranchOnlyPasses() {
        for (boolean bufgce : new boolean[] { false, true }) {
            Circuit imp = bufgce ? new Circuit().bufgce("en_buf", "en", "en_g", "vcc")
                    : new Circuit().buffer("BUFG", "en_buf", "en", "en_g");
            String[] en = fill("en");
            for (int i = 0; i < LOADS / 2; i++) {
                en[i] = "en_g";
            }
            check(reference(), bank(imp, en, fill("rst"))).assertPass();
        }
    }

    /** A buffer inside a hierarchical block, on its enable and its clock. */
    @Test
    public void testBufferInsideBlockPasses() {
        String part = LecNetlistFixtures.ULTRASCALE_PLUS_PART;
        Circuit ref = bank(new Circuit(true, part), fill("en"), fill("rst"));
        Circuit imp = new Circuit(true, part).bufgce("en_buf", "en", "en_g", "vcc")
                .buffer("BUFG", "clk_buf", "clk", "clk_g");
        for (int i = 0; i < LOADS; i++) {
            imp.flop("FDRE", "ff" + i, DATA[i % DATA.length], "q" + i, "en_g", "rst", "clk_g",
                    null).out("q" + i, "q" + i);
        }
        check(ref, imp).assertPass();
    }

    /**
     * A transparent buffer hides no change of the logic in front of it or
     * behind it.
     */
    @Test
    public void testLogicChangeAroundBufferFails() {
        // In front: the buffered enable is a & b in REF but a | b in IMP.
        Circuit ref = bank(new Circuit().lut("f", "4'h8", "fo", "a", "b"), fill("fo"),
                fill("rst"));
        Circuit front = new Circuit().lut("f", "4'hE", "fo", "a", "b").buffer("BUFG", "fo_buf",
                "fo", "fo_g");
        LecHarness result = check(ref, bank(front, fill("fo_g"), fill("rst")));
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff0/CE");
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff8/CE");

        // Behind: the enable is buffered and one load's data input moved.
        Circuit behind = new Circuit().bufgce("en_buf", "en", "en_g", "vcc");
        for (int i = 0; i < LOADS; i++) {
            behind.flop("FDRE", "ff" + i, i == 3 ? "a" : DATA[i % DATA.length], "q" + i,
                    "en_g", "rst", "clk", null).out("q" + i, "q" + i);
        }
        result = check(reference(), behind);
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff3/D");
        Assertions.assertEquals(1, result.getReport().getDifferences().size(),
                result.getOutput());

        // A buffered clock, with one load clocked from elsewhere.
        Circuit clocked = new Circuit().buffer("BUFG", "clk_buf", "clk", "clk_g");
        for (int i = 0; i < LOADS; i++) {
            clocked.flop("FDRE", "ff" + i, DATA[i % DATA.length], "q" + i, "en", "rst",
                    i == 5 ? "a" : "clk_g", null).out("q" + i, "q" + i);
        }
        result = check(reference(), clocked);
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff5/C");
        Assertions.assertEquals(1, result.getReport().getDifferences().size(),
                result.getOutput());
    }

    /**
     * An enable computed by logic is not tied, even when the logic is
     * constant: the buffer stays a terminal.
     */
    @Test
    public void testEnableFromConstantLogicStaysTerminal() {
        Circuit imp = new Circuit().lut("one", "2'h3", "one_o", "a").bufgce("en_buf", "en",
                "en_g", "one_o");
        LecHarness result = check(reference(), bank(imp, fill("en_g"), fill("rst")));
        assertFinding(result, LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
        Assertions.assertTrue(result.getOutput().contains(CellClassifier.CLOCK_BUFFER_KEPT
                + ": BUFGCE whose CE is not tied active"), result.getOutput());
    }

    /** A buffer that synchronises its start to configuration stays a terminal. */
    @Test
    public void testStartupSyncStaysTerminal() {
        for (String type : new String[] { "BUFGCE", "BUFGCE_DIV" }) {
            Circuit imp = type.equals("BUFGCE")
                    ? new Circuit().bufgce("en_buf", "en", "en_g", "vcc")
                    : new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "gnd");
            imp.prop("en_buf", "STARTUP_SYNC", "TRUE");
            LecHarness result = check(reference(), bank(imp, fill("en_g"), fill("rst")));
            assertFinding(result, LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
            Assertions.assertTrue(result.getOutput().contains(CellClassifier.CLOCK_BUFFER_KEPT
                    + ": " + type + " with STARTUP_SYNC other than FALSE"), result.getOutput());
        }
        Circuit off = new Circuit().bufgce("en_buf", "en", "en_g", "vcc").prop("en_buf",
                "STARTUP_SYNC", "FALSE");
        check(reference(), bank(off, fill("en_g"), fill("rst"))).assertPass();
    }

    /**
     * Clock buffers not provably {@code O = I} stay opaque terminals, even
     * wired as a plain buffer.
     */
    @Test
    public void testOtherClockBuffersStayTerminals() {
        for (String type : new String[] { "BUFGCE_1", "BUFGCTRL", "BUFGMUX", "BUFGMUX_1",
                "BUFGMUX_CTRL", "BUFG_GT", "BUFG_GT_SYNC", "BUFG_PS", "MBUFGCE", "MBUFGCE_DIV",
                "MBUFG_GT", "MBUFG_PS" }) {
            Circuit imp = new Circuit(false, VERSAL_PART).buffer(type, "en_buf", "en", "en_g");
            LecHarness result = check(reference(VERSAL_PART),
                    bank(imp, fill("en_g"), fill("rst")));
            assertFinding(result, LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
            Assertions.assertTrue(result.getOutput().contains(type), result.getOutput());
        }
    }

    // ------------------------------------------------- settings and controls

    private static void assertKept(LecHarness result, String reason) {
        assertFinding(result, LecDifferenceType.TERMINAL_MISSING_IN_REF, "en_buf");
        Assertions.assertTrue(result.getOutput().contains(CellClassifier.CLOCK_BUFFER_KEPT
                + ": " + reason), result.getOutput());
    }

    /** A Versal BUFGCE or BUFGCE_DIV on the enable, with its controls tied. */
    private static Circuit tiedBuffer(String type) {
        Circuit imp = new Circuit(false, VERSAL_PART);
        return type.equals("BUFGCE") ? imp.bufgce("en_buf", "en", "en_g", "vcc")
                : imp.bufgceDiv("en_buf", "en", "en_g", "vcc", "gnd");
    }

    private static LecHarness checkVersal(Circuit imp) {
        return check(reference(VERSAL_PART), bank(imp, fill("en_g"), fill("rst")));
    }

    /**
     * A synchronised enable or clear starts with the buffer disabled or
     * cleared, so the buffer misses the first edges of its input even with its
     * controls tied: it stays a terminal.
     */
    @Test
    public void testHardsyncStaysTerminal() {
        for (String type : new String[] { "BUFGCE", "BUFGCE_DIV" }) {
            for (String value : new String[] { "HARDSYNC", "\"hardsync\"", "OTHER" }) {
                assertKept(checkVersal(tiedBuffer(type).prop("en_buf", "CE_TYPE", value)),
                        type + " with CE_TYPE other than SYNC or ASYNC");
            }
            for (String value : new String[] { "SYNC", "ASYNC", "\"Sync\"", "async" }) {
                checkVersal(tiedBuffer(type).prop("en_buf", "CE_TYPE", value)).assertPass();
            }
        }
        for (String value : new String[] { "TRUE", "\"true\"", "1'b1" }) {
            assertKept(checkVersal(tiedBuffer("BUFGCE_DIV").prop("en_buf", "HARDSYNC_CLR",
                    value)), "BUFGCE_DIV with HARDSYNC_CLR other than FALSE");
        }
        for (String value : new String[] { "FALSE", "\"False\"" }) {
            checkVersal(tiedBuffer("BUFGCE_DIV").prop("en_buf", "HARDSYNC_CLR", value))
                    .assertPass();
        }
    }

    /** Other spellings of the attributes that make a buffer transparent. */
    @Test
    public void testAttributeEncodings() {
        for (String value : new String[] { "false", "\"False\"", " FALSE " }) {
            checkVersal(tiedBuffer("BUFGCE").prop("en_buf", "STARTUP_SYNC", value)).assertPass();
        }
        for (String value : new String[] { "true", "\"TRUE\"" }) {
            assertKept(checkVersal(tiedBuffer("BUFGCE").prop("en_buf", "STARTUP_SYNC", value)),
                    "BUFGCE with STARTUP_SYNC other than FALSE");
        }
        checkVersal(tiedBuffer("BUFGCE_DIV").prop("en_buf", "BUFGCE_DIVIDE", 1)).assertPass();
        checkVersal(tiedBuffer("BUFGCE_DIV").prop("en_buf", "BUFGCE_DIVIDE", "\"1\""))
                .assertPass();
        assertKept(checkVersal(tiedBuffer("BUFGCE_DIV").prop("en_buf", "BUFGCE_DIVIDE", 2)),
                "BUFGCE_DIV with BUFGCE_DIVIDE other than 1");
        // Not recognised as 1, so kept, which is safe
        assertKept(checkVersal(tiedBuffer("BUFGCE_DIV").prop("en_buf", "BUFGCE_DIVIDE",
                "32'd1")), "BUFGCE_DIV with BUFGCE_DIVIDE other than 1");
    }

    /**
     * An inversion attribute that cannot be read keeps the buffer a terminal,
     * with the reason in a warning, rather than failing classification. The
     * check then refuses the value where it reads the terminal's pins, as it
     * does for any primitive terminal.
     */
    @Test
    public void testUnreadableInversionKeepsTerminal() {
        for (String pin : new String[] { "CE", "CLR", "I" }) {
            String key = "IS_" + pin + "_INVERTED";
            Circuit imp = bank(tiedBuffer("BUFGCE_DIV").prop("en_buf", key, "maybe"),
                    fill("en_g"), fill("rst"));
            LecLog log = new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
            FlatNetlist flat = FlatNetlist.build(imp.netlist(), log);
            Connectivity conn = ConnectivityBuilder.build(flat, log);
            CellClassifier classifier = new CellClassifier(Series.Versal, new LecConfig(), log);
            Assertions.assertEquals(CellRole.TERMINAL,
                    classifier.classify(flat, conn, flat.findInstId("en_buf")));
            Assertions.assertTrue(log.getSubjectCounts(CellClassifier.CLOCK_BUFFER_KEPT)
                    .containsKey("BUFGCE_DIV whose " + key + " value 'maybe' cannot be read"),
                    log.getAllCounts().toString());
            LecException refused = Assertions.assertThrows(LecException.class,
                    () -> check(reference(VERSAL_PART), imp));
            Assertions.assertTrue(refused.getMessage().contains("Unreadable " + key),
                    refused.getMessage());
        }
    }

    /**
     * An unconnected enable is tied only where the pin default table lists its
     * tie value; without a part, or with an inversion attribute, it is not
     * known and the buffer stays a terminal.
     */
    @Test
    public void testUnconnectedEnable() {
        String us = LecNetlistFixtures.ULTRASCALE_PLUS_PART;
        for (String part : new String[] { us, VERSAL_PART }) {
            Circuit imp = new Circuit(false, part).bufgce("en_buf", "en", "en_g", null);
            check(reference(part), bank(imp, fill("en_g"), fill("rst"))).assertPass();
        }
        Circuit noPart = new Circuit(false, null).bufgce("en_buf", "en", "en_g", null);
        LecHarness result = check(reference(null), bank(noPart, fill("en_g"), fill("rst")));
        Assertions.assertFalse(result.getOutput().contains(LecNetlistFixtures.ULTRASCALE_PLUS_PART),
                result.getOutput());
        assertKept(result, "BUFGCE whose CE is not tied active");
        Circuit inverted = new Circuit().bufgce("en_buf", "en", "en_g", null)
                .prop("en_buf", "IS_CE_INVERTED", "1'b1");
        assertKept(check(reference(), bank(inverted, fill("en_g"), fill("rst"))),
                "BUFGCE whose CE is not tied active");
    }

    /**
     * An enable on a net nothing drives is unconnected; one on a net with a
     * second driver beside the constant is not tied.
     */
    @Test
    public void testEnableOnUndrivenOrMultiDrivenNet() {
        Circuit undriven = new Circuit().bufgce("en_buf", "en", "en_g", "floating");
        check(reference(), bank(undriven, fill("en_g"), fill("rst"))).assertPass();
        Circuit shorted = new Circuit().lut("x", "2'h1", "vcc", "a").bufgce("en_buf", "en",
                "en_g", "vcc");
        assertKept(check(reference(), bank(shorted, fill("en_g"), fill("rst"))),
                "BUFGCE whose CE is not tied active");
    }

    /** The clear of a BUFGCE_DIV must be tied inactive, after inversion. */
    @Test
    public void testBufgceDivClear() {
        String part = LecNetlistFixtures.ULTRASCALE_PLUS_PART;
        Circuit high = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "vcc");
        assertKept(check(reference(), bank(high, fill("en_g"), fill("rst"))),
                "BUFGCE_DIV whose CLR is not tied inactive");
        Circuit invertedLow = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "gnd")
                .prop("en_buf", "IS_CLR_INVERTED", "1'b1");
        assertKept(check(reference(), bank(invertedLow, fill("en_g"), fill("rst"))),
                "BUFGCE_DIV whose CLR is not tied inactive");
        Circuit invertedHigh = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", "vcc")
                .prop("en_buf", "IS_CLR_INVERTED", "1'b1");
        check(reference(), bank(invertedHigh, fill("en_g"), fill("rst"))).assertPass();
        for (String p : new String[] { part, VERSAL_PART }) {
            Circuit open = new Circuit(false, p).bufgceDiv("en_buf", "en", "en_g", "vcc", null);
            check(reference(p), bank(open, fill("en_g"), fill("rst"))).assertPass();
        }
        Circuit openInverted = new Circuit().bufgceDiv("en_buf", "en", "en_g", "vcc", null)
                .prop("en_buf", "IS_CLR_INVERTED", "1'b1");
        assertKept(check(reference(), bank(openInverted, fill("en_g"), fill("rst"))),
                "BUFGCE_DIV whose CLR is not tied inactive");
    }

    /**
     * A buffer with the same name, transparent in one netlist and a terminal in
     * the other, is a terminal present in one netlist only, either way round.
     */
    @Test
    public void testTransparentInOneNetlistOnlyFails() {
        Circuit tied = new Circuit().bufgce("en_buf", "en", "en_g", "vcc");
        Circuit gated = new Circuit().bufgce("en_buf", "en", "en_g", "a");
        assertFinding(check(bank(tied, fill("en_g"), fill("rst")),
                bank(gated, fill("en_g"), fill("rst"))), LecDifferenceType.TERMINAL_MISSING_IN_REF,
                "en_buf");
        tied = new Circuit().bufgce("en_buf", "en", "en_g", "vcc");
        gated = new Circuit().bufgce("en_buf", "en", "en_g", "a");
        assertFinding(check(bank(gated, fill("en_g"), fill("rst")),
                bank(tied, fill("en_g"), fill("rst"))), LecDifferenceType.TERMINAL_MISSING_IN_IMP,
                "en_buf");
    }

    // ------------------------------------------------------ around buffers

    /** Two buffered clocks, with one load moved from one to the other. */
    @Test
    public void testSwapBetweenBufferedClocksFails() {
        Circuit[] sides = new Circuit[2];
        for (int side = 0; side < 2; side++) {
            Circuit c = new Circuit().buffer("BUFG", "clka_buf", "clk", "clk_a")
                    .buffer("BUFG", "clkb_buf", "clk2", "clk_b");
            for (int i = 0; i < LOADS; i++) {
                boolean onB = side == 0 ? i == 5 : i == 6;
                c.flop("FDRE", "ff" + i, DATA[i % DATA.length], "q" + i, "en", "rst",
                        onB ? "clk_b" : "clk_a", null).out("q" + i, "q" + i);
            }
            sides[side] = c;
        }
        LecHarness result = check(sides[0], sides[1]);
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff5/C");
        assertFinding(result, LecDifferenceType.CONE_FUNCTION_DIFFERS, "ff6/C");
        Assertions.assertEquals(2, result.getReport().getDifferences().size(),
                result.getOutput());
    }

    /**
     * A ring through a transparent buffer is a combinational loop: it is
     * reported as one, where a buffer kept as a terminal used to cut it.
     */
    @Test
    public void testLoopThroughBufferIsReported() {
        Circuit[] sides = new Circuit[2];
        for (int side = 0; side < 2; side++) {
            sides[side] = new Circuit().lut("inv", "2'h1", "inv_o", "osc")
                    .buffer("BUFG", "osc_buf", "inv_o", "osc")
                    .flop("FDRE", "ff", "osc", "q", "vcc", "gnd", "clk", null).out("q", "q");
        }
        LecHarness result = check(sides[0], sides[1]);
        Assertions.assertTrue(
                result.getReport().getCount(LecDifferenceType.COMBINATIONAL_LOOP) > 0,
                result.getOutput());
    }

    /** A black box behind a buffer reads the buffered net's function. */
    @Test
    public void testBlackBoxBehindBuffer() {
        Circuit ref = new Circuit().blackBox("bb", "en", "q");
        ref.out("q", "q");
        Circuit imp = new Circuit().buffer("BUFG", "en_buf", "en", "en_g")
                .blackBox("bb", "en_g", "q");
        imp.out("q", "q");
        check(ref, imp).assertPass();
        Circuit moved = new Circuit().buffer("BUFG", "en_buf", "rst", "en_g")
                .blackBox("bb", "en_g", "q");
        moved.out("q", "q");
        ref = new Circuit().blackBox("bb", "en", "q");
        ref.out("q", "q");
        assertFinding(check(ref, moved), LecDifferenceType.CONE_FUNCTION_DIFFERS, null);
    }

    // ------------------------------------------------------------ self-check

    /**
     * The self-check moves a retimed register to another clock and expects a
     * difference. A replica of the same clock behind a transparent buffer is
     * not another clock: with no other clock in the design the case finds
     * nowhere to apply, and with one it moves the register there.
     */
    @Test
    public void testSelfCheckMovesToAnotherClockOnly(@TempDir Path dir) throws IOException {
        for (boolean secondClock : new boolean[] { false, true }) {
            Circuit ref = new Circuit()
                    .flop("FDRE", "ra", "a", "qa", "vcc", "gnd", "clk", null)
                    .flop("FDRE", "rb", "b", "qb", "vcc", "gnd", "clk", null)
                    .lut("f", "4'h8", "fo", "qa", "qb")
                    .flop("FDRE", "out", "fo", "q", "vcc", "gnd", "clk", null)
                    .out("q", "q");
            Circuit imp = new Circuit()
                    .buffer("BUFG", "clk_buf0", "clk", "clk_g0")
                    .buffer("BUFG", "clk_buf1", "clk", "clk_g1")
                    .lut("f", "4'h8", "fo", "a", "b")
                    .flop("FDRE", "r", "fo", "qr", "vcc", "gnd", "clk_g0", null)
                    .flop("FDRE", "out", "qr", "q", "vcc", "gnd", "clk_g1", null)
                    .out("q", "q");
            if (secondClock) {
                for (Circuit c : new Circuit[] { ref, imp }) {
                    c.flop("FDRE", "z", "c", "qz", "vcc", "gnd", "clk2", null).out("qz", "qz");
                }
            }
            Path refFile = dir.resolve("ref.edf");
            Path impFile = dir.resolve("imp.edf");
            EDIFTools.writeEDIFFile(refFile, ref.netlist(),
                    LecNetlistFixtures.ULTRASCALE_PLUS_PART);
            EDIFTools.writeEDIFFile(impFile, imp.netlist(),
                    LecNetlistFixtures.ULTRASCALE_PLUS_PART);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream original = System.out;
            int exit;
            try {
                System.setOut(new PrintStream(buffer));
                exit = new LecSelfCheck(refFile, impFile, map("IMP r = REF f/O")).run();
            } finally {
                System.setOut(original);
            }
            String output = buffer.toString();
            Assertions.assertEquals(LogicEquivalenceChecker.EXIT_PASS, exit, output);
            String line = Arrays.stream(output.split("\\R"))
                    .filter(l -> l.startsWith("retimed register moved to another clock"))
                    .findFirst().orElse("");
            Assertions.assertEquals(!secondClock, line.contains("SKIPPED"), output);
            Assertions.assertEquals(secondClock, line.contains("clk2"), output);
        }
    }

    // ---------------------------------------------------------------- builder

    /**
     * A design built from named nets, either flat or with all of its logic
     * inside block {@code u}, with the common ports and ties.
     */
    private static final class Circuit {

        private final LecNetlistFixtures fixture = new LecNetlistFixtures("top");

        private final EDIFCell top = fixture.getTop();

        /** Where the logic lives: the top cell, or the cell of block u. */
        private final EDIFCell body;

        private final EDIFCellInst block;

        Circuit() {
            this(false, LecNetlistFixtures.ULTRASCALE_PLUS_PART);
        }

        /** @param part The part, or null for a netlist that names none. */
        Circuit(boolean hierarchical, String part) {
            if (part != null) {
                setPart(part);
            } else {
                fixture.getNetlist().getDesign().removeProperty(EDIFTools.EDIF_PART_PROP);
            }
            body = hierarchical ? fixture.newHierCell("core") : top;
            block = hierarchical ? LecNetlistFixtures.add(top, "u", body) : null;
            for (String input : new String[] { "a", "b", "c", "d", "en", "rst", "clk",
                    "clk2" }) {
                port(input, EDIFDirection.INPUT);
            }
            LecNetlistFixtures.connect(body, "vcc",
                    LecNetlistFixtures.add(body, "VCC", fixture.vcc()), "P");
            LecNetlistFixtures.connect(body, "gnd",
                    LecNetlistFixtures.add(body, "GND", fixture.gnd()), "G");
        }

        void setPart(String part) {
            fixture.getNetlist().getDesign().addProperty(EDIFTools.EDIF_PART_PROP, part,
                    EDIFValueType.STRING);
        }

        /** A design port, passed through block u's boundary if there is one. */
        private void port(String name, EDIFDirection direction) {
            LecNetlistFixtures.port(top, name, direction);
            if (block == null) {
                LecNetlistFixtures.connect(top, name, top, name);
                return;
            }
            LecNetlistFixtures.port(body, name, direction);
            LecNetlistFixtures.connect(body, name, body, name);
            LecNetlistFixtures.connect(top, name, top, name, block, name);
        }

        Circuit out(String port, String net) {
            port(port, EDIFDirection.OUTPUT);
            if (!port.equals(net)) {
                throw new IllegalArgumentException("Name the output net after its port");
            }
            return this;
        }

        Circuit lut(String name, String init, String out, String... inputs) {
            EDIFCellInst lut = LecNetlistFixtures.add(body, name, fixture.lut(inputs.length));
            LecNetlistFixtures.setProperty(lut, "INIT", init);
            for (int i = 0; i < inputs.length; i++) {
                LecNetlistFixtures.connect(body, inputs[i], lut, "I" + i);
            }
            LecNetlistFixtures.connect(body, out, lut, "O");
            return this;
        }

        Circuit flop(String type, String name, String d, String q, String ce, String reset,
                String clock, String init) {
            String resetPin = type.equals("FDSE") ? "S" : "R";
            EDIFCell cell = fixture.primitive(type, new String[] { "C", "CE", "D", resetPin },
                    new String[] { "Q" });
            EDIFCellInst flop = LecNetlistFixtures.add(body, name, cell);
            if (init != null) {
                LecNetlistFixtures.setProperty(flop, "INIT", init);
            }
            LecNetlistFixtures.connect(body, d, flop, "D");
            LecNetlistFixtures.connect(body, q, flop, "Q");
            LecNetlistFixtures.connect(body, ce, flop, "CE");
            LecNetlistFixtures.connect(body, reset, flop, resetPin);
            LecNetlistFixtures.connect(body, clock, flop, "C");
            return this;
        }

        /** A buffer of any type with only an {@code I} and an {@code O} pin. */
        Circuit buffer(String type, String name, String in, String out) {
            EDIFCellInst buf = LecNetlistFixtures.add(body, name,
                    fixture.primitive(type, new String[] { "I" }, new String[] { "O" }));
            LecNetlistFixtures.connect(body, in, buf, "I");
            LecNetlistFixtures.connect(body, out, buf, "O");
            return this;
        }

        /** A BUFGCE; a null enable leaves CE unconnected. */
        Circuit bufgce(String name, String in, String out, String ce) {
            EDIFCellInst buf = LecNetlistFixtures.add(body, name, fixture.primitive("BUFGCE",
                    new String[] { "I", "CE" }, new String[] { "O" }));
            LecNetlistFixtures.connect(body, in, buf, "I");
            LecNetlistFixtures.connect(body, out, buf, "O");
            if (ce != null) {
                LecNetlistFixtures.connect(body, ce, buf, "CE");
            }
            return this;
        }

        /** A BUFGCE_DIV; a null enable or clear leaves that pin unconnected. */
        Circuit bufgceDiv(String name, String in, String out, String ce, String clr) {
            EDIFCellInst buf = LecNetlistFixtures.add(body, name, fixture.primitive(
                    "BUFGCE_DIV", new String[] { "CLR", "I", "CE" }, new String[] { "O" }));
            LecNetlistFixtures.connect(body, in, buf, "I");
            LecNetlistFixtures.connect(body, out, buf, "O");
            if (ce != null) {
                LecNetlistFixtures.connect(body, ce, buf, "CE");
            }
            if (clr != null) {
                LecNetlistFixtures.connect(body, clr, buf, "CLR");
            }
            return this;
        }

        /** A black box: a cell with no contents and an I and an O pin. */
        Circuit blackBox(String name, String in, String out) {
            EDIFCell type = netlist().getWorkLibrary().getCell("opaque_ip");
            if (type == null) {
                type = fixture.newHierCell("opaque_ip");
                LecNetlistFixtures.port(type, "I", EDIFDirection.INPUT);
                LecNetlistFixtures.port(type, "O", EDIFDirection.OUTPUT);
            }
            EDIFCellInst box = LecNetlistFixtures.add(body, name, type);
            LecNetlistFixtures.connect(body, in, box, "I");
            LecNetlistFixtures.connect(body, out, box, "O");
            return this;
        }

        /** A property of integer type rather than a string. */
        Circuit prop(String instName, String key, int value) {
            body.getCellInst(instName).addProperty(key, value);
            return this;
        }

        Circuit prop(String instName, String key, String value) {
            LecNetlistFixtures.setProperty(body.getCellInst(instName), key, value);
            return this;
        }

        EDIFNetlist netlist() {
            return fixture.getNetlist();
        }
    }
}
