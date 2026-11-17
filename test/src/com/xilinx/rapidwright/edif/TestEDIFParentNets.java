/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.edif;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;

/**
 * Tests that the parent nets, net aliases and physical pins that {@link EDIFNetlist} finds on
 * demand are those of a search of each net that uses nothing kept from before
 * ({@link #referenceSearch}), also after the netlist has changed.
 */
public class TestEDIFParentNets {

    /**
     * Searches all nets connected to a net, visiting the nets by their hierarchical names. This
     * is how the parent net map of a netlist was built before parent nets were found on demand.
     */
    private static EDIFHierPortInst referenceSearch(EDIFHierNet initialNet, List<EDIFHierNet> aliases,
            List<EDIFHierPortInst> leafCellPins) {
        return referenceSearch(initialNet, aliases, leafCellPins, false);
    }

    /**
     * @param onlyConnectedPorts True to leave out, as the netlist does, the connections through
     * a port of a cell that are only seen from one side: a net is connected to a port of its cell
     * if it has the port inst for it, and is the net that the cell has for the port. (Removing a
     * port inst from a net leaves the net as the one of the port, and before parent nets were
     * found on demand, the net was still found from outside the cell, though no longer the nets
     * outside the cell from it.) Likewise, a port inst of a cell instance that is found by its
     * name, but was given the index of another bit, is not on the port of that name.
     */
    private static EDIFHierPortInst referenceSearch(EDIFHierNet initialNet, List<EDIFHierNet> aliases,
            List<EDIFHierPortInst> leafCellPins, boolean onlyConnectedPorts) {
        Queue<EDIFHierNet> queue = new ArrayDeque<>();
        queue.add(initialNet);
        HashSet<EDIFHierNet> visited = new HashSet<>();

        EDIFHierPortInst source = null;
        EDIFHierPortInst fallbackSource = null;
        boolean multipleFallbackSources = false;
        while (!queue.isEmpty()) {
            EDIFHierNet net = queue.poll();
            if (!visited.add(net)) {
                continue;
            }
            if (aliases != null) {
                aliases.add(net);
            }
            for (EDIFPortInst relP : net.getNet().getPortInsts()) {
                EDIFHierPortInst p = new EDIFHierPortInst(net.getHierarchicalInst(), relP);
                String relName = relP.getPortInstNameFromPort();
                if (onlyConnectedPorts && relP.getCellInst() == null
                        && net.getHierarchicalInst().getCellType().getInternalNet(relName) != net.getNet()) {
                    continue;
                }

                boolean isCellPin = relP.getCellInst() != null && relP.getCellInst().getCellType().isLeafCellOrBlackBox();
                if (leafCellPins != null && isCellPin) {
                    leafCellPins.add(p);
                }

                boolean isTopLevelPortInst = p.getHierarchicalInst().isTopLevelInst() && relP.getCellInst() == null;
                boolean isToplevelInput = isTopLevelPortInst && p.isInput();
                if (isToplevelInput || (isCellPin && p.isOutput())) {
                    if (source != null) {
                        throw new RuntimeException("Multiple sources!");
                    }
                    source = p;
                }

                boolean isToplevelInout = isTopLevelPortInst && !p.isInput() && !p.isOutput();
                if (isToplevelInout) {
                    if (fallbackSource != null) {
                        multipleFallbackSources = true;
                    } else {
                        fallbackSource = p;
                    }
                }

                if (p.getPortInst().getCellInst() == null) {
                    if (!p.getHierarchicalInst().isTopLevelInst()) {
                        final EDIFHierPortInst upPort = p.getPortInParent();
                        if (upPort != null && upPort.getNet() != null && !(onlyConnectedPorts
                                && !upPort.getPortInst().getPortInstNameFromPort().equals(relName))) {
                            queue.add(upPort.getHierarchicalNet());
                        }
                    }
                } else {
                    EDIFHierNet otherNet = p.getInternalNet();
                    if (otherNet == null) {
                        continue;
                    }
                    if (onlyConnectedPorts && otherNet.getNet().getPortInsts().stream().noneMatch(
                            pi -> pi.getCellInst() == null && pi.getPortInstNameFromPort().equals(relName))) {
                        continue;
                    }
                    queue.add(otherNet);
                }
            }
        }

        if (source != null) {
            return source;
        }
        if (multipleFallbackSources) {
            throw new RuntimeException("Multiple sources!");
        }
        return fallbackSource;
    }

    static EDIFHierPortInst referenceSearchStrict(EDIFHierNet net, List<EDIFHierNet> aliases) {
        return referenceSearch(net, aliases, null, true);
    }

    private static final String THROWS = "throws";

    /** The parent net of a net by the reference search: a net, null, or {@link #THROWS}. */
    static Object referenceParent(EDIFHierNet net) {
        try {
            EDIFHierPortInst source = referenceSearch(net, null, null, true);
            return source == null ? null : source.getHierarchicalNet();
        } catch (RuntimeException e) {
            return THROWS;
        }
    }

    static Object parent(EDIFNetlist netlist, EDIFHierNet net) {
        try {
            return netlist.getParentNet(net);
        } catch (RuntimeException e) {
            return THROWS;
        }
    }

    static List<EDIFHierNet> getAllHierNets(EDIFNetlist netlist) {
        List<EDIFHierNet> nets = new ArrayList<>();
        Queue<EDIFHierCellInst> queue = new ArrayDeque<>();
        queue.add(netlist.getTopHierCellInst());
        while (!queue.isEmpty()) {
            EDIFHierCellInst inst = queue.poll();
            for (EDIFNet net : inst.getCellType().getNets()) {
                nets.add(new EDIFHierNet(inst, net));
            }
            for (EDIFCellInst child : inst.getCellType().getCellInsts()) {
                if (!child.getCellType().isLeafCellOrBlackBox()) {
                    queue.add(inst.getChild(child));
                }
            }
        }
        return nets;
    }

