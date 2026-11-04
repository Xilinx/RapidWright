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

import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestCompactStringMap {

    /** A value that knows its own name, as netlist objects do */
    private static class Named {
        final String name;
        Named(String name) {
            this.name = name;
        }
        String getName() {
            return name;
        }
        @Override
        public String toString() {
            return name;
        }
    }

    private static void assertSame(Map<String, Named> expected, CompactStringMap<Named> actual) {
        Assertions.assertEquals(expected.size(), actual.size());
        Assertions.assertEquals(expected, actual);
        Assertions.assertEquals(actual, expected);
        Assertions.assertEquals(expected.hashCode(), actual.hashCode());
        Assertions.assertEquals(expected.keySet(), actual.keySet());
        Assertions.assertEquals(expected.entrySet(), actual.entrySet());
        Assertions.assertEquals(new ArrayList<>(actual.values()).size(), expected.size());
        for (Map.Entry<String, Named> e : expected.entrySet()) {
            Assertions.assertSame(e.getValue(), actual.get(e.getKey()));
            Assertions.assertTrue(actual.containsKey(e.getKey()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRandomOpsAgainstHashMap(boolean derived) {
        Random r = new Random(42);
        CompactStringMap<Named> m = derived ? new CompactStringMap<>(Named::getName) : new CompactStringMap<>();
        Map<String, Named> ref = new HashMap<>();
        for (int step = 0; step < 200000; step++) {
            String key = "n" + r.nextInt(3000);
            int op = r.nextInt(10);
            if (op < 5) {
                Named v = new Named(key);
                Assertions.assertSame(ref.put(key, v), m.put(key, v));
            } else if (op < 8) {
                Assertions.assertSame(ref.remove(key), m.remove(key));
            } else if (op == 8) {
                Assertions.assertSame(ref.get(key), m.get(key));
            } else if (r.nextInt(100) == 0) {
                // Remove about half the entries through an iterator
                Iterator<String> it = m.keySet().iterator();
                while (it.hasNext()) {
                    String k = it.next();
                    if (r.nextBoolean()) {
                        it.remove();
                        ref.remove(k);
                    }
                }
            }
            if (step % 20000 == 0) assertSame(ref, m);
        }
        assertSame(ref, m);
        Assertions.assertEquals(derived, !m.isKeyed());
        m.clear();
        ref.clear();
        assertSame(ref, m);
    }

    @Test
    public void testNullKeyAndValueAndMismatchedKey() {
        CompactStringMap<Named> m = new CompactStringMap<>(n -> n == null ? null : n.getName());
        Named a = new Named("a");
        Named nul = new Named(null);
        m.put("a", a);
        m.put(null, nul);
        Assertions.assertFalse(m.isKeyed());
        Assertions.assertSame(nul, m.get(null));
        Assertions.assertTrue(m.containsKey(null));
        // A key that differs from the value's own key switches to keyed mode, keeping all entries
        m.put("alias", a);
        Assertions.assertTrue(m.isKeyed());
        Assertions.assertSame(a, m.get("a"));
        Assertions.assertSame(a, m.get("alias"));
        Assertions.assertSame(nul, m.get(null));
        m.put("nothing", null);
        Assertions.assertTrue(m.containsKey("nothing"));
        Assertions.assertNull(m.get("nothing"));
        Assertions.assertTrue(m.containsValue(null));
        Assertions.assertEquals(4, m.size());
        Assertions.assertSame(nul, m.remove(null));
        Assertions.assertFalse(m.containsKey(null));
        Assertions.assertEquals(3, m.size());
        Assertions.assertNull(m.get(42));
        Assertions.assertFalse(m.containsKey(42));
    }

    @Test
    public void testFailFastAndEntrySetValue() {
        CompactStringMap<Named> m = new CompactStringMap<>(Named::getName);
        for (int i = 0; i < 100; i++) m.put("x" + i, new Named("x" + i));
        Assertions.assertThrows(ConcurrentModificationException.class, () -> {
            for (String k : m.keySet()) m.put(k + "_new", new Named(k + "_new"));
        });
        CompactStringMap<Named> k = new CompactStringMap<>();
        for (int i = 0; i < 100; i++) k.put("x" + i, new Named("x" + i));
        Named y = new Named("y");
        for (Map.Entry<String, Named> e : k.entrySet()) e.setValue(y);
        for (Named v : k.values()) Assertions.assertSame(y, v);
        Assertions.assertEquals(100, k.size());
    }

    @Test
    public void testCopyInIterationOrder() {
        // Inserting one map's entries into another in its iteration order (sorted by home slot) must
        // not cluster them while the destination is smaller (quadratic with a single hash multiplier)
        CompactStringMap<Named> src = new CompactStringMap<>(Named::getName);
        for (int i = 0; i < 2000000; i++) {
            Named n = new Named("top/inst_" + i);
            src.put(n.getName(), n);
        }
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            CompactStringMap<Named> dst = new CompactStringMap<>(Named::getName);
            for (Named n : src.values()) dst.put(n.getName(), n);
            Assertions.assertEquals(src.size(), dst.size());
        });
    }

    @Test
    public void testTombstoneChurn() {
        // Many insert/remove cycles over few keys must not grow the table without bound
        CompactStringMap<Named> m = new CompactStringMap<>(Named::getName);
        List<Named> live = new ArrayList<>();
        for (int i = 0; i < 1000000; i++) {
            Named n = new Named("k" + i);
            m.put(n.getName(), n);
            live.add(n);
            if (live.size() > 8) {
                Named old = live.remove(0);
                Assertions.assertSame(old, m.remove(old.getName()));
            }
        }
        Assertions.assertEquals(8, m.size());
        for (Named n : live) Assertions.assertSame(n, m.get(n.getName()));
        MapBackedHashMap<String, Named> h = new MapBackedHashMap<>(m);
        Assertions.assertEquals(8, h.size());
        Assertions.assertEquals(new HashMap<>(m), h);
        Assertions.assertEquals(new HashMap<>(m), new HashMap<>(h));
    }

    @Test
    public void testCapacityLimitFailsInsteadOfHanging() {
        int max = CompactStringMap.MAX_CAPACITY;
        int maxThreshold = (max >> 1) + (max >> 3);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // More than half full at the largest capacity: rebuild in place instead of overflowing
            Assertions.assertEquals(max, CompactStringMap.rebuildCapacity(max, maxThreshold / 2 + 1));
            Assertions.assertEquals(max, CompactStringMap.rebuildCapacity(max, maxThreshold));
            Assertions.assertThrows(IllegalStateException.class,
                    () -> CompactStringMap.rebuildCapacity(max, maxThreshold + 1));
            Assertions.assertThrows(IllegalStateException.class,
                    () -> CompactStringMap.rebuildCapacity(max >> 1, maxThreshold + 1));
            Assertions.assertThrows(IllegalStateException.class,
                    () -> CompactStringMap.capacityFor(2, Integer.MAX_VALUE));
            Assertions.assertEquals(max, CompactStringMap.capacityFor(2, maxThreshold));
        });
        // Normal growth is unchanged: threshold(16) = 10
        Assertions.assertEquals(16, CompactStringMap.rebuildCapacity(16, 5));
        Assertions.assertEquals(32, CompactStringMap.rebuildCapacity(16, 6));
        Assertions.assertEquals(64, CompactStringMap.rebuildCapacity(16, 25));
    }
}
