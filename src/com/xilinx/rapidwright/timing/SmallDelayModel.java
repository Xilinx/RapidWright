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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * Implement DelayModel using as small memory as possible.
 */
public class SmallDelayModel implements DelayModel {

    /**
     * Specify equivalent bels for logic delays. Equivalent bels map to the same index.
     */
    private Map<String, Short> bel2IdxMap;
    /**
     * Specify equivalent sites for logic delays. Equivalent sites map to the same index.
     */
    private Map<String, Short> site2IdxMap;

    private HashMap<String, List<int[]>> logicDelays;
    private HashMap<String, Short> intraSiteDelays;
    /**
     * Mapping between config value of a bel to a one-hot binary.
     */
    private Map<String, Integer> configCodeMap;
    
    public int getEncodedConfigCode(String value) {
        return configCodeMap.getOrDefault(value, 0);
    }

    public short getBELIndex(String belName) {
    	return bel2IdxMap.get(belName);
    }
    
    /**
     *  Implement the method with the same signature defined in DelayModel interface.
     */
    public Short getIntraSiteDelay(SiteTypeEnum siteTypeName, String frBelPin, String toBelPin) {
        boolean verbose = false;
        Short delay = null;
        Short idx = site2IdxMap.get(siteTypeName.name());
        if (idx == null) {
        	return null;
//            throw new IllegalArgumentException("SmallDelayModel: Unknown site/belName to getIntraSiteDelay."
//                    + "  site/belName " + siteTypeName + "  frBelPin " + frBelPin + "  toBelPin " + toBelPin);
        } else {
            // Certain that the following combination do not cause duplication. Otherwise, separators must be added.
            String key = idx + frBelPin + toBelPin;
            delay = intraSiteDelays.get(key);
            if (delay == null) {
                delay = -2;
                if (verbose) {
                    System.out.println("WARNING in SmallDelayModel: Unknown connection to getIntraSiteDelay."
                            + "  site/belName " + siteTypeName + "  frBelPin " + frBelPin + "  toBelPin " + toBelPin);
                }
            }
        }
        return delay;
    }

    /**
     *  Implement the method with the same signature defined in DelayModel interface.
     */
    public short getLogicDelay(short belIdx, String frBelPin, String toBelPin) {
        return getLogicDelay(belIdx, frBelPin, toBelPin, 0);
    }

    /**
     *  Implement the method with the same signature defined in DelayModel interface.
     */
    public short getLogicDelay(short belIdx, String frBelPin, String toBelPin, int encodedConfig) {
        Short delay = -2;

        // Certain that the following combination do not cause duplication. Otherwise, separators must be added.
        String key = belIdx + frBelPin + toBelPin;
        List<int[]> entries = logicDelays.get(key);

        if (entries != null) {
            for (int[] entry : entries) {
                assert entry.length == 2 :
                        " Wrong number of elements in an entry of logicDelay. " + entry.length + " expect 2.";
                if ((encodedConfig & entry[1]) == encodedConfig) {
                    delay = (short) entry[0];
                    break;
                }
            }
        }
        return delay;
    }

    /**
     * Store the given timing arc for intra-site delay.
     * @param idx      a short integer specifying a site
     * @param fr       a bel or site pin specifying the begin of the intra-site connection
     * @param to       a bel or site pin specifying the end of the intra-site connection
     * @param delay    the delay of the intra-site connection
     * @param siteName the site name to be used in case of exception
     */
    private void storeIntraSiteDelay(Short idx, String fr, String to, short delay, String siteName) {
        String key = idx + fr + to;
        if (intraSiteDelays.containsKey(key)) {
            throw new IllegalArgumentException("SmallDelayModel: Duplicate entry found for " +
                    siteName + "  fr " + fr + "  to " + to + " .");
        } else {
            intraSiteDelays.put(key, delay);
        }
    }

