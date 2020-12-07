package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.TextList;
import org.capnproto.PrimitiveList.Int;
import org.capnproto.StructList.Reader;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Grade;
import com.xilinx.rapidwright.device.Package;
import com.xilinx.rapidwright.device.PackagePin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PIPType;
import com.xilinx.rapidwright.device.PIPWires;
import com.xilinx.rapidwright.device.PseudoPIPHelper;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.interchange.EnumerateCellBelMapping;
import com.xilinx.rapidwright.interchange.CellBelMapping;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PrimToMacroExpansion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PseudoCell;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SitePin;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParentPins;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;

public class DeviceResourcesVerifier {
    private static Enumerator<String> allStrings;

    private static boolean expect(String gold, String test) {
        if(gold == null && test == null) return true;
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

        Design design = new Design();
        design.setPartName(deviceName);

        DeviceResources.Device.Reader dReader = null;
        ReaderOptions readerOptions = new ReaderOptions(1024L*1024L*1024L*2L, 64);
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
        Map<String,TileType.Reader> tileTypeMap = new HashMap<String, TileType.Reader>();
        Map<TileTypeEnum, TileType.Reader> tileTypeEnumMap = new HashMap<TileTypeEnum, TileType.Reader>();
        HashMap<String, StructList.Reader<DeviceResources.Device.PIP.Reader>> ttPIPMap = new HashMap<>();
        for(int i=0; i < dReader.getTileTypeList().size(); i++) {
            TileType.Reader ttReader = dReader.getTileTypeList().get(i);
            String name = allStrings.get(ttReader.getName());
            tileTypeMap.put(name, ttReader);
            TileTypeEnum tileTypeEnum = TileTypeEnum.valueOf(name);
            tileTypeEnumMap.put(tileTypeEnum, ttReader);
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
            Integer tileTypeIndex = tileReader.getType();
            TileType.Reader ttReader = dReader.getTileTypeList().get(tileTypeIndex);
            String tileTypeName = allStrings.get(ttReader.getName());
            expect(tile.getTileTypeEnum().name(), tileTypeName);
            expect(tile.getRow(), tileReader.getRow());
            expect(tile.getColumn(), tileReader.getCol());

            expect(tile.getTilePatternIndex(), tileReader.getTilePatIdx());

            // Verify Tile Types
            TileType.Reader tileType = tileTypeMap.get(tileTypeName);
            expect(tile.getTileTypeEnum().name(), allStrings.get(tileType.getName()));
            expect(tile.getWireCount(), tileType.getWires().size());
            PrimitiveList.Int.Reader wiresReader = tileType.getWires();
            for(int j=0; j < tile.getWireCount(); j++) {
                expect(tile.getWireName(j), allStrings.get(wiresReader.get(j)));
            }

            // Verify Sites
            expect(tile.getSites().length, tileReader.getSites().size());
            for(int j=0; j < tile.getSites().length; j++) {
                DeviceResources.Device.Site.Reader siteReader = tileReader.getSites().get(j);
                expect(tile.getSites()[j].getName(), allStrings.get(siteReader.getName()));
                int tileTypeSiteTypeIdx = siteReader.getType();
                int siteTypeIdx = tileType.getSiteTypes().get(tileTypeSiteTypeIdx).getPrimaryType();
                expect(tile.getSites()[j].getSiteTypeEnum().name(),
                        allStrings.get(stReaders.get(siteTypeIdx).getName()));
                verifySiteType(device, dReader, tile.getSites()[j], siteTypeIdx);
            }

            Reader<DeviceResources.Device.SiteTypeInTileType.Reader> siteTypesReader = tileType.getSiteTypes();

            expect(tile.getSites().length, siteTypesReader.size());
            for(int j=0; j < tile.getSites().length; j++) {
                DeviceResources.Device.SiteTypeInTileType.Reader siteTypeReader = siteTypesReader.get(j);
                SiteType.Reader stReader = stReaders.get(siteTypeReader.getPrimaryType());
                int siteTypeName = stReader.getName();
                Site site = tile.getSites()[j];
                expect(site.getSiteTypeEnum().name(),
                       allStrings.get(siteTypeName));

                PrimitiveList.Int.Reader pinToWires = siteTypeReader.getPrimaryPinsToTileWires();
                expect(site.getSitePinCount(), pinToWires.size());
                for(int k=0; k < site.getSitePinCount(); k++) {
                    String pinName = site.getPinName(k);
                    expect(allStrings.getIndex(pinName),
                           stReader.getPins().get(k).getName());
                    expect(allStrings.getIndex(site.getTileWireNameFromPinName(pinName)),
                           pinToWires.get(k));
                }

                SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
                expect(altSiteTypes.length, siteTypeReader.getAltPinsToPrimaryPins().size());
                for(int k=0; k < altSiteTypes.length; k++) {
                    SiteInst siteInst = design.createSiteInst("site_instance", altSiteTypes[k], site);
                    SiteType.Reader altStReader = stReaders.get(stReader.getAltSiteTypes().get(k));
                    expect(allStrings.getIndex(altSiteTypes[k].name()), altStReader.getName());

                    ParentPins.Reader parentPins = siteTypeReader.getAltPinsToPrimaryPins().get(k);

                    String[] altSitePins = siteInst.getSitePinNames();
                    Set<String> altSitePinSet = new HashSet<String>();
                    for(int l=0; l < altSitePins.length; ++l) {
                        altSitePinSet.add(altSitePins[l]);
                    }
                    expect(parentPins.getPins().size(), altSitePins.length);
                    expect(parentPins.getPins().size(), altStReader.getPins().size());

                    for(int l=0; l < parentPins.getPins().size(); l++) {
                        String sitePin = allStrings.get(altStReader.getPins().get(l).getName());
                        if(!altSitePinSet.contains(sitePin)) {
                            throw new RuntimeException("Site pin " + sitePin + " not found in site.");
                        }

                        String primSitePin = siteInst.getPrimarySitePinName(sitePin);
                        expect(site.getPinIndex(primSitePin), parentPins.getPins().get(l));
                    }

                    design.removeSiteInst(siteInst);
                }
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

                if(pipReader.hasPseudoCells()) {
                    PseudoPIPHelper pipHelper = PseudoPIPHelper.getPseudoPIPHelper(pip);
                    List<BELPin> goldBELPins = pipHelper.getUsedBELPins();
                    HashSet<BELPin> foundBELPins = new HashSet<BELPin>();
                    Site site = pipHelper.getTilePrototype().getSitePinFromWire(pipHelper.getStartWire()).getSite();
                    StructList.Reader<PseudoCell.Reader> pseudoCells = pipReader.getPseudoCells();
                    for(int k=0; k < pseudoCells.size(); k++) {
                        PseudoCell.Reader pseudoCell = pseudoCells.get(k);
                        String belName = allStrings.get(pseudoCell.getBel());
                        BEL bel = site.getBEL(belName);
                        Int.Reader pinNamesReader = pseudoCell.getPins();
                        for(int l=0; l < pinNamesReader.size(); l++) {
                            String pinName = allStrings.get(pinNamesReader.get(l));
                            BELPin testPin = bel.getPin(pinName);
                            foundBELPins.add(testPin);
                        }

                    }

                    for(BELPin goldBELPin : goldBELPins) {
                        if(goldBELPin.isSitePort()) continue;
                        if(!foundBELPins.remove(goldBELPin)) {
                            throw new RuntimeException("ERROR: BELPin " + goldBELPin.toString()
                                + " not found for pseudo PIP " + pipHelper.getTileTypeEnum() +"."
                                    + pipHelper.getPseudoPIPName());
                        }
                    }
                    if(foundBELPins.size() > 0) {
                        throw new RuntimeException("ERROR: Found unknown BELPins "+ foundBELPins
                                +" for pseudo PIP " + pipHelper.getPseudoPIPName() + " ");
                    }

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

        verifyCellBelPinMaps(allStrings, dReader, design);
        verifyPackages(allStrings, dReader, device);

        // Get examples for each site type to a site.
        Map<SiteTypeEnum, Site> siteTypes = new HashMap<SiteTypeEnum, Site>();
        for(Tile tile : device.getAllTiles()) {
            for(Site site : tile.getSites()) {
                siteTypes.put(site.getSiteTypeEnum(), site);
                SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
                for(SiteTypeEnum altSiteType : altSiteTypes) {
                    siteTypes.put(altSiteType, site);
                }
            }
        }
        ConstantDefinitions.verifyConstants(allStrings, device, design, siteTypes, dReader.getConstants(), tileTypeEnumMap);

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
                throw new RuntimeException("ERROR: Mismatch on site pin direction, site pin " + pinName + " for site " + site.getName());
            }
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

        //SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
        //PrimitiveList.Int.Reader altSiteTypesReader = stReader.getAltSiteTypes();
        //expect(altSiteTypes.length, altSiteTypesReader.size());

        verifiedSiteTypes.add(site.getSiteTypeEnum());
        return true;
    }

    static private void verifyCellBelPinMap(Map<SiteTypeEnum, List<Site>> siteMap, CellBelMapping cellBelMap, EDIFCell topLevelCell, EDIFCell cell, Design design) {
        EDIFCellInst cellInst = new EDIFCellInst("test", cell, topLevelCell);
        Cell physCell = design.createCell("test", cellInst);

        List<Map.Entry<SiteTypeEnum, String>> entries = new ArrayList<>();

        Map<SiteTypeEnum,Set<String>> sites = physCell.getCompatiblePlacements();
        Set<SiteTypeEnum> siteTypes = new HashSet<SiteTypeEnum>();
        siteTypes.addAll(sites.keySet());
        siteTypes.retainAll(siteMap.keySet());

        Map<SiteTypeEnum,Set<String>> sitesFromDev = cellBelMap.getCompatiblePlacements(cell.getName());

        expect(siteTypes.size(), sitesFromDev.size());
        if(!siteTypes.equals(sitesFromDev.keySet())) {
            throw new RuntimeException(String.format(
                        "Cell %s -> set of site types does not match",
                        cell.getName()));
        }

        for(SiteTypeEnum siteType : siteTypes) {
            Set<String> bels = sites.get(siteType);
            Set<String> belsFromDev = sitesFromDev.get(siteType);
            if(!bels.equals(belsFromDev)) {
                throw new RuntimeException(String.format(
                            "Cell %s -> BELs for site type %s doesn't match",
                            cell.getName(), siteType.name()));
            }
        }

        for (Map.Entry<SiteTypeEnum,Set<String>> site : sites.entrySet()) {
            for(String bel : site.getValue()) {
                entries.add(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(site.getKey(), bel));
            }
        }

        design.removeCell(physCell);
        topLevelCell.removeCellInst(cellInst);
        physCell = null;
        cellInst = null;

        for(Map.Entry<SiteTypeEnum, String> possibleSite : entries) {
            SiteTypeEnum siteType = possibleSite.getKey();
            String bel = possibleSite.getValue();
            if(!siteMap.containsKey(siteType)) {
                continue;
            }

            for(List<String> parameters : EnumerateCellBelMapping.getParametersFor(cell.getName())) {
                String[] parameterArray = parameters.toArray(new String[parameters.size()]);
                Map<String, String> pinMappingFromDev = cellBelMap.getPinMappingsP2L(
                        cell.getName(),
                        siteType,
                        bel,
                        parameterArray);

                String parametersStr = new String();
                for(String parameter : parameters) {
                    parametersStr = parametersStr + " " + parameter;
                }

                for(Site site : siteMap.get(siteType)) {
                    SiteInst siteInst = design.createSiteInst("test_site", siteType, site);
                    physCell = design.createAndPlaceCell("test", Unisim.valueOf(cell.getName()), site.getName() + "/" + bel, parameterArray);

                    Map<String, String> pinMapping = new HashMap<String, String>();

                    // TODO: Disabled because of https://github.com/Xilinx/RapidWright/issues/101
                    //for(Map.Entry<String, String> pinMap : physCell.getPinMappingsL2P().entrySet()) {
                    //    pinMapping.add(pinMap);
                    //}

                    for(Map.Entry<String, String> pinMap : physCell.getPinMappingsP2L().entrySet()) {
                        pinMapping.put(pinMap.getKey(), pinMap.getValue());
                    }

                    if(!pinMapping.equals(pinMappingFromDev)) {
                        throw new RuntimeException(String.format(
                            "Cell %s -> BEL pins for site type %s and parameters %s doesn't match",
                            cell.getName(), siteType.name(), parametersStr));
                    }

                    design.removeCell(physCell);
                    design.removeSiteInst(siteInst);
                    topLevelCell.removeCellInst("test");
                }
            }
        }
    }

    static private void verifyCellBelPinMaps(Enumerator<String> allStrings, DeviceResources.Device.Reader dReader, Design design) {
        EDIFLibrary prims = Design.getPrimitivesLibrary(design.getDevice().getName());
        EDIFLibrary library = new EDIFLibrary("work");

        EDIFNetlist netlist = new EDIFNetlist("netlist");
        netlist.setDevice(design.getDevice());
        netlist.addLibrary(library);
        netlist.addLibrary(prims);

        EDIFCell topLevelCell = new EDIFCell(library, "top");

        EDIFDesign edifDesign = new EDIFDesign("design");
        edifDesign.setTopCell(topLevelCell);

        Map<SiteTypeEnum, List<Site>> siteMap = EnumerateCellBelMapping.createSiteMap(design.getDevice());

        EDIFLibrary macros = Design.getMacroPrimitives(design.getDevice().getSeries());
        Set<String> macroCells = new HashSet<String>();
        for(EDIFCell cell : macros.getCells()) {
            macroCells.add(cell.getName());
        }

        CellBelMapping cellBelMap = new CellBelMapping(allStrings, dReader.getCellBelMap());
        for(EDIFCell cell : prims.getCells()) {
            if(!macroCells.contains(cell.getName())) {
                verifyCellBelPinMap(siteMap, cellBelMap, topLevelCell, cell, design);
            }
        }
    }

    static private void verifyPackages(Enumerator<String> allStrings, DeviceResources.Device.Reader dReader, Device device) {
        StructList.Reader<DeviceResources.Device.Package.Reader> packagesObj = dReader.getPackages();
        Set<String> packages = device.getPackages();
        expect(packages.size(), packagesObj.size());

        Set<String> packagesFromReader = new HashSet<String>();
        for(DeviceResources.Device.Package.Reader packageObj : packagesObj) {
            String packageName = allStrings.get(packageObj.getName());
            packagesFromReader.add(packageName);
        }

        if(!packagesFromReader.equals(packages)) {
            throw new RuntimeException("Packages doesn't match");
        }

        for(DeviceResources.Device.Package.Reader packageObj : packagesObj) {
            String packageName = allStrings.get(packageObj.getName());
            Package pack = device.getPackage(packageName);

            expect(pack.getName(), packageName);

            Set<String> packagePinsFromReader = new HashSet<String>();
            for(DeviceResources.Device.Package.PackagePin.Reader packagePinObj : packageObj.getPackagePins()) {
                packagePinsFromReader.add(allStrings.get(packagePinObj.getPackagePin()));
            }

            Set<String> packagePins = new HashSet<String>();
            packagePins.addAll(pack.getPackagePinMap().keySet());

            expect(packagePins.size(), packagePinsFromReader.size());
            if(!packagePins.equals(packagePinsFromReader)) {
                throw new RuntimeException("Package pins doesn't match");
            }

            for(DeviceResources.Device.Package.PackagePin.Reader packagePinObj : packageObj.getPackagePins()) {
                String packagePinName = allStrings.get(packagePinObj.getPackagePin());
                PackagePin packagePin = pack.getPackagePinMap().get(packagePinName);

                expect(packagePin.getName(), packagePinName);

                Site site = packagePin.getSite();
                if(site == null) {
                    if(packagePinObj.getSite().isSite()) {
                        throw new RuntimeException("Has site when no site is expected?");
                    }
                    if(packagePinObj.getBel().isBel()) {
                        throw new RuntimeException("Has BEL when no site is expected?");
                    }
                } else {
                    if(!packagePinObj.getSite().isSite()) {
                        throw new RuntimeException("Has site when site is expected?");
                    }
                    if(!packagePinObj.getBel().isBel()) {
                        throw new RuntimeException("Has BEL when site is expected?");
                    }

                    expect(site.getName(), allStrings.get(packagePinObj.getSite().getSite()));
                    expect("PAD", allStrings.get(packagePinObj.getBel().getBel()));
                }
            }

            Map<String, Integer> gradesMap = new HashMap<String, Integer>();
            int i = 0;
            for(Grade grade : pack.getGrades()) {
                gradesMap.put(grade.getName(), i);
                i += 1;
            }

            expect(pack.getGrades().length, gradesMap.size());
            expect(pack.getGrades().length, packageObj.getGrades().size());

            for(DeviceResources.Device.Package.Grade.Reader gradeObj : packageObj.getGrades()) {
                String gradeName = allStrings.get(gradeObj.getName());
                int gradeIndex = gradesMap.get(gradeName);
                Grade grade = pack.getGrades()[gradeIndex];

                expect(grade.getName(), gradeName);
                expect(grade.getSpeedGrade(), allStrings.get(gradeObj.getSpeedGrade()));
                expect(grade.getTemperatureGrade(), allStrings.get(gradeObj.getTemperatureGrade()));
            }
        }
    }
}
