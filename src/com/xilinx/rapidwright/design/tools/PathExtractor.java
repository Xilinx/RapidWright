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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.IntraSiteNet;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
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
import com.xilinx.rapidwright.util.Pair;

/**
 * This is a tool to extract a signal path implementation from one DCP and copy
 * it into an empty context of a new DCP. This is useful for creating small test
 * cases to be used in CI.
 * 
 */
public class PathExtractor {

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
            dstNet = dstInst.getNet(net.getNet().getName());
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

    private static void captureIntraSiteNets(Map<Net, Map<Pair<SiteInst, BELPin>, IntraSiteNet>> nets,
            Net net, Cell cell, String logPinName) {
        BELPin belPin = cell.getBEL().getPin(cell.getPhysicalPinMapping(logPinName));
        SiteInst si = cell.getSiteInst();

        // Check for multiple site pin outputs
        List<SitePinInst> spis = new ArrayList<>();
        for (String pinName : DesignTools.getAllRoutedSitePinsFromPhysicalPin(cell, net, belPin.getName())) {
            SitePinInst spiMaybe = si.getSitePinInst(pinName);
            if (spiMaybe != null) {
                spis.add(spiMaybe);
            }
        }

        if (spis.size() == 0) {
            // This intra-site net is internal, doesn't have a site pin
            Pair<SiteInst, BELPin> isnKey = new Pair<>(si, belPin);
            nets.computeIfAbsent(net, m -> new HashMap<>())
                .computeIfAbsent(isnKey, i -> new IntraSiteNet(si, net, belPin)).addSink(belPin);                        
        } else {
            for (SitePinInst spi : spis) {
                BELPin src = belPin.isOutput() ? belPin : spi.getBELPin();
                BELPin snk = belPin.isOutput() ? spi.getBELPin() : belPin;
                Pair<SiteInst, BELPin> isnKey = new Pair<>(si, src);
                nets.computeIfAbsent(net, m -> new HashMap<>())
                    .computeIfAbsent(isnKey, i -> new IntraSiteNet(si, net, src)).addSink(snk);
            }
        }
    }
    
    /**
     * Examines a cell and decides if it is part of a macro. If so, it adds all the
     * sibling leaf cells in the macro to the provided sets.
     * 
     * @param design    The current design
     * @param cell      The cell to check
     * @param cells     The cells we are preserving in the path
     * @param siteInsts The site instances we are preserving as part of the path
     */
    private static void addOtherMacroSiblingCells(Design design, Cell cell, Set<Cell> cells,
            Set<SiteInst> siteInsts, Map<Net, Map<Pair<SiteInst, BELPin>, IntraSiteNet>> nets) {
        // Identify any parent macro cells, pull in siblings
        EDIFHierCellInst parent = cell.getEDIFHierCellInst().getParent();
        if (parent.getCellType().isMacro()) {
            EDIFHierCellInst grandParent = parent.getParent();
            if (grandParent.getCellType().isMacro()) {
                _addOtherMacroSiblingCells(design, grandParent, cells, siteInsts, nets);
            } else {
                _addOtherMacroSiblingCells(design, parent, cells, siteInsts, nets);
            }
        }

    }

