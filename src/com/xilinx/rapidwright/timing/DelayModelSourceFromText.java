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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.util.FileTools;

/**
 * An implementation of DelayModelSource, used to provide data source to DelayModel class.
 */
class DelayModelSourceFromText extends DelayModelSource {

    // These members are built by collectConfigs and used by storeArcs
    /**
     * Map a config value to a unique index.
     */
    protected Map<String,Integer> configName2Idx;
    /**
     * Contain all possible configs names of this bel.
     */
    protected List<String> configNames;
    /**
     * Contain all config values for this bel. Each entry is the array of possible values of the 
     * corresponding configName.
     */
    protected List<List<String>>   configValues;

    /**
     * Extract the bel name from the given line. If the line contains config values,
     * create a unique idx for each config value and store it in configCodeMap.
     * Call this only when the bel is seen for the first time.
     * @param line The line containing the config values.
     * @return The bel name.
     */
    private String collectConfigs(String line) {
        final short max_num_configs = 15; // size of positive part of short
        short num_configs = 0;

        configName2Idx = new HashMap<String,Integer>();
        configNames    = new ArrayList<String>();
        configValues   = new ArrayList<List<String>>();

        List<String> items  = Arrays.asList(line.trim().split("\\s+"));
        // TODO: get equivalent bel from the line
        String[] belNames   = items.get(1).split(",");
        String belName      = belNames[0];
        String configName   = null;
        List<String> values = null;

        int i = 2;
        // look for config
        for (; i < items.size() ; i++ ) { // bel <belName> is ignored
            String e = items.get(i);
            if (e.matches("(.*):")) {
                break;
            }
        }

        for (; i < items.size() ; i++ ) { // bel <belName> is ignored
            String e = items.get(i);
            if (e.matches("(.*):")) {
                configName = e;
                configName2Idx.put(e,configNames.size());
                configNames.add(e);
                if (values != null)  {
                    configValues.add(values);  // Store values of previous configName
                }
                values  = new ArrayList<String>();
            } else { // this is value of a config
                configCodeMap.put(belName + ":" + configName + e, (short) Math.pow(2, num_configs++));
                values.add(e);
                assert num_configs < max_num_configs :
                        "num_configs is too high. Please change data type to accommodate " + num_configs + ".";
            }
        }
        if (values != null) {
            configValues.add(values);  // Store values of the last configName
        }

        return belName;
    }

    /**
     * Process the given line for logic delay.
     * @param belName The current bel under processing.
     * @param line    The line to be processed.
     */
    private void storeLogicDelayArc(String belName, String line) {

        List<String> items = Arrays.asList(line.trim().split("\\s+"));

        String src = items.get(0);
        String dst = items.get(1);
        Short  dly = Short.parseShort(items.get(2));

        Short  config = -1; // all 1

        if (items.size() > 3) {
            Short[] cfgArray  = new Short[configNames.size()]; // initialize to null
            String configName = null;
            config = 0;

            // fill out config listed for this arc
            for (int i = 3; i < items.size(); i++) {
                String e = items.get(i);
                if (e.matches("(.*):")) {
                    configName = e;
                    Integer idx = configName2Idx.get(e);
                    if (idx == null) {
                        throw new IllegalArgumentException("Unknown config name " + e + " in " + line);
                    } else {
                        cfgArray[idx] = 1;
                    }
                } else { // this is value of a config
                    Short tconfig = configCodeMap.get(belName + ":" + configName + e);
                    config = (short) (config | tconfig);
                }
            }

            // fill out absence configName, which mean all values of the configName is applicable to this arc.
            for (int i = 0; i < cfgArray.length; i++) {
                if (cfgArray[i] == null) {
                    configName = configNames.get(i);
                    for (String e : configValues.get(i)) {
                        Short tconfig = configCodeMap.get(belName + ":" + configName + e);
                        config = (short) (config | tconfig);
                    }
                }
            }
        }

        List<String> srcs = Arrays.asList(src.trim().split(","));
        List<String> dsts = Arrays.asList(dst.trim().split(","));
        for (String s : srcs) {
            for (String t : dsts) {
                logicDelays.add(new DelayEntry(belName, s, t, dly, config));
            }
        }
    }

