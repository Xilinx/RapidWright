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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.StringPool;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestEDIFTokenizer {

    private static final int TESTING_MAX_TOKEN_LENGTH = 4096;

    private List<EDIFToken> readTokens(EDIFTokenizer tokenizer) {
        List<EDIFToken> tokens = new ArrayList<>();
        EDIFToken t;
        while ((t = tokenizer.getOptionalNextToken(false)) != null) {
            tokens.add(t);
        }
        return tokens;
    }

    @Test
    public void testTokenizerOffsets(@TempDir Path tempDir) throws IOException {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        Path edif = tempDir.resolve("picoblaze.edf");
        d.getNetlist().exportEDIF(edif);
        long fileSize = Files.size(edif);
        EDIFTokenizer tokenizer = new EDIFTokenizer(edif, new BufferedInputStream(Files.newInputStream(edif)), StringPool.singleThreadedPool());

        List<EDIFToken> allTokens = readTokens(tokenizer);
        tokenizer.close();

        LongStream.range(0, fileSize).parallel()
                .forEach(i-> {
                    try (EDIFTokenizer skipTokenizer = new EDIFTokenizer(edif, new BufferedInputStream(Files.newInputStream(edif)), StringPool.singleThreadedPool(), TESTING_MAX_TOKEN_LENGTH)) {
                        skipTokenizer.skip(i);

                        compareSuffixTokens(i, allTokens, skipTokenizer);

                    } catch (RuntimeException e) {
                        throw new RuntimeException("Failed parsing starting at offset "+i, e);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private void compareSuffixTokens(long offset, List<EDIFToken> allTokens, EDIFTokenizer tokenizer) {
        final EDIFToken firstToken = tokenizer.getOptionalNextToken(true);
        if (firstToken == null) {
            //Already at end
            return;
        }

        int tokenOffset = allTokens.indexOf(firstToken);
        if (tokenOffset ==-1) {
            Assertions.fail(generateOffsetErrorMsg(offset, allTokens, firstToken));
        }
    }

    @NotNull
    private String generateOffsetErrorMsg(long offset, List<EDIFToken> allTokens, EDIFToken firstSuffix) {
        if (firstSuffix.byteOffset <= allTokens.get(0).byteOffset) {
            return "With start offset " + offset + ", suffix tokens were not a suffix of full tokens. First suffix token: "
                    + firstSuffix + ", first full token: " + allTokens.get(0);
        }

        for (int i = 1; i< allTokens.size(); i++) {
            EDIFToken currentAll = allTokens.get(i);

            if (currentAll.byteOffset >= firstSuffix.byteOffset) {
                return "With start offset " + offset + ", suffix tokens were not a suffix of full tokens. First suffix token: "
                        + firstSuffix + ". Tokens around that byte offset: " + allTokens.get(i - 1) + ","
                        + currentAll;
            }
        }
        return "With start offset " + offset + ", suffix tokens " + " were not a suffix of full tokens. First suffix token: "
                + firstSuffix + ", last full token: " + allTokens.get(allTokens.size() - 1);
    }

    private byte[] toByteArray(String s) {
        final ByteBuffer buffer = StandardCharsets.UTF_8.encode(s);
        byte[] data = new byte[buffer.limit()];
        buffer.get(data);
        return data;
    }

    private InputStream stringToInputStream(String s) {
        return new ByteArrayInputStream(toByteArray(s));
    }

    @Test
    public void readEmptyQuotes() throws IOException {
        EDIFTokenizer tokenizerV2 = new EDIFTokenizer(null, new ByteArrayInputStream(toByteArray("\"\"")), StringPool.singleThreadedPool(), EDIFTokenizer.DEFAULT_MAX_TOKEN_LENGTH);
        final EDIFToken token = tokenizerV2.getOptionalNextToken(true);
        Assertions.assertNotNull(token);
        Assertions.assertEquals("", token.text);
        Assertions.assertEquals(2, token.byteOffset);
    }

    @Test
    public void testTooLongToken() {
        InputStream is = stringToInputStream(repeatString("ASDF", 250));
        EDIFTokenizer tokenizer = new EDIFTokenizer(null, is, StringPool.singleThreadedPool(), 256);
        Assertions.assertThrows(TokenTooLongException.class, () -> tokenizer.getOptionalNextToken(true));
    }

    private String repeatString(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }


    private static final int FILLING_MAX_TOKEN_LENGTH=8;
    private static final int READ_COUNT = 3;


    void doTestFilling(List<Integer> lengths) {
        try {

            String alphabet = "abcdefghijklmnopqrstuvwxyz";
            String input = repeatString(alphabet + alphabet.toUpperCase() + "0123456789", 8);


            StringBuilder sb = new StringBuilder();

            TestingTokenizer tokenizer = new TestingTokenizer(null, stringToInputStream(input), StringPool.singleThreadedPool(), FILLING_MAX_TOKEN_LENGTH);

            for (Integer length : lengths) {
                tokenizer.fill();
                sb.append(tokenizer.testingReadToken(length));
            }

            String readString = sb.toString();
            Assertions.assertEquals(lengths.stream().mapToInt(i -> i).sum(), readString.length());
            Assertions.assertEquals(input.substring(0, readString.length()), readString);
        } catch (IOException e) {
            Assertions.fail(e);
        }
    }

    Stream<List<Integer>> testTokenizerFilling(List<Integer> oldLengths) {
        if (oldLengths.size()>=READ_COUNT) {
            return Stream.of(oldLengths);
        }
        return IntStream.rangeClosed(0, FILLING_MAX_TOKEN_LENGTH).mapToObj(i -> {
            final ArrayList<Integer> newLengths = new ArrayList<>(oldLengths);
            newLengths.add(i);
            return newLengths;
        }).flatMap(this::testTokenizerFilling);

    }


    @Test
    void testTokenizerFilling() {
        testTokenizerFilling(new ArrayList<>())
                .forEach(this::doTestFilling);
    }

    static class TestingTokenizer extends EDIFTokenizer {

        public TestingTokenizer(Path fileName, InputStream in, StringPool uniquifier, int maxTokenLength) {
            super(fileName, in, uniquifier, maxTokenLength);
        }

        String testingReadToken(int length) {
            Assertions.assertTrue(length<=maxTokenLength);
            int endOffset = bufferAddressMask & (offset+length);
            final String text = getUniqueToken(offset, endOffset, true);
            offset = endOffset;
            return text;
        }

    }


    @Test
    void testConcatenateMultibyte() {
        //This test string contains multi-byte characters. We cannot encode it directly as a string here, because
        //source code encoding varies between platforms.
        byte[] bytes = new byte[]{
                (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x8b, (byte) 0xf0, (byte) 0x9f,
                (byte) 0x8e, (byte) 0x9b, (byte) 0xef, (byte) 0xb8, (byte) 0x8f, (byte) 0xc3,
                (byte) 0xa4, (byte) 0xc3, (byte) 0xb6, (byte) 0xc3, (byte) 0xbc, (byte) 0xc3,
                (byte) 0x9f, (byte) 0xce, (byte) 0xa9, (byte) 0xce, (byte) 0xa6
        };
        String orig = new String(bytes, StandardCharsets.UTF_8);
        for (int i=0;i<bytes.length;i++) {

            String read = EDIFTokenizer.byteArrayToStringMulti(bytes, 0, i, i, bytes.length-i);
            Assertions.assertEquals(orig, read);
        }
    }



}
