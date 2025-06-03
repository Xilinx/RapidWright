/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.python.google.common.hash.HashFunction;
import org.python.google.common.hash.Hashing;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Flattens a design and obfuscates all of the names to SHA256 hashes. Note that
 * the default constructor uses the current time to pepper the inputs to ensure
 * that the obfuscated output is different each time the application is run.
 * This behavior can be overridden by using the DesignObfuscator(String) constructor.
 */
public class DesignObfuscator {

    private String timeStamp;
    
    private Map<String, String> obfuscatedMap;
    
    private HashFunction sha256;
    
    private EDIFLibrary macros;

    /**
     * Creates a design obfuscator object to obfuscate the names in a design.
     */
    public DesignObfuscator() {
        timeStamp = Long.toHexString(System.nanoTime() * System.nanoTime());
        obfuscatedMap = new HashMap<>();
        sha256 = Hashing.sha256();
        dontObfuscate("VCC");
        dontObfuscate("GND");
        dontObfuscate("<const0>");
        dontObfuscate("<const1>");
        dontObfuscate(Net.GND_NET);
        dontObfuscate(Net.VCC_NET);
        dontObfuscate(Net.USED_NET);
        dontObfuscate(Net.Z_NET);
    }

    /**
     * Create a design obfuscator with a custom pepper string.
     * 
     * @param pepper The string combined with names to be hashed.
     */
    public DesignObfuscator(String pepper) {
        this();
        this.timeStamp = pepper;
    }

    /**
     * Specify a name that exists in the netlist to preserve.
     * 
     * @param preserve The name to preserve and won't be obfuscated.
     */
    public void dontObfuscate(String preserve) {
        obfuscatedMap.put(preserve, preserve);
    }

    public String hash(String name) {
        return obfuscatedMap.computeIfAbsent(name,
                s -> sha256.hashString(s + timeStamp, StandardCharsets.UTF_8).toString());
    }

    private String getObfuscatedNetName(Net net) {
        EDIFHierNet hierNet = net.getLogicalHierNet();
        String oNetName = null;
        // Don't change the names of nets inside macros
        if (hierNet != null && macros.containsCell(hierNet.getHierarchicalInst().getCellType())) {
            // Check for two-level-deep macros
            if (macros.containsCell(hierNet.getHierarchicalInst().getParent().getCellType())) {
                oNetName = hash(hierNet.getHierarchicalInst().getParent().getFullHierarchicalInstName())
                        + EDIFTools.EDIF_HIER_SEP + hierNet.getHierarchicalInst().getInst().getName()
                        + EDIFTools.EDIF_HIER_SEP + hierNet.getNet().getName();
            } else {
                oNetName = hash(hierNet.getHierarchicalInstName()) 
                        + EDIFTools.EDIF_HIER_SEP + hierNet.getNet().getName();
            }
            obfuscatedMap.put(net.getName(), oNetName);
        } else {
            oNetName = hash(net.getName());
        }
        return oNetName;
    }

    private String getObfuscatedCellName(Cell cell) {
        String oCellName = null;
        EDIFCell parent = cell.getParentCell();

        if (parent != null && macros.containsCell(parent)) {
            // This is a macro primitive element, preserve inner macro instance name
            EDIFHierCellInst parentInst = cell.getEDIFHierCellInst().getParent();
            // Check for two-level-deep macros
            if (macros.containsCell(parentInst.getParent().getCellType())) {
                oCellName = hash(parentInst.getParent().getFullHierarchicalInstName()) 
                        + EDIFTools.EDIF_HIER_SEP + parentInst.getInst().getName() 
                        + EDIFTools.EDIF_HIER_SEP + cell.getEDIFHierCellInst().getInst().getName();
            } else {
                oCellName = hash(parentInst.getFullHierarchicalInstName()) 
                        + EDIFTools.EDIF_HIER_SEP + cell.getEDIFHierCellInst().getInst().getName();
            }
            obfuscatedMap.put(cell.getName(), oCellName);
        } else {
            if (parent == null && cell.isRoutethru()) {
                // These names are physical-dependent, no need to obfuscate
                oCellName = cell.getName();
                obfuscatedMap.put(oCellName, oCellName);
            } else {
                oCellName = hash(cell.getName());
            }
        }
        return oCellName;
    }

    /**
     * Creates a new design that mirrors the provided design in physical
     * implementation, but completely flattens logical hierarchy and obfuscates all
     * logical names.
     * 
     * @param d The original design
     * @return The flattened, obfuscated design
     */
    public Design obfuscateDesign(Design d) {
        // Flatten netlist first to remove all hierarchy (design macros are collapsed)
        EDIFNetlist flatNetlist = EDIFTools.createFlatNetlist(d.getNetlist(), d.getPartName());
        macros = Design.getMacroPrimitives(d.getSeries());
        
        EDIFNetlist obfuscatedNetlist = EDIFTools.createNewNetlist("design");
        EDIFTools.ensureCorrectPartInEDIF(obfuscatedNetlist, d.getPartName());
        
        EDIFCell obfuscatedTop = obfuscatedNetlist.getTopCell();
        EDIFLibrary obfuscatedPrimLib = obfuscatedNetlist.getHDIPrimitivesLibrary();

        for (EDIFCellInst inst : flatNetlist.getTopCell().getCellInsts()) {
            String obfuscatedName = hash(inst.getName());
            EDIFCellInst oInst = new EDIFCellInst(obfuscatedName, inst.getCellType(), obfuscatedTop);
            oInst.setPropertiesMap(inst.createDuplicatePropertiesMap());
            obfuscatedTop.addCellInst(oInst);
            if (!obfuscatedPrimLib.containsCell(inst.getCellType())) {
                obfuscatedPrimLib.addCell(inst.getCellType());
            }
        }
        
        for (EDIFPort port : flatNetlist.getTopCell().getPorts()) {
            String obfuscatedPortName = hash(port.getBusName()) + port.getBusRange();
            obfuscatedTop.createPort(obfuscatedPortName, port.getDirection(), port.getWidth());
        }

        for (EDIFNet net : flatNetlist.getTopCell().getNets()) {
            String obfuscatedName = hash(net.getName());
            EDIFNet oNet = obfuscatedTop.createNet(obfuscatedName);
            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst inst = portInst.getCellInst();
                if (inst == null) {
                    EDIFPort oPort = obfuscatedTop.getPort(hash(portInst.getPort().getBusName()));
                    if (portInst.getPort().isBus()) {
                        oNet.createPortInst(oPort, portInst.getIndex());
                    } else {
                        oNet.createPortInst(oPort);
                    }
                } else {
                    EDIFCellInst oInst = obfuscatedTop.getCellInst(hash(inst.getName()));
                    if (portInst.getPort().isBus()) {
                        oNet.createPortInst(portInst.getPort().getBusName(), portInst.getIndex(), oInst);
                    } else {
                        oNet.createPortInst(portInst.getPort().getName(), oInst);
                    }
                }
            }
        }
        
