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
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Predicate;

/**
 * Compact storage for the port instances of an {@link EDIFNet} or {@link EDIFCellInst}, replacing a
 * per-owner {@link EDIFPortInstList} (an ArrayList wrapper around an Object[]). The owner keeps a
 * single {@code EDIFPortInst[]} field ("store") that is either null (no port instances) or a
 * non-empty array sorted the same way as {@link EDIFPortInstList} (see
 * {@link EDIFPortInstList#compare}). The array may carry unused capacity: unused slots are null and
 * always at the end, so the size is the index of the first null (O(1) when the array is full, which
 * is the case after {@link #trim(EDIFPortInst[])}).
 * <p>
 * All methods are static and return the (possibly new) store, which the owner must write back.
 * Not thread-safe (same as {@link EDIFPortInstList}).
 * <p>
 * Structural changes are detected without a modification counter in the owner: an array is only
 * changed in place by inserting into its unused capacity or by removing a single element, which
 * move its first null forward or back. Shrinking, bulk removals and reordering sorts return a new
 * array (or null). So if the store is the same array and still has the same size, it has not been
 * structurally modified (see {@link #sameStructure}), unless a removal and an insertion happened in
 * between: like that of any {@link List}, fail-fast behavior is only best-effort.
 */
final class EDIFPortInstArray {

    private EDIFPortInstArray() {
    }

