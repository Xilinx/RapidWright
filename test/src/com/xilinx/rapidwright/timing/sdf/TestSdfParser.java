/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing.sdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SdfParser} and {@link SdfWriter} against SDF text exercising every construct
 * and formatting quirk Vivado emits.
 */
public class TestSdfParser {

    @ParameterizedTest(name = "round-trip is byte-identical (fastCorner={0})")
    @ValueSource(booleans = {false, true})
    public void testRoundTripIsByteIdentical(boolean fastCorner, @TempDir Path tempDir)
            throws IOException {
        String content = fastCorner ? SdfTestFiles.adversarialSdfFastCorner()
                : SdfTestFiles.adversarialSdf();
        Path input = SdfTestFiles.write(tempDir, "input.sdf", content);

        SdfFile sdf = SdfParser.parse(input);
        // Write to a different file from the one read, so a leaked handle shows up as a
        // recognisable filename rather than as a confusing failure elsewhere.
        Path output = tempDir.resolve("output.sdf");
        SdfWriter.write(sdf, output);

        Assertions.assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output),
                "round-trip of the SDF was not byte-identical");
    }

    @Test
    public void testReparseIsEqual(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());

        SdfFile first = SdfParser.parse(input);
        Path output = tempDir.resolve("output.sdf");
        SdfWriter.write(first, output);
        SdfFile second = SdfParser.parse(output);

        Assertions.assertEquals(first.getCells(), second.getCells());
        Assertions.assertEquals(first.getDesign(), second.getDesign());
        Assertions.assertEquals(first.getTimeScale(), second.getTimeScale());
    }

    @Test
    public void testHeaderAndStructure(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());
        SdfFile sdf = SdfParser.parse(input);

        Assertions.assertEquals("3.0", sdf.getSdfVersion());
        Assertions.assertEquals("top_with_(CELL_in_the_name", sdf.getDesign());
        Assertions.assertEquals("XILINX", sdf.getVendor());
        Assertions.assertEquals("Vivado", sdf.getProgram());
        Assertions.assertEquals("2025.2", sdf.getProgramVersion());
        Assertions.assertEquals("/", sdf.getDivider());
        Assertions.assertEquals("1ps", sdf.getTimeScale());
        Assertions.assertEquals(1.0, sdf.getTenthsToPs() * 10, 1e-12);

        Assertions.assertEquals(6, sdf.getCells().size());

        // Exactly one top-level cell, and it must be last.
        SdfCell top = sdf.getTopCell();
        Assertions.assertNotNull(top);
        Assertions.assertSame(sdf.getCells().get(sdf.getCells().size() - 1), top);
        Assertions.assertEquals("", top.getInstance());
        Assertions.assertEquals(SdfCell.Style.TOP, top.getStyle());
        Assertions.assertEquals(2, sdf.getInterconnects().size());
    }

    @Test
    public void testOptionalBlocksAreTracked(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());
        SdfFile sdf = SdfParser.parse(input);

        SdfCell fdre = sdf.getCells().get(0);
        Assertions.assertTrue(fdre.hasDelay());
        Assertions.assertTrue(fdre.hasTimingCheck());
        Assertions.assertNull(fdre.getPathPulsePercent());

        SdfCell obuft = sdf.getCells().get(2);
        Assertions.assertTrue(obuft.hasDelay());
        Assertions.assertFalse(obuft.hasTimingCheck());
        Assertions.assertNotNull(obuft.getPathPulsePercent());

        // An MMCM is written with timing checks and no delays at all.
        SdfCell mmcm = sdf.getCells().get(3);
        Assertions.assertFalse(mmcm.hasDelay());
        Assertions.assertTrue(mmcm.hasTimingCheck());
        Assertions.assertTrue(mmcm.getDelayEntries().isEmpty());
    }

    @Test
    public void testTriStateIoPathWithAbsentDelvals(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());
        SdfFile sdf = SdfParser.parse(input);

        List<SdfDelayEntry> entries = sdf.getCells().get(2).getDelayEntries();
        Assertions.assertEquals(2, entries.size());

        SdfDelayValues values = entries.get(1).getValues();
        Assertions.assertEquals(6, values.size());
        // An absent delval is not the same thing as a delval of zero.
        Assertions.assertFalse(values.isPresent(0));
        Assertions.assertFalse(values.isPresent(1));
        for (int i = 2; i < 6; i++) {
            Assertions.assertTrue(values.isPresent(i), "slot " + i + " should be present");
        }
        Assertions.assertEquals(6294, values.getTenths(2, SdfDelayValues.MIN));
        Assertions.assertEquals(56021, values.getTenths(2, SdfDelayValues.MAX));
    }

    @Test
    public void testNegativeAndNegativeZeroValues(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());
        SdfFile sdf = SdfParser.parse(input);

        SdfTimingCheck setupHold = sdf.getCells().get(0).getTimingChecks().get(0);
        Assertions.assertEquals(SdfTimingCheck.Kind.SETUPHOLD, setupHold.getKind());
        Assertions.assertEquals(-360, setupHold.getFirstValues().getTenths(0, SdfDelayValues.MIN));
        Assertions.assertEquals(600, setupHold.getSecondValues().getTenths(0, SdfDelayValues.MIN));

        SdfDelayValues lut = sdf.getCells().get(4).getDelayEntries().get(0).getValues();
        Assertions.assertEquals(SdfDelayValues.NEG_ZERO, lut.getTenths(0, SdfDelayValues.MIN));
        Assertions.assertEquals(0.0, lut.getValue(0, SdfDelayValues.MIN), 0.0);
    }

    @Test
    public void testClockPortsAreIdentifiedFromTimingChecks(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());
        SdfFile sdf = SdfParser.parse(input);

        // A cell's clock pin is discoverable from its timing checks alone, with no per-device
        // table, which is what lets a sequential IOPATH be told apart from a combinational one on
        // any architecture.
        Assertions.assertEquals(java.util.Collections.singletonList("C"),
                sdf.getCells().get(0).getClockPorts());
        Assertions.assertEquals(java.util.Collections.singletonList("CLKIN1"),
                sdf.getCells().get(3).getClockPorts());
        // A cell with no timing checks reports no clocks rather than guessing.
        Assertions.assertTrue(sdf.getCells().get(2).getClockPorts().isEmpty());
    }

    @Test
    public void testAsyncResetConstructs(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "input.sdf", SdfTestFiles.adversarialSdf());
        SdfFile sdf = SdfParser.parse(input);

        SdfCell fdpe = sdf.getCells().get(1);
        Assertions.assertEquals("FDPE", fdpe.getCellType());

        // The reset-to-output arc: an edge-qualified source and a single delay value, unlike the
        // rise/fall pair every other IOPATH carries.
        List<SdfDelayEntry> entries = fdpe.getDelayEntries();
        Assertions.assertEquals(2, entries.size());
        Assertions.assertEquals(SdfEdge.NONE, entries.get(0).getSourceEdge());
        Assertions.assertEquals(SdfEdge.POSEDGE, entries.get(1).getSourceEdge());
        Assertions.assertEquals("PRE", entries.get(1).getSource());
        Assertions.assertEquals("Q", entries.get(1).getDestination());
        Assertions.assertEquals(1, entries.get(1).getValues().size());
        Assertions.assertEquals(2130, entries.get(1).getValues().getTenths(0, SdfDelayValues.MAX));

        // RECREM parses like SETUPHOLD and, like it, names the clock as its reference port.
        SdfTimingCheck recrem = fdpe.getTimingChecks().get(1);
        Assertions.assertEquals(SdfTimingCheck.Kind.RECREM, recrem.getKind());
        Assertions.assertEquals(SdfEdge.NEGEDGE, recrem.getFirstEdge());
        Assertions.assertEquals("PRE", recrem.getFirstPort());
        Assertions.assertEquals("C", recrem.getSecondPort());
        Assertions.assertEquals("C", recrem.getClockPort());
        Assertions.assertEquals(-330, recrem.getSecondValues().getTenths(0, SdfDelayValues.MAX));

        // The clock is still identified as exactly C: WIDTH on PRE must not make PRE a clock.
        Assertions.assertEquals(java.util.Collections.singletonList("C"), fdpe.getClockPorts());
    }

    @Test
    public void testGzippedInputIsDetectedByMagicNotExtension(@TempDir Path tempDir)
            throws IOException {
        // write_sdf -gzip emits a gzip stream while keeping the .sdf name, so detection cannot
        // rely on the extension.
        Path gzipped = tempDir.resolve("compressed.sdf");
        try (java.util.zip.GZIPOutputStream out =
                new java.util.zip.GZIPOutputStream(Files.newOutputStream(gzipped))) {
            out.write(SdfTestFiles.adversarialSdf().getBytes(StandardCharsets.UTF_8));
        }

        Assertions.assertTrue(SdfInput.isGzipped(gzipped));
        SdfFile sdf = SdfParser.parse(gzipped);
        Assertions.assertEquals(6, sdf.getCells().size());

        // Round-trip is defined on the uncompressed byte stream.
        Path output = tempDir.resolve("output.sdf");
        SdfWriter.write(sdf, output);
        Assertions.assertArrayEquals(SdfTestFiles.adversarialSdf().getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(output));
    }

    @Test
    public void testEmptySdfFile(@TempDir Path tempDir) throws IOException {
        Path input = SdfTestFiles.write(tempDir, "empty.sdf",
                "(DELAYFILE \n(SDFVERSION \"3.0\" )\n(DIVIDER /)\n(TIMESCALE 1ps)\n)\n");
        SdfFile sdf = SdfParser.parse(input);
        Assertions.assertTrue(sdf.getCells().isEmpty());
        Assertions.assertNull(sdf.getTopCell());

        Path output = tempDir.resolve("output.sdf");
        SdfWriter.write(sdf, output);
        Assertions.assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
    }

    // ------------------------------------------------------------------------------------------
    // Strictness: constructs RapidWright does not model must fail loudly, never be skipped.
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "rejects unsupported construct: {0}")
    @ValueSource(strings = {
            "      (COND cond_a (IOPATH I0 O (1.0:1.0:1.0) (1.0:1.0:1.0)))",
            "      (PORT I0 (1.0:1.0:1.0) (1.0:1.0:1.0))",
            "      (DEVICE (1.0:1.0:1.0) (1.0:1.0:1.0))",
            "      (RETAIN (1.0:1.0:1.0))",
    })
    public void testRejectsUnsupportedDelayConstructs(String line, @TempDir Path tempDir) {
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(DIVIDER /)\n"
                + "(TIMESCALE 1ps)\n"
                + "(CELL \n"
                + "  (CELLTYPE \"LUT6\")\n"
                + "  (INSTANCE lut)\n"
                + "  (DELAY \n"
                + "    (ABSOLUTE \n"
                + line + "\n"
                + "    )\n"
                + "  )\n"
                + ")\n"
                + ")\n";
        Path input = SdfTestFiles.write(tempDir, "bad.sdf", sdf);

        SdfParseException e = Assertions.assertThrows(SdfParseException.class,
                () -> SdfParser.parse(input));
        Assertions.assertTrue(e.getMessage().contains("unsupported"),
                "message should say the construct is unsupported: " + e.getMessage());
        Assertions.assertTrue(e.getLineNumber() >= 10,
                "should report the offending line, got " + e.getLineNumber());
    }

    @Test
    public void testRejectsUnsupportedTimingCheck(@TempDir Path tempDir) {
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(DIVIDER /)\n"
                + "(TIMESCALE 1ps)\n"
                + "(CELL \n"
                + "  (CELLTYPE \"FDRE\")\n"
                + "  (INSTANCE ff)\n"
                + "    (TIMINGCHECK\n"
                + "      (NOCHANGE (posedge R) (posedge C) (1.0:1.0:1.0) (1.0:1.0:1.0))\n"
                + "    )\n"
                + ")\n"
                + ")\n";
        Path input = SdfTestFiles.write(tempDir, "bad.sdf", sdf);

        SdfParseException e = Assertions.assertThrows(SdfParseException.class,
                () -> SdfParser.parse(input));
        Assertions.assertTrue(e.getMessage().contains("NOCHANGE"), e.getMessage());
        Assertions.assertEquals(9, e.getLineNumber());
    }

    @Test
    public void testRejectsUnsupportedHeaderField(@TempDir Path tempDir) {
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(VOLTAGE 1.0:1.0:1.0)\n"
                + "(TIMESCALE 1ps)\n"
                + ")\n";
        Path input = SdfTestFiles.write(tempDir, "bad.sdf", sdf);

        SdfParseException e = Assertions.assertThrows(SdfParseException.class,
                () -> SdfParser.parse(input));
        Assertions.assertTrue(e.getMessage().contains("VOLTAGE"), e.getMessage());
        Assertions.assertEquals(3, e.getLineNumber());
    }

    /**
     * Cross-checks the tokenizer's hand-rolled number reader against the straightforward one in
     * {@link SdfNumbers}.
     *
     * The tokenizer parses delay values straight out of its ring buffer for speed, duplicating
     * logic that {@link SdfNumbers#parseTenths(String)} expresses plainly. Two implementations of
     * the same thing are only safe while something holds them to each other, which is this test's
     * job.
     */
    @Test
    public void testTokenizerNumbersAgreeWithReferenceParser(@TempDir Path tempDir) {
        String[] literals = {
                "0.0", "-0.0", "1.0", "-1.0", "0.5", "9.9", "10.0", "99.9", "100.0",
                "5602.1", "-36.0", "214748364.7", "-214748364.7",   // the representable extremes
        };

        StringBuilder sb = new StringBuilder();
        sb.append("(DELAYFILE \n(SDFVERSION \"3.0\" )\n(TIMESCALE 1ps)\n")
          .append("(CELL \n  (CELLTYPE \"LUT6\")\n  (INSTANCE lut)\n")
          .append("  (DELAY \n    (ABSOLUTE \n");
        for (String literal : literals) {
            sb.append("      (IOPATH I0 O (").append(literal).append(':').append(literal)
              .append(':').append(literal).append("))\n");
        }
        sb.append("    )\n  )\n)\n)\n");

        Path input = SdfTestFiles.write(tempDir, "numbers.sdf", sb.toString());
        SdfFile sdf = SdfParser.parse(input);
        List<SdfDelayEntry> entries = sdf.getCells().get(0).getDelayEntries();
        Assertions.assertEquals(literals.length, entries.size());

        for (int i = 0; i < literals.length; i++) {
            int expected = SdfNumbers.parseTenths(literals[i]);
            int actual = entries.get(i).getValues().getTenths(0, SdfDelayValues.MIN);
            Assertions.assertEquals(expected, actual,
                    "tokenizer and reference parser disagree on '" + literals[i] + "'");
        }
    }

    @Test
    public void testReferenceParserRejectsTheSameThingsTheTokenizerDoes() {
        // Malformed or unrepresentable literals must be refused rather than rounded, in both.
        String[] bad = {"", "1", "1.", ".5", "1.25", "abc", "1.0x", "--1.0", "99999999999.9"};
        for (String literal : bad) {
            Assertions.assertThrows(NumberFormatException.class,
                    () -> SdfNumbers.parseTenths(literal),
                    "should have rejected '" + literal + "'");
        }
    }

    @Test
    public void testRejectsComments(@TempDir Path tempDir) {
        // Vivado never writes comments. Accepting them silently would mean the round-trip
        // guarantee quietly stopped holding.
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "// a comment\n"
                + "(TIMESCALE 1ps)\n"
                + ")\n";
        Path input = SdfTestFiles.write(tempDir, "bad.sdf", sdf);
        Assertions.assertThrows(SdfParseException.class, () -> SdfParser.parse(input));
    }

    @Test
    public void testRejectsTopCellThatIsNotLast(@TempDir Path tempDir) {
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(TIMESCALE 1ps)\n"
                + "(CELL \n"
                + "    (CELLTYPE \"top\")\n"
                + "    (INSTANCE )\n"
                + ")\n"
                + "(CELL \n"
                + "  (CELLTYPE \"FDRE\")\n"
                + "  (INSTANCE ff)\n"
                + ")\n"
                + ")\n";
        Path input = SdfTestFiles.write(tempDir, "bad.sdf", sdf);

        SdfParseException e = Assertions.assertThrows(SdfParseException.class,
                () -> SdfParser.parse(input));
        Assertions.assertTrue(e.getMessage().contains("must be the last cell"), e.getMessage());
    }

    @Test
    public void testRejectsTooMuchPrecision(@TempDir Path tempDir) {
        // Values are stored as exact tenths. Rounding a value with more precision would silently
        // change a delay, so it is refused instead.
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(TIMESCALE 1ps)\n"
                + "(CELL \n"
                + "  (CELLTYPE \"LUT6\")\n"
                + "  (INSTANCE lut)\n"
                + "  (DELAY \n"
                + "    (ABSOLUTE \n"
                + "      (IOPATH I0 O (1.25:1.0:1.0) (1.0:1.0:1.0))\n"
                + "    )\n"
                + "  )\n"
                + ")\n"
                + ")\n";
        Path input = SdfTestFiles.write(tempDir, "bad.sdf", sdf);

        SdfParseException e = Assertions.assertThrows(SdfParseException.class,
                () -> SdfParser.parse(input));
        Assertions.assertTrue(e.getMessage().contains("precision"), e.getMessage());
    }

    @Test
    public void testRejectsTruncatedFile(@TempDir Path tempDir) {
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(TIMESCALE 1ps)\n"
                + "(CELL \n"
                + "  (CELLTYPE \"LUT6\")\n";
        Path input = SdfTestFiles.write(tempDir, "truncated.sdf", sdf);
        Assertions.assertThrows(SdfParseException.class, () -> SdfParser.parse(input));
    }

    @Test
    public void testRejectsTrailingContent(@TempDir Path tempDir) {
        String sdf = "(DELAYFILE \n"
                + "(SDFVERSION \"3.0\" )\n"
                + "(TIMESCALE 1ps)\n"
                + ")\n"
                + "(DELAYFILE \n)\n";
        Path input = SdfTestFiles.write(tempDir, "trailing.sdf", sdf);

        SdfParseException e = Assertions.assertThrows(SdfParseException.class,
                () -> SdfParser.parse(input));
        Assertions.assertTrue(e.getMessage().contains("after the end of DELAYFILE"),
                e.getMessage());
    }
}
