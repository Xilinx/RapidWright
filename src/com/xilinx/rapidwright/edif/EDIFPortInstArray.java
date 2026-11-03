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

package com.xilinx.rapidwright.edif;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;

/**
 * Compact storage for the port instances of an {@link EDIFNet} or {@link EDIFCellInst}, replacing a
 * per-owner {@link EDIFPortInstList} (an ArrayList wrapper around an Object[]). The owner keeps a
 * single {@code Object} field ("store") that is one of:
 * <ul>
 * <li>{@code null}: no port instances,</li>
 * <li>an {@link EDIFPortInst}: exactly one port instance,</li>
 * <li>an {@code EDIFPortInst[]}: two or more port instances, sorted the same way as
 * {@link EDIFPortInstList} (see {@link EDIFPortInstList#compare}). The array may carry unused
 * capacity: unused slots are null and always at the end, so the size is the index of the first null
 * (O(1) when the array is full, which is the case after {@link #trim(Object)}).</li>
 * </ul>
 * All methods are static and return the (possibly new) store, which the owner must write back.
 * Not thread-safe (same as {@link EDIFPortInstList}).
 */
final class EDIFPortInstArray {

    private EDIFPortInstArray() {
    }

    static int size(Object store) {
        if (store == null) return 0;
        if (store instanceof EDIFPortInst) return 1;
        return arraySize((EDIFPortInst[]) store);
    }