    /**
     * Store the given timing arc for logic delay of a bel.
     *
     * @param idx      a short integer specifying a bel
     * @param fr       a input bel pin
     * @param to       a output bel pin
     * @param delay    the logic delay between the fr and to pins
     * @param config   bit-wise OR of all valid configuration of this timing arc
     */
    // A timing arc representing a logic delay can have different values depending on bel configuration.
    // Take CARRY8 for example, if CI comes from AX pin, the logic delay from CI is 50 ps more than
    // if CI comes from CIN pin. Using config as part of the key requires more entry in the map.
    // For example, CARRY8 have 3 config parameters with 4, 4 and 2 possible values.
    // If config is included as a part of the dictionary key, a common arc will need to store 32 times
    // with only different being the config.
    // A different approach is implemented here where config is in values instead of keys.
    // Each value will have another Short to store valid configurations for the arc, called config,
    // in addition to one Short for delay value. Each value of a configuration is assigned a unique value,
    // representing in one-hot binary with.
    // The config of an arc is a bit-wise OR of all of its valid configuration.
    // As config is not in the key, values of an arc cen be a List, one element for a distinct delay value.
    // As a result, there is a small runtime overhead to go through the list.
    // However, the size of these lists is only 3. Thus, the overhead of this is much less than 2x.
    private void storeLogicDelay(short idx, String fr, String to, short delay, int config) {
        String key = idx + fr + to;
        // Is there a shortcut for this?
        int[] t = new int[2];
        t[0] = delay;
        t[1] = config;

        List<int[]> entries = null;
        if (logicDelays.containsKey(key)) {
            entries = logicDelays.get(key);
        } else {
            entries = new ArrayList<>();
        }
        entries.add(t);
        logicDelays.put(key, entries);
    }

    /**
     *  constructor for SmallDelayModel class
     * @param src specify the source for the delay model
     */
    public SmallDelayModel(DelayModelSource src) {

        logicDelays     = new HashMap<>();
        intraSiteDelays = new HashMap<>();
        bel2IdxMap      = src.getBEL2IdxMap();
        site2IdxMap     = src.getSite2IdxMap();

        // populate logic delay.
        configCodeMap   = src.getConfigCodeMap();
        List<DelayEntry> logicDelayEntries     = src.getLogicDelayEntries();
        for (DelayEntry e : logicDelayEntries) {
            String belName = e.scope;
            // Assumption 1 of DelayModelSource is satisfied by equivalent mapping in bel2IdxMap.
            Short belIdx = bel2IdxMap.get(belName);
            if (belIdx == null) {
                throw new IllegalArgumentException("SmallDelayModel: Unknown belName to " +
                        belName + " in constructing logic delay database.");
            } else {
                storeLogicDelay(belIdx, e.fr, e.to, e.delay, e.config);
            }
        }

        // populate intra site delay.
        List<DelayEntry> intraSiteDelayEntries = src.getIntraSiteDelayEntries();
        for (DelayEntry e : intraSiteDelayEntries ) {
            String siteName = e.scope;
            // Assumption 2 of DelayModelSource is satisfied by equivalent mapping in site2IdxMap.
            Short siteIdx = site2IdxMap.get(siteName);
            storeIntraSiteDelay(siteIdx, e.fr, e.to, e.delay, siteName);
        }
    }


    // ************************    helper methods     ***********************

    private void printAllLogicDelays() {
        System.out.println("\nlogicDelays SmallDelayModel");
        System.out.println("All configs");
        SortedSet<String> keys = new TreeSet<>(configCodeMap.keySet());
        for (String key : keys) {
            System.out.println("Key = " + key );
        }
        System.out.println("\n");

        System.out.println("All delay entries");
        for (Map.Entry<String, List<int[]>> entry : logicDelays.entrySet()) {
            System.out.println(entry.getKey());
            for (int[] arr : entry.getValue()) {
                System.out.println(arr[0] + " " + String.format("0x%08x",arr[1]));
            }
        }
    }
}
