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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

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

    public static void addCellPinMappings(Cell c, String... pinMappings) {
        for (String pinMapping : pinMappings) {
            int colonIdx = pinMapping.indexOf(':');
            c.addPinMapping(pinMapping.substring(0, colonIdx), pinMapping.substring(colonIdx + 1));
        }
    }

    public static void routeSiteNet(SiteInst si, Net n, String... siteWires) {
        for (String siteWire : siteWires) {
            BELPin bp = si.getSiteWirePins(siteWire)[0];
            si.routeIntraSiteNet(n, bp, bp);
        }
    }
    
    public static void addSitePIPs(SiteInst si, String... sitePIPs) {
        for (String sitePIP : sitePIPs) {
            int colonIdx = sitePIP.indexOf(':');
            si.addSitePIP(sitePIP.substring(0, colonIdx), sitePIP.substring(colonIdx + 1));
        }
    }

    public static Cell genCell(SiteInst si, String name, boolean isRoutethru, String type, String bel,
            String... pinMaps) {
        Cell c = null;
        if (isRoutethru || type.startsWith("<")) {
            c = new Cell(name, si.getBEL(bel));
            c.setSiteInst(si);
            si.getCellMap().put(bel, c);
            c.setType(type);
            c.setRoutethru(isRoutethru);
        } else {
            c = si.getDesign().createAndPlaceCell(si.getDesign().getTopEDIFCell(), name, Unisim.valueOf(type),
                    si.getSite(), si.getBEL(bel));
        }
        for (String pinMap : pinMaps) {
            int colonIdx = pinMap.indexOf(':');
            c.addPinMapping(pinMap.substring(0, colonIdx), pinMap.substring(colonIdx + 1));
        }
        return c;
    }

    private static int uniqueCount = 0;
    
    /**
     * Creates boilerplate code for a test by re-creating a SiteInst's configuration
     * from scratch in RapidWright APIs. This is useful for testing when only a
     * small context is necessary to reproduce a specific scenario.
     * 
     * @param inst          The instance to replicate in the test
     * @param ps            The PrintStream (System.out, or a file-based
     *                      PrintStream, for example).
     * @param simplifyNames Flag indicating that the cell and and net names should
     *                      be simplified.
     */
    public static void genCodeForTestSite(SiteInst inst, PrintStream ps, boolean includeRouting) {
        Design design = inst.getDesign();
        EDIFNetlist netlist = design.getNetlist();
        String part = design.getPartName();
        String siteName = inst.getSiteName();
        Map<String, String> nameMap = new HashMap<>();
        
        ps.println("    public Design genTestDesign() {");
        ps.println("        Design design = new Design(\"test\", \"" + part + "\");");
        ps.println("        Device device = design.getDevice();");
        ps.println("        SiteInst si = design.createSiteInst(\"" + siteName + "\", SiteTypeEnum." 
                                                                + inst.getSiteTypeEnum() 
                                                                + ", device.getSite(\"" 
                                                                + siteName + "\"));");
        ps.println("        EDIFNetlist netlist = design.getNetlist();");
        ps.println("        EDIFCell top = netlist.getTopCell();");
        String tab = "            ";
        for (Cell c : inst.getCells()) {
            String newCellName = nameMap.computeIfAbsent(c.getName(), n -> ("cell" + uniqueCount++));
            nameMap.put(c.getName(), newCellName);
            ps.println("        {");

            ps.print(tab + "Cell c = CodeGenerator.genCell(si, \"" + newCellName + "\", " + c.isRoutethru() + ", \""
                    + c.getType() + "\", \"" + c.getBELName() + "\"");

//            if (!c.isRoutethru() && !c.isFFRoutethruCell() && c.getType() != null) {
//                ps.println(tab + "Cell c = design.createAndPlaceCell(\"" + newCellName + "\", Unisim." + c.getType()
//                        + ", \"" + siteName + "/" + c.getBELName() + "\");");
//
//            } else {
//                ps.println(tab + "Cell c = new Cell(\"" + newCellName + "\", si.getBEL(\"" + c.getBELName() + "\"));");
//                ps.println(tab + "c.setSiteInst(si);");
//                ps.println(tab + "c.setType(\"" + c.getType() + "\");");
//            }
//
//            ps.print(tab + "CodeGenerator.addCellPinMappings(c");
//            for (Entry<String, String> pm : c.getPinMappingsP2L().entrySet()) {
//                ps.print(", \"" + pm.getKey() + ":" + pm.getValue() + "\"");
//            }
//            ps.println(");");
//
            for (Entry<String, String> pm : c.getPinMappingsP2L().entrySet()) {
                ps.print(", \"" + pm.getKey() + ":" + pm.getValue() + "\"");
            }
            ps.println(");");

            for (Entry<String,AltPinMapping> apm : c.getAltPinMappings().entrySet()) {
                ps.println(tab + "c.addAltPinMapping(\"" + apm.getKey() + "\",new AltPinMapping(\""
                                                    +apm.getValue().getLogicalName()+"\", \""
                                                    +nameMap.computeIfAbsent(apm.getValue().getAltCellName(), n -> ("cell" + uniqueCount++))+"\",\""
                                                    +apm.getValue().getAltCellType()+"\"));");    
            }
            if (!c.isRoutethru()) {
                for (Entry<String, EDIFPropertyValue> e : c.getProperties().entrySet()) {
                    ps.println(tab + "c.addProperty(\"" + e.getKey() + "\", \"" + e.getValue().getValue()
                            + "\", EDIFValueType." + e.getValue().getType().name() + ");");
                }
            }
            for (String fixPin : c.getPhysicalPinMappings()) {
                if (c.isPinFixed(fixPin)) {
                    ps.println(tab + "c.fixPin(\""+fixPin+"\");");
                }
            }
            SiteTypeEnum altBlockedType = c.getAltBlockedSiteType();
            if (altBlockedType != null) {
                ps.println(tab + "c.setAltBlockedSiteType(SiteTypeEnum." + altBlockedType.name()+");");
            }

            if (c.isBELFixed()) ps.println(tab + "c.setBELFixed(true);");
            if (c.isLocked()) ps.println(tab + "c.setLocked(true);");
            if (c.isNullBEL()) ps.println(tab + "c.setNullBEL(true);");
            if (c.isSiteFixed()) ps.println(tab + "c.setSiteFixed(true);");
            ps.println("        }");
        }

        List<SitePIP> usedSitePIPs = inst.getUsedSitePIPs();
        for (int i = 0; i < usedSitePIPs.size(); i++) {
            SitePIP p = usedSitePIPs.get(i);
            ps.print(i == 0 ? "CodeGenerator.addSitePIPs(si, " : ", ");
            ps.print("\"" + p.getBELName() + ":" + p.getInputPinName() + "\"");
            if (i == usedSitePIPs.size() - 1) {
                ps.println(");");
            }
        }

//        for (SitePIP sPIP : inst.getUsedSitePIPs()) {
//            ps.println(tab + "si.addSitePIP(\"" + sPIP.getBELName() + "\", \"" + sPIP.getInputPinName() + "\");");
//        }

        for (Entry<Net, List<String>> e : inst.getNetToSiteWiresMap().entrySet()) {
            Net n = e.getKey();
            boolean hasSrc = false;
            boolean hasSnk = false;
            String newNetName = n.isStaticNet() ? null : nameMap.computeIfAbsent(n.getName(), p -> ("net" + uniqueCount++));
            ps.println("        {");
            if (newNetName == null) {
                ps.println(tab + "Net n = design." + (n.isVCCNet() ? "getVccNet()" : "getGndNet()") + ";");
                for (Cell c : inst.getCells()) {
                    if (!c.isRoutethru()) {
                        EDIFHierCellInst ci = c.getEDIFHierCellInst();
                        for (EDIFPortInst pi : ci.getInst().getPortInsts()) {
                            if ((pi.getNet().isVCC() && n.isVCCNet()) || (pi.getNet().isGND() && n.isGNDNet())) {
                                
                                ps.println(tab + "EDIFTools.getStaticNet(NetType." + (pi.getNet().isVCC() ? "VCC" : "GND") 
                                        + ", top, netlist).createPortInst(\"" + pi.getName() + "\", design.getCell(\""
                                        + nameMap.get(c.getName()) + "\"));");
                            }
                        }
                    }
                }
            } else {
                ps.println(tab + "Net n = design.createNet(\""+newNetName+"\");");
                if (n.getLogicalHierNet() != null) {
                    for (EDIFHierPortInst pi : n.getLogicalHierNet().getLeafHierPortInsts()) {
                        String newCellName = nameMap.get(pi.getFullHierarchicalInstName());
                        if (newCellName != null) {
                            ps.println(tab + "n.getLogicalNet().createPortInst(\"" + pi.getPortInst().getName()
                                    + "\", design.getCell(\"" + newCellName + "\"));");
                            if (pi.isOutput()) {
                                hasSrc = true;
                            } else {
                                hasSnk = true;
                            }
                        }
                    }
                    if (!hasSrc) {
                        ps.println(tab + "top.getNet(\"" + newNetName + "\").createPortInst(top.createPort(\"port"
                                + (uniqueCount++) + "\", EDIFDirection.INPUT, 1));");
                    }
                    if (!hasSnk) {
                        ps.println(tab + "top.getNet(\"" + newNetName + "\").createPortInst(top.createPort(\"port"
                                + (uniqueCount++) + "\", EDIFDirection.OUTPUT, 1));");
                    }
                }
            }

            
            
            for (SitePinInst pin : n.getPins()) {
                if (pin.getSiteInst() == inst) {
                    ps.println(tab + "n.createPin(\"" + pin.getName() + "\", si);");
                }
            }
            if (includeRouting) {
                ps.println(tab + "CodeGenerator.addPIPs(n, new String[] {");
                for (PIP p : n.getPIPs()) {
                    ps.println(tab + "\"" + p.toString() + "\",");
                }                                        
                ps.println(tab + "});");
            }
            ps.print(tab + "CodeGenerator.routeSiteNet(si, n ");
            for (String siteWire : e.getValue()) {
                ps.print(", \"" + siteWire + "\"");
            }
            ps.println(");");
            ps.println("        }");
        }
        
        ps.println("    return design;");
        ps.println("}");
    }
}
