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

package com.xilinx.rapidwright.util.function;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.function.IOSupplier;

import com.xilinx.rapidwright.util.FileTools;

public interface InputStreamSupplier extends IOSupplier<InputStream> {
    static InputStreamSupplier fromPath(Path p, boolean decompressToDisk) {
        return () -> getInputStream(p, decompressToDisk);
    }

    /**
     * Gets the InputStream for the provided file path. If the file is gzipped (*.gz
     * extension), it will decompress the file alongside the original with the '.gz'
     * extension removed.
     * 
     * @param fileName         Path to the file or gzipped file from which to get an
     *                         InputStream.
     * @param decompressToDisk To make certain operations faster, decompress the
     *                         file to disk first rather than to decompress through
     *                         the InputStream.
     * @return An InputStream of the file, or of a decompressed copy of a gzipped
     *         file.
     */
    public static InputStream getInputStream(Path fileName, boolean decompressToDisk) {
        InputStream in = null;
        try {
            if (fileName.toString().endsWith(".gz") && decompressToDisk) {
                Path decompressed = FileTools.getDecompressedGZIPFileName(fileName);
                synchronized (InputStreamSupplier.class) {
                    if (!decompressed.toFile().exists()) {
                        fileName = FileTools.decompressGZIPFile(fileName);
                    } else {
                        fileName = decompressed;
                    }
                }
            }
            in = new FileInputStream(fileName.toString());
            if (fileName.toString().endsWith(".gz")) {
                in = new GZIPInputStream(in);
            }
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException("ERROR: Could not find file: " + fileName, e);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Problem reading file: " + fileName, e);
        }
        return in;
    }
}
