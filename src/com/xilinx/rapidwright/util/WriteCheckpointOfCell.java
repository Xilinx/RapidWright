/*
 *
 * Copyright (c) 2022 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

/**
 *  Write checkpoint of a cell, ie., similar to Vivado's TCL command write_checkpoint -cell.
 */
public class WriteCheckpointOfCell {
    public static void main(String[] args) {
        String usage = String.join(System.getProperty("line.separator"),
                "Write checkpoint of a cell from a given DCP, ie., similar to Vivado's TCL command write_checkpoint -cell",
                "  Syntax:",
                "  WriteCheckpointOfCell -cell <full hierarchy cell name> -in <src DCP> -out <output DCP>");

        if (args.length < 6) {
            System.out.println(usage);
            System.exit(1);
        }

        String srcDCPName  = "";
        String srcCellName = "";
        String outDCPName  = "";

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "-help":
                    System.out.println(usage);
                    System.exit(0);
                    break;
                case "-cell":
                    srcCellName = args[++i];
                    break;
                case "-in":
                    srcDCPName = args[++i];
                    break;
                case "-out":
                    outDCPName = args[++i];
                    break;
                default:
                    System.out.println("Invalid option " + args[i] + " found.");
                    System.out.println(usage);
                    System.exit(1);
                    break;
            }
            i++;
        }

        Design srcDesign = Design.readCheckpoint(srcDCPName);
        Design d = DesignTools.createDesignFromCellWithStatic(srcDesign, srcCellName);
        d.writeCheckpoint(outDCPName);
    }
}
