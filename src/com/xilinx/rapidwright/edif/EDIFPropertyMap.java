/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A lightweight, live {@link Map} view over an {@link EDIFPropertyObject}'s
 * inlined property storage.
 *
 * To minimize per-object memory, EDIF property storage lives directly inside the
 * {@link EDIFPropertyObject} (see {@link EDIFPropertyObject#getRawPropertyData()})
 * rather than in a dedicated map object.  This class provides the {@link Map}
 * interface over that storage on demand: {@link EDIFPropertyObject#getPropertiesMap()}
 * returns one of these views (a small, short-lived object that holds only a
 * reference back to its owner).  All operations read and write through to the
 * owner, so the view always reflects the current properties and mutations affect
 * the underlying object.
 *
 * This view is not thread-safe (neither is the underlying storage); a single
 * EDIF object's properties are only ever mutated by one thread during parsing.
 */
public class EDIFPropertyMap implements Map<String, EDIFPropertyValue> {

    private static final Object[] EMPTY = new Object[0];

    private final EDIFPropertyObject owner;

    EDIFPropertyMap(EDIFPropertyObject owner) {
        this.owner = owner;
    }

    /** @return true if this view is backed by the given object. */
    boolean isViewOf(EDIFPropertyObject o) {
        return owner == o;
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, EDIFPropertyValue> asMap(Object d) {
        return (HashMap<String, EDIFPropertyValue>) d;
    }

    /** Returns the owner's compact array, or {@link #EMPTY} if not in compact mode. */
    private Object[] compactOrEmpty() {
        Object d = owner.getRawPropertyData();
        return (d instanceof Object[]) ? (Object[]) d : EMPTY;
    }

    @Override
    public int size() {
        return owner.getPropertyCount();
    }

    @Override
    public boolean isEmpty() {
        return owner.getPropertyCount() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return (key instanceof String) && owner.getProperty((String) key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        Object d = owner.getRawPropertyData();
        if (d == null) {
            return false;
        }
        if (d instanceof HashMap) {
            return asMap(d).containsValue(value);
        }
        Object[] a = (Object[]) d;
        for (int i = 1; i < a.length; i += 2) {
            if (Objects.equals(value, a[i])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public EDIFPropertyValue get(Object key) {
        return (key instanceof String) ? owner.getProperty((String) key) : null;
    }

    @Override
    public EDIFPropertyValue put(String key, EDIFPropertyValue value) {
        return owner.addProperty(key, value);
    }

    @Override
    public EDIFPropertyValue remove(Object key) {
        return (key instanceof String) ? owner.removeProperty((String) key) : null;
    }

    @Override
    public void putAll(Map<? extends String, ? extends EDIFPropertyValue> m) {
        for (Map.Entry<? extends String, ? extends EDIFPropertyValue> e : m.entrySet()) {
            owner.addProperty(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        owner.clearProperties();
    }

    // The collection views below intentionally do NOT decide between the compact
    // and promoted (HashMap) representations when they are created. That decision
    // is deferred to each iterator() call, so a view obtained while compact stays
    // consistent (size and iteration agree) if the backing is later promoted to a
    // HashMap by an intervening insertion.

    @Override
    public Set<String> keySet() {
        return new AbstractSet<String>() {
            @Override
            public Iterator<String> iterator() {
                Object d = owner.getRawPropertyData();
                if (d instanceof HashMap) {
                    return asMap(d).keySet().iterator();
                }
                return new BaseIterator<String>() {
                    @Override
                    String elementAt(Object[] a, int idx) {
                        return (String) a[idx];
                    }
                };
            }

            @Override
            public int size() {
                return EDIFPropertyMap.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return containsKey(o);
            }
        };
    }

    @Override
    public Collection<EDIFPropertyValue> values() {
        return new AbstractCollection<EDIFPropertyValue>() {
            @Override
            public Iterator<EDIFPropertyValue> iterator() {
                Object d = owner.getRawPropertyData();
                if (d instanceof HashMap) {
                    return asMap(d).values().iterator();
                }
                return new BaseIterator<EDIFPropertyValue>() {
                    @Override
                    EDIFPropertyValue elementAt(Object[] a, int idx) {
                        return (EDIFPropertyValue) a[idx + 1];
                    }
                };
            }

            @Override
            public int size() {
                return EDIFPropertyMap.this.size();
            }
        };
    }

    @Override
    public Set<Map.Entry<String, EDIFPropertyValue>> entrySet() {
        return new AbstractSet<Map.Entry<String, EDIFPropertyValue>>() {
            @Override
            public Iterator<Map.Entry<String, EDIFPropertyValue>> iterator() {
                Object d = owner.getRawPropertyData();
                if (d instanceof HashMap) {
                    return asMap(d).entrySet().iterator();
                }
                return new BaseIterator<Map.Entry<String, EDIFPropertyValue>>() {
                    @Override
                    Map.Entry<String, EDIFPropertyValue> elementAt(Object[] a, int idx) {
                        return new MapEntry((String) a[idx]);
                    }
                };
            }

            @Override
            public int size() {
                return EDIFPropertyMap.this.size();
            }
        };
    }

    /**
     * A live view of a single entry. {@code setValue} writes through to the
     * owning {@link EDIFPropertyObject}.
     */
    private class MapEntry implements Map.Entry<String, EDIFPropertyValue> {
        private final String key;

        MapEntry(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public EDIFPropertyValue getValue() {
            return owner.getProperty(key);
        }

        @Override
        public EDIFPropertyValue setValue(EDIFPropertyValue value) {
            return owner.addProperty(key, value);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return Objects.equals(key, e.getKey()) && Objects.equals(getValue(), e.getValue());
        }

        @Override
        public int hashCode() {
            EDIFPropertyValue v = getValue();
            return (key == null ? 0 : key.hashCode()) ^ (v == null ? 0 : v.hashCode());
        }

        @Override
        public String toString() {
            return key + "=" + getValue();
        }
    }

    /**
     * Iterator over the owner's compact array representation. Fail-fast without a
     * modification counter: it captures the backing array at creation, and any
     * structural modification replaces the owner's backing (with a new array, or
     * a HashMap on promotion), which is detected by reference identity.
     */
    private abstract class BaseIterator<E> implements Iterator<E> {
        private Object[] snapshot = compactOrEmpty();
        private int cursor = 0;

        abstract E elementAt(Object[] a, int idx);

        private void checkForComodification() {
            // compactOrEmpty() normalizes the empty (null) state to EMPTY so an
            // empty map's iterator does not spuriously report comodification.
            if (compactOrEmpty() != snapshot) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public boolean hasNext() {
            return cursor < snapshot.length;
        }

        @Override
        public E next() {
            checkForComodification();
            if (cursor >= snapshot.length) {
                throw new NoSuchElementException();
            }
            E e = elementAt(snapshot, cursor);
            cursor += 2;
            return e;
        }

        @Override
        public void remove() {
            if (cursor == 0) {
                throw new IllegalStateException();
            }
            checkForComodification();
            int idx = cursor - 2;
            owner.removeProperty((String) snapshot[idx]);
            // Our own remove replaced the owner's backing array; resync the snapshot.
            snapshot = compactOrEmpty();
            cursor = idx;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map)) {
            return false;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        if (m.size() != size()) {
            return false;
        }
        Object d = owner.getRawPropertyData();
        if (d instanceof HashMap) {
            return asMap(d).equals(m);
        }
        Object[] a = (d instanceof Object[]) ? (Object[]) d : EMPTY;
        for (int i = 0; i < a.length; i += 2) {
            if (!Objects.equals(a[i + 1], m.get(a[i]))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        // Matches the AbstractMap/Map contract (sum of entry hash codes).
        Object d = owner.getRawPropertyData();
        if (d instanceof HashMap) {
            return asMap(d).hashCode();
        }
        int h = 0;
        Object[] a = (d instanceof Object[]) ? (Object[]) d : EMPTY;
        for (int i = 0; i < a.length; i += 2) {
            int keyHash = a[i] == null ? 0 : a[i].hashCode();
            int valHash = a[i + 1] == null ? 0 : a[i + 1].hashCode();
            h += keyHash ^ valHash;
        }
        return h;
    }

    @Override
    public String toString() {
        Object d = owner.getRawPropertyData();
        if (d instanceof HashMap) {
            return asMap(d).toString();
        }
        Object[] a = (d instanceof Object[]) ? (Object[]) d : EMPTY;
        if (a.length == 0) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < a.length; i += 2) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a[i]).append('=').append(a[i + 1]);
        }
        return sb.append('}').toString();
    }
}
