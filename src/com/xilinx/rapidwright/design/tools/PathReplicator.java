/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.design.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.rwroute.PartialRouter;
import com.xilinx.rapidwright.util.FileTools;

/**
 * This is a tool to extract a signal path implementation from one DCP and copy
 * it into an empty context of a new DCP. This is useful for creating small test
 * cases to be used in CI.
 * 
 */
public class PathReplicator {

    public static EDIFHierCellInst ensureHierCellInstExists(EDIFHierCellInst cellInst, Design dst) {
        EDIFHierCellInst parent = cellInst.getParent();
        if (parent == null || cellInst.isTopLevelInst()) {
            return dst.getNetlist().getTopHierCellInst();
        }
        EDIFNetlist dstNetlist = dst.getNetlist();
        EDIFHierCellInst dstParent = dstNetlist.getHierCellInstFromName(parent.toString());
        if (dstParent == null) {
            dstParent = ensureHierCellInstExists(parent, dst);
        }
        EDIFCellInst currInst = dstParent.getCellType().getCellInst(cellInst.getInst().getName());
        if (currInst == null) {
            EDIFLibrary origLib = cellInst.getCellType().getLibrary();
            EDIFLibrary dstLib = dstNetlist.getLibrary(origLib.getName());
            if (dstLib == null) {
                dstLib = new EDIFLibrary(origLib.getName());
                dstNetlist.addLibrary(dstLib);
            }
            EDIFCell type = dstLib.getCell(cellInst.getCellName());
            if (type == null) {
                type = new EDIFCell(dstLib, cellInst.getCellName());
                for (EDIFPort port : cellInst.getCellType().getPorts()) {
                    type.createPort(port);
                }
                type.setPropertiesMap(cellInst.getCellType().createDuplicatePropertiesMap());
            }
            currInst = dstParent.getCellType().createChildCellInst(cellInst.getInst().getName(), type);
        }

        return dstParent.getChild(currInst);
    }
    
    public static EDIFHierNet ensureHierNetExists(EDIFHierNet net, Design dst) {
        EDIFHierCellInst dstInst = ensureHierCellInstExists(net.getHierarchicalInst(), dst);
        EDIFHierNet dstNet = dstInst.getNet(net.getNet().getName());
        if (dstNet == null) {
            // We will create the net and connect it to the present cells and ports accordingly
            EDIFNet origNet = net.getNet();
            EDIFCell dstCell = dstInst.getCellType();
            EDIFNet newNet = dstCell.createNet(origNet.getName());
            for (EDIFPortInst portInst : origNet.getPortInsts()) {
                if (portInst.isTopLevelPort()) {
                    newNet.createPortInst(dstCell.getPort(portInst.getPort().getBusName()), portInst.getIndex());                        
                } else {
                    EDIFCellInst dstConnInst = dstCell.getCellInst(portInst.getCellInst().getName());
                    if (dstConnInst != null) {
                        if (portInst.getPort().isBus()) {
                            newNet.createPortInst(
                                    dstConnInst.getPort(portInst.getPort().getBusName()),
                                    portInst.getIndex(), dstConnInst);
                        } else {
                            newNet.createPortInst(portInst.getName(), dstConnInst);                            
                        }
                    }
                }
            }
        }
        return dstNet;
    }
    
    private static void copyCellPinMappings(Cell src, Cell dst) {
        String[] physPinMappings = src.getPhysicalPinMappings();
        BEL bel = src.getBEL();
        for (int j = 0; j < physPinMappings.length; j++) {
            BELPin physPin = bel.getPin(j);
            if (physPinMappings[j] == null) {
                dst.removePinMapping(physPin.getName());
            } else {
                dst.addPinMapping(physPin.getName(), physPinMappings[j]);
            }
        }
    }

