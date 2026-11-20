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

package com.xilinx.rapidwright.timing.ultrascaleplus;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.InterconnectParams;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * Tests the fitted UltraScale+ interconnect model.
 */
public class TestUltraScalePlusDelayEstimator {

    @Test
    public void testAllCorners() {
        for (Corner corner : Corner.values()) {
            InterconnectParams params = UltraScalePlusTimingFamily.getInterconnectParams(corner);
            Assertions.assertEquals(corner, params.getCorner());
            Assertions.assertEquals(UltraScalePlusNodeClass.values().length, params.getNumNodeClasses());
            for (UltraScalePlusNodeClass c : UltraScalePlusNodeClass.values()) {
                Assertions.assertEquals(c.name(), params.getNodeClassNames().get(c.ordinal()));
            }
            Assertions.assertTrue(params.hasPinDeltas(), corner.name());
            Assertions.assertEquals("2", params.getSpeedRef());
            // The lookahead is three times an admissible lower bound
            Assertions.assertEquals(14.5f, params.getAdmissibleLookaheadPerTileX());
            Assertions.assertEquals(6.3f, params.getAdmissibleLookaheadPerTileY());
            Assertions.assertEquals(3 * params.getAdmissibleLookaheadPerTileX(), params.getLookaheadPerTileX(), 0.01f);
            Assertions.assertEquals(3 * params.getAdmissibleLookaheadPerTileY(), params.getLookaheadPerTileY(), 0.01f);
        }
    }

