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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Everything the comparison needs from one netlist, and nothing else.
 *
 * The point of this class is what it does <i>not</i> hold: once a netlist has
 * been elaborated into the shared graph, the flattened model and the
 * connectivity are released, leaving only names mapped to graph literals.
 */
public class NetlistSummary {

    private final String label;

    private final String partName;

    private final Series series;

    /** Checked terminal input pin, by canonical key, mapped to its cone literal. */
    private final Map<String, Integer> coneLiterals = new TreeMap<>();

    /** Every terminal, by canonical instance name, mapped to its configuration. */
    private final Map<String, TerminalAttributes> terminals = new TreeMap<>();

    private final NetlistStats stats;

    private LoopReport loops = new LoopReport();

    private int aigNodesAdded;

    private int maxConeDepth;

    NetlistSummary(String label, String partName, Series series, NetlistStats stats) {
        this.label = label;
        this.partName = partName;
        this.series = series;
        this.stats = stats;
    }

    /**
     * Elaborates one netlist into the shared graph and returns its summary.
     *
     * Every checked cone root becomes an entry in {@link #getConeLiterals()}:
     * every input pin of every terminal, clocks included, plus every top level
     * output port bit, which is also a terminal.
     */
    public static NetlistSummary build(String label, FlatNetlist flat, Connectivity conn,
            CellClassifier classifier, Aig aig, Series series, NetlistStats stats, LecLog log) {
        NetlistSummary summary = new NetlistSummary(label,
                NetlistLoader.getPartName(flat.getNetlist()), series, stats);
        ConeBuilder cones = new ConeBuilder(flat, conn, classifier, aig, series, log, label);
        int nodesBefore = aig.getNodeCount();
        summary.collectTerminalCones(flat, classifier, cones, log);
        summary.collectTopOutputCones(flat, conn, cones);
        summary.collectShortedDriverCones(flat, cones);
        summary.loops = cones.getLoopReport();
        summary.aigNodesAdded = aig.getNodeCount() - nodesBefore;
        summary.maxConeDepth = cones.getMaxDepth();
        return summary;
    }

    /**
     * Walks every terminal, building a cone for each input pin and
     * recording the terminal's own configuration.
     */
    private void collectTerminalCones(FlatNetlist flat, CellClassifier classifier,
            ConeBuilder cones, LecLog log) {
        for (int instId : flat.getLeafInstIds()) {
            if (classifier.classify(flat.getCellType(instId)) != CellRole.TERMINAL) {
                continue;
            }
            String typeName = flat.getCellTypeName(instId);
            String instName = flat.getInstName(instId);
            CellIndex index = flat.getIndex(instId);
            Set<String> foldedPins = new TreeSet<>();
            for (int bit = 0; bit < index.getPortBitCount(); bit++) {
                EDIFPort port = index.getPortOfBit(bit);
                if (port.getDirection() != EDIFDirection.INPUT) {
                    continue;
                }
                String pinName = index.getPortBitName(bit);
                int lit = cones.literalDriving(instId, bit);
                String basePin = CellClassifier.stripBusIndex(pinName);
                if (ModelUtils.isPinBitInverted(flat.getInst(instId), basePin,
                        busIndexOf(pinName))) {
                    lit = Aig.not(lit);
                }
                // The cone accounts for this pin's inversion either way, so the
                // attribute is folded even where it reads zero.
                foldedPins.add(basePin);
                coneLiterals.put(TerminalKey.forPin(instName, pinName), lit);
            }
            terminals.put(instName.isEmpty() ? typeName : instName,
                    TerminalAttributes.of(typeName, flat.getInst(instId), foldedPins));
        }
    }

    /** @return The bit index of a bus pin such as {@code ALUMODE[2]}, or 0. */
    private static int busIndexOf(String pinName) {
        String basePin = CellClassifier.stripBusIndex(pinName);
        if (basePin.length() == pinName.length()) {
            return 0;
        }
        return Integer.parseInt(pinName.substring(basePin.length() + 1, pinName.length() - 1));
    }

    /**
     * Top level output ports are terminals too, so the logic reaching each one
     * is a checked cone, as is the driven side of a bidirectional port. They
     * attach to a net rather than to a cell pin, so they are resolved from the
     * electrical node.
     *
     * Every port bit, inputs included, is also recorded as a terminal whose type
     * is its direction, so a port added, removed or redirected is reported even
     * where no cone reaches it.
     */
    private void collectTopOutputCones(FlatNetlist flat, Connectivity conn, ConeBuilder cones) {
        int topId = flat.getTopInstId();
        if (flat.isLeaf(topId)) {
            return;
        }
        CellIndex index = flat.getIndex(topId);
        Set<String> noFoldedPins = Collections.emptySet();
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            EDIFDirection dir = index.getPortOfBit(bit).getDirection();
            terminals.put("top level port " + index.getPortBitName(bit),
                    TerminalAttributes.of(dir + " port", null, noFoldedPins));
            if (dir == EDIFDirection.INPUT) {
                continue;
            }
            int localNet = index.getBoundaryNet(bit);
            int root = localNet < 0 ? -1 : conn.getRoot(flat.getGlobalNet(topId, localNet));
            coneLiterals.put(TerminalKey.forTopPort(index.getPortBitName(bit)),
                    dir == EDIFDirection.INOUT ? cones.literalOfInoutRoot(root)
                            : cones.literalOfNetRoot(root));
        }
    }

    /**
     * A shorted net is named from its driver pins, which only means the same
     * thing in both netlists if its cloud drivers compute the same functions,
     * so each is checked as a cone keyed by its pin.
     */
    private void collectShortedDriverCones(FlatNetlist flat, ConeBuilder cones) {
        for (List<Integer> pins = cones.takeShortedDriverPins(); !pins.isEmpty();
                pins = cones.takeShortedDriverPins()) {
            for (int pin : pins) {
                int owner = flat.getPinOwner(pin);
                coneLiterals.put(flat.getPinName(owner, flat.getPinPortBit(pin)),
                        cones.literalOfPin(pin));
            }
        }
    }

    public String getLabel() {
        return label;
    }

    public String getPartName() {
        return partName;
    }

    public Series getSeries() {
        return series;
    }

    /** @return Checked cone roots, by canonical key, mapped to graph literals. */
    public Map<String, Integer> getConeLiterals() {
        return coneLiterals;
    }

    /** @return Terminals by canonical name, mapped to their configuration. */
    public Map<String, TerminalAttributes> getTerminals() {
        return terminals;
    }

    public NetlistStats getStats() {
        return stats;
    }

    public LoopReport getLoopReport() {
        return loops;
    }

    /** @return How many graph nodes elaborating this netlist added. */
    public int getAigNodesAdded() {
        return aigNodesAdded;
    }

    public int getMaxConeDepth() {
        return maxConeDepth;
    }

    /** @return A snapshot of the cone map, ordered, for stable reporting. */
    public Map<String, Integer> getOrderedConeLiterals() {
        return new LinkedHashMap<>(coneLiterals);
    }
}
