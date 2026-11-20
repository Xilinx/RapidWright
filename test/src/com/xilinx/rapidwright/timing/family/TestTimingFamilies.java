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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusDelayEstimator;
import com.xilinx.rapidwright.timing.versal.VersalDelayEstimator;
import com.xilinx.rapidwright.timing.versal.VersalTimingFamily;

public class TestTimingFamilies {

    @Test
    public void testRegistry() {
        for (Series series : Series.values()) {
            if (series == Series.UltraScale) {
                continue;
            }
            TimingFamily family = TimingFamilies.of(series);
            Assertions.assertEquals(series, family.getSeries());
            Assertions.assertSame(family, TimingFamilies.of(series));
        }
        Assertions.assertEquals(EnumSet.of(Series.UltraScalePlus, Series.Versal), TimingFamilies.getSupportedSeries());
        Assertions.assertTrue(TimingFamilies.isSupported(Series.UltraScalePlus));
        Assertions.assertTrue(TimingFamilies.isSupported(Series.Versal));
        Assertions.assertFalse(TimingFamilies.isSupported(Series.UltraScale));
        Assertions.assertFalse(TimingFamilies.isSupported(Series.Series7));
    }

    @Test
    public void testSeries7Stub() {
        TimingFamily family = TimingFamilies.of(Series.Series7);
        UnsupportedOperationException e = Assertions.assertThrows(UnsupportedOperationException.class,
                () -> family.createDelayEstimator(null, null, Corner.SLOW_MAX, false));
        Assertions.assertEquals(TimingFamilies.getUnsupportedMessage(Series.Series7), e.getMessage());
        Assertions.assertSame(RouterKnobs.DEFAULT, family.getRouterKnobs());
    }

    /**
     * UltraScale devices have no timing model: the message says so and names the series that have one.
     */
    @Test
    public void testUltraScaleUnsupported() {
        String expected = "No timing model for the UltraScale series; RapidWright has timing models for the"
                + " [UltraScalePlus, Versal] series";
        Assertions.assertEquals(expected, TimingFamilies.getUnsupportedMessage(Series.UltraScale));
        UnsupportedOperationException e = Assertions.assertThrows(UnsupportedOperationException.class,
                () -> TimingFamilies.of(Series.UltraScale));
        Assertions.assertEquals(expected, e.getMessage());
    }

    @Test
    public void testRouterKnobs() {
        RouterKnobs usplus = TimingFamilies.of(Series.UltraScalePlus).getRouterKnobs();
        Assertions.assertEquals(0.99f, usplus.getMaxCriticality());
        Assertions.assertEquals(1.2f, usplus.getTimingDrivenPresentCongestionMultiplier());
        Assertions.assertTrue(usplus.isTimeAllNetsInPartialRouting());
        Assertions.assertEquals(1.85f, usplus.getLookaheadRatioX());
        Assertions.assertEquals(1.1f, usplus.getLookaheadRatioY());
        RouterKnobs versal = TimingFamilies.of(Series.Versal).getRouterKnobs();
        Assertions.assertEquals(0.95f, versal.getMaxCriticality());
        Assertions.assertTrue(versal.hasTimingDrivenPresentCongestionMultiplier());
        Assertions.assertEquals(1.2f, versal.getTimingDrivenPresentCongestionMultiplier());
        Assertions.assertTrue(versal.isTimeAllNetsInPartialRouting());
        Assertions.assertEquals(2.0f, versal.getLookaheadRatioX());
        Assertions.assertEquals(1.0f, versal.getLookaheadRatioY());
        Assertions.assertEquals(0.99f, RouterKnobs.DEFAULT.getMaxCriticality());
        Assertions.assertFalse(RouterKnobs.DEFAULT.hasTimingDrivenPresentCongestionMultiplier());
        Assertions.assertFalse(RouterKnobs.DEFAULT.isTimeAllNetsInPartialRouting());
        Assertions.assertFalse(RouterKnobs.DEFAULT.hasLookaheadRatios());
    }

