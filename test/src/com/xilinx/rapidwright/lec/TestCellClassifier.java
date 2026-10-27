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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Checks that cells are sorted into the roles of {@link CellRole}, and
 * that anything unfamiliar degrades in the safe direction.
 */
public class TestCellClassifier {

    private final EDIFNetlist netlist = new EDIFNetlist("top");

    private final EDIFLibrary primitives =
            netlist.addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME));

    private final EDIFLibrary work =
            netlist.addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME));

    private CellClassifier classifier() {
        return new CellClassifier(Series.UltraScalePlus, new LecConfig(),
                new LecLog(false, new PrintStream(new ByteArrayOutputStream())));
    }

    private CellRole roleOfPrimitive(String name) {
        return classifier().classify(new EDIFCell(primitives, name));
    }

    private CellRole roleOfWorkCell(String name) {
        return classifier().classify(new EDIFCell(work, name));
    }

    /** The interior of a cone: LUTs, wide multiplexers, carry primitives and gates. */
    @Test
    public void testCombinationalTypes() {
        for (String name : new String[] { "LUT1", "LUT6", "LUT6_2", "LUT6CY", "LUTCY1", "LUTCY2",
                "MUXF7", "MUXF8", "MUXF9", "INV", "AND2B1L", "OR2L", "CARRY4", "CARRY8",
                "LOOKAHEAD8", "MUXCY", "XORCY", "XORCY_L" }) {
            Assertions.assertEquals(CellRole.COMBINATIONAL, roleOfPrimitive(name),
                    name + " is combinational");
        }
    }

    /** Cone endpoints: sequential elements, memories and DSPs. */
    @Test
    public void testTerminalTypes() {
        for (String name : new String[] { "FDRE", "FDCE", "LDCE", "SRL16E", "SRLC32E",
                "RAMB18E2", "RAMB36E2", "URAM288", "DSP48E2", "RAMD64E", "RAM64X1D",
                "RAM128X1S" }) {
            Assertions.assertEquals(CellRole.TERMINAL, roleOfPrimitive(name),
                    name + " is a terminal");
        }
    }

    /**
     * Constants must be recognised by value, since a constant
     * sourced from a different tie cell is not a difference.
     */
    @Test
    public void testConstants() {
        Assertions.assertEquals(CellRole.CONSTANT_GND, roleOfPrimitive("GND"));
        Assertions.assertEquals(CellRole.CONSTANT_VCC, roleOfPrimitive("VCC"));
    }

    /**
     * Despite matching the {@code LUT*} name pattern, a configurable
     * LUT holds state that is shifted at run time, so its outputs are not a
     * function of its inputs and it must not be treated as combinational.
     */
    @Test
    public void testConfigurableLutIsATerminal() {
        Assertions.assertEquals(CellRole.TERMINAL, roleOfPrimitive("CFGLUT5"));
    }

    /**
     * Input and output buffers must be transparent, otherwise no cone could ever
     * reach a top level port, which is a source terminal.
     */
    @Test
    public void testIoBuffersAreTransparent() {
        for (String name : new String[] { "IBUF", "OBUF", "IBUFDS", "OBUFDS" }) {
            Assertions.assertEquals(CellRole.COMBINATIONAL, roleOfPrimitive(name),
                    name + " must be transparent to logic");
        }
    }

    /**
     * A buffer whose output can be high impedance cannot be represented in a two
     * valued graph, so it must be cut rather than modelled.
     */
    @Test
    public void testTristateBuffersAreNotCombinational() {
        for (String name : new String[] { "OBUFT", "IOBUF", "OBUFTDS", "IOBUFDS" }) {
            Assertions.assertEquals(CellRole.TERMINAL, roleOfPrimitive(name),
                    name + " can drive high impedance and must not be modelled as logic");
        }
    }

    /**
     * An unfamiliar device primitive is opaque but still matchable, because its
     * identity is fixed by the architecture. An unfamiliar cell outside the
     * primitive library is a black box whose name may not correspond between the
     * two netlists.
     */
    @Test
    public void testUnfamiliarCellsSplitOnLibrary() {
        Assertions.assertEquals(CellRole.TERMINAL, roleOfPrimitive("GTYE4_CHANNEL"),
                "An unfamiliar device primitive should be an opaque terminal");
        Assertions.assertEquals(CellRole.TERMINAL, roleOfPrimitive("MMCME3_ADV"));
        Assertions.assertEquals(CellRole.UNINTERPRETED, roleOfWorkCell("my_encrypted_ip"),
                "A black box outside the primitive library cannot be assumed to correspond");
    }

    /** A black box must be reported so the user knows the verdict is weakened. */
    /**
     * A name is only meaningful inside the primitive library. A user cell
     * called {@code FDRE} or {@code LUT4} is a black box, and a classifier that
     * has already seen the primitive of that name must not lend it that role.
     */
    @Test
    public void testPrimitiveNamesOutsideThePrimitiveLibraryAreBlackBoxes() {
        CellClassifier classifier = classifier();
        for (String name : new String[] { "FDRE", "LUT4", "RAMB36E2", "GND" }) {
            Assertions.assertNotEquals(CellRole.UNINTERPRETED,
                    classifier.classify(new EDIFCell(primitives, name)));
            Assertions.assertEquals(CellRole.UNINTERPRETED,
                    classifier.classify(new EDIFCell(work, name)),
                    "work." + name + " is a black box");
        }
    }

    @Test
    public void testBlackBoxesAreReported() {
        LecLog log = new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
        CellClassifier classifier = new CellClassifier(Series.UltraScalePlus, new LecConfig(), log);
        classifier.classify(new EDIFCell(work, "some_ip"));
        Assertions.assertEquals(1, log.getCount(CellClassifier.BLACK_BOX_TYPE));
        Assertions.assertTrue(log.getSubjectCounts(CellClassifier.BLACK_BOX_TYPE)
                .containsKey("some_ip"));
    }

    @Test
    public void testStripBusIndex() {
        Assertions.assertEquals("ADDRARDADDR", CellClassifier.stripBusIndex("ADDRARDADDR[3]"));
        Assertions.assertEquals("CI", CellClassifier.stripBusIndex("CI"));
    }
}
