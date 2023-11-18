/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Hayden Cook, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestCodeGenerator {

    @Test
    public void testTestNetGenerator() {
        String dcpName = RapidWrightDCP.getString("routethru_luts.dcp");
        System.out.println(dcpName);

        List<String>  nets = new ArrayList<>();
        nets.add("i_IBUF[1]");

        List<String> siteInsts = new ArrayList<>();
        siteInsts.add("SLICE_X0Y0");

        String actualString = CodeGenerator.testNetGenerator(dcpName, nets, siteInsts);
        System.out.println(actualString);

        String expectedString =
                "Design design = new Design(\"top\", \"xc7a35tcpg236-1\");\n" +
                "Device device = design.getDevice();\n" +
                "\n" +
                "Net net = com.xilinx.rapidwright.util.CodeGenerator.createTestNet(design, \"net\", new String[]{\n" +
                "        \"LIOI3_X0Y1/LIOI3.LIOI_IBUF1->LIOI_I1\",\n" +
                "        \"LIOI3_X0Y1/LIOI3.LIOI_I1->LIOI_ILOGIC1_D\",\n" +
                "        \"LIOI3_X0Y1/LIOI3.LIOI_ILOGIC1_D->>IOI_ILOGIC1_O\",\n" +
                "        \"LIOI3_X0Y1/LIOI3.IOI_ILOGIC1_O->>IOI_LOGIC_OUTS18_0\",\n" +
                "        \"IO_INT_INTERFACE_L_X0Y1/IO_INT_INTERFACE_L.INT_INTERFACE_LOGIC_OUTS_L_B18->>INT_INTERFACE_LOGIC_OUTS_L18\",\n" +
                "        \"INT_L_X0Y1/INT_L.LOGIC_OUTS_L18->>EE2BEG0\",\n" +
                "        \"INT_L_X2Y1/INT_L.EE2END0->>SL1BEG0\",\n" +
                "        \"INT_L_X2Y0/INT_L.SL1END0->>BYP_ALT1\",\n" +
                "        \"INT_L_X2Y0/INT_L.BYP_ALT1->>BYP_L1\",\n" +
                "        \"CLBLL_L_X2Y0/CLBLL_L.CLBLL_BYP1->CLBLL_LL_AX\"\n" +
                "});\n" +
                "SiteInst si1 = design.createSiteInst(device.getSite(\"IOB_X0Y1\"));\n" +
                "net.createPin(\"I\", si1);\n" +
                "SiteInst si2 = design.createSiteInst(device.getSite(\"SLICE_X0Y0\"));\n" +
                "net.createPin(\"AX\", si2);\n" +
                "\n";

        Assertions.assertEquals(expectedString, actualString);

        nets.add("i_IBUF[0]");
        siteInsts.add("IOB_X0Y1");

        actualString = CodeGenerator.testNetGenerator(dcpName, nets, siteInsts);


        expectedString =
                "Design design = new Design(\"top\", \"xc7a35tcpg236-1\");\n" +
                "Device device = design.getDevice();\n" +
                "\n" +
                "Net net0 = com.xilinx.rapidwright.util.CodeGenerator.createTestNet(design, \"net0\", new String[]{\n" +
                "        \"LIOI3_X0Y1/LIOI3.LIOI_IBUF1->LIOI_I1\",\n" +
                "        \"LIOI3_X0Y1/LIOI3.LIOI_I1->LIOI_ILOGIC1_D\",\n" +
                "        \"LIOI3_X0Y1/LIOI3.LIOI_ILOGIC1_D->>IOI_ILOGIC1_O\",\n" +
                "        \"LIOI3_X0Y1/LIOI3.IOI_ILOGIC1_O->>IOI_LOGIC_OUTS18_0\",\n" +
                "        \"IO_INT_INTERFACE_L_X0Y1/IO_INT_INTERFACE_L.INT_INTERFACE_LOGIC_OUTS_L_B18->>INT_INTERFACE_LOGIC_OUTS_L18\",\n" +
                "        \"INT_L_X0Y1/INT_L.LOGIC_OUTS_L18->>EE2BEG0\",\n" +
                "        \"INT_L_X2Y1/INT_L.EE2END0->>SL1BEG0\",\n" +
                "        \"INT_L_X2Y0/INT_L.SL1END0->>BYP_ALT1\",\n" +
                "        \"INT_L_X2Y0/INT_L.BYP_ALT1->>BYP_L1\",\n" +
                "        \"CLBLL_L_X2Y0/CLBLL_L.CLBLL_BYP1->CLBLL_LL_AX\"\n" +
                "});\n" +
                "SiteInst si1 = design.createSiteInst(device.getSite(\"IOB_X0Y1\"));\n" +
                "net0.createPin(\"I\", si1);\n" +
                "SiteInst si2 = design.createSiteInst(device.getSite(\"SLICE_X0Y0\"));\n" +
                "net0.createPin(\"AX\", si2);\n" +
                "Net net1 = com.xilinx.rapidwright.util.CodeGenerator.createTestNet(design, \"net1\", new String[]{\n" +
                "        \"LIOI3_SING_X0Y0/LIOI3_SING.LIOI_IBUF0->LIOI_I0\",\n" +
                "        \"LIOI3_SING_X0Y0/LIOI3_SING.LIOI_I0->LIOI_ILOGIC0_D\",\n" +
                "        \"LIOI3_SING_X0Y0/LIOI3_SING.LIOI_ILOGIC0_D->>IOI_ILOGIC0_O\",\n" +
                "        \"LIOI3_SING_X0Y0/LIOI3_SING.IOI_ILOGIC0_O->>IOI_LOGIC_OUTS18_0\",\n" +
                "        \"IO_INT_INTERFACE_L_X0Y0/IO_INT_INTERFACE_L.INT_INTERFACE_LOGIC_OUTS_L_B18->>INT_INTERFACE_LOGIC_OUTS_L18\",\n" +
                "        \"INT_L_X0Y0/INT_L.LOGIC_OUTS_L18->>EE2BEG0\",\n" +
                "        \"INT_L_X2Y0/INT_L.EE2END0->>IMUX_L1\",\n" +
                "        \"CLBLL_L_X2Y0/CLBLL_L.CLBLL_IMUX1->CLBLL_LL_A3\"\n" +
                "});\n" +
                "SiteInst si3 = design.createSiteInst(device.getSite(\"IOB_X0Y0\"));\n" + 
                "net1.createPin(\"I\", si3);\n" +
                "net1.createPin(\"A3\", si2);\n"
                + "\n";
        Assertions.assertEquals(expectedString, actualString);
    }
}
