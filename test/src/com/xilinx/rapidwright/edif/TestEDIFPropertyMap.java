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

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Validates {@link EDIFPropertyMap}, the memory-compact property map used by
 * {@link EDIFPropertyObject} (optimization "A").
 */
public class TestEDIFPropertyMap {

    private static EDIFPropertyValue v(String s) {
        return new EDIFPropertyValue(s, EDIFValueType.STRING);
    }

    /**
     * Creates a fresh, always-mutable {@link EDIFPropertyMap} view backed by a
     * new {@link EDIFPropertyObject}. (The view is the public type returned by
     * {@link EDIFPropertyObject#getPropertiesMap()}; this exercises it directly.)
     */
    private static EDIFPropertyMap newMap() {
        return new EDIFPropertyMap(new EDIFPropertyObject("test"));
    }

    private static EDIFPropertyMap newMap(Map<String, EDIFPropertyValue> src) {
        EDIFPropertyMap m = newMap();
        m.putAll(src);
        return m;
    }

    @Test
    public void testBasicOperations() {
        EDIFPropertyMap m = newMap();
        Assertions.assertTrue(m.isEmpty());
        Assertions.assertEquals(0, m.size());
        Assertions.assertNull(m.get("missing"));
        Assertions.assertFalse(m.containsKey("missing"));

        Assertions.assertNull(m.put("a", v("1")));
        Assertions.assertNull(m.put("b", v("2")));
        Assertions.assertNull(m.put("c", v("3")));
        Assertions.assertEquals(3, m.size());
        Assertions.assertFalse(m.isEmpty());

        Assertions.assertEquals("1", m.get("a").getValue());
        Assertions.assertEquals("2", m.get("b").getValue());
        Assertions.assertEquals("3", m.get("c").getValue());
        Assertions.assertTrue(m.containsKey("a"));
        Assertions.assertTrue(m.containsValue(v("2")));
        Assertions.assertFalse(m.containsValue(v("nope")));

        // Overwrite returns previous value and does not grow
        EDIFPropertyValue old = m.put("b", v("22"));
        Assertions.assertEquals("2", old.getValue());
        Assertions.assertEquals("22", m.get("b").getValue());
        Assertions.assertEquals(3, m.size());

        // Remove
        Assertions.assertEquals("1", m.remove("a").getValue());
        Assertions.assertNull(m.remove("a"));
        Assertions.assertEquals(2, m.size());
        Assertions.assertFalse(m.containsKey("a"));
        Assertions.assertEquals("22", m.get("b").getValue());
        Assertions.assertEquals("3", m.get("c").getValue());

        m.clear();
        Assertions.assertTrue(m.isEmpty());
        Assertions.assertEquals(0, m.size());
    }

    @Test
    public void testNullHandling() {
        EDIFPropertyMap m = newMap();
        Assertions.assertNull(m.get(null));
        Assertions.assertFalse(m.containsKey(null));
        Assertions.assertNull(m.remove(null));
        Assertions.assertThrows(NullPointerException.class, () -> m.put(null, v("x")));
    }

