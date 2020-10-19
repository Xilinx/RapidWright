package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.TextList;
import org.capnproto.StructList.Reader;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PIPType;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PrimToMacroExpansion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SitePin;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;

public class DeviceResourcesVerifier {
    private static Enumerator<String> allStrings;
    
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
   
    public static boolean verifyDeviceResources(String devResFileName, String deviceName) throws IOException {
        allStrings = new Enumerator<String>();
        verifiedSiteTypes = new HashSet<SiteTypeEnum>();

        Device device = Device.getDevice(deviceName);
        
        DeviceResources.Device.Reader dReader = null;
        ReaderOptions readerOptions = new ReaderOptions(1024L*1024L*512L, 64);
        MessageReader readMsg = null;
        readMsg = Interchange.readInterchangeFile(devResFileName, readerOptions);

        dReader = readMsg.getRoot(DeviceResources.Device.factory);
        
        int strCount = dReader.getStrList().size();
        TextList.Reader reader = dReader.getStrList();
        for(int i=0; i < strCount; i++) {
            String str = reader.get(i).toString();
            allStrings.addObject(str);
        }
        
        // Create a lookup map for tile types
        HashMap<String,TileType.Reader> tileTypeMap = new HashMap<String, TileType.Reader>();
        HashMap<String, StructList.Reader<DeviceResources.Device.PIP.Reader>> ttPIPMap = new HashMap<>();
        for(int i=0; i < dReader.getTileTypeList().size(); i++) {
            TileType.Reader ttReader = dReader.getTileTypeList().get(i);
            String name = allStrings.get(ttReader.getName());
            tileTypeMap.put(name, ttReader);
            ttPIPMap.put(name, ttReader.getPips());
        }
        
        expect(device.getName(), dReader.getName().toString());

        Reader<DeviceResources.Device.Tile.Reader> tilesReader = dReader.getTileList();
        expect(device.getAllTiles().size(), tilesReader.size());

        Reader<SiteType.Reader> stReaders = dReader.getSiteTypeList();
        
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
                        allStrings.get(stReaders.get(siteTypeIdx).getName()));
                verifySiteType(device, dReader, tile.getSites()[j], siteTypeIdx);
            }
            
            // Verify Tile Types
            String tileTypeName = allStrings.get(tileReader.getType());
            TileType.Reader tileType = tileTypeMap.get(tileTypeName);
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
            Reader<DeviceResources.Device.PIP.Reader> pipsReader = ttPIPMap.get(tileTypeName);
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
        
        Netlist.Reader primLibs = dReader.getPrimLibs();
        EDIFNetlist primsAndMacros = LogNetlistReader.getLogNetlist(primLibs, true);
        Set<String> libsFound = primsAndMacros.getLibrariesMap().keySet();
        for(String libExpected : new String[] {EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME, 
            device.getSeries()+"_"+EDIFTools.MACRO_PRIMITIVES_LIB}) {
            if(!libsFound.remove(libExpected)) {
                throw new RuntimeException("Missing expected library: " + libExpected);
            }
        }
        int size = libsFound.size(); 
        if(size > 0) {
            throw new RuntimeException("Found the following unexpected librar"+
                    (size > 1 ? "ies" : "y")+": " + libsFound);
        }
        for(EDIFLibrary lib : primsAndMacros.getLibraries()) {
            EDIFLibrary reference = lib.isHDIPrimitivesLibrary() ? Design.getPrimitivesLibrary() : 
                                                    Design.getMacroPrimitives(device.getSeries());
            Set<String> cellsFound = lib.getCellMap().keySet();
            Set<String> cellsExpected = reference.getCellMap().keySet();
            
            if(!cellsFound.containsAll(cellsExpected)) {
                cellsExpected.removeAll(cellsFound);
                throw new RuntimeException("Missing some cells expected in library " +
                        lib.getName() + ": " + cellsExpected);
            }
            if(!cellsExpected.containsAll(cellsFound)) {
                cellsFound.removeAll(cellsExpected);
                throw new RuntimeException("Extra cells found in library " +
                        lib.getName() + ": " + cellsFound);
            }
        }
        
        StructList.Reader<PrimToMacroExpansion.Reader> exceptionMap = dReader.getExceptionMap();
        int mapSize = exceptionMap.size();
        for(int i=0; i < mapSize; i++) {
            PrimToMacroExpansion.Reader entry = exceptionMap.get(i);
            String primName = allStrings.get(entry.getPrimName());
            String macroName = allStrings.get(entry.getMacroName());
            if(!EDIFNetlist.macroExpandExceptionMap.get(primName).equals(macroName)) {
                throw new RuntimeException("Exception map mismatch: " + 
                        "("+ primName+"-->" +macroName+") does not match expected mapping ("+
                        primName+"-->"+EDIFNetlist.macroExpandExceptionMap.get(primName)+")");
            }
        }
        
        return true;
    }
    
    private static HashSet<SiteTypeEnum> verifiedSiteTypes; 
    
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
            Integer siteWireIndex = site.getSiteWireIndex(pinName);
            expect(siteWireIndex == null ? -1 : siteWireIndex, spReader.getSitewire());
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
            expect(DeviceResourcesWriter.getBELCategory(bel).name(), belReader.getCategory().name());
            PrimitiveList.Int.Reader belPinsReader = belReader.getPins();
            expect(bel.getPins().length, belPinsReader.size());
            for(int j=0; j < bel.getPins().length; j++) {
                BELPin belPin = bel.getPin(j);
                DeviceResources.Device.BELPin.Reader bpReader = 
                        stBPReader.get(belPinsReader.get(j));
                expect(belPin.getName(), allStrings.get(bpReader.getName()));
                expect(DeviceResourcesWriter.getBELPinDirection(belPin).name(), bpReader.getDir().name());
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
                        "/" + DeviceResourcesWriter.getBELPinDirection(belPin));
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
}
