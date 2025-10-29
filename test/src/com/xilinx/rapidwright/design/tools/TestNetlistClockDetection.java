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

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestNetlistClockDetection {

    @Test
    public void testNetlistClockDetection(@TempDir Path dir) {
        Path path = RapidWrightDCP.getPath("test_clock_detection.edif");

        String[] args = new String[] {
            path.toString(),
            "A",
            "B",
            "GE_net",
            "GE_net_1",
            "GE_net_2",
            "GE_net_3",
            "GE_net_4",
            "GE_net_5",
            "GE_net_6",
            "GE_net_7",
            "GND",
            "O52_net",
            "O52_net_1",
            "O52_net_2",
            "O52_net_3",
            "PROP_net",
            "PROP_net_1",
            "PROP_net_2",
            "PROP_net_3",
            "PROP_net_4",
            "PROP_net_5",
            "PROP_net_6",
            "PROP_net_7",
            "VCC",
            "out_q",
            "qa",
            "qb",
            "qb2",
            "x_o5",
            "x_o6",
            "xlnx_opt_",
            "xlnx_opt__1",
            "xlnx_opt__10",
            "xlnx_opt__11",
            "xlnx_opt__12",
            "xlnx_opt__13",
            "xlnx_opt__14",
            "xlnx_opt__15",
            "xlnx_opt__2",
            "xlnx_opt__3",
            "xlnx_opt__4",
            "xlnx_opt__5",
            "xlnx_opt__6",
            "xlnx_opt__7",
            "xlnx_opt__8",
            "xlnx_opt__9",
            "y_DI[0]",
            "y_DI[1]",
            "y_DI[2]",
            "y_DI[3]",
            "y_DI[4]",
            "y_DI[5]",
            "y_DI[6]",
            "y_DI[7]",
            "y_S[0]",
            "y_S[1]",
            "y_S[2]",
            "y_S[3]",
            "y_S[4]",
            "y_S[5]",
            "y_S[6]",
            "y_S[7]",
            "y_co[0]",
            "y_co[1]",
            "y_co[2]",
            "y_co[3]",
            "y_co[4]",
            "y_co[5]",
            "y_co[6]",
            "y_co[7]",
            "y_o7",
            "y_o[0]",
            "y_o[1]",
            "y_o[2]",
            "y_o[3]",
            "y_o[4]",
            "y_o[5]",
            "y_o[6]",
            "y_o[7]",
            "u_x/I0",
            "u_x/I1",
            "u_x/I2",
            "u_x/I3",
            "u_x/I4",
            "u_x/I5",
            "u_x/O5",
            "u_x/O6",
            "u_y_LUT6CY_0/GE",
            "u_y_LUT6CY_0/I0",
            "u_y_LUT6CY_0/I1",
            "u_y_LUT6CY_0/I2",
            "u_y_LUT6CY_0/I3",
            "u_y_LUT6CY_0/I4",
            "u_y_LUT6CY_0/O51",
            "u_y_LUT6CY_0/O52",
            "u_y_LUT6CY_0/PROP",
            "u_y_LUT6CY_1/GE",
            "u_y_LUT6CY_1/I0",
            "u_y_LUT6CY_1/I1",
            "u_y_LUT6CY_1/I2",
            "u_y_LUT6CY_1/I3",
            "u_y_LUT6CY_1/I4",
            "u_y_LUT6CY_1/O51",
            "u_y_LUT6CY_1/O52",
            "u_y_LUT6CY_1/PROP",
            "u_y_LUT6CY_2/GE",
            "u_y_LUT6CY_2/I0",
            "u_y_LUT6CY_2/I1",
            "u_y_LUT6CY_2/I2",
            "u_y_LUT6CY_2/I3",
            "u_y_LUT6CY_2/I4",
            "u_y_LUT6CY_2/O51",
            "u_y_LUT6CY_2/O52",
            "u_y_LUT6CY_2/PROP",
            "u_y_LUT6CY_3/GE",
            "u_y_LUT6CY_3/I0",
            "u_y_LUT6CY_3/I1",
            "u_y_LUT6CY_3/I2",
            "u_y_LUT6CY_3/I3",
            "u_y_LUT6CY_3/I4",
            "u_y_LUT6CY_3/O51",
            "u_y_LUT6CY_3/O52",
            "u_y_LUT6CY_3/PROP",
            "u_y_LUT6CY_4/GE",
            "u_y_LUT6CY_4/I0",
            "u_y_LUT6CY_4/I1",
            "u_y_LUT6CY_4/I2",
            "u_y_LUT6CY_4/I3",
            "u_y_LUT6CY_4/I4",
            "u_y_LUT6CY_4/O51",
            "u_y_LUT6CY_4/O52",
            "u_y_LUT6CY_4/PROP",
            "u_y_LUT6CY_5/GE",
            "u_y_LUT6CY_5/I0",
            "u_y_LUT6CY_5/I1",
            "u_y_LUT6CY_5/I2",
            "u_y_LUT6CY_5/I3",
            "u_y_LUT6CY_5/I4",
            "u_y_LUT6CY_5/O51",
            "u_y_LUT6CY_5/O52",
            "u_y_LUT6CY_5/PROP",
            "u_y_LUT6CY_6/GE",
            "u_y_LUT6CY_6/I0",
            "u_y_LUT6CY_6/I1",
            "u_y_LUT6CY_6/I2",
            "u_y_LUT6CY_6/I3",
            "u_y_LUT6CY_6/I4",
            "u_y_LUT6CY_6/O51",
            "u_y_LUT6CY_6/O52",
            "u_y_LUT6CY_6/PROP",
            "u_y_LUT6CY_7/GE",
            "u_y_LUT6CY_7/I0",
            "u_y_LUT6CY_7/I1",
            "u_y_LUT6CY_7/I2",
            "u_y_LUT6CY_7/I3",
            "u_y_LUT6CY_7/I4",
            "u_y_LUT6CY_7/O51",
            "u_y_LUT6CY_7/O52",
            "u_y_LUT6CY_7/PROP",
        };

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

        Map<String, Set<String>> expectedRetMap = new HashMap<>();
        expectedRetMap.put("xlnx_opt__1", new HashSet<>(Arrays.asList("VCC_2/P")));
        expectedRetMap.put("y_co[0]", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("y_S[2]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_0/PROP", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_2/I4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_2/I2", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_2/I3", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_y_LUT6CY_2/I0", new HashSet<>(Arrays.asList("VCC_5/P")));
        expectedRetMap.put("u_y_LUT6CY_2/I1", new HashSet<>(Arrays.asList("VCC_6/P")));
        expectedRetMap.put("qa", new HashSet<>(Arrays.asList("A")));
        expectedRetMap.put("qb", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("y_DI[0]", new HashSet<>(Arrays.asList("D")));
        expectedRetMap.put("y_o7", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_o[0]", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_5/GE", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_co[1]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_1/PROP", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_S[3]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_2/O51", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_5/I4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_2/O52", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_5/I3", new HashSet<>(Arrays.asList("D")));
        expectedRetMap.put("u_y_LUT6CY_5/I2", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_5/I1", new HashSet<>(Arrays.asList("VCC_12/P")));
        expectedRetMap.put("O52_net_3", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_o[7]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_5/I0", new HashSet<>(Arrays.asList("VCC_11/P")));
        expectedRetMap.put("O52_net_1", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("O52_net_2", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_DI[1]", new HashSet<>(Arrays.asList("C")));
        expectedRetMap.put("u_y_LUT6CY_0/GE", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("VCC", new HashSet<>(Arrays.asList("VCC/P")));
        expectedRetMap.put("xlnx_opt__8", new HashSet<>(Arrays.asList("VCC_9/P")));
        expectedRetMap.put("xlnx_opt__9", new HashSet<>(Arrays.asList("VCC_10/P")));
        expectedRetMap.put("xlnx_opt__6", new HashSet<>(Arrays.asList("VCC_7/P")));
        expectedRetMap.put("xlnx_opt__7", new HashSet<>(Arrays.asList("VCC_8/P")));
        expectedRetMap.put("GND", new HashSet<>(Arrays.asList("GND/G")));
        expectedRetMap.put("xlnx_opt__4", new HashSet<>(Arrays.asList("VCC_5/P")));
        expectedRetMap.put("xlnx_opt__5", new HashSet<>(Arrays.asList("VCC_6/P")));
        expectedRetMap.put("xlnx_opt__2", new HashSet<>(Arrays.asList("VCC_3/P")));
        expectedRetMap.put("xlnx_opt__3", new HashSet<>(Arrays.asList("VCC_4/P")));
        expectedRetMap.put("qb2", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("A", new HashSet<>(Arrays.asList("A")));
        expectedRetMap.put("PROP_net", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("y_co[2]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("B", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_y_LUT6CY_0/I4", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_y_LUT6CY_6/GE", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_S[0]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_0/I1", new HashSet<>(Arrays.asList("VCC_2/P")));
        expectedRetMap.put("u_y_LUT6CY_0/I0", new HashSet<>(Arrays.asList("VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_0/I3", new HashSet<>(Arrays.asList("D")));
        expectedRetMap.put("u_y_LUT6CY_4/O51", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_0/I2", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_4/O52", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_2/PROP", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_o[6]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_x/O6", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_3/GE", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_DI[6]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_x/O5", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "B")));
        expectedRetMap.put("O52_net", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_6/O51", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_1/O51", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_6/O52", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_S[1]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("y_co[3]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_3/I3", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_y_LUT6CY_3/I4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_3/I1", new HashSet<>(Arrays.asList("VCC_8/P")));
        expectedRetMap.put("u_y_LUT6CY_3/I2", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_3/I0", new HashSet<>(Arrays.asList("VCC_7/P")));
        expectedRetMap.put("y_o[5]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_5/PROP", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_DI[7]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("y_co[4]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_S[6]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("PROP_net_6", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_x/I0", new HashSet<>(Arrays.asList("A")));
        expectedRetMap.put("PROP_net_7", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_x/I1", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_x/I2", new HashSet<>(Arrays.asList("C")));
        expectedRetMap.put("out_q", new HashSet<>(Arrays.asList("A")));
        expectedRetMap.put("y_o[4]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_x/I3", new HashSet<>(Arrays.asList("D")));
        expectedRetMap.put("u_x/I4", new HashSet<>(Arrays.asList("VCC/P")));
        expectedRetMap.put("u_x/I5", new HashSet<>(Arrays.asList("GND/G")));
        expectedRetMap.put("y_DI[4]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_1/GE", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("PROP_net_1", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("PROP_net_2", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("GE_net", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("PROP_net_3", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("PROP_net_4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_1/O52", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_6/PROP", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("PROP_net_5", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_7/PROP", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_co[5]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_4/PROP", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("GE_net_6", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_S[7]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_1/I2", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "D", "A", "B")));
        expectedRetMap.put("GE_net_5", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_1/I1", new HashSet<>(Arrays.asList("VCC_4/P")));
        expectedRetMap.put("GE_net_4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_1/I4", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("GE_net_3", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_1/I3", new HashSet<>(Arrays.asList("C")));
        expectedRetMap.put("u_y_LUT6CY_1/I0", new HashSet<>(Arrays.asList("VCC_3/P")));
        expectedRetMap.put("u_y_LUT6CY_6/I0", new HashSet<>(Arrays.asList("VCC_13/P")));
        expectedRetMap.put("GE_net_7", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_6/I1", new HashSet<>(Arrays.asList("VCC_14/P")));
        expectedRetMap.put("u_y_LUT6CY_6/I2", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_6/I3", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_y_LUT6CY_6/I4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("GE_net_2", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_o[3]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("GE_net_1", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_DI[5]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_3/O52", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_4/GE", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "VCC_10/P", "C", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "VCC_9/P", "GND/G", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_3/O51", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("xlnx_opt__13", new HashSet<>(Arrays.asList("VCC_14/P")));
        expectedRetMap.put("xlnx_opt__14", new HashSet<>(Arrays.asList("VCC_15/P")));
        expectedRetMap.put("xlnx_opt__15", new HashSet<>(Arrays.asList("VCC_16/P")));
        expectedRetMap.put("xlnx_opt__10", new HashSet<>(Arrays.asList("VCC_11/P")));
        expectedRetMap.put("y_co[6]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("xlnx_opt__11", new HashSet<>(Arrays.asList("VCC_12/P")));
        expectedRetMap.put("xlnx_opt__12", new HashSet<>(Arrays.asList("VCC_13/P")));
        expectedRetMap.put("y_S[4]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_4/I4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_4/I3", new HashSet<>(Arrays.asList("C")));
        expectedRetMap.put("u_y_LUT6CY_4/I2", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_7/I0", new HashSet<>(Arrays.asList("VCC_15/P")));
        expectedRetMap.put("u_y_LUT6CY_4/I1", new HashSet<>(Arrays.asList("VCC_10/P")));
        expectedRetMap.put("u_y_LUT6CY_7/I1", new HashSet<>(Arrays.asList("VCC_16/P")));
        expectedRetMap.put("u_y_LUT6CY_4/I0", new HashSet<>(Arrays.asList("VCC_9/P")));
        expectedRetMap.put("u_y_LUT6CY_7/I2", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "D", "A", "B")));
        expectedRetMap.put("u_y_LUT6CY_7/I3", new HashSet<>(Arrays.asList("B")));
        expectedRetMap.put("u_y_LUT6CY_7/I4", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_5/O52", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_DI[2]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("y_o[2]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_5/O51", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_3/PROP", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("xlnx_opt_", new HashSet<>(Arrays.asList("VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_7/GE", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_S[5]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("u_y_LUT6CY_0/O51", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_0/O52", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "VCC_2/P", "B", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_7/O52", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("y_DI[3]", new HashSet<>(Arrays.asList()));
        expectedRetMap.put("y_o[1]", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "VCC_4/P", "D", "VCC_3/P", "A", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_2/GE", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("u_y_LUT6CY_7/O51", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));
        expectedRetMap.put("x_o6", new HashSet<>(Arrays.asList("VCC/P", "GND/G", "C", "D", "A", "B")));
        expectedRetMap.put("x_o5", new HashSet<>(Arrays.asList("VCC/P", "C", "D", "A", "B")));
        expectedRetMap.put("y_co[7]", new HashSet<>(Arrays.asList("VCC/P", "VCC_12/P", "VCC_11/P", "C", "VCC_10/P", "D", "VCC_16/P", "VCC_15/P", "VCC_14/P", "VCC_13/P", "GND/G", "VCC_9/P", "VCC_8/P", "VCC_7/P", "VCC_6/P", "VCC_5/P", "VCC_4/P", "A", "VCC_3/P", "B", "VCC_2/P", "VCC_1/P")));

        Assertions.assertEquals(expectedRetMap, retMap);
    }
}
