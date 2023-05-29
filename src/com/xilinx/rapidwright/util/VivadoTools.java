/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Zak Nafziger, Advanced Micro Devices, Inc.
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods to run a tcl script through vivado and parse the stdout
 * @author zakn
 *
 */
public class VivadoTools {
    public static List<String> searchVivadoLog(List<String> log, String key) {
        List<String> results = new ArrayList<>();
        for (String l : log) {
            if (l.contains(key)) {
                results.add(l);
            }
        }
        return results;
    }

    public static List<String> runTcl(Path outputLog, String tclScript, boolean verbose) {
        final String vivadoCmd = "vivado -log " + outputLog.toString() + " -mode batch -source "
                + tclScript;
        FileTools.runCommand(vivadoCmd, verbose);
        List<String> log = new ArrayList<>();
        log = FileTools.getLinesFromTextFile(outputLog.toString());
        return log;
    }
}