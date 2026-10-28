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

package com.xilinx.rapidwright.edif;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Helper class that keeps track of EDIF Renames during parsing. It is not thread-safe: each parser (or parallel
 * parser worker) uses its own, which other threads may only read once that parser has finished writing to it.
 */
public class EDIFReadLegalNameCache {

    /** EDIFName equality is by name, so renames are looked up by object identity */
    private final Map<EDIFName, String> renames = new IdentityHashMap<>();

    public void setRename(EDIFName name, String rename) {
        renames.put(name, rename);
    }

    public String getEDIFRename(EDIFName name) {
        return renames.get(name);
    }

    /**
     * Remove all renames, e.g. to release them once they are no longer needed.
     */
    public void clear() {
        renames.clear();
    }

    public String getLegalEDIFName(EDIFName name) {
        String rename = getEDIFRename(name);
        if (rename != null) {
            return rename;
        }
        return name.getName();
    }
}
