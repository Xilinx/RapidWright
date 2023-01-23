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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.xilinx.rapidwright.util.Params;
import com.xilinx.rapidwright.util.StringPool;
import com.xilinx.rapidwright.util.function.InputStreamSupplier;

/**
 * Manually supply the start offsets of the different threads
 */
public class ParallelEDIFParserTestSpecificOffsets extends ParallelEDIFParser{
    private final List<ParseStart>  startOffsets;

    ParallelEDIFParserTestSpecificOffsets(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier, int maxTokenLength, List<ParseStart>  startOffsets) throws IOException {
        super(fileName, fileSize, inputStreamSupplier, maxTokenLength, Integer.MAX_VALUE);
        this.startOffsets = startOffsets;
    }

    ParallelEDIFParserTestSpecificOffsets(Path fileName, int maxTokenLength, List<ParseStart> startOffsets) throws IOException {
        this(fileName, Files.size(fileName),
                InputStreamSupplier.fromPath(fileName,
                        fileName.toString().endsWith(".gz")
                                && Params.RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK),
                maxTokenLength, startOffsets);
    }

    @Override
    protected void initializeWorkers() throws IOException {
        workers.clear();
        for (ParseStart  startOffset : startOffsets) {
            workers.add(new TestingParallelEDIFParserWorker(fileName, inputStreamSupplier.get(), startOffset.offset, uniquifier, maxTokenLength, startOffset.name, cache));
        }
    }

    public int getSuccessfulThreads() {
        return workers.size();
    }

    private static class TestingParallelEDIFParserWorker extends ParallelEDIFParserWorker {

        private final String name;

        public TestingParallelEDIFParserWorker(Path fileName, InputStream in, long offset, StringPool uniquifier, int maxTokenLength, String name, EDIFReadLegalNameCache cache) {
            super(fileName, in, offset, uniquifier, maxTokenLength, cache);
            this.name = name;
        }

        @Override
        public String toString() {
            return '"'+name+"\"@"+offset;
        }
    }
}
