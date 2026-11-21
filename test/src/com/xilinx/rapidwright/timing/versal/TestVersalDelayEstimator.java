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

package com.xilinx.rapidwright.timing.versal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.CrossingIndex;
import com.xilinx.rapidwright.timing.family.InterconnectParams;
import com.xilinx.rapidwright.timing.family.SlrCoordinates;

public class TestVersalDelayEstimator {

    @ParameterizedTest
    @CsvSource({
            "xcvc1902,false",
            "xcvp1202,false",
            "xcv80,false",
            "xc2ve3858,true",
    })
    public void testIsGen2(String deviceName, boolean expected) {
        Device device = Device.getDevice(deviceName);
        Assertions.assertEquals(expected ? VersalTimingFamily.Generation.GEN2 : VersalTimingFamily.Generation.GEN1,
                VersalTimingFamily.getGeneration(device));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GEN1", "GEN2"})
    public void testInterconnectParams(String gen) {
        InterconnectParams terms = VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.valueOf(gen),
                Corner.SLOW_MAX);
        Assertions.assertEquals(VersalNodeClass.values().length, terms.getNumNodeClasses());
        for (VersalNodeClass c : VersalNodeClass.values()) {
            Assertions.assertEquals(c.name(), terms.getNodeClassNames().get(c.ordinal()));
        }
        int sdqNode = VersalNodeClass.SDQNODE.ordinal();
        for (VersalNodeClass c : VersalNodeClass.values()) {
            int i = c.ordinal();
            Assertions.assertTrue(terms.getBaseDelay(i) >= 0, c.name());
            if (c.name().matches("[HV](SINGLE|DOUBLE|QUAD|LONG\\d+)_[EWNS]")) {
                // A general wire and the SDQNODE it drives make one stage, between which the delay is split
                float stage = terms.getBaseDelay(i) + terms.getBaseDelay(sdqNode) + terms.getTransitionDelay(i, sdqNode);
                Assertions.assertTrue(stage > 0, c.name());
            }
        }
        Assertions.assertTrue(terms.getLookaheadPerTileX() > 0);
        Assertions.assertTrue(terms.getLookaheadPerTileY() > 0);
        Assertions.assertTrue(terms.getNumCrossingClasses() > 0);
        Assertions.assertEquals(1.0f, terms.getSpeedScale("-" + terms.getSpeedRef()));
    }

    @Test
    public void testIntWiresClassified() {
        Device device = Device.getDevice("xcvc1902");
        Tile tile = device.getTile("INT_X40Y120");
        for (int i = 0; i < tile.getWireCount(); i++) {
            IntentCode ic = tile.getWireIntentCode(i);
            if (ic == IntentCode.INTENT_DEFAULT) {
                continue;
            }
            Assertions.assertNotEquals(VersalNodeClass.OTHER, VersalNodeClass.of(Node.getNode(tile, i)),
                    tile.getWireName(i) + " " + ic);
        }
    }

