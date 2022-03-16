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
import java.util.Set;

public class LongEnumerator extends ArrayList<Long> {

    private static final long serialVersionUID = 5235125492429382642L;

    private HashMap<Long, Integer> map = new HashMap<Long, Integer>();

    public Integer getIndex(Long obj) {
        Integer idx = map.get(obj);
        if (idx == null) {
            idx = map.size();
            map.put(obj, idx);
            add(obj);
        }
        return idx;
    }

    public Integer maybeGetIndex(Long obj) {
        return map.get(obj);
    }

    public void addObject(Long obj) {
        getIndex(obj);
    }

    public void update(Long obj, int index) {
        set(index, obj);
        map.put(obj, index);
    }

    public void ensureSize(int size) {
        ensureCapacity(size);
        while(size() < size) {
            add(null);
        }
    }

    public Set<Long> keySet() {
        return map.keySet();
    }

    @Override
    public Long get(int index) {
        if(size() -1 < index) return null;
        return super.get(index);
    }

    @Override
    public void clear() {
        super.clear();
        map.clear();
    }
}
