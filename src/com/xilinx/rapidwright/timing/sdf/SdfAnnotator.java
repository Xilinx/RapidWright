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

package com.xilinx.rapidwright.timing.sdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingVertex;

/**
 * Applies the delays from an {@link SdfFile} to a {@link TimingGraph}.
 *
 * This is what turns a parsed SDF into usable timing: after annotation the graph's existing
 * traversal API -- {@code getTimingPath}, {@code getMaxDelayPath},
 * {@code prettyPrintPathDelays} -- reports Vivado's numbers rather than RapidWright's estimates,
 * on any device, including those RapidWright ships no timing data for.
 *
 * <b>How SDF constructs map onto the graph.</b> An {@code IOPATH} is a delay arc inside one cell,
 * so it becomes the logic delay of the edge between that cell's two pin vertices. An
 * {@code INTERCONNECT} is a routed net segment between two cells, so it becomes the net delay of
 * the edge between those pins. Timing checks are not applied: they constrain a design rather than
 * describe a delay. They are still read, because they are how a cell's clock pin is identified.
 *
 * <b>Sequential arcs need care.</b> A flip-flop's {@code C -> Q} is an {@code IOPATH}, but the
 * graph deliberately excludes clock nets, so a clock pin usually has no vertex and there is no edge
 * to put the delay on. RapidWright instead accounts for clock-to-output as the logic delay of the
 * net edges leaving the output pin, and that is where the value is applied. Which pin is the clock
 * is read from the cell's own timing checks rather than from a per-device table, so this works
 * unchanged across architectures.
 *
 * <b>Names.</b> Every SDF instance name, once unescaped, is a leaf cell name in the
 * macro-expanded netlist -- verified across UltraScale+ and Versal designs, including
 * macro-expanded children such as {@code .../LUT6}, {@code .../INBUF_INST} and
 * {@code .../DSP_ALU_INST}. Because a graph vertex is named exactly
 * {@code <leafCellPath>/<pinName>}, an unescaped endpoint is usually the vertex key directly.
 *
 * Nothing is skipped silently: every entry that cannot be applied is counted and sampled in the
 * returned {@link SdfAnnotationReport}, as is every graph edge no entry touched.
 */
public class SdfAnnotator {

    private final Design design;

    private final SdfFile sdf;

    private final SdfAnnotationConfig config;

    /** Leaf cell instances of the macro-expanded netlist, keyed by full hierarchical name. */
    private final Map<String, EDIFCellInst> leafCells;

    /** Picoseconds per tenth of the SDF's time unit. */
    private final double tenthsToPs;

    private SdfAnnotationReport report;

    private TimingGraph graph;

    /**
     * Edges whose net delay has been set, by identity rather than by equality.
     *
     * Net and logic coverage are tracked separately and deliberately so. A net edge leaving a
     * flip-flop carries both a net delay from an INTERCONNECT and a logic delay from that flop's
     * clock-to-output arc. With a single set, an applied clock-to-output arc would mark the edge
     * covered and hide a missing INTERCONNECT, or the reverse, so coverage would read 100% while
     * half of an edge's delay was still zero.
     */
    private Set<TimingEdge> netAnnotatedEdges;

    /** Edges whose logic delay has been set, by identity. */
    private Set<TimingEdge> logicAnnotatedEdges;

    /**
     * Edges that have already received a control-input-to-output arc, so that a second such arc
     * into the same output keeps the worst value rather than simply overwriting.
     */
    private Set<TimingEdge> sequentialArcEdges;

    /** Vertices and edges this annotation added, which means the graph's shape changed. */
    private int createdEdges;

    /**
     * @param design The design the SDF was written from.
     * @param sdf The parsed SDF.
     */
    public SdfAnnotator(Design design, SdfFile sdf) {
        this(design, sdf, new SdfAnnotationConfig());
    }

    /**
     * @param design The design the SDF was written from.
     * @param sdf The parsed SDF.
     * @param config Options controlling the mapping.
     */
    public SdfAnnotator(Design design, SdfFile sdf, SdfAnnotationConfig config) {
        this.design = design;
        this.sdf = sdf;
        this.config = config;
        this.tenthsToPs = sdf.getTenthsToPs();
        this.leafCells = design.getNetlist().generateCellInstMap();
    }

