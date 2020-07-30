package com.xilinx.rapidwright.interchange;

import java.io.FileOutputStream;
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
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;
import org.capnproto.StructList.Builder;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePIPStatus;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysBelPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysPIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.Property;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch.RouteSegment;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.SiteInstance;

public class PhysNetlistWriter {
        
    public static final boolean BUILD_ROUTING_GRAPH_ON_EXPORT = false;
    
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
    
    private static void writePlacement(PhysNetlist.Builder physNetlist, Design design, 
            Enumerator<String> strings) {
        Builder<CellPlacement.Builder> cells = physNetlist.initPlacements(design.getCells().size());
        int i=0;
        for(Cell cell : design.getCells()) {
            CellPlacement.Builder physCell = cells.get(i);
            physCell.setCellName(strings.getIndex(cell.getName()));
            physCell.setType(strings.getIndex(cell.getType()));
            physCell.setSite(strings.getIndex(cell.getSiteName()));
            physCell.setBel(strings.getIndex(cell.getBELName()));
            physCell.setIsBelFixed(cell.isBELFixed());
            physCell.setIsSiteFixed(cell.isSiteFixed());
            Builder<PinMapping.Builder> pinMap = physCell.initPinMap(cell.getPinMappingsL2P().size());
            int j=0; 
            for(Entry<String,String> e : cell.getPinMappingsL2P().entrySet()) {
                PinMapping.Builder pinMapping = pinMap.get(j);
                pinMapping.setBel(strings.getIndex(cell.getBELName()));
                pinMapping.setCellPin(strings.getIndex(e.getKey()));
                pinMapping.setBelPin(strings.getIndex(e.getValue()));
                pinMapping.setIsFixed(cell.isPinFixed(e.getValue()));
                j++;
            }
            i++;
        }        
    }
    
    private static void writePhysNets(PhysNetlist.Builder physNetlist, Design design, 
                                        Enumerator<String> strings) {
    	
    	// Extract out site routing first, for partially routed designs...
    	HashMap<Net, ArrayList<RouteBranchNode>> netSiteRouting = new HashMap<>();
    	for(SiteInst siteInst : design.getSiteInsts()) {
    		Site site = siteInst.getSite();
            for(SitePIP sitePIP : siteInst.getUsedSitePIPs()) {
                String siteWire = sitePIP.getInputPin().getSiteWireName();
                Net net = siteInst.getNetFromSiteWire(siteWire);
                SitePIPStatus status = siteInst.getSitePIPStatus(sitePIP);
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
	                    BELPin[] belPins = site.getBELPins(siteWire);
	                    for(BELPin belPin : belPins) {
	                        if(!belPin.isOutput()) 
	                            continue;
	                        segments.add(new RouteBranchNode(site,belPin));
	                        break;
	                    }
                    }                	
                }
            }
    	}
    	
    	int physNetCount = netSiteRouting.size();
    	// Check if some routes exists without site routing
    	for(Net net : design.getNets()) {
    		if(!netSiteRouting.containsKey(net)) {
    			physNetCount++;
    		}
    	}
    	
    	
        Builder<PhysNet.Builder> nets = physNetlist.initPhysNets(physNetCount);
        int i=0;
        for(Net net : design.getNets()) {
            PhysNet.Builder physNet = nets.get(i);
            physNet.setName(strings.getIndex(net.getName()));
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
            if(net.getName().equals("n216")) {
                System.out.println();
            }
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
                        driverBranch.addBranch(rb);
                    }
                }
            }
            
            // PASS 2: Any nodes not reachable by sources, go onto branches list
            Queue<RouteBranchNode> queue = new LinkedList<>(sources);
            while(!queue.isEmpty()) {
                RouteBranchNode curr = queue.poll();
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
                    // TODO - Check context of net to determine direction
                    physPIP.setForward(true);
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
        
        FileOutputStream fo = new java.io.FileOutputStream(fileName);
        SerializePacked.writeToUnbuffered(fo.getChannel(), message);
        fo.close();
    }
}