    /**
     * Process the given line for intra-site delay.
     * @param siteName The current site under processing.
     * @param line     The line to be processed.
     */
    private void storeIntraSiteDelayArc(String siteName, String line) {
        String[] items = line.split("\\s+");
        String[] src   = items[0].split(",");
        String[] dst   = items[1].split(",");
        Short    dly   = Short.parseShort(items[2]);

        String   key         = null;
        String[] replacement = null;
        if ( items.length > 3 ) {
            key = items[3].substring(0, items[3].length()-1); // remove trailing :
        }

        Short  config = -1; // all 1

        for ( String f : src) {
            for (String t : dst) {
                // TODO: remove the key itself from the replacement to allow adding
                //  the original one first followed by substition loop without if
                if (key == null) {
                    intraSiteDelays.add(new DelayEntry(siteName, f, t, dly, config));
                } else {
                    for (int i = 4; i < items.length; i++) {
                        String val = items[i];
                        String tf  = f.replaceAll(key, val);
                        String tt  = t.replaceAll(key, val);
                        intraSiteDelays.add(new DelayEntry(siteName, tf, tt, dly, config));
                    }
                }
            }
        }
    }

    /**
     * Parse and dispatch each line of the given file to either  storeLogicDelayArc or storeIntraSiteDelayArc.
     * @param fileName Specify the text file to load logic and intra-site delays from.
     */
    private void readIntraSiteDelays(String fileName) {

        InputStream inputStream = null;
        Scanner sc = null;

        String siteName = null;
        String belName  = null;

        try {
            inputStream = FileTools.getRapidWrightResourceInputStream(fileName);
            sc = new Scanner(inputStream, "UTF-8");
            while (sc.hasNextLine()) {
                // Make canonical from "," without spaces
                String line = sc.nextLine().trim().replaceAll(",\\s+",",");


                String testLine = line.replaceAll("\\s+", "");
                boolean lineIsBlank = testLine.isEmpty();

                if (lineIsBlank || line.trim().matches("^#.*")) { // if not a comment line
//                    System.out.println("skip " + line);
                } else {
                    // TODO: consider changing this construct so that only the keywords (bel,site)
                    //  are specified in only one place.
                    Pattern pattern = Pattern.compile("^(bel|site) (\\w+)");
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        if (matcher.group(1).equalsIgnoreCase("bel")) {
                            belName = collectConfigs(line);
                            siteName = null;
                        } else if (matcher.group(1).equalsIgnoreCase("site")) {
                            belName = null;
                            siteName = matcher.group(2);
                        }
                    } else {
                        if (belName != null) {
                            storeLogicDelayArc(belName, line);
                        } else if (siteName != null) {
                            storeIntraSiteDelayArc(siteName, line);
                        }
                    }
                }
            }
            // Note that Scanner suppresses exceptions
            if (sc.ioException() != null) {
                throw sc.ioException();
            }
        } catch (IOException ex) {
            System.out.println (ex.toString());
            System.out.println("IOException during reading file " + fileName);
        }

        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException ex) {
            System.out.println (ex.toString());
            System.out.println("IOException during reading file " + fileName);
        } finally {
            if (sc != null) {
                sc.close();
            }
        }
    }


    /**
     * Constructor for  DelayModelSourceFromText class.
     * @param fileName Specify the text file to load logic and intra-site delays from.
     */
    public DelayModelSourceFromText(String fileName) {
        logicDelays     = new ArrayList<DelayEntry>();
        intraSiteDelays = new ArrayList<DelayEntry>();
        configCodeMap   = new HashMap<String, Short>();
        readIntraSiteDelays(fileName);
    }

    // ************************    helper methods     ***********************
    /**
     * For unit testing.
     */
    public void printConfigCodeMap() {
        System.out.println("\n");
        SortedSet<String> keys = new TreeSet<>(configCodeMap.keySet());
        for (String key : keys) {
            System.out.println("Key = " + key + " : values " +  Integer.toBinaryString(configCodeMap.get(key)));
        }
    }
    /**
     * For unit testing.
     */
    public void printListOfString(String words, List<String> in) {
        System.out.print(words);
        for (String s : in) {
            System.out.print(" " + s);
        }
        System.out.println();
    }
    /**
     * For unit testing.
     */
    public void printListOfListOfString(String words, List<List<String>> in) {
        System.out.println("\n" + words);
        for (List<String> s : in) {
            printListOfString("", s);
        }
        System.out.println();
    }
}