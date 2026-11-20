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

package com.xilinx.rapidwright.timing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.clock.ClockData;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.clock.ClockNetwork;
import com.xilinx.rapidwright.timing.clock.ClockNodeDelayModel;
import com.xilinx.rapidwright.timing.clock.TimingConstraints;
import com.xilinx.rapidwright.timing.clock.UncertaintyModel;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.InterconnectParams;
import com.xilinx.rapidwright.timing.family.SlrCoordinates;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingData;
import com.xilinx.rapidwright.timing.family.TimingDataFile;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;
import com.xilinx.rapidwright.timing.sta.FamilyArcProvider;
import com.xilinx.rapidwright.timing.sta.StaClockAnalysis;
import com.xilinx.rapidwright.timing.sta.StaGraphBuilder;
import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusTimingFamily;
import com.xilinx.rapidwright.timing.versal.VersalTimingFamily;

/**
 * The read-only accessors, factories and copies of the timing model, used from outside the packages that declare
 * them (clock, family, sta): this test does not compile if one of them loses its public access. Their behaviour is
 * tested next to them (TestClockModel, TestClockData, TestInterconnectParams, TestTimingFamilies,
 * TestFamilyArcProvider, TestStaClockAnalysis, TestSlrCoordinates).
 */
public class TestTimingModelAccess {

    @Test
    public void testPublicAccess() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        Device device = d.getDevice();
        TimingFamily family = TimingFamilies.of(device);
        SpeedGrade grade = family.resolveSpeedGrade(PartNameTools.getPart(d.getPartName()));

        // Interconnect parameters and delay estimators from given parameters
        ClassBasedDelayEstimator own = (ClassBasedDelayEstimator) family.createDelayEstimator(device, grade,
                Corner.SLOW_MAX, true);
        InterconnectParams params = own.getParams();
        InterconnectParams copy = params.toBuilder(params.getDataVersion()).build();
        Map<SiteTypeEnum, Map<String, Float>> sinkPinDeltas = copy.getSinkPinDeltas();
        Map<SiteTypeEnum, Map<String, Float>> sourcePinDeltas = copy.getSourcePinDeltas();
        Assertions.assertEquals(params.getSinkPinDeltas(), sinkPinDeltas);
        Assertions.assertEquals(params.getSourcePinDeltas(), sourcePinDeltas);
        ClassBasedDelayEstimator fromParams = family.createDelayEstimator(device, copy, grade, true);
        Assertions.assertSame(copy, fromParams.getParams());
        Assertions.assertSame(copy, ((UltraScalePlusTimingFamily) family).createDelayEstimator(device, copy, grade,
                true).getParams());
        Device versal = Device.getDevice("xcvc1902");
        InterconnectParams versalParams = VersalTimingFamily.getInterconnectParams(
                VersalTimingFamily.getGeneration(versal), Corner.SLOW_MAX);
        Assertions.assertSame(versalParams, ((VersalTimingFamily) TimingFamilies.of(versal))
                .createDelayEstimator(versal, versalParams, null, false).getParams());

        // The arc provider with the delay estimators of all three corners
        DelayEstimator min = family.createDelayEstimator(device, grade, Corner.FAST_MIN, true);
        DelayEstimator min2 = family.createDelayEstimator(device, grade, Corner.SLOW_MIN, true);
        Assertions.assertNotNull(new FamilyArcProvider(d, fromParams, Corner.SLOW_MAX, min, Corner.FAST_MIN, min2,
                Corner.SLOW_MIN));

        // Facts of the device and of the timing graph's construction
        Assertions.assertFalse(SlrCoordinates.of(device).isSlrLocal());
        BEL ff = null;
        for (Cell c : d.getCells()) {
            if (ff == null && c.getType().equals("FDRE") && c.getBEL() != null) {
                ff = c.getBEL();
            }
        }
        Assertions.assertNotNull(ff);
        Assertions.assertTrue(StaGraphBuilder.isClockBELPin(ff.getPin("CLK")));
        Assertions.assertFalse(StaGraphBuilder.isClockBELPin(ff.getPin("D")));

