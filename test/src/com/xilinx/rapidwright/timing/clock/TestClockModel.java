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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.TimingFamilies;

public class TestClockModel {

    /** Delays per corner: SLOW_MAX, SLOW_MIN, FAST_MIN, FAST_MAX */
    private static float[] d(float smax, float smin, float fmin, float fmax) {
        return new float[]{smax, smin, fmin, fmax};
    }

    @Test
    public void testTreeAndCrpr() {
        ClockTree t = new ClockTree();
        int root = t.addNode(ClockTree.NO_PARENT, d(100, 90, 50, 55));
        int a = t.addNode(root, d(200, 180, 100, 110));
        int b = t.addNode(root, d(300, 270, 150, 165));
        int a1 = t.addNode(a, d(10, 9, 5, 6));
        int a2 = t.addNode(a, d(20, 18, 10, 12));
        int other = t.addNode(ClockTree.NO_PARENT, null);
        t.finish();
        Assertions.assertEquals(310f, t.getInsertion(a1, Corner.SLOW_MAX));
        Assertions.assertEquals(288f, t.getInsertion(a2, Corner.SLOW_MIN));
        Assertions.assertEquals(177f, t.getInsertion(a2, Corner.FAST_MAX));
        Assertions.assertEquals(a, t.getLca(a1, a2));
        Assertions.assertEquals(root, t.getLca(a1, b));
        Assertions.assertEquals(a1, t.getLca(a1, a1));
        Assertions.assertEquals(ClockTree.NO_PARENT, t.getLca(a1, other));
        Assertions.assertEquals(2, t.getDepth(a2));
        // Setup credit: SLOW_MAX - SLOW_MIN at the LCA; hold: FAST_MAX - FAST_MIN
        Assertions.assertEquals(300f - 270f, t.getSetupCredit(a1, a2));
        Assertions.assertEquals(165f - 150f, t.getHoldCredit(a1, a2));
        Assertions.assertEquals(10f, t.getSetupCredit(a1, b));
        Assertions.assertEquals(0f, t.getSetupCredit(a1, other));
        // Same leaf: the whole insertion is common
        Assertions.assertEquals(310f - 279f, t.getSetupCredit(a1, a1));

        ClockModel m = ClockModel.of(t, new int[]{a1, a2, -1});
        Assertions.assertEquals(t.getInsertion(a2, Corner.FAST_MIN), m.getInsertion(1, Corner.FAST_MIN));
        Assertions.assertEquals(0f, m.getInsertion(2, Corner.SLOW_MAX));
        Assertions.assertEquals(30f, m.getSetupCrpr(0, 1));
        Assertions.assertEquals(0f, m.getSetupCrpr(0, 2));
        Assertions.assertEquals(15f, m.getHoldCrpr(1, 0));
    }

    @Test
    public void testInterSlrCompensation() {
        // root -> spine -> {a (SLR 0) -> a1, b (SLR 1) -> b1}; a1 and a2 in SLR 0, b1 in SLR 1
        ClockTree t = new ClockTree();
        int root = t.addNode(ClockTree.NO_PARENT, d(100, 90, 50, 55));
        int spine = t.addNode(root, d(200, 180, 100, 110));
        int a = t.addNode(spine, d(300, 270, 150, 165));
        int a1 = t.addNode(a, d(10, 9, 5, 6));
        int a2 = t.addNode(a, d(20, 18, 10, 12));
        int b = t.addNode(spine, d(400, 360, 200, 220));
        int b1 = t.addNode(b, d(30, 27, 15, 18));
        ClockModel m = ClockModel.of(t, new int[]{a1, a2, b1, -1}, new int[]{0, 0, 1, -1});
        Assertions.assertEquals(1, m.getSlr(2));
        // Same SLR, unknown SLR or no prorate: none
        Assertions.assertEquals(0f, m.getInterSlrCompensation(0, 1, Corner.SLOW_MIN, 0.15f));
        Assertions.assertEquals(0f, m.getInterSlrCompensation(0, 3, Corner.SLOW_MIN, 0.15f));
        Assertions.assertEquals(0f, m.getInterSlrCompensation(0, 2, Corner.SLOW_MIN, 0f));
        // (DCD - CCD)*PF: DCD = b1's insertion, CCD = insertion at the LCA (spine)
        float dcd = 90 + 180 + 360 + 27;
        Assertions.assertEquals((dcd - 270) * 0.15f, m.getInterSlrCompensation(0, 2, Corner.SLOW_MIN, 0.15f), 1e-3);
        float dcdFast = 55 + 110 + 165 + 6;
        Assertions.assertEquals((dcdFast - 165) * 0.1f, m.getInterSlrCompensation(2, 0, Corner.FAST_MAX, 0.1f), 1e-3);
        // CCD at the start of the LCA, i.e. at its parent (root)
        m.setInterSlrCommonAtLcaStart(true);
        Assertions.assertEquals((dcd - 90) * 0.15f, m.getInterSlrCompensation(0, 2, Corner.SLOW_MIN, 0.15f), 1e-3);
        Assertions.assertEquals((dcdFast - 55) * 0.1f, m.getInterSlrCompensation(2, 0, Corner.FAST_MAX, 0.1f), 1e-3);
        // An LCA at the root: CCD 0 at its start
        ClockTree t2 = new ClockTree();
        int r2 = t2.addNode(ClockTree.NO_PARENT, d(100, 90, 50, 55));
        int x = t2.addNode(r2, d(10, 9, 5, 6));
        int y = t2.addNode(r2, d(20, 18, 10, 12));
        ClockModel m2 = ClockModel.of(t2, new int[]{x, y}, new int[]{0, 1});
        Assertions.assertEquals(18 * 0.5f, m2.getInterSlrCompensation(0, 1, Corner.SLOW_MIN, 0.5f), 1e-3);
        m2.setInterSlrCommonAtLcaStart(true);
        Assertions.assertEquals((90 + 18) * 0.5f, m2.getInterSlrCompensation(0, 1, Corner.SLOW_MIN, 0.5f), 1e-3);
        // The data's setting: UltraScale+ at the start of the LCA, Versal at its end
        Assertions.assertTrue(ClockData.get("ultrascaleplus", null).getParams().isInterSlrCommonAtLcaStart());
        Assertions.assertFalse(ClockData.get("versal_gen1", null).getParams().isInterSlrCommonAtLcaStart());
    }

    /**
     * The CRPR credit at a routing node where two clock paths branch, credited at its start
     * ({@code ClockTree.setCreditAtStart}): that of its parent; at the routing node of a site pin both clock pins are
     * on: the whole node. UltraScale+ clock data credit the routing nodes of the horizontal distribution and the local
     * clock nodes at their start, Versal clock data every node at its end.
     */
    @Test
    public void testCrprAtStartOfBranchingRoutingNode() {
        ClockTree t = new ClockTree();
        int root = t.addNode(ClockTree.NO_PARENT, d(100, 80, 40, 50));
        int branch = t.addNode(root, d(30, 20, 10, 14));
        int pinA = t.addNode(branch, d(8, 6, 3, 4));
        int pinB = t.addNode(branch, d(9, 7, 3, 4));
        int a1 = t.addNode(pinA, d(5, 4, 2, 2));
        int a2 = t.addNode(pinA, d(6, 5, 3, 2));
        int b1 = t.addNode(pinB, d(5, 4, 2, 2));
        for (int n : new int[]{branch, pinA, pinB}) {
            t.setCreditAtStart(n);
        }
        // Branching at the routing node: the credit of its parent (setup 100 - 80, hold 50 - 40)
        Assertions.assertEquals(root, t.getCreditNode(a1, b1));
        Assertions.assertEquals(20f, t.getSetupCredit(a1, b1), 1e-3);
        Assertions.assertEquals(10f, t.getHoldCredit(a1, b1), 1e-3);
        // Both pins below the same site pin node: the whole node (setup 138 - 106, hold 68 - 53)
        Assertions.assertEquals(pinA, t.getCreditNode(a1, a2));
        Assertions.assertEquals(32f, t.getSetupCredit(a1, a2), 1e-3);
        Assertions.assertEquals(15f, t.getHoldCredit(a1, a2), 1e-3);
        Assertions.assertEquals(a1, t.getCreditNode(a1, a1));
        // One pin on the site pin node, the other below a routing node it drives: the paths branch at the
        // node, so the credit of its parent (setup 130 - 100, hold 64 - 50)
        int c1 = t.addNode(t.addNode(pinA, d(4, 3, 1, 2)), d(5, 4, 2, 2));
        Assertions.assertEquals(branch, t.getCreditNode(a1, c1));
        Assertions.assertEquals(branch, t.getCreditNode(c1, a1));
        Assertions.assertEquals(30f, t.getSetupCredit(a1, c1), 1e-3);
        Assertions.assertEquals(14f, t.getHoldCredit(c1, a1), 1e-3);
        // Unmarked nodes are credited whole
        ClockTree u = new ClockTree();
        int ur = u.addNode(ClockTree.NO_PARENT, d(100, 80, 40, 50));
        int ub = u.addNode(ur, d(30, 20, 10, 14));
        int ux = u.addNode(u.addNode(ub, null), null);
        int uy = u.addNode(u.addNode(ub, null), null);
        Assertions.assertEquals(ub, u.getCreditNode(ux, uy));
        Assertions.assertEquals(30f, u.getSetupCredit(ux, uy), 1e-3);
        Assertions.assertEquals(new HashSet<>(Arrays.asList("CLK_HDISTR", "CLK_LOCAL")),
                ClockData.get("ultrascaleplus", null).getParams().getCrprBranchStartClasses());
        Assertions.assertTrue(ClockData.get("versal_gen1", null).getParams().getCrprBranchStartClasses().isEmpty());
    }

