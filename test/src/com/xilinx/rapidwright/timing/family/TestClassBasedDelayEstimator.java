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

package com.xilinx.rapidwright.timing.family;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

public class TestClassBasedDelayEstimator {

    /** Nothing serializes delay estimators */
    @Test
    public void testNotSerializable() {
        Assertions.assertFalse(Serializable.class.isAssignableFrom(DelayEstimator.class));
        Assertions.assertFalse(Serializable.class.isAssignableFrom(ClassBasedDelayEstimator.class));
    }

    /** A wire that the classifier does not know is reported, naming it */
    @Test
    public void testUnknownWireReported() {
        Device device = Device.getDevice("xcvc1902");
        Tile tile = device.getTile("INT_X40Y120");
        String unknown = tile.getWireName(0);
        WireClasses classes = new WireClasses("Test", (ic, name) -> name.equals(unknown) ? -1 : 0);
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () -> classes.get(device));
        Assertions.assertTrue(e.getMessage().contains(unknown), e.getMessage());
        Assertions.assertEquals(0, new WireClasses("Test", (ic, name) -> 0).get(device)[TileTypeEnum.INT.ordinal()][0]);
    }

    /** The estimators of all corners of a device share one crossing index */
    @ParameterizedTest
    @CsvSource({"xcvc1902", "xcvu3p"})
    public void testCrossingIndexSharedPerDevice(String deviceName) {
        Device device = Device.getDevice(deviceName);
        TimingFamily family = TimingFamilies.of(device);
        CrossingIndex index = ((ClassBasedDelayEstimator) family.createDelayEstimator(device, null, Corner.SLOW_MAX,
                false)).getCrossings();
        for (Corner corner : Corner.values()) {
            for (boolean useUTurnNodes : new boolean[]{false, true}) {
                ClassBasedDelayEstimator e = (ClassBasedDelayEstimator) family.createDelayEstimator(device, null, corner,
                        useUTurnNodes);
                Assertions.assertSame(index, e.getCrossings(), corner + " " + useUTurnNodes);
            }
        }
    }

    /**
     * The lookahead delays of the data are three times the admissible ones, at every corner (both scale with the
     * corner alike)
     */
    @ParameterizedTest
    @CsvSource({"xcvc1902", "xc2ve3858", "xcvu3p"})
    public void testLookaheadThriceAdmissible(String deviceName) {
        Device device = Device.getDevice(deviceName);
        TimingFamily family = TimingFamilies.of(device);
        for (Corner corner : Corner.values()) {
            DelayEstimator e = family.createDelayEstimator(device, null, corner, true);
            Assertions.assertEquals(e.getLookaheadDelayPerTileX(), 3 * e.getAdmissibleLookaheadDelayPerTileX(),
                    0.001f, corner.toString());
            Assertions.assertEquals(e.getLookaheadDelayPerTileY(), 3 * e.getAdmissibleLookaheadDelayPerTileY(),
                    0.001f, corner.toString());
        }
    }

    /**
     * The admissible lookahead is a lower bound of the delay of the shortest path, at SLOW_MAX, from a node driving the
     * interconnect to every INT tile input multiplexer reached within a radius (charging each hop its node delay and
     * transition, at least 0, as RWRoute does)
     */
    @ParameterizedTest
    @CsvSource({"xcvc1902,SDQNODE", "xcvu3p,CLE_OUTPUT"})
    public void testAdmissibleLookaheadOnShortestPaths(String deviceName, String sourceClass) {
        Device device = Device.getDevice(deviceName);
        ClassBasedDelayEstimator est = (ClassBasedDelayEstimator) TimingFamilies.of(device)
                .createDelayEstimator(device, null, Corner.SLOW_MAX, true);
        float ax = est.getAdmissibleLookaheadDelayPerTileX() * 100f;
        float ay = est.getAdmissibleLookaheadDelayPerTileY() * 100f;
        List<String> classes = est.getParams().getNodeClassNames();
        int srcClass = classes.indexOf(sourceClass);
        int imux = classes.indexOf("IMUX");
        float radius = 400f;
        List<Tile> ints = new ArrayList<>();
        for (Tile t : device.getAllTiles()) {
            if (t.getTileTypeEnum() == TileTypeEnum.INT) {
                ints.add(t);
            }
        }
        Random rnd = new Random(1);
        long checked = 0;
        for (int s = 0; s < 3; s++) {
            Tile tile = ints.get(rnd.nextInt(ints.size()));
            Node src = null;
            for (int w = 0; w < tile.getWireCount() && src == null; w++) {
                // (An UltraScale+ slice output's node has its base wire in the slice's tile, reaching into the INT tile)
                Node n = Node.getNode(tile, w);
                if (n != null && est.getNodeClass(n) == srcClass) {
                    src = n;
                }
            }
            Assertions.assertNotNull(src, tile.toString());
            Map<Node, Float> dist = new HashMap<>();
            PriorityQueue<Map.Entry<Node, Float>> queue = new PriorityQueue<>(Map.Entry.comparingByValue());
            dist.put(src, 0f);
            queue.add(new AbstractMap.SimpleEntry<>(src, 0f));
            while (!queue.isEmpty()) {
                Map.Entry<Node, Float> e = queue.poll();
                Node n = e.getKey();
                float d = e.getValue();
                if (d > dist.get(n)) {
                    continue;
                }
                if (d > radius) {
                    break;
                }
                if (est.getNodeClass(n) == imux && n.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
                    Tile t = n.getTile();
                    float bound = ax * Math.abs(t.getTileXCoordinate() - tile.getTileXCoordinate())
                            + ay * Math.abs(t.getTileYCoordinate() - tile.getTileYCoordinate());
                    Assertions.assertTrue(d + 0.5f >= bound, src + " -> " + n + ": " + d + " < " + bound);
                    checked++;
                }
                for (Node m : n.getAllDownhillNodes()) {
                    if (est.getNodeClass(m) == 0 && m.getIntentCode().name().contains("GLOBAL")) {
                        // The clock network
                        continue;
                    }
                    float hop = Math.max(0, (est.isDelayCharged(m) ? est.getDelayOf(m) : 0) + est.getExtraDelay(n, m));
                    Float old = dist.get(m);
                    if (old == null || d + hop < old) {
                        dist.put(m, d + hop);
                        queue.add(new AbstractMap.SimpleEntry<>(m, d + hop));
                    }
                }
            }
        }
        Assertions.assertTrue(checked > 1000, Long.toString(checked));
    }

    /**
     * The fanout load delay of a node grows with the base-2 logarithm of the number of sink pins reached through it,
     * and is 0 for a node leading to a single one
     */
    @Test
    public void testFanoutLoadDelayLogarithmic() {
        Device device = Device.getDevice("xcvu3p");
        ClassBasedDelayEstimator est = (ClassBasedDelayEstimator) TimingFamilies.of(device)
                .createDelayEstimator(device, null, Corner.SLOW_MAX, true);
        Assertions.assertTrue(est.getParams().hasFanoutLoadDelays());
        Node node = device.getNode("INT_X10Y76/EE2_E_BEG0");
        Assertions.assertEquals(0f, est.getFanoutLoadDelay(node, 1));
        float two = est.getFanoutLoadDelay(node, 2);
        Assertions.assertTrue(two > 0, Float.toString(two));
        Assertions.assertEquals(3 * two, est.getFanoutLoadDelay(node, 8), 1e-3f);
    }

    /**
     * A wire that crosses an I/O column slows down more per extra branch than the same wire between two adjacent INT
     * columns, and both grow linearly with the extra branches
     */
    @Test
    public void testFanoutDelayOfColumnCrossing() {
        Device device = Device.getDevice("xcvu3p");
        ClassBasedDelayEstimator est = (ClassBasedDelayEstimator) TimingFamilies.of(device)
                .createDelayEstimator(device, null, Corner.SLOW_MAX, true);
        Assertions.assertTrue(est.getParams().hasFanoutCrossingDelays());
        // A double between INT_X35 and INT_X36 crosses the I/O column, one between INT_X2 and INT_X3 crosses none
        Node across = device.getNode("INT_X35Y76/EE2_E_BEG0");
        Node within = device.getNode("INT_X2Y76/EE2_E_BEG0");
        Assertions.assertEquals(est.getNodeClass(across), est.getNodeClass(within));
        Assertions.assertTrue(est.getFanoutDelay(across, 2) > est.getFanoutDelay(within, 2));
        Assertions.assertEquals(2 * est.getFanoutDelay(across, 2), est.getFanoutDelay(across, 3), 1e-3f);
        Assertions.assertEquals(0f, est.getFanoutDelay(across, 1));
    }

    /**
     * The fanout delays of a speed grade scale by its fanout factor, its other delays by its factor: at -2HP the delays
     * of extra branches are larger relative to -2MP than the other delays; grades without a fanout factor of their own
     * scale fanout delays like the other delays
     */
    @Test
    public void testFanoutSpeedScale() {
        Device device = Device.getDevice("xcvc1902");
        TimingFamily family = TimingFamilies.of(device);
        ClassBasedDelayEstimator ref = (ClassBasedDelayEstimator) family.createDelayEstimator(device,
                SpeedGrade.of("2MP"), Corner.SLOW_MAX, true);
        ClassBasedDelayEstimator hp = (ClassBasedDelayEstimator) family.createDelayEstimator(device,
                SpeedGrade.of("2HP"), Corner.SLOW_MAX, true);
        InterconnectParams params = hp.getParams();
        Assertions.assertTrue(params.hasFanoutSpeedScales());
        float speed = params.getSpeedScale("2HP");
        float fanout = params.getFanoutSpeedScale("-2HP");
        Assertions.assertTrue(fanout > 1f && speed < 1f, fanout + " " + speed);
        Assertions.assertEquals(1f, params.getFanoutSpeedScale("2MP"));
        Assertions.assertEquals(speed / fanout, hp.getScale() / hp.getFanoutScale(), 1e-5f);
        Assertions.assertEquals(ref.getScale(), ref.getFanoutScale());

        Node node = device.getNode("INT_X20Y100/OUT_NN4_E_BEG0");
        float ratio = hp.getFanoutDelay(node, 3) / ref.getFanoutDelay(node, 3);
        Assertions.assertEquals(fanout, ratio, 1e-4f);
        Assertions.assertEquals(speed, hp.getDelayOf(node) / (float) ref.getDelayOf(node), 0.02f);

        Device us = Device.getDevice("xcvu3p");
        ClassBasedDelayEstimator usEst = (ClassBasedDelayEstimator) TimingFamilies.of(us).createDelayEstimator(us,
                SpeedGrade.of("1"), Corner.SLOW_MAX, true);
        Assertions.assertFalse(usEst.getParams().hasFanoutSpeedScales());
        Assertions.assertEquals(usEst.getScale(), usEst.getFanoutScale());
    }

    /** The sum of a node's track deltas (see InterconnectParams#getTrackDeltas()) */
    private static float getTrackDelta(InterconnectParams params, Node node) {
        float delta = 0;
        for (Wire w : node.getAllWiresInNode()) {
            Map<String, Float> m = params.getTrackDeltas().get(w.getTile().getTileTypeEnum().name());
            if (m != null) {
                delta += m.getOrDefault(w.getWireName(), 0f);
            }
        }
        return delta;
    }

    /**
     * Singles across the I/O column of the same class and span differ in delay by the difference of the track
     * deltas of the tracks they take through the column (some by more than 100 ps); a single that crosses no I/O
     * column gets none
     */
    @Test
    public void testTrackDeltasOfColumnCrossing() {
        Device device = Device.getDevice("xcvu3p");
        ClassBasedDelayEstimator est = (ClassBasedDelayEstimator) TimingFamilies.of(device)
                .createDelayEstimator(device, null, Corner.SLOW_MAX, true);
        InterconnectParams params = est.getParams();
        Assertions.assertTrue(params.hasTrackDeltas());
        Assertions.assertEquals("IO", params.getColClassNames().get(params.getTrackDeltaClass()));
        Node first = null;
        float spread = 0;
        for (int i = 0; i < 8; i++) {
            // Singles from INT_X35 to INT_X36 cross the I/O column
            Node node = device.getNode("INT_X35Y261/EE1_E_BEG" + i);
            if (first == null) {
                first = node;
                continue;
            }
            Assertions.assertEquals(est.getNodeClass(first), est.getNodeClass(node));
            float expected = (getTrackDelta(params, node) - getTrackDelta(params, first)) * est.getScale();
            Assertions.assertEquals(expected, est.getDelayOf(node) - est.getDelayOf(first), 1.01f, node.toString());
            spread = Math.max(spread, Math.abs(expected));
        }
        Assertions.assertTrue(spread > 100, Float.toString(spread));
        Assertions.assertEquals(0f, getTrackDelta(params, device.getNode("INT_X2Y261/EE1_E_BEG0")));
    }

    /** Track deltas may apply to the crossings of several column and row classes */
    @Test
    public void testTrackDeltaClasses() {
        String[] nodeClasses = {"NONE", "WIRE"};
        InterconnectParams params = new InterconnectParams.Builder("test", Corner.SLOW_MAX, "2", nodeClasses)
                .setColClasses(new String[]{"C1", "C2"}, new String[]{"^C1_", "^C2_"})
                .setRowClasses(new String[]{"R1"}, new String[]{"^R1_"})
                .setCrossingWeights(new float[2][3])
                .setTrackDeltas(new String[]{"C2", "R1"}, new String[]{"INTF.W0", "INTF.W1"}, new short[]{15, -20})
                .build();
        Assertions.assertTrue(params.hasTrackDeltas());
        Assertions.assertArrayEquals(new int[]{1, 2}, params.getTrackDeltaClasses());
        Assertions.assertEquals(1, params.getTrackDeltaClass());
        Assertions.assertEquals(1.5f, params.getTrackDeltas().get("INTF").get("W0"));
        Assertions.assertEquals(-2f, params.getTrackDeltas().get("INTF").get("W1"));

        // The single-class form still applies to one column class
        InterconnectParams one = new InterconnectParams.Builder("test", Corner.SLOW_MAX, "2", nodeClasses)
                .setColClasses(new String[]{"C1", "C2"}, new String[]{"^C1_", "^C2_"})
                .setCrossingWeights(new float[2][2])
                .setTrackDeltas("C2", new String[]{"INTF.W0"}, new short[]{15})
                .build();
        Assertions.assertArrayEquals(new int[]{1}, one.getTrackDeltaClasses());

        Assertions.assertThrows(IllegalArgumentException.class, () -> new InterconnectParams.Builder("test",
                Corner.SLOW_MAX, "2", nodeClasses)
                .setColClasses(new String[]{"C1"}, new String[]{"^C1_"})
                .setCrossingWeights(new float[2][1])
                .setTrackDeltas(new String[]{"R9"}, new String[]{"INTF.W0"}, new short[]{15})
                .build());
    }

    /**
     * Versal quads across a hard block column (same class and gap crossings) differ in delay by the difference of the
     * track deltas of the wires they have in the column's interface tiles; the deltas apply to the crossings of
     * several column and row classes
     */
    @Test
    public void testTrackDeltasOfSeveralCrossingClasses() {
        Device device = Device.getDevice("xcvc1902");
        ClassBasedDelayEstimator est = (ClassBasedDelayEstimator) TimingFamilies.of(device)
                .createDelayEstimator(device, null, Corner.SLOW_MAX, true);
        InterconnectParams params = est.getParams();
        Assertions.assertTrue(params.hasTrackDeltas());
        List<String> classes = new ArrayList<>(params.getColClassNames());
        classes.addAll(params.getRowClassNames());
        Set<String> trackClasses = new HashSet<>();
        for (int c : params.getTrackDeltaClasses()) {
            trackClasses.add(classes.get(c));
        }
        Assertions.assertTrue(trackClasses.containsAll(Arrays.asList("HB", "URAM", "BRAM", "VNOC")),
                trackClasses.toString());
        Map<String, Node> firstOfGroup = new HashMap<>();
        float spread = 0;
        int compared = 0;
        for (String dir : new String[]{"E", "W"}) {
            for (int i = 0; i < 8; i++) {
                // Quads from INT_X106 east cross a hard block column
                Node node = device.getNode("INT_X106Y156/OUT_EE4_" + dir + "_BEG" + i);
                int[] counts = new int[params.getNumCrossingClasses()];
                est.getCrossings().getCrossings(node, counts);
                Assertions.assertTrue(counts[params.getColClassNames().indexOf("HB")] > 0, node.toString());
                String group = est.getNodeClass(node) + Arrays.toString(counts);
                Node first = firstOfGroup.putIfAbsent(group, node);
                if (first == null) {
                    continue;
                }
                float expected = (getTrackDelta(params, node) - getTrackDelta(params, first)) * est.getScale();
                Assertions.assertEquals(expected, est.getDelayOf(node) - est.getDelayOf(first), 1.01f, node.toString());
                spread = Math.max(spread, Math.abs(expected));
                compared++;
            }
        }
        Assertions.assertTrue(compared > 0);
        Assertions.assertTrue(spread > 20, Float.toString(spread));
        // Nodes that cross no gap get no track delta, also those with wires that have one: the doubles of a tile that
        // cross nothing have the delay of their class
        Map<Integer, Node> firstOfClass = new HashMap<>();
        boolean listedWires = false;
        for (String dir : new String[]{"E", "W"}) {
            for (int i = 0; i < 8; i++) {
                Node node = device.getNode("INT_X50Y100/OUT_EE2_" + dir + "_BEG" + i);
                int[] counts = new int[params.getNumCrossingClasses()];
                est.getCrossings().getCrossings(node, counts);
                Assertions.assertEquals(0, Arrays.stream(counts).sum(), node.toString());
                listedWires |= getTrackDelta(params, node) != 0;
                Node first = firstOfClass.putIfAbsent(est.getNodeClass(node), node);
                if (first != null) {
                    Assertions.assertEquals(est.getDelayOf(first), est.getDelayOf(node), node.toString());
                }
            }
        }
        Assertions.assertTrue(listedWires);
    }
}
