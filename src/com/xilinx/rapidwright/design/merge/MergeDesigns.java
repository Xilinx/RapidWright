package com.xilinx.rapidwright.design.merge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.python.bouncycastle.util.Arrays;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Merges two or more designs into a single Design.  Merge process can be customized through the
 * use of the {@link MergeOptions} class.
 */
public class MergeDesigns {
    
//    private static boolean netsHaveCompatibleSources(EDIFNet net0, EDIFNet net1) {
//        if(net0 == null || net1 == null) return true;
//        EDIFPortInst src0 = net0.getSourcePortInsts(true);
//        
//    }
//    
//    public static boolean canMerge(Design design0, Design design1) {
//        EDIFCell design0Top = design0.getTopEDIFCell();
//        EDIFCell design1Top = design1.getTopEDIFCell();
//
//        // Check ports
//        Map<String,EDIFPort> design1Ports = design1Top.getPortMap();
//        for(Entry<String,EDIFPort> port0Entry : design0Top.getPortMap().entrySet()) {
//            EDIFPort port1 = design1Ports.get(port0Entry.getKey()); 
//            if(port1 != null && port1.getDirection() == port0Entry.getValue().getDirection()) {
//                if(port1.isInput()) {
//                    System.out.println("Matching Input: " + port1);
//                }else if(port1.isOutput() && port1.getLeft() == port0Entry.getValue().getLeft() 
//                                          && port1.getRight() == port0Entry.getValue().getRight()) {
//                    // Outputs both have to have the same source to be merged
//                    for(int i=0; i < port1.getWidth(); i++) {
//                        EDIFNet net0 = port0Entry.getValue().getInternalNet(i);
//                        EDIFNet net1 = port1.getInternalNet(i);
//                        if(!netsHaveEquivalentSource(net0, net1)) {
//                            return false;
//                        }
//                    }
//                }
//            }
//        }
//        
//        // Check nets
//        
//        
//        // Check logical instances
//        
//        // Check conflicting placement
//        
//        // Check conflicting routing
//        
//        
//        
//        return true;
//    }
    
//    private static EDIFPortInst getOtherPortInst(EDIFPortInst portInst) {
//        EDIFNet net = portInst.getNet();
//        for(EDIFPortInst otherPortInst : net.getPortInsts()) {
//            if(otherPortInst == portInst) continue;
//            return otherPortInst;
//        }
//        return null;
//    }
//    
//    private static void mergeNets(Net dest, Net src) {
//        Set<PIP> pips = new HashSet<>(dest.getPIPs());
//        if(src != null) pips.addAll(src.getPIPs());
//        dest.setPIPs(pips);
//    }
//    
//    private static void mergeSiteInsts(SiteInst dst, SiteInst src) {
//        Design design0 = dst.getDesign();
//        boolean modifiedSite = false;
//        for(Cell c : src.getCells()) {
//            Cell dstCell = design0.getCell(c.getName());
//            if(dstCell == null) {
//                EDIFHierCellInst cellInst = design0.getNetlist().getHierCellInstFromName(c.getName());
//                if(cellInst != null && dst.getCell(c.getBEL()) == null) {
//                    dstCell = c.copyCell(cellInst.getFullHierarchicalInstName(), cellInst.getInst(), dst);
//                    dst.addCell(dstCell);
//                    modifiedSite = true;
//                }
//            }
//        }
//        if(modifiedSite) {
//            dst.routeSite();
//        }
//    }
    