    @Test
    public void testScalesByCorner() {
        // Fast corners barely depend on the speed grade
        Assertions.assertEquals(1.126f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX).getSpeedScale("-1"));
        Assertions.assertEquals(1.048f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MIN).getSpeedScale("-1"));
        Assertions.assertEquals(1.0f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.FAST_MIN).getSpeedScale("-1"));
        Assertions.assertEquals(1.0f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.FAST_MAX).getSpeedScale("1"));
        Assertions.assertEquals(1.0f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.FAST_MIN).getSpeedScale("-2"));
        Assertions.assertNull(UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX).getSpeedScale("-9"));
        Assertions.assertEquals(1.0002f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX).getDeviceScale("xcvu9p"));
        Assertions.assertEquals(1.0004f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MIN).getDeviceScale("xcvu9p"));
        Assertions.assertEquals(1f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.FAST_MIN).getDeviceScale("xcvu3p"));
    }

    @ParameterizedTest
    @CsvSource({
            // corner, node, class, delay, fanout delay of 3 branches
            "SLOW_MAX,INT_X40Y120/EE1_E_BEG0,SINGLE_E,7,7.6",
            "SLOW_MAX,INT_X40Y120/NN4_W_BEG0,VQUAD_N,24,18.6",
            "SLOW_MAX,INT_X40Y120/EE12_BEG0,HLONG_E,167,36.8",     // crosses URAM, CBRK x2 and RBRK
            "SLOW_MAX,INT_X40Y120/SS12_BEG0,VLONG_S,82,20.8",
            "SLOW_MAX,INT_X40Y120/INT_NODE_IMUX_0_INT_OUT0,INODE,25,12.4",
            "SLOW_MAX,INT_X40Y120/IMUX_E10,IMUX,7,0",
            "SLOW_MAX,INT_X40Y120/SDQNODE_E_0_FT1,SDQNODE_FT,43,14.8",
            "SLOW_MIN,INT_X40Y120/EE12_BEG0,HLONG_E,121,26.0",
            "FAST_MIN,INT_X40Y120/EE12_BEG0,HLONG_E,85,25.6",
            "FAST_MIN,INT_X40Y120/NN4_W_BEG0,VQUAD_N,15,8.8",
            "FAST_MAX,INT_X40Y120/SS12_BEG0,VLONG_S,56,19.8",
    })
    public void testFrozenNodeDelays(String corner, String nodeName, String nodeClass, int delay, float fanout) {
        Device device = Device.getDevice("xcvu3p");
        UltraScalePlusDelayEstimator estimator = new UltraScalePlusDelayEstimator(device,
                UltraScalePlusTimingFamily.getInterconnectParams(Corner.valueOf(corner)), null);
        Assertions.assertEquals(1f, estimator.getScale());
        Node node = device.getNode(nodeName);
        Assertions.assertEquals(UltraScalePlusNodeClass.valueOf(nodeClass), UltraScalePlusNodeClass.of(node));
        Assertions.assertTrue(estimator.isDelayCharged(node));
        Assertions.assertEquals(delay, estimator.getDelayOf(node));
        Assertions.assertEquals(fanout, estimator.getFanoutDelay(node, 3), 1e-4f);
        Assertions.assertEquals(0f, estimator.getFanoutDelay(node, 1));
    }

    @Test
    public void testPinDeltas() {
        InterconnectParams params = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        // Slice pins: the pin's own delta
        Assertions.assertEquals(8.0f, params.getSinkPinDelta(SiteTypeEnum.SLICEL, "A1"));
        Assertions.assertEquals(24.4f, params.getSinkPinDelta(SiteTypeEnum.SLICEM, "DX"));
        Assertions.assertEquals(-0.3f, params.getSourcePinDelta(SiteTypeEnum.SLICEL, "AQ"));
        // Other sites: the pin's delta plus that of its bus
        Assertions.assertEquals(1.9f + 12.7f, params.getSinkPinDelta(SiteTypeEnum.DSP48E2, "A12"));
        Assertions.assertEquals("A", InterconnectParams.getPinDeltaBusName(SiteTypeEnum.DSP48E2, "A12"));
        Assertions.assertEquals("A1", InterconnectParams.getPinDeltaBusName(SiteTypeEnum.SLICEL, "A1"));
        Assertions.assertEquals(0f, params.getSinkPinDelta(SiteTypeEnum.SLICEL, "NOT_A_PIN"));
        Assertions.assertEquals(0f, params.getSinkPinDelta(SiteTypeEnum.BUFGCE, "CLK_IN"));

        Design design = new Design("pins", "xcvu3p-ffvc1517-2-e");
        Device device = design.getDevice();
        SiteInst si = design.createSiteInst(device.getSite("SLICE_X40Y120"));
        SitePinInst source = new SitePinInst(true, "AQ", si);
        SitePinInst sink = new SitePinInst(false, "A1", si);
        UltraScalePlusDelayEstimator estimator = new UltraScalePlusDelayEstimator(device, params, "-2");
        SiteTypeEnum type = si.getSite().getSiteTypeEnum();
        float expected = params.getSourcePinDelta(type, "AQ") + params.getSinkPinDelta(type, "A1");
        Assertions.assertEquals((short) Math.round(expected), estimator.getPinDelay(source, sink));
        Assertions.assertEquals((short) Math.round(params.getSinkPinDelta(type, "A1")), estimator.getPinDelay(null, sink));
        Assertions.assertEquals(0, estimator.getPinDelay(null, null));
    }

    @Test
    public void testFamilyFactory() {
        Device device = Device.getDevice("xcvu3p");
        TimingFamily family = TimingFamilies.of(device);
        Assertions.assertTrue(DelayEstimator.create(device, false) instanceof UltraScalePlusDelayEstimator);
        DelayEstimator slow = family.createDelayEstimator(device, SpeedGrade.of("-1"), Corner.SLOW_MAX, false);
        Assertions.assertTrue(slow instanceof UltraScalePlusDelayEstimator);
        Assertions.assertEquals(1.126f, ((UltraScalePlusDelayEstimator) slow).getScale());
        DelayEstimator fast = family.createDelayEstimator(device, SpeedGrade.of("-1"), Corner.FAST_MIN, false);
        Assertions.assertEquals(Corner.FAST_MIN, ((UltraScalePlusDelayEstimator) fast).getParams().getCorner());
        Assertions.assertEquals(1.0f, ((UltraScalePlusDelayEstimator) fast).getScale());
    }

    @Test
    public void testLookaheadPerCorner() {
        Device device = Device.getDevice("xcvu3p");
        UltraScalePlusDelayEstimator slow = new UltraScalePlusDelayEstimator(device,
                UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX), null);
        UltraScalePlusDelayEstimator fast = new UltraScalePlusDelayEstimator(device,
                UltraScalePlusTimingFamily.getInterconnectParams(Corner.FAST_MIN), null);
        // The data's lookahead and pre-route delays are SLOW_MAX's: scaled down at FAST_MIN by the ratio of the
        // summed class base delays
        InterconnectParams ps = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        InterconnectParams pf = UltraScalePlusTimingFamily.getInterconnectParams(Corner.FAST_MIN);
        double sum = 0;
        double sumRef = 0;
        for (int i = 0; i < ps.getNumNodeClasses(); i++) {
            if (ps.getBaseDelay(i) > 0 && pf.getBaseDelay(i) > 0) {
                sum += pf.getBaseDelay(i);
                sumRef += ps.getBaseDelay(i);
            }
        }
        float k = (float) (sum / sumRef);
        Assertions.assertTrue(k > 0.3f && k < 0.9f, "corner scale " + k);
        Assertions.assertEquals(ps.getLookaheadPerTileX() * slow.getScale() / 100f, slow.getLookaheadDelayPerTileX(),
                1e-6f);
        Assertions.assertEquals(slow.getLookaheadDelayPerTileX() * k, fast.getLookaheadDelayPerTileX(), 1e-6f);
        Assertions.assertTrue(fast.getPreRouteBaseDelay() < slow.getPreRouteBaseDelay());
        // The admissible lookahead scales with the same factor
        Assertions.assertEquals(slow.getAdmissibleLookaheadDelayPerTileX() * k,
                fast.getAdmissibleLookaheadDelayPerTileX(), 1e-6f);
        Assertions.assertTrue(fast.getAdmissibleLookaheadDelayPerTileY() <= fast.getLookaheadDelayPerTileY());
    }

    /**
     * The U-turn nodes of a device, by class (see {@link UltraScalePlusDelayEstimator#isUTurnNode})
     */
    @Test
    public void testUTurnNodeCounts() {
        Device device = Device.getDevice("xcau10p");
        UltraScalePlusDelayEstimator masked = new UltraScalePlusDelayEstimator(device,
                UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX), null, false);
        Map<UltraScalePlusNodeClass, Integer> counts = new EnumMap<>(UltraScalePlusNodeClass.class);
        for (Tile t : device.getAllTiles()) {
            if (t.getTileTypeEnum() != TileTypeEnum.INT) {
                continue;
            }
            for (int w = 0; w < t.getWireCount(); w++) {
                Node n = Node.getNode(t, w);
                if (n == null || !n.getTile().equals(t) || n.getWireIndex() != w) {
                    continue;
                }
                boolean uTurn = masked.isUTurnNode(n);
                Assertions.assertEquals(uTurn, masked.getDelayOf(n) == UltraScalePlusDelayEstimator.UTURN_MASK_DELAY,
                        n.toString());
                if (uTurn) {
                    counts.merge(UltraScalePlusNodeClass.of(n), 1, Integer::sum);
                }
            }
        }
        Assertions.assertEquals("{SINGLE_E=1440, SINGLE_W=1476, SINGLE_N=576, SINGLE_S=576, DOUBLE_E=2880, "
                + "DOUBLE_W=2880, DOUBLE_N=1152, DOUBLE_S=1152, HQUAD_E=5760, HQUAD_W=5760, VQUAD_N=2304, "
                + "VQUAD_S=2304, HLONG_E=8640, HLONG_W=8640, VLONG_N=3456, VLONG_S=3456}", counts.toString());
    }

    @Test
    public void testUTurnNodesMasked() {
        Device device = Device.getDevice("xcvu3p");
        UltraScalePlusDelayEstimator masked = new UltraScalePlusDelayEstimator(device,
                UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX), null, false);
        UltraScalePlusDelayEstimator allowed = new UltraScalePlusDelayEstimator(device,
                UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX), null, true);
        Assertions.assertTrue(masked.masksUTurnNodes());
        Assertions.assertFalse(allowed.masksUTurnNodes());
        // Long wires leaving the device's left and right edges turn back: masked
        int numMasked = 0;
        int numOther = 0;
        for (Tile t : new Tile[]{device.getTile("INT_X0Y60"), device.getTile("INT_X1Y60")}) {
            for (int w = 0; w < t.getWireCount(); w++) {
                Node n = Node.getNode(t, w);
                if (n == null || !n.getTile().equals(t)) continue;
                short m = masked.getDelayOf(n);
                if (m == UltraScalePlusDelayEstimator.UTURN_MASK_DELAY) {
                    numMasked++;
                    Assertions.assertTrue(allowed.getDelayOf(n) < 10000, n.toString());
                } else {
                    numOther++;
                    Assertions.assertEquals(allowed.getDelayOf(n), m, n.toString());
                }
            }
        }
        Assertions.assertTrue(numMasked > 0, "no U-turn node");
        Assertions.assertTrue(numOther > 0);
        // The family honours the flag
        Assertions.assertTrue(DelayEstimator.create(device, false).masksUTurnNodes());
        Assertions.assertFalse(DelayEstimator.create(device, true).masksUTurnNodes());
    }
}
