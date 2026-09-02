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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.xilinx.rapidwright.util.StringPool;

/**
 * Tokenizes an {@link InputStream} containing SDF text.
 *
 * The buffering strategy follows {@code EDIFTokenizer}: a power-of-two circular byte buffer of
 * twice the maximum token length, addressed with a bitmask rather than a modulo, refilled only when
 * less than one maximum token length remains so that a refill can never land in the middle of a
 * token. A zero byte is kept just past the filled region as a sentinel, which makes end-of-file and
 * an over-long token the same cheap check. Because this class does its own buffering, wrapping the
 * stream in a {@link java.io.BufferedInputStream} would only add a copy.
 *
 * Three things differ from the EDIF tokenizer, all forced by the shape of SDF:
 *
 * SDF identifiers carry Verilog-style backslash escapes, so a token may legitimately contain
 * {@code \(}, {@code \)}, {@code \"} or an escaped space. The backslash is therefore treated as a
 * token ender in the lookup table, keeping the hot loop branch-free, and the escape is resolved
 * after the loop by skipping two bytes and re-entering.
 *
 * Resynchronisation after a blind seek is exact rather than heuristic. Every quoted string Vivado
 * writes lies entirely on one line, so a position immediately following a newline is guaranteed not
 * to be inside a string. {@link #skip(long)} therefore advances to the next line start, which is a
 * single scan for {@code \n}, instead of the statistical inside-or-outside-quotes guess the EDIF
 * tokenizer has to make.
 *
 * Delay values are read straight out of the ring buffer into {@code int} tenths by
 * {@link #nextTenths()}, never through a {@code String} or {@code Float}. A large SDF holds tens of
 * millions of these, and a float round-trip would not reproduce the input bytes.
 */
public class SdfTokenizer implements AutoCloseable {

    /** Buffer sizing, matching the EDIF tokenizer's default of 4 MiB per token. */
    public static final int DEFAULT_MAX_TOKEN_LENGTH = 8192 * 16 * 32;

    private static final Charset CHARSET = StandardCharsets.UTF_8;

    private final Path fileName;

    private final InputStream in;

    private final byte[] buffer;

    private final StringPool uniquifier;

    private final int maxTokenLength;

    private final int bufferAddressMask;

    private long byteOffset;

    private long lineNumber = 1;

    /** Byte offset of the first character of the most recently returned token. */
    private long tokenStartByteOffset;

    /** Line number of the first character of the most recently returned token. */
    private long tokenStartLineNumber = 1;

    private int offset = 0;

    private int available = 0;

    private boolean sawEOF = false;

    /**
     * @param fileName The file being read, used only for diagnostics.
     * @param in The stream to tokenize; must not be additionally buffered.
     * @param uniquifier Pool used to intern long-lived token text.
     * @param maxTokenLength Maximum token length in bytes; must be a power of two.
     */
    public SdfTokenizer(Path fileName, InputStream in, StringPool uniquifier, int maxTokenLength) {
        this.fileName = fileName;
        this.in = in;
        this.uniquifier = uniquifier;
        this.maxTokenLength = maxTokenLength;
        // Only a power of two shares no bits with its lower neighbour, which is what makes the
        // bitmask a valid substitute for a modulo.
        if (maxTokenLength <= 0 || (maxTokenLength & (maxTokenLength - 1)) != 0) {
            throw new IllegalArgumentException("ERROR: max token length must be a power of two"
                    + " but is " + maxTokenLength);
        }
        this.bufferAddressMask = maxTokenLength * 2 - 1;
        this.buffer = new byte[maxTokenLength * 2];
    }

    /**
     * @param fileName The file being read, used only for diagnostics.
     * @param in The stream to tokenize; must not be additionally buffered.
     * @param uniquifier Pool used to intern long-lived token text.
     */
    public SdfTokenizer(Path fileName, InputStream in, StringPool uniquifier) {
        this(fileName, in, uniquifier, DEFAULT_MAX_TOKEN_LENGTH);
    }

    // ------------------------------------------------------------------------------------------
    // Buffer management
    // ------------------------------------------------------------------------------------------

    private boolean ensureRead(int startOffset, int endOffset) throws IOException {
        while (startOffset < endOffset) {
            int actuallyRead = in.read(buffer, startOffset, endOffset - startOffset);
            if (actuallyRead == -1) {
                sawEOF = true;
                buffer[startOffset] = 0;
                return false;
            }
            available += actuallyRead;
            startOffset += actuallyRead;
        }
        return true;
    }

