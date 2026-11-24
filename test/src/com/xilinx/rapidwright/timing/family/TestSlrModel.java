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

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

public class TestSlrModel {

    /** The rows of the super long lines of a series, as RWRoute's routing graph has them */
    private static int sllRows(Device device) {
        return device.getSeries() == com.xilinx.rapidwright.device.Series.Versal ? 75 : 60;
    }

    /**
     * Checks the cheapest SLL of a device against the crossing delays of its SLL nodes that join two SLRs in one tile,
     * and that it is cheaper than what the per-row lookahead charges for the rows an SLL spans.
     */
    private static float checkModel(Device device, DelayEstimator estimator, int numSlrs) {
        SlrModel m = TimingFamilies.of(device).getSlrModel(device, estimator);
        Assertions.assertEquals(numSlrs, m.getNumSlrs());
        Assertions.assertTrue(m.getNumSllNodes() > 1000, m.toString());
        int checked = 0;
        for (Tile tile : device.getAllTiles()) {
            for (int w = 0; w < tile.getWireCount(); w++) {
                IntentCode ic = tile.getWireIntentCode(w);
                if (ic != IntentCode.NODE_LAGUNA_DATA && ic != IntentCode.NODE_SLL_DATA) continue;
                Node sll = Node.getNode(tile, w);
                Assertions.assertTrue(SlrModel.isSll(sll));
                if (!SlrModel.joinsSlrs(sll)) continue;
                float d = SlrModel.getCrossingDelay(estimator, sll);
                if (Float.isNaN(d)) continue;
                Assertions.assertTrue(d >= m.getSllDelay(), sll + " " + d + " < " + m);
                checked++;
            }
            if (checked > 0) break;
        }
        Assertions.assertTrue(checked > 0);
        return m.getSllDelay();
    }

    @Test
    public void testUltraScalePlus() {
        Device device = Device.getDevice("xcvu9p");
        TimingFamily family = TimingFamilies.of(device);
        DelayEstimator slowMax = family.createDelayEstimator(device, SpeedGrade.of("2"), Corner.SLOW_MAX, false);
        float sll = checkModel(device, slowMax, 3);
        // The UBUMP class delay (about 750 ps at -2), well below the 60 rows of the per-row lookahead
        Assertions.assertTrue(sll > 700 && sll < 800, Float.toString(sll));
        Assertions.assertTrue(sll < sllRows(device) * slowMax.getLookaheadDelayPerTileY() * 100);
        DelayEstimator fastMin = family.createDelayEstimator(device, SpeedGrade.of("2"), Corner.FAST_MIN, false);
        float fast = checkModel(device, fastMin, 3);
        Assertions.assertTrue(fast > 0 && fast < sll, Float.toString(fast));
    }

    @Test
    public void testSingleSlr() {
        Device single = Device.getDevice("xcvu3p");
        SlrModel m = TimingFamilies.of(single).getSlrModel(single, DelayEstimator.create(single, false));
        Assertions.assertEquals(1, m.getNumSlrs());
        Assertions.assertEquals(0f, m.getSllDelay());
        Assertions.assertEquals(0, m.getNumSllNodes());
    }

    @Test
    public void testVersal() {
        Device device = Device.getDevice("xcv80");
        DelayEstimator slowMax = TimingFamilies.of(device).createDelayEstimator(device, SpeedGrade.of("2MHP"),
                Corner.SLOW_MAX, false);
        float sll = checkModel(device, slowMax, 3);
        // The cheapest SLL between two SLRs (about 590 ps on xcv80): the SLL_DATA nodes that start and end in one
        // SLR (about 270 ps) do not count
        Assertions.assertTrue(sll > 550 && sll < 650, Float.toString(sll));
        Assertions.assertTrue(sll < sllRows(device) * slowMax.getLookaheadDelayPerTileY() * 100);
    }

    /** Only SLLs that join two SLRs count: on xcv80, most SLL_DATA nodes start and end in one SLR */
    @Test
    public void testOnlySllsBetweenSlrs() {
        Device device = Device.getDevice("xcv80");
        DelayEstimator slowMax = TimingFamilies.of(device).createDelayEstimator(device, SpeedGrade.of("2MHP"),
                Corner.SLOW_MAX, false);
        SlrModel m = SlrModel.create(device, slowMax);
        int joining = 0;
        int within = 0;
        float leastWithin = Float.MAX_VALUE;
        Set<Node> seen = new HashSet<>();
        for (Tile tile : device.getAllTiles()) {
            for (int w = 0; w < tile.getWireCount(); w++) {
                if (tile.getWireIntentCode(w) != IntentCode.NODE_SLL_DATA) continue;
                Node sll = Node.getNode(tile, w);
                if (sll == null || !seen.add(sll)) continue;
                float d = SlrModel.getCrossingDelay(slowMax, sll);
                if (Float.isNaN(d)) continue;
                if (SlrModel.joinsSlrs(sll)) {
                    joining++;
                    Assertions.assertTrue(d >= m.getSllDelay(), sll + " " + d + " < " + m);
                } else {
                    within++;
                    leastWithin = Math.min(leastWithin, d);
                }
            }
            if (joining > 100 && within > 100) break;
        }
        Assertions.assertTrue(joining > 100 && within > 100, joining + " " + within);
        // An SLL within one SLR is cheaper than any crossing
        Assertions.assertTrue(leastWithin < m.getSllDelay(), leastWithin + " vs " + m);
    }
}