    /**
     * Checks the parent net, the aliases and the physical pins of each net against the reference
     * search of that net.
     */
    private static void assertSameAsReference(EDIFNetlist netlist, List<EDIFHierNet> nets, String context) {
        for (EDIFHierNet net : nets) {
            Object expected = referenceParent(net);
            Object actual = parent(netlist, net);
            Assertions.assertEquals(expected, actual, () -> context + ": parent net of " + net);
            Assertions.assertEquals(expected, parent(netlist, net), () -> context + ": parent net (again) of " + net);
            try {
                Assertions.assertEquals(expected, netlist.getParentNetMap().get(net));
                Assertions.assertEquals(expected != null, netlist.getParentNetMap().containsKey(net));
                Assertions.assertEquals(expected == null ? null : expected.toString(),
                        netlist.getParentNetName(net.getHierarchicalNetName()));
                Assertions.assertNotEquals(THROWS, expected, () -> context + ": " + net);
            } catch (RuntimeException e) {
                Assertions.assertEquals(THROWS, expected, () -> context + ": " + net);
            }

            if (expected == THROWS) {
                Assertions.assertThrows(RuntimeException.class, () -> netlist.getNetAliases(net));
                Assertions.assertThrows(RuntimeException.class, () -> netlist.getPhysicalPins(net));
                continue;
            }
            List<EDIFHierNet> expectedAliases = new ArrayList<>();
            List<EDIFHierPortInst> expectedPins = new ArrayList<>();
            referenceSearch(net, expectedAliases, expectedPins, true);
            if (expected == null && net.getNet().getPortInsts().size() != 0) {
                Assertions.assertThrows(RuntimeException.class, () -> netlist.getNetAliases(net));
            } else {
                Assertions.assertEquals(expectedAliases, netlist.getNetAliases(net), () -> context + ": aliases of " + net);
            }
            if (net.equals(expected)) {
                Assertions.assertEquals(expectedPins, netlist.getPhysicalPins(net), () -> context + ": pins of " + net);
                Assertions.assertEquals(expectedPins, netlist.getPhysicalNetPinMap().get(net));
            } else {
                Assertions.assertNull(netlist.getPhysicalPins(net), () -> context + ": pins of " + net);
            }
        }
    }

    /**
     * Builds the maps of all nets as they were built before: from the top-level input and inout
     * ports, then the leaf cell outputs in the order of a breadth-first walk of the netlist.
     */
    private static void referenceMaps(EDIFNetlist netlist, Map<EDIFHierNet, EDIFHierNet> parentNetMap,
            Map<EDIFHierNet, List<EDIFHierPortInst>> physicalNetPinMap, List<EDIFHierPortInst> physicalGndPins,
            List<EDIFHierPortInst> physicalVccPins) {
        EDIFHierCellInst topCellInst = netlist.getTopHierCellInst();
        Queue<EDIFHierPortInst> queue = new LinkedList<>();
        for (EDIFNet n : netlist.getTopCell().getNets()) {
            for (EDIFPortInst p : n.getPortInsts()) {
                if (p.isTopLevelPort() && !p.isOutput()) {
                    queue.add(new EDIFHierPortInst(topCellInst, p));
                }
            }
        }
        Queue<EDIFHierCellInst> instQueue = new LinkedList<>();
        instQueue.add(topCellInst);
        while (!instQueue.isEmpty()) {
            EDIFHierCellInst currInst = instQueue.poll();
            for (EDIFCellInst eci : currInst.getInst().getCellType().getCellInsts()) {
                if (eci.getCellType().getCellInsts().size() == 0 && eci.getCellType().getNets().size() == 0) {
                    for (EDIFPortInst portInst : eci.getPortInsts()) {
                        if (portInst.isOutput() && portInst.getNet() != null) {
                            queue.add(new EDIFHierPortInst(currInst, portInst));
                        }
                    }
                } else {
                    instQueue.add(currInst.getChild(eci));
                }
            }
        }
        for (EDIFHierPortInst pr : queue) {
            EDIFHierNet net = pr.getHierarchicalNet();
            if (parentNetMap.containsKey(net)) {
                continue;
            }
            List<EDIFHierNet> aliases = new ArrayList<>();
            List<EDIFHierPortInst> leafCellPins = new ArrayList<>();
            EDIFHierPortInst source = referenceSearch(net, aliases, leafCellPins);
            EDIFHierNet parentNet = source.getHierarchicalNet();
            if (!parentNet.equals(net)) {
                // The physical pins are in the order found from the parent net
                leafCellPins.clear();
                referenceSearch(parentNet, null, leafCellPins);
            }
            NetType type = EDIFNetlist.identifyNetType(source);
            if (type == NetType.GND) {
                physicalGndPins.addAll(leafCellPins);
            } else if (type == NetType.VCC) {
                physicalVccPins.addAll(leafCellPins);
            }
            physicalNetPinMap.put(parentNet, leafCellPins);
            for (EDIFHierNet alias : aliases) {
                parentNetMap.put(alias, parentNet);
            }
        }
    }

    private static void assertMapsSameAsReference(EDIFNetlist netlist) {
        Map<EDIFHierNet, EDIFHierNet> parentNetMap = new HashMap<>();
        Map<EDIFHierNet, List<EDIFHierPortInst>> physicalNetPinMap = new HashMap<>();
        List<EDIFHierPortInst> physicalGndPins = new ArrayList<>();
        List<EDIFHierPortInst> physicalVccPins = new ArrayList<>();
        referenceMaps(netlist, parentNetMap, physicalNetPinMap, physicalGndPins, physicalVccPins);

        // The static pins do not need the maps of all nets
        netlist.resetParentNetMap();
        Assertions.assertEquals(physicalGndPins, netlist.getPhysicalGndPins());
        Assertions.assertEquals(physicalVccPins, netlist.getPhysicalVccPins());
        Assertions.assertFalse(netlist.isParentNetMapBuilt());

        // Nor do lookups
        for (Map.Entry<EDIFHierNet, EDIFHierNet> e : parentNetMap.entrySet()) {
            Assertions.assertEquals(e.getValue(), netlist.getParentNet(e.getKey()));
        }
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : physicalNetPinMap.entrySet()) {
            Assertions.assertEquals(e.getValue(), netlist.getPhysicalPins(e.getKey()));
        }
        Assertions.assertEquals(parentNetMap.get(netlist.getHierNetFromName("clk")),
                netlist.getParentNetMap().get(netlist.getHierNetFromName("clk")));
        Assertions.assertFalse(netlist.isParentNetMapBuilt());

