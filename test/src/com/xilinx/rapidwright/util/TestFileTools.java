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
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URL;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    @Test
    public void testUnzipFileChecksCrc(@TempDir Path tempDir) throws IOException {
        final byte[] data = new byte[64];
        Arrays.fill(data, (byte) 'A');
        final CRC32 crc = new CRC32();
        crc.update(data);
        final Path zip = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : new String[]{"a.txt", "b.txt"}) {
                // Stored, so that each entry's data is in the file as is
                ZipEntry e = new ZipEntry(name);
                e.setMethod(ZipEntry.STORED);
                e.setSize(data.length);
                e.setCrc(crc.getValue());
                zos.putNextEntry(e);
                zos.write(data);
                zos.closeEntry();
            }
        }

        // Intact: every entry is extracted
        final Path good = tempDir.resolve("good");
        FileTools.unzipFile(zip.toString(), good.toString());
        Assertions.assertArrayEquals(data, Files.readAllBytes(good.resolve("a.txt")));
        Assertions.assertArrayEquals(data, Files.readAllBytes(good.resolve("b.txt")));

        // The first entry's data corrupted, but not its CRC: extracting stops at it, as it fails
        final byte[] bytes = Files.readAllBytes(zip);
        int start = 0;
        for (int run = 0; run < data.length; start++) {
            run = bytes[start] == 'A' ? run + 1 : 0;
        }
        start -= data.length;
        bytes[start] = 'B';
        Files.write(zip, bytes);
        final Path bad = tempDir.resolve("bad");
        FileTools.unzipFile(zip.toString(), bad.toString());
        Assertions.assertTrue(Files.exists(bad.resolve("a.txt")));
        Assertions.assertFalse(Files.exists(bad.resolve("b.txt")));
    }

    /**
     * Serves the given body for each request, as a response that claims its full length but whose
     * connection is closed after only the given number of bytes of it, for each of the first
     * truncatedResponses requests (as a server can do while a download looks successful), then in
     * full. Runs until the returned socket is closed.
     */
    private static ServerSocket serveTruncated(byte[] body, int truncatedResponses, int bytesSent,
                                               AtomicInteger requests) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    // Read the request up to the blank line that ends its headers
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                            StandardCharsets.US_ASCII));
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    }
                    boolean truncated = requests.getAndIncrement() < truncatedResponses;
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body, 0, truncated ? bytesSent : body.length);
                    out.flush();
                } catch (IOException e) {
                    // Closed
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(0).nextBytes(bytes);
        return bytes;
    }

    private static void assertNoTemporaryFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            Assertions.assertEquals(0, files.filter((f) -> f.getFileName().toString().endsWith(".part")).count());
        }
    }

    @Test
    public void testDownloadDataFileRetriesTruncatedDownload(@TempDir Path dir) throws IOException {
        byte[] body = randomBytes(100000);
        String md5 = Installer.calculateMD5OfStream(new ByteArrayInputStream(body));
        Path dst = dir.resolve("sub").resolve("data.dat");
        AtomicInteger requests = new AtomicInteger();
        try (ServerSocket server = serveTruncated(body, 2, 1000, requests)) {
            String url = new URL("http", server.getInetAddress().getHostAddress(), server.getLocalPort(), "/data")
                    .toString();
            // The first two downloads end early without any error, so are only found invalid by their md5sum
            Assertions.assertEquals(md5, FileTools.downloadDataFile(url, dst, md5, 3, 1));
        }
        Assertions.assertEquals(3, requests.get());
        Assertions.assertArrayEquals(body, Files.readAllBytes(dst));
        assertNoTemporaryFiles(dst.getParent());
    }

    @Test
    public void testDownloadDataFileFailureKeepsExistingFile(@TempDir Path dir) throws IOException {
        byte[] body = randomBytes(100000);
        String md5 = Installer.calculateMD5OfStream(new ByteArrayInputStream(body));
        Path dst = dir.resolve("data.dat");
        byte[] existing = "existing".getBytes(StandardCharsets.US_ASCII);
        Files.write(dst, existing);
        AtomicInteger requests = new AtomicInteger();
        try (ServerSocket server = serveTruncated(body, Integer.MAX_VALUE, 1000, requests)) {
            String url = new URL("http", server.getInetAddress().getHostAddress(), server.getLocalPort(), "/data")
                    .toString();
            RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                    () -> FileTools.downloadDataFile(url, dst, md5, 3, 1));
            Assertions.assertTrue(e.getMessage().contains("after 3 attempts"), e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("downloaded 1000 bytes"), e.getMessage());
        }
        Assertions.assertEquals(3, requests.get());
        // An invalid download never replaces the file there
        Assertions.assertArrayEquals(existing, Files.readAllBytes(dst));
        assertNoTemporaryFiles(dir);
    }
}
