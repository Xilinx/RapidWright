/*
 * Copyright (c) 2022, Antmicro
 * All rights reserved.
 *
 * Author: Antmicro Team, Antmicro
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

package com.xilinx.rapidwright.interchange;

import java.io.IOException;

import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFParser;
import com.xilinx.rapidwright.tests.CodePerfTracker;


/**
 * Example code that performs conversion from a EDIF to LogicalNetlist.
 *
 */
public class EdifToLogicalNetlist {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("USAGE: <input>.edif [<output.netlist>]");
            System.out.println("   Converts EDIF to FPGA interchange logical netlist");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("EDIF->LogicalNetlist");

        // Read EDIF
        t.start("Read EDIF");
        try (EDIFParser parser = new EDIFParser(args[0])) {
            EDIFNetlist netlist = parser.parseEDIFNetlist();

            // Write LogicalNetlist
            t.stop().start("Write LogicalNetlist");
            String fname;
            if (args.length < 2) {
                fname = args[0] + ".netlist";
            }
            else {
                fname = args[1];
            }
            LogNetlistWriter.writeLogNetlist(netlist, fname);
        }
        t.stop().printSummary();
    }
}
