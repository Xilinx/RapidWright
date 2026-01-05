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

/**
 * Logic to convert from partition index 
 * to FPGA partition label using the following
 * naming scheme:
 *   0..25  -> A..Z
 *   26..51 -> AZ..ZZ
 *   52..77 -> AZZ..ZZZ
 *
 * similar to "excel-style (bijective) base-26"
 */
final class PartitionLabel {

    private PartitionLabel() {}

    private static final char[] ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /**
     * Converts partition index to FPGA label string.
     * @param idx The partition index to convert
     * @return FPGA label string like "FPGA_A" or "FPGA_BZ"
     */
    static String indexToFpgaLabel(int idx) {
        if (idx < 0) throw new IllegalArgumentException("partition index must be non-negative");
        String suffix;
        if (idx < 26) {
            suffix = String.valueOf(ALPHA[idx]);
        } else {
            int t = idx - 26;
            int q = t / 26;   //how many trailing 'Z' to add
            int r = t % 26;   //leading letter
            StringBuilder sb = new StringBuilder();
            sb.append(ALPHA[r]);
            for (int i = 0; i < q + 1; i++) sb.append('Z');
            suffix = sb.toString();
        }
        return "FPGA_" + suffix;
    }
}