    @Test
    public void testViews() {
        EDIFPropertyMap m = newMap();
        m.put("a", v("1"));
        m.put("b", v("2"));
        m.put("c", v("3"));

        Assertions.assertEquals(3, m.keySet().size());
        Assertions.assertTrue(m.keySet().contains("a"));
        Assertions.assertTrue(m.keySet().containsAll(List.of("a", "b", "c")));

        Assertions.assertEquals(3, m.values().size());
        List<String> values = new ArrayList<>();
        for (EDIFPropertyValue val : m.values()) {
            values.add(val.getValue());
        }
        Assertions.assertTrue(values.containsAll(List.of("1", "2", "3")));

        // entrySet iteration
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<String, EDIFPropertyValue> e : m.entrySet()) {
            seen.put(e.getKey(), e.getValue().getValue());
        }
        Assertions.assertEquals(Map.of("a", "1", "b", "2", "c", "3"), seen);
    }

    @Test
    public void testEntrySetWriteThrough() {
        EDIFPropertyMap m = newMap();
        m.put("a", v("1"));
        m.put("b", v("2"));
        for (Map.Entry<String, EDIFPropertyValue> e : m.entrySet()) {
            if (e.getKey().equals("a")) {
                e.setValue(v("99"));
            }
        }
        Assertions.assertEquals("99", m.get("a").getValue());
        Assertions.assertEquals("2", m.get("b").getValue());
    }

    @Test
    public void testIteratorRemove() {
        EDIFPropertyMap m = newMap();
        m.put("a", v("1"));
        m.put("b", v("2"));
        m.put("c", v("3"));
        m.put("d", v("4"));

        Iterator<String> it = m.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equals("b") || k.equals("d")) {
                it.remove();
            }
        }
        Assertions.assertEquals(2, m.size());
        Assertions.assertTrue(m.containsKey("a"));
        Assertions.assertTrue(m.containsKey("c"));
        Assertions.assertFalse(m.containsKey("b"));
        Assertions.assertFalse(m.containsKey("d"));
    }

    @Test
    public void testConcurrentModification() {
        EDIFPropertyMap m = newMap();
        m.put("a", v("1"));
        m.put("b", v("2"));
        Iterator<String> it = m.keySet().iterator();
        it.next();
        m.put("c", v("3")); // structural modification
        Assertions.assertThrows(ConcurrentModificationException.class, it::next);
    }

    @Test
    public void testEmptyIterator() {
        EDIFPropertyMap m = newMap();
        Iterator<String> it = m.keySet().iterator();
        Assertions.assertFalse(it.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    public void testCopyConstructorAndEqualsHashCode() {
        Map<String, EDIFPropertyValue> ref = new HashMap<>();
        ref.put("x", v("10"));
        ref.put("y", v("20"));

        EDIFPropertyMap m = newMap(ref);
        Assertions.assertEquals(2, m.size());
        Assertions.assertEquals(ref, m);
        Assertions.assertEquals(m, ref);
        // Map.equals/hashCode contract: equal maps share hashCode
        Assertions.assertEquals(ref.hashCode(), m.hashCode());

        // A HashMap constructed from our map must round-trip
        Map<String, EDIFPropertyValue> copy = new HashMap<>(m);
        Assertions.assertEquals(m, copy);
    }

    @Test
    public void testPutAll() {
        EDIFPropertyMap m = newMap();
        m.put("keep", v("0"));
        Map<String, EDIFPropertyValue> src = new HashMap<>();
        src.put("a", v("1"));
        src.put("b", v("2"));
        m.putAll(src);
        Assertions.assertEquals(3, m.size());
        Assertions.assertEquals("0", m.get("keep").getValue());
        Assertions.assertEquals("1", m.get("a").getValue());
        Assertions.assertEquals("2", m.get("b").getValue());
    }

    /**
     * Collection views (keySet/values/entrySet) obtained while the backing is in
     * its compact representation must remain consistent if the map is later
     * promoted to a HashMap. In particular {@code size()} and iteration must
     * agree; a stale view must not silently drop entries.
     */
    @Test
    public void testCachedViewsSurvivePromotion() {
        EDIFPropertyMap m = newMap();
        m.put("k0", v("0")); // compact at this point

        // Obtain the views while compact, then force promotion.
        java.util.Set<String> keys = m.keySet();
        java.util.Collection<EDIFPropertyValue> vals = m.values();
        java.util.Set<Map.Entry<String, EDIFPropertyValue>> entries = m.entrySet();

        int n = EDIFPropertyObject.PROMOTE_THRESHOLD + 5;
        for (int i = 1; i < n; i++) {
            m.put("k" + i, v(Integer.toString(i)));
        }
        // Sanity: we actually crossed into the promoted representation.
        Assertions.assertTrue(m.size() > EDIFPropertyObject.PROMOTE_THRESHOLD);

        // size() of the cached views reflects the promoted map...
        Assertions.assertEquals(n, keys.size());
        Assertions.assertEquals(n, vals.size());
        Assertions.assertEquals(n, entries.size());

        // ...and iteration agrees with size() (no dropped entries).
        java.util.Set<String> iterated = new java.util.HashSet<>();
        for (String k : keys) {
            iterated.add(k);
        }
        Assertions.assertEquals(n, iterated.size());
        for (int i = 0; i < n; i++) {
            Assertions.assertTrue(iterated.contains("k" + i), "missing k" + i);
        }

        int valCount = 0;
        for (EDIFPropertyValue ignored : vals) {
            valCount++;
        }
        Assertions.assertEquals(n, valCount);

        int entryCount = 0;
        for (Map.Entry<String, EDIFPropertyValue> e : entries) {
            Assertions.assertEquals(m.get(e.getKey()), e.getValue());
            entryCount++;
        }
        Assertions.assertEquals(n, entryCount);
    }

    /**
     * Verifies the transparent promotion from the compact array representation to
     * a HashMap once the entry count crosses {@link EDIFPropertyObject#PROMOTE_THRESHOLD}.
     * Behavior and contents must be identical across the boundary.
     */
    @Test
    public void testPromotionAcrossThreshold() {
        EDIFPropertyMap m = newMap();
        HashMap<String, EDIFPropertyValue> ref = new HashMap<>();
        int n = EDIFPropertyObject.PROMOTE_THRESHOLD + 5; // force promotion
        for (int i = 0; i < n; i++) {
            m.put("k" + i, v("v" + i));
            ref.put("k" + i, v("v" + i));
            Assertions.assertEquals(i + 1, m.size());
            // Every entry inserted so far must still be retrievable
            for (int j = 0; j <= i; j++) {
                Assertions.assertEquals("v" + j, m.get("k" + j).getValue());
            }
        }
        Assertions.assertEquals(ref, m);
        Assertions.assertEquals(ref.keySet(), m.keySet());
        Assertions.assertEquals(ref.hashCode(), m.hashCode());

        // Overwrite (no size change) and removal still work after promotion
        Assertions.assertEquals("v0", m.put("k0", v("changed")).getValue());
        Assertions.assertEquals("changed", m.get("k0").getValue());
        Assertions.assertEquals(n, m.size());
        Assertions.assertEquals("changed", m.remove("k0").getValue());
        Assertions.assertNull(m.get("k0"));
        Assertions.assertEquals(n - 1, m.size());

        // Iteration over the promoted map yields all remaining entries
        int count = 0;
        for (Map.Entry<String, EDIFPropertyValue> e : m.entrySet()) {
            Assertions.assertEquals(m.get(e.getKey()), e.getValue());
            count++;
        }
        Assertions.assertEquals(n - 1, count);
    }

    /**
     * Exercises a map with several hundred entries (as seen on transceiver
     * primitives) to ensure the promoted (HashMap-backed) path is correct.
     */
    @Test
    public void testLargeMap() {
        EDIFPropertyMap m = newMap();
        HashMap<String, EDIFPropertyValue> ref = new HashMap<>();
        for (int i = 0; i < 600; i++) {
            m.put("p" + i, v(Integer.toString(i)));
            ref.put("p" + i, v(Integer.toString(i)));
        }
        Assertions.assertEquals(600, m.size());
        Assertions.assertEquals(ref, m);
        for (int i = 0; i < 600; i++) {
            Assertions.assertEquals(Integer.toString(i), m.get("p" + i).getValue());
        }
        // copy constructor of a large map stays consistent
        EDIFPropertyMap copy = newMap(m);
        Assertions.assertEquals(m, copy);
        Assertions.assertEquals(600, copy.size());
    }

    /**
     * Differential fuzz test: apply the same random sequence of operations to an
     * {@link EDIFPropertyMap} and a reference {@link HashMap} and assert that the
     * two remain equivalent throughout. The key space (24) exceeds
     * {@link EDIFPropertyObject#PROMOTE_THRESHOLD}, so this also exercises promotion.
     */
    @Test
    public void testRandomizedAgainstHashMap() {
        Random rand = new Random(0xC0FFEE);
        EDIFPropertyMap m = newMap();
        HashMap<String, EDIFPropertyValue> ref = new HashMap<>();

        String[] keys = new String[24];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "k" + i;
        }

        for (int iter = 0; iter < 20000; iter++) {
            String key = keys[rand.nextInt(keys.length)];
            int op = rand.nextInt(10);
            if (op < 6) {
                EDIFPropertyValue val = v("val" + rand.nextInt(100));
                Assertions.assertEquals(ref.put(key, val), m.put(key, val));
            } else if (op < 8) {
                Assertions.assertEquals(ref.remove(key), m.remove(key));
            } else if (op == 8) {
                Assertions.assertEquals(ref.get(key), m.get(key));
                Assertions.assertEquals(ref.containsKey(key), m.containsKey(key));
            } else {
                Assertions.assertEquals(ref.size(), m.size());
            }
            Assertions.assertEquals(ref.size(), m.size());
        }

        // Final structural equivalence
        Assertions.assertEquals(ref, m);
        Assertions.assertEquals(m, ref);
        Assertions.assertEquals(ref.keySet(), m.keySet());
        Assertions.assertEquals(ref.hashCode(), m.hashCode());
        for (String k : keys) {
            Assertions.assertEquals(ref.get(k), m.get(k));
        }
    }
}
