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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.CellPinStaticDefaults;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCellInst;

/**
 * Builds the unified cone of every checked pin into the shared and-inverter
 * graph.
 *
 * <h2>Traversal</h2>
 *
 * Cone construction is a backward walk from a pin to the terminals feeding it,
 * carried out over a graph whose nodes are <i>leaf cell output pins</i> rather
 * than cells. Working at pin granularity is what keeps carry chains from being
 * reported as loops: a carry chain is cyclic as a graph of cells but acyclic
 * as a graph of pins, so it never looks like a combinational loop.
 *
 * The walk uses an explicit work list, never Java recursion. A carry chain
 * across a wide adder is a dependency chain thousands of pins deep, and a
 * recursive traversal would overflow the stack on exactly the designs this tool
 * exists to check.
 *
 * <h2>Loop breaking</h2>
 *
 * Reaching a pin that is already on the stack means a genuine bit level cycle.
 * It is recorded in the {@link LoopReport} and cut by minting a fresh unstable
 * variable for that back edge, so the run continues and finds every loop rather
 * than aborting at the first.
 *
 * <h2>Where cones stop</h2>
 *
 * At constants, at top level ports, and at terminal output pins, each of which
 * becomes a primary input of the shared graph named by its
 * {@link TerminalKey}. A terminal's key is stable, meaning the same name refers
 * to the same signal in both netlists, which is what lets two independently
 * built functions be compared. Loop breaks, multi-driver nets that cannot be
 * named from terminals, and black box outputs produce unstable variables
 * instead, and any cone reaching one can only be reported inconclusive.
 */
public class ConeBuilder {

    /** Returned by input resolution when a frame was pushed and work must resume. */
    private static final int PUSHED = Integer.MIN_VALUE;

    private static final byte WHITE = 0;

    private static final byte GRAY = 1;

    private static final byte BLACK = 2;

    /** Warning category for an output pin of a combinational cell with no model. */
    public static final String UNMODELED_OUTPUT = "Combinational output without a model";

    /** Warning category for a net whose drivers cannot be named stably. */
    public static final String UNSTABLE_MULTI_DRIVER =
            "Multi-driver net cut as an unmatchable variable";

    /** Warning category for an input pin left to its implicit tie value. */
    public static final String IMPLICIT_TIE = "Unconnected pin tied to its default value";

    private final FlatNetlist flat;

    private final Connectivity conn;

    private final CellClassifier classifier;

    private final PrimitiveModelLibrary library;

    private final Aig aig;

    private final LecLog log;

    private final Series series;

    private final LoopReport loops = new LoopReport();

    /** The literal computed for each leaf output pin, or -1 if not yet built. */
    private final int[] litOf;

    private final byte[] state;

    /** The variable a back edge was cut with, per pin, or -1. */
    private final int[] loopLit;

    /** Owning instance of each leaf pin, precomputed to avoid repeated searching. */
    private final int[] pinOwner;

    /** Role of each cell type, cached by dense type identifier. */
    private final CellRole[] roleOfType;

    private final Map<Integer, Integer> multiDriverLits = new HashMap<>();

    private final Map<String, Unisim> unisimCache = new HashMap<>();

    private int[] framePin = new int[1024];

    private int[][] frameSupport = new int[1024][];

    private int[][] frameLits = new int[1024][];

    private int[] frameCursor = new int[1024];

    private int top = -1;

    private int maxDepth;

    private int unstableCounter;

    public ConeBuilder(FlatNetlist flat, Connectivity conn, CellClassifier classifier,
            Aig aig, Series series, LecLog log) {
        this.flat = flat;
        this.conn = conn;
        this.classifier = classifier;
        this.aig = aig;
        this.series = series;
        this.log = log;
        this.library = new PrimitiveModelLibrary(log);

        int pins = flat.getLeafPinCount();
        litOf = new int[pins];
        loopLit = new int[pins];
        Arrays.fill(litOf, -1);
        Arrays.fill(loopLit, -1);
        state = new byte[pins];
        pinOwner = buildPinOwner(flat);
        roleOfType = new CellRole[flat.getCellTypes().size()];
    }

    /** Precomputes the owning instance of every leaf pin in one linear pass. */
    private static int[] buildPinOwner(FlatNetlist flat) {
        int[] owner = new int[flat.getLeafPinCount()];
        for (int instId : flat.getLeafInstIds()) {
            int base = flat.getGlobalPin(instId, 0);
            int count = flat.getIndex(instId).getPortBitCount();
            for (int i = 0; i < count; i++) {
                owner[base + i] = instId;
            }
        }
        return owner;
    }

