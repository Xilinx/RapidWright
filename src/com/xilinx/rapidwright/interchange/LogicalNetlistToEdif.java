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
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;


/**
 * Example code that performs conversion from a LogicalNetlist to EDIF.
 *
 */
public class LogicalNetlistToEdif {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("USAGE: <input>.netlist [<output.edif>]");
            System.out.println("   Converts FPGA interchange logical netlist to EDIF");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("LogicalNetlist->EDIF");

        // Read LogicalNetlist
        t.start("Read LogicalNetlist");
        EDIFNetlist netlist = LogNetlistReader.readLogNetlist(args[0]);

        // Write EDIF
        t.stop().start("Write EDIF");
        String fname;
        if (args.length < 2) {
            fname = args[0] + ".edif";
        }
        else {
            fname = args[1];
        }
        netlist.exportEDIF(fname);

        t.stop().printSummary();
    }
}
