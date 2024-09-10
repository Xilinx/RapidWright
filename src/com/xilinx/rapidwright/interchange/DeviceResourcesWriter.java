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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.capnproto.MessageBuilder;
import org.capnproto.PrimitiveList;
import org.capnproto.PrimitiveList.Int;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;
import org.capnproto.Void;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.VivadoProp;
import com.xilinx.rapidwright.design.VivadoPropType;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Grade;
import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
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
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BEL.Builder;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BELCategory;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.BELInverter;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellInversion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellParameterDefinition;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellPinInversion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellPinInversionParameter;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterDefinition;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterDefinitions;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterFormat;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterMapEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterMapRule;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PrimToMacroExpansion;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.PseudoCell;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SitePin;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteWire;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Pair;

public class DeviceResourcesWriter {
    private static StringEnumerator allStrings;
    private static IdentityEnumerator<SiteTypeEnum> allSiteTypes;

    private static HashMap<TileTypeEnum,Tile> tileTypes;
    private static HashMap<SiteTypeEnum,Site> siteTypes;

    public static void populateSiteEnumerations(SiteInst siteInst, Site site) {
        if (!siteTypes.containsKey(siteInst.getSiteTypeEnum())) {
            if (site.getSiteTypeEnum() != siteInst.getSiteTypeEnum()) {
                return;
            }
            siteTypes.put(siteInst.getSiteTypeEnum(), site);
            allStrings.addObject(siteInst.getSiteTypeEnum().toString());

            for (String siteWire : siteInst.getSiteWires()) {
                allStrings.addObject(siteWire);
            }
            for (BEL bel : siteInst.getBELs()) {
                allStrings.addObject(bel.getName());
                allStrings.addObject(bel.getBELType());
                for (BELPin belPin : bel.getPins()) {
                    allStrings.addObject(belPin.getName());
                }
            }
            for (String sitePin : siteInst.getSitePinNames()) {
                allStrings.addObject(sitePin);
            }
        }
    }

    public static void populateEnumerations(Design design, Device device) {

        allStrings = new StringEnumerator();
        allSiteTypes = new IdentityEnumerator<>();

        HashMap<SiteTypeEnum,Site> allAltSiteTypeEnums = new HashMap<>();

        tileTypes = new HashMap<>();
        siteTypes = new HashMap<>();
        for (Tile tile : device.getAllTiles()) {
            allStrings.addObject(tile.getName());
            if (!tileTypes.containsKey(tile.getTileTypeEnum())) {
                allStrings.addObject(tile.getTileTypeEnum().name());
                for (int i=0; i < tile.getWireCount(); i++) {
                    allStrings.addObject(tile.getWireName(i));
                }
                tileTypes.put(tile.getTileTypeEnum(),tile);
            }
            for (Site site : tile.getSites()) {
                allStrings.addObject(site.getName());
                allStrings.addObject(site.getSiteTypeEnum().name());
                SiteInst siteInst = design.createSiteInst("site_instance", site.getSiteTypeEnum(), site);
                populateSiteEnumerations(siteInst, site);
                design.removeSiteInst(siteInst);

                SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
                for (int i=0; i < altSiteTypes.length; i++) {
                    SiteInst altSiteInst = design.createSiteInst("site_instance", altSiteTypes[i], site);
                    populateSiteEnumerations(altSiteInst, site);
                    design.removeSiteInst(altSiteInst);
                    if (!allAltSiteTypeEnums.containsKey(altSiteTypes[i])) {
                        allAltSiteTypeEnums.put(altSiteTypes[i], site);
                    }
                }
            }

        }
        Map<String, Pair<String, EnumSet<IOStandard>>> macroExpandExceptionMap =
                EDIFNetlist.macroExpandExceptionMap.getOrDefault(device.getSeries(), Collections.emptyMap());
        for (Entry<String,Pair<String, EnumSet<IOStandard>>> e : macroExpandExceptionMap.entrySet()) {
            allStrings.addObject(e.getKey());
            allStrings.addObject(e.getValue().getFirst());
            for (IOStandard ioStd : e.getValue().getSecond()) {
                allStrings.addObject(ioStd.name());
            }
        }

        for (Entry<SiteTypeEnum, Site> altSiteType : allAltSiteTypeEnums.entrySet()) {
            if (!siteTypes.containsKey(altSiteType.getKey())) {
                siteTypes.put(altSiteType.getKey(), altSiteType.getValue());
            }
        }
    }

