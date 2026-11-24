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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusDelayEstimator;

public class TestClockData {

    @Test
    public void testFamilies() {
        Assertions.assertEquals("ultrascaleplus", ClockData.getFamilyId(Device.getDevice("xcvu3p")));
        Assertions.assertEquals("versal_gen1", ClockData.getFamilyId(Device.getDevice("xcvc1902")));
        Assertions.assertEquals("versal_gen2", ClockData.getFamilyId(Device.getDevice("xc2ve3858")));
        // No clock data for UltraScale devices, which have no timing model
        Assertions.assertNull(ClockData.getFamilyId(Device.getDevice(Device.KCU105)));
        ClockData us = ClockData.get(Device.getDevice("xcvu3p"), SpeedGrade.of("2"));
        Assertions.assertEquals("usplus", us.getFamily());
        ClockDelayParams p = us.getParams();
        // Generator compensation, buffer arcs, delay taps, leaf configuration, uncertainty, inter-SLR
        Assertions.assertEquals(-1269f, p.getGeneratorDelay("MMCME4_ADV", "ZHOLD")[Corner.SLOW_MAX.ordinal()]);
        Assertions.assertEquals(28f, p.getBufferDelay("BUFGCE", "I", "O")[Corner.SLOW_MAX.ordinal()]);
        int leaf = p.getDelayTapTable("CLK_LEAF_SITES_12_CLK_IN");
        Assertions.assertTrue(leaf >= 0);
        Assertions.assertEquals(56f, p.getDelayTapDelay(leaf, Corner.SLOW_MAX.ordinal(), 1));
        Assertions.assertTrue(p.getDelayTapTable("CLK_BUFCE_ROW_FSR_3_CLK_IN") >= 0);
        Assertions.assertEquals(28f, p.getLeafDelay("SLICEL.CLK1|C", Corner.SLOW_MAX.ordinal()));
        Assertions.assertEquals(39f, p.getLeafDelay("MMCM.CLKFBIN|CLKFBIN", Corner.SLOW_MAX.ordinal()));
        Assertions.assertEquals(71f, us.getCoefficients().totalSystemJitter);
        Assertions.assertArrayEquals(new float[]{79f, 4.33f}, us.getCoefficients().jitter.get("MMCM"));
        Assertions.assertEquals(120f, us.getCoefficients().phaseErrorSame);
        Assertions.assertEquals(0.15f, us.getInterSlrProrate());
        // US+ general interconnect in clock routes is timed by the data delay estimator
        Assertions.assertEquals(ClockNodeClassifier.DATA, us.getClassifier().classify(IntentCode.NODE_SINGLE, "EE1_E_BEG0"));
        Assertions.assertEquals("CLK_LEAF_IN", us.getClassifier().classify(IntentCode.INTENT_DEFAULT,
                "CLK_LEAF_SITES_15_CLK_IN"));

        ClockData g1 = ClockData.get(Device.getDevice("xcvc1902"), SpeedGrade.of("2MP"));
        Assertions.assertTrue(g1.getParams().hasClockModifier());
        Assertions.assertEquals(413f, g1.getParams().getGeneratorDelay("XPLL", null)[Corner.SLOW_MAX.ordinal()]);
        Assertions.assertEquals(0f, g1.getCoefficients().totalSystemJitter);
        // The modifier's reference grade is 2MHP; 2MP has no scale (1)
        Assertions.assertEquals(97f, g1.getParams().getClockModifierDelay(Corner.SLOW_MAX.ordinal(), 0));
        Assertions.assertEquals(0.1f, g1.getInterSlrProrate());
        // Per-output generator compensation, zero-delay connections, IRI site taps
        Assertions.assertEquals(-46f, g1.getParams().getGeneratorDelay("XPLL", null, "CLKOUTPHY", "DIV1")
                [Corner.SLOW_MAX.ordinal()]);
        Assertions.assertTrue(g1.getParams().isZeroDelayConnection("PS9", "PMCRCLKCLK_0_", "BUFG_PS"));
        Assertions.assertTrue(g1.getParams().getSiteTapKeys().contains("IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE"));
        // US+ dedicated clock input routes, input buffers and load pin deltas
        Assertions.assertEquals(280f * p.getRouteScale(0), p.getDedicatedRoute("HPIOB_M.I->BUFGCE.CLK_IN")
                [Corner.SLOW_MAX.ordinal()], 1e-3);
        Assertions.assertEquals(418f, p.getInputBuffer("HPIOB:INBUF")[Corner.SLOW_MAX.ordinal()]);
        Assertions.assertEquals(100f, p.getInputBufferPortNet("DIFFINBUF")[Corner.SLOW_MAX.ordinal()]);
        Assertions.assertEquals(40.8f, p.getLoadPinDelta("BUFGCE.CLK_IN")[Corner.SLOW_MAX.ordinal()], 1e-3);

        // gen2: a separate fit for the L grades; its general interconnect nodes have a class
        ClockData m = ClockData.get("versal_gen2", "2MP");
        ClockData l = ClockData.get("versal_gen2", "2LP");
        Assertions.assertEquals(68f * m.getParams().getRouteScale(0),
                m.getParams().getDedicatedRoute("PS11.PMCRCLKCLK_0_->BUFG_PS_ULVT.I")[0], 1e-3);
        // Unrouted port -> clock buffer inputs per grade; the L grades' own load pin deltas; per-device routes
        Assertions.assertEquals(68f, m.getParams().getUnroutedInput()[Corner.SLOW_MAX.ordinal()]);
        Assertions.assertEquals(74f, ClockData.get("versal_gen2", "1LXP").getParams().getUnroutedInput()
                [Corner.SLOW_MAX.ordinal()]);
        float[] mb = m.getParams().getLoadPinDelta("RAMB36.CLKBWRCLKU");
        Assertions.assertTrue(mb == null || mb[Corner.SLOW_MAX.ordinal()] == 0f);
        Assertions.assertEquals(112.9f, l.getParams().getLoadPinDelta("RAMB36.CLKBWRCLKU")[Corner.SLOW_MAX.ordinal()],
                1e-3);
        Assertions.assertEquals(47.4f, m.getParams().getLoadPinDelta("DSP58.CLK")[Corner.SLOW_MAX.ordinal()], 1e-3);
        Assertions.assertEquals(14.8f, l.getParams().getLoadPinDelta("DSP58.CLK")[Corner.SLOW_MAX.ordinal()], 1e-3);
        String x5 = "X5PHIO_XCVR.XCV2PHYPLL_RX_OUT_M->X5PLL.CLKIN@X5PLL_X0Y0";
        Assertions.assertEquals(338f * m.getParams().getRouteScale(0),
                m.getParams().getDeviceDedicatedRoute("xc2vm3558", x5)[Corner.SLOW_MAX.ordinal()], 1e-3);
        Assertions.assertEquals(603f * m.getParams().getRouteScale(0),
                m.getParams().getDeviceDedicatedRoute("xc2ve3858", x5)[Corner.SLOW_MAX.ordinal()], 1e-3);
        Assertions.assertNull(m.getParams().getDeviceDedicatedRoute("xc2ve3358", x5));
        Assertions.assertEquals(432f, g1.getParams().getGeneratorDelay("XPLL", null, "CLKOUT1", null)
                [Corner.SLOW_MAX.ordinal()]);
        // The IRI site taps are at their reference grade 1LXP
        Assertions.assertEquals(264f, ClockData.get("versal_gen2", "1LXP").getParams()
                .getSiteTapDelay("IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE", Corner.SLOW_MAX.ordinal(), 3));
        int bm = m.getParams().getClassIndex("CLK_BUFG");
        int bl = l.getParams().getClassIndex("CLK_BUFG");
        Assertions.assertEquals(3.69f * m.getParams().getRouteScale(0),
                m.getParams().getClassDelay(bm, 0, 0, 0, 0, 0, 1), 1e-3);
        float lScale = l.getParams().getRouteScale(0);
        Assertions.assertEquals(19.48f * lScale, l.getParams().getClassDelay(bl, 0, 0, 0, 0, 0, 1), 1e-3);
        Assertions.assertEquals("CLK_DATA", m.getClassifier().classify(IntentCode.NODE_HSINGLE, "INT_SDQ_E0"));
        Assertions.assertEquals(132.5f, m.getCoefficients().jitter.get("MMCM")[0]);
    }

