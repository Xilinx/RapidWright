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
import java.util.List;
import java.util.ListIterator;
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
 * <p>
 * Structural changes are detectable without a modification counter in the owner: an array store
 * is only ever changed in place by inserting into its unused capacity, which moves its first null
 * forward. Removals and reordering sorts always return a new array (or a single element/null). So
 * if the store is the same array and still has the same size, it has not been structurally
 * modified (see {@link #sameStructure(Object, int, Object)}).
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
        return insertAt(a, size, ~idx, e);
    }

    /**
     * Inserts e at index idx (0 <= idx <= size), shifting later entries up; does not check the
     * sort order. Uses the unused capacity of an array store in place, if any.
     */
    static Object insertAt(Object store, int idx, EDIFPortInst e) {
        if (store == null) return e;
        if (store instanceof EDIFPortInst) {
            EDIFPortInst single = (EDIFPortInst) store;
            return idx == 0 ? new EDIFPortInst[] {e, single} : new EDIFPortInst[] {single, e};
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        return insertAt(a, arraySize(a), idx, e);
    }

    private static EDIFPortInst[] insertAt(EDIFPortInst[] a, int size, int idx, EDIFPortInst e) {
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
     * Compares e with the entry a, using the same order as {@link EDIFPortInstList}: negative if
     * a sorts before e, 0 if they have the same cell inst name and port inst name.
     */
    private static int compare(EDIFPortInst a, EDIFPortInst e) {
        return compareSingle(a, e.getCellInst(), e.getName());
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

    /**
     * Removes the element at index i (0 <= i < size) and returns the new store. Never modifies an
     * array in place: the result is a new exact-size array (or a single element/null), so that
     * live iterators can detect the removal (see the class comment).
     */
    static Object removeAt(Object store, int i) {
        if (store instanceof EDIFPortInst) {
            return null;
        }
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        if (size == 2) {
            return a[1 - i];
        }
        EDIFPortInst[] b = new EDIFPortInst[size - 1];
        System.arraycopy(a, 0, b, 0, i);
        System.arraycopy(a, i + 1, b, i, size - i - 1);
        return b;
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

    /**
     * Sorts the entries (after deferred adds) and trims unused capacity. An array that is not
     * already sorted is sorted in a new array, so that live iterators can detect the reordering
     * (see the class comment).
     */
    static Object sort(Object store) {
        if (!(store instanceof EDIFPortInst[])) return store;
        EDIFPortInst[] a = (EDIFPortInst[]) store;
        int size = arraySize(a);
        if (!isSorted(a, size)) {
            a = Arrays.copyOf(a, size);
            Arrays.sort(a);
        }
        // Unlike add(), deferSortAdd() does not replace an existing equal port inst
        assert(!hasAdjacentDuplicates(a, size));
        return trim(a);
    }

    private static boolean isSorted(EDIFPortInst[] a, int size) {
        for (int i = 1; i < size; i++) {
            if (a[i - 1].compareTo(a[i]) > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAdjacentDuplicates(EDIFPortInst[] a, int size) {
        for (int i = 1; i < size; i++) {
            if (a[i - 1].compareTo(a[i]) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code current} has the same structure as {@code expected} had when it held
     * {@code expectedSize} elements, i.e. no element was inserted or removed and no reordering sort
     * happened in between (see the class comment). Replacing the element of a single-element store
     * (by {@link View#set} or by overwriting an equal port inst) is not a structural change.
     */
    static boolean sameStructure(Object expected, int expectedSize, Object current) {
        if (expected instanceof EDIFPortInst[]) {
            if (current != expected) return false;
            EDIFPortInst[] a = (EDIFPortInst[]) expected;
            return a[expectedSize - 1] != null && (expectedSize == a.length || a[expectedSize] == null);
        }
        if (expected == null) return current == null;
        return current instanceof EDIFPortInst;
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

        /**
         * Inserts e at the given index. The list must stay sorted without duplicates (port insts
         * are looked up by binary search), so index must be the sorted position of e and no equal
         * port inst (same cell inst name and port inst name) may be present.
         * @throws IndexOutOfBoundsException if index is not in [0, size()].
         * @throws IllegalArgumentException if e does not belong at index.
         */
        @Override
        public void add(int index, EDIFPortInst e) {
            Object s = store();
            int size = EDIFPortInstArray.size(s);
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if ((index > 0 && compare(EDIFPortInstArray.get(s, index - 1), e) >= 0)
                    || (index < size && compare(EDIFPortInstArray.get(s, index), e) <= 0)) {
                throw new IllegalArgumentException("Index " + index + " is not the sorted position of "
                        + e + " (or an equal port inst is already present); use add(EDIFPortInst)");
            }
            store(insertAt(s, index, e));
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

        /**
         * Forward iterator with the same fail-fast rules as {@link ListItr}, kept small so that the
         * JIT inlines it (and scalar-replaces the view and the iterator) in for-each loops: the
         * common array case is one identity check plus the first-null check.
         */
        private final class Itr implements Iterator<EDIFPortInst> {
            private Object expectedStore = store();
            /** expectedStore if it is an array, else null */
            private EDIFPortInst[] arr = expectedStore instanceof EDIFPortInst[] ? (EDIFPortInst[]) expectedStore : null;
            private int expectedSize = arr != null ? arraySize(arr) : (expectedStore == null ? 0 : 1);
            private int cursor;
            private int lastRet = -1;
            private EDIFPortInst lastElem;

            @Override
            public boolean hasNext() {
                return cursor < expectedSize;
            }

            @Override
            public EDIFPortInst next() {
                Object s = store();
                EDIFPortInst[] a = arr;
                int i = cursor;
                EDIFPortInst e;
                if (a != null) {
                    int n = expectedSize;
                    // In-place insertion moves the first null of the array (see class comment)
                    if (s != a || a[n - 1] == null || (n < a.length && a[n] != null)) {
                        throw new ConcurrentModificationException();
                    }
                    if (i >= n) throw new NoSuchElementException();
                    e = a[i];
                } else {
                    // Empty or single-element store, checked inline (no call) so that the JIT can
                    // still scalar-replace this iterator. Replacing the single element is not
                    // structural (same as sameStructure()), so return the current one.
                    if (i >= expectedSize) throw new NoSuchElementException();
                    if (!(s instanceof EDIFPortInst)) throw new ConcurrentModificationException();
                    e = (EDIFPortInst) s;
                }
                cursor = i + 1;
                lastRet = i;
                return lastElem = e;
            }

            @Override
            public void remove() {
                if (lastRet < 0) throw new IllegalStateException();
                Object s = store();
                if (!sameStructure(expectedStore, expectedSize, s)
                        || (s instanceof EDIFPortInst && s != lastElem)) {
                    throw new ConcurrentModificationException();
                }
                store(removeAt(s, lastRet));
                expectedStore = store();
                arr = expectedStore instanceof EDIFPortInst[] ? (EDIFPortInst[]) expectedStore : null;
                expectedSize--;
                cursor = lastRet;
                lastRet = -1;
                lastElem = null;
            }
        }

        @Override
        public ListIterator<EDIFPortInst> listIterator() {
            return new ListItr(0);
        }

        @Override
        public ListIterator<EDIFPortInst> listIterator(int index) {
            int size = size();
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return new ListItr(index);
        }

        /**
         * Sub-list view. Like the sub-lists of ArrayList, it fails fast (ConcurrentModificationException)
         * once the list is structurally modified other than through the sub-list.
         */
        @Override
        public List<EDIFPortInst> subList(int fromIndex, int toIndex) {
            int size = size();
            if (fromIndex < 0 || toIndex > size || fromIndex > toIndex) {
                throw new IndexOutOfBoundsException("fromIndex: " + fromIndex + ", toIndex: " + toIndex
                        + ", Size: " + size);
            }
            return new SubList(this, fromIndex, toIndex - fromIndex);
        }

        /**
         * List iterator (also see {@link Itr}) that is fail-fast on structural changes made other than through itself,
         * including changes made through the owner (see {@link #sameStructure}). Replacing the
         * element of a single-element list is not structural, so next() returns the current element;
         * remove() and set() still refuse to act on an element other than the one last returned.
         */
        private final class ListItr implements ListIterator<EDIFPortInst> {
            private Object expectedStore;
            private int expectedSize;
            private int cursor;
            private int lastRet = -1;
            private EDIFPortInst lastElem;

            ListItr(int index) {
                resync();
                cursor = index;
            }

            private void resync() {
                expectedStore = store();
                expectedSize = EDIFPortInstArray.size(expectedStore);
            }

            private Object checkForComodification() {
                Object s = store();
                if (!sameStructure(expectedStore, expectedSize, s)) {
                    throw new ConcurrentModificationException();
                }
                return s;
            }

            /** Checks that lastRet still refers to lastElem (needed for single-element stores). */
            private Object checkLastRet() {
                if (lastRet < 0) throw new IllegalStateException();
                Object s = checkForComodification();
                if (s instanceof EDIFPortInst && s != lastElem) {
                    throw new ConcurrentModificationException();
                }
                return s;
            }

            @Override
            public boolean hasNext() {
                return cursor < expectedSize;
            }

            @Override
            public boolean hasPrevious() {
                return cursor > 0;
            }

            @Override
            public int nextIndex() {
                return cursor;
            }

            @Override
            public int previousIndex() {
                return cursor - 1;
            }

            @Override
            public EDIFPortInst next() {
                Object s = checkForComodification();
                int i = cursor;
                if (i >= expectedSize) throw new NoSuchElementException();
                EDIFPortInst e = s instanceof EDIFPortInst[] ? ((EDIFPortInst[]) s)[i] : (EDIFPortInst) s;
                cursor = i + 1;
                lastRet = i;
                return lastElem = e;
            }

            @Override
            public EDIFPortInst previous() {
                Object s = checkForComodification();
                int i = cursor - 1;
                if (i < 0) throw new NoSuchElementException();
                EDIFPortInst e = s instanceof EDIFPortInst[] ? ((EDIFPortInst[]) s)[i] : (EDIFPortInst) s;
                cursor = i;
                lastRet = i;
                return lastElem = e;
            }

            @Override
            public void remove() {
                Object s = checkLastRet();
                store(removeAt(s, lastRet));
                resync();
                cursor = lastRet;
                lastRet = -1;
                lastElem = null;
            }

            @Override
            public void set(EDIFPortInst e) {
                checkLastRet();
                View.this.set(lastRet, e);
                resync();
                lastElem = e;
            }

            @Override
            public void add(EDIFPortInst e) {
                checkForComodification();
                View.this.add(cursor, e);
                resync();
                cursor++;
                lastRet = -1;
                lastElem = null;
            }
        }
    }

    /**
     * Sub-list of a {@link View}. Checks the root's store before every access (see
     * {@link #sameStructure}), so the iterators that AbstractList builds on top of get(), set(),
     * add(int, E) and remove(int) are fail-fast on changes made through the owner as well. A
     * single-element store must be the identical element (stricter than for {@link View}'s
     * iterators, as AbstractList's iterators do not track the element they returned).
     */
    private static final class SubList extends AbstractList<EDIFPortInst> implements RandomAccess {
        private final View root;
        private final int offset;
        private int size;
        private Object expectedStore;
        private int expectedSize;

        SubList(View root, int offset, int size) {
            this.root = root;
            this.offset = offset;
            this.size = size;
            resync();
        }

        private void resync() {
            expectedStore = root.store();
            expectedSize = EDIFPortInstArray.size(expectedStore);
        }

        private void checkForComodification() {
            Object s = root.store();
            if (!sameStructure(expectedStore, expectedSize, s)
                    || (expectedStore instanceof EDIFPortInst && s != expectedStore)) {
                throw new ConcurrentModificationException();
            }
        }

        private void checkIndex(int index, int limit) {
            if (index < 0 || index >= limit) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
        }

        @Override
        public int size() {
            checkForComodification();
            return size;
        }

        @Override
        public EDIFPortInst get(int index) {
            checkIndex(index, size);
            checkForComodification();
            return root.get(offset + index);
        }

        @Override
        public EDIFPortInst set(int index, EDIFPortInst e) {
            checkIndex(index, size);
            checkForComodification();
            EDIFPortInst old = root.set(offset + index, e);
            resync();
            return old;
        }

        @Override
        public void add(int index, EDIFPortInst e) {
            checkIndex(index, size + 1);
            checkForComodification();
            root.add(offset + index, e);
            resync();
            size++;
            modCount++;
        }

        @Override
        public EDIFPortInst remove(int index) {
            checkIndex(index, size);
            checkForComodification();
            EDIFPortInst old = root.remove(offset + index);
            resync();
            size--;
            modCount++;
            return old;
        }

        @Override
        protected void removeRange(int fromIndex, int toIndex) {
            for (int i = toIndex - 1; i >= fromIndex; i--) {
                remove(i);
            }
        }
    }
}
