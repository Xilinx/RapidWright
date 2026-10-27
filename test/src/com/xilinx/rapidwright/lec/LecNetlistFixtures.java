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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;

/**
 * Builds small partitioned netlists in memory, so that the acceptance cases can
 * be exercised precisely.
 *
 * Real checkpoints are excellent for confirming the tool copes with real
 * netlists, but they are useless for the negative cases: to prove the checker
 * reports FAIL when it should, the difference has to be introduced
 * deliberately and in one known place. These fixtures give exact control over
 * partitioning, cone structure and cell configuration, and need neither a
 * device database nor Vivado.
 *
 * Every fixture has the same shape, a top cell whose children are
 * partitions, with signals crossing between them through
 * boundary ports.
 */
public class LecNetlistFixtures {

    /** A part whose series RapidWright can resolve without a device database. */
    public static final String ULTRASCALE_PLUS_PART = "xcvu3p-ffvc1517-2-i";

    private final EDIFNetlist netlist;

    private final EDIFLibrary work;

    private final EDIFLibrary primitives;

    private final EDIFCell top;

    private final Map<String, EDIFCell> primitiveCache = new HashMap<>();

    public LecNetlistFixtures(String topName) {
        netlist = new EDIFNetlist(topName);
        EDIFDesign design = new EDIFDesign(topName);
        netlist.setDesign(design);
        design.addProperty(EDIFTools.EDIF_PART_PROP, ULTRASCALE_PLUS_PART, EDIFValueType.STRING);
        primitives = netlist.addLibrary(
                new EDIFLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME));
        work = netlist.addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME));
        top = new EDIFCell(work, topName);
        design.setTopCell(top);
        netlist.setDevice(null);
    }

    public EDIFNetlist getNetlist() {
        return netlist;
    }

    public EDIFCell getTop() {
        return top;
    }

    /** Creates an empty hierarchical cell, used for a partition. */
    public EDIFCell newHierCell(String name) {
        return new EDIFCell(work, name);
    }

    /**
     * Creates, or returns, a leaf primitive with the given interface. Ports are
     * declared explicitly rather than copied from the unisim library so that
     * these fixtures do not depend on the device data files.
     */
    public EDIFCell primitive(String name, String[] inputs, String[] outputs) {
        EDIFCell cached = primitiveCache.get(name);
        if (cached != null) {
            return cached;
        }
        EDIFCell cell = new EDIFCell(primitives, name);
        for (String input : inputs) {
            cell.createPort(input, EDIFDirection.INPUT, 1);
        }
        for (String output : outputs) {
            cell.createPort(output, EDIFDirection.OUTPUT, 1);
        }
        primitiveCache.put(name, cell);
        return cell;
    }

    public EDIFCell lut(int inputs) {
        String[] pins = new String[inputs];
        for (int i = 0; i < inputs; i++) {
            pins[i] = "I" + i;
        }
        return primitive("LUT" + inputs, pins, new String[] { "O" });
    }

    public EDIFCell muxf7() {
        return primitive("MUXF7", new String[] { "I0", "I1", "S" }, new String[] { "O" });
    }

    public EDIFCell inv() {
        return primitive("INV", new String[] { "I" }, new String[] { "O" });
    }

    public EDIFCell fdre() {
        return primitive("FDRE", new String[] { "C", "CE", "D", "R" }, new String[] { "Q" });
    }

    public EDIFCell vcc() {
        return primitive("VCC", new String[0], new String[] { "P" });
    }

    public EDIFCell gnd() {
        return primitive("GND", new String[0], new String[] { "G" });
    }

    /**
     * Creates the eight bit carry primitive with its real bussed interface, so
     * that the bit level dependency behaviour is exercised exactly as it is on a
     * real design.
     */
    public EDIFCell carry8() {
        EDIFCell cached = primitiveCache.get("CARRY8");
        if (cached != null) {
            return cached;
        }
        EDIFCell cell = new EDIFCell(primitives, "CARRY8");
        cell.createPort("CI", EDIFDirection.INPUT, 1);
        cell.createPort("CI_TOP", EDIFDirection.INPUT, 1);
        cell.createPort("DI[7:0]", EDIFDirection.INPUT, 8);
        cell.createPort("S[7:0]", EDIFDirection.INPUT, 8);
        cell.createPort("O[7:0]", EDIFDirection.OUTPUT, 8);
        cell.createPort("CO[7:0]", EDIFDirection.OUTPUT, 8);
        primitiveCache.put("CARRY8", cell);
        return cell;
    }

    /** Adds an instance of a cell type inside a parent cell. */
    public static EDIFCellInst add(EDIFCell parent, String name, EDIFCell type) {
        EDIFCellInst inst = parent.createCellInst(name, parent);
        inst.setCellType(type);
        return inst;
    }

    /** Sets a configuration property, such as a look up table's INIT. */
    public static void setProperty(EDIFCellInst inst, String key, String value) {
        inst.addProperty(key, value, EDIFValueType.STRING);
    }

    /** Creates a net inside a cell and attaches it to the listed pins. */
    public static EDIFNet connect(EDIFCell parent, String netName, Object... pins) {
        EDIFNet net = parent.getNet(netName);
        if (net == null) {
            net = parent.createNet(netName);
        }
        for (int i = 0; i < pins.length; i += 2) {
            Object target = pins[i];
            String pinName = (String) pins[i + 1];
            if (target instanceof EDIFCellInst) {
                net.createPortInst(pinName, (EDIFCellInst) target);
            } else {
                net.createPortInst(pinName, (EDIFCell) target);
            }
        }
        return net;
    }

    /** Declares a port on a cell, for a boundary crossing or a design port. */
    public static void port(EDIFCell cell, String name, EDIFDirection direction) {
        cell.createPort(name, direction, 1);
    }

    /**
     * Builds a two partition design computing one function into one flip flop.
     *
     * <pre>
     * top
     *  |- PART_1 : lutA over primary inputs a, b  -> boundary port x
     *  |- PART_2 : lutB over x and c              -> ff.D
     * </pre>
     *
     * @param lutBInit  INIT of the second look up table, so a caller can perturb
     *                  the logic by exactly one bit.
     * @param moveLutB  When true the second look up table lives in PART_1
     *                  instead, which changes the partitioning without changing
     *                  the logic.
     * @param ffInPart2 Which partition holds the flip flop. Terminals are
     *                  matched by hierarchical path, so both netlists of a
     *                  comparison must use the same value.
     */
    public static EDIFNetlist twoPartitionDesign(String lutBInit, boolean moveLutB,
            boolean ffInPart2) {
        return twoPartitionDesign(lutBInit, moveLutB, ffInPart2, "");
    }

    /**
     * As above, but with a suffix applied to every combinational instance name.
     *
     * Different instance names are not a difference, so a
     * netlist built with a suffix must compare equal to one built without.
     * Terminals keep their names, because they are the reference
     * frame of the comparison.
     */
    public static EDIFNetlist twoPartitionDesign(String lutBInit, boolean moveLutB,
            boolean ffInPart2, String cloudSuffix) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "b", EDIFDirection.INPUT);
        port(top, "c", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);
        port(top, "q", EDIFDirection.OUTPUT);

        EDIFCell part1 = fixture.newHierCell("part1_type");
        EDIFCell part2 = fixture.newHierCell("part2_type");
        EDIFCellInst inst1 = add(top, "PART_1", part1);
        EDIFCellInst inst2 = add(top, "PART_2", part2);

        EDIFCell lutHome = moveLutB ? part1 : part2;
        EDIFCell ffHome = ffInPart2 ? part2 : part1;

        port(part1, "a", EDIFDirection.INPUT);
        port(part1, "b", EDIFDirection.INPUT);
        port(part1, "c", EDIFDirection.INPUT);
        port(part1, "clk", EDIFDirection.INPUT);
        port(part2, "clk", EDIFDirection.INPUT);
        port(part2, "c", EDIFDirection.INPUT);

        EDIFCellInst lutA = add(part1, "lutA" + cloudSuffix, fixture.lut(2));
        setProperty(lutA, "INIT", "4'h8");
        connect(part1, "a_i", part1, "a", lutA, "I0");
        connect(part1, "b_i", part1, "b", lutA, "I1");

        EDIFCellInst lutB = add(lutHome, "lutB" + cloudSuffix, fixture.lut(2));
        setProperty(lutB, "INIT", lutBInit);
        EDIFCellInst ff = add(ffHome, "ff", fixture.fdre());
        EDIFCellInst vccInst = add(ffHome, "VCC", fixture.vcc());
        EDIFCellInst gndInst = add(ffHome, "GND", fixture.gnd());

        wireTwoPartition(fixture, top, part1, part2, lutHome, ffHome, lutA, lutB, ff,
                vccInst, gndInst, moveLutB, ffInPart2);
        return fixture.getNetlist();
    }

    /**
     * Wires the two partition design, creating whatever boundary ports the
     * chosen placement of the second look up table and the flip flop requires.
     */
    private static void wireTwoPartition(LecNetlistFixtures fixture, EDIFCell top, EDIFCell part1,
            EDIFCell part2, EDIFCell lutHome, EDIFCell ffHome, EDIFCellInst lutA,
            EDIFCellInst lutB, EDIFCellInst ff, EDIFCellInst vccInst, EDIFCellInst gndInst,
            boolean moveLutB, boolean ffInPart2) {
        connect(top, "a", top, "a", fixture.instOf(top, "PART_1"), "a");
        connect(top, "b", top, "b", fixture.instOf(top, "PART_1"), "b");
        connect(top, "clk", top, "clk", fixture.instOf(top, "PART_1"), "clk",
                fixture.instOf(top, "PART_2"), "clk");
        connect(top, "c", top, "c", fixture.instOf(top, "PART_1"), "c",
                fixture.instOf(top, "PART_2"), "c");

        if (moveLutB) {
            connect(part1, "x", lutA, "O", lutB, "I0");
            connect(part1, "c_i", part1, "c", lutB, "I1");
        } else {
            port(part1, "x", EDIFDirection.OUTPUT);
            port(part2, "x", EDIFDirection.INPUT);
            connect(part1, "x", lutA, "O", part1, "x");
            connect(top, "x", fixture.instOf(top, "PART_1"), "x",
                    fixture.instOf(top, "PART_2"), "x");
            connect(part2, "x_i", part2, "x", lutB, "I0");
            connect(part2, "c_i", part2, "c", lutB, "I1");
        }

        if (lutHome == ffHome) {
            connect(ffHome, "d", lutB, "O", ff, "D");
        } else {
            port(lutHome, "y", EDIFDirection.OUTPUT);
            port(ffHome, "y", EDIFDirection.INPUT);
            connect(lutHome, "y_o", lutB, "O", lutHome, "y");
            connect(top, "y", fixture.instOf(top, lutHome == part1 ? "PART_1" : "PART_2"), "y",
                    fixture.instOf(top, ffHome == part1 ? "PART_1" : "PART_2"), "y");
            connect(ffHome, "d", ffHome, "y", ff, "D");
        }

        connect(ffHome, "clk_i", ffHome, "clk", ff, "C");
        connect(ffHome, "vcc", vccInst, "P", ff, "CE");
        connect(ffHome, "gnd", gndInst, "G", ff, "R");
        port(ffHome, "q", EDIFDirection.OUTPUT);
        connect(ffHome, "q_o", ff, "Q", ffHome, "q");
        connect(top, "q", fixture.instOf(top, ffInPart2 ? "PART_2" : "PART_1"), "q", top, "q");
    }


    /**
     * Builds a cluster
     * that is cyclic when viewed as a graph of whole cells but acyclic at bit
     * level, as a multiplier compression tree is.
     *
     * <pre>
     * carryA.O[2] -&gt; lutAB -&gt; carryB.S[5]
     * carryB.O[1] -&gt; lutBA -&gt; carryA.S[6]
     * </pre>
     *
     * Cell A depends on cell B and cell B depends on cell A, yet no bit depends
     * on itself: {@code O[2]} is a function of bits 0 to 2 only and {@code O[1]}
     * of bits 0 to 1, so neither reaches the sum input the other drives. This
     * must elaborate cleanly and report no combinational loop.
     */
    public static EDIFNetlist carryCompressionTree() {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);

        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        port(part, "a", EDIFDirection.INPUT);
        port(part, "clk", EDIFDirection.INPUT);
        connect(top, "a", top, "a", partInst, "a");
        connect(top, "clk", top, "clk", partInst, "clk");

        EDIFCellInst carryA = add(part, "carryA", fixture.carry8());
        EDIFCellInst carryB = add(part, "carryB", fixture.carry8());
        EDIFCellInst lutAB = add(part, "lutAB", fixture.lut(1));
        EDIFCellInst lutBA = add(part, "lutBA", fixture.lut(1));
        setProperty(lutAB, "INIT", "2'h2");
        setProperty(lutBA, "INIT", "2'h2");
        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());

        connect(part, "gnd", gndInst, "G");
        connect(part, "vcc", vccInst, "P");
        connect(part, "a_i", part, "a", carryA, "S[0]", carryB, "S[0]");

        // The cross coupling that makes the two cells cyclic as a pair.
        connect(part, "ab", carryA, "O[2]", lutAB, "I0");
        connect(part, "ab_o", lutAB, "O", carryB, "S[5]");
        connect(part, "ba", carryB, "O[1]", lutBA, "I0");
        connect(part, "ba_o", lutBA, "O", carryA, "S[6]");

        tieRemainingCarryPins(part, carryA, gndInst, vccInst);
        tieRemainingCarryPins(part, carryB, gndInst, vccInst);

        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        connect(part, "sum", carryA, "O[7]", ff, "D");
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }

    /**
     * Ties every carry pin that the compression tree fixture has not already
     * driven, so that the cell is fully connected.
     */
    private static void tieRemainingCarryPins(EDIFCell parent, EDIFCellInst carry,
            EDIFCellInst gndInst, EDIFCellInst vccInst) {
        connect(parent, "gnd", carry, "CI");
        connect(parent, "gnd", carry, "CI_TOP");
        for (int bit = 0; bit < 8; bit++) {
            if (carry.getPortInst("S[" + bit + "]") == null) {
                connect(parent, "vcc", carry, "S[" + bit + "]");
            }
            connect(parent, "gnd", carry, "DI[" + bit + "]");
        }
    }

    /**
     * Builds a genuine combinational loop: two look up tables that drive each
     * other. This must be reported as an error rather than
     * silently accepted.
     */
    public static EDIFNetlist genuineCombinationalLoop() {
        return genuineCombinationalLoop(true);
    }

    /**
     * As above, but able to build the loop-free variant of the same circuit, so
     * that a loop can be made to appear in one netlist and not the other.
     *
     * @param withLoop When false the feedback edge is replaced by a primary
     *                 input, leaving the same cells with no cycle.
     */
    public static EDIFNetlist genuineCombinationalLoop(boolean withLoop) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);

        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        port(part, "a", EDIFDirection.INPUT);
        port(part, "clk", EDIFDirection.INPUT);
        connect(top, "a", top, "a", partInst, "a");
        connect(top, "clk", top, "clk", partInst, "clk");

        EDIFCellInst lut1 = add(part, "loopA", fixture.lut(2));
        EDIFCellInst lut2 = add(part, "loopB", fixture.lut(2));
        setProperty(lut1, "INIT", "4'h8");
        setProperty(lut2, "INIT", "4'hE");
        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());

        connect(part, "a_i", part, "a", lut1, "I1", lut2, "I1");
        connect(part, "loop1", lut1, "O", lut2, "I0");
        if (withLoop) {
            connect(part, "loop2", lut2, "O", lut1, "I0");
        } else {
            connect(part, "loop2", lut2, "O");
            connect(part, "a_i", lut1, "I0");
        }

        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        connect(part, "d", lut1, "O", ff, "D");
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }

    /** The four input function both forms of the resynthesis fixture compute. */
    private static final long RESYNTHESIS_TABLE = 0xE880L;

    /**
     * Builds the same four input function two ways, so that the two netlists
     * agree on the logic but disagree on structure.
     *
     * The decomposition deliberately splits on the <i>lowest</i> input, whereas
     * the tool's own lowering of a whole look up table splits on the highest.
     * That difference matters: splitting on the same variable would produce
     * bit identical graph nodes and the comparison would be settled by
     * structural hashing, leaving the solver path untested. Splitting on a
     * different variable makes the two graphs genuinely different while keeping
     * the function the same, so only the solver can prove they agree.
     *
     * @param decomposed False for a single look up table, true for a
     *                   multiplexer over two smaller ones.
     */
    public static EDIFNetlist resynthesisedFunction(boolean decomposed) {
        return resynthesisedFunction(decomposed, RESYNTHESIS_TABLE);
    }

    private static EDIFNetlist resynthesisedFunction(boolean decomposed, long table) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        for (String name : new String[] { "a", "b", "c", "d", "clk" }) {
            port(top, name, EDIFDirection.INPUT);
        }
        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        for (String name : new String[] { "a", "b", "c", "d", "clk" }) {
            port(part, name, EDIFDirection.INPUT);
            connect(top, name, top, name, partInst, name);
        }
        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());

        if (!decomposed) {
            EDIFCellInst lut = add(part, "whole", fixture.lut(4));
            setProperty(lut, "INIT", "16'h" + Long.toHexString(table));
            connect(part, "a_i", part, "a", lut, "I0");
            connect(part, "b_i", part, "b", lut, "I1");
            connect(part, "c_i", part, "c", lut, "I2");
            connect(part, "d_i", part, "d", lut, "I3");
            connect(part, "f", lut, "O");
        } else {
            EDIFCellInst lutLow = add(part, "half0", fixture.lut(3));
            EDIFCellInst lutHigh = add(part, "half1", fixture.lut(3));
            EDIFCellInst mux = add(part, "sel", fixture.muxf7());
            setProperty(lutLow, "INIT", "8'h" + Long.toHexString(cofactor(table, false)));
            setProperty(lutHigh, "INIT", "8'h" + Long.toHexString(cofactor(table, true)));
            connect(part, "b_i", part, "b", lutLow, "I0", lutHigh, "I0");
            connect(part, "c_i", part, "c", lutLow, "I1", lutHigh, "I1");
            connect(part, "d_i", part, "d", lutLow, "I2", lutHigh, "I2");
            connect(part, "a_i", part, "a", mux, "S");
            connect(part, "lo", lutLow, "O", mux, "I0");
            connect(part, "hi", lutHigh, "O", mux, "I1");
            connect(part, "f", mux, "O");
        }

        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        connect(part, "f", ff, "D");
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }

    /**
     * Extracts the three input function obtained by fixing the lowest input of
     * a four input truth table.
     *
     * @param high Whether to take the cofactor with that input at one.
     */
    private static long cofactor(long table, boolean high) {
        long result = 0;
        for (int minterm = 0; minterm < 8; minterm++) {
            int source = (minterm << 1) | (high ? 1 : 0);
            if (((table >>> source) & 1L) != 0) {
                result |= 1L << minterm;
            }
        }
        return result;
    }

    /**
     * Builds a flip flop fed either through an explicit inverter or with the
     * inversion absorbed into the flip flop's own data pin attribute.
     *
     * Both describe the same circuit, so they must compare equal, and the
     * absorbed attribute must not be reported as a terminal property
     * difference.
     */
    public static EDIFNetlist absorbedInversion(boolean absorb) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);
        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        port(part, "a", EDIFDirection.INPUT);
        port(part, "clk", EDIFDirection.INPUT);
        connect(top, "a", top, "a", partInst, "a");
        connect(top, "clk", top, "clk", partInst, "clk");

        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());
        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        if (absorb) {
            setProperty(ff, "IS_D_INVERTED", "1'b1");
            connect(part, "a_i", part, "a", ff, "D");
        } else {
            EDIFCellInst inverter = add(part, "inv", fixture.inv());
            connect(part, "a_i", part, "a", inverter, "I");
            connect(part, "d", inverter, "O", ff, "D");
        }
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }


    /**
     * The resynthesised function with one INIT bit flipped, so the two netlists
     * really do compute different logic.
     */
    public static EDIFNetlist perturbedResynthesisedFunction() {
        EDIFNetlist netlist = resynthesisedFunction(true);
        EDIFCell part = partitionCellOf(netlist, "PART_1");
        EDIFCellInst half = part.getCellInst("half0");
        Assertions.assertNotNull(half, "The decomposed fixture should contain half0");
        setProperty(half, "INIT", "8'h81");
        return netlist;
    }

    /** A flip flop fed directly, with no inversion anywhere. */
    public static EDIFNetlist noInversion() {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);
        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        port(part, "a", EDIFDirection.INPUT);
        port(part, "clk", EDIFDirection.INPUT);
        connect(top, "a", top, "a", partInst, "a");
        connect(top, "clk", top, "clk", partInst, "clk");

        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());
        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        connect(part, "a_i", part, "a", ff, "D");
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }

    /**
     * A flip flop driven through a black box whose instance name differs
     * between the two netlists.
     *
     * Combinational cloud instance naming may differ
     * arbitrarily, so nothing lets the two black box outputs be identified with
     * each other. The comparison must therefore come out inconclusive rather
     * than claiming a difference it cannot attribute.
     */
    public static EDIFNetlist blackBoxDriven(String blackBoxInstName) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);
        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        port(part, "a", EDIFDirection.INPUT);
        port(part, "clk", EDIFDirection.INPUT);
        connect(top, "a", top, "a", partInst, "a");
        connect(top, "clk", top, "clk", partInst, "clk");

        EDIFCell blackBox = fixture.newHierCell("opaque_ip");
        port(blackBox, "I", EDIFDirection.INPUT);
        port(blackBox, "O", EDIFDirection.OUTPUT);
        EDIFCellInst bb = add(part, blackBoxInstName, blackBox);

        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());
        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        connect(part, "a_i", part, "a", bb, "I");
        connect(part, "d", bb, "O", ff, "D");
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }


    /**
     * A top level output {@code a} fed straight from input {@code source}. The
     * output sorts before both candidate inputs, {@code w} and {@code z}, so
     * the node's first port is the output rather than its source.
     */
    public static EDIFNetlist feedthrough(String source) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.OUTPUT);
        port(top, "w", EDIFDirection.INPUT);
        port(top, "z", EDIFDirection.INPUT);
        add(top, "GND", fixture.gnd());
        connect(top, "n", top, source, top, "a");
        return fixture.getNetlist();
    }

    /**
     * Two IOBUFs, driven from inputs {@code a} and {@code b}, onto the
     * bidirectional ports {@code p} and {@code q}, or onto {@code q} and
     * {@code p} when swapped.
     */
    public static EDIFNetlist ioBuffersOnInouts(boolean swapped) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "b", EDIFDirection.INPUT);
        port(top, "p", EDIFDirection.INOUT);
        port(top, "q", EDIFDirection.INOUT);
        EDIFCell iobuf = fixture.primitive("IOBUF", new String[] { "I", "T" },
                new String[] { "O" });
        if (iobuf.getPort("IO") == null) {
            iobuf.createPort("IO", EDIFDirection.INOUT, 1);
        }
        EDIFCellInst iobA = add(top, "iob_a", iobuf);
        EDIFCellInst iobB = add(top, "iob_b", iobuf);
        EDIFCellInst gndInst = add(top, "GND", fixture.gnd());
        connect(top, "a", top, "a", iobA, "I");
        connect(top, "b", top, "b", iobB, "I");
        connect(top, "gnd", gndInst, "G", iobA, "T", iobB, "T");
        connect(top, "p", top, "p", swapped ? iobB : iobA, "IO");
        connect(top, "q", top, "q", swapped ? iobA : iobB, "IO");
        return fixture.getNetlist();
    }

    /**
     * A flip flop capturing input {@code d}, clocked from input {@code clock},
     * which is {@code clk_a} or {@code clk_b}.
     */
    public static EDIFNetlist clockedFlop(String clock) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "clk_a", EDIFDirection.INPUT);
        port(top, "clk_b", EDIFDirection.INPUT);
        port(top, "d", EDIFDirection.INPUT);
        EDIFCellInst ff = add(top, "ff", fixture.fdre());
        EDIFCellInst gndInst = add(top, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(top, "VCC", fixture.vcc());
        connect(top, "d", top, "d", ff, "D");
        connect(top, clock, top, clock, ff, "C");
        connect(top, "vcc", vccInst, "P", ff, "CE");
        connect(top, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }

    /**
     * A net shorted between a flip flop output and a look up table output,
     * read by a second flip flop. Such shorts occur in real netlists.
     *
     * @param lutInit INIT of the look up table driving the short.
     */
    public static EDIFNetlist shortedNet(String lutInit) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "b", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);
        EDIFCellInst lut = add(top, "lut", fixture.lut(2));
        setProperty(lut, "INIT", lutInit);
        EDIFCellInst ffA = add(top, "ff_a", fixture.fdre());
        EDIFCellInst ffB = add(top, "ff_b", fixture.fdre());
        EDIFCellInst gndInst = add(top, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(top, "VCC", fixture.vcc());
        connect(top, "a", top, "a", lut, "I0", ffA, "D");
        connect(top, "b", top, "b", lut, "I1");
        connect(top, "short", lut, "O", ffA, "Q", ffB, "D");
        connect(top, "clk", top, "clk", ffA, "C", ffB, "C");
        connect(top, "vcc", vccInst, "P", ffA, "CE", ffB, "CE");
        connect(top, "gnd", gndInst, "G", ffA, "R", ffB, "R");
        return fixture.getNetlist();
    }

    /**
     * Complements the contents of every look up table in a netlist, which is a
     * blunt but reliable way to make a real design compute different logic.
     *
     * A single flipped bit would be a more surgical mutation, but on an
     * arbitrary design there is no guarantee that any particular table feeds a
     * checked pin, which would make the test unreliable for reasons that have
     * nothing to do with the checker.
     *
     * @return How many instances were changed.
     */
    public static int invertLutInits(EDIFNetlist netlist) {
        int mutated = 0;
        for (EDIFLibrary library : netlist.getLibraries()) {
            if (library.isHDIPrimitivesLibrary()) {
                continue;
            }
            for (EDIFCell cell : library.getCells()) {
                for (EDIFCellInst inst : cell.getCellInsts()) {
                    if (invertLutInit(inst)) {
                        mutated++;
                    }
                }
            }
        }
        return mutated;
    }

    /** @return True if the instance was a look up table whose INIT was changed. */
    private static boolean invertLutInit(EDIFCellInst inst) {
        String typeName = inst.getCellType().getName();
        if (!typeName.startsWith("LUT") || typeName.startsWith("LUTCY")) {
            return false;
        }
        EDIFPropertyValue init = inst.getProperty("INIT");
        if (init == null || init.getValue() == null) {
            return false;
        }
        String text = init.getValue();
        int quote = text.indexOf('\'');
        if (quote < 0) {
            return false;
        }
        int width;
        try {
            width = Integer.parseInt(text.substring(0, quote));
        } catch (NumberFormatException e) {
            return false;
        }
        long value;
        try {
            value = LUTTools.getInitValue(text);
        } catch (RuntimeException e) {
            return false;
        }
        long mask = width >= 64 ? -1L : (1L << width) - 1;
        long inverted = (~value) & mask;
        setProperty(inst, "INIT", width + "'h" + Long.toHexString(inverted));
        return true;
    }

    private EDIFCellInst instOf(EDIFCell parent, String name) {
        return parent.getCellInst(name);
    }

    /** Deletes a terminal, so that the terminal sets no longer correspond. */
    public static void removeTerminal(EDIFNetlist netlist, String partitionInstName,
            String terminalName) {
        EDIFCell partition = partitionCellOf(netlist, partitionInstName);
        Assertions.assertNotNull(partition, "No partition named " + partitionInstName);
        Assertions.assertNotNull(partition.removeCellInst(terminalName),
                "No terminal named " + terminalName + " in " + partitionInstName);
    }

    /** Changes a configuration property of a terminal. */
    public static void setTerminalProperty(EDIFNetlist netlist, String partitionInstName,
            String terminalName, String key, String value) {
        EDIFCell partition = partitionCellOf(netlist, partitionInstName);
        Assertions.assertNotNull(partition, "No partition named " + partitionInstName);
        EDIFCellInst inst = partition.getCellInst(terminalName);
        Assertions.assertNotNull(inst, "No terminal named " + terminalName);
        setProperty(inst, key, value);
    }

    private static EDIFCell partitionCellOf(EDIFNetlist netlist, String partitionInstName) {
        EDIFCellInst inst = netlist.getTopCell().getCellInst(partitionInstName);
        return inst == null ? null : inst.getCellType();
    }

    /**
     * Two look up tables wired into a ring, where the pin closing the ring is one
     * the driving table's INIT does or does not depend on.
     *
     * The cell graph has a cycle either way. Whether the <i>pin</i> graph does
     * depends entirely on the INIT, which is what makes this the look up table
     * analogue of the carry cluster case: Vivado packs unrelated functions into
     * one physical table, so a table can sit on a ring while the output pin in
     * question ignores the returning edge.
     *
     * @param throughDependentInput True to close the ring through an input
     *                              {@code lutA} really uses, giving a genuine
     *                              loop; false to close it through one its INIT
     *                              ignores, which is no loop at all.
     */
    public static EDIFNetlist lutCycleThroughIgnoredInput(boolean throughDependentInput) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        port(top, "a", EDIFDirection.INPUT);
        port(top, "clk", EDIFDirection.INPUT);

        EDIFCell part = fixture.newHierCell("part1_type");
        EDIFCellInst partInst = add(top, "PART_1", part);
        port(part, "a", EDIFDirection.INPUT);
        port(part, "clk", EDIFDirection.INPUT);
        connect(top, "a", top, "a", partInst, "a");
        connect(top, "clk", top, "clk", partInst, "clk");

        EDIFCellInst lutA = add(part, "ringA", fixture.lut(2));
        EDIFCellInst lutB = add(part, "ringB", fixture.lut(2));
        // 4'hC is I1 alone, so I0 - the pin the ring returns on - is ignored.
        // 4'h8 is I0 & I1, which does depend on it.
        setProperty(lutA, "INIT", throughDependentInput ? "4'h8" : "4'hC");
        setProperty(lutB, "INIT", "4'h8");
        EDIFCellInst gndInst = add(part, "GND", fixture.gnd());
        EDIFCellInst vccInst = add(part, "VCC", fixture.vcc());

        connect(part, "a_i", part, "a", lutA, "I1", lutB, "I1");
        connect(part, "fwd", lutA, "O", lutB, "I0");
        connect(part, "back", lutB, "O", lutA, "I0");

        EDIFCellInst ff = add(part, "ff", fixture.fdre());
        connect(part, "d", lutB, "O", ff, "D");
        connect(part, "clk_i", part, "clk", ff, "C");
        connect(part, "vcc", vccInst, "P", ff, "CE");
        connect(part, "gnd", gndInst, "G", ff, "R");
        return fixture.getNetlist();
    }
}
