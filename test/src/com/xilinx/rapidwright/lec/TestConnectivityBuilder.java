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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;

/**
 * Checks that the tool's own electrical node structure agrees with
 * RapidWright's.
 *
 * A custom connectivity pass exists only because RapidWright's alias traversal
 * raises an error on multi-driver nets, which do occur.
 * On a design that happens to be single driver throughout, the two must
 * therefore produce exactly the same partition of hierarchical nets. That makes
 * {@code EDIFNetlist.getParentNetMap} a free and rather strong oracle.
 */
public class TestConnectivityBuilder {

    /**
     * Reads the logical netlist out of a checkpoint the same way the tool does,
     * without loading a device or the physical netlist. Besides matching the
     * code path under test, this keeps the tests runnable without the device
     * database files.
     */
    static EDIFNetlist loadLogical(String dcpName) {
        LecConfig config = new LecConfig();
        return NetlistLoader.load(RapidWrightDCP.getPath(dcpName), config,
                new LecLog(false, new PrintStream(new ByteArrayOutputStream())));
    }

    /**
     * Builds the tool's view of a netlist and returns each hierarchical net name
     * mapped to the identifier of the electrical node it belongs to.
     */
    private static Map<String, Integer> buildNodeMap(EDIFNetlist netlist) {
        LecLog log = new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
        FlatNetlist flat = FlatNetlist.build(netlist, log);
        Connectivity conn = ConnectivityBuilder.build(flat, log);
        Map<String, Integer> nodeOf = new HashMap<>();
        for (int instId = 0; instId < flat.getInstCount(); instId++) {
            if (flat.isLeaf(instId)) {
                continue;
            }
            String prefix = flat.getInstName(instId);
            CellIndex index = flat.getIndex(instId);
            for (EDIFNet net : flat.getCellType(instId).getNets()) {
                int root = conn.getRoot(flat.getGlobalNet(instId, index.localNetIndex(net)));
                String hierName = prefix.isEmpty() ? net.getName()
                        : prefix + EDIFTools.EDIF_HIER_SEP + net.getName();
                nodeOf.put(hierName, root);
            }
        }
        return nodeOf;
    }

