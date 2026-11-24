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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * The object queries of the XDC reader (XdcObjectFinder, with its netlist index, and the query cache of
 * XdcInterpreter): one test per query form on a hierarchical netlist, and the same results whatever the parallelism.
 */
public class TestXdcObjectFinder {

    /** Groups and flip-flops per group of the large block: more cells than the parallel scan threshold */
    private static final int GROUPS = 40;
    private static final int FLOPS = 512;

    /**
     * top: leaf flip-flops r0, u0x; u0, u1 (blk: r0, r1, d_reg[0], sub); u2 (blk2: r0, sub); sub (inner: q_reg and a
     * flip-flop whose local name contains '/', flat/x_reg); big (GROUPS groups g&lt;i&gt; of FLOPS flip-flops
     * f&lt;j&gt;_reg). In every cell, all clock pins are on a net clk and all data pins on a net dn.
     */
    static EDIFNetlist hierNetlist() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell fdre = Design.getUnisimCell(Unisim.FDRE);
        EDIFTools.ensureCellInLibraries(netlist, fdre);
        EDIFCell inner = new EDIFCell(netlist.getWorkLibrary(), "inner");
        flops(inner, fdre, "q_reg", "flat/x_reg");
        EDIFCell blk = new EDIFCell(netlist.getWorkLibrary(), "blk");
        flops(blk, fdre, "r0", "r1", "d_reg[0]");
        blk.createChildCellInst("sub", inner);
        EDIFCell blk2 = new EDIFCell(netlist.getWorkLibrary(), "blk2");
        flops(blk2, fdre, "r0");
        blk2.createChildCellInst("sub", inner);
        EDIFCell group = new EDIFCell(netlist.getWorkLibrary(), "group");
        String[] names = new String[FLOPS];
        for (int j = 0; j < FLOPS; j++) {
            names[j] = "f" + j + "_reg";
        }
        flops(group, fdre, names);
        EDIFCell bank = new EDIFCell(netlist.getWorkLibrary(), "bank");
        for (int i = 0; i < GROUPS; i++) {
            bank.createChildCellInst("g" + i, group);
        }
        EDIFCell top = netlist.getTopCell();
        flops(top, fdre, "r0", "u0x");
        top.createChildCellInst("u0", blk);
        top.createChildCellInst("u1", blk);
        top.createChildCellInst("u2", blk2);
        top.createChildCellInst("big", bank);
        return netlist;
    }

    private static void flops(EDIFCell parent, EDIFCell fdre, String... names) {
        EDIFNet clk = parent.createNet("clk");
        EDIFNet dn = parent.createNet("dn");
        for (String name : names) {
            EDIFCellInst ci = parent.createChildCellInst(name, fdre);
            clk.createPortInst("C", ci);
            dn.createPortInst("D", ci);
        }
    }

    /** The names of the objects each "probe" command of a script gets, and the warnings */
    private static final class Probe {
        final List<List<String>> results = new ArrayList<>();
        final Map<String, Integer> warnings = new LinkedHashMap<>();

        Probe(EDIFNetlist netlist, String... lines) {
            XdcInterpreter interp = new XdcInterpreter(new XdcObjectFinder(netlist), (name, args, index) -> {
                if (!name.equals("probe")) {
                    return;
                }
                List<String> names = new ArrayList<>();
                for (Object o : (List<?>) args.get(0)) {
                    names.add(((XdcObject) o).getName());
                }
                results.add(names);
            }, warnings);
            interp.evalScript(String.join("\n", lines));
        }
    }

    private static List<String> sorted(List<String> l) {
        List<String> s = new ArrayList<>(l);
        Collections.sort(s);
        return s;
    }

    private static void assertNames(List<String> actual, String... expected) {
        List<String> e = new ArrayList<>(Arrays.asList(expected));
        Collections.sort(e);
        Assertions.assertEquals(e, sorted(actual));
    }

    /**
     * Checks results in order: cells are listed by local name, the local names in the order a depth-first traversal
     * meets them (see XdcObjectFinder.Index)
     */
    private static void assertOrdered(List<String> actual, String... expected) {
        Assertions.assertEquals(Arrays.asList(expected), actual);
    }

    /** One query per line, as "probe [query]", with current_instance lines in between */
    private static final String[] QUERIES = {
            // 0-6: get_cells -hierarchical, by local name (a pattern with '/' matches local names with '/' only)
            "probe [get_cells -hierarchical r0]",
            "probe [get_cells -hierarchical sub/q_reg]",
            "probe [get_cells -hierarchical flat/x_reg]",
            "probe [get_cells -hierarchical flat/x_re?]",
            "probe [get_cells -hierarchical {d_reg[0]}]",
            "probe [get_cells -hierarchical u*]",
            "probe [get_cells -hierarchical *x_reg]",
            // 7-8: get_cells, levels relative to the current instance
            "probe [get_cells u*/r?]",
            "probe [get_cells u0/sub/flat/x_re?]",
            // 9-11: -regexp, -nocase
            "probe [get_cells -regexp {u[01]/r.}]",
            "probe [get_cells -hierarchical -regexp {u[01]/r.}]",
            "probe [get_cells -hierarchical -regexp -nocase {U0/R.}]",
            // 12-14: -filter with a required NAME glob (name index), and without (parallel filter of all cells)
            "probe [get_cells -hierarchical -filter {NAME =~ u*/sub/*}]",
            "probe [get_cells -hierarchical -filter {NAME =~ big/g1*/f1_reg && REF_NAME == FDRE}]",
            "probe [get_cells -hierarchical -filter {REF_NAME == FDRE || NAME =~ u0*}]",
            // 15-19: get_pins
            "probe [get_pins -hierarchical */C]",
            "probe [get_pins -hierarchical -filter {NAME =~ u0/*/D}]",
            "probe [get_pins u0/r0/C]",
            "probe [get_pins -hierarchical -regexp {.*sub/q_reg/D}]",
            "probe [get_pins -hierarchical -regexp -nocase {.*SUB/Q_REG/d}]",
            // 20-21: get_nets
            "probe [get_nets -hierarchical clk]",
            "probe [get_nets -hierarchical -regexp {u0/.*}]",
            // 22-27: below a current instance that is not the top
            "current_instance u0",
            "probe [get_cells -hierarchical r*]",
            "probe [get_cells sub/q_reg]",
            "probe [get_pins -hierarchical */D]",
            "probe [get_nets -hierarchical *]",
            "probe [get_cells -hierarchical -filter {NAME =~ *x_reg}]",
            "probe [get_cells -hierarchical -regexp {sub/.*}]",
            // 28-29 (current_instance is relative to the current instance)
            "current_instance ../big/g3",
            "probe [get_cells -hierarchical f1?_reg]",
            "probe [get_cells -hierarchical *]",
            "current_instance",
            // 30: the first query again, at the top
            "probe [get_cells -hierarchical r0]",
    };

    /** Flip-flops of the netlist */
    private static final int ALL_FLOPS = 2 + 2 * 5 + 3 + GROUPS * FLOPS;

    private static void checkResults(List<List<String>> r) {
        Assertions.assertEquals(31, r.size());
        assertOrdered(r.get(0), "r0", "u1/r0", "u2/r0", "u0/r0");
        // As in Vivado, a -hierarchical pattern does not match a path across levels
        assertNames(r.get(1));
        // A literal local name with '/'
        assertNames(r.get(2), "u0/sub/flat/x_reg", "u1/sub/flat/x_reg", "u2/sub/flat/x_reg");
        assertNames(r.get(3), "u0/sub/flat/x_reg", "u1/sub/flat/x_reg", "u2/sub/flat/x_reg");
        // Brackets are literal
        assertNames(r.get(4), "u0/d_reg[0]", "u1/d_reg[0]");
        assertNames(r.get(5), "u0", "u1", "u2", "u0x");
        assertNames(r.get(6), "u0/sub/flat/x_reg", "u1/sub/flat/x_reg", "u2/sub/flat/x_reg");
        assertNames(r.get(7), "u0/r0", "u0/r1", "u1/r0", "u1/r1", "u2/r0");
        assertNames(r.get(8), "u0/sub/flat/x_reg");
        // Without -hierarchical, -regexp matches the children of the current instance only
        assertNames(r.get(9));
        assertOrdered(r.get(10), "u1/r0", "u0/r0", "u1/r1", "u0/r1");
        assertNames(r.get(11), "u0/r0", "u0/r1");
        assertOrdered(r.get(12), "u1/sub/flat/x_reg", "u2/sub/flat/x_reg", "u0/sub/flat/x_reg", "u1/sub/q_reg",
                "u2/sub/q_reg", "u0/sub/q_reg");
        List<String> g = new ArrayList<>();
        for (int i = 0; i < GROUPS; i++) {
            if (("g" + i).startsWith("g1")) {
                g.add("big/g" + i + "/f1_reg");
            }
        }
        assertNames(r.get(13), g.toArray(new String[0]));
        // All flip-flops, and the hierarchical cells u0 and u0/sub
        Assertions.assertEquals(ALL_FLOPS + 2, r.get(14).size());
        Assertions.assertTrue(r.get(14).contains("u0/sub") && r.get(14).contains("big/g39/f511_reg"));
        Assertions.assertEquals(ALL_FLOPS, r.get(15).size());
        assertOrdered(r.get(16), "u0/r0/D", "u0/r1/D", "u0/d_reg[0]/D", "u0/sub/flat/x_reg/D", "u0/sub/q_reg/D");
        assertNames(r.get(17), "u0/r0/C");
        assertNames(r.get(18), "u0/sub/q_reg/D", "u1/sub/q_reg/D", "u2/sub/q_reg/D");
        assertNames(r.get(19), "u0/sub/q_reg/D", "u1/sub/q_reg/D", "u2/sub/q_reg/D");
        // clk of the top, u0, u1, u2, their subs and the groups
        Assertions.assertEquals(1 + 3 + 3 + GROUPS, r.get(20).size());
        assertNames(r.get(21), "u0/clk", "u0/dn", "u0/sub/clk", "u0/sub/dn");
        // current_instance u0
        assertNames(r.get(22), "u0/r0", "u0/r1");
        assertNames(r.get(23), "u0/sub/q_reg");
        assertNames(r.get(24), "u0/r0/D", "u0/r1/D", "u0/d_reg[0]/D", "u0/sub/q_reg/D", "u0/sub/flat/x_reg/D");
        assertNames(r.get(25), "u0/clk", "u0/dn", "u0/sub/clk", "u0/sub/dn");
        assertNames(r.get(26), "u0/sub/flat/x_reg");
        assertNames(r.get(27), "u0/sub/q_reg", "u0/sub/flat/x_reg");
        // current_instance big/g3
        Assertions.assertEquals(10, r.get(28).size());
        Assertions.assertTrue(r.get(28).contains("big/g3/f15_reg"));
        Assertions.assertEquals(FLOPS, r.get(29).size());
        // The query cache keys on the current instance: the first query at the top again
        Assertions.assertEquals(r.get(0), r.get(30));
    }

    @Test
    public void testQueryForms() {
        EDIFNetlist netlist = hierNetlist();
        Probe p = new Probe(netlist, QUERIES);
        checkResults(p.results);
        Assertions.assertTrue(p.warnings.isEmpty(), p.warnings.toString());
    }

    /**
     * The index and the scans run in the pool of the caller: the same results, in the same order, at 1 to N threads.
     * It takes seconds; the timeout makes a hang in a pool of one thread (see XdcObjectFinder.sort) a failure.
     */
    @Test
    @Timeout(120)
    public void testParallelismIndependent() throws Exception {
        EDIFNetlist netlist = hierNetlist();
        List<List<List<String>>> runs = new ArrayList<>();
        for (int threads : new int[]{1, 2, 8}) {
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                runs.add(pool.submit(() -> new Probe(netlist, QUERIES).results).get());
            } finally {
                pool.shutdown();
            }
        }
        checkResults(runs.get(0));
        Assertions.assertEquals(runs.get(0), runs.get(1));
        Assertions.assertEquals(runs.get(0), runs.get(2));
        // And as the caller's thread runs them (common pool)
        Assertions.assertEquals(runs.get(0), new Probe(netlist, QUERIES).results);
    }

    /**
     * A repeated query gives the same objects (or error) and repeats the warnings of the first evaluation; a query
     * with an unknown option is an error, as in Vivado
     */
    @Test
    public void testQueryCacheRepeatsWarnings() {
        EDIFNetlist netlist = hierNetlist();
        Probe p = new Probe(netlist,
                "probe [get_cells -hierarchical -bogus r0]",
                "probe [get_cells -hierarchical -bogus r0]",
                "probe [get_cells -hierarchical -filter {NOSUCH == x} r0]",
                "probe [get_cells -hierarchical -filter {NOSUCH == x} r0]",
                "probe [get_cells r0]",
                "current_instance u2",
                "probe [get_cells r0]");
        Assertions.assertEquals(2, p.warnings.get("get_cells with an unknown or ambiguous option -bogus (not valid)"));
        Assertions.assertEquals(2, p.warnings.get("probe with an object query that is not valid (ignored)"));
        Assertions.assertEquals(2, p.warnings.get("-filter property NOSUCH not in the netlist (empty result)"));
        Assertions.assertEquals(4, p.results.size());
        assertNames(p.results.get(0));
        Assertions.assertEquals(p.results.get(0), p.results.get(1));
        assertNames(p.results.get(2), "r0");
        assertNames(p.results.get(3), "u2/r0");
    }

    /** Literal names, -nocase without -regexp and patterns that match nothing */
    @Test
    public void testLiteralNames() {
        EDIFNetlist netlist = hierNetlist();
        Probe p = new Probe(netlist,
                "probe [get_cells u0/sub]",
                "probe [get_cells u0/nosuch]",
                "probe [get_cells -hierarchical nosuch*]",
                "probe [get_cells u0/sub/flat/x_reg]",
                "probe [get_pins -hierarchical {d_reg[0]/C}]",
                "probe [get_nets u0/sub/clk]");
        assertNames(p.results.get(0), "u0/sub");
        assertNames(p.results.get(1));
        assertNames(p.results.get(2));
        assertNames(p.results.get(3), "u0/sub/flat/x_reg");
        assertNames(p.results.get(4), "u0/d_reg[0]/C", "u1/d_reg[0]/C");
        assertNames(p.results.get(5), "u0/sub/clk");
    }

    /**
     * A bus name stands for the bits of the bus, as in Vivado: get_pins of a leaf cell's bus (CARRY8 S[7:0], O[7:0],
     * bits 7..4 connected) or of a hierarchical cell's bus port gives its connected bit pins
     */
    @Test
    public void testBusNamePins() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell carry8 = Design.getUnisimCell(Unisim.CARRY8);
        EDIFTools.ensureCellInLibraries(netlist, carry8);
        EDIFCell ip = new EDIFCell(netlist.getWorkLibrary(), "ip");
        ip.createPort("st[3:0]", EDIFDirection.INPUT, 4);
        ip.createPort("en", EDIFDirection.INPUT, 1);
        EDIFCell top = netlist.getTopCell();
        EDIFCellInst c0 = top.createChildCellInst("c0", carry8);
        EDIFCellInst u = top.createChildCellInst("u_ip", ip);
        for (int i = 0; i < 4; i++) {
            EDIFNet n = top.createNet("s" + i);
            n.createPortInst("S", i, c0);
            n.createPortInst("st", i, u);
            top.createNet("o" + i).createPortInst("O", i, c0);
        }
        top.createNet("ci").createPortInst("CI", c0);
        top.createNet("en").createPortInst("en", u);
        Probe p = new Probe(netlist,
                "probe [get_pins c0/S]",
                "probe [get_pins u_ip/st]",
                "probe [get_pins {c0/S[5]}]",
                "probe [get_pins c0/CI]",
                "probe [get_pins c?/O]",
                "probe [get_pins -hierarchical */st]",
                "probe [get_pins c0/D]",
                "probe [get_pins u_ip/en]");
        assertNames(p.results.get(0), "c0/S[4]", "c0/S[5]", "c0/S[6]", "c0/S[7]");
        assertNames(p.results.get(1), "u_ip/st[0]", "u_ip/st[1]", "u_ip/st[2]", "u_ip/st[3]");
        assertNames(p.results.get(2), "c0/S[5]");
        assertNames(p.results.get(3), "c0/CI");
        assertNames(p.results.get(4), "c0/O[4]", "c0/O[5]", "c0/O[6]", "c0/O[7]");
        assertNames(p.results.get(5), "u_ip/st[0]", "u_ip/st[1]", "u_ip/st[2]", "u_ip/st[3]");
        assertNames(p.results.get(6));
        assertNames(p.results.get(7), "u_ip/en");
    }

    /**
     * As in Vivado, a -hierarchical pattern is compared to the local names of the cells, pins' cells and nets at every
     * level: a pattern with '/' only matches local names that contain '/' (flattened hierarchy), not a path
     */
    @Test
    public void testHierarchicalPatternsMatchLocalNames() {
        Probe p = new Probe(hierNetlist(),
                "probe [get_cells -hierarchical u0/*]",
                "probe [get_cells -hierarchical */q_reg]",
                "probe [get_cells -hierarchical *x_reg]",
                "probe [get_pins -hierarchical u0/r0/C]",
                "probe [get_pins -hierarchical flat/x_reg/D]",
                "probe [get_nets -hierarchical u0/clk]",
                "probe [get_nets -hierarchical {sub/*}]",
                "current_instance u0",
                "probe [get_nets -hierarchical clk]");
        assertNames(p.results.get(0));
        assertNames(p.results.get(1));
        assertNames(p.results.get(2), "u0/sub/flat/x_reg", "u1/sub/flat/x_reg", "u2/sub/flat/x_reg");
        assertNames(p.results.get(3));
        assertNames(p.results.get(4), "u0/sub/flat/x_reg/D", "u1/sub/flat/x_reg/D", "u2/sub/flat/x_reg/D");
        assertNames(p.results.get(5));
        assertNames(p.results.get(6));
        assertNames(p.results.get(7), "u0/clk", "u0/sub/clk");
        Assertions.assertTrue(p.warnings.isEmpty(), p.warnings.toString());
    }

    /**
     * A query without a pattern argument matches all objects (get_nets and get_pins too); a pattern argument that
     * holds no pattern (an empty string or object list) matches none, as in Vivado
     */
    @Test
    public void testDefaultAndEmptyPatterns() {
        Probe p = new Probe(hierNetlist(),
                "probe [get_nets]",
                "probe [get_pins]",
                "probe [get_pins *]",
                "probe [get_cells -hierarchical {}]",
                "probe [get_cells [get_cells nosuch]]",
                "probe [get_ports {}]",
                "probe [get_nets -hierarchical -filter {NAME =~ u0/*clk}]",
                "current_instance u0",
                "probe [get_pins]");
        assertNames(p.results.get(0), "clk", "dn");
        List<String> topPins = new ArrayList<>();
        for (String c : new String[]{"r0", "u0x"}) {
            topPins.add(c + "/C");
            topPins.add(c + "/D");
        }
        assertNames(p.results.get(1), topPins.toArray(new String[0]));
        Assertions.assertEquals(p.results.get(1), p.results.get(2));
        for (int i = 3; i <= 5; i++) {
            assertNames(p.results.get(i));
        }
        assertNames(p.results.get(6), "u0/clk", "u0/sub/clk");
        assertNames(p.results.get(7), "u0/r0/C", "u0/r0/D", "u0/r1/C", "u0/r1/D", "u0/d_reg[0]/C", "u0/d_reg[0]/D");
    }

    /**
     * current_instance is relative to the current instance (UG835), ".." going up; an instance that does not exist
     * leaves it unchanged, as in Vivado
     */
    @Test
    public void testCurrentInstanceRelative() {
        Probe p = new Probe(hierNetlist(),
                "current_instance u0",
                "current_instance sub",
                "probe [get_cells q_reg]",
                "current_instance ..",
                "probe [get_cells r?]",
                "current_instance ../u2/sub",
                "probe [get_cells q_reg]",
                "current_instance nosuch",
                "probe [get_cells q_reg]",
                "current_instance",
                "current_instance /u1",
                "probe [get_cells r0]");
        assertNames(p.results.get(0), "u0/sub/q_reg");
        assertNames(p.results.get(1), "u0/r0", "u0/r1");
        assertNames(p.results.get(2), "u2/sub/q_reg");
        assertNames(p.results.get(3), "u2/sub/q_reg");
        assertNames(p.results.get(4), "r0");
        Assertions.assertEquals(2, p.warnings.get("current_instance of a cell that is not found (unchanged)"));
    }

    /**
     * -filter: a trailing token or an unbalanced parenthesis is a syntax error; == is case-sensitive; a property that
     * no cell of the netlist has (e.g. one that Vivado computes) matches nothing, also when negated; all as in Vivado
     */
    @Test
    public void testFilterErrors() {
        Probe p = new Probe(hierNetlist(),
                "probe [get_cells -hierarchical -filter {REF_NAME == FDRE garbage}]",
                "probe [get_cells -hierarchical -filter {(REF_NAME == FDRE}]",
                "probe [get_cells -hierarchical -filter {REF_NAME == FDRE)}]",
                "probe [get_cells -hierarchical -filter {REF_NAME == fdre}]",
                "probe [get_cells -hierarchical -filter {PRIMITIVE_LEVEL != INTERNAL}]",
                "probe [get_cells -hierarchical -filter {IS_SEQUENTIAL && CLASS != cell}]",
                "probe [get_cells -hierarchical -filter {NAME =~ u0/* && !(NOSUCH_PROP == x)}]",
                "probe [get_cells u0/* -filter {ref_name == blk2 || REF_NAME == inner}]");
        // A syntax error is an error of the query: the command using it is not applied
        Assertions.assertEquals(5, p.results.size());
        for (int i = 0; i < 4; i++) {
            assertNames(p.results.get(i));
        }
        assertNames(p.results.get(4), "u0/sub");
        Assertions.assertEquals(3, p.warnings.get("get_cells -filter with a syntax error (not valid)"));
        Assertions.assertEquals(3, p.warnings.get("probe with an object query that is not valid (ignored)"));
        Assertions.assertEquals(1, p.warnings.get("-filter property PRIMITIVE_LEVEL not in the netlist (empty result)"));
        Assertions.assertEquals(1, p.warnings.get("-filter property CLASS not in the netlist (empty result)"));
        Assertions.assertEquals(1, p.warnings.get("-filter property NOSUCH_PROP not in the netlist (empty result)"));
    }

    /**
     * The pins of a net: those on the net itself (of the cells at its level, leaf or hierarchical), or with -leaf
     * the leaf pins of the whole net across the hierarchy, as in Vivado
     */
    @Test
    public void testPinsOfNets() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell fdre = Design.getUnisimCell(Unisim.FDRE);
        EDIFTools.ensureCellInLibraries(netlist, fdre);
        EDIFCell blk = new EDIFCell(netlist.getWorkLibrary(), "blk");
        blk.createPort("ck", EDIFDirection.INPUT, 1);
        EDIFNet inner = blk.createNet("ck");
        inner.createPortInst(blk.getPort("ck"));
        inner.createPortInst("C", blk.createChildCellInst("q_reg", fdre));
        EDIFCell top = netlist.getTopCell();
        EDIFNet clk = top.createNet("clk");
        clk.createPortInst("C", top.createChildCellInst("r0", fdre));
        clk.createPortInst("ck", top.createChildCellInst("u0", blk));
        Probe p = new Probe(netlist,
                "probe [get_pins -of_objects [get_nets clk]]",
                "probe [get_pins -leaf -of_objects [get_nets clk]]");
        assertNames(p.results.get(0), "r0/C", "u0/ck");
        assertNames(p.results.get(1), "r0/C", "u0/q_reg/C");
    }

    /**
     * Vivado's names of processing system clock pins (PMCRCLKCLKINT[i] for the netlist's PMCRCLKCLK[i]) apply to
     * literal pin names of processing system primitives only, never to patterns or negated filters
     */
    @Test
    public void testPinAlias() {
        EDIFNetlist netlist = hierNetlist();
        EDIFCell ps = new EDIFCell(netlist.getHDIPrimitivesLibrary(), "PS9");
        ps.createPort("PMCRCLKCLK[1:0]", EDIFDirection.OUTPUT, 2);
        EDIFCellInst psInst = netlist.getTopCell().createChildCellInst("ps", ps);
        for (int i = 0; i < 2; i++) {
            netlist.getTopCell().createNet("pmc" + i).createPortInst("PMCRCLKCLK", i, psInst);
        }
        Probe p = new Probe(netlist,
                "probe [get_pins {ps/PMCRCLKCLKINT[0]}]",
                "probe [get_pins {ps/PMCRCLKCLKINT[*]}]",
                "probe [get_pins -of_objects [get_cells ps] -filter {REF_PIN_NAME == PMCRCLKCLKINT[1]}]",
                "probe [get_pins u0/r0/CINT]",
                "probe [get_pins u0/r0/*INT]",
                "probe [get_pins -hierarchical *INT]",
                "probe [get_pins {ps/*INT[0]}]",
                "probe [get_pins -of_objects [get_cells u0/r0] -filter {REF_PIN_NAME =~ *INT}]",
                "probe [get_pins -of_objects [get_cells u0/r0] -filter {REF_PIN_NAME != CINT}]");
        assertNames(p.results.get(0), "ps/PMCRCLKCLK[0]");
        assertNames(p.results.get(1), "ps/PMCRCLKCLK[0]", "ps/PMCRCLKCLK[1]");
        assertNames(p.results.get(2), "ps/PMCRCLKCLK[1]");
        for (int i = 3; i <= 7; i++) {
            assertNames(p.results.get(i));
        }
        assertNames(p.results.get(8), "u0/r0/C", "u0/r0/D");
    }

    /** [list ...] of object queries is one list of the objects; an unquoted [*] in a name is a bus index */
    @Test
    public void testListAndUnquotedBusIndex() {
        Probe p = new Probe(hierNetlist(),
                "probe [list [get_cells u0/r0] [get_cells u1/r0]]",
                "probe [get_pins -of_objects [list [get_cells u0/r0] [get_cells u1/r1]] -filter {REF_PIN_NAME == C}]",
                "probe [get_cells u0/d_reg[*]]",
                "probe [get_cells u1/d_reg[0]]");
        assertNames(p.results.get(0), "u0/r0", "u1/r0");
        assertNames(p.results.get(1), "u0/r0/C", "u1/r1/C");
        assertNames(p.results.get(2), "u0/d_reg[0]");
        assertNames(p.results.get(3), "u1/d_reg[0]");
        Assertions.assertTrue(p.warnings.isEmpty(), p.warnings.toString());
    }

    /**
     * A query that Vivado rejects (a pattern with -of_objects, -nocase without -regexp) or that uses an undefined
     * variable or an unsupported command makes the command using it not applied, with a warning
     */
    @Test
    public void testInvalidQueriesDropTheCommand() {
        Probe p = new Probe(hierNetlist(),
                "probe [get_cells -of_objects [get_pins u0/r0/C] r*]",
                "probe [get_pins -of_objects [get_cells u0/r0] C]",
                "probe [get_cells -nocase U0]",
                "probe [get_cells -hierarchical $undefined]",
                "probe [get_cells -hierarchical [lindex $regs 0]]",
                "probe [get_cells -hierarchical [all_registers]]",
                "probe [get_cells -hierarchical [get_cells $undefined; get_cells *]]",
                "probe [get_cells \"u0/$undefined\"]",
                "set x [get_cells $undefined]",
                "probe [get_cells $x]",
                "probe [get_cells -regexp -nocase U0]");
        Assertions.assertEquals(1, p.results.size());
        assertNames(p.results.get(0), "u0");
        Assertions.assertEquals(3, p.warnings.get("probe with an object query that is not valid (ignored)"));
        Assertions.assertEquals(1, p.warnings.get("get_cells -of_objects with a pattern (not valid)"));
        Assertions.assertEquals(1, p.warnings.get("get_pins -of_objects with a pattern (not valid)"));
        Assertions.assertEquals(1, p.warnings.get("get_cells -nocase without -regexp (not valid)"));
        Assertions.assertEquals(5, p.warnings.get("probe with an undefined Tcl variable (ignored)"));
        Assertions.assertEquals(1, p.warnings.get("set with an undefined Tcl variable (ignored)"));
        Assertions.assertEquals(1, p.warnings.get("probe with an unsupported nested command (ignored)"));
        Assertions.assertEquals(1, p.warnings.get("unsupported nested command all_registers"));
    }
}
