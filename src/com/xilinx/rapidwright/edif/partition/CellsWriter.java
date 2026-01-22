/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Writes cells.txt (one partition name per line) into the output directory.
 *
 * Simply displays the name of the partitions ex:
 *   Line 1: FPGA_A
 *   Line 2: FPGA_B
 *   Line 3: FPGA_C
 *   Line 4: FPGA_D
 */
public final class CellsWriter {

    private CellsWriter() {
        // no instances.
    }

    /**
     * Writes cells.txt using the provided list of names (one per line, in order).
     *
     * @param outputDirectory The output directory.
     * @param partitionNames The list of partition names.
     */
    public static void write(Path outputDirectory, List<String> partitionNames) {
        if (partitionNames == null) {
            throw new IllegalArgumentException("partitionNames is null");
        }
        try {
            if (outputDirectory != null) {
                Files.createDirectories(outputDirectory);
            }
            Path cellsFilePath = outputDirectory.resolve("cells.txt");
            Files.write(cellsFilePath, partitionNames, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes cells.txt for a given number of partitions, generating names using partitionlabel.
     *
     * @param outputDirectory The output directory.
     * @param numPartitions The number of partitions.
     */
    public static void write(Path outputDirectory, int numPartitions) {
        write(outputDirectory, generateNames(numPartitions));
    }

    /**
     * Writes cells.txt from an index->name map and fills any missing indices with generated labels.
     *
     * @param outputDirectory The output directory.
     * @param indexToName A map of partition indices to names.
     */
    public static void write(Path outputDirectory, Map<Integer, String> indexToName) {
        if (indexToName == null || indexToName.isEmpty()) {
            write(outputDirectory, Collections.emptyList());
            return;
        }
        int maxIdx = -1;
        for (Integer idx : indexToName.keySet()) {
            if (idx != null && idx > maxIdx) {
                maxIdx = idx;
            }
        }
        if (maxIdx < 0) {
            write(outputDirectory, Collections.emptyList());
            return;
        }
        List<String> partitionNames = new ArrayList<>(maxIdx + 1);
        for (int i = 0; i <= maxIdx; i++) {
            String name = indexToName.get(i);
            if (name == null || name.trim().isEmpty()) {
                name = generateName(i);
            }
            partitionNames.add(name);
        }
        write(outputDirectory, partitionNames);
    }

    /**
     * Generates a list of canonical partition names ["fpga_a","fpga_b",...] using partitionlabel.
     *
     * @param partitionCount The number of partitions.
     * @return A list of generated partition names.
     */
    public static List<String> generateNames(int partitionCount) {
        if (partitionCount <= 0) {
            return Collections.emptyList();
        }
        List<String> partitionNames = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            partitionNames.add(PartitionLabel.indexToFpgaLabel(i));
        }
        return partitionNames;
    }

    /**
     * Generates a single canonical partition name "fpga_*" via partitionlabel.
     *
     * @param partitionIndex The partition index.
     * @return The generated partition name.
     */
    public static String generateName(int partitionIndex) {
        return PartitionLabel.indexToFpgaLabel(partitionIndex);
    }

}