    private CellRole roleOf(int instId) {
        int typeId = flat.getCellTypeId(instId);
        CellRole role = roleOfType[typeId];
        if (role == null) {
            role = classifier.classify(flat.getCellType(instId));
            roleOfType[typeId] = role;
        }
        return role;
    }

    /**
     * Resolves the Boolean function driving a leaf cell input pin, which is what
     * a checked cone root is.
     *
     * @param instId A leaf instance.
     * @param bit    An input port bit of that instance.
     * @return The literal computing the function feeding that pin.
     */
    public int literalDriving(int instId, int bit) {
        int lit = resolveInput(instId, bit);
        if (lit != PUSHED) {
            return lit;
        }
        runLoop();
        lit = resolveInput(instId, bit);
        if (lit == PUSHED) {
            throw new LecException("Cone construction did not converge at "
                    + flat.getPinName(instId, bit));
        }
        return lit;
    }

    /**
     * Resolves the literal arriving at one input pin, either directly or by
     * scheduling the driving cell for construction.
     *
     * @return The literal, or {@link #PUSHED} if a frame was pushed instead.
     */
    private int resolveInput(int instId, int bit) {
        int pin = flat.getGlobalPin(instId, bit);
        int root = pin < 0 ? -1 : conn.getPinRoot(pin);
        if (root < 0 || conn.getRootKind(root) == Connectivity.UNDRIVEN) {
            return defaultLit(instId, bit);
        }
        return resolveRoot(root);
    }

    /**
     * Resolves the literal carried by an electrical node, independent of which
     * pin is observing it.
     *
     * @return The literal, or {@link #PUSHED} if a frame was pushed instead.
     */
    private int resolveRoot(int root) {
        switch (conn.getRootKind(root)) {
        case Connectivity.CONST0:
            return Aig.FALSE;
        case Connectivity.CONST1:
            return Aig.TRUE;
        case Connectivity.TOP_INPUT:
        case Connectivity.TOP_INOUT:
            return topPortLit(root);
        case Connectivity.UNDRIVEN:
            return Aig.FALSE;
        case Connectivity.MULTI_DRIVER:
            return multiDriverLit(root);
        case Connectivity.DRIVEN_BY_LEAF:
        default:
            return literalOfDriver(conn.getDriverPin(root));
        }
    }

    /**
     * Resolves the function carried by an electrical node. Used for top level
     * output ports, which are checked terminals but which are attached to a net
     * rather than to a cell pin.
     */
    public int literalOfNetRoot(int root) {
        if (root < 0) {
            return Aig.FALSE;
        }
        int lit = resolveRoot(root);
        if (lit != PUSHED) {
            return lit;
        }
        runLoop();
        lit = resolveRoot(root);
        if (lit == PUSHED) {
            throw new LecException("Cone construction did not converge at electrical node "
                    + root);
        }
        return lit;
    }

    /**
     * Resolves the literal produced by a leaf cell output pin, pushing a frame
     * if that output still has to be built.
     */
    private int literalOfDriver(int driverPin) {
        if (driverPin < 0) {
            return Aig.FALSE;
        }
        if (state[driverPin] == BLACK) {
            return litOf[driverPin];
        }
        if (state[driverPin] == GRAY) {
            return breakLoop(driverPin);
        }
        int owner = pinOwner[driverPin];
        CellRole role = roleOf(owner);
        if (role != CellRole.COMBINATIONAL) {
            int lit = boundaryLit(owner, driverPin, role);
            litOf[driverPin] = lit;
            state[driverPin] = BLACK;
            return lit;
        }
        push(driverPin);
        return PUSHED;
    }

    /**
     * Drains the work list. Each iteration either resolves one more input of the
     * frame on top, pushes a new frame, or completes and pops the frame.
     */
    private void runLoop() {
        while (top >= 0) {
            int frame = top;
            int pin = framePin[frame];
            if (frameSupport[frame] == null && !beginFrame(frame, pin)) {
                continue;
            }
            int owner = pinOwner[pin];
            int[] support = frameSupport[frame];
            boolean pushed = false;
            while (frameCursor[frame] < support.length) {
                int lit = resolveInput(owner, support[frameCursor[frame]]);
                if (lit == PUSHED) {
                    pushed = true;
                    break;
                }
                frameLits[frame][frameCursor[frame]] = lit;
                frameCursor[frame]++;
            }
            if (pushed) {
                continue;
            }
            completeFrame(frame, pin, owner);
        }
    }