    @Test
    public void testLookaheadSwitch() {
        String old = System.getProperty(RouterKnobs.LOOKAHEAD_PROPERTY);
        String oldWeight = System.getProperty(RouterKnobs.LOOKAHEAD_WEIGHT_PROPERTY);
        try {
            System.clearProperty(RouterKnobs.LOOKAHEAD_PROPERTY);
            System.clearProperty(RouterKnobs.LOOKAHEAD_WEIGHT_PROPERTY);
            Assertions.assertFalse(RouterKnobs.isAnisotropicLookaheadSelected());
            Assertions.assertEquals(1f, RouterKnobs.getLookaheadWeight());
            System.setProperty(RouterKnobs.LOOKAHEAD_PROPERTY, RouterKnobs.LOOKAHEAD_ANISOTROPIC);
            System.setProperty(RouterKnobs.LOOKAHEAD_WEIGHT_PROPERTY, "1.2");
            Assertions.assertTrue(RouterKnobs.isAnisotropicLookaheadSelected());
            Assertions.assertEquals(1.2f, RouterKnobs.getLookaheadWeight());
            System.setProperty(RouterKnobs.LOOKAHEAD_PROPERTY, "bogus");
            Assertions.assertThrows(IllegalArgumentException.class, RouterKnobs::isAnisotropicLookaheadSelected);
        } finally {
            restore(RouterKnobs.LOOKAHEAD_PROPERTY, old);
            restore(RouterKnobs.LOOKAHEAD_WEIGHT_PROPERTY, oldWeight);
        }
    }

