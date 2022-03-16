/* 
 * Copyright (c) 2020 Xilinx, Inc. 
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
import java.util.HashMap;

import com.xilinx.rapidwright.edif.EDIFEnumerable;

public class Enumerator<T> extends ArrayList<T> {

    private static final long serialVersionUID = 5235125492429382642L;

    private HashMap<String, Integer> map = new HashMap<String, Integer>();

    private String getKey(T obj) {
        String key = null;
        if(obj instanceof EDIFEnumerable) {
            key = ((EDIFEnumerable)obj).getUniqueKey();
        } else {
            key = obj.toString();
        }
        return key;
    }

    public Integer maybeGetIndex(T obj) {
        String key = getKey(obj);
        return map.get(key);
    }

    public Integer getIndex(T obj) {
        String key = getKey(obj);
        Integer idx = map.get(key);
        if (idx == null) {
            idx = map.size();
            map.put(key, idx);
            add(obj);
        }
        return idx;
    }

    public void addObject(T obj) {
        getIndex(obj);
    }

    public void update(T obj, int index) {
        set(index, obj);
        map.put(getKey(obj), index);
    }

    public void ensureSize(int size) {
        ensureCapacity(size);
        while(size() < size) {
            add(null);
        }
    }

    @Override
    public T get(int index) {
        if(size() -1 < index) return null;
        return super.get(index);
    }

    @Override
    public void clear() {
        super.clear();
        map.clear();
    }
}