    /**
     * Applies every delay in the SDF to the graph.
     *
     * An IOPATH may describe an arc the graph has no edge for, so annotating can change the
     * graph's shape. When that happens on a graph that was already finalized, the super source and
     * sink and the cached topological order are recomputed here, since both depend on the shape and
     * a stale order yields wrong arrival and required times even when every edge delay is right. A
     * caller that built the graph with {@code deferFinalize} should call
     * {@link com.xilinx.rapidwright.timing.TimingManager#finalizeTimingGraph()} afterwards instead.
     *
     * @param timingGraph The graph to annotate, already built.
     * @return What happened, including everything that could not be applied.
     */
    public SdfAnnotationReport annotate(TimingGraph timingGraph) {
        this.graph = timingGraph;
        this.report = new SdfAnnotationReport(config.getSampleLimit());
        this.report.setSdfSource(sdf.getSource());
        this.netAnnotatedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        this.logicAnnotatedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        this.sequentialArcEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        this.createdEdges = 0;

        long entries = 0;
        for (SdfCell cell : sdf.getCells()) {
            entries += cell.getDelayEntries().size();
        }
        report.setSdfEntryCount(entries);

        // Whether the graph had already been finalized when we were handed it. If so and we go on
        // to change its shape, the super source and sink and the cached topological order it
        // computed are now stale and have to be redone.
        boolean wasFinalized = timingGraph.superSource != null;

        for (SdfCell cell : sdf.getCells()) {
            annotateCell(cell);
        }

        if (createdEdges > 0 && wasFinalized) {
            timingGraph.finalizeTopology();
        }

        recordUnannotatedEdges();

        if (config.getWarnWhenNotClean() && !report.isClean()) {
            System.err.println("WARNING: SDF annotation was not clean; "
                    + report.getAppliedEntryCount() + " of " + report.getSdfEntryCount()
                    + " delay entries applied, " + report.getGraphEdgesAnnotated() + " of "
                    + report.getGraphEdgeCount() + " timing graph edges annotated."
                    + " Call printSummary() on the returned report for details.");
        }
        return report;
    }

    /**
     * Applies every delay in the SDF, throwing rather than reporting if anything is unresolved.
     *
     * @param timingGraph The graph to annotate, already built.
     * @return The report, which is guaranteed clean.
     * @throws SdfAnnotationException If any entry or edge could not be accounted for.
     */
    public SdfAnnotationReport annotateOrThrow(TimingGraph timingGraph) {
        // Suppress the warning and restore the caller's setting afterwards: throwing already says
        // everything the warning would, and the config belongs to the caller, not to this call.
        boolean previous = config.getWarnWhenNotClean();
        config.setWarnWhenNotClean(false);
        SdfAnnotationReport result;
        try {
            result = annotate(timingGraph);
        } finally {
            config.setWarnWhenNotClean(previous);
        }
        if (!result.isClean()) {
            throw new SdfAnnotationException(result);
        }
        return result;
    }

    /**
     * Ensures the netlist's macro primitives have been expanded into their constituent cells.
     *
     * Without this almost nothing matches: Vivado writes SDF against the post-implementation leaf
     * view, in which an {@code IBUF} appears as {@code .../INBUF_INST} plus
     * {@code .../IBUFCTRL_INST} and a {@code LUT6_2} as {@code .../LUT6} plus {@code .../LUT5},
     * whereas an unexpanded netlist has only the macro.
     *
     * @param design The design whose netlist should be expanded.
     */
    public static void ensureMacrosExpanded(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        netlist.expandMacroUnisims(design.getDevice().getSeries());
    }

    // ------------------------------------------------------------------------------------------
    // Per-cell annotation
    // ------------------------------------------------------------------------------------------

    private void annotateCell(SdfCell cell) {
        String instance = SdfNames.unescape(cell.getInstance());
        Set<String> clockPorts = new HashSet<>();
        for (String clock : cell.getClockPorts()) {
            clockPorts.add(SdfNames.unescape(clock));
        }

        for (SdfDelayEntry entry : cell.getDelayEntries()) {
            if (entry.getKind() == SdfDelayEntry.Kind.INTERCONNECT) {
                annotateInterconnect(entry);
            } else {
                annotateIoPath(cell, instance, clockPorts, entry);
            }
        }
    }

