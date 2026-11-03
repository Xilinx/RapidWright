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
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
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
}
