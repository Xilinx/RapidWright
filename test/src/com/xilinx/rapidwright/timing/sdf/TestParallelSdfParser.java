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
 * Tests that the parallel parser agrees with the serial one exactly, at every chunk count and on
 * inputs designed to land chunk boundaries in awkward places.
 *
 * The serial {@link SdfParser} is the reference implementation, so "agrees with serial" is the
 * definition of correct here. Round-trip byte equality is asserted as well, since a merge that
 * reordered or dropped entries would show up there even if the cell count matched.
 */
public class TestParallelSdfParser {

    /**
     * Builds an SDF large enough to be split many ways, with a top-level cell that dominates it
     * exactly as a real one does.
     *
     * @param leafCells Number of leaf cells.
     * @param interconnects Number of INTERCONNECT entries in the top-level cell.
     * @return The SDF text.
     */
    static String generateSdf(int leafCells, int interconnects) {
        StringBuilder sb = new StringBuilder();
        sb.append("(DELAYFILE \n")
          .append("(SDFVERSION \"3.0\" )\n")
          .append("(DESIGN \"top\")\n")
          .append("(DATE \"Tue Aug 25 14:14:21 2026\")\n")
          .append("(VENDOR \"XILINX\")\n")
          .append("(PROGRAM \"Vivado\")\n")
          .append("(VERSION \"2025.2\")\n")
          .append("(DIVIDER /)\n")
          .append("(TIMESCALE 1ps)\n");

        for (int i = 0; i < leafCells; i++) {
            sb.append("(CELL \n")
              .append("  (CELLTYPE \"FDRE\")\n")
              .append("  (INSTANCE reg_").append(i).append("\\[0\\]\\.q)\n")
              .append("  (DELAY \n")
              .append("    (ABSOLUTE \n")
              .append("      (IOPATH C Q (").append(i % 900 + 10).append(".0:")
              .append(i % 900 + 20).append(".0:").append(i % 900 + 20).append(".0) (")
              .append(i % 900 + 10).append(".0:").append(i % 900 + 20).append(".0:")
              .append(i % 900 + 20).append(".0))\n")
              .append("    )\n")
              .append("  )\n")
              .append("    (TIMINGCHECK\n")
              .append("      (SETUPHOLD (posedge D) (posedge C) (-36.0:-25.0:-25.0)"
                      + " (60.0:60.0:60.0))\n")
              .append("      (PERIOD (posedge C) (550.0:550.0:550.0))\n")
              .append("    )\n")
              .append(")\n");
        }

        sb.append("(CELL \n")
          .append("    (CELLTYPE \"top\")\n")
          .append("    (INSTANCE )\n")
          .append("    (DELAY\n")
          .append("      (ABSOLUTE\n");
        for (int i = 0; i < interconnects; i++) {
            sb.append("      (INTERCONNECT reg_").append(i).append("\\[0\\]\\.q/Q reg_")
              .append(i + 1).append("\\[0\\]\\.q/D (").append(i % 500 + 1).append(".0:")
              .append(i % 500 + 2).append(".0:").append(i % 500 + 2).append(".0) (")
              .append(i % 500 + 1).append(".0:").append(i % 500 + 2).append(".0:")
              .append(i % 500 + 2).append(".0))\n");
        }
        sb.append("      )\n")
          .append("    )\n")
          .append(")\n")
          .append(")\n");
        return sb.toString();
    }