    /**
     * Applies one {@code IOPATH}, an arc between two pins of the same cell.
     *
     * @param cell The enclosing SDF cell.
     * @param instance The unescaped instance path.
     * @param clockPorts Pins this cell's timing checks identify as clocks.
     * @param entry The arc to apply.
     */
    private void annotateIoPath(SdfCell cell, String instance, Set<String> clockPorts,
            SdfDelayEntry entry) {
        if (instance.isEmpty()) {
            // An IOPATH on the top-level cell has no leaf cell to attach to.
            report.record(SdfAnnotationReport.Reason.TOP_LEVEL_PORT, describe(entry));
            return;
        }
        EDIFCellInst inst = leafCells.get(instance);
        if (inst == null) {
            recordUnresolvedInstance(instance, entry);
            return;
        }

        Float delay = extractDelay(entry);
        if (delay == null) {
            return;
        }

        String from = SdfNames.unescape(entry.getSource());
        String to = SdfNames.unescape(entry.getDestination());

        List<String> toPins = resolvePinNames(inst, to, entry);
        if (toPins.isEmpty()) {
            return;
        }

        if (clockPorts.contains(from) || entry.getSourceEdge() != SdfEdge.NONE) {
            // A control-input-to-output arc: either a clock-to-output, or an asynchronous set or
            // reset arc, which Vivado marks by qualifying the source with an edge as in
            // "(IOPATH (posedge PRE) Q ...)".
            //
            // The graph has no edge that can carry either one. It omits clock nets, so a clock pin
            // usually has no vertex at all, and RapidWright's own estimator accounts for
            // clock-to-output as the logic delay of the net edges *leaving* the output. Both arcs
            // therefore have to land in that same place.
            //
            // Giving the asynchronous arc its own PRE -> Q edge instead would double-count: a path
            // entering through PRE would pick up the reset-to-output delay on that edge and then
            // the clock-to-output delay again on the outgoing edge. Since one logic delay per
            // outgoing edge is all the graph can hold, the worst of the competing arcs is kept.
            if (!config.getAnnotateSequentialArcs()) {
                report.record(SdfAnnotationReport.Reason.SEQUENTIAL_ARC_SKIPPED, describe(entry));
                return;
            }
            boolean applied = false;
            for (String toPin : toPins) {
                applied |= applySequentialArc(instance + "/" + toPin, delay);
            }

            report.record(applied ? SdfAnnotationReport.Reason.OK_SEQUENTIAL_ARC
                    : SdfAnnotationReport.Reason.PIN_NOT_IN_TIMING_GRAPH,
                    applied ? null : describe(entry) + " [output pin drives nothing in the graph]");
            return;
        }

        List<String> fromPins = resolvePinNames(inst, from, entry);
        if (fromPins.isEmpty()) {
            return;
        }

        boolean applied = false;
        boolean missingSrc = false;
        boolean missingDst = false;
        boolean created = false;
        for (String fromPin : fromPins) {
            TimingVertex src = graph.getTimingVertex(instance + "/" + fromPin);
            if (src == null) {
                missingSrc = true;
                continue;
            }
            for (String toPin : toPins) {
                TimingVertex dst = graph.getTimingVertex(instance + "/" + toPin);
                if (dst == null) {
                    missingDst = true;
                    continue;
                }
                TimingEdge edge = graph.getEdge(src, dst);
                if (edge == null) {
                    if (!config.getCreateMissingLogicEdges()) {
                        report.record(SdfAnnotationReport.Reason.EDGE_MISSING_SKIPPED,
                                describe(entry));
                        continue;
                    }
                    edge = graph.addLogicDelayEdge(src, dst);
                    if (edge == null) {
                        continue;
                    }
                    created = true;
                    createdEdges++;
                }
                setLogicDelay(edge, delay);
                applied = true;
            }
        }

        if (applied) {
            report.record(created ? SdfAnnotationReport.Reason.OK_IOPATH_EDGE_CREATED
                    : SdfAnnotationReport.Reason.OK_IOPATH, null);
        } else if (missingSrc || missingDst) {
            report.record(SdfAnnotationReport.Reason.PIN_NOT_IN_TIMING_GRAPH, describe(entry)
                    + " [" + (missingSrc ? "source" : "destination")
                    + " pin is not connected in this design]");
        }
    }

