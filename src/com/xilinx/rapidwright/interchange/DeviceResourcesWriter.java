package com.xilinx.rapidwright.interchange;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;

import org.capnproto.MessageBuilder;
import org.capnproto.PrimitiveList;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PIPType;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BELCategory;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PrimToMacroExpansion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SitePin;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteWire;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BEL.Builder;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class DeviceResourcesWriter {
    private static Enumerator<String> allStrings;
    private static Enumerator<String> allSiteTypes;

    private static HashMap<TileTypeEnum,Tile> tileTypes; 
    private static HashMap<SiteTypeEnum,Site> siteTypes;
    
    public static void populateEnumerations(Device device) {
        allStrings = new Enumerator<>();
        allSiteTypes = new Enumerator<>();

        tileTypes = new HashMap<>(); 
        siteTypes = new HashMap<>();
        for(Tile tile : device.getAllTiles()) {
            allStrings.addObject(tile.getName());
            if(!tileTypes.containsKey(tile.getTileTypeEnum())) {
                allStrings.addObject(tile.getTileTypeEnum().name());
                for(int i=0; i < tile.getWireCount(); i++) {
                    allStrings.addObject(tile.getWireName(i));
                }
                tileTypes.put(tile.getTileTypeEnum(),tile);
            }
            for(Site site : tile.getSites()) {
                allStrings.addObject(site.getName());
                allStrings.addObject(site.getSiteTypeEnum().name());
                if(!siteTypes.containsKey(site.getSiteTypeEnum())) {
                    allStrings.addObject(site.getSiteTypeEnum().toString());
                    for(int i=0; i < site.getSiteWireCount(); i++) {
                        allStrings.addObject(site.getSiteWireName(i));
                    }
                    for(BEL bel : site.getBELs()) {
                        allStrings.addObject(bel.getName());
                        allStrings.addObject(bel.getBELType());
                        for(BELPin belPin : bel.getPins()) {
                            allStrings.addObject(belPin.getName());
                        }
                    }
                    for(int i=0; i < site.getSitePinCount(); i++) {
                        allStrings.addObject(site.getPinName(i));
                    }
                    siteTypes.put(site.getSiteTypeEnum(), site);
                }   
            }
            
        }
        
        for(Entry<String,String> e : EDIFNetlist.macroExpandExceptionMap.entrySet()) {
            allStrings.addObject(e.getKey()); 
            allStrings.addObject(e.getValue());
        }
    }

    
    public static void writeDeviceResourcesFile(Device device, CodePerfTracker t, 
                                                                String fileName) throws IOException {
        t.start("populateEnums");
        populateEnumerations(device);

        MessageBuilder message = new MessageBuilder();
        DeviceResources.Device.Builder devBuilder = message.initRoot(DeviceResources.Device.factory);
        devBuilder.setName(device.getName());

        t.stop().start("Strings");
        writeAllStringsToBuilder(devBuilder);
        
        t.stop().start("SiteTypes");
        writeAllSiteTypesToBuilder(device, devBuilder);
        
        t.stop().start("TileTypes");
        writeAllTileTypesToBuilder(device, devBuilder);
        
        t.stop().start("Tiles");
        writeAllTilesToBuilder(device, devBuilder);
        
        t.stop().start("Wires&Nodes");
        writeAllWiresAndNodesToBuilder(device, devBuilder);
        
        t.stop().start("Prims&Macros");
        // Create an EDIFNetlist populated with just primitive and macro libraries
        EDIFLibrary prims = Design.getPrimitivesLibrary();       
        EDIFLibrary macros = Design.getMacroPrimitives(device.getSeries());
        EDIFNetlist netlist = new EDIFNetlist("PrimitiveLibs");
        netlist.addLibrary(prims);
        netlist.addLibrary(macros);
        ArrayList<EDIFCell> dupsToRemove = new ArrayList<EDIFCell>();
        for(EDIFCell cell : macros.getCells()) {
            EDIFCell hdiCell = prims.getCell(cell.getName());
            if(hdiCell != null) {
                dupsToRemove.add(cell);
            }
            for(EDIFCellInst inst : cell.getCellInsts()) {
                EDIFCell hdiCellInst = prims.getCell(inst.getCellType().getName());
                if(hdiCellInst != null) {
                    // remap cell definition to HDI Primitives library
                    inst.updateCellType(hdiCellInst);
                }
                
            }
        }
        
        Netlist.Builder netlistBuilder = devBuilder.getPrimLibs();
        LogNetlistWriter.populateNetlistBuilder(netlist, netlistBuilder, true);
        
        // Write macro exception map
        int size = EDIFNetlist.macroExpandExceptionMap.size();
        StructList.Builder<PrimToMacroExpansion.Builder> exceptionMap = 
                devBuilder.initExceptionMap(size);
        int i=0;
        for(Entry<String, String> entry : EDIFNetlist.macroExpandExceptionMap.entrySet()) {
            PrimToMacroExpansion.Builder entryBuilder = exceptionMap.get(i);
            entryBuilder.setMacroName(allStrings.getIndex(entry.getValue()));
            entryBuilder.setPrimName(allStrings.getIndex(entry.getKey()));
            i++;
        }        
        t.stop().start("Write File");
        Interchange.writeInterchangeFile(fileName, message);
        t.stop();
    }
    
    public static void writeAllStringsToBuilder(DeviceResources.Device.Builder devBuilder) {
        int stringCount = allStrings.size();
        TextList.Builder strList = devBuilder.initStrList(stringCount);
        for(int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(allStrings.get(i)));
        }
    }

    protected static BELCategory getBELCategory(BEL bel) {
        BELClass category = bel.getBELClass();
        if(category == BELClass.BEL)
            return BELCategory.LOGIC;
        if(category == BELClass.RBEL)
            return BELCategory.ROUTING;
        if(category == BELClass.PORT)
            return BELCategory.SITE_PORT;
        return BELCategory._NOT_IN_SCHEMA;
    }
    
    protected static Direction getBELPinDirection(BELPin belPin) {
        BELPin.Direction dir = belPin.getDir();
        if(dir == BELPin.Direction.INPUT)
            return Direction.INPUT;
        if(dir == BELPin.Direction.OUTPUT)
            return Direction.OUTPUT;
        if(dir == BELPin.Direction.BIDIRECTIONAL)
            return Direction.INOUT;
        return Direction._NOT_IN_SCHEMA;
    }
    
    public static void writeAllSiteTypesToBuilder(Device device, DeviceResources.Device.Builder devBuilder) {
        StructList.Builder<SiteType.Builder> siteTypesList = devBuilder.initSiteTypeList(siteTypes.size());
        
        int i=0; 
        for(Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            SiteType.Builder siteType = siteTypesList.get(i);
            Site site = e.getValue();
            siteType.setName(allStrings.getIndex(e.getKey().name()));
            allSiteTypes.addObject(e.getKey().name());
            // BELs & BELPins
            Enumerator<BELPin> allBELPins = new Enumerator<BELPin>();
            StructList.Builder<Builder> belBuilders = siteType.initBels(site.getBELs().length);
            for(int j=0; j < site.getBELs().length; j++) {
                BEL bel = site.getBELs()[j];
                Builder belBuilder = belBuilders.get(j);
                belBuilder.setName(allStrings.getIndex(bel.getName()));
                belBuilder.setType(allStrings.getIndex(bel.getBELType()));
                PrimitiveList.Int.Builder belPinsBuilder = belBuilder.initPins(bel.getPins().length);
                for(int k=0; k < bel.getPins().length; k++) {
                    belPinsBuilder.set(k, allBELPins.size());
                    BELPin belPin = bel.getPin(k); 
                    allBELPins.addObject(belPin);
                }
                belBuilder.setCategory(getBELCategory(bel));
            }
            StructList.Builder<DeviceResources.Device.BELPin.Builder> belPinBuilders =
                    siteType.initBelPins(allBELPins.size());
            for(int j=0; j < allBELPins.size(); j++) {
                DeviceResources.Device.BELPin.Builder belPinBuilder = belPinBuilders.get(j);
                BELPin belPin = allBELPins.get(j);
                belPinBuilder.setName(allStrings.getIndex(belPin.getName()));
                belPinBuilder.setDir(getBELPinDirection(belPin));
                belPinBuilder.setBel(allStrings.getIndex(belPin.getBEL().getName()));
            }
            
            // SitePins
            int highestIndexInputPin = site.getHighestInputPinIndex();
            ArrayList<String> pinNames = new ArrayList<String>();
            for(int j=0; j < site.getSitePinCount(); j++) {
                String pinName = site.getPinName(j);
                pinNames.add(pinName);
            }
            siteType.setLastInput(highestIndexInputPin);
            StructList.Builder<SitePin.Builder> pins = siteType.initPins(pinNames.size());
            for(int j=0; j < pinNames.size(); j++) {
                SitePin.Builder pin = pins.get(j);
                pin.setName(allStrings.getIndex(pinNames.get(j)));
                pin.setDir(j <= highestIndexInputPin ? Direction.INPUT : Direction.OUTPUT);
                Integer siteWireIdx = site.getSiteWireIndex(pinNames.get(j));
                pin.setSitewire(siteWireIdx == null ? -1 : siteWireIdx);
            }
            
            // SitePIPs
            StructList.Builder<DeviceResources.Device.SitePIP.Builder> spBuilders = 
                    siteType.initSitePIPs(site.getSitePIPCount());
            for(int j=0; j < site.getSitePIPCount(); j++) {
                DeviceResources.Device.SitePIP.Builder spBuilder = spBuilders.get(j);
                SitePIP sitePIP = site.getSitePIP(j);
                spBuilder.setInpin(allBELPins.getIndex(sitePIP.getInputPin()));
                spBuilder.setOutpin(allBELPins.getIndex(sitePIP.getOutputPin()));
            }
            
            // SiteWires
            StructList.Builder<SiteWire.Builder> swBuilders = 
                    siteType.initSiteWires(site.getSiteWireCount());
            for(int j=0; j < site.getSiteWireCount(); j++) {
                SiteWire.Builder swBuilder = swBuilders.get(j);
                String siteWireName = site.getSiteWireName(j);
                swBuilder.setName(allStrings.getIndex(siteWireName));
                BELPin[] swPins = site.getBELPins(siteWireName);
                PrimitiveList.Int.Builder bpBuilders = swBuilder.initPins(swPins.length);
                for(int k=0; k < swPins.length; k++) {
                    bpBuilders.set(k, allBELPins.getIndex(swPins[k]));
                }
            }
            
            i++;
        }
    }

    public static void writeAllTileTypesToBuilder(Device device, DeviceResources.Device.Builder devBuilder) {
        StructList.Builder<TileType.Builder> tileTypesList = devBuilder.initTileTypeList(tileTypes.size());
        int i=0; 
        for(Entry<TileTypeEnum,Tile> e : tileTypes.entrySet()) {
            Tile tile = e.getValue();
            TileType.Builder tileType = tileTypesList.get(i);
            // name
            tileType.setName(allStrings.getIndex(e.getKey().name()));
            
            // siteTypes
            Site[] sites = tile.getSites();
            PrimitiveList.Int.Builder siteTypes = tileType.initSiteTypes(sites.length);
            for(int j=0; j < sites.length; j++) {
                siteTypes.set(j, allStrings.getIndex(sites[j].getSiteTypeEnum().name()));
            }
            
            // wires
            PrimitiveList.Int.Builder wires = tileType.initWires(tile.getWireCount());
            for(int j=0 ; j < tile.getWireCount(); j++) {
                wires.set(j, allStrings.getIndex(tile.getWireName(j)));
            }

            // pips
            ArrayList<PIP> pips = tile.getPIPs();
            StructList.Builder<DeviceResources.Device.PIP.Builder> pipBuilders =
                    tileType.initPips(pips.size());
            for(int j=0; j < pips.size(); j++) {
                DeviceResources.Device.PIP.Builder pipBuilder = pipBuilders.get(j);
                PIP pip = pips.get(j);
                pipBuilder.setWire0(pip.getStartWireIndex());
                pipBuilder.setWire1(pip.getEndWireIndex());
                pipBuilder.setDirectional(!pip.isBidirectional());
                if(pip.getPIPType() == PIPType.BI_DIRECTIONAL_BUFFERED20) {
                    pipBuilder.setBuffered20(true);
                } else if(pip.getPIPType() == PIPType.BI_DIRECTIONAL_BUFFERED21_BUFFERED20) {
                    pipBuilder.setBuffered20(true);
                    pipBuilder.setBuffered21(true);
                } else if(pip.getPIPType() == PIPType.DIRECTIONAL_BUFFERED21) {
                    pipBuilder.setBuffered21(true);
                }                
            }
            i++;
        }
        
    }

    public static void writeAllTilesToBuilder(Device device, DeviceResources.Device.Builder devBuilder) {
        Collection<Tile> tiles = device.getAllTiles();
        StructList.Builder<DeviceResources.Device.Tile.Builder> tileBuilders = 
                devBuilder.initTileList(tiles.size());
        
        int i=0; 
        for(Tile tile : tiles) {
            DeviceResources.Device.Tile.Builder tileBuilder = tileBuilders.get(i);
            tileBuilder.setName(allStrings.getIndex(tile.getName()));
            tileBuilder.setType(allStrings.getIndex(tile.getTileTypeEnum().name()));
            Site[] sites = tile.getSites();
            StructList.Builder<DeviceResources.Device.Site.Builder> siteBuilders = 
                    tileBuilder.initSites(sites.length);
            for(int j=0; j < sites.length; j++) {
                DeviceResources.Device.Site.Builder siteBuilder = siteBuilders.get(j);
                siteBuilder.setName(allStrings.getIndex(sites[j].getName()));
                siteBuilder.setType(allSiteTypes.getIndex(sites[j].getSiteTypeEnum().name()));
            }
            tileBuilder.setRow((short)tile.getRow());
            tileBuilder.setCol((short)tile.getColumn());
            tileBuilder.setTilePatIdx(tile.getTilePatternIndex());            
            i++;
        }
        
    }
    
    private static long makeKey(Tile tile, int wire) {
        long key = wire;
        key = (((long)tile.getUniqueAddress()) << 32) | key;  
        return key; 
    }
        
    public static void writeAllWiresAndNodesToBuilder(Device device, DeviceResources.Device.Builder devBuilder) {
        LongEnumerator allWires = new LongEnumerator();
        LongEnumerator allNodes = new LongEnumerator();
        
        
        for(Tile tile : device.getAllTiles()) {
            for(int i=0; i < tile.getWireCount(); i++) {
                Wire wire = new Wire(tile,i);
                allWires.addObject(makeKey(wire.getTile(), wire.getWireIndex()));
            }
            for(PIP p : tile.getPIPs()) {
                Node start = p.getStartNode();
                allNodes.addObject(makeKey(start.getTile(), start.getWire()));
                Node end = p.getEndNode();
                allNodes.addObject(makeKey(end.getTile(), end.getWire()));
            }
        }
        
        StructList.Builder<DeviceResources.Device.Wire.Builder> wireBuilders = 
                devBuilder.initWires(allWires.size());
        
        for(int i=0; i < allWires.size(); i++) {
            DeviceResources.Device.Wire.Builder wireBuilder = wireBuilders.get(i);
            long wireKey = allWires.get(i);
            Wire wire = new Wire(device.getTile((int)(wireKey >>> 32)), (int)(wireKey & 0xffffffff));
            //Wire wire = allWires.get(i);
            wireBuilder.setTile(allStrings.getIndex(wire.getTile().getName()));
            wireBuilder.setWire(wire.getWireIndex());
        }
        
        StructList.Builder<DeviceResources.Device.Node.Builder> nodeBuilders = 
                devBuilder.initNodes(allNodes.size());
        for(int i=0; i < allNodes.size(); i++) {
            DeviceResources.Device.Node.Builder nodeBuilder = nodeBuilders.get(i);
            //Node node = allNodes.get(i);
            long nodeKey = allNodes.get(i);
            Node node = new Node(device.getTile((int)(nodeKey >>> 32)), (int)(nodeKey & 0xffffffff));
            Wire[] wires = node.getAllWiresInNode();
            PrimitiveList.Int.Builder wBuilders = nodeBuilder.initWires(wires.length);
            for(int k=0; k < wires.length; k++) {
                wBuilders.set(k, allWires.getIndex(makeKey(wires[k].getTile(), wires[k].getWireIndex())));
            }
        }
    }

}