        // Iterating over the maps finds all nets
        Assertions.assertEquals(parentNetMap, netlist.getParentNetMap());
        Assertions.assertEquals(parentNetMap, new HashMap<>(netlist.getParentNetMap()));
        Assertions.assertTrue(netlist.isParentNetMapBuilt());
        Assertions.assertEquals(physicalNetPinMap, new HashMap<>(netlist.getPhysicalNetPinMap()));
        Assertions.assertEquals(parentNetMap.size(), netlist.getParentNetMap().size());
        Assertions.assertEquals(parentNetMap.keySet(), netlist.getParentNetMap().keySet());
        Assertions.assertEquals(physicalGndPins, netlist.getPhysicalGndPins());
        Assertions.assertEquals(physicalVccPins, netlist.getPhysicalVccPins());
        Map<String, String> names = new HashMap<>();
        parentNetMap.forEach((k, v) -> names.put(k.getHierarchicalNetName(), v.getHierarchicalNetName()));
        Assertions.assertEquals(names, new HashMap<>(netlist.getParentNetMapNames()));
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : physicalNetPinMap.entrySet()) {
            Assertions.assertEquals(e.getValue(), netlist.getPhysicalPins(e.getKey()));
        }

        // Starting over, from the maps of all nets
        netlist.resetParentNetMap();
        Assertions.assertFalse(netlist.isParentNetMapBuilt());
        Assertions.assertEquals(physicalNetPinMap, new HashMap<>(netlist.getPhysicalNetPinMap()));
        Assertions.assertEquals(physicalGndPins, netlist.getPhysicalGndPins());
        Assertions.assertEquals(physicalVccPins, netlist.getPhysicalVccPins());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> netlist.getParentNetMap().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "picoblaze_ooc_X10Y235.dcp",
            "microblazeAndILA_3pblocks.dcp",
            "bnn.dcp",
            "inout.dcp",
            "noc_tutorial_routed.dcp",
            "optical-flow.dcp",
    })
    public void testSameAsReferenceSearch(String dcp) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        EDIFNetlist netlist = design.getNetlist();
        List<EDIFHierNet> nets = getAllHierNets(netlist);
        Assertions.assertFalse(nets.isEmpty());

        // The parent net of each net, from one reference search per set of connected nets
        Map<EDIFHierNet, Object> expected = new HashMap<>();
        int withParent = 0;
        for (EDIFHierNet net : nets) {
            if (!expected.containsKey(net)) {
                List<EDIFHierNet> aliases = new ArrayList<>();
                EDIFHierPortInst source = referenceSearch(net, aliases, null);
                for (EDIFHierNet alias : aliases) {
                    expected.put(alias, source == null ? null : source.getHierarchicalNet());
                }
            }
            if (expected.get(net) != null) {
                withParent++;
            }
        }
        Assertions.assertTrue(withParent > nets.size() / 2);

        // In netlist order, then in a random order after starting over
        for (EDIFHierNet net : nets) {
            Assertions.assertEquals(expected.get(net), netlist.getParentNet(net), net.toString());
        }
        Assertions.assertFalse(netlist.isParentNetMapBuilt());
        netlist.resetParentNetMap();
        List<EDIFHierNet> shuffled = new ArrayList<>(nets);
        Collections.shuffle(shuffled, new Random(1));
        for (EDIFHierNet net : shuffled) {
            Assertions.assertEquals(expected.get(net), netlist.getParentNet(net), net.toString());
        }
        for (EDIFHierNet net : nets) {
            Assertions.assertEquals(expected.get(net), netlist.findParentNet(net), net.toString());
        }

        // Aliases and physical pins of a sample of the nets
        Random random = new Random(2);
        List<EDIFHierNet> sample = new ArrayList<>();
        for (EDIFHierNet net : nets) {
            if (nets.size() < 5000 || random.nextInt(nets.size()) < 5000) {
                sample.add(net);
            }
        }
        assertSameAsReference(netlist, sample, dcp);

        assertMapsSameAsReference(netlist);
    }

    /**
     * Lookups from several threads at the same time, each starting with nothing recorded, find
     * what a single thread finds.
     */
    @Test
    public void testConcurrentLookups() throws Exception {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        EDIFNetlist netlist = design.getNetlist();
        List<EDIFHierNet> nets = getAllHierNets(netlist);
        Map<EDIFHierNet, EDIFHierNet> expected = new HashMap<>();
        for (EDIFHierNet net : nets) {
            expected.put(net, netlist.getParentNet(net));
        }
        Map<EDIFHierNet, List<EDIFHierPortInst>> expectedPins = new HashMap<>(netlist.getPhysicalNetPinMap());
        List<EDIFHierPortInst> expectedGndPins = new ArrayList<>(netlist.getPhysicalGndPins());

        final int numThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        try {
            for (int iteration = 0; iteration < 10; iteration++) {
                netlist.resetParentNetMap();
                List<Future<Integer>> futures = new ArrayList<>();
                for (int t = 0; t < numThreads; t++) {
                    final int offset = t * nets.size() / numThreads;
                    final boolean pinsToo = (t % 2) == 1;
                    futures.add(pool.submit(() -> {
                        int mismatches = 0;
                        for (int i = 0; i < nets.size(); i++) {
                            EDIFHierNet net = nets.get((offset + i) % nets.size());
                            EDIFHierNet parentNet = netlist.getParentNet(net);
                            if (!Objects.equals(expected.get(net), parentNet)) {
                                mismatches++;
                            }
                            if (pinsToo && parentNet != null
                                    && !expectedPins.get(parentNet).equals(netlist.getPhysicalPins(parentNet))) {
                                mismatches++;
                            }
                        }
                        if (!expectedGndPins.equals(netlist.getPhysicalGndPins())) {
                            mismatches++;
                        }
                        return mismatches;
                    }));
                }
                for (Future<Integer> f : futures) {
                    Assertions.assertEquals(0, f.get(120, TimeUnit.SECONDS), "iteration " + iteration);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * What several threads record at the same time, each starting with nothing, follows the
     * changes made to the netlist afterwards: removing the source of a net leaves all its
     * aliases without a parent net.
     */
    @Test
    public void testChangesAfterConcurrentLookups() throws Exception {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        EDIFNetlist netlist = design.getNetlist();
        List<EDIFHierNet> nets = getAllHierNets(netlist);
        // Nets with aliases and a leaf cell output as their source
        List<EDIFHierNet> parentNets = new ArrayList<>();
        Random random = new Random(3);
        for (EDIFHierNet net : nets) {
            if (net.equals(netlist.getParentNet(net)) && random.nextInt(20) == 0
                    && netlist.getNetAliases(net).size() > 1 && !net.getNet().getSourcePortInsts(false).isEmpty()) {
                parentNets.add(net);
            }
        }
        Assertions.assertTrue(parentNets.size() > 50);

        final int numThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                netlist.resetParentNetMap();
                List<Future<Integer>> futures = new ArrayList<>();
                for (int t = 0; t < numThreads; t++) {
                    final int offset = t * 7919;
                    futures.add(pool.submit(() -> {
                        int withParent = 0;
                        for (int i = 0; i < nets.size(); i++) {
                            // All threads meet the same nets at about the same time
                            if (netlist.getParentNet(nets.get((i + (i % 8 == 0 ? offset : 0)) % nets.size())) != null) {
                                withParent++;
                            }
                        }
                        return withParent;
                    }));
                }
                for (Future<Integer> f : futures) {
                    Assertions.assertTrue(f.get(120, TimeUnit.SECONDS) > 0);
                }
                for (int i = 0; i < 40; i++) {
                    EDIFHierNet parentNet = parentNets.get(random.nextInt(parentNets.size()));
                    List<EDIFHierNet> aliases = netlist.getNetAliases(parentNet);
                    EDIFPortInst source = parentNet.getNet().getSourcePortInsts(false).get(0);
                    parentNet.getNet().removePortInst(source);
                    for (EDIFHierNet alias : aliases) {
                        Assertions.assertEquals(referenceParent(alias), parent(netlist, alias), alias.toString());
                    }
                    parentNet.getNet().addPortInst(source);
                    for (EDIFHierNet alias : aliases) {
                        Assertions.assertEquals(parentNet, netlist.getParentNet(alias), alias.toString());
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Checks the parent net of every net of a netlist against one reference search per set of
     * connected nets, and the physical pins of every hundredth parent net.
     */
    private static void assertAllSameAsReference(EDIFNetlist netlist, String context) {
        Map<EDIFHierNet, Object> expected = new HashMap<>();
        int count = 0;
        for (EDIFHierNet net : getAllHierNets(netlist)) {
            if (!expected.containsKey(net)) {
                List<EDIFHierNet> aliases = new ArrayList<>();
                List<EDIFHierPortInst> pins = new ArrayList<>();
                Object parentNet;
                try {
                    EDIFHierPortInst source = referenceSearch(net, aliases, pins, true);
                    parentNet = (source == null) ? null : source.getHierarchicalNet();
                } catch (RuntimeException e) {
                    parentNet = THROWS;
                }
                for (EDIFHierNet alias : aliases) {
                    expected.put(alias, parentNet);
                }
                if (parentNet instanceof EDIFHierNet && (count++ % 100) == 0) {
                    pins.clear();
                    referenceSearch((EDIFHierNet) parentNet, null, pins, true);
                    Assertions.assertEquals(pins, netlist.getPhysicalPins((EDIFHierNet) parentNet),
                            () -> context + ": pins of " + net);
                }
            }
            Assertions.assertEquals(expected.get(net), parent(netlist, net), () -> context + ": parent net of " + net);
        }
    }

    /**
     * The changes that {@link ECOTools} makes to a netlist are followed, with the parent nets of
     * all nets looked up before each of them.
     */
    @Test
    public void testFollowsECOTools() {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();
        assertAllSameAsReference(netlist, "initial");

        List<EDIFHierPortInst> pins = new ArrayList<>();
        Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        for (int i = 0; i < 14; i++) {
            EDIFHierPortInst pin = netlist.getHierPortInstFromName("u_ila_0/probe0[" + i + "]");
            pins.add(pin);
            EDIFHierNet net = netlist.getHierNetFromName("base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/"
                    + "Performance.Core/Data_Flow_I/Data_Addr[0][" + (74 + i) + "]");
            netToPortInsts.put(net, new ArrayList<>(Collections.singletonList(pin)));
        }
        EDIFHierNet insideNet = pins.get(0).getInternalNet();
        EDIFHierNet probeParentNet = netlist.getParentNet(pins.get(0).getHierarchicalNet());
        Assertions.assertNotNull(probeParentNet);
        Assertions.assertEquals(probeParentNet, netlist.getParentNet(insideNet));

        ECOTools.disconnectNet(design, pins, deferredRemovals);
        Assertions.assertNotEquals(probeParentNet, netlist.getParentNet(insideNet));
        assertAllSameAsReference(netlist, "disconnectNet");
        DesignTools.batchRemoveSitePins(deferredRemovals, true);
        deferredRemovals.clear();

        ECOTools.connectNet(design, netToPortInsts, deferredRemovals);
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : netToPortInsts.entrySet()) {
            Assertions.assertEquals(netlist.getParentNet(e.getKey()),
                    netlist.getParentNet(e.getValue().get(0).getInternalNet()));
        }
        assertAllSameAsReference(netlist, "connectNet");

        design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        netlist = design.getNetlist();
        assertAllSameAsReference(netlist, "initial");
        ECOTools.createCell(design, netlist.getCell("kcpsm6"), Arrays.asList("processor2", "processor3"));
        assertAllSameAsReference(netlist, "createCell");
        ECOTools.createNet(design, Arrays.asList("processor/foo", "your_program/bar"));
        assertAllSameAsReference(netlist, "createNet");
        ECOTools.removeCell(design, Collections.singletonList(netlist.getHierCellInstFromName("processor")),
                deferredRemovals);
        assertAllSameAsReference(netlist, "removeCell");
    }

    /**
     * A cell that is made a primitive becomes the source of the nets on its outputs.
     */
    @Test
    public void testMakePrimitive() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell top = netlist.getTopCell();
        EDIFCell driver = new EDIFCell(netlist.getWorkLibrary(), "driver");
        EDIFPort driverO = driver.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell sink = new EDIFCell(netlist.getWorkLibrary(), "sink");
        EDIFPort sinkI = sink.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell child = new EDIFCell(netlist.getWorkLibrary(), "child");
        EDIFPort childO = child.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFNet local = child.createNet("local");
        local.createPortInst(childO);
        local.createPortInst(driverO, child.createChildCellInst("d", driver));
        EDIFNet out = top.createNet("out");
        out.createPortInst(childO, top.createChildCellInst("u", child));
        out.createPortInst(sinkI, top.createChildCellInst("s", sink));

        EDIFHierNet outNet = netlist.getHierNetFromName("out");
        EDIFHierNet localNet = netlist.getHierNetFromName("u/local");
        Assertions.assertEquals(localNet, netlist.getParentNet(outNet));
        Assertions.assertEquals("[u/d/O, s/I]", netlist.getPhysicalPins(localNet).toString());
        Assertions.assertNull(netlist.getPhysicalPins(outNet));

        child.makePrimitive();
        Assertions.assertEquals(outNet, netlist.getParentNet(outNet));
        Assertions.assertEquals("[s/I, u/O]", netlist.getPhysicalPins(outNet).toString());
    }

    /**
     * A port inst that is given another index is on that bit of its port, whatever its name.
     */
    @Test
    public void testPortInstIndexChange() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell top = netlist.getTopCell();
        EDIFCell driver = new EDIFCell(netlist.getWorkLibrary(), "driver");
        EDIFPort driverO = driver.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell sink = new EDIFCell(netlist.getWorkLibrary(), "sink");
        EDIFPort sinkI = sink.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell child = new EDIFCell(netlist.getWorkLibrary(), "child");
        EDIFPort bus = child.createPort("p[1:0]", EDIFDirection.OUTPUT, 2);
        for (int i = 0; i < 2; i++) {
            EDIFNet net = child.createNet("n" + i);
            net.createPortInst(bus, i);
            net.createPortInst(driverO, child.createChildCellInst("d" + i, driver));
        }
        EDIFNet out = top.createNet("out");
        EDIFPortInst portInst = out.createPortInst(bus, 0, top.createChildCellInst("u", child));
        out.createPortInst(sinkI, top.createChildCellInst("s", sink));

        EDIFHierNet outNet = netlist.getHierNetFromName("out");
        EDIFHierNet n0 = netlist.getHierNetFromName("u/n0");
        EDIFHierNet n1 = netlist.getHierNetFromName("u/n1");
        Assertions.assertEquals(n0, netlist.getParentNet(outNet));
        Assertions.assertEquals(n1, netlist.getParentNet(n1));
        Assertions.assertEquals("[u/d0/O, s/I]", netlist.getPhysicalPins(n0).toString());
        Assertions.assertEquals(Arrays.asList(outNet, n0), netlist.getNetAliases(outNet));

        // The net outside is now on the other bit of the port. (From the net inside, the port
        // inst outside is still not found, as it is looked up by the name it was created with.)
        portInst.setIndex(1);
        for (int i = 0; i < 2; i++) {
            Assertions.assertEquals(n1, netlist.getParentNet(outNet));
            Assertions.assertEquals(n1, netlist.findParentNet(outNet));
            Assertions.assertEquals(n1, netlist.getParentNetMap().get(outNet));
            Assertions.assertEquals(Arrays.asList(outNet, n1), netlist.getNetAliases(outNet));
            Assertions.assertEquals(n0, netlist.getParentNet(n0));
            Assertions.assertEquals(Collections.singletonList(n0), netlist.getNetAliases(n0));
            Assertions.assertEquals("[u/d0/O]", netlist.getPhysicalPins(n0).toString());
            netlist.resetParentNetMap();
        }

        portInst.setIndex(0);
        Assertions.assertEquals(n0, netlist.getParentNet(outNet));
        Assertions.assertEquals("[u/d0/O, s/I]", netlist.getPhysicalPins(n0).toString());
        Assertions.assertEquals(3, netlist.getParentNetMap().size());
    }

    /**
     * A port inst that is given the index of a bit whose net inside the cell is on another port
     * too connects the nets on both ports, when looked up from its net. (From the net on the
     * other port, the port inst is not found, so that the two nets do not agree on being
     * connected: whichever is looked up first, the lookups end.)
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPortInstIndexChangeThroughCell(boolean otherNetFirst) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell top = netlist.getTopCell();
        EDIFCell driver = new EDIFCell(netlist.getWorkLibrary(), "driver");
        EDIFPort driverO = driver.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell sink = new EDIFCell(netlist.getWorkLibrary(), "sink");
        EDIFPort sinkI = sink.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell child = new EDIFCell(netlist.getWorkLibrary(), "child");
        EDIFPort bus = child.createPort("p[1:0]", EDIFDirection.INPUT, 2);
        EDIFPort y = child.createPort("y", EDIFDirection.OUTPUT, 1);
        EDIFNet through = child.createNet("through");
        through.createPortInst(bus, 1);
        through.createPortInst(y);
        EDIFCellInst u = top.createChildCellInst("u", child);
        EDIFNet in = top.createNet("in");
        EDIFPortInst portInst = in.createPortInst(bus, 0, u);
        in.createPortInst(driverO, top.createChildCellInst("d", driver));
        EDIFNet out = top.createNet("out");
        out.createPortInst(y, u);
        out.createPortInst(sinkI, top.createChildCellInst("s", sink));

        EDIFHierNet inNet = netlist.getHierNetFromName("in");
        EDIFHierNet outNet = netlist.getHierNetFromName("out");
        EDIFHierNet throughNet = netlist.getHierNetFromName("u/through");
        Assertions.assertEquals(inNet, netlist.getParentNet(inNet));
        Assertions.assertNull(netlist.getParentNet(outNet));
        Assertions.assertEquals(Collections.singletonList(inNet), netlist.getNetAliases(inNet));

        portInst.setIndex(1);
        for (int i = 0; i < 2; i++) {
            if (otherNetFirst) {
                Assertions.assertNull(netlist.getParentNet(outNet));
                Assertions.assertNull(netlist.getParentNet(throughNet));
            }
            Assertions.assertEquals(inNet, netlist.getParentNet(inNet));
            Assertions.assertEquals(Arrays.asList(inNet, throughNet, outNet), netlist.getNetAliases(inNet));
            Assertions.assertEquals("[d/O, s/I]", netlist.getPhysicalPins(inNet).toString());
            // Once looked up from the net of the port inst, the net on the other port is known
            // to be connected to it
            Assertions.assertEquals(inNet, netlist.getParentNet(outNet));
            Assertions.assertEquals(inNet, netlist.getParentNet(inNet));
            netlist.resetParentNetMap();
        }
    }

    /**
     * Two port insts of a cell instance that are given the same bit, whose net inside the cell is
     * on another port too, leave three nets that do not agree on which of them are connected:
     * each of the two is connected to the third, but not to the other. A net above them has the
     * parent net that a search finds from it, also after a change, and their one source is not
     * taken for two.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPortInstsGivenTheSameBit(boolean sourceOnThirdNet) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell top = netlist.getTopCell();
        EDIFCell driver = new EDIFCell(netlist.getWorkLibrary(), "driver");
        EDIFPort driverO = driver.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell sink = new EDIFCell(netlist.getWorkLibrary(), "sink");
        EDIFPort sinkI = sink.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell child = new EDIFCell(netlist.getWorkLibrary(), "child");
        EDIFPort bus = child.createPort("p[2:0]", EDIFDirection.INOUT, 3);
        EDIFPort y = child.createPort("y", EDIFDirection.INOUT, 1);
        EDIFNet through = child.createNet("through");
        through.createPortInst(bus, 1);
        through.createPortInst(y);
        EDIFCell middle = new EDIFCell(netlist.getWorkLibrary(), "middle");
        EDIFPort pa = middle.createPort("pa", EDIFDirection.INOUT, 1);
        EDIFPort pc = middle.createPort("pc", EDIFDirection.INOUT, 1);
        EDIFCellInst j = middle.createChildCellInst("j", child);
        EDIFNet a = middle.createNet("a");
        a.createPortInst(pa);
        EDIFPortInst portInstA = a.createPortInst(bus, 0, j);
        EDIFNet c = middle.createNet("c");
        c.createPortInst(pc);
        EDIFPortInst portInstC = c.createPortInst(bus, 2, j);
        EDIFNet b = middle.createNet("b");
        b.createPortInst(y, j);
        b.createPortInst(sinkI, middle.createChildCellInst("s", sink));
        EDIFPortInst source = (sourceOnThirdNet ? b : a).createPortInst(driverO, middle.createChildCellInst("d", driver));
        EDIFCellInst m = top.createChildCellInst("m", middle);
        EDIFNet high = top.createNet("high");
        high.createPortInst(pa, m);
        high.createPortInst(pc, m);

        portInstA.setIndex(1);
        portInstC.setIndex(1);
        List<EDIFHierNet> nets = Arrays.asList(netlist.getHierNetFromName("high"),
                netlist.getHierNetFromName("m/a"), netlist.getHierNetFromName("m/c"));
        EDIFHierNet parentNet = netlist.getHierNetFromName(sourceOnThirdNet ? "m/b" : "m/a");
        for (int i = 0; i < 2; i++) {
            for (EDIFHierNet net : nets) {
                Assertions.assertEquals(parentNet, referenceParent(net), net.toString());
                Assertions.assertEquals(parentNet, netlist.getParentNet(net), net.toString());
            }
            Assertions.assertEquals("[m/d/O, m/s/I]", netlist.getPhysicalPins(parentNet).toString());
        }

        source.getNet().removePortInst(source);
        for (EDIFHierNet net : nets) {
            Assertions.assertNull(netlist.getParentNet(net), net.toString());
        }
        Assertions.assertNull(netlist.getPhysicalPins(parentNet));
    }

    /**
     * A change to a net that a cell of another netlist shares (a shallow copy of its cell) is
     * followed in that netlist too, including by what it keeps until it changes.
     */
    @Test
    public void testSharedCellChange() {
        EDIFNetlist original = EDIFTools.createNewNetlist("original");
        EDIFCell driver = new EDIFCell(original.getWorkLibrary(), "driver");
        EDIFPort driverO = driver.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell cell = new EDIFCell(original.getWorkLibrary(), "shared");
        EDIFPort cellO = cell.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFNet local = cell.createNet("local");
        local.createPortInst(cellO);
        EDIFPortInst source = local.createPortInst(driverO, cell.createChildCellInst("d", driver));

        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell top = netlist.getTopCell();
        EDIFCell sink = new EDIFCell(netlist.getWorkLibrary(), "sink");
        EDIFPort sinkI = sink.createPort("I", EDIFDirection.INPUT, 1);
        EDIFCell copy = new EDIFCell(netlist.getWorkLibrary(), cell);
        EDIFNet out = top.createNet("out");
        out.createPortInst(cellO, top.createChildCellInst("u", copy));
        out.createPortInst(sinkI, top.createChildCellInst("s", sink));

        EDIFHierNet outNet = netlist.getHierNetFromName("out");
        EDIFHierNet localNet = netlist.getHierNetFromName("u/local");
        Assertions.assertSame(local, localNet.getNet());
        Assertions.assertEquals(localNet, netlist.getParentNet(outNet));
        Assertions.assertEquals("[u/d/O, s/I]", netlist.getPhysicalPins(localNet).toString());
        Assertions.assertEquals(2, netlist.getParentNetMap().size());
        Assertions.assertTrue(netlist.isParentNetMapBuilt());

        local.removePortInst(source);
        Assertions.assertNull(netlist.getParentNet(outNet));
        Assertions.assertNull(netlist.getPhysicalPins(localNet));
        Assertions.assertFalse(netlist.isParentNetMapBuilt());
        Assertions.assertEquals(0, netlist.getParentNetMap().size());

        local.addPortInst(source);
        Assertions.assertEquals(localNet, netlist.getParentNet(outNet));
        Assertions.assertEquals("[u/d/O, s/I]", netlist.getPhysicalPins(localNet).toString());
        Assertions.assertEquals(2, netlist.getParentNetMap().size());

        // A port of the shared cell that only gets a net inside the cell later
        EDIFPort cellO2 = cell.createPort("O2", EDIFDirection.OUTPUT, 1);
        EDIFNet out2 = top.createNet("out2");
        out2.createPortInst(cellO2, top.getCellInst("u"));
        out2.createPortInst(sinkI, top.createChildCellInst("s2", sink));
        EDIFHierNet out2Net = netlist.getHierNetFromName("out2");
        Assertions.assertNull(netlist.getParentNet(out2Net));
        EDIFNet inside = cell.createNet("inside");
        inside.createPortInst(driverO, cell.createChildCellInst("d2", driver));
        EDIFHierNet insideNet = netlist.getHierNetFromName("u/inside");
        Assertions.assertEquals(insideNet, netlist.getParentNet(insideNet));
        Assertions.assertNull(netlist.getParentNet(out2Net));
        Assertions.assertEquals("[u/d2/O]", netlist.getPhysicalPins(insideNet).toString());
        inside.createPortInst(cellO2);
        Assertions.assertEquals(insideNet, netlist.getParentNet(out2Net));
        Assertions.assertEquals("[u/d2/O, s2/I]", netlist.getPhysicalPins(insideNet).toString());
        Assertions.assertEquals(4, netlist.getParentNetMap().size());
    }

    /**
     * A random netlist: cells that instantiate leaf cells and each other, with nets that connect
     * their ports and the pins of their instances at random, so that it has nets passing through
     * cells, unconnected ports, nets with several sources, and cells instantiated more than once.
     */
    static class RandomNetlist {
        final Random random;
        final EDIFNetlist netlist;
        final EDIFLibrary work;
        final List<EDIFCell> leafCells = new ArrayList<>();
        /** Cells with contents (or that had some), in an order in which each only instantiates earlier ones */
        final List<EDIFCell> cells = new ArrayList<>();
        final boolean keepConsistent;
        int count;

        RandomNetlist(long seed, boolean keepConsistent) {
            random = new Random(seed);
            this.keepConsistent = keepConsistent;
            netlist = EDIFTools.createNewNetlist("top");
            work = netlist.getWorkLibrary();
            leafCells.add(createLeafCell("SRC", "O", EDIFDirection.OUTPUT, 1));
            leafCells.add(createLeafCell("SNK", "I", EDIFDirection.INPUT, 1));
            leafCells.add(createLeafCell("PAD", "IO", EDIFDirection.INOUT, 1));
            EDIFCell buf = createLeafCell("BUF", "I", EDIFDirection.INPUT, 1);
            buf.createPort("O", EDIFDirection.OUTPUT, 1);
            leafCells.add(buf);
            EDIFCell wide = createLeafCell("WIDE", "I[1:0]", EDIFDirection.INPUT, 2);
            wide.createPort("O[1:0]", EDIFDirection.OUTPUT, 2);
            leafCells.add(wide);
            for (int i = 0; i < 6; i++) {
                EDIFCell cell = new EDIFCell(work, "h" + i);
                populate(cell, true);
                cells.add(cell);
            }
            populate(netlist.getTopCell(), true);
            cells.add(netlist.getTopCell());
        }

        private EDIFCell createLeafCell(String name, String port, EDIFDirection direction, int width) {
            EDIFCell cell = new EDIFCell(work, name);
            cell.createPort(port, direction, width);
            return cell;
        }

        private EDIFDirection randomDirection() {
            int r = random.nextInt(10);
            return r < 5 ? EDIFDirection.INPUT : r < 9 ? EDIFDirection.OUTPUT : EDIFDirection.INOUT;
        }

        private EDIFCell randomCell() {
            return cells.get(random.nextInt(cells.size()));
        }

        private <T> T pick(List<T> list) {
            return list.isEmpty() ? null : list.get(random.nextInt(list.size()));
        }

        /** A cell that can be instantiated in the given one: a leaf cell, or an earlier cell */
        private EDIFCell randomCellType(EDIFCell parent) {
            int index = cells.contains(parent) ? cells.indexOf(parent) : cells.size();
            if (index == 0 || random.nextInt(10) < 5) {
                return pick(leafCells);
            }
            return cells.get(random.nextInt(index));
        }

        private void populate(EDIFCell cell, boolean withPorts) {
            if (withPorts) {
                int ports = random.nextInt(5);
                for (int i = 0; i < ports; i++) {
                    if (random.nextInt(4) == 0) {
                        cell.createPort("p" + i + "[1:0]", randomDirection(), 2);
                    } else {
                        cell.createPort("p" + i, randomDirection(), 1);
                    }
                }
            }
            int insts = 2 + random.nextInt(5);
            for (int i = 0; i < insts; i++) {
                cell.createChildCellInst("i" + (count++), randomCellType(cell));
            }
            int nets = 3 + random.nextInt(6);
            List<EDIFNet> netList = new ArrayList<>();
            for (int i = 0; i < nets; i++) {
                netList.add(cell.createNet("n" + (count++)));
            }
            for (Object[] point : getFreePoints(cell)) {
                if (random.nextInt(10) < 8) {
                    connect(pick(netList), point);
                }
            }
        }

        /**
         * The ports of a cell and the pins of its instances that are on no net, each as the cell
         * inst (null for a port of the cell), the port and the index.
         */
        private List<Object[]> getFreePoints(EDIFCell cell) {
            List<Object[]> points = new ArrayList<>();
            for (EDIFPort port : cell.getPorts()) {
                for (int i : port.getBitBlastedIndices()) {
                    String name = port.getPortInstNameFromPort(i);
                    EDIFNet net = cell.getInternalNet(name);
                    if (net == null || net.getPortInst(null, name) == null) {
                        points.add(new Object[] {null, port, i});
                    }
                }
            }
            for (EDIFCellInst inst : cell.getCellInsts()) {
                for (EDIFPort port : inst.getCellType().getPorts()) {
                    for (int i : port.getBitBlastedIndices()) {
                        EDIFPortInst portInst = inst.getPortInst(port.getPortInstNameFromPort(i));
                        if (portInst == null || portInst.getNet() == null) {
                            points.add(new Object[] {inst, port, i});
                        }
                    }
                }
            }
            return points;
        }

        private void connect(EDIFNet net, Object[] point) {
            EDIFCellInst inst = (EDIFCellInst) point[0];
            EDIFPort port = (EDIFPort) point[1];
            int index = port.isBus() ? (Integer) point[2] : -1;
            net.createPortInst(port, index, inst);
        }

        private void disconnect(EDIFNet net, EDIFPortInst portInst, boolean fully) {
            net.removePortInst(portInst);
            if (fully && portInst.getCellInst() == null && net.getParentCell() != null) {
                net.getParentCell().removeInternalPortMapEntry(portInst.getName());
            }
        }

        /** Makes one random change to the netlist, and returns its description. */
        String change() {
            EDIFCell cell = randomCell();
            List<EDIFNet> nets = new ArrayList<>(cell.getNets());
            List<EDIFCellInst> insts = new ArrayList<>(cell.getCellInsts());
            EDIFNet net = pick(nets);
            EDIFCellInst inst = pick(insts);
            boolean fully = keepConsistent || random.nextBoolean();
            switch (random.nextInt(13)) {
            case 0: {
                if (net == null || net.getPortInsts().isEmpty()) break;
                EDIFPortInst portInst = pick(new ArrayList<>(net.getPortInsts()));
                disconnect(net, portInst, fully);
                return "disconnect " + portInst + " from " + net + " in " + cell + " " + fully;
            }
            case 1:
            case 2: {
                Object[] point = pick(getFreePoints(cell));
                if (net == null || point == null) break;
                connect(net, point);
                return "connect " + net + " in " + cell;
            }
            case 3: {
                EDIFNet newNet = cell.createNet("n" + (count++));
                List<Object[]> points = getFreePoints(cell);
                for (int i = 0; i < 3 && !points.isEmpty(); i++) {
                    connect(newNet, points.remove(random.nextInt(points.size())));
                }
                return "new net " + newNet + " in " + cell;
            }
            case 4: {
                if (net == null) break;
                if (fully) {
                    for (EDIFPortInst portInst : new ArrayList<>(net.getPortInsts())) {
                        disconnect(net, portInst, true);
                    }
                }
                cell.removeNet(net);
                return "remove net " + net + " in " + cell + " " + fully;
            }
            case 5: {
                EDIFCellInst newInst = cell.createChildCellInst("i" + (count++), randomCellType(cell));
                for (Object[] point : getFreePoints(cell)) {
                    if (point[0] == newInst && net != null && random.nextBoolean()) {
                        connect(pick(nets), point);
                    }
                }
                return "new inst " + newInst + " in " + cell;
            }
            case 6: {
                if (inst == null) break;
                if (fully) {
                    for (EDIFPortInst portInst : new ArrayList<>(inst.getPortInsts())) {
                        if (portInst.getNet() != null) {
                            portInst.getNet().removePortInst(portInst);
                        }
                    }
                }
                cell.removeCellInst(inst);
                return "remove inst " + inst + " in " + cell + " " + fully;
            }
            case 7: {
                // Another cell with the same contents, which is then changed on its own
                if (inst == null) break;
                EDIFCell type = inst.getCellType();
                EDIFCell copy;
                try {
                    copy = new EDIFCell(work, type, type.getName() + "_c" + (count++));
                } catch (RuntimeException e) {
                    // A net of the cell has a port inst of an instance that was removed
                    Assertions.assertFalse(keepConsistent);
                    break;
                }
                if (random.nextBoolean()) {
                    inst.setCellType(copy);
                } else {
                    // Leaves the port insts of the instance with the ports of the former cell
                    inst.setCellTypeRaw(copy);
                }
                if (cells.contains(type)) {
                    cells.add(cells.indexOf(type), copy);
                } else {
                    leafCells.add(copy);
                }
                return "copy cell of " + inst + " in " + cell;
            }
            case 8: {
                List<EDIFPort> ports = new ArrayList<>(random.nextBoolean() ? cell.getPorts() : pick(leafCells).getPorts());
                EDIFPort port = pick(ports);
                if (port == null) break;
                port.setDirection(randomDirection());
                return "direction of " + port + " of " + port.getParentCell();
            }
            case 9: {
                if (net != null && random.nextBoolean()) {
                    net.rename("r" + (count++));
                    return "rename net " + net + " in " + cell;
                } else if (inst != null) {
                    cell.renameCellInst(inst, "r" + (count++));
                    // The port insts of a net are looked up by the name of their cell inst
                    for (EDIFPortInst portInst : inst.getPortInsts()) {
                        if (portInst.getNet() != null) {
                            portInst.getNet().sortPortInsts();
                        }
                    }
                    return "rename inst " + inst + " in " + cell;
                }
                break;
            }
            case 10: {
                // Black box, to be populated again later
                if (cell == netlist.getTopCell() || random.nextInt(4) != 0) break;
                if (random.nextBoolean()) {
                    cell.makePrimitive();
                    return "make primitive " + cell;
                }
                for (EDIFCellInst i : insts) {
                    cell.removeCellInst(i);
                }
                for (EDIFNet n : nets) {
                    if (fully) {
                        for (EDIFPortInst portInst : new ArrayList<>(n.getPortInsts())) {
                            disconnect(n, portInst, true);
                        }
                    }
                    cell.removeNet(n);
                }
                return "empty " + cell + " " + fully;
            }
            case 11: {
                if (!cell.isLeafCellOrBlackBox()) break;
                populate(cell, false);
                return "populate " + cell;
            }
            case 12: {
                // Move a port inst to another net
                EDIFNet other = pick(nets);
                if (net == null || other == net || net.getPortInsts().isEmpty()) break;
                EDIFPortInst portInst = pick(new ArrayList<>(net.getPortInsts()));
                net.removePortInst(portInst);
                other.addPortInst(portInst);
                return "move " + portInst + " from " + net + " to " + other + " in " + cell;
            }
            default:
                break;
            }
            return null;
        }
    }

    /**
     * After each of a series of random changes to a random netlist, every net has the parent
     * net, aliases and physical pins that a search of the changed netlist finds, without
     * {@link EDIFNetlist#resetParentNetMap()}, whatever was looked up before the change.
     */
    @ParameterizedTest
    @CsvSource({
            "1, true", "2, true", "3, true", "4, true", "5, true", "6, true", "7, true", "8, true", "9, true", "10, true",
            "1, false", "2, false", "3, false", "4, false", "5, false", "6, false", "7, false", "8, false", "9, false",
            "10, false",
    })
    public void testFollowsRandomChanges(long seed, boolean keepConsistent) {
        RandomNetlist r = new RandomNetlist(seed, keepConsistent);
        EDIFNetlist netlist = r.netlist;
        assertSameAsReference(netlist, getAllHierNets(netlist), "initial");
        int changes = 0;
        for (int step = 0; step < 400; step++) {
            // Look up some of the nets, so that each change meets another part of them recorded
            List<EDIFHierNet> nets = getAllHierNets(netlist);
            int percent = r.random.nextInt(101);
            for (EDIFHierNet net : nets) {
                if (r.random.nextInt(100) < percent) {
                    Object parentNet = parent(netlist, net);
                    if (parentNet instanceof EDIFHierNet && r.random.nextInt(4) == 0) {
                        netlist.getPhysicalPins((EDIFHierNet) parentNet);
                    }
                }
            }
            if (r.random.nextInt(10) == 0) {
                try {
                    netlist.getParentNetMap().size();
                    netlist.getPhysicalGndPins();
                } catch (RuntimeException e) {
                    // A net with several sources
                }
            }
            if (r.random.nextInt(50) == 0) {
                netlist.resetParentNetMap();
            }

            String change = null;
            for (int i = 1 + r.random.nextInt(3); i > 0; i--) {
                String c = r.change();
                if (c != null) {
                    change = (change == null) ? c : change + "; " + c;
                    changes++;
                }
            }
            assertSameAsReference(netlist, getAllHierNets(netlist), "seed " + seed + " step " + step + " after " + change);

            if (keepConsistent && step % 20 == 0) {
                // All nets at once
                Map<EDIFHierNet, EDIFHierNet> expected = new HashMap<>();
                boolean expectThrows = false;
                for (EDIFHierNet net : getAllHierNets(netlist)) {
                    Object parentNet = referenceParent(net);
                    if (parentNet == THROWS) {
                        expectThrows = true;
                    } else if (parentNet != null) {
                        expected.put(net, (EDIFHierNet) parentNet);
                    }
                }
                if (expectThrows) {
                    Assertions.assertThrows(RuntimeException.class, () -> netlist.getParentNetMap().size());
                } else {
                    Assertions.assertEquals(expected, new HashMap<>(netlist.getParentNetMap()), "step " + step);
                }
            }
        }
        Assertions.assertTrue(changes > 300);
    }
}