    /**
     * Prepares a newly pushed frame by asking the cell's model which inputs the
     * output depends on.
     *
     * @return True if the frame is ready to resolve inputs, false if it was
     *         already resolved and popped.
     */
    private boolean beginFrame(int frame, int pin) {
        if (state[pin] == BLACK) {
            pop();
            return false;
        }
        int owner = pinOwner[pin];
        int bit = pin - flat.getGlobalPin(owner, 0);
        CellIndex index = flat.getIndex(owner);
        EDIFCellInst inst = flat.getInst(owner);
        PrimitiveModel model = library.get(flat.getCellTypeName(owner));
        int[] support = model == null ? null : model.supportOf(index, inst, bit);
        if (support == null) {
            log.warn(UNMODELED_OUTPUT, flat.getCellTypeName(owner) + "."
                    + index.getPortBitName(bit), flat.getInstName(owner));
            int lit = aig.newInput(TerminalKey.OPAQUE_PREFIX + flat.getPinName(owner, bit), false);
            litOf[pin] = lit;
            state[pin] = BLACK;
            pop();
            return false;
        }
        frameSupport[frame] = support;
        frameLits[frame] = new int[support.length];
        frameCursor[frame] = 0;
        state[pin] = GRAY;
        return true;
    }

    /** Builds the output's function now that every input literal is known. */
    private void completeFrame(int frame, int pin, int owner) {
        int bit = pin - flat.getGlobalPin(owner, 0);
        PrimitiveModel model = library.get(flat.getCellTypeName(owner));
        int lit = model.build(aig, flat.getIndex(owner), flat.getInst(owner), bit,
                frameLits[frame]);
        litOf[pin] = lit;
        state[pin] = BLACK;
        pop();
    }

    /**
     * Cuts a genuine bit level cycle.
     *
     * The pins between the target's frame and the top of the stack form the
     * cycle. It is recorded once and cut with a fresh unstable variable, so the
     * cone that contained it becomes inconclusive rather than silently wrong,
     * and the traversal carries on to find any other loops.
     */
    private int breakLoop(int pin) {
        if (loopLit[pin] >= 0) {
            return loopLit[pin];
        }
        int start = -1;
        for (int i = top; i >= 0; i--) {
            if (framePin[i] == pin) {
                start = i;
                break;
            }
        }
        if (start >= 0) {
            String[] cycle = new String[top - start + 1];
            for (int i = start; i <= top; i++) {
                int owner = pinOwner[framePin[i]];
                cycle[i - start] = flat.getPinName(owner,
                        framePin[i] - flat.getGlobalPin(owner, 0));
            }
            loops.record(cycle);
        }
        int owner = pinOwner[pin];
        int lit = aig.newInput(TerminalKey.LOOP_PREFIX
                + flat.getPinName(owner, pin - flat.getGlobalPin(owner, 0)), false);
        loopLit[pin] = lit;
        return lit;
    }

    /**
     * Mints the primary input standing for a cone boundary: a constant, a
     * terminal output, or a black box output.
     */
    private int boundaryLit(int instId, int pin, CellRole role) {
        if (role == CellRole.CONSTANT_GND) {
            return Aig.FALSE;
        }
        if (role == CellRole.CONSTANT_VCC) {
            return Aig.TRUE;
        }
        int bit = pin - flat.getGlobalPin(instId, 0);
        String name = flat.getPinName(instId, bit);
        if (role == CellRole.TERMINAL) {
            return aig.newInput(name, true);
        }
        return aig.newInput(TerminalKey.OPAQUE_PREFIX + name, false);
    }

    /** A top level port is a source terminal, named by its port bit. */
    private int topPortLit(int root) {
        int bit = conn.getTopPortBit(root);
        if (bit < 0) {
            return Aig.FALSE;
        }
        String name = flat.getIndex(flat.getTopInstId()).getPortBitName(bit);
        return aig.newInput(TerminalKey.forTopPort(name), true);
    }

    /**
     * Cuts a net that has several drivers.
     *
     * When every driver is a terminal the net can still be named stably, from
     * the sorted set of driver pins, and therefore still matched between the two
     * netlists. When any driver is inside the combinational cloud no stable name
     * exists, because cloud instance naming may differ arbitrarily,
     * so the variable is unmatchable and taints the cones that reach it.
     */
    private int multiDriverLit(int root) {
        Integer cached = multiDriverLits.get(root);
        if (cached != null) {
            return cached;
        }
        int[] drivers = conn.getExtraDrivers(root);
        int lit;
        String[] names = drivers == null ? null : namesIfAllTerminal(drivers);
        if (names != null) {
            lit = aig.newInput(TerminalKey.forMultiDriver(names), true);
        } else {
            lit = aig.newInput(TerminalKey.MULTI_DRIVER_PREFIX + "#" + (unstableCounter++), false);
            log.warn(UNSTABLE_MULTI_DRIVER, drivers == null ? "unknown drivers"
                    : drivers.length + " drivers", exampleDriverName(drivers));
        }
        multiDriverLits.put(root, lit);
        return lit;
    }

