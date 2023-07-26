/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.edif;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache for speeding up {@link EDIFCell#getPortByLegalName(String, EDIFReadLegalNameCache)} when querying many ports
 */
public class EDIFPortCache {
    private final Map<String, EDIFPort> cache;
    private final EDIFCell cell;

    public EDIFPortCache(EDIFCell cell, EDIFReadLegalNameCache nameCache) {
        this.cell = cell;
        cache = new HashMap<>(cell.getPortMap());
        final Collection<EDIFPort> ports = cell.getPorts();
        for (EDIFPort port : ports) {
            final String edifName = nameCache.getLegalEDIFName(port);
            if (edifName != null) {
                cache.put(edifName, port);
            }
        }

    }

    public EDIFPort getPort(String name) {
        return cache.get(name);
    }
}
