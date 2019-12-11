/*
 *
 * Copyright (c) 2019 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

package com.xilinx.rapidwright.timing;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An abstract class defining interface for DelayModelSource, used to provide data source to DelayModel class.
 * It also defines equivalent sites and bels.
 */
abstract class DelayModelSource {

    protected String name = "DelayModelSource";
    // pass data to DelayModel. The data will be stored in a different way for runtime efficiency.
    /**
     * Store timing arcs for logic delays.
     */
    protected List<DelayEntry> logicDelays;
    /**
     * Store timing arcs for intra-site delays.
     */
    protected List<DelayEntry> intraSiteDelays;
    // for looking up config for a given bel instance
    /**
     * Mapping between config value of a bel to a one-hot binary.
     */
    protected Map<String, Short> configCodeMap;


    // TODO: populate from files
    /**
     * Specify equivalent bels for logic delays. Equivalent bels map to the same index.
     */
    private static final Map<String, Short> bel2IdxMap;
    static {
        HashMap<String, Short> aMap = new HashMap<String, Short>();
        aMap.put("CARRY8", (short) 0);
        aMap.put("A6LUT",  (short) 1);
        aMap.put("B6LUT",  (short) 1);
        aMap.put("C6LUT",  (short) 1);
        aMap.put("D6LUT",  (short) 1);
        aMap.put("E6LUT",  (short) 1);
        aMap.put("F6LUT",  (short) 1);
        aMap.put("G6LUT",  (short) 1);
        aMap.put("H6LUT",  (short) 1);
        aMap.put("A5LUT",  (short) 2);
        aMap.put("B5LUT",  (short) 2);
        aMap.put("C5LUT",  (short) 2);
        aMap.put("D5LUT",  (short) 2);
        aMap.put("E5LUT",  (short) 2);
        aMap.put("F5LUT",  (short) 2);
        aMap.put("G5LUT",  (short) 2);
        aMap.put("H5LUT",  (short) 2);
        aMap.put("AFF",    (short) 3);
        aMap.put("AFF2",   (short) 3);
        aMap.put("BFF",    (short) 3);
        aMap.put("BFF2",   (short) 3);
        aMap.put("CFF",    (short) 3);
        aMap.put("CFF2",   (short) 3);
        aMap.put("DFF",    (short) 3);
        aMap.put("DFF2",   (short) 3);
        aMap.put("EFF",    (short) 3);
        aMap.put("EFF2",   (short) 3);
        aMap.put("FFF",    (short) 3);
        aMap.put("FFF2",   (short) 3);
        aMap.put("GFF",    (short) 3);
        aMap.put("GFF2",   (short) 3);
        aMap.put("HFF",    (short) 3);
        aMap.put("HFF2",   (short) 3);
        aMap.put("F7MUX_AB",   (short) 4);
        aMap.put("F7MUX_CD",   (short) 4);
        aMap.put("F7MUX_EF",   (short) 4);
        aMap.put("F7MUX_GH",   (short) 4);
        bel2IdxMap = Collections.unmodifiableMap(aMap);
    }

    // Specify equivalent sites for intra-site interconnect delays
    // TODO: populate from files
    /**
     * Specify equivalent sites for logic delays. Equivalent sites map to the same index.
     */
    private static final Map<String, Short> site2IdxMap;
    static {
        HashMap<String, Short> aMap = new HashMap<String, Short>();
        aMap.put("SLICEL", (short) 0);
        aMap.put("SLICEM", (short) 0);
        site2IdxMap= Collections.unmodifiableMap(aMap);
    }

    public String getName() {
        return name;
    };
    public List<DelayEntry> getLogicDelayEntries() {
        return Collections.unmodifiableList(logicDelays);
    }
    public List<DelayEntry> getIntraSiteDelayEntries() {
        return Collections.unmodifiableList(intraSiteDelays);
    }
    public Map<String, Short> getConfigCodeMap() {
        return Collections.unmodifiableMap(configCodeMap);
    }
    public Map<String, Short> getBEL2IdxMap() {
        return Collections.unmodifiableMap(bel2IdxMap);
    }
    public Map<String, Short> getSite2IdxMap() {
        return Collections.unmodifiableMap(site2IdxMap);
    }
}