        // We need to re-expand the macros for proper placement and routing
        // representation
        obfuscatedNetlist.expandMacroUnisims(d.getSeries());

        // Create a clean new design, copy over some flags
        Design obfuscatedDesign = new Design(obfuscatedNetlist);
        obfuscatedDesign.setAdvancedFlow(d.isAdvancedFlow());
        obfuscatedDesign.setAutoIOBuffers(d.isAutoIOBuffersSet());
        obfuscatedDesign.setDesignOutOfContext(d.isDesignOutOfContext());
        
        // Transfer routing information
        for (Net net : d.getNets()) {
            String obfuscatedName = getObfuscatedNetName(net);
            Net obfuscatedNet = obfuscatedDesign.createNet(obfuscatedName);
            obfuscatedNet.setPIPs(net.getPIPs());
            for (SitePinInst spi : net.getPins()) {
                SiteInst oSI = obfuscatedDesign.getSiteInstFromSite(spi.getSite());
                if (oSI == null) {
                    SiteInst si = spi.getSiteInst();
                    oSI = obfuscatedDesign.createSiteInst(si.getName(), si.getSiteTypeEnum(), si.getSite());
                }
                SitePinInst oSPI = new SitePinInst(spi.getName(), oSI);
                obfuscatedNet.addPin(oSPI, false);
            }
        }
        
        // Transfer placement information
        for (SiteInst si : d.getSiteInsts()) {
            SiteInst oSI = obfuscatedDesign.getSiteInstFromSite(si.getSite());
            if (oSI == null) {
                oSI = obfuscatedDesign.createSiteInst(si.getName(), si.getSiteTypeEnum(), si.getSite());
            }
            oSI.setSiteLocked(si.isSiteLocked());
            for (Cell c : si.getCells()) {
                String oCellName = getObfuscatedCellName(c);
                Cell oCell = c.copyCell(oCellName, null, oSI);
                if (oCell.isRoutethru()) {
                    oSI.getCellMap().put(oCellName, oCell);
                } else {
                    oSI.addCell(oCell);
                }

            }
            for (SitePIP p : si.getUsedSitePIPs()) {
                oSI.addSitePIP(p);
            }
            for (Entry<Net, List<String>> e : si.getNetToSiteWiresMap().entrySet()) {
                String oNetName = getObfuscatedNetName(e.getKey());
                Net oNet = obfuscatedDesign.getNet(oNetName);
                if (oNet == null) {
                    oNet = obfuscatedDesign.createNet(oNetName);
                }
                for (String siteWireName : e.getValue()) {
                    // TODO
                    // oSI.routeIntraSiteNet(oNet, siteWireName);
                    BELPin[] pins = si.getSiteWirePins(siteWireName);
                    if (pins.length == 0) {
                        continue;
                    }
                    BELPin pin = pins[0];
                    oSI.routeIntraSiteNet(oNet, pin, pin);
                }
            }
        }
        
        // Check if constraints are present, warn user they won't be propagated
        boolean hasConstraints = false;
        for (ConstraintGroup cg : ConstraintGroup.values()) {
            if (d.getXDCConstraints(cg).size() > 0) {
                hasConstraints = true;
                break;
            }
        }
        if (hasConstraints) {
            System.out.println("WARNING: Design contains XDC constraints which will not be present in the obfuscated design.");
        }
        if (d.getPartitionPins().size() > 0) {
            // TODO support partition pins
            System.out.println("WARNING: Design contains partition which will not be present in the obfuscated design.");
        }
        
        return obfuscatedDesign;
    }
    
    public void writeObfuscationMapFile(String fileName) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileName))) {
            for (Entry<String, String> e : obfuscatedMap.entrySet()) {
                bw.write(e.getValue() + " " + e.getKey() + "\n");
            }
        } catch (IOException e1) {
            throw new UncheckedIOException(e1);
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("USAGE: <input.dcp> <output.dcp> [obfuscation_map.txt]");
            return;
        }
        Design d = Design.readCheckpoint(args[0]);
        DesignObfuscator o = new DesignObfuscator();
        Design obfuscated = o.obfuscateDesign(d);
        obfuscated.writeCheckpoint(args[1]);
        
        if (args.length == 3) {
            o.writeObfuscationMapFile(args[2]);
        }
    }
}
