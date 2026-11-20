/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;

/**
 * Classifies the nodes of clock nets into clock node classes, by an ordered list of rules on the
 * intent code and base wire name of a node: the first matching rule gives the class. A rule's class
 * may contain {@code {K}}, the intent code without its {@code NODE_GLOBAL_} or {@code NODE_} prefix
 * ({@code INTENT_DEFAULT} giving {@code DEFAULT}). The class {@link #DATA} stands for the general
 * interconnect wires that clock routes may use, which the family's data interconnect model times.
 * <p>
 * The default rules ({@code getDefaultRules}) are those the fitted clock data was classified with:
 * <ol>
 * <li>INTENT_DEFAULT, wire {@code ^CLK_LEAF_SITES_}: CLK_LEAF_IN (US+ BUFCE_LEAF input);</li>
 * <li>any intent, wire {@code _PIN$}: CLK_{K}_PIN;</li>
 * <li>NODE_GLOBAL_*, wire containing BUFCE_ROW: CLK_{K}_ROWBUF;</li>
 * <li>NODE_GLOBAL_*, wire containing PD_OPT_DELAY: CLK_{K}_OPTDLY;</li>
 * <li>NODE_GLOBAL_*, wire matching CMT_MUX or CMT_DRVR_TRI: CLK_{K}_MUX;</li>
 * <li>any other NODE_GLOBAL_*: CLK_{K};</li>
 * <li>the family's general interconnect intents: DATA;</li>
 * <li>anything else: CLK_{K} (e.g. CLK_LOCAL, CLK_PINFEED, CLK_CLE_CNODE).</li>
 * </ol>
 * The generated clock data of a family carries its rules, so that this class stays data-driven.
 */
final class ClockNodeClassifier {

    /** Class of the general interconnect nodes, timed by the data interconnect model */
    static final String DATA = "DATA";

    /** A classification rule: intent regex and wire regex (both found in the names) and class template */
    static final class Rule {
        private final Pattern intent;
        private final Pattern wire;
        private final String classTemplate;

        /**
         * @param intentRegex Regular expression found in the intent code's name.
         * @param wireRegex Regular expression found in the base wire name ("" for any wire).
         * @param classTemplate The class, possibly with {@code {K}}.
         */
        Rule(String intentRegex, String wireRegex, String classTemplate) {
            this.intent = Pattern.compile(intentRegex);
            this.wire = Pattern.compile(wireRegex);
            this.classTemplate = classTemplate;
        }

        boolean matches(String intentName, String wireName) {
            return intent.matcher(intentName).find() && wire.matcher(wireName).find();
        }

        String apply(String intentName) {
            if (!classTemplate.contains("{K}")) {
                return classTemplate;
            }
            return classTemplate.replace("{K}", intentKey(intentName));
        }

        @Override
        public String toString() {
            return intent.pattern() + " | " + wire.pattern() + " -> " + classTemplate;
        }
    }

    /** UltraScale+ general interconnect intents (rule 7) */
    private static final String USPLUS_DATA_INTENTS = "^NODE_(SINGLE|DOUBLE|HQUAD|VQUAD|HLONG|VLONG)$";
    /** Versal general interconnect intents (rule 7) */
    private static final String VERSAL_DATA_INTENTS =
            "^NODE_(HSINGLE|HDOUBLE|HQUAD|HLONG6|HLONG10|VSINGLE|VDOUBLE|VQUAD|VLONG7|VLONG12|SDQNODE|INODE|IMUX|PINBOUNCE)$";

    private final List<Rule> rules;

    /**
     * @param rules The rules, in order (the first match wins).
     */
    ClockNodeClassifier(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /**
     * Gets the default rules of a series (see the class description).
     * @param series The series (UltraScale+ or Versal; others get the UltraScale+ data intents).
     * @return The rules.
     */
    static List<Rule> getDefaultRules(Series series) {
        List<Rule> r = new ArrayList<>();
        r.add(new Rule("^INTENT_DEFAULT$", "^CLK_LEAF_SITES_", "CLK_LEAF_IN"));
        r.add(new Rule(".*", "_PIN$", "CLK_{K}_PIN"));
        r.add(new Rule("^NODE_GLOBAL_", "BUFCE_ROW", "CLK_{K}_ROWBUF"));
        r.add(new Rule("^NODE_GLOBAL_", "PD_OPT_DELAY", "CLK_{K}_OPTDLY"));
        r.add(new Rule("^NODE_GLOBAL_", "CMT_MUX|CMT_DRVR_TRI", "CLK_{K}_MUX"));
        r.add(new Rule("^NODE_GLOBAL_", "", "CLK_{K}"));
        r.add(new Rule(series == Series.Versal ? VERSAL_DATA_INTENTS : USPLUS_DATA_INTENTS, "", DATA));
        r.add(new Rule(".*", "", "CLK_{K}"));
        return r;
    }

    /** @return The classifier with the default rules of a series. */
    static ClockNodeClassifier forSeries(Series series) {
        return new ClockNodeClassifier(getDefaultRules(series));
    }

    /**
     * Gets the key {K} of an intent code: its name without the NODE_GLOBAL_ or NODE_ prefix,
     * INTENT_DEFAULT giving DEFAULT.
     */
    static String intentKey(String intentName) {
        if (intentName.equals("INTENT_DEFAULT")) {
            return "DEFAULT";
        }
        if (intentName.startsWith("NODE_GLOBAL_")) {
            return intentName.substring("NODE_GLOBAL_".length());
        }
        if (intentName.startsWith("NODE_")) {
            return intentName.substring("NODE_".length());
        }
        return intentName;
    }

    /**
     * Classifies a base wire.
     * @param intent Its intent code.
     * @param wireName Its name.
     * @return The class name, {@link #DATA} for general interconnect, or null if no rule matches.
     */
    String classify(IntentCode intent, String wireName) {
        String in = intent.name();
        for (Rule r : rules) {
            if (r.matches(in, wireName)) {
                return r.apply(in);
            }
        }
        return null;
    }

    /** Classifies a node by its base wire (see {@link #classify(IntentCode, String)}). */
    String classify(Node node) {
        return classify(node.getIntentCode(), node.getWireName());
    }
}
