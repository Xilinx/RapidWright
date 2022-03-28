/*
 * Copyright (c) 2022 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class EDIFTokenizer implements AutoCloseable {
    private static final boolean debug = false;

    private final Path fileName;

    private final InputStream in;

    private final byte[] buffer;

    protected long byteOffset;

    protected final NameUniquifier uniquifier;
    private final int maxTokenLength;
    private final int bufferAddressMask;

    public static final int DEFAULT_MAX_TOKEN_LENGTH = 8192*16*32;


    private int offset = 0;
    private int available = 0;
    private boolean sawEOF = false;

    /**
     * Load more data from stream.
     *
     * Only do anything if there is less than one max token length of data available. Then, fill the whole buffer
     * except one byte. That one byte is set to -1. This way, we never have to re-fill inside long tokens. We also
     * can detect too-long tokens that are longer than our buffer.
     */
    private void fill() throws IOException {
        if (available>maxTokenLength || sawEOF) {
            return;
        }

        //Start position, inclusive
        int fillPosition = (offset+available)&bufferAddressMask;

        boolean fillingAtEnd = fillPosition > offset;

        //End is exclusive
        int fillEnd = fillingAtEnd ? buffer.length : offset;
        if (fillEnd == 0) {
            fillEnd = buffer.length;
        }

        //Filling the array completely?
        if ( (fillEnd & bufferAddressMask) == offset) {
            //Leave space for the marker byte
            fillEnd=bufferAddressMask&(fillEnd-1);
            buffer[bufferAddressMask&(offset-1)] = -1;
        }

        while (fillPosition < fillEnd) {
            int actuallyRead = in.read(buffer, fillPosition, fillEnd-fillPosition);
            if (actuallyRead==-1) {
                sawEOF = true;
                buffer[fillPosition] = -1;
                return;
            }
            fillPosition+=actuallyRead;
            available+=actuallyRead;
        }

        if (fillingAtEnd) {
            //Also fill at beginning
            fill();
        }
    }

    public EDIFTokenizer(Path fileName, InputStream in, NameUniquifier uniquifier, int maxTokenLength) {
        this.fileName = fileName;
        this.in = in;
        this.uniquifier = uniquifier;
        this.maxTokenLength = maxTokenLength;
        //Only a power of two does not share any bits with its lower neighbour
        if ((maxTokenLength & (maxTokenLength-1)) != 0) {
            throw new IllegalStateException("max token length must be a power of two but is "+maxTokenLength);
        }
        bufferAddressMask = maxTokenLength*2-1;
        this.buffer = new byte[maxTokenLength*2];
    }

    public EDIFTokenizer(Path fileName, InputStream in, NameUniquifier uniquifier) {
        this(fileName, in, uniquifier, DEFAULT_MAX_TOKEN_LENGTH);
    }

    /**
     * create a token instance from offsets
     * @param startOffset start offset inside buffer. inclusive
     * @param endOffset end offset inside buffer. exclusive
     * @param isShortLived only long lived tokens will get uniquified
     * @return
     */
    private EDIFToken getUniqueToken(int startOffset, int endOffset, boolean isShortLived) {
        String tmp;
        if (endOffset > startOffset) {
            tmp = new String(buffer, startOffset, endOffset-startOffset);
        } else {
            //We do string concatenation here, so we introduce a copy. It does not seem like there is any way to avoid this.
            String strA = new String(buffer, startOffset, buffer.length-startOffset);
            String strB = new String(buffer, 0, endOffset);
            tmp = strA + strB;
        }
        String unique = uniquifier.uniquifyName(tmp, isShortLived);
        final EDIFToken edifToken = new EDIFToken(unique, byteOffset);
        byteOffset+=unique.length();
        available-=unique.length();
        return edifToken;
    }

    /**
     * Starting quote is expected to have already been read. Searching for closing quote and return everything before.
     * @return
     */
    private EDIFToken getQuotedToken(boolean isShortLived) throws IOException {
        int offsetStart = offset;

        LOOP: while (true) {
            switch (buffer[offset]) {
                case -1:
                    if (sawEOF) {
                        throw EDIFParseException.unexpectedEOF();
                    }
                    throw tokenTooLong(offsetStart);
                case '"':
                    break LOOP;
            }
            offset = bufferAddressMask & (offset+1);
        }
        final EDIFToken token = getUniqueToken(offsetStart, offset, isShortLived);

        offset=(offset+1)&bufferAddressMask; //Actually read closing quote

        byteOffset+=2; //Adjust for both quotes
        available-=2;

        return token;
    }

    private IOException tokenTooLong(int bufferOffset) {
        final long byteOffsetAtStart = this.byteOffset;
        final String failingToken = getUniqueToken(bufferOffset, bufferOffset + 150, true).text;
        throw new TokenTooLongException("ERROR: String buffer overflow on byte offset " +
                byteOffsetAtStart + " parsing token starting with "+ failingToken +"...\n\t Please revisit why this EDIF token "
                + "is so long or increase the buffer in " + this.getClass().getCanonicalName());
    }

    private EDIFToken getUnquotedTokenLong(boolean isShortLived) throws IOException {

        int offsetStart = bufferAddressMask & (offset-1);

        LOOP: while (true) {
            switch (buffer[offset]) {
                case '"':
                    throw new EDIFParseException("Cannot have quote inside of token!");
                case -1:
                    if (!sawEOF) {
                        throw tokenTooLong(offsetStart);
                    }
                    //else fallthrough
                case '\n':
                case ' ':
                case '\r':
                case '\t':
                case '(':
                case ')':
                    break LOOP;
            }
            offset = bufferAddressMask & (offset+1);
        }
        return getUniqueToken(offsetStart, offset, isShortLived);
    }

    private int readByte() throws IOException {
        fill();
        int res = buffer[offset];
        if (res!=-1) {
            offset=bufferAddressMask & (offset+1);
        }
        return res;
    }

    public EDIFToken getOptionalNextToken(boolean isShortLived) {
        try {
            int ch;
            while ((ch = readByte()) != -1) {

                switch (ch) {
                    case '"':
                        return getQuotedToken(isShortLived);
                    case '(':byteOffset++;available--;
                        return new EDIFToken("(", byteOffset-1);
                    case ')':byteOffset++;available--;
                        return new EDIFToken(")", byteOffset-1);

                    case ' ':
                    case '\n':
                    case '\r':
                    case '\t':
                        byteOffset++;
                        available--;
                        break;
                    default:
                            return getUnquotedTokenLong(isShortLived);
                }
            }
            //EOF
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading EDIF file: "
                    + fileName, e);
        }
    }

    public Path getFileName() {
        return fileName;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }


    private static final Set<Character> tokenEnderChars = new HashSet<>();
    static {
        tokenEnderChars.add('"');
        tokenEnderChars.add('(');
        tokenEnderChars.add(')');
        tokenEnderChars.add(' ');
        tokenEnderChars.add('\n');
        tokenEnderChars.add('\r');
        tokenEnderChars.add('\t');
    }

    void skipInBuffer(int amount) {
        available -= amount;
        offset = bufferAddressMask & (offset+amount);
        byteOffset += amount;
    }

    /**
     * During advancing, we may have ended up in a quoted string. Compare the amount of token ending characters inside
     * and outside of quotes to determine this case. The higher ratio is probably outside.
     * @return True if we succeeded, false if reached EOF
     */
    private boolean advanceToEndOfQuote() throws IOException {
        if (offset != 0) {
            throw new RuntimeException("can only advance if current offset is zero!");
        }
        if (available != buffer.length) {
            //Hit EOF
            skipInBuffer(available);
            return false;
        }

        boolean inQuote = false;
        int totalInQuote = 0;
        int tokenEndersInQuote = 0;
        int totalOutsideQuote = 0;
        int tokenEndersOutsideQuote = 0;
        Integer firstQuoteOffset = null;
        for (int i = 0; i < maxTokenLength*2; i++) {
            int ch = buffer[i];
            if (ch == -1) {
                throw new IllegalStateException("unexpected -1");
            }
            if (ch == '"') {
                inQuote = !inQuote;
                if (firstQuoteOffset == null) {
                    firstQuoteOffset = i;
                }
            } else {
                boolean isTokenEnder = tokenEnderChars.contains((char)ch);
                if (inQuote) {
                    totalInQuote++;
                    if (isTokenEnder) {
                        tokenEndersInQuote++;
                    }
                } else {
                    totalOutsideQuote++;
                    if (isTokenEnder) {
                        tokenEndersOutsideQuote++;
                    }
                }
            }
        }

        //Never saw any Quotes?
        if (firstQuoteOffset == null) {
            return true;
        }

        float enderRatioInside = (float) tokenEndersInQuote / totalInQuote;
        float enderRatioOutside = (float) tokenEndersOutsideQuote / totalOutsideQuote;

        if (totalOutsideQuote == 0 || enderRatioInside > enderRatioOutside) {
            skipInBuffer(firstQuoteOffset+1);
        }
        return true;
    }

    /**
     * Advance to the next token ending character but don't consume it
     * @throws IOException
     */
    void advanceToTokenEnder() throws IOException{
        int ch;
        while ((ch = buffer[offset]) != -1) {
            if (tokenEnderChars.contains((char)ch)) {
                return;
            }
            skipInBuffer(1);
        }
        //Reached EOF. nothing to do
    }

    /**
     * In parallel loading, we seek ahead by some offset. We probably end up inside some token.
     *
     * This method tries to advance to the next token boundary. This is just a guess that needs to verified once the
     * thread that reads the preceding part of the file catches up to this one.
     */
    private void advanceToTokenBeginning() throws IOException {
        fill();

        if (!advanceToEndOfQuote()) {
            return;
        }
        advanceToTokenEnder();

    }

    private void ensureSkip(long i) throws IOException {
        if (available!=0) {
            throw new RuntimeException("available != 0");
        }
        long actual = 0;
        while (actual < i) {
            actual += in.skip(i-actual);
        }
        byteOffset += i;
    }

    public void skip(long i) {
        if (i == 0) {
            return;
        }
        try {
            ensureSkip(i);
            advanceToTokenBeginning();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
