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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Future;

import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Writes an {@link SdfFile} back out as SDF text.
 *
 * The writer reproduces Vivado's output byte for byte, quirks included: the trailing space after
 * {@code (CELL}, {@code (DELAY} and {@code (ABSOLUTE} but not after {@code (TIMINGCHECK}; the space
 * before the closing parenthesis of {@code (SDFVERSION "3.0" )}; the two-space indentation of a
 * leaf cell against the four-space indentation of the top-level cell; and one fractional digit on
 * every delay value. That fidelity is not cosmetic. A round-trip that reproduces the input exactly
 * is the strongest cheap evidence that the parser dropped nothing, which is why
 * {@code TestSdfRoundTrip} asserts it over a corpus of real Vivado output.
 *
 * Cells are rendered into byte arrays in parallel and drained in order, so output ordering is
 * preserved while generation scales. Fixed syntax is emitted from pre-encoded {@code byte[]}
 * constants rather than from {@code String}s, avoiding a character-set encode per token.
 */
public class SdfWriter {

    /** Cells rendered per parallel chunk. Large enough to amortise task overhead. */
    private static final int CELLS_PER_CHUNK = 256;

    /** Initial size of a chunk's render buffer, grown as needed. */
    private static final int CHUNK_BUFFER_SIZE = 1 << 20;

    /**
     * Delay entries rendered per parallel chunk when one cell holds too many to render at once.
     *
     * At roughly 100 to 200 bytes per entry this keeps a chunk near 10 MB, comfortably below the
     * 2 GB ceiling on a Java array while still being large enough to amortise task overhead.
     */
    private static final int ENTRIES_PER_CHUNK = 1 << 16;

    private static final byte[] DELAYFILE_OPEN = bytes("(DELAYFILE \n");
    private static final byte[] SDFVERSION_OPEN = bytes("(SDFVERSION \"");
    // Vivado writes a space before the closing parenthesis of SDFVERSION and nowhere else.
    private static final byte[] SDFVERSION_CLOSE = bytes("\" )\n");
    private static final byte[] DESIGN_OPEN = bytes("(DESIGN \"");
    private static final byte[] DATE_OPEN = bytes("(DATE \"");
    private static final byte[] VENDOR_OPEN = bytes("(VENDOR \"");
    private static final byte[] PROGRAM_OPEN = bytes("(PROGRAM \"");
    private static final byte[] VERSION_OPEN = bytes("(VERSION \"");
    private static final byte[] QUOTE_CLOSE = bytes("\")\n");
    private static final byte[] DIVIDER_OPEN = bytes("(DIVIDER ");
    private static final byte[] TIMESCALE_OPEN = bytes("(TIMESCALE ");
    private static final byte[] PAREN_CLOSE_NL = bytes(")\n");

    private static final byte[] CELL_OPEN = bytes("(CELL \n");
    private static final byte[] CELL_CLOSE = bytes(")\n");

    private static final byte[] CELLTYPE_OPEN_2 = bytes("  (CELLTYPE \"");
    private static final byte[] CELLTYPE_OPEN_4 = bytes("    (CELLTYPE \"");
    private static final byte[] INSTANCE_OPEN_2 = bytes("  (INSTANCE ");
    private static final byte[] INSTANCE_OPEN_4 = bytes("    (INSTANCE ");

    private static final byte[] DELAY_OPEN_2 = bytes("  (DELAY \n");
    private static final byte[] DELAY_CLOSE_2 = bytes("  )\n");
    private static final byte[] ABSOLUTE_OPEN_4 = bytes("    (ABSOLUTE \n");
    private static final byte[] ABSOLUTE_CLOSE_4 = bytes("    )\n");
    private static final byte[] PATHPULSEPERCENT_OPEN_4 = bytes("    (PATHPULSEPERCENT ");

    // The top-level cell omits the trailing spaces that a leaf cell has, and indents one level
    // deeper. Both differences are Vivado's, and both have to be reproduced.
    private static final byte[] DELAY_OPEN_4_TOP = bytes("    (DELAY\n");
    private static final byte[] DELAY_CLOSE_4_TOP = bytes("    )\n");
    private static final byte[] ABSOLUTE_OPEN_6_TOP = bytes("      (ABSOLUTE\n");
    private static final byte[] ABSOLUTE_CLOSE_6_TOP = bytes("      )\n");

    private static final byte[] ENTRY_INDENT = bytes("      ");
    private static final byte[] IOPATH_OPEN = bytes("(IOPATH ");
    private static final byte[] INTERCONNECT_OPEN = bytes("(INTERCONNECT ");

    private static final byte[] TIMINGCHECK_OPEN_4 = bytes("    (TIMINGCHECK\n");
    private static final byte[] TIMINGCHECK_CLOSE_4 = bytes("    )\n");
    private static final byte[] SETUPHOLD_OPEN = bytes("(SETUPHOLD ");
    private static final byte[] RECREM_OPEN = bytes("(RECREM ");
    private static final byte[] PERIOD_OPEN = bytes("(PERIOD ");
    private static final byte[] WIDTH_OPEN = bytes("(WIDTH ");
    private static final byte[] POSEDGE_OPEN = bytes("(posedge ");
    private static final byte[] NEGEDGE_OPEN = bytes("(negedge ");

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private SdfWriter() {
    }

    /**
     * Scratch space reused across every delay value a single thread writes.
     *
     * A large SDF contains tens of millions of values; allocating a temporary array to encode each
     * one would generate more garbage than the render itself. One of these is created per parallel
     * chunk, so no synchronisation is needed.
     */
    private static final class Scratch {

        /** Buffer for one encoded delay value. */
        final byte[] buffer = new byte[SdfNumbers.maxEncodedLength()];
    }

    /**
     * Writes a name.
     *
     * This deliberately goes through {@code String.getBytes} rather than copying characters into
     * the scratch buffer. Names are ASCII, so on a modern JVM the string already holds Latin-1
     * bytes and the encode is an intrinsified array copy; a hand-written per-character loop
     * measured slower despite avoiding the allocation.
     *
     * @param out Destination.
     * @param s The name to write.
     * @throws IOException If writing fails.
     */
    private static void writeName(OutputStream out, String s) throws IOException {
        out.write(bytes(s));
    }

    /**
     * Writes an SDF model to a file.
     *
     * @param sdf The model to write.
     * @param fileName Destination file.
     */
    public static void write(SdfFile sdf, Path fileName) {
        write(sdf, fileName, CodePerfTracker.SILENT);
    }

    /**
     * Writes an SDF model to a file, reporting timing.
     *
     * @param sdf The model to write.
     * @param fileName Destination file.
     * @param t Performance tracker, or {@link CodePerfTracker#SILENT}.
     */
    public static void write(SdfFile sdf, Path fileName, CodePerfTracker t) {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(fileName))) {
            write(sdf, out, t);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't write file : " + fileName, e);
        }
    }

    /**
     * Writes an SDF model to a stream.
     *
     * @param sdf The model to write.
     * @param out Destination stream; the caller retains ownership and must close it.
     * @param t Performance tracker, or {@link CodePerfTracker#SILENT}.
     */
    public static void write(SdfFile sdf, OutputStream out, CodePerfTracker t) {
        t.start("Write SDF");
        try {
            writeHeader(sdf, out);

            List<SdfCell> cells = sdf.getCells();
            if (ParallelismTools.getParallel() && cells.size() > CELLS_PER_CHUNK) {
                writeCellsParallel(cells, out);
            } else {
                // Written straight to the stream rather than buffered whole: a large design's SDF
                // runs to several gigabytes, which no single byte array can hold. The caller
                // supplies a BufferedOutputStream, so this is not doing unbuffered writes.
                Scratch scratch = new Scratch();
                for (SdfCell cell : cells) {
                    writeCell(cell, out, scratch);
                }
            }

            // Closing parenthesis of DELAYFILE.
            out.write(PAREN_CLOSE_NL);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while writing SDF", e);
        }
        t.stop();
    }

    /**
     * Renders cells in parallel and drains the results in order, so the output preserves the
     * model's cell ordering regardless of the order in which the chunks finish.
     *
     * @param cells The cells to write.
     * @param out Destination stream.
     * @throws IOException If writing fails.
     */
    private static void writeCellsParallel(List<SdfCell> cells, OutputStream out)
            throws IOException {
        // Cap the rendered-but-not-yet-written backlog. Submitting every chunk up front would let
        // a large design hold its whole multi-gigabyte output in memory at once, since results are
        // drained in order and cannot be released until their turn comes.
        final int maxInFlight = Math.max(4, ParallelismTools.maxParallelism() * 4);
        Deque<Future<byte[]>> pending = new ArrayDeque<>();

        for (int start = 0; start < cells.size(); ) {
            SdfCell cell = cells.get(start);
            if (cell.getDelayEntries().size() > ENTRIES_PER_CHUNK) {
                // One cell too large to render into a single array. The top-level cell holds every
                // INTERCONNECT in the design, which on a large part runs to millions of entries and
                // gigabytes of text, so chunking purely by cell count would overflow the 2 GB limit
                // on a Java array. Emit it as a prologue, a series of entry ranges, and an
                // epilogue, all rendered in parallel and drained in order like any other chunk.
                final SdfCell big = cell;
                pending.add(submitRender(s -> writeCellPrologue(big, s.out, s.scratch)));
                int entryCount = big.getDelayEntries().size();
                for (int e = 0; e < entryCount; e += ENTRIES_PER_CHUNK) {
                    final int efrom = e;
                    final int eto = Math.min(e + ENTRIES_PER_CHUNK, entryCount);
                    pending.add(submitRender(s -> writeEntryRange(big, efrom, eto, s.out,
                            s.scratch)));
                    while (pending.size() >= maxInFlight) {
                        out.write(ParallelismTools.get(pending.removeFirst()));
                    }
                }
                pending.add(submitRender(s -> writeCellEpilogue(big, s.out, s.scratch)));
                start++;
            } else {
                final int from = start;
                int end = start;
                while (end < cells.size() && end - from < CELLS_PER_CHUNK
                        && cells.get(end).getDelayEntries().size() <= ENTRIES_PER_CHUNK) {
                    end++;
                }
                final int to = end;
                pending.add(submitRender(s -> {
                    for (int i = from; i < to; i++) {
                        writeCell(cells.get(i), s.out, s.scratch);
                    }
                }));
                start = end;
            }
            while (pending.size() >= maxInFlight) {
                out.write(ParallelismTools.get(pending.removeFirst()));
            }
        }
        while (!pending.isEmpty()) {
            out.write(ParallelismTools.get(pending.removeFirst()));
        }
    }

    /** The destination and scratch space handed to a single render task. */
    private static final class RenderSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(CHUNK_BUFFER_SIZE);
        final Scratch scratch = new Scratch();
    }

    /** A unit of rendering work producing one contiguous piece of the output. */
    private interface RenderStep {
        void render(RenderSink sink) throws IOException;
    }

    /**
     * Submits one render step, returning its bytes.
     *
     * @param step The work to perform.
     * @return A future for the rendered bytes.
     */
    private static Future<byte[]> submitRender(RenderStep step) {
        return ParallelismTools.submit(() -> {
            RenderSink sink = new RenderSink();
            step.render(sink);
            return sink.out.toByteArray();
        });
    }

    private static void writeHeader(SdfFile sdf, OutputStream out) throws IOException {
        out.write(DELAYFILE_OPEN);
        writeQuotedField(out, SDFVERSION_OPEN, sdf.getSdfVersion(), SDFVERSION_CLOSE);
        writeQuotedField(out, DESIGN_OPEN, sdf.getDesign(), QUOTE_CLOSE);
        writeQuotedField(out, DATE_OPEN, sdf.getDate(), QUOTE_CLOSE);
        writeQuotedField(out, VENDOR_OPEN, sdf.getVendor(), QUOTE_CLOSE);
        writeQuotedField(out, PROGRAM_OPEN, sdf.getProgram(), QUOTE_CLOSE);
        writeQuotedField(out, VERSION_OPEN, sdf.getProgramVersion(), QUOTE_CLOSE);
        if (sdf.getDivider() != null) {
            out.write(DIVIDER_OPEN);
            out.write(bytes(sdf.getDivider()));
            out.write(PAREN_CLOSE_NL);
        }
        if (sdf.getTimeScale() != null) {
            out.write(TIMESCALE_OPEN);
            out.write(bytes(sdf.getTimeScale()));
            out.write(PAREN_CLOSE_NL);
        }
    }

    private static void writeQuotedField(OutputStream out, byte[] open, String value, byte[] close)
            throws IOException {
        if (value == null) {
            return;
        }
        out.write(open);
        out.write(bytes(value));
        out.write(close);
    }

    /**
     * Renders one cell.
     *
     * @param cell The cell to render.
     * @param out Destination.
     * @throws IOException If writing fails.
     */
    static void writeCell(SdfCell cell, OutputStream out, Scratch scratch)
            throws IOException {
        writeCellPrologue(cell, out, scratch);
        if (cell.hasDelay()) {
            for (SdfDelayEntry entry : cell.getDelayEntries()) {
                writeDelayEntry(entry, out, scratch);
            }
        }
        writeCellEpilogue(cell, out, scratch);
    }

    /**
     * Writes everything of a cell up to and including the opening of its {@code ABSOLUTE} block.
     *
     * Split out from {@link #writeCellEpilogue} so that a cell with a very large entry list can be
     * emitted in pieces; see {@link #writeCellsParallel}.
     *
     * @param cell The cell to write.
     * @param out Destination.
     * @param scratch Reusable buffer.
     * @throws IOException If writing fails.
     */
    private static void writeCellPrologue(SdfCell cell, OutputStream out, Scratch scratch)
            throws IOException {
        boolean top = cell.isTopCell();

        out.write(CELL_OPEN);

        out.write(top ? CELLTYPE_OPEN_4 : CELLTYPE_OPEN_2);
        writeName(out, cell.getCellType());
        out.write(QUOTE_CLOSE);

        out.write(top ? INSTANCE_OPEN_4 : INSTANCE_OPEN_2);
        writeName(out, cell.getInstance());
        out.write(PAREN_CLOSE_NL);

        if (cell.hasDelay()) {
            out.write(top ? DELAY_OPEN_4_TOP : DELAY_OPEN_2);

            if (cell.getPathPulsePercent() != null) {
                out.write(PATHPULSEPERCENT_OPEN_4);
                writeDelayValues(cell.getPathPulsePercent(), out, scratch);
                out.write(PAREN_CLOSE_NL);
            }

            out.write(top ? ABSOLUTE_OPEN_6_TOP : ABSOLUTE_OPEN_4);
        }
    }

    /**
     * Writes everything of a cell from the close of its {@code ABSOLUTE} block onwards.
     *
     * @param cell The cell to write.
     * @param out Destination.
     * @param scratch Reusable buffer.
     * @throws IOException If writing fails.
     */
    private static void writeCellEpilogue(SdfCell cell, OutputStream out, Scratch scratch)
            throws IOException {
        boolean top = cell.isTopCell();

        if (cell.hasDelay()) {
            out.write(top ? ABSOLUTE_CLOSE_6_TOP : ABSOLUTE_CLOSE_4);
            out.write(top ? DELAY_CLOSE_4_TOP : DELAY_CLOSE_2);
        }

        if (cell.hasTimingCheck()) {
            out.write(TIMINGCHECK_OPEN_4);
            for (SdfTimingCheck check : cell.getTimingChecks()) {
                writeTimingCheck(check, out, scratch);
            }
            out.write(TIMINGCHECK_CLOSE_4);
        }

        out.write(CELL_CLOSE);
    }

    /**
     * Writes a run of one cell's delay entries.
     *
     * @param cell The cell the entries belong to.
     * @param from Index of the first entry, inclusive.
     * @param to Index one past the last entry.
     * @param out Destination.
     * @param scratch Reusable buffer.
     * @throws IOException If writing fails.
     */
    private static void writeEntryRange(SdfCell cell, int from, int to, OutputStream out,
            Scratch scratch) throws IOException {
        List<SdfDelayEntry> entries = cell.getDelayEntries();
        for (int i = from; i < to; i++) {
            writeDelayEntry(entries.get(i), out, scratch);
        }
    }

    private static void writeDelayEntry(SdfDelayEntry entry, OutputStream out,
            Scratch scratch) throws IOException {
        out.write(ENTRY_INDENT);
        out.write(entry.getKind() == SdfDelayEntry.Kind.IOPATH ? IOPATH_OPEN : INTERCONNECT_OPEN);
        writePortSpec(entry.getSourceEdge(), entry.getSource(), out);
        out.write(' ');
        writeName(out, entry.getDestination());
        out.write(' ');
        writeDelayValues(entry.getValues(), out, scratch);
        out.write(PAREN_CLOSE_NL);
    }

    private static void writeTimingCheck(SdfTimingCheck check, OutputStream out,
            Scratch scratch) throws IOException {
        out.write(ENTRY_INDENT);
        switch (check.getKind()) {
            case SETUPHOLD: out.write(SETUPHOLD_OPEN); break;
            case RECREM: out.write(RECREM_OPEN); break;
            case PERIOD: out.write(PERIOD_OPEN); break;
            default: out.write(WIDTH_OPEN); break;
        }
        writePortSpec(check.getFirstEdge(), check.getFirstPort(), out);
        if (check.getSecondPort() != null) {
            out.write(' ');
            writePortSpec(check.getSecondEdge(), check.getSecondPort(), out);
        }
        out.write(' ');
        writeDelayValues(check.getFirstValues(), out, scratch);
        if (check.getSecondValues() != null) {
            out.write(' ');
            writeDelayValues(check.getSecondValues(), out, scratch);
        }
        out.write(PAREN_CLOSE_NL);
    }

    private static void writePortSpec(SdfEdge edge, String port, OutputStream out)
            throws IOException {
        switch (edge) {
            case POSEDGE:
                out.write(POSEDGE_OPEN);
                writeName(out, port);
                out.write(')');
                break;
            case NEGEDGE:
                out.write(NEGEDGE_OPEN);
                writeName(out, port);
                out.write(')');
                break;
            default:
                writeName(out, port);
                break;
        }
    }

    /**
     * Writes a delval list, preserving both which slots were absent and whether each present slot
     * used the triple or the single-value form.
     *
     * @param values The values to write.
     * @param out Destination.
     * @throws IOException If writing fails.
     */
    private static void writeDelayValues(SdfDelayValues values, OutputStream out,
            Scratch scratch) throws IOException {
        byte[] buffer = scratch.buffer;
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(' ');
            }
            out.write('(');
            if (values.isPadded(i)) {
                out.write(' ');
            }
            if (values.isPresent(i)) {
                if (values.isTriple(i)) {
                    for (int c = 0; c < SdfDelayValues.TRIPLE_SIZE; c++) {
                        if (c > 0) {
                            out.write(':');
                        }
                        int n = SdfNumbers.writeTenths(buffer, 0, values.getTenths(i, c));
                        out.write(buffer, 0, n);
                    }
                } else {
                    int n = SdfNumbers.writeTenths(buffer, 0,
                            values.getTenths(i, SdfDelayValues.MIN));
                    out.write(buffer, 0, n);
                }
            }
            out.write(')');
        }
    }

    /**
     * Renders a single cell to a byte array. Used by the round-trip verifier to compare a
     * re-rendered cell against the bytes it was parsed from.
     *
     * @param cell The cell to render.
     * @return The rendered bytes.
     */
    public static byte[] renderCell(SdfCell cell) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try {
            writeCell(cell, buffer, new Scratch());
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while rendering SDF cell", e);
        }
        return buffer.toByteArray();
    }
}
