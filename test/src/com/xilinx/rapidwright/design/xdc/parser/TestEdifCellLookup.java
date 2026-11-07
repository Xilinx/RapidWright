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

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
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
        List<String> wild = List.of("a/x*", "a*/x0", "a*/x?", "a/y", "a/z*", "?b*/x0", "*/x1*", "m/s*/x1", "m/*/y",
                "m/s1/fifo[*].reg", "q/x*", "a/x0/*", "*");
        for (String p : wild) {
            Set<String> got = lookup.getHierCellInstsFromWildcardName(p)
                    .map(EDIFHierCellInst::getFullHierarchicalInstName).collect(Collectors.toCollection(TreeSet::new));
            Assertions.assertEquals(bruteForce(lookup, p, FilenameUtils::wildcardMatch), got, p);
        }
        List<String> rx = List.of("a/x[01]", "a.*/x1.*", "m/s\\d/y", "(?i)A/X0", "m/s1/fifo\\[3\\]\\.reg");
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
        for (String p : List.of("A/X*", "M/S*/X1", "A*/X0", "M/S1/FIFO[*].REG")) {
            Set<String> got = upper.getHierCellInstsFromWildcardName(p)
                    .map(EDIFHierCellInst::getFullHierarchicalInstName).collect(Collectors.toCollection(TreeSet::new));
            Set<String> want = new TreeSet<>();
            for (String n : bruteForce(upper, p, FilenameUtils::wildcardMatch)) want.add(n);
            Assertions.assertFalse(got.isEmpty(), p);
            Assertions.assertEquals(want.size(), got.size(), p);
        }
    }
}
