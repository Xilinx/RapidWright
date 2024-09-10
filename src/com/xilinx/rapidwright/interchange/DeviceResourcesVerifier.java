/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.PrimitiveList.Int;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.StructList.Reader;
import org.capnproto.TextList;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.VivadoProp;
import com.xilinx.rapidwright.design.VivadoPropType;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Grade;
import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PIPType;
import com.xilinx.rapidwright.device.Package;
import com.xilinx.rapidwright.device.PackagePin;
import com.xilinx.rapidwright.device.PseudoPIPHelper;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BELInverter;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellInversion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellParameterDefinition;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellPinInversion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterDefinition;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterDefinitions;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterFormat;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterMapEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterMapRule;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParentPins;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PrimToMacroExpansion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PseudoCell;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SitePin;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.util.Pair;

public class DeviceResourcesVerifier {
    private static StringEnumerator allStrings;

    private static boolean expect(boolean gold, boolean test) {
        if (gold != test) throw new RuntimeException("ERROR: Device mismatch: gold=" + gold
                + ", test=" + test);
        return true;
    }

    private static boolean expect(String gold, String test) {
        if (gold == null && test == null) return true;
        if (!gold.equals(test)) throw new RuntimeException("ERROR: Device mismatch: gold=" + gold
                + ", test=" + test);
        return true;
    }

    private static boolean expect(int gold, int test) {
        if (gold != test) throw new RuntimeException("ERROR: Device mismatch: gold=" + gold
                + ", test=" + test);
        return true;
    }

    private static void verifyBelPin(StructList.Reader<DeviceResources.Device.BELPin.Reader> belPins, BELPin pin, int belPinIndex) {
        DeviceResources.Device.BELPin.Reader belPin = belPins.get(belPinIndex);
        expect(pin.getName(), allStrings.get(belPin.getName()));
        BELPin.Direction dir = pin.getDir();
        if (dir == BELPin.Direction.INPUT) {
            expect(Direction.INPUT.name(), belPin.getDir().name());
        } else if (dir == BELPin.Direction.OUTPUT) {
            expect(Direction.OUTPUT.name(), belPin.getDir().name());
        } else if (dir == BELPin.Direction.BIDIRECTIONAL) {
            expect(Direction.INOUT.name(), belPin.getDir().name());
        } else {
            expect(Direction._NOT_IN_SCHEMA.name(), belPin.getDir().name());
        }
        expect(pin.getBEL().getName(), allStrings.get(belPin.getBel()));
    }