    /**
     * Every clock pin of an UltraScale+ block RAM or FIFO has a leaf delay (from its site pin to the cell pin, by
     * placed site type), at the reference grade and at -3: those of both halves of a RAMB36 site, of a RAMB18E2 in
     * the upper (RAMB181) as well as the lower RAMB18 site, and of FIFO18E2 and FIFO36E2. The upper RAMB18 has the
     * leaf delays of the lower one; a FIFO's clocks have longer ones than a block RAM's at the max corners.
     */
    @Test
    public void testBlockRamAndFifoLeafDelays() {
        String[] keys = {"RAMB180.CLKAL|CLKARDCLK", "RAMB180.CLKBL|CLKBWRCLK", "RAMB181.CLKAU|CLKARDCLK",
            "RAMB181.CLKBU|CLKBWRCLK", "RAMB36.CLKAL|CLKARDCLK", "RAMB36.CLKAU|CLKARDCLK", "RAMB36.CLKBL|CLKBWRCLK",
            "RAMB36.CLKBU|CLKBWRCLK", "FIFO18_0.CLKAL|RDCLK", "FIFO18_0.CLKBL|WRCLK", "FIFO36.CLKAL|RDCLK",
            "FIFO36.CLKAU|RDCLK", "FIFO36.CLKBL|WRCLK", "FIFO36.CLKBU|WRCLK"};
        for (String grade : new String[]{"2", "3"}) {
            ClockDelayParams p = ClockData.get("ultrascaleplus", grade).getParams();
            for (String key : keys) {
                Assertions.assertTrue(p.hasLeafConfig(key), grade + " " + key);
                for (Corner c : Corner.values()) {
                    Assertions.assertTrue(p.getLeafDelay(key, c.ordinal()) > 20f, grade + " " + key + " " + c);
                }
            }
            for (Corner c : Corner.values()) {
                int i = c.ordinal();
                Assertions.assertEquals(p.getLeafDelay("RAMB180.CLKAL|CLKARDCLK", i),
                        p.getLeafDelay("RAMB181.CLKAU|CLKARDCLK", i), 3f, grade + " " + c);
                Assertions.assertEquals(p.getLeafDelay("RAMB180.CLKBL|CLKBWRCLK", i),
                        p.getLeafDelay("RAMB181.CLKBU|CLKBWRCLK", i), 3f, grade + " " + c);
                if (c == Corner.SLOW_MAX || c == Corner.FAST_MAX) {
                    Assertions.assertTrue(p.getLeafDelay("FIFO18_0.CLKBL|WRCLK", i)
                            > p.getLeafDelay("RAMB180.CLKBL|CLKBWRCLK", i) + 5f, grade + " " + c);
                }
            }
        }
        // Measured at -2 and -3: -3 has delays of its own (the -3 FIFO delays are not those of -2 scaled)
        Assertions.assertEquals(90f, ClockData.get("ultrascaleplus", "2").getParams().getLeafDelay("FIFO18_0.CLKBL|WRCLK",
                Corner.SLOW_MAX.ordinal()));
        ClockDelayParams p3 = ClockData.get("ultrascaleplus", "3").getParams();
        String[] measured3 = {"RAMB181.CLKAU|CLKARDCLK", "RAMB181.CLKBU|CLKBWRCLK", "FIFO18_0.CLKAL|RDCLK",
            "FIFO18_0.CLKBL|WRCLK", "FIFO36.CLKAL|RDCLK", "FIFO36.CLKAU|RDCLK", "FIFO36.CLKBL|WRCLK",
            "FIFO36.CLKBU|WRCLK"};
        float[] slowMax3 = {68f, 66f, 80f, 86f, 80f, 80f, 86f, 86f};
        for (int k = 0; k < measured3.length; k++) {
            Assertions.assertEquals(slowMax3[k], p3.getLeafDelay(measured3[k], Corner.SLOW_MAX.ordinal()),
                    measured3[k]);
        }
    }

