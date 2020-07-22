package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.IOException;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.TextList;
import org.python.google.common.base.Enums;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysBelPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysCell;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysPIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.Property;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteSrc;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteSrc.RouteSegment;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.SiteInstance;

public class PhysNetlistReader {

    protected static final String DISABLE_AUTO_IO_BUFFERS = "DISABLE_AUTO_IO_BUFFERS";
    protected static final String OUT_OF_CONTEXT = "OUT_OF_CONTEXT";
    
    public static Design readPhysNetlist(String physNetlistFileName, EDIFNetlist netlist) throws IOException {
        Design design = new Design();
        design.setNetlist(netlist);
        
        
        FileInputStream fis = new java.io.FileInputStream(physNetlistFileName);
        MessageReader readMsg = SerializePacked.readFromUnbuffered((fis).getChannel());
        fis.close();
    
        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);
        design.setPartName(physNetlist.getPart().toString());   
        
        Enumerator<String> allStrings = readAllStrings(physNetlist);
    
        readSiteInsts(physNetlist, design, allStrings);
        
        readPhysCells(physNetlist, design, allStrings);
        
        readPlacement(physNetlist, design, allStrings);
        
        readRouting(physNetlist, design, allStrings);
        
        readDesignProperties(physNetlist, design, allStrings);
        
