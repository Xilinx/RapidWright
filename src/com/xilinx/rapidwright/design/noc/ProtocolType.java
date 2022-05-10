/*
 * Copyright (c) 2022 Xilinx, Inc.
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
 * Enumerates AXI protocols 
 */
public enum ProtocolType {
    AXI_MEMORY_MAPPED("AXI_MM"),
    AXI_STREAM("AXI_STRM");

    private static Map<String,ProtocolType> map;

    static {
        map = new HashMap<>();
        for(ProtocolType e : values()) {
            map.put(e.toString(), e);
        }
    }    

    private final String p;

    ProtocolType(String p){
        this.p = p;
    }

    public static ProtocolType stringToValue(String s) {
        return map.get(s);
    }

    @Override
    public String toString() {
        return p;
    }
}