    @ParameterizedTest(name = "parallel matches serial with {0} chunks")
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 13, 16, 32, 64})
    public void testParallelMatchesSerial(int chunks, @TempDir Path tempDir) throws IOException {
        String content = generateSdf(400, 4000);
        Path input = SdfTestFiles.write(tempDir, "big.sdf", content);
        long fileSize = Files.size(input);

        SdfFile expected = SdfParser.parse(input);

        List<SdfChunkIndexer.Anchor> splitPoints =
                SdfChunkIndexer.findSplitPoints(input, fileSize, chunks);
        Assertions.assertEquals(0, splitPoints.get(0).byteOffset,
                "the first chunk must start at the beginning so it owns the header");
        assertSplitPointsAreValid(content, splitPoints);

        SdfFile actual = parseWithSplitPoints(input, splitPoints);

        assertSameContent(expected, actual);
        assertRoundTrips(actual, input, tempDir.resolve("out" + chunks + ".sdf"));
    }

    @Test
    public void testChunkBoundariesLandInsideTheTopCell(@TempDir Path tempDir) throws IOException {
        // The point of indexing INTERCONNECT as well as CELL: with a top cell this dominant,
        // splitting only at cells would leave one worker with nearly all the bytes.
        String content = generateSdf(4, 20000);
        Path input = SdfTestFiles.write(tempDir, "topheavy.sdf", content);
        long fileSize = Files.size(input);

        List<SdfChunkIndexer.Anchor> splitPoints =
                SdfChunkIndexer.findSplitPoints(input, fileSize, 16);
        long insideTopCell = splitPoints.stream().filter(a -> !a.isCell).count();
        Assertions.assertTrue(insideTopCell >= 8,
                "expected most chunks to start inside the top-level cell, got " + insideTopCell
                        + " of " + splitPoints.size());

        SdfFile expected = SdfParser.parse(input);
        SdfFile actual = parseWithSplitPoints(input, splitPoints);
        assertSameContent(expected, actual);
        // Entry order within the reassembled top cell has to match exactly, not just in count.
        Assertions.assertEquals(expected.getTopCell().getDelayEntries(),
                actual.getTopCell().getDelayEntries());
        assertRoundTrips(actual, input, tempDir.resolve("out.sdf"));
    }

    @Test
    public void testEveryPossibleSplitPointCount(@TempDir Path tempDir) throws IOException {
        // Sweep chunk counts so that boundaries fall at many different places relative to cell and
        // entry starts, including counts far exceeding the number of anchors.
        String content = generateSdf(30, 200);
        Path input = SdfTestFiles.write(tempDir, "sweep.sdf", content);
        long fileSize = Files.size(input);
        SdfFile expected = SdfParser.parse(input);
        byte[] original = Files.readAllBytes(input);

        for (int chunks = 1; chunks <= 300; chunks++) {
            List<SdfChunkIndexer.Anchor> splitPoints =
                    SdfChunkIndexer.findSplitPoints(input, fileSize, chunks);
            assertSplitPointsAreValid(content, splitPoints);
            SdfFile actual = parseWithSplitPoints(input, splitPoints);
            Assertions.assertEquals(expected.getCells(), actual.getCells(),
                    "mismatch with " + chunks + " chunks");

            Path out = tempDir.resolve("sweep_out.sdf");
            SdfWriter.write(actual, out);
            Assertions.assertArrayEquals(original, Files.readAllBytes(out),
                    "round-trip differed with " + chunks + " chunks");
        }
    }

    @Test
    public void testTopLevelParseSelectsSerialForSmallFiles(@TempDir Path tempDir) {
        Path input = SdfTestFiles.write(tempDir, "small.sdf", SdfTestFiles.adversarialSdf());
        Assertions.assertEquals(1, ParallelSdfParser.calcThreads(1024, 0));

        // The public entry point must give the same answer regardless of which path it picks.
        SdfFile viaTools = SdfTools.readSdf(input);
        SdfFile viaSerial = SdfParser.parse(input);
        assertSameContent(viaSerial, viaTools);
    }

    @Test
    public void testGzippedInputIsParsedInParallel(@TempDir Path tempDir) throws IOException {
        String content = generateSdf(200, 3000);
        Path plain = SdfTestFiles.write(tempDir, "plain.sdf", content);
        // write_sdf -gzip keeps the .sdf name, so the compressed file is deliberately not
        // called .gz here.
        Path gzipped = tempDir.resolve("compressed.sdf");
        try (java.util.zip.GZIPOutputStream out =
                new java.util.zip.GZIPOutputStream(Files.newOutputStream(gzipped))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }

        SdfFile expected = SdfParser.parse(plain);
        SdfFile actual = SdfTools.readSdf(gzipped);
        assertSameContent(expected, actual);
        Assertions.assertEquals(gzipped, actual.getSource());
    }

    @Test
    public void testDetectsTruncatedFile(@TempDir Path tempDir) throws IOException {
        String content = generateSdf(100, 1000);
        // Drop the final closing parenthesis of DELAYFILE.
        String truncated = content.substring(0, content.length() - 2);
        Path input = SdfTestFiles.write(tempDir, "truncated.sdf", truncated);
        long fileSize = Files.size(input);

        List<SdfChunkIndexer.Anchor> splitPoints =
                SdfChunkIndexer.findSplitPoints(input, fileSize, 8);
        Assertions.assertThrows(SdfParseException.class,
                () -> parseWithSplitPoints(input, splitPoints));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Parses a file using an explicit set of split points, which is how the tests drive the
     * parallel path deterministically rather than depending on the machine's core count.
     *
     * @param input The file to parse.
     * @param splitPoints Chunk start positions.
     * @return The merged model.
     */
    private static SdfFile parseWithSplitPoints(Path input,
            List<SdfChunkIndexer.Anchor> splitPoints) {
        java.util.List<SdfChunk> chunks = new java.util.ArrayList<>(splitPoints.size());
        com.xilinx.rapidwright.util.StringPool pool =
                com.xilinx.rapidwright.util.StringPool.concurrentPool();
        for (int i = 0; i < splitPoints.size(); i++) {
            long stop = i + 1 < splitPoints.size()
                    ? splitPoints.get(i + 1).byteOffset : Long.MAX_VALUE;
            try (SdfParser parser = new SdfParser(input, SdfInput.open(input), pool)) {
                chunks.add(parser.parseChunk(splitPoints.get(i), stop));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return ParallelSdfParser.merge(input, chunks);
    }

    /**
     * Checks that every split point really is the start of a line whose content begins with an
     * anchor keyword, and that the reported line number is right.
     *
     * @param content The file's text.
     * @param splitPoints The split points to check.
     */
    private static void assertSplitPointsAreValid(String content,
            List<SdfChunkIndexer.Anchor> splitPoints) {
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        long previous = -1;
        for (SdfChunkIndexer.Anchor anchor : splitPoints) {
            Assertions.assertTrue(anchor.byteOffset > previous,
                    "split points must strictly increase");
            previous = anchor.byteOffset;

            int offset = (int) anchor.byteOffset;
            if (offset == 0) {
                continue;
            }
            // An anchor sits at the line's first non-space character, which is where the parser
            // will be positioned after skipping indentation.
            int back = offset;
            while (back > 0 && raw[back - 1] == ' ') {
                back--;
            }
            Assertions.assertTrue(back == 0 || raw[back - 1] == '\n',
                    "split point at " + offset + " is not on the first token of a line");
            String rest = content.substring(offset);
            Assertions.assertTrue(
                    rest.startsWith("(CELL ") || rest.startsWith("(CELL\n")
                            || rest.startsWith("(INTERCONNECT "),
                    "split point at " + offset + " is not on an anchor keyword: "
                            + rest.substring(0, Math.min(40, rest.length())));
            Assertions.assertEquals(anchor.isCell, rest.startsWith("(CELL"),
                    "anchor kind is wrong at offset " + offset);

            long expectedLine = 1;
            for (int i = 0; i < offset; i++) {
                if (raw[i] == '\n') {
                    expectedLine++;
                }
            }
            Assertions.assertEquals(expectedLine, anchor.lineNumber,
                    "line number is wrong at offset " + offset);
        }
    }

    private static void assertSameContent(SdfFile expected, SdfFile actual) {
        Assertions.assertEquals(expected.getDesign(), actual.getDesign());
        Assertions.assertEquals(expected.getSdfVersion(), actual.getSdfVersion());
        Assertions.assertEquals(expected.getTimeScale(), actual.getTimeScale());
        Assertions.assertEquals(expected.getCells().size(), actual.getCells().size(),
                "cell count differs");
        Assertions.assertEquals(expected.getCells(), actual.getCells());
    }

    private static void assertRoundTrips(SdfFile sdf, Path original, Path output)
            throws IOException {
        SdfWriter.write(sdf, output);
        Assertions.assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(output),
                "round-trip of the parallel parse was not byte-identical");
    }
}
