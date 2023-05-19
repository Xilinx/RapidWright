/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.capnproto.StructList;
import org.capnproto.PrimitiveList;

import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.interchange.DeviceResources.Device;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CommonCellBelPinMaps;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterCellBelPinMaps;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.SiteTypeBelEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterSiteTypeBelEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelPinEntry;

class CellBelMapping {
    private class CellBelPinMapping {
        private Map<SiteTypeEnum, Set<String>>  compatiblePlacements;
        private Map<Map.Entry<SiteTypeEnum, String>, Map<String, String>> commonMaps;
        private Map<Map.Entry<SiteTypeEnum, String>, Map<String, Map<String, String>>> parameterMaps;

        private Map<String, String> readPins(String cell, StringEnumerator allStrings, StructList.Reader<CellBelPinEntry.Reader> pins) {
            Map<String, String> pinMap = new HashMap<String, String>();

            for (CellBelPinEntry.Reader pin : pins) {
                String belPin = allStrings.get(pin.getBelPin());
                String cellPin = allStrings.get(pin.getCellPin());
                String otherCellPin = pinMap.get(belPin);
                if (otherCellPin != null) {
                    throw new RuntimeException(String.format(
                                "Duplicate BEL pin entry '%s' for '%s', '%s' <-> '%s'",
                                otherCellPin, cell, cellPin, belPin));
                }
                pinMap.put(belPin, cellPin);
            }

            return pinMap;
        }

        private void addPlacements(Set<Map.Entry<SiteTypeEnum, String>> placements) {
            for (Map.Entry<SiteTypeEnum, String> placement : placements) {
                Set<String> placementsForSiteType = compatiblePlacements.get(placement.getKey());
                if (placementsForSiteType == null) {
                    placementsForSiteType = new HashSet<String>();
                    compatiblePlacements.put(placement.getKey(), placementsForSiteType);
                }

                placementsForSiteType.add(placement.getValue());
            }
        }

        public CellBelPinMapping(String cell, StringEnumerator allStrings, Device.CellBelMapping.Reader cellBelMap) {
            compatiblePlacements = new HashMap<SiteTypeEnum, Set<String>>();
            commonMaps = new HashMap<Map.Entry<SiteTypeEnum, String>, Map<String, String>>();
            parameterMaps = new HashMap<Map.Entry<SiteTypeEnum, String>, Map<String, Map<String, String>>>();

            for (CommonCellBelPinMaps.Reader commonPin : cellBelMap.getCommonPins()) {
                Map<String, String> pins = readPins(cell, allStrings, commonPin.getPins());

                for (SiteTypeBelEntry.Reader entry : commonPin.getSiteTypes()) {
                    SiteTypeEnum siteType = SiteTypeEnum.valueOf(allStrings.get(entry.getSiteType()));

                    PrimitiveList.Int.Reader belStrings = entry.getBels();
                    for (int i = 0; i < belStrings.size(); ++i) {
                        int belStringIdx = belStrings.get(i);
                        String bel = allStrings.get(belStringIdx);
                        Map.Entry<SiteTypeEnum, String> key = new AbstractMap.SimpleEntry(siteType, bel);

                        Map<String, String> otherPins = commonMaps.get(key);
                        if (otherPins != null) {
                            throw new RuntimeException(String.format(
                                        "Duplicate common pin entry for site type '%s' BEL '%s'",
                                        siteType.name(), bel));
                        }

                        commonMaps.put(key, pins);
                    }
                }
            }

            for (ParameterCellBelPinMaps.Reader parameterPin : cellBelMap.getParameterPins()) {
                Map<String, String> pins = readPins(cell, allStrings, parameterPin.getPins());

                for (ParameterSiteTypeBelEntry.Reader entry : parameterPin.getParametersSiteTypes()) {
                    SiteTypeEnum siteType = SiteTypeEnum.valueOf(allStrings.get(entry.getSiteType()));
                    String bel = allStrings.get(entry.getBel());
                    Map.Entry<SiteTypeEnum, String> key = new AbstractMap.SimpleEntry(siteType, bel);

                    Map<String, Map<String, String>> parameterToPins = parameterMaps.get(key);
                    if (parameterToPins == null) {
                        parameterToPins = new HashMap<String, Map<String, String>>();
                        parameterMaps.put(key, parameterToPins);
                    }

                    PropertyMap.Entry.Reader parameter = entry.getParameter();

                    String parameterKey = allStrings.get(parameter.getKey());
                    String parameterValue;

                    if (parameter.isTextValue()) {
                        String textValue = allStrings.get(parameter.getTextValue());
                        if (textValue.contains("\"")) {
                            throw new RuntimeException("ERROR: String '"+textValue+
                                    "'\n\t value contains unescaped '\"' "
                                    + "character. Please replace with EDIF escape value '%34%'.");
                        }

                        parameterValue = textValue;
                    } else if (parameter.isIntValue()) {
                        parameterValue = String.format("%d", parameter.getIntValue());
                    } else if (parameter.isBoolValue()) {
                        parameterValue = String.format("%d", parameter.getBoolValue());
                    } else {
                        throw new RuntimeException("ERROR: Unknown property type for key " + parameterKey);
                    }

                    String parameterStr = parameterKey + "=" + parameterValue;

                    Map<String, String> otherPins = parameterToPins.get(parameterStr);
                    if (otherPins != null) {
                        throw new RuntimeException(String.format(
                                    "Duplicate common pin entry for site type '%s' BEL '%s' parameter '%s'",
                                    siteType.name(), bel, parameterStr));
                    }

                    parameterToPins.put(parameterStr, pins);
                }
            }

            addPlacements(commonMaps.keySet());
            addPlacements(parameterMaps.keySet());
        }

        public Map<SiteTypeEnum,Set<String>> getCompatiblePlacements() {
            return compatiblePlacements;
        }

        public Map<String,String>  getPinMappingsP2L(SiteTypeEnum siteType, String bel, String... params) {
            Map<String, String> pins = new HashMap<String, String>();

            Map.Entry<SiteTypeEnum, String> key = new AbstractMap.SimpleEntry(siteType, bel);

            Map<String, String> commonPins = commonMaps.get(key);
            pins.putAll(commonPins);

            Map<String, Map<String, String>> allParameterPins = parameterMaps.get(key);

            for (String parameter : params) {
                Map<String, String> parameterPins = allParameterPins.get(parameter);
                if (parameterPins != null) {
                    pins.putAll(parameterPins);
                }
            }

            return pins;
        }
    }

    private Map<String, CellBelPinMapping> map;

    public CellBelMapping(StringEnumerator allStrings, StructList.Reader<Device.CellBelMapping.Reader> cellBelMaps) {
        map = new HashMap<String, CellBelPinMapping>();

        for (Device.CellBelMapping.Reader cellBelMap : cellBelMaps) {
            String cell = allStrings.get(cellBelMap.getCell());
            CellBelPinMapping obj = map.get(cell);
            if (obj != null) {
                throw new RuntimeException(String.format(
                            "Duplicate cell '%s' in map data",
                            cell));
            }

            map.put(cell, new CellBelPinMapping(cell, allStrings, cellBelMap));
        }
    }

    public Map<SiteTypeEnum,Set<String>>  getCompatiblePlacements(String cell) {
        return map.get(cell).getCompatiblePlacements();
    }

    public Map<String,String>  getPinMappingsP2L(String cell, SiteTypeEnum siteType, String bel, String... params) {
        return map.get(cell).getPinMappingsP2L(siteType, bel, params);
    }
}
