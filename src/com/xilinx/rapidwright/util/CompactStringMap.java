/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A memory-compact, open-addressing (linear probing) {@link Map} from String keys to values, intended
 * for the very large name-keyed maps of a netlist or design (such as the instances and nets of an
 * EDIFCell, or the cells and nets of a Design).
 * <p>
 * Unlike {@link java.util.HashMap}, no per-entry Node or Entry object is allocated. The map runs in
 * one of two modes:
 * <ul>
 * <li><b>Derived-key mode</b> (created with a key function): only the values are stored and the key
 * of an entry is obtained from its value through the key function (for example the object's name).
 * This costs one reference plus one byte per table slot.</li>
 * <li><b>Keyed mode</b> (created without a key function): parallel key and value arrays, for maps
 * whose key cannot be derived from the value. This costs two references plus one byte per slot.</li>
 * </ul>
 * A derived-key map transparently switches to keyed mode (keeping all its entries) the first time an
 * entry is put under a key that is not the value's derived key, or with a null value, so that it
 * always keeps the full {@link Map} semantics. A value's derived key must not change while the value
 * is in a derived-key map: remove it under its old key before renaming it, then put it back.
 * <p>
 * Each slot has a one-byte tag: empty, removed (a tombstone), or seven bits of the key's hash. Probing
 * scans the tags and only reads a value (and its key) when the tag matches, so a lookup reads about as
 * many objects as a HashMap lookup, and an absent key usually reads none. Lookups use the cached
 * {@link String#hashCode()} of the keys. Null keys and null values are supported. Removal leaves a
 * tombstone (or an empty slot when possible), so that entries never move except when the table is
 * rebuilt on growth. Iteration order is the table order, which, as for HashMap, depends on the keys
 * and on the history of insertions; it is not the insertion order. Iterators are fail-fast on a
 * best-effort basis and support {@link Iterator#remove()}.
 * <p>
 * Like HashMap, this class is not thread-safe: concurrent reads are safe as long as no thread
 * modifies the map concurrently (lookups do not modify any state).
 *
 * @param <V> Type of the values.
 */
public class CompactStringMap<V> implements Map<String, V> {

    /** Stands for a null value in keyed mode (a null value slot is a free slot) */
    private static final Object NULL_VALUE = new Object();

    /** Tag of a slot that was never used since the table was built */
    private static final byte EMPTY = 0;
    /** Tag of a slot whose entry was removed (probing continues past it) */
    private static final byte TOMBSTONE = 1;
    // Tags of occupied slots have the top bit set (negative bytes), see tag()

    private static final int MIN_CAPACITY = 2;
    /** Largest power-of-two array length */
    static final int MAX_CAPACITY = 1 << 30;

    /** Key function of a derived-key map, null for a map created in keyed mode */
    private final Function<? super V, String> keyFn;
    /** Table of values (NULL_VALUE for a null value), null until the first insertion */
    private Object[] vals;
    /** Tag of each slot, see EMPTY, TOMBSTONE and tag() */
    private byte[] tags;
    /** Keys parallel to vals in keyed mode, null in derived-key mode */
    private String[] keys;
    /** Number of entries */
    private int size;
    /** Number of non-empty slots (entries plus tombstones) */
    private int used;
    private int modCount;

    /**
     * Creates an empty map in keyed mode (parallel key and value arrays).
     */
    public CompactStringMap() {
        this.keyFn = null;
    }

    /**
     * Creates an empty map in derived-key mode.
     * @param keyFn Gets the key of a value. It must accept any value put in the map and must not
     *              return a different key for a value while the value is in the map.
     */
    public CompactStringMap(Function<? super V, String> keyFn) {
        this.keyFn = Objects.requireNonNull(keyFn);
    }

    /** Key function of a derived-key map whose subclass overrides {@link #keyOf(Object)} */
    private static final Function<Object, String> KEY_OF_OVERRIDDEN = v -> {
        throw new UnsupportedOperationException("keyOf() is not overridden");
    };

    /**
     * Creates an empty map for a subclass that overrides {@link #keyOf(Object)} (without a key
     * function), in derived-key mode if derivedKeys is true, else in keyed mode.
     * @param derivedKeys True for derived-key mode, with keys given by the subclass's keyOf().
     */
    protected CompactStringMap(boolean derivedKeys) {
        this.keyFn = derivedKeys ? KEY_OF_OVERRIDDEN : null;
    }

    /**
     * Creates an empty map in derived-key mode with room for the expected number of entries.
     * @param keyFn See {@link #CompactStringMap(Function)}.
     * @param expectedSize Number of entries the map can hold without growing.
     */
    public CompactStringMap(Function<? super V, String> keyFn, int expectedSize) {
        this(keyFn);
        if (expectedSize > 0) allocate(capacityFor(expectedSize));
    }

    /**
     * Gets the key of a value in derived-key mode. By default this applies the key function; a subclass
     * may instead override it (see {@link #CompactStringMap(boolean)}), which lets the JIT compiler
     * inline it into lookups (a key function field is a megamorphic call when maps of several types
     * are used).
     * @param value A value of this map (not null).
     * @return The value's key.
     */
    protected String keyOf(V value) {
        return keyFn.apply(value);
    }

    /**
     * @return True if this map stores its keys (keyed mode), false if it derives them from its values.
     */
    public boolean isKeyed() {
        return keyFn == null || keys != null;
    }

    // ---------------------------------------------------------------------------------------------
    // Hashing and probing

    /**
     * Odd multipliers for {@link #slot(int, int)}, a different one for each table length. With a single
     * multiplier, inserting the entries of one map into another in iteration order (sorted by home
     * slot) while the destination table is still smaller than the source table would pile them into
     * the start of the table, making the insertions quadratic.
     */
    private static final int[] MULTIPLIERS = new int[33];
    static {
        long z = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < MULTIPLIERS.length; i++) {
            // SplitMix64
            z += 0x9E3779B97F4A7C15L;
            long x = z;
            x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
            x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
            x ^= x >>> 31;
            MULTIPLIERS[i] = (int) x | 1;
        }
    }

    /**
     * Home slot of a key in a table of the given (power of two) length: multiplicative hashing of the
     * key's (cached) String hash code, taking the top bits of the product.
     */
    private static int slot(int hash, int length) {
        int nlz = Integer.numberOfLeadingZeros(length);
        return (hash * MULTIPLIERS[nlz]) >>> (nlz + 1);
    }

    /**
     * Tag of an occupied slot: the top bit set and seven bits of the hash (independent of the slot).
     */
    private static byte tag(int hash) {
        return (byte) (((hash * 0x9E3779B9) >>> 25) | 0x80);
    }

    private static int hash(Object key) {
        return key == null ? 0 : key.hashCode();
    }

    /** Max entries plus tombstones for a table capacity: 5/8 load factor */
    private static int threshold(int capacity) {
        return (capacity >> 1) + (capacity >> 3);
    }

    private static int capacityFor(int entries) {
        return capacityFor(MIN_CAPACITY, entries);
    }

    /**
     * Smallest power-of-two capacity, at least cap, whose threshold holds the given number of
     * entries.
     * @throws IllegalStateException if that exceeds {@link #MAX_CAPACITY}.
     */
    static int capacityFor(int cap, int entries) {
        while (threshold(cap) < entries) {
            if (cap >= MAX_CAPACITY) throw new IllegalStateException("Map too large");
            cap <<= 1;
        }
        return cap;
    }

    /**
     * Capacity for rebuilding a table of the given capacity with room for at least minEntries:
     * grows it unless the table is mostly tombstones (then it is rebuilt at the same capacity),
     * and never shrinks it.
     */
    static int rebuildCapacity(int cap, int minEntries) {
        if (minEntries > threshold(cap) / 2 && cap < MAX_CAPACITY) cap <<= 1;
        return capacityFor(cap, minEntries);
    }

    @SuppressWarnings("unchecked")
    private String keyAt(Object[] tab, int i) {
        String[] ks = keys;
        return ks != null ? ks[i] : keyOf((V) tab[i]);
    }

    /**
     * @param hash Hash code of key. It is compared to the (cached) hash code of k ahead of equals(),
     *             which avoids reading the characters of keys whose tags match by chance.
     */
    private static boolean keyEquals(String k, Object key, int hash) {
        return k == key || (k != null && k.hashCode() == hash && k.equals(key));
    }

    /**
     * @return The slot of key, or -1 if it is absent.
     */
    private int find(Object key) {
        byte[] tg = tags;
        if (tg == null || !(key == null || key instanceof String)) return -1;
        int h = hash(key);
        return keys != null ? findKeyed(tg, key, h) : findDerived(tg, key, h);
    }

    // The probe loops are kept in small separate methods so that the JIT compiler inlines them into
    // callers, where the exact map class (and so keyOf()) is often known

    private int findDerived(byte[] tg, Object key, int h) {
        int mask = tg.length - 1;
        int i = slot(h, tg.length);
        byte t = tag(h);
        Object[] tab = vals;
        for (byte b; (b = tg[i]) != EMPTY; i = (i + 1) & mask) {
            if (b == t) {
                @SuppressWarnings("unchecked")
                String k = keyOf((V) tab[i]);
                if (keyEquals(k, key, h)) return i;
            }
        }
        return -1;
    }

    private int findKeyed(byte[] tg, Object key, int h) {
        int mask = tg.length - 1;
        int i = slot(h, tg.length);
        byte t = tag(h);
        String[] ks = keys;
        for (byte b; (b = tg[i]) != EMPTY; i = (i + 1) & mask) {
            if (b == t && keyEquals(ks[i], key, h)) return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static <V> V unmask(Object v) {
        return v == NULL_VALUE ? null : (V) v;
    }

    // ---------------------------------------------------------------------------------------------
    // Map

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return find(key) >= 0;
    }

    @Override
    public V get(Object key) {
        int i = find(key);
        return i < 0 ? null : unmask(vals[i]);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        int i = find(key);
        return i < 0 ? defaultValue : unmask(vals[i]);
    }

    @Override
    public boolean containsValue(Object value) {
        Object[] tab = vals;
        if (tab == null) return false;
        Object masked = value == null ? NULL_VALUE : value;
        for (Object v : tab) {
            if (v != null && (v == masked || masked.equals(v))) return true;
        }
        return false;
    }

    @Override
    public V put(String key, V value) {
        return put(key, value, false);
    }

    @Override
    public V putIfAbsent(String key, V value) {
        return put(key, value, true);
    }

    private V put(String key, V value, boolean onlyIfAbsent) {
        if (keys == null && keyFn != null) {
            if (value == null || !keyEquals(keyOf(value), key, hash(key))) {
                switchToKeyed();
            }
        }
        if (tags == null) {
            allocate(MIN_CAPACITY);
        }
        byte[] tg = tags;
        int mask = tg.length - 1;
        int h = hash(key);
        int i = slot(h, tg.length);
        byte t = tag(h);
        int firstTombstone = -1;
        for (byte b; (b = tg[i]) != EMPTY; i = (i + 1) & mask) {
            if (b == TOMBSTONE) {
                if (firstTombstone < 0) firstTombstone = i;
            } else if (b == t && keyEquals(keyAt(vals, i), key, h)) {
                V old = unmask(vals[i]);
                if (!onlyIfAbsent || old == null) {
                    vals[i] = value == null ? NULL_VALUE : value;
                }
                return old;
            }
        }
        // Absent
        if (firstTombstone >= 0) {
            i = firstTombstone;
        } else if (used + 1 > threshold(tg.length)) {
            rebuild(size + 1);
            tg = tags;
            mask = tg.length - 1;
            i = slot(h, tg.length);
            while (tg[i] != EMPTY) i = (i + 1) & mask;
            used++;
        } else {
            used++;
        }
        tg[i] = t;
        vals[i] = value == null ? NULL_VALUE : value;
        if (keys != null) keys[i] = key;
        size++;
        modCount++;
        return null;
    }

    /**
     * Rebuilds the table (dropping tombstones) with room for at least the given number of entries,
     * growing it if needed (but never shrinking it).
     */
    private void rebuild(int minEntries) {
        Object[] oldVals = vals;
        byte[] oldTags = tags;
        String[] oldKeys = keys;
        int cap = rebuildCapacity(oldTags.length, minEntries);
        Object[] newVals = new Object[cap];
        byte[] newTags = new byte[cap];
        String[] newKeys = oldKeys != null ? new String[cap] : null;
        int mask = cap - 1;
        for (int j = 0; j < oldTags.length; j++) {
            byte b = oldTags[j];
            if (b >= 0) continue; // empty or tombstone
            String k = keyAt(oldVals, j);
            int i = slot(hash(k), cap);
            while (newTags[i] != EMPTY) i = (i + 1) & mask;
            newTags[i] = b;
            newVals[i] = oldVals[j];
            if (newKeys != null) newKeys[i] = k;
        }
        vals = newVals;
        tags = newTags;
        keys = newKeys;
        used = size;
        modCount++;
    }

    private void allocate(int capacity) {
        vals = new Object[capacity];
        tags = new byte[capacity];
        keys = isKeyed() ? new String[capacity] : null;
    }

    private void switchToKeyed() {
        Object[] tab = vals;
        if (tab == null) {
            keys = new String[MIN_CAPACITY];
            allocate(MIN_CAPACITY);
        } else {
            String[] ks = new String[tab.length];
            for (int i = 0; i < tab.length; i++) {
                if (tab[i] != null) ks[i] = keyAt(tab, i);
            }
            keys = ks;
        }
    }

    @Override
    public V remove(Object key) {
        int i = find(key);
        if (i < 0) return null;
        V old = unmask(vals[i]);
        removeAt(i);
        return old;
    }

    @Override
    public boolean remove(Object key, Object value) {
        int i = find(key);
        if (i < 0 || !Objects.equals(unmask(vals[i]), value)) return false;
        removeAt(i);
        return true;
    }

    /**
     * Removes the entry in slot i without moving any other entry: the slot becomes empty if the next
     * slot is empty (as do the tombstones just before it), otherwise it becomes a tombstone.
     */
    private void removeAt(int i) {
        byte[] tg = tags;
        int mask = tg.length - 1;
        vals[i] = null;
        if (keys != null) keys[i] = null;
        if (tg[(i + 1) & mask] == EMPTY) {
            tg[i] = EMPTY;
            used--;
            for (int j = (i - 1) & mask; tg[j] == TOMBSTONE; j = (j - 1) & mask) {
                tg[j] = EMPTY;
                used--;
            }
        } else {
            tg[i] = TOMBSTONE;
        }
        size--;
        modCount++;
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> m) {
        if (tags == null && m.size() > 0) {
            allocate(capacityFor(m.size()));
        }
        for (Map.Entry<? extends String, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        if (size == 0 && used == 0) return;
        vals = null;
        tags = null;
        keys = null;
        size = 0;
        used = 0;
        modCount++;
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super V> action) {
        Object[] tab = vals;
        if (tab == null) return;
        int mc = modCount;
        for (int i = 0; i < tab.length; i++) {
            Object v = tab[i];
            if (v == null) continue;
            action.accept(keyAt(tab, i), unmask(v));
            if (mc != modCount) throw new ConcurrentModificationException();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Views

    private abstract class SlotIterator<T> implements Iterator<T> {
        private final Object[] tab = vals;
        private int next = -1;
        private int last = -1;
        private int expectedModCount = modCount;

        SlotIterator() {
            advance();
        }

        private void advance() {
            Object[] t = tab;
            if (t == null) return;
            int i = next + 1;
            while (i < t.length && t[i] == null) i++;
            next = i;
        }

        @Override
        public boolean hasNext() {
            return tab != null && next < tab.length;
        }

        int nextSlot() {
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            if (!hasNext()) throw new NoSuchElementException();
            last = next;
            advance();
            return last;
        }

        Object[] table() {
            return tab;
        }

        @Override
        public void remove() {
            if (last < 0) throw new IllegalStateException();
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            removeAt(last);
            last = -1;
            expectedModCount = modCount;
        }
    }

    private final class KeyIterator extends SlotIterator<String> {
        @Override
        public String next() {
            int i = nextSlot();
            return keyAt(table(), i);
        }
    }

    private final class ValueIterator extends SlotIterator<V> {
        @Override
        public V next() {
            return unmask(table()[nextSlot()]);
        }
    }

    private final class EntryIterator extends SlotIterator<Map.Entry<String, V>> {
        @Override
        public Map.Entry<String, V> next() {
            int i = nextSlot();
            return new MapEntry(keyAt(table(), i), CompactStringMap.<V>unmask(table()[i]));
        }
    }

    /** Entry snapshot whose setValue() writes through to the map */
    private final class MapEntry implements Map.Entry<String, V> {
        private final String key;
        private V value;

        MapEntry(String key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            V old = this.value;
            CompactStringMap.this.put(key, value);
            this.value = value;
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    @Override
    public Set<String> keySet() {
        return new AbstractSet<String>() {
            @Override
            public Iterator<String> iterator() {
                return new KeyIterator();
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(Object o) {
                return containsKey(o);
            }

            @Override
            public boolean remove(Object o) {
                int i = find(o);
                if (i < 0) return false;
                removeAt(i);
                return true;
            }

            @Override
            public void clear() {
                CompactStringMap.this.clear();
            }
        };
    }

    @Override
    public Collection<V> values() {
        return new AbstractCollection<V>() {
            @Override
            public Iterator<V> iterator() {
                return new ValueIterator();
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(Object o) {
                return containsValue(o);
            }

            @Override
            public void clear() {
                CompactStringMap.this.clear();
            }
        };
    }

    @Override
    public Set<Map.Entry<String, V>> entrySet() {
        return new AbstractSet<Map.Entry<String, V>>() {
            @Override
            public Iterator<Map.Entry<String, V>> iterator() {
                return new EntryIterator();
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(Object o) {
                if (!(o instanceof Map.Entry)) return false;
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                int i = find(e.getKey());
                return i >= 0 && Objects.equals(unmask(vals[i]), e.getValue());
            }

            @Override
            public boolean remove(Object o) {
                if (!(o instanceof Map.Entry)) return false;
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                return CompactStringMap.this.remove(e.getKey(), e.getValue());
            }

            @Override
            public void clear() {
                CompactStringMap.this.clear();
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Object

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Map)) return false;
        Map<?, ?> m = (Map<?, ?>) o;
        if (m.size() != size) return false;
        Object[] tab = vals;
        if (tab == null) return true;
        try {
            for (int i = 0; i < tab.length; i++) {
                Object v = tab[i];
                if (v == null) continue;
                String k = keyAt(tab, i);
                V value = unmask(v);
                if (value == null) {
                    if (m.get(k) != null || !m.containsKey(k)) return false;
                } else if (!value.equals(m.get(k))) {
                    return false;
                }
            }
        } catch (ClassCastException | NullPointerException unused) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        Object[] tab = vals;
        if (tab == null) return 0;
        for (int i = 0; i < tab.length; i++) {
            Object v = tab[i];
            if (v == null) continue;
            h += Objects.hashCode(keyAt(tab, i)) ^ Objects.hashCode(unmask(v));
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        Object[] tab = vals;
        if (tab != null) {
            boolean first = true;
            for (int i = 0; i < tab.length; i++) {
                Object v = tab[i];
                if (v == null) continue;
                if (!first) sb.append(", ");
                first = false;
                Object val = unmask(v);
                sb.append(keyAt(tab, i)).append('=').append(val == this ? "(this Map)" : val);
            }
        }
        return sb.append('}').toString();
    }
}
