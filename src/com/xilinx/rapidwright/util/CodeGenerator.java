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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.PIP;

import java.util.Collection;
import java.util.List;

/**
 * This utility class is used to create RapidWright code from a DCP file that is tedious to create by hand.
 * @author Hayden Cook
 * Created on: August 16, 2023
 */
public class CodeGenerator {

    /**
     * Generates RapidWright test code from a provided checkpoint that instantiates the provided nets and siteInsts,
     * and adds the associated PIPs to each of the nets.
     *
     * @param dcp The path to the desired DCP to generate code from.
     * @param nets A collection of nets to generate code for.
     * @param siteInsts A collection of site instances to generate code for.
     * @return The resulting code.
     */
    public static String testNetGenerator(String dcp, Collection<String> nets, Collection<String> siteInsts) {
        Design d = Design.readCheckpoint(dcp);
        return testNetGenerator(d, nets, siteInsts);
    }

    /**
     * Generates RapidWright test code from a provided checkpoint that instantiates the provided nets and siteInsts,
     * and adds the associated PIPs to each of the nets.
     *
     * @param dcp The path to the desired DCP to generate code from.
     * @param edif The path to the external EDIF file to load (in case of an encrypted DCP file).
     * @param nets A collection of nets to generate code for.
     * @param siteInsts A collection of site instances to generate code for.
     * @return The resulting code.
     */
    public static String testNetGenerator(String dcp, String edif, Collection<String> nets, Collection<String> siteInsts) {
        Design d = Design.readCheckpoint(dcp, edif);
        return testNetGenerator(d, nets, siteInsts);
    }

    /**
     * Generates RapidWright test code from a provided checkpoint that instantiates the provided nets and siteInsts,
     * and adds the associated PIPs to each of the nets.
     *
     * @param design The design to generate code from.
     * @param nets A collection of nets to generate code for.
     * @param siteInsts A collection of site instances to generate code for.
     * @return The resulting code.
     */
    public static String testNetGenerator(Design design, Collection<String> nets, Collection<String> siteInsts) {
        StringBuilder code = new StringBuilder();
        String partName = design.getPartName();
        String designName = design.getName();

        code.append(String.format("Design design = new Design(\"%s\", \"%s\");\n", designName, partName));
        code.append("Device device = design.getDevice();\n\n");

        int netIdx = 0;
        for (String netName : nets) {
            Net net = design.getNet(netName);
            String varName = "net";
            if (nets.size() > 1) varName += netIdx;
            code.append(String.format("Net %s = TestDesignHelper.createTestNet(design, \"%s\", new String[]{\n", varName, varName));

            List<PIP> pips = net.getPIPs();
            for(int i = 0; i < pips.size(); i++) {
                PIP pip = pips.get(i);
                code.append(String.format("        \"%s\"", pip));
                if (i != pips.size()-1)
                    code.append(",");
                code.append("\n");
            }
            code.append("});\n");
            netIdx++;
        }
        code.append("\n");

        int siteIdx = 0;
        for (String siteName : siteInsts) {
            String varName = "si";
            if (nets.size() > 1) varName += siteIdx;
            code.append(String.format("SiteInst %s = design.createSiteInst(design.getDevice().getSite(\"%s\"));\n", varName, siteName));
            siteIdx++;
        }

        return code.toString();
    }
}