    /**
     * Asserts that grouping nets by this tool's node identifier yields exactly
     * the same partition as grouping them by RapidWright's parent net name.
     *
     * Equality of partitions is checked in both directions. Checking only one
     * would pass a builder that merged everything into a single node.
     */
    private static void assertSamePartition(Map<String, Integer> mine,
            Map<String, String> theirs) {
        Map<Integer, String> mineToTheirs = new HashMap<>();
        Map<String, Integer> theirsToMine = new HashMap<>();
        int compared = 0;
        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            String theirParent = theirs.get(e.getKey());
            if (theirParent == null) {
                continue;
            }
            compared++;
            String previousParent = mineToTheirs.putIfAbsent(e.getValue(), theirParent);
            if (previousParent != null) {
                Assertions.assertEquals(previousParent, theirParent,
                        "Net '" + e.getKey() + "' shares a node with nets RapidWright places in "
                                + "different parent nets");
            }
            Integer previousNode = theirsToMine.putIfAbsent(theirParent, e.getValue());
            if (previousNode != null) {
                Assertions.assertEquals(previousNode, e.getValue(),
                        "Net '" + e.getKey() + "' was split from other nets RapidWright places "
                                + "in parent net '" + theirParent + "'");
            }
        }
        Assertions.assertTrue(compared > 0, "No nets were compared");
    }

    @ParameterizedTest
    @ValueSource(strings = { "picoblaze_ooc_X10Y235.dcp", "bnn.dcp", "ramb18.dcp",
            "routethru_luts.dcp", "versal_cout_hq2.dcp" })
    public void testAgreesWithParentNetMap(String dcp) {
        EDIFNetlist netlist = loadLogical(dcp);
        Map<String, String> theirs;
        try {
            theirs = netlist.getParentNetMapNames();
        } catch (RuntimeException e) {
            Assertions.assertTrue(false, "Fixture " + dcp + " is not single driver, so it cannot "
                    + "be used as an oracle: " + e.getMessage());
            return;
        }
        assertSamePartition(buildNodeMap(netlist), theirs);
    }

    /**
     * A checkpoint containing protected IP has an encrypted logical netlist that
     * no EDIF parser can read. RapidWright ships the plain netlist beside it in
     * a {@code <checkpoint>.edf} directory, together with the still-encrypted
     * cells as separate files. The loader must fall back to that plain netlist
     * rather than failing, and the cells that remain encrypted must surface as
     * black boxes rather than being silently mistaken for logic.
     */
    @Test
    public void testEncryptedCheckpointFallsBackToCompanionEdif() {
        EDIFNetlist netlist = loadLogical("multiply_ip.dcp");
        Assertions.assertNotNull(netlist, "Encrypted checkpoint produced no netlist");

        LecLog log = new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
        FlatNetlist flat = FlatNetlist.build(netlist, log);
        Connectivity conn = ConnectivityBuilder.build(flat, log);
        CellClassifier classifier = new CellClassifier(Series.Versal, new LecConfig(), log);
        NetlistStats stats = NetlistStats.collect("REF", flat, conn, classifier, log);

        Assertions.assertTrue(stats.getLeafCount() > 0, "No leaf cells were found");
        Assertions.assertTrue(stats.getBlackBoxTypeCounts().containsKey(
                "mult_gen_0_mult_gen_v12_0_21"),
                "The encrypted IP cell should be reported as a black box, but the black box "
                        + "types were " + stats.getBlackBoxTypeCounts().keySet());
    }

    /**
     * The same netlist read through the checkpoint path and read directly as an
     * EDIF file must produce the same electrical node structure, since the two
     * readers differ only in where the bytes come from.
     */
    @Test
    public void testEdifAndCheckpointAgree() {
        EDIFNetlist fromDcp = loadLogical("multiply_ip.dcp");
        EDIFNetlist fromEdif = EDIFTools.readEdifFile(
                RapidWrightDCP.getPath("multiply_ip.dcp.edf").resolve("multiply_ip.edf"));
        Map<String, Integer> viaDcp = buildNodeMap(fromDcp);
        Map<String, Integer> viaEdif = buildNodeMap(fromEdif);
        Assertions.assertEquals(viaDcp.keySet(), viaEdif.keySet(),
                "The two readers disagree on which hierarchical nets exist");
        Map<Integer, Integer> mapping = new HashMap<>();
        for (Map.Entry<String, Integer> e : viaDcp.entrySet()) {
            Integer previous = mapping.putIfAbsent(e.getValue(), viaEdif.get(e.getKey()));
            if (previous != null) {
                Assertions.assertEquals(previous, viaEdif.get(e.getKey()),
                        "Net '" + e.getKey() + "' groups differently via EDIF than via DCP");
            }
        }
    }

    /**
     * Every leaf pin that is attached to a net must resolve to the same node as
     * the net it is attached to, and an unattached pin must resolve to nothing.
     * This guards the precomputed pin to node table, which cone construction
     * relies on for every input pin in the design.
     */
    @Test
    public void testPinRootMatchesNetRoot() {
        EDIFNetlist netlist = loadLogical("picoblaze_ooc_X10Y235.dcp");
        LecLog log = new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
        FlatNetlist flat = FlatNetlist.build(netlist, log);
        Connectivity conn = ConnectivityBuilder.build(flat, log);

        int attached = 0;
        for (int instId : flat.getLeafInstIds()) {
            CellIndex index = flat.getIndex(instId);
            for (int bit = 0; bit < index.getPortBitCount(); bit++) {
                int pin = flat.getGlobalPin(instId, bit);
                int pinRoot = conn.getPinRoot(pin);
                EDIFNet net = netFor(flat, instId, index, bit);
                if (net == null) {
                    Assertions.assertEquals(-1, pinRoot,
                            "Unconnected pin " + flat.getPinName(instId, bit) + " has a node");
                    continue;
                }
                attached++;
                int parentId = flat.getParent(instId);
                int expected = conn.getRoot(flat.getGlobalNet(parentId,
                        flat.getIndex(parentId).localNetIndex(net)));
                Assertions.assertEquals(expected, pinRoot,
                        "Pin " + flat.getPinName(instId, bit) + " resolves to the wrong node");
            }
        }
        Assertions.assertTrue(attached > 0, "No attached pins were found");
    }

    private static EDIFNet netFor(FlatNetlist flat, int instId, CellIndex index, int bit) {
        return flat.getInst(instId).getPortInst(index.getPortBitName(bit)) == null ? null
                : flat.getInst(instId).getPortInst(index.getPortBitName(bit)).getNet();
    }
}