    /**
     * Speed grades: the -3 UltraScale+ clocks are faster at the slow corner; a grade without data takes that of
     * the nearest listed grade. The CLE clock modifier has the delays of the site type (SLICEL, SLICEM).
     */
    @Test
    public void testGradesAndClockModifier() {
        ClockDelayParams us3 = ClockData.get("ultrascaleplus", "3").getParams();
        Assertions.assertEquals(0.8958f, us3.getRouteScale(Corner.SLOW_MIN.ordinal()), 1e-4);
        Assertions.assertEquals(1f, us3.getRouteScale(Corner.FAST_MIN.ordinal()), 1e-4);
        Assertions.assertEquals(ClockData.get("ultrascaleplus", "2L").getParams().getRouteScale(Corner.SLOW_MIN.ordinal()),
                ClockData.get("ultrascaleplus", "2LX").getParams().getRouteScale(Corner.SLOW_MIN.ordinal()));
        ClockDelayParams g1 = ClockData.get("versal_gen1", "2MHP").getParams();
        Assertions.assertEquals(90f, g1.getClockModifierDelay("SLICEM", Corner.SLOW_MAX.ordinal(), 0));
        Assertions.assertEquals(79f, g1.getClockModifierDelay("SLICEM", Corner.SLOW_MIN.ordinal(), 0));
        Assertions.assertEquals(97f, g1.getClockModifierDelay("SLICEL", Corner.SLOW_MAX.ordinal(), 0));
        ClockDelayParams g2 = ClockData.get("versal_gen2", "2MP").getParams();
        Assertions.assertEquals(58f, g2.getClockModifierDelay("SLICEM", Corner.SLOW_MAX.ordinal(), 0));
        Assertions.assertEquals(64f, g2.getClockModifierDelay("SLICEL", Corner.SLOW_MAX.ordinal(), 0));
    }