    /**
     * Applies a clock-to-output delay to every net edge leaving the given output pin.
     *
     * @param outputVertexName Full hierarchical name of the output pin.
     * @param delay The delay in picoseconds.
     * @return True if at least one edge was updated.
     */
    private boolean applySequentialArc(String outputVertexName, float delay) {
        TimingVertex vertex = graph.getTimingVertex(outputVertexName);
        if (vertex == null || !graph.containsVertex(vertex)) {
            return false;
        }
        Set<TimingEdge> outgoing = graph.outgoingEdgesOf(vertex);
        if (outgoing.isEmpty()) {
            return false;
        }
        boolean applied = false;
        for (TimingEdge edge : outgoing) {
            if (sequentialArcEdges.contains(edge)) {
                // A second arc into the same output, such as a reset-to-output alongside a
                // clock-to-output. Keep the worst, since the edge can hold only one.
                if (delay <= edge.getLogicDelay()) {
                    applied = true;
                    continue;
                }
            }
            setLogicDelay(edge, delay);
            sequentialArcEdges.add(edge);
            applied = true;
        }
        return applied;
    }

    /**
     * Applies one {@code INTERCONNECT}, a routed net segment between two cell pins.
     *
     * @param entry The arc to apply.
     */
    private void annotateInterconnect(SdfDelayEntry entry) {
        Float delay = extractDelay(entry);
        if (delay == null) {
            return;
        }

        TimingVertex src = resolveEndpoint(entry.getSource(), entry, true);
        if (src == null) {
            return;
        }
        TimingVertex dst = resolveEndpoint(entry.getDestination(), entry, false);
        if (dst == null) {
            return;
        }

        TimingEdge edge = graph.getEdge(src, dst);
        if (edge == null) {
            // The two pins exist but the graph has no net edge between them, which happens when
            // the net was not routed or was excluded from the build.
            report.record(SdfAnnotationReport.Reason.EDGE_MISSING_SKIPPED, describe(entry));
            return;
        }
        if (netAnnotatedEdges.contains(edge)) {
            // jgrapht permits only one edge per vertex pair, so a second entry for the same pair
            // has to be merged rather than added. Keeping the larger value is the safe choice for
            // setup analysis.
            report.record(SdfAnnotationReport.Reason.DUPLICATE_ENTRY, describe(entry));
            if (delay <= edge.getNetDelay()) {
                return;
            }
        }
        setNetDelay(edge, delay);
        report.record(SdfAnnotationReport.Reason.OK_INTERCONNECT, null);
    }

    /**
     * Resolves an {@code INTERCONNECT} endpoint to a graph vertex.
     *
     * @param endpoint The escaped endpoint text.
     * @param entry The entry it came from, for diagnostics.
     * @param isSource Whether this is the driving end.
     * @return The vertex, or null if it could not be resolved.
     */
    private TimingVertex resolveEndpoint(String endpoint, SdfDelayEntry entry, boolean isSource) {
        // A graph vertex is named exactly "<leafCellPath>/<pinName>", which is what an unescaped
        // endpoint already is, so the common case is a single map lookup with no splitting.
        String name = SdfNames.unescape(endpoint);
        TimingVertex vertex = graph.getTimingVertex(name);
        if (vertex != null) {
            return vertex;
        }

        String[] split = SdfNames.splitEndpoint(endpoint);
        if (split == null) {
            report.record(SdfAnnotationReport.Reason.TOP_LEVEL_PORT, describe(entry));
            return null;
        }
        EDIFCellInst inst = leafCells.get(split[0]);
        if (inst == null) {
            recordUnresolvedInstance(split[0], entry);
            return null;
        }
        // The cell is real but the graph has no vertex for this pin, which means no net the graph
        // was built from reaches it.
        report.record(SdfAnnotationReport.Reason.PIN_NOT_IN_TIMING_GRAPH, describe(entry)
                + " [" + (isSource ? "source" : "destination") + " pin is not in the graph]");
        return null;
    }

