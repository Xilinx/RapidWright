/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, 2024, Advanced Micro Devices, Inc.
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
 * <p>
 * A name that is not a legal EDIF identifier is renamed to a legal one, once per cache. Different names can be made
 * legal as the same identifier (ignoring case, as Vivado does not like renames that differ only in case), e.g.
 * {@code a[0]} and {@code A(0)}: the first of them to be renamed gets that identifier, and each later one gets it
 * with a suffix ({@code _HDI_0}, {@code _HDI_1}, ...), in the order in which they are first renamed. Each renamed name
 * keeps the same legal identifier for as long as the cache is used, and no two renamed names share one. (A name that
 * is already legal is written as it is, without being checked against these: so a legal {@code a_0_} and a renamed
 * {@code a[0]} would share one.) Which renamed name gets which identifier depends on that order: with
 * {@link #singleThreaded()}, that is the order in which the names are written; with {@link #multiThreaded()}, shared
 * by threads writing different parts of a netlist at the same time, it depends on their timing, so the identifiers
 * (and so the text, and its length) may differ between exports of the same netlist.
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

    @SuppressWarnings("unchecked")
    private EDIFWriteLegalNameCache(Map<String, T> usedRenames, Supplier<Map<String, byte[]>> renameSupplier) {
        this.usedRenames = usedRenames;
        this.renames = new Map[256];
        for (int i = 0; i < renames.length; i++) {
            renames[i] = renameSupplier.get();
        }
        this.busCollisionRenames = renameSupplier.get();
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

    /**
     * @return A cache that can be shared by threads writing different parts of a netlist at the same time. Names that
     * are made legal as the same identifier are told apart in the order in which threads first rename them, which
     * depends on their timing (see {@link EDIFWriteLegalNameCache}).
     */
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
