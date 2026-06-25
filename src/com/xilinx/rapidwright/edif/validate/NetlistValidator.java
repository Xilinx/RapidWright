/*
 *
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
package com.xilinx.rapidwright.edif.validate;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFName;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Comprehensively traverses an {@link EDIFNetlist} object graph and reports any
 * structural inconsistencies or EDIF-export-safety problems that could otherwise
 * write out as valid-looking EDIF but fail or silently corrupt when read into
 * Vivado.
 *
 * <p>This validator focuses on the in-memory object graph and EDIF export
 * semantics; it does not perform device- or Unisim-aware checks (primitive pin
 * legality, required properties, etc.).</p>
 *
 * <p>The validator never throws on an inconsistency it is designed to detect;
 * instead it accumulates {@link ValidationIssue} objects into an
 * {@link ValidationReport}. It is designed to run on very large netlists:
 * the heavy per-cell pass only ever builds maps scoped to a single cell, and the
 * report caps the number of stored issues per code.</p>
 */
public class NetlistValidator {

    /** If false, skips sibling-scope EDIF name-legalization collision checks. */
    public boolean checkNameLegalization = true;

    /**
     * If true, verifies that each cell's cached non-hierarchical instantiation
     * count matches the actual number of instantiations found during traversal.
     */
    public boolean checkInstantiationCounts = true;

    /** Codes the caller has chosen to suppress entirely. */
    public Set<IssueCode> disabledCodes = new HashSet<>();

    /** Per-code cap on retained issues (overflow is still counted). */
    public int maxIssuesPerCode = ValidationReport.DEFAULT_MAX_ISSUES_PER_CODE;

    private ValidationReport report;

    /** Identity set of every cell owned by a library of the netlist under test. */
    private Set<EDIFCell> netlistCells;

    /** Actual instantiation counts observed during the per-cell pass. */
    private Map<EDIFCell, Integer> observedInstCounts;

    /**
     * Validates the provided netlist and returns a report of all issues found.
     *
     * @param netlist The netlist to validate.
     * @return The populated validation report.
     */
    public ValidationReport validate(EDIFNetlist netlist) {
        report = new ValidationReport(maxIssuesPerCode);
        netlistCells = null;
        observedInstCounts = checkInstantiationCounts ? new IdentityHashMap<>() : null;

        buildCellIndex(netlist);
        checkTopAndDesign(netlist);
        checkLibraries(netlist);
        for (EDIFLibrary lib : netlist.getLibraries()) {
            for (EDIFCell cell : lib.getCells()) {
                checkCell(cell);
            }
        }
        checkInstantiationCountDrift();
        checkHierarchyAndReachability(netlist);

        return report;
    }

    private void add(IssueCode code, String location, String message) {
        if (disabledCodes.contains(code)) {
            return;
        }
        report.add(code, location, message);
    }

    /* ------------------------------------------------------------------ *
     *  Index construction
     * ------------------------------------------------------------------ */

