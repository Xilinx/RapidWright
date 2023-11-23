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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;

/**
 * This utility class is used to create RapidWright code from a DCP file that is tedious to create by hand.
 * @author Hayden Cook
 * Created on: August 16, 2023
 */
public class CodeGenerator {

    /**
     * Convenience method to take a net and return a String the generates the routed
     * net for an example test case in RapidWright code
     * 
     * @param net The net to extract routing and SiteInst from
     * @return The RapidWright code string
     */
    public static String testNetGenerator(Net net) {
        Collection<String> nets = new ArrayList<>();
        nets.add(net.getName());
        Collection<String> siteInsts = new ArrayList<>();
        for (SiteInst si : net.getSiteInsts()) {
            siteInsts.add(si.getSiteName());
        }
        return testNetGenerator(net.getDesign(), nets, siteInsts);
    }

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
        int siteIdx = 0;
        Map<String, Integer> siteInstsCreated = new HashMap<>();
        for (String netName : nets) {
            Net net = design.getNet(netName);
            String varName = "net";
            if (nets.size() > 1) varName += netIdx;
            code.append(String.format(
                    "Net %s = " + CodeGenerator.class.getName() + ".createTestNet(design, \"%s\", new String[]{\n",
                    varName, varName));

            List<PIP> pips = net.getPIPs();
            for(int i = 0; i < pips.size(); i++) {
                PIP pip = pips.get(i);
                code.append(String.format("        \"%s\"", pip));
                if (i != pips.size()-1)
                    code.append(",");
                code.append("\n");
            }
            code.append("});\n");
            for (SitePinInst pin : net.getPins()) {
                String siteName = pin.getSiteInst().getSiteName();
                Integer siteInstIdx = siteInstsCreated.get(siteName);
                if (siteInstIdx == null) {
                    siteIdx++;
                    code.append(createSiteInstCode(siteIdx, siteName));
                    siteInstsCreated.put(siteName, siteIdx);
                    siteInstIdx = siteIdx;
                }
                code.append(
                        varName + ".createPin(\"" + pin.getName() + "\", " + getSiteInstVarName(siteInstIdx) + ");\n");
            }
            netIdx++;
        }
        code.append("\n");

        for (String siteName : siteInsts) {
            if (siteInstsCreated.containsKey(siteName)) {
                continue;
            }
            siteIdx++;
            code.append(createSiteInstCode(siteIdx, siteName));
            siteInstsCreated.put(siteName, siteIdx);
        }

        return code.toString();
    }
    
    private static String createSiteInstCode(int siteInstIdx, String siteName) {
        String varName = getSiteInstVarName(siteInstIdx);
        return String.format("SiteInst %s = design.createSiteInst(device.getSite(\"%s\"));\n", varName, siteName);
    }

    private static String getSiteInstVarName(int siteInstIdx) {
        return "si" + siteInstIdx;
    }

    public static Net createTestNet(Design design, String netName, String[] pips) {
        Net net = design.createNet(netName);
        addPIPs(net, pips);
        return net;
    }

    public static void addPIPs(Net net, String[] pips) {
        Device device = net.getDesign().getDevice();
        for (String pip : pips) {
            net.addPIP(device.getPIP(pip));
        }
    }
}
