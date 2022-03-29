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

public class EDIFTokenizerV2 implements AutoCloseable, IEDIFTokenizer {
    private static final boolean debug = false;

    private final Path fileName;

    private final InputStream in;

    private final byte[] buffer;

    protected long byteOffset;

    protected final NameUniquifier uniquifier;
    protected final int maxTokenLength;
    protected final int bufferAddressMask;

    public static final int DEFAULT_MAX_TOKEN_LENGTH = 8192*16*32;


    protected int offset = 0;
    private int available = 0;
    private boolean sawEOF = false;

    private boolean ensureRead(int startOffset, int endOffset) throws IOException {
        while (startOffset < endOffset) {
            int actuallyRead = in.read(buffer, startOffset, endOffset-startOffset);
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
     * Load more data from stream.
     *
     * Only do anything if there is less than one max token length of data available. Then, fill the whole buffer
     * except one byte. That one byte is set to 0. This way, we never have to re-fill inside long tokens. We also
     * can detect too-long tokens that are longer than our buffer.
     */
    protected void fill() throws IOException {
        if (available>maxTokenLength || sawEOF) {
            return;
        }

        int fillStart = (offset+available)&bufferAddressMask;
        int fillEnd = (offset-1) & bufferAddressMask;

        buffer[fillEnd] = 0;

        if (fillStart>fillEnd) {
            //Fill in two parts
            if (!ensureRead(fillStart, buffer.length)) {
                return;
            }
            ensureRead(0, fillEnd);
        } else {
            ensureRead(fillStart, fillEnd);
        }
    }

    public EDIFTokenizerV2(Path fileName, InputStream in, NameUniquifier uniquifier, int maxTokenLength) {
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

    public EDIFTokenizerV2(Path fileName, InputStream in, NameUniquifier uniquifier) {
        this(fileName, in, uniquifier, DEFAULT_MAX_TOKEN_LENGTH);
    }

    /**
     * create a token instance from offsets
     * @param startOffset start offset inside buffer. inclusive
     * @param endOffset end offset inside buffer. exclusive
     * @param isShortLived only long lived tokens will get uniquified
     * @return
     */
    protected String getUniqueToken(int startOffset, int endOffset, boolean isShortLived) {
        String tmp;
        if (endOffset >= startOffset) {
            tmp = new String(buffer, startOffset, endOffset-startOffset);
        } else {
            //We do string concatenation here, so we introduce a copy. It does not seem like there is any way to avoid this.
            String strA = new String(buffer, startOffset, buffer.length-startOffset);
            String strB = new String(buffer, 0, endOffset);
            tmp = strA + strB;
        }
        String unique = uniquifier.uniquifyName(tmp, isShortLived);
        byteOffset+=unique.length();
        available-=unique.length();
        if (available<0) {
            throw new EDIFParseException("Token probably too long or failed to fetch data in time: "+unique+" at "+byteOffset);
        }
        return unique;
    }

    /**
     * Starting quote is expected to have already been read. Searching for closing quote and return everything before.
     * @return
     */
    private String getQuotedToken(boolean isShortLived) throws IOException {
        int offsetStart = offset;

        byte current = buffer[offset];
        while (current != 0 && current!='"') {
            offset=bufferAddressMask & (offset+1);
            current=buffer[offset];
        }
        if (current==0) {
            if (sawEOF) {
                throw EDIFParseException.unexpectedEOF();
            }
            throw tokenTooLong(offsetStart);
        }

        //Token length is checked inside getUniqueToken, so let's adjust availability beforehand
        available-=2;

        final String token = getUniqueToken(offsetStart, offset, isShortLived);

        offset=(offset+1)&bufferAddressMask; //Actually read closing quote
        byteOffset+=2; //Adjust for both quotes

        return token;
    }

    private IOException tokenTooLong(int bufferOffset) {
        final long byteOffsetAtStart = this.byteOffset;
        final String failingToken = getUniqueToken(bufferOffset, bufferOffset + 150, true);
        throw new TokenTooLongException("ERROR: String buffer overflow on byte offset " +
                byteOffsetAtStart + " parsing token starting with "+ failingToken +"...\n\t Please revisit why this EDIF token "
                + "is so long or increase the buffer in " + this.getClass().getCanonicalName());
    }


    /**
     * Check if a character ends a token. Hardcoded using switch
     */
    private static boolean endsTokenSwitch(char c) {
        switch (c) {
            case 0:
            case '"':
            case '(':
            case ')':
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                return true;
            default:
                return false;
        }
    }

    private static boolean[] makeTokenEnderTable(int size) {
        boolean[] res = new boolean[size];
        for (int i = 0; i < size; i++) {
            char c = (char) i;
            res[i] = endsTokenSwitch(c);
        }
        return res;
    }

    private static final boolean[] ENDS_TOKEN_ASCII = makeTokenEnderTable(128);
    private static final boolean[] ENDS_TOKEN = makeTokenEnderTable(256);


    /**
     * Check if a character ends a token.
     *
     * This is FASTER than endsTokenSwitch! Hooray for jump tables!
     */
    private static boolean endsTokenOpt(char c) {
        return ENDS_TOKEN[c];
    }


    private String getUnquotedToken(boolean isShortLived) throws IOException {

        int offsetStart = bufferAddressMask & (offset-1);

        byte current = buffer[offset];

        //This is the hottest loop in the whole parser. Just look for anything that ends a token, figure out the reason
        //after the loop.
        while (!endsTokenOpt((char)current)) {
            offset=bufferAddressMask & (offset+1);
            current=buffer[offset];
        }

        switch (current) {
            case '"':
                throw new EDIFParseException("Cannot have quote inside of token!");
            case 0:
                if (!sawEOF) {
                    throw tokenTooLong(offsetStart);
                }
        }
        return getUniqueToken(offsetStart, offset, isShortLived);
    }

    private char readByte() throws IOException {
        fill();
        char res = (char) buffer[offset];
        if (res==0) {
            return 0;
        }
        offset=bufferAddressMask & (offset+1);
        return res;
    }

    public EDIFToken getOptionalNextToken(boolean isShortLived) {
        String tokenText = getOptionalNextTokenString(isShortLived);
        if (tokenText==null) {
            return null;
        }
        return new EDIFToken(tokenText, byteOffset);
    }

    public String getOptionalNextTokenString(boolean isShortLived) {
        try {
            char ch;
            while ((ch = readByte()) != 0) {
                switch (ch) {
                    case '"':
                        return getQuotedToken(isShortLived);
                    case '(':
                        byteOffset++;
                        available--;
                        return "(";
                    case ')':
                        byteOffset++;
                        available--;
                        return ")";

                    case ' ':
                    case '\n':
                    case '\r':
                    case '\t':
                        byteOffset++;
                        available--;
                        break;
                    default:
                        return getUnquotedToken(isShortLived);
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
        if (available != (buffer.length-1)) { //Offset by one since we don't fill the buffer completely
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
        for (int i = 0; i < available; i++) {
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
                boolean isTokenEnder = endsTokenOpt((char)ch);
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
            if (endsTokenOpt((char)ch)) {
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

    @Override
    public NameUniquifier getUniquifier() {
        return uniquifier;
    }

    @Override
    public long getByteOffset() {
        return byteOffset;
    }
}