    /**
     * Loads more data from the stream, doing nothing unless less than one maximum token length
     * remains. Fills all but one byte of the buffer and writes a zero sentinel into that byte, so
     * that a refill never has to happen mid-token and an over-long token is detectable.
     *
     * @throws IOException If the underlying stream fails.
     */
    private void fill() throws IOException {
        if (available > maxTokenLength || sawEOF) {
            return;
        }

        int fillStart = (offset + available) & bufferAddressMask;
        int fillEnd = (offset - 1) & bufferAddressMask;

        // Overwrite the zero left by initialization or by the previous fill's EOF placeholder.
        assert buffer[fillStart] == 0;

        buffer[fillEnd] = 0;

        if (fillStart > fillEnd) {
            // The free region wraps, so fill it in two parts.
            if (!ensureRead(fillStart, buffer.length)) {
                return;
            }
            ensureRead(0, fillEnd);
        } else {
            ensureRead(fillStart, fillEnd);
        }
    }

    private void skipInBuffer(int amount) {
        available -= amount;
        offset = bufferAddressMask & (offset + amount);
        byteOffset += amount;
    }

    // ------------------------------------------------------------------------------------------
    // Character classification
    // ------------------------------------------------------------------------------------------

    /**
     * Whether a character terminates an unquoted token.
     *
     * The backslash is included so that the hot scanning loop stays a single table lookup with no
     * branches; the escape it introduces is resolved by the caller once the loop exits.
     *
     * @param c The character to classify.
     * @return True if the character ends a token.
     */
    private static boolean endsTokenSwitch(char c) {
        switch (c) {
            case 0:
            case '"':
            case '(':
            case ')':
            case '\\':
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                return true;
            default:
                return false;
        }
    }

    private static boolean[] makeTokenEnderTable() {
        boolean[] res = new boolean[256];
        for (int i = 0; i < 256; i++) {
            res[i] = endsTokenSwitch((char) i);
        }
        return res;
    }

    private static final boolean[] ENDS_TOKEN = makeTokenEnderTable();

    /**
     * @param c The character to classify.
     * @return True if the character ends a token. A table lookup, which measured faster than the
     *         equivalent switch.
     */
    private static boolean endsToken(char c) {
        return ENDS_TOKEN[c];
    }

    // ------------------------------------------------------------------------------------------
    // Token extraction
    // ------------------------------------------------------------------------------------------

    /**
     * Decodes a token from buffer offsets, handling the case where it wraps the ring.
     *
     * @param startOffset Start offset within the buffer, inclusive.
     * @param endOffset End offset within the buffer, exclusive.
     * @param isShortLived Skip interning when true.
     * @return The decoded token text.
     */
    private String getUniqueToken(int startOffset, int endOffset, boolean isShortLived) {
        String token;
        int length;
        if (endOffset >= startOffset) {
            length = endOffset - startOffset;
            token = new String(buffer, startOffset, length, CHARSET);
        } else {
            int length1 = buffer.length - startOffset;
            length = length1 + endOffset;
            // Concatenate before decoding so a multi-byte character split across the ring boundary
            // is still decoded correctly.
            byte[] complete = new byte[length];
            System.arraycopy(buffer, startOffset, complete, 0, length1);
            System.arraycopy(buffer, 0, complete, length1, endOffset);
            token = new String(complete, CHARSET);
        }
        if (!isShortLived) {
            token = uniquifier.uniquifyName(token);
        }
        byteOffset += length;
        available -= length;
        if (available < 0) {
            throw parseError("token probably too long, or data was not fetched in time: " + token);
        }
        return token;
    }

    /**
     * Reads a quoted token. The opening quote is expected to have been consumed already.
     *
     * @param isShortLived Skip interning when true.
     * @return The token text, without the surrounding quotes.
     * @throws IOException If the underlying stream fails.
     */
    private String getQuotedToken(boolean isShortLived) throws IOException {
        int offsetStart = offset;

        byte current = buffer[offset];
        int newlines = 0;
        while (current != 0 && current != '"') {
            if (current == '\n') {
                newlines++;
            }
            offset = bufferAddressMask & (offset + 1);
            current = buffer[offset];
        }
        if (current == 0) {
            if (sawEOF) {
                throw parseError("unexpected end of file inside a quoted string");
            }
            throw tokenTooLong(offsetStart);
        }

        // getUniqueToken checks the remaining length, so account for both quotes beforehand.
        available -= 2;

        String token = getUniqueToken(offsetStart, offset, isShortLived);

        offset = (offset + 1) & bufferAddressMask;
        byteOffset += 2;
        lineNumber += newlines;

        return token;
    }

