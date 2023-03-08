/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.xilinx.rapidwright.util.StringPool;

/**
 * Tokenize an InputStream containing an EDIF. This class buffers its input internally. To minimize copying data,
 * combining it with a {@link java.io.BufferedInputStream} should be avoided.
 */
public class EDIFTokenizer implements AutoCloseable {

    private final Path fileName;

    private final InputStream in;

    private final byte[] buffer;
    private static final Charset charset = StandardCharsets.UTF_8;

    protected long byteOffset;

    protected final StringPool uniquifier;
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

    public EDIFTokenizer(Path fileName, InputStream in, StringPool uniquifier, int maxTokenLength) {
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

    public EDIFTokenizer(Path fileName, InputStream in, StringPool uniquifier) {
        this(fileName, in, uniquifier, DEFAULT_MAX_TOKEN_LENGTH);
    }


    /**
     * Read two separate locations from a buffer, concatenating them into a single string.
     * Suppports multi-byte characters split between the two parts
     * @param buffer the buffer to read from
     * @param start1 first part start
     * @param length1 first part length
     * @param start2 second part start
     * @param length2 second part length
     * @return the string assembled from the two locations
     */
    public static String byteArrayToStringMulti(byte[] buffer, int start1, int length1, int start2, int length2) {
        //To support multi-byte characters being split between the parts, we have to take
        // care to first concatenate, then decode.
        byte[] complete = new byte[length1 + length2];
        System.arraycopy(buffer, start1, complete, 0, length1);
        System.arraycopy(buffer, start2, complete, length1, length2);
        return new String(complete, charset);
    }

    /**
     * create a token instance from offsets
     * @param startOffset start offset inside buffer. inclusive
     * @param endOffset end offset inside buffer. exclusive
     * @param isShortLived skip uniquifying if true
     * @return decoded token text
     */
    protected String getUniqueToken(int startOffset, int endOffset, boolean isShortLived) {
        String token;
        if (endOffset >= startOffset) {
            token = new String(buffer, startOffset, endOffset-startOffset, charset);
        } else {
            token = byteArrayToStringMulti(buffer, startOffset, buffer.length-startOffset, 0, endOffset);
        }
        if (!isShortLived) {
            token = uniquifier.uniquifyName(token);
        }
        byteOffset+= token.length();
        available-= token.length();
        if (available<0) {
            throw new EDIFParseException("Token probably too long or failed to fetch data in time: "+ token +" at "+byteOffset);
        }
        return token;
    }

    /**
     * Starting quote is expected to have already been read. Searching for closing quote and return everything between.
     * @return The token
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

    private static boolean[] makeTokenEnderTable() {
        boolean[] res = new boolean[256];
        for (int i = 0; i < 256; i++) {
            char c = (char) i;
            res[i] = endsTokenSwitch(c);
        }
        return res;
    }

    private static final boolean[] ENDS_TOKEN = makeTokenEnderTable();


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
        //Inverting the result of endsTokenOpt performed better than having a continuesToken function.
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

    /**
     * Get the next token object
     * @param isShortLived skip uniquifying if true
     * @return token object, or null if at end of file
     */
    public EDIFToken getOptionalNextToken(boolean isShortLived) {
        String tokenText = getOptionalNextTokenString(isShortLived);
        if (tokenText==null) {
            return null;
        }
        return new EDIFToken(tokenText, byteOffset);
    }

    /**
     * Get the next token string
     * @param isShortLived skip uniquifying if true
     * @return token text, or null if at end of file
     */
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
    private boolean advanceToEndOfQuote() {
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
            if (ch == 0) {
                throw new IllegalStateException("unexpected end of file marker");
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
     */
    void advanceToTokenEnder() {
        int ch;
        while ((ch = buffer[offset]) != 0) {
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
     * This method tries to advance to the next token boundary. This is an educated guess that needs to be verified once
     * the thread that reads the preceding part of the file catches up to this one.
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
            long skipped = in.skip(i-actual);
            if (skipped == 0) {
                if (in.read() == -1) {
                    // EOF reached
                    break;
                }
                // One byte was successfully read
                skipped = 1;
            }
            actual += skipped;
        }
        byteOffset += actual;
    }

    /**
     * Skip ahead by some offset.
     * After skipping, this method tries to advance to the next token boundary. This is an educated guess that needs
     * to be verified once the thread that reads the preceding part of the file catches up to this one.
     * @param i offset to advance by
     */
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

    public StringPool getUniquifier() {
        return uniquifier;
    }

    public long getByteOffset() {
        return byteOffset;
    }
}
