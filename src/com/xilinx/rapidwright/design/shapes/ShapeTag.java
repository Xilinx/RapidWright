/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development
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

package com.xilinx.rapidwright.design.shapes;

import java.util.HashMap;
import java.util.Map;

public enum ShapeTag {

    CLUSTER("Cluster"),
    LUTNM("LUTNM"),
    CARRY_CHAIN("Carry-chain"),
    MUXF7("MuxF7"),
    MUXF8("MuxF8"),
    MUXF9("MuxF9");
    
    private String name;
    
    public static Map<String, ShapeTag> values;

    static {
        values = new HashMap<>();
        for (ShapeTag tag : values()) {
            values.put(tag.name, tag);
            values.put(tag.name(), tag);
        }
    }

    private ShapeTag(String name) {
        this.name = name;
    }
    
    public String toString() {
        return name;
    }

}