        // The clock tree of a clock model and the classes of the clock node delay model
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));
        ClockData data = ClockData.get(device, null);
        ClockNodeDelayModel nodeModel = data.createNodeDelayModel();
        ClockModel model = ClockModel.build(d, cn, nodeModel);
        int numNodes = model.getNumTreeNodes();
        Assertions.assertTrue(numNodes > 1);
        Assertions.assertTrue(model.getTreeParent(numNodes - 1) < numNodes - 1);
        int pinNode = model.getClockPinNode(0);
        Assertions.assertEquals(pinNode, model.getClockPinNode(0, -1));
        Assertions.assertEquals(model.getInsertion(0, Corner.SLOW_MAX),
                pinNode < 0 ? 0f : model.getTreeInsertion(pinNode, Corner.SLOW_MAX));
        Assertions.assertTrue(model.isInterSlrCommonAtLcaStart());
        int[] launchKeys = StaClockAnalysis.getLaunchKeys(model);
        Assertions.assertTrue(launchKeys == null || launchKeys.length == model.getNumClockPins());
        Assertions.assertTrue(StaClockAnalysis.getHoldRefineSlack() > 0);
        Assertions.assertTrue(StaClockAnalysis.getHoldRefineSpread() > 0);

        // A copy of the clock data with a class term, a load pin delta and the discrete jitter changed: the delays of
        // the nodes of every clock class change at the corner of the change only, and the clock uncertainty changes
        ClockData.Overrides overrides = new ClockData.Overrides()
                .setLoadPinDelta(SiteTypeEnum.BUFGCE, "CLK_IN", Corner.SLOW_MAX, 1f);
        TimingDataFile.Section clock = TimingData.get(UltraScalePlusTimingFamily.DATA_NAME).getClockSection();
        for (Object nodeClass : (List<?>) ClockData.decode(clock.getStrings("strings"), clock.getInts("ints"))
                .get("clockClasses")) {
            overrides.setClassTerm((String) nodeClass, Corner.SLOW_MAX, ClockData.Term.BASE, 1000f);
        }
        for (String kind : new String[]{"MMCM", "PLL", "PS", "GT", "PORT", "FABRIC"}) {
            overrides.setDiscreteJitter(kind, 500f, 0f);
        }
        ClockData overridden = data.withOverrides(overrides);
        ClockNodeDelayModel overriddenNodes = overridden.createNodeDelayModel();
        float[] before = new float[Corner.values().length];
        float[] after = new float[before.length];
        int numClockNodes = 0;
        for (Net net : d.getNets()) {
            if (!net.isClockNet()) {
                continue;
            }
            for (PIP pip : net.getPIPs()) {
                Node node = pip.getEndNode();
                if (nodeModel.isDataNode(node) || !nodeModel.hasClockClass(node)) {
                    continue;
                }
                nodeModel.getDelays(node, 1, before);
                overriddenNodes.getDelays(node, 1, after);
                for (Corner c : Corner.values()) {
                    if (c == Corner.SLOW_MAX) {
                        Assertions.assertNotEquals(before[c.ordinal()], after[c.ordinal()], node.toString());
                    } else {
                        Assertions.assertEquals(before[c.ordinal()], after[c.ordinal()], node + " " + c);
                    }
                }
                numClockNodes++;
            }
        }
        Assertions.assertTrue(numClockNodes > 0);
        Assertions.assertTrue(cn.getNumClocks() > 0);
        UncertaintyModel unc = data.createUncertaintyModel(cn, TimingConstraints.fromDesign(d));
        UncertaintyModel overriddenUnc = overridden.createUncertaintyModel(cn, TimingConstraints.fromDesign(d));
        Assertions.assertTrue(overriddenUnc.getSetup(0, 0) > unc.getSetup(0, 0));
    }
}
