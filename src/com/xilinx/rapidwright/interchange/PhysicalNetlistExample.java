package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map.Entry;

import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;
import org.python.google.common.base.Enums;
import org.capnproto.StructList.Builder;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePIPStatus;
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
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class PhysicalNetlistExample {

    public static final String DISABLE_AUTO_IO_BUFFERS = "DISABLE_AUTO_IO_BUFFERS";
    public static final String OUT_OF_CONTEXT = "OUT_OF_CONTEXT";
    
    private static Enumerator<String> allStrings = new Enumerator<>();
    
    
    public static void writePhysCells(PhysNetlist.Builder physNetlist, Design design) {
        // Site Inst
        Builder<SiteInstance.Builder> siteInsts = physNetlist.initSiteInsts(design.getSiteInsts().size());
        int i=0; 
        for(SiteInst si : design.getSiteInsts()) {
            SiteInstance.Builder siBuilder = siteInsts.get(i);
            siBuilder.setSite(allStrings.getIndex(si.getSiteName()));
            siBuilder.setType(allStrings.getIndex(si.getSiteTypeEnum().name()));
            i++;
        }
        
        // Cells
        Builder<CellPlacement.Builder> cells = physNetlist.initPlacements(design.getCells().size());
        i=0;
        for(Cell cell : design.getCells()) {
            CellPlacement.Builder physCell = cells.get(i);
            physCell.setCellName(allStrings.getIndex(cell.getName()));
            physCell.setType(allStrings.getIndex(cell.getType()));
            physCell.setSite(allStrings.getIndex(cell.getSiteName()));
            physCell.setBel(allStrings.getIndex(cell.getBELName()));
            Builder<PinMapping.Builder> pinMap = physCell.initPinMap(cell.getPinMappingsL2P().size());
            int j=0; 
            for(Entry<String,String> e : cell.getPinMappingsL2P().entrySet()) {
                PinMapping.Builder pinMapping = pinMap.get(j);
                pinMapping.setBel(allStrings.getIndex(cell.getBELName()));
                pinMapping.setCellPin(allStrings.getIndex(e.getKey()));
                pinMapping.setBelPin(allStrings.getIndex(e.getValue()));
                j++;
            }
            i++;
        }        
    }
    
    public static void writePhysNets(PhysNetlist.Builder physNetlist, Design design) {
        // Phys Nets
        Builder<PhysNet.Builder> nets = physNetlist.initPhysNets(design.getNets().size());
        int i=0;
        for(Net net : design.getNets()) {
            PhysNet.Builder physNet = nets.get(i);
            physNet.setName(allStrings.getIndex(net.getName()));
            // We need to traverse the net inside sites to fully populate routing spec
            ArrayList<Object> routingSources = new ArrayList<>(net.getPIPs()); 
            for(SitePinInst spi : net.getPins()) {
                routingSources.add(spi);
                Site site = spi.getSite();
                SiteInst siteInst = spi.getSiteInst();
                HashSet<String> siteWires = siteInst.getSiteCTags().get(net);
                if(siteWires != null) {
                    for(String siteWire : siteWires) {
                        BELPin[] belPins = site.getBELPins(siteWire);
                        for(BELPin belPin : belPins) {
                            if(belPin.isSitePort()) continue;
                            if(belPin.getBEL().getBELClass() == BELClass.RBEL && belPin.isOutput()) continue;
                            if(siteInst.getCell(belPin.getBEL()) == null) continue;
                            routingSources.add(new SiteBELPin(site,belPin));
                        }
                    }
                }
                    
                
                for(SitePIP sitePIP : siteInst.getUsedSitePIPs()) {
                    String siteWire = sitePIP.getInputPin().getSiteWireName();
                    if(siteInst.getNetFromSiteWire(siteWire).equals(net)) {
                        SitePIPStatus status = siteInst.getSitePIPStatus(sitePIP);
                        routingSources.add(new SiteSitePIP(site, sitePIP, status.isFixed()));
                    }
                }
            }
            
            Builder<RouteSrc.Builder> routeSrcs = physNet.initRouting(routingSources.size());
            for(int j=0; j < routingSources.size(); j++) {
                Object currSrc = routingSources.get(j);
                RouteSegment.Builder segment = routeSrcs.get(j).getRouteSegment();
                switch(RouteClass.valueOf(currSrc.getClass().getSimpleName())) {
                    case PIP:{
                        PIP pip = (PIP) currSrc;
                        PhysPIP.Builder physPIP = segment.initPip();
                        physPIP.setTile(allStrings.getIndex(pip.getTile().getName()));
                        physPIP.setWire0(allStrings.getIndex(pip.getStartWireName()));
                        physPIP.setWire1(allStrings.getIndex(pip.getEndWireName()));
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
                        physPin.setBel(allStrings.getIndex(sbp.belPin.getBEL().getName()));
                        physPin.setPin(allStrings.getIndex(sbp.belPin.getName()));
                        physPin.setSite(allStrings.getIndex(sbp.site.getName()));
                        break;
                    }
                    case SitePinInst:{
                        SitePinInst spi = (SitePinInst) currSrc;
                        PhysSitePin.Builder physSitePin = segment.initSitePin();
                        physSitePin.setSite(allStrings.getIndex(spi.getSite().getName()));
                        physSitePin.setPin(allStrings.getIndex(spi.getName()));
                        break;
                    }
                    case SiteSitePIP: {
                        SiteSitePIP sitePIP = (SiteSitePIP) currSrc;
                        PhysSitePIP.Builder physSitePIP = segment.initSitePIP();
                        physSitePIP.setSite(allStrings.getIndex(sitePIP.site.getName()));
                        physSitePIP.setBel(allStrings.getIndex(sitePIP.sitePIP.getBELName()));
                        physSitePIP.setPin(allStrings.getIndex(sitePIP.sitePIP.getInputPinName()));
                        physSitePIP.setIsFixed(sitePIP.isFixed);
                        break;
                    }
                    default:
                        throw new RuntimeException("Unhandled class in routing representation: " +
                                currSrc.getClass());
                }
                
            }
            i++;
        }        
    }

    enum RouteClass {
        PIP, SiteBELPin, SitePinInst, SiteSitePIP;
    }
    
    public static MessageBuilder writePhysNetlist(Design design) {
        MessageBuilder message = new MessageBuilder();
        PhysNetlist.Builder physNetlist = message.initRoot(PhysNetlist.factory);
                
        physNetlist.setPart(design.getPartName());
        
        writePhysCells(physNetlist, design);
        
        writePhysNets(physNetlist, design);
        
        StructList.Builder<Property.Builder> props = physNetlist.initProperties(2);
        Property.Builder autoIOs = props.get(0);
        autoIOs.setKey(allStrings.getIndex(DISABLE_AUTO_IO_BUFFERS));
        autoIOs.setValue(allStrings.getIndex(design.isAutoIOBuffersSet() ? "0" : "1"));
        
        Property.Builder ooc = props.get(1);
        ooc.setKey(allStrings.getIndex(OUT_OF_CONTEXT));
        ooc.setValue(allStrings.getIndex(design.isDesignOutOfContext() ? "1" : "0"));
        
        TextList.Builder strList = physNetlist.initStrList(allStrings.size());
        int stringCount = strList.size();
        for(int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(allStrings.get(i)));
        }      
        
        return message;
    }
    
    public static Design readPhysNetlist(String physNetlistFileName, EDIFNetlist netlist) throws IOException {
        Design design = new Design();
        design.setNetlist(netlist);
        
        FileInputStream fis = new java.io.FileInputStream(physNetlistFileName);
        MessageReader readMsg = SerializePacked.readFromUnbuffered((fis).getChannel());
        fis.close();

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);
        design.setPartName(physNetlist.getPart().toString());
        Device device = design.getDevice();

        
        allStrings.clear();
        TextList.Reader strListReader = physNetlist.getStrList();
        int strCount = strListReader.size();
        for(int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.addObject(str);
        }

        StructList.Reader<SiteInstance.Reader> siteInsts = physNetlist.getSiteInsts();
        int siteInstCount = siteInsts.size();
        for(int i=0; i < siteInstCount; i++) {
            SiteInstance.Reader si = siteInsts.get(i);
            String siteName = allStrings.get(si.getSite());
            SiteTypeEnum type = SiteTypeEnum.valueOf(allStrings.get(si.getType()));
            design.createSiteInst(siteName, type, device.getSite(siteName));
        }
        
        StructList.Reader<PhysCell.Reader> physCells = physNetlist.getPhysCells();
        int physCellCount = physCells.size();
        for(int i=0; i < physCellCount; i++) {
            PhysCell.Reader physCell = physCells.get(i);
            // TODO
        }
        
        StructList.Reader<CellPlacement.Reader> placements = physNetlist.getPlacements();
        int placementCount = placements.size();
        for(int i=0; i < placementCount; i++) {
            CellPlacement.Reader placement = placements.get(i);
            String cellName = allStrings.get(placement.getCellName());
            EDIFCellInst cellInst = netlist.getCellInstFromHierName(cellName);
            if(cellInst == null) {
                String cellType = allStrings.get(placement.getType());
                Unisim unisim = Enums.getIfPresent(Unisim.class, cellType).get();
                if(unisim == null) {
                    EDIFCell cell = new EDIFCell(null,cellType);
                    cellInst = new EDIFCellInst(cellName,cell, null);
                } else {
                    cellInst = Design.createUnisimInst(null, cellName, unisim);                    
                }
            }
            Site site = device.getSite(allStrings.get(placement.getSite()));
            SiteInst siteInst = design.getSiteInstFromSite(site);
            BEL bel = site.getBEL(allStrings.get(placement.getBel()));
            Cell cell = new Cell(cellName, siteInst, bel, cellInst);
            

            PrimitiveList.Int.Reader otherBELs = placement.getOtherBels();
            int otherBELCount = otherBELs.size();
            for(int j=0; j < otherBELCount; j++) {
                String belLoc = allStrings.get(otherBELs.get(j));
                // TODO - Other BELs?                
            }
            
            
            StructList.Reader<PinMapping.Reader> pinMap = placement.getPinMap();
            int pinMapCount = pinMap.size();
            for(int j=0; j < pinMapCount; j++) {
                PinMapping.Reader pinMapping = pinMap.get(j);
                String belName = allStrings.get(pinMapping.getBel());
                Cell c = siteInst.getCell(belName);
                if(c == null) {
                    throw new RuntimeException("TODO: Unhandled scenario");
                }
                String belPinName = allStrings.get(pinMapping.getBelPin());
                String cellPinName = allStrings.get(pinMapping.getCellPin());
                c.addPinMapping(belPinName, cellPinName);
            }
        }
        
        StructList.Reader<PhysNet.Reader> nets = physNetlist.getPhysNets();
        int netCount = nets.size();
        for(int i=0; i < netCount; i++) {
            PhysNet.Reader netReader = nets.get(i);
            String netName = allStrings.get(netReader.getName());
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
                        Tile tile = device.getTile(allStrings.get(pReader.getTile()));
                        String wire0 = allStrings.get(pReader.getWire0());
                        String wire1 = allStrings.get(pReader.getWire1());
                        PIP pip = new PIP(tile,wire0, wire1);
                        pip.setIsPIPFixed(pReader.getIsFixed());
                        net.addPIP(pip);                        
                        break;
                    }
                    case BEL_PIN:{
                        PhysBelPin.Reader bpReader = segment.getBelPin();
                        SiteInst siteInst = getSiteInst(bpReader.getSite(), design);
                        BELPin belPin = siteInst.getSite().getBELPin(allStrings.get(bpReader.getBel()), 
                                                                    allStrings.get(bpReader.getPin()));
                        siteInst.routeIntraSiteNet(net, belPin, belPin);                        
                        break;
                    }
                    case SITE_P_I_P:{
                        PhysSitePIP.Reader spReader = segment.getSitePIP();
                        SiteInst siteInst = getSiteInst(spReader.getSite(), design);
                        siteInst.addSitePIP(allStrings.get(spReader.getBel()), 
                                            allStrings.get(spReader.getPin()));
                        break;                        
                    }
                    case SITE_PIN: {
                        PhysSitePin.Reader spReader = segment.getSitePin();
                        SiteInst siteInst = getSiteInst(spReader.getSite(), design);
                        String pinName = allStrings.get(spReader.getPin());
                        net.addPin(new SitePinInst(pinName, siteInst), true);   
                        break;
                    }
                    case _NOT_IN_SCHEMA: {
                        throw new RuntimeException("ERROR: Unknown route segment type");
                    }
                }
            }           
        }
        
        StructList.Reader<Property.Reader> props = physNetlist.getProperties();
        int propCount = props.size();
        for(int i=0; i < propCount; i++) {
            Property.Reader pReader = props.get(i);
            String key = allStrings.get(pReader.getKey());
            if(DISABLE_AUTO_IO_BUFFERS.equals(key)) {
                boolean setAutoIOBuffers = "0".equals(allStrings.get(pReader.getValue()));
                design.setAutoIOBuffers(setAutoIOBuffers);
            }else if(OUT_OF_CONTEXT.equals(key)) {
                boolean isDesignOOC = "1".equals(allStrings.get(pReader.getValue()));
                design.setDesignOutOfContext(isDesignOOC);
            }
        }
        
        return design;
    }
    
    private static SiteInst getSiteInst(int stringIdx, Design design) {
        String siteName = allStrings.get(stringIdx);
        Site site = design.getDevice().getSite(siteName);
        if(site == null) {
            throw new RuntimeException("ERROR: Unknown site " + siteName +
                    " found while parsing routing");
        }
        SiteInst siteInst = design.getSiteInstFromSite(site);
        return siteInst;
    }
        
    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <input>.dcp");
            System.out.println("   Example round trip test for a logical & physical netlist to start from a DCP,"
                    + " get converted to a\n   Cap'n Proto serialized file and then read back into "
                    + "a DCP file.  Creates two new files:\n\t1. <input>.netlist "
                    + "- Cap'n Proto serialized file"
                    + "\n\t2. <input>.roundtrip.edf - EDIF after being written/read from serialized format");
            return;            
        }
    
        CodePerfTracker t = new CodePerfTracker("DCP->Interchange Format->DCP");
        
        t.start("Read DCP");
        // Read DCP into memory using RapidWright
        Design design = Design.readCheckpoint(args[0]);
        t.stop().start("Write Logical Netlist");
        // Write Logical & Physical Netlist to Cap'n Proto Serialization file
        String logNetlistFileName = args[0].replace(".dcp", ".netlist");
        FileOutputStream fo = new java.io.FileOutputStream(logNetlistFileName);
        MessageBuilder message = LogicalNetlistExample.writeLogNetlist(design.getNetlist());
        SerializePacked.writeToUnbuffered(fo.getChannel(), message);
        fo.close();
        
        t.stop().start("Write Physical Netlist");
        String physNetlistFileName = args[0].replace(".dcp", ".phys");
        fo = new java.io.FileOutputStream(physNetlistFileName);
        message = writePhysNetlist(design);
        SerializePacked.writeToUnbuffered(fo.getChannel(), message);
        fo.close();
        
        t.stop().start("Read Logical Netlist");
        // Read Netlist into RapidWright netlist
        EDIFNetlist n2 = LogicalNetlistExample.readLogNetlist(logNetlistFileName);
        
        t.stop().start("Read Physical Netlist");
        Design roundtrip = readPhysNetlist(physNetlistFileName, n2);
        
        t.stop().start("Write DCP");
        // Write RapidWright netlist back to edif
        roundtrip.writeCheckpoint(args[0].replace(".dcp", ".roundtrip.dcp"));
        
        t.stop().printSummary();
    }
}
