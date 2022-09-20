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

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class that keeps track of EDIF Renames during parsing. Two different subclasses exist for thread-safe vs.
 * non-thread-safe implementation
 */
public abstract class EDIFReadLegalNameCache {

    /**
     * Wrapper object that overrides equals and hashCode to require an identical wrapped object
     * @param <T>
     */
    private static class IdentityEqualsHash<T> {
        private final T obj;

        private IdentityEqualsHash(T obj) {
            this.obj = obj;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IdentityEqualsHash<?> that = (IdentityEqualsHash<?>) o;
            return obj==that.obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(obj);
        }
    }

    private EDIFReadLegalNameCache() {
    }

    public abstract void setRename(EDIFName name, String rename);
    public abstract String getEDIFRename(EDIFName name);

    public String getLegalEDIFName(EDIFName name) {
        String rename = getEDIFRename(name);
        if (rename != null) {
            return rename;
        }
        return name.getName();
    }

    public static EDIFReadLegalNameCache createSingleThreaded() {
        return new EDIFReadLegalNameCache() {
            private final Map<EDIFName, String> renames = new IdentityHashMap<>();
            public void setRename(EDIFName name, String rename) {
                renames.put(name, rename);
            }
            public String getEDIFRename(EDIFName name) {
                return renames.get(name);
            }
        };
    }
    public static EDIFReadLegalNameCache createMultiThreaded() {
        return new EDIFReadLegalNameCache() {
            private final Map<IdentityEqualsHash<EDIFName>, String> renames = new ConcurrentHashMap<>();
            public void setRename(EDIFName name, String rename) {
                renames.put(new IdentityEqualsHash<>(name), rename);
            }
            public String getEDIFRename(EDIFName name) {
                return renames.get(new IdentityEqualsHash<>(name));
            }
        };
    }
}