    /**
     * The general interconnect nodes of UltraScale+ clock routes get the unmasked delays of the fitted
     * estimators: U-turn nodes, which the router's estimators mask with a prohibitive delay, get their
     * real delay in the clock insertion.
     */
    @Test
    public void testDataNodesNotMasked() {
        Device device = Device.getDevice("xcvu3p");
        ClockDelayParams.DataNodeDelay d = ClockData.get(device, SpeedGrade.of("2")).getParams()
                .getDataNodeDelay();
        DelayEstimator masked = TimingFamilies.of(device).createDelayEstimator(device, null, Corner.SLOW_MAX, false);
        int numUTurn = 0;
        for (Tile t : new Tile[]{device.getTile("INT_X0Y60"), device.getTile("INT_X1Y60")}) {
            for (int w = 0; w < t.getWireCount(); w++) {
                Node n = Node.getNode(t, w);
                if (n == null || !n.getTile().equals(t)) continue;
                if (masked.getDelayOf(n) == UltraScalePlusDelayEstimator.UTURN_MASK_DELAY) numUTurn++;
                for (Corner c : Corner.values()) {
                    Assertions.assertTrue(d.getDelay(n, c) < UltraScalePlusDelayEstimator.UTURN_MASK_DELAY,
                            n + " " + c);
                }
            }
        }
        Assertions.assertTrue(numUTurn > 0, "no U-turn node");
    }

    /**
     * The directory of the fits' clock.json files (<dir>/<family>/clock.json), from the system property
     * {@value #FITS_DIR_PROPERTY}; testSameAsJson is skipped without it. The Gradle test JVM gets it from
     * e.g. {@code JAVA_TOOL_OPTIONS=-Drapidwright.timing.fitsDir=<dir>}.
     */
    public static final String FITS_DIR_PROPERTY = "rapidwright.timing.fitsDir";

