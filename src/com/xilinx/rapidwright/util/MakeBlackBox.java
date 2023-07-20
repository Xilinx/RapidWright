/*
 *
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

package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Command line wrapper to black box one or more cell instances in a design.
 * 
 */
public class MakeBlackBox {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("USAGE: <input.dcp> <output.dcp> <cellinst-to-be-blackboxed> [another-cellinst-to-be-blackboxed] [...]");
            System.exit(1);
        }
        CodePerfTracker t = new CodePerfTracker("MakeBlackbox");

        t.start("Read DCP");
        Design input = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);
        t.stop().start("Blackbox cell(s)");

        for (int i = 2; i < args.length; i++) {
            DesignTools.makeBlackBox(input, args[i]);
        }

        t.stop().start("Write DCP");
        input.writeCheckpoint(args[1], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