    /** Number of non-null leading entries (unused capacity is null and at the end). */
    static int arraySize(EDIFPortInst[] a) {
        int n = a.length;
        if (n == 0 || a[n - 1] != null) return n;
        // binary search for the first null
        int lo = 0, hi = n - 1; // a[hi] == null
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] == null) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** Returns element i, or null if i is out of range. */
    static EDIFPortInst get(Object store, int i) {
        if (store == null || i < 0) return null;
        if (store instanceof EDIFPortInst) return i == 0 ? (EDIFPortInst) store : null;
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        return i < a.length ? a[i] : null;
    }

    private static int binarySearch(EDIFPortInst[] a, int size, EDIFCellInst inst, String portInstName) {
        String instName = inst == null ? null : inst.getName();
        int left = 0;
        int right = size - 1;
        while (left <= right) {
            int pivot = (left + right) >>> 1;
            int result = EDIFPortInstList.compare(a[pivot], instName, portInstName);
            if (result < 0) {
                left = pivot + 1;
            } else if (result > 0) {
                right = pivot - 1;
            } else {
                return pivot;
            }
        }
        return ~left;
    }

    private static int compareSingle(EDIFPortInst single, EDIFCellInst inst, String portInstName) {
        return EDIFPortInstList.compare(single, inst == null ? null : inst.getName(), portInstName);
    }

    static EDIFPortInst get(Object store, EDIFCellInst inst, String portInstName) {
        if (store == null) return null;
        if (store instanceof EDIFPortInst) {
            EDIFPortInst single = (EDIFPortInst) store;
            return compareSingle(single, inst, portInstName) == 0 ? single : null;
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int idx = binarySearch(a, arraySize(a), inst, portInstName);
        return idx < 0 ? null : a[idx];
    }

    private static int grow(int len) {
        // Exact growth for tiny arrays (the common 2-4 entry case needs no trim afterwards),
        // 1.5x beyond that to keep incremental adds amortized.
        return len < 4 ? len + 1 : len + (len >> 1);
    }

    /**
     * Sorted insertion; an existing equal entry (same cell inst name and port inst name) is
     * overwritten, as in {@link EDIFPortInstList#add(EDIFPortInst)}.
     */
    static Object add(Object store, EDIFPortInst e) {
        if (store == null) return e;
        EDIFCellInst inst = e.getCellInst();
        String name = e.getName();
        if (store instanceof EDIFPortInst) {
            EDIFPortInst single = (EDIFPortInst) store;
            int c = compareSingle(single, inst, name);
            if (c == 0) return e;
            return c < 0 ? new EDIFPortInst[] {single, e} : new EDIFPortInst[] {e, single};
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        int idx = binarySearch(a, size, inst, name);
        if (idx >= 0) {
            a[idx] = e;
            return a;
        }
        idx = ~idx;
        if (size < a.length) {
            System.arraycopy(a, idx, a, idx + 1, size - idx);
            a[idx] = e;
            return a;
        }
        EDIFPortInst[] b = new EDIFPortInst[grow(a.length)];
        System.arraycopy(a, 0, b, 0, idx);
        b[idx] = e;
        System.arraycopy(a, idx, b, idx + 1, size - idx);
        return b;
    }

    /**
     * Appends without sorting or duplicate replacement, as in
     * {@link EDIFPortInstList#deferSortAdd(EDIFPortInst)}. Conclude a batch with {@link #sort(Object)}.
     */
    static Object deferSortAdd(Object store, EDIFPortInst e) {
        if (store == null) return e;
        if (store instanceof EDIFPortInst) {
            return new EDIFPortInst[] {(EDIFPortInst) store, e};
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        if (size == a.length) {
            a = Arrays.copyOf(a, grow(a.length));
        }
        a[size] = e;
        return a;
    }

    /** Removes the element at index i (0 <= i < size) and returns the new store. */
    static Object removeAt(Object store, int i) {
        if (store instanceof EDIFPortInst) {
            return null;
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        if (size == 2) {
            return a[1 - i];
        }
        System.arraycopy(a, i + 1, a, i, size - i - 1);
        a[size - 1] = null;
        return a;
    }

    /** Index of the matching entry, or a negative value if none. */
    static int indexOf(Object store, EDIFCellInst inst, String portInstName) {
        if (store == null) return -1;
        if (store instanceof EDIFPortInst) {
            return compareSingle((EDIFPortInst) store, inst, portInstName) == 0 ? 0 : -1;
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        return binarySearch(a, arraySize(a), inst, portInstName);
    }

    /** Sorts the entries (after deferred adds) and trims unused capacity. */
    static Object sort(Object store) {
        if (!(store instanceof EDIFPortInst[])) return store;
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        Arrays.sort(a, 0, size);
        // Unlike add(), deferSortAdd() does not replace an existing equal port inst
        assert(!hasAdjacentDuplicates(a, size));
        return trim(a);
    }

    private static boolean hasAdjacentDuplicates(EDIFPortInst[] a, int size) {
        for (int i = 1; i < size; i++) {
            if (a[i - 1].compareTo(a[i]) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Drops unused capacity: exact-length array, single element or null. */
    static Object trim(Object store) {
        if (!(store instanceof EDIFPortInst[])) return store;
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        if (size == a.length && size > 1) return a;
        if (size == 0) return null;
        if (size == 1) return a[0];
        return Arrays.copyOf(a, size);
    }

    /** Snapshot copy as a (trimmed) {@link EDIFPortInstList}. */
    static EDIFPortInstList toList(Object store) {
        if (store == null) return null;
        return new EDIFPortInstList(new View() {
            @Override
            Object store() {
                return store;
            }

            @Override
            void store(Object s) {
                throw new UnsupportedOperationException();
            }
        });
    }

    /**
     * Live {@link java.util.List} view over an owner's store. Reads go straight to the owner's
     * current store. Mutations through the view behave like the former {@link EDIFPortInstList}:
     * {@link #add(EDIFPortInst)} is a sorted insert (overwriting an equal entry), removals keep the
     * order. Like ArrayList, mutations through the view do not update the port inst's net/cell
     * inst back-references; use the owner's add/remove methods for that. Iterators are fail-fast
     * on structural changes made outside of the iterator.
     */
    abstract static class View extends AbstractList<EDIFPortInst> implements RandomAccess {

        abstract Object store();

        abstract void store(Object s);

        @Override
        public int size() {
            return EDIFPortInstArray.size(store());
        }

        @Override
        public boolean isEmpty() {
            return store() == null || size() == 0;
        }

        @Override
        public EDIFPortInst get(int index) {
            EDIFPortInst e = EDIFPortInstArray.get(store(), index);
            if (e == null) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
            }
            return e;
        }

        @Override
        public EDIFPortInst set(int index, EDIFPortInst element) {
            Object s = store();
            EDIFPortInst old = get(index);
            if (s instanceof EDIFPortInst) {
                store(element);
            } else {
                ((EDIFPortInst[]) s)[index] = element;
            }
            return old;
        }

        @Override
        public boolean add(EDIFPortInst e) {
            store(EDIFPortInstArray.add(store(), e));
            return true;
        }

        /** The index is ignored: the element is inserted at its sorted position. */
        @Override
        public void add(int index, EDIFPortInst e) {
            add(e);
        }

        @Override
        public EDIFPortInst remove(int index) {
            EDIFPortInst old = get(index);
            store(EDIFPortInstArray.removeAt(store(), index));
            return old;
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof EDIFPortInst)) return false;
            EDIFPortInst pi = (EDIFPortInst) o;
            Object s = store();
            int idx = pi.getName() == null ? -1 : EDIFPortInstArray.indexOf(s, pi.getCellInst(), pi.getName());
            if (idx >= 0 && pi.equals(EDIFPortInstArray.get(s, idx))) {
                store(EDIFPortInstArray.removeAt(s, idx));
                return true;
            }
            return super.remove(o);
        }

        @Override
        public void clear() {
            store(null);
        }

        @Override
        public Iterator<EDIFPortInst> iterator() {
            return new Itr();
        }

        private final class Itr implements Iterator<EDIFPortInst> {
            private Object expectedStore = store();
            /** expectedStore if it is an array, else null */
            private EDIFPortInst[] arr = expectedStore instanceof EDIFPortInst[] ? (EDIFPortInst[]) expectedStore : null;
            private int expectedSize = arr != null ? arraySize(arr) : (expectedStore == null ? 0 : 1);
            private int cursor;
            private int lastRet = -1;

            @Override
            public boolean hasNext() {
                return cursor < expectedSize;
            }

            private void checkForComodification() {
                if (store() != expectedStore) throw new ConcurrentModificationException();
                EDIFPortInst[] a = arr;
                // In-place insert/remove moves the first null of the array
                if (a != null && (a[expectedSize - 1] == null
                        || (expectedSize < a.length && a[expectedSize] != null))) {
                    throw new ConcurrentModificationException();
                }
            }

            @Override
            public EDIFPortInst next() {
                int i = cursor;
                if (i >= expectedSize) throw new NoSuchElementException();
                checkForComodification();
                cursor = i + 1;
                lastRet = i;
                EDIFPortInst[] a = arr;
                return a != null ? a[i] : (EDIFPortInst) expectedStore;
            }

            @Override
            public void remove() {
                if (lastRet < 0) throw new IllegalStateException();
                checkForComodification();
                store(EDIFPortInstArray.removeAt(expectedStore, lastRet));
                expectedStore = store();
                arr = expectedStore instanceof EDIFPortInst[] ? (EDIFPortInst[]) expectedStore : null;
                expectedSize--;
                cursor = lastRet;
                lastRet = -1;
            }
        }
    }
}