    /**
     * Reads an unquoted token, resolving any backslash escapes it contains.
     *
     * @param isShortLived Skip interning when true.
     * @return The token text, with escapes preserved verbatim so it can be written back out.
     * @throws IOException If the underlying stream fails.
     */
    private String getUnquotedToken(boolean isShortLived) throws IOException {
        int offsetStart = bufferAddressMask & (offset - 1);
        // Rewind over the byte the dispatch loop already consumed so it is examined here too. That
        // matters when a token begins with an escape, as in a name whose first character is
        // escaped: the backslash must be seen by the escape handling below rather than treated as
        // an immediate token terminator.
        offset = offsetStart;

        while (true) {
            byte current = buffer[offset];

            // The hottest loop in the parser: scan for anything that ends a token and work out
            // which case it was afterwards.
            while (!endsToken((char) current)) {
                offset = bufferAddressMask & (offset + 1);
                current = buffer[offset];
            }

            if (current == '\\') {
                // A Verilog escape: consume the backslash and whatever it protects, then keep
                // scanning. The escaped bytes stay in the token text.
                offset = bufferAddressMask & (offset + 1);
                byte escaped = buffer[offset];
                if (escaped == 0) {
                    if (sawEOF) {
                        throw parseError("unexpected end of file after an escape character");
                    }
                    throw tokenTooLong(offsetStart);
                }
                if (escaped == '\n') {
                    lineNumber++;
                }
                offset = bufferAddressMask & (offset + 1);
                continue;
            }

            if (current == '"') {
                throw parseError("unexpected quote inside a token");
            }
            if (current == 0 && !sawEOF) {
                throw tokenTooLong(offsetStart);
            }
            return getUniqueToken(offsetStart, offset, isShortLived);
        }
    }

    private RuntimeException tokenTooLong(int startOffset) {
        long byteOffsetAtStart = this.byteOffset;
        int endOffset = bufferAddressMask & (startOffset + 150);
        String failingToken = getUniqueToken(startOffset, endOffset, true);
        return new SdfParseException(fileName, lineNumber, byteOffsetAtStart,
                "token buffer overflow parsing a token starting with " + failingToken + "..."
                + "\n\tThis usually means a parallel worker resynchronised at a position that only"
                + " looked like a token boundary. If the token really is this long, increase the"
                + " buffer size in " + getClass().getCanonicalName() + ".");
    }

    private char readByte() throws IOException {
        fill();
        char res = (char) buffer[offset];
        if (res == 0) {
            return 0;
        }
        offset = bufferAddressMask & (offset + 1);
        return res;
    }

