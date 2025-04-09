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

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
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

    @Test
    public void testGenCodeForTestSite(@TempDir Path tempDir) {
        Design d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X36Y97");

        Path javaFile = tempDir.resolve(CodeGenerator.TEST_SITE_INST_CLASS_NAME + ".java");
        try (PrintStream ps = new PrintStream(javaFile.toString())) {
            CodeGenerator.genCodeForTestSite(si, ps, false);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // Compiler should return 0 if code compiled successfully
        Assertions.assertEquals(0, compiler.run(null, null, null, javaFile.toString()));

        try {
            // Run the generated code and extract the Design object
            URLClassLoader classLoader = URLClassLoader.newInstance(new URL[] { tempDir.toFile().toURI().toURL() });
            Class<?> testClass = Class.forName(CodeGenerator.TEST_SITE_INST_CLASS_NAME, true, classLoader);
            Method testMethod = testClass.getMethod(CodeGenerator.TEST_SITE_INST_METHOD_NAME);
            Design testDesign = (Design) testMethod.invoke(testClass.getDeclaredConstructor().newInstance());

            // Compare against the original SiteInst
            SiteInst testSiteInst = testDesign.getSiteInstFromSiteName(si.getSiteName());
            Assertions.assertNotNull(testSiteInst);
            Assertions.assertEquals(si.getCells().size(), testSiteInst.getCells().size());
            for (Cell c : si.getCells()) {
                BEL bel = c.getBEL();
                Cell testCell = testSiteInst.getCell(bel);
                Assertions.assertEquals(c.getType(), testCell.getType());

                Assertions.assertEquals(Arrays.toString(c.getPhysicalPinMappings()),
                        Arrays.toString(testCell.getPhysicalPinMappings()));
                for (Entry<String, String> e : c.getPinMappingsP2L().entrySet()) {
                    Assertions.assertEquals(e.getValue(), testCell.getLogicalPinMapping(e.getKey()));
                }
                Assertions.assertEquals(c.isBELFixed(), testCell.isBELFixed());
                Assertions.assertEquals(c.isSiteFixed(), testCell.isSiteFixed());
                Assertions.assertEquals(c.isCellFixed(), testCell.isCellFixed());
                Assertions.assertEquals(c.isRoutethru(), testCell.isRoutethru());
            }

            for (Entry<Net, List<String>> e : si.getNetToSiteWiresMap().entrySet()) {
                // Net names will not match, so we just match the site wire sets
                Net equivalentNet = testSiteInst.getNetFromSiteWire(e.getValue().get(0));
                List<String> testSiteWires = testSiteInst.getSiteWiresFromNet(equivalentNet);
                String[] gold = e.getValue().toArray(new String[e.getValue().size()]);
                Arrays.sort(gold);
                String[] test = testSiteWires.toArray(new String[testSiteWires.size()]);
                Arrays.sort(test);
                Assertions.assertTrue(Arrays.deepEquals(gold, test));
            }

        } catch (MalformedURLException | ClassNotFoundException | NoSuchMethodException | SecurityException
                | IllegalAccessException | IllegalArgumentException | InvocationTargetException
                | InstantiationException e) {
            throw new RuntimeException(e);
        }
    }
}
