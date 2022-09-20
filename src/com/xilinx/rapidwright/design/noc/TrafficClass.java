/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author Zac Blair, Xilinx Research Labs.
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
package com.xilinx.rapidwright.design.noc;

import java.util.HashMap;
import java.util.Map;

/**
 * Enumerates traffic classification on a given connection
 */
public enum TrafficClass {
    LOW_LATENCY("LL"),
    BEST_EFFORT("BE"),
    ISOCHRONOUS("ISOC"),
    BANDWIDTH("BW");

    private static Map<String,TrafficClass> map;

    static {
        map = new HashMap<>();
        for (TrafficClass e : values()) {
            map.put(e.toString(), e);
        }
    }

    private final String tc;

    TrafficClass(String tc) {
        this.tc = tc;
    }

    public static TrafficClass stringToValue(String s) {
        return map.get(s);
    }

    @Override
    public String toString() {
        return tc;
    }
}