    /**
     * Extracts a placed and routed path defined by a list of logical pin names into
     * another design. This method will faithfully reproduce logical hierarchy,
     * names, placement and routing of the defined path. It will also reproduce
     * clock nets that drive any cell in the specified path up to the source BUFG.
     * All other inputs of cells in the netlist are currently left unconnected.
     * 
     * @param src      The design source where the path exists.
     * @param dst      The destination design where the path should be replicated.
     * @param pathPins The ordered list of pins in the source design to replicate.
     */
    public static void replicatePath(Design src, Design dst, List<String> pathPins) {
        EDIFNetlist netlist = src.getNetlist();
        Set<Cell> cells = new HashSet<>();
        Set<SiteInst> siteInsts = new HashSet<>();
        Map<Net, Set<SitePinInst>> nets = new HashMap<>();
        for (String pinName : pathPins) {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(pinName);
            if (ehpi == null) {
                throw new RuntimeException("ERROR: Couldn't find pin " + ehpi);
            }
            Cell cell = ehpi.getPhysicalCell(src);
            if (cell != null) {
                cells.add(cell);
                siteInsts.add(cell.getSiteInst());
                String[] physPinMappings = cell.getPhysicalPinMappings();
                for (int i = 0; i < physPinMappings.length; i++) {
                    String logPinName = physPinMappings[i];
                    if (logPinName != null) {
                        BELPin belPin = cell.getBEL().getPin(i);
                        // Be sure to add the clock nets
                        if (belPin.isClock()) {
                            Net clk = cell.getSiteInst().getNetFromSiteWire(belPin.getSiteWireName());
                            if (clk != null) {
                                SitePinInst clkPin = cell.getSitePinFromLogicalPin(logPinName, null);
                                nets.computeIfAbsent(clk, s -> new HashSet<>()).add(clkPin);
                                // Preserve the clock source bufg
                                List<EDIFHierPortInst> bufgPin = clk.getLogicalHierNet()
                                        .getLeafHierPortInsts(true, false, false);
                                assert(bufgPin.size() == 1);
                                Cell bufg = bufgPin.get(0).getPhysicalCell(src);
                                cells.add(bufg);
                                if (bufg.getType().equals("BUFGCE")) {
                                    SitePinInst ce = bufg.getSitePinFromLogicalPin("CE", null);
                                    nets.computeIfAbsent(src.getVccNet(), s -> new HashSet<>()).add(ce);
                                }
                            }
                        }
                    }
                }

            }
            
            Net net = ehpi.getRoutedPhysicalNet(src);
            if (net != null && !net.isStaticNet()) {
                SitePinInst spi = cell.getSitePinFromLogicalPin(ehpi.getPortInst().getName(), null);
                Set<SitePinInst> spis = nets.computeIfAbsent(net, s -> new HashSet<>());
                if (spi != null) {
                    spis.add(spi);
                }
            }
        }
        
        // Copy all physical cells
        for (Cell cell : cells) {
            EDIFHierCellInst orig = cell.getEDIFHierCellInst();
            EDIFHierCellInst hierCell = ensureHierCellInstExists(orig, dst);
            Cell dstCell = dst.createCell(hierCell.toString(), hierCell.getInst());
            hierCell.getInst().setPropertiesMap(cell.getEDIFCellInst().createDuplicatePropertiesMap());
            dst.placeCell(dstCell, cell.getSite(), cell.getBEL(), cell.getPhysicalPinMappings());
            copyCellPinMappings(cell, dstCell);
        }
        
        EDIFNetlist dstNetlist = dst.getNetlist();

        // Ensure all alias parent cells are present in the netlist
        for (Entry<Net, Set<SitePinInst>> e : nets.entrySet()) {
            if (e.getKey().isStaticNet()) continue;
            for (EDIFHierNet alias : netlist.getNetAliases(e.getKey().getLogicalHierNet())) {
                // Macros are excluded because they will pull in leaf cells unnecessarily
                if (!alias.getHierarchicalInst().getCellType().isMacro()) {
                    ensureHierCellInstExists(alias.getHierarchicalInst(), dst);
                }
            }
        }
        
        // Copy all physical nets
        for (Entry<Net, Set<SitePinInst>> e : nets.entrySet()) {
            Net net = e.getKey();

            Set<SitePinInst> pinsToKeep = e.getValue();
            // Copy logical net and aliases
            Net dstNet = null;
            if (!net.isStaticNet()) {
                EDIFHierNet logHierNet = net.getLogicalHierNet();
                for (EDIFHierNet alias : netlist.getNetAliases(logHierNet)) {
                    if (dstNetlist.getHierCellInstFromName(alias.getHierarchicalInstName()) != null) {
                        ensureHierNetExists(alias, dst);
                    }
                }

                dstNet = dst.createNet(logHierNet);
            } else {
                dstNet = dst.getStaticNet(net.getType());
            }

            for (SitePinInst pin : pinsToKeep) {
                SiteInst si = pin.getSiteInst();
                if (si == null) {
                    continue;
                }

                // Copy site pins (although they don't exist in DCPs)
                SiteInst dstSiteInst = dst.getSiteInstFromSiteName(si.getSiteName());
                if (dstSiteInst == null) {
                    continue;
                }
                dstNet.createPin(pin.getName(), dstSiteInst);
                
                // Copy site routing
                for (String siteWire : pin.getSiteInst().getSiteWiresFromNet(net)) {
                    BELPin[] belPins = dstSiteInst.getSite().getBELPins(siteWire);
                    dstSiteInst.routeIntraSiteNet(dstNet, belPins[0], belPins[0]);
                    
                    // Check for sitePIPs and routethrus
                    for (BELPin belPin : belPins) {
                        SitePIP sitePIP = pin.getSiteInst().getUsedSitePIP(belPin);
                        if (sitePIP != null) {
                            dstSiteInst.addSitePIP(sitePIP);
                        }
                        Cell cell = si.getCell(belPin.getBEL());
                        if (cell != null && cell.isRoutethru() && belPin.isOutput()) {
                            String[] physPinMappings = cell.getPhysicalPinMappings();
                            for (int i = 0; i < physPinMappings.length; i++) {
                                if (physPinMappings[i] != null) {
                                    BELPin rtInput = cell.getBEL().getPin(i);
                                    if (!dstSiteInst.routeIntraSiteNet(dstNet, rtInput, belPin)) {
                                        throw new RuntimeException("ERROR: Failed to replicate site "
                                                + "routing for routethru " + cell);
                                    }

                                    Cell dstRtCell = dstSiteInst.getCell(cell.getBEL());
                                    if (dstRtCell != null) {
                                        if (cell.isPinFixed(rtInput.getName())) {
                                            dstRtCell.fixPin(rtInput.getName());
                                        }
                                        copyCellPinMappings(cell, dstRtCell);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            // Copy intersite routing
            Set<SitePinInst> pinsToUnroute = new HashSet<>();
            for (SitePinInst pin : net.getPins()) {
                if (!pin.isOutPin() && !pinsToKeep.contains(pin)) {
                    pinsToUnroute.add(pin);
                }
            }
            // For static nets, some pins are implicit, we need to filter them as well
            if (net.isStaticNet()) {
                for (PIP p : net.getPIPs()) {
                    Node n = p.getEndNode();
                    if (n != null) {
                        SitePin sp = n.getSitePin();
                        if (sp != null && sp.isInput()) {
                            SiteInst si = dst.getSiteInstFromSite(sp.getSite());
                            if (si == null) {
                                SiteInst origSi = src.getSiteInstFromSite(sp.getSite());
                                if (origSi != null) {
                                    SiteInst siDummy = new SiteInst(sp.getSite().getName(),
                                            origSi.getSiteTypeEnum());
                                    siDummy.place(sp.getSite());
                                    SitePinInst spi = new SitePinInst(sp.getPinName(), siDummy);
                                    // Note: This modifies the source design by adding dummy site pins
                                    // to the source static net in order to get the desired output from
                                    // DesignTools.getTrimmablePIPsFromPins().
                                    net.addPin(spi, false);
                                    pinsToUnroute.add(spi);
                                }
                            }
                        }
                    }
                }                
            }

            // Only keep routing connected to the pins that drive a used site pin
            Set<PIP> pipsToExclude = DesignTools.getTrimmablePIPsFromPins(net, pinsToUnroute);
            for (PIP p : net.getPIPs()) {
                if (!pipsToExclude.contains(p)) {
                    dstNet.addPIP(new PIP(p));
                }
            }
        }

        dst.getNetlist().consolidateAllToWorkLibrary(true);

        // Tie off CE/SR pins
        DesignTools.createCeSrRstPinsToVCC(dst);
        PartialRouter.routeDesignPartialNonTimingDriven(dst, null);
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("USAGE: <source.dcp> <dest.dcp> <path.txt>");
            System.out.println("         path.txt could be generated from Vivado with a Tcl command such as:");
            System.out.println("         'set fp [open path.txt \"w\"]; foreach p [get_pins -of [get_timing_paths -nworst 1 ]] {puts $fp $p}; close $fp'");
            return;
        }

        Design src = Design.readCheckpoint(args[0]);
        Design dst = new Design(src.getTopEDIFCell().getName(), src.getPartName());
        List<String> pathPins = FileTools.getLinesFromTextFile(args[2]);

        replicatePath(src, dst, pathPins);

        dst.writeCheckpoint(args[1]);
    }
}
