/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AEAI CTO Group.
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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;

import com.github.luben.zstd.ZstdOutputStream;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.support.StringArrayConverter;
import org.junit.jupiter.params.provider.ValueSource;

public class TestFileTools {

    @ParameterizedTest
    @CsvSource({ "'xc7a15t'", "'xc7a15t, xcau10p'" })
    public void testStaticInstallDataFiles(@ConvertWith(StringArrayConverter.class) String[] devices,
            @TempDir Path tmpPath) {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put(FileTools.RAPIDWRIGHT_VARIABLE_NAME, tmpPath.toString());
        String classpath = ManagementFactory.getRuntimeMXBean().getClassPath();
        
        StringBuilder devicesString = new StringBuilder();
        boolean first = true;
        for (String device : devices) {
            if (first) {
                first = false;
            } else {
                devicesString.append(",");
            }
            devicesString.append("\"" + device + "\"");
        }
        
        pb.command("java", "-cp", classpath, Jython.class.getCanonicalName(), "-c",
                "from com.xilinx.rapidwright.util import FileTools;"
                + "FileTools.ensureDataFilesAreStaticInstallFriendly("+devicesString+")");
        pb.redirectErrorStream(true);
        pb.inheritIO();
        try {
            Process p = pb.start();
            Assertions.assertEquals(0, p.waitFor());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        
        for (String expectedFile : FileTools.getAllDependentDataFiles(devices)) {
            Assertions.assertTrue(tmpPath.resolve(expectedFile).toFile().exists());
        }
    }

    @Test
    public void testIsGzippedFile(@TempDir Path tmpPath) {
        String testFileName = "edif_parsing_stress_test.edf";
        Path input = RapidWrightDCP.getPath(testFileName);
        Path gzipped = tmpPath.resolve(testFileName + ".gz");
        Path zstdFile = tmpPath.resolve(testFileName + ".zstd");
        List<String> lines = FileTools.getLinesFromTextFile(input.toString());
        try (DeflaterOutputStream out = new DeflaterOutputStream(Files.newOutputStream(gzipped))) {
            for (String line : lines) {
                out.write(line.getBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(zstdFile))) {
            for (String line : lines) {
                out.write(line.getBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Assertions.assertTrue(FileTools.isFileGzipped(gzipped));
        Assertions.assertFalse(FileTools.isFileGzipped(input));
        Assertions.assertFalse(FileTools.isFileGzipped(zstdFile));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    public void testGetAutoBufferedInputStream(int mode) throws IOException {
        byte[] ubuf = new byte[1024];
        for (int i = 0; i < ubuf.length; i++) {
            ubuf[i] = (byte) i;
        }

        PipedInputStream pis = new PipedInputStream(1024);
        PipedOutputStream pos = new PipedOutputStream();
        pos.connect(pis);

        byte[] cbuf;
        if (mode == 0) {
            // No compression
            cbuf = ubuf;
        } else if (mode == 1) {
            // Compress using Zstd
            cbuf = Zstd.compress(ubuf);
        } else if (mode == 2) {
            // Compress using ZLIB
            cbuf = new byte[ubuf.length];
            Deflater def = new Deflater();
            def.setInput(ubuf);
            def.finish();
            def.deflate(cbuf);
            def.end();
        } else {
            throw new RuntimeException();
        }
        pos.write(cbuf);

        BufferedInputStream bis = FileTools.getAutoBufferedInputStream(pis);
        if (mode == 2) {
            // Compressed using ZLIB -- check that stream is *not* decompressed
            for (int i = 0; i < cbuf.length; i++) {
                Assertions.assertEquals(cbuf[i], (byte) bis.read());
            }
        } else {
            // Check that the stream is decompressed
            for (int i = 0; i < ubuf.length; i++) {
                Assertions.assertEquals(ubuf[i], (byte) bis.read());
            }
        }
    }
}