    /**
     * Records an unresolved instance name, distinguishing a missing macro sub-cell from a wholly
     * unknown name and naming the longest prefix that did resolve.
     *
     * @param instance The unescaped instance path that failed to resolve.
     * @param entry The entry it came from.
     */
    private void recordUnresolvedInstance(String instance, SdfDelayEntry entry) {
        // Walk back through the hierarchy, not just the leaf map, to find how far the name got.
        // Naming the deepest level that did resolve turns "no such cell" into something
        // actionable: it distinguishes a wholly foreign path from a known parent whose child the
        // netlist spells differently, which is what happens when an implementation-only rename
        // such as a replication suffix reaches the SDF but not the EDIF.
        EDIFNetlist netlist = design.getNetlist();
        int slash = instance.lastIndexOf('/');
        while (slash > 0) {
            String prefix = instance.substring(0, slash);
            String child = instance.substring(slash + 1);
            if (leafCells.containsKey(prefix)
                    || netlist.getHierCellInstFromName(prefix) != null) {
                report.record(SdfAnnotationReport.Reason.MACRO_SUBCELL_NOT_IN_NETLIST,
                        describe(entry) + " [resolved as far as '" + prefix + "', which has no"
                        + " child named '" + child + "']");
                return;
            }
            slash = instance.lastIndexOf('/', slash - 1);
        }
        report.record(SdfAnnotationReport.Reason.CELL_INSTANCE_NOT_IN_NETLIST,
                describe(entry) + " [no cell named '" + instance + "']");
    }

    /**
     * Maps an SDF pin name onto the pin names the timing graph uses.
     *
     * Vivado writes a bus pin either indexed, as {@code WEA[3]}, or bare when the arc applies to
     * the whole bus, as {@code ADDRARDADDR}. The graph names each bit separately, so a bare bus
     * name expands to one name per bit.
     *
     * @param inst The cell the pin belongs to.
     * @param pin The unescaped pin name from the SDF.
     * @param entry The entry it came from, for diagnostics.
     * @return The pin names to use, empty if the pin is not on the cell.
     */
    private List<String> resolvePinNames(EDIFCellInst inst, String pin, SdfDelayEntry entry) {
        EDIFPort port = inst.getCellType().getPortByPortInstName(pin);
        if (port == null) {
            // getPortByPortInstName only strips a trailing index, so a bare bus name such as
            // RAMB36E2's DOUTADOUT misses. Ports are keyed by the bus name with its opening
            // bracket, so retry with that.
            port = inst.getCellType().getPort(pin + "[");
        }
        if (port == null) {
            report.record(SdfAnnotationReport.Reason.PIN_NOT_ON_CELL,
                    describe(entry) + " [cell type " + inst.getCellType().getName()
                    + " has no pin '" + pin + "']");
            return Collections.emptyList();
        }
        if (!port.isBus() || pin.endsWith("]")) {
            return Collections.singletonList(pin);
        }
        List<String> names = new ArrayList<>(port.getWidth());
        for (int i = 0; i < port.getWidth(); i++) {
            names.add(port.getPortInstNameFromPort(i));
        }
        report.record(SdfAnnotationReport.Reason.PIN_BUS_EXPANDED,
                describe(entry) + " [pin '" + pin + "' expanded to " + port.getWidth() + " bits]");
        return names;
    }

    // ------------------------------------------------------------------------------------------
    // Delay extraction
    // ------------------------------------------------------------------------------------------