    static int size(EDIFPortInst[] a) {
        return a == null ? 0 : arraySize(a);
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
    static EDIFPortInst get(EDIFPortInst[] a, int i) {
        return a == null || i < 0 || i >= a.length ? null : a[i];
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

    static EDIFPortInst get(EDIFPortInst[] a, EDIFCellInst inst, String portInstName) {
        if (a == null) return null;
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
     * @throws NullPointerException if e is null (null marks unused capacity).
     */
    static EDIFPortInst[] add(EDIFPortInst[] a, EDIFPortInst e) {
        Objects.requireNonNull(e);
        if (a == null) return new EDIFPortInst[] {e};
        int size = arraySize(a);
        int idx = binarySearch(a, size, e.getCellInst(), e.getName());
        if (idx >= 0) {
            a[idx] = e;
            return a;
        }
        return insertAt(a, size, ~idx, e);
    }

    /**
     * Inserts e at index idx (0 <= idx <= size), shifting later entries up; does not check the
     * sort order. Uses the unused capacity of the array in place, if any.
     * @throws NullPointerException if e is null (null marks unused capacity).
     */
    static EDIFPortInst[] insertAt(EDIFPortInst[] a, int idx, EDIFPortInst e) {
        Objects.requireNonNull(e);
        if (a == null) return new EDIFPortInst[] {e};
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
        EDIFCellInst inst = e.getCellInst();
        return EDIFPortInstList.compare(a, inst == null ? null : inst.getName(), e.getName());
    }

    /**
     * Appends without sorting or duplicate replacement, as in
     * {@link EDIFPortInstList#deferSortAdd(EDIFPortInst)}. Conclude a batch with {@link #sort}.
     * @throws NullPointerException if e is null (null marks unused capacity).
     */
    static EDIFPortInst[] deferSortAdd(EDIFPortInst[] a, EDIFPortInst e) {
        Objects.requireNonNull(e);
        if (a == null) return new EDIFPortInst[] {e};
        int size = arraySize(a);
        if (size == a.length) {
            a = Arrays.copyOf(a, grow(a.length));
        }
        a[size] = e;
        return a;
    }

    /**
     * Removes the element at index i (0 <= i < size) and returns the new store: null if it was the
     * last element, else the same array with the later elements shifted down in place (as
     * ArrayList.remove() does), or a new exact-size array once at most half of the array would be
     * used, so that removals neither allocate per call nor leave much unused capacity behind.
     */
    static EDIFPortInst[] removeAt(EDIFPortInst[] a, int i) {
        int n = arraySize(a) - 1;
        if (n == 0) return null;
        if (n <= a.length >> 1) {
            EDIFPortInst[] b = new EDIFPortInst[n];
            System.arraycopy(a, 0, b, 0, i);
            System.arraycopy(a, i + 1, b, i, n - i);
            return b;
        }
        System.arraycopy(a, i + 1, a, i, n - i);
        a[n] = null;
        return a;
    }

    /**
     * Removes the elements at indices [from, to) (0 <= from <= to <= size) in one pass and returns
     * the new store (a new exact-size array or null, unless nothing is removed).
     */
    static EDIFPortInst[] removeRange(EDIFPortInst[] a, int from, int to) {
        if (from == to) return a;
        int size = size(a);
        int n = size - (to - from);
        if (n == 0) return null;
        EDIFPortInst[] b = new EDIFPortInst[n];
        System.arraycopy(a, 0, b, 0, from);
        System.arraycopy(a, to, b, from, size - to);
        return b;
    }

    /**
     * Removes the elements at indices [from, to) that match the filter in one pass. Returns the
     * same store if none match, else a new exact-size array or null. The filter is applied to all
     * elements first, so the store is unchanged if it throws.
     */
    static EDIFPortInst[] removeIf(EDIFPortInst[] a, int from, int to, Predicate<? super EDIFPortInst> filter) {
        Objects.requireNonNull(filter);
        if (a == null || from == to) return a;
        int size = arraySize(a);
        boolean[] remove = null;
        int removed = 0;
        for (int i = from; i < to; i++) {
            if (filter.test(a[i])) {
                if (remove == null) remove = new boolean[to - from];
                remove[i - from] = true;
                removed++;
            }
        }
        if (removed == 0) return a;
        int n = size - removed;
        if (n == 0) return null;
        EDIFPortInst[] b = new EDIFPortInst[n];
        System.arraycopy(a, 0, b, 0, from);
        int j = from;
        for (int i = from; i < to; i++) {
            if (!remove[i - from]) b[j++] = a[i];
        }
        System.arraycopy(a, to, b, j, size - to);
        return b;
    }

    /** Index of the matching entry, or a negative value if none. */
    static int indexOf(EDIFPortInst[] a, EDIFCellInst inst, String portInstName) {
        if (a == null) return -1;
        return binarySearch(a, arraySize(a), inst, portInstName);
    }

    /**
     * Sorts the entries (after deferred adds) and trims unused capacity. An array that is not
     * already sorted is sorted in a new array, so that live iterators can detect the reordering
     * (see the class comment).
     */
    static EDIFPortInst[] sort(EDIFPortInst[] a) {
        if (a == null) return null;
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
     * happened in between, as far as can be detected (see the class comment).
     */
    static boolean sameStructure(EDIFPortInst[] expected, int expectedSize, EDIFPortInst[] current) {
        if (current != expected) return false;
        if (expected == null) return true;
        return expected[expectedSize - 1] != null
                && (expectedSize == expected.length || expected[expectedSize] == null);
    }

    /** Drops unused capacity: exact-length array or null. */
    static EDIFPortInst[] trim(EDIFPortInst[] a) {
        if (a == null) return null;
        int size = arraySize(a);
        if (size == a.length) return a;
        if (size == 0) return null;
        return Arrays.copyOf(a, size);
    }

    /** Snapshot copy as a (trimmed) {@link EDIFPortInstList}. */
    static EDIFPortInstList toList(EDIFPortInst[] a) {
        if (a == null) return null;
        return new EDIFPortInstList(new View() {
            @Override
            EDIFPortInst[] store() {
                return a;
            }

            @Override
            void store(EDIFPortInst[] s) {
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
     * (best-effort, see the class comment) on structural changes made outside of the iterator.
     * Null elements are not permitted: add() and set() throw NullPointerException without changing
     * the list.
     */
    abstract static class View extends AbstractList<EDIFPortInst> implements RandomAccess {

        abstract EDIFPortInst[] store();

        abstract void store(EDIFPortInst[] s);

        @Override
        public int size() {
            return EDIFPortInstArray.size(store());
        }

        @Override
        public boolean isEmpty() {
            return store() == null;
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
            // null marks unused capacity, so storing it would truncate the list
            Objects.requireNonNull(element);
            EDIFPortInst old = get(index);
            store()[index] = element;
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
            EDIFPortInst[] s = store();
            int size = EDIFPortInstArray.size(s);
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if ((index > 0 && compare(s[index - 1], e) >= 0)
                    || (index < size && compare(s[index], e) <= 0)) {
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
            EDIFPortInst[] s = store();
            int idx = pi.getName() == null ? -1 : EDIFPortInstArray.indexOf(s, pi.getCellInst(), pi.getName());
            if (idx >= 0 && pi.equals(s[idx])) {
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
        protected void removeRange(int fromIndex, int toIndex) {
            store(EDIFPortInstArray.removeRange(store(), fromIndex, toIndex));
        }

        // Bulk removals in one pass (removing through the iterator one by one would shift the
        // array for every removed element)
        @Override
        public boolean removeIf(Predicate<? super EDIFPortInst> filter) {
            EDIFPortInst[] s = store();
            EDIFPortInst[] r = EDIFPortInstArray.removeIf(s, 0, EDIFPortInstArray.size(s), filter);
            if (r == s) return false;
            store(r);
            return true;
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeIf(c::contains);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeIf(e -> !c.contains(e));
        }

        @Override
        public Iterator<EDIFPortInst> iterator() {
            return new Itr();
        }

        /**
         * Forward iterator with the same fail-fast rules as {@link ListItr}, kept small so that the
         * JIT inlines it (and scalar-replaces the view and the iterator) in for-each loops: the
         * common case is one identity check plus the first-null check.
         */
        private final class Itr implements Iterator<EDIFPortInst> {
            private EDIFPortInst[] expected = store();
            private int expectedSize = EDIFPortInstArray.size(expected);
            private int cursor;
            private int lastRet = -1;

            @Override
            public boolean hasNext() {
                return cursor < expectedSize;
            }

            @Override
            public EDIFPortInst next() {
                EDIFPortInst[] a = expected;
                int n = expectedSize;
                // Inserting or removing in place moves the first null of the array (see class comment)
                if (store() != a || (a != null && (a[n - 1] == null || (n < a.length && a[n] != null)))) {
                    throw new ConcurrentModificationException();
                }
                int i = cursor;
                if (i >= n) throw new NoSuchElementException();
                cursor = i + 1;
                lastRet = i;
                return a[i];
            }

            @Override
            public void remove() {
                if (lastRet < 0) throw new IllegalStateException();
                EDIFPortInst[] s = store();
                if (!sameStructure(expected, expectedSize, s)) {
                    throw new ConcurrentModificationException();
                }
                store(removeAt(s, lastRet));
                expected = store();
                expectedSize--;
                cursor = lastRet;
                lastRet = -1;
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
         * List iterator (also see {@link Itr}) that is fail-fast on structural changes made other
         * than through itself, including changes made through the owner (see {@link #sameStructure}).
         */
        private final class ListItr implements ListIterator<EDIFPortInst> {
            private EDIFPortInst[] expected;
            private int expectedSize;
            private int cursor;
            private int lastRet = -1;

            ListItr(int index) {
                resync();
                cursor = index;
            }

            private void resync() {
                expected = store();
                expectedSize = EDIFPortInstArray.size(expected);
            }

            private EDIFPortInst[] checkForComodification() {
                EDIFPortInst[] s = store();
                if (!sameStructure(expected, expectedSize, s)) {
                    throw new ConcurrentModificationException();
                }
                return s;
            }

            private EDIFPortInst[] checkLastRet() {
                if (lastRet < 0) throw new IllegalStateException();
                return checkForComodification();
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
                EDIFPortInst[] s = checkForComodification();
                int i = cursor;
                if (i >= expectedSize) throw new NoSuchElementException();
                cursor = i + 1;
                lastRet = i;
                return s[i];
            }

            @Override
            public EDIFPortInst previous() {
                EDIFPortInst[] s = checkForComodification();
                int i = cursor - 1;
                if (i < 0) throw new NoSuchElementException();
                cursor = i;
                lastRet = i;
                return s[i];
            }

            @Override
            public void remove() {
                EDIFPortInst[] s = checkLastRet();
                store(removeAt(s, lastRet));
                resync();
                cursor = lastRet;
                lastRet = -1;
            }

            @Override
            public void set(EDIFPortInst e) {
                checkLastRet();
                View.this.set(lastRet, e);
            }

            @Override
            public void add(EDIFPortInst e) {
                checkForComodification();
                View.this.add(cursor, e);
                resync();
                cursor++;
                lastRet = -1;
            }
        }
    }

    /**
     * Sub-list of a {@link View}. Checks the root's store before every access (see
     * {@link #sameStructure}), so the iterators that AbstractList builds on top of get(), set(),
     * add(int, E) and remove(int) are fail-fast on changes made through the owner as well.
     */
    private static final class SubList extends AbstractList<EDIFPortInst> implements RandomAccess {
        private final View root;
        private final int offset;
        private int size;
        private EDIFPortInst[] expected;
        private int expectedSize;

        SubList(View root, int offset, int size) {
            this.root = root;
            this.offset = offset;
            this.size = size;
            resync();
        }

        private void resync() {
            expected = root.store();
            expectedSize = EDIFPortInstArray.size(expected);
        }

        private void checkForComodification() {
            if (!sameStructure(expected, expectedSize, root.store())) {
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
            return root.set(offset + index, e);
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
            checkForComodification();
            if (fromIndex == toIndex) return;
            root.store(EDIFPortInstArray.removeRange(root.store(), offset + fromIndex, offset + toIndex));
            resync();
            size -= toIndex - fromIndex;
            modCount++;
        }

        @Override
        public boolean removeIf(Predicate<? super EDIFPortInst> filter) {
            checkForComodification();
            EDIFPortInst[] s = root.store();
            EDIFPortInst[] r = EDIFPortInstArray.removeIf(s, offset, offset + size, filter);
            if (r == s) return false;
            root.store(r);
            size -= EDIFPortInstArray.size(s) - EDIFPortInstArray.size(r);
            resync();
            modCount++;
            return true;
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeIf(c::contains);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeIf(e -> !c.contains(e));
        }
    }
}
