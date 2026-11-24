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

package com.xilinx.rapidwright.design.xdc.parser;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.xdc.XDCParser;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

public class TestEdifCellLookup {

    @Test
    public void testWildcardMatchAsFilenameUtils() {
        String[] names = {"", "a", "ab", "abc", "a_1", "x0", "x10", "fifo[3].reg", "**", "a?b", "aaa", "abab"};
        String[] patterns = {"", "*", "?", "a", "a*", "*b", "a?", "?b*", "*a*b*", "**", "x?", "x*0", "a*a",
                "fifo[*].reg", "*?", "?*?", "ab*ab", "a**b", "*b*", "a*b?", "**?b"};
        for (String n : names) {
            for (String p : patterns) {
                Assertions.assertEquals(FilenameUtils.wildcardMatch(n, p), EdifCellLookup.wildcardMatch(n, p),
                        "name '" + n + "' pattern '" + p + "'");
            }
        }
    }

    private static EDIFNetlist netlist() {
        Design d = new Design("lookup", "xcvu3p-ffvc1517-2-e");
        EDIFNetlist nl = d.getNetlist();
        EDIFCell top = nl.getTopCell();
        EDIFCell sub = new EDIFCell(nl.getWorkLibrary(), "sub");
        for (String c : new String[] {"x0", "x1", "x10", "y", "fifo[3].reg"}) {
            sub.createChildCellInst(c, nl.getHDIPrimitive(Unisim.FDRE));
        }
        EDIFCell mid = new EDIFCell(nl.getWorkLibrary(), "mid");
        mid.createChildCellInst("s0", sub);
        mid.createChildCellInst("s1", sub);
        for (String c : new String[] {"a", "ab", "a_1"}) {
            top.createChildCellInst(c, sub);
        }
        top.createChildCellInst("m", mid);
        return nl;
    }

    /** @return The names of all cells whose name, level by level, the filter accepts. */
    private static Set<String> bruteForce(EdifCellLookup<EDIFHierCellInst> lookup, String pattern, BiPredicate<String, String> match) {
        String[] parts = pattern.split("/");
        Set<String> out = new TreeSet<>();
        for (EDIFHierCellInst ci : (Iterable<EDIFHierCellInst>) lookup.getAllCellInsts()::iterator) {
            String name = lookup.getAbsoluteOriginalName(ci);
            if (name.isEmpty()) continue;
            String[] n = name.split("/");
            if (n.length != parts.length) continue;
            boolean ok = true;
            for (int i = 0; i < n.length && ok; i++) ok = match.test(n[i], parts[i]);
            if (ok) out.add(name);
        }
        return out;
    }

    @Test
    public void testWildcardAndRegexpNamesAsFullScan() {
        EDIFNetlist nl = netlist();
        RegularEdifCellLookup lookup = new RegularEdifCellLookup(nl);
        List<String> wild = Arrays.asList("a/x*", "a*/x0", "a*/x?", "a/y", "a/z*", "?b*/x0", "*/x1*", "m/s*/x1",
                "m/*/y", "m/s1/fifo[*].reg", "q/x*", "a/x0/*", "*");
        for (String p : wild) {
            Set<String> got = lookup.getHierCellInstsFromWildcardName(p)
                    .map(EDIFHierCellInst::getFullHierarchicalInstName).collect(Collectors.toCollection(TreeSet::new));
            Assertions.assertEquals(bruteForce(lookup, p, FilenameUtils::wildcardMatch), got, p);
        }
        List<String> rx = Arrays.asList("a/x[01]", "a.*/x1.*", "m/s\\d/y", "(?i)A/X0", "m/s1/fifo\\[3\\]\\.reg");
        for (String p : rx) {
            Set<String> got = lookup.getHierCellInstsFromRegexpName(p)
                    .map(EDIFHierCellInst::getFullHierarchicalInstName).collect(Collectors.toCollection(TreeSet::new));
            Assertions.assertEquals(bruteForce(lookup, p, String::matches), got, p);
        }
    }

    /**
     * A subclass of RegularEdifCellLookup with other original names (here upper case) gets the generic lookup:
     * the by-name shortcuts of the base class do not apply to it.
     */
    @Test
    public void testSubclassWithOtherNames() {
        EDIFNetlist nl = netlist();
        RegularEdifCellLookup upper = new RegularEdifCellLookup(nl) {
            @Override
            public String getRelativeOriginalName(EDIFHierCellInst current) {
                return current.getInst().getName().toUpperCase();
            }

            @Override
            public String getAbsoluteOriginalName(EDIFHierCellInst current) {
                return current.getFullHierarchicalInstName().toUpperCase();
            }
        };
        for (String p : Arrays.asList("A/X*", "M/S*/X1", "A*/X0", "M/S1/FIFO[*].REG")) {
            Set<String> got = upper.getHierCellInstsFromWildcardName(p)
                    .map(EDIFHierCellInst::getFullHierarchicalInstName).collect(Collectors.toCollection(TreeSet::new));
            Set<String> want = new TreeSet<>();
            for (String n : bruteForce(upper, p, FilenameUtils::wildcardMatch)) want.add(n);
            Assertions.assertFalse(got.isEmpty(), p);
            Assertions.assertEquals(want.size(), got.size(), p);
        }
    }

    /** A lookup that counts the children lists it is asked for (one per cell and walk). */
    private static final class CountingLookup extends RegularEdifCellLookup {
        final AtomicInteger childLists = new AtomicInteger();

        CountingLookup(EDIFNetlist netlist) {
            super(netlist);
        }

        @Override
        public Stream<? extends EDIFHierCellInst> getChildrenOf(EDIFHierCellInst f) {
            childLists.incrementAndGet();
            return super.getChildrenOf(f);
        }
    }

