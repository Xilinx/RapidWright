/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

public class IdentityEnumerator<T> extends ArrayList<T> {

    private static final long serialVersionUID = 5235125492429382642L;

    private Map<T, Integer> map = new IdentityHashMap<>();

    public Integer maybeGetIndex(T obj) {
        return map.get(obj);
    }

    public Integer getIndex(T obj) {
        int size = map.size();
        Integer index = map.putIfAbsent(obj, size);
        if (index == null) {
            index = size;
            add(obj);
        }
        return index;
    }

    public void addObject(T obj) {
        getIndex(obj);
    }

    public void update(T obj, int index) {
        set(index, obj);
        map.put(obj, index);
    }

    public void ensureSize(int size) {
        ensureCapacity(size);
        while (size() < size) {
            add(null);
        }
    }

    @Override
    public T get(int index) {
        return super.get(index);
    }

    @Override
    public void clear() {
        super.clear();
        map.clear();
    }
}
