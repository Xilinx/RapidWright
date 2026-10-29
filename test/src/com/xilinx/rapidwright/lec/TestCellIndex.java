/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.lec;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Guards the port bit numbering, which is the easiest place in the tool to
 * introduce a silent, catastrophic error.
 *
 * {@link EDIFPortInst#getIndex()} reports a dense position within the bit
 * vector, not the number written between the brackets in the pin name. For a
 * port declared {@code S[7:0]} the pin {@code S[3]} sits at position 4. Getting
 * that backwards reverses every bus in the design, and for a carry chain it
 * reverses the direction carries propagate, which produces a wrong answer
 * rather than an error.
 */
public class TestCellIndex {

    private EDIFNetlist netlist;

    private EDIFCell cell;

    @BeforeEach
    public void setUp() {
        CellIndex.clearCache();
        netlist = new EDIFNetlist("top");
        EDIFLibrary work = netlist.addLibrary(new EDIFLibrary("work"));
        cell = new EDIFCell(work, "indexed");
    }

    /**
     * The descending declaration Vivado emits for almost every bus. Position 0
     * holds the highest numbered bit.
     */
    @Test
    public void testLittleEndianBus() {
        EDIFPort port = cell.createPort("S[7:0]", EDIFDirection.INPUT, 8);
        CellIndex index = CellIndex.get(cell);
        Assertions.assertEquals(8, index.getPortBitCount());
        Assertions.assertTrue(port.isLittleEndian());

        Assertions.assertEquals("S[7]", index.getPortBitName(0));
        Assertions.assertEquals("S[0]", index.getPortBitName(7));
        Assertions.assertEquals(7, index.getNamedIndex(0));
        Assertions.assertEquals(0, index.getNamedIndex(7));
        Assertions.assertEquals(4, index.portBitIndexOfName(port, 3));
        Assertions.assertEquals("S[3]", index.getPortBitName(4));
    }

    /** An ascending declaration, where position and bit number coincide. */
    @Test
    public void testBigEndianBus() {
        EDIFPort port = cell.createPort("B[0:7]", EDIFDirection.INPUT, 8);
        CellIndex index = CellIndex.get(cell);
        Assertions.assertFalse(port.isLittleEndian());
        Assertions.assertEquals("B[0]", index.getPortBitName(0));
        Assertions.assertEquals("B[7]", index.getPortBitName(7));
        Assertions.assertEquals(3, index.portBitIndexOfName(port, 3));
    }

    /**
     * A bus that does not start at zero. Here neither position nor bit number
     * can be assumed to be the other.
     */
    @Test
    public void testNonZeroBasedBus() {
        EDIFPort port = cell.createPort("odd[2:5]", EDIFDirection.INPUT, 4);
        CellIndex index = CellIndex.get(cell);
        Assertions.assertEquals(4, index.getPortBitCount());
        Assertions.assertEquals("odd[2]", index.getPortBitName(0));
        Assertions.assertEquals("odd[5]", index.getPortBitName(3));
        Assertions.assertEquals(0, index.portBitIndexOfName(port, 2));
        Assertions.assertEquals(1, index.portBitIndexOfName(port, 3));
    }

    /** A scalar port has exactly one bit and no brackets in its name. */
    @Test
    public void testScalarPort() {
        EDIFPort port = cell.createPort("CI", EDIFDirection.INPUT, 1);
        CellIndex index = CellIndex.get(cell);
        Assertions.assertEquals(1, index.getPortBitCount());
        Assertions.assertEquals("CI", index.getPortBitName(0));
        Assertions.assertEquals(-1, index.getNamedIndex(0));
        Assertions.assertEquals(0, index.portBitIndexOfName(port, 0));
        Assertions.assertEquals(0, index.portBitIndexOfPinName("CI"));
    }

    /**
     * The critical round trip: the bit a port instance is attached to, resolved
     * from the port instance itself, must match the bit resolved from the name
     * that port instance carries. This is the check that would have caught a
     * reversed bus.
     */
    @Test
    public void testPortInstIndexMatchesName() {
        cell.createPort("S[7:0]", EDIFDirection.INPUT, 8);
        cell.createPort("B[0:7]", EDIFDirection.OUTPUT, 8);
        cell.createPort("odd[2:5]", EDIFDirection.INPUT, 4);
        cell.createPort("CI", EDIFDirection.INPUT, 1);

        EDIFLibrary work = netlist.getLibrary("work");
        EDIFCell parent = new EDIFCell(work, "parent");
        EDIFCellInst inst = parent.createCellInst("u_child", parent);
        inst.setCellType(cell);

        CellIndex index = CellIndex.get(cell);
        Set<Integer> seen = new HashSet<>();
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            String pinName = index.getPortBitName(bit);
            EDIFNet net = parent.createNet("n_" + bit);
            EDIFPortInst portInst = net.createPortInst(pinName, inst);
            Assertions.assertNotNull(portInst, "Could not attach a net to pin " + pinName);
            Assertions.assertEquals(bit, index.portBitIndex(portInst),
                    "Pin " + pinName + " resolved to the wrong port bit");
            Assertions.assertEquals(bit, index.portBitIndexOfPinName(pinName),
                    "Pin name " + pinName + " resolved to the wrong port bit");
            Assertions.assertTrue(seen.add(bit), "Port bit " + bit + " was claimed twice");
        }
        Assertions.assertEquals(21, seen.size());
    }

    /**
     * A port bit connected on the inside of a cell must report that net, and an
     * unconnected one must report nothing. This table is what carries a cone
     * across a hierarchy boundary.
     */
    @Test
    public void testBoundaryNets() {
        cell.createPort("A", EDIFDirection.INPUT, 1);
        cell.createPort("Y", EDIFDirection.OUTPUT, 1);
        EDIFNet inner = cell.createNet("inner");
        inner.createPortInst("A", cell);

        CellIndex index = CellIndex.get(cell);
        int aBit = index.portBitIndexOfPinName("A");
        int yBit = index.portBitIndexOfPinName("Y");
        Assertions.assertEquals(index.localNetIndex(inner), index.getBoundaryNet(aBit));
        Assertions.assertEquals(-1, index.getBoundaryNet(yBit),
                "An unconnected port bit must not report a boundary net");
    }

    /** An unknown pin name must resolve to nothing rather than to bit zero. */
    @Test
    public void testUnknownPinName() {
        cell.createPort("S[7:0]", EDIFDirection.INPUT, 8);
        CellIndex index = CellIndex.get(cell);
        Assertions.assertEquals(-1, index.portBitIndexOfPinName("NOPE"));
        Assertions.assertEquals(-1, index.portBitIndexOfPinName("S[99]"));
    }
}
