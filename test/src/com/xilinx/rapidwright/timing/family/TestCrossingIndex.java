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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

public class TestCrossingIndex {

    /**
     * A fingerprint of the spans and crossings of all the nodes whose base wire is in every 50th INT tile of a
     * device.
     */
    private static String fingerprint(Device device, CrossingIndex index) {
        int[] counts = new int[index.getNumCrossingClasses()];
        long hash = 17;
        int intTiles = 0;
        for (Tile tile : device.getAllTiles()) {
            if (tile.getTileTypeEnum() != TileTypeEnum.INT || intTiles++ % 50 != 0) {
                continue;
            }
            for (int i = 0; i < tile.getWireCount(); i++) {
                Node node = Node.getNode(tile, i);
                if (node == null || node.getTile() != tile || node.getWireIndex() != i) {
                    continue;
                }
                hash = hash * 1000003L + index.getCrossings(node, counts);
                for (int c : counts) {
                    hash = hash * 31 + c;
                }
            }
        }
        return Long.toHexString(hash);
    }

    /**
     * On devices whose INT tiles have unique coordinates (single-SLR devices, and multi-SLR devices whose SLRs
     * share one coordinate system), the gaps nodes cross are found over the whole device. The fingerprints were
     * taken with the index that had no tables per SLR; they only change if the column or row classes of a family
     * change.
     */
    @ParameterizedTest
    @CsvSource({
            // Single SLR
            "xcvu3p,c9615a4ec91da431",
            "xcvc1902,a7d6730cac01b2ef",
            // Stacked SLRs
            "xcvu9p,68abbeb8a504f452",
            "xcvp1552,99734ec9b93051f7",
            "xcvp1802,1750b60bb7dbcaf0",
    })
    public void testUniqueCoordinatesUnchanged(String deviceName, String expected) {
        Device device = Device.getDevice(deviceName);
        ClassBasedDelayEstimator estimator = (ClassBasedDelayEstimator) DelayEstimator.create(device, false);
        Assertions.assertFalse(estimator.getCrossings().isSlrLocal());
        Assertions.assertEquals(expected, fingerprint(device, estimator.getCrossings()));
    }

    /** On xcvp1902, INT tile coordinates restart in each SLR (INT_S&lt;slr&gt;X&lt;x&gt;Y&lt;y&gt;) */
    @ParameterizedTest
    @CsvSource({
            "xcvp1902,true",
            "xcvu9p,false",
            "xcvp1552,false",
    })
    public void testSlrLocalCoordinates(String deviceName, boolean expected) {
        Device device = Device.getDevice(deviceName);
        ClassBasedDelayEstimator estimator = (ClassBasedDelayEstimator) DelayEstimator.create(device, false);
        Assertions.assertEquals(expected, estimator.getCrossings().isSlrLocal());
    }

    /**
     * On xcvp1902, a super long line between two SLRs is measured in device-wide coordinates: its span is the distance
     * between its ends in the device (not the difference of two SLRs' local coordinates), so that all vertical ones
     * span the same number of rows and cross gaps (the SLR boundary), and horizontal ones span about the same number
     * of columns.
     */
    @ParameterizedTest
    @CsvSource({
            "xcvp1902,SLL2",
    })
    public void testSllsBetweenSlrsMeasuredDeviceWide(String deviceName, String sllTileType) {
        Device device = Device.getDevice(deviceName);
        ClassBasedDelayEstimator estimator = (ClassBasedDelayEstimator) DelayEstimator.create(device, false);
        CrossingIndex index = estimator.getCrossings();
        Assertions.assertTrue(index.isSlrLocal());
        SlrCoordinates g = SlrCoordinates.of(device);
        TileTypeEnum type = TileTypeEnum.valueOf(sllTileType);
        int[] counts = new int[index.getNumCrossingClasses()];
        Set<Long> seen = new HashSet<>();
        Set<Integer> verticalSpans = new TreeSet<>();
        Set<Integer> horizontalSpans = new TreeSet<>();
        Map<Long, String> horizontalCounts = new HashMap<>();
        int between = 0;
        for (Tile tile : device.getAllTiles()) {
            if (tile.getTileTypeEnum() != type) {
                continue;
            }
            for (int i = 0; i < tile.getWireCount(); i++) {
                if (tile.getWireIntentCode(i) != IntentCode.NODE_SLL_DATA) {
                    continue;
                }
                Node node = Node.getNode(tile, i);
                if (node == null || !seen.add(((long) node.getTile().getUniqueAddress() << 32) | node.getWireIndex())) {
                    continue;
                }
                Wire[] wires = node.getAllWiresInNode();
                Set<Integer> slrs = new HashSet<>();
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (Wire w : wires) {
                    Tile t = w.getTile();
                    slrs.add(t.getSLRIndex());
                    minX = Math.min(minX, g.x(t));
                    maxX = Math.max(maxX, g.x(t));
                    minY = Math.min(minY, g.y(t));
                    maxY = Math.max(maxY, g.y(t));
                }
                if (slrs.size() < 2) {
                    continue;
                }
                between++;
                int span = index.getCrossings(node, counts);
                Assertions.assertEquals((maxX - minX) + (maxY - minY), span, node.toString());
                if (maxX == minX) {
                    verticalSpans.add(span);
                    int gaps = 0;
                    for (int c : counts) {
                        gaps += c;
                    }
                    Assertions.assertTrue(gaps > 0, node.toString());
                } else {
                    Assertions.assertEquals(minY, maxY, node.toString());
                    horizontalSpans.add(span);
                    // The gaps between the same two columns are the same in every row of clock regions
                    String c = Arrays.toString(counts);
                    String other = horizontalCounts.putIfAbsent(((long) minX << 32) | maxX, c);
                    Assertions.assertTrue(other == null || other.equals(c), node + ": " + c + " vs " + other);
                }
            }
        }
        Assertions.assertTrue(between > 0);
        Assertions.assertEquals(1, verticalSpans.size(), verticalSpans.toString());
        Assertions.assertFalse(horizontalSpans.isEmpty());
        Assertions.assertTrue(horizontalSpans.size() <= 4, horizontalSpans.toString());
    }
}
