/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;

public class TutorialSupport {

    public static final String TUTORIAL_FOLDER = "http://www.rapidwright.io/docs/_sources/";

    public static final String RST_SRC_SUFFIX = ".rst.txt";

    public static List<String> runTutorialVivadoCommands(Path tmpDir, Tutorial name, int... lineNumbers) {
        List<String> commands = getTutorialCommands(tmpDir, name, lineNumbers);

        // Run 'rapidwright' commands in vivado to avoid breaking the context flow
        // and join initial 'vivado -source' commands so they are contiguous
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            if (command.startsWith("rapidwright ")) {
                commands.set(i, "exec " + command);
            } else if (command.startsWith("vivado -source ")) {
                commands.set(i, command.replace("vivado -", ""));
            } else if (command.equals("report_route_status")) {
                commands.set(i, "if {![report_route_status -boolean_check ROUTED_FULLY]} { error {} }");
            }
        }

        Path tmpTcl = tmpDir.resolve("tmp.tcl");
        FileTools.writeLinesToTextFile(commands, tmpTcl.toString());

        VivadoTools.runTcl(tmpDir.resolve("output.log"), tmpTcl, true, null, tmpDir.toFile());
        return commands;
    }

    public static List<String> runTutorialCommands(Path tmpDir, Tutorial name, int... lineNumbers) {
        List<String> commands = getTutorialCommands(tmpDir, name, lineNumbers);
        for (String command : commands) {
            FileTools.runCommand(command, true, null, tmpDir.toFile());
        }
        return commands;
    }

    public static List<String> getTutorialCommands(Path tmpDir, Tutorial name, int[] lineNumbers) {
        Path tmpFile = tmpDir.resolve(name + RST_SRC_SUFFIX);
        FileTools.runCommand("wget " + getTutorialSourceURL(name) + " -O " + tmpFile.toString(), true);
        List<String> tmpFileLines = FileTools.getLinesFromTextFile(tmpFile.toString());
        List<String> lines = new ArrayList<>();
        for (int lineNumber : lineNumbers) {
            lines.add(tmpFileLines.get(lineNumber - 1).trim());
        }
        return lines;
    }

    public static String getTutorialSourceURL(Tutorial name) {
        return TUTORIAL_FOLDER + name.getFileName() + RST_SRC_SUFFIX;
    }
}
