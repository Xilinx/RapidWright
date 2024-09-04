/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Keith Rothman, Google, Inc.
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.capnproto.MessageBuilder;
import org.capnproto.StructList;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelMapping;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelPinEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CommonCellBelPinMaps;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterCellBelPinMaps;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterSiteTypeBelEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteTypeBelEntry;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class EnumerateCellBelMapping {
    private List<SiteTypeEnum> siteTypes;
    private List<Site> sites;
    private List<String> bels;
    private List<Set<Map.Entry<String, String>>> pinMappings;
    private List<List<String>> parameterSets;

    public EnumerateCellBelMapping() {
        siteTypes = new ArrayList<SiteTypeEnum>();
        sites = new ArrayList<Site>();
        bels = new ArrayList<String>();
        pinMappings = new ArrayList<Set<Map.Entry<String, String>>>();
        parameterSets = new ArrayList<List<String>>();
    }

    public void addInstance(SiteTypeEnum siteType, Site site, String bel, HashSet<Map.Entry<String, String>> pinMapping, List<String> parameters) {
        siteTypes.add(siteType);
        sites.add(site);
        bels.add(bel);
        pinMappings.add(pinMapping);
        parameterSets.add(parameters);
    }

    private void checkSizeInvariance() throws RuntimeException {
        if (sites != null && siteTypes.size() != sites.size()) {
            throw new RuntimeException(String.format(
                "siteTypes.size() (%d) != sites.size() (%d)\n",
                siteTypes.size(), sites.size()));
        }

        if (siteTypes.size() != bels.size()) {
            throw new RuntimeException(String.format(
                "siteTypes.size() (%d) != bels.size() (%d)\n",
                siteTypes.size(), bels.size()));
        }

        if (siteTypes.size() != pinMappings.size()) {
            throw new RuntimeException(String.format(
                        "siteTypes.size() (%d) != pinMappings.size() (%d)\n",
                siteTypes.size(), pinMappings.size()));
        }

        if (siteTypes.size() != parameterSets.size()) {
            throw new RuntimeException(String.format(
                "siteTypes.size() (%d) != parameterSets.size() (%d)\n",
                siteTypes.size(), parameterSets.size()));
        }
    }


    private void checkSiteInvariance() throws RuntimeException {
        checkSizeInvariance();

        Map<List<Object>, Set<Site>> sitesforBels = new HashMap<List<Object>, Set<Site>>();
        Map<SiteTypeEnum, Set<Site>> sitesForSiteTypes = new HashMap<SiteTypeEnum, Set<Site>>();

        for (int i = 0; i < siteTypes.size(); ++i) {
            List<Object> key = new ArrayList<Object>();
            key.add(siteTypes.get(i));
            key.add(bels.get(i));
            key.add(pinMappings.get(i));
            key.add(parameterSets.get(i));

            Set<Site> sitesForKey = sitesforBels.get(key);
            if (sitesForKey == null) {
                sitesForKey = new HashSet<Site>();
                sitesforBels.put(key, sitesForKey);
            }
            sitesForKey.add(sites.get(i));

            Set<Site> sitesForSiteType = sitesForSiteTypes.get(siteTypes.get(i));
            if (sitesForSiteType == null) {
                sitesForSiteType = new HashSet<Site>();
                sitesForSiteTypes.put(siteTypes.get(i), sitesForSiteType);
            }
            sitesForSiteType.add(sites.get(i));
        }

        Set<List<Object>> sitesTested = new HashSet<List<Object>>();

        for (int i = 0; i < siteTypes.size(); ++i) {
            List<Object> key = new ArrayList<Object>();
            key.add(siteTypes.get(i));
            key.add(bels.get(i));
            key.add(pinMappings.get(i));
            key.add(parameterSets.get(i));

            if (sitesTested.contains(key)) {
                continue;
            }
            sitesTested.add(key);

            Set<Site> sitesForKey = sitesforBels.get(key);
            Set<Site> sitesForSiteType = sitesForSiteTypes.get(siteTypes.get(i));

            if (!sitesForKey.equals(sitesForSiteType)) {
                throw new RuntimeException(String.format(
                    "Site invariance not met for site %s (%s) BEL %s",
                    sites.get(i).getName(), siteTypes.get(i).name(),
                    bels.get(i)));
            }
        }

        siteTypes = new ArrayList<SiteTypeEnum>();
        sites = null;
        bels = new ArrayList<String>();
        pinMappings = new ArrayList<Set<Map.Entry<String, String>>>();
        parameterSets = new ArrayList<List<String>>();
        for (List<Object> key : sitesTested) {
            siteTypes.add((SiteTypeEnum)key.get(0));
            bels.add((String)key.get(1));
            pinMappings.add((Set<Map.Entry<String, String>>)key.get(2));
            parameterSets.add((List<String>)key.get(3));
        }
    }

    private Set<Map.Entry<String, String>> createAllPins() {
        checkSizeInvariance();

        Set<Map.Entry<String, String>> allPins = new HashSet<Map.Entry<String, String>>();
        for (Set<Map.Entry<String, String>> pins : pinMappings) {
            for (Map.Entry<String, String> pin : pins) {
                allPins.add(pin);
            }
        }

        return allPins;
    }

    private Map<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>> createCommonPins(Set<Map.Entry<String, String>> allPins) {

        Map<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>> commonPins = new HashMap<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>>();
        for (int i = 0; i < siteTypes.size(); ++i) {
            Map.Entry<SiteTypeEnum, String> key = new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypes.get(i), bels.get(i));
            Set<Map.Entry<String, String>> pins = commonPins.get(key);
            if (pins == null) {
                pins = new HashSet<Map.Entry<String, String>>();
                pins.addAll(allPins);
                commonPins.put(key, pins);
            }

            pins.retainAll(pinMappings.get(i));
        }

        return commonPins;
    }

    private Map<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>, Set<Map.Entry<String, String>>> createParameterToPins(
            Set<Map.Entry<String, String>> allPins,
            Map<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>> commonPins) {
        checkSizeInvariance();

        Map<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>, Set<Map.Entry<String, String>>> parameterToPins = new HashMap<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>, Set<Map.Entry<String, String>>>();
        for (int i = 0; i < siteTypes.size(); ++i) {
            Map.Entry<SiteTypeEnum, String> commonPinsKey = new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypes.get(i), bels.get(i));
            for (String parameter : parameterSets.get(i)) {
                Map.Entry<SiteTypeEnum, Map.Entry<String, String>> key = new AbstractMap.SimpleEntry<SiteTypeEnum, Map.Entry<String, String>>(
                        siteTypes.get(i), new AbstractMap.SimpleEntry<String, String>(
                            bels.get(i),
                            parameter));

                Set<Map.Entry<String, String>> pins = parameterToPins.get(key);
                if (pins == null) {
                    pins = new HashSet<Map.Entry<String, String>>();
                    pins.addAll(allPins);
                    pins.removeAll(commonPins.get(commonPinsKey));

                    parameterToPins.put(key, pins);
                }

                pins.retainAll(pinMappings.get(i));
            }
        }

        return parameterToPins;
    }

    public void verifyCellBelPinMaps(
        Map<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>> commonPins,
        Map<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>, Set<Map.Entry<String, String>>> parameterToPins) {
        for (int i = 0; i < siteTypes.size(); ++i) {
            Set<Map.Entry<String, String>> assembledPins = new HashSet<Map.Entry<String, String>>();

            Map.Entry<SiteTypeEnum, String> commonPinsKey = new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypes.get(i), bels.get(i));
            assembledPins.addAll(commonPins.get(commonPinsKey));

            String parametersJoined = new String();
            for (String parameter : parameterSets.get(i)) {
                parametersJoined += " " + parameter;
                Map.Entry<SiteTypeEnum, Map.Entry<String, String>> key = new AbstractMap.SimpleEntry<SiteTypeEnum, Map.Entry<String, String>>(
                        siteTypes.get(i), new AbstractMap.SimpleEntry<String, String>(
                            bels.get(i),
                            parameter));
                assembledPins.addAll(parameterToPins.get(key));
            }

            if (!pinMappings.get(i).equals(assembledPins)) {
                Set<Map.Entry<String, String>> diff = new HashSet<Map.Entry<String, String>>();

                diff.addAll(pinMappings.get(i));
                diff.removeAll(assembledPins);
                System.out.printf("pinMappings - assembledPins:\n");
                for (Map.Entry<String, String> entry : diff) {
                    System.out.printf(" - %s => %s\n", entry.getKey(), entry.getValue());
                }

                diff.clear();
                diff.addAll(assembledPins);
                diff.removeAll(pinMappings.get(i));
                System.out.printf("assembledPins - pinMappings:\n");
                for (Map.Entry<String, String> entry : diff) {
                    System.out.printf(" - %s => %s\n", entry.getKey(), entry.getValue());
                }

                throw new RuntimeException(String.format(
                    "Site type %s BEL %s parameters %s doesn't generate correct pin set.",
                    siteTypes.get(i).name(),
                    bels.get(i),
                    parametersJoined));
            }
        }
    }

    private void writePinMap(StringEnumerator allStrings, Set<Map.Entry<String, String>> pins, StructList.Builder<CellBelPinEntry.Builder> pinsObj) {
        List<Map.Entry<String, String>> pinsToSort = new ArrayList<Map.Entry<String, String>>();
        pinsToSort.addAll(pins);
        pinsToSort.sort(new StringPairCompare());

        int i = 0;
        for (Map.Entry<String, String> pin : pinsToSort) {
            CellBelPinEntry.Builder pinObj = pinsObj.get(i);
            pinObj.setCellPin(allStrings.getIndex(pin.getKey()));
            pinObj.setBelPin(allStrings.getIndex(pin.getValue()));
            i += 1;
        }
    }

    private void writeCommonSiteTypeBels(StringEnumerator allStrings, CommonCellBelPinMaps.Builder builder, Set<Map.Entry<SiteTypeEnum, String>> siteTypesAndBels) {
        Map<SiteTypeEnum, Set<String>> siteTypesToBels = new HashMap<SiteTypeEnum, Set<String>>();

        for (Map.Entry<SiteTypeEnum, String> siteTypeAndBel : siteTypesAndBels) {
            SiteTypeEnum siteType = siteTypeAndBel.getKey();
            Set<String> bels = siteTypesToBels.get(siteType);
            if (bels == null) {
                bels = new HashSet<String>();
                siteTypesToBels.put(siteType, bels);
            }

            bels.add(siteTypeAndBel.getValue());
        }

        List<SiteTypeEnum> siteTypes = new ArrayList<SiteTypeEnum>();
        siteTypes.addAll(siteTypesToBels.keySet());
        siteTypes.sort(new SiteTypeCompare());

        StructList.Builder<SiteTypeBelEntry.Builder> siteTypesObj = builder.initSiteTypes(siteTypesToBels.size());
        int i = 0;
        for (SiteTypeEnum siteType : siteTypes) {
            Set<String> bels = siteTypesToBels.get(siteType);
            List<String> belsSorted = new ArrayList<String>();
            belsSorted.addAll(bels);
            belsSorted.sort(new StringCompare());

            SiteTypeBelEntry.Builder siteTypeObj = siteTypesObj.get(i);

            siteTypeObj.setSiteType(allStrings.getIndex(siteType.name()));
            siteTypeObj.initBels(belsSorted.size());

            for (int j = 0; j < belsSorted.size(); ++j) {
                siteTypeObj.getBels().set(j, allStrings.getIndex(belsSorted.get(j)));
            }

            i += 1;
        }
    }

    private void writeParameterSiteTypeBels(StringEnumerator allStrings, ParameterCellBelPinMaps.Builder builder, Set<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>> siteTypesBelsAndParameters) {
        List<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>> siteTypesBelsAndParametersSorted = new ArrayList<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>>();
        siteTypesBelsAndParametersSorted.addAll(siteTypesBelsAndParameters);
        siteTypesBelsAndParametersSorted.sort(new SiteTypeStringPairCompare());

        StructList.Builder<ParameterSiteTypeBelEntry.Builder> entriesObj = builder.initParametersSiteTypes(siteTypesBelsAndParametersSorted.size());

        int i = 0;
        for (Map.Entry<SiteTypeEnum, Map.Entry<String, String>> entry : siteTypesBelsAndParametersSorted) {
            ParameterSiteTypeBelEntry.Builder entryObj = entriesObj.get(i);

            SiteTypeEnum siteType = entry.getKey();
            String bel = entry.getValue().getKey();
            String parameter = entry.getValue().getValue();

            entryObj.setSiteType(allStrings.getIndex(siteType.name()));
            entryObj.setBel(allStrings.getIndex(bel));
            PropertyMap.Entry.Builder property = entryObj.getParameter();

            String[] parameterParts = parameter.split("=", 2);
            if (parameterParts.length != 2) {
                throw new RuntimeException(String.format(
                            "Failed to parse parameter '%s'",
                            parameter));
            }
            String parameterKey = parameterParts[0];
            String parameterValue = parameterParts[1];
            property.setKey(allStrings.getIndex(parameterKey));
            property.setTextValue(allStrings.getIndex(parameterValue));

            i += 1;
        }
    }

    public void writeMapping(StringEnumerator allStrings, CellBelMapping.Builder builder) {
        checkSiteInvariance();

        Set<Map.Entry<String, String>> allPins = createAllPins();

        // Build forward lookups from site_type, bel -> common pins and
        // site_type, bel, parameters -> additional_pins
        Map<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>> commonPins = createCommonPins(allPins);
        Map<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>, Set<Map.Entry<String, String>>> parameterToPins = createParameterToPins(allPins, commonPins);

        // Check if parameter combinations need additional logic.
        verifyCellBelPinMaps(commonPins, parameterToPins);

        // Build reverse maps to remove duplication.
        Map<Set<Map.Entry<String, String>>, Set<Map.Entry<SiteTypeEnum, String>>> commonPinsRev = new HashMap<Set<Map.Entry<String, String>>, Set<Map.Entry<SiteTypeEnum, String>>>();
        for (Map.Entry<Map.Entry<SiteTypeEnum, String>, Set<Map.Entry<String, String>>> commonPin : commonPins.entrySet()) {
            Set<Map.Entry<String, String>> key = commonPin.getValue();

            Set<Map.Entry<SiteTypeEnum, String>> siteTypesAndBels = commonPinsRev.get(key);
            if (siteTypesAndBels == null) {
                siteTypesAndBels = new HashSet<Map.Entry<SiteTypeEnum, String>>();
                commonPinsRev.put(key, siteTypesAndBels);
            }

            siteTypesAndBels.add(commonPin.getKey());
        }

        StructList.Builder<CommonCellBelPinMaps.Builder> commonPinsObj = builder.initCommonPins(commonPinsRev.size());
        int i = 0;
        for (Map.Entry<Set<Map.Entry<String, String>>, Set<Map.Entry<SiteTypeEnum, String>>> entry : commonPinsRev.entrySet()) {
            CommonCellBelPinMaps.Builder entryObj = commonPinsObj.get(i);

            Set<Map.Entry<String, String>> pins = entry.getKey();
            StructList.Builder<CellBelPinEntry.Builder> pinsObj = entryObj.initPins(pins.size());
            writePinMap(allStrings, pins, pinsObj);
            writeCommonSiteTypeBels(allStrings, entryObj, entry.getValue());

            i += 1;
        }

        Map<Set<Map.Entry<String, String>>, Set<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>>> parameterToPinsRev = new HashMap<Set<Map.Entry<String, String>>, Set<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>>>();
        for (Map.Entry<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>, Set<Map.Entry<String, String>>> parameterPin : parameterToPins.entrySet()) {
            Set<Map.Entry<String, String>> key = parameterPin.getValue();

            Set<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>> siteTypeBelAndParameters = parameterToPinsRev.get(key);
            if (siteTypeBelAndParameters == null) {
                siteTypeBelAndParameters = new HashSet<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>>();
                parameterToPinsRev.put(key, siteTypeBelAndParameters);
            }

            siteTypeBelAndParameters.add(parameterPin.getKey());
        }

        StructList.Builder<ParameterCellBelPinMaps.Builder> parameterPinsObj = builder.initParameterPins(parameterToPinsRev.size());
        i = 0;
        for (Map.Entry<Set<Map.Entry<String, String>>, Set<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>>> entry : parameterToPinsRev.entrySet()) {
            ParameterCellBelPinMaps.Builder entryObj = parameterPinsObj.get(i);

            Set<Map.Entry<String, String>> pins = entry.getKey();
            StructList.Builder<CellBelPinEntry.Builder> pinsObj = entryObj.initPins(pins.size());
            writePinMap(allStrings, pins, pinsObj);
            writeParameterSiteTypeBels(allStrings, entryObj, entry.getValue());

            i += 1;
        }
    }

    private static class StringPairCompare implements Comparator<Map.Entry<String, String>> {
        @Override
        public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
            int result = a.getKey().compareTo(b.getKey());
            if (result != 0) {
                return result;
            }

            return a.getValue().compareTo(b.getValue());
        }
    };

    private static class SiteTypeCompare implements Comparator<SiteTypeEnum> {
        @Override
        public int compare(SiteTypeEnum a, SiteTypeEnum b) {
            return a.name().compareTo(b.name());
        }
    };

    public static class StringCompare implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            return a.compareTo(b);
        }
    };

    private static class SiteTypeStringPairCompare implements Comparator<Map.Entry<SiteTypeEnum, Map.Entry<String, String>>> {
        @Override
        public int compare(Map.Entry<SiteTypeEnum, Map.Entry<String, String>> a, Map.Entry<SiteTypeEnum, Map.Entry<String, String>> b) {
            int result = a.getKey().name().compareTo(b.getKey().name());
            if (result != 0) {
                return result;
            }

            result = a.getValue().getKey().compareTo(b.getValue().getKey());
            if (result != 0) {
                return result;
            }

            return a.getValue().getValue().compareTo(b.getValue().getValue());
        }
    }

    private static void addSite(Map<SiteTypeEnum, List<Site>> siteMap, Site site, SiteTypeEnum siteType) {
        List<Site> sites = siteMap.get(siteType);
        if (sites == null) {
            sites = new ArrayList<Site>();
            siteMap.put(siteType, sites);
        }

        sites.add(site);
    }

    public static List<List<String>> getParametersFor(Series series, String cellName) {
        List<List<String>> parameterSets = new ArrayList<List<String>>();
        if (series == Series.Versal) {
            if (cellName.equals("URAM288E5_BASE") || cellName.equals("URAM288E5")) {
                int[] portWidths = {18, 36, 72};
                String[] ports = {"A", "B"};
                for (int portWidth : portWidths) {
                    List<String> parameters = new ArrayList<String>();
                    for (String port : ports) {
                        parameters.add("EN_ECC_RD_"+port+"=FALSE");
                        parameters.add("EN_ECC_WR_"+port+"=FALSE");
                        parameters.add("WRITE_WIDTH_" + port + "=" + portWidth);
                    }
                    parameterSets.add(parameters);
                }
            } else if (cellName.equals("DSP_PREADD_DATA58") || cellName.equals("DSP_SRCMX_OPTINV")) {
                for (String mode : new String[] {"INT24", "INT8", "FP32", "CINT18"}) {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("DSP_MODE=" + mode);
                    parameterSets.add(parameters);
                }
            } else if (cellName.equals("IBUF") || cellName.equals("IBUFE3") ||
                     cellName.equals("IBUF_IBUFDISABLE") || cellName.equals("IOBUF") ||
                     cellName.equals("IOBUFE3") || cellName.equals("IOBUF_DCIEN")) {
                for (String mode : new String[] {"LVCMOS15", "LVCMOS12", "LVDCI_15"}) {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("IOSTANDARD=" + mode);
                    parameterSets.add(parameters);
                }
            } else {
                parameterSets.add(new ArrayList<String>());
            }
        } else {
            if (cellName.equals("RAMB18E1") || cellName.equals("RAMB18E2")) {
                int[] portWidths = {0, 1, 2, 4, 9, 18};
                for (int writeWidthA : portWidths) {
                    for (int writeWidthB : portWidths) {
                        List<String> parameters = new ArrayList<String>();
                        parameters.add(String.format("WRITE_WIDTH_A=%d", writeWidthA));
                        parameters.add(String.format("WRITE_WIDTH_B=%d", writeWidthB));
                        parameters.add("RAM_MODE=TDP");
                        parameters.add("DOA_REG=0");
                        parameters.add("DOB_REG=0");
                        parameterSets.add(parameters);
                    }
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=SDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=36");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=1");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=1");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }
            }
            else if (cellName.equals("RAMB36E1") || cellName.equals("RAMB36E2")) {
                int[] portWidths = {0, 1, 2, 4, 9, 18, 36};
                int[] portWidthsNoZero = {1, 2, 4, 9, 18, 36};
                for (int writeWidthA : portWidthsNoZero) {
                    for (int writeWidthB : portWidths) {
                        List<String> parameters = new ArrayList<String>();
                        parameters.add(String.format("WRITE_WIDTH_A=%d", writeWidthA));
                        parameters.add(String.format("WRITE_WIDTH_B=%d", writeWidthB));
                        parameters.add("RAM_MODE=TDP");
                        parameters.add("DOA_REG=0");
                        parameters.add("DOB_REG=0");

                        parameterSets.add(parameters);
                    }
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=SDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=72");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=1");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=1");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=1");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }

                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=1");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=1");
                    parameterSets.add(parameters);
                }


                /* FIXME: https://github.com/SymbiFlow/RapidWright/issues/2
                 {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("RAM_MODE=TDP");
                    parameters.add("WRITE_WIDTH_A=0");
                    parameters.add("WRITE_WIDTH_B=0");
                    parameters.add("DOA_REG=0");
                    parameters.add("DOB_REG=0");
                    parameterSets.add(parameters);
                }*/
            } else if (cellName.equals("IDDR") || cellName.equals("IDDR_2CLK") || cellName.equals("ODDR")) {
                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("__SRVAL=FALSE");
                    parameterSets.add(parameters);
                }
                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("__SRVAL=TRUE");
                    parameterSets.add(parameters);
                }

            } else if (cellName.equals("URAM288")) {
                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("EN_ECC_RD_A=FALSE");
                    parameters.add("EN_ECC_WR_A=FALSE");
                    parameterSets.add(parameters);
                }
                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("EN_ECC_RD_B=FALSE");
                    parameters.add("EN_ECC_WR_B=FALSE");
                    parameterSets.add(parameters);
                }
                {
                    List<String> parameters = new ArrayList<String>();
                    parameters.add("EN_ECC_RD_A=FALSE");
                    parameters.add("EN_ECC_WR_A=FALSE");
                    parameters.add("EN_ECC_RD_B=FALSE");
                    parameters.add("EN_ECC_WR_B=FALSE");
                    parameterSets.add(parameters);
                }
            } else {
                parameterSets.add(new ArrayList<String>());
            }
        }

        return parameterSets;
    }


    public static Map<SiteTypeEnum, List<Site>> createSiteMap(Device device) {
        Map<SiteTypeEnum, List<Site>> siteMap = new HashMap<SiteTypeEnum, List<Site>>();
        for (Tile[] tiles : device.getTiles()) {
            for (Tile tile : tiles) {
                for (Site site : tile.getSites()) {
                    addSite(siteMap, site, site.getSiteTypeEnum());
                    for (SiteTypeEnum site_type : site.getAlternateSiteTypeEnums()) {
                        addSite(siteMap, site, site_type);
                    }
                }
            }
        }

        return siteMap;
    }

    public static void populateCellBelPin(StringEnumerator allStrings, Map<SiteTypeEnum, List<Site>> siteMap, CellBelMapping.Builder mapping, EDIFCell topLevelCell, EDIFCell cell, Design design) {
        mapping.setCell(allStrings.getIndex(cell.getName()));
        EDIFCellInst cellInst = new EDIFCellInst("test", cell, topLevelCell);
        Cell physCell = design.createCell("test", cellInst);

        List<Map.Entry<SiteTypeEnum, String>> entries = new ArrayList<>();

        Map<SiteTypeEnum,Set<String>> sites = physCell.getCompatiblePlacements(design.getDevice());
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

        EnumerateCellBelMapping data = new EnumerateCellBelMapping();

        for (Map.Entry<SiteTypeEnum, String> possibleSite : entries) {
            SiteTypeEnum siteType = possibleSite.getKey();
            String bel = possibleSite.getValue();
            if (!siteMap.containsKey(siteType)) {
                continue;
            }

            for (List<String> parameters : getParametersFor(series, cell.getName())) {
                HashSet<Map.Entry<String, String>> pinMapping = null;

                for (Site site : siteMap.get(siteType)) {
                    if (pinMapping == null) {
                        SiteInst siteInst = design.createSiteInst("test_site", siteType, site);

                        String[] parameterArray = parameters.toArray(new String[parameters.size()]);
                        physCell = design.createAndPlaceCell("test", Unisim.valueOf(cell.getName()),
                                site.getName() + "/" + bel, parameterArray);

                        // Build complete P2L map.
                        Map<String, String> pinMap = new HashMap<String, String>();

                        for (Map.Entry<String, Set<String>> pinPair : physCell.getPinMappingsL2P().entrySet()) {
                            for (String physPin : pinPair.getValue()) {
                                pinMap.put(physPin, pinPair.getKey());
                            }
                        }

                        pinMap.putAll(physCell.getPinMappingsP2L());

                        pinMapping = new HashSet<Map.Entry<String, String>>();
                        for (Map.Entry<String, String> pinPair : pinMap.entrySet()) {
                            pinMapping.add(
                                    new AbstractMap.SimpleEntry<String, String>(pinPair.getValue(), pinPair.getKey()));
                        }

                        data.addInstance(siteType, site, bel, pinMapping, parameters);

                        design.removeCell(physCell);
                        design.removeSiteInst(siteInst);
                        topLevelCell.removeCellInst("test");
                        design.getTopEDIFCell().removeCellInst("test");
                    } else {
                        data.addInstance(siteType, site, bel, pinMapping, parameters);
                    }
                }
            }
        }

        data.writeMapping(allStrings, mapping);
    }

    public static void populateAllPinMappings(String part, Device device, DeviceResources.Device.Builder devBuilder, StringEnumerator allStrings) {
        Design design = new Design("top", part);

        EDIFLibrary prims = Design.getPrimitivesLibrary(design.getDevice().getName());
        EDIFLibrary library = new EDIFLibrary("work");

        EDIFNetlist netlist = new EDIFNetlist("netlist");
        netlist.setDevice(device);
        netlist.addLibrary(library);
        netlist.addLibrary(prims);

        EDIFCell topLevelCell = new EDIFCell(library, "top");

        EDIFDesign edifDesign = new EDIFDesign("design");
        edifDesign.setTopCell(topLevelCell);

        Map<SiteTypeEnum, List<Site>> siteMap = EnumerateCellBelMapping.createSiteMap(device);

        // Count how many cells need mapping.
        EDIFLibrary macros = Design.getMacroPrimitives(device.getSeries());
        Set<String> macroCells = new HashSet<String>();
        for (EDIFCell cell : macros.getCells()) {
            macroCells.add(cell.getName());
        }

        int count = 0;
        for (EDIFCell cell : prims.getCells()) {
            if (!macroCells.contains(cell.getName())) {
                count += 1;
            }
        }

        int i = 0;
        StructList.Builder<CellBelMapping.Builder> cellMapping = devBuilder.initCellBelMap(count);
        for (EDIFCell cell : prims.getCells()) {
            if (!macroCells.contains(cell.getName())) {
                populateCellBelPin(allStrings, siteMap, cellMapping.get(i), topLevelCell, cell, design);
                i += 1;
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Enumerate Cell<->BEL mapping: " + args[0]);
        t.useGCToTrackMemory(true);

        t.start("Load Device");
        Device device = Device.getDevice(args[0]);

        t.stop().start("Enumerate cells in sites");
        StringEnumerator allStrings = new StringEnumerator();

        MessageBuilder message = new MessageBuilder();
        DeviceResources.Device.Builder devBuilder = message.initRoot(DeviceResources.Device.factory);
        populateAllPinMappings(args[0], device, devBuilder, allStrings);

        t.stop().printSummary();
    }
}
