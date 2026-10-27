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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.StringPool;

/**
 * A single-threaded recursive-descent parser for Vivado-written SDF files.
 *
 * This is the reference implementation. It is straightforward on purpose: {@link ParallelSdfParser}
 * is tested by requiring it to produce a result equal to this one, so this class is the definition
 * of correct behaviour and should stay easy to read.
 *
 * The grammar accepted is exactly the subset {@code write_sdf} emits, enumerated in
 * {@link SdfKeywords}. Anything else raises {@link SdfParseException} rather than being skipped, so
 * a construct RapidWright does not model can never be silently dropped from a round-trip or from a
 * delay annotation.
 */
public class SdfParser implements AutoCloseable {

    private final SdfTokenizer tokenizer;

    private final Path fileName;

    /**
     * Byte offset at which this parser stops. Used only by {@link ParallelSdfParser}, which gives
     * each worker the offset of the next worker's chunk; left at {@link Long#MAX_VALUE} for a
     * whole-file parse, so the serial path behaves as if the bound did not exist.
     */
    private long stopByteOffset = Long.MAX_VALUE;

    /**
     * Set when the most recent {@link #parseCell()} stopped at {@link #stopByteOffset} partway
     * through its delay entries, meaning the cell is continued by the next chunk.
     */
    private boolean lastCellIncomplete;

    /**
     * The source endpoint of the previous {@code INTERCONNECT}, kept so that the entries of one
     * driver can share a single string. See {@link #dedupeSource(String)}.
     */
    private String lastInterconnectSource;

    /**
     * @param fileName The file being parsed, used for diagnostics.
     * @param in The stream to read; must not be additionally buffered.
     * @param uniquifier Pool used to intern names.
     */
    public SdfParser(Path fileName, InputStream in, StringPool uniquifier) {
        this.fileName = fileName;
        this.tokenizer = new SdfTokenizer(fileName, in, uniquifier);
    }

    /**
     * @param fileName The file to parse.
     */
    public SdfParser(Path fileName) {
        this(fileName, SdfInput.open(fileName), StringPool.singleThreadedPool());
    }

    /**
     * Parses an SDF file.
     *
     * Transparently handles files written by {@code write_sdf -gzip}, which are gzip streams that
     * keep the {@code .sdf} name, by sniffing the stream's magic bytes rather than trusting the
     * extension.
     *
     * @param fileName The file to parse.
     * @return The parsed model.
     */
    public static SdfFile parse(Path fileName) {
        return parse(fileName, CodePerfTracker.SILENT);
    }

    /**
     * Parses an SDF file, reporting timing.
     *
     * @param fileName The file to parse.
     * @param t Performance tracker, or {@link CodePerfTracker#SILENT}.
     * @return The parsed model.
     */
    public static SdfFile parse(Path fileName, CodePerfTracker t) {
        t.start("Parse SDF");
        try (SdfParser parser = new SdfParser(fileName)) {
            SdfFile result = parser.parseSdfFile();
            t.stop();
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }
    }

    /**
     * Parses the whole file, from the opening {@code (DELAYFILE} to end of input.
     *
     * @return The parsed model.
     */
    public SdfFile parseSdfFile() {
        SdfFile file = new SdfFile();
        file.setSource(fileName);

        // The header scan has to consume the first "(CELL" to discover that the header ended, so
        // it hands that token back rather than pushing it into the tokenizer.
        boolean moreCells = parseHeader(file);

        while (moreCells) {
            file.addCell(parseCell());

            String token = tokenizer.getOptionalNextTokenString(true);
            if (token == null) {
                throw tokenizer.parseError("unexpected end of file, expected a CELL or the closing"
                        + " parenthesis of DELAYFILE");
            }
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                moreCells = false;
                break;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            pendingCellToken = new SdfToken(token, tokenizer.getTokenStartByteOffset(),
                    tokenizer.getTokenStartLineNumber());
            expectKeyword(SdfKeywords.CELL);
        }

        String trailing = tokenizer.getOptionalNextTokenString(true);
        if (trailing != null) {
            throw tokenizer.parseError("unexpected content after the end of DELAYFILE: "
                    + trailing);
        }
        validate(file);
        return file;
    }