    /**
     * In every tile of a first and a second generation device, the only wires without a class (OTHER) are site pins,
     * clock network wires and wires without a routing role (INTENT_DEFAULT): the unknown-wire check of
     * {@link VersalNodeClass#getWireClasses} passes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"xcvc1902", "xc2ve3858"})
    public void testAllRoutingWiresClassified(String deviceName) {
        Device device = Device.getDevice(deviceName);
        byte[][] classes = VersalNodeClass.getWireClasses(device);
        int checked = 0;
        for (TileTypeEnum type : TileTypeEnum.values()) {
            byte[] tileClasses = classes[type.ordinal()];
            if (tileClasses == null) {
                continue;
            }
            Tile tile = device.getArbitraryTileOfType(type);
            for (int i = 0; i < tile.getWireCount(); i++) {
                IntentCode ic = tile.getWireIntentCode(i);
                String name = tile.getWireName(i);
                if (name.endsWith("_PIN") || ic == IntentCode.INTENT_DEFAULT || ic.name().startsWith("NODE_GLOBAL_")) {
                    continue;
                }
                Assertions.assertNotEquals(VersalNodeClass.OTHER.ordinal(), tileClasses[i], tile + "/" + name + " " + ic);
                checked++;
            }
        }
        Assertions.assertTrue(checked > 10000, Integer.toString(checked));
    }

    @Test
    public void testWireClassesPerDevice() {
        // The same tile type can have different wires in different devices
        for (String deviceName : new String[]{"xcvc1902", "xcvp1202"}) {
            Device device = Device.getDevice(deviceName);
            Tile tile = device.getArbitraryTileOfType(TileTypeEnum.RCLK_INT_L_FT);
            for (int i = 0; i < tile.getWireCount(); i++) {
                Node node = Node.getNode(tile, i);
                if (node == null) {
                    continue;
                }
                // A node's class is that of its base wire
                Tile base = node.getTile();
                Assertions.assertEquals(VersalNodeClass.classify(base.getWireIntentCode(node.getWireIndex()), node.getWireName()),
                        VersalNodeClass.of(node), deviceName + " " + node);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            // General wires within a clock region and away from hard blocks cross nothing
            "INT_X50Y46/OUT_NN1_E_BEG0,VSINGLE_N,1,''",
            "INT_X40Y120/OUT_EE2_E_BEG0,HDOUBLE_E,1,''",
            // Crossing the RCLK row between rows 47 and 48, and the clock region boundary between 95 and 96
            "INT_X50Y47/OUT_NN1_E_BEG0,VSINGLE_N,1,RCLK",
            "INT_X50Y44/OUT_NN7_BEG0,VLONG7_N,7,RCLK",
            "INT_X50Y94/OUT_NN7_BEG0,VLONG7_N,7,RBRK REBUF",
            // Crossing the hard block column between X107 and X108
            "INT_X104Y120/OUT_EE10_BEG0,HLONG10_E,5,CBRK CBRK CBRK HB",
    })
    public void testCrossings(String nodeName, String expectedClass, int expectedLength, String expectedCrossings) {
        Device device = Device.getDevice("xcvc1902");
        VersalDelayEstimator estimator = (VersalDelayEstimator) DelayEstimator.create(device, false);
        CrossingIndex crossings = estimator.getCrossings();
        InterconnectParams terms = estimator.getParams();
        List<String> classNames = new ArrayList<>(terms.getColClassNames());
        classNames.addAll(terms.getRowClassNames());

        Node node = device.getNode(nodeName);
        Assertions.assertEquals(VersalNodeClass.valueOf(expectedClass), VersalNodeClass.of(node));
        int[] counts = new int[crossings.getNumCrossingClasses()];
        Assertions.assertEquals(expectedLength, crossings.getCrossings(node, counts));
        Assertions.assertEquals(expectedCrossings, crossed(counts, classNames));
    }

    private static String crossed(int[] counts, List<String> classNames) {
        List<String> crossed = new ArrayList<>();
        for (int j = 0; j < counts.length; j++) {
            for (int k = 0; k < counts[j]; k++) {
                crossed.add(classNames.get(j));
            }
        }
        return String.join(" ", crossed);
    }

    /**
     * The INT tile coordinates of xcvp1902 restart in each of its SLRs (INT_S&lt;slr&gt;X&lt;x&gt;Y&lt;y&gt;): a node
     * crosses the gaps between the INT columns and rows of its own SLR, and its delay is that of its class, span
     * and those crossings. (Gaps found over the INT tiles of all SLRs at once put the hard block columns of other
     * SLRs between neighboring INT columns: the first node below counted 22 crossings.)
     */
    @ParameterizedTest
    @CsvSource({
            // Horizontal: the column gaps of the clock region row of the node's SLR
            "INT_S0X53Y400/OUT_WW10_BEG0,HLONG10_W,5,CBRK",
            "INT_S2X53Y400/OUT_WW10_BEG0,HLONG10_W,5,CBRK",
            "INT_S3X53Y400/OUT_EE6_BEG1,HLONG6_E,3,CBRK VNOC INTF",
            // Vertical: the row gaps of the node's INT column in its SLR
            "INT_S1X84Y17/OUT_SS12_BEG0,VLONG12_S,12,REBUF REBUF REBUF",
            "INT_S1X53Y400/OUT_NN7_BEG0,VLONG7_N,7,REBUF",
    })
    public void testCrossingsSlrLocalCoordinates(String nodeName, String expectedClass, int expectedLength,
                                                 String expectedCrossings) {
        Device device = Device.getDevice("xcvp1902");
        VersalDelayEstimator estimator = (VersalDelayEstimator) DelayEstimator.create(device, false);
        CrossingIndex crossings = estimator.getCrossings();
        InterconnectParams terms = estimator.getParams();
        List<String> classNames = new ArrayList<>(terms.getColClassNames());
        classNames.addAll(terms.getRowClassNames());

        Node node = device.getNode(nodeName);
        Assertions.assertEquals(VersalNodeClass.valueOf(expectedClass), VersalNodeClass.of(node));
        int[] counts = new int[crossings.getNumCrossingClasses()];
        Assertions.assertEquals(expectedLength, crossings.getCrossings(node, counts));
        Assertions.assertEquals(expectedCrossings, crossed(counts, classNames));

        int cls = estimator.getNodeClass(node);
        Assertions.assertTrue(terms.dependsOnSpan(cls));
        int[] expectedCounts = new int[counts.length];
        for (String c : expectedCrossings.split(" ")) {
            expectedCounts[classNames.indexOf(c)]++;
        }
        float delay = terms.getBaseDelay(cls);
        delay += terms.getLengthWeight(cls) * expectedLength;
        float[] weights = terms.getCrossingWeights(cls);
        for (int j = 0; j < weights.length; j++) {
            delay += weights[j] * expectedCounts[j];
        }
        Assertions.assertEquals(Math.max(0, Math.round(delay * estimator.getScale())), estimator.getDelayOf(node));
    }