    private static void writeCellParameterDefinitions(Series series, EDIFNetlist prims, ParameterDefinitions.Builder builder) {
        Set<String> cellsWithParameters = new HashSet<String>();
        for (EDIFLibrary library : prims.getLibraries()) {
            for (EDIFCell cell : library.getCells()) {
                String cellTypeName = cell.getName();

                Map<String,VivadoProp> defaultCellProperties = Design.getDefaultCellProperties(series, cellTypeName);
                if (defaultCellProperties != null && defaultCellProperties.size() > 0) {
                    cellsWithParameters.add(cellTypeName);
                }
            }
        }

        StructList.Builder<CellParameterDefinition.Builder> cellParamDefs = builder.initCells(cellsWithParameters.size());
        int i = 0;
        for (String cellTypeName : cellsWithParameters) {
            CellParameterDefinition.Builder cellParamDef = cellParamDefs.get(i);
            i += 1;


            cellParamDef.setCellType(allStrings.getIndex(cellTypeName));
            Map<String,VivadoProp> defaultCellProperties = Design.getDefaultCellProperties(series, cellTypeName);

            StructList.Builder<ParameterDefinition.Builder> paramDefs = cellParamDef.initParameters(defaultCellProperties.size());
            int j = 0;
            for (Map.Entry<String, VivadoProp> property : defaultCellProperties.entrySet()) {
                ParameterDefinition.Builder paramDef = paramDefs.get(j);
                j += 1;

                String propName = property.getKey();
                VivadoProp propValue = property.getValue();

                Integer nameIdx = allStrings.getIndex(propName);
                paramDef.setName(nameIdx);

                PropertyMap.Entry.Builder defaultValue = paramDef.getDefault();
                defaultValue.setKey(nameIdx);
                defaultValue.setTextValue(allStrings.getIndex(propValue.getValue()));

                if (propValue.getType() == VivadoPropType.BINARY) {
                    paramDef.setFormat(ParameterFormat.VERILOG_BINARY);
                } else if (propValue.getType() == VivadoPropType.BOOL) {
                    paramDef.setFormat(ParameterFormat.BOOLEAN);
                } else if (propValue.getType() == VivadoPropType.DOUBLE) {
                    paramDef.setFormat(ParameterFormat.FLOATING_POINT);
                } else if (propValue.getType() == VivadoPropType.HEX) {
                    paramDef.setFormat(ParameterFormat.VERILOG_HEX);
                } else if (propValue.getType() == VivadoPropType.INT) {
                    paramDef.setFormat(ParameterFormat.INTEGER);
                } else if (propValue.getType() == VivadoPropType.STRING) {
                    paramDef.setFormat(ParameterFormat.STRING);
                } else {
                    throw new RuntimeException(String.format("Unknown VivadoPropType %s", propValue.getType().name()));
                }
            }
        }
    }


    protected static boolean containsUnusedMacros(EDIFCell cell, Set<EDIFCell> unusedMacros) {
        Queue<EDIFCell> q = new LinkedList<>();
        Set<EDIFCell> visited = new HashSet<>();
        q.add(cell);
        while (!q.isEmpty()) {
            EDIFCell curr = q.poll();
            visited.add(curr);
            if (unusedMacros.contains(curr)) {
                unusedMacros.add(curr);
                return true;
            }
            for (EDIFCellInst inst : cell.getCellInsts()) {
                EDIFCell child = inst.getCellType();
                if (visited.contains(child)) continue;
                q.add(child);
            }
        }
        return false;
    }

    public static void writeDeviceResourcesFile(String part, Device device, CodePerfTracker t,
            String fileName) throws IOException {
        writeDeviceResourcesFile(part, device, t, fileName, false);
    }

