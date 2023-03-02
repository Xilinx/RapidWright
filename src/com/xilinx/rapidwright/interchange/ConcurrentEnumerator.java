/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD, Inc.
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

package com.xilinx.rapidwright.interchange;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread safe version of {@link Enumerator}
 * 
 * @param <T> Class type to enumerate
 */
public class ConcurrentEnumerator<T> extends Enumerator<T> {

    private static final long serialVersionUID = -7899071863571443164L;

    public ConcurrentEnumerator() {
        super(new ConcurrentHashMap<>());
    }

    public Integer getIndex(T obj) {
        String key = getKey(obj);
        return map.computeIfAbsent(key, (k) -> {
            int size;
            synchronized(this) {
                size = size();
                add(obj);
            }
            return size;
        });
    }
}