    /** The generated classes hold the same data as the fits' clock.json (opt-in, see {@link #FITS_DIR_PROPERTY}) */
    @ParameterizedTest
    @CsvSource({"ultrascaleplus,", "ultrascaleplus,2L", "ultrascaleplus,3", "versal_gen1,2MP", "versal_gen1,1LP", "versal_gen1,2MHP",
        "versal_gen2,2MP", "versal_gen2,1LP",
        "versal_gen2,2LHP"})
    public void testSameAsJson(String family, String grade) throws Exception {
        String dir = System.getProperty(FITS_DIR_PROPERTY);
        Assumptions.assumeTrue(dir != null && !dir.isEmpty(), FITS_DIR_PROPERTY + " not set");
        ClockData a = ClockData.get(family, grade);
        // The folders of the fits have the family names of their data
        Path json = Paths.get(dir, a.getFamily(), "clock.json");
        Assertions.assertTrue(Files.exists(json), json + " not found");
        ClockData b = ClockDataJson.read(json, grade);
        ClockDelayParams pa = a.getParams();
        ClockDelayParams pb = b.getParams();
        Assertions.assertEquals(pb.getClassNames(), pa.getClassNames());
        for (int c = 0; c < ClockDelayParams.NUM_CORNERS; c++) {
            Assertions.assertEquals(pb.getRouteScale(c), pa.getRouteScale(c));
            for (int k = 0; k < pa.getClassNames().size(); k++) {
                for (ClockData.Term t : ClockData.Term.values()) {
                    Assertions.assertEquals(pb.getClassTerm(k, c, t), pa.getClassTerm(k, c, t));
                }
            }
            for (int t = 0; t < pa.getNumDelayTapTables(); t++) {
                for (int i = 0; i < 16; i++) {
                    Assertions.assertEquals(pb.getDelayTapDelay(t, c, i), pa.getDelayTapDelay(t, c, i));
                }
            }
            for (String key : new String[]{"SLICEL.CLK1|C", "SLICEL.CLK|C", "MMCM.CLKFBIN|CLKFBIN", "RAMB36.CLKARDCLKL|CLKARDCLKL"}) {
                Assertions.assertEquals(pb.getLeafDelay(key, c), pa.getLeafDelay(key, c));
            }
            for (int tap = 0; tap < 16; tap++) {
                Assertions.assertEquals(pb.getClockModifierDelay(c, tap), pa.getClockModifierDelay(c, tap));
            }
            for (String g : new String[]{"MMCME4_ADV:ZHOLD", "MMCME5:INTERNAL", "XPLL:", "PLLE4_ADV:INTERNAL"}) {
                String[] kc = g.split(":", -1);
                Assertions.assertArrayEquals(pb.getGeneratorDelay(kc[0], kc[1].isEmpty() ? null : kc[1]),
                        pa.getGeneratorDelay(kc[0], kc[1].isEmpty() ? null : kc[1]));
            }
            Assertions.assertEquals(pb.getEstimateDelay(c, 1, 2, 0, 1, false), pa.getEstimateDelay(c, 1, 2, 0, 1, false));
            Assertions.assertEquals(pb.getEstimateDelay(c, 1, 2, 0, 1, true), pa.getEstimateDelay(c, 1, 2, 0, 1, true));
        }
        Assertions.assertEquals(b.getCoefficients().totalSystemJitter, a.getCoefficients().totalSystemJitter);
        Assertions.assertEquals(b.getCoefficients().jitter.keySet(), a.getCoefficients().jitter.keySet());
        Assertions.assertEquals(b.getInterSlrProrate(), a.getInterSlrProrate());
    }