    public static void writeDeviceResourcesFile(String part, Device device, CodePerfTracker t, 
            String fileName, boolean skipRouteResources) throws IOException {
        Design design = new Design();
        design.setPartName(part);
        Series series = device.getSeries();

        t.start("populateEnums");
        populateEnumerations(design, device);

        MessageBuilder message = new MessageBuilder();
        DeviceResources.Device.Builder devBuilder = message.initRoot(DeviceResources.Device.factory);
        devBuilder.setName(device.getName());

        t.stop().start("SiteTypes");
        writeAllSiteTypesToBuilder(design, device, devBuilder);

        t.stop().start("TileTypes");
        Map<TileTypeEnum, Integer> tileTypeIndicies = writeAllTileTypesToBuilder(design, device, devBuilder);
        Map<TileTypeEnum, TileType.Builder> tileTypesObj = new HashMap<TileTypeEnum, TileType.Builder>();
        for (Map.Entry<TileTypeEnum, Integer> tileType : tileTypeIndicies.entrySet()) {
            tileTypesObj.put(tileType.getKey(), devBuilder.getTileTypeList().get(tileType.getValue()));
        }

        t.stop().start("Tiles");
        writeAllTilesToBuilder(device, devBuilder, tileTypeIndicies);

        t.stop().start("Wires&Nodes");
        writeAllWiresAndNodesToBuilder(device, devBuilder, skipRouteResources);

        t.stop().start("Prims&Macros");
        // Create an EDIFNetlist populated with just primitive and macro libraries
        EDIFLibrary prims = Design.getPrimitivesLibrary(device.getName());
        // Copy the macros library so we can modify it
        EDIFLibrary macros = new EDIFLibrary(Design.getMacroPrimitives(series));
        EDIFNetlist netlist = new EDIFNetlist("PrimitiveLibs");
        netlist.addLibrary(prims);
        List<EDIFCell> dupsToRemove = new ArrayList<EDIFCell>();
        for (EDIFCell hdiCell : prims.getCells()) {
            EDIFCell cell = macros.getCell(hdiCell.getName());
            if (cell != null) {
                dupsToRemove.add(hdiCell);
            }
        }

        for (EDIFCell dupCell : dupsToRemove) {
            prims.removeCell(dupCell);
        }

        removeUnusedMacros(macros, prims);

        // Perform a deep copy because macro cells (which were shallow copied before)
        // must now instantiate primitives from our primitives library
        macros = netlist.copyLibraryAndSubCells(macros);

        Map<String, Pair<String, EnumSet<IOStandard>>> macroCollapseExceptionMap =
                EDIFNetlist.macroCollapseExceptionMap.getOrDefault(series, Collections.emptyMap());
        List<Unisim> unisims = new ArrayList<Unisim>();
        for (EDIFCell cell : macros.getCells()) {
            String cellName = cell.getName();
            Pair<String, EnumSet<IOStandard>> entry = macroCollapseExceptionMap.get(cellName);
            if (entry != null) {
                cellName = entry.getFirst();
            }
            Unisim unisim = Unisim.valueOf(cellName);
            Map<String,String> invertiblePins = DesignTools.getInvertiblePinMap(series, unisim);
            if (invertiblePins != null && invertiblePins.size() > 0) {
                unisims.add(unisim);
            }
        }
        for (EDIFCell cell : prims.getCells()) {
            Unisim unisim = Unisim.valueOf(cell.getName());
            Map<String,String> invertiblePins = DesignTools.getInvertiblePinMap(series, unisim);
            if (invertiblePins != null && invertiblePins.size() > 0) {
                unisims.add(unisim);
            }
        }

        StructList.Builder<CellInversion.Builder> cellInversions = devBuilder.initCellInversions(unisims.size());
        for (int i = 0; i < unisims.size(); ++i) {
            Unisim unisim = unisims.get(i);
            CellInversion.Builder cellInversion = cellInversions.get(i);
            cellInversion.setCell(allStrings.getIndex(unisim.name()));

            Map<String,String> invertiblePins = DesignTools.getInvertiblePinMap(series, unisim);
            StructList.Builder<CellPinInversion.Builder> cellPinInversions = cellInversion.initCellPins(invertiblePins.size());

            int j = 0;
            for (Map.Entry<String, String> entry : invertiblePins.entrySet()) {
                String port = entry.getKey();
                String parameterStr = entry.getValue();

                CellPinInversion.Builder pinInversion = cellPinInversions.get(j);
                j += 1;

                pinInversion.setCellPin(allStrings.getIndex(port));

                CellPinInversionParameter.Builder param = pinInversion.getNotInverting();
                PropertyMap.Entry.Builder parameter = param.initParameter();
                parameter.setKey(allStrings.getIndex(parameterStr));
                parameter.setTextValue(allStrings.getIndex("1'b0"));

                param = pinInversion.getInverting();
                parameter = param.initParameter();
                parameter.setKey(allStrings.getIndex(parameterStr));
                parameter.setTextValue(allStrings.getIndex("1'b1"));
            }
        }

        Netlist.Builder netlistBuilder = devBuilder.getPrimLibs();
        netlistBuilder.setName(netlist.getName());
        LogNetlistWriter writer = new LogNetlistWriter(allStrings, new HashMap<String, String>() {{
                    put(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME, LogNetlistWriter.DEVICE_PRIMITIVES_LIB);
                    put(series+"_"+EDIFTools.MACRO_PRIMITIVES_LIB, LogNetlistWriter.DEVICE_MACROS_LIB);
                }}
            );
        writer.populateNetlistBuilder(netlist, netlistBuilder, CodePerfTracker.SILENT);

        writeCellParameterDefinitions(series, netlist, devBuilder.getParameterDefs());

        // Write macro exception map
        Map<String, Pair<String, EnumSet<IOStandard>>> expandMap =
                EDIFNetlist.macroExpandExceptionMap.getOrDefault(series, Collections.emptyMap());
        Map<String, MacroParamRule[]> paramRules = MacroParamMappingRules.macroRules.get(series);
        Set<String> exceptionMacros = new TreeSet<>(expandMap.keySet());
        exceptionMacros.addAll(paramRules.keySet());
        int size = exceptionMacros.size();
        StructList.Builder<PrimToMacroExpansion.Builder> exceptionMap =
                devBuilder.initExceptionMap(size);
        int i=0;
        int ioStdPropIdx = allStrings.getIndex(EDIFNetlist.IOSTANDARD_PROP);
        for (String macroName : exceptionMacros) {
            PrimToMacroExpansion.Builder entryBuilder = exceptionMap.get(i);
            entryBuilder.setPrimName(allStrings.getIndex(macroName));
            entryBuilder.setMacroName(allStrings.getIndex(macroName));

            // Check if this macro has an expansion exception
            if (expandMap.containsKey(macroName)) {
                Pair<String, EnumSet<IOStandard>> expandException = expandMap.get(macroName);
                entryBuilder.setMacroName(allStrings.getIndex(expandException.getFirst()));

                StructList.Builder<PropertyMap.Entry.Builder> ioStdEntries =
                        entryBuilder.initParameters(expandException.getSecond().size());
                int j=0;
                for (IOStandard ioStd : expandException.getSecond()) {
                    PropertyMap.Entry.Builder ioStdEntry = ioStdEntries.get(j);
                    ioStdEntry.setKey(ioStdPropIdx);
                    ioStdEntry.setTextValue(allStrings.getIndex(ioStd.name()));
                    j++;
                }
            }

            // Check if this macro has a parameter propagation rule set
            if (paramRules.containsKey(macroName)) {
                MacroParamRule[] rules = paramRules.get(macroName);
                StructList.Builder<ParameterMapRule.Builder> parameterMap =
                        entryBuilder.initParamMapping(rules.length);
                int j=0;
                for (MacroParamRule rule : rules) {
                    ParameterMapRule.Builder ruleBuilder = parameterMap.get(j);
                    ruleBuilder.setPrimParam(allStrings.getIndex(rule.getPrimParam()));
                    ruleBuilder.setInstName(allStrings.getIndex(rule.getInstName()));
                    ruleBuilder.setInstParam(allStrings.getIndex(rule.getInstParam()));
                    if (rule.getBitSlice() != null) {
                        PrimitiveList.Int.Builder bitsBuilder =
                                ruleBuilder.initBitSlice(rule.getBitSlice().length);
                        for (int k = 0; k < rule.getBitSlice().length; k++) {
                            bitsBuilder.set(k, rule.getBitSlice()[k]);
                        }
                    } else if (rule.getTableLookup() != null) {
                        // Lookup table
                        StructList.Builder<ParameterMapEntry.Builder> tableBuilder =
                            ruleBuilder.initTableLookup(rule.getTableLookup().length);
                        for (int k = 0; k < rule.getTableLookup().length; k++) {
                            ParameterMapEntry.Builder itemBuilder = tableBuilder.get(k);
                            MacroParamTableEntry tableEntry = rule.getTableLookup()[k];
                            itemBuilder.setFrom(allStrings.getIndex(tableEntry.from));
                            itemBuilder.setFrom(allStrings.getIndex(tableEntry.to));
                        }
                    } else {
                        ruleBuilder.setCopyValue(Void.VOID);
                    }
                    j++;
                }
            }
            i++;
        }

        t.stop().start("Cell <-> BEL pin map");
        EnumerateCellBelMapping.populateAllPinMappings(part, device, devBuilder, allStrings);

        t.stop().start("Packages");
        populatePackages(allStrings, device, devBuilder);

        t.stop().start("Constants");
        ConstantDefinitions.writeConstants(allStrings, device, devBuilder.initConstants(), design, siteTypes, tileTypesObj);

        t.stop().start("Wire Types");
        writeWireTypes(allStrings, devBuilder);

        t.stop().start("Strings");
        writeAllStringsToBuilder(devBuilder);

        t.stop().start("Write File");
        Interchange.writeInterchangeFile(fileName, message);
        t.stop();
    }

