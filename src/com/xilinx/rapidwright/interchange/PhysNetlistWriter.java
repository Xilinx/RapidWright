package com.xilinx.rapidwright.interchange;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

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
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteSrc;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.SiteInstance;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteSrc.RouteSegment;

public class PhysNetlistWriter {
        
    
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
    	HashMap<Net, ArrayList<Object>> netSiteRouting = new HashMap<>();
    	for(SiteInst siteInst : design.getSiteInsts()) {
    		Site site = siteInst.getSite();
            for(SitePIP sitePIP : siteInst.getUsedSitePIPs()) {
                String siteWire = sitePIP.getInputPin().getSiteWireName();
                Net net = siteInst.getNetFromSiteWire(siteWire);
                SitePIPStatus status = siteInst.getSitePIPStatus(sitePIP);
                ArrayList<Object> segments = netSiteRouting.get(net);
                if(segments == null) {
                	segments = new ArrayList<Object>();
                	netSiteRouting.put(net, segments);
                }
                segments.add(new SiteSitePIP(site, sitePIP, status.isFixed()));
            }
            
            for(Entry<Net,HashSet<String>> e : siteInst.getSiteCTags().entrySet()) {
                ArrayList<Object> segments = netSiteRouting.get(e.getKey());
                if(segments == null) {
                	segments = new ArrayList<Object>();
                	netSiteRouting.put(e.getKey(), segments);
                }
                if(e.getValue() != null && e.getValue().size() > 0) {
                    for(String siteWire : e.getValue()) {
	                    BELPin[] belPins = site.getBELPins(siteWire);
	                    for(BELPin belPin : belPins) {
	                        if(!belPin.isOutput()) 
	                            continue;
	                        segments.add(new SiteBELPin(site,belPin));
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
            ArrayList<Object> routingSources = new ArrayList<>(net.getPIPs()); 
            for(SitePinInst spi : net.getPins()) {
                routingSources.add(spi);
            }
            ArrayList<Object> segments = netSiteRouting.remove(net);
            if(segments != null) routingSources.addAll(segments);
            
            populateRouting(routingSources, physNet, strings);
            i++;
        }

        // Clean up any nets not found in design that were stored in site routing
        for(Entry<Net,ArrayList<Object>> e : netSiteRouting.entrySet()) {
        	PhysNet.Builder physNet = nets.get(i);
        	physNet.setName(strings.getIndex(e.getKey().getName()));
        	populateRouting(e.getValue(), physNet, strings);
        	i++;
        }
    }

    private static void populateRouting(ArrayList<Object> routingSources, PhysNet.Builder physNet, 
    									Enumerator<String> strings) {
    	if(routingSources == null || routingSources.size() == 0) return;
        Builder<RouteSrc.Builder> routeSrcs = physNet.initRouting(routingSources.size());
        for(int j=0; j < routingSources.size(); j++) {
            Object currSrc = routingSources.get(j);
            RouteSegment.Builder segment = routeSrcs.get(j).getRouteSegment();
            switch(RouteClass.valueOf(currSrc.getClass().getSimpleName())) {
                case PIP:{
                    PIP pip = (PIP) currSrc;
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
                case SiteBELPin:{
                    SiteBELPin sbp = (SiteBELPin) currSrc;
                    PhysBelPin.Builder physPin = segment.initBelPin();
                    physPin.setBel(strings.getIndex(sbp.belPin.getBEL().getName()));
                    physPin.setPin(strings.getIndex(sbp.belPin.getName()));
                    physPin.setSite(strings.getIndex(sbp.site.getName()));
                    break;
                }
                case SitePinInst:{
                    SitePinInst spi = (SitePinInst) currSrc;
                    PhysSitePin.Builder physSitePin = segment.initSitePin();
                    physSitePin.setSite(strings.getIndex(spi.getSite().getName()));
                    physSitePin.setPin(strings.getIndex(spi.getName()));
                    break;
                }
                case SiteSitePIP: {
                    SiteSitePIP sitePIP = (SiteSitePIP) currSrc;
                    PhysSitePIP.Builder physSitePIP = segment.initSitePIP();
                    physSitePIP.setSite(strings.getIndex(sitePIP.site.getName()));
                    physSitePIP.setBel(strings.getIndex(sitePIP.sitePIP.getBELName()));
                    physSitePIP.setPin(strings.getIndex(sitePIP.sitePIP.getInputPinName()));
                    physSitePIP.setIsFixed(sitePIP.isFixed);
                    break;
                }
                default:
                    throw new RuntimeException("Unhandled class in routing representation: " +
                            currSrc.getClass());
            }
            
        }
    }
    
    enum RouteClass {
        PIP, SiteBELPin, SitePinInst, SiteSitePIP;
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
