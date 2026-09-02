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

package com.xilinx.rapidwright.timing.sdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Opens SDF files, transparently handling compression.
 *
 * {@code write_sdf -gzip} writes a gzip stream but keeps whatever filename it was given, so a
 * compressed SDF is routinely called {@code design.sdf} with no {@code .gz} suffix. Detection is
 * therefore done by reading the stream's magic bytes rather than by inspecting the name.
 *
 * Note that {@code FileTools.isFileGzipped} cannot be used here: it tests for the zlib magic
 * {@code 78 9c}, not the gzip magic {@code 1f 8b}, and {@code InputStreamSupplier} keys off the
 * {@code .gz} extension.
 */
class SdfInput {

    /** First two bytes of every gzip stream. */
    private static final int GZIP_MAGIC_0 = 0x1f;

    /** Second byte of the gzip magic number. */
    private static final int GZIP_MAGIC_1 = 0x8b;

    /** Bytes of magic number that identify a gzip stream. */
    private static final int GZIP_MAGIC_LENGTH = 2;

    /**
     * Buffer size for the decompressor. A larger buffer than the default measurably speeds up
     * gzip reads, matching the sizing {@code FileTools.decompressGZIPFile} settled on.
     */
    private static final int GZIP_BUFFER_SIZE = 65536;

    private SdfInput() {
    }

    /**
     * Tests whether a file is gzip-compressed by inspecting its first two bytes.
     *
     * @param fileName The file to test.
     * @return True if the file begins with the gzip magic number.
     */
    public static boolean isGzipped(Path fileName) {
        try (InputStream in = Files.newInputStream(fileName)) {
            return in.read() == GZIP_MAGIC_0 && in.read() == GZIP_MAGIC_1;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }
    }

    /**
     * Opens an SDF file for reading, decompressing it if it is gzipped.
     *
     * The returned stream is deliberately not wrapped in a {@link java.io.BufferedInputStream}:
     * {@link SdfTokenizer} maintains its own buffer, so an extra layer would only add a copy.
     *
     * @param fileName The file to open.
     * @return A stream of the file's uncompressed bytes.
     */
    public static InputStream open(Path fileName) {
        InputStream in = null;
        try {
            // Sniffed and consumed through one handle: opening the file a second time to test for
            // gzip would double the I/O and, if the decompressor then failed to start, leak the
            // first stream.
            in = new PushbackInputStream(Files.newInputStream(fileName), GZIP_MAGIC_LENGTH);
            byte[] magic = new byte[GZIP_MAGIC_LENGTH];
            int read = 0;
            while (read < GZIP_MAGIC_LENGTH) {
                int n = in.read(magic, read, GZIP_MAGIC_LENGTH - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            boolean gzipped = read == GZIP_MAGIC_LENGTH
                    && (magic[0] & 0xff) == GZIP_MAGIC_0 && (magic[1] & 0xff) == GZIP_MAGIC_1;
            if (read > 0) {
                ((PushbackInputStream) in).unread(magic, 0, read);
            }
            return gzipped ? new GZIPInputStream(in, GZIP_BUFFER_SIZE) : in;
        } catch (IOException e) {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }
    }
}