    @Test
    public void testKnobOverrides() {
        RouterKnobs versal = TimingFamilies.of(Series.Versal).getRouterKnobs();
        String[] keys = {RouterKnobs.MAX_CRITICALITY_PROPERTY, RouterKnobs.TD_PRESENT_CONGESTION_MULTIPLIER_PROPERTY,
                RouterKnobs.TIME_ALL_NETS_PROPERTY};
        String[] old = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            old[i] = System.getProperty(keys[i]);
            System.clearProperty(keys[i]);
        }
        try {
            Assertions.assertSame(versal, versal.withOverrides());
            System.setProperty(RouterKnobs.MAX_CRITICALITY_PROPERTY, "0.97");
            System.setProperty(RouterKnobs.TIME_ALL_NETS_PROPERTY, "false");
            RouterKnobs k = versal.withOverrides();
            Assertions.assertEquals(0.97f, k.getMaxCriticality());
            Assertions.assertFalse(k.isTimeAllNetsInPartialRouting());
            Assertions.assertEquals(1.2f, k.getTimingDrivenPresentCongestionMultiplier());
            Assertions.assertEquals(versal.getLookaheadRatioX(), k.getLookaheadRatioX());
            System.setProperty(RouterKnobs.TD_PRESENT_CONGESTION_MULTIPLIER_PROPERTY, "NaN");
            Assertions.assertFalse(versal.withOverrides().hasTimingDrivenPresentCongestionMultiplier());
        } finally {
            for (int i = 0; i < keys.length; i++) {
                restore(keys[i], old[i]);
            }
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    public void testSpeedGrade() {
        Assertions.assertNull(SpeedGrade.of((String) null));
        Assertions.assertNull(SpeedGrade.of(""));
        Assertions.assertEquals("2MP", SpeedGrade.of("-2MP").getName());
        Assertions.assertEquals(SpeedGrade.of("-2MP"), SpeedGrade.of("2MP"));
        Assertions.assertEquals("-2MP", SpeedGrade.of("2MP").toString());
    }

    @Test
    public void testNearestSpeedGrade() {
        List<String> usplus = Arrays.asList("1", "1L", "1LV", "2L", "2LV", "3", "1Q", "1M", "2LVI", "2");
        Assertions.assertEquals("2", SpeedGrade.nearest("-2", usplus));
        // Same voltage class, nearest speed digit
        Assertions.assertEquals("2LV", SpeedGrade.nearest("3LV", usplus));
        Assertions.assertEquals("2L", SpeedGrade.nearest("-3L", usplus));
        // Else same speed digit, the longest common voltage class prefix, then the shortest
        Assertions.assertEquals("2L", SpeedGrade.nearest("2LE", usplus));
        Assertions.assertEquals("1", SpeedGrade.nearest("1E", usplus));
        // A tie of speed digits takes the slower grade
        Assertions.assertEquals("1L", SpeedGrade.nearest("2L", Arrays.asList("1L", "3L")));
        List<String> gen1 = Arrays.asList("1LHP", "1LP", "1MP", "2HP", "2LHP", "2LLI", "2LP", "2MHP", "3HP", "2MP");
        Assertions.assertEquals("2MP", SpeedGrade.nearest("3MP", gen1));
        Assertions.assertEquals("2HP", SpeedGrade.nearest("1HP", gen1));
        Assertions.assertEquals("2LHP", SpeedGrade.nearest("3LHP", gen1));
        // Nothing near: the reference grade
        Assertions.assertNull(SpeedGrade.nearest("4X", gen1));
        Assertions.assertEquals("2MP", SpeedGrade.resolve("4X", gen1, "2MP", "test data"));
        Assertions.assertEquals("2MHP", SpeedGrade.resolve("-2MHP", gen1, "2MP", "test data"));

        // The delay estimators use the nearest grade's factor
        Device device = Device.getDevice("xcvu3p");
        TimingFamily family = TimingFamilies.of(device);
        UltraScalePlusDelayEstimator e3 = (UltraScalePlusDelayEstimator) family.createDelayEstimator(device,
                SpeedGrade.of("-3LV"), Corner.SLOW_MAX, false);
        UltraScalePlusDelayEstimator e2 = (UltraScalePlusDelayEstimator) family.createDelayEstimator(device,
                SpeedGrade.of("-2LV"), Corner.SLOW_MAX, false);
        Assertions.assertEquals(e2.getScale(), e3.getScale());
        Assertions.assertNotEquals(1f, e3.getScale());
    }

    @Test
    public void testFactories() {
        Device usplus = Device.getDevice("xcvu3p");
        TimingFamily family = TimingFamilies.of(usplus);
        Assertions.assertTrue(DelayEstimator.create(usplus, false) instanceof UltraScalePlusDelayEstimator);
        // All four corners
        for (Corner corner : Corner.values()) {
            Assertions.assertTrue(family.createDelayEstimator(usplus, null, corner, false)
                    instanceof UltraScalePlusDelayEstimator);
        }
        Assertions.assertNotNull(family.getFittedCellArcs(usplus));

        Device versal = Device.getDevice("xcvc1902");
        Assertions.assertTrue(DelayEstimator.create(versal, false) instanceof VersalDelayEstimator);
        Assertions.assertNotNull(TimingFamilies.of(versal).getFittedCellArcs(versal));
        // A faster speed grade scales the delays down
        VersalDelayEstimator fast = (VersalDelayEstimator) DelayEstimator.create(versal, "-2HP", false);
        VersalDelayEstimator ref = (VersalDelayEstimator) DelayEstimator.create(versal, "-2MP", false);
        Assertions.assertTrue(fast.getScale() < ref.getScale());
    }

    /**
     * The nodes of a sample of tiles: those of the tile of a node that crosses a gap with track deltas (if any), and
     * of INT tiles across the device.
     */
    private static List<Node> getSampleNodes(Device device, String trackTile) {
        List<Tile> tiles = new ArrayList<>();
        if (trackTile != null) {
            tiles.add(device.getTile(trackTile));
        }
        int numInt = 0;
        for (Tile t : device.getAllTiles()) {
            if (t.getName().startsWith("INT_X") && numInt++ % 997 == 0) {
                tiles.add(t);
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (Tile t : tiles) {
            for (int w = 0; w < t.getWireCount(); w++) {
                Node n = Node.getNode(t, w);
                if (n != null && n.getTile().equals(t)) {
                    nodes.add(n);
                }
            }
        }
        return nodes;
    }

    /** Asserts that two estimators give the same delays, scales and lookahead, on a sample of nodes */
    private static void assertSameDelays(ClassBasedDelayEstimator expected, ClassBasedDelayEstimator actual,
                                         List<Node> nodes, String what) {
        Assertions.assertEquals(expected.getScale(), actual.getScale(), what);
        Assertions.assertEquals(expected.getFanoutScale(), actual.getFanoutScale(), what);
        Assertions.assertEquals(expected.getLookaheadDelayPerTileX(), actual.getLookaheadDelayPerTileX(), what);
        Assertions.assertEquals(expected.getLookaheadDelayPerTileY(), actual.getLookaheadDelayPerTileY(), what);
        Assertions.assertEquals(expected.getAdmissibleLookaheadDelayPerTileX(),
                actual.getAdmissibleLookaheadDelayPerTileX(), what);
        Assertions.assertEquals(expected.getAdmissibleLookaheadDelayPerTileY(),
                actual.getAdmissibleLookaheadDelayPerTileY(), what);
        Assertions.assertEquals(expected.getPreRouteBaseDelay(), actual.getPreRouteBaseDelay(), what);
        int numClasses = expected.getParams().getNumNodeClasses();
        for (int p = 0; p < numClasses; p++) {
            for (int c = 0; c < numClasses; c++) {
                Assertions.assertEquals(expected.getExtraDelay(p, c), actual.getExtraDelay(p, c), what);
            }
        }
        for (Node n : nodes) {
            Assertions.assertEquals(expected.getNodeClass(n), actual.getNodeClass(n), what + " " + n);
            Assertions.assertEquals(expected.getDelayOf(n), actual.getDelayOf(n), what + " " + n);
            Assertions.assertEquals(expected.getFanoutDelay(n, 3), actual.getFanoutDelay(n, 3), what + " " + n);
            Assertions.assertEquals(expected.getFanoutLoadDelay(n, 8), actual.getFanoutLoadDelay(n, 8), what + " " + n);
        }
    }

    /**
     * An estimator created from a family's own interconnect parameters of a corner gives the delays of the family's
     * own estimator at that corner (and grade, U-turn setting): on UltraScale+ (pin and track deltas, the nodes of
     * a tile across an I/O column) and on Versal at a speed grade whose fanout delays have a factor of their own
     * (track deltas: the nodes of a tile across a hard block column). The sample does exercise the track deltas
     * and the fanout factor. On Versal gen2 (no track deltas, no fanout factors in its data), at a grade whose
     * delays are scaled.
     */
    @ParameterizedTest
    @CsvSource({"xcvu3p,-2,INT_X35Y261", "xcvu3p,-1,INT_X35Y261", "xcvc1902,-2HP,INT_X106Y156",
        "xcvc1902,-1LP,INT_X106Y156", "xc2ve3858,-1LP,"})
    public void testDelayEstimatorFromParams(String deviceName, String grade, String trackTile) {
        Device device = Device.getDevice(deviceName);
        TimingFamily family = TimingFamilies.of(device);
        SpeedGrade speedGrade = SpeedGrade.of(grade);
        List<Node> nodes = getSampleNodes(device, trackTile);
        Assertions.assertTrue(nodes.size() > 1000, Integer.toString(nodes.size()));
        for (Corner corner : Corner.values()) {
            for (boolean useUTurnNodes : new boolean[]{true, false}) {
                String what = deviceName + " " + grade + " " + corner + " " + useUTurnNodes;
                ClassBasedDelayEstimator own = (ClassBasedDelayEstimator) family.createDelayEstimator(device,
                        speedGrade, corner, useUTurnNodes);
                ClassBasedDelayEstimator fromParams = family.createDelayEstimator(device, own.getParams(), speedGrade,
                        useUTurnNodes);
                Assertions.assertNotSame(own, fromParams);
                Assertions.assertEquals(own.getClass(), fromParams.getClass());
                Assertions.assertSame(own.getParams(), fromParams.getParams());
                assertSameDelays(own, fromParams, nodes, what);
            }
        }
        ClassBasedDelayEstimator own = (ClassBasedDelayEstimator) family.createDelayEstimator(device, speedGrade,
                Corner.SLOW_MAX, true);
        if (trackTile == null) {
            Assertions.assertFalse(own.getParams().hasTrackDeltas());
            Assertions.assertNotEquals(1f, own.getScale());
            return;
        }
        // The sample has nodes with track deltas: without them, some delays change
        InterconnectParams noTracks = own.getParams().toBuilder("no track deltas")
                .setTrackDeltas(new String[0], new String[0], new short[0]).build();
        Assertions.assertFalse(noTracks.hasTrackDeltas());
        ClassBasedDelayEstimator withoutTracks = family.createDelayEstimator(device, noTracks, speedGrade, true);
        int changed = 0;
        for (Node n : nodes) {
            changed += own.getDelayOf(n) != withoutTracks.getDelayOf(n) ? 1 : 0;
        }
        Assertions.assertTrue(changed > 0);
        if (device.getSeries() == Series.Versal) {
            // A grade whose fanout delays scale differently from its other delays
            Assertions.assertTrue(own.getParams().hasFanoutSpeedScales());
            Assertions.assertNotEquals(own.getScale(), own.getFanoutScale());
        }
    }

    /**
     * Parameters derived with a changed base delay of one node class change the delays of that class's nodes by
     * the scaled change, and no others; parameters of another family's classes are refused; a family without a
     * class-based model has no such factory.
     */
    @Test
    public void testDelayEstimatorFromDerivedParams() {
        Device device = Device.getDevice("xcvu3p");
        TimingFamily family = TimingFamilies.of(device);
        ClassBasedDelayEstimator own = (ClassBasedDelayEstimator) family.createDelayEstimator(device,
                SpeedGrade.of("-1"), Corner.SLOW_MAX, true);
        InterconnectParams params = own.getParams();
        int cls = params.getNodeClassNames().indexOf("SINGLE_E");
        float original = params.getBaseDelay(cls);
        float[] base = new float[params.getNumNodeClasses()];
        float[] length = new float[base.length];
        float[] fanout = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            base[i] = params.getBaseDelay(i) + (i == cls ? 10f : 0f);
            length[i] = params.getLengthWeight(i);
            fanout[i] = params.getFanoutDelay(i);
        }
        InterconnectParams derived = params.toBuilder(params.getDataVersion() + ", SINGLE_E + 10 ps")
                .setClassDelays(base, length, fanout).build();
        ClassBasedDelayEstimator est = family.createDelayEstimator(device, derived, SpeedGrade.of("-1"), true);
        Assertions.assertEquals(own.getScale(), est.getScale());
        int moved = 0;
        for (Node n : getSampleNodes(device, "INT_X35Y261")) {
            int d = est.getDelayOf(n) - own.getDelayOf(n);
            if (own.getNodeClass(n) == cls) {
                Assertions.assertEquals(10f * own.getScale(), d, 1f, n.toString());
                moved++;
            } else {
                Assertions.assertEquals(0, d, n.toString());
            }
        }
        Assertions.assertTrue(moved > 0);
        // The family's own data is unchanged
        Assertions.assertEquals(original, ((ClassBasedDelayEstimator) family.createDelayEstimator(device,
                SpeedGrade.of("-1"), Corner.SLOW_MAX, true)).getParams().getBaseDelay(cls));

        Device versal = Device.getDevice("xcvc1902");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TimingFamilies.of(versal).createDelayEstimator(versal, params, null, true));
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> TimingFamilies.of(Series.Series7).createDelayEstimator(null, params, null, true));
    }
}