    private static Design mergeDesigns(Design design0, Design design1, AbstractDesignMerger merger) {       
        EDIFCell topCell0 = design0.getTopEDIFCell();
        EDIFCell topCell1 = design1.getTopEDIFCell();
        
        design0.getNetlist().migrateCellAndSubCells(topCell1, true);
        
        for(EDIFPort port1 : new ArrayList<>(topCell1.getPorts())) {
            EDIFPort port0 = topCell0.getPort(port1.getBusName());
            if(port0 == null) {
                topCell0.addPort(port1);
            } else {
                merger.mergePorts(port0, port1);
            }
        }
        
        for(EDIFNet net1 : topCell1.getNets()) {
            EDIFNet net0 = topCell0.getNet(net1);
            if(net0 == null) {
                topCell0.addNet(net1);
            } else {
                merger.mergeLogicalNets(net0, net1);
            }
        }
                
        for(EDIFCellInst inst1 : topCell1.getCellInsts()) {
            EDIFCellInst inst0 = topCell0.getCellInst(inst1.getName());
            if(inst0 == null) {
                topCell0.addCellInst(inst1);
            } else {
                merger.mergeCellInsts(inst0, inst1);
            }
        }
        
        for(SiteInst siteInst1 : design1.getSiteInsts()) {
            SiteInst siteInst0 = design0.getSiteInstFromSiteName(siteInst1.getSiteName());
            if(siteInst0 == null) {
                design0.addSiteInst(siteInst1);
            } else {
                merger.mergeSiteInsts(siteInst0, siteInst1);
            }
        }
        
        for(Net net1 : design1.getNets()) {
            Net net0 = design0.getNet(net1.getName());
            if(net0 == null) {
                design0.addNet(net1);
            } else {
                merger.mergePhysicalNets(net0, net1);
            }
        }

        // Merge encrypted cells
        List<String> encryptedCells = design1.getNetlist().getEncryptedCells();
        if(encryptedCells != null && encryptedCells.size() > 0) {
            design0.getNetlist().addEncryptedCells(encryptedCells);
        }          

        design0.getNetlist().removeUnusedCellsFromAllWorkLibraries();
        
        return design0;
    }
    
//    private static Design mergeDesigns(Design design0, Design design1, MergeOptions options) {
//        // Move logical cells (except anchor register duplicates
//        EDIFCell design0Top = design0.getTopEDIFCell();
//        EDIFCell design1Top = design1.getTopEDIFCell();
//        
//        design0.getNetlist().migrateCellAndSubCells(design1Top, true);
//        EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, design0Top, design0.getNetlist());
//        EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, design0Top, design0.getNetlist());
//
//        Map<String,EDIFNet> netMap = new HashMap<>();
//        netMap.put(vcc.getName(), vcc);
//        netMap.put(gnd.getName(), gnd);
//
//        String clkName = options.getClkName(); 
//        EDIFNet clk = null;
//        if(clkName != null) {
//            clk = design0Top.getNet(clkName);
//            netMap.put(clk.getName(), clk);
//        }
//
//        Set<String> cellTypesToSkip = options.getCellTypesToMerge();
//        
//        // Add cell instances (except duplicates)
//        List<String> duplicates = new ArrayList<>();
//        for(EDIFCellInst inst : design1Top.getCellInsts()) {
//            EDIFCellInst duplicate = design0Top.getCellInst(inst.getName());
//            if(duplicate != null) {
//                String duplicateName = duplicate.getCellType().getName();
//                if(!cellTypesToSkip.contains(duplicateName)) {
//                    duplicates.add(inst.getName());
//                }
//            } else {
//                design0Top.addCellInst(inst);
//                for(EDIFPortInst portInst : inst.getPortInsts()) {
//                    if(portInst.isOutput()) continue;
//                    EDIFNet prevNet = portInst.getNet();
//                    if(prevNet == null) {
//                        throw new RuntimeException("ERROR: Unconnected input "
//                                + portInst + " on " + inst );
//                    }
//                    EDIFNet newNet = netMap.get(prevNet.getName());
//                    if(newNet != null) {
//                        newNet.addPortInst(portInst);
//                    }
//                }
//            }
//        }
//        
//        // Handle duplicates
//        Set<String> netsToSkip = options.getNetsToMerge();
//        Set<String> portsToSkip = options.getPortsToMerge();
//        for(String duplicate : duplicates) {
//            EDIFCellInst anchorRegInst = design0Top.getCellInst(duplicate);
//            EDIFPortInst dPort = anchorRegInst.getPortInst("D");
//            EDIFPortInst qPort = anchorRegInst.getPortInst("Q");
//            EDIFPortInst connectedToD = getOtherPortInst(dPort);
//            EDIFPortInst connectedToQ = getOtherPortInst(qPort);
//            
//            EDIFPortInst topPortInst = connectedToQ.isTopLevelPort() ? connectedToQ : 
//                                      (connectedToD.isTopLevelPort() ? connectedToD : null);
//            if(topPortInst == null) {
//                throw new RuntimeException("ERROR: Duplicate cell inst not connected to top level port");
//            }
//            // Remove top-level port and connect to merged inst instead
//            EDIFNet net = topPortInst.getNet();
//            net.removePortInst(topPortInst);
//            design0Top.removePort(topPortInst.getPort());
//            EDIFCellInst design1Anchor = design1Top.getCellInst(duplicate);
//            EDIFPortInst design1AnchorQ = design1Anchor.getPortInst("Q");
//            EDIFPortInst otherSlotQInput = getOtherPortInst(design1AnchorQ);
//            EDIFPortInst design1AnchorD = design1Anchor.getPortInst("D");
//            EDIFPortInst otherSlotDInput = getOtherPortInst(design1AnchorD);
//
//            EDIFPortInst slotPortInst = connectedToQ.isTopLevelPort() ? otherSlotQInput : otherSlotDInput;
//            net.addPortInst(design0Top.getCellInst(slotPortInst.getCellInst().getName()).getPortInst(slotPortInst.getName()));
//
//            portsToSkip.add(topPortInst.getPort().getName());
//            netsToSkip.add(design1AnchorD.getNet().getName());
//            String srcNetName = design1AnchorQ.getNet().getName();
//            netsToSkip.add(srcNetName);
//            Net physNet = design1.getNet(srcNetName);
//            if(physNet.hasPIPs()) {
//                mergeNets(design0.getNet(net.getName()), physNet);
//            }
//        }
//        
//        // Add ports except those connected to duplicates
//        for(EDIFPort port : design1Top.getPorts()) {
//            if(portsToSkip.contains(port.getName())) continue;
//            design0Top.addPort(port);
//        }
//        
//        // Add nets except those connected to duplicates
//        for(EDIFNet net : design1Top.getNets()) {
//            if(netsToSkip.contains(net.getName())) continue;
//            if(net.getPortInsts().size() == 0) continue;
//            EDIFNet existingNet = design0Top.getNet(net.getName());
//            if(existingNet != null) {
//                if(existingNet.getParentCell() == net.getParentCell()) {
//                    continue;
//                } else {
//                    // Check if this is VCC or GND
//                    List<EDIFPortInst> srcs = net.getSourcePortInsts(false);
//                    if(srcs != null && srcs.size() >= 1) {
//                        EDIFCell cell = srcs.get(0).getCellInst().getCellType();
//                        String cellName = cell.getName();
//                        if(cell.isPrimitive() && cellName.equals("VCC") || cellName.equals("GND")) {
//                            EDIFNet destNet = design0Top.getNet(net.getName());
//                            for(EDIFPortInst portInst : new ArrayList<>(net.getPortInsts())) {
//                                portInst.getNet().removePortInst(portInst);
//                                destNet.addPortInst(portInst);
//                            }
//                        }
//                    }                        
//                }
//            }else {
//                design0Top.addNet(net);                    
//            }
//        }
//        
//        // Stitch physical netlist
//        for(SiteInst siteInst : design1.getSiteInsts()) {
//            SiteInst dstSiteInst = design0.getSiteInstFromSite(siteInst.getSite()); 
//            if(dstSiteInst != null) {
//                mergeSiteInsts(dstSiteInst, siteInst);
//                continue;
//            }
//            design0.addSiteInst(siteInst);
//        }
//        
//        for(Net net : design1.getNets()) {
//            if(netsToSkip.contains(net.getName())) continue;
//            if(net.isStaticNet()) continue;
//            design0.addNet(net);
//        }
//        if(clkName != null) {
//            mergeNets(design0.getNet(clkName), design1.getNet(clkName));    
//        }
//        mergeNets(design0.getGndNet(), design1.getGndNet());
//        mergeNets(design0.getVccNet(), design1.getVccNet());
//        
//        // Merge encrypted cells
//        List<String> encryptedCells = design1.getNetlist().getEncryptedCells();
//        if(encryptedCells != null && encryptedCells.size() > 0) {
//            design0.getNetlist().addEncryptedCells(encryptedCells);
//        }          
//
//        design0.getNetlist().removeUnusedCellsFromAllWorkLibraries();
//        return design0;
//    }
    

