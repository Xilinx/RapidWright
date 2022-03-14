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
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class EDIFTokenizer implements AutoCloseable {
    private static final boolean debug = false;

    private final Path fileName;

    private final InputStream in;

    private final char[] buffer;

    private final Deque<EDIFToken> nextTokens = new LinkedList<>(); //TODO Try ArrayDeque

    protected long byteOffset;

    protected final NameUniquifier uniquifier;
    private final int maxTokenLength;

    public static final int DEFAULT_MAX_TOKEN_LENGTH = 8192*16*18;

    public EDIFTokenizer(Path fileName, InputStream in, NameUniquifier uniquifier, int maxTokenLength) {
        this.fileName = fileName;
        this.in = in;
        this.uniquifier = uniquifier;
        this.maxTokenLength = maxTokenLength;
        this.buffer = new char[maxTokenLength];
    }

    public EDIFTokenizer(Path fileName, InputStream in, NameUniquifier uniquifier) {
        this(fileName, in, uniquifier, DEFAULT_MAX_TOKEN_LENGTH);
    }

    private EDIFToken getUniqueToken(long tokenStart, char[] buffer, int offset, int count){
        String tmp = new String(buffer, offset, count);
        String unique = uniquifier.uniquifyName(tmp);
        return new EDIFToken(unique, tokenStart);
    }

    /**
     * Starting quote is expected to have already been read. Searching for closing quote and return everything before.
     * @return
     */
    private EDIFToken getQuotedToken(long tokenStart) throws IOException {

        int ch = -1;
        int idx = 0;
        while((ch = in.read()) != -1){
            byteOffset++;
            if (ch == '"') {
                return getUniqueToken(tokenStart, buffer, 0, idx);
            }
            buffer[idx++] = (char) ch;
        }
        throw EDIFParseException.unexpectedEOF();
    }

    private EDIFToken getUnquotedToken(char startChar, long tokenStart) throws IOException {
        buffer[0] = startChar;
        int idx = 1;
        int ch;
        while ((ch = in.read()) != -1) {
            byteOffset++;
            switch (ch) {
                case '\n':
                case ' ':
                case '\r':
                case '\t':
                    return getUniqueToken(tokenStart, buffer, 0, idx);

                case '(':
                    nextTokens.add(new EDIFToken("(", byteOffset-1));
                    return getUniqueToken(tokenStart, buffer, 0, idx);
                case ')':
                    nextTokens.add(new EDIFToken(")", byteOffset-1));
                    return getUniqueToken(tokenStart, buffer, 0, idx);

                case '"':
                    throw new EDIFParseException("Cannot have quote inside of token!");

                default:
                    buffer[idx++] = (char) ch;
            }
        }
        throw EDIFParseException.unexpectedEOF();
    }

    public EDIFToken getOptionalNextToken() {
        if (!nextTokens.isEmpty()) {
            return nextTokens.poll();
        }
        try {
            int ch;
            while ((ch = in.read()) != -1) {
                byteOffset++;
                switch (ch) {
                    case '"':
                        return getQuotedToken(byteOffset-1);
                    case '(':
                        return new EDIFToken("(", byteOffset-1);
                    case ')':
                        return new EDIFToken(")", byteOffset-1);

                    case ' ':
                    case '\n':
                    case '\r':
                    case '\t':
                        break;
                    default:
                        return getUnquotedToken((char) ch, byteOffset-1);
                }
            }
            //EOF
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: IOException while reading EDIF file: "
                    + fileName, e);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new EDIFParseException("ERROR: String buffer overflow on byte offset " +
                    byteOffset + " parsing token starting with \n\t'" +
                    new String(buffer, 0, 128) + "...'. \n\tPlease revisit why this EDIF token "
                    + "is so long or increase the buffer in " + this.getClass().getCanonicalName(), e);
        }
    }

    public EDIFToken peekOptionalNextToken(){
        if(nextTokens.isEmpty()){
            EDIFToken next = getOptionalNextToken();
            nextTokens.addFirst(next);
        }
        return nextTokens.peek();
    }

    public Path getFileName() {
        return fileName;
    }

    @Override
    public void close() throws IOException {
        if(in!=null) {
            in.close();
        }
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

    /**
     * During advancing, we may have ended up in a quoted string. Compare the amount of token ending characters inside
     * and outside of quotes to determine this case. The higher ratio is probably outside.
     * @return True if we succeeded, false if reached EOF
     */
    private boolean advanceToEndOfQuote() throws IOException {
        in.mark(maxTokenLength*2);
        boolean inQuote = false;
        int totalInQuote = 0;
        int tokenEndersInQuote = 0;
        int totalOutsideQuote = 0;
        int tokenEndersOutsideQuote = 0;
        Integer firstQuoteOffset = null;
        for (int i = 0; i < maxTokenLength*2; i++) {
            int ch = in.read();
            if (ch == -1) {
                //Reached EOF. There's no more tokens for us.
                //Don't bother
                return false;
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

        in.reset();

        //Never saw any Quotes?
        if (firstQuoteOffset == null) {
            return true;
        }

        //Started at quote, never saw another one?
        if (totalOutsideQuote == 0) {
            ensureSkip(firstQuoteOffset+1);
            return true;
        }

        float enderRatioInside = (float) tokenEndersInQuote / totalInQuote;
        float enderRatioOutside = (float) tokenEndersOutsideQuote / totalOutsideQuote;

        if (enderRatioInside > enderRatioOutside) {
            ensureSkip(firstQuoteOffset+1);
        }
        return true;
    }

    /**
     * Advance to the next token ending character but don't consume it
     * @throws IOException
     */
    void advanceToTokenEnder() throws IOException{

        in.mark(maxTokenLength);
        int tokenEnderOffset = 0;
        int ch;
        while ((ch = in.read()) != -1) {
            if (tokenEnderChars.contains((char)ch)) {
                in.reset();
                ensureSkip(tokenEnderOffset);
                return;
            }
            tokenEnderOffset++;
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

        if (!advanceToEndOfQuote()) {
            return;
        }
        advanceToTokenEnder();

    }

    private void ensureSkip(long i) throws IOException {
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
