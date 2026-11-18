/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.util.FileTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;

public class TestYosysTools {
    final String verilogFd = "" +
            "module top(input clk, input d, output q);\n" +
            "always @(posedge clk)\n" +
            "    q <= d;\n" +
            "endmodule\n" +
            "";

    @Test
    void testSynthXilinx(@TempDir Path workDir) {
        // Skip test if yosys is not on PATH
        Assumptions.assumeTrue(YosysTools.isYosysOnPath());

        Path input = workDir.resolve("input.v");
        FileTools.writeStringToTextFile(verilogFd, input.toString());
        EDIFNetlist netlist = YosysTools.synthXilinxWithWorkDir(workDir, input);
        EDIFCell top = netlist.getTopCell();
        Collection<EDIFCellInst> cellInsts = top.getCellInsts();
        Assertions.assertEquals(7, cellInsts.size());
        for (EDIFCellInst eci : cellInsts) {
            String cellTypeName = eci.getCellType().getName();
            // 1xGND, 1xVCC
            if (cellTypeName.equals("GND") || cellTypeName.equals("VCC")) {
                continue;
            }
            // 2xIBUF, 1xOBUF
            if (cellTypeName.equals("IBUF") || cellTypeName.equals("OBUF")) {
                continue;
            }
            // 1xBUFG
            if (cellTypeName.equals("BUFG")) {
                continue;
            }
            // 1xFDRE
            Assertions.assertEquals("FDRE", cellTypeName);
            Assertions.assertEquals("$iopadmap$clk", eci.getPortInst("C").getNet().getName());
            Assertions.assertEquals("$iopadmap$d", eci.getPortInst("D").getNet().getName());
            Assertions.assertEquals("$iopadmap$q", eci.getPortInst("Q").getNet().getName());
            Assertions.assertEquals("GND_NET", eci.getPortInst("R").getNet().getName());
            Assertions.assertEquals("VCC_NET", eci.getPortInst("CE").getNet().getName());
        }
    }

    final String verilogHier1 = "" +
            "module top(input [5:0] i, output o);\n" +
            "wire a;\n" +
            "foo f(i, a);\n" +
            "assign o = ~a;\n" +
            "endmodule\n" +
            "";

    final String verilogHier2 = "" +
            "module foo(input [5:0] i, output o);\n" +
            "assign o = &i;\n" +
            "endmodule\n" +
            "";

    @Test
    void testSynthXilinxMultiFile(@TempDir Path workDir) {
        // Skip test if yosys is not on PATH
        Assumptions.assumeTrue(YosysTools.isYosysOnPath());

        Path input1 = workDir.resolve("input1.v");
        FileTools.writeStringToTextFile(verilogHier1, input1.toString());

        Path input2 = workDir.resolve("input2.v");
        FileTools.writeStringToTextFile(verilogHier2, input2.toString());

        EDIFNetlist netlist = YosysTools.synthXilinxWithWorkDir(YosysTools.SYNTH_XILINX_FLAG_FAMILY_XCUP +
                YosysTools.SYNTH_XILINX_FLAG_FLATTEN +
                YosysTools.SYNTH_XILINX_FLAG_OUT_OF_CONTEXT,
                workDir, input2, input1);
        EDIFCell top = netlist.getTopCell();
        Assertions.assertEquals("top", top.getName());
        Collection<EDIFCellInst> cellInsts = top.getCellInsts();
        Assertions.assertEquals(3, cellInsts.size());
        for (EDIFCellInst eci : cellInsts) {
            String cellTypeName = eci.getCellType().getName();
            if (cellTypeName.equals("GND") || cellTypeName.equals("VCC")) {
                continue;
            }
            Assertions.assertEquals("LUT6", cellTypeName);
            Assertions.assertEquals("f.i[0]", eci.getPortInst("I0").getNet().getName());
            Assertions.assertEquals("f.i[1]", eci.getPortInst("I1").getNet().getName());
            Assertions.assertEquals("f.i[2]", eci.getPortInst("I2").getNet().getName());
            Assertions.assertEquals("f.i[3]", eci.getPortInst("I3").getNet().getName());
            Assertions.assertEquals("f.i[4]", eci.getPortInst("I4").getNet().getName());
            Assertions.assertEquals("f.i[5]", eci.getPortInst("I5").getNet().getName());
            Assertions.assertEquals("o", eci.getPortInst("O").getNet().getName());
            // 6-input NAND
            Assertions.assertEquals("string(64'h7fffffffffffffff)", eci.getProperty("INIT").toString());
        }
    }
}
