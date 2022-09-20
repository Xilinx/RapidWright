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
 * Enumerates the four major channel types that are used in a NOC Connection
 */
public enum ChannelType {
    READ("READ"),
    READ_REQUEST("READ_REQ"),
    WRITE("WRITE"),
    WRITE_RESPONSE("WRITE_RESP");

    private static Map<String,ChannelType> map;

    static {
        map = new HashMap<>();
        for (ChannelType e : values()) {
            map.put(e.toString(), e);
        }
    }

    private final String nc;

    ChannelType(String nc) {
        this.nc = nc;
    }

    public static ChannelType stringToValue(String s) {
        return map.get(s);
    }

    @Override
    public String toString() {
        return nc;
    }
}
