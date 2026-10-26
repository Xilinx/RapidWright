/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, 2026, Advanced Micro Devices, Inc.
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

/**
 * Deduplicate Strings for optimized memory usage
 */
public class StringPool {

    private final Map<String,String> stringPool;

    private StringPool(Map<String, String> stringPool) {
        this.stringPool = stringPool;
    }

    /**
     * Create a new thread safe StringPool, sized to hold the given number of Strings without
     * having to grow. Growing a pool that is being filled by many threads is costly, as each
     * time its table doubles, every String already in it is moved to the new table.
     * @param initialCapacity The number of Strings expected to be pooled.
     * @return a thread safe StringPool
     */
    public static StringPool concurrentPool(int initialCapacity) {
        // ConcurrentHashMap sizes its table to hold initialCapacity entries
        final Map<String, String> concurrentMap = new ConcurrentHashMap<>(initialCapacity);
        return new StringPool(concurrentMap) {
            @Override
            public String uniquifyName(String tmpName) {
                // Look up first: a ConcurrentHashMap only avoids locking in putIfAbsent() when the key is the
                // first in its bin, whereas get() never locks, and nearly all lookups find an existing String
                final String pooled = concurrentMap.get(tmpName);
                if (pooled != null) {
                    return pooled;
                }
                return super.uniquifyName(tmpName);
            }
        };
    }

    /**
     * Create a new StringPool that should be only used by a single thread.
     * @return a non thread safe StringPool
     */
    public static StringPool singleThreadedPool() {
        return new StringPool(new HashMap<>());
    }

    /**
     * Create a new StringPool that should be only used by a single thread, sized to hold the
     * given number of Strings without having to grow.
     * @param initialCapacity The number of Strings expected to be pooled.
     * @return a non thread safe StringPool
     */
    public static StringPool singleThreadedPool(int initialCapacity) {
        // Unlike ConcurrentHashMap, HashMap sizes its table to initialCapacity bins, and grows it
        // when more than 3/4 full, so it needs initialCapacity / 0.75 bins (as Java 19's
        // HashMap.newHashMap() computes)
        return new StringPool(new HashMap<>((int) Math.min((long) Math.ceil(initialCapacity / 0.75),
                Integer.MAX_VALUE)));
    }

    /**
     * Get the pooled String equal to the given one, adding the given one to the pool if there is none.
     * @param tmpName The String to look up.
     * @return The pooled String equal to tmpName (tmpName itself if it was not already pooled).
     */
    public String uniquifyName(String tmpName) {
        final String previous = stringPool.putIfAbsent(tmpName, tmpName);
        return previous != null ? previous : tmpName;
    }

}