    /**
     * Picks a single delay, in picoseconds, out of an entry's delay value list.
     *
     * @param entry The entry to read.
     * @return The delay, or null if the entry carried no usable value.
     */
    private Float extractDelay(SdfDelayEntry entry) {
        SdfDelayValues values = entry.getValues();
        int component = componentIndex();

        int count = 0;
        double sum = 0;
        double worst = Double.NEGATIVE_INFINITY;
        int firstPresent = -1;
        for (int slot = 0; slot < values.size(); slot++) {
            if (!values.isPresent(slot)) {
                continue;
            }
            if (firstPresent < 0) {
                firstPresent = slot;
            }
            double v = values.getValue(slot, component);
            sum += v;
            count++;
            if (v > worst) {
                worst = v;
            }
        }
        if (count == 0) {
            report.record(SdfAnnotationReport.Reason.EMPTY_DELVAL_LIST, describe(entry));
            return null;
        }

        // RISE and FALL name specific slots of the delay value list: slot 0 is the rising-output
        // transition and slot 1 the falling one. When the requested slot is absent the entry simply
        // does not describe that transition -- a tri-state IOPATH may carry only the
        // high-impedance transitions -- and any other slot would be a different transition
        // masquerading as the requested one. Rather than substitute one silently, fall back to the
        // worst present value, which is at least conservative, and say so.
        int requestedSlot = -1;
        if (config.getTransition() == SdfAnnotationConfig.Transition.RISE) {
            requestedSlot = 0;
        } else if (config.getTransition() == SdfAnnotationConfig.Transition.FALL) {
            requestedSlot = 1;
        }

        double picked;
        if (requestedSlot >= 0) {
            if (requestedSlot < values.size() && values.isPresent(requestedSlot)) {
                picked = values.getValue(requestedSlot, component);
            } else {
                report.record(SdfAnnotationReport.Reason.REQUESTED_TRANSITION_ABSENT,
                        describe(entry) + " [no " + config.getTransition()
                        + " value; using the worst of the " + count + " present]");
                picked = worst;
            }
        } else if (config.getTransition() == SdfAnnotationConfig.Transition.AVERAGE) {
            picked = sum / count;
        } else {
            picked = worst;
        }

        // The SDF stores tenths of its own TIMESCALE unit; the timing graph works in picoseconds.
        double ps = picked * 10 * tenthsToPs;
        if (ps < 0) {
            // Negative values are legitimate in a timing check but not in a propagation delay.
            report.record(SdfAnnotationReport.Reason.NEGATIVE_DELAY, describe(entry));
            ps = 0;
        }
        return (float) ps;
    }

