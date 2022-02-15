/*
 * Copyright (c) 2022 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
package com.xilinx.rapidwright.design.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Provides a basic design merging behavior when merging designs.  If no other design merger is 
 * implemented, {@link MergeDesigns#mergeDesigns(com.xilinx.rapidwright.design.Design...)} is used
 * by default. 
 */
public class DefaultDesignMerger extends AbstractDesignMerger {

    private Map<String, String> replacedNets = new HashMap<>();
    
    private EDIFPortInst getSingleSource(EDIFNet net) {
        List<EDIFPortInst> srcs = net.getSourcePortInsts(true);
        if(srcs.size() == 0) return null;
        if(srcs.size() == 1) return srcs.get(0);
        throw new RuntimeException("ERROR: Net "+ net +" has more than one source!");
    }
    
    private boolean checkIfNetSourcesMergeCompatible(EDIFNet n0, EDIFNet n1) {
        List<EDIFPortInst> srcs0 = n0.getSourcePortInsts(true);
        List<EDIFPortInst> srcs1 = n1.getSourcePortInsts(true);
        
        if(srcs0.size() != 1 || srcs1.size() != 1) {
            return false;
        }
        
        if(!srcs0.get(0).getFullName().equals(srcs1.get(0).getFullName())) {
            if(!srcs0.get(0).isTopLevelPort() && !srcs1.get(0).isTopLevelPort()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void mergePorts(EDIFPort p0, EDIFPort p1) {
        if(!p0.isBusRangeEqual(p1)) {
            // TODO - Perhaps there are future use cases where disjoint ranges could be 
            // merged, but we'll leave that exercise for another day
            throw new RuntimeException("ERROR: Port range mismatch " + p0.getName() 
                + " and " + p1.getName());
        }
        
        if(p0.getDirection() != p1.getDirection()) {
            boolean p0IsOutput = p0.isOutput();
            // Two ports with same name but opposite direction, plan to remove both and connect
            List<EDIFNet> nets0 = p0.getInternalNets();
            List<EDIFNet> nets1 = p1.getInternalNets();
            ArrayList<EDIFPortInst> toRemove = new ArrayList<>();
            for(int i=0; i < nets0.size(); i++) {
                EDIFNet net0 = nets0.get(i);
                EDIFNet net1 = nets1.get(i);
                List<EDIFPortInst> toSwitch = new ArrayList<>();
                for(EDIFPortInst portInst0 : net0.getPortInsts()) {
                    if(portInst0.isTopLevelPort() && portInst0.getPort() == p0) {
                        toRemove.add(portInst0);
                    }else if(!p0IsOutput) {
                        toSwitch.add(portInst0);
                        // Update site routing if net is not the same name
                        if(!net0.getName().equals(net1.getName()) && portInst0.isInput()) {
                            replacedNets.put(net0.getName(), net1.getName());
                        }
                    }
                }
                for(EDIFPortInst pi : toSwitch) {
                    net0.removePortInst(pi);
                    net1.addPortInst(pi);
                }
                toSwitch.clear();

                for(EDIFPortInst portInst1 : net1.getPortInsts()) {
                    if(portInst1.isTopLevelPort() && portInst1.getPort() == p1) {
                        toRemove.add(portInst1);
                    }else if(p0IsOutput) {
                        toSwitch.add(portInst1);
                    }
                }
                for(EDIFPortInst pi : toSwitch) {
                    net1.removePortInst(pi);
                    net0.addPortInst(pi);
                }
                toSwitch.clear();
            }
            
            for(EDIFPortInst remove : toRemove) {
                EDIFNet net = remove.getNet(); 
                net.removePortInst(remove);
                if(net.getPortInsts().size() == 0) {
                    net.getParentCell().removeNet(net);
                }
            }
            
            
            p0.getParentCell().removePort(p0);
            p1.getParentCell().removePort(p1);
            return;
        }
        if(p0.isOutput() && p1.isOutput()) {
            List<EDIFNet> nets0 = p0.getInternalNets();
            List<EDIFNet> nets1 = p1.getInternalNets();
            for(int i=0; i < nets0.size(); i++) {
                if(!checkIfNetSourcesMergeCompatible(nets0.get(i), nets1.get(i))) {
                    throw new RuntimeException("ERROR: Unable to merge output port " + p0 
                            + " incompatible source on driving nets");
                }
            }
            
            throw new RuntimeException("ERROR: Unable to merge port " + p0 + ", duplicate output "
                    + "with different direction");
        }
        
        for(EDIFNet net : p1.getInternalNets()) {
            for(EDIFPortInst portInst: net.getPortInsts()) {
                if(portInst.getPort() == p1) {
                   portInst.setPort(p0); 
                }
            }
        }
    }

    @Override
    public void mergeLogicalNets(EDIFNet n0, EDIFNet n1) {
        if(!checkIfNetSourcesMergeCompatible(n0, n1)) {
            throw new RuntimeException("ERROR: Uncompatible nets to merge: " + n0);
        }
        // Remove top-level port if one source already got copied
        EDIFPortInst src0 = getSingleSource(n0);
        EDIFPortInst src1 = getSingleSource(n1);
        EDIFPortInst nonTopLevelPortSrc = (src0 != null && !src0.isTopLevelPort()) ? 
                        src0 : ((src1 != null && !src1.isTopLevelPort()) ? src1 : null);
        EDIFPortInst topLevelPortSrc = (src0 != null && src0.isTopLevelPort()) ? 
                        src0 : ((src1 != null && src1.isTopLevelPort()) ? src1 : null);
        // We are merging a net where one instance has a top level port source and the other
        // has a real source.  Move all sinks to the real source and discard the top level port
        if(nonTopLevelPortSrc != null && topLevelPortSrc != null) {
            EDIFNet portNet = topLevelPortSrc.getNet();
            portNet.removePortInst(topLevelPortSrc);
            n0.getParentCell().removePort(topLevelPortSrc.getPort());
            // Swap references so sinks land on the proper net
            if(portNet == n0) {
                EDIFNet tmp = n1;
                n1 = n0;
                n0 = tmp;
            }
        }
        
        for(EDIFPortInst p1 : new ArrayList<>(n1.getPortInsts())) {
            if(p1.isOutput() || (p1.isTopLevelPort() && p1.isInput())) continue;
            if(n0.getPortInst(p1.getCellInst(), p1.getName()) == null) {
                n1.removePortInst(p1);
                n0.addPortInst(p1);
            }
        }
    }
    
    @Override
    public void mergeCellInsts(EDIFCellInst i0, EDIFCellInst i1) {
        if(!i0.getCellType().getName().equals(i1.getCellType().getName())) {
            throw new RuntimeException("ERROR: Cell type mismatch for instance " + i0.getName());
        }
        for(EDIFPortInst portInst1 : i1.getPortInsts()) {
            EDIFPortInst portInst0 = i0.getPortInst(portInst1.getName());
            
            // If portInst0 is null, prioritize portInst1
            if(portInst0 == null) {
                portInst0 = new EDIFPortInst(i0.getPort(portInst1.getPort().getBusName()), 
                        portInst1.getNet(), portInst1.getIndex(), i0);
                continue;
            }
            if(portInst0.getDirection() != portInst1.getDirection()) {
                throw new RuntimeException("ERROR: Mismatched port directions on cell type " 
                        + i1.getCellType());
            }
            EDIFNet net0 = portInst0.getNet();
            EDIFNet net1 = portInst1.getNet();
            if(net0.getParentCell() == net1.getParentCell()) {
                // This pin has already been moved over from a duplicate port, it should be removed
                if(net0.getName().equals(net1.getName())){
                    net1.removePortInst(portInst1);
                }else {
                    net0.removePortInst(portInst0);
                }
                continue;
            }
            
            if(portInst1.isInput()) {
                boolean unconnected = false;
                List<EDIFPortInst> srcs = net1.getSourcePortInsts(true);
                if(srcs.size() > 1) throw new RuntimeException("ERROR: Unhandled multi-driver case");
                unconnected = srcs.size() == 0 || (srcs.size() == 1 && srcs.get(0).isTopLevelPort());
                if(unconnected || net0.getName().equals(net1.getName())) {
                    // Leave net0 intact and don't merge or copy 
                } else {
                    portInst0.setParentNet(net1);
                }
                
            } else if(portInst1.isOutput()) {
                boolean allSinksTopLevel = true;
                for(EDIFPortInst sink : net1.getPortInsts()) {
                    if(portInst1 == sink) continue;
                    if(!sink.isTopLevelPort()) {
                        allSinksTopLevel = false;
                    }
                }
                if(allSinksTopLevel || net0.getName().equals(net1.getName())) {
                    // Leave net0 intact, don't merge or copy
                } else {
                    portInst0.setParentNet(net1);
                }
            } else {
                throw new RuntimeException("ERROR: Unhandled case " + portInst1.getDirection());
            }
        }
    }

    @Override
    public void mergeSiteInsts(SiteInst s0, SiteInst s1) {
        boolean modifiedSite = false;
        for(Cell c : s1.getCells()) {
            Cell dstCell = s0.getDesign().getCell(c.getName());
            if(dstCell == null) {
                EDIFHierCellInst cellInst = s0.getDesign().getNetlist().getHierCellInstFromName(c.getName());
                if(cellInst != null && s0.getCell(c.getBEL()) == null) {
                    dstCell = c.copyCell(cellInst.getFullHierarchicalInstName(), cellInst.getInst(), s0);
                    s0.addCell(dstCell);
                    modifiedSite = true;
                }
            }
        }
        
        for(Net net : new ArrayList<>(s0.getNetSiteWireMap().values())) {
            String newNetName = replacedNets.get(net.getName());
            if(newNetName != null) {
                Net newNet = s0.getDesign().getNet(newNetName);
                for(String siteWire : new ArrayList<>(s0.getSiteWiresFromNet(net))) {
                    BELPin[] pins = s0.getSiteWirePins(siteWire);
                    BELPin siteWirePin = pins[0];
                    s0.unrouteIntraSiteNet(siteWirePin, siteWirePin);
                    s0.routeIntraSiteNet(newNet, siteWirePin, siteWirePin);
                }
            }
        }
        
        if(modifiedSite) {
            s0.routeSite();
        }
    }

    @Override
    public void mergePhysicalNets(Net n0, Net n1) {
        Set<PIP> pips = new HashSet<>(n0.getPIPs());
        if(n1 != null) pips.addAll(n1.getPIPs());
        n0.setPIPs(pips);
    }


}
