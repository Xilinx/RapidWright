/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Misha Matlin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design.tools;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestNetlistClockDetection {

    @Test
    public void testNetlistClockDetection(@TempDir Path dir) {
        Path path = RapidWrightDCP.getPath("test_clock_detection.edif");

        Map<String, Set<String>> testMap = new HashMap<>();
        testMap.put("qb", new HashSet<>(Arrays.asList("B")));
        testMap.put("VCC", new HashSet<>(Arrays.asList("VCC/P")));
        testMap.put("u_x/I2", new HashSet<>(Arrays.asList("C")));
        testMap.put("u_x/O5", new HashSet<>(Arrays.asList("A", "B", "C", "D", "VCC/P")));
        testMap.put("u_x/I0", new HashSet<>(Arrays.asList("A")));
        testMap.put("out_q", new HashSet<>(Arrays.asList("A")));
        testMap.put("y_o7", new HashSet<>(Arrays.asList("A", "B", "C", "D", "GND/G", "VCC/P", "VCC_1/P", "VCC_2/P", "VCC_3/P", "VCC_4/P", "VCC_5/P", "VCC_6/P", "VCC_7/P", "VCC_8/P", "VCC_9/P", "VCC_10/P", "VCC_11/P", "VCC_12/P", "VCC_13/P", "VCC_14/P", "VCC_15/P", "VCC_16/P")));
        testMap.put("u_y_LUT6CY_1/I4", new HashSet<>(Arrays.asList("A", "B", "C", "D", "VCC/P", "VCC_1/P", "VCC_2/P")));
        testMap.put("A", new HashSet<>(Arrays.asList("A")));
        testMap.put("u_x/I5", new HashSet<>(Arrays.asList("GND/G")));

        String[] args = Stream.concat(Stream.of(path.toString()), testMap.keySet().stream()).toArray(String[]::new);

        ByteArrayOutputStream capturedStdoutStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedStdoutStream));

        NetlistClockDetection.main(args);

        String capturedStdout = capturedStdoutStream.toString();

        System.setOut(System.out);

        JSONObject retJson = new JSONObject(capturedStdout);

        Map<String, Set<String>> retMap = new HashMap<>();
        for (Entry<String, Object> e : retJson.toMap().entrySet()) {
            @SuppressWarnings("unchecked")
            List<String> pinsArr = (ArrayList<String>) e.getValue();
            retMap.put(e.getKey(), new HashSet<>(pinsArr));
        }

        Assertions.assertEquals(testMap, retMap);
    }
}
