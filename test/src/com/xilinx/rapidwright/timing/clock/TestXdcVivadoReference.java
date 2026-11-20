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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.RapidWrightDCP;

/**
 * The object queries of the XDC reader compared with Vivado's. The expected objects (in
 * verilog_ethernet_vivado_queries.txt) are the names of the objects that Vivado 2026.1 returns for the listed queries
 * on the public design verilog_ethernet.dcp of RapidWrightDCP, as printed by xdc_queries.tcl (both files in the test
 * resources of this package); a query that Vivado rejects must not be applied by the reader either.
 * <p>
 * Intended differences:
 * <ul>
 * <li>The netlist has no port instance for an unconnected pin of a primitive (e.g. an MMCM's CLKFBOUTB): the
 * expected pins are those Vivado returns that the netlist has.</li>
 * <li>Properties that Vivado computes (PRIMITIVE_LEVEL) are not supported: a filter using them matches no object,
 * with a warning.</li>
 * <li>The clock network does not derive the clock of an MMCM's feedback output (Vivado's mmcm_clkfb), which clocks
 * no register.</li>
 * </ul>
 */
public class TestXdcVivadoReference {

    private static final String DESIGN = "verilog_ethernet.dcp";

    /** The clocks Vivado derives that the clock network does not (see the class description) */
    private static final List<String> NOT_DERIVED = Collections.singletonList("mmcm_clkfb");

    /** A query and Vivado's result: the sorted names, or their number and MD5, or null for an error */
    private static final class Query {
        final String query;
        final int count;
        final List<String> names;
        final String md5;

        Query(String query, String result) {
            this.query = query;
            if (result == null) {
                count = -1;
                names = null;
                md5 = null;
                return;
            }
            String[] f = result.split(" ");
            count = Integer.parseInt(f[0]);
            if (f.length == 2 && f[1].startsWith("md5:")) {
                names = null;
                md5 = f[1].substring(4);
            } else {
                names = Arrays.asList(f).subList(1, f.length);
                md5 = null;
                Assertions.assertEquals(count, names.size(), query);
            }
        }
    }

    /** The commands of the reference file: queries, and the current_instance commands as text */
    private static List<Object> readReference() throws IOException {
        List<Object> out = new ArrayList<>();
        try (InputStream in = TestXdcVivadoReference.class.getResourceAsStream("verilog_ethernet_vivado_queries.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String query = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("S ")) {
                    out.add(line.substring(2));
                } else if (line.startsWith("Q ")) {
                    query = line.substring(2);
                } else if (line.startsWith("R ")) {
                    out.add(new Query(query, line.substring(2)));
                } else if (line.startsWith("E ")) {
                    out.add(new Query(query, null));
                }
            }
        }
        return out;
    }

    private static String md5(List<String> sortedNames) throws NoSuchAlgorithmException {
        byte[] d = MessageDigest.getInstance("MD5").digest(String.join("\n", sortedNames)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    public void testQueriesMatchVivado() throws Exception {
        Assumptions.assumeTrue(Files.exists(RapidWrightDCP.getPath(DESIGN)), DESIGN + " not available");
        Design d = RapidWrightDCP.loadDCP(DESIGN);
        EDIFNetlist netlist = d.getNetlist();
        // The clock queries resolve against the clocks of the design's constraints
        List<String> xdc = new ArrayList<>();
        for (ConstraintGroup g : new ConstraintGroup[]{ConstraintGroup.EARLY, ConstraintGroup.NORMAL,
                ConstraintGroup.LATE}) {
            List<String> c = d.getXDCConstraints(g);
            if (c != null) {
                xdc.addAll(c);
            }
        }
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));

        // The design's constraints first, so that the queries come after every clock definition, then one "probe"
        // command per query
        List<Object> reference = readReference();
        Map<Integer, Object> results = new HashMap<>();
        Map<String, Integer> warnings = new LinkedHashMap<>();
        XdcInterpreter interp = new XdcInterpreter(new XdcObjectFinder(netlist), (name, args, index) -> {
            if (name.equals("probe")) {
                results.put(index, args.get(0));
            }
        }, warnings);
        interp.evalScript(String.join("\n", xdc));
        int first = interp.getCommandIndex();
        StringBuilder script = new StringBuilder();
        for (Object o : reference) {
            script.append(o instanceof Query ? "probe [" + ((Query) o).query + "]" : o).append('\n');
        }
        interp.evalScript(script.toString());

        int checked = 0;
        for (int i = 0; i < reference.size(); i++) {
            if (!(reference.get(i) instanceof Query)) continue;
            Query q = (Query) reference.get(i);
            Object result = results.get(first + i);
            if (q.count < 0) {
                // Vivado rejects the query: the command using it is not applied
                Assertions.assertNull(result, q.query);
                checked++;
                continue;
            }
            Assertions.assertNotNull(result, q.query + " not applied: " + warnings);
            List<String> actual = new ArrayList<>();
            boolean pins = false;
            boolean clocks = false;
            for (Object o : (List<?>) result) {
                XdcObject x = (XdcObject) o;
                if (x.getKind() == XdcObject.Kind.CLOCK) {
                    clocks = true;
                    for (int id : cn.resolveClocks(Collections.singletonList(x))) {
                        actual.add(cn.getClock(id).getName());
                    }
                } else {
                    actual.add(x.getName());
                    pins |= x.getKind() == XdcObject.Kind.PIN;
                }
            }
            Collections.sort(actual);
            if (q.query.contains("PRIMITIVE_LEVEL")) {
                Assertions.assertEquals(Collections.emptyList(), actual, q.query);
                Assertions.assertTrue(warnings.containsKey(
                        "-filter property PRIMITIVE_LEVEL not in the netlist (empty result)"));
            } else if (q.md5 != null) {
                Assertions.assertEquals(q.count, actual.size(), q.query);
                Assertions.assertEquals(q.md5, md5(actual), q.query);
            } else {
                List<String> expected = new ArrayList<>();
                for (String n : q.names) {
                    if ((!pins || netlist.getHierPortInstFromName(n) != null) && !(clocks && NOT_DERIVED.contains(n))) {
                        expected.add(n);
                    }
                }
                Assertions.assertEquals(expected, actual, q.query);
            }
            checked++;
        }
        Assertions.assertEquals(80, checked);
    }
}
