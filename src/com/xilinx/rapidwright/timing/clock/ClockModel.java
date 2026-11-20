/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.BELAttr;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteConfig;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PackagePin;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * The clock insertion delays of the clock pins of a design, at the four corners, and their clock
 * pessimism removal (CRPR), from a {@code ClockTree} built over the stages of its
 * {@link ClockNetwork}:
 * <ul>
 * <li><b>Routed clock nets</b>: the PIPs of a stage's net are walked from each sink site pin up to
 * the source, each routing node becoming a tree node timed by a {@link ClockNodeDelayModel}; the
 * clock pins hang below their site pin's node with the leaf delay. The CRPR of two clock pins whose paths branch
 * at a routing node of some classes (UltraScale+ horizontal distribution and local clock nodes, see
 * {@code ClockDelayParams.setCrprBranchStartClasses}) is the clock arrival at the start of that node.</li>
 * <li><b>Unrouted clock nets</b> (or sinks off the routing): the estimate of
 * {@code ClockDelayParams}: a node per clock region below the stage's root, timed by the distance
 * from the clock root (CLOCK_ROOT / USER_CLOCK_ROOT, else the load-median clock region). The clock pins
 * of a top-level port without a buffer (out-of-context designs) that its routing does not reach get their leaf
 * delay alone: the leaf configuration of their site pin and cell pin and the CLE clock modifier (Vivado gives
 * them exactly that when the port's net has no routing).</li>
 * <li><b>Buffers</b>: the root of a buffer's output stage hangs below its input pin with the buffer's
 * delay ({@code BufferDelay}: the fitted cell arc library's combinational arc, or clock-to-Q arc for a
 * register driving a clock, else the parameters' buffer arcs).</li>
 * <li><b>Leaves</b>: the tap terms of the site pin node to the load site, the leaf configuration of
 * the site pin and cell pin, and the CLE clock modifier (Versal programmable clock delay).</li>
 * <li><b>Clock generators</b>: an MMCM/PLL hangs a node below its CLKIN pin with the delay
 * {@code c[kind:COMPENSATION][C] - F[opp(C)]}, F being the feedback insertion (the delay from the
 * CLKFBOUT stage's root down to the CLKFBIN pin) at the opposite min/max corner, 0 for INTERNAL
 * compensation; the roots of its output stages hang below that node with their phase shift
 * {@code PHASE/360*T(CLKOUT)}. The node is common to the outputs, so the CRPR of paths between two
 * outputs of a generator includes the compensation (as Vivado's). The phase shifts (the clocks' source
 * latencies) are part of the insertion delays (see {@link #includesSourceLatency()}).</li>
 * </ul>
 * Without a design (or without placement), every clock pin gets the stages' buffer and generator
 * delays only.
 */
public final class ClockModel {

    /** The delay of a buffer (or other cell) on a clock path, from an input pin to an output pin */
    @FunctionalInterface
    interface BufferDelay {
        /**
         * @param cell The cell instance.
         * @param physCell The placed cell, or null.
         * @param inPin The input pin name.
         * @param outPin The output pin name.
         * @return The delays per corner ordinal, or null if unknown (0 is used).
         */
        float[] getDelay(EDIFCellInst cell, Cell physCell, String inPin, String outPin);
    }

    private final ClockTree tree;
    private final int[] pinNode;
    private final boolean[] pinRouted;
    /** SLR index of each clock pin's cell, -1 if unknown */
    private final int[] pinSlr;
    private int numRoutedPins;
    private int numEstimatedPins;
    /** Whether the CCD of the inter-SLR compensation ends at the start of the last common node */
    private boolean interSlrCommonAtLcaStart;
    private final Map<String, Integer> warnings = new LinkedHashMap<>();

    /**
     * Whether the insertion delays include the clocks' source latency (the phase shifts of clock
     * generators): true for models built over a clock network, false for hand-built ones
     */
    private boolean includesSourceLatency;

    private ClockModel(ClockTree tree, int[] pinNode, boolean[] pinRouted) {
        this.tree = tree;
        this.pinNode = pinNode;
        this.pinRouted = pinRouted;
        this.pinSlr = new int[pinNode.length];
        Arrays.fill(pinSlr, -1);
    }

    /**
     * Creates a model from a hand-built tree (e.g. for tests).
     * @param tree The tree.
     * @param clockPinNodes The tree node of each clock pin (-1 for an ideal clock pin: no insertion).
     * @return The model.
     */
    static ClockModel of(ClockTree tree, int[] clockPinNodes) {
        tree.finish();
        return new ClockModel(tree, clockPinNodes.clone(), new boolean[clockPinNodes.length]);
    }

    /**
     * Creates a model from a hand-built tree with the SLR of each clock pin (e.g. for tests of the
     * inter-SLR compensation).
     * @param tree The tree.
     * @param clockPinNodes The tree node of each clock pin (-1 for an ideal clock pin: no insertion).
     * @param clockPinSlrs The SLR index of each clock pin (-1 if unknown).
     * @return The model.
     */
    static ClockModel of(ClockTree tree, int[] clockPinNodes, int[] clockPinSlrs) {
        ClockModel m = of(tree, clockPinNodes);
        System.arraycopy(clockPinSlrs, 0, m.pinSlr, 0, m.pinSlr.length);
        return m;
    }

    /**
     * Creates a model of ideal clocks: no insertion delay, no CRPR.
     * @param numClockPins The number of clock pins.
     */
    public static ClockModel ideal(int numClockPins) {
        int[] nodes = new int[numClockPins];
        Arrays.fill(nodes, -1);
        return of(new ClockTree(), nodes);
    }

    /**
     * Creates a model of ideal clocks (no insertion delay, no CRPR) that has the SLR of each clock pin of a
     * design's clock network, e.g. to stand for the model built after the clock nets are routed.
     * @param design The design (its placement).
     * @param cn The clock network.
     * @return The model.
     */
    public static ClockModel ideal(Design design, ClockNetwork cn) {
        ClockModel m = ideal(cn.getNumClockPins());
        for (int i = 0; i < cn.getNumClockPins(); i++) {
            Cell pc = design.getCell(cn.getClockPin(i).getFullHierarchicalInstName());
            if (pc != null && pc.getTile() != null) {
                m.pinSlr[i] = pc.getTile().getSLRIndex();
            }
        }
        return m;
    }

    /**
     * Builds the model of a design's clock network, with buffer delays from the parameters.
     * @param design The design (its placement and clock routing are used), or null.
     * @param cn The clock network.
     * @param nodeModel Times the clock route nodes.
     * @return The model.
     */
    public static ClockModel build(Design design, ClockNetwork cn, ClockNodeDelayModel nodeModel) {
        return build(design, cn, nodeModel, getBufferDelay(design, nodeModel.getParams()));
    }

    /**
     * Gets the buffer delays of a design's clock cells: the combinational arcs of the family's fitted
     * cell arc library (scaled to the device and speed grade), else the parameters' buffer arcs.
     * @param design The design, or null for the parameters' buffer arcs only.
     * @param params The parameters.
     * @return The buffer delays.
     */
    static BufferDelay getBufferDelay(Design design, ClockDelayParams params) {
        FittedCellArcLibrary lib = null;
        String device = null;
        String grade = null;
        if (design != null) {
            try {
                TimingFamily family = TimingFamilies.of(design.getDevice());
                lib = family.getFittedCellArcs(design.getDevice());
                device = design.getDevice().getName();
                String part = design.getPartName();
                SpeedGrade g = part == null || part.isEmpty() ? null
                        : family.resolveSpeedGrade(PartNameTools.getPart(part));
                grade = g == null || !lib.hasSpeedGrade(g.getName()) ? null : g.getName();
            } catch (RuntimeException e) {
                lib = null;
            }
        }
        final FittedCellArcLibrary fl = lib;
        final String dev = device;
        final String sg = grade;
        return (ci, pc, in, out) -> {
            String type = ci.getCellType().getName();
            if (design != null && params.hasInputBuffers() && pc != null && pc.getSiteInst() != null
                    && (type.equals("INBUF") || type.equals("DIFFINBUF"))) {
                float[] d = getInputBufferDelay(design, params, pc, in);
                if (d != null) {
                    return d;
                }
            }
            if (fl != null && pc != null && pc.getBEL() != null && pc.getSiteInst() != null) {
                String site = pc.getSiteInst().getSiteTypeEnum().name();
                FittedCellArcLibrary.Role role = FittedCellArcLibrary.Role.COMB;
                FittedCellArcLibrary.Result r = lookupArc(fl, ci, pc, site, role, in, out);
                if (r == null) {
                    // A clock from a register output (e.g. a divider flip-flop): its clock-to-Q arc
                    role = FittedCellArcLibrary.Role.CLK_TO_Q;
                    r = lookupArc(fl, ci, pc, site, role, in, out);
                }
                if (r != null) {
                    float[] d = new float[ClockDelayParams.NUM_CORNERS];
                    String category = FittedCellArcLibrary.getCategory(type, site);
                    for (Corner c : Corner.values()) {
                        int idx = FittedCellArcLibrary.getDataIndex(c);
                        d[c.ordinal()] = fl.scale(r.getDelays()[idx], dev, sg, type, r.getArc(), category, role, idx);
                    }
                    return d;
                }
            }
            return params.getBufferDelay(type, in, out);
        };
    }

    /**
     * Gets the pad to output delay of a placed input buffer (INBUF, DIFFINBUF) from the parameters' input
     * buffers: {@code base[<HPIOB|HDIOB>:INBUF[:<IOSTANDARD>]]} (or {@code HPIOBDIFFINBUF:DIFFINBUF}) plus the
     * package delay of the package pin of the port driving the input pin ({@link PackagePin#getMaxDelay()} at
     * the max corners, {@link PackagePin#getMinDelay()} at the min corners).
     * @return The delays per corner ordinal, null if the parameters have none for the buffer.
     */
    static float[] getInputBufferDelay(Design design, ClockDelayParams params, Cell pc, String inPin) {
        String site = pc.getSiteInst().getSiteTypeEnum().name().replaceAll("_(M|S|SNGL)$", "");
        String type = pc.getType();
        float[] base = null;
        if (type.equals("INBUF")) {
            String std = getIoStandard(pc);
            if (std != null) {
                base = params.getInputBuffer(site + ":INBUF:" + std);
            }
            if (base == null) {
                base = params.getInputBuffer(site + ":INBUF");
            }
        } else {
            base = params.getInputBuffer(site + ":DIFFINBUF");
        }
        if (base == null) {
            return null;
        }
        float[] d = base.clone();
        PackagePin pp = getPortPackagePin(design, pc, inPin);
        if (pp != null) {
            for (Corner c : Corner.values()) {
                Integer fs = isLate(c) ? pp.getMaxDelay() : pp.getMinDelay();
                if (fs != null) {
                    d[c.ordinal()] += fs / 1000f;
                }
            }
        }
        return d;
    }

    /** The IOSTANDARD of an input buffer cell (or of its I/O buffer macro), null if none */
    private static String getIoStandard(Cell pc) {
        EDIFHierCellInst hc = pc.getEDIFHierCellInst();
        for (EDIFHierCellInst c = hc; c != null && !c.isTopLevelInst(); c = c.getParent()) {
            EDIFPropertyValue v = c.getInst().getProperty("IOSTANDARD");
            if (v != null) {
                return v.getValue().replace("\"", "").trim();
            }
        }
        return null;
    }

    /** The package pin of the top-level port driving an input pin of a placed cell, null if none */
    private static PackagePin getPortPackagePin(Design design, Cell pc, String inPin) {
        EDIFHierCellInst hc = pc.getEDIFHierCellInst();
        if (hc == null || hc.getInst().getPortInst(inPin) == null) {
            return null;
        }
        EDIFHierNet hn = new EDIFHierPortInst(hc.getParent(), hc.getInst().getPortInst(inPin)).getHierarchicalNet();
        EDIFHierNet root = hn == null ? null : design.getNetlist().getParentNet(hn);
        if (root == null) {
            return null;
        }
        for (EDIFPortInst p : root.getNet().getPortInsts()) {
            if (!p.isTopLevelPort()) continue;
            Cell port = design.getCell(p.getName());
            if (port != null && port.getSite() != null && design.getDevice().getActivePackage() != null) {
                return design.getDevice().getActivePackage().getPackagePin(port.getSite());
            }
        }
        return null;
    }

    /**
     * Looks up an arc of a placed cell: by the physical BEL pins in slices (e.g. a LUT1's I0 on A6 to O6),
     * by the logical pin base names otherwise.
     * @return The arc's result, null if not found.
     */
    private static FittedCellArcLibrary.Result lookupArc(FittedCellArcLibrary fl, EDIFCellInst ci, Cell pc,
                                                         String site, FittedCellArcLibrary.Role role,
                                                         String in, String out) {
        String type = ci.getCellType().getName();
        FittedCellArcLibrary.Result r;
        if (site.equals("SLICEL") || site.equals("SLICEM")) {
            String from = pc.getPhysicalPinMapping(in);
            String to = pc.getPhysicalPinMapping(out);
            if (from == null || to == null) {
                return null;
            }
            r = fl.lookup(type, pc.getBELName(), site, role, from, -1, to, -1, null, busBase(in), busBase(out));
        } else {
            r = fl.lookup(type, pc.getBELName(), site, role, busBase(in), -1, busBase(out), -1, null, null, null);
        }
        return r.getArc() != null && r.getDelays() != null && r.getMismatch() < 4 ? r : null;
    }

    private static String busBase(String pin) {
        int b = pin.indexOf('[');
        return b < 0 ? pin : pin.substring(0, b);
    }

    /**
     * Builds the model of a design's clock network.
     * @param design The design (its placement and clock routing are used), or null.
     * @param cn The clock network.
     * @param nodeModel Times the clock route nodes.
     * @param bufferDelay Times the buffers.
     * @return The model.
     */
    static ClockModel build(Design design, ClockNetwork cn, ClockNodeDelayModel nodeModel,
                                   BufferDelay bufferDelay) {
        Builder b = new Builder(design, cn, nodeModel, bufferDelay);
        ClockModel m = b.build();
        m.interSlrCommonAtLcaStart = nodeModel.getParams().isInterSlrCommonAtLcaStart();
        return m;
    }

    /**
     * Sets where the common clock delay of the inter-SLR compensation ends, see
     * {@code ClockDelayParams.setInterSlrCommonAtLcaStart} (a model built from clock data
     * takes it from the data).
     * @param atLcaStart True for the start of the last common node, false for its end.
     */
    void setInterSlrCommonAtLcaStart(boolean atLcaStart) {
        interSlrCommonAtLcaStart = atLcaStart;
    }

    /**
     * @return Whether the insertion delays include the clocks' source latency (generator phase shifts),
     * which the timing analysis must then not add again (see {@link Clock#getSourceLatency()}).
     */
    public boolean includesSourceLatency() {
        return includesSourceLatency;
    }

    public int getNumClockPins() {
        return pinNode.length;
    }

    /**
     * Gets the number of nodes of the model's clock tree: the clock pins' nodes ({@link #getClockPinNode(int)},
     * {@link #getClockPinNode(int, int)}) and their ancestors up to the clock sources. Nodes have the ids 0 to this
     * number - 1, a node's parent having a lower id than the node.
     * @return The number of nodes.
     */
    public int getNumTreeNodes() {
        return tree.getNumNodes();
    }

    /**
     * Gets the parent of a node of the clock tree: the node before it on the clock path from its root (a clock
     * source).
     * @param node The node id, 0 to {@link #getNumTreeNodes()} - 1.
     * @return The parent's id, lower than the node's, or -1 for a root.
     * @throws IndexOutOfBoundsException If the node id is not that of a node of the tree.
     */
    public int getTreeParent(int node) {
        if (node < 0 || node >= tree.getNumNodes()) {
            throw new IndexOutOfBoundsException("Clock tree node " + node + " of " + tree.getNumNodes());
        }
        return tree.getParent(node);
    }

    /**
     * Gets the insertion delay of a node of the clock tree: the sum of the delays of the nodes from its root to it,
     * itself included. The insertion delay of a clock pin ({@link #getInsertion(int, Corner)}) is that of its node.
     * As the other getters of the model, safe to call from several threads once the model is built.
     * @param node The node id, 0 to {@link #getNumTreeNodes()} - 1.
     * @param corner The corner.
     * @return The insertion delay in ps; it includes the source latency of the clocks (e.g. generator phase
     * shifts) if {@link #includesSourceLatency()}.
     * @throws IndexOutOfBoundsException If the node id is not that of a node of the tree.
     */
    public float getTreeInsertion(int node, Corner corner) {
        return tree.getInsertion(node, corner);
    }

    /**
     * Gets the node of the clock tree whose insertion delays give the CRPR credit of two nodes: the setup credit is
     * its SLOW_MAX - SLOW_MIN insertion delay, the hold credit its FAST_MAX - FAST_MIN. That is the nodes' lowest
     * common ancestor or, on UltraScale+ when the clock paths branch at a routing node that is not the node of a
     * site pin both are on, its parent (the clock arrival at the start of the routing node).
     * @param launchNode The launch clock pin's node (e.g. {@link #getClockPinNode(int, int)}).
     * @param captureNode The capture clock pin's node.
     * @return The node id, or -1 if the nodes have no common ancestor (no credit).
     */
    public int getTreeCrprNode(int launchNode, int captureNode) {
        return launchNode < 0 || captureNode < 0 ? -1 : tree.getCreditNode(launchNode, captureNode);
    }

    /**
     * Gets the node of a clock pin in the clock tree, whose insertion delay ({@link #getTreeInsertion}) is the
     * pin's (see {@link #getInsertion(int, Corner)}).
     * @param clockPin The clock pin, 0 to {@link #getNumClockPins()} - 1.
     * @return The node id, or -1 if the pin has none (an insertion delay of 0).
     */
    public int getClockPinNode(int clockPin) {
        return pinNode[clockPin];
    }

    /**
     * Gets the node of a clock pin in the clock tree for a clock: when clocks reach the pin by several paths (the
     * inputs of a clock mux or generator), the end of the clock's own path (see
     * {@link #getInsertion(int, int, Corner)}), else the pin's node ({@link #getClockPinNode(int)}). In a model
     * built over a clock network, only the clocks of the pin's clock edges
     * ({@link ClockNetwork#getClockPinEdges(int)}: clock id = edge / 2) can have a node of their own; for any other
     * clock, the node is the pin's.
     * @param clockPin The clock pin, 0 to {@link #getNumClockPins()} - 1.
     * @param clock The clock id (see {@link Clock#getId()}), or -1 for the pin's node.
     * @return The node id, or -1 if none (an insertion delay of 0).
     */
    public int getClockPinNode(int clockPin, int clock) {
        return node(clockPin, clock);
    }

    /**
     * Gets where the common clock delay of the inter-SLR compensation ends (see
     * {@link #getInterSlrCompensation(int, int, Corner, float)}): at the start of the last common node of the two
     * clock paths, i.e. at its parent's insertion delay, or at its end (its own insertion delay). A model built from
     * clock data takes it from the data.
     * @return True for the start of the last common node, false for its end.
     */
    public boolean isInterSlrCommonAtLcaStart() {
        return interSlrCommonAtLcaStart;
    }

    /** @return Whether the insertion delay of a clock pin comes from its routing (not estimated). */
    public boolean isRouted(int clockPin) {
        return pinRouted[clockPin];
    }

    public int getNumRoutedPins() {
        return numRoutedPins;
    }

    public int getNumEstimatedPins() {
        return numEstimatedPins;
    }

    /**
     * @return The insertion delay of a clock pin at a corner, in ps; it includes the source latency of the clocks
     * (e.g. generator phase shifts) if {@link #includesSourceLatency()}.
     */
    public float getInsertion(int clockPin, Corner corner) {
        int n = pinNode[clockPin];
        return n < 0 ? 0f : tree.getInsertion(n, corner);
    }

    /** The clock pins' nodes of the clocks that reach them by another path than the pin's, by (pin, clock) */
    private final Map<Long, Integer> pinClockNode = new HashMap<>();

    /**
     * Sets the tree node of a clock pin for a clock that reaches it by another path than the pin's (e.g.
     * through another input of a clock mux), as {@link #build} does; e.g. for tests of hand-built trees.
     * @param clockPin The clock pin.
     * @param clock The clock.
     * @param node The tree node of the clock's path to the pin.
     */
    void setClockPathNode(int clockPin, int clock, int node) {
        pinClockNode.put(((long) clockPin << 32) | clock, node);
    }

    /** The tree node of a clock pin for a clock: that of the clock's path (e.g. through a clock mux input) */
    private int node(int clockPin, int clock) {
        if (clock >= 0 && !pinClockNode.isEmpty()) {
            Integer n = pinClockNode.get(((long) clockPin << 32) | clock);
            if (n != null) {
                return n;
            }
        }
        return pinNode[clockPin];
    }

    /**
     * Gets the insertion delay of a clock at a clock pin at a corner: through the clock's own path when
     * clocks reach the pin by several paths (the inputs of a clock mux or generator), see
     * {@link #getInsertion(int, Corner)}.
     * @param clockPin The clock pin.
     * @param clock The clock id.
     * @param corner The corner.
     * @return The insertion delay in ps.
     */
    public float getInsertion(int clockPin, int clock, Corner corner) {
        int n = node(clockPin, clock);
        return n < 0 ? 0f : tree.getInsertion(n, corner);
    }

    /** @return The setup CRPR credit between two clock pins for a clock (its own paths), in ps. */
    public float getSetupCrpr(int launchPin, int capturePin, int clock) {
        int a = node(launchPin, clock);
        int b = node(capturePin, clock);
        return a < 0 || b < 0 ? 0f : tree.getSetupCredit(a, b);
    }

    /** @return The hold CRPR credit between two clock pins for a clock (its own paths), in ps. */
    public float getHoldCrpr(int launchPin, int capturePin, int clock) {
        int a = node(launchPin, clock);
        int b = node(capturePin, clock);
        return a < 0 || b < 0 ? 0f : tree.getHoldCredit(a, b);
    }

    /**
     * Gets the arrival of a clock at a clock pin at a corner, relative to its edge at its source: the
     * insertion delay (none if the clock is ideal) plus the clock's latency (see
     * {@link #getClockLatency(Clock, boolean, boolean)}).
     * @param clockPin The clock pin.
     * @param clock A clock of the pin.
     * @param corner The corner.
     * @return The arrival in ps.
     */
    public float getClockArrival(int clockPin, Clock clock, Corner corner) {
        float latency = (float) getClockLatency(clock, isLate(corner), includesSourceLatency());
        return clock.isIdeal() ? latency : getInsertion(clockPin, clock.getId(), corner) + latency;
    }

    /**
     * Gets the latency that a clock adds to the insertion delays of its clock pins (as Vivado's): its user
     * source latency (set_clock_latency -source), plus its network latency if it is ideal (a set_clock_latency
     * without -source, which replaces the insertion delays of the clock), plus its own source latency (e.g. a
     * generator's phase shift) if the insertion delays do not include it.
     * @param clock The clock.
     * @param late True for the max corners' latency, false for the min corners'.
     * @param insertionIncludesSourceLatency Whether the insertion delays include the clock's own source
     *                                       latency (see {@link #includesSourceLatency()}).
     * @return The latency in ps.
     */
    public static double getClockLatency(Clock clock, boolean late, boolean insertionIncludesSourceLatency) {
        return (insertionIncludesSourceLatency ? 0 : clock.getSourceLatency()) + clock.getUserSourceLatency(late)
                + (clock.isIdeal() ? clock.getNetworkLatency(late) : 0);
    }

    /** @return Whether a corner takes the late (max) clock latencies: SLOW_MAX and FAST_MAX. */
    static boolean isLate(Corner corner) {
        return corner == Corner.SLOW_MAX || corner == Corner.FAST_MAX;
    }

    /**
     * Gets the inter-SLR compensation of a check whose launch and capture clock pins are in different
     * SLRs: {@code (DCD - CCD)*PF}, DCD being the capture clock pin's insertion delay and CCD the
     * clock arrival at the clock paths' last common node (their CRPR LCA), at a corner: the insertion
     * delay of the LCA, or, if the data say so (UltraScale+, see
     * {@code ClockDelayParams.setInterSlrCommonAtLcaStart}), that of its parent, i.e. the
     * arrival at the start of the LCA. The timing analysis subtracts it from the slack of setup and
     * hold checks. This is the form of the setup checks and of the fast-corner hold checks; Vivado's
     * slow-corner hold checks, which the analysis does not time, use {@code (SCD + DPD - CCD)*PF}.
     * @param launchPin The launch clock pin.
     * @param capturePin The capture clock pin.
     * @param corner The corner of the capture clock (SLOW_MIN for setup, FAST_MAX for hold).
     * @param prorate PF, see {@link ClockData#getInterSlrProrate()}.
     * @return The compensation in ps, 0 if both pins are in the same SLR (or unknown).
     */
    public float getInterSlrCompensation(int launchPin, int capturePin, Corner corner, float prorate) {
        return getInterSlrCompensation(launchPin, capturePin, corner, prorate, false);
    }

    /**
     * Gets the inter-SLR compensation of a check whose launch and capture clock pins are in different
     * SLRs, or whose data path crosses an SLR boundary (Vivado compensates the checks of such paths
     * even when both clock pins are in the same SLR), see
     * {@link #getInterSlrCompensation(int, int, Corner, float)}.
     * @param dataCrossesSlr True if the data path of the check crosses an SLR boundary.
     * @return The compensation in ps, 0 if the path does not cross SLRs.
     */
    public float getInterSlrCompensation(int launchPin, int capturePin, Corner corner, float prorate,
                                         boolean dataCrossesSlr) {
        return getInterSlrCompensation(launchPin, capturePin, corner, prorate, dataCrossesSlr, -1);
    }

    /**
     * Gets the inter-SLR compensation of a check of a clock, through the clock's own paths to the clock pins
     * (e.g. through an input of a clock mux), see {@link #getInterSlrCompensation(int, int, Corner, float, boolean)}.
     * @param clock The clock id, -1 for the clock pins' own paths.
     * @return The compensation in ps, 0 if the path does not cross SLRs.
     */
    public float getInterSlrCompensation(int launchPin, int capturePin, Corner corner, float prorate,
                                         boolean dataCrossesSlr, int clock) {
        if (!crossesSlrs(launchPin, capturePin, prorate, dataCrossesSlr)) {
            return 0f;
        }
        int b = node(capturePin, clock);
        if (b < 0) {
            return 0f;
        }
        int common = commonNode(node(launchPin, clock), b);
        float dcd = tree.getInsertion(b, corner);
        float ccd = common < 0 ? 0f : tree.getInsertion(common, corner);
        return (dcd - ccd) * prorate;
    }

    /** Whether a check gets the inter-SLR compensation: clock pins in different SLRs, or a data path crossing */
    private boolean crossesSlrs(int launchPin, int capturePin, float prorate, boolean dataCrossesSlr) {
        if (prorate == 0f) {
            return false;
        }
        return dataCrossesSlr || pinSlr[launchPin] >= 0 && pinSlr[capturePin] >= 0
                && pinSlr[launchPin] != pinSlr[capturePin];
    }

    /** The node whose insertion is the common clock delay of two clock pin nodes, -1 if none */
    private int commonNode(int a, int b) {
        int lca = a < 0 || b < 0 ? -1 : tree.getLca(a, b);
        return lca < 0 || !interSlrCommonAtLcaStart ? lca : tree.getParent(lca);
    }

    /**
     * Gets the inter-SLR compensation of a slow-corner hold check, (SCD + data path - CCD)*PF, i.e. of its
     * arrival less the common clock delay, under the same conditions as
     * {@link #getInterSlrCompensation(int, int, Corner, float, boolean)}.
     * @param launchPin The launch clock pin.
     * @param capturePin The capture clock pin.
     * @param arrival The arrival time of the check's data at its endpoint (launch insertion plus data path).
     * @param corner The corner of the common clock delay (that of the launch, SLOW_MIN).
     * @param prorate The inter-SLR compensation factor.
     * @param dataCrossesSlr Whether the data path crosses an SLR boundary.
     * @return The compensation in ps, 0 if the path does not cross SLRs.
     */
    public float getInterSlrCompensationOfArrival(int launchPin, int capturePin, float arrival, Corner corner,
                                                  float prorate, boolean dataCrossesSlr) {
        return getInterSlrCompensationOfArrival(launchPin, capturePin, arrival, corner, prorate, dataCrossesSlr, -1);
    }

    /**
     * Gets the inter-SLR compensation of a slow-corner hold check of a clock, through the clock's own paths to the
     * clock pins, see {@link #getInterSlrCompensationOfArrival(int, int, float, Corner, float, boolean)}.
     * @param clock The clock id, -1 for the clock pins' own paths.
     * @return The compensation in ps, 0 if the path does not cross SLRs.
     */
    public float getInterSlrCompensationOfArrival(int launchPin, int capturePin, float arrival, Corner corner,
                                                  float prorate, boolean dataCrossesSlr, int clock) {
        if (!crossesSlrs(launchPin, capturePin, prorate, dataCrossesSlr)) {
            return 0f;
        }
        int common = commonNode(node(launchPin, clock), node(capturePin, clock));
        float ccd = common < 0 ? 0f : tree.getInsertion(common, corner);
        return (arrival - ccd) * prorate;
    }

    /** @return The SLR index of a clock pin's cell, -1 if unknown. */
    public int getSlr(int clockPin) {
        return pinSlr[clockPin];
    }

    /** @return The setup CRPR credit between a launch and a capture clock pin (their own paths), in ps. */
    public float getSetupCrpr(int launchPin, int capturePin) {
        return getSetupCrpr(launchPin, capturePin, -1);
    }

    /** @return The hold CRPR credit between a launch and a capture clock pin (their own paths), in ps. */
    public float getHoldCrpr(int launchPin, int capturePin) {
        return getHoldCrpr(launchPin, capturePin, -1);
    }

    /**
     * @return The warnings of the model's build with their number of occurrences, see
     * {@link ClockNetwork#printWarnings(String, Map, boolean)}.
     */
    public Map<String, Integer> getWarnings() {
        return Collections.unmodifiableMap(warnings);
    }

    /**
     * Gets the tap of the CLE clock modifier (Versal programmable clock delay) of a site: the value of
     * FF_CLK_MOD.CLK_DLY_VAL (e.g. 4'h6) if FF_CLK_MOD.IMUX_CLK_MODE is DELAY.
     * @param design The design.
     * @param site The site.
     * @return The tap, -1 if the modifier is off or unknown.
     */
    public static int getClockModifierTap(Design design, Site site) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        SiteConfig sc = attrs == null ? null : attrs.get(site);
        if (sc == null || sc.getBELAttributes() == null) {
            return -1;
        }
        String mode = null;
        String tap = null;
        for (Map.Entry<BEL, Map<String, BELAttr>> e : sc.getBELAttributes().entrySet()) {
            String bel = e.getKey() == null ? "" : e.getKey().getName();
            for (Map.Entry<String, BELAttr> a : e.getValue().entrySet()) {
                String name = a.getKey();
                String full = name.contains(".") ? name : bel + "." + name;
                if (!full.contains("FF_CLK_MOD")) continue;
                if (full.endsWith("IMUX_CLK_MODE")) {
                    mode = a.getValue().getValue();
                } else if (full.endsWith("CLK_DLY_VAL")) {
                    tap = a.getValue().getValue();
                }
            }
        }
        if (mode == null || !mode.equalsIgnoreCase("DELAY") || tap == null) {
            return -1;
        }
        return (int) ClockNetwork.parseNumber(tap, -1);
    }

    /**
     * Gets the programmable clock delay tap of a site: the first of the keys ({@code <BEL>.<attribute>}, the
     * attribute's digits written N) that the site's BEL attributes have.
     * @return {key index, tap}, or an empty array if none (or tap 0).
     */
    static int[] getSiteTap(Design design, Site site, List<String> keys) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        SiteConfig sc = attrs == null ? null : attrs.get(site);
        if (sc == null || sc.getBELAttributes() == null) {
            return new int[0];
        }
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<BEL, Map<String, BELAttr>> e : sc.getBELAttributes().entrySet()) {
            String bel = e.getKey() == null ? "" : e.getKey().getName();
            for (Map.Entry<String, BELAttr> a : e.getValue().entrySet()) {
                String name = a.getKey();
                String full = (name.contains(".") ? name : bel + "." + name).replaceAll("[0-9]", "N");
                values.put(full, a.getValue().getValue());
            }
        }
        for (int k = 0; k < keys.size(); k++) {
            String v = values.get(keys.get(k));
            if (v != null) {
                int tap = (int) ClockNetwork.parseNumber(v, 0);
                return tap > 0 ? new int[]{k, tap} : new int[0];
            }
        }
        return new int[0];
    }

    /**
     * Gets the opposite min/max corner of the same speed: SLOW_MAX and SLOW_MIN, FAST_MIN and FAST_MAX.
     * @param corner A corner ordinal.
     * @return The opposite corner's ordinal.
     */
    static int oppositeCorner(int corner) {
        switch (Corner.values()[corner]) {
            case SLOW_MAX:
                return Corner.SLOW_MIN.ordinal();
            case SLOW_MIN:
                return Corner.SLOW_MAX.ordinal();
            case FAST_MIN:
                return Corner.FAST_MAX.ordinal();
            default:
                return Corner.FAST_MIN.ordinal();
        }
    }

    private static final java.util.regex.Pattern ROOT_REGION = java.util.regex.Pattern.compile("X(\\d+)Y(\\d+)");

    /** Builds a model over the stages of a clock network */
    private static final class Builder {
        private final Design design;
        private final ClockNetwork cn;
        private final ClockNodeDelayModel nodeModel;
        private final ClockDelayParams params;
        private final BufferDelay bufferDelay;
        private final ClockTree tree = new ClockTree();
        private final Map<String, Integer> leafByPin = new HashMap<>();
        /** The leaves of the pins by "pin|stage", when clocks reach a pin by several paths */
        private final Map<String, Integer> leafByPinStage = new HashMap<>();
        private final Map<String, Boolean> leafRouted = new HashMap<>();
        private final int[] stageRoot;
        /** The node common to the outputs of a generator below an input pin node, by cell and parent */
        private final Map<String, Integer> generatorNode = new HashMap<>();
        private final int[] stageGenerator;
        /** The CLKFBOUT stage of each generator node (a generator's input path) */
        private final Map<Integer, Integer> feedbackStage = new HashMap<>();
        /** The leaves of the CLKFBIN pins of the generators, by cell name */
        private final Map<String, List<Integer>> feedbackInputs = new HashMap<>();
        /** The leaves of the digital deskew inputs of the generators (CLKIN1_DESKEW, ...), by pin name */
        private final Map<String, Integer> deskewInputs = new HashMap<>();
        private final Map<String, Integer> warnings = new LinkedHashMap<>();
        private final float[] scratch = new float[ClockDelayParams.NUM_CORNERS];
        /** By node class index: whether the CRPR credits a routing node of the class at its start */
        private final boolean[] crprStartClass;

        Builder(Design design, ClockNetwork cn, ClockNodeDelayModel nodeModel, BufferDelay bufferDelay) {
            this.design = design;
            this.cn = cn;
            this.nodeModel = nodeModel;
            this.params = nodeModel.getParams();
            this.bufferDelay = bufferDelay;
            this.stageRoot = new int[cn.getStages().size()];
            this.stageGenerator = new int[stageRoot.length];
            Arrays.fill(stageGenerator, ClockTree.NO_PARENT);
            List<String> classes = params.getClassNames();
            Set<String> startClasses = params.getCrprBranchStartClasses();
            this.crprStartClass = new boolean[startClasses.isEmpty() ? 0 : classes.size()];
            for (int i = 0; i < crprStartClass.length; i++) {
                crprStartClass[i] = startClasses.contains(classes.get(i));
            }
        }

        private void warn(String kind) {
            warnings.merge(kind, 1, Integer::sum);
        }

        ClockModel build() {
            List<ClockNetwork.Stage> stages = cn.getStages();
            Map<String, Integer> stageOfDriver = new HashMap<>();
            for (ClockNetwork.Stage st : stages) {
                int parent = ClockTree.NO_PARENT;
                if (st.getParentPin() != null) {
                    // The parent pin's leaf on the parent stage's path (a clock mux input has one per path)
                    Integer pn = leafByPinStage.get(st.getParentPin().toString() + "|" + st.getParentStage());
                    if (pn == null) {
                        pn = leafByPin.get(st.getParentPin().toString());
                    }
                    if (pn != null) {
                        parent = pn;
                    }
                }
                if (isGeneratorStage(st) && parent != ClockTree.NO_PARENT) {
                    String key = st.getDriver().getFullHierarchicalInstName() + "|" + parent;
                    Integer g = generatorNode.get(key);
                    if (g == null) {
                        g = tree.addNode(parent, null);
                        generatorNode.put(key, g);
                    }
                    parent = g;
                    stageGenerator[st.getId()] = g;
                    if (st.getDriver().getPortInst().getName().equals("CLKFBOUT")) {
                        feedbackStage.putIfAbsent(g, st.getId());
                    }
                }
                int root = tree.addNode(parent, rootDelay(st));
                stageRoot[st.getId()] = root;
                if (st.getDriver() != null) {
                    stageOfDriver.put(st.getDriver().toString(), st.getId());
                }
                buildStage(st, root);
            }
            compensateGenerators(stages, stageOfDriver);
            deskewGenerators(stages);
            tree.finish();
            int n = cn.getNumClockPins();
            int[] pinNode = new int[n];
            boolean[] routed = new boolean[n];
            ClockModel m = new ClockModel(tree, pinNode, routed);
            for (int i = 0; i < n; i++) {
                String name = cn.getClockPin(i).toString();
                Integer node = leafByPin.get(name);
                pinNode[i] = node == null ? -1 : node;
                routed[i] = Boolean.TRUE.equals(leafRouted.get(name));
                Cell pc = physCell(cn.getClockPin(i));
                if (pc != null && pc.getTile() != null) {
                    m.pinSlr[i] = pc.getTile().getSLRIndex();
                }
                if (routed[i]) {
                    m.numRoutedPins++;
                } else {
                    m.numEstimatedPins++;
                }
                // The clocks reaching the pin by another path (e.g. another input of a clock mux)
                for (int e : cn.getClockPinEdges(i)) {
                    Integer other = leafByPinStage.get(name + "|" + cn.getClockPinStage(i, e >> 1));
                    if (other != null && other != pinNode[i]) {
                        m.pinClockNode.put(((long) i << 32) | (e >> 1), other);
                    }
                }
            }
            for (String u : nodeModel.getUnknownClasses()) {
                warn("clock node class without data (0 ps): " + u);
            }
            m.warnings.putAll(warnings);
            m.includesSourceLatency = true;
            return m;
        }

        private Cell physCell(EDIFHierPortInst p) {
            if (design == null) {
                return null;
            }
            return design.getCell(p.getFullHierarchicalInstName());
        }

        /**
         * Whether a stage is the output of a clock generator (MMCM/PLL/...): an auto-derived clock's, or a
         * user generated clock's on a generator output pin (whose delay is the generator's compensated one)
         */
        private boolean isGeneratorStage(ClockNetwork.Stage st) {
            if (st.getKind() == ClockNetwork.StageKind.GENERATOR) {
                return true;
            }
            EDIFHierPortInst drv = st.getDriver();
            if (st.getKind() != ClockNetwork.StageKind.USER_PIN || drv == null) {
                return false;
            }
            String pin = drv.getPortInst().getName();
            return ClockNetwork.isGenerator(drv.getPortInst().getCellInst().getCellType().getName())
                    && (pin.startsWith("CLKOUT") || pin.startsWith("CLKFBOUT"));
        }

        private float[] rootDelay(ClockNetwork.Stage st) {
            if (isGeneratorStage(st)) {
                // Compensated afterwards
                return null;
            }
            switch (st.getKind()) {
                case BUFFER:
                case DIVIDER:
                case USER_PIN: {
                    EDIFHierPortInst in = st.getParentPin();
                    EDIFHierPortInst out = st.getDriver();
                    if (in == null || out == null) {
                        return null;
                    }
                    EDIFCellInst ci = out.getPortInst().getCellInst();
                    float[] d = bufferDelay.getDelay(ci, physCell(out), in.getPortInst().getName(),
                            out.getPortInst().getName());
                    if (d == null) {
                        warn("no delay for clock cell type " + ci.getCellType().getName() + " (0 ps)");
                    }
                    return d;
                }
                default:
                    // Ports have no delay; generators are compensated afterwards
                    return null;
            }
        }

        /** The physical net of a stage, null if none */
        private Net physNet(ClockNetwork.Stage st) {
            if (design == null) {
                return null;
            }
            EDIFHierNet hn = st.getNet();
            EDIFHierNet parent = design.getNetlist().getParentNet(hn);
            Net net = design.getNet((parent != null ? parent : hn).getHierarchicalNetName());
            return net;
        }

        private void buildStage(ClockNetwork.Stage st, int root) {
            Net net = physNet(st);
            ClockRoute route = net != null && net.hasPIPs() ? new ClockRoute(net, nodeModel, design) : null;
            // The tree node of each route node
            Map<Node, Integer> nodeIdx = new HashMap<>();
            Map<ClockRegion, Integer> regionIdx = new HashMap<>();
            int[] centroid = null;
            SitePinInst source = net == null ? null : net.getSource();
            // An intra-site stage (e.g. a Versal DSP58's clock source mux to its other parts) has no route
            Cell driverCell = st.getDriver() == null || route != null ? null : physCell(st.getDriver());
            Site driverSite = driverCell == null ? null : driverCell.getSite();
            for (EDIFHierPortInst sink : st.getSinks()) {
                String pinName = sink.toString();
                if (leafByPinStage.containsKey(pinName + "|" + st.getId())) continue;
                boolean clockPin = cn.getClockPinIndex(pinName) >= 0;
                Cell pc = physCell(sink);
                int attach = root;
                boolean routed = false;
                float[] leaf = null;
                SitePinInst spi = null;
                if (design != null && net != null) {
                    try {
                        spi = sink.getRoutedSitePinInst(design);
                    } catch (RuntimeException e) {
                        spi = null;
                    }
                }
                if (spi != null && source != null && params.hasDedicatedRoutes()) {
                    // A dedicated clock input connection: its route delay as a whole (or 0), plus the leaf
                    leaf = dedicatedLeaf(source, spi, sink, pc, clockPin);
                    routed = leaf != null;
                }
                if (!routed && route != null && spi != null) {
                    Node n = spi.getConnectedNode();
                    if (n != null && (route.isEntered(n) || nodeIdx.containsKey(n))) {
                        attach = routeNode(n, route, nodeIdx, root);
                        routed = true;
                        leaf = routedLeaf(n, route, spi, sink, pc, clockPin);
                    }
                }
                if (!routed && driverSite != null && pc != null && pc.getSite() == driverSite) {
                    leaf = new float[ClockDelayParams.NUM_CORNERS];
                    routed = true;
                }
                if (!routed && st.getKind() == ClockNetwork.StageKind.PORT) {
                    // The port to pad net of an input buffer, or an unrouted port to clock buffer connection
                    // (out-of-context designs)
                    String type = sink.getCellType().getName();
                    float[] d = params.getInputBufferPortNet(type);
                    if (d == null && design != null && type.startsWith("BUFG") && spi == null) {
                        d = params.getUnroutedInput();
                    }
                    if (d != null) {
                        leaf = d.clone();
                    }
                }
                if (!routed && leaf == null && clockPin && pc != null && st.getKind() == ClockNetwork.StageKind.PORT) {
                    // A clock pin of a top-level port without a clock buffer, off the port's routing (an
                    // out-of-context design's clock): its leaf delay alone (e.g. about 30 ps on a flip-flop, 75 ps
                    // on a block RAM), which is what Vivado gives it when the port's net has no routing
                    leaf = unroutedPortLeaf(net, sink, pc, clockPin);
                }
                if (!routed && clockPin && pc != null && pc.getTile() != null
                        && st.getKind() != ClockNetwork.StageKind.PORT) {
                    // Estimate from the clock root (root property, else the load-median clock region). The
                    // clock pins of a top-level port without a clock buffer or routing are not (see above)
                    if (centroid == null) {
                        centroid = clockRoot(st);
                    }
                    ClockRegion cr = pc.getTile().getClockRegion();
                    if (cr != null && centroid != null) {
                        Integer rn = regionIdx.get(cr);
                        if (rn == null) {
                            float[] d = new float[ClockDelayParams.NUM_CORNERS];
                            int dSlr = slrOf(cr) - centroid[2];
                            for (int c = 0; c < d.length; c++) {
                                d[c] = params.getEstimateDelay(c, cr.getColumn() - centroid[0],
                                        cr.getRow() - centroid[1], dSlr, centroid[3], centroid[4] != 0);
                            }
                            rn = tree.addNode(root, d);
                            regionIdx.put(cr, rn);
                        }
                        attach = rn;
                        leaf = new float[ClockDelayParams.NUM_CORNERS];
                        for (int c = 0; c < leaf.length; c++) {
                            // (The estimate's constant term includes the leaf delay)
                            leaf[c] = clockModifierDelay(pc, c);
                        }
                    }
                }
                int leafNode = tree.addNode(attach, leaf);
                if (sink.getPortInst().getName().equals("CLKFBIN")
                        && ClockNetwork.isGenerator(sink.getCellType().getName())) {
                    feedbackInputs.computeIfAbsent(sink.getFullHierarchicalInstName(), k -> new ArrayList<>())
                            .add(leafNode);
                }
                if (ClockNetwork.isGenerator(sink.getCellType().getName())
                        && ClockNetwork.isDeskewPin(sink.getPortInst().getName())) {
                    deskewInputs.putIfAbsent(pinName, leafNode);
                }
                leafByPin.putIfAbsent(pinName, leafNode);
                leafByPinStage.put(pinName + "|" + st.getId(), leafNode);
                leafRouted.putIfAbsent(pinName, routed);
            }
        }

        /**
         * The delay from a load's site pin node to its cell pin: the site pin node's tap terms to the
         * load site, the leaf configuration and, on the flip-flops of a CLE with a programmable clock
         * delay, the clock modifier.
         */
        private float[] routedLeaf(Node sitePinNode, ClockRoute route, SitePinInst spi, EDIFHierPortInst sink,
                                   Cell pc, boolean clockPin) {
            float[] leaf = new float[ClockDelayParams.NUM_CORNERS];
            route.getLeaveToSite(sitePinNode, spi, leaf);
            // The key of the placed site type (e.g. RAMB36 in a RAMBFIFO36 site), else of the site's type
            String key = leafKey(spi, sink);
            float[] delta = loadPinDelta(spi);
            for (int c = 0; c < leaf.length; c++) {
                leaf[c] += params.getLeafDelay(key, c) + (clockPin ? clockModifierDelay(pc, c) : 0f)
                        + (delta != null ? delta[c] : 0f);
            }
            return leaf;
        }

        /**
         * The delay of a dedicated clock input connection from its source site pin to a load cell pin:
         * 0 for a zero-delay connection, else the dedicated route's delay plus the leaf configuration and
         * clock modifier; null if the connection is not dedicated.
         */
        private float[] dedicatedLeaf(SitePinInst source, SitePinInst spi, EDIFHierPortInst sink, Cell pc,
                                      boolean clockPin) {
            String[] srcTypes = siteTypes(source);
            String[] loadTypes = siteTypes(spi);
            for (String st : srcTypes) {
                for (String lt : loadTypes) {
                    if (params.isZeroDelayConnection(st, source.getName(), lt)) {
                        return new float[ClockDelayParams.NUM_CORNERS];
                    }
                }
            }
            String device = design.getDevice().getName();
            for (String st : srcTypes) {
                for (String lt : loadTypes) {
                    String key = st + "." + source.getName() + "->" + lt + "." + spi.getName();
                    float[] d = params.getDeviceDedicatedRoute(device, key + "@" + spi.getSite().getName());
                    if (d == null) {
                        d = params.getDedicatedRoute(key);
                    }
                    if (d != null) {
                        String leaf = leafKey(spi, sink);
                        for (int c = 0; c < d.length; c++) {
                            d[c] += params.getLeafDelay(leaf, c) + (clockPin ? clockModifierDelay(pc, c) : 0f);
                        }
                        return d;
                    }
                }
            }
            return null;
        }

        /** The placed site type of a site pin's site, and the site's type if different */
        private String[] siteTypes(SitePinInst spi) {
            String placed = spi.getSiteInst().getSiteTypeEnum().name();
            String own = spi.getSite().getSiteTypeEnum().name();
            return placed.equals(own) ? new String[]{placed} : new String[]{placed, own};
        }

        /** The leaf configuration key of a load: the placed site type's if known, else the site's type's */
        private String leafKey(SitePinInst spi, EDIFHierPortInst sink) {
            return leafKey(spi.getSiteInst(), spi.getName(), sink);
        }

        /** The leaf configuration key of a load on a site pin of a site instance (see the above) */
        private String leafKey(SiteInst si, String sitePin, EDIFHierPortInst sink) {
            String suffix = "." + sitePin + "|" + sink.getPortInst().getName();
            String key = si.getSiteTypeEnum() + suffix;
            return params.hasLeafConfig(key) ? key : si.getSite().getSiteTypeEnum() + suffix;
        }

        /**
         * The delay of a clock pin that a top-level port drives without a clock buffer or routing: the leaf
         * configuration of the site pin that the site routing of its net reaches the cell pin from, and the clock
         * modifier; null if the site pin is unknown.
         */
        private float[] unroutedPortLeaf(Net net, EDIFHierPortInst sink, Cell pc, boolean clockPin) {
            if (net == null || pc.getSiteInst() == null) {
                return null;
            }
            String sitePin;
            try {
                sitePin = DesignTools.getRoutedSitePin(pc, net, sink.getPortInst().getName());
            } catch (RuntimeException e) {
                sitePin = null;
            }
            if (sitePin == null) {
                return null;
            }
            String key = leafKey(pc.getSiteInst(), sitePin, sink);
            float[] leaf = new float[ClockDelayParams.NUM_CORNERS];
            for (int c = 0; c < leaf.length; c++) {
                leaf[c] = params.getLeafDelay(key, c) + (clockPin ? clockModifierDelay(pc, c) : 0f);
            }
            return leaf;
        }

        /** The route correction of a load site pin (placed site type, else the site's type), null if none */
        private float[] loadPinDelta(SitePinInst spi) {
            float[] d = params.getLoadPinDelta(spi.getSiteInst().getSiteTypeEnum() + "." + spi.getName());
            return d != null ? d : params.getLoadPinDelta(spi.getSite().getSiteTypeEnum() + "." + spi.getName());
        }

        /** Sites whose CLE clock modifier is on, with their tap (-1 when off), cached */
        private final Map<Site, Integer> modifierTaps = new HashMap<>();

        /**
         * The delay of the CLE clock modifier for a cell: when its site's FF_CLK_MOD.IMUX_CLK_MODE is
         * DELAY and the cell is on a flip-flop BEL (*FF, *FF2) or a LUT BEL (a LUT RAM or shift register),
         * the delay of tap FF_CLK_MOD.CLK_DLY_VAL in sites of its type.
         */
        private float clockModifierDelay(Cell pc, int corner) {
            if (pc == null || !params.hasClockModifier() || design == null || pc.getSite() == null) {
                return 0f;
            }
            // Every clocked cell of the site: its flip-flops and its LUT RAMs and shift registers
            String bel = pc.getBELName();
            if (bel == null || !(bel.endsWith("FF") || bel.endsWith("FF2") || bel.endsWith("LUT"))) {
                return 0f;
            }
            Site site = pc.getSite();
            Integer tap = modifierTaps.get(site);
            if (tap == null) {
                tap = getClockModifierTap(design, site);
                modifierTaps.put(site, tap);
            }
            return tap < 0 ? 0f : params.getClockModifierDelay(site.getSiteTypeEnum().name(), corner, tap);
        }

        /** Gets (creating it and its ancestors if need be) the tree node of a routing node. */
        private int routeNode(Node n, ClockRoute route, Map<Node, Integer> nodeIdx, int root) {
            Integer existing = nodeIdx.get(n);
            if (existing != null) {
                return existing;
            }
            List<Node> chain = new ArrayList<>();
            Set<Node> onChain = new HashSet<>();
            Node cur = n;
            int parent = root;
            while (cur != null) {
                Integer idx = nodeIdx.get(cur);
                if (idx != null) {
                    parent = idx;
                    break;
                }
                if (!onChain.add(cur)) {
                    // A loop of PIPs (invalid routing): the chain starts below the stage's root
                    warn("clock net routing with a loop of PIPs: " + cur);
                    break;
                }
                chain.add(cur);
                cur = route.getDriver(cur);
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                Node node = chain.get(i);
                route.getNodeDelays(node, scratch);
                parent = tree.addNode(parent, scratch);
                nodeIdx.put(node, parent);
                if (crprStartClass.length > 0) {
                    int cls = nodeModel.getClassIndex(node);
                    if (cls >= 0 && cls < crprStartClass.length && crprStartClass[cls]) {
                        tree.setCreditAtStart(parent);
                    }
                }
            }
            return parent;
        }

        private static int slrOf(ClockRegion cr) {
            Tile t = cr.getApproximateCenter();
            return t == null ? 0 : t.getSLRIndex();
        }

        /**
         * The clock root of a stage: {column, row, SLR, clock regions from the driver to the root, 1 if the
         * load-median root else 0}; the root is the net's CLOCK_ROOT / USER_CLOCK_ROOT property (e.g. X2Y3),
         * else the clock region of the median column and row of the stage's placed loads; null if no load is
         * placed.
         */
        private int[] clockRoot(ClockNetwork.Stage st) {
            List<Integer> cols = new ArrayList<>();
            List<Integer> rows = new ArrayList<>();
            ClockRegion any = null;
            for (EDIFHierPortInst sink : st.getSinks()) {
                Cell pc = physCell(sink);
                if (pc == null || pc.getTile() == null) continue;
                ClockRegion cr = pc.getTile().getClockRegion();
                if (cr == null) continue;
                cols.add(cr.getColumn());
                rows.add(cr.getRow());
                any = cr;
            }
            if (any == null) {
                return null;
            }
            int[] prop = rootProperty(st);
            boolean median = prop == null;
            int cx;
            int cy;
            if (median) {
                Collections.sort(cols);
                Collections.sort(rows);
                cx = cols.get(cols.size() / 2);
                cy = rows.get(rows.size() / 2);
            } else {
                cx = prop[0];
                cy = prop[1];
            }
            ClockRegion root = any.getDevice().getClockRegion(cy, cx);
            int slr = root != null ? slrOf(root) : slrOf(any);
            int bufToRoot = 0;
            Cell drv = st.getDriver() == null ? null : physCell(st.getDriver());
            if (drv != null && drv.getTile() != null && drv.getTile().getClockRegion() != null) {
                ClockRegion b = drv.getTile().getClockRegion();
                bufToRoot = Math.abs(b.getColumn() - cx) + Math.abs(b.getRow() - cy);
            }
            return new int[]{cx, cy, slr, bufToRoot, median ? 1 : 0};
        }

        /** The clock root {column, row} of a stage's net property CLOCK_ROOT / USER_CLOCK_ROOT, null if none */
        private int[] rootProperty(ClockNetwork.Stage st) {
            EDIFHierNet hn = st.getNet();
            if (design != null) {
                EDIFHierNet parent = design.getNetlist().getParentNet(hn);
                if (parent != null) {
                    hn = parent;
                }
            }
            for (String key : new String[]{"CLOCK_ROOT", "USER_CLOCK_ROOT"}) {
                EDIFPropertyValue v = hn.getNet().getProperty(key);
                if (v == null) continue;
                java.util.regex.Matcher m = ROOT_REGION.matcher(v.getValue());
                if (m.find()) {
                    return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
                }
            }
            return null;
        }

        /**
         * Sets the delays of the generators, {@code c[kind:COMPENSATION][C] - F[opp(C)]} on their common
         * node (else on the output stage's root), and of their output stages, {@code PHASE/360*T(CLKOUT)}:
         * F being the feedback insertion (from the CLKFBOUT stage's root down to the CLKFBIN pin, leaf
         * included) at the opposite min/max corner of the same speed, and 0 for INTERNAL (and NONE)
         * compensation. A generator with clocks on two inputs (CLKIN1 and CLKIN2) has a common node, a
         * feedback path and output stages per input.
         */
        private void compensateGenerators(List<ClockNetwork.Stage> stages, Map<String, Integer> stageOfDriver) {
            Map<String, float[]> fbCache = new HashMap<>();
            for (ClockNetwork.Stage st : stages) {
                if (!isGeneratorStage(st)) continue;
                EDIFHierPortInst drv = st.getDriver();
                EDIFCellInst ci = drv.getPortInst().getCellInst();
                String cellName = drv.getFullHierarchicalInstName();
                String type = ci.getCellType().getName();
                String comp = resolveCompensation(drv.getHierarchicalInst().getChild(ci), type,
                        ClockNetwork.getString(ci, "COMPENSATION"), st.getParentPin());
                boolean internal = comp.equalsIgnoreCase("INTERNAL") || comp.equalsIgnoreCase("NONE");
                int common = stageGenerator[st.getId()];
                // The feedback path of the generator's input this output derives from
                String fbKey = cellName + "|" + common;
                float[] fb = fbCache.get(fbKey);
                if (fb == null) {
                    fb = new float[ClockDelayParams.NUM_CORNERS];
                    Integer fbStage = common == ClockTree.NO_PARENT ? null : feedbackStage.get(common);
                    if (fbStage == null) {
                        fbStage = stageOfDriver.get(cellName + "/CLKFBOUT");
                    }
                    List<Integer> fbIns = feedbackInputs.get(cellName);
                    if (!internal && fbStage != null && fbIns != null) {
                        float[] sum = feedbackInsertion(stageRoot[fbStage], fbIns);
                        if (sum != null) {
                            fb = sum;
                        } else {
                            warn("clock generator feedback path not found (no compensation): " + cellName);
                        }
                    } else if (!internal) {
                        warn("clock generator without a traced external feedback path (no compensation): " + cellName);
                    }
                    fbCache.put(fbKey, fb);
                }
                // The generator's delay (common to its outputs) and the output's own
                String outPin = drv.getPortInst().getName();
                String phyDivide = outPin.startsWith("CLKOUTPHY") ? ClockNetwork.getString(ci, "CLKOUTPHY_DIVIDE")
                        : null;
                if (!params.hasGeneratorDelay(type, comp)) {
                    warn("clock generator without compensation data: " + ClockDelayParams.getGeneratorKind(type) + ":"
                            + comp);
                }
                float[] gen = params.getGeneratorDelay(type, comp);
                float[] genOut = params.getGeneratorDelay(type, comp, outPin, phyDivide);
                float phase = (float) getPhaseShift(st);
                if (outPin.startsWith("CLKOUTPHY_") && params.hasGeneratorOutputDelay(type, comp, outPin, phyDivide)) {
                    // The fixed phase of an X5PLL CLKOUTPHY_90 is part of its output's compensation delay
                    phase = 0f;
                }
                int root = stageRoot[st.getId()];
                for (int c = 0; c < fb.length; c++) {
                    float g = gen[c] - fb[oppositeCorner(c)];
                    float own = genOut[c] - gen[c] + phase;
                    if (common == ClockTree.NO_PARENT) {
                        tree.setDelay(root, c, g + own);
                    } else {
                        tree.setDelay(common, c, g);
                        tree.setDelay(root, c, own);
                    }
                }
            }
        }

        /**
         * Sets the delays of the outputs of Versal XPLLs under the control of a phase detector of their digital deskew
         * (CLKOUTn_PHASE_CTRL 2'b01: the first, 2'b11: the second), as Vivado times them when the detector's
         * CLKINn_DESKEW input is driven through a loop from an output of the XPLL that no detector controls and its
         * CLKFBn_DESKEW input through a loop from an output of the XPLL (e.g. the controlled output, through a clock
         * buffer and its clock tree): the output is aligned to the clock at CLKINn_DESKEW less the feedback loop,
         * {@code d = b[C] + L_in[C] - L_fb[opp(C)] + p}: b the CLKIN to output delay of the output driving
         * CLKINn_DESKEW (its compensation and phase shift), L_in and L_fb the insertion delays of the loops from those
         * outputs to the deskew inputs, at the opposite min/max corner of the same speed for the feedback, and p the
         * phase shift of the controlled output if it is not the one driving CLKFBn_DESKEW. Other arrangements (and
         * the programmable deskew delays, DESKEW_DELAY_ENn) keep the outputs' delays without deskew.
         */
        private void deskewGenerators(List<ClockNetwork.Stage> stages) {
            if (deskewInputs.isEmpty()) {
                return;
            }
            // The output stages of each generator by their root node, and the roots of all generator outputs
            Map<String, Map<Integer, ClockNetwork.Stage>> outputs = new HashMap<>();
            Set<Integer> outputRoots = new HashSet<>();
            for (ClockNetwork.Stage st : stages) {
                if (isGeneratorStage(st)) {
                    outputs.computeIfAbsent(st.getDriver().getFullHierarchicalInstName(), k -> new HashMap<>())
                            .put(stageRoot[st.getId()], st);
                    outputRoots.add(stageRoot[st.getId()]);
                }
            }
            Set<String> warned = new HashSet<>();
            float[] in = new float[ClockDelayParams.NUM_CORNERS];
            float[] fb = new float[ClockDelayParams.NUM_CORNERS];
            for (ClockNetwork.Stage st : stages) {
                if (!isGeneratorStage(st)) continue;
                EDIFHierPortInst drv = st.getDriver();
                EDIFCellInst ci = drv.getPortInst().getCellInst();
                if (!ClockDelayParams.getGeneratorKind(ci.getCellType().getName()).equals("XPLL")) continue;
                String outPin = drv.getPortInst().getName();
                int pd = getPhaseDetector(ci, outPin);
                if (pd == 0) continue;
                String cellName = drv.getFullHierarchicalInstName();
                Integer inLeaf = deskewInputs.get(cellName + "/CLKIN" + pd + "_DESKEW");
                Integer fbLeaf = deskewInputs.get(cellName + "/CLKFB" + pd + "_DESKEW");
                Map<Integer, ClockNetwork.Stage> outs = outputs.get(cellName);
                ClockNetwork.Stage inOut = inLeaf == null ? null : loopOf(inLeaf, outs, outputRoots, in);
                ClockNetwork.Stage fbOut = fbLeaf == null ? null : loopOf(fbLeaf, outs, outputRoots, fb);
                if (inOut == null || fbOut == null
                        || getPhaseDetector(ci, inOut.getDriver().getPortInst().getName()) != 0) {
                    continue;
                }
                if ("TRUE".equalsIgnoreCase(ClockNetwork.getString(ci, "DESKEW_DELAY_EN" + pd))) {
                    if (warned.add(cellName)) {
                        warn("clock generator digital deskew with programmable delays (not deskewed): " + cellName);
                    }
                    continue;
                }
                int common = stageGenerator[st.getId()];
                int inRoot = stageRoot[inOut.getId()];
                int root = stageRoot[st.getId()];
                float phase = fbOut == st ? 0f : (float) getPhaseShift(st);
                for (int c = 0; c < in.length; c++) {
                    float g = common == ClockTree.NO_PARENT ? 0f : tree.getDelay(common, c);
                    float base = g + tree.getDelay(inRoot, c);
                    tree.setDelay(root, c, base + in[c] - fb[oppositeCorner(c)] + phase - g);
                }
            }
        }

        /** The phase detector controlling an output of an XPLL (CLKOUTn_PHASE_CTRL 2'b01: 1, 2'b11: 2), else 0 */
        private static int getPhaseDetector(EDIFCellInst ci, String outPin) {
            String v = ClockNetwork.getString(ci, outPin + "_PHASE_CTRL");
            return v == null ? 0 : v.endsWith("01") ? 1 : v.endsWith("11") ? 2 : 0;
        }

        /**
         * The output stage of a generator whose root a deskew input's leaf is below, with the delays from that root
         * down to the leaf (the loop's insertion); null if it is below none of them, or if the loop passes through
         * an output of another generator first (outRoots: the roots of all generator outputs).
         */
        private ClockNetwork.Stage loopOf(int leaf, Map<Integer, ClockNetwork.Stage> outs, Set<Integer> outRoots,
                                          float[] delays) {
            Arrays.fill(delays, 0f);
            for (int node = leaf; node != ClockTree.NO_PARENT; node = tree.getParent(node)) {
                ClockNetwork.Stage st = outs.get(node);
                if (st != null) {
                    return st;
                }
                if (outRoots.contains(node)) {
                    return null;
                }
                for (int c = 0; c < delays.length; c++) {
                    delays[c] += tree.getDelay(node, c);
                }
            }
            return null;
        }

        /**
         * The compensation of a clock generator, as Vivado resolves it: NONE for the Versal XPLL, X5PLL and DPLL;
         * else its COMPENSATION, whose default AUTO stands for INTERNAL with a direct feedback (CLKFBOUT driving
         * CLKFBIN); with an external feedback, an MMCM's AUTO (and ZHOLD) is ZHOLD if the input clock comes
         * straight from an input buffer, else BUF_IN, always BUF_IN on Versal (no ZHOLD).
         * @param cell The generator.
         * @param type Its type.
         * @param comp Its COMPENSATION, null if not set.
         * @param inPin The input pin of the compensated clock (CLKIN1, CLKIN2), or null.
         */
        static String resolveCompensation(EDIFHierCellInst cell, String type, String comp, EDIFHierPortInst inPin) {
            String kind = ClockDelayParams.getGeneratorKind(type);
            if (kind.equals("XPLL") || kind.equals("X5PLL") || kind.equals("DPLL")) {
                return comp == null ? "NONE" : comp;
            }
            boolean auto = comp == null || comp.equalsIgnoreCase("AUTO");
            if (auto && isDirectFeedback(cell)) {
                return "INTERNAL";
            }
            if (!kind.equals("MMCM") || !auto && !comp.equalsIgnoreCase("ZHOLD")) {
                return auto ? "AUTO" : comp;
            }
            if (type.startsWith("MMCME5")) {
                return "BUF_IN";
            }
            EDIFHierNet hn = inPin == null ? null : inPin.getHierarchicalNet();
            EDIFHierPortInst src = hn == null ? null : hn.getLeafSourcePortInst();
            String srcType = src == null ? "" : src.getCellType().getName();
            boolean fromInputBuffer = srcType.startsWith("IBUF") || srcType.equals("INBUF")
                    || srcType.equals("DIFFINBUF");
            return fromInputBuffer ? "ZHOLD" : "BUF_IN";
        }

        /** Whether a generator's CLKFBIN is driven by its own CLKFBOUT, or not connected */
        private static boolean isDirectFeedback(EDIFHierCellInst cell) {
            EDIFPortInst fbIn = cell.getInst().getPortInst("CLKFBIN");
            EDIFHierNet hn = fbIn == null ? null : new EDIFHierPortInst(cell.getParent(), fbIn).getHierarchicalNet();
            EDIFHierPortInst src = hn == null ? null : hn.getLeafSourcePortInst();
            return src == null || src.getPortInst().getCellInst() == cell.getInst();
        }

        /**
         * The insertion of a feedback path: the delays from the first of the CLKFBIN leaves below a CLKFBOUT
         * stage's root up to that root, per corner; null if none of the leaves is below it.
         */
        private float[] feedbackInsertion(int top, List<Integer> fbIns) {
            for (int fbIn : fbIns) {
                float[] sum = new float[ClockDelayParams.NUM_CORNERS];
                int node = fbIn;
                while (node >= 0 && node != top) {
                    for (int c = 0; c < sum.length; c++) {
                        sum[c] += tree.getDelay(node, c);
                    }
                    node = tree.getParent(node);
                }
                if (node == top) {
                    return sum;
                }
            }
            return null;
        }

        /**
         * The phase shift (in latency mode) of the clock of a generator output stage, in ps: that of the clock
         * the stage was derived for (the clock of its input), else of the first clock on its driver pin
         */
        private double getPhaseShift(ClockNetwork.Stage st) {
            int id = st.getDerivedClock();
            if (id < 0) {
                List<Integer> edges = cn.getPinClockEdges(st.getDriver().toString());
                if (edges.isEmpty()) {
                    return 0;
                }
                id = edges.get(0) >> 1;
            }
            Clock c = cn.getClock(id);
            return c.getSourceLatency() - (c.getMaster() == null ? 0 : c.getMaster().getSourceLatency());
        }
    }
}