    public static Design mergeDesigns(Design...designs) {
        MessageGenerator.waitOnAnyKey();
        return mergeDesigns(new DefaultDesignMerger(), designs);
    }

    /**
     * Merges two or more designs together into a single design.  Merges both logical and physical 
     * netlist.  Assumes that designs are compatible for merging. Assumes that if there are duplicate
     * cells in the set of designs to be merged that they are flip-flops and that they are always
     * connected to a top-level port.   
     * @param options The set of options to customize the merge process based on netlist-specific 
     * names
     * @param designs The set of designs to be merged into a single design.
     * @return The merged design that contains the superset of all logic, placement and routing of
     * the input designs.  
     */
    public static Design mergeDesigns(AbstractDesignMerger merger, Design...designs) {
        Design result = null;
        for(Design design : designs) {
            if(result == null) {
                result = design;
            }else {
                result = mergeDesigns(result, design, merger);
            }
        }
        
        result.getNetlist().resetParentNetMap();
        DesignTools.makePhysNetNamesConsistent(result);
        return result;
    }
    
    /**
     * Searches recursively in the given input directory for DCPs and presents the set of those
     * DCPs to MergeDesigns.mergeDesigns() with the default set of options.  
     * @param args [0]=Input directory of source DCPs to search recursively, 
     * [1]=Merged DCP output filename and an optional 
     * [2]=An optional regular expression string to apply to DCPs found in [0]. 
     * @throws InterruptedException
     */
    public static void main(String[] args) throws InterruptedException {
        if(args.length != 2 && args.length != 3) {
            System.out.println("Usage: <dir with DCPs> <merged output DCP filename> [dcp filter regex]");
            return;
        }
        String dcpRegex = args.length == 3 ? args[2] : ".*\\.dcp";
        CodePerfTracker t = new CodePerfTracker("Merge Designs");
  
        Path start = Paths.get(args[0]);
        List<File> dcps = null;
        try (Stream<Path> stream = Files.walk(start, Integer.MAX_VALUE)) {
            dcps = stream
                    .map(p -> p.toFile())
                    .filter(p -> p.isFile() && p.getAbsolutePath().matches(dcpRegex))
                    .collect(Collectors.toList());
        } catch(IOException e) {
            e.printStackTrace();
        }
        System.out.println("Merging DCPs:");
        for(File f : dcps) {
            System.out.println("  " + f.getAbsolutePath());
        }
        
        Design[] designs = new Design[dcps.size()];
        for(int i=0; i < designs.length; i++) {
            t.start("Read DCP " + i);
            designs[i] = Design.readCheckpoint(dcps.get(i).toPath(), CodePerfTracker.SILENT);
            t.stop();
        }

        Design tmp = designs[0];
        designs[0] = designs[1];
        designs[1] = tmp;
        
        t.start("Merge DCPs");
        Design merged = mergeDesigns(designs);
        t.stop().start("Write DCP");
        merged.writeCheckpoint(args[1], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