    /**
     * @return The driver pin names if every driver is a terminal, or null if any
     *         driver is combinational cloud logic.
     */
    private String[] namesIfAllTerminal(int[] drivers) {
        String[] names = new String[drivers.length];
        for (int i = 0; i < drivers.length; i++) {
            int owner = pinOwner[drivers[i]];
            if (roleOf(owner) != CellRole.TERMINAL) {
                return null;
            }
            names[i] = flat.getPinName(owner, drivers[i] - flat.getGlobalPin(owner, 0));
        }
        return names;
    }

    private String exampleDriverName(int[] drivers) {
        if (drivers == null || drivers.length == 0) {
            return null;
        }
        int owner = pinOwner[drivers[0]];
        return flat.getPinName(owner, drivers[0] - flat.getGlobalPin(owner, 0));
    }

    /**
     * The value an unconnected input pin takes.
     *
     * Vivado ties unconnected pins to a fixed value that depends on the pin, so
     * a design that leaves a pin dangling and one that ties it explicitly are
     * the same circuit. Consulting the same table RapidWright uses keeps those
     * two comparing equal. Ground is the fallback, matching the {@code rv 0}
     * convention in the golden simulation models.
     */
    private int defaultLit(int instId, int bit) {
        String typeName = flat.getCellTypeName(instId);
        String pinName = CellClassifier.stripBusIndex(flat.getIndex(instId).getPortBitName(bit));
        NetType tie = lookupPinDefault(typeName, pinName);
        log.warn(IMPLICIT_TIE, typeName + "." + pinName + " -> "
                + (tie == NetType.VCC ? "VCC" : "GND"), flat.getInstName(instId));
        return tie == NetType.VCC ? Aig.TRUE : Aig.FALSE;
    }

    private NetType lookupPinDefault(String typeName, String pinName) {
        Unisim unisim = unisimCache.get(typeName);
        if (unisim == null) {
            unisim = toUnisim(typeName);
            unisimCache.put(typeName, unisim);
        }
        return pinDefault(series, unisim, pinName);
    }

    private static Unisim toUnisim(String typeName) {
        try {
            return Unisim.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            return Unisim.GND;              // stands in for "no entry"; ties to ground
        }
    }

    /**
     * The tie value an unconnected pin of a primitive takes, as a stand-alone
     * lookup.
     *
     * Exposed because {@link LecSelfCheck} has to identify provably constant
     * inputs to build its don't-care control, and a second copy of this table
     * could drift from the one elaboration actually uses — which would let the
     * control assert an INIT entry is unreachable when the checker thinks
     * otherwise.
     */
    static NetType pinDefault(Series series, Unisim unisim, String pinName) {
        if (series == null || unisim == Unisim.GND) {
            return NetType.GND;
        }
        try {
            NetType tie = CellPinStaticDefaults.getCellPinDefault(series, unisim, pinName);
            return tie == null ? NetType.GND : tie;
        } catch (RuntimeException e) {
            return NetType.GND;
        }
    }

    /** @see #pinDefault(Series, Unisim, String) */
    static NetType pinDefault(Series series, String typeName, String pinName) {
        return pinDefault(series, toUnisim(typeName), pinName);
    }

    private void push(int pin) {
        top++;
        if (top == framePin.length) {
            int capacity = framePin.length * 2;
            framePin = Arrays.copyOf(framePin, capacity);
            frameSupport = Arrays.copyOf(frameSupport, capacity);
            frameLits = Arrays.copyOf(frameLits, capacity);
            frameCursor = Arrays.copyOf(frameCursor, capacity);
        }
        framePin[top] = pin;
        frameSupport[top] = null;
        frameLits[top] = null;
        frameCursor[top] = 0;
        if (top > maxDepth) {
            maxDepth = top;
        }
    }

    private void pop() {
        frameSupport[top] = null;
        frameLits[top] = null;
        top--;
    }

    public LoopReport getLoopReport() {
        return loops;
    }

    /** @return The deepest the traversal stack reached, for the scale report. */
    public int getMaxDepth() {
        return maxDepth;
    }

    public Aig getAig() {
        return aig;
    }
}
