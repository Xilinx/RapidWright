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
public class SdfInput {

    /** First two bytes of every gzip stream. */
    private static final int GZIP_MAGIC_0 = 0x1f;

    /** Second byte of the gzip magic number. */
    private static final int GZIP_MAGIC_1 = 0x8b;

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
        try {
            InputStream in = Files.newInputStream(fileName);
            if (isGzipped(fileName)) {
                return new GZIPInputStream(in, GZIP_BUFFER_SIZE);
            }
            return in;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }
    }

    /**
     * Returns the length of an SDF file's uncompressed content.
     *
     * For a plain file this is the file size. For a gzipped file the size must be measured by
     * decompressing, since the gzip trailer's length field is only 32 bits and so is unreliable for
     * the large files this parser targets.
     *
     * @param fileName The file to measure.
     * @return The number of uncompressed bytes.
     */
    public static long getUncompressedSize(Path fileName) {
        if (!isGzipped(fileName)) {
            try {
                return Files.size(fileName);
            } catch (IOException e) {
                throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
            }
        }
        long total = 0;
        byte[] buffer = new byte[GZIP_BUFFER_SIZE];
        try (InputStream in = open(fileName)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }
        return total;
    }
}