    /**
     * Lists the differences between two objects of the clock data: the paths of the fields, array elements and map
     * entries whose values differ, down to numbers and strings, recursing into the objects of the timing packages.
     * Functional objects (e.g. the delays of general interconnect nodes) are skipped; patterns compare by regex.
     */
    private static void diff(Object a, Object b, String path, List<String> out) throws IllegalAccessException {
        if (a == b || isFunction(a) && isFunction(b)) {
            return;
        }
        if (a == null || b == null || a.getClass() != b.getClass()) {
            out.add(path);
            return;
        }
        Class<?> c = a.getClass();
        if (c.isArray()) {
            int n = Math.max(Array.getLength(a), Array.getLength(b));
            for (int i = 0; i < n; i++) {
                if (i >= Array.getLength(a) || i >= Array.getLength(b)) {
                    out.add(path + "[" + i + "]");
                } else {
                    diff(Array.get(a, i), Array.get(b, i), path + "[" + i + "]", out);
                }
            }
        } else if (a instanceof Map) {
            TreeSet<String> keys = new TreeSet<>();
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            for (Object k : ma.keySet()) {
                keys.add(String.valueOf(k));
            }
            for (Object k : mb.keySet()) {
                keys.add(String.valueOf(k));
            }
            for (String k : keys) {
                diff(ma.get(k), mb.get(k), path + "{" + k + "}", out);
            }
        } else if (a instanceof Collection) {
            diff(((Collection<?>) a).toArray(), ((Collection<?>) b).toArray(), path, out);
        } else if (a instanceof Pattern) {
            if (!((Pattern) a).pattern().equals(((Pattern) b).pattern())) {
                out.add(path);
            }
        } else if (c.getName().startsWith("com.xilinx.rapidwright.timing.")) {
            for (; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    diff(f.get(a), f.get(b), path + "." + f.getName(), out);
                }
            }
        } else if (!a.equals(b)) {
            out.add(path);
        }
    }

    /** Whether an object is a lambda, e.g. the delays of general interconnect nodes */
    private static boolean isFunction(Object o) {
        return o != null && (o.getClass().isSynthetic() || o.getClass().getName().contains("$$Lambda"));
    }

    /** The differences of two clock data, but for their record of overrides */
    private static List<String> diff(ClockData a, ClockData b) {
        List<String> out = new ArrayList<>();
        try {
            diff(a, b, "", out);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        out.removeIf(path -> path.startsWith(".overrides"));
        return out;
    }

    /** The data tree of the clock data of a timing data set (shared by all the clock data of the set) */
    private static Map<String, Object> getTree(String family) {
        return ClockData.getTree(family);
    }

    /** A deep copy of a data tree: its maps and lists copied, its leaves (strings, numbers) shared */
    private static Object copyTree(Object o) {
        if (o instanceof Map) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                m.put(e.getKey(), copyTree(e.getValue()));
            }
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object v : (List<?>) o) {
                l.add(copyTree(v));
            }
            return l;
        }
        return o;
    }

    /**
     * Clock data with overrides is a copy that differs from the data in exactly the overridden terms: class terms
     * (including those of other corners kept), load pin deltas (a new pin's other corners 0), the discrete
     * jitter of a kind. The data that ClockData.get() returned (the cached instance itself for Versal gen2, a copy
     * of it for the others), the shared data tree and every later get() are unchanged: field by field, bit for bit,
     * equal to a fresh decode, and the tree equal to a deep copy taken before. The copy keeps the data's delays of
     * general interconnect nodes. Overrides of a copy that has overrides keep the copy's, but for the terms they
     * set again.
     */
    @ParameterizedTest
    @CsvSource({"xcvu3p,2", "xcvc1902,2MP", "xc2ve3858,2MP"})
    public void testWithOverrides(String deviceName, String grade) {
        Device device = Device.getDevice(deviceName);
        ClockData before = ClockData.get(device, SpeedGrade.of(grade));
        String family = ClockData.getFamilyId(device);
        ClockData fresh = ClockData.get(family, before.getSpeedGrade());
        // (The data that get() returns differs from a fresh decode in its general interconnect node delays only)
        Assertions.assertEquals(Arrays.asList(), diff(fresh, before));
        Object treeBefore = copyTree(getTree(family));
        // get() hands out the cached instance itself where the data times general interconnect with a class of its own
        Assertions.assertEquals(family.equals("versal_gen2"), before == ClockData.get(device, SpeedGrade.of(grade)));
        ClockDelayParams p = before.getParams();
        // The first class with a base delay
        String nodeClass = null;
        for (String name : new TreeSet<>(p.getClassNames())) {
            if (nodeClass == null && p.getClassTerm(p.getClassIndex(name), 0, ClockData.Term.BASE) != 0f) {
                nodeClass = name;
            }
        }
        Assertions.assertNotNull(nodeClass);
        int cls = p.getClassIndex(nodeClass);
        float base = p.getClassTerm(cls, Corner.SLOW_MAX.ordinal(), ClockData.Term.BASE);
        float[] port = before.getCoefficients().jitter.get("PORT");
        // The first load pin (of a known site type) with a delta of the data at every corner
        String pinKey = null;
        Set<String> siteTypes = new HashSet<>();
        for (SiteTypeEnum t : SiteTypeEnum.values()) {
            siteTypes.add(t.name());
        }
        for (String key : new TreeSet<>(p.getLoadPinKeys())) {
            float[] delta = p.getLoadPinDelta(key);
            boolean all = siteTypes.contains(key.substring(0, key.indexOf('.')));
            for (float v : delta) {
                all &= v != 0f && v != -12.5f;
            }
            if (pinKey == null && all) {
                pinKey = key;
            }
        }
        Assertions.assertNotNull(pinKey);
        float[] oldDelta = p.getLoadPinDelta(pinKey).clone();
        String[] typeAndPin = pinKey.split("\\.", 2);

        ClockData.Overrides o = new ClockData.Overrides()
                .setClassTerm(nodeClass, Corner.SLOW_MAX, ClockData.Term.BASE, base + 10f)
                .setClassTerm(nodeClass, Corner.FAST_MIN, ClockData.Term.FANOUT, 3f)
                .setLoadPinDelta(SiteTypeEnum.valueOf(typeAndPin[0]), typeAndPin[1], Corner.SLOW_MIN, -12.5f)
                .setDiscreteJitter("PORT", 11f, 0.5f);
        ClockData copy = before.withOverrides(o);
        Assertions.assertNotSame(before, copy);
        Assertions.assertSame(p.getDataNodeDelay(), copy.getParams().getDataNodeDelay());
        // Later changes to the overrides do not reach the copy
        o.setDiscreteJitter("PORT", 99f, 0f);
        int fanout = ClockData.Term.FANOUT.ordinal();
        int baseTerm = ClockData.Term.BASE.ordinal();
        List<String> expected = new ArrayList<>(Arrays.asList(
                ".params.classTerms[" + baseTerm + "][" + Corner.SLOW_MAX.ordinal() + "][" + cls + "]",
                ".coefficients.jitter{PORT}[0]", ".coefficients.jitter{PORT}[1]"));
        if (p.getClassTerm(cls, Corner.FAST_MIN.ordinal(), ClockData.Term.FANOUT) != 3f) {
            expected.add(".params.classTerms[" + fanout + "][" + Corner.FAST_MIN.ordinal() + "][" + cls + "]");
        }
        expected.add(".params.loadPinDelta{" + pinKey + "}[" + Corner.SLOW_MIN.ordinal() + "]");
        if (port == null) {
            expected.remove(".coefficients.jitter{PORT}[0]");
            expected.remove(".coefficients.jitter{PORT}[1]");
            expected.add(".coefficients.jitter{PORT}");
        }
        List<String> actual = diff(before, copy);
        Assertions.assertEquals(new TreeSet<>(expected), new TreeSet<>(actual));
        Assertions.assertEquals(expected.size(), actual.size());
        // The values of the copy; the other corners of the load pin keep the data's values
        ClockDelayParams q = copy.getParams();
        Assertions.assertEquals(base + 10f, q.getClassTerm(cls, Corner.SLOW_MAX.ordinal(), ClockData.Term.BASE));
        Assertions.assertEquals(3f, q.getClassTerm(cls, Corner.FAST_MIN.ordinal(), ClockData.Term.FANOUT));
        for (Corner c : Corner.values()) {
            Assertions.assertEquals(c == Corner.SLOW_MIN ? -12.5f : oldDelta[c.ordinal()],
                    q.getLoadPinDelta(pinKey)[c.ordinal()], c.toString());
        }
        Assertions.assertEquals((base + 10f) * q.getRouteScale(0), q.getClassDelay(cls, 0, 0, 0, 0, 0, 1), 1e-3f);
        Clock clock = Clock.createPrimary(0, "c", 4000, new double[]{0, 2000}, "port");
        Assertions.assertEquals("PORT", clock.getKind());
        Assertions.assertEquals(11f + 0.5f * 4f, copy.getCoefficients().getDiscreteJitter(clock), 1e-4f);

        // Nothing shared changed: the data get() returned, and the data of a later get()
        Assertions.assertEquals(Arrays.asList(), diff(fresh, before));
        ClockData after = ClockData.get(device, SpeedGrade.of(grade));
        Assertions.assertEquals(Arrays.asList(), diff(fresh, after));
        Assertions.assertEquals(base, after.getParams().getClassTerm(cls, Corner.SLOW_MAX.ordinal(),
                ClockData.Term.BASE));
        Assertions.assertEquals(treeBefore, getTree(family));

        // Overrides of a copy keep the copy's: a second override on top of the first
        ClockData second = copy.withOverrides(new ClockData.Overrides()
                .setClassTerm(nodeClass, Corner.SLOW_MAX, ClockData.Term.BASE, base + 20f));
        Assertions.assertEquals(Arrays.asList(
                ".params.classTerms[" + baseTerm + "][" + Corner.SLOW_MAX.ordinal() + "][" + cls + "]"),
                diff(copy, second));
        ClockDelayParams r = second.getParams();
        Assertions.assertEquals(base + 20f, r.getClassTerm(cls, Corner.SLOW_MAX.ordinal(), ClockData.Term.BASE));
        Assertions.assertEquals(3f, r.getClassTerm(cls, Corner.FAST_MIN.ordinal(), ClockData.Term.FANOUT));
        Assertions.assertEquals(-12.5f, r.getLoadPinDelta(pinKey)[Corner.SLOW_MIN.ordinal()]);
        Assertions.assertEquals(oldDelta[Corner.SLOW_MAX.ordinal()],
                r.getLoadPinDelta(pinKey)[Corner.SLOW_MAX.ordinal()]);
        Assertions.assertEquals(11f + 0.5f * 4f, second.getCoefficients().getDiscreteJitter(clock), 1e-4f);
        Assertions.assertEquals(Arrays.asList(), diff(fresh, ClockData.get(device, SpeedGrade.of(grade))));
        Assertions.assertEquals(Arrays.asList(), diff(fresh, before));
        Assertions.assertEquals(treeBefore, getTree(family));
    }

    /** Overrides of unknown node classes or kinds of clocks, or of values that are not finite, are refused */
    @Test
    public void testOverridesRefused() {
        ClockData data = ClockData.get(Device.getDevice("xcvu3p"), SpeedGrade.of("2"));
        ClockData.Overrides unknownClass = new ClockData.Overrides()
                .setClassTerm("NO_SUCH_CLASS", Corner.SLOW_MAX, ClockData.Term.BASE, 1f);
        Assertions.assertThrows(IllegalArgumentException.class, () -> data.withOverrides(unknownClass));
        // Load pins without a delta in the data: a pin of no site type, and a pin of another site type than the one
        // that has a delta for it (SLICEL.H1); the message names the pin
        Assertions.assertNotNull(data.getParams().getLoadPinDelta("SLICEL.H1"));
        for (String[] pin : new String[][]{{"RAMB36", "NO_SUCH_PIN"}, {"SLICEM", "H1"}}) {
            ClockData.Overrides unknownPin = new ClockData.Overrides()
                    .setLoadPinDelta(SiteTypeEnum.valueOf(pin[0]), pin[1], Corner.SLOW_MAX, 1f);
            IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> data.withOverrides(unknownPin));
            Assertions.assertTrue(e.getMessage().contains(pin[0] + "." + pin[1]), e.getMessage());
        }
        Assertions.assertNull(data.getParams().getLoadPinDelta("SLICEM.H1"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ClockData.Overrides().setDiscreteJitter("NO_SUCH_KIND", 1f, 0f));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ClockData.Overrides().setDiscreteJitter("MMCM", Float.NaN, 0f));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ClockData.Overrides()
                .setLoadPinDelta(SiteTypeEnum.RAMB36, "CLKARDCLKL", Corner.SLOW_MAX, Float.POSITIVE_INFINITY));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ClockData.Overrides()
                .setClassTerm("CLK_LEAF_IN", Corner.SLOW_MAX, ClockData.Term.BASE, Float.NaN));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClockData.Term.valueOf("NO_SUCH_TERM"));
        for (String kind : Clock.KINDS) {
            new ClockData.Overrides().setDiscreteJitter(kind, 1f, 0f);
        }
        // No overrides: a copy equal to the data
        Assertions.assertEquals(Arrays.asList(), diff(data, data.withOverrides(new ClockData.Overrides())));
    }
}