    /**
     * The column class of the CIDB tiles between the SLRs of xcvp1902 that lie side by side (CIDB) only adds delay to
     * super long lines: no other node class has a crossing weight or a fanout crossing delay for it, and it takes no
     * track deltas, so that no other node changes.
     */
    @ParameterizedTest
    @EnumSource(Corner.class)
    public void testCidbColumnOnlyForSuperLongLines(Corner corner) {
        InterconnectParams terms = VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1, corner);
        int cidb = terms.getColClassNames().indexOf("CIDB");
        Assertions.assertTrue(cidb >= 0, terms.getColClassNames().toString());
        for (VersalNodeClass c : VersalNodeClass.values()) {
            float w = terms.getCrossingWeights(c.ordinal())[cidb];
            if (c == VersalNodeClass.SLL_DATA) {
                Assertions.assertTrue(w > 0, c.name());
            } else {
                Assertions.assertEquals(0f, w, c.name());
            }
        }
        Assertions.assertEquals(0f, terms.getFanoutCrossingDelay(cidb));
        Assertions.assertFalse(Arrays.stream(terms.getTrackDeltaClasses()).anyMatch(j -> j == cidb));
    }

    /** Super long lines of xcvp1902 between SLRs side by side, one of each set of crossed columns */
    private static final String[] XCVP1902_HORIZONTAL_SLLS = {
            "SLL2_S0X115Y10/UBUMP0", "SLL2_1_S0X126Y10/UBUMP0", "SLL2_1_S0X129Y10/UBUMP0", "SLL2_S0X123Y10/UBUMP0",
            "SLL2_1_S0X133Y10/UBUMP0",
    };

    /** Super long lines of xcvp1902 between SLRs on top of each other, one of each set of crossed rows */
    private static final String[] XCVP1902_VERTICAL_SLLS = {
            "SLL2_1_S0X16Y516/UBUMP0", "SLL2_1_S0X16Y514/UBUMP0", "SLL2_1_S0X16Y528/UBUMP0", "SLL2_1_S0X16Y530/UBUMP0",
    };

    /** Horizontal super long lines of xcvp1902 within one SLR */
    private static final String[] XCVP1902_SLR_INTERNAL_SLLS = {
            "SLL2_S0X101Y10/UBUMP0", "SLL2_1_S0X16Y10/UBUMP0", "SLL2_S0X13Y10/UBUMP0", "SLL2_S0X22Y10/UBUMP0",
    };

    /**
     * The four SLRs of xcvp1902 lie side by side in pairs, so that, unlike the SLRs of the other Versal devices,
     * which lie on top of each other, some are joined by horizontal super long lines, which span about 22 INT
     * columns. In timing data matching Vivado 2026.1, these are slower than the vertical ones: their SLL_DATA node
     * about 660 against 480 ps at SLOW_MAX and 215 against 125 ps at FAST_MIN, the nodes onto and off them alike.
     * They, and no super long line within an SLR, cross the column of CIDB tiles between the SLRs (class CIDB), whose
     * crossing weight makes every horizontal super long line between SLRs slower than every vertical one at every
     * corner.
     */
    @ParameterizedTest
    @EnumSource(Corner.class)
    public void testHorizontalSuperLongLinesBetweenSlrsSlower(Corner corner) {
        Device device = Device.getDevice("xcvp1902");
        VersalDelayEstimator estimator = new VersalDelayEstimator(device,
                VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1, corner), null);
        InterconnectParams terms = estimator.getParams();
        CrossingIndex crossings = estimator.getCrossings();
        int cidb = terms.getColClassNames().indexOf("CIDB");
        Assertions.assertTrue(cidb >= 0, terms.getColClassNames().toString());
        SlrCoordinates global = SlrCoordinates.of(device);
        int[] counts = new int[crossings.getNumCrossingClasses()];
        float leastHorizontal = Float.MAX_VALUE;
        for (String name : XCVP1902_HORIZONTAL_SLLS) {
            Node sll = device.getNode(name);
            Assertions.assertEquals(VersalNodeClass.SLL_DATA, VersalNodeClass.of(sll), name);
            int[] span = span(sll, global);
            Assertions.assertTrue(span[0] > span[1] && span[2] == 2, name);
            crossings.getCrossings(sll, counts);
            Assertions.assertEquals(1, counts[cidb], name);
            leastHorizontal = Math.min(leastHorizontal, estimator.getDelayOf(sll));
        }
        float mostVertical = 0;
        for (String name : XCVP1902_VERTICAL_SLLS) {
            Node sll = device.getNode(name);
            Assertions.assertEquals(VersalNodeClass.SLL_DATA, VersalNodeClass.of(sll), name);
            int[] span = span(sll, global);
            Assertions.assertTrue(span[1] > span[0] && span[2] == 2, name);
            crossings.getCrossings(sll, counts);
            Assertions.assertEquals(0, counts[cidb], name);
            mostVertical = Math.max(mostVertical, estimator.getDelayOf(sll));
        }
        for (String name : XCVP1902_SLR_INTERNAL_SLLS) {
            Node sll = device.getNode(name);
            Assertions.assertEquals(VersalNodeClass.SLL_DATA, VersalNodeClass.of(sll), name);
            int[] span = span(sll, global);
            Assertions.assertTrue(span[0] > span[1] && span[2] == 1, name);
            crossings.getCrossings(sll, counts);
            Assertions.assertEquals(0, counts[cidb], name);
        }
        Assertions.assertTrue(leastHorizontal > mostVertical, corner + ": " + leastHorizontal + " <= " + mostVertical);
    }

    /**
     * On the devices whose SLRs lie on top of each other, no super long line crosses a column of CIDB tiles,
     * and their delays are those before that class was added: here, at the four corners (SLOW_MAX, SLOW_MIN, FAST_MAX,
     * FAST_MIN), a horizontal and a vertical super long line within an SLR and a vertical one between two SLRs of
     * xcv80.
     */
    @ParameterizedTest
    @CsvSource({
            "SLL_X23Y10/UBUMP0,723,269,651,206",
            "SLL_1_X124Y100/UBUMP3,518,254,445,191",
            "SLL_1_X124Y384/UBUMP0,603,297,539,218",
    })
    public void testSuperLongLinesOfStackedSlrs(String nodeName, short slowMax, short slowMin, short fastMax,
                                                short fastMin) {
        Device device = Device.getDevice("xcv80");
        Corner[] corners = {Corner.SLOW_MAX, Corner.SLOW_MIN, Corner.FAST_MAX, Corner.FAST_MIN};
        short[] expected = {slowMax, slowMin, fastMax, fastMin};
        Node sll = device.getNode(nodeName);
        Assertions.assertEquals(VersalNodeClass.SLL_DATA, VersalNodeClass.of(sll), nodeName);
        for (int i = 0; i < corners.length; i++) {
            VersalDelayEstimator estimator = new VersalDelayEstimator(device,
                    VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1, corners[i]), null);
            Assertions.assertEquals(expected[i], estimator.getDelayOf(sll), corners[i].toString());
        }
    }

    @Test
    public void testNoCidbCrossingsOfStackedSlrs() {
        Device device = Device.getDevice("xcv80");
        VersalDelayEstimator estimator = (VersalDelayEstimator) DelayEstimator.create(device, false);
        CrossingIndex crossings = estimator.getCrossings();
        int cidb = estimator.getParams().getColClassNames().indexOf("CIDB");
        Assertions.assertTrue(cidb >= 0);
        int[] counts = new int[crossings.getNumCrossingClasses()];
        int checked = 0;
        for (Tile tile : device.getAllTiles()) {
            for (int i = 0; i < tile.getWireCount(); i++) {
                if (tile.getWireIntentCode(i) != IntentCode.NODE_SLL_DATA) {
                    continue;
                }
                Node sll = Node.getNode(tile, i);
                if (sll == null || !sll.getTile().equals(tile) || sll.getWireIndex() != i) {
                    continue;
                }
                crossings.getCrossings(sll, counts);
                Assertions.assertEquals(0, counts[cidb], sll.toString());
                checked++;
            }
        }
        Assertions.assertTrue(checked > 100000, Integer.toString(checked));
    }

    /** The device-wide X and Y extent of the wires of a node, and the number of SLRs they lie in */
    private static int[] span(Node node, SlrCoordinates global) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long slrs = 0;
        for (Wire w : node.getAllWiresInNode()) {
            Tile t = w.getTile();
            minX = Math.min(minX, global.x(t));
            maxX = Math.max(maxX, global.x(t));
            minY = Math.min(minY, global.y(t));
            maxY = Math.max(maxY, global.y(t));
            slrs |= 1L << t.getSLRIndex();
        }
        return new int[]{maxX - minX, maxY - minY, Long.bitCount(slrs)};
    }
}