    /**
     * The CRPR credits of flip-flop pairs of a routed UltraScale+ design (picoblaze_partial) are within 7 ps of
     * Vivado 2026.1's clock pessimism removal (report_timing, hold at the fast and slow corners): where the clock
     * paths branch on the horizontal distribution (crediting the whole branching node gave 11-17 ps more), and on
     * the vertical distribution (credited whole).
     */
    @Test
    public void testCrprOfRoutedDesignAgainstVivado() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));
        ClockData data = ClockData.get(d.getDevice(), TimingFamilies.of(d.getDevice())
                .resolveSpeedGrade(PartNameTools.getPart(d.getPartName())));
        ClockModel m = ClockModel.build(d, cn, data.createNodeDelayModel());
        // launch, capture, Vivado's pessimism removal at the fast and slow corners (ps)
        Object[][] pairs = {
            {"picoblaze_5_41/output_port_y_reg[2]/C", "picoblaze_5_42/in_port_reg[2]/C", 80f, 123f},
            {"picoblaze_2_41/output_port_y_reg[2]/C", "picoblaze_2_42/in_port_reg[2]/C", 93f, 147f},
            {"picoblaze_10_35/output_port_y_reg[2]/C", "picoblaze_10_36/in_port_reg[2]/C", 47f, 70f},
        };
        for (Object[] p : pairs) {
            int launch = cn.getClockPinIndex((String) p[0]);
            int capture = cn.getClockPinIndex((String) p[1]);
            Assertions.assertTrue(m.isRouted(launch) && m.isRouted(capture), (String) p[0]);
            Assertions.assertEquals((float) p[2], m.getHoldCrpr(launch, capture), 7f, (String) p[0]);
            Assertions.assertEquals((float) p[3], m.getSetupCrpr(launch, capture), 7f, (String) p[0]);
        }
    }

    /**
     * The clock pins of an out-of-context design's clock port, which drives them without a clock buffer and whose
     * routing does not reach them (picoblaze_ooc_X10Y235_2022_1), get their leaf delay: that of the leaf
     * configuration of their placed site type, site pin and cell pin, e.g. 28 ps (SLOW_MAX) on a SLICEL flip-flop
     * and 76 ps on a block RAM.
     */
    @Test
    public void testLeafDelaysOfUnbufferedClockPort() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));
        ClockData data = ClockData.get(d.getDevice(), TimingFamilies.of(d.getDevice())
                .resolveSpeedGrade(PartNameTools.getPart(d.getPartName())));
        ClockModel m = ClockModel.build(d, cn, data.createNodeDelayModel());
        ClockDelayParams p = data.getParams();
        int numPins = 0;
        float maxFlipFlop = 0;
        float minBlockRam = Float.MAX_VALUE;
        for (int i = 0; i < cn.getNumClockPins(); i++) {
            Assertions.assertFalse(m.isRouted(i));
            EDIFHierPortInst pin = cn.getClockPin(i);
            Cell c = d.getCell(pin.getFullHierarchicalInstName());
            String port = pin.getPortInst().getName();
            String sitePin = DesignTools.getRoutedSitePin(c, d.getNet("clk"), port);
            Assertions.assertNotNull(sitePin, pin.toString());
            String key = c.getSiteInst().getSiteTypeEnum() + "." + sitePin + "|" + port;
            Assertions.assertTrue(p.hasLeafConfig(key), key);
            for (Corner corner : Corner.values()) {
                Assertions.assertEquals(p.getLeafDelay(key, corner.ordinal()), m.getInsertion(i, corner), 1e-3f,
                        pin + " " + corner);
            }
            float ins = m.getInsertion(i, Corner.SLOW_MAX);
            if (c.getType().equals("FDRE")) {
                maxFlipFlop = Math.max(maxFlipFlop, ins);
            } else if (c.getType().equals("RAMB36E2")) {
                minBlockRam = Math.min(minBlockRam, ins);
            }
            numPins++;
        }
        Assertions.assertTrue(numPins > 100);
        Assertions.assertTrue(maxFlipFlop >= 28f && maxFlipFlop <= 30f, "flip-flop " + maxFlipFlop);
        Assertions.assertTrue(minBlockRam >= 70f && minBlockRam <= 80f, "block RAM " + minBlockRam);
    }

    /**
     * The programmable delay taps (BUFCE_ROW_FSR) on the clock routes of a routed UltraScale+ design
     * (picoblaze_partial, whose routed clock takes such taps): the insertion delay of each routed clock pin is
     * that of the taps of its row buffer.
     */
    @Test
    public void testDelayTapsOnRoutedDesign() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));
        // Only the BUFCE_ROW_FSR tap table: insertion = the taps of the routes' row buffers
        ClockDelayParams p = new ClockDelayParams();
        float[] taps = {0, 179, 358, 538};
        p.addDelayTaps("^CLK_BUFCE_ROW_FSR_\\d+_CLK_IN$", new float[][]{taps, taps, taps, taps});
        ClockModel m = ClockModel.build(d, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(d.getDevice().getSeries()), p), (ci, pc, in, out) -> null);
        int tapped = 0;
        for (int i = 0; i < cn.getNumClockPins(); i++) {
            if (!m.isRouted(i)) continue;
            float ins = m.getInsertion(i, Corner.SLOW_MAX);
            Assertions.assertTrue(ins == 0f || ins == 179f || ins == 358f || ins == 538f, "insertion " + ins);
            if (ins > 0) tapped++;
        }
        Assertions.assertTrue(tapped > 0);
    }

    @Test
    public void testClassifier() {
        ClockNodeClassifier us = ClockNodeClassifier.forSeries(Series.UltraScalePlus);
        Assertions.assertEquals("CLK_LEAF_IN", us.classify(IntentCode.INTENT_DEFAULT, "CLK_LEAF_SITES_15_CLK_IN"));
        Assertions.assertEquals("CLK_DEFAULT", us.classify(IntentCode.INTENT_DEFAULT, "CLK_BUFGCE_79_CLK_IN"));
        Assertions.assertEquals("CLK_BUFG", us.classify(IntentCode.NODE_GLOBAL_BUFG, "CLK_BUFGCE_6_CLK_OUT"));
        Assertions.assertEquals("CLK_BUFG_MUX", us.classify(IntentCode.NODE_GLOBAL_BUFG, "CLK_CMT_MUX_16_ENC_1_CLK_OUT"));
        Assertions.assertEquals("CLK_HDISTR_ROWBUF", us.classify(IntentCode.NODE_GLOBAL_HDISTR, "CLK_BUFCE_ROW_FSR_0_CLK_OUT"));
        Assertions.assertEquals("CLK_LEAF", us.classify(IntentCode.NODE_GLOBAL_LEAF, "CLK_LEAF_SITES_15_CLK_LEAF"));
        Assertions.assertEquals("CLK_LOCAL", us.classify(IntentCode.NODE_LOCAL, "INT_NODE_GLOBAL_6_INT_OUT"));
        Assertions.assertEquals(ClockNodeClassifier.DATA, us.classify(IntentCode.NODE_SINGLE, "EE1_E_BEG0"));
        Assertions.assertEquals("CLK_DEFAULT_PIN", us.classify(IntentCode.INTENT_DEFAULT, "CLK_IN_PIN"));
        ClockNodeClassifier v = ClockNodeClassifier.forSeries(Series.Versal);
        Assertions.assertEquals("CLK_HDISTR_LOCAL_OPTDLY",
                v.classify(IntentCode.NODE_GLOBAL_HDISTR_LOCAL, "CLKE_PD_OPT_DELAY_SSIT_O"));
        Assertions.assertEquals(ClockNodeClassifier.DATA, v.classify(IntentCode.NODE_IMUX, "IMUX_B_0"));
        Assertions.assertEquals("CLK_CLE_CNODE", v.classify(IntentCode.NODE_CLE_CNODE, "CNODE_W_0"));
        Assertions.assertEquals("CLK_LEAF_PIN", v.classify(IntentCode.NODE_GLOBAL_LEAF, "BUFDIV_LEAF_O_PIN"));
    }

    @Test
    public void testParams() {
        ClockDelayParams p = new ClockDelayParams();
        p.setClassDelay("CLK_HROUTE", Corner.SLOW_MAX, 100, 0.5f, -10, 2, 20, 50);
        int c = p.getClassIndex("CLK_HROUTE");
        Assertions.assertEquals(0, c);
        Assertions.assertEquals(-1, p.getClassIndex("CLK_LEAF"));
        Assertions.assertTrue(p.dependsOnSpan(c));
        // base + length*10 + crCol*2 + crRow*1 + slr*1 + fanout*(3-1)
        Assertions.assertEquals(100 + 5 - 20 + 2 + 20 + 100, p.getClassDelay(c, 0, 10, 2, 1, 1, 3), 1e-4);
        Assertions.assertEquals(0f, p.getClassDelay(c, Corner.FAST_MIN.ordinal(), 10, 2, 1, 1, 3));
        p.setGeneratorDelay("MMCME4_ADV:ZHOLD", d(-500, -480, -300, -320));
        p.setGeneratorDelay("DEFAULT", d(1, 2, 3, 4));
        Assertions.assertEquals(-480f, p.getGeneratorDelay("MMCME4_ADV", "ZHOLD")[1]);
        Assertions.assertEquals(4f, p.getGeneratorDelay("PLLE4_ADV", "INTERNAL")[3]);
        p.setUnrouted(Corner.SLOW_MAX, 300, 40, 30, 999, 500);
        Assertions.assertEquals(300 + 80 + 30 + 500, p.getEstimateRegionDelay(0, -1, 2, 1), 1e-4);
        // Tap and delay arc terms of the PIP leaving a node, scaled per corner
        p.setClassTerm("CLK_HROUTE", Corner.SLOW_MAX, ClockData.Term.TAP_X, 3);
        p.setClassTerm("CLK_HROUTE", Corner.SLOW_MAX, ClockData.Term.TAP_Y, 1);
        Assertions.assertTrue(p.dependsOnLeave(c));
        Assertions.assertEquals(3 * 4 + 2, p.getLeaveDelay(c, 0, 4, 2), 1e-4);
        // Delay tap tables by wire name (US+ BUFCE_ROW_FSR and BUFCE_LEAF inputs)
        p.addDelayTaps("^CLK_BUFCE_ROW_FSR_\\d+_CLK_IN$", new float[][]{{0, 179, 358, 538}, {0, 160, 320, 480},
            {0, 100, 200, 300}, {0, 110, 220, 330}});
        p.addDelayTaps("^CLK_LEAF_SITES_\\d+_CLK_IN$", new float[][]{{0, 56, 131}, {0, 50, 120}, {0, 30, 70},
            {0, 33, 77}});
        int row = p.getDelayTapTable("CLK_BUFCE_ROW_FSR_12_CLK_IN");
        int leafIn = p.getDelayTapTable("CLK_LEAF_SITES_7_CLK_IN");
        Assertions.assertEquals(0, row);
        Assertions.assertEquals(1, leafIn);
        Assertions.assertEquals(-1, p.getDelayTapTable("CLK_BUFCE_ROW_FSR_12_CLK_OUT"));
        Assertions.assertEquals(358f, p.getDelayTapDelay(row, 0, 2));
        Assertions.assertEquals(0f, p.getDelayTapDelay(row, 0, 0));
        Assertions.assertEquals(0f, p.getDelayTapDelay(leafIn, 0, 9));
        Assertions.assertEquals(70f, p.getDelayTapDelay(leafIn, Corner.FAST_MIN.ordinal(), 2));
        p.setRouteScale(Corner.SLOW_MAX, 2f);
        Assertions.assertEquals(2 * (3 * 4 + 2), p.getLeaveDelay(c, 0, 4, 2), 1e-4);
        Assertions.assertEquals(2 * 179f, p.getDelayTapDelay(row, 0, 1));
        Assertions.assertEquals(2 * 100f, p.getClassDelay(c, 0, 0, 0, 0, 0, 1), 1e-4);
        // Leaf configuration and clock modifier
        p.setLeafConfig("SLICEL.CLK1|C", d(28, 25, 15, 17));
        Assertions.assertEquals(25f, p.getLeafDelay("SLICEL.CLK1|C", 1));
        Assertions.assertEquals(0f, p.getLeafDelay("SLICEL.CLK2|C", 1));
        Assertions.assertFalse(p.hasClockModifier());
        p.setClockModifierDelays(new float[][]{{58, 134}, {50, 120}, {30, 70}, {33, 77}});
        Assertions.assertEquals(134f, p.getClockModifierDelay(0, 1));
        Assertions.assertEquals(0f, p.getClockModifierDelay(0, 7));
        // Compensation keys: <kind>:<COMPENSATION>, then <kind>:INTERNAL
        p.setGeneratorDelay("MMCM:INTERNAL", d(-127, -120, -80, -85));
        Assertions.assertEquals(-127f, p.getGeneratorDelay("MMCME4_ADV", "BUF_IN")[0]);
        Assertions.assertEquals("X5PLL", ClockDelayParams.getGeneratorKind("X5PLL"));
        Assertions.assertEquals("XPLL", ClockDelayParams.getGeneratorKind("XPLL"));
        // Per-output keys, then <kind>:<COMP>; an X5PLL without data takes the XPLL's
        p.setGeneratorDelay("XPLL:NONE", d(413, 406, 344, 357));
        p.setGeneratorDelay("XPLL:NONE:CLKOUTPHY:DIV1", d(-46, -131, -145, -60));
        Assertions.assertEquals(-46f, p.getGeneratorDelay("XPLL", null, "CLKOUTPHY", "DIV1")[0]);
        Assertions.assertTrue(p.hasGeneratorOutputDelay("XPLL", null, "CLKOUTPHY", "DIV1"));
        Assertions.assertEquals(413f, p.getGeneratorDelay("XPLL", null, "CLKOUTPHY", "DIV2")[0]);
        Assertions.assertFalse(p.hasGeneratorOutputDelay("XPLL", null, "CLKOUTPHY", "DIV2"));
        Assertions.assertEquals(413f, p.getGeneratorDelay("X5PLL", null, "CLKOUT1", null)[0]);
        // Dedicated routes scale with the route; zero-delay connections; site taps; load pin deltas
        p.setDedicatedRoute("HPIOB_M.I->BUFGCE.CLK_IN", d(280, 250, 150, 170));
        Assertions.assertEquals(2 * 280f, p.getDedicatedRoute("HPIOB_M.I->BUFGCE.CLK_IN")[0]);
        Assertions.assertNull(p.getDedicatedRoute("HPIOB_S.I->BUFGCE.CLK_IN"));
        p.addZeroDelayConnection("^PS9$", "^PMCRCLKCLK", "^BUFG_PS$");
        Assertions.assertTrue(p.isZeroDelayConnection("PS9", "PMCRCLKCLK_0_", "BUFG_PS"));
        Assertions.assertFalse(p.isZeroDelayConnection("PS9", "PMCRCLKCLK_0_", "BUFGCE"));
        p.setSiteTaps("IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE", new float[][]{{0, 46}, {0, 38}, {0, 26}, {0, 36}});
        Assertions.assertEquals(46f, p.getSiteTapDelay("IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE", 0, 1));
        Assertions.assertEquals(0f, p.getSiteTapDelay("IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE", 0, 9));
        p.setLoadPinDelta("RAMB36.CLKARDCLKL", d(-35, -30, -20, -25));
        Assertions.assertEquals(-30f, p.getLoadPinDelta("RAMB36.CLKARDCLKL")[1]);
        Assertions.assertEquals("PLL", ClockDelayParams.getGeneratorKind("PLLE4_ADV"));
        Assertions.assertEquals(Corner.SLOW_MIN.ordinal(), ClockModel.oppositeCorner(Corner.SLOW_MAX.ordinal()));
        Assertions.assertEquals(Corner.FAST_MAX.ordinal(), ClockModel.oppositeCorner(Corner.FAST_MIN.ordinal()));
    }

    /**
     * Versal XPLL digital deskew, as Vivado 2026.1 times it: an output under a phase detector (CLKOUT1_PHASE_CTRL
     * 2'b01) whose CLKIN1_DESKEW is driven by another output (CLKOUT2, 106.875 degrees) and whose CLKFB1_DESKEW by its
     * own clock buffer's output gets the delay of CLKOUT2 (with its phase shift) less the feedback loop's insertion at
     * the opposite min/max corner: here, without placement, the BUFGCE alone. The other outputs, and the controlled
     * outputs of XPLLs without that arrangement (no CLKIN1_DESKEW loop; a CLKIN1_DESKEW loop through another XPLL;
     * programmable deskew delays, reported once per XPLL), keep their delay.
     */
    @Test
    public void testXpllDigitalDeskew() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        String[] xpllProps = {"CLKIN_PERIOD", "5.0", "CLKFBOUT_MULT", "16", "DIVCLK_DIVIDE", "1", "CLKOUT0_DIVIDE", "16",
            "CLKOUT1_DIVIDE", "16", "CLKOUT2_DIVIDE", "16", "CLKOUT0_PHASE", "-53.4375", "CLKOUT2_PHASE", "106.875",
            "CLKOUT1_PHASE_CTRL", "2'b01"};
        for (String x : new String[]{"xp", "xn", "xd", "xc"}) {
            nb.inst(x, Unisim.XPLL, xpllProps);
            nb.inst(x + "_b1", Unisim.BUFGCE);
            nb.connect("clk", x + "/CLKIN");
            nb.connect(x + "_o1", x + "/CLKOUT1", x + "_b1/I");
            nb.connect(x + "_ck1", x + "_b1/O", x + "/CLKFB1_DESKEW");
            nb.flop(x + "_r1", x + "_ck1");
            nb.connect(x + "_o2", x + "/CLKOUT2");
            if (x.equals("xp") || x.equals("xd")) {
                // xn: no CLKIN1_DESKEW loop, no deskew; xc: below
                nb.connect(x + "_o2", x + "/CLKIN1_DESKEW");
            }
        }
        // xd: programmable deskew delays, with two outputs under the phase detector (one warning)
        nb.insts.get("xd").addProperty("DESKEW_DELAY_EN1", "TRUE");
        nb.insts.get("xd").addProperty("CLKOUT0_PHASE_CTRL", "2'b01");
        // xc: the CLKIN1_DESKEW loop from CLKOUT2 passes through another XPLL (not deskewed)
        nb.inst("xg", Unisim.XPLL, "CLKIN_PERIOD", "5.0", "CLKFBOUT_MULT", "16", "DIVCLK_DIVIDE", "1",
                "CLKOUT0_DIVIDE", "16");
        nb.connect("xc_o2", "xg/CLKIN");
        nb.connect("xg_o0", "xg/CLKOUT0", "xc/CLKIN1_DESKEW");
        for (String x : new String[]{"xp", "xd"}) {
            nb.inst(x + "_b0", Unisim.BUFGCE);
            nb.connect(x + "_o0", x + "/CLKOUT0", x + "_b0/I");
            nb.connect(x + "_ck0", x + "_b0/O");
            nb.flop(x + "_r0", x + "_ck0");
        }
        nb.connect("clk", "clk");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 5 -name clk [get_ports clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("BUFGCE", d(78, 63, 45, 61));
        p.setGeneratorDelay("XPLL:NONE", d(413, 406, 344, 357));
        p.setGeneratorDelay("XPLL:NONE:CLKOUT1", d(432, 426, 349, 369));
        p.setGeneratorDelay("XPLL:NONE:CLKOUT2", d(428, 422, 354, 369));
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.Versal), p));
        float phase2 = 106.875f / 360 * 5000;
        float phase0 = -53.4375f / 360 * 5000;
        Corner[] cs = {Corner.SLOW_MAX, Corner.SLOW_MIN, Corner.FAST_MIN, Corner.FAST_MAX};
        float[] out2 = {428, 422, 354, 369};
        float[] out1 = {432, 426, 349, 369};
        float[] out0 = {413, 406, 344, 357};
        float[] bufg = {78, 63, 45, 61};
        float[] bufgOpp = {63, 78, 61, 45};
        for (int c = 0; c < cs.length; c++) {
            // CLKIN -> CLKOUT1: CLKOUT2's delay with its phase, less the feedback loop at the opposite corner
            Assertions.assertEquals(out2[c] + phase2 - bufgOpp[c] + bufg[c],
                    m.getInsertion(cn.getClockPinIndex("xp_r1/C"), cs[c]), 1e-2, cs[c].name());
            Assertions.assertEquals(out0[c] + phase0 + bufg[c],
                    m.getInsertion(cn.getClockPinIndex("xp_r0/C"), cs[c]), 1e-2, cs[c].name());
            Assertions.assertEquals(out1[c] + bufg[c], m.getInsertion(cn.getClockPinIndex("xn_r1/C"), cs[c]), 1e-2);
            Assertions.assertEquals(out1[c] + bufg[c], m.getInsertion(cn.getClockPinIndex("xd_r1/C"), cs[c]), 1e-2);
            Assertions.assertEquals(out0[c] + phase0 + bufg[c],
                    m.getInsertion(cn.getClockPinIndex("xd_r0/C"), cs[c]), 1e-2);
            Assertions.assertEquals(out1[c] + bufg[c], m.getInsertion(cn.getClockPinIndex("xc_r1/C"), cs[c]), 1e-2);
        }
        Assertions.assertEquals(1, (int) m.getWarnings().get(
                "clock generator digital deskew with programmable delays (not deskewed): xd"));
    }

    @Test
    public void testNetlistOnlyModel() {
        // Without a design, the insertion delays are the buffer and generator delays of the stages
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("IBUF", d(500, 450, 300, 330));
        p.setBufferDelay("BUFG", d(100, 90, 60, 66));
        p.setBufferDelay("BUFGCE_DIV", d(120, 110, 70, 77));
        p.setBufferDelay("LUT1", d(40, 36, 20, 22));
        p.setGeneratorDelay("MMCME4_ADV", d(50, 45, 30, 33));
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.UltraScalePlus), p));
        int r0 = cn.getClockPinIndex("r0/C");
        int rdiv = cn.getClockPinIndex("rdiv/C");
        int rinv = cn.getClockPinIndex("rinv/C");
        // IBUF + (MMCM - feedback BUFG at the opposite corner) + BUFG; the MMCM's COMPENSATION is AUTO
        Assertions.assertTrue(m.includesSourceLatency());
        Assertions.assertEquals(500 + (50 - 90) + 100, m.getInsertion(r0, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(450 + (45 - 100) + 90, m.getInsertion(r0, Corner.SLOW_MIN), 1e-3);
        Assertions.assertEquals(300 + (30 - 66) + 60, m.getInsertion(r0, Corner.FAST_MIN), 1e-3);
        // CLKOUT1's 90 degree phase shift (1.25 ns) is part of the insertion
        Assertions.assertEquals(500 + (50 + 1250 - 90) + 120, m.getInsertion(rdiv, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(500 + (50 - 90) + 100 + 40, m.getInsertion(rinv, Corner.SLOW_MAX), 1e-3);
        // r0 and rinv share the path up to the BUFG output
        Assertions.assertEquals((500 - 40 + 100) - (450 - 55 + 90), m.getSetupCrpr(r0, rinv), 1e-3);
        // r0 and rdiv share the path through the MMCM (its compensated delay is common to its outputs)
        Assertions.assertEquals((500 + 50 - 90) - (450 + 45 - 100), m.getSetupCrpr(r0, rdiv), 1e-3);
        Assertions.assertEquals(0, m.getNumRoutedPins());
    }

    /**
     * A clock mux (BUFGCTRL) with a clock on each input: each clock has its own path to the flops (its
     * input's insertion delay, and the CRPR of its own path), not the first input's.
     */
    @Test
    public void testClockMuxPaths() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("ib", Unisim.IBUF);
        nb.inst("bg", Unisim.BUFG);
        nb.inst("mux", Unisim.BUFGCTRL);
        nb.connect("clk_a", "clk_a", "ib/I");
        nb.connect("a", "ib/O", "mux/I0");
        nb.connect("clk_b", "clk_b", "bg/I");
        nb.connect("b", "bg/O", "mux/I1");
        nb.connect("m", "mux/O");
        nb.flop("r1", "m");
        nb.flop("r2", "m");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name ka [get_ports clk_a]",
                "create_clock -period 10 -name kb [get_ports clk_b]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("IBUF", d(500, 450, 300, 330));
        p.setBufferDelay("BUFG", d(100, 90, 60, 66));
        p.setBufferDelay("BUFGCTRL", d(30, 27, 15, 17));
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.UltraScalePlus), p));
        int r1 = cn.getClockPinIndex("r1/C");
        int r2 = cn.getClockPinIndex("r2/C");
        int ka = cn.getClock("ka").getId();
        int kb = cn.getClock("kb").getId();
        Assertions.assertNotEquals(cn.getClockPinStage(r1, ka), cn.getClockPinStage(r1, kb));
        Assertions.assertEquals(500 + 30, m.getInsertion(r1, ka, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(100 + 30, m.getInsertion(r1, kb, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals((500 + 30) - (450 + 27), m.getSetupCrpr(r1, r2, ka), 1e-3);
        Assertions.assertEquals((100 + 30) - (90 + 27), m.getSetupCrpr(r1, r2, kb), 1e-3);
    }

    @Test
    public void testUserGeneratedClockOnGeneratorOutput() {
        // A create_generated_clock on an MMCM output times that output as the auto-derived clock would: the
        // compensated generator delay, not a cell arc
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("IBUF", d(500, 450, 300, 330));
        p.setBufferDelay("BUFG", d(100, 90, 60, 66));
        p.setBufferDelay("MMCME4_ADV", d(-1000, -900, -500, -550));
        p.setGeneratorDelay("MMCME4_ADV", d(50, 45, 30, 33));
        float[] ins = new float[2];
        for (int i = 0; i < 2; i++) {
            List<String> xdc = new ArrayList<>(Arrays.asList("create_clock -period 8 -name clk_a [get_ports clk_a]"));
            if (i == 1) {
                xdc.add("create_generated_clock -name gen0 -source [get_pins mmcm/CLKIN1] -multiply_by 2 "
                        + "[get_pins mmcm/CLKOUT0]");
            }
            ClockNetwork cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, xdc));
            ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                    ClockNodeClassifier.forSeries(Series.UltraScalePlus), p));
            ins[i] = m.getInsertion(cn.getClockPinIndex("r0/C"), Corner.SLOW_MAX);
        }
        // Not the cell arc's -1000
        Assertions.assertTrue(ins[0] > 0);
        Assertions.assertEquals(ins[0], ins[1], 1e-3);
    }

    @Test
    public void testClockLatency() {
        // set_clock_latency: source latencies (min/max corners, inherited by the generated clocks) and an
        // ideal clock's network latency
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]",
                "create_clock -period 10 -name clk_b [get_ports clk_b]",
                "set_clock_latency -source -max 0.300 [get_clocks clk_a]",
                "set_clock_latency -source -min 0.100 [get_clocks clk_a]",
                "set_clock_latency 0.250 [get_clocks clk_b]"));
        Assertions.assertEquals(3, tc.getClockLatencies().size());
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("IBUF", d(500, 450, 300, 330));
        p.setBufferDelay("BUFG", d(100, 90, 60, 66));
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.UltraScalePlus), p));
        Clock a = cn.getClock("clk_a");
        Assertions.assertEquals(300, a.getUserSourceLatency(true), 1e-9);
        Assertions.assertEquals(100, a.getUserSourceLatency(false), 1e-9);
        Assertions.assertFalse(a.isIdeal());
        int r0 = cn.getClockPinIndex("r0/C");
        Clock o0 = cn.getClock(cn.getClockPinEdges(r0)[0] >> 1);
        Assertions.assertSame(a, o0.getMaster());
        Assertions.assertEquals(300, o0.getUserSourceLatency(true), 1e-9);
        Assertions.assertEquals(m.getInsertion(r0, Corner.SLOW_MAX) + 300f, m.getClockArrival(r0, o0, Corner.SLOW_MAX),
                1e-3);
        Assertions.assertEquals(m.getInsertion(r0, Corner.FAST_MIN) + 100f, m.getClockArrival(r0, o0, Corner.FAST_MIN),
                1e-3);
        // The ideal clock: its network latency instead of the insertion delays
        Clock b = cn.getClock("clk_b");
        Assertions.assertTrue(b.isIdeal());
        int rb = cn.getClockPinIndex("rb/C");
        Assertions.assertTrue(m.getInsertion(rb, Corner.SLOW_MAX) > 0);
        Assertions.assertEquals(250f, m.getClockArrival(rb, b, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(250f, m.getClockArrival(rb, b, Corner.FAST_MIN), 1e-3);
    }

    /** An MMCM with BUFG feedback and clocks on CLKIN1 and CLKIN2, CLKOUT1 /4 at 90 degrees through a BUFG */
    private static TestClockNetwork.NetlistBuilder dualInputMmcm() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("ib_a", Unisim.IBUF);
        nb.inst("ib_b", Unisim.IBUF);
        nb.inst("mmcm", Unisim.MMCME4_ADV, "CLKFBOUT_MULT_F", "10.0", "DIVCLK_DIVIDE", "1", "CLKOUT1_DIVIDE", "4",
                "CLKOUT1_PHASE", "90.0", "COMPENSATION", "ZHOLD");
        nb.inst("bfb", Unisim.BUFG);
        nb.inst("b1", Unisim.BUFG);
        nb.connect("clk_a", "clk_a", "ib_a/I");
        nb.connect("clk_b", "clk_b", "ib_b/I");
        nb.connect("a", "ib_a/O", "mmcm/CLKIN1");
        nb.connect("b", "ib_b/O", "mmcm/CLKIN2");
        nb.connect("fb", "mmcm/CLKFBOUT", "bfb/I");
        nb.connect("fbb", "bfb/O", "mmcm/CLKFBIN");
        nb.connect("o1", "mmcm/CLKOUT1", "b1/I");
        nb.connect("c1", "b1/O");
        nb.flop("r1", "c1");
        return nb;
    }

    private static ClockDelayParams bufferParams() {
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("IBUF", d(500, 450, 300, 330));
        p.setBufferDelay("BUFG", d(100, 90, 60, 66));
        return p;
    }

    /**
     * A generator with clocks on both inputs compensates the feedback path of each one, and each output clock has
     * the phase shift of its own period.
     */
    @Test
    public void testDualInputGenerator() {
        TestClockNetwork.NetlistBuilder nb = dualInputMmcm();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk_a [get_ports clk_a]",
                "create_clock -period 8 -name clk_b [get_ports clk_b]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockDelayParams p = bufferParams();
        p.setGeneratorDelay("MMCM:ZHOLD", d(50, 45, 30, 33));
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.UltraScalePlus), p));
        Assertions.assertTrue(m.getWarnings().isEmpty(), m.getWarnings().toString());
        int r1 = cn.getClockPinIndex("r1/C");
        Assertions.assertEquals(2, cn.getClockPinEdges(r1).length);
        for (int e : cn.getClockPinEdges(r1)) {
            Clock c = cn.getClock(e >> 1);
            // 90 degrees of the output period: 4 ns from clk_a, 3.2 ns from clk_b
            double phase = c.getMaster().getName().equals("clk_a") ? 1000 : 800;
            Assertions.assertEquals(phase, c.getSourceLatency(), 1e-6);
            // IBUF + (MMCM - feedback BUFG at the opposite corner) + phase + BUFG
            Assertions.assertEquals(500 + (50 - 90) + phase + 100, m.getInsertion(r1, c.getId(), Corner.SLOW_MAX),
                    1e-3, c.getName());
            Assertions.assertEquals(300 + (30 - 66) + phase + 60, m.getInsertion(r1, c.getId(), Corner.FAST_MIN),
                    1e-3, c.getName());
        }
    }

    /**
     * COMPENSATION AUTO, the default, as Vivado 2026.1 resolves it (the COMPENSATION of the synthesized netlist):
     * INTERNAL with a direct feedback; with a BUFG feedback, ZHOLD for an MMCME4 whose input clock comes from an
     * input buffer, BUF_IN if it comes from a BUFG (xcvu3p; a ZHOLD so set becomes BUF_IN too), BUF_IN for an
     * MMCME5 (xcvc1902, which has no ZHOLD).
     */
    @Test
    public void testAutoCompensation() {
        ClockDelayParams p = bufferParams();
        p.setGeneratorDelay("MMCM:ZHOLD", d(-1000, -1100, -700, -650));
        p.setGeneratorDelay("MMCM:BUF_IN", d(-400, -410, -300, -290));
        p.setGeneratorDelay("MMCM:INTERNAL", d(-100, -110, -70, -65));
        ClockNodeDelayModel nm = new ClockNodeDelayModel(ClockNodeClassifier.forSeries(Series.UltraScalePlus), p);
        for (Unisim type : new Unisim[]{Unisim.MMCME4_ADV, Unisim.MMCME5}) {
            for (String variant : new String[]{"direct", "ibuf", "bufg", "zhold"}) {
                TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
                nb.inst("ib", Unisim.IBUF);
                nb.inst("mmcm", type, "CLKFBOUT_MULT_F", "10.0", "CLKFBOUT_MULT", "10", "CLKOUT0_DIVIDE_F", "10.0",
                        "CLKOUT0_DIVIDE", "10");
                if (variant.equals("zhold")) {
                    nb.insts.get("mmcm").addProperty("COMPENSATION", "ZHOLD");
                }
                nb.inst("b0", Unisim.BUFG);
                nb.connect("clk", "clk", "ib/I");
                float in = 500;
                if (variant.equals("bufg") || variant.equals("zhold")) {
                    nb.inst("bin", Unisim.BUFG);
                    nb.connect("ci", "ib/O", "bin/I");
                    nb.connect("cb", "bin/O", "mmcm/CLKIN1");
                    in += 100;
                } else {
                    nb.connect("ci", "ib/O", "mmcm/CLKIN1");
                }
                if (variant.equals("direct")) {
                    nb.connect("fb", "mmcm/CLKFBOUT", "mmcm/CLKFBIN");
                } else {
                    nb.inst("bfb", Unisim.BUFG);
                    nb.connect("fb", "mmcm/CLKFBOUT", "bfb/I");
                    nb.connect("fbb", "bfb/O", "mmcm/CLKFBIN");
                }
                nb.connect("o0", "mmcm/CLKOUT0", "b0/I");
                nb.connect("c0", "b0/O");
                nb.flop("r0", "c0");
                ClockNetwork cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist,
                        Arrays.asList("create_clock -period 10 -name clk [get_ports clk]")));
                ClockModel m = ClockModel.build(null, cn, nm);
                Assertions.assertTrue(m.getWarnings().isEmpty(), m.getWarnings().toString());
                float gen = variant.equals("direct") ? -100
                        : type == Unisim.MMCME4_ADV && variant.equals("ibuf") ? -1000 - 90 : -400 - 90;
                Assertions.assertEquals(in + gen + 100, m.getInsertion(cn.getClockPinIndex("r0/C"),
                        Corner.SLOW_MAX), 1e-3, type + " " + variant);
            }
        }
    }

    /**
     * A constant CLKINSEL selects the input whose clocks a generator derives (Vivado 2026.1, xcvu3p and xcvc1902:
     * with CLKINSEL tied to 1, only CLKIN1's clocks).
     */
    @Test
    public void testConstantClockInputSelect() {
        for (int sel = 0; sel < 2; sel++) {
            TestClockNetwork.NetlistBuilder nb = dualInputMmcm();
            nb.tie("mmcm/CLKINSEL", 1, sel);
            ClockNetwork cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, Arrays.asList(
                    "create_clock -period 10 -name clk_a [get_ports clk_a]",
                    "create_clock -period 8 -name clk_b [get_ports clk_b]")));
            int[] edges = cn.getClockPinEdges(cn.getClockPinIndex("r1/C"));
            Assertions.assertEquals(1, edges.length);
            Assertions.assertEquals(sel == 1 ? "clk_a" : "clk_b", cn.getClock(edges[0] >> 1).getMaster().getName());
        }
    }

    /**
     * The source latency of an ideal clock adds to its network latency (Vivado 2026.1: set_clock_latency -source 0.4
     * and set_clock_latency 0.2 on a clock: "clock source latency 0.400" then "ideal clock network latency 0.200").
     */
    @Test
    public void testSourceLatencyOfIdealClock() {
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk_b [get_ports clk_b]",
                "set_clock_latency -source 0.4 [get_clocks clk_b]",
                "set_clock_latency 0.2 [get_clocks clk_b]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.UltraScalePlus), bufferParams()));
        Clock b = cn.getClock("clk_b");
        Assertions.assertTrue(b.isIdeal());
        int rb = cn.getClockPinIndex("rb/C");
        Assertions.assertEquals(600f, m.getClockArrival(rb, b, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(600f, m.getClockArrival(rb, b, Corner.FAST_MIN), 1e-3);
        Assertions.assertEquals(600.0, ClockModel.getClockLatency(b, true, true), 1e-9);
    }

    /** The inter-SLR compensation of a clock through a clock mux input takes the clock's own paths */
    @Test
    public void testInterSlrCompensationOfClockPaths() {
        // root -> {a -> {a0, a1}, b -> {b0, b1}}: clock 0 by a, clock 1 by b; pin 0 in SLR 0, pin 1 in SLR 1
        ClockTree t = new ClockTree();
        int root = t.addNode(ClockTree.NO_PARENT, d(100, 90, 50, 55));
        int a = t.addNode(root, d(300, 270, 150, 165));
        int a0 = t.addNode(a, d(10, 9, 5, 6));
        int a1 = t.addNode(a, d(20, 18, 10, 12));
        int b = t.addNode(root, d(400, 360, 200, 220));
        int b0 = t.addNode(b, d(30, 27, 15, 18));
        int b1 = t.addNode(b, d(40, 36, 20, 24));
        ClockModel m = ClockModel.of(t, new int[]{a0, a1}, new int[]{0, 1});
        m.setClockPathNode(0, 1, b0);
        m.setClockPathNode(1, 1, b1);
        // (DCD - CCD)*PF: clock 0's DCD by a1 and CCD at a; clock 1's by b1 and b
        Assertions.assertEquals(18 * 0.5f, m.getInterSlrCompensation(0, 1, Corner.SLOW_MIN, 0.5f, false, 0), 1e-3);
        Assertions.assertEquals(36 * 0.5f, m.getInterSlrCompensation(0, 1, Corner.SLOW_MIN, 0.5f, false, 1), 1e-3);
        Assertions.assertEquals((1000 - (90 + 360)) * 0.5f, m.getInterSlrCompensationOfArrival(0, 1, 1000f,
                Corner.SLOW_MIN, 0.5f, false, 1), 1e-3);
    }

    /**
     * Every kind of clock has a discrete jitter in the data of each family, its own or another's: Versal gen1 has
     * no MMCM data (its MMCM clocks take the PLL's, with a warning), Versal gen2 no PLL data, UltraScale+ no GT or
     * FABRIC data (PORT's). Every kind of generator but the DPLL has compensation data.
     */
    @Test
    public void testJitterKindsWithoutData() {
        String[] kinds = {"MMCM", "PLL", "PS", "GT", "PORT", "FABRIC"};
        for (String fam : new String[]{"ultrascaleplus", "versal_gen1", "versal_gen2"}) {
            UncertaintyModel.Coefficients k = ClockData.get(fam, null).getCoefficients();
            for (String kind : kinds) {
                Assertions.assertNotNull(k.getJitterKind(kind), fam + " " + kind);
            }
        }
        // The compensation of the generators: every kind but the DPLL has data (warned, see ClockModel)
        for (String[] fk : new String[][]{{"ultrascaleplus", "MMCME4_ADV", "ZHOLD"}, {"ultrascaleplus", "MMCME4_ADV", "BUF_IN"},
                {"ultrascaleplus", "MMCME4_ADV", "INTERNAL"}, {"ultrascaleplus", "PLLE4_ADV", "INTERNAL"},
                {"versal_gen1", "MMCME5", "BUF_IN"}, {"versal_gen1", "MMCME5", "INTERNAL"},
                {"versal_gen1", "XPLL", null}, {"versal_gen2", "MMCME5", "BUF_IN"}, {"versal_gen2", "X5PLL", null}}) {
            Assertions.assertTrue(ClockData.get(fk[0], null).getParams().hasGeneratorDelay(fk[1], fk[2]),
                    Arrays.toString(fk));
        }
        Assertions.assertFalse(ClockData.get("versal_gen1", null).getParams().hasGeneratorDelay("DPLL", null));
        UncertaintyModel.Coefficients gen1 = ClockData.get("versal_gen1", null).getCoefficients();
        Assertions.assertFalse(gen1.jitter.containsKey("MMCM"));
        Assertions.assertEquals("PLL", gen1.getJitterKind("MMCM"));
        Assertions.assertEquals("MMCM", ClockData.get("versal_gen2", null).getCoefficients().getJitterKind("PLL"));
        Assertions.assertEquals("PORT", ClockData.get("ultrascaleplus", null).getCoefficients().getJitterKind("FABRIC"));

        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("mmcm", Unisim.MMCME5, "CLKFBOUT_MULT", "24", "CLKOUT0_DIVIDE", "24", "COMPENSATION", "INTERNAL");
        nb.connect("clk", "clk", "mmcm/CLKIN1");
        nb.connect("fb", "mmcm/CLKFBOUT", "mmcm/CLKFBIN");
        nb.connect("o0", "mmcm/CLKOUT0");
        nb.flop("r0", "o0");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk [get_ports clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Clock o0 = cn.getClock("o0");
        Assertions.assertEquals("MMCM", o0.getKind());
        float pll = gen1.jitter.get("PLL")[0] + gen1.jitter.get("PLL")[1] * 10;
        Assertions.assertTrue(pll > 0);
        Assertions.assertEquals(pll, gen1.getDiscreteJitter(o0), 1e-3);
        UncertaintyModel u = ClockData.get("versal_gen1", null).createUncertaintyModel(cn, tc);
        Assertions.assertEquals(pll / 2, u.getSetup(o0.getId(), o0.getId()), 1e-3);
        Assertions.assertEquals(1, u.getWarnings().get(
                "clocks without discrete jitter data, that of another kind used: MMCM as PLL"));
    }

    /**
     * The discrete jitter of UltraScale+ clocks, as Vivado 2026.1 gives it:
     * <ul>
     * <li>a clock generator output's depends on the VCO frequency, the feedback multiplier and the output divide
     * (e.g. 350 ps for an MMCME4 at 800 MHz, x32, /50; 77 ps at 1250 MHz, x10, /2), the VCO frequency coming from the
     * period of the clock at the generator's input (here 8 ns, its CLKIN1_PERIOD property saying 10 ns);</li>
     * <li>a primary clock defined on a clock buffer that a generator output drives has the generator's jitter, its VCO
     * frequency from the clock at the generator's input if there is one, else from its input period property;</li>
     * <li>one defined on a BUFG_GT that a transceiver output drives has 60 ps; one of a port none.</li>
     * </ul>
     */
    @Test
    public void testDiscreteJitterOfGeneratorAndBufferClocks() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("mmcm", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.000", "CLKFBOUT_MULT_F", "32.000", "DIVCLK_DIVIDE",
                "4", "CLKOUT0_DIVIDE_F", "50.000", "COMPENSATION", "INTERNAL");
        nb.inst("bufg", Unisim.BUFGCE);
        nb.connect("clk", "clk", "mmcm/CLKIN1");
        nb.connect("fb", "mmcm/CLKFBOUT", "mmcm/CLKFBIN");
        nb.connect("o0", "mmcm/CLKOUT0", "bufg/I");
        nb.connect("ck", "bufg/O");
        nb.flop("r0", "ck");
        nb.inst("mmcm2", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.000", "CLKFBOUT_MULT_F", "10.000", "DIVCLK_DIVIDE",
                "1", "CLKOUT0_DIVIDE_F", "2.000", "COMPENSATION", "INTERNAL");
        nb.connect("clk2", "clk2", "mmcm2/CLKIN1");
        nb.connect("fb2", "mmcm2/CLKFBOUT", "mmcm2/CLKFBIN");
        nb.inst("bufg2", Unisim.BUFGCE);
        nb.connect("o2", "mmcm2/CLKOUT0", "bufg2/I");
        nb.flop("r2", "o2");
        nb.connect("ck2", "bufg2/O");
        nb.flop("r4", "ck2");
        nb.inst("gt", Unisim.GTYE4_CHANNEL);
        nb.inst("bufg_gt", Unisim.BUFG_GT);
        nb.connect("txout", "gt/TXOUTCLK", "bufg_gt/I");
        nb.connect("gck", "bufg_gt/O");
        nb.flop("r1", "gck");
        nb.connect("pclk", "pclk");
        nb.flop("r3", "pclk");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 2 -name k [get_pins bufg/O]",
                "create_clock -period 8 -name c2 [get_ports clk2]",
                "create_clock -period 2 -name k2 [get_pins bufg2/O]",
                "create_clock -period 2 -name g [get_pins bufg_gt/O]",
                "create_clock -period 2 -name p [get_ports pclk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Clock k = cn.getClock("k");
        Assertions.assertEquals("MMCM", k.getKind());
        Assertions.assertEquals(800, k.getVcoFrequency(), 1e-6);
        Assertions.assertEquals(32, k.getGeneratorMultiplier(), 1e-6);
        Assertions.assertEquals(50, k.getGeneratorDivide(), 1e-6);
        Clock o2 = cn.getClock("o2");
        Assertions.assertEquals("MMCM", o2.getKind());
        Assertions.assertEquals(1250, o2.getVcoFrequency(), 1e-6);
        // A clock on a buffer behind a generator whose input has a clock: the VCO frequency of that clock's period
        Clock k2 = cn.getClock("k2");
        Assertions.assertEquals("MMCM", k2.getKind());
        Assertions.assertEquals(1250, k2.getVcoFrequency(), 1e-6);
        Clock g = cn.getClock("g");
        Assertions.assertEquals("GT", g.getKind());
        Assertions.assertTrue(Double.isNaN(g.getVcoFrequency()));
        Clock p = cn.getClock("p");
        Assertions.assertEquals("FABRIC", p.getKind());

        ClockData usp = ClockData.get("ultrascaleplus", "3");
        UncertaintyModel.Coefficients coef = usp.getCoefficients();
        // Vivado: DJ 350, 77, 60 and 0 ps; uncertainty sqrt(71^2 + DJ^2) / 2: 179, 52, 46 and 35 ps
        Assertions.assertEquals(350f, coef.getDiscreteJitter(k), 1f);
        Assertions.assertEquals(77f, coef.getDiscreteJitter(o2), 1f);
        Assertions.assertEquals(77f, coef.getDiscreteJitter(k2), 1f);
        Assertions.assertEquals(60f, coef.getDiscreteJitter(g), 1e-3);
        Assertions.assertEquals(0f, coef.getDiscreteJitter(p), 1e-3);
        UncertaintyModel u = usp.createUncertaintyModel(cn, tc);
        Assertions.assertEquals(179f, u.getSetup(k.getId(), k.getId()), 1f);
        Assertions.assertEquals(52f, u.getSetup(o2.getId(), o2.getId()), 1f);
        Assertions.assertEquals(46f, u.getSetup(g.getId(), g.getId()), 1f);
        Assertions.assertEquals(35.5f, u.getSetup(p.getId(), p.getId()), 0.1f);
        // The same table for the PLLs (Vivado: a PLLE4 output has an MMCME4 output's jitter at the same settings)
        Assertions.assertSame(coef.jitterTables.get("MMCM"), coef.jitterTables.get("PLL"));
        Assertions.assertEquals(95f, coef.jitterTables.get("PLL").getJitter(1000, 10, 2), 0.5f);
        Assertions.assertEquals(124f, coef.jitterTables.get("PLL").getJitter(1000, 10, 8), 0.5f);
    }

    /**
     * The discrete jitter of UltraScale+ clocks behind clock generators in other arrangements, as Vivado 2026.1 gives
     * it (MMCME4 A: 10 ns, x32, /4, /50: 350 ps; MMCME4 B and D: x10, /1, /2 at an 8 ns input period: 77 ps):
     * <ul>
     * <li>the clocks a divider or a create_generated_clock derives from a primary clock on a buffer behind a
     * generator have its jitter (350 ps);</li>
     * <li>a create_generated_clock on a generator output has the generator's jitter at the VCO frequency of the clock
     * at its input (77 ps), and so has a primary clock on a buffer behind that output;</li>
     * <li>without a clock at the generator's input, the input period property of the input CLKINSEL selects
     * (CLKIN2_PERIOD, 8 ns: 77 ps);</li>
     * <li>a primary clock on a clock mux output has the jitter of the input that a constant select picks: 350 ps on
     * the MMCM's input, 0 on a port's (BUFGMUX S, BUFGCTRL S0 / S1); with a select that is not constant, the MMCM's
     * on either input.</li>
     * </ul>
     */
    @Test
    public void testDiscreteJitterOfDerivedAndMuxedGeneratorClocks() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("mmcmA", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.0", "CLKFBOUT_MULT_F", "32.0", "DIVCLK_DIVIDE", "4",
                "CLKOUT0_DIVIDE_F", "50.0", "CLKOUT1_DIVIDE", "50", "COMPENSATION", "INTERNAL");
        nb.connect("clk_a", "clk_a", "mmcmA/CLKIN1");
        nb.connect("fa", "mmcmA/CLKFBOUT", "mmcmA/CLKFBIN");
        nb.inst("mmcmB", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.0", "CLKFBOUT_MULT_F", "10.0", "DIVCLK_DIVIDE", "1",
                "CLKOUT0_DIVIDE_F", "2.0", "COMPENSATION", "INTERNAL");
        nb.connect("clk_b", "clk_b", "mmcmB/CLKIN1");
        nb.connect("fb", "mmcmB/CLKFBOUT", "mmcmB/CLKFBIN");
        nb.inst("mmcmD", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.0", "CLKIN2_PERIOD", "8.0", "CLKFBOUT_MULT_F",
                "10.0", "DIVCLK_DIVIDE", "1", "CLKOUT0_DIVIDE_F", "2.0", "COMPENSATION", "INTERNAL");
        nb.connect("clk_d", "clk_d", "mmcmD/CLKIN1");
        nb.connect("clk_e", "clk_e", "mmcmD/CLKIN2");
        nb.tie("mmcmD/CLKINSEL", 1, 0);
        nb.connect("fd", "mmcmD/CLKFBOUT", "mmcmD/CLKFBIN");
        nb.inst("bufA", Unisim.BUFGCE);
        nb.connect("oa0", "mmcmA/CLKOUT0", "bufA/I");
        nb.inst("bdivA1", Unisim.BUFGCE_DIV, "BUFGCE_DIVIDE", "2");
        nb.inst("bdivA2", Unisim.BUFGCE_DIV, "BUFGCE_DIVIDE", "2");
        nb.connect("ckA", "bufA/O", "bdivA1/I", "bdivA2/I");
        nb.connect("ckA1", "bdivA1/O");
        nb.connect("ckA2", "bdivA2/O");
        nb.inst("bufB", Unisim.BUFGCE);
        nb.inst("bufB2", Unisim.BUFGCE);
        nb.connect("ob", "mmcmB/CLKOUT0", "bufB/I", "bufB2/I");
        nb.connect("ckB", "bufB/O");
        nb.connect("ckB2", "bufB2/O");
        nb.inst("muxC1", Unisim.BUFGMUX);
        nb.inst("muxC0", Unisim.BUFGMUX);
        nb.inst("ctrlC", Unisim.BUFGCTRL);
        nb.inst("muxCs", Unisim.BUFGMUX);
        nb.inst("muxCr", Unisim.BUFGMUX);
        nb.inst("ctrlCu", Unisim.BUFGCTRL);
        nb.connect("oa1", "mmcmA/CLKOUT1", "muxC1/I0", "muxC0/I0", "ctrlC/I0", "muxCs/I0", "muxCr/I1", "ctrlCu/I1");
        nb.connect("clk_p", "clk_p", "muxC1/I1", "muxC0/I1", "ctrlC/I1", "muxCs/I1", "muxCr/I0", "ctrlCu/I0");
        nb.connect("sel", "sel", "muxCs/S", "muxCr/S", "ctrlCu/S0");
        nb.connect("sel2", "sel2", "ctrlCu/S1");
        nb.tie("muxC1/S", 1, 1);
        nb.tie("muxC0/S", 1, 0);
        nb.tie("ctrlC/S0", 1, 0);
        nb.tie("ctrlC/S1", 1, 1);
        nb.connect("ckC1", "muxC1/O");
        nb.connect("ckC0", "muxC0/O");
        nb.connect("ckCt", "ctrlC/O");
        nb.connect("ckCs", "muxCs/O");
        nb.connect("ckCr", "muxCr/O");
        nb.connect("ckCu", "ctrlCu/O");
        nb.inst("bufD", Unisim.BUFGCE);
        nb.connect("od", "mmcmD/CLKOUT0", "bufD/I");
        nb.connect("ckD", "bufD/O");
        String[] nets = {"ckA", "ckA1", "ckA2", "ckB", "ckB2", "ckC1", "ckC0", "ckCt", "ckCs", "ckCr", "ckCu", "ckD"};
        for (int i = 0; i < nets.length; i++) {
            nb.flop("r" + i, nets[i]);
        }
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name ca [get_ports clk_a]",
                "create_clock -period 8 -name cb [get_ports clk_b]",
                "create_clock -period 2 -name kA [get_pins bufA/O]",
                "create_generated_clock -name gA2 -source [get_pins bdivA2/I] -divide_by 2 [get_pins bdivA2/O]",
                "create_generated_clock -name gB -source [get_pins mmcmB/CLKIN1] -multiply_by 5 [get_pins mmcmB/CLKOUT0]",
                "create_clock -period 1.6 -name kB [get_pins bufB/O]",
                "create_clock -period 2 -name kC1 [get_pins muxC1/O]",
                "create_clock -period 2 -name kC0 [get_pins muxC0/O]",
                "create_clock -period 2 -name kCt [get_pins ctrlC/O]",
                "create_clock -period 2 -name kCs [get_pins muxCs/O]",
                "create_clock -period 2 -name kCr [get_pins muxCr/O]",
                "create_clock -period 2 -name kCu [get_pins ctrlCu/O]",
                "create_clock -period 1.6 -name kD [get_pins bufD/O]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        UncertaintyModel.Coefficients coef = ClockData.get("ultrascaleplus", "3").getCoefficients();
        // Vivado's discrete jitter of each clock to itself (report_timing), in ps
        String[] names = {"kA", "ckA1", "gA2", "gB", "kB", "kD", "kC0", "kC1", "kCt", "kCs", "kCr", "kCu"};
        float[] vivado = {350, 350, 350, 77, 77, 77, 350, 0, 0, 350, 350, 350};
        for (int i = 0; i < names.length; i++) {
            Clock c = cn.getClock(names[i]);
            Assertions.assertNotNull(c, names[i]);
            Assertions.assertEquals(vivado[i], coef.getDiscreteJitter(c), 1f, names[i]);
        }
        // The settings come from the traced primary clock (descendants) and from the generator output (gB, kB)
        Assertions.assertSame(cn.getClock("kA"), cn.getClock("ckA1").getGeneratorSettingsSource());
        Assertions.assertSame(cn.getClock("kA"), cn.getClock("gA2").getGeneratorSettingsSource());
        Assertions.assertEquals("MMCM", cn.getClock("gB").getKind());
        Assertions.assertEquals(1250, cn.getClock("gB").getVcoFrequency(), 1e-6);
        Assertions.assertEquals(1250, cn.getClock("kB").getVcoFrequency(), 1e-6);
        Assertions.assertEquals(1250, cn.getClock("kD").getVcoFrequency(), 1e-6);
        Assertions.assertEquals("MMCM", cn.getClock("kC0").getKind());
        Assertions.assertNotEquals("MMCM", cn.getClock("kC1").getKind());
        Assertions.assertNotEquals("MMCM", cn.getClock("kCt").getKind());
    }

    /**
     * User generated clocks on a generator output take the settings of their master clock at the generator's input
     * (MMCME4, x10, /1, /2: 95 ps at a 10 ns input clock, 77 ps at 8 ns, the jitter Vivado gives those settings),
     * whatever the order of the constraints that bring a clock to the input, and per master clock when two clocks
     * reach the generator's inputs.
     */
    @Test
    public void testDiscreteJitterOfGeneratedClocksOnGeneratorOutputs() {
        UncertaintyModel.Coefficients coef = ClockData.get("ultrascaleplus", "3").getCoefficients();
        String out = "create_generated_clock -name gE -source [get_ports clk_e] -multiply_by 5 [get_pins mmcmE/CLKOUT0]";
        String up = "create_generated_clock -name gU -source [get_pins bufE/I] -divide_by 1 [get_pins bufE/O]";
        for (boolean outFirst : new boolean[]{true, false}) {
            TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
            nb.inst("bufE", Unisim.BUFGCE);
            nb.connect("clk_e", "clk_e", "bufE/I");
            nb.inst("mmcmE", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.0", "CLKFBOUT_MULT_F", "10.0", "DIVCLK_DIVIDE",
                    "1", "CLKOUT0_DIVIDE_F", "2.0", "COMPENSATION", "INTERNAL");
            nb.connect("ck_e", "bufE/O", "mmcmE/CLKIN1");
            nb.connect("fe", "mmcmE/CLKFBOUT", "mmcmE/CLKFBIN");
            nb.inst("bufO", Unisim.BUFGCE);
            nb.connect("oe", "mmcmE/CLKOUT0", "bufO/I");
            nb.connect("ck_o", "bufO/O");
            nb.flop("r", "ck_o");
            ClockNetwork cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, Arrays.asList(
                    "create_clock -period 8 -name ce [get_ports clk_e]", outFirst ? out : up, outFirst ? up : out)));
            Clock g = cn.getClock("gE");
            Assertions.assertEquals(1250, g.getVcoFrequency(), 1e-6, "outFirst " + outFirst);
            Assertions.assertEquals(77f, coef.getDiscreteJitter(g), 1f, "outFirst " + outFirst);
        }
        // Two clocks on the inputs (CLKINSEL from fabric) and an output clock per master clock
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("mmcmF", Unisim.MMCME4_ADV, "CLKIN1_PERIOD", "10.0", "CLKIN2_PERIOD", "8.0", "CLKFBOUT_MULT_F", "10.0",
                "DIVCLK_DIVIDE", "1", "CLKOUT0_DIVIDE_F", "2.0", "COMPENSATION", "INTERNAL");
        nb.connect("clk_1", "clk_1", "mmcmF/CLKIN1");
        nb.connect("clk_2", "clk_2", "mmcmF/CLKIN2");
        nb.connect("sel", "sel", "mmcmF/CLKINSEL");
        nb.connect("ff", "mmcmF/CLKFBOUT", "mmcmF/CLKFBIN");
        nb.inst("bufF", Unisim.BUFGCE);
        nb.connect("of", "mmcmF/CLKOUT0", "bufF/I");
        nb.connect("ck_f", "bufF/O");
        nb.flop("rf", "ck_f");
        ClockNetwork cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name c1 [get_ports clk_1]",
                "create_clock -period 8 -name c2 [get_ports clk_2]",
                "create_generated_clock -name gF1 -source [get_pins mmcmF/CLKIN1] -master_clock c1 -multiply_by 5"
                        + " [get_pins mmcmF/CLKOUT0]",
                "create_generated_clock -name gF2 -source [get_pins mmcmF/CLKIN2] -master_clock c2 -multiply_by 5"
                        + " -add [get_pins mmcmF/CLKOUT0]")));
        Assertions.assertEquals(1000, cn.getClock("gF1").getVcoFrequency(), 1e-6);
        Assertions.assertEquals(95f, coef.getDiscreteJitter(cn.getClock("gF1")), 1f);
        Assertions.assertEquals(1250, cn.getClock("gF2").getVcoFrequency(), 1e-6);
        Assertions.assertEquals(77f, coef.getDiscreteJitter(cn.getClock("gF2")), 1f);
    }

    @Test
    public void testUncertainty() {
        TimingConstraints tc = TimingConstraints.parse((EDIFNetlist) null, Arrays.asList(
                "create_clock -period 5 -name clk1 [get_ports clk1]",
                "create_clock -period 7 -name clk2 [get_ports clk2]",
                "set_system_jitter 0.070",
                "set_input_jitter clk1 0.120",
                "set_clock_uncertainty 0.150 [get_clocks clk2]",
                "set_clock_uncertainty -setup 0.250 -from [get_clocks clk1] -to [get_clocks clk2]",
                "set_clock_uncertainty -hold 0.080 -from [get_clocks clk1] -to [get_clocks clk2]"));
        ClockNetwork cn = ClockNetwork.build((EDIFNetlist) null, tc);
        List<Clock> clocks = cn.getClocks();
        UncertaintyModel u = new UncertaintyModel(clocks, cn, tc, new UncertaintyModel.Coefficients());
        int c1 = cn.getClock("clk1").getId();
        int c2 = cn.getClock("clk2").getId();
        // Vivado (cons_unc): clk1 -> clk1 0.078, clk1 -> clk2 setup 0.328, hold 0.158, clk2 -> clk1 0.078
        Assertions.assertEquals(77.8, u.getSetup(c1, c1), 0.5);
        Assertions.assertEquals(327.8, u.getSetup(c1, c2), 0.5);
        Assertions.assertEquals(157.8, u.getHold(c1, c2), 0.5);
        Assertions.assertEquals(77.8, u.getSetup(c2, c1), 0.5);
        // Same-clock hold checks: user uncertainty only (the simple one of clk2)
        Assertions.assertEquals(0f, u.getHold(c1, c1));
        Assertions.assertEquals(150f, u.getHold(c2, c2));
        // Default system jitter (50 ps): sqrt(2)*50/2
        UncertaintyModel d = new UncertaintyModel(clocks, null, null, new UncertaintyModel.Coefficients());
        Assertions.assertEquals(35.36, d.getSetup(c1, c2), 0.01);

        // -add adds to the value of the same kind set before, else a later command replaces it; the
        // inter-clock uncertainty wins whatever the order
        TimingConstraints ta = TimingConstraints.parse((EDIFNetlist) null, Arrays.asList(
                "create_clock -period 5 -name clk1 [get_ports clk1]",
                "create_clock -period 7 -name clk2 [get_ports clk2]",
                "set_clock_uncertainty -setup 0.250 -from [get_clocks clk1] -to [get_clocks clk2]",
                "set_clock_uncertainty -setup -add 0.050 -from [get_clocks clk1] -to [get_clocks clk2]",
                "set_clock_uncertainty 0.300 [get_clocks clk2]",
                "set_clock_uncertainty 0.150 [get_clocks clk2]",
                "set_clock_uncertainty -hold -add 0.020 [get_clocks clk2]"));
        ClockNetwork ca = ClockNetwork.build((EDIFNetlist) null, ta);
        Assertions.assertTrue(ta.getUncertainties().get(1).add);
        UncertaintyModel.Coefficients none = new UncertaintyModel.Coefficients();
        none.systemJitter = 0f;
        UncertaintyModel ua = new UncertaintyModel(ca.getClocks(), ca, ta, none);
        int a1 = ca.getClock("clk1").getId();
        int a2 = ca.getClock("clk2").getId();
        Assertions.assertEquals(300f, ua.getSetup(a1, a2), 1e-3);
        Assertions.assertEquals(170f, ua.getHold(a1, a2), 1e-3);
        Assertions.assertEquals(150f, ua.getSetup(a2, a2), 1e-3);
        Assertions.assertEquals(170f, ua.getHold(a2, a2), 1e-3);
        Assertions.assertEquals(0f, ua.getSetup(a2, a1), 1e-3);
    }

    /**
     * Inter-clock uncertainty per edge pair (-rise_from/-fall_from, -rise_to/-fall_to), as Vivado-written XDC files
     * carry it: the analysis keeps one value per clock pair, which the same-edge entries set (the larger of the
     * rise-to-rise and fall-to-fall values); the opposite-edge entries are not modeled.
     */
    @Test
    public void testEdgeSpecificUncertainty() {
        TimingConstraints tc = TimingConstraints.parse((EDIFNetlist) null, Arrays.asList(
                "create_clock -period 5 -name clk1 [get_ports clk1]",
                "create_clock -period 7 -name clk2 [get_ports clk2]",
                "create_clock -period 9 -name clk3 [get_ports clk3]",
                "create_clock -period 11 -name clk4 [get_ports clk4]",
                "set_clock_uncertainty -setup -rise_from [get_clocks {clk1 clk3}] -rise_to [get_clocks clk2] 1.200",
                "set_clock_uncertainty -setup -rise_from [get_clocks clk1] -fall_to [get_clocks clk2] 0.500",
                "set_clock_uncertainty -setup -fall_from [get_clocks clk1] -rise_to [get_clocks clk2] 0.500",
                "set_clock_uncertainty -setup -fall_from [get_clocks clk1] -fall_to [get_clocks clk2] 1.000",
                "set_clock_uncertainty -setup -rise_from clk3 -rise_to clk4 0.700",
                "set_clock_uncertainty -setup -add -fall_from clk3 -fall_to clk4 0.100",
                "set_clock_uncertainty -hold -rise_from clk2 -rise_to clk1 0.200",
                "set_clock_uncertainty -hold -rise_from clk2 -fall_to clk1 0.100",
                "set_clock_uncertainty -hold -fall_from clk2 -fall_to clk1 0.300",
                // A value for all edges sets both edges
                "set_clock_uncertainty -hold -fall_from clk4 -fall_to clk3 0.600",
                "set_clock_uncertainty -hold -from clk4 -to clk3 0.400",
                "set_clock_uncertainty -hold -fall_from clk4 -fall_to clk3 0.250",
                "set_clock_uncertainty -setup -from clk4 -to clk3 0.400",
                "set_clock_uncertainty -setup -fall_from clk4 -fall_to clk3 0.500"));
        List<TimingConstraints.UncertaintyDef> defs = tc.getUncertainties();
        Assertions.assertEquals(11, defs.size());
        Assertions.assertEquals(1.2, defs.get(0).value, 1e-12);
        Assertions.assertEquals(TimingConstraints.UncertaintyDef.RISE, defs.get(0).edge);
        Assertions.assertTrue(defs.get(0).setup);
        Assertions.assertFalse(defs.get(0).hold);
        Assertions.assertEquals(2, defs.get(0).from.size());
        Assertions.assertEquals("clk2", defs.get(0).to.get(0).getName());
        Assertions.assertEquals(TimingConstraints.UncertaintyDef.FALL, defs.get(1).edge);
        Assertions.assertEquals(TimingConstraints.UncertaintyDef.ANY_EDGE, defs.get(7).edge);
        Assertions.assertEquals(3, tc.getWarnings().get(
                "set_clock_uncertainty between opposite clock edges (not modeled: same-edge value applies)"));
        Assertions.assertEquals(1, tc.getWarnings().size(), tc.getWarnings().toString());

        ClockNetwork cn = ClockNetwork.build((EDIFNetlist) null, tc);
        UncertaintyModel.Coefficients none = new UncertaintyModel.Coefficients();
        none.systemJitter = 0f;
        UncertaintyModel u = new UncertaintyModel(cn.getClocks(), cn, tc, none);
        int c1 = cn.getClock("clk1").getId();
        int c2 = cn.getClock("clk2").getId();
        int c3 = cn.getClock("clk3").getId();
        int c4 = cn.getClock("clk4").getId();
        // max(1.2 rise, 1.0 fall), whatever the clock lists the values came with
        Assertions.assertEquals(1200f, u.getSetup(c1, c2), 1e-3);
        Assertions.assertEquals(1200f, u.getSetup(c3, c2), 1e-3);
        Assertions.assertEquals(700f, u.getSetup(c3, c4), 1e-3);
        Assertions.assertEquals(300f, u.getHold(c2, c1), 1e-3);
        Assertions.assertEquals(400f, u.getHold(c4, c3), 1e-3);
        Assertions.assertEquals(500f, u.getSetup(c4, c3), 1e-3);
        Assertions.assertEquals(0f, u.getSetup(c2, c1), 1e-3);
    }

    /**
     * Versal clock jitter (Vivado 2026.1, CJ/2 + PE + PJ): a port clock reaching its clock pins through no clock
     * buffer (an out-of-context clock port driving its flops) is a FABRIC clock, with CJ 0.600 ns; through a
     * BUFG, a PORT clock. The system jitter adds to the discrete jitter, the input jitter in quadrature:
     * CJ = sqrt((SJ + DJ)^2 + IJ^2).
     */
    @Test
    public void testVersalFabricClockJitter() {
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        nb.flop("rf", "clk_f");
        nb.connect("clk_f", "clk_f");
        List<String> xdc = new ArrayList<>(Arrays.asList(
                "create_clock -period 1.3 -name clk_f [get_ports clk_f]",
                "create_clock -period 4 -name clk_b [get_ports clk_b]",
                "create_clock -period 8 -name clk_a [get_ports clk_a]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, xdc));
        Clock f = cn.getClock("clk_f");
        Clock b = cn.getClock("clk_b");
        Assertions.assertEquals("FABRIC", f.getKind());
        Assertions.assertEquals("PORT", b.getKind());
        // clk_a only reaches its clock pins through the MMCM and BUFGs
        Assertions.assertEquals("PORT", cn.getClock("clk_a").getKind());

        UncertaintyModel.Coefficients k = new UncertaintyModel.Coefficients();
        k.systemJitterAdds = true;
        k.totalSystemJitter = 0f;
        k.jitter.put("PORT", new float[]{126f, 0f});
        k.jitter.put("FABRIC", new float[]{600f, 0f});
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, xdc);
        UncertaintyModel u = new UncertaintyModel(cn.getClocks(), cn, tc, k);
        Assertions.assertEquals(300f, u.getSetup(f.getId(), f.getId()), 1e-3);
        Assertions.assertEquals(63f, u.getSetup(b.getId(), b.getId()), 1e-3);
        // set_system_jitter 0.05 and set_input_jitter 0.3 (Vivado: CJ 0.176 ns, then 0.348 ns, on clk_b)
        List<String> xdc2 = new ArrayList<>(xdc);
        xdc2.add("set_system_jitter 0.05");
        TimingConstraints tc2 = TimingConstraints.parse(nb.netlist, xdc2);
        UncertaintyModel u2 = new UncertaintyModel(cn.getClocks(), cn, tc2, k);
        Assertions.assertEquals(88f, u2.getSetup(b.getId(), b.getId()), 1e-3);
        Assertions.assertEquals(325f, u2.getSetup(f.getId(), f.getId()), 1e-3);
        xdc2.add("set_input_jitter clk_b 0.3");
        UncertaintyModel u3 = new UncertaintyModel(cn.getClocks(), cn, TimingConstraints.parse(nb.netlist, xdc2), k);
        Assertions.assertEquals(Math.sqrt(176.0 * 176.0 + 300.0 * 300.0) / 2, u3.getSetup(b.getId(), b.getId()), 1e-2);

        // The fitted Versal data: FABRIC 600 ps on both generations, no FABRIC jitter on UltraScale+
        for (String fam : new String[]{"versal_gen1", "versal_gen2"}) {
            UncertaintyModel.Coefficients c = ClockData.get(fam, null).getCoefficients();
            Assertions.assertTrue(c.systemJitterAdds, fam);
            Assertions.assertEquals(600f, c.getDiscreteJitter(f), 1e-3, fam);
        }
        UncertaintyModel.Coefficients usp = ClockData.get("ultrascaleplus", null).getCoefficients();
        Assertions.assertFalse(usp.systemJitterAdds);
        Assertions.assertEquals(0f, usp.getDiscreteJitter(f));
    }

    @Test
    public void testGeneratorJitter() {
        // Two MMCMs fed by clk_a: jitter a + b*T of their outputs, phase errors same/in/diff
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        nb.inst("mmcm2", com.xilinx.rapidwright.design.Unisim.MMCME4_ADV, "CLKFBOUT_MULT_F", "10.0",
                "CLKOUT0_DIVIDE_F", "5.0", "COMPENSATION", "INTERNAL");
        nb.connect("a_ib", "mmcm2/CLKIN1");
        nb.connect("m2o0", "mmcm2/CLKOUT0");
        nb.flop("rm2", "m2o0");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        UncertaintyModel.Coefficients k = new UncertaintyModel.Coefficients();
        k.totalSystemJitter = 71f;
        k.jitter.put("MMCM", new float[]{79f, 4.33f});
        k.phaseErrorSame = 120f;
        k.phaseErrorIn = 90f;
        k.phaseErrorDiff = 40f;
        UncertaintyModel u = new UncertaintyModel(cn.getClocks(), cn, tc, k);
        Clock a = cn.getClock("clk_a");
        Clock o0 = cn.getClock("o0");
        Clock o1 = cn.getClock("o1");
        Clock div = cn.getClock("cdiv");
        Clock m2 = cn.getClock("m2o0");
        Assertions.assertEquals("MMCM", o0.getKind());
        Assertions.assertEquals("MMCM", div.getKind());
        Assertions.assertSame(o1, div.getGeneratorOutput());
        float dj0 = 79f + 4.33f * 4;
        float dj1 = 79f + 4.33f * 5;
        float dj2 = 79f + 4.33f * 4;
        // Same clock: its own jitter, no phase error
        Assertions.assertEquals(Math.sqrt(71 * 71 + dj0 * dj0) / 2, u.getSetup(o0.getId(), o0.getId()), 1e-3);
        // Two outputs of one MMCM: the larger jitter, phase error "same"
        Assertions.assertEquals(Math.sqrt(71 * 71 + dj1 * dj1) / 2 + 120, u.getSetup(o0.getId(), o1.getId()), 1e-3);
        // A divided output keeps its generator output's jitter
        Assertions.assertEquals(Math.sqrt(71 * 71 + dj1 * dj1) / 2 + 120, u.getSetup(o0.getId(), div.getId()), 1e-3);
        // Outputs of two MMCMs: quadrature sum, phase error "diff"
        Assertions.assertEquals(Math.sqrt(71 * 71 + dj0 * dj0 + dj2 * dj2) / 2 + 40, u.getSetup(o0.getId(),
                m2.getId()), 1e-3);
        // An MMCM output and its input clock: phase error "in"
        Assertions.assertEquals(Math.sqrt(71 * 71 + dj0 * dj0) / 2 + 90, u.getSetup(a.getId(), o0.getId()), 1e-3);
        Assertions.assertEquals(0f, u.getHold(o0.getId(), o0.getId()));
    }

    @Test
    public void testJsonData() {
        String json = "{\"family\": \"versal_gen2\", \"version\": 1, \"speedRef\": \"2MP\","
                + "\"classRules\": [{\"intent\": \".*\", \"wire\": \"_PIN$\", \"class\": \"CLK_{K}_PIN\"},"
                + " {\"intent\": \"^NODE_GLOBAL_\", \"wire\": \"\", \"class\": \"CLK_{K}\"},"
                + " {\"intent\": \".*\", \"wire\": \"\", \"class\": \"CLK_{K}\"}],"
                + "\"dataIntents\": [\"NODE_IMUX\"], \"dataClass\": \"CLK_DATA\","
                + "\"clockClasses\": [\"CLK_GCLK\", \"CLK_LEAF\"],"
                + "\"clockCorners\": {\"SLOW_MAX\": {\"base\": {\"CLK_GCLK\": 72.3}, \"tapX\": {\"CLK_GCLK\": 3.9},"
                + " \"tapY\": {\"CLK_LEAF\": 61.0}}},"
                + "\"delayTaps\": {\"^CLK_LEAF_SITES_\\\\d+_CLK_IN$\": {\"SLOW_MAX\": [0, 56, 131], \"FAST_MIN\": [0, 30, 70]}},"
                + "\"speedScalesByCorner\": {\"1LP\": {\"SLOW_MAX\": 1.5}},"
                + "\"leafConfig\": {\"SLOW_MAX\": {\"SLICEL.CLK|C\": 28.0, \"MMCM.CLKFBIN|CLKFBIN\": 39.0}},"
                + "\"leafSpeedScalesByCorner\": {\"1LP\": {\"SLOW_MAX\": 1.42}},"
                + "\"leafConfigByGrade\": {\"1LP\": {\"SLOW_MAX\": {\"SLICEL.CLK|C\": 300.0}}},"
                + "\"clockModifier\": {\"refGrade\": \"2MP\", \"delays\": {\"SLOW_MAX\": [58.0, 134.0]},"
                + " \"speedScalesByCorner\": {\"1LP\": {\"SLOW_MAX\": 1.33}}},"
                + "\"bufferArcs\": {\"SLOW_MAX\": {\"BUFGCE:I->O\": 28.0}},"
                + "\"generators\": {\"compensation\": {\"SLOW_MAX\": {\"MMCM:ZHOLD\": -1269.0, \"XPLL:NONE\": 33.0}}},"
                + "\"uncertainty\": {\"tsj\": 71.0, \"jitter\": {\"MMCM\": [79.0, 4.33]},"
                + " \"phaseError\": {\"same\": 120.0}, \"sameClockHold\": 0.0},"
                + "\"unrouted\": {\"corners\": {\"SLOW_MAX\": {\"a\": 814.2, \"av\": 291.8, \"ah\": 407.3,"
                + " \"abuf\": 375.4, \"aslr\": 0.0}}}}";
        for (String grade : new String[]{null, "1LP"}) {
            ClockData data = ClockDataJson.parse(json, grade);
            ClockDelayParams p = data.getParams();
            float s = grade == null ? 1f : 1.5f;
            int gclk = p.getClassIndex("CLK_GCLK");
            Assertions.assertEquals(72.3f * s, p.getClassDelay(gclk, 0, 0, 0, 0, 0, 1), 1e-3);
            Assertions.assertEquals(3.9f * 2 * s, p.getLeaveDelay(gclk, 0, 2, 0), 1e-3);
            Assertions.assertEquals(61f * 3 * s, p.getLeaveDelay(p.getClassIndex("CLK_LEAF"), 0, 0, 3), 1e-3);
            int t = p.getDelayTapTable("CLK_LEAF_SITES_3_CLK_IN");
            Assertions.assertEquals(131f * s, p.getDelayTapDelay(t, 0, 2), 1e-3);
            Assertions.assertEquals(0f, p.getDelayTapDelay(t, Corner.SLOW_MIN.ordinal(), 2));
            Assertions.assertEquals(grade == null ? 28f : 300f, p.getLeafDelay("SLICEL.CLK|C", 0), 1e-3);
            Assertions.assertEquals(grade == null ? 39f : 39f * 1.42f, p.getLeafDelay("MMCM.CLKFBIN|CLKFBIN", 0), 1e-3);
            Assertions.assertEquals(grade == null ? 134f : 134f * 1.33f, p.getClockModifierDelay(0, 1), 1e-3);
            Assertions.assertEquals(28f, p.getBufferDelay("BUFGCE", "I", "O")[0]);
            Assertions.assertEquals(-1269f, p.getGeneratorDelay("MMCME5", "ZHOLD")[0]);
            Assertions.assertEquals(33f, p.getGeneratorDelay("XPLL", null)[0]);
            Assertions.assertEquals(814.2f + 291.8f, p.getEstimateRegionDelay(0, 0, 1, 0), 1e-3);
            Assertions.assertEquals(71f, data.getCoefficients().totalSystemJitter);
            Assertions.assertEquals(120f, data.getCoefficients().phaseErrorSame);
            ClockNodeClassifier cl = data.getClassifier();
            Assertions.assertEquals("CLK_GCLK", cl.classify(IntentCode.NODE_GLOBAL_GCLK, "GCLK_B_0"));
            Assertions.assertEquals("CLK_LEAF_PIN", cl.classify(IntentCode.NODE_GLOBAL_LEAF, "BUFDIV_LEAF_O_PIN"));
            Assertions.assertEquals(ClockNodeClassifier.DATA, cl.classify(IntentCode.NODE_IMUX, "IMUX_B_0"));
            Assertions.assertEquals("CLK_CLE_CNODE", cl.classify(IntentCode.NODE_CLE_CNODE, "CNODE_W_0"));
        }
    }

    /**
     * The jitter of a Versal port clock interpolates in the logarithm of the period, holds beyond the listed periods,
     * and adds the larger of its row and column extents, which hold beyond their tables
     */
    @Test
    public void testPortJitter() {
        UncertaintyModel.PortJitter j = new UncertaintyModel.PortJitter(new float[]{1f, 10f}, new float[]{50f, 80f},
                new float[]{0f, 6f, 12f}, new float[]{0f, 0f, 20f});
        Assertions.assertEquals(50f, j.getJitter(0.5, 1, 1), 1e-3f);
        Assertions.assertEquals(65f, j.getJitter(Math.sqrt(10), 1, 1), 1e-3f);
        Assertions.assertEquals(80f, j.getJitter(30, 0, 0), 1e-3f);
        Assertions.assertEquals(56f, j.getJitter(1, 2, 2), 1e-3f);
        Assertions.assertEquals(70f, j.getJitter(1, 2, 3), 1e-3f);
        Assertions.assertEquals(70f, j.getJitter(1, 9, 9), 1e-3f);
    }

    /**
     * The tree accessors of a hand-built model: node count, parents, insertion delays of the nodes, the nodes of the
     * clock pins (also of a clock that reaches a pin by another path) and where the inter-SLR common clock delay
     * ends.
     */
    @Test
    public void testTreeAccessors() {
        ClockTree t = new ClockTree();
        int root = t.addNode(ClockTree.NO_PARENT, d(100, 90, 50, 55));
        int a = t.addNode(root, d(200, 180, 100, 110));
        int a1 = t.addNode(a, d(10, 9, 5, 6));
        int b1 = t.addNode(root, d(20, 18, 10, 12));
        int other = t.addNode(ClockTree.NO_PARENT, d(7, 6, 5, 4));
        ClockModel m = ClockModel.of(t, new int[]{a1, b1, -1});
        m.setClockPathNode(0, 1, other);
        Assertions.assertEquals(5, m.getNumTreeNodes());
        Assertions.assertEquals(-1, m.getTreeParent(root));
        Assertions.assertEquals(root, m.getTreeParent(a));
        Assertions.assertEquals(a, m.getTreeParent(a1));
        Assertions.assertEquals(root, m.getTreeParent(b1));
        Assertions.assertEquals(-1, m.getTreeParent(other));
        // Ids outside the tree (the tree's arrays may be longer than its nodes)
        for (int node : new int[]{-1, 5}) {
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> m.getTreeParent(node));
            Assertions.assertThrows(IndexOutOfBoundsException.class, () -> m.getTreeInsertion(node, Corner.SLOW_MAX));
        }
        Assertions.assertEquals(310f, m.getTreeInsertion(a1, Corner.SLOW_MAX));
        Assertions.assertEquals(108f, m.getTreeInsertion(b1, Corner.SLOW_MIN));
        Assertions.assertEquals(5f, m.getTreeInsertion(other, Corner.FAST_MIN));
        Assertions.assertEquals(a1, m.getClockPinNode(0));
        Assertions.assertEquals(b1, m.getClockPinNode(1));
        Assertions.assertEquals(-1, m.getClockPinNode(2));
        // Clock 1 reaches pin 0 by another path; clock 0 and the pin itself (-1) by the pin's
        Assertions.assertEquals(other, m.getClockPinNode(0, 1));
        Assertions.assertEquals(a1, m.getClockPinNode(0, 0));
        Assertions.assertEquals(a1, m.getClockPinNode(0, -1));
        Assertions.assertEquals(b1, m.getClockPinNode(1, 1));
        Assertions.assertEquals(m.getInsertion(0, 1, Corner.SLOW_MAX), m.getTreeInsertion(other, Corner.SLOW_MAX));
        Assertions.assertFalse(m.isInterSlrCommonAtLcaStart());
        m.setInterSlrCommonAtLcaStart(true);
        Assertions.assertTrue(m.isInterSlrCommonAtLcaStart());
    }

    /** The lowest common ancestor of two clock tree nodes, from the public tree accessors (-1 if none) */
    private static int getLca(ClockModel m, int a, int b) {
        Set<Integer> path = new HashSet<>();
        for (int n = a; n >= 0; n = m.getTreeParent(n)) {
            path.add(n);
        }
        for (int n = b; n >= 0; n = m.getTreeParent(n)) {
            if (path.contains(n)) {
                return n;
            }
        }
        return -1;
    }

    /**
     * The tree accessors of models built from the clock data of routed designs (UltraScale+, Versal) hold what the
     * other getters of the model give: the insertion delays of the clock pins (for every clock), and the CRPR
     * credits (insertion delay differences at the clock pins' common node, found through the parents, or at its
     * parent for some routing nodes on UltraScale+).
     */
    @ParameterizedTest
    @CsvSource({"picoblaze_partial.dcp,true", "versal_slr_crossing.dcp,false"})
    public void testTreeAccessorsOfBuiltModel(String dcp, boolean commonAtLcaStart) {
        Design d = RapidWrightDCP.loadDCP(dcp);
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));
        ClockModel m = ClockModel.build(d, cn, ClockData.get(d.getDevice(), null).createNodeDelayModel());
        Assertions.assertEquals(commonAtLcaStart, m.isInterSlrCommonAtLcaStart());
        int n = m.getNumTreeNodes();
        Assertions.assertTrue(n > 1, Integer.toString(n));
        for (int node = 0; node < n; node++) {
            Assertions.assertTrue(m.getTreeParent(node) < node);
        }
        int numPins = m.getNumClockPins();
        Assertions.assertTrue(numPins > 10);
        int withNode = 0;
        for (int i = 0; i < numPins; i++) {
            int node = m.getClockPinNode(i);
            Assertions.assertTrue(node >= -1 && node < n);
            withNode += node >= 0 ? 1 : 0;
            Assertions.assertEquals(node, m.getClockPinNode(i, -1));
            for (int k = -1; k < cn.getNumClocks(); k++) {
                int nk = m.getClockPinNode(i, k);
                for (Corner c : Corner.values()) {
                    float expected = k < 0 ? m.getInsertion(i, c) : m.getInsertion(i, k, c);
                    Assertions.assertEquals(expected, nk < 0 ? 0f : m.getTreeInsertion(nk, c));
                }
            }
        }
        Assertions.assertTrue(withNode > 0);
        assertPathNodesOfClockEdges(cn, m);
        int step = Math.max(1, numPins / 60);
        int atStart = 0;
        for (int i = 0; i < numPins; i += step) {
            for (int j = 1; j < numPins; j += step) {
                int lca = getLca(m, m.getClockPinNode(i), m.getClockPinNode(j));
                int cn0 = m.getTreeCrprNode(m.getClockPinNode(i), m.getClockPinNode(j));
                // The credit node is the LCA or, on UltraScale+ (routing nodes credited at their start), its parent
                if (cn0 != lca) {
                    Assertions.assertTrue(commonAtLcaStart, i + " " + j);
                    Assertions.assertEquals(m.getTreeParent(lca), cn0, i + " " + j);
                    atStart++;
                }
                float setup = cn0 < 0 ? 0f : m.getTreeInsertion(cn0, Corner.SLOW_MAX)
                        - m.getTreeInsertion(cn0, Corner.SLOW_MIN);
                float hold = cn0 < 0 ? 0f : m.getTreeInsertion(cn0, Corner.FAST_MAX)
                        - m.getTreeInsertion(cn0, Corner.FAST_MIN);
                Assertions.assertEquals(setup, m.getSetupCrpr(i, j, -1), i + " " + j);
                Assertions.assertEquals(hold, m.getHoldCrpr(i, j, -1), i + " " + j);
            }
        }
        Assertions.assertEquals(commonAtLcaStart, atStart > 0);
    }

    /** Only the clocks of a pin's clock edges can have a path node of their own; the others have the pin's */
    private static void assertPathNodesOfClockEdges(ClockNetwork cn, ClockModel m) {
        for (int i = 0; i < m.getNumClockPins(); i++) {
            Set<Integer> clocks = new HashSet<>();
            for (int e : cn.getClockPinEdges(i)) {
                clocks.add(e >> 1);
            }
            for (int k = 0; k < cn.getNumClocks(); k++) {
                if (!clocks.contains(k)) {
                    Assertions.assertEquals(m.getClockPinNode(i), m.getClockPinNode(i, k), cn.getClockPin(i) + " " + k);
                }
            }
        }
    }

    /**
     * A clock mux: each of its two clocks reaches the flops behind it by its own path, whose node holds its
     * insertion delay; a third clock, which does not reach them, gets the pins' node.
     */
    @Test
    public void testClockPinNodesOfMux() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        nb.inst("ib", Unisim.IBUF);
        nb.inst("bg", Unisim.BUFG);
        nb.inst("bc", Unisim.BUFG);
        nb.inst("mux", Unisim.BUFGCTRL);
        nb.connect("clk_a", "clk_a", "ib/I");
        nb.connect("a", "ib/O", "mux/I0");
        nb.connect("clk_b", "clk_b", "bg/I");
        nb.connect("b", "bg/O", "mux/I1");
        nb.connect("m", "mux/O");
        nb.flop("r1", "m");
        nb.flop("r2", "m");
        nb.connect("clk_c", "clk_c", "bc/I");
        nb.connect("c", "bc/O");
        nb.flop("r3", "c");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name ka [get_ports clk_a]",
                "create_clock -period 10 -name kb [get_ports clk_b]",
                "create_clock -period 12 -name kc [get_ports clk_c]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        ClockDelayParams p = new ClockDelayParams();
        p.setBufferDelay("IBUF", d(500, 450, 300, 330));
        p.setBufferDelay("BUFG", d(100, 90, 60, 66));
        p.setBufferDelay("BUFGCTRL", d(30, 27, 15, 17));
        ClockModel m = ClockModel.build(null, cn, new ClockNodeDelayModel(
                ClockNodeClassifier.forSeries(Series.UltraScalePlus), p));
        int r1 = cn.getClockPinIndex("r1/C");
        int r3 = cn.getClockPinIndex("r3/C");
        int ka = cn.getClock("ka").getId();
        int kb = cn.getClock("kb").getId();
        int kc = cn.getClock("kc").getId();
        int na = m.getClockPinNode(r1, ka);
        int nb1 = m.getClockPinNode(r1, kb);
        Assertions.assertNotEquals(na, nb1);
        Assertions.assertEquals(500 + 30, m.getTreeInsertion(na, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(100 + 30, m.getTreeInsertion(nb1, Corner.SLOW_MAX), 1e-3);
        Assertions.assertEquals(m.getClockPinNode(r1), m.getClockPinNode(r1, kc));
        Assertions.assertEquals(m.getClockPinNode(r3), m.getClockPinNode(r3, ka));
        Assertions.assertEquals(100f, m.getTreeInsertion(m.getClockPinNode(r3), Corner.SLOW_MAX), 1e-3);
        assertPathNodesOfClockEdges(cn, m);
    }

    /**
     * The node classes of the clock node delay model: general interconnect nodes of UltraScale+ clock routes are
     * data nodes, timed by the data interconnect model (the delays of the family's estimators at each corner);
     * the nodes of routed clock nets have clock classes, whose delays do not decrease with the number of branches.
     * The general interconnect nodes of Versal gen2 have a clock class of their own.
     */
    @Test
    public void testNodeDelayModelClasses() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        ClockNodeDelayModel m = ClockData.get(d.getDevice(), null).createNodeDelayModel();
        DelayEstimator[] est = new DelayEstimator[Corner.values().length];
        for (Corner c : Corner.values()) {
            est[c.ordinal()] = TimingFamilies.of(d.getDevice()).createDelayEstimator(d.getDevice(), null, c, true);
        }
        Set<String> dataIntents = new HashSet<>(Arrays.asList("NODE_SINGLE", "NODE_DOUBLE", "NODE_HQUAD",
                "NODE_VQUAD", "NODE_HLONG", "NODE_VLONG"));
        float[] out = new float[Corner.values().length];
        float[] out4 = new float[Corner.values().length];
        int numData = 0;
        int numClock = 0;
        int numPositive = 0;
        for (Net net : d.getNets()) {
            for (PIP pip : net.getPIPs()) {
                Node node = pip.getEndNode();
                if (net.isClockNet()) {
                    if (!m.hasClockClass(node)) {
                        continue;
                    }
                    Assertions.assertFalse(m.isDataNode(node), node.toString());
                    m.getDelays(node, 1, out);
                    m.getDelays(node, 4, out4);
                    for (Corner c : Corner.values()) {
                        Assertions.assertTrue(out4[c.ordinal()] >= out[c.ordinal()], node + " " + c);
                    }
                    numPositive += out[Corner.SLOW_MAX.ordinal()] > 0 ? 1 : 0;
                    numClock++;
                } else if (numData < 200 && dataIntents.contains(node.getIntentCode().name())) {
                    Assertions.assertTrue(m.isDataNode(node), node.toString());
                    Assertions.assertFalse(m.hasClockClass(node), node.toString());
                    m.getDelays(node, 1, out);
                    for (Corner c : Corner.values()) {
                        Assertions.assertEquals((float) est[c.ordinal()].getDelayOf(node), out[c.ordinal()],
                                node + " " + c);
                    }
                    numData++;
                }
            }
        }
        Assertions.assertTrue(numClock > 0);
        Assertions.assertTrue(numPositive > 0);
        Assertions.assertTrue(numData > 20, Integer.toString(numData));

        // Versal gen2: general interconnect has a clock class
        Device gen2 = Device.getDevice("xc2ve3858");
        ClockNodeDelayModel m2 = ClockData.get(gen2, null).createNodeDelayModel();
        int numHSingle = 0;
        for (Tile t : gen2.getAllTiles()) {
            if (!t.getName().startsWith("INT_X")) {
                continue;
            }
            for (int w = 0; w < t.getWireCount(); w++) {
                Node node = Node.getNode(t, w);
                if (node == null || node.getIntentCode() != IntentCode.NODE_HSINGLE) {
                    continue;
                }
                Assertions.assertFalse(m2.isDataNode(node), node.toString());
                Assertions.assertTrue(m2.hasClockClass(node), node.toString());
                numHSingle++;
            }
            if (numHSingle > 20) {
                break;
            }
        }
        Assertions.assertTrue(numHSingle > 0);
    }
}