    /**
     * Reads the next token, skipping whitespace.
     *
     * @param isShortLived Skip interning when true. Pass true for keywords and punctuation, which
     *                     are compared and discarded, and false for names that are retained.
     * @return The token text, or null at end of file. Parentheses are returned as the interned
     *         constants {@link SdfKeywords#LEFT_PAREN} and {@link SdfKeywords#RIGHT_PAREN}.
     */
    public String getOptionalNextTokenString(boolean isShortLived) {
        try {
            char ch;
            while ((ch = readByte()) != 0) {
                switch (ch) {
                    case '"':
                        // byteOffset still points at the opening quote at this point.
                        tokenStartByteOffset = byteOffset;
                        tokenStartLineNumber = lineNumber;
                        return getQuotedToken(isShortLived);
                    case '(':
                        tokenStartByteOffset = byteOffset;
                        tokenStartLineNumber = lineNumber;
                        byteOffset++;
                        available--;
                        return SdfKeywords.LEFT_PAREN;
                    case ')':
                        tokenStartByteOffset = byteOffset;
                        tokenStartLineNumber = lineNumber;
                        byteOffset++;
                        available--;
                        return SdfKeywords.RIGHT_PAREN;
                    case '\n':
                        lineNumber++;
                        byteOffset++;
                        available--;
                        break;
                    case ' ':
                    case '\r':
                    case '\t':
                        byteOffset++;
                        available--;
                        break;
                    default:
                        tokenStartByteOffset = byteOffset;
                        tokenStartLineNumber = lineNumber;
                        return getUnquotedToken(isShortLived);
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
    }

    /**
     * Reads the next token, which must exist.
     *
     * @param isShortLived Skip interning when true.
     * @return The token text.
     * @throws SdfParseException At end of file.
     */
    public String getNextTokenString(boolean isShortLived) {
        String token = getOptionalNextTokenString(isShortLived);
        if (token == null) {
            throw parseError("unexpected end of file");
        }
        return token;
    }

    /**
     * Reads the next token together with the position at which it started.
     *
     * @param isShortLived Skip interning when true.
     * @return The token, or null at end of file.
     */
    public SdfToken getOptionalNextToken(boolean isShortLived) {
        String text = getOptionalNextTokenString(isShortLived);
        if (text == null) {
            return null;
        }
        return new SdfToken(text, tokenStartByteOffset, tokenStartLineNumber);
    }

    /**
     * @return Byte offset of the first character of the most recently returned token. Unlike
     *         {@link #getByteOffset()} this is the token's start, which is what a diagnostic or a
     *         chunk boundary should refer to.
     */
    public long getTokenStartByteOffset() {
        return tokenStartByteOffset;
    }

    /**
     * @return 1-based line number of the first character of the most recently returned token.
     */
    public long getTokenStartLineNumber() {
        return tokenStartLineNumber;
    }

    /**
     * Reads the next token as a delay value, in tenths of the file's time unit.
     *
     * This bypasses token decoding entirely, reading digits straight out of the ring buffer. It is
     * the single hottest operation when parsing a large SDF, and going through a {@code String} and
     * {@code Float.parseFloat} here would both cost throughput and lose the exact decimal
     * representation the writer needs.
     *
     * @return The value in tenths, or {@link SdfDelayValues#NEG_ZERO} for a literal {@code -0.0}.
     * @throws SdfParseException If the next token is not a well-formed delay value.
     */
    public int nextTenths() {
        try {
            fill();
            // Skip whitespace.
            char ch;
            while (true) {
                ch = (char) buffer[offset];
                if (ch == 0) {
                    throw parseError("unexpected end of file where a delay value was expected");
                }
                if (ch == ' ' || ch == '\t' || ch == '\r') {
                    skipInBuffer(1);
                } else if (ch == '\n') {
                    lineNumber++;
                    skipInBuffer(1);
                } else {
                    break;
                }
            }

            long startByteOffset = byteOffset;
            boolean negative = false;
            if (ch == '-') {
                negative = true;
                skipInBuffer(1);
            } else if (ch == '+') {
                skipInBuffer(1);
            }

            long value = 0;
            int intDigits = 0;
            while (true) {
                ch = (char) buffer[offset];
                if (ch < '0' || ch > '9') break;
                value = value * 10 + (ch - '0');
                if (value > Integer.MAX_VALUE) {
                    throw new SdfParseException(fileName, lineNumber, startByteOffset,
                            "delay value out of range");
                }
                intDigits++;
                skipInBuffer(1);
            }
            if (intDigits == 0) {
                throw new SdfParseException(fileName, lineNumber, startByteOffset,
                        "expected a delay value");
            }

            value *= 10;
            if (value > Integer.MAX_VALUE) {
                throw new SdfParseException(fileName, lineNumber, startByteOffset,
                        "delay value out of range");
            }

            if ((char) buffer[offset] == '.') {
                skipInBuffer(1);
                int fracDigits = 0;
                while (true) {
                    ch = (char) buffer[offset];
                    if (ch < '0' || ch > '9') break;
                    if (fracDigits == 0) {
                        value += (ch - '0');
                        if (value > Integer.MAX_VALUE) {
                            throw new SdfParseException(fileName, lineNumber, startByteOffset,
                                    "delay value out of range");
                        }
                    } else if (ch != '0') {
                        // Rounding here would silently corrupt a delay, so refuse instead.
                        throw new SdfParseException(fileName, lineNumber, startByteOffset,
                                "delay value has more precision than one fractional digit, which"
                                + " RapidWright's exact integer representation cannot hold");
                    }
                    fracDigits++;
                    skipInBuffer(1);
                }
                if (fracDigits == 0) {
                    throw new SdfParseException(fileName, lineNumber, startByteOffset,
                            "expected a fractional digit after '.'");
                }
            }

            // The value must be followed by a delimiter, not by more identifier characters.
            char terminator = (char) buffer[offset];
            if (terminator != 0 && !endsToken(terminator) && terminator != ':') {
                throw new SdfParseException(fileName, lineNumber, startByteOffset,
                        "malformed delay value");
            }

            if (negative) {
                if (value == 0) {
                    return SdfDelayValues.NEG_ZERO;
                }
                return (int) -value;
            }
            return (int) value;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
    }

    /**
     * Consumes a single expected delimiter character, skipping any preceding whitespace.
     *
     * @param expected The character to consume.
     * @throws SdfParseException If the next non-whitespace character is not the expected one.
     */
    public void expectChar(char expected) {
        try {
            fill();
            while (true) {
                char ch = (char) buffer[offset];
                if (ch == 0) {
                    throw parseError("unexpected end of file, expected '" + expected + "'");
                }
                if (ch == '\n') {
                    lineNumber++;
                    skipInBuffer(1);
                } else if (ch == ' ' || ch == '\t' || ch == '\r') {
                    skipInBuffer(1);
                } else if (ch == expected) {
                    skipInBuffer(1);
                    return;
                } else {
                    throw parseError("expected '" + expected + "' but found '" + ch + "'");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
    }

    /**
     * Counts and consumes a run of spaces at the current position.
     *
     * Used to capture the single space Vivado writes between a delval's opening parenthesis and its
     * first value in fast-corner files. That space carries no meaning, but reproducing it is what
     * makes a round-trip of such a file byte-identical.
     *
     * @return The number of spaces consumed.
     */
    public int consumeSpaces() {
        try {
            fill();
            int count = 0;
            while ((char) buffer[offset] == ' ') {
                skipInBuffer(1);
                count++;
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
    }

    /**
     * Looks at the next non-whitespace character without consuming it.
     *
     * @return The character, or 0 at end of file.
     */
    public char peekNonWhitespace() {
        try {
            fill();
            while (true) {
                char ch = (char) buffer[offset];
                if (ch == 0) {
                    return 0;
                }
                if (ch == '\n') {
                    lineNumber++;
                    skipInBuffer(1);
                } else if (ch == ' ' || ch == '\t' || ch == '\r') {
                    skipInBuffer(1);
                } else {
                    return ch;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Seeking
    // ------------------------------------------------------------------------------------------

    private void ensureSkip(long i) throws IOException {
        if (available != 0) {
            throw new IllegalStateException("ERROR: cannot skip with data still buffered");
        }
        long actual = 0;
        while (actual < i) {
            long skipped = in.skip(i - actual);
            if (skipped == 0) {
                if (in.read() == -1) {
                    break;
                }
                skipped = 1;
            }
            actual += skipped;
        }
        byteOffset += actual;
    }

    /**
     * Advances to the byte immediately following the next newline.
     *
     * Every quoted string in a Vivado-written SDF lies entirely on one line, so the position
     * reached is guaranteed not to be inside a string. That makes this an exact resynchronisation
     * point rather than the educated guess a general s-expression tokenizer has to make.
     *
     * @return True if a line start was reached, false if end of file came first.
     * @throws IOException If the underlying stream fails.
     */
    private boolean advanceToNextLineStart() throws IOException {
        while (true) {
            fill();
            char ch = (char) buffer[offset];
            if (ch == 0) {
                if (sawEOF) {
                    skipInBuffer(available);
                    return false;
                }
                throw parseError("no newline found within one buffer length while resynchronising");
            }
            skipInBuffer(1);
            if (ch == '\n') {
                lineNumber++;
                return true;
            }
        }
    }

    /**
     * Skips ahead by the given number of bytes, then advances to the following line start.
     *
     * @param i Number of bytes to skip.
     * @return True if a line start was reached, false if end of file came first.
     */
    public boolean skipToLineStart(long i) {
        try {
            if (i > 0) {
                ensureSkip(i);
            }
            return advanceToNextLineStart();
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
    }

    /**
     * Skips ahead to an offset already known to be a line start, without scanning.
     *
     * Used by the parallel parser, whose index pass has already proved the target position is the
     * first byte of a line, so no resynchronisation is needed.
     *
     * @param targetByteOffset Absolute byte offset to seek to; must be a line start.
     * @param targetLineNumber 1-based line number at that offset.
     */
    public void seekToKnownLineStart(long targetByteOffset, long targetLineNumber) {
        if (targetByteOffset < byteOffset) {
            throw new IllegalArgumentException("ERROR: cannot seek backwards, from " + byteOffset
                    + " to " + targetByteOffset);
        }
        try {
            ensureSkip(targetByteOffset - byteOffset);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading SDF file: "
                    + fileName, e);
        }
        this.lineNumber = targetLineNumber;
    }

    // ------------------------------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a positioned parse exception at the tokenizer's current location.
     *
     * @param message Description of the problem.
     * @return The exception, for the caller to throw.
     */
    public SdfParseException parseError(String message) {
        return new SdfParseException(fileName, lineNumber, byteOffset, message);
    }

    /**
     * @return The file being read, or null if the stream did not come from one.
     */
    public Path getFileName() {
        return fileName;
    }

    /**
     * @return The current absolute byte offset within the file.
     */
    public long getByteOffset() {
        return byteOffset;
    }

    /**
     * @return The current 1-based line number.
     */
    public long getLineNumber() {
        return lineNumber;
    }

    /**
     * @return The pool used to intern token text.
     */
    public StringPool getUniquifier() {
        return uniquifier;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
