package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.TextList;
import org.python.google.common.base.Enums;
import org.python.google.common.base.Optional;

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
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
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
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

public class PhysNetlistReader {

    protected static final String DISABLE_AUTO_IO_BUFFERS = "DISABLE_AUTO_IO_BUFFERS";
    protected static final String OUT_OF_CONTEXT = "OUT_OF_CONTEXT";

    private static final String STATIC_SOURCE = "STATIC_SOURCE";
    private static int tieoffInstanceCount = 0;

    public static Design readPhysNetlist(String physNetlistFileName, EDIFNetlist netlist) throws IOException {
        Design design = new Design();
        design.setNetlist(netlist);


        FileInputStream fis = new java.io.FileInputStream(physNetlistFileName);
        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = SerializePacked.readFromUnbuffered((fis).getChannel(), rdOptions);
        fis.close();

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);
        design.setPartName(physNetlist.getPart().toString());

        Enumerator<String> allStrings = readAllStrings(physNetlist);

        readSiteInsts(physNetlist, design, allStrings);

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

    private static void readPlacement(PhysNetlist.Reader physNetlist, Design design,
                                        Enumerator<String> strings) {
        HashMap<String, PhysCellType> physCells = new HashMap<>();
        StructList.Reader<PhysCell.Reader> physCellReaders = physNetlist.getPhysCells();
        int physCellCount = physCellReaders.size();
        for(int i=0; i < physCellCount; i++) {
            PhysCell.Reader reader = physCellReaders.get(i);
            physCells.put(strings.get(reader.getCellName()), reader.getPhysType());
        }


        StructList.Reader<CellPlacement.Reader> placements = physNetlist.getPlacements();
        int placementCount = placements.size();
        Device device = design.getDevice();
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary macroPrims = Design.getMacroPrimitives(device.getSeries());
        Map<String, List<String>> macroLeafChildren = new HashMap<>();
        for(int i=0; i < placementCount; i++) {
            CellPlacement.Reader placement = placements.get(i);
            String cellName = strings.get(placement.getCellName());
            EDIFCellInst cellInst = null;
            Site site = device.getSite(strings.get(placement.getSite()));
            SiteInst siteInst = design.getSiteInstFromSite(site);
            String belName = strings.get(placement.getBel());
            HashSet<String> otherBELLocs = null;
            if(physCells.get(cellName) == PhysCellType.LOCKED) {
                cellInst = new EDIFCellInst(PhysNetlistWriter.LOCKED,null,null);
                if(siteInst == null) {
                    siteInst = new SiteInst(site.getName(), design, site.getSiteTypeEnum(), site);
                }
                siteInst.setSiteLocked(true);
                Cell c = siteInst.getCell(belName);
                if(c == null){
                    BEL bel = siteInst.getBEL(belName);
                    c = new Cell(PhysNetlistWriter.LOCKED, bel, cellInst);
                    c.setBELFixed(placement.getIsBelFixed());
                    c.setNullBEL(bel == null);
                    siteInst.addCell(c);
                }
                c.setLocked(true);

                // c Alternative Blocked Site Type // TODO
            } else if(physCells.get(cellName) == PhysCellType.PORT) {
                siteInst.getBEL(belName);
                Cell portCell = new Cell(cellName,siteInst.getBEL(belName),null);
                portCell.setType(PhysNetlistWriter.PORT);
                siteInst.addCell(portCell);
                portCell.setBELFixed(placement.getIsBelFixed());
                portCell.setSiteFixed(placement.getIsSiteFixed());
            } else {
            	cellInst = netlist.getCellInstFromHierName(cellName);
            	String cellType = strings.get(placement.getType());
                if(cellInst == null) {
                    Optional<Unisim> maybeUnisim = Enums.getIfPresent(Unisim.class, cellType);
                    Unisim unisim = maybeUnisim.isPresent() ? maybeUnisim.get() : null;
                    if(unisim == null) {
                        EDIFCell cell = new EDIFCell(null,cellType);
                        cellInst = new EDIFCellInst(cellName,cell, null);
                    } else {
                        cellInst = Design.createUnisimInst(null, cellName, unisim);
                    }
                }
                if((cellType != null && macroPrims.containsCell(cellType)) || 
                		macroPrims.containsCell(cellInst.getCellType())) {
                	throw new RuntimeException("ERROR: Placement for macro primitive " 
                			+ cellInst.getCellType().getName() + " (instance "+cellName+") is "
                			+ "invalid.  Please only provide placements for the macro's children "
                			+ "leaf cells: " + cellInst.getCellType().getCellInsts() +".");
                }
                
                BEL bel = siteInst.getBEL(strings.get(placement.getBel()));
                if(bel == null) {
                    throw new RuntimeException(
                  		  "ERROR: The placement specified on BEL " + site.getName() + "/" 
                          + strings.get(placement.getBel()) + " could not be found in the target "
                          + "device.");
                }
                if(bel.getBELType().equals("HARD0") || bel.getBELType().equals("HARD1")) {
                    throw new RuntimeException(
                    		  "ERROR: The placement specified on BEL " + site.getName() + "/" 
                            + bel.getName() + " is not valid. HARD0 and HARD1 BEL types do not "
                            + "require placed cells.");
                }
                Cell cell = new Cell(cellName, siteInst, bel, cellInst);
                cell.setBELFixed(placement.getIsBelFixed());
                cell.setSiteFixed(placement.getIsSiteFixed());

                PrimitiveList.Int.Reader otherBELs = placement.getOtherBels();
                int otherBELCount = otherBELs.size();
                if(otherBELCount > 0) otherBELLocs = new HashSet<String>();
                for(int j=0; j < otherBELCount; j++) {
                    String belLoc = strings.get(otherBELs.get(j));
                    otherBELLocs.add(belLoc);
                }
            }

            PhysNet.Reader nullNet = physNetlist.getNullNet();
            StructList.Reader<RouteBranch.Reader> stubs = nullNet.getStubs();
            int stubCount = stubs.size();
            for(int k=0; k < stubCount; k++) {
            	RouteSegment.Reader segment = stubs.get(k).getRouteSegment();
                PhysSitePIP.Reader spReader = segment.getSitePIP();
                SiteInst sitePIPSiteInst = getSiteInst(spReader.getSite(), design, strings);
                sitePIPSiteInst.addSitePIP(strings.get(spReader.getBel()),
                                           strings.get(spReader.getPin()));            	
            }
            
            
            StructList.Reader<PinMapping.Reader> pinMap = placement.getPinMap();
            int pinMapCount = pinMap.size();
            for(int j=0; j < pinMapCount; j++) {
                PinMapping.Reader pinMapping = pinMap.get(j);
                belName = strings.get(pinMapping.getBel());
                String belPinName = strings.get(pinMapping.getBelPin());
                String cellPinName = strings.get(pinMapping.getCellPin());
                Cell c = siteInst.getCell(belName);
                if(c == null) {
                    if(otherBELLocs.contains(belName)) {
                        BEL bel = siteInst.getBEL(belName);
                        if(bel == null) {
                            throw new RuntimeException("ERROR: Couldn't find BEL " + belName
                                    + " in site " + siteInst.getSiteName() + " of type "
                                    + siteInst.getSiteTypeEnum());
                        }
                        c = new Cell(cellName, bel, cellInst);
                        c.setSiteInst(siteInst);
                        siteInst.getCellMap().put(belName, c);
                        c.setRoutethru(true);
                    }else {
                        throw new RuntimeException("ERROR: Missing BEL location in other BEL list: "
                                + belName + " on for pin mapping "
                                + belPinName + " -> " + cellPinName);
                    }
                }
                // Remote pin mappings from other cells
                if(c.getLogicalPinMapping(belPinName) != null && pinMapping.hasOtherCell()){
                    c.setRoutethru(true);
                    MultiCellPinMapping.Reader otherCell = pinMapping.getOtherCell();
                    c.addAltPinMapping(belPinName, new AltPinMapping(cellPinName,
                            strings.get(otherCell.getMultiCell()),
                            strings.get(otherCell.getMultiType())));
                }else {
                    c.addPinMapping(belPinName, cellPinName);
                    if(pinMapping.getIsFixed()) {
                        c.fixPin(belPinName);
                    }
                }
            }
        }
        
        // Validate macro primitives are placed fully
        HashSet<String> checked = new HashSet<>();
        for(Cell c : design.getCells()) {
        	EDIFCell cellType = c.getParentCell();
        	if(cellType != null && macroPrims.containsCell(cellType)) {
        		String parentHierName = c.getParentHierarchicalInstName();
            	if(checked.contains(parentHierName)) continue;
            	List<String> missingPlacements = null;
            	List<String> childrenNames = macroLeafChildren.get(cellType.getName());
            	if(childrenNames == null) {
            		childrenNames = EDIFTools.getMacroLeafCellNames(cellType);
            		macroLeafChildren.put(cellType.getName(), childrenNames);
            	}
            	//for(EDIFCellInst inst : cellType.getCellInsts()) { // TODO - Fix up loop list
            	for(String childName : childrenNames) {
            		String childCellName = parentHierName + EDIFTools.EDIF_HIER_SEP + childName;
            		Cell child = design.getCell(childCellName);
            		if(child == null) {
            			if(missingPlacements == null) missingPlacements = new ArrayList<String>();
            			missingPlacements.add(childName + " (" + childCellName + ")");
            		}
            	}
            	if(missingPlacements != null && !cellType.getName().equals("IOBUFDS")) {
        			throw new RuntimeException("ERROR: Macro primitive '"+ parentHierName 
        					+ "' is not fully placed. Expected placements for all child cells: " 
        					+ cellType.getCellInsts() + ", but missing placements "
        				    + "for cells: " + missingPlacements);            		
            	}
            	
            	checked.add(parentHierName);
        	}
        }
    }
    