    private static List<String> names(Stream<EDIFHierCellInst> cells) {
        return cells.map(EDIFHierCellInst::getFullHierarchicalInstName).collect(Collectors.toList());
    }

    private static final int CELLS = 1 + 3 * 6 + 1 + 2 * 6;
    private static final List<String> FDRE_QUERIES = Arrays.asList(
            "set a [get_cells -hierarchical -filter {(ORIG_REF_NAME == FDRE || REF_NAME == FDRE)}]",
            "set b [get_cells -hierarchical -filter {(ORIG_REF_NAME == FDRE || REF_NAME == FDRE)}]");

    /**
     * Without caching getAllCellInsts walks the hierarchy on every call; with caching once, until the cache is
     * cleared or caching is turned off. The order is that of allCellInsts(root) either way.
     */
    @Test
    public void testAllCellInstsWalkedOnceWhileCached() {
        EDIFNetlist nl = netlist();
        RegularEdifCellLookup plain = new RegularEdifCellLookup(nl);
        List<String> want = names(plain.allCellInsts(plain.getRoot()));
        Assertions.assertEquals(CELLS, want.size());
        CountingLookup lookup = new CountingLookup(nl);
        Assertions.assertFalse(lookup.isCacheAllCellInsts());
        Assertions.assertEquals(want, names(lookup.getAllCellInsts()));
        Assertions.assertEquals(want, names(lookup.getAllCellInsts()));
        Assertions.assertEquals(2 * CELLS, lookup.childLists.get());
        lookup.setCacheAllCellInsts(true);
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(want, names(lookup.getAllCellInsts()));
            Assertions.assertEquals(3 * CELLS, lookup.childLists.get());
        }
        lookup.clearAllCellInstsCache();
        Assertions.assertEquals(want, names(lookup.getAllCellInsts()));
        Assertions.assertEquals(4 * CELLS, lookup.childLists.get());
        lookup.setCacheAllCellInsts(false);
        Assertions.assertEquals(want, names(lookup.getAllCellInsts()));
        Assertions.assertEquals(5 * CELLS, lookup.childLists.get());
    }

    /** getAllCellInsts goes through allCellInsts, so a subclass that prunes the walk is honoured. */
    @Test
    public void testAllCellInstsOverrideHonoured() {
        EDIFNetlist nl = netlist();
        RegularEdifCellLookup pruned = new RegularEdifCellLookup(nl) {
            @Override
            public Stream<EDIFHierCellInst> allCellInsts(EDIFHierCellInst ci) {
                // (cells of type "mid" without their contents)
                if (ci.getCellType().getName().equals("mid")) return Stream.of(ci);
                return super.allCellInsts(ci);
            }
        };
        for (boolean cache : new boolean[] {false, true}) {
            pruned.setCacheAllCellInsts(cache);
            List<String> got = names(pruned.getAllCellInsts());
            Assertions.assertEquals(1 + 3 * 6 + 1, got.size());
            Assertions.assertTrue(got.contains("m"));
            Assertions.assertFalse(got.contains("m/s0"));
        }
    }

    /**
     * XDCParser.parseXDC caches the list of all cells for one parse: one walk for all its queries, released when
     * the parse ends, so a lookup reused across parses sees the netlist as it is at each parse.
     */
    @Test
    public void testParseXdcCachesForOneParse() {
        EDIFNetlist nl = netlist();
        CountingLookup lookup = new CountingLookup(nl);
        XDCParser.parseXDC(nl.getDevice(), FDRE_QUERIES, lookup);
        Assertions.assertEquals(CELLS, lookup.childLists.get());
        Assertions.assertFalse(lookup.isCacheAllCellInsts());
        // (released: a new walk)
        Assertions.assertEquals(CELLS, lookup.getAllCellInsts().count());
        Assertions.assertEquals(2 * CELLS, lookup.childLists.get());
        nl.getTopCell().createChildCellInst("z", nl.getHDIPrimitive(Unisim.FDRE));
        XDCParser.parseXDC(nl.getDevice(), FDRE_QUERIES, lookup);
        Assertions.assertEquals(3 * CELLS + 1, lookup.childLists.get());
    }

    /** A parse that fails still turns the caching off and releases the list. */
    @Test
    public void testParseXdcFailureReleasesCache() {
        EDIFNetlist nl = netlist();
        CountingLookup lookup = new CountingLookup(nl);
        List<String> xdc = Arrays.asList(FDRE_QUERIES.get(0), "no_such_command_in_any_xdc 1");
        Assertions.assertThrows(RuntimeException.class, () -> XDCParser.parseXDC(nl.getDevice(), xdc, lookup));
        Assertions.assertEquals(CELLS, lookup.childLists.get());
        Assertions.assertFalse(lookup.isCacheAllCellInsts());
        lookup.getAllCellInsts().count();
        Assertions.assertEquals(2 * CELLS, lookup.childLists.get());
    }

    /**
     * A caller that turned caching on keeps one list across its parses (one walk for both); turning it off
     * drops the list.
     */
    @Test
    public void testCallerCachesAcrossParses() {
        EDIFNetlist nl = netlist();
        CountingLookup lookup = new CountingLookup(nl);
        lookup.setCacheAllCellInsts(true);
        XDCParser.parseXDC(nl.getDevice(), FDRE_QUERIES, lookup);
        XDCParser.parseXDC(nl.getDevice(), FDRE_QUERIES, lookup);
        Assertions.assertTrue(lookup.isCacheAllCellInsts());
        Assertions.assertEquals(CELLS, lookup.childLists.get());
        lookup.setCacheAllCellInsts(false);
        Assertions.assertEquals(CELLS, lookup.getAllCellInsts().count());
        Assertions.assertEquals(2 * CELLS, lookup.childLists.get());
    }
}
