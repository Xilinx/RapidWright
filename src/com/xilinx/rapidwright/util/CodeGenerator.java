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

    /**
     * Shorthand method to route site wires with a particular net in a site
     * instance.
     * 
     * @param si        The site instance to target
     * @param n         The net to be site routed
     * @param siteWires The list of site wire names to route
     */
    public static void routeSiteNet(SiteInst si, Net n, String... siteWires) {
        for (String siteWire : siteWires) {
            BELPin bp = si.getSiteWirePins(siteWire)[0];
            si.routeIntraSiteNet(n, bp, bp);
        }
    }
    
    /**
     * Shorthand method for turning on site PIPs in a site instance.
     * 
     * @param si       The target site instance.
     * @param sitePIPs The list of site PIPs (of the format <BEL name>:<input pin
     *                 name>) to turn on.
     */
    public static void addSitePIPs(SiteInst si, String... sitePIPs) {
        for (String sitePIP : sitePIPs) {
            int colonIdx = sitePIP.indexOf(':');
            si.addSitePIP(sitePIP.substring(0, colonIdx), sitePIP.substring(colonIdx + 1));
        }
    }

    /**
     * Shorthand method for creating a placed cell inside a site instance. This is
     * mostly useful for constructing test case scenarios.
     * 
     * @param si          The target site instance
     * @param name        Name of the cell to create
     * @param isRoutethru Flag indicating if the cell should be a routethru
     * @param type        The type of the cell (Unisim or special field)
     * @param bel         The name of the BEL where the cell should be placed
     * @param pinMaps     A variable number of pin mappings for the cell where each
     *                    string is of the format <physical pin name>:<logical pin
     *                    name>
     * @return The created cell.
     */
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
            String logPin = pinMap.substring(colonIdx + 1);
            if (logPin.equals("null")) {
                c.removePinMapping(pinMap.substring(0, colonIdx));
            } else {
                c.addPinMapping(pinMap.substring(0, colonIdx), logPin);
            }

        }
        return c;
    }

    private static int uniqueCount = 0;
    
    public static String TEST_SITE_INST_CLASS_NAME = "GenTestSiteInstDesign";
    public static String TEST_SITE_INST_METHOD_NAME = "genTestSiteInstDesign";

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
        String part = design.getPartName();
        String siteName = inst.getSiteName();
        Map<String, String> nameMap = new HashMap<>();
        
        for (String c : new String[] {"design.AltPinMapping", "design.Cell", "design.Design", 
                "design.Net", "design.NetType", "design.SiteInst", "device.Device", 
                "device.SiteTypeEnum", "edif.EDIFCell", "edif.EDIFDirection", "edif.EDIFHierCellInst",
                "edif.EDIFHierPortInst", "edif.EDIFNetlist", "edif.EDIFTools", "edif.EDIFValueType",
                "util.CodeGenerator" }) {
            ps.println("import com.xilinx.rapidwright."+c+";");
        }

        ps.println("");
        ps.println("public class " + TEST_SITE_INST_CLASS_NAME + " {");
        ps.println("    public Design " + TEST_SITE_INST_METHOD_NAME + "() {");
        ps.println("        Design design = new Design(\"test\", \"" + part + "\");");
        ps.println("        Device device = design.getDevice();");
        ps.println("        SiteInst si = design.createSiteInst(\"" + siteName + "\", SiteTypeEnum." 
                                                                + inst.getSiteTypeEnum() 
                                                                + ", device.getSite(\"" 
                                                                + siteName + "\"));");
        ps.println("        EDIFNetlist netlist = design.getNetlist();");
        ps.println("        EDIFCell top = netlist.getTopCell();");
        String tab = "        ";
        for (Cell c : inst.getCells()) {
            String newCellName = nameMap.computeIfAbsent(c.getName(), n -> ("cell" + uniqueCount++));
            nameMap.put(c.getName(), newCellName);

            String varName = newCellName + ((c.isRoutethru() || c.getType().startsWith("<")) ? "_" + c.getBELName() : "");
            
            ps.print(tab + "Cell "+varName+" = CodeGenerator.genCell(si, \"" + newCellName + "\", " + c.isRoutethru() + ", \""
                    + c.getType() + "\", \"" + c.getBELName() + "\"");

            String[] physPinMappings = c.getPhysicalPinMappings();
            for (int i = 0; i < physPinMappings.length; i++) {
                String physPinName = c.getBEL().getPin(i).getName();
                String logPinName = physPinMappings[i];
                ps.print(", \"" + physPinName + ":" + logPinName + "\"");
            }
            ps.println(");");

            for (Entry<String,AltPinMapping> apm : c.getAltPinMappings().entrySet()) {
                ps.println(tab + varName + ".addAltPinMapping(\"" + apm.getKey() + "\",new AltPinMapping(\""
                                                    +apm.getValue().getLogicalName()+"\", \""
                                                    +nameMap.computeIfAbsent(apm.getValue().getAltCellName(), n -> ("cell" + uniqueCount++))+"\",\""
                                                    +apm.getValue().getAltCellType()+"\"));");    
            }
            if (!c.isRoutethru()) {
                for (Entry<String, EDIFPropertyValue> e : c.getProperties().entrySet()) {
                    ps.println(tab + varName + ".addProperty(\"" + e.getKey() + "\", \"" + e.getValue().getValue()
                            + "\", EDIFValueType." + e.getValue().getType().name() + ");");
                }
            }
            for (String fixPin : c.getPhysicalPinMappings()) {
                if (c.isPinFixed(fixPin)) {
                    ps.println(tab + varName + ".fixPin(\""+fixPin+"\");");
                }
            }
            SiteTypeEnum altBlockedType = c.getAltBlockedSiteType();
            if (altBlockedType != null) {
                ps.println(tab + varName + ".setAltBlockedSiteType(SiteTypeEnum." + altBlockedType.name()+");");
            }

            if (c.isBELFixed()) ps.println(tab + varName + ".setBELFixed(true);");
            if (c.isLocked()) ps.println(tab + varName + ".setLocked(true);");
            if (c.isNullBEL()) ps.println(tab + varName + ".setNullBEL(true);");
            if (c.isSiteFixed()) ps.println(tab + varName + ".setSiteFixed(true);");
        }

        ps.println();
        List<SitePIP> usedSitePIPs = inst.getUsedSitePIPs();
        for (int i = 0; i < usedSitePIPs.size(); i++) {
            SitePIP p = usedSitePIPs.get(i);
            ps.print(i == 0 ? tab + "CodeGenerator.addSitePIPs(si, " : ", ");
            ps.print("\"" + p.getBELName() + ":" + p.getInputPinName() + "\"");
            if (i == usedSitePIPs.size() - 1) {
                ps.println(");");
            }
        }
        ps.println();

        for (Entry<Net, List<String>> e : inst.getNetToSiteWiresMap().entrySet()) {
            Net n = e.getKey();
            boolean hasSrc = false;
            boolean hasSnk = false;
            String newNetName = (n.isStaticNet() || n.isUsedNet()) ? null
                    : nameMap.computeIfAbsent(n.getName(), p -> ("net" + uniqueCount++));
            if (newNetName == null) {
                if (n.isStaticNet()) {
                    newNetName = n.isVCCNet() ? "vcc" : "gnd";
                    ps.println(tab + "Net " + newNetName + " = design." + (n.isVCCNet() ? "getVccNet()" : "getGndNet()")
                            + ";");
                    for (Cell c : inst.getCells()) {
                        if (!c.isRoutethru()) {
                            EDIFHierCellInst ci = c.getEDIFHierCellInst();
                            for (EDIFPortInst pi : ci.getInst().getPortInsts()) {
                                if ((pi.getNet().isVCC() && n.isVCCNet()) || (pi.getNet().isGND() && n.isGNDNet())) {

                                    ps.println(tab + "EDIFTools.getStaticNet(NetType."
                                            + (pi.getNet().isVCC() ? "VCC" : "GND")
                                            + ", top, netlist).createPortInst(\"" + pi.getName() + "\", "
                                            + nameMap.get(c.getName()) + ");");
                                }
                            }
                        }
                    }
                } else if (n.isUsedNet()) {
                    newNetName = "usedNet";
                    ps.println(tab + "Net " + newNetName + " = design.createNet(Net.USED_NET);");
                } else {
                    throw new RuntimeException("ERROR: Unhandled Net type");
                }
            } else {
                ps.println(tab + "Net " + newNetName + " = design.createNet(\"" + newNetName + "\");");
                if (n.getLogicalHierNet() != null) {
                    for (EDIFHierPortInst pi : n.getLogicalHierNet().getLeafHierPortInsts()) {
                        String newCellName = nameMap.get(pi.getFullHierarchicalInstName());
                        if (newCellName != null) {
                            ps.println(tab + newNetName + ".getLogicalNet().createPortInst(\""
                                    + pi.getPortInst().getName()
                                    + "\", " + newCellName + ");");
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
                    ps.println(tab + newNetName + ".createPin(\"" + pin.getName() + "\", si);");
                }
            }
            if (includeRouting) {
                ps.println(tab + "CodeGenerator.addPIPs(" + newNetName + ", new String[] {");
                for (PIP p : n.getPIPs()) {
                    ps.println(tab + "\"" + p.toString() + "\",");
                }                                        
                ps.println(tab + "});");
            }
            ps.print(tab + "CodeGenerator.routeSiteNet(si, " + newNetName + " ");
            for (String siteWire : e.getValue()) {
                ps.print(", \"" + siteWire + "\"");
            }
            ps.println(");");
        }
        ps.println(tab + "design.setDesignOutOfContext(true);");
        ps.println(tab + "design.setAutoIOBuffers(false);");
        ps.println(tab + "return design;");
        ps.println("    }");
        ps.println("}");
    }
}