    private static NetType getNetType(PhysNet.Reader netReader, String netName) {
    	switch(netReader.getType()) {
    		case GND:
    			if(!netName.equals(Net.GND_NET)) {
    				throw new RuntimeException("ERROR: Invalid GND Net " + netName +
    						", should be named " + Net.GND_NET);
    			}
    			return NetType.GND;
    		case VCC:
    			if(!netName.equals(Net.VCC_NET)) {
    				throw new RuntimeException("ERROR: Invalid VCC Net " + netName +
    						", should be named " + Net.VCC_NET);
    			}
    			return NetType.VCC;
    		default:
    			return NetType.WIRE;
    	}
    }
    
    private static void readRouting(PhysNetlist.Reader physNetlist, Design design, 
                                    Enumerator<String> strings) {
        StructList.Reader<PhysNet.Reader> nets = physNetlist.getPhysNets();
        EDIFNetlist netlist = design.getNetlist();
        int netCount = nets.size();
        for(int i=0; i < netCount; i++) {
            PhysNet.Reader netReader = nets.get(i);
            String netName = strings.get(netReader.getName());
            EDIFHierNet edifNet = netlist.getHierNetFromName(netName);
            Net net = new Net(netName, edifNet == null ? null : edifNet.getNet());
            design.addNet(net);
            net.setType(getNetType(netReader, netName));
            
            // Sources
            StructList.Reader<RouteBranch.Reader> routeSrcs = netReader.getSources();
            int routeSrcsCount = routeSrcs.size();
            for(int j=0; j < routeSrcsCount; j++) {
                RouteBranch.Reader branchReader = routeSrcs.get(j);
                readRouteBranch(branchReader, net, design, strings);
            }
            // Stubs
            StructList.Reader<RouteBranch.Reader> routeStubs = netReader.getStubs();
            int routeStubsCount = routeStubs.size();
            for(int j=0; j < routeStubsCount; j++) {
                RouteBranch.Reader branchReader = routeStubs.get(j);
                readRouteBranch(branchReader, net, design, strings);
            }

        }
    }