    public static void removeUnusedMacros(EDIFLibrary macros, EDIFLibrary prims) {
        Set<EDIFCell> unsupportedMacros = new HashSet<>();
        for (EDIFCell cell : macros.getCells()) {
            for (EDIFCellInst inst : cell.getCellInsts()) {
                EDIFCell instCell = inst.getCellType();
                if (!prims.containsCell(instCell) && !macros.containsCell(instCell)) {
                    unsupportedMacros.add(cell);
                    continue;
                }
                EDIFCell macroCell = macros.getCell(instCell.getName());
                if (macroCell != null && !unsupportedMacros.contains(macroCell)) {
                    // remap cell definition to macro library
                    inst.setCellType(macroCell);
                }
            }
        }

        // Not all devices have all the primitives to support all macros, thus we will
        // remove
        // them to avoid stale references
        for (EDIFCell macro : new ArrayList<>(macros.getCells())) {
            if (containsUnusedMacros(macro, unsupportedMacros)) {
                macros.removeCell(macro);
            }
        }
    }

    public static void writeAllStringsToBuilder(DeviceResources.Device.Builder devBuilder) {
        int stringCount = allStrings.size();
        TextList.Builder strList = devBuilder.initStrList(stringCount);
        for (int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(allStrings.get(i)));
        }
    }

    protected static BELCategory getBELCategory(BEL bel) {
        BELClass category = bel.getBELClass();
        if (category == BELClass.BEL)
            return BELCategory.LOGIC;
        if (category == BELClass.RBEL)
            return BELCategory.ROUTING;
        if (category == BELClass.PORT)
            return BELCategory.SITE_PORT;
        return BELCategory._NOT_IN_SCHEMA;
    }

    protected static Direction getBELPinDirection(BELPin belPin) {
        BELPin.Direction dir = belPin.getDir();
        if (dir == BELPin.Direction.INPUT)
            return Direction.INPUT;
        if (dir == BELPin.Direction.OUTPUT)
            return Direction.OUTPUT;
        if (dir == BELPin.Direction.BIDIRECTIONAL)
            return Direction.INOUT;
        return Direction._NOT_IN_SCHEMA;
    }

    public static void writeAllSiteTypesToBuilder(Design design, Device device, DeviceResources.Device.Builder devBuilder) {
        StructList.Builder<SiteType.Builder> siteTypesList = devBuilder.initSiteTypeList(siteTypes.size());

        int i=0;
        for (Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            SiteType.Builder siteType = siteTypesList.get(i);
            Site site = e.getValue();
            SiteInst siteInst = design.createSiteInst("site_instance", e.getKey(), site);
            Tile tile = siteInst.getTile();
            siteType.setName(allStrings.getIndex(e.getKey().name()));
            allSiteTypes.addObject(e.getKey());

            IdentityEnumerator<BELPin> allBELPins = new IdentityEnumerator<BELPin>();

            // BELs
            StructList.Builder<Builder> belBuilders = siteType.initBels(siteInst.getBELs().length);
            for (int j=0; j < siteInst.getBELs().length; j++) {
                BEL bel = siteInst.getBELs()[j];
                Builder belBuilder = belBuilders.get(j);
                belBuilder.setName(allStrings.getIndex(bel.getName()));
                belBuilder.setType(allStrings.getIndex(bel.getBELType()));
                PrimitiveList.Int.Builder belPinsBuilder = belBuilder.initPins(bel.getPins().length);
                for (int k=0; k < bel.getPins().length; k++) {
                    BELPin belPin = bel.getPin(k);
                    belPinsBuilder.set(k, allBELPins.getIndex(belPin));
                }
                belBuilder.setCategory(getBELCategory(bel));

                if (bel.canInvert() && !bel.getName().equals("SRCMXINV") && !bel.getName().equals("SRCFPMXINV")) {
                    BELInverter.Builder belInverter = belBuilder.initInverting();
                    belInverter.setNonInvertingPin(allBELPins.getIndex(bel.getNonInvertingPin()));
                    belInverter.setInvertingPin(allBELPins.getIndex(bel.getInvertingPin()));
                } else {
                    belBuilder.setNonInverting(Void.VOID);
                }
            }

            // SitePins
            int highestIndexInputPin = siteInst.getHighestSitePinInputIndex();
            ArrayList<String> pinNames = new ArrayList<String>();
            for (String pinName : siteInst.getSitePinNames()) {
                pinNames.add(pinName);
            }
            siteType.setLastInput(highestIndexInputPin);

            StructList.Builder<SitePin.Builder> pins = siteType.initPins(pinNames.size());
            for (int j=0; j < pinNames.size(); j++) {
                String primarySitePinName = pinNames.get(j);
                int sitePinIndex = site.getPinIndex(pinNames.get(j));
                if (sitePinIndex == -1) {
                    primarySitePinName = siteInst.getPrimarySitePinName(pinNames.get(j));
                    sitePinIndex = site.getPinIndex(primarySitePinName);
                }

                if (sitePinIndex == -1) {
                    throw new RuntimeException("Failed to find pin index for site " + site.getName() + " site type " + e.getKey().name()+ " site pin " + primarySitePinName + " / " + pinNames.get(j));
                }

                SitePin.Builder pin = pins.get(j);
                pin.setName(allStrings.getIndex(pinNames.get(j)));
                pin.setDir(j <= highestIndexInputPin ? Direction.INPUT : Direction.OUTPUT);
                BEL bel = siteInst.getBEL(pinNames.get(j));
                BELPin[] belPins = bel.getPins();
                if (belPins.length != 1) {
                    throw new RuntimeException("Only expected 1 BEL pin on site pin BEL.");
                }
                BELPin belPin = belPins[0];
                pin.setBelpin(allBELPins.getIndex(belPin));
            }

            // SiteWires
            String[] siteWires = siteInst.getSiteWires();
            StructList.Builder<SiteWire.Builder> swBuilders =
                    siteType.initSiteWires(siteWires.length);
            for (int j=0; j < siteWires.length; j++) {
                SiteWire.Builder swBuilder = swBuilders.get(j);
                String siteWireName = siteWires[j];
                swBuilder.setName(allStrings.getIndex(siteWireName));
                BELPin[] swPins = siteInst.getSiteWirePins(siteWireName);
                PrimitiveList.Int.Builder bpBuilders = swBuilder.initPins(swPins.length);
                for (int k=0; k < swPins.length; k++) {
                    bpBuilders.set(k, allBELPins.getIndex(swPins[k]));
                }
            }

            // Write out BEL pins.
            StructList.Builder<DeviceResources.Device.BELPin.Builder> belPinBuilders =
                    siteType.initBelPins(allBELPins.size());
            for (int j=0; j < allBELPins.size(); j++) {
                DeviceResources.Device.BELPin.Builder belPinBuilder = belPinBuilders.get(j);
                BELPin belPin = allBELPins.get(j);
                belPinBuilder.setName(allStrings.getIndex(belPin.getName()));
                belPinBuilder.setDir(getBELPinDirection(belPin));
                belPinBuilder.setBel(allStrings.getIndex(belPin.getBEL().getName()));
            }
            SitePIP[] allSitePIPs = siteInst.getSitePIPs();

            // Write out SitePIPs
            StructList.Builder<DeviceResources.Device.SitePIP.Builder> spBuilders =
                    siteType.initSitePIPs(allSitePIPs.length);
            for (int j=0; j < allSitePIPs.length; j++) {
                DeviceResources.Device.SitePIP.Builder spBuilder = spBuilders.get(j);
                SitePIP sitePIP = allSitePIPs[j];
                spBuilder.setInpin(allBELPins.getIndex(sitePIP.getInputPin()));
                spBuilder.setOutpin(allBELPins.getIndex(sitePIP.getOutputPin()));
            }

            design.removeSiteInst(siteInst);
            i++;
        }

        i = 0;
        for (Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            Site site = e.getValue();

            SiteType.Builder siteType = siteTypesList.get(i);

            SiteTypeEnum[] altSiteTypes = site.getAlternateSiteTypeEnums();
            PrimitiveList.Int.Builder altSiteTypesBuilder = siteType.initAltSiteTypes(altSiteTypes.length);

            for (int j=0; j < altSiteTypes.length; ++j) {
                Integer siteTypeIdx = allSiteTypes.maybeGetIndex(altSiteTypes[j]);
                if (siteTypeIdx == null) {
                    throw new RuntimeException("Site type " + altSiteTypes[j].name() + " is missing from allSiteTypes Enumerator.");
                }
                altSiteTypesBuilder.set(j, siteTypeIdx);
            }

            i++;
        }
    }

    private static void populateAltSitePins(
            Design design,
            Site site,
            int primaryTypeIndex,
            StructList.Builder<DeviceResources.Device.ParentPins.Builder> listOfParentPins,
            DeviceResources.Device.Builder devBuilder) {
        PrimitiveList.Int.Builder altSiteTypes = devBuilder.getSiteTypeList().get(primaryTypeIndex).getAltSiteTypes();
        SiteTypeEnum[] altSiteTypeEnums = site.getAlternateSiteTypeEnums();
        for (int i = 0; i < altSiteTypeEnums.length; ++i) {
            SiteInst siteInst = design.createSiteInst("site_instance", altSiteTypeEnums[i], site);

            DeviceResources.Device.SiteType.Builder altSiteType = devBuilder.getSiteTypeList().get(altSiteTypes.get(i));
            StructList.Builder<DeviceResources.Device.SitePin.Builder> sitePins = altSiteType.getPins();
            PrimitiveList.Int.Builder parentPins = listOfParentPins.get(i).initPins(altSiteType.getPins().size());

            for (int j = 0; j < sitePins.size(); j++) {
                DeviceResources.Device.SitePin.Builder sitePin = sitePins.get(j);
                String sitePinName = allStrings.get(sitePin.getName());
                String parentPinName = siteInst.getPrimarySitePinName(sitePinName);
                parentPins.set(j, site.getPinIndex(parentPinName));
            }

            design.removeSiteInst(siteInst);
        }
    }

    public static Map<TileTypeEnum, Integer> writeAllTileTypesToBuilder(Design design, Device device, DeviceResources.Device.Builder devBuilder) {
        StructList.Builder<TileType.Builder> tileTypesList = devBuilder.initTileTypeList(tileTypes.size());

        Map<TileTypeEnum, Integer> tileTypeIndicies = new HashMap<TileTypeEnum, Integer>();

        // Order tile types by their TILE_TYPE_IDX (may not be contiguous)
        Map<Integer, TileTypeEnum> tileTypeIndexMap = new TreeMap<>();
        for (Entry<TileTypeEnum,Tile> e : tileTypes.entrySet()) {
            tileTypeIndexMap.put(e.getValue().getTileTypeIndex(), e.getKey());
        }

        int i = 0;
        for (Entry<Integer, TileTypeEnum> e : tileTypeIndexMap.entrySet()) {
            TileTypeEnum type = e.getValue();
            Tile tile = tileTypes.get(type);
            TileType.Builder tileType = tileTypesList.get(i);
            tileTypeIndicies.put(type, i);
            // name
            tileType.setName(allStrings.getIndex(type.name()));

            // siteTypes
            Site[] sites = tile.getSites();
            StructList.Builder<DeviceResources.Device.SiteTypeInTileType.Builder> siteTypes = tileType.initSiteTypes(sites.length);
            for (int j=0; j < sites.length; j++) {
                DeviceResources.Device.SiteTypeInTileType.Builder siteType = siteTypes.get(j);
                int primaryTypeIndex = allSiteTypes.getIndex(sites[j].getSiteTypeEnum());
                siteType.setPrimaryType(primaryTypeIndex);

                int numPins = sites[j].getSitePinCount();
                PrimitiveList.Int.Builder pinWires = siteType.initPrimaryPinsToTileWires(numPins);
                for (int k=0; k < numPins; ++k) {
                    pinWires.set(k, allStrings.getIndex(sites[j].getTileWireNameFromPinName(sites[j].getPinName(k))));
                }

                populateAltSitePins(
                        design,
                        sites[j],
                        primaryTypeIndex,
                        siteType.initAltPinsToPrimaryPins(sites[j].getAlternateSiteTypeEnums().length),
                        devBuilder);
            }

            // wires
            PrimitiveList.Int.Builder wires = tileType.initWires(tile.getWireCount());
            for (int j=0 ; j < tile.getWireCount(); j++) {
                wires.set(j, allStrings.getIndex(tile.getWireName(j)));
            }

            // pips
            ArrayList<PIP> pips = tile.getPIPs();
            StructList.Builder<DeviceResources.Device.PIP.Builder> pipBuilders =
                    tileType.initPips(pips.size());
            for (int j=0; j < pips.size(); j++) {
                DeviceResources.Device.PIP.Builder pipBuilder = pipBuilders.get(j);
                PIP pip = pips.get(j);
                pipBuilder.setWire0(pip.getStartWireIndex());
                pipBuilder.setWire1(pip.getEndWireIndex());
                pipBuilder.setDirectional(!pip.isBidirectional());
                if (pip.getPIPType() == PIPType.BI_DIRECTIONAL_BUFFERED20) {
                    pipBuilder.setBuffered20(true);
                } else if (pip.getPIPType() == PIPType.BI_DIRECTIONAL_BUFFERED21_BUFFERED20) {
                    pipBuilder.setBuffered20(true);
                    pipBuilder.setBuffered21(true);
                } else if (pip.getPIPType() == PIPType.DIRECTIONAL_BUFFERED21) {
                    pipBuilder.setBuffered21(true);
                }

                if (pip.isRouteThru()) {
                    PseudoPIPHelper pseudoPIPHelper = PseudoPIPHelper.getPseudoPIPHelper(pip);
                    List<BELPin> belPins = pseudoPIPHelper.getUsedBELPins();
                    if (belPins == null || belPins.size() < 1) continue;

                    HashMap<BEL,ArrayList<BELPin>> pins = new HashMap<BEL, ArrayList<BELPin>>();
                    for (BELPin pin : belPins) {
                        ArrayList<BELPin> currBELPins = pins.get(pin.getBEL());
                        if (currBELPins == null) {
                            currBELPins = new ArrayList<>();
                            pins.put(pin.getBEL(), currBELPins);
                        }
                        currBELPins.add(pin);
                    }
                    StructList.Builder<PseudoCell.Builder> pseudoCells = pipBuilder.initPseudoCells(pins.size());
                    int k=0;
                    for (Entry<BEL, ArrayList<BELPin>> e3 : pins.entrySet()) {
                        PseudoCell.Builder pseudoCell = pseudoCells.get(k);
                        pseudoCell.setBel(allStrings.getIndex(e3.getKey().getName()));
                        List<BELPin> usedPins = e3.getValue();
                        int pinCount = usedPins.size();
                        Int.Builder pinsBuilder = pseudoCell.initPins(pinCount);
                        for (int l=0; l < pinCount; l++) {
                            pinsBuilder.set(l, allStrings.getIndex(usedPins.get(l).getName()));
                        }
                        k++;
                    }
                }
            }
            i++;
        }

        return tileTypeIndicies;
    }

    public static void writeAllTilesToBuilder(Device device, DeviceResources.Device.Builder devBuilder, Map<TileTypeEnum, Integer> tileTypeIndicies) {
        StructList.Builder<DeviceResources.Device.Tile.Builder> tileBuilders =
                devBuilder.initTileList(device.getColumns() * device.getRows());

        int i=0;
        for (Tile[] tiles : device.getTiles()) {
            for (Tile tile : tiles) {
                DeviceResources.Device.Tile.Builder tileBuilder = tileBuilders.get(i);
                tileBuilder.setName(allStrings.getIndex(tile.getName()));
                tileBuilder.setType(tileTypeIndicies.get(tile.getTileTypeEnum()));
                Site[] sites = tile.getSites();
                StructList.Builder<DeviceResources.Device.Site.Builder> siteBuilders = tileBuilder
                        .initSites(sites.length);
                for (int j = 0; j < sites.length; j++) {
                    DeviceResources.Device.Site.Builder siteBuilder = siteBuilders.get(j);
                    siteBuilder.setName(allStrings.getIndex(sites[j].getName()));
                    siteBuilder.setType(j);
                }
                tileBuilder.setRow((short) tile.getRow());
                tileBuilder.setCol((short) tile.getColumn());
                i++;
            }
        }

    }

    private static long makeKey(Tile tile, int wire) {
        long key = wire;
        key = (((long)tile.getUniqueAddress()) << 32) | key;
        return key;
    }

    public static void writeAllWiresAndNodesToBuilder(Device device, DeviceResources.Device.Builder devBuilder,
            boolean skipRouteResources) {
        LongEnumerator allWires = new LongEnumerator();
        ArrayList<Long> allNodes = new ArrayList<>();

        if (!skipRouteResources) {
            for (Tile tile : device.getAllTiles()) {
                for (int i = 0; i < tile.getWireCount(); i++) {
                    Wire wire = new Wire(tile, i);
                    allWires.addObject(makeKey(wire.getTile(), wire.getWireIndex()));

                    Node node = wire.getNode();
                    if (node == null)
                        continue;
                    if (node.getTile() == tile && node.getWireIndex() == i)
                        allNodes.add(makeKey(node.getTile(), node.getWireIndex()));
                }
            }
        }

        StructList.Builder<DeviceResources.Device.Wire.Builder> wireBuilders =
                devBuilder.initWires(allWires.size());

        for (int i=0; i < allWires.size(); i++) {
            DeviceResources.Device.Wire.Builder wireBuilder = wireBuilders.get(i);
            long wireKey = allWires.get(i);
            Wire wire = new Wire(device.getTile((int)(wireKey >>> 32)), (int)(wireKey & 0xffffffff));
            //Wire wire = allWires.get(i);
            wireBuilder.setTile(allStrings.getIndex(wire.getTile().getName()));
            wireBuilder.setWire(allStrings.getIndex(wire.getWireName()));
            wireBuilder.setType(wire.getIntentCode().ordinal());
        }

        StructList.Builder<DeviceResources.Device.Node.Builder> nodeBuilders =
                devBuilder.initNodes(allNodes.size());
        for (int i=0; i < allNodes.size(); i++) {
            DeviceResources.Device.Node.Builder nodeBuilder = nodeBuilders.get(i);
            //Node node = allNodes.get(i);
            long nodeKey = allNodes.get(i);
            Node node = Node.getNode(device.getTile((int)(nodeKey >>> 32)), (int)(nodeKey & 0xffffffff));
            Wire[] wires = node.getAllWiresInNode();
            PrimitiveList.Int.Builder wBuilders = nodeBuilder.initWires(wires.length);
            for (int k=0; k < wires.length; k++) {
                wBuilders.set(k, allWires.getIndex(makeKey(wires[k].getTile(), wires[k].getWireIndex())));
            }
        }
    }
    private static void populatePackages(StringEnumerator allStrings, Device device, DeviceResources.Device.Builder devBuilder) {
        Set<String> packages = device.getPackages();
        List<String> packagesList = new ArrayList<String>();
        packagesList.addAll(packages);
        packagesList.sort(new EnumerateCellBelMapping.StringCompare());
        StructList.Builder<DeviceResources.Device.Package.Builder> packagesObj = devBuilder.initPackages(packages.size());

        for (int i = 0; i < packages.size(); ++i) {
            Package pack = device.getPackage(packagesList.get(i));
            DeviceResources.Device.Package.Builder packageBuilder = packagesObj.get(i);

            packageBuilder.setName(allStrings.getIndex(pack.getName()));

            LinkedHashMap<String,PackagePin> packagePinMap = pack.getPackagePinMap();
            List<String> packagePins = new ArrayList<String>();
            packagePins.addAll(packagePinMap.keySet());
            packagePins.sort(new EnumerateCellBelMapping.StringCompare());

            StructList.Builder<DeviceResources.Device.Package.PackagePin.Builder> packagePinsObj = packageBuilder.initPackagePins(packagePins.size());
            for (int j = 0; j < packagePins.size(); ++j) {
                PackagePin packagePin = packagePinMap.get(packagePins.get(j));
                DeviceResources.Device.Package.PackagePin.Builder packagePinObj = packagePinsObj.get(j);

                packagePinObj.setPackagePin(allStrings.getIndex(packagePin.getName()));
                Site site = packagePin.getSite();
                if (site != null) {
                    packagePinObj.initSite().setSite(allStrings.getIndex(site.getName()));
                } else {
                    packagePinObj.initSite().setNoSite(Void.VOID);
                }

                BEL bel = packagePin.getBEL();
                if (bel != null) {
                    packagePinObj.initBel().setBel(allStrings.getIndex(bel.getName()));
                } else {
                    packagePinObj.initBel().setNoBel(Void.VOID);
                }
            }

            StructList.Builder<DeviceResources.Device.Package.Grade.Builder> grades = packageBuilder.initGrades(pack.getGrades().length);
            for (int j = 0; j < pack.getGrades().length; ++j) {
                Grade grade = pack.getGrades()[j];
                DeviceResources.Device.Package.Grade.Builder gradeObj = grades.get(j);
                gradeObj.setName(allStrings.getIndex(grade.getName()));
                gradeObj.setSpeedGrade(allStrings.getIndex(grade.getSpeedGrade()));
                gradeObj.setTemperatureGrade(allStrings.getIndex(grade.getTemperatureGrade()));
            }
        }
    }
    public static void writeWireTypes(StringEnumerator allStrings, DeviceResources.Device.Builder devBuilder) {
        StructList.Builder<DeviceResources.Device.WireType.Builder> wireTypesObj =
                devBuilder.initWireTypes(IntentCode.values.length);
        for (IntentCode intent : IntentCode.values) {
            DeviceResources.Device.WireType.Builder wireType = wireTypesObj.get(intent.ordinal());
            wireType.setName(allStrings.getIndex(intent.toString()));
            wireType.setCategory(WireType.intentToCategory(intent));
        }
    }
}
