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

package com.xilinx.rapidwright.edif.optimizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.optimizer.EDIFConstPropEngine.Lut;

/**
 * Writes the optimization report requested with {@link EDIFOptOptions#setReport}: one
 * tab-separated line per change (action, object, cell type, explanation), then the number of
 * each action. Leaf cells and nets are named by their hierarchical names in the input netlist;
 * edits of a cell definition (applying to all its instances) as definition:instance.
 */
class EDIFOptReport {

    private final Writer out;
    private final Map<String, Integer> counts = new TreeMap<>();

    EDIFOptReport(Writer out) {
        this.out = out;
        write("# action\tobject\ttype\tdetail\n");
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void add(String action, String object, String type, String detail) {
        counts.merge(action, 1, Integer::sum);
        write(action + '\t' + object + '\t' + type + '\t' + detail + '\n');
    }

    /** Writes the number of each action and flushes the writer. */
    void finish() {
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            write("# SUMMARY\t" + c.getKey() + '\t' + c.getValue() + '\n');
        }
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reports the decisions of the analysis on leaf cells and nets (before any edit). */
    void addDecisions(EDIFConstPropEngine e) {
        EDIFLeafIndex idx = e.idx;
        Map<Integer, Integer> aliasOf = new HashMap<>();
        for (int i = 0; i < e.aliasLeaf.size(); i++) {
            aliasOf.putIfAbsent(e.aliasLeaf.get(i), i);
        }
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            String name = idx.getLeafName(l);
            String type = idx.getLeafInst(l).getCellType().getName();
            byte r = e.removed[l];
            if (r != EDIFConstPropEngine.ALIVE) {
                add(removedAction(r), name, type, removedDetail(e, l, r, aliasOf.get(l)));
                continue;
            }
            boolean reported = false;
            Lut s = e.lut[l];
            if (s != null && s.changed) {
                add(s.dual || type.equals("LUT" + s.size) ? "LUT_CHANGED" : "LUT_RESIZED", name,
                        type, lutDetail(e, l, s));
                reported = true;
            }
            Map<String, String> props = e.props.get(l);
            if (props != null) {
                for (Map.Entry<String, String> p : props.entrySet()) {
                    String pin = p.getKey().replaceAll("^IS_(.*)_INVERTED$", "$1");
                    int pi = e.findPin(l, pin);
                    Integer inv = pi < 0 || e.absorbedFrom == null ? null : e.absorbedFrom.get(pi);
                    add("PIN_INVERTED", name, type, p.getKey() + "=" + p.getValue()
                            + (inv == null ? "" : ", absorbed inverter " + idx.getLeafName(inv)));
                }
                reported = true;
            }
            if (s == null || !s.changed) {
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                    int orig = idx.getPinNet(p);
                    if (e.pinMovedTo[p] >= 0 && e.resolve(orig) != e.pinNet(p)) {
                        add("PIN_MOVED", name, type, idx.getPinPortInst(p).getName() + " moved to "
                                + netDesc(e, e.pinNet(p)) + absorbedNote(e, p));
                        reported = true;
                    }
                }
            }
            String mark = e.marks.get(l);
            if (mark != null && !reported) {
                add("MARKED", name, type, "OPT_MODIFIED=" + mark + markReason(mark));
            }
        }
        for (int i = 0; i < e.orphanNets.size(); i++) {
            int n = e.orphanNets.get(i);
            add("NET_DISCONNECTED", netDesc(e, n), "-", "all its loads were removed; driver "
                    + driverDesc(e, n) + " kept");
        }
    }

    private static String markReason(String mark) {
        switch (mark) {
            case EDIFConstPropEngine.MARK_SWEEP:
                return " (all loads of an output were swept)";
            case EDIFConstPropEngine.MARK_PROPCONST:
                return " (reconnected: a buffer, inverter or constant net it was on was merged)";
            default:
                return " (reconnected by post-processing)";
        }
    }

    private static String removedAction(byte r) {
        switch (r) {
            case EDIFConstPropEngine.RM_CONST:
                return "REMOVED_CONSTANT";
            case EDIFConstPropEngine.RM_ALIAS:
                return "REMOVED_BUFFER";
            case EDIFConstPropEngine.RM_SWEEP:
                return "SWEPT";
            case EDIFConstPropEngine.RM_DANGLING_PP:
                return "REMOVED_UNLOADED";
            default:
                return "REMOVED_INVERTER";
        }
    }

    private String removedDetail(EDIFConstPropEngine e, int l, byte r, Integer alias) {
        EDIFLeafIndex idx = e.idx;
        String why = e.why == null ? null : e.why.get(l);
        StringBuilder sb = new StringBuilder();
        switch (r) {
            case EDIFConstPropEngine.RM_CONST: {
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                    int n = e.pinNet(p);
                    if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER && n >= 0
                            && e.netVal[n] != EDIFConstPropEngine.UNK) {
                        sb.append(sb.length() == 0 ? "" : ", ").append(idx.getPinPortInst(p)
                                .getName()).append(" constant ").append(constant(e.netVal[n]));
                    }
                }
                if (why != null) {
                    sb.append(": ").append(why);
                } else {
                    sb.append(": constant inputs");
                    appendConstantInputs(e, l, sb);
                }
                break;
            }
            case EDIFConstPropEngine.RM_ALIAS:
                sb.append("buffer");
                break;
            case EDIFConstPropEngine.RM_SWEEP:
                sb.append("no path to a top-level output, preserved cell or net, or black box");
                break;
            case EDIFConstPropEngine.RM_DANGLING_PP:
                sb.append("left without loads by constant propagation");
                break;
            default:
                sb.append(why != null ? why : "inverter absorbed by its loads");
                break;
        }
        if (alias != null) {
            int out = e.aliasOutPin.get(alias);
            int in = e.aliasInPin.get(alias);
            sb.append("; ").append(idx.getPinPortInst(out).getName()).append(" merged into ")
                    .append(netDesc(e, e.resolve(e.aliasTarget.get(alias))));
            if (in >= 0) {
                sb.append(" (from ").append(idx.getPinPortInst(in).getName()).append(')');
            }
        }
        return sb.toString();
    }

    private static String constant(byte v) {
        return v == EDIFConstPropEngine.C1 ? "1" : "0";
    }

    /** Appends the constant inputs of leaf l and where each constant comes from. */
    private void appendConstantInputs(EDIFConstPropEngine e, int l, StringBuilder sb) {
        EDIFLeafIndex idx = e.idx;
        boolean any = false;
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            int n = e.pinNet(p);
            if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER || n < 0
                    || e.netVal[n] == EDIFConstPropEngine.UNK) {
                continue;
            }
            sb.append(any ? ", " : " ").append(idx.getPinPortInst(p).getName()).append('=')
                    .append(constant(e.netVal[n])).append(" from ").append(driverDesc(e, n));
            any = true;
        }
        if (!any) {
            sb.append(" none (constant function)");
        }
    }

    private String lutDetail(EDIFConstPropEngine e, int l, Lut s) {
        EDIFLeafIndex idx = e.idx;
        EDIFCellInst inst = idx.getLeafInst(l);
        EDIFPropertyValue init = inst.getProperty("INIT");
        String newType = s.dual ? inst.getCellType().getName() : "LUT" + s.size;
        StringBuilder sb = new StringBuilder();
        sb.append(inst.getCellType().getName()).append(" INIT=")
                .append(init == null ? "-" : init.getValue()).append(" -> ").append(newType)
                .append(" INIT=").append(EDIFOptEditor.initString(s.bits, s.table)).append(':');
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                continue;
            }
            String pin = idx.getPinPortInst(p).getName();
            int kept = -1;
            for (int i = 0; i < s.size; i++) {
                if (s.srcPin[i] == p) {
                    kept = i;
                }
            }
            int n = e.pinNet(p);
            sb.append(' ').append(pin);
            if (kept >= 0) {
                sb.append("->I").append(kept);
                int orig = idx.getPinNet(p);
                if (orig >= 0 && e.resolve(orig) != n) {
                    sb.append(" (moved to ").append(netDesc(e, n)).append(absorbedNote(e, p))
                            .append(')');
                } else if (e.absorbedFrom != null && e.absorbedFrom.containsKey(p)) {
                    sb.append(" (inverted").append(absorbedNote(e, p)).append(')');
                }
            } else if (n >= 0 && e.netVal[n] != EDIFConstPropEngine.UNK) {
                sb.append(" dropped (constant ").append(constant(e.netVal[n])).append(" from ")
                        .append(driverDesc(e, n)).append(')');
            } else {
                String same = null;
                for (int i = 0; i < s.size && same == null; i++) {
                    if (e.pinNet(s.srcPin[i]) == n) {
                        same = idx.getPinPortInst(s.srcPin[i]).getName();
                    }
                }
                sb.append(same != null ? " dropped (same net as " + same + ")"
                        : " dropped (function does not depend on it)");
            }
            sb.append(',');
        }
        sb.setLength(sb.length() - 1);
        String why = e.why == null ? null : e.why.get(l);
        if (why != null) {
            sb.append("; ").append(why);
        }
        return sb.toString();
    }

    private static String absorbedNote(EDIFConstPropEngine e, int p) {
        Integer inv = e.absorbedFrom == null ? null : e.absorbedFrom.get(p);
        return inv == null ? "" : ", absorbed inverter " + e.idx.getLeafName(inv);
    }

    /** @return The hierarchical name of a net (its first segment). */
    static String netDesc(EDIFConstPropEngine e, int n) {
        EDIFLeafIndex idx = e.idx;
        int seg = idx.getNetSeg(idx.getNetSegStart(n));
        int h = idx.getSegHier(seg);
        String name = idx.getSegEDIFNet(seg).getName();
        return h == 0 ? name : idx.getHierName(h) + "/" + name;
    }

    /** @return A description of the driver of (the alias group root of) net n. */
    private static String driverDesc(EDIFConstPropEngine e, int n) {
        EDIFLeafIndex idx = e.idx;
        int dp = e.netDriver[e.resolve(n)];
        if (dp == -2) {
            return "several drivers";
        }
        if (dp < 0) {
            return "no driver";
        }
        int dl = idx.getPinLeaf(dp);
        if (dl < 0) {
            return "port " + idx.getPinPortInst(dp).getName();
        }
        return idx.getPinName(dp) + " (" + idx.getLeafInst(dl).getCellType().getName() + ")";
    }
}
