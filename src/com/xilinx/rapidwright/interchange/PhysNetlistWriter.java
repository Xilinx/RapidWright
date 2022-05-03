/* 
 * Copyright (c) 2020 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import org.capnproto.MessageBuilder;
import org.capnproto.PrimitiveList;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;
import org.capnproto.StructList.Builder;

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePIPStatus;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.MultiCellPinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysBelPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysCell;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysCellType;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysPIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.Property;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch.RouteSegment;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.SiteInstance;
import com.xilinx.rapidwright.interchange.RouteBranchNode.RouteSegmentType;

public class PhysNetlistWriter {
        
    public static final boolean BUILD_ROUTING_GRAPH_ON_EXPORT = true;
    
    private static void writeSiteInsts(PhysNetlist.Builder physNetlist, Design design, 
            Enumerator<String> strings) {
        Builder<SiteInstance.Builder> siteInsts = physNetlist.initSiteInsts(design.getSiteInsts().size());
        int i=0; 
        for(SiteInst si : design.getSiteInsts()) {
            SiteInstance.Builder siBuilder = siteInsts.get(i);
            siBuilder.setSite(strings.getIndex(si.getSiteName()));
            siBuilder.setType(strings.getIndex(si.getSiteTypeEnum().name()));
            i++;
        }
        
    }
    
    protected static final String LOCKED = "<LOCKED>";
    protected static final String PORT = "<PORT>";
    
    protected static String getUniqueLockedCellName(Cell cell, HashMap<String,PhysCellType> physCells) {
        String cellName = cell.getName();
        if(cellName.equals(LOCKED)) {
        	cellName = cell.getSiteName() + "_" + cell.getBELName() + "_" + LOCKED;
        	physCells.put(cellName,PhysCellType.LOCKED);
        } else if(cell.getType().equals(PORT)) {
        	physCells.put(cellName,PhysCellType.PORT);
        }
        return cellName;
    }
    
    private static void writePlacement(PhysNetlist.Builder physNetlist, Design design, 
            Enumerator<String> strings) {
        HashMap<String,PhysCellType> physCells = new HashMap<>();
        ArrayList<Cell> allCells = new ArrayList<Cell>();
        HashMap<String,ArrayList<Cell>> multiBelCells = new HashMap<>();
        int i=0;
        for(SiteInst siteInst : design.getSiteInsts()) {
        	if(!siteInst.isPlaced()) continue;
            for(Cell cell : siteInst.getCells()) {
            	if(cell.isRoutethru()) {
                    if (!DesignTools.isBELALut(cell.getBELName())) {
                        throw new RuntimeException("Unexpected routethru BEL: " + cell);
                    }
                    continue;
                }
            	allCells.add(cell);
            	if(!cell.isPlaced()) continue;
            	String cellName = cell.getName();
            	if(cellName.equals(PhysNetlistWriter.LOCKED)) continue;
            	if(!design.getCell(cellName).getBELName().equals(cell.getBELName())) {
            		ArrayList<Cell> cells = multiBelCells.get(cellName);
            		if(cells == null) {
            			cells = new ArrayList<Cell>();
            		}
            		// Don't add multi-bel cells, store relevant info in pin placements
            		allCells.remove(allCells.size()-1);
            		cells.add(cell);
            		multiBelCells.put(cellName, cells);
            	}
            }
        }
        
        Builder<CellPlacement.Builder> cells = physNetlist.initPlacements(allCells.size());
        for(Cell cell : allCells) {
        	CellPlacement.Builder physCell = cells.get(i);
            String cellName = getUniqueLockedCellName(cell, physCells);
            physCell.setCellName(strings.getIndex(cellName));
            physCell.setType(strings.getIndex(cell.getType()));
            physCell.setSite(strings.getIndex(cell.getSiteName()));
            String belName = cell.getBELName();
            if(belName != null) {
            	physCell.setBel(strings.getIndex(belName));
            }
            physCell.setIsBelFixed(cell.isBELFixed());
            physCell.setIsSiteFixed(cell.isSiteFixed());
            ArrayList<Cell> otherBels = multiBelCells.get(cell.getName());
            int additionalPinMappings = 0;
            if(otherBels != null) {
            	PrimitiveList.Int.Builder others = physCell.initOtherBels(otherBels.size());
            	int j=0;
            	for(Cell c : otherBels) {
            		additionalPinMappings += c.getPinMappingsP2L().size();
            		if(c.hasAltPinMappings()) {
            			additionalPinMappings += c.getAltPinMappings().size();
            		}
            		others.set(j, strings.getIndex(c.getBELName()));
            		j++;
            	}
            }
            Builder<PinMapping.Builder> pinMap = physCell.initPinMap(cell.getPinMappingsP2L().size()
            	 + additionalPinMappings);
            Integer idx = 0;
            idx = addCellPinMappings(cell, strings, pinMap, 0);
            if(otherBels != null) {
	            for(Cell c : otherBels) {
	                idx = addCellPinMappings(c, strings, pinMap, idx);
	            }
            }
            
            i++;
        }        
        
        // Add PhysCells
        Builder<PhysCell.Builder> physCellBuilders = physNetlist.initPhysCells(physCells.size());
        int j=0;
        for(Entry<String,PhysCellType> e : physCells.entrySet()) {
        	String physCellName = e.getKey();
        	PhysCellType type = e.getValue();
        	PhysCell.Builder physCellBuilder = physCellBuilders.get(j);
        	physCellBuilder.setCellName(strings.getIndex(physCellName));
        	physCellBuilder.setPhysType(type);
        	j++;
        }
    }
    
    private static int addCellPinMappings(Cell cell, Enumerator<String> strings, 
    										Builder<PinMapping.Builder> pinMap, Integer idx) { 
        for(Entry<String,String> e : cell.getPinMappingsP2L().entrySet()) {
            PinMapping.Builder pinMapping = pinMap.get(idx);
            pinMapping.setBel(strings.getIndex(cell.getBELName()));
            pinMapping.setCellPin(strings.getIndex(e.getValue()));
            pinMapping.setBelPin(strings.getIndex(e.getKey()));
            pinMapping.setIsFixed(cell.isPinFixed(e.getKey()));
            idx++;
        } 
        if(cell.hasAltPinMappings()) {
        	for(Entry<String,AltPinMapping> e : cell.getAltPinMappings().entrySet()) {
        		PinMapping.Builder pinMapping = pinMap.get(idx);
                pinMapping.setBel(strings.getIndex(cell.getBELName()));
                pinMapping.setCellPin(strings.getIndex(e.getValue().getLogicalName()));
                pinMapping.setBelPin(strings.getIndex(e.getKey()));
                MultiCellPinMapping.Builder otherCell = pinMapping.getOtherCell();
                otherCell.setMultiCell(strings.getIndex(e.getValue().getAltCellName()));
                otherCell.setMultiType(strings.getIndex(e.getValue().getAltCellType()));
                idx++;
        	}
        }
    	return idx;
    }
    
    private static void writePhysNets(PhysNetlist.Builder physNetlist, Design design, 
                                        Enumerator<String> strings) {
    	
    	// Extract out site routing first, for partially routed designs...
    	HashMap<Net, ArrayList<RouteBranchNode>> netSiteRouting = new HashMap<>();
    	List<RouteBranchNode> nullNetStubs = new ArrayList<>();
    	for(SiteInst siteInst : design.getSiteInsts()) {
    		Site site = siteInst.getSite();
            for(SitePIP sitePIP : siteInst.getUsedSitePIPs()) {
                String siteWire = sitePIP.getInputPin().getSiteWireName();
                Net net = siteInst.getNetFromSiteWire(siteWire);
                if(net == null) {
                	String sitePinName = sitePIP.getInputPin().getConnectedSitePinName();
                	SitePinInst spi = siteInst.getSitePinInst(sitePinName);
                	if(spi != null) {
                		net = spi.getNet();
                	}
                }
                SitePIPStatus status = siteInst.getSitePIPStatus(sitePIP);
                if(net == null) {
                	nullNetStubs.add(new RouteBranchNode(site, sitePIP, status.isFixed()));
                	continue;
                }
                
                ArrayList<RouteBranchNode> segments = netSiteRouting.get(net);
                if(segments == null) {
                	segments = new ArrayList<RouteBranchNode>();
                	netSiteRouting.put(net, segments);
                }
                segments.add(new RouteBranchNode(site, sitePIP, status.isFixed()));
            }
            
            for(Entry<Net,HashSet<String>> e : siteInst.getSiteCTags().entrySet()) {
                ArrayList<RouteBranchNode> segments = netSiteRouting.get(e.getKey());
                if(segments == null) {
                	segments = new ArrayList<RouteBranchNode>();
                	netSiteRouting.put(e.getKey(), segments);
                }
                if(e.getValue() != null && e.getValue().size() > 0) {
                    for(String siteWire : e.getValue()) {
	                    BELPin[] belPins = siteInst.getSiteWirePins(siteWire);
	                    for(BELPin belPin : belPins) {
	                        BEL bel = belPin.getBEL();
	                        Cell cell = siteInst.getCell(bel);
	                        boolean routethru = false;
	                        if(belPin.isInput()) {
	                            // Skip if no BEL placed here
	                            if (cell == null) {
	                                continue;
	                            }
	                            // Skip if pin not used (e.g. A1 connects to A[56]LUT.A1;
	                            // both cells can exist but not both need be using this pin)
	                            if (cell.getLogicalPinMapping(belPin.getName()) == null) {
	                                continue;
	                            }
                            } else {
                                routethru = cell != null && cell.isRoutethru();
                            }
                            segments.add(new RouteBranchNode(site, belPin, routethru));
	                    }
                    }                	
                }
            }
    	}
    	
    	PhysNet.Builder nullNet = physNetlist.getNullNet();
    	Builder<RouteBranch.Builder> stubs = nullNet.initStubs(nullNetStubs.size());
    	int i=0;
    	for(RouteBranchNode node : nullNetStubs) {
    		RouteBranch.Builder stub = stubs.get(i);
    		PhysSitePIP.Builder physSitePIP = stub.initRouteSegment().initSitePIP();
            SiteSitePIP sitePIP = node.getSitePIP();
            physSitePIP.setSite(strings.getIndex(sitePIP.site.getName()));
            physSitePIP.setBel(strings.getIndex(sitePIP.sitePIP.getBELName()));
            physSitePIP.setPin(strings.getIndex(sitePIP.sitePIP.getInputPinName()));
            physSitePIP.setIsFixed(sitePIP.isFixed);
    		i++;
    	}
    	
    	int physNetCount = netSiteRouting.size();
    	// Check if some routes exists without site routing
    	for(Net net : design.getNets()) {
    		if(!netSiteRouting.containsKey(net)) {
    			physNetCount++;
    		}
    	}
    	
    	
        Builder<PhysNet.Builder> nets = physNetlist.initPhysNets(physNetCount);
        i=0;
        for(Net net : design.getNets()) {
            PhysNet.Builder physNet = nets.get(i);
            physNet.setName(strings.getIndex(net.getName()));
            switch (net.getType()) {
            	case GND:
            		physNet.setType(PhysNetlist.NetType.GND);
            		break;
            	case VCC:
            		physNet.setType(PhysNetlist.NetType.VCC);
            		break;            		
            	default:
            		physNet.setType(PhysNetlist.NetType.SIGNAL);
            }
            
            // We need to traverse the net inside sites to fully populate routing spec
            ArrayList<RouteBranchNode> routingSources = new ArrayList<>();
            for(PIP p : net.getPIPs()) {
                routingSources.add(new RouteBranchNode(p));
            }
            for(SitePinInst spi : net.getPins()) {
                routingSources.add(new RouteBranchNode(spi));
            }
            ArrayList<RouteBranchNode> segments = netSiteRouting.remove(net);
            if(segments != null) routingSources.addAll(segments);
            populateRouting(routingSources, physNet, strings);
            i++;
        }

        // Clean up any nets not found in design that were stored in site routing
        for(Entry<Net,ArrayList<RouteBranchNode>> e : netSiteRouting.entrySet()) {
        	PhysNet.Builder physNet = nets.get(i);
        	physNet.setName(strings.getIndex(e.getKey().getName()));
        	populateRouting(e.getValue(), physNet, strings);
        	i++;
        }
    }
    
    
    @SuppressWarnings("unused")
    private static void debugPrintRouteBranchNodes(List<RouteBranchNode> nodes, String prefix) {
        for(RouteBranchNode n : nodes) {
            System.out.println(prefix + n.toString());
            debugPrintRouteBranchNodes(n.getBranches(), prefix + "  ");
        }
    }
    
    private static void populateRouting(ArrayList<RouteBranchNode> routingBranches, 
                                        PhysNet.Builder physNet, Enumerator<String> strings) {

        List<RouteBranchNode> sources = new ArrayList<>();
        List<RouteBranchNode> stubs = new ArrayList<>();
        
        if(BUILD_ROUTING_GRAPH_ON_EXPORT) {
            Map<String, RouteBranchNode> map = new HashMap<>();
            for(RouteBranchNode rb : routingBranches) {
                map.put(rb.toString(), rb);
            }
            
            // PASS 1: Connect drivers of each branch, put sources on source list
            for(RouteBranchNode rb : routingBranches) {
                if(rb.isSource()) {
                    sources.add(rb);
                } else {
                    for(String driver : rb.getDrivers()) {
                        RouteBranchNode driverBranch = map.get(driver);
                        if(driverBranch == null) continue;
                        if(driverBranch.getType() == RouteSegmentType.PIP) {
                            PIP pip = driverBranch.getPIP();
                            if(pip.isBidirectional() && rb.getType() == RouteSegmentType.PIP) {
                                PIP curr = rb.getPIP();
                                Node driverNode = pip.isReversed() ? 
                                                  pip.getStartNode() : pip.getEndNode();
                                if(!curr.getStartNode().equals(driverNode)) {
                                    continue;
                                }
                            }
                        }
                        driverBranch.addBranch(rb);
                    }
                }
            }
            
            // PASS 2: Any nodes not reachable by sources, go onto stubs list
            Queue<RouteBranchNode> queue = new LinkedList<>(sources);
            while(!queue.isEmpty()) {
                RouteBranchNode curr = queue.poll();
                if(curr.hasBeenVisited()) {
                    continue;
                }
                curr.setVisited(true);
                map.remove(curr.toString());
                queue.addAll(curr.getBranches());
            }
            for(RouteBranchNode rb : map.values()) {
                if(rb.getParent() == null) {
                    stubs.add(rb);
                }
            }
        } else {
            stubs = routingBranches;
        }
        
        //if(strings.get(physNet.getName()).equals("")) debugPrintRouteBranchNodes(sources, "");
        
        // Serialize...
        Builder<RouteBranch.Builder> routeSrcs = physNet.initSources(sources.size());
        for(int i=0; i < sources.size(); i++) {
            RouteBranch.Builder srcBuilder = routeSrcs.get(i);
            RouteBranchNode src = sources.get(i);
            writeRouteBranch(srcBuilder, src, strings);
        }
        Builder<RouteBranch.Builder> routeStubs = physNet.initStubs(stubs.size());
        for(int i=0; i < stubs.size(); i++) {
            RouteBranch.Builder stubBuilder = routeStubs.get(i);
            RouteBranchNode src = stubs.get(i);
            writeRouteBranch(stubBuilder, src, strings);
        }
    }

    private static void writeRouteBranch(RouteBranch.Builder srcBuilder, RouteBranchNode src, 
                                            Enumerator<String> strings) {
        RouteSegment.Builder segment = srcBuilder.getRouteSegment();
        switch(src.getType()) {
            case PIP:{
                PIP pip = src.getPIP();
                PhysPIP.Builder physPIP = segment.initPip();
                physPIP.setTile(strings.getIndex(pip.getTile().getName()));
                physPIP.setWire0(strings.getIndex(pip.getStartWireName()));
                physPIP.setWire1(strings.getIndex(pip.getEndWireName()));
                physPIP.setIsFixed(pip.isPIPFixed());
                if(pip.isBidirectional()) {
                    physPIP.setForward(!pip.isReversed());
                }
                break;
            }
            case BEL_PIN:{
                SiteBELPin sbp = src.getBELPin();
                PhysBelPin.Builder physPin = segment.initBelPin();
                physPin.setBel(strings.getIndex(sbp.belPin.getBEL().getName()));
                physPin.setPin(strings.getIndex(sbp.belPin.getName()));
                physPin.setSite(strings.getIndex(sbp.site.getName()));
                break;
            }
            case SITE_PIN:{
                SitePinInst spi = src.getSitePin();
                PhysSitePin.Builder physSitePin = segment.initSitePin();
                physSitePin.setSite(strings.getIndex(spi.getSite().getName()));
                physSitePin.setPin(strings.getIndex(spi.getName()));
                break;
            }
            case SITE_PIP: {
                SiteSitePIP sitePIP = src.getSitePIP();
                PhysSitePIP.Builder physSitePIP = segment.initSitePIP();
                physSitePIP.setSite(strings.getIndex(sitePIP.site.getName()));
                physSitePIP.setBel(strings.getIndex(sitePIP.sitePIP.getBELName()));
                physSitePIP.setPin(strings.getIndex(sitePIP.sitePIP.getInputPinName()));
                physSitePIP.setIsFixed(sitePIP.isFixed);
                break;
            }
            default:
                throw new RuntimeException("Unhandled class in routing representation: " +
                        src.getType());
        }
        int size = src.getBranches().size();
        Builder<RouteBranch.Builder> branches = srcBuilder.initBranches(size);
        for(int i=0; i < size; i++) {
            writeRouteBranch(branches.get(i), src.getBranch(i), strings);
        }
    }
    
    private static void writeDesignProperties(PhysNetlist.Builder physNetlist, Design design, 
                                                Enumerator<String> strings) {
        StructList.Builder<Property.Builder> props = physNetlist.initProperties(2);
        Property.Builder autoIOs = props.get(0);
        autoIOs.setKey(strings.getIndex(PhysNetlistReader.DISABLE_AUTO_IO_BUFFERS));
        autoIOs.setValue(strings.getIndex(design.isAutoIOBuffersSet() ? "0" : "1"));
        
        Property.Builder ooc = props.get(1);
        ooc.setKey(strings.getIndex(PhysNetlistReader.OUT_OF_CONTEXT));
        ooc.setValue(strings.getIndex(design.isDesignOutOfContext() ? "1" : "0"));        
    }
    
    private static void writeStrings(PhysNetlist.Builder physNetlist, Enumerator<String> strings) {
        TextList.Builder strList = physNetlist.initStrList(strings.size());
        int stringCount = strList.size();
        for(int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(strings.get(i)));
        }              
    }
    
    public static void writePhysNetlist(Design design, String fileName) throws IOException {
        MessageBuilder message = new MessageBuilder();
        PhysNetlist.Builder physNetlist = message.initRoot(PhysNetlist.factory);
        Enumerator<String> strings = new Enumerator<>();

        physNetlist.setPart(design.getPartName());
        
        writeSiteInsts(physNetlist, design, strings);
        
        writePlacement(physNetlist, design, strings);
        
        writePhysNets(physNetlist, design, strings);
        
        writeDesignProperties(physNetlist, design, strings);
        
        writeStrings(physNetlist, strings);
        
        Interchange.writeInterchangeFile(fileName, message);
    }
}
