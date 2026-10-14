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

package com.xilinx.rapidwright.edif.compare;

import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.optimizer.EDIFLeafIndex;
import com.xilinx.rapidwright.edif.optimizer.VerilogLiterals;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Compares two netlists at the leaf-cell level, independent of how nets are named or how they
 * traverse the hierarchy. Each non-static leaf cell is matched by its full hierarchical name and
 * compared by type, properties and, for every pin, the leaf pin (or top-level port, or constant)
 * that drives its physical net. Intended to compare the result of a netlist optimization against
 * a reference (e.g. Vivado's opt_design) result.
 */
public class EDIFLeafNetlistComparator {

    /** Categories of differences. */
    public enum Category {
        /** Leaf only present in the gold netlist. */
        ONLY_GOLD,
        /** Leaf only present in the test netlist. */
        ONLY_TEST,
        /** Same leaf name, different cell type. */
        TYPE,
        /** Same type, a property value differs. */
        PROPERTY,
        /** An input pin is driven differently. */
        PIN_DRIVER,
        /** A pin is connected in one netlist only. */
        PIN_CONNECTED,
        /** LUT whose INIT/pin drivers differ but computes the same function of the same drivers. */
        LUT_EQUIVALENT,
        /** A top-level output port is driven differently. */
        TOP_PORT,
    }

    public static final String CONST0 = "<CONST0>";
    public static final String CONST1 = "<CONST1>";
    public static final String UNDRIVEN = "<UNDRIVEN>";

    private final Map<Category, List<String>> diffs = new TreeMap<>();
    private final Map<Category, Map<String, Integer>> diffsByType = new TreeMap<>();
    private int matched;
    private int goldLeaves;
    private int testLeaves;

    private int maxExamples = 50;



    public void setMaxExamples(int maxExamples) {
        this.maxExamples = maxExamples;
    }

    private void addDiff(Category c, String type, String msg) {
        diffs.computeIfAbsent(c, k -> new ArrayList<>()).add(msg);
        diffsByType.computeIfAbsent(c, k -> new TreeMap<>()).merge(type, 1, Integer::sum);
    }

    public Map<Category, List<String>> getDiffs() {
        return diffs;
    }

    public int getDiffCount(Category c) {
        List<String> l = diffs.get(c);
        return l == null ? 0 : l.size();
    }

    /** @return Number of differences, not counting {@link Category#LUT_EQUIVALENT}. */
    public int getTotalDiffs() {
        int total = 0;
        for (Map.Entry<Category, List<String>> e : diffs.entrySet()) {
            if (e.getKey() != Category.LUT_EQUIVALENT) {
                total += e.getValue().size();
            }
        }
        return total;
    }

    private static boolean isStatic(EDIFCellInst inst) {
        return inst.getCellType().isStaticSource();
    }

    /**
     * Computes a name for whatever drives the net of pin p: a leaf pin name, top-level port name,
     * constant or undriven marker. Results are cached per net in the provided array.
     */
    static String driverOf(EDIFLeafIndex idx, int p, String[] cache) {
        int n = idx.getPinNet(p);
        if (n < 0) {
            return UNDRIVEN;
        }
        if (cache[n] != null) {
            return cache[n];
        }
        TreeSet<String> drivers = null;
        String driver = null;
        for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
            int q = idx.getNetPin(i);
            if (idx.getPinDir(q) != EDIFLeafIndex.DRIVER) {
                continue;
            }
            String d;
            int l = idx.getPinLeaf(q);
            if (l >= 0 && idx.getLeafInst(l).getCellType().isStaticSource()) {
                d = idx.getLeafInst(l).getCellType().isGNDSource() ? CONST0 : CONST1;
            } else {
                d = idx.getPinName(q);
            }
            if (driver == null) {
                driver = d;
            } else if (!driver.equals(d)) {
                if (drivers == null) {
                    drivers = new TreeSet<>();
                    drivers.add(driver);
                }
                drivers.add(d);
            }
        }
        String result = drivers != null ? String.join("|", drivers)
                : driver == null ? UNDRIVEN : driver;
        cache[n] = result;
        return result;
    }

    private static Map<String, Integer> leafMap(EDIFLeafIndex idx) {
        Map<String, Integer> m = new HashMap<>();
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            if (!isStatic(idx.getLeafInst(l))) {
                m.put(idx.getLeafName(l), l);
            }
        }
        return m;
    }

    private static Map<String, String> pinDrivers(EDIFLeafIndex idx, int l, String[] cache) {
        Map<String, String> m = new TreeMap<>();
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                continue;
            }
            m.put(idx.getPinPortInst(p).getName(), driverOf(idx, p, cache));
        }
        return m;
    }

    /**
     * Computes a canonical truth table for a LUT as a function of its distinct named drivers.
     * Returns null if the cell is not a plain LUT1-6.
     */
    static String lutFunction(EDIFCellInst inst, Map<String, String> drivers) {
        String type = inst.getCellType().getName();
        if (!type.matches("LUT[1-6]")) {
            return null;
        }
        int size = type.charAt(3) - '0';
        EDIFPropertyValue initVal = inst.getProperty("INIT");
        if (initVal == null) {
            return null;
        }
        long init = LUTTools.getInitValue(initVal.getValue());
        TreeSet<String> varSet = new TreeSet<>();
        String[] in = new String[size];
        for (int i = 0; i < size; i++) {
            in[i] = drivers.getOrDefault("I" + i, UNDRIVEN);
            if (!in[i].equals(CONST0) && !in[i].equals(CONST1)) {
                varSet.add(in[i]);
            }
        }
        List<String> vars = new ArrayList<>(varSet);
        if (vars.size() > 6) {
            return null;
        }
        long table = 0;
        for (int row = 0; row < (1 << vars.size()); row++) {
            int addr = 0;
            for (int i = 0; i < size; i++) {
                int bit;
                if (in[i].equals(CONST0)) {
                    bit = 0;
                } else if (in[i].equals(CONST1)) {
                    bit = 1;
                } else {
                    bit = (row >> vars.indexOf(in[i])) & 1;
                }
                addr |= bit << i;
            }
            if (((init >>> addr) & 1L) != 0) {
                table |= 1L << row;
            }
        }
        return vars + ":" + Long.toHexString(table);
    }

    /**
     * Compares the gold and test netlists.
     * @param gold The reference netlist.
     * @param test The netlist to check.
     * @return The total number of differences found (excluding equivalent LUTs).
     */
    public int compare(EDIFNetlist gold, EDIFNetlist test) {
        EDIFLeafIndex g = new EDIFLeafIndex(gold);
        EDIFLeafIndex t = new EDIFLeafIndex(test);
        return compare(g, t);
    }

    public int compare(EDIFLeafIndex g, EDIFLeafIndex t) {
        diffs.clear();
        diffsByType.clear();
        matched = 0;
        goldLeaves = 0;
        testLeaves = 0;
        String[] gc = new String[g.getNumNets()];
        String[] tc = new String[t.getNumNets()];
        Map<String, Integer> gm = leafMap(g);
        Map<String, Integer> tm = leafMap(t);
        goldLeaves = gm.size();
        testLeaves = tm.size();
        for (Map.Entry<String, Integer> e : gm.entrySet()) {
            String name = e.getKey();
            EDIFCellInst gi = g.getLeafInst(e.getValue());
            Integer tl = tm.get(name);
            String gType = gi.getCellType().getName();
            if (tl == null) {
                addDiff(Category.ONLY_GOLD, gType, name + " (" + gType + ")");
                continue;
            }
            matched++;
            EDIFCellInst ti = t.getLeafInst(tl);
            String tType = ti.getCellType().getName();
            Map<String, String> gd = pinDrivers(g, e.getValue(), gc);
            Map<String, String> td = pinDrivers(t, tl, tc);
            if (!gType.equals(tType)) {
                String gf = lutFunction(gi, gd);
                if (gf != null && gf.equals(lutFunction(ti, td))) {
                    addDiff(Category.LUT_EQUIVALENT, gType, name + " " + gType + " vs " + tType);
                } else {
                    addDiff(Category.TYPE, gType, name + " " + gType + " vs " + tType + " gold="
                            + describe(gi, gd) + " test=" + describe(ti, td));
                }
                continue;
            }
            boolean lutEquiv = false;
            if (!gd.equals(td) || !Objects.equals(prop(gi, "INIT"), prop(ti, "INIT"))) {
                String gf = lutFunction(gi, gd);
                if (gf != null && gf.equals(lutFunction(ti, td))) {
                    addDiff(Category.LUT_EQUIVALENT, gType, name);
                    lutEquiv = true;
                }
            }
            comparePropsAndPins(name, gType, gi, ti, gd, td, lutEquiv);
        }
        for (Map.Entry<String, Integer> e : tm.entrySet()) {
            if (!gm.containsKey(e.getKey())) {
                String tType = t.getLeafInst(e.getValue()).getCellType().getName();
                addDiff(Category.ONLY_TEST, tType, e.getKey() + " (" + tType + ")");
            }
        }
        compareTopPorts(g, t, gc, tc);
        return getTotalDiffs();
    }

    private static String prop(EDIFCellInst inst, String key) {
        EDIFPropertyValue v = inst.getProperty(key);
        if (v == null && isCaseInsensitive(key)) {
            for (Map.Entry<String, EDIFPropertyValue> e : inst.getPropertiesMap().entrySet()) {
                if (e.getKey().equalsIgnoreCase(key)) {
                    v = e.getValue();
                }
            }
        }
        if (v != null && isCaseInsensitive(key)) {
            Boolean b = VerilogLiterals.parseBoolean(v.getValue());
            return b != null ? b.toString() : v.getValue().toLowerCase();
        }
        return v == null ? null : v.getValue();
    }

    /**
     * Vivado normalizes the case of these attribute names and values when reading a netlist,
     * so they are compared case-insensitively.
     */
    private static boolean isCaseInsensitive(String key) {
        return key.equalsIgnoreCase("DONT_TOUCH") || key.equalsIgnoreCase("MARK_DEBUG")
                || key.equalsIgnoreCase("KEEP") || key.equalsIgnoreCase("KEEP_HIERARCHY");
    }

    private static String describe(EDIFCellInst inst, Map<String, String> drivers) {
        String init = prop(inst, "INIT");
        return (init == null ? "" : init + " ") + drivers;
    }

    private void comparePropsAndPins(String name, String type, EDIFCellInst gi, EDIFCellInst ti,
                                     Map<String, String> gd, Map<String, String> td,
                                     boolean lutEquiv) {
        TreeSet<String> keys = new TreeSet<>();
        for (Map.Entry<String, EDIFPropertyValue> e : gi.getPropertiesMap().entrySet()) {
            keys.add(isCaseInsensitive(e.getKey()) ? e.getKey().toUpperCase() : e.getKey());
        }
        for (Map.Entry<String, EDIFPropertyValue> e : ti.getPropertiesMap().entrySet()) {
            keys.add(isCaseInsensitive(e.getKey()) ? e.getKey().toUpperCase() : e.getKey());
        }
        for (String key : keys) {
            if (lutEquiv && key.equals("INIT")) {
                continue;
            }
            String gv = prop(gi, key);
            String tv = prop(ti, key);
            if (!Objects.equals(gv, tv)) {
                addDiff(Category.PROPERTY, type, name + " " + key + ": " + gv + " vs " + tv);
            }
        }
        if (lutEquiv) {
            return;
        }
        TreeSet<String> pins = new TreeSet<>(gd.keySet());
        pins.addAll(td.keySet());
        for (String pin : pins) {
            String gv = gd.get(pin);
            String tv = td.get(pin);
            if (gv == null || tv == null) {
                if (Objects.equals(gv == null ? UNDRIVEN : gv, tv == null ? UNDRIVEN : tv)) {
                    continue;
                }
                addDiff(Category.PIN_CONNECTED, type, name + "/" + pin + ": " + gv + " vs " + tv);
            } else if (!gv.equals(tv)) {
                addDiff(Category.PIN_DRIVER, type, name + "/" + pin + ": " + gv + " vs " + tv);
            }
        }
    }

    private void compareTopPorts(EDIFLeafIndex g, EDIFLeafIndex t, String[] gc, String[] tc) {
        for (String d : interfaceDiffs(g.getNetlist(), t.getNetlist())) {
            addDiff(Category.TOP_PORT, "port", d);
        }
        Map<String, String> gp = topOutputDrivers(g, gc);
        Map<String, String> tp = topOutputDrivers(t, tc);
        Set<String> ports = new TreeSet<>(gp.keySet());
        ports.addAll(tp.keySet());
        for (String port : ports) {
            String gv = gp.get(port);
            String tv = tp.get(port);
            if (!Objects.equals(gv, tv)) {
                addDiff(Category.TOP_PORT, "port", port + ": " + gv + " vs " + tv);
            }
        }
    }

    /**
     * @return The top-level ports of a netlist: name (with its bus range) to direction and
     * width.
     */
    static Map<String, String> topInterface(EDIFNetlist netlist) {
        Map<String, String> ports = new TreeMap<>();
        for (EDIFPort port : netlist.getTopCell().getPorts()) {
            ports.put(port.getName(), port.getDirection() + " width " + port.getWidth());
        }
        return ports;
    }

    /**
     * @return One message per top-level port that differs between the two netlists (missing in
     * either, or with a different direction or width).
     */
    static List<String> interfaceDiffs(EDIFNetlist gold, EDIFNetlist test) {
        Map<String, String> g = topInterface(gold);
        Map<String, String> t = topInterface(test);
        Set<String> names = new TreeSet<>(g.keySet());
        names.addAll(t.keySet());
        List<String> diffs = new ArrayList<>();
        for (String name : names) {
            if (!Objects.equals(g.get(name), t.get(name))) {
                diffs.add("interface " + name + ": " + g.get(name) + " vs " + t.get(name));
            }
        }
        return diffs;
    }

    private static Map<String, String> topOutputDrivers(EDIFLeafIndex idx, String[] cache) {
        Map<String, String> m = new TreeMap<>();
        for (int p = 0; p < idx.getNumPins(); p++) {
            if (idx.getPinLeaf(p) < 0 && idx.getPinDir(p) != EDIFLeafIndex.DRIVER) {
                EDIFPortInst pi = idx.getPinPortInst(p);
                m.put(pi.getName(), driverOf(idx, p, cache));
            }
        }
        return m;
    }

    /**
     * Prints a summary and up to maxExamples examples per category.
     * @param ps Output stream.
     */
    public void printReport(PrintStream ps) {
        ps.println("Leaf cells (non-static): gold=" + goldLeaves + " test=" + testLeaves
                + " matched=" + matched);
        for (Category c : Category.values()) {
            List<String> l = diffs.getOrDefault(c, Collections.emptyList());
            ps.println(String.format("%-14s %d %s", c, l.size(),
                    l.isEmpty() ? "" : diffsByType.get(c)));
        }
        ps.println("TOTAL (excluding LUT_EQUIVALENT) " + getTotalDiffs());
        for (Map.Entry<Category, List<String>> e : diffs.entrySet()) {
            ps.println("== " + e.getKey());
            List<String> l = e.getValue();
            for (int i = 0; i < Math.min(maxExamples, l.size()); i++) {
                ps.println("  " + l.get(i));
            }
            if (l.size() > maxExamples) {
                ps.println("  ... " + (l.size() - maxExamples) + " more");
            }
        }
    }

    /**
     * Prints every non-static leaf cell with its type, properties and pin drivers, plus the
     * drivers of top-level output ports, sorted by name.
     * @param netlist The netlist to dump.
     * @param ps Output stream.
     */
    public static void dump(EDIFNetlist netlist, PrintStream ps) {
        EDIFLeafIndex idx = new EDIFLeafIndex(netlist);
        String[] cache = new String[idx.getNumNets()];
        Map<String, Integer> m = new TreeMap<>(leafMap(idx));
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            EDIFCellInst inst = idx.getLeafInst(e.getValue());
            StringBuilder sb = new StringBuilder(e.getKey() + " " + inst.getCellType().getName());
            TreeMap<String, String> props = new TreeMap<>();
            for (Map.Entry<String, EDIFPropertyValue> p : inst.getPropertiesMap().entrySet()) {
                String k = p.getKey();
                props.put(isCaseInsensitive(k) ? k.toUpperCase() : k, prop(inst, k));
            }
            for (Map.Entry<String, String> p : props.entrySet()) {
                sb.append(" ").append(p.getKey()).append("=").append(p.getValue());
            }
            sb.append(" ").append(pinDrivers(idx, e.getValue(), cache));
            boolean loaded = false;
            for (int p = idx.getLeafPinStart(e.getValue()); p < idx.getLeafPinEnd(e.getValue());
                 p++) {
                if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                    sb.append(" ").append(idx.getPinPortInst(p).getName()).append("->")
                            .append(idx.getPinNet(p) < 0 ? "x" : "n");
                }
            }
            ps.println(sb);
        }
        ps.println("PORTS " + topOutputDrivers(idx, cache));
    }

    private static String indent(int d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /**
     * Prints the named leaf cells (and the cells driving their pins, to the given depth) of a
     * netlist.
     */
    public static void show(EDIFNetlist netlist, List<String> names, int depth, PrintStream ps) {
        EDIFLeafIndex idx = new EDIFLeafIndex(netlist);
        String[] cache = new String[idx.getNumNets()];
        Map<String, Integer> m = new HashMap<>();
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            m.put(idx.getLeafName(l), l);
        }
        List<String> todo = new ArrayList<>(names);
        TreeSet<String> seen = new TreeSet<>();
        for (int d = 0; d <= depth && !todo.isEmpty(); d++) {
            List<String> next = new ArrayList<>();
            for (String name : todo) {
                if (!seen.add(name)) {
                    continue;
                }
                Integer l = m.get(name);
                if (l == null) {
                    ps.println(indent(d) + name + " <missing>");
                    continue;
                }
                EDIFCellInst inst = idx.getLeafInst(l);
                Map<String, String> drv = pinDrivers(idx, l, cache);
                ps.println(indent(d) + name + " " + inst.getCellType().getName() + " "
                        + new TreeMap<>(inst.getPropertiesMap()) + " " + drv);
                if (d == 0) {
                    for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                        int n = idx.getPinNet(p);
                        if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER || n < 0) {
                            continue;
                        }
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
                            int q = idx.getNetPin(i);
                            if (q != p && count++ < 12) {
                                sb.append(' ').append(idx.getPinName(q));
                            }
                        }
                        ps.println(indent(d) + "  LOADS " + idx.getPinPortInst(p).getName()
                                + " (" + count + "):" + sb);
                    }
                }
                for (String v : drv.values()) {
                    int slash = v.lastIndexOf('/');
                    if (slash > 0) {
                        next.add(v.substring(0, slash));
                    }
                }
            }
            todo = next;
        }
    }

    public static void main(String[] args) {
        if (args.length >= 4 && args[0].equals("-show")) {
            // -show <depth> <edf> <name>...
            List<String> names = new ArrayList<>();
            for (int i = 3; i < args.length; i++) {
                names.add(args[i]);
            }
            show(EDIFTools.readEdifFile(Paths.get(args[2])), names,
                    Integer.parseInt(args[1]), System.out);
            return;
        }
        if (args.length >= 2 && args[0].equals("-dump")) {
            for (int i = 1; i < args.length; i++) {
                System.out.println("### " + args[i]);
                dump(EDIFTools.readEdifFile(Paths.get(args[i])), System.out);
            }
            return;
        }
        if (args.length < 2) {
            System.out.println("USAGE: <gold.edf> <test.edf> [max_examples] | -dump <edf>...");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("Leaf netlist compare", true);
        t.start("Read gold");
        EDIFNetlist gold = EDIFTools.readEdifFile(Paths.get(args[0]));
        t.stop().start("Read test");
        EDIFNetlist test = EDIFTools.readEdifFile(Paths.get(args[1]));
        t.stop().start("Compare");
        EDIFLeafNetlistComparator c = new EDIFLeafNetlistComparator();
        if (args.length > 2) {
            c.setMaxExamples(Integer.parseInt(args[2]));
        }
        c.compare(gold, test);
        t.stop().printSummary();
        c.printReport(System.out);
    }
}
