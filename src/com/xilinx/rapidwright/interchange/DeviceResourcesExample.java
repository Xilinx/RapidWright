package com.xilinx.rapidwright.interchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.StructList.Reader;
import org.capnproto.Text;
import org.capnproto.TextList;

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
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BEL.Builder;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BELCategory;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SitePin;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteWire;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class DeviceResourcesExample {

    private static Enumerator<String> allStrings = new Enumerator<>();
    private static Enumerator<String> allSiteTypes = new Enumerator<>();

    private static HashMap<TileTypeEnum,Tile> tileTypes = new HashMap<>(); 
    private static HashMap<SiteTypeEnum,Site> siteTypes = new HashMap<>();
    
    public static void populateEnumerations(Device device) {
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
    }

    
    public static MessageBuilder createDeviceResourcesMessage(Device device, CodePerfTracker t) {
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
        t.stop();
        
        int idx = devBuilder.getTileList().get(0).getName();
        System.out.println("Tile 0 Name: " + idx);
        System.out.println("  " + devBuilder.getStrList().get(idx).toString());
        return message;        

    }
    
    public static void writeAllStringsToBuilder(DeviceResources.Device.Builder devBuilder) {
        int stringCount = allStrings.size();
        TextList.Builder strList = devBuilder.initStrList(stringCount);
        for(int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(allStrings.get(i)));
        }        
    }

    private static BELCategory getBELCategory(BEL bel) {
        BELClass category = bel.getBELClass();
        if(category == BELClass.BEL)
            return BELCategory.LOGIC;
        if(category == BELClass.RBEL)
            return BELCategory.ROUTING;
        if(category == BELClass.PORT)
            return BELCategory.SITE_PORT;
        return BELCategory._NOT_IN_SCHEMA;
    }
    
    private static Direction getBELPinDirection(BELPin belPin) {
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
                pin.setSitewire(site.getSiteWireIndex(pinNames.get(j)));
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
        
    public static void writeAllWiresAndNodesToBuilder(Device device, DeviceResources.Device.Builder devBuilder) {
        Enumerator<Wire> allWires = new Enumerator<>();
        Enumerator<Node> allNodes = new Enumerator<>();
        
        for(Tile tile : device.getAllTiles()) {
            for(int i=0; i < tile.getWireCount(); i++) {
                Wire wire = new Wire(tile,i);
                allWires.addObject(wire);
            }
            for(PIP p : tile.getPIPs()) {
                allNodes.addObject(p.getStartNode());
                allNodes.addObject(p.getEndNode());
            }
        }
        
        StructList.Builder<DeviceResources.Device.Wire.Builder> wireBuilders = 
                devBuilder.initWires(allWires.size());
        
        for(int i=0; i < allWires.size(); i++) {
            DeviceResources.Device.Wire.Builder wireBuilder = wireBuilders.get(i);
            Wire wire = allWires.get(i);
            wireBuilder.setTile(allStrings.getIndex(wire.getTile().getName()));
            wireBuilder.setWire(wire.getWireIndex());
        }
        
        StructList.Builder<DeviceResources.Device.Node.Builder> nodeBuilders = 
                devBuilder.initNodes(allNodes.size());
        for(int i=0; i < allNodes.size(); i++) {
            DeviceResources.Device.Node.Builder nodeBuilder = nodeBuilders.get(i);
            Node node = allNodes.get(i);
            Wire[] wires = node.getAllWiresInNode();
            PrimitiveList.Int.Builder wBuilders = nodeBuilder.initWires(wires.length);
            for(int k=0; k < wires.length; k++) {
                wBuilders.set(k, allWires.getIndex(wires[k]));
            }
        }
    }

    
    private static boolean expect(String gold, String test) {
        if(!gold.equals(test)) throw new RuntimeException("ERROR: Device mismatch: gold=" + gold 
                + ", test=" + test);
        return true;
    }
    
    private static boolean expect(int gold, int test) {
        if(gold != test) throw new RuntimeException("ERROR: Device mismatch: gold=" + gold 
                + ", test=" + test);
        return true;
    }
   
    public static boolean verifyDeviceResources(String devResFileName, String deviceName) {
        allStrings.clear();

        Device device = Device.getDevice(deviceName);
        
        FileInputStream fis = null;
        DeviceResources.Device.Reader dReader = null;
        
        try {
            fis = new java.io.FileInputStream(devResFileName);
            ReaderOptions readerOptions = new ReaderOptions(1024L*1024L*128L, 64);
            MessageReader readMsg = SerializePacked.readFromUnbuffered((fis).getChannel(), readerOptions);
            fis.close();
            
            dReader = readMsg.getRoot(DeviceResources.Device.factory);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        int strCount = dReader.getStrList().size();
        TextList.Reader reader = dReader.getStrList();
        for(int i=0; i < strCount; i++) {
            String str = reader.get(i).toString();
            allStrings.addObject(str);
        }
        
        // Create a lookup map for tile types
        HashMap<String,TileType.Reader> tileTypeMap = new HashMap<String, TileType.Reader>();
        for(int i=0; i < dReader.getTileTypeList().size(); i++) {
            TileType.Reader ttReader = dReader.getTileTypeList().get(i);
            tileTypeMap.put(allStrings.get(ttReader.getName()), ttReader);
        }
        
        expect(device.getName(), dReader.getName().toString());

        Reader<DeviceResources.Device.Tile.Reader> tilesReader = dReader.getTileList();
        expect(device.getAllTiles().size(), tilesReader.size());

        int tileCount = tilesReader.size();
        for(int i=0; i < tileCount; i++) {
            DeviceResources.Device.Tile.Reader tileReader = tilesReader.get(i);
            String tileName = allStrings.get(tileReader.getName());
            Tile tile = device.getTile(tileName);
            expect(tile.getTileTypeEnum().name(), allStrings.get(tileReader.getType()));
            expect(tile.getRow(), tileReader.getRow());
            expect(tile.getColumn(), tileReader.getCol());

            expect(tile.getTilePatternIndex(), tileReader.getTilePatIdx()); 
            
            // Verify Sites
            expect(tile.getSites().length, tileReader.getSites().size());
            for(int j=0; j < tile.getSites().length; j++) {
                DeviceResources.Device.Site.Reader siteReader = tileReader.getSites().get(j);
                expect(tile.getSites()[j].getName(), allStrings.get(siteReader.getName()));
                int siteTypeIdx = siteReader.getType();
                expect(tile.getSites()[j].getSiteTypeEnum().name(),
                        allStrings.get(dReader.getSiteTypeList().get(siteTypeIdx).getName()));
                verifySiteType(device, dReader, tile.getSites()[j], siteTypeIdx);
            }
            
            // Verify Tile Types
            TileType.Reader tileType = tileTypeMap.get(allStrings.get(tileReader.getType()));
            expect(tile.getTileTypeEnum().name(), allStrings.get(tileType.getName()));
            expect(tile.getWireCount(), tileType.getWires().size());
            PrimitiveList.Int.Reader wiresReader = tileType.getWires();
            for(int j=0; j < tile.getWireCount(); j++) {
                expect(tile.getWireName(j), allStrings.get(wiresReader.get(j)));
            }
            PrimitiveList.Int.Reader siteTypesReader = tileType.getSiteTypes();
            expect(tile.getSites().length, siteTypesReader.size());
            for(int j=0; j < tile.getSites().length; j++) {
                expect(tile.getSites()[j].getSiteTypeEnum().name(),
                        allStrings.get(siteTypesReader.get(j)));
            }
            ArrayList<PIP> pips = tile.getPIPs();
            Reader<DeviceResources.Device.PIP.Reader> pipsReader = tileType.getPips();
            expect(pips.size(), pipsReader.size());

            for(int j=0; j < pips.size(); j++) {
                DeviceResources.Device.PIP.Reader pipReader = pipsReader.get(j);
                PIP pip = pips.get(j);
                expect(pip.getStartWireIndex(), pipReader.getWire0());
                expect(pip.getEndWireIndex(), pipReader.getWire1());
                if(pip.isBidirectional() == pipReader.getDirectional()) {
                    throw new RuntimeException("PIP Directionality mismatch " + pip);
                }
                PIPType type = pip.getPIPType();
                 
                boolean isBuffered20 = 
                        type == PIPType.BI_DIRECTIONAL_BUFFERED20 || 
                        type == PIPType.BI_DIRECTIONAL_BUFFERED21_BUFFERED20;
                boolean isBuffered21 = 
                        type == PIPType.BI_DIRECTIONAL_BUFFERED21_BUFFERED20 || 
                        type == PIPType.DIRECTIONAL_BUFFERED21;
                if(pipReader.getBuffered20() != isBuffered20) {
                    throw new RuntimeException("PIP Buffered20 mismatch " + pip);
                }
                if(pipReader.getBuffered21() != isBuffered21) {
                    throw new RuntimeException("PIP Buffered21 mismatch " + pip);
                }
            }
        }
        
        return true;
    }
    
    private static HashSet<SiteTypeEnum> verifiedSiteTypes = new HashSet<SiteTypeEnum>(); 
    
    private static boolean verifySiteType(Device device, DeviceResources.Device.Reader dReader,
            Site site, int siteTypeIdx) {
        if(verifiedSiteTypes.contains(site.getSiteTypeEnum())) {
            return true;
        }
        SiteType.Reader stReader = dReader.getSiteTypeList().get(siteTypeIdx);
        expect(site.getSiteTypeEnum().name(), allStrings.get(stReader.getName()));
        Reader<SitePin.Reader> sitePinsReader = stReader.getPins();
        int pinCount = sitePinsReader.size();
        int highestIndexInputPin = site.getHighestInputPinIndex();
        for(int i=0; i < pinCount; i++) {
            String pinName = site.getPinName(i);
            SitePin.Reader spReader = sitePinsReader.get(i);
            expect(pinName, allStrings.get(spReader.getName()));
            Direction dir = spReader.getDir();
            boolean isInput = site.isInputPin(pinName); 
            boolean isOutput = site.isOutputPin(pinName); 
            if( (isInput != (dir == Direction.INPUT)) || (isOutput != (dir == Direction.OUTPUT)) ){
                throw new RuntimeException("ERROR: Mismatch on site pin direction");
            }
            expect(site.getSiteWireIndex(pinName), spReader.getSitewire());
        }
        expect(highestIndexInputPin, stReader.getLastInput());
        
        Reader<DeviceResources.Device.BELPin.Reader> stBPReader = stReader.getBelPins();
        Reader<DeviceResources.Device.BEL.Reader> stBELReader = stReader.getBels();
        expect(site.getBELs().length, stBELReader.size());
        for(int i=0; i < site.getBELs().length; i++) {
            BEL bel = site.getBELs()[i];
            DeviceResources.Device.BEL.Reader belReader = stBELReader.get(i); 
            expect(bel.getName(),allStrings.get(belReader.getName()));
            expect(bel.getBELType(),allStrings.get(belReader.getType()));
            expect(getBELCategory(bel).name(), belReader.getCategory().name());
            PrimitiveList.Int.Reader belPinsReader = belReader.getPins();
            expect(bel.getPins().length, belPinsReader.size());
            for(int j=0; j < bel.getPins().length; j++) {
                BELPin belPin = bel.getPin(j);
                DeviceResources.Device.BELPin.Reader bpReader = 
                        stBPReader.get(belPinsReader.get(j));
                expect(belPin.getName(), allStrings.get(bpReader.getName()));
                expect(getBELPinDirection(belPin).name(), bpReader.getDir().name());
                expect(bel.getName(), allStrings.get(bpReader.getBel()));
            }
        }
        
        // Check SitePIPs 
        expect(site.getSitePIPCount(), stReader.getSitePIPs().size());
        for(int i=0; i < site.getSitePIPCount(); i++) {
            SitePIP sitePIP = site.getSitePIP(i);
            DeviceResources.Device.SitePIP.Reader spReader = stReader.getSitePIPs().get(i);
            DeviceResources.Device.BELPin.Reader in = stReader.getBelPins().get(spReader.getInpin());
            DeviceResources.Device.BELPin.Reader out = stReader.getBelPins().get(spReader.getOutpin());
            expect(sitePIP.getInputPinName(), allStrings.get(in.getName()));
            expect(sitePIP.getOutputPinName(), allStrings.get(out.getName()));
        }
        
        // SiteWires
        Reader<DeviceResources.Device.SiteWire.Reader> siteWiresReader = stReader.getSiteWires();
        expect(site.getSiteWireCount(), siteWiresReader.size());
        for(int i=0; i < site.getSiteWireCount(); i++) {
            String siteWireName = site.getSiteWireName(i);
            DeviceResources.Device.SiteWire.Reader siteWire = siteWiresReader.get(i);
            expect(siteWireName, allStrings.get(siteWire.getName()));
            BELPin[] belPins = site.getBELPins(i);
            HashSet<String> belPinStrings = new HashSet<String>();
            for(BELPin belPin : belPins) {
                belPinStrings.add(belPin.getBEL().getName() + "/" + belPin.getName() +
                        "/" + getBELPinDirection(belPin));
            }
            for(int j=0; j < siteWire.getPins().size(); j++) {
                DeviceResources.Device.BELPin.Reader bp = 
                        stBPReader.get(siteWire.getPins().get(j));
                String belName = allStrings.get(bp.getBel());
                String pinName = allStrings.get(bp.getName());
                Direction dir = bp.getDir();
                String belPinString = belName + "/" + pinName + "/" + dir;
                if(!belPinStrings.remove(belPinString)) {
                    throw new RuntimeException("Mismatch with belpin: " + belPinString);
                }
            }
        }
        
        
        verifiedSiteTypes.add(site.getSiteTypeEnum());
        return true;
    }
    
    
    
    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Device Resources Dump");
        t.useGCToTrackMemory(true);

        // Create device resource file if it doesn't exist
        String capnProtoFileName = args[0] + ".device";
        if(!new File(capnProtoFileName).exists()) {
            //MessageGenerator.waitOnAnyKey();
            t.start("Load Device");
            Device device = Device.getDevice(args[0]);
            t.stop();
            // Write Netlist to Cap'n Proto Serialization file
            MessageBuilder message = createDeviceResourcesMessage(device, t);
            t.start("Write File");
            FileOutputStream fo = new java.io.FileOutputStream(capnProtoFileName);
            SerializePacked.writeToUnbuffered(fo.getChannel(), message);
            fo.close();
            Device.releaseDeviceReferences();
            t.stop();
        }
        
        t.start("Verify file");
        // Verify device resources
        verifyDeviceResources(capnProtoFileName, args[0]);
        
        t.stop().printSummary();
    }
}