    private static void readRouteBranch(RouteBranch.Reader branchReader, Net net, Design design,
                                        Enumerator<String> strings) {
        RouteBranch.RouteSegment.Reader segment = branchReader.getRouteSegment();
        Device device = design.getDevice();
        switch(segment.which()) {
            case PIP:{
                PhysPIP.Reader pReader = segment.getPip();
                Tile tile = device.getTile(strings.get(pReader.getTile()));
                String wire0 = strings.get(pReader.getWire0());
                String wire1 = strings.get(pReader.getWire1());
                PIP pip = new PIP(tile, wire0, wire1);
                pip.setIsPIPFixed(pReader.getIsFixed());
                net.addPIP(pip);
                break;
            }
            case BEL_PIN:{
                PhysBelPin.Reader bpReader = segment.getBelPin();
                SiteInst siteInst = getSiteInst(bpReader.getSite(), design, strings);
                String bel_name = strings.get(bpReader.getBel());
                BEL bel = siteInst.getBEL(bel_name);
                if(bel == null) {
                    throw new RuntimeException(String.format("ERROR: Failed to get BEL %s", bel_name));
                }
                String bel_pin = strings.get(bpReader.getPin());
                BELPin belPin = bel.getPin(bel_pin);
                if(belPin == null) {
                    throw new RuntimeException(String.format("ERROR: Failed to get BEL pin %s/%s", bel_name, bel_pin));
                }
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
                if(siteInst == null && net.isStaticNet()){
                    Site site = design.getDevice().getSite(strings.get(spReader.getSite()));
                    siteInst = new SiteInst(STATIC_SOURCE + tieoffInstanceCount++, site.getSiteTypeEnum());
                    siteInst.place(site);
                }

                net.addPin(new SitePinInst(pinName, siteInst), false);
                break;
            }
            case _NOT_IN_SCHEMA: {
                throw new RuntimeException("ERROR: Unknown route segment type");
            }
        }

        StructList.Reader<RouteBranch.Reader> branches = branchReader.getBranches();
        int branchesCount = branches.size();
        for(int j=0; j < branchesCount; j++) {
            RouteBranch.Reader bReader = branches.get(j);
            readRouteBranch(bReader, net, design, strings);
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
        if(siteInst == null && site.getSiteTypeEnum() == SiteTypeEnum.TIEOFF) {
            // Create a dummy TIEOFF SiteInst
            siteInst = new SiteInst(STATIC_SOURCE + tieoffInstanceCount++, site.getSiteTypeEnum());
            siteInst.place(site);
        }
        return siteInst;
    }
}
