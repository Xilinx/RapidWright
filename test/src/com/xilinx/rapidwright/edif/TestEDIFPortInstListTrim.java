/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

/**
 * Validates optimization "C": trimming the backing capacity of every
 * {@link EDIFPortInstList} after a netlist is fully loaded.
 *
 * <p>The capacity of a {@link java.util.ArrayList} is not exposed through any
 * public API, so the capacity-specific assertions read it via reflection. Under
 * the Java Platform Module System this requires {@code java.base/java.util} to
 * be open; when it is not (the default), those assertions are skipped via JUnit
 * {@link Assumptions} while the behavioral assertions (which need no reflection)
 * always run.</p>
 */
public class TestEDIFPortInstListTrim {

    private static final Field ELEMENT_DATA;
    private static final boolean CAN_READ_CAPACITY;
    static {
        Field f = null;
        boolean ok = false;
        try {
            f = ArrayList.class.getDeclaredField("elementData");
            ok = f.trySetAccessible();
        } catch (Throwable t) {
            ok = false;
        }
        ELEMENT_DATA = f;
        CAN_READ_CAPACITY = ok;
    }

    private static int capacityOf(ArrayList<?> list) {
        try {
            return ((Object[]) ELEMENT_DATA.get(list)).length;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testTrimPreservesContentsAndLookups() {
        // Build a list with deliberate capacity slack (ArrayList grows to >size)
        EDIFPortInstList list = new EDIFPortInstList();
        List<EDIFPortInst> expected = new ArrayList<>();
        TestEDIFPortInstList helper = new TestEDIFPortInstList();
        for (int i = 0; i < 5; i++) {
            EDIFPortInst pi = helper.makeEDIFPortInst("inst" + i + "/I");
            list.add(pi);
            expected.add(pi);
        }
        List<EDIFPortInst> before = new ArrayList<>(list);

        list.trimToSize();

        // Contents and order unchanged
        Assertions.assertEquals(before, new ArrayList<>(list));
        // Sorted lookup still works for every element
        for (EDIFPortInst pi : expected) {
            Assertions.assertSame(pi, list.get(pi.getCellInst(), pi.getName()));
        }

        // Capacity assertion (only where ArrayList internals are accessible)
        if (CAN_READ_CAPACITY) {
            Assertions.assertEquals(list.size(), capacityOf(list),
                    "After trimToSize, capacity should equal size");
        }
    }

    @Test
    public void testCapacityHadSlackBeforeTrim() {
        Assumptions.assumeTrue(CAN_READ_CAPACITY,
                "Skipped: java.base/java.util not open for reflection");
        EDIFPortInstList list = new EDIFPortInstList();
        TestEDIFPortInstList helper = new TestEDIFPortInstList();
        for (int i = 0; i < 5; i++) {
            list.add(helper.makeEDIFPortInst("inst" + i + "/I"));
        }
        Assertions.assertTrue(capacityOf(list) > list.size(),
                "Expected ArrayList to carry capacity slack before trimming");
        list.trimToSize();
        Assertions.assertEquals(list.size(), capacityOf(list));
    }

    @Test
    public void testNetlistTrimIsIdempotentAndPreservesConnectivity() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("trimTest");
        EDIFCell top = netlist.getTopCell();
        EDIFCell leaf = new EDIFCell(netlist.getWorkLibrary(), "leaf");
        EDIFPort in = leaf.createPort("I", EDIFDirection.INPUT, 1);
        EDIFPort out = leaf.createPort("O", EDIFDirection.OUTPUT, 1);

        List<EDIFCellInst> insts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            insts.add(leaf.createCellInst("u" + i, top));
        }
        EDIFNet net = top.createNet("n");
        for (EDIFCellInst inst : insts) {
            net.createPortInst(in, inst);
        }
        int netSize = net.getPortInsts().size();
        Assertions.assertEquals(insts.size(), netSize);

        netlist.trimEDIFPortInstLists();

        // Connectivity preserved
        Assertions.assertEquals(netSize, net.getPortInsts().size());
        for (EDIFCellInst inst : insts) {
            Assertions.assertNotNull(net.getPortInst(inst, "I"));
            Assertions.assertNotNull(inst.getPortInst("I"));
        }
        if (CAN_READ_CAPACITY) {
            EDIFPortInstList netList = net.getEDIFPortInstList();
            Assertions.assertEquals(netList.size(), capacityOf(netList));
        }

        // Idempotent: a second trim changes nothing observable
        netlist.trimEDIFPortInstLists();
        Assertions.assertEquals(netSize, net.getPortInsts().size());

        // After trim we can still add and look up new port insts
        net.createPortInst(out, insts.get(0));
        Assertions.assertNotNull(net.getPortInst(insts.get(0), "O"));
    }

    @Test
    public void testParserProducesTrimmedLists(@TempDir Path dir) {
        Assumptions.assumeTrue(CAN_READ_CAPACITY,
                "Skipped: java.base/java.util not open for reflection");
        // Validate the pure EDIF parse path: export a netlist and read it back.
        // (Design.loadDCP additionally expands macros AFTER parsing, which creates
        // new untrimmed lists; optimization C targets the parse itself, which is
        // also what EDIFTools.readEdifFile exercises.)
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Path edf = dir.resolve("parsed.edf");
        design.getNetlist().exportEDIF(edf.toString());
        EDIFNetlist netlist = EDIFTools.readEdifFile(edf);

        int listsChecked = 0;
        int nonTrivial = 0;
        for (EDIFLibrary lib : netlist.getLibraries()) {
            for (EDIFCell cell : lib.getCells()) {
                for (EDIFNet net : cell.getNets()) {
                    EDIFPortInstList l = net.getEDIFPortInstList();
                    if (l == null) continue;
                    Assertions.assertEquals(l.size(), capacityOf(l),
                            "Net port inst list not trimmed: " + cell.getName() + "/" + net.getName());
                    listsChecked++;
                    if (l.size() > 1) nonTrivial++;
                }
                for (EDIFCellInst inst : cell.getCellInsts()) {
                    EDIFPortInstList l = inst.getEDIFPortInstList();
                    if (l == null) continue;
                    Assertions.assertEquals(l.size(), capacityOf(l),
                            "Inst port inst list not trimmed: " + cell.getName() + "/" + inst.getName());
                    listsChecked++;
                    if (l.size() > 1) nonTrivial++;
                }
            }
        }
        Assertions.assertTrue(listsChecked > 100, "Expected to check many lists, only saw " + listsChecked);
        Assertions.assertTrue(nonTrivial > 0, "Expected some multi-element lists");
    }

    @Test
    public void testRoundTripUnaffectedByTrim(@TempDir Path dir) {
        // Trimming must not alter the exported EDIF
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Path out1 = dir.resolve("trim1.edf");
        Path out2 = dir.resolve("trim2.edf");
        netlist.exportEDIF(out1.toString());
        netlist.trimEDIFPortInstLists();
        netlist.exportEDIF(out2.toString());
        Assertions.assertEquals(readAll(out1), readAll(out2));
    }

    private static String readAll(Path p) {
        try {
            return new String(java.nio.file.Files.readAllBytes(p));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