    /**
     * Parses the {@code (DELAYFILE} keyword and the header fields that follow it, stopping once the
     * first {@code (CELL} has been recognised.
     *
     * @param file The model to populate.
     * @return True if a cell follows, false if the file closed its DELAYFILE with no cells at all.
     */
    private boolean parseHeader(SdfFile file) {
        expectToken(SdfKeywords.LEFT_PAREN);
        expectKeyword(SdfKeywords.DELAYFILE);

        while (true) {
            String token = tokenizer.getOptionalNextTokenString(true);
            if (token == null) {
                throw tokenizer.parseError("unexpected end of file inside the DELAYFILE header");
            }
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                return false;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            SdfToken openParen = new SdfToken(token, tokenizer.getTokenStartByteOffset(),
                    tokenizer.getTokenStartLineNumber());

            String keyword = tokenizer.getNextTokenString(true);
            if (SdfKeywords.CELL.equalsIgnoreCase(keyword)) {
                pendingCellToken = openParen;
                return true;
            }
            parseHeaderField(file, keyword);
        }
    }

    /**
     * The {@code (} token of the cell currently being parsed, recorded so that
     * {@link SdfCell#getStartByteOffset()} refers to the cell's first byte rather than to wherever
     * the tokenizer happens to be.
     */
    private SdfToken pendingCellToken;