        return design;
    }
    
    private static Enumerator<String> readAllStrings(PhysNetlist.Reader physNetlist){
        Enumerator<String> allStrings = new Enumerator<>();
        TextList.Reader strListReader = physNetlist.getStrList();
        int strCount = strListReader.size();
        for(int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.addObject(str);
        }
        return allStrings;
    }
    
    private static void readSiteInsts(PhysNetlist.Reader physNetlist, Design design,
                                        Enumerator<String> strings) {
        Device device = design.getDevice();
        StructList.Reader<SiteInstance.Reader> siteInsts = physNetlist.getSiteInsts();
        int siteInstCount = siteInsts.size();
        for(int i=0; i < siteInstCount; i++) {
            SiteInstance.Reader si = siteInsts.get(i);
            String siteName = strings.get(si.getSite());
            SiteTypeEnum type = SiteTypeEnum.valueOf(strings.get(si.getType()));
            design.createSiteInst(siteName, type, device.getSite(siteName));
        }        
    }

    private static void readPhysCells(PhysNetlist.Reader physNetlist, Design design,
                                        Enumerator<String> strings) {
        StructList.Reader<PhysCell.Reader> physCells = physNetlist.getPhysCells();
        int physCellCount = physCells.size();
        for(int i=0; i < physCellCount; i++) {
            PhysCell.Reader physCell = physCells.get(i);
            // TODO
        }
    }
    
    private static void readPlacement(PhysNetlist.Reader physNetlist, Design design, 
                                        Enumerator<String> strings) {
        StructList.Reader<CellPlacement.Reader> placements = physNetlist.getPlacements();        
        int placementCount = placements.size();
        Device device = design.getDevice();
        EDIFNetlist netlist = design.getNetlist();
        for(int i=0; i < placementCount; i++) {
            CellPlacement.Reader placement = placements.get(i);
            String cellName = strings.get(placement.getCellName());
            EDIFCellInst cellInst = netlist.getCellInstFromHierName(cellName);
            if(cellInst == null) {
                String cellType = strings.get(placement.getType());
                Unisim unisim = Enums.getIfPresent(Unisim.class, cellType).get();
                if(unisim == null) {
                    EDIFCell cell = new EDIFCell(null,cellType);
                    cellInst = new EDIFCellInst(cellName,cell, null);
                } else {
                    cellInst = Design.createUnisimInst(null, cellName, unisim);                    
                }
            }
            Site site = device.getSite(strings.get(placement.getSite()));
            SiteInst siteInst = design.getSiteInstFromSite(site);
            BEL bel = site.getBEL(strings.get(placement.getBel()));
            Cell cell = new Cell(cellName, siteInst, bel, cellInst);
            
    
            PrimitiveList.Int.Reader otherBELs = placement.getOtherBels();
            int otherBELCount = otherBELs.size();
            for(int j=0; j < otherBELCount; j++) {
                String belLoc = strings.get(otherBELs.get(j));
                // TODO - Other BELs?                
            }
            
            
            StructList.Reader<PinMapping.Reader> pinMap = placement.getPinMap();
            int pinMapCount = pinMap.size();
            for(int j=0; j < pinMapCount; j++) {
                PinMapping.Reader pinMapping = pinMap.get(j);
                String belName = strings.get(pinMapping.getBel());
                Cell c = siteInst.getCell(belName);
                if(c == null) {
                    throw new RuntimeException("TODO: Unhandled scenario");
                }
                String belPinName = strings.get(pinMapping.getBelPin());
                String cellPinName = strings.get(pinMapping.getCellPin());
                c.addPinMapping(belPinName, cellPinName);
            }
        }        
    }
    
    private static void readRouting(PhysNetlist.Reader physNetlist, Design design, 
                                    Enumerator<String> strings) {
        StructList.Reader<PhysNet.Reader> nets = physNetlist.getPhysNets();
        Device device = design.getDevice();
        EDIFNetlist netlist = design.getNetlist();
        int netCount = nets.size();
        for(int i=0; i < netCount; i++) {
            PhysNet.Reader netReader = nets.get(i);
            String netName = strings.get(netReader.getName());
            EDIFHierNet edifNet = netlist.getHierNetFromName(netName);
            Net net = new Net(netName, edifNet == null ? null : edifNet.getNet());
            design.addNet(net);
            StructList.Reader<RouteSrc.Reader> routeSrcs = netReader.getRouting();
            int routeSrcsCount = routeSrcs.size();
            for(int j=0; j < routeSrcsCount; j++) {
                RouteSegment.Reader segment = routeSrcs.get(j).getRouteSegment();
                switch(segment.which()) {
                    case PIP:{
                        PhysPIP.Reader pReader = segment.getPip();
                        Tile tile = device.getTile(strings.get(pReader.getTile()));
                        String wire0 = strings.get(pReader.getWire0());
                        String wire1 = strings.get(pReader.getWire1());
                        PIP pip = new PIP(tile,wire0, wire1);
                        pip.setIsPIPFixed(pReader.getIsFixed());
                        net.addPIP(pip);                        
                        break;
                    }
                    case BEL_PIN:{
                        PhysBelPin.Reader bpReader = segment.getBelPin();
                        SiteInst siteInst = getSiteInst(bpReader.getSite(), design, strings);
                        BELPin belPin = siteInst.getSite().getBELPin(strings.get(bpReader.getBel()), 
                                                                    strings.get(bpReader.getPin()));
                        siteInst.routeIntraSiteNet(net, belPin, belPin);                        
                        break;
                    }
                    case SITE_P_I_P:{
                        PhysSitePIP.Reader spReader = segment.getSitePIP();
                        SiteInst siteInst = getSiteInst(spReader.getSite(), design, strings);
                        siteInst.addSitePIP(strings.get(spReader.getBel()), 
                                            strings.get(spReader.getPin()));
                        break;                        
                    }
                    case SITE_PIN: {
                        PhysSitePin.Reader spReader = segment.getSitePin();
                        SiteInst siteInst = getSiteInst(spReader.getSite(), design, strings);
                        String pinName = strings.get(spReader.getPin());
                        net.addPin(new SitePinInst(pinName, siteInst), true);   
                        break;
                    }
                    case _NOT_IN_SCHEMA: {
                        throw new RuntimeException("ERROR: Unknown route segment type");
                    }
                }
            }           
        }        
    }
    
    private static void readDesignProperties(PhysNetlist.Reader physNetlist, Design design, 
                                                Enumerator<String> strings) {
        StructList.Reader<Property.Reader> props = physNetlist.getProperties();
        int propCount = props.size();
        for(int i=0; i < propCount; i++) {
            Property.Reader pReader = props.get(i);
            String key = strings.get(pReader.getKey());
            if(DISABLE_AUTO_IO_BUFFERS.equals(key)) {
                boolean setAutoIOBuffers = "0".equals(strings.get(pReader.getValue()));
                design.setAutoIOBuffers(setAutoIOBuffers);
            }else if(OUT_OF_CONTEXT.equals(key)) {
                boolean isDesignOOC = "1".equals(strings.get(pReader.getValue()));
                design.setDesignOutOfContext(isDesignOOC);
            }
        }
    }
    
    private static SiteInst getSiteInst(int stringIdx, Design design, Enumerator<String> strings) {
        String siteName = strings.get(stringIdx);
        Site site = design.getDevice().getSite(siteName);
        if(site == null) {
            throw new RuntimeException("ERROR: Unknown site " + siteName +
                    " found while parsing routing");
        }
        SiteInst siteInst = design.getSiteInstFromSite(site);
        return siteInst;
    }
}
