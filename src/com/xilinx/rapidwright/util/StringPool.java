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

package com.xilinx.rapidwright.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Deduplicate Strings for optimized memory usage
 */
public class StringPool {

    private final Map<String,String> stringPool;

    private StringPool(Map<String, String> stringPool) {
        this.stringPool = stringPool;
    }

    /**
     * Create a new thread safe StringPool
     * @return a thread safe StringPool
     */
    public static StringPool concurrentPool() {
        return new StringPool(new ConcurrentHashMap<>());
    }

    /**
     * Create a new StringPool that should be only used by a single thread.
     * @return a non thread safe StringPool
     */
    public static StringPool singleThreadedPool() {
        return new StringPool(new HashMap<>());
    }

    public String uniquifyName(String tmpName) {
        return stringPool.computeIfAbsent(tmpName, Function.identity());
    }

}
