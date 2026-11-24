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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Reads a family's fitted clock data from its {@code clock.json} (see {@link ClockData}), for development and
 * tests: at run time, the data comes from the timing data files (see {@link ClockData#get}).
 */
final class ClockDataJson {

    private ClockDataJson() {
    }

    /**
     * Reads clock data from a file.
     * @param path The clock.json file.
     * @param speedGrade The speed grade without a leading dash (e.g. 2, 2MP), or null for the data's
     *                   reference grade.
     * @return The data.
     */
    static ClockData read(Path path, String speedGrade) {
        try {
            return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), speedGrade);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses clock data.
     * @param json The JSON text.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @return The data.
     */
    static ClockData parse(String json, String speedGrade) {
        return ClockData.fromTree(toTree(new JSONObject(json)), speedGrade, null);
    }

    /**
     * Converts a JSON object to the data tree of {@link com.xilinx.rapidwright.timing.clock.ClockData}: maps,
     * lists, strings, doubles and booleans.
     */
    static Map<String, Object> toTree(JSONObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            m.put(k, toValue(o.get(k)));
        }
        return m;
    }

    private static Object toValue(Object v) {
        if (v instanceof JSONObject) {
            return toTree((JSONObject) v);
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> l = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                l.add(toValue(a.get(i)));
            }
            return l;
        }
        if (v instanceof Number) {
            return (double) ((Number) v).floatValue();
        }
        if (v == JSONObject.NULL) {
            return null;
        }
        return v;
    }
}
