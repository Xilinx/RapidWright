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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the bare-array port instance storage of {@link EDIFNet} and {@link EDIFCellInst}
 * ({@link EDIFPortInstArray}) against the semantics of {@link EDIFPortInstList}.
 */
public class TestEDIFPortInstArray {

    private static class Fixture {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("pia");
        EDIFCell top = netlist.getTopCell();
        EDIFCell leaf = new EDIFCell(netlist.getWorkLibrary(), "leaf");
        EDIFPort in = leaf.createPort("I", EDIFDirection.INPUT, 1);
        EDIFPort bus = leaf.createPort("D[7:0]", EDIFDirection.INPUT, 8);
        EDIFPort out = leaf.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFPort topIn = top.createPort("tin", EDIFDirection.INPUT, 1);
    }

    @Test
    public void testRandomOpsMatchEDIFPortInstList() {
        Fixture f = new Fixture();
        List<EDIFCellInst> insts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            insts.add(f.leaf.createCellInst("u" + i, f.top));
        }
        EDIFNet net = f.top.createNet("n");
        EDIFPortInstList ref = new EDIFPortInstList();
        Random r = new Random(42);
        for (int step = 0; step < 4000; step++) {
            EDIFCellInst inst = insts.get(r.nextInt(insts.size()));
            int op = r.nextInt(3);
            if (op < 2) {
                EDIFPortInst pi = r.nextBoolean() ? net.createPortInst(f.in, inst)
                        : net.createPortInst(f.bus, r.nextInt(8), inst);
                ref.add(pi);
            } else if (net.getPortInstCount() > 0) {
                EDIFPortInst victim = net.getPortInsts().get(r.nextInt(net.getPortInstCount()));
                Assertions.assertSame(victim, net.removePortInst(victim));
                Assertions.assertSame(victim, ref.remove(victim));
            }
            if (step % 97 == 0) {
                f.netlist.trimEDIFPortInstLists();
            }
            Assertions.assertEquals(ref, net.getPortInsts());
            Assertions.assertEquals(ref.size(), net.getPortInstCount());
        }
        for (EDIFPortInst pi : ref) {
            Assertions.assertSame(pi, net.getPortInst(pi.getCellInst(), pi.getName()));
        }
        Assertions.assertNull(net.getPortInst(insts.get(0), "nope"));
    }

    @Test
    public void testSingleAndEmptyTransitions() {
        Fixture f = new Fixture();
        EDIFCellInst u0 = f.leaf.createCellInst("u0", f.top);
        EDIFNet net = f.top.createNet("n");
        Assertions.assertTrue(net.getPortInsts().isEmpty());
        Assertions.assertNull(net.getEDIFPortInstList());
        EDIFPortInst a = net.createPortInst(f.in, u0);
        Assertions.assertEquals(Collections.singletonList(a), net.getPortInsts());
        Assertions.assertSame(a, net.getPortInst(u0, "I"));
        Assertions.assertSame(a, u0.getPortInst("I"));
        EDIFPortInst t = net.createPortInst(f.topIn);
        Assertions.assertEquals(2, net.getPortInsts().size());
        Assertions.assertSame(t, net.getPortInst(null, "tin"));
        Assertions.assertSame(t, net.getTopLevelPortInst());
        net.removePortInst(a);
        Assertions.assertEquals(Collections.singletonList(t), net.getPortInsts());
        net.removePortInst(t);
        Assertions.assertTrue(net.getPortInsts().isEmpty());
        Assertions.assertNull(net.removePortInst(t));
    }

    @Test
    public void testDuplicateOverwrite() {
        Fixture f = new Fixture();
        EDIFCellInst u0 = f.leaf.createCellInst("u0", f.top);
        EDIFNet net = f.top.createNet("n");
        EDIFPortInst a = net.createPortInst(f.in, u0);
        EDIFPortInst b = net.createPortInst(f.in, u0);
        Assertions.assertEquals(1, net.getPortInsts().size());
        Assertions.assertSame(b, net.getPortInst(u0, "I"));
        Assertions.assertSame(b, u0.getPortInst("I"));
        Assertions.assertNotSame(a, b);
    }

    @Test
    public void testDeferredSortAndTrim() {
        Fixture f = new Fixture();
        EDIFNet net = f.top.createNet("n");
        List<EDIFCellInst> insts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            insts.add(f.leaf.createCellInst("u" + (49 - i), f.top));
        }
        EDIFPortInstList ref = new EDIFPortInstList();
        for (EDIFCellInst inst : insts) {
            EDIFPortInst pi = net.createPortInst(f.in, inst, true);
            ref.add(pi);
            for (int b = 7; b >= 0; b--) {
                pi = net.createPortInst(f.bus, b, inst, true);
                ref.add(pi);
            }
        }
        f.top.sortEDIFPortInstLists();
        Assertions.assertEquals(ref, net.getPortInsts());
        EDIFPortInstList snap = net.getEDIFPortInstList();
        Assertions.assertEquals(ref, snap);
        for (EDIFCellInst inst : insts) {
            Assertions.assertEquals(9, inst.getPortInsts().size());
            Assertions.assertNotNull(inst.getPortInst("D[3]"));
            Assertions.assertNotNull(inst.getPortInst("I"));
        }
        // The snapshot is not live
        snap.clear();
        Assertions.assertEquals(ref.size(), net.getPortInsts().size());
    }

    @Test
    public void testIteratorFailFastAndRemove() {
        Fixture f = new Fixture();
        EDIFNet net = f.top.createNet("n");
        for (int i = 0; i < 6; i++) {
            net.createPortInst(f.in, f.leaf.createCellInst("u" + i, f.top));
        }
        Assertions.assertThrows(ConcurrentModificationException.class, () -> {
            for (EDIFPortInst pi : net.getPortInsts()) {
                net.removePortInst(pi);
            }
        });
        Assertions.assertThrows(ConcurrentModificationException.class, () -> {
            for (EDIFPortInst pi : net.getPortInsts()) {
                net.createPortInst(f.out, pi.getCellInst());
            }
        });
        int n = net.getPortInsts().size();
        Iterator<EDIFPortInst> it = net.getPortInsts().iterator();
        int removed = 0;
        while (it.hasNext()) {
            EDIFPortInst pi = it.next();
            if (pi.getName().equals("O")) {
                it.remove();
                removed++;
            }
        }
        Assertions.assertEquals(n - removed, net.getPortInsts().size());
        // Removing everything through a copy is fine
        for (EDIFPortInst pi : new ArrayList<>(net.getPortInsts())) {
            net.removePortInst(pi);
        }
        Assertions.assertEquals(0, net.getPortInsts().size());
    }

    @Test
    public void testLiveViewMutation() {
        Fixture f = new Fixture();
        EDIFCellInst u0 = f.leaf.createCellInst("u0", f.top);
        EDIFCellInst u1 = f.leaf.createCellInst("u1", f.top);
        EDIFNet net = f.top.createNet("n");
        EDIFPortInst a = net.createPortInst(f.in, u1);
        List<EDIFPortInst> view = net.getPortInsts();
        EDIFPortInst b = net.createPortInst(f.in, u0);
        Assertions.assertEquals(2, view.size());
        Assertions.assertSame(b, view.get(0));
        Assertions.assertTrue(view.contains(a));
        Assertions.assertTrue(view.remove(b));
        Assertions.assertEquals(Collections.singletonList(a), net.getPortInsts());
        view.add(b);
        Assertions.assertSame(b, net.getPortInst(u0, "I"));
        view.clear();
        Assertions.assertEquals(0, net.getPortInsts().size());
    }

    /** Net "n" with port "I" on cell insts u0..u{n-1}, sorted u0 < u1 < ... */
    private static EDIFNet netWithPins(Fixture f, int n) {
        EDIFNet net = f.top.createNet("n");
        for (int i = 0; i < n; i++) {
            net.createPortInst(f.in, f.leaf.createCellInst("u" + i, f.top));
        }
        return net;
    }

    @Test
    public void testPositionalAddIsCheckedAndExact() {
        Fixture f = new Fixture();
        EDIFNet net = netWithPins(f, 0);
        EDIFCellInst u0 = f.leaf.createCellInst("u0", f.top);
        EDIFCellInst u1 = f.leaf.createCellInst("u1", f.top);
        EDIFCellInst u2 = f.leaf.createCellInst("u2", f.top);
        EDIFPortInst p0 = net.createPortInst(f.in, u0);
        EDIFPortInst p2 = net.createPortInst(f.in, u2);
        List<EDIFPortInst> view = net.getPortInsts();
        EDIFPortInst p1 = new EDIFPortInst(f.in, null, u1);
        // Out of bounds: throws, list unchanged
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> view.add(3, p1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> view.add(-1, p1));
        // Not the sorted position: throws, list unchanged
        Assertions.assertThrows(IllegalArgumentException.class, () -> view.add(0, p1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> view.add(2, p1));
        // An equal entry is present: throws instead of overwriting (size must grow by one)
        EDIFPortInst p2b = new EDIFPortInst(f.in, null, u2);
        Assertions.assertThrows(IllegalArgumentException.class, () -> view.add(1, p2b));
        Assertions.assertThrows(IllegalArgumentException.class, () -> view.add(2, p2b));
        Assertions.assertEquals(2, view.size());
        view.add(1, p1);
        Assertions.assertEquals(Arrays.asList(p0, p1, p2), view);
        Assertions.assertSame(p1, net.getPortInst(u1, "I"));

        // ListIterator.add() inserts at the cursor and keeps the cursor consistent
        view.remove(p1);
        ListIterator<EDIFPortInst> it = view.listIterator();
        Assertions.assertSame(p0, it.next());
        it.add(p1);
        Assertions.assertEquals(2, it.nextIndex());
        Assertions.assertSame(p2, it.next());
        Assertions.assertFalse(it.hasNext());
        Assertions.assertEquals(Arrays.asList(p0, p1, p2), view);

        // A sub-list insertion stays inside the sub-list
        view.remove(p1);
        List<EDIFPortInst> sub = view.subList(1, 2);
        Assertions.assertThrows(IllegalArgumentException.class, () -> sub.add(1, p1));
        sub.add(0, p1);
        Assertions.assertEquals(Arrays.asList(p1, p2), sub);
        Assertions.assertEquals(Arrays.asList(p0, p1, p2), view);
    }

    @Test
    public void testStaleIteratorsFailFastOnOwnerChanges() {
        // Copilot scenario: a list iterator reads a from [a, b]; a is removed through the owner;
        // the iterator's remove() must throw instead of removing b.
        Fixture f = new Fixture();
        EDIFNet net = netWithPins(f, 2);
        EDIFPortInst a = net.getPortInsts().get(0);
        EDIFPortInst b = net.getPortInsts().get(1);
        ListIterator<EDIFPortInst> lit = net.getPortInsts().listIterator();
        Assertions.assertSame(a, lit.next());
        net.removePortInst(a);
        Assertions.assertThrows(ConcurrentModificationException.class, lit::remove);
        Assertions.assertEquals(Collections.singletonList(b), net.getPortInsts());

        // Same through a sub-list iterator
        net = netWithPins(f = new Fixture(), 3);
        List<EDIFPortInst> all = new ArrayList<>(net.getPortInsts());
        Iterator<EDIFPortInst> sit = net.getPortInsts().subList(0, 2).iterator();
        Assertions.assertSame(all.get(0), sit.next());
        net.removePortInst(all.get(0));
        Assertions.assertThrows(ConcurrentModificationException.class, sit::remove);
        Assertions.assertEquals(all.subList(1, 3), net.getPortInsts());

        // Remove and re-add through the owner (same size afterwards) is detected
        net = netWithPins(f = new Fixture(), 4);
        all = new ArrayList<>(net.getPortInsts());
        Iterator<EDIFPortInst> it = net.getPortInsts().iterator();
        it.next();
        net.removePortInst(all.get(2));
        net.addPortInst(all.get(2));
        Assertions.assertEquals(all, net.getPortInsts());
        Assertions.assertThrows(ConcurrentModificationException.class, it::next);

        // Single element removed and a different one added: stale remove() must not remove it
        net = netWithPins(f = new Fixture(), 1);
        EDIFPortInst only = net.getPortInsts().get(0);
        it = net.getPortInsts().iterator();
        Assertions.assertSame(only, it.next());
        net.removePortInst(only);
        EDIFPortInst other = net.createPortInst(f.out, only.getCellInst());
        Assertions.assertThrows(ConcurrentModificationException.class, it::remove);
        Assertions.assertEquals(Collections.singletonList(other), net.getPortInsts());

        // Deferred adds followed by a reordering sort are detected
        Fixture g = new Fixture();
        net = g.top.createNet("d");
        for (int i = 3; i >= 0; i--) {
            net.createPortInst(g.in, g.leaf.createCellInst("u" + i, g.top), true);
        }
        it = net.getPortInsts().iterator();
        it.next();
        g.top.sortEDIFPortInstLists();
        Assertions.assertThrows(ConcurrentModificationException.class, it::next);
    }

    @Test
    public void testSingleElementReplacementKeepsIterators() {
        // Copilot scenario: replacing the only port inst is not a structural change
        Fixture f = new Fixture();
        EDIFNet net = netWithPins(f, 1);
        EDIFPortInst a = net.getPortInsts().get(0);
        List<EDIFPortInst> view = net.getPortInsts();
        Iterator<EDIFPortInst> it = view.iterator();
        EDIFPortInst r = new EDIFPortInst(f.in, null, a.getCellInst());
        Assertions.assertSame(a, view.set(0, r));
        Assertions.assertTrue(it.hasNext());
        Assertions.assertSame(r, it.next());
        Assertions.assertFalse(it.hasNext());

        // Overwriting the connection through the owner
        it = net.getPortInsts().iterator();
        EDIFPortInst c = net.createPortInst(f.in, a.getCellInst());
        Assertions.assertSame(c, it.next());
        it.remove();
        Assertions.assertTrue(net.getPortInsts().isEmpty());

        // ListIterator.set() on a single element, then remove()
        net = netWithPins(f = new Fixture(), 1);
        EDIFPortInst x = net.getPortInsts().get(0);
        ListIterator<EDIFPortInst> lit = net.getPortInsts().listIterator();
        lit.next();
        EDIFPortInst y = new EDIFPortInst(f.in, null, x.getCellInst());
        lit.set(y);
        Assertions.assertEquals(Collections.singletonList(y), net.getPortInsts());
        lit.remove();
        Assertions.assertTrue(net.getPortInsts().isEmpty());
    }

    @Test
    public void testBulkRemovalsMatchArrayList() {
        Random r = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            int n = r.nextInt(8);
            Fixture f = new Fixture();
            EDIFNet net = netWithPins(f, n);
            List<EDIFPortInst> view = net.getPortInsts();
            List<EDIFPortInst> ref = new ArrayList<>(view);
            int from = n == 0 ? 0 : r.nextInt(n + 1);
            int to = from + (n == from ? 0 : r.nextInt(n - from + 1));
            Iterator<EDIFPortInst> stale = view.iterator();
            int op = trial % 6;
            boolean changed;
            if (op == 0) {
                view.subList(from, to).clear();
                ref.subList(from, to).clear();
                changed = from < to;
            } else if (op == 1) {
                // Remove cell insts with an odd index
                changed = view.removeIf(p -> p.getCellInst().getName().hashCode() % 2 != 0);
                Assertions.assertEquals(ref.removeIf(p -> p.getCellInst().getName().hashCode() % 2 != 0), changed);
            } else if (op == 2) {
                List<EDIFPortInst> c = new ArrayList<>(ref.subList(from, to));
                changed = view.removeAll(c);
                Assertions.assertEquals(ref.removeAll(c), changed);
            } else if (op == 3) {
                List<EDIFPortInst> c = new ArrayList<>(ref.subList(from, to));
                changed = view.retainAll(c);
                Assertions.assertEquals(ref.retainAll(c), changed);
            } else if (op == 4) {
                List<EDIFPortInst> sub = view.subList(from, to);
                changed = sub.removeIf(p -> p.getCellInst().getName().hashCode() % 2 == 0);
                List<EDIFPortInst> refSub = ref.subList(from, to);
                Assertions.assertEquals(refSub.removeIf(p -> p.getCellInst().getName().hashCode() % 2 == 0), changed);
                Assertions.assertEquals(refSub, sub);
            } else {
                List<EDIFPortInst> sub = view.subList(from, to);
                List<EDIFPortInst> c = ref.isEmpty() ? ref : Collections.singletonList(ref.get(r.nextInt(n)));
                changed = sub.retainAll(c);
                List<EDIFPortInst> refSub = ref.subList(from, to);
                Assertions.assertEquals(refSub.retainAll(c), changed);
                Assertions.assertEquals(refSub.size(), sub.size());
                Assertions.assertEquals(refSub, sub);
            }
            Assertions.assertEquals(ref, view);
            Assertions.assertEquals(ref.size(), net.getPortInstCount());
            for (EDIFPortInst pi : ref) {
                Assertions.assertSame(pi, net.getPortInst(pi.getCellInst(), pi.getName()));
            }
            if (changed) {
                // A bulk removal is a structural change for iterators created before it
                Assertions.assertThrows(ConcurrentModificationException.class, stale::next);
            }
        }
        // A throwing filter leaves the list unchanged
        EDIFNet net = netWithPins(new Fixture(), 4);
        List<EDIFPortInst> ref = new ArrayList<>(net.getPortInsts());
        EDIFPortInst third = ref.get(2);
        Assertions.assertThrows(IllegalStateException.class, () -> net.getPortInsts().removeIf(p -> {
            if (p == third) throw new IllegalStateException();
            return true;
        }));
        Assertions.assertEquals(ref, net.getPortInsts());
    }

    @Test
    public void testNullElementsRejected() {
        // Copilot scenario: five entries in a six-slot array; set(2, null) must not truncate the list
        Fixture f = new Fixture();
        EDIFNet net = netWithPins(f, 5);
        List<EDIFPortInst> ref = new ArrayList<>(net.getPortInsts());
        List<EDIFPortInst> view = net.getPortInsts();
        Assertions.assertThrows(NullPointerException.class, () -> view.set(2, null));
        Assertions.assertThrows(NullPointerException.class, () -> view.add(null));
        Assertions.assertThrows(NullPointerException.class, () -> view.add(5, null));
        Assertions.assertThrows(NullPointerException.class, () -> view.subList(1, 3).set(0, null));
        ListIterator<EDIFPortInst> lit = view.listIterator();
        lit.next();
        Assertions.assertThrows(NullPointerException.class, () -> lit.set(null));
        Assertions.assertThrows(NullPointerException.class, () -> lit.add(null));
        Assertions.assertEquals(ref, net.getPortInsts());
        f.netlist.trimEDIFPortInstLists();
        Assertions.assertEquals(ref, net.getPortInsts());
        for (EDIFPortInst pi : ref) {
            Assertions.assertSame(pi, net.getPortInst(pi.getCellInst(), pi.getName()));
        }

        // Single-element and empty stores
        EDIFNet one = netWithPins(new Fixture(), 1);
        EDIFPortInst a = one.getPortInsts().get(0);
        Assertions.assertThrows(NullPointerException.class, () -> one.getPortInsts().set(0, null));
        Assertions.assertEquals(Collections.singletonList(a), one.getPortInsts());
        EDIFNet empty = netWithPins(new Fixture(), 0);
        Assertions.assertThrows(NullPointerException.class, () -> empty.getPortInsts().add(null));
        Assertions.assertThrows(NullPointerException.class, () -> empty.getPortInsts().add(0, null));
        Assertions.assertTrue(empty.getPortInsts().isEmpty());
    }

    @Test
    public void testListIteratorBidirectionalAndEquality() {
        Fixture f = new Fixture();
        EDIFNet net = netWithPins(f, 5);
        List<EDIFPortInst> ref = new ArrayList<>(net.getPortInsts());
        ListIterator<EDIFPortInst> it = net.getPortInsts().listIterator(5);
        for (int i = 4; i >= 0; i--) {
            Assertions.assertTrue(it.hasPrevious());
            Assertions.assertEquals(i, it.previousIndex());
            Assertions.assertSame(ref.get(i), it.previous());
        }
        Assertions.assertFalse(it.hasPrevious());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> net.getPortInsts().listIterator(6));
        Assertions.assertEquals(ref, net.getPortInsts());
        Assertions.assertEquals(ref.hashCode(), net.getPortInsts().hashCode());
        Assertions.assertEquals(ref.subList(1, 4), net.getPortInsts().subList(1, 4));
        net.getPortInsts().subList(1, 4).clear();
        Assertions.assertEquals(Arrays.asList(ref.get(0), ref.get(4)), net.getPortInsts());
        for (EDIFPortInst pi : ref.subList(1, 4)) {
            Assertions.assertNull(net.getPortInst(pi.getCellInst(), pi.getName()));
        }
    }
}