    private void parseHeaderField(SdfFile file, String keyword) {
        if (SdfKeywords.SDFVERSION.equalsIgnoreCase(keyword)) {
            file.setSdfVersion(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.DESIGN.equalsIgnoreCase(keyword)) {
            file.setDesign(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.DATE.equalsIgnoreCase(keyword)) {
            file.setDate(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.VENDOR.equalsIgnoreCase(keyword)) {
            file.setVendor(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.PROGRAM.equalsIgnoreCase(keyword)) {
            file.setProgram(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.VERSION.equalsIgnoreCase(keyword)) {
            file.setProgramVersion(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.DIVIDER.equalsIgnoreCase(keyword)) {
            file.setDivider(tokenizer.getNextTokenString(false));
        } else if (SdfKeywords.TIMESCALE.equalsIgnoreCase(keyword)) {
            file.setTimeScale(tokenizer.getNextTokenString(false));
        } else {
            throw tokenizer.parseError("unsupported SDF header construct '" + keyword + "'."
                    + " RapidWright supports the subset of SDF that Vivado's write_sdf emits;"
                    + " see " + SdfKeywords.class.getSimpleName() + ".");
        }
        expectToken(SdfKeywords.RIGHT_PAREN);
    }

    /**
     * Parses one chunk of a file on behalf of {@link ParallelSdfParser}.
     *
     * The chunk runs from {@code start}, which {@link SdfChunkIndexer} has already proved is the
     * first byte of a {@code (CELL} or {@code (INTERCONNECT} line, up to but not including
     * {@code stopByteOffset}. Because both endpoints are real construct boundaries, no
     * resynchronisation guesswork is needed and no construct is ever split.
     *
     * @param start Where this chunk begins.
     * @param stopOffset Where the next chunk begins, or {@link Long#MAX_VALUE} for the last chunk.
     * @return The chunk's contribution to the file.
     */
    SdfChunk parseChunk(SdfChunkIndexer.Anchor start, long stopOffset) {
        this.stopByteOffset = stopOffset;
        SdfChunk chunk = new SdfChunk(start.byteOffset);

        if (start.byteOffset == 0) {
            // The first chunk also owns the file header.
            chunk.setHeader(new SdfFile());
            chunk.getHeader().setSource(fileName);
            if (!parseHeader(chunk.getHeader())) {
                chunk.setSawDelayFileClose(true);
                return chunk;
            }
        } else {
            tokenizer.seekToKnownLineStart(start.byteOffset, start.lineNumber);
            if (!start.isCell) {
                // This chunk begins partway through the top-level cell's delay entries.
                parseTopCellFragment(chunk);
                return chunk;
            }
            expectToken(SdfKeywords.LEFT_PAREN);
            pendingCellToken = new SdfToken(SdfKeywords.LEFT_PAREN, start.byteOffset,
                    start.lineNumber);
            expectKeyword(SdfKeywords.CELL);
        }

        while (true) {
            SdfCell cell = parseCell();
            if (lastCellIncomplete) {
                lastCellIncomplete = false;
                chunk.setPartialCell(cell);
                return chunk;
            }
            chunk.addCell(cell);

            if (atStop()) {
                return chunk;
            }
            char next = tokenizer.peekNonWhitespace();
            if (next == 0) {
                throw tokenizer.parseError("unexpected end of file, expected a CELL or the closing"
                        + " parenthesis of DELAYFILE");
            }
            String token = tokenizer.getNextTokenString(true);
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                chunk.setSawDelayFileClose(true);
                return chunk;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            pendingCellToken = new SdfToken(token, tokenizer.getTokenStartByteOffset(),
                    tokenizer.getTokenStartLineNumber());
            expectKeyword(SdfKeywords.CELL);
        }
    }

    /**
     * Parses a run of the top-level cell's delay entries, for a chunk that starts inside it.
     *
     * @param chunk Destination for the parsed entries.
     */
    private void parseTopCellFragment(SdfChunk chunk) {
        List<SdfDelayEntry> entries = new ArrayList<>();
        while (true) {
            if (atStop()) {
                chunk.setFragmentEntries(entries);
                return;
            }
            char next = tokenizer.peekNonWhitespace();
            if (next == 0) {
                throw tokenizer.parseError("unexpected end of file inside the top-level cell");
            }
            if (next == ')') {
                // The end of the top cell's ABSOLUTE block; this chunk closes out the file.
                chunk.setFragmentEntries(entries);
                expectToken(SdfKeywords.RIGHT_PAREN);
                expectToken(SdfKeywords.RIGHT_PAREN);
                expectToken(SdfKeywords.RIGHT_PAREN);
                // Record where the cell itself ends, before the DELAYFILE close, so the merged
                // cell can report its true extent rather than the chunk boundary it was split at.
                chunk.setCompletedCellEndByteOffset(tokenizer.getByteOffset());
                expectToken(SdfKeywords.RIGHT_PAREN);
                chunk.setSawDelayFileClose(true);
                checkNothingFollows();
                return;
            }
            expectToken(SdfKeywords.LEFT_PAREN);
            entries.add(parseDelayEntry());
        }
    }

    /**
     * Verifies that the file ends where the grammar says it should.
     */
    private void checkNothingFollows() {
        String trailing = tokenizer.getOptionalNextTokenString(true);
        if (trailing != null) {
            throw tokenizer.parseError("unexpected content after the end of DELAYFILE: "
                    + trailing);
        }
    }

    /**
     * Parses one cell body. The opening {@code (CELL} has already been consumed.
     *
     * @return The parsed cell.
     */
    SdfCell parseCell() {
        long startByteOffset = pendingCellToken != null ? pendingCellToken.byteOffset
                : tokenizer.getTokenStartByteOffset();
        long startLine = pendingCellToken != null ? pendingCellToken.lineNumber
                : tokenizer.getTokenStartLineNumber();
        pendingCellToken = null;

        String cellType = null;
        String instance = null;
        boolean instanceSeen = false;
        boolean hasDelay = false;
        boolean hasTimingCheck = false;
        SdfDelayValues pathPulsePercent = null;
        List<SdfDelayEntry> delayEntries = new ArrayList<>();
        List<SdfTimingCheck> timingChecks = new ArrayList<>();

        while (true) {
            String token = tokenizer.getNextTokenString(true);
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                break;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            String keyword = tokenizer.getNextTokenString(true);

            if (SdfKeywords.CELLTYPE.equalsIgnoreCase(keyword)) {
                cellType = tokenizer.getNextTokenString(false);
                expectToken(SdfKeywords.RIGHT_PAREN);
            } else if (SdfKeywords.INSTANCE.equalsIgnoreCase(keyword)) {
                instanceSeen = true;
                // The top-level cell is written as "(INSTANCE )" with no name at all.
                String next = tokenizer.getNextTokenString(false);
                if (SdfKeywords.RIGHT_PAREN.equals(next)) {
                    instance = "";
                } else {
                    instance = next;
                    expectToken(SdfKeywords.RIGHT_PAREN);
                }
            } else if (SdfKeywords.DELAY.equalsIgnoreCase(keyword)) {
                hasDelay = true;
                pathPulsePercent = parseDelayBlock(delayEntries, pathPulsePercent);
                if (lastCellIncomplete) {
                    if (cellType == null || !instanceSeen) {
                        throw tokenizer.parseError("chunk boundary fell inside a cell before its"
                                + " CELLTYPE and INSTANCE were read");
                    }
                    return new SdfCell(cellType, instance,
                            instance.isEmpty() ? SdfCell.Style.TOP : SdfCell.Style.NORMAL,
                            true, false, pathPulsePercent, delayEntries, timingChecks,
                            startByteOffset, tokenizer.getByteOffset(), startLine);
                }
            } else if (SdfKeywords.TIMINGCHECK.equalsIgnoreCase(keyword)) {
                hasTimingCheck = true;
                parseTimingCheckBlock(timingChecks);
            } else {
                throw tokenizer.parseError("unsupported construct '" + keyword + "' inside a CELL."
                        + " RapidWright supports the subset of SDF that Vivado's write_sdf emits;"
                        + " see " + SdfKeywords.class.getSimpleName() + ".");
            }
        }

        if (cellType == null) {
            throw tokenizer.parseError("CELL at line " + startLine + " has no CELLTYPE");
        }
        if (!instanceSeen) {
            throw tokenizer.parseError("CELL at line " + startLine + " has no INSTANCE");
        }

        SdfCell.Style style = instance.isEmpty() ? SdfCell.Style.TOP : SdfCell.Style.NORMAL;
        return new SdfCell(cellType, instance, style, hasDelay, hasTimingCheck, pathPulsePercent,
                delayEntries, timingChecks, startByteOffset, tokenizer.getByteOffset(), startLine);
    }

    /**
     * Parses a {@code (DELAY ...)} block, which contains an optional {@code PATHPULSEPERCENT} and
     * an {@code (ABSOLUTE ...)} block of delay arcs.
     *
     * @param entries Destination for the parsed arcs, appended in file order.
     * @param existingPathPulsePercent Any value already parsed for this cell.
     * @return The path pulse percent value, or null if none was present.
     */
    private SdfDelayValues parseDelayBlock(List<SdfDelayEntry> entries,
            SdfDelayValues existingPathPulsePercent) {
        SdfDelayValues pathPulsePercent = existingPathPulsePercent;
        while (true) {
            String token = tokenizer.getNextTokenString(true);
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                return pathPulsePercent;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            String keyword = tokenizer.getNextTokenString(true);

            if (SdfKeywords.PATHPULSEPERCENT.equalsIgnoreCase(keyword)) {
                pathPulsePercent = parseDelayValueList();
                expectToken(SdfKeywords.RIGHT_PAREN);
            } else if (SdfKeywords.ABSOLUTE.equalsIgnoreCase(keyword)) {
                parseAbsoluteBlock(entries);
                if (lastCellIncomplete) {
                    // The chunk ended inside this block; unwind without touching the next
                    // chunk's bytes.
                    return pathPulsePercent;
                }
            } else {
                throw tokenizer.parseError("unsupported construct '" + keyword + "' inside DELAY."
                        + " RapidWright supports the subset of SDF that Vivado's write_sdf emits;"
                        + " see " + SdfKeywords.class.getSimpleName() + ".");
            }
        }
    }

    /**
     * Parses an {@code (ABSOLUTE ...)} block of {@code IOPATH} and {@code INTERCONNECT} arcs.
     *
     * @param entries Destination for the parsed arcs, appended in file order.
     */
    private void parseAbsoluteBlock(List<SdfDelayEntry> entries) {
        while (true) {
            if (atStop()) {
                // Only reachable for a bounded chunk parse: the rest of this block belongs to the
                // next worker, which will continue appending to the same cell.
                lastCellIncomplete = true;
                return;
            }
            String token = tokenizer.getNextTokenString(true);
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                return;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            entries.add(parseDelayEntry());
        }
    }

    /**
     * Tests whether the parser has reached the end of its chunk.
     *
     * The check is made after whitespace has been skipped, so it compares the offset of the next
     * construct's opening parenthesis against the next chunk's start. That is exactly the boundary
     * {@link SdfChunkIndexer} chose, so a construct is never split between two workers.
     *
     * @return True if the next construct belongs to the following chunk.
     */
    private boolean atStop() {
        if (stopByteOffset == Long.MAX_VALUE) {
            return false;
        }
        char next = tokenizer.peekNonWhitespace();
        return next != 0 && tokenizer.getByteOffset() >= stopByteOffset;
    }

    /**
     * Parses one {@code IOPATH} or {@code INTERCONNECT} arc. The opening parenthesis has already
     * been consumed.
     *
     * @return The parsed arc.
     */
    SdfDelayEntry parseDelayEntry() {
        long byteOffset = tokenizer.getTokenStartByteOffset();
        long lineNumber = tokenizer.getTokenStartLineNumber();
        String keyword = tokenizer.getNextTokenString(true);

        SdfDelayEntry.Kind kind;
        if (SdfKeywords.IOPATH.equalsIgnoreCase(keyword)) {
            kind = SdfDelayEntry.Kind.IOPATH;
        } else if (SdfKeywords.INTERCONNECT.equalsIgnoreCase(keyword)) {
            kind = SdfDelayEntry.Kind.INTERCONNECT;
        } else {
            throw tokenizer.parseError("unsupported construct '" + keyword + "' inside ABSOLUTE."
                    + " RapidWright supports the subset of SDF that Vivado's write_sdf emits;"
                    + " see " + SdfKeywords.class.getSimpleName() + ".");
        }

        // An IOPATH names bare pins on one cell, drawn from a tiny alphabet ("I0", "O", "C", "Q"),
        // so interning collapses millions of duplicates for the cost of a few map entries. An
        // INTERCONNECT names full hierarchical pin paths: sinks are essentially all distinct, so
        // interning them would pay a contended concurrent-map insert per endpoint to dedupe almost
        // nothing. On a 412 MB file that cost more than doubled the parse time.
        boolean iopath = kind == SdfDelayEntry.Kind.IOPATH;
        SdfEdge sourceEdge = SdfEdge.NONE;
        String source;
        if (iopath && tokenizer.peekNonWhitespace() == '(') {
            // An edge-qualified source, which Vivado writes for a flip-flop's asynchronous set and
            // reset arcs: (IOPATH (posedge PRE) Q (162.0:213.0:213.0)).
            String[] spec = parsePortSpec();
            sourceEdge = edgeOf(spec[0]);
            source = spec[1];
        } else {
            source = tokenizer.getNextTokenString(!iopath);
            if (!iopath) {
                source = dedupeSource(source);
            }
        }
        String destination = tokenizer.getNextTokenString(!iopath);
        SdfDelayValues values = parseDelayValueList();
        expectToken(SdfKeywords.RIGHT_PAREN);

        return new SdfDelayEntry(kind, sourceEdge, source, destination, values, byteOffset,
                lineNumber);
    }

    /**
     * Parses a {@code (TIMINGCHECK ...)} block.
     *
     * @param checks Destination for the parsed checks, appended in file order.
     */
    private void parseTimingCheckBlock(List<SdfTimingCheck> checks) {
        while (true) {
            String token = tokenizer.getNextTokenString(true);
            if (SdfKeywords.RIGHT_PAREN.equals(token)) {
                return;
            }
            expect(SdfKeywords.LEFT_PAREN, token);
            checks.add(parseTimingCheck());
        }
    }

    /**
     * Parses one timing check. The opening parenthesis has already been consumed.
     *
     * @return The parsed check.
     */
    private SdfTimingCheck parseTimingCheck() {
        long byteOffset = tokenizer.getTokenStartByteOffset();
        long lineNumber = tokenizer.getTokenStartLineNumber();
        String keyword = tokenizer.getNextTokenString(true);

        SdfTimingCheck.Kind kind;
        boolean twoPorts;
        if (SdfKeywords.SETUPHOLD.equalsIgnoreCase(keyword)) {
            kind = SdfTimingCheck.Kind.SETUPHOLD;
            twoPorts = true;
        } else if (SdfKeywords.RECREM.equalsIgnoreCase(keyword)) {
            kind = SdfTimingCheck.Kind.RECREM;
            twoPorts = true;
        } else if (SdfKeywords.PERIOD.equalsIgnoreCase(keyword)) {
            kind = SdfTimingCheck.Kind.PERIOD;
            twoPorts = false;
        } else if (SdfKeywords.WIDTH.equalsIgnoreCase(keyword)) {
            kind = SdfTimingCheck.Kind.WIDTH;
            twoPorts = false;
        } else {
            throw tokenizer.parseError("unsupported timing check '" + keyword + "'."
                    + " RapidWright supports the subset of SDF that Vivado's write_sdf emits;"
                    + " see " + SdfKeywords.class.getSimpleName() + ".");
        }

        String[] first = parsePortSpec();
        String[] second = twoPorts ? parsePortSpec() : null;

        // Each value field of a timing check is exactly one delval.
        SdfDelayValues firstValues = parseDelayValueList(1);
        SdfDelayValues secondValues = twoPorts ? parseDelayValueList(1) : null;
        expectToken(SdfKeywords.RIGHT_PAREN);

        return new SdfTimingCheck(kind, edgeOf(first[0]), first[1],
                second == null ? SdfEdge.NONE : edgeOf(second[0]),
                second == null ? null : second[1],
                firstValues, secondValues, byteOffset, lineNumber);
    }

    /**
     * Parses a port specification, which is either a bare pin name or a pin name qualified by an
     * edge, as in {@code (posedge C)}.
     *
     * @return A two-element array of {@code {edgeKeywordOrNull, portName}}.
     */
    private String[] parsePortSpec() {
        String token = tokenizer.getNextTokenString(false);
        if (!SdfKeywords.LEFT_PAREN.equals(token)) {
            return new String[] {null, token};
        }
        String edge = tokenizer.getNextTokenString(true);
        String port = tokenizer.getNextTokenString(false);
        expectToken(SdfKeywords.RIGHT_PAREN);
        return new String[] {edge, port};
    }

    private SdfEdge edgeOf(String keyword) {
        try {
            return SdfEdge.fromKeyword(keyword);
        } catch (IllegalArgumentException e) {
            throw tokenizer.parseError("unsupported edge qualifier '" + keyword + "'; expected "
                    + SdfKeywords.POSEDGE + " or " + SdfKeywords.NEGEDGE);
        }
    }

    /**
     * Collapses the repeated source endpoint of a driver's {@code INTERCONNECT} entries onto a
     * single string.
     *
     * Vivado emits every sink of a driver consecutively, so a one-element cache dedupes exactly as
     * well as a hash map would. Measured over the corpus this is not an approximation: in each
     * file the number of consecutive runs of equal sources equals the number of distinct sources,
     * for ratios between four and nine sinks per driver. Unlike a shared pool, this costs one
     * reference comparison and no synchronisation, so it stays free when workers run in parallel.
     *
     * @param source The source endpoint just parsed.
     * @return An equal string, reusing the previous one when they match.
     */
    private String dedupeSource(String source) {
        if (source.equals(lastInterconnectSource)) {
            return lastInterconnectSource;
        }
        lastInterconnectSource = source;
        return source;
    }

    /**
     * Parses a run of delvals, each either {@code ()} or a parenthesised {@code min:typ:max}
     * triple, stopping at the first token that is not an opening parenthesis.
     *
     * Reading values directly as integers here, rather than as tokens, is what keeps the parser
     * fast on files containing tens of millions of them, and is also what makes the writer able to
     * reproduce the input exactly.
     *
     * @return The parsed delvals, preserving which slots were absent.
     */
    private SdfDelayValues parseDelayValueList() {
        return parseDelayValueList(0);
    }

    /**
     * Parses at most {@code maxCount} delvals.
     *
     * The bound matters: a timing check's value fields are each a single delval, so
     * {@code (SETUPHOLD (posedge D) (posedge C) (a) (b))} is two one-element lists, not one
     * two-element list. Parsing greedily there would fold the hold limit into the setup limit and
     * emit a stray separator when writing the file back out.
     *
     * @param maxCount Maximum number of delvals to consume, or 0 for as many as are present.
     * @return The parsed delvals, preserving which slots were absent.
     */
    private SdfDelayValues parseDelayValueList(int maxCount) {
        int size = 0;
        long presentMask = 0;
        long tripleMask = 0;
        long paddedMask = 0;
        int[] tenths = null;

        while ((maxCount <= 0 || size < maxCount) && tokenizer.peekNonWhitespace() == '(') {
            tokenizer.expectChar('(');
            if (size >= Long.SIZE) {
                throw tokenizer.parseError("more than " + Long.SIZE + " delay values in one list");
            }

            // Vivado writes fast-corner files with one space here and slow-corner files with none.
            int pad = tokenizer.consumeSpaces();
            if (pad > 1) {
                throw tokenizer.parseError("a delay value is indented by " + pad + " spaces;"
                        + " RapidWright models only the single space Vivado writes in fast-corner"
                        + " files, and wider padding would not survive a round-trip");
            }
            if (pad == 1) {
                paddedMask |= 1L << size;
            }
            if (tenths == null) {
                tenths = new int[SdfDelayValues.TRIPLE_SIZE * 6];
            } else if (tenths.length < (size + 1) * SdfDelayValues.TRIPLE_SIZE) {
                int[] grown = new int[tenths.length * 2];
                System.arraycopy(tenths, 0, grown, 0, tenths.length);
                tenths = grown;
            }

            if (tokenizer.peekNonWhitespace() == ')') {
                // An absent delval, written "()". Distinct from a delval of zero.
                tokenizer.expectChar(')');
                size++;
                continue;
            }

            int base = size * SdfDelayValues.TRIPLE_SIZE;
            tenths[base] = tokenizer.nextTenths();
            char next = tokenizer.peekNonWhitespace();
            if (next == ':') {
                tokenizer.expectChar(':');
                tenths[base + 1] = tokenizer.nextTenths();
                tokenizer.expectChar(':');
                tenths[base + 2] = tokenizer.nextTenths();
                if (tokenizer.peekNonWhitespace() == ':') {
                    throw tokenizer.parseError("a delay triple must have exactly three components");
                }
                tripleMask |= 1L << size;
            } else {
                // A single value stands for all three components, as in PATHPULSEPERCENT.
                tenths[base + 1] = tenths[base];
                tenths[base + 2] = tenths[base];
            }
            tokenizer.expectChar(')');
            presentMask |= 1L << size;
            size++;
        }

        if (size == 0) {
            return SdfDelayValues.EMPTY;
        }
        int[] exact = new int[size * SdfDelayValues.TRIPLE_SIZE];
        System.arraycopy(tenths, 0, exact, 0, exact.length);
        return new SdfDelayValues(exact, presentMask, tripleMask, paddedMask, size);
    }

    /**
     * Checks the invariants the rest of the SDF support relies on.
     *
     * @param file The parsed model.
     */
    static void validate(SdfFile file) {
        int topCells = 0;
        for (int i = 0; i < file.getCells().size(); i++) {
            SdfCell cell = file.getCells().get(i);
            if (!cell.isTopCell()) {
                continue;
            }
            topCells++;
            if (i != file.getCells().size() - 1) {
                throw new SdfParseException(file.getSource(), cell.getLineNumber(),
                        cell.getStartByteOffset(),
                        "the top-level cell (the one with an empty INSTANCE) must be the last cell"
                        + " in the file, but " + (file.getCells().size() - 1 - i)
                        + " cells follow it");
            }
        }
        if (topCells > 1) {
            throw new SdfParseException(file.getSource(), -1, -1,
                    "found " + topCells + " cells with an empty INSTANCE; expected at most one");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private void expectToken(String expected) {
        expect(expected, tokenizer.getNextTokenString(true));
    }

    private void expectKeyword(String expected) {
        String actual = tokenizer.getNextTokenString(true);
        if (!expected.equalsIgnoreCase(actual)) {
            throw tokenizer.parseError("expected '" + expected + "' but found '" + actual + "'");
        }
    }

    private void expect(String expected, String actual) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw tokenizer.parseError("expected '" + expected + "' but found '" + actual + "'");
        }
    }

    /**
     * @return The tokenizer this parser reads from.
     */
    SdfTokenizer getTokenizer() {
        return tokenizer;
    }

    @Override
    public void close() throws IOException {
        tokenizer.close();
    }
}
