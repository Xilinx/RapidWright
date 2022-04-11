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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestEDIFParser {
    private static final Path input = RapidWrightDCP.getPath("edif_parsing_stress_test.edf");

    private static final long FILE_SIZE;

    static {
        try {
            FILE_SIZE = Files.size(input);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * List of byte offsets that cause interesting behaviour if we start parsing there
     */
    private static final List<ParseStart> interestingOffsets = Arrays.asList(
      new ParseStart("Mismatch in First Evil Cell", 517L, false),
      new ParseStart("Mismatch in Second Evil Cell", 849L, false),
      new ParseStart("Totally Misaligned", 1045L, false),
      new ParseStart("After Evil", 1111L, true),
      new ParseStart("At EOF", FILE_SIZE-2, false)
    );

    /**
     * Check that we can recover from misdetected token starts.
     *
     * Deliberately set a very low max token length for the tokenizer. Then run the parallel EDIF parser with very
     * specific start offsets to generate interesting behavior.
     */
    @ParameterizedTest(name="{0}")
    @MethodSource("testParallelArgs")
    @CheckOpenFiles
    public void testParallel(String ignoredDescription, List<ParseStart> offsets, int expectedSuccessfulThreads) throws IOException {
        try (ParallelEDIFParserTestSpecificOffsets parser = new ParallelEDIFParserTestSpecificOffsets(input, 128, offsets)) {
            parser.parseEDIFNetlist(new CodePerfTracker("parse edif"));
            Assertions.assertEquals(expectedSuccessfulThreads, parser.getSuccessfulThreads());
        }
    }

    /**
     * Use listIndex as a bitfield to select which of the items in interestingOffsets to include in the testcase run
     */
    private static Arguments makeArgs(int listIndex) {
        Stream.Builder<String> names = Stream.builder();
        List<ParseStart> offsets = new ArrayList<>();
        offsets.add(new ParseStart("Start of file", 0L, true));

        int expectedSuccessfulThreads = 1;
        for (int i=0;i<interestingOffsets.size();i++) {
            if (((1<<i)&listIndex) != 0) {
                final ParseStart offs = interestingOffsets.get(i);
                offsets.add(offs);
                names.add(offs.name);
                if(offs.success) {
                    expectedSuccessfulThreads++;
                }
            }
        }
        String name = names.build().collect(Collectors.joining(", "));
        if (name.isEmpty()) {
            name = "Only at start";
        }
        return Arguments.of(name, offsets, expectedSuccessfulThreads);
    }

    public static Stream<Arguments> testParallelArgs() {
        //Build all combinations of interesting starts
        return IntStream.range(0, 1<<interestingOffsets.size())
                .mapToObj(TestEDIFParser::makeArgs);
    }

    @Test
    @CheckOpenFiles
    public void loadEDIFSingleThreaded() throws IOException {
        try (EDIFParser parser = new EDIFParser(input)) {
            parser.parseEDIFNetlist();
        }

    }
}
