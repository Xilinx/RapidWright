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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Helper class that keeps track of EDIF Renames during writing. This class is thread-safe.
 */
public abstract class EDIFWriteLegalNameCache<T> {

    /**
     * Marker String to indicate that some does not need to be renamed.
     *
     * We cannot use null, as null values have special behaviour in {@link Map#computeIfAbsent(Object, Function)}.
     */
    private static final byte[] MARKER_NO_RENAME = new byte[0];

    /**
     * Maps from unsuffixed legal name to the number of times we saw it from different source names
     */
    protected final Map<String, T> usedRenames;
    /**
     * The actual renamed names
     */
    private final Map<String, byte[]>[] renames;

    private final Map<String, byte[]> busCollisionRenames;

    private EDIFWriteLegalNameCache(Map<String, T> usedRenames, Supplier<Map<String, byte[]>> renameSupplier) {
        this.usedRenames = usedRenames;
        this.renames = new Map[256];
        for (int i = 0; i < renames.length; i++) {
            renames[i] = renameSupplier.get();
        }
        this.busCollisionRenames = new HashMap<>();
    }

    protected abstract int getAndIncrement(String rename);

    private byte[] calcRename(String name) {
        final String rename = EDIFTools.makeNameEDIFCompatible(name);
        if (rename.equals(name)) {
            return MARKER_NO_RENAME;
        }
        // Vivado does not like renames that only differ in case. Normalize to lower case
        // before checking if we already saw this rename.
        int previousCount = getAndIncrement(rename.toLowerCase());
        if (previousCount == 0) {
            return rename.getBytes(StandardCharsets.UTF_8);
        }
        return (rename+"_HDI_"+(previousCount-1)).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] getEDIFRename(String name) {
        Map<String, byte[]> map = renames[name.charAt(0)&0xFF];
        final byte[] rename = map.computeIfAbsent(name, this::calcRename);
        //Checking equality against this special marker instance is ok
        if (rename == MARKER_NO_RENAME) {
            return null;
        }
        return rename;
    }

    public byte[] getBusCollisionEDIFRename(String name) {
        return busCollisionRenames.computeIfAbsent(name,
                n -> (EDIFTools.makeNameEDIFCompatible(n) + "_BUS_").getBytes(StandardCharsets.UTF_8));
    }

    public static EDIFWriteLegalNameCache<?> singleThreaded() {
        return new EDIFWriteLegalNameCache<Integer>(new HashMap<>(), HashMap::new) {

            @Override
            protected int getAndIncrement(String rename) {
                int count = usedRenames.getOrDefault(rename, 0);
                usedRenames.put(rename, count+1);
                return count;
            }
        };
    }

    public static EDIFWriteLegalNameCache<?> multiThreaded() {
        return new EDIFWriteLegalNameCache<AtomicInteger>(new ConcurrentHashMap<>(), ConcurrentHashMap::new) {
            @Override
            protected int getAndIncrement(String rename) {
                AtomicInteger counter = usedRenames.computeIfAbsent(rename, x->new AtomicInteger());
                return counter.getAndIncrement();
            }
        };
    }

    public byte[] getLegalEDIFName(String name) {
        byte[] rename = getEDIFRename(name);
        return rename == null ? name.getBytes(StandardCharsets.UTF_8) : rename;
    }
}
