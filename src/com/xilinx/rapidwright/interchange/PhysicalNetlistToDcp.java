/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Keith Rothman, Google, Inc.
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.design.ConstraintGroup;

public class PhysicalNetlistToDcp {

    public static void main(String[] args) throws IOException {
        if(args.length != 4) {
            System.out.println("USAGE: <input>.netlist <input>.phys <input>.xdc <output>.dcp");
            System.exit(1);
            return;
        }

        String logNetlistFileName = args[0];
        String physNetlistFileName = args[1];
        String xdcFileName = args[2];
        String outputDCPFileName = args[3];

        CodePerfTracker t = new CodePerfTracker("Interchange Format->DCP",false);

        t.start("Read Logical Netlist");

        // Read Netlist into RapidWright netlist
        EDIFNetlist n2 = LogNetlistReader.readLogNetlist(logNetlistFileName);

        t.stop().start("Read Physical Netlist");

        // Read Physical Netlist into RapidWright netlist
        Design roundtrip = PhysNetlistReader.readPhysNetlist(physNetlistFileName, n2);

        // Add XDC constraints
        List<String> lines = Files.readAllLines(new File(xdcFileName).toPath(), Charset.defaultCharset());
        roundtrip.setXDCConstraints(lines, ConstraintGroup.NORMAL);

        t.stop().start("Write DCP");

        // Write RapidWright netlist back to edif
        roundtrip.writeCheckpoint(outputDCPFileName, CodePerfTracker.SILENT);

        t.stop().printSummary();
    }
}