    public static boolean verifyDeviceResources(String devResFileName, String deviceName) throws IOException {
        allStrings = new StringEnumerator();
        verifiedSiteTypes = new HashSet<SiteTypeEnum>();

        Device device = Device.getDevice(deviceName);
        Series series = device.getSeries();

        Design design = new Design();
        design.setPartName(deviceName);

        DeviceResources.Device.Reader dReader = null;
        ReaderOptions readerOptions = new ReaderOptions(1024L * 1024L * 1024L * 64L, 64);
        MessageReader readMsg = null;
        readMsg = Interchange.readInterchangeFile(devResFileName, readerOptions);

        dReader = readMsg.getRoot(DeviceResources.Device.factory);

        boolean containsRoutingResources = dReader.getNodes().size() > 0;
        
        int strCount = dReader.getStrList().size();
        TextList.Reader reader = dReader.getStrList();
        for (int i=0; i < strCount; i++) {
            String str = reader.get(i).toString();
            allStrings.addObject(str);
        }

        // Create a lookup map for tile types
        Map<String, TileType.Reader> tileTypeMap = new HashMap<String, TileType.Reader>();
        int tileTypeCount = dReader.getTileTypeList().size();
        Map<TileTypeEnum, TileType.Reader> tileTypeEnumMap = new HashMap<TileTypeEnum, TileType.Reader>();
        HashMap<String, StructList.Reader<DeviceResources.Device.PIP.Reader>> ttPIPMap = new HashMap<>();
        for (int i=0; i < tileTypeCount; i++) {
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

        // Get examples for each site type to a site.
        Map<SiteTypeEnum, Site> siteTypes = new HashMap<SiteTypeEnum, Site>();
        for (Tile tile : device.getAllTiles()) {
            for (Site site : tile.getSites()) {
                siteTypes.put(site.getSiteTypeEnum(), site);
                SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
                for (SiteTypeEnum altSiteType : altSiteTypes) {
                    siteTypes.put(altSiteType, site);
                }
            }
        }

        Reader<SiteType.Reader> stReaders = dReader.getSiteTypeList();
        for (SiteType.Reader stReader : stReaders) {
            Set<Integer> belPinIndicies = new HashSet<Integer>();
            SiteTypeEnum siteTypeEnum = SiteTypeEnum.valueOf(allStrings.get(stReader.getName()));

            Site site = siteTypes.get(siteTypeEnum);
            SiteInst siteInst = design.createSiteInst("site_instance", siteTypeEnum, site);

            StructList.Reader<DeviceResources.Device.BELPin.Reader> belPinsReader = stReader.getBelPins();
            StructList.Reader<DeviceResources.Device.BEL.Reader> belsReader = stReader.getBels();
            BEL[] bels = siteInst.getBELs();
            expect(bels.length, belsReader.size());
            for (int i=0; i < bels.length; i++) {
                BEL bel = bels[i];
                DeviceResources.Device.BEL.Reader belReader = belsReader.get(i);

                expect(bel.getName(), allStrings.get(belReader.getName()));
                expect(bel.getBELType(), allStrings.get(belReader.getType()));

                BELPin[] belPins = bel.getPins();
                PrimitiveList.Int.Reader pinsReader = belReader.getPins();
                expect(belPins.length, pinsReader.size());
                for (int j=0; j < belPins.length; j++) {
                    BELPin pin = belPins[j];
                    int belPinIndex = pinsReader.get(j);
                    belPinIndicies.add(belPinIndex);
                    verifyBelPin(belPinsReader, pin, belPinIndex);
                }

                expect(DeviceResourcesWriter.getBELCategory(bel).name(), belReader.getCategory().name());

                if (bel.canInvert() && !bel.getName().equals("SRCMXINV") && !bel.getName().equals("SRCFPMXINV")) {
                    expect(true, belReader.hasInverting());
                    BELInverter.Reader belInverter = belReader.getInverting();

                    BELPin nonInverting = bel.getNonInvertingPin();
                    belPinIndicies.add(belInverter.getNonInvertingPin());
                    verifyBelPin(belPinsReader, nonInverting, belInverter.getNonInvertingPin());

                    BELPin inverting = bel.getInvertingPin();
                    belPinIndicies.add(belInverter.getInvertingPin());
                    verifyBelPin(belPinsReader, inverting, belInverter.getInvertingPin());
                } else {
                    expect(false, belReader.hasInverting());
                }
            }

            StructList.Reader<DeviceResources.Device.SitePin.Reader> pinsReader = stReader.getPins();
            String[] pinNames = siteInst.getSitePinNames();
            expect(pinNames.length, pinsReader.size());

            int highestIndexInputPin = siteInst.getHighestSitePinInputIndex();
            expect(highestIndexInputPin, stReader.getLastInput());

            Set<String> pinNameSet = new HashSet<String>();
            for (String pin : pinNames) {
                pinNameSet.add(pin);
            }
            for (int i=0; i < pinNames.length; i++) {
                DeviceResources.Device.SitePin.Reader pinReader = pinsReader.get(i);
                String pinName = allStrings.get(pinReader.getName());
                if (!pinNameSet.contains(pinName)) {
                    throw new RuntimeException("Site pin " + pinName + " not found in site.");
                }

                String primarySitePinName = pinName;
                int sitePinIndex = site.getPinIndex(pinName);
                if (sitePinIndex == -1) {
                    primarySitePinName = siteInst.getPrimarySitePinName(pinName);
                    sitePinIndex = site.getPinIndex(primarySitePinName);
                }

                SitePinInst pin = siteInst.getSitePinInst(pinNames[i]);
                Direction dir = pinReader.getDir();
                if (i <= highestIndexInputPin) {
                    expect(Direction.INPUT.name(), dir.name());
                } else {
                    expect(Direction.OUTPUT.name(), dir.name());
                }

                BEL bel = siteInst.getBEL(pinName);
                BELPin[] belPins = bel.getPins();
                if (belPins.length != 1) {
                    throw new RuntimeException("Only expected 1 BEL pin on site pin BEL.");
                }

                BELPin belPin = belPins[0];
                belPinIndicies.add(pinReader.getBelpin());
                verifyBelPin(belPinsReader, belPin, pinReader.getBelpin());
            }

            Set<String> siteWires = new HashSet<String>();
            for (String siteWire : siteInst.getSiteWires()) {
                siteWires.add(siteWire);
            }

            StructList.Reader<DeviceResources.Device.SiteWire.Reader> siteWiresReader = stReader.getSiteWires();
            expect(siteWires.size(), siteWiresReader.size());

            for (DeviceResources.Device.SiteWire.Reader siteWireReader : siteWiresReader) {
                String siteWireName = allStrings.get(siteWireReader.getName());
                if (!siteWires.contains(siteWireName)) {
                    throw new RuntimeException("Site wire " + siteWireName + " not found in site.");
                }

                BELPin[] belPins = siteInst.getSiteWirePins(siteWireName);
                PrimitiveList.Int.Reader wiresReader = siteWireReader.getPins();
                expect(belPins.length, wiresReader.size());

                for (int i=0; i < belPins.length; i++) {
                    belPinIndicies.add(wiresReader.get(i));
                    verifyBelPin(belPinsReader, belPins[i], wiresReader.get(i));
                }
            }

            expect(belPinIndicies.size(), belPinsReader.size());

            StructList.Reader<DeviceResources.Device.SitePIP.Reader> sitePipsReader = stReader.getSitePIPs();
            SitePIP[] sitePIPs = siteInst.getSitePIPs();
            expect(sitePIPs.length, sitePipsReader.size());
            Map<String, SitePIP> sitePIPMap = new HashMap<>();
            for (SitePIP sitePIP : sitePIPs) {
                sitePIPMap.put(sitePIP.toString(),sitePIP);
            }


            for (DeviceResources.Device.SitePIP.Reader spReader : sitePipsReader) {
                DeviceResources.Device.BELPin.Reader bpReader = belPinsReader.get(spReader.getInpin());
                DeviceResources.Device.BELPin.Reader bpOutReader = belPinsReader.get(spReader.getOutpin());
                String inputBel = allStrings.get(bpReader.getBel());
                String inputBelPin = allStrings.get(bpReader.getName());
                String outputBelPin = allStrings.get(bpOutReader.getName());
                SitePIP sitePIP = sitePIPMap.get(inputBel + "." + inputBelPin + "->>" + outputBelPin);

                verifyBelPin(belPinsReader, sitePIP.getInputPin(), spReader.getInpin());
                verifyBelPin(belPinsReader, sitePIP.getOutputPin(), spReader.getOutpin());
            }

            design.removeSiteInst(siteInst);
        }

        int tileCount = tilesReader.size();
        for (int i=0; i < tileCount; i++) {
            DeviceResources.Device.Tile.Reader tileReader = tilesReader.get(i);
            String tileName = allStrings.get(tileReader.getName());
            Tile tile = device.getTile(tileName);
            Integer tileTypeIndex = tileReader.getType();
            TileType.Reader ttReader = dReader.getTileTypeList().get(tileTypeIndex);
            String tileTypeName = allStrings.get(ttReader.getName());
            expect(tile.getTileTypeEnum().name(), tileTypeName);
            expect(tile.getRow(), tileReader.getRow());
            expect(tile.getColumn(), tileReader.getCol());
            // Note: Tile.getUniqueAddress() is equivalent to the INDEX property on a Vivado Tile object
            expect(tile.getUniqueAddress(), i);

            // Verify Tile Types
            TileType.Reader tileType = tileTypeMap.get(tileTypeName);
            expect(tile.getTileTypeEnum().name(), allStrings.get(tileType.getName()));
            expect(tile.getWireCount(), tileType.getWires().size());
            PrimitiveList.Int.Reader wiresReader = tileType.getWires();
            for (int j=0; j < tile.getWireCount(); j++) {
                expect(tile.getWireName(j), allStrings.get(wiresReader.get(j)));
            }

            // Verify Sites
            expect(tile.getSites().length, tileReader.getSites().size());
            for (int j=0; j < tile.getSites().length; j++) {
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
            for (int j=0; j < tile.getSites().length; j++) {
                DeviceResources.Device.SiteTypeInTileType.Reader siteTypeReader = siteTypesReader.get(j);
                SiteType.Reader stReader = stReaders.get(siteTypeReader.getPrimaryType());
                int siteTypeName = stReader.getName();
                Site site = tile.getSites()[j];
                expect(site.getSiteTypeEnum().name(),
                       allStrings.get(siteTypeName));

                PrimitiveList.Int.Reader pinToWires = siteTypeReader.getPrimaryPinsToTileWires();
                expect(site.getSitePinCount(), pinToWires.size());

                StructList.Reader<SitePin.Reader> sitePins = stReader.getPins();
                for (int k=0; k < site.getSitePinCount(); k++) {
                    String pinName = site.getPinName(k);
                    expect(allStrings.getIndex(pinName), sitePins.get(k).getName());
                    expect(allStrings.getIndex(site.getTileWireNameFromPinName(pinName)),
                           pinToWires.get(k));
                }

                SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
                expect(altSiteTypes.length, siteTypeReader.getAltPinsToPrimaryPins().size());
                for (int k=0; k < altSiteTypes.length; k++) {
                    SiteInst siteInst = design.createSiteInst("site_instance", altSiteTypes[k], site);
                    SiteType.Reader altStReader = stReaders.get(stReader.getAltSiteTypes().get(k));
                    expect(allStrings.getIndex(altSiteTypes[k].name()), altStReader.getName());

                    ParentPins.Reader parentPins = siteTypeReader.getAltPinsToPrimaryPins().get(k);
                    Int.Reader parentPinIdxs = parentPins.getPins();

                    String[] altSitePinNames = siteInst.getSitePinNames();
                    Set<String> altSitePinSet = new HashSet<String>();
                    for (int l=0; l < altSitePinNames.length; ++l) {
                        altSitePinSet.add(altSitePinNames[l]);
                    }
                    expect(parentPinIdxs.size(), altSitePinNames.length);
                    expect(parentPinIdxs.size(), altStReader.getPins().size());

                    StructList.Reader<SitePin.Reader> altSitePins = altStReader.getPins();
                    for (int l = 0; l < parentPinIdxs.size(); l++) {
                        String sitePin = allStrings.get(altSitePins.get(l).getName());
                        if (!altSitePinSet.contains(sitePin)) {
                            throw new RuntimeException("Site pin " + sitePin + " not found in site.");
                        }

                        String primSitePin = siteInst.getPrimarySitePinName(sitePin);
                        expect(site.getPinIndex(primSitePin), parentPinIdxs.get(l));
                    }

                    design.removeSiteInst(siteInst);
                }
            }

            ArrayList<PIP> pips = tile.getPIPs();
            Reader<DeviceResources.Device.PIP.Reader> pipsReader = ttPIPMap.get(tileTypeName);
            expect(pips.size(), pipsReader.size());

            for (int j=0; j < pips.size(); j++) {
                DeviceResources.Device.PIP.Reader pipReader = pipsReader.get(j);
                PIP pip = pips.get(j);
                expect(pip.getStartWireIndex(), pipReader.getWire0());
                expect(pip.getEndWireIndex(), pipReader.getWire1());
                if (pip.isBidirectional() == pipReader.getDirectional()) {
                    throw new RuntimeException("PIP Directionality mismatch " + pip);
                }
                PIPType type = pip.getPIPType();

                boolean isBuffered20 =
                        type == PIPType.BI_DIRECTIONAL_BUFFERED20 ||
                        type == PIPType.BI_DIRECTIONAL_BUFFERED21_BUFFERED20;
                boolean isBuffered21 =
                        type == PIPType.BI_DIRECTIONAL_BUFFERED21_BUFFERED20 ||
                        type == PIPType.DIRECTIONAL_BUFFERED21;
                if (pipReader.getBuffered20() != isBuffered20) {
                    throw new RuntimeException("PIP Buffered20 mismatch " + pip);
                }
                if (pipReader.getBuffered21() != isBuffered21) {
                    throw new RuntimeException("PIP Buffered21 mismatch " + pip);
                }

                if (pipReader.hasPseudoCells()) {
                    PseudoPIPHelper pipHelper = PseudoPIPHelper.getPseudoPIPHelper(pip);
                    List<BELPin> goldBELPins = pipHelper.getUsedBELPins();
                    HashSet<BELPin> foundBELPins = new HashSet<BELPin>();
                    Site site = pipHelper.getTilePrototype().getSitePinFromWire(pipHelper.getStartWire()).getSite();
                    StructList.Reader<PseudoCell.Reader> pseudoCells = pipReader.getPseudoCells();
                    for (int k=0; k < pseudoCells.size(); k++) {
                        PseudoCell.Reader pseudoCell = pseudoCells.get(k);
                        String belName = allStrings.get(pseudoCell.getBel());
                        BEL bel = site.getBEL(belName);
                        Int.Reader pinNamesReader = pseudoCell.getPins();
                        for (int l=0; l < pinNamesReader.size(); l++) {
                            String pinName = allStrings.get(pinNamesReader.get(l));
                            BELPin testPin = bel.getPin(pinName);
                            foundBELPins.add(testPin);
                        }

                    }

                    for (BELPin goldBELPin : goldBELPins) {
                        if (!foundBELPins.remove(goldBELPin)) {
                            throw new RuntimeException("ERROR: BELPin " + goldBELPin.toString()
                                + " not found for pseudo PIP " + pipHelper.getTileTypeEnum() +"."
                                    + pipHelper.getPseudoPIPName());
                        }
                    }
                    if (foundBELPins.size() > 0) {
                        throw new RuntimeException("ERROR: Found unknown BELPins "+ foundBELPins
                                +" for pseudo PIP " + pipHelper.getPseudoPIPName() + " ");
                    }

                }
            }
        }

        Netlist.Reader primLibs = dReader.getPrimLibs();
        LogNetlistReader netlistReader = new LogNetlistReader(allStrings, new HashMap<String, String>() {{
                    put(LogNetlistWriter.DEVICE_PRIMITIVES_LIB, EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
                }}
            );
        EDIFNetlist primsAndMacros = netlistReader.readLogNetlist(primLibs,
                /*skipTopStuff=*/true, /*expandMacros=*/false);

        Set<String> libsFound = new HashSet<String>();
        libsFound.addAll(primsAndMacros.getLibrariesMap().keySet());
        for (String libExpected : new String[] {EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME,
            LogNetlistWriter.DEVICE_MACROS_LIB}) {
            if (!libsFound.remove(libExpected)) {
                throw new RuntimeException("Missing expected library: " + libExpected);
            }
        }
        int size = libsFound.size();
        if (size > 0) {
            throw new RuntimeException("Found the following unexpected librar"+
                    (size > 1 ? "ies" : "y")+": " + libsFound);
        }

        Map<String, Pair<String, EnumSet<IOStandard>>> macroCollapseExceptionMap =
                EDIFNetlist.macroCollapseExceptionMap.getOrDefault(series, Collections.emptyMap());
        Set<Unisim> unisimsExpected = new HashSet<Unisim>();
        for (EDIFLibrary lib : primsAndMacros.getLibraries()) {
            EDIFLibrary reference = lib.isHDIPrimitivesLibrary() ? Design.getPrimitivesLibrary(design.getDevice().getName()) :
                                                    new EDIFLibrary(Design.getMacroPrimitives(series));

            if (!lib.isHDIPrimitivesLibrary()) {
                // Remove unused macros from reference
                EDIFLibrary prims = Design.getPrimitivesLibrary(design.getDevice().getName());
                DeviceResourcesWriter.removeUnusedMacros(reference, prims);
            }

            Set<String> cellsFound = new HashSet<String>();
            cellsFound.addAll(lib.getCellMap().keySet());

            Set<String> cellsExpected = new HashSet<String>();
            cellsExpected.addAll(reference.getCellMap().keySet());

            for (String cellName : reference.getCellMap().keySet()) {
                if (!lib.isHDIPrimitivesLibrary()) {
                    Pair<String,EnumSet<IOStandard>> entry = macroCollapseExceptionMap.get(cellName);
                    if (entry != null) {
                        cellName = entry.getFirst();
                    }
                }

                unisimsExpected.add(Unisim.valueOf(cellName));
            }

            if (lib.isHDIPrimitivesLibrary()) {
                EDIFLibrary macros = Design.getMacroPrimitives(device.getSeries());
                Set<String> dupCells = new HashSet<String>();
                for (String cell : cellsExpected) {
                    if (macros.getCell(cell) != null) {
                        dupCells.add(cell);
                    }
                }

                cellsExpected.removeAll(dupCells);
            }

            if (!cellsFound.containsAll(cellsExpected)) {
                cellsExpected.removeAll(cellsFound);
                throw new RuntimeException("Missing some cells expected in library " +
                        lib.getName() + ": " + cellsExpected);
            }
            if (!cellsExpected.containsAll(cellsFound)) {
                cellsFound.removeAll(cellsExpected);
                throw new RuntimeException("Extra cells found in library " +
                        lib.getName() + ": " + cellsFound);
            }
        }

        verifyCellInversions(device, dReader, unisimsExpected);

        StructList.Reader<PrimToMacroExpansion.Reader> exceptionMap = dReader.getExceptionMap();
        Map<String,MacroParamRule[]> rulesMap = MacroParamMappingRules.macroRules.get(series);
        Map<String, Pair<String, EnumSet<IOStandard>>> macroExpandExceptionMap =
                EDIFNetlist.macroExpandExceptionMap.getOrDefault(series, Collections.emptyMap());
        int mapSize = exceptionMap.size();
        for (int i=0; i < mapSize; i++) {
            PrimToMacroExpansion.Reader entry = exceptionMap.get(i);
            String macroName = allStrings.get(entry.getMacroName());
            if (entry.hasParameters()) {
                String primName = allStrings.get(entry.getPrimName());
                Pair<String,EnumSet<IOStandard>> mapping = macroExpandExceptionMap.get(primName);
                EnumSet<IOStandard> ioStdSet = mapping.getSecond();
                if (!mapping.getFirst().equals(macroName)) {
                    throw new RuntimeException("Exception map mismatch: " +
                            "("+ primName+"-->" +macroName+") does not match expected mapping ("+
                            primName+"-->"+EDIFNetlist.macroExpandExceptionMap.get(primName)+")");
                }

                Reader<PropertyMap.Entry.Reader> parameterReader = entry.getParameters();
                if (ioStdSet.size() != parameterReader.size()) {
                    throw new RuntimeException("Exception map parameter set mismatch: differing number "
                        + "of IOStandard property values, found " + parameterReader.size()
                        + ", expected " + mapping.getSecond().size() );
                }
                for (PropertyMap.Entry.Reader paramReader : entry.getParameters()) {
                    expect(EDIFNetlist.IOSTANDARD_PROP, allStrings.get(paramReader.getKey()));
                    IOStandard ioStandardValue = IOStandard.valueOf(allStrings.get(paramReader.getTextValue()));
                    if (!ioStdSet.contains(ioStandardValue)) {
                        throw new RuntimeException("ERROR: IOStandard " + ioStandardValue
                                + " not found in exception map." );
                    }
                }
            }
            MacroParamRule[] rules = rulesMap.get(macroName);
            if (entry.hasParamMapping() && rules != null) {
                Reader<ParameterMapRule.Reader> rulesReader = entry.getParamMapping();
                expect(rules.length, rulesReader.size());
                for (int j=0; j < rules.length; j++) {
                    MacroParamRule rule = rules[j];
                    ParameterMapRule.Reader ruleReader = rulesReader.get(j);
                    expect(rule.getInstParam(), allStrings.get(ruleReader.getInstParam()));
                    expect(rule.getInstName(), allStrings.get(ruleReader.getInstName()));
                    expect(rule.getPrimParam(), allStrings.get(ruleReader.getPrimParam()));
                    if (rule.getBitSlice() != null) {
                        PrimitiveList.Int.Reader bitSliceReader = ruleReader.getBitSlice();
                        expect(rule.getBitSlice().length, bitSliceReader.size());
                        for (int k=0; k < rule.getBitSlice().length; k++) {
                            expect(rule.getBitSlice()[k], bitSliceReader.get(k));
                        }
                    } else if (rule.getTableLookup() != null) {
                        Reader<ParameterMapEntry.Reader> tableReader = ruleReader.getTableLookup();
                        expect(rule.getTableLookup().length, tableReader.size());
                        for (int k=0; k < rule.getTableLookup().length; k++) {
                            ParameterMapEntry.Reader tableEntry = tableReader.get(k);
                            expect(rule.getTableLookup()[k].from, allStrings.get(tableEntry.getFrom()));
                            expect(rule.getTableLookup()[k].to, allStrings.get(tableEntry.getTo()));
                        }
                    }
                }
            }
        }

        verifyCellBelPinMaps(allStrings, dReader, design);
        verifyPackages(allStrings, dReader, device);

        if (containsRoutingResources) {
            ConstantDefinitions.verifyConstants(allStrings, device, design, siteTypes, dReader.getConstants(), tileTypeEnumMap);            
        }


        return true;
    }

    private static HashSet<SiteTypeEnum> verifiedSiteTypes;

    private static boolean verifySiteType(Device device, DeviceResources.Device.Reader dReader,
            Site site, int siteTypeIdx) {
        if (verifiedSiteTypes.contains(site.getSiteTypeEnum())) {
            return true;
        }
        SiteType.Reader stReader = dReader.getSiteTypeList().get(siteTypeIdx);
        expect(site.getSiteTypeEnum().name(), allStrings.get(stReader.getName()));
        Reader<SitePin.Reader> sitePinsReader = stReader.getPins();
        int pinCount = sitePinsReader.size();
        int highestIndexInputPin = site.getHighestInputPinIndex();
        for (int i=0; i < pinCount; i++) {
            String pinName = site.getPinName(i);
            SitePin.Reader spReader = sitePinsReader.get(i);
            expect(pinName, allStrings.get(spReader.getName()));
            Direction dir = spReader.getDir();
            boolean isInput = site.isInputPin(pinName);
            boolean isOutput = site.isOutputPin(pinName);
            if ( (isInput != (dir == Direction.INPUT)) || (isOutput != (dir == Direction.OUTPUT)) ) {
                throw new RuntimeException("ERROR: Mismatch on site pin direction, site pin " + pinName + " for site " + site.getName());
            }
        }
        expect(highestIndexInputPin, stReader.getLastInput());

        Reader<DeviceResources.Device.BELPin.Reader> stBPReader = stReader.getBelPins();
        Reader<DeviceResources.Device.BEL.Reader> stBELReader = stReader.getBels();
        expect(site.getBELs().length, stBELReader.size());
        for (int i=0; i < site.getBELs().length; i++) {
            BEL bel = site.getBELs()[i];
            DeviceResources.Device.BEL.Reader belReader = stBELReader.get(i);
            expect(bel.getName(),allStrings.get(belReader.getName()));
            expect(bel.getBELType(),allStrings.get(belReader.getType()));
            expect(DeviceResourcesWriter.getBELCategory(bel).name(), belReader.getCategory().name());
            PrimitiveList.Int.Reader belPinsReader = belReader.getPins();
            expect(bel.getPins().length, belPinsReader.size());
            for (int j=0; j < bel.getPins().length; j++) {
                BELPin belPin = bel.getPin(j);
                DeviceResources.Device.BELPin.Reader bpReader =
                        stBPReader.get(belPinsReader.get(j));
                expect(belPin.getName(), allStrings.get(bpReader.getName()));
                expect(DeviceResourcesWriter.getBELPinDirection(belPin).name(), bpReader.getDir().name());
                expect(bel.getName(), allStrings.get(bpReader.getBel()));
            }

            if (bel.canInvert()) {
                expect(belReader.isInverting(), true);
                expect(belReader.isNonInverting(), false);

                BELInverter.Reader belInverter = belReader.getInverting();
                DeviceResources.Device.BELPin.Reader bpReader;

                bpReader = stBPReader.get(belInverter.getNonInvertingPin());
                expect(bel.getNonInvertingPin().getName(), allStrings.get(bpReader.getName()));

                bpReader = stBPReader.get(belInverter.getInvertingPin());
                expect(bel.getInvertingPin().getName(), allStrings.get(bpReader.getName()));
            } else {
                expect(belReader.isInverting(), false);
                expect(belReader.isNonInverting(), true);
            }
        }

        // Check SitePIPs
        expect(site.getSitePIPCount(), stReader.getSitePIPs().size());
        for (int i=0; i < site.getSitePIPCount(); i++) {
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
        for (int i=0; i < site.getSiteWireCount(); i++) {
            String siteWireName = site.getSiteWireName(i);
            DeviceResources.Device.SiteWire.Reader siteWire = siteWiresReader.get(i);
            expect(siteWireName, allStrings.get(siteWire.getName()));
            BELPin[] belPins = site.getBELPins(i);
            HashSet<String> belPinStrings = new HashSet<String>();
            for (BELPin belPin : belPins) {
                belPinStrings.add(belPin.getBEL().getName() + "/" + belPin.getName() +
                        "/" + DeviceResourcesWriter.getBELPinDirection(belPin));
            }
            for (int j=0; j < siteWire.getPins().size(); j++) {
                DeviceResources.Device.BELPin.Reader bp =
                        stBPReader.get(siteWire.getPins().get(j));
                String belName = allStrings.get(bp.getBel());
                String pinName = allStrings.get(bp.getName());
                Direction dir = bp.getDir();
                String belPinString = belName + "/" + pinName + "/" + dir;
                if (!belPinStrings.remove(belPinString)) {
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

        Map<SiteTypeEnum,Set<String>> sites = physCell.getCompatiblePlacements(design.getDevice());
        Set<SiteTypeEnum> siteTypes = new HashSet<SiteTypeEnum>();
        siteTypes.addAll(sites.keySet());
        siteTypes.retainAll(siteMap.keySet());

        Map<SiteTypeEnum,Set<String>> sitesFromDev = cellBelMap.getCompatiblePlacements(cell.getName());

        expect(siteTypes.size(), sitesFromDev.size());
        if (!siteTypes.equals(sitesFromDev.keySet())) {
            throw new RuntimeException(String.format(
                        "Cell %s -> set of site types does not match",
                        cell.getName()));
        }

        for (SiteTypeEnum siteType : siteTypes) {
            Set<String> bels = sites.get(siteType);
            Set<String> belsFromDev = sitesFromDev.get(siteType);
            if (!bels.equals(belsFromDev)) {
                throw new RuntimeException(String.format(
                            "Cell %s -> BELs for site type %s doesn't match",
                            cell.getName(), siteType.name()));
            }
        }

        for (Map.Entry<SiteTypeEnum,Set<String>> site : sites.entrySet()) {
            for (String bel : site.getValue()) {
                entries.add(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(site.getKey(), bel));
            }
        }

        Series series = design.getDevice().getSeries();
        design.removeCell(physCell);
        topLevelCell.removeCellInst(cellInst);
        physCell = null;
        cellInst = null;

        for (Map.Entry<SiteTypeEnum, String> possibleSite : entries) {
            SiteTypeEnum siteType = possibleSite.getKey();
            String bel = possibleSite.getValue();
            if (!siteMap.containsKey(siteType)) {
                continue;
            }

            for (List<String> parameters : EnumerateCellBelMapping.getParametersFor(series, cell.getName())) {
                String[] parameterArray = parameters.toArray(new String[parameters.size()]);
                Map<String, String> pinMappingFromDev = cellBelMap.getPinMappingsP2L(
                        cell.getName(),
                        siteType,
                        bel,
                        parameterArray);

                String parametersStr = new String();
                for (String parameter : parameters) {
                    parametersStr = parametersStr + " " + parameter;
                }

                for (Site site : siteMap.get(siteType)) {
                    SiteInst siteInst = design.createSiteInst("test_site", siteType, site);
                    physCell = design.createAndPlaceCell("test", Unisim.valueOf(cell.getName()), site.getName() + "/" + bel, parameterArray);

                    Map<String, String> pinMapping = new HashMap<String, String>();

                    for (Map.Entry<String, Set<String>> pinMap : physCell.getPinMappingsL2P().entrySet()) {
                        for (String physPin : pinMap.getValue()) {
                            pinMapping.put(physPin, pinMap.getKey());
                        }
                    }

                    pinMapping.putAll(physCell.getPinMappingsP2L());

                    if (!pinMapping.equals(pinMappingFromDev)) {
                        for (String belPin : pinMappingFromDev.keySet()) {
                            if (!pinMapping.containsKey(belPin)) {
                                System.out.printf(" - %s in DeviceResources, not in RapidWright\n", belPin);
                            }
                        }
                        for (String belPin : pinMapping.keySet()) {
                            if (!pinMappingFromDev.containsKey(belPin)) {
                                System.out.printf(" - %s in RapidWright, not in DeviceResources\n", belPin);
                            }
                        }

                        for (String belPin : pinMapping.keySet()) {
                            if (!pinMappingFromDev.containsKey(belPin)) {
                                continue;
                            }

                            if (!pinMapping.get(belPin).equals(pinMappingFromDev.get(belPin))) {
                                System.out.printf(" - %s != %s\n", pinMapping.get(belPin), pinMappingFromDev.get(belPin));
                            }
                        }

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

    static private void verifyParameterDefinitiosn(StringEnumerator allStrings, DeviceResources.Device.Reader dReader, Design design) {
        EDIFLibrary prims = Design.getPrimitivesLibrary(design.getDevice().getName());

        Set<String> cellsWithParameters = new HashSet<String>();
        for (EDIFCell cell : prims.getCells()) {
            String cellTypeName = cell.getName();

            Map<String,VivadoProp> defaultCellProperties = Design.getDefaultCellProperties(design.getDevice().getSeries(), cellTypeName);
            if (defaultCellProperties != null && defaultCellProperties.size() > 0) {
                cellsWithParameters.add(cellTypeName);
            }
        }

        ParameterDefinitions.Reader paramDefs = dReader.getParameterDefs();
        expect(paramDefs.getCells().size(), cellsWithParameters.size());

        for (CellParameterDefinition.Reader cellParamDef : paramDefs.getCells()) {
            String cellType = allStrings.get(cellParamDef.getCellType());

            if (!cellsWithParameters.contains(cellType)) {
                throw new RuntimeException(String.format(
                            "Cell %s has parameters in DeviceResources, but not in RapidWright?",
                            cellType));
            }

            Map<String,VivadoProp> defaultCellProperties = Design.getDefaultCellProperties(design.getDevice().getSeries(), cellType);
            expect(cellParamDef.getParameters().size(), defaultCellProperties.size());

            for (ParameterDefinition.Reader paramDef : cellParamDef.getParameters()) {
                String paramName = allStrings.get(paramDef.getName());

                // default.key and name should be the same.
                expect(paramDef.getName(), paramDef.getDefault().getKey());

                if (!defaultCellProperties.containsKey(paramName)) {
                    throw new RuntimeException(String.format(
                            "Cell %s has parameter %s in DeviceResources, but not in RapidWright?",
                            cellType, paramName));
                }
                if (!paramDef.getDefault().isTextValue()) {
                    throw new RuntimeException(String.format(
                            "Cell %s parameter %s default is not a textValue",
                            cellType, paramName));
                }

                VivadoProp propValue = defaultCellProperties.get(paramName);

                expect(allStrings.get(paramDef.getDefault().getTextValue()), propValue.getValue());

                ParameterFormat expected;
                if (propValue.getType() == VivadoPropType.BINARY) {
                    expected = ParameterFormat.VERILOG_BINARY;
                } else if (propValue.getType() == VivadoPropType.BOOL) {
                    expected = ParameterFormat.BOOLEAN;
                } else if (propValue.getType() == VivadoPropType.DOUBLE) {
                    expected = ParameterFormat.FLOATING_POINT;
                } else if (propValue.getType() == VivadoPropType.HEX) {
                    expected = ParameterFormat.VERILOG_HEX;
                } else if (propValue.getType() == VivadoPropType.INT) {
                    expected = ParameterFormat.INTEGER;
                } else if (propValue.getType() == VivadoPropType.STRING) {
                    expected = ParameterFormat.STRING;
                } else {
                    throw new RuntimeException(String.format("Unknown VivadoPropType %s", propValue.getType().name()));
                }

                if (expected != paramDef.getFormat()) {
                    throw new RuntimeException(String.format("Expected ParameterFormat %s got %s",
                                expected.name(), paramDef.getFormat().name()));
                }
            }

        }
    }

    static private void verifyCellBelPinMaps(StringEnumerator allStrings, DeviceResources.Device.Reader dReader, Design design) {
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
        for (EDIFCell cell : macros.getCells()) {
            macroCells.add(cell.getName());
        }

        CellBelMapping cellBelMap = new CellBelMapping(allStrings, dReader.getCellBelMap());
        for (EDIFCell cell : prims.getCells()) {
            if (!macroCells.contains(cell.getName())) {
                verifyCellBelPinMap(siteMap, cellBelMap, topLevelCell, cell, design);
            }
        }
    }

    static private void verifyPackages(StringEnumerator allStrings, DeviceResources.Device.Reader dReader, Device device) {
        StructList.Reader<DeviceResources.Device.Package.Reader> packagesObj = dReader.getPackages();
        Set<String> packages = device.getPackages();
        expect(packages.size(), packagesObj.size());

        Set<String> packagesFromReader = new HashSet<String>();
        for (DeviceResources.Device.Package.Reader packageObj : packagesObj) {
            String packageName = allStrings.get(packageObj.getName());
            packagesFromReader.add(packageName);
        }

        if (!packagesFromReader.equals(packages)) {
            throw new RuntimeException("Packages doesn't match");
        }

        for (DeviceResources.Device.Package.Reader packageObj : packagesObj) {
            String packageName = allStrings.get(packageObj.getName());
            Package pack = device.getPackage(packageName);

            expect(pack.getName(), packageName);

            Set<String> packagePinsFromReader = new HashSet<String>();
            for (DeviceResources.Device.Package.PackagePin.Reader packagePinObj : packageObj.getPackagePins()) {
                packagePinsFromReader.add(allStrings.get(packagePinObj.getPackagePin()));
            }

            Set<String> packagePins = new HashSet<String>();
            packagePins.addAll(pack.getPackagePinMap().keySet());

            expect(packagePins.size(), packagePinsFromReader.size());
            if (!packagePins.equals(packagePinsFromReader)) {
                throw new RuntimeException("Package pins doesn't match");
            }

            for (DeviceResources.Device.Package.PackagePin.Reader packagePinObj : packageObj.getPackagePins()) {
                String packagePinName = allStrings.get(packagePinObj.getPackagePin());
                PackagePin packagePin = pack.getPackagePinMap().get(packagePinName);

                expect(packagePin.getName(), packagePinName);

                Site site = packagePin.getSite();
                if (site == null) {
                    if (packagePinObj.getSite().isSite()) {
                        throw new RuntimeException("Has site when no site is expected?");
                    }
                } else {
                    if (!packagePinObj.getSite().isSite()) {
                        throw new RuntimeException("Has site when site is expected?");
                    }
                    expect(site.getName(), allStrings.get(packagePinObj.getSite().getSite()));
                }

                BEL bel = packagePin.getBEL();
                if (bel == null) {
                    if (packagePinObj.getBel().isBel()) {
                        throw new RuntimeException("Has BEL when no site is expected?");
                    }
                } else {
                    if (!packagePinObj.getBel().isBel()) {
                        throw new RuntimeException("Has BEL when site is expected?");
                    }

                    expect(bel.getName(), allStrings.get(packagePinObj.getBel().getBel()));
                }
            }

            Map<String, Integer> gradesMap = new HashMap<String, Integer>();
            int i = 0;
            for (Grade grade : pack.getGrades()) {
                gradesMap.put(grade.getName(), i);
                i += 1;
            }

            expect(pack.getGrades().length, gradesMap.size());
            expect(pack.getGrades().length, packageObj.getGrades().size());

            for (DeviceResources.Device.Package.Grade.Reader gradeObj : packageObj.getGrades()) {
                String gradeName = allStrings.get(gradeObj.getName());
                int gradeIndex = gradesMap.get(gradeName);
                Grade grade = pack.getGrades()[gradeIndex];

                expect(grade.getName(), gradeName);
                expect(grade.getSpeedGrade(), allStrings.get(gradeObj.getSpeedGrade()));
                expect(grade.getTemperatureGrade(), allStrings.get(gradeObj.getTemperatureGrade()));
            }
        }
    }

    static private void verifyCellInversions(Device device, DeviceResources.Device.Reader dReader, Set<Unisim> unisimsExpected) {
        Set<Unisim> unisimsWithInversions = new HashSet<Unisim>();
        Set<Unisim> unisimsInReader = new HashSet<Unisim>();

        for (Unisim unisim : unisimsExpected) {
            Map<String, String> invertiblePinMap = DesignTools.getInvertiblePinMap(device.getSeries(), unisim);
            if (invertiblePinMap != null && invertiblePinMap.size() > 0) {
                unisimsWithInversions.add(unisim);
            }
        }

        Map<String, String> macroToPrims = new HashMap<String, String>();
        for (PrimToMacroExpansion.Reader entry : dReader.getExceptionMap()) {
            String primName = allStrings.get(entry.getPrimName());
            String macroName = allStrings.get(entry.getMacroName());
            macroToPrims.put(macroName, primName);
        }

        for (CellInversion.Reader cellInversion : dReader.getCellInversions()) {
            String cellName = allStrings.get(cellInversion.getCell());

            String primName = macroToPrims.get(cellName);
            if (primName != null) {
                cellName = primName;
            }

            Unisim unisim = Unisim.valueOf(cellName);

            unisimsInReader.add(unisim);

            Map<String, String> invertiblePinMap = DesignTools.getInvertiblePinMap(device.getSeries(), unisim);

            StructList.Reader<CellPinInversion.Reader> pins = cellInversion.getCellPins();
            expect(invertiblePinMap.size(), pins.size());

            for (CellPinInversion.Reader pin : pins) {
                String cellPin = allStrings.get(pin.getCellPin());
                String expectedParameter = invertiblePinMap.get(cellPin);
                expect(true, pin.getNotInverting().isParameter());
                expect(true, pin.getInverting().isParameter());

                PropertyMap.Entry.Reader parameter = pin.getNotInverting().getParameter();
                expect(expectedParameter, allStrings.get(parameter.getKey()));
                expect(true, parameter.isTextValue());
                expect("1'b0", allStrings.get(parameter.getTextValue()));

                parameter = pin.getInverting().getParameter();
                expect(expectedParameter, allStrings.get(parameter.getKey()));
                expect(true, parameter.isTextValue());
                expect("1'b1", allStrings.get(parameter.getTextValue()));
            }
        }

        expect(unisimsWithInversions.size(), unisimsInReader.size());

        if (!unisimsWithInversions.equals(unisimsInReader)) {
            throw new RuntimeException("Inverted parameters Unisim doesn't match!");
        }
    }
}