    private static void _addOtherMacroSiblingCells(Design design, EDIFHierCellInst parentMacro,
            Set<Cell> cells, Set<SiteInst> siteInsts, Map<Net, Map<Pair<SiteInst, BELPin>, IntraSiteNet>> nets) {
        for (EDIFCellInst inst : parentMacro.getCellType().getCellInsts()) {
            EDIFHierCellInst relative = parentMacro.getChild(inst);
            if (inst.getCellType().isPrimitive()) {
                Cell c = design.getCell(relative.getFullHierarchicalInstName());
                if (c != null) {
                    cells.add(c);
                    siteInsts.add(c.getSiteInst());
                    String[] physPinMappings = c.getPhysicalPinMappings();
                    for (int i = 0; i < physPinMappings.length; i++) {
                        String logPinName = physPinMappings[i];
                        if (logPinName != null) {
                            BELPin belPin = c.getBEL().getPin(i);
                            if (belPin.isInput()) {
                                Net net = c.getSiteInst().getNetFromSiteWire(belPin.getSiteWireName());
                                if (net != null &&net.isStaticNet()) {
                                    captureIntraSiteNets(nets, net, c, logPinName);
                                }
                            }
                        }
                    }
                }
            } else {
                _addOtherMacroSiblingCells(design, relative, cells, siteInsts, nets);
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
    public static void extractPath(Design src, Design dst, List<String> pathPins) {
        DesignTools.makePhysNetNamesConsistent(src);
        DesignTools.createMissingSitePinInsts(src);

        Map<Net, Cell> mbufgces = new HashMap<>();

        EDIFNetlist netlist = src.getNetlist();
        Set<Cell> cells = new HashSet<>();
        Set<SiteInst> siteInsts = new HashSet<>();
        // Map that keeps track of all intrasite nets that need to be preserved and copied
        // Pair<SiteInst, BELPin> is simply a key for the IntraSiteNet object
        Map<Net, Map<Pair<SiteInst, BELPin>, IntraSiteNet>> nets = new HashMap<>();
        for (String pinName : pathPins) {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(pinName);
            if (ehpi == null) {
                throw new RuntimeException("ERROR: Couldn't find pin " + pinName);
            }
            Cell cell = ehpi.getPhysicalCell(src);
            if (cell != null) {
                cells.add(cell);
                siteInsts.add(cell.getSiteInst());
                addOtherMacroSiblingCells(src, cell, cells, siteInsts, nets);
                String[] physPinMappings = cell.getPhysicalPinMappings();
                for (int i = 0; i < physPinMappings.length; i++) {
                    String logPinName = physPinMappings[i];
                    if (logPinName != null) {
                        BELPin belPin = cell.getBEL().getPin(i);
                        // Be sure to add the clock nets
                        if (belPin.isClock()) {
                            Net clk = cell.getSiteInst().getNetFromSiteWire(belPin.getSiteWireName());
                            if (clk != null) {
                                captureIntraSiteNets(nets, clk, cell, logPinName);
                                // Preserve the clock source bufg
                                List<EDIFHierPortInst> bufgPin = clk.getLogicalHierNet()
                                        .getLeafHierPortInsts(true, false, false);
                                assert(bufgPin.size() == 1);
                                Cell bufg = bufgPin.get(0).getPhysicalCell(src);
                                cells.add(bufg);
                                boolean isMBUFGCE = bufg.getType().equals("MBUFGCE");
                                if (bufg.getType().contains("BUFGCE")) {
                                    captureIntraSiteNets(nets, src.getVccNet(), bufg, "CE");
                                    captureIntraSiteNets(nets, clk, bufg, isMBUFGCE ? "O1" : "O");
                                }
                                if (isMBUFGCE) {
                                    // Multiple logical clocks map to the same routing
                                    mbufgces.put(clk, bufg);
                                }
                            }
                        }
                    }
                }

            }
            
            Net net = ehpi.getRoutedPhysicalNet(src);
            if (net != null && !net.isStaticNet()) {
                captureIntraSiteNets(nets, net, cell, ehpi.getPortInst().getName());
            }
        }

        // Copy all physical cells
        for (Cell cell : cells) {
            EDIFHierCellInst orig = cell.getEDIFHierCellInst();
            EDIFHierCellInst hierCell = ensureHierCellInstExists(orig, dst);
            SiteInst siteInst = cell.getSiteInst();
            // Make sure site inst of the correct type is created
            if (dst.getSiteInstFromSite(siteInst.getSite()) == null) {
                dst.createSiteInst(siteInst.getName(), siteInst.getSiteTypeEnum(),
                        siteInst.getSite());
            }
            Cell dstCell = dst.createCell(hierCell.toString(), hierCell.getInst());
            hierCell.getInst().setPropertiesMap(cell.getEDIFCellInst().createDuplicatePropertiesMap());
            dst.placeCell(dstCell, cell.getSite(), cell.getBEL(), cell.getPhysicalPinMappings());
            copyCellPinMappings(cell, dstCell);
        }
        
        EDIFNetlist dstNetlist = dst.getNetlist();

        // Ensure all alias parent cells are present in the netlist
        for (Entry<Net, Map<Pair<SiteInst, BELPin>, IntraSiteNet>> e : nets.entrySet()) {
            Net net = e.getKey();
            if (net.isStaticNet()) continue;
            for (EDIFHierNet alias : netlist.getNetAliases(net.getLogicalHierNet())) {
                // Macros are excluded because they will pull in leaf cells unnecessarily
                if (!alias.getHierarchicalInst().getCellType().isMacro()) {
                    ensureHierCellInstExists(alias.getHierarchicalInst(), dst);
                }
            }
        }
        
        // Copy all physical nets
        for (Entry<Net, Map<Pair<SiteInst, BELPin>, IntraSiteNet>> e : nets.entrySet()) {
            Net net = e.getKey();
            Set<SitePinInst> pinsToKeep = new HashSet<>();

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

            for (Entry<Pair<SiteInst, BELPin>, IntraSiteNet> e2 : e.getValue().entrySet()) {
                SiteInst si = e2.getKey().getFirst();
                BELPin srcBELPin = e2.getKey().getSecond();
                IntraSiteNet intraSiteNet = e2.getValue();

                // Copy site pins (although they don't exist in DCPs)
                SiteInst dstSiteInst = dst.getSiteInstFromSiteName(si.getSiteName());
                if (dstSiteInst == null) {
                    continue;
                }

                if (srcBELPin.isSitePort()) {
                    if (net.isStaticNet()) {
                        dstNet.createPin(srcBELPin.getName(), dstSiteInst);
                    } else {
                        pinsToKeep.add(si.getSitePinInst(srcBELPin.getName()));
                    }
                }

                for (BELPin sink : intraSiteNet.getSinks()) {
                    dstSiteInst.routeIntraSiteNet(dstNet, srcBELPin, sink);
                }
            }

            if (net.isStaticNet()) {
                // GND & VCC don't need to be replicated, so we'll just re-route it later
                continue;
            }

            // Copy inter-site routing
            Set<SitePinInst> pinsToUnroute = new HashSet<>();
            for (SitePinInst pin : net.getPins()) {
                if (!pin.isOutPin() && !pinsToKeep.contains(pin)) {
                    pinsToUnroute.add(pin);
                }
            }

            if (net.getSource() == null) {
                continue;
            }

            // Correctly trim off MBUFGCE clocks (check all O1-O4 outputs)
            Cell mbufgce = mbufgces.get(net);
            Set<PIP> mbufgAliasPipsToExclude = null;
            if (mbufgce != null) {
                for (int i = 1; i < 5; i++) {
                    String belPinName = "O" + i;
                    String logPinName = mbufgce.getLogicalPinMapping(belPinName);
                    EDIFHierPortInst portInst = mbufgce.getEDIFHierCellInst().getPortInst(logPinName);
                    EDIFHierNet clkAlias = portInst == null ? null : portInst.getHierarchicalNet();
                    Net physClkAlias = clkAlias == null ? null : src.getNet(clkAlias.toString());
                    if (physClkAlias != null && !mbufgces.containsKey(physClkAlias) && net.hasPIPs()
                            && !physClkAlias.hasPIPs()) {
                        // We need to copy over the physical net PIPs to the alias so the trim
                        // algorithm will work
                        physClkAlias.getPIPs().addAll(net.getPIPs());
                        if (mbufgAliasPipsToExclude == null) {
                            mbufgAliasPipsToExclude = DesignTools.getTrimmablePIPsFromPins(
                                    physClkAlias, physClkAlias.getSinkPins());
                        } else {
                            mbufgAliasPipsToExclude.addAll(DesignTools.getTrimmablePIPsFromPins(
                                    physClkAlias, physClkAlias.getSinkPins()));
                        }
                        // Then we return the net back to its previous state
                        physClkAlias.unroute();
                    }
                }
            }

            // Only keep routing connected to the pins that drive a used site pin
            Set<PIP> pipsToExclude = DesignTools.getTrimmablePIPsFromPins(net, pinsToUnroute, mbufgAliasPipsToExclude);
            if (mbufgAliasPipsToExclude != null) {
                pipsToExclude.addAll(mbufgAliasPipsToExclude);
            }
            for (PIP p : net.getPIPs()) {
                if (!pipsToExclude.contains(p)) {
                    dstNet.addPIP(new PIP(p));
                }
            }
        }

        dst.getNetlist().consolidateAllToWorkLibrary(true);

        routeStaticNets(dst);
    }

    public static void connectUndrivenInputsToVCC(Design design) { 
        Net vcc = design.getVccNet();
        for (Cell cell : design.getCells()) {
            SiteInst si = cell.getSiteInst();
            BEL bel = cell.getBEL();
            if (bel == null) continue;
            String[] physPinMappings = cell.getPhysicalPinMappings();
            for (int i=0; i < physPinMappings.length; i++) {
                String logPinName = physPinMappings[i];
                if (logPinName == null) continue;
                BELPin belPin = bel.getPin(i);
                if (belPin.isInput()) {
                    Net net = si.getNetFromSiteWire(belPin.getSiteWireName());
                    if (net == null || net == vcc) {
                        String sitePinName = belPin.getConnectedSitePinName();
                        if (sitePinName == null) {
                            sitePinName = cell.getCorrespondingSitePinName(logPinName);
                        } 
                        if (sitePinName == null) continue;

                        SitePinInst spi = si.getSitePinInst(sitePinName);
                        if (spi == null) {
                            spi = vcc.createPin(sitePinName, si);
                        } else if (spi.getNet() != vcc) {
                            continue;
                        }
                        si.routeIntraSiteNet(vcc, spi.getBELPin(), belPin);
                    }
                }
            }
        }
    }

    public static void routeStaticNets(Design design) {
        DesignTools.updatePinsIsRouted(design);
        DesignTools.createCeSrRstPinsToVCC(design);
        DesignTools.createA1A6ToStaticNets(design);
        // connectUndrivenInputsToVCC(design);

        List<SitePinInst> pins = new ArrayList<>();
        pins.addAll(design.getVccNet().getSinkPins());
        pins.addAll(design.getGndNet().getSinkPins());
        PartialRouter.routeDesignPartialNonTimingDriven(design, pins, false);
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

        extractPath(src, dst, pathPins);

        dst.writeCheckpoint(args[1]);
    }
}
