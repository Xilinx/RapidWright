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

package com.xilinx.rapidwright.edif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that gzipped EDIF is recognized by its content, so that files which do
 * not carry the conventional '.gz' extension are still read correctly.
 */
public class TestEDIFGzipDetection {

    private static final String NETLIST = "(edif test\n"
            + "  (edifVersion 2 0 0)\n"
            + "  (edifLevel 0)\n"
            + "  (keywordMap (keywordLevel 0))\n"
            + "  (status (written (timeStamp 2024 1 1 0 0 0)"
            + " (program \"Vivado\" (version \"2024.1\"))))\n"
            + "  (library work\n"
            + "    (edifLevel 0)\n"
            + "    (technology (numberDefinition))\n"
            + "    (cell top (cellType GENERIC)\n"
            + "      (view netlist (viewType NETLIST)\n"
            + "        (interface (port a (direction INPUT)))\n"
            + "      )\n"
            + "    )\n"
            + "  )\n"
            + "  (design top (cellRef top (libraryRef work)))\n"
            + ")\n";

    private static void assertParses(EDIFNetlist netlist) {
        Assertions.assertNotNull(netlist.getDesign());
        Assertions.assertEquals("top", netlist.getDesign().getTopCell().getName());
        Assertions.assertEquals(1, netlist.getDesign().getTopCell().getPorts().size());
    }

    private static Path writeGzipped(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(p))) {
            out.write(NETLIST.getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }

    private static Path writePlain(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, NETLIST.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test
    public void testGzippedWithoutGzExtension(@TempDir Path dir) throws IOException {
        Path p = writeGzipped(dir, "compressed.edf");
        Assertions.assertTrue(EDIFTools.isGzipped(p));
        try (EDIFParser parser = new EDIFParser(p)) {
            assertParses(parser.parseEDIFNetlist());
        }
        assertParses(EDIFTools.loadEDIFFile(p));
    }

    @Test
    public void testGzippedWithGzExtension(@TempDir Path dir) throws IOException {
        Path p = writeGzipped(dir, "compressed.edf.gz");
        Assertions.assertTrue(EDIFTools.isGzipped(p));
        assertParses(EDIFTools.loadEDIFFile(p));
    }

    @Test
    public void testPlainFileNotDetectedAsGzip(@TempDir Path dir) throws IOException {
        Path p = writePlain(dir, "plain.edf");
        Assertions.assertFalse(EDIFTools.isGzipped(p));
        assertParses(EDIFTools.loadEDIFFile(p));
    }

    @Test
    public void testEmptyFileNotDetectedAsGzip(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.edf");
        Files.write(p, new byte[0]);
        Assertions.assertFalse(EDIFTools.isGzipped(p));
    }

    /**
     * Detection peeks at the stream that is already open, so it must leave the
     * leading bytes in place for the caller.
     */
    @Test
    public void testOpenEDIFInputStreamPreservesLeadingBytes(@TempDir Path dir) throws IOException {
        for (Path p : new Path[] { writePlain(dir, "plain.edf"), writeGzipped(dir, "gz.edf"),
                writeGzipped(dir, "named.edf.gz") }) {
            try (InputStream in = EDIFTools.openEDIFInputStream(p)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[512];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                Assertions.assertEquals(NETLIST, out.toString("UTF-8"),
                        "content differs for " + p.getFileName());
            }
        }
    }

    /** A file too short to hold the magic bytes must not be misread as gzip. */
    @Test
    public void testTruncatedFileNotDetectedAsGzip(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("onebyte.edf");
        Files.write(p, new byte[] { (byte) 0x1f });
        Assertions.assertFalse(EDIFTools.isGzipped(p));
        try (InputStream in = EDIFTools.openEDIFInputStream(p)) {
            Assertions.assertEquals(0x1f, in.read());
            Assertions.assertEquals(-1, in.read());
        }
    }

    /**
     * The parallel parser derives its thread count and worker offsets from the gzip
     * flag, so it must reach that path correctly both when the caller supplies the
     * flag and when the parser determines it.
     */
    @Test
    public void testParallelParserOnGzippedWithoutGzExtension(@TempDir Path dir)
            throws IOException {
        Path p = writeGzipped(dir, "compressed.edf");
        try (ParallelEDIFParser parser =
                new ParallelEDIFParser(p, Files.size(p), EDIFTools.isGzipped(p))) {
            assertParses(parser.parseEDIFNetlist());
        }
        try (ParallelEDIFParser parser = new ParallelEDIFParser(p)) {
            assertParses(parser.parseEDIFNetlist());
        }
    }

    /** The same, for a plain file, so a wrongly-set flag cannot pass unnoticed. */
    @Test
    public void testParallelParserOnPlainFile(@TempDir Path dir) throws IOException {
        Path p = writePlain(dir, "plain.edf");
        try (ParallelEDIFParser parser =
                new ParallelEDIFParser(p, Files.size(p), EDIFTools.isGzipped(p))) {
            assertParses(parser.parseEDIFNetlist());
        }
    }
}