    private void buildCellIndex(EDIFNetlist netlist) {
        netlistCells = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EDIFLibrary lib : netlist.getLibraries()) {
            for (EDIFCell cell : lib.getCells()) {
                netlistCells.add(cell);
            }
        }
    }

    /* ------------------------------------------------------------------ *
     *  Pass 0: top / design / part
     * ------------------------------------------------------------------ */

    private void checkTopAndDesign(EDIFNetlist netlist) {
        EDIFDesign design = netlist.getDesign();
        if (design == null) {
            add(IssueCode.NULL_DESIGN, loc(netlist), "Netlist has no EDIFDesign set");
            return;
        }
        EDIFCell top = design.getTopCell();
        if (top == null) {
            add(IssueCode.NULL_TOP_CELL, loc(netlist), "EDIFDesign has no top cell set");
            return;
        }
        if (netlistCells != null && !netlistCells.contains(top)) {
            add(IssueCode.TOP_CELL_NOT_IN_NETLIST, cellLoc(top),
                    "Top cell is not contained in any library of this netlist");
        }
        EDIFLibrary topLib = top.getLibrary();
        if (topLib != null && topLib.isHDIPrimitivesLibrary()) {
            add(IssueCode.TOP_CELL_IN_PRIMITIVES_LIB, cellLoc(top),
                    "Top cell resides in the hdi_primitives library");
        }
        String part = EDIFTools.getPartName(netlist);
        if (part == null || part.isEmpty()) {
            add(IssueCode.MISSING_PART_PROPERTY, loc(netlist),
                    "No PART property found on the design; Vivado requires a part");
        }
    }

    /* ------------------------------------------------------------------ *
     *  Pass: library wiring
     * ------------------------------------------------------------------ */

    private void checkLibraries(EDIFNetlist netlist) {
        // Track cells seen across libraries to detect shared/duplicated cell objects.
        Map<EDIFCell, EDIFLibrary> cellOwner = new IdentityHashMap<>();
        for (Map.Entry<String, EDIFLibrary> e : netlist.getLibrariesMap().entrySet()) {
            EDIFLibrary lib = e.getValue();
            if (lib.getNetlist() != netlist) {
                add(IssueCode.LIBRARY_NETLIST_BACKPTR_BROKEN, libLoc(lib),
                        "Library's netlist back-pointer does not refer to the netlist being validated");
            }
            for (Map.Entry<String, EDIFCell> ce : lib.getCellMap().entrySet()) {
                EDIFCell cell = ce.getValue();
                if (!ce.getKey().equals(cell.getName())) {
                    add(IssueCode.CELL_MAP_KEY_MISMATCH, cellLoc(cell),
                            "Library cell-map key '" + ce.getKey() + "' != cell name '"
                                    + cell.getName() + "'");
                }
                if (cell.getLibrary() != lib) {
                    add(IssueCode.CELL_LIBRARY_BACKPTR_BROKEN, cellLoc(cell),
                            "Cell's library back-pointer does not refer to its containing library");
                }
                EDIFLibrary prev = cellOwner.put(cell, lib);
                if (prev != null) {
                    add(IssueCode.CELL_IN_MULTIPLE_LIBRARIES, cellLoc(cell),
                            "Cell object is shared by libraries '" + prev.getName() + "' and '"
                                    + lib.getName() + "'");
                }
            }
        }
    }

    /* ------------------------------------------------------------------ *
     *  Pass 1: per-cell local checks (scale-conscious; maps scoped to cell)
     * ------------------------------------------------------------------ */

    private void checkCell(EDIFCell cell) {
        // Maps scoped to this cell only.
        Map<String, String> legalCellNames = checkNameLegalization ? new HashMap<>() : null;

        // Ports. Port name legalization collisions are intentionally not checked:
        // bus-vs-single-bit root-name collisions are resolved by the EDIF writer's
        // bus-collision rename ("_BUS_" suffix, see EDIFPort.getBusEDIFRename), and
        // the cell port map already forbids same-key duplicates, so emitted port
        // names are always distinct.
        for (Map.Entry<String, EDIFPort> pe : cell.getPortMap().entrySet()) {
            checkPort(cell, pe.getKey(), pe.getValue());
        }

        // Cell instances
        for (EDIFCellInst inst : cell.getCellInsts()) {
            checkCellInst(cell, inst);
            if (legalCellNames != null) {
                checkLegalCollision(legalCellNames, inst.getName(), cellLoc(cell),
                        IssueCode.NAME_LEGALIZATION_COLLISION, "instance");
            }
            if (observedInstCounts != null && inst.getCellType() != null) {
                observedInstCounts.merge(inst.getCellType(), 1, Integer::sum);
            }
        }

        // Nets + the per-cell connectivity invariants (#39, #45)
        // portInstNetMap: portInst identity -> the net it was found on
        Map<EDIFPortInst, EDIFNet> portInstNetMap = new IdentityHashMap<>();
        // connectedBit: (cellInst, portInstName) -> net, to detect a port bit on two nets
        Map<String, EDIFNet> connectedBit = new HashMap<>();
        Map<String, String> legalNetNames = checkNameLegalization ? new HashMap<>() : null;

        for (EDIFNet net : cell.getNets()) {
            checkNet(cell, net, portInstNetMap, connectedBit);
            if (legalNetNames != null) {
                checkLegalCollision(legalNetNames, net.getName(), cellLoc(cell),
                        IssueCode.NET_NAME_LEGALIZATION_COLLISION, "net");
            }
        }

        // Verify every portInst on every cellInst is also present on exactly one net.
        for (EDIFCellInst inst : cell.getCellInsts()) {
            for (EDIFPortInst pi : inst.getPortInsts()) {
                if (!portInstNetMap.containsKey(pi)) {
                    add(IssueCode.PORTINST_DUAL_MEMBERSHIP_BROKEN, portInstLoc(cell, pi),
                            "PortInst is on cell instance '" + inst.getName()
                                    + "' but not present on any net in the parent cell");
                }
            }
        }

        checkInternalPortMap(cell);
    }

    private void checkPort(EDIFCell cell, String mapKey, EDIFPort port) {
        if (isEmpty(port.getName())) {
            add(IssueCode.EMPTY_OBJECT_NAME, cellLoc(cell), "Cell has a port with an empty name");
        }
        if (port.getParentCell() != cell) {
            add(IssueCode.PORT_PARENT_BROKEN, portLoc(cell, port),
                    "Port's parent-cell back-pointer is broken");
        }
        if (port.getDirection() == null) {
            add(IssueCode.PORT_NULL_DIRECTION, portLoc(cell, port), "Port has null direction");
        }
        if (port.getWidth() <= 0) {
            add(IssueCode.PORT_NONPOSITIVE_WIDTH, portLoc(cell, port),
                    "Port has non-positive width " + port.getWidth());
        }
        // Expected map key: bussed ports keyed by busName(true) (ends with '['),
        // single-bit ports keyed by full name.
        String expectedKey = port.isBus() ? port.getBusName(true) : port.getName();
        if (!expectedKey.equals(mapKey)) {
            add(IssueCode.PORT_MAP_KEY_MISMATCH, portLoc(cell, port),
                    "Port map key '" + mapKey + "' != expected key '" + expectedKey + "'");
        }
        // Bus name / width consistency
        if (port.isBus()) {
            try {
                Integer left = port.getLeft();
                Integer right = port.getRight();
                if (left == null || right == null) {
                    add(IssueCode.PORT_MALFORMED_BUS_NAME, portLoc(cell, port),
                            "Bus port name is missing a valid [hi:lo] range");
                } else {
                    int implied = Math.abs(left - right) + 1;
                    if (implied != port.getWidth()) {
                        add(IssueCode.PORT_WIDTH_MISMATCH, portLoc(cell, port),
                                "Port width " + port.getWidth() + " != range-implied width "
                                        + implied + " from '" + port.getName() + "'");
                    }
                }
            } catch (NumberFormatException ex) {
                add(IssueCode.PORT_MALFORMED_BUS_NAME, portLoc(cell, port),
                        "Bus port name has a non-numeric index range: '" + port.getName() + "'");
            }
        }
    }

    private void checkCellInst(EDIFCell cell, EDIFCellInst inst) {
        if (isEmpty(inst.getName())) {
            add(IssueCode.EMPTY_OBJECT_NAME, cellLoc(cell), "Cell has an instance with an empty name");
        }
        if (inst.getParentCell() != cell) {
            add(IssueCode.CELLINST_PARENT_BROKEN, instLoc(cell, inst),
                    "Instance's parent-cell back-pointer is broken");
        }
        EDIFCell type = inst.getCellType();
        if (type == null) {
            add(IssueCode.CELLINST_NULL_CELLTYPE, instLoc(cell, inst), "Instance has null cell type");
            return;
        }
        if (netlistCells != null && !netlistCells.contains(type)) {
            add(IssueCode.INST_DANGLING_CELLTYPE, instLoc(cell, inst),
                    "Instance references cell type '" + type.getName()
                            + "' that is not contained in any library of this netlist");
        }
        EDIFName viewref = inst.getViewref();
        if (viewref != null && !viewref.getName().equals(type.getView())) {
            add(IssueCode.VIEWREF_MISMATCH, instLoc(cell, inst),
                    "Instance viewref '" + viewref.getName() + "' != cell type view '"
                            + type.getView() + "'");
        }
        if (inst.isBlackBox() && type.hasContents()) {
            add(IssueCode.BLACKBOX_HAS_CONTENTS, instLoc(cell, inst),
                    "Instance is marked as a black box but its cell type still has contents");
        }
    }

    private void checkNet(EDIFCell cell, EDIFNet net, Map<EDIFPortInst, EDIFNet> portInstNetMap,
            Map<String, EDIFNet> connectedBit) {
        if (isEmpty(net.getName())) {
            add(IssueCode.EMPTY_OBJECT_NAME, cellLoc(cell), "Cell has a net with an empty name");
        }
        if (net.getParentCell() != cell) {
            add(IssueCode.NET_PARENT_BROKEN, netLoc(cell, net),
                    "Net's parent-cell back-pointer is broken");
        }

        int realSources = 0;
        int loptSources = 0;
        int sinks = 0;
        Set<String> seenOnThisNet = new HashSet<>();
        for (EDIFPortInst pi : net.getPortInsts()) {
            // PortInst back-pointer to this net
            if (pi.getNet() != net) {
                add(IssueCode.PORTINST_PARENTNET_BROKEN, portInstLoc(cell, pi),
                        "PortInst's parent-net back-pointer does not refer to the net containing it");
            }
            // duplicate on this net
            String dedupKey = (pi.getCellInst() == null ? "" : pi.getCellInst().getName())
                    + "/" + pi.getName();
            if (!seenOnThisNet.add(dedupKey)) {
                add(IssueCode.PORTINST_DUPLICATE_ON_NET, portInstLoc(cell, pi),
                        "PortInst '" + dedupKey + "' appears more than once on net '"
                                + net.getName() + "'");
            }
            // membership bookkeeping for dual-membership check
            EDIFNet prevNet = portInstNetMap.put(pi, net);
            if (prevNet != null && prevNet != net) {
                add(IssueCode.PORTINST_DUAL_MEMBERSHIP_BROKEN, portInstLoc(cell, pi),
                        "Same PortInst object appears on multiple nets");
            }

            checkPortInst(cell, net, pi);

            // External (instance) port insts must belong to the cellInst's list.
            EDIFCellInst inst = pi.getCellInst();
            if (inst != null) {
                if (inst.getPortInst(pi.getName()) != pi) {
                    add(IssueCode.PORTINST_DUAL_MEMBERSHIP_BROKEN, portInstLoc(cell, pi),
                            "PortInst is on net '" + net.getName()
                                    + "' but not registered on its cell instance '"
                                    + inst.getName() + "'");
                }
                // A given physical port bit must be connected by only one net.
                EDIFNet bitPrev = connectedBit.put(dedupKey, net);
                if (bitPrev != null && bitPrev != net) {
                    add(IssueCode.PORT_BIT_MULTIPLY_CONNECTED, portInstLoc(cell, pi),
                            "Port bit '" + dedupKey + "' is connected by both net '"
                                    + bitPrev.getName() + "' and net '" + net.getName() + "'");
                }
            }

            // Source/sink tally for driver checks. Note INOUT pins are
            // intentionally counted as neither source nor sink, so bidirectional
            // pins never inflate the driver count. Vivado "lopt" logic-optimization
            // output pins are counted separately so they don't masquerade as a
            // genuine second driver (they are an artifact present in valid
            // post-optimization netlists).
            boolean isSource = (pi.isOutput() && !pi.isTopLevelPort())
                    || (pi.isInput() && pi.isTopLevelPort());
            boolean isSink = (pi.isInput() && !pi.isTopLevelPort())
                    || (pi.isOutput() && pi.isTopLevelPort());
            if (isSource) {
                if (isLoptOutputPin(pi)) {
                    loptSources++;
                } else {
                    realSources++;
                }
            }
            if (isSink) {
                sinks++;
            }
        }

        int totalSources = realSources + loptSources;
        if (net.getPortInsts().isEmpty()) {
            add(IssueCode.NET_EMPTY, netLoc(cell, net), "Net has no port instances");
        } else if (realSources > 1) {
            add(IssueCode.NET_MULTI_DRIVER, netLoc(cell, net),
                    "Net has " + realSources + " real drivers (sources)"
                            + (loptSources > 0 ? " plus " + loptSources + " Vivado 'lopt' pin(s)" : ""));
        } else if (totalSources > 1) {
            // realSources <= 1 but an lopt output makes the net appear multi-driven;
            // this is a benign Vivado optimization artifact, reported as INFO.
            add(IssueCode.NET_MULTI_DRIVER_LOPT, netLoc(cell, net),
                    "Net has " + totalSources + " drivers including " + loptSources
                            + " Vivado 'lopt' optimization pin(s)");
        } else if (totalSources == 0 && sinks > 0) {
            add(IssueCode.NET_UNDRIVEN, netLoc(cell, net),
                    "Net drives " + sinks + " sink(s) but has no source");
        }
    }

    /**
     * Identifies a Vivado "lopt" (logic optimization) output pin. These appear as
     * extra output ports (named {@code lopt} or {@code lopt_<n>}) created by Vivado
     * optimization and are not genuine independent drivers, so they are excluded
     * from the real-driver count for {@link IssueCode#NET_MULTI_DRIVER}.
     *
     * @param pi The port instance to test.
     * @return True if this is an output port named lopt / lopt_&lt;n&gt;.
     */
    private static boolean isLoptOutputPin(EDIFPortInst pi) {
        if (pi.getPort() == null || pi.getDirection() != EDIFDirection.OUTPUT) {
            return false;
        }
        String base = pi.getPort().getBusName();
        if (!base.startsWith("lopt")) {
            return false;
        }
        return base.equals("lopt") || base.matches("lopt_\\d+");
    }

    private void checkPortInst(EDIFCell cell, EDIFNet net, EDIFPortInst pi) {
        EDIFPort port = pi.getPort();
        if (port == null) {
            add(IssueCode.PORTINST_NULL_PORT, portInstLoc(cell, pi), "PortInst has null port");
            return;
        }
        EDIFCellInst inst = pi.getCellInst();
        // The port must belong to the correct cell (internal) or cell type (external).
        EDIFCell expectedCell = inst != null ? inst.getCellType() : net.getParentCell();
        if (expectedCell != null && port.getParentCell() != expectedCell) {
            add(IssueCode.PORTINST_PORT_WRONG_CELL, portInstLoc(cell, pi),
                    "PortInst's port belongs to cell '"
                            + (port.getParentCell() == null ? "null" : port.getParentCell().getName())
                            + "' but should belong to '" + expectedCell.getName() + "'");
        }
        // Index vs bus consistency
        if (port.isBus()) {
            if (pi.getIndex() == -1) {
                add(IssueCode.PORTINST_INDEX_MISMATCH, portInstLoc(cell, pi),
                        "PortInst on bus port '" + port.getName() + "' has no index (-1)");
            } else if (pi.getIndex() < 0 || pi.getIndex() >= port.getWidth()) {
                add(IssueCode.PORTINST_INDEX_MISMATCH, portInstLoc(cell, pi),
                        "PortInst index " + pi.getIndex() + " out of range [0," + port.getWidth()
                                + ") for port '" + port.getName() + "'");
            }
        } else if (pi.getIndex() != -1) {
            add(IssueCode.PORTINST_INDEX_MISMATCH, portInstLoc(cell, pi),
                    "PortInst on single-bit port '" + port.getName() + "' has index "
                            + pi.getIndex() + " (expected -1)");
        }
        // Name must be derivable from port + index
        try {
            String expectedName = pi.getPortInstNameFromPort();
            if (!expectedName.equals(pi.getName())) {
                add(IssueCode.PORTINST_NAME_DESYNC, portInstLoc(cell, pi),
                        "PortInst name '" + pi.getName() + "' != name derived from port+index '"
                                + expectedName + "'");
            }
        } catch (RuntimeException ex) {
            add(IssueCode.PORTINST_NAME_DESYNC, portInstLoc(cell, pi),
                    "PortInst name could not be derived from its port+index: " + ex.getMessage());
        }
    }

    private void checkInternalPortMap(EDIFCell cell) {
        Map<String, EDIFNet> internal = cell.getInternalNetMap();
        if (internal.isEmpty()) {
            return;
        }
        for (Map.Entry<String, EDIFNet> e : internal.entrySet()) {
            EDIFNet mapped = e.getValue();
            if (mapped == null) {
                continue;
            }
            if (cell.getNet(mapped.getName()) != mapped) {
                add(IssueCode.INTERNAL_PORTMAP_STALE, cellLoc(cell),
                        "internalPortMap entry '" + e.getKey()
                                + "' points to net '" + mapped.getName()
                                + "' that is not (or no longer) in this cell");
            }
        }
    }

    /* ------------------------------------------------------------------ *
     *  Pass 2: instantiation-count drift + hierarchy + reachability
     * ------------------------------------------------------------------ */

    private void checkInstantiationCountDrift() {
        if (observedInstCounts == null) {
            return;
        }
        for (EDIFCell cell : netlistCells) {
            int observed = observedInstCounts.getOrDefault(cell, 0);
            int cached = cell.getNonHierInstantiationCount();
            if (observed != cached) {
                add(IssueCode.INSTANTIATION_COUNT_DRIFT, cellLoc(cell),
                        "Cached non-hierarchical instantiation count " + cached
                                + " != observed " + observed);
            }
        }
    }

    private void checkHierarchyAndReachability(EDIFNetlist netlist) {
        EDIFDesign design = netlist.getDesign();
        EDIFCell top = design == null ? null : design.getTopCell();
        if (top == null) {
            return;
        }
        // DFS with WHITE/GRAY/BLACK coloring to detect cycles, reachable set as a side effect.
        Map<EDIFCell, Integer> color = new IdentityHashMap<>();
        Set<EDIFCell> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
        detectCycleDFS(top, color, reachable);

        // Reachability: cells in work libraries not reachable from top are unused.
        for (EDIFLibrary lib : netlist.getLibraries()) {
            if (lib.isHDIPrimitivesLibrary()) {
                continue;
            }
            for (EDIFCell cell : lib.getCells()) {
                if (!reachable.contains(cell)) {
                    add(IssueCode.UNREACHABLE_CELL, cellLoc(cell),
                            "Cell is not reachable from the top cell (unused)");
                }
            }
        }
    }

    /**
     * Iterative DFS cycle detection over the cell -> child-cell-type graph.
     * Reports CYCLIC_HIERARCHY when a cell is found on the current DFS path.
     *
     * @return true if any cycle was found.
     */
    private boolean detectCycleDFS(EDIFCell root, Map<EDIFCell, Integer> color, Set<EDIFCell> reachable) {
        final int WHITE = 0, GRAY = 1, BLACK = 2;
        boolean found = false;
        Deque<EDIFCell> path = new ArrayDeque<>();
        Deque<Iterator<EDIFCellInst>> iters = new ArrayDeque<>();

        color.put(root, GRAY);
        reachable.add(root);
        path.push(root);
        iters.push(root.getCellInsts().iterator());

        while (!path.isEmpty()) {
            Iterator<EDIFCellInst> it = iters.peek();
            if (it.hasNext()) {
                EDIFCellInst inst = it.next();
                EDIFCell child = inst.getCellType();
                if (child == null) {
                    continue;
                }
                int c = color.getOrDefault(child, WHITE);
                if (c == GRAY) {
                    add(IssueCode.CYCLIC_HIERARCHY, cellLoc(child),
                            "Cell participates in a cyclic instantiation (instantiated under itself)");
                    found = true;
                } else if (c == WHITE) {
                    color.put(child, GRAY);
                    reachable.add(child);
                    path.push(child);
                    iters.push(child.getCellInsts().iterator());
                }
            } else {
                color.put(path.pop(), BLACK);
                iters.pop();
            }
        }
        return found;
    }

    /* ------------------------------------------------------------------ *
     *  Name-legalization collision helper
     * ------------------------------------------------------------------ */

    /**
     * Detects sibling names that would be emitted as the same EDIF name. The EDIF
     * writer uniquifies <em>renamed</em> names against each other with a "_HDI_"
     * suffix, so two names that both require legalization never actually collide.
     * The remaining real hazard is an already-legal name being shadowed by another
     * name whose legalized form equals it (the legal name is emitted verbatim and
     * is not tracked for uniquification). Therefore a collision is only reported
     * when at least one of the two colliding names is already EDIF-legal.
     */
    private void checkLegalCollision(Map<String, String> seen, String rawName, String location,
            IssueCode code, String kind) {
        if (isEmpty(rawName)) {
            return;
        }
        String legal = EDIFTools.makeNameEDIFCompatible(rawName);
        String prevRaw = seen.putIfAbsent(legal, rawName);
        if (prevRaw != null && !prevRaw.equals(rawName)) {
            boolean currAlreadyLegal = rawName.equals(legal);
            boolean prevAlreadyLegal = prevRaw.equals(legal);
            if (currAlreadyLegal || prevAlreadyLegal) {
                add(code, location, "Two " + kind + " names legalize to the same EDIF name '"
                        + legal + "': '" + prevRaw + "' and '" + rawName + "'"
                        + " (one is already EDIF-legal and would be shadowed)");
            }
        }
    }

    /* ------------------------------------------------------------------ *
     *  Location string helpers
     * ------------------------------------------------------------------ */

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String loc(EDIFNetlist nl) {
        return "netlist:" + nl.getName();
    }

    private static String libLoc(EDIFLibrary lib) {
        return lib.getName();
    }

    private static String cellLoc(EDIFCell cell) {
        EDIFLibrary lib = cell.getLibrary();
        return (lib == null ? "?" : lib.getName()) + "/" + cell.getName();
    }

    private static String portLoc(EDIFCell cell, EDIFPort port) {
        return cellLoc(cell) + ".port:" + port.getName();
    }

    private static String instLoc(EDIFCell cell, EDIFCellInst inst) {
        return cellLoc(cell) + "/" + inst.getName();
    }

    private static String netLoc(EDIFCell cell, EDIFNet net) {
        return cellLoc(cell) + ":" + net.getName();
    }

    private static String portInstLoc(EDIFCell cell, EDIFPortInst pi) {
        String instName = pi.getCellInst() == null ? "<top>" : pi.getCellInst().getName();
        return cellLoc(cell) + "/" + instName + "." + pi.getName();
    }

    /* ------------------------------------------------------------------ *
     *  CLI
     * ------------------------------------------------------------------ */

    private static EDIFNetlist loadNetlist(String path, int maxThreads) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".dcp")) {
            return Design.readCheckpoint(path).getNetlist();
        } else if (lower.endsWith(".bedf") || lower.endsWith(".bin")) {
            return EDIFNetlist.readBinaryEDIF(Paths.get(path));
        }
        return EDIFTools.readEdifFile(Paths.get(path), maxThreads);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("USAGE: NetlistValidator <input.edf|.dcp|.bedf> "
                    + "[--report <out.txt>] [--no-namecheck] [--no-instcount] "
                    + "[--max-per-code <N>] [--threads <N>] [--warnings-as-errors]");
            System.exit(2);
        }

        String input = args[0];
        Path reportFile = null;
        boolean warningsAsErrors = false;
        int maxThreads = Integer.MAX_VALUE;
        NetlistValidator validator = new NetlistValidator();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--report":
                    reportFile = Paths.get(args[++i]);
                    break;
                case "--no-namecheck":
                    validator.checkNameLegalization = false;
                    break;
                case "--no-instcount":
                    validator.checkInstantiationCounts = false;
                    break;
                case "--max-per-code":
                    validator.maxIssuesPerCode = Integer.parseInt(args[++i]);
                    break;
                case "--threads":
                    maxThreads = Integer.parseInt(args[++i]);
                    break;
                case "--warnings-as-errors":
                    warningsAsErrors = true;
                    break;
                default:
                    System.err.println("WARNING: ignoring unknown argument '" + args[i] + "'");
            }
        }

        CodePerfTracker t = new CodePerfTracker("Validate EDIF Netlist");
        t.start("Load");
        EDIFNetlist netlist = loadNetlist(input, maxThreads);
        t.stop().start("Validate");
        ValidationReport report = validator.validate(netlist);
        t.stop();

        PrintStream out = System.out;
        report.print(out);
        if (reportFile != null) {
            report.writeReport(reportFile);
            System.out.println("Report written to " + reportFile);
        }
        t.printSummary();

        boolean failed = !report.isValid() || (warningsAsErrors && report.getWarningCount() > 0);
        System.exit(failed ? 1 : 0);
    }
}
