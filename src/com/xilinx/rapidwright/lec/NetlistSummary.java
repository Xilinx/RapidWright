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

import java.util.LinkedHashMap;
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
 * been elaborated into the shared graph, the EDIF object graph, the flattened
 * model and the connectivity are all released, leaving only names mapped to
 * graph literals. That is what lets the two netlists be processed one after the
 * other instead of both being resident at once, which roughly halves peak
 * memory on a large design.
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
     * every non-clock input pin of every terminal, plus every top level output
     * port bit, which is also a terminal.
     */
    public static NetlistSummary build(String label, FlatNetlist flat, Connectivity conn,
            CellClassifier classifier, Aig aig, Series series, NetlistStats stats, LecLog log) {
        NetlistSummary summary = new NetlistSummary(label,
                NetlistLoader.getPartName(flat.getNetlist()), series, stats);
        ConeBuilder cones = new ConeBuilder(flat, conn, classifier, aig, series, log);
        int nodesBefore = aig.getNodeCount();
        summary.collectTerminalCones(flat, classifier, cones, log);
        summary.collectTopOutputCones(flat, conn, cones);
        summary.loops = cones.getLoopReport();
        summary.aigNodesAdded = aig.getNodeCount() - nodesBefore;
        summary.maxConeDepth = cones.getMaxDepth();
        return summary;
    }

    /**
     * Walks every terminal, building a cone for each non-clock input pin and
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
                if (classifier.isClockPin(typeName, pinName)) {
                    continue;
                }
                int lit = cones.literalDriving(instId, bit);
                String basePin = CellClassifier.stripBusIndex(pinName);
                if (ModelUtils.isPinInverted(flat.getInst(instId), basePin)) {
                    lit = Aig.not(lit);
                    foldedPins.add(basePin);
                }
                coneLiterals.put(TerminalKey.forPin(instName, pinName), lit);
            }
            terminals.put(instName.isEmpty() ? typeName : instName,
                    TerminalAttributes.of(typeName, flat.getInst(instId), foldedPins));
        }
    }

    /**
     * Top level output ports are terminals too, so the logic reaching each one
     * is a checked cone. They attach to a net rather than to a cell pin, so they
     * are resolved from the electrical node.
     */
    private void collectTopOutputCones(FlatNetlist flat, Connectivity conn, ConeBuilder cones) {
        int topId = flat.getTopInstId();
        if (flat.isLeaf(topId)) {
            return;
        }
        CellIndex index = flat.getIndex(topId);
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            if (index.getPortOfBit(bit).getDirection() != EDIFDirection.OUTPUT) {
                continue;
            }
            int localNet = index.getBoundaryNet(bit);
            int root = localNet < 0 ? -1 : conn.getRoot(flat.getGlobalNet(topId, localNet));
            coneLiterals.put(TerminalKey.forTopPort(index.getPortBitName(bit)),
                    cones.literalOfNetRoot(root));
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