    private int componentIndex() {
        switch (config.getCorner()) {
            case MIN: return SdfDelayValues.MIN;
            case TYP: return SdfDelayValues.TYP;
            default: return SdfDelayValues.MAX;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Edge updates
    // ------------------------------------------------------------------------------------------

    private void setLogicDelay(TimingEdge edge, float delayPs) {
        // TimingEdge.setLogicDelay already recomputes the total and syncs the graph's edge weight.
        edge.setLogicDelay(delayPs);
        logicAnnotatedEdges.add(edge);
    }

    private void setNetDelay(TimingEdge edge, float delayPs) {
        // The intra-site component has to be cleared as well: TimingEdge.setNetDelay leaves it
        // alone, and the delay-breakdown printer expects an intra-site edge to have net delay equal
        // to intra-site delay. An SDF INTERCONNECT is the whole segment, so keeping a stale
        // intra-site figure alongside it would make the breakdown inconsistent.
        edge.setIntraSiteDelay(0f);
        edge.setNetDelay(delayPs);
        netAnnotatedEdges.add(edge);
    }

    /**
     * Counts, and samples, every delay component of the graph that no SDF entry supplied.
     *
     * These are the most important numbers in the report. An unmatched SDF entry is only unused
     * data, but a graph edge whose delay did not come from the SDF still has one, and silently
     * corrupts every path through it.
     *
     * Components are counted separately rather than per edge. An edge is not one number: a net edge
     * leaving a flip-flop holds a net delay from an INTERCONNECT <i>and</i> a logic delay from that
     * flop's clock-to-output arc. Treating the edge as covered as soon as either arrived would
     * let a missing INTERCONNECT hide behind a present clock-to-output arc, reporting full
     * coverage over a graph half of whose delay was still zero.
     */
    private void recordUnannotatedEdges() {
        TimingVertex superSource = graph.superSource;
        TimingVertex superSink = graph.superSink;

        long annotatable = 0;
        long annotated = 0;
        long netRequired = 0;
        long netAnnotated = 0;
        long logicRequired = 0;
        long logicAnnotated = 0;

        List<String> missingNet = new ArrayList<>();
        List<String> missingLogic = new ArrayList<>();
        List<String> missingClockArc = new ArrayList<>();
        long missingNetCount = 0;
        long missingLogicCount = 0;
        long missingClockArcCount = 0;

        for (TimingEdge edge : graph.edgeSet()) {
            // The artificial edges into and out of the super source and sink are zero by
            // construction, so they are neither annotatable nor missing. Counting them would
            // understate coverage by a large and meaningless margin.
            if (superSource != null && superSource.equals(edge.getSrc())) {
                continue;
            }
            if (superSink != null && superSink.equals(edge.getDst())) {
                continue;
            }
            annotatable++;
            boolean hasNet = netAnnotatedEdges.contains(edge);
            boolean hasLogic = logicAnnotatedEdges.contains(edge);
            if (hasNet || hasLogic) {
                annotated++;
            }

            if (isNetEdge(edge)) {
                // A routed net segment: its net delay must come from an INTERCONNECT.
                netRequired++;
                if (hasNet) {
                    netAnnotated++;
                } else {
                    missingNetCount++;
                    if (missingNet.size() < config.getSampleLimit()) {
                        missingNet.add(describeEdge(edge, "net"));
                    }
                }
                // A net edge leaving a sequential output additionally carries that cell's
                // clock-to-output delay, because the graph has no clock arc to put it on.
                if (edge.getSrc() != null && edge.getSrc().getFlopOutput()) {
                    logicRequired++;
                    if (hasLogic) {
                        logicAnnotated++;
                    } else {
                        missingClockArcCount++;
                        if (missingClockArc.size() < config.getSampleLimit()) {
                            missingClockArc.add(describeEdge(edge, "clock-to-output"));
                        }
                    }
                }
            } else {
                // A cell-internal arc: its logic delay must come from an IOPATH.
                logicRequired++;
                if (hasLogic) {
                    logicAnnotated++;
                } else {
                    missingLogicCount++;
                    if (missingLogic.size() < config.getSampleLimit()) {
                        missingLogic.add(describeEdge(edge, "logic"));
                    }
                }
            }
        }

        report.setGraphEdgeCount(annotatable);
        report.setGraphEdgesAnnotated(annotated);
        report.setNetDelayCounts(netRequired, netAnnotated);
        report.setLogicDelayCounts(logicRequired, logicAnnotated);

        recordMany(SdfAnnotationReport.Reason.EDGE_MISSING_NET_DELAY, missingNetCount, missingNet);
        recordMany(SdfAnnotationReport.Reason.EDGE_MISSING_LOGIC_DELAY, missingLogicCount,
                missingLogic);
        recordMany(SdfAnnotationReport.Reason.FLOP_OUTPUT_MISSING_CLOCK_ARC, missingClockArcCount,
                missingClockArc);
    }

    /**
     * @param edge The edge to classify.
     * @return True if the edge stands for a routed net segment rather than a cell-internal arc.
     *         Cell-internal arcs are created with no net, which is how the rest of the timing code
     *         tells the two apart.
     */
    private static boolean isNetEdge(TimingEdge edge) {
        return edge.getNet() != null || edge.getEdifNet() != null;
    }

    private static String describeEdge(TimingEdge edge, String component) {
        return edge.getSrc() + " -> " + edge.getDst() + " (" + component + " delay still "
                + (component.equals("net") ? edge.getNetDelay() : edge.getLogicDelay()) + " ps)";
    }

    /**
     * Records a category the exact number of times it occurred while keeping only the collected
     * samples, so the count stays accurate and memory stays bounded.
     *
     * @param reason The category.
     * @param count How many times it occurred.
     * @param samples The samples collected, at most the configured limit.
     */
    private void recordMany(SdfAnnotationReport.Reason reason, long count, List<String> samples) {
        for (long i = 0; i < count; i++) {
            report.record(reason, i < samples.size() ? samples.get((int) i) : null);
        }
    }

    /**
     * @param entry The entry to describe.
     * @return A one-line description carrying the entry's position in the SDF file.
     */
    private String describe(SdfDelayEntry entry) {
        String file = sdf.getSource() == null ? "<sdf>" : sdf.getSource().getFileName().toString();
        return file + ":" + entry.getLineNumber() + ": " + entry.getKind() + " "
                + entry.getSource() + " -> " + entry.getDestination();
    }

    /**
     * @return The design being annotated.
     */
    public Design getDesign() {
        return design;
    }

    /**
     * @return The SDF being applied.
     */
    public SdfFile getSdfFile() {
        return sdf;
    }

    /**
     * @return The options in effect.
     */
    public SdfAnnotationConfig getConfig() {
        return config;
    }
}
