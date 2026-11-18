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

package com.xilinx.rapidwright.util;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestParams {

    /**
     * Params are read once, when the class is loaded, so each case reads
     * RW_ZSTD_COMPRESSION_LEVEL in a JVM of its own, with only the given
     * environment variable set. Its input and output are files rather than
     * pipes: the JDK closes a child process's pipes on a thread of its own
     * once the process exits, which can be after waitFor() returns, and so
     * after this test (whose open files are checked) ends.
     */
    @ParameterizedTest
    @CsvSource({
            "RW_ZSTD_COMPRESSION_LEVEL, 7, 7",
            // Another parameter must not change the compression level
            "RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK, 1, 3",
    })
    public void testZstdCompressionLevel(String name, String value, int expected, @TempDir Path tempDir)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        Map<String, String> env = pb.environment();
        env.remove(Params.RW_ZSTD_COMPRESSION_LEVEL_NAME);
        env.remove(Params.RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK_NAME);
        env.put(name, value);
        String classpath = ManagementFactory.getRuntimeMXBean().getClassPath();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        pb.command(java, "-cp", classpath, Jython.class.getCanonicalName(), "-c",
                "from com.xilinx.rapidwright.util import Params;"
                + "print('LEVEL=%d' % Params.RW_ZSTD_COMPRESSION_LEVEL)");
        Path input = Files.createFile(tempDir.resolve("input"));
        Path outputFile = tempDir.resolve("output");
        pb.redirectInput(input.toFile());
        pb.redirectOutput(outputFile.toFile());
        pb.redirectErrorStream(true);
        int exitCode = pb.start().waitFor();
        String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
        Assertions.assertEquals(0, exitCode, output);
        Assertions.assertTrue(output.contains("LEVEL=" + expected), output);
    }
}
