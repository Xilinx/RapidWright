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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusTimingFamily;
import com.xilinx.rapidwright.timing.versal.VersalTimingFamily;

/**
 * Checks the lookups of {@link FittedCellArcLibrary} against expected results (fitted_arc_vectors.txt, generated
 * together with the fitted data).
 */
public class TestFittedCellArcLibrary {

    private static FittedCellArcLibrary library(String family) {
        switch (family) {
            case "usplus":
                return UltraScalePlusTimingFamily.getInstance().getFittedCellArcs(null);
            case "versal_gen1":
                return VersalTimingFamily.getFittedCellArcs(VersalTimingFamily.Generation.GEN1);
            default:
                return VersalTimingFamily.getFittedCellArcs(VersalTimingFamily.Generation.GEN2);
        }
    }

    private static String nul(String s) {
        return s.equals("-") ? null : s;
    }

    private static String tenths(float[] d) {
        if (d == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            sb.append(i == 0 ? "" : ",").append(Math.round(d[i] * 10));
        }
        return sb.toString();
    }

    @Test
    public void testAgainstReferenceModel() throws IOException {
        int lookups = 0;
        int configs = 0;
        int factors = 0;
        int categories = 0;
        try (InputStream in = TestFittedCellArcLibrary.class.getResourceAsStream("fitted_arc_vectors.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            FittedCellArcLibrary lib = null;
            String family = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] t = line.split("\t", -1);
                switch (t[0]) {
                    case "F":
                        family = t[1];
                        lib = library(family);
                        break;
                    case "L": {
                        Map<String, String> attrs = new HashMap<>();
                        if (!t[9].equals("-")) {
                            // Properties as KEY=VALUE, separated by semicolons
                            for (String kv : t[9].split(";")) {
                                String[] p = kv.split("=", 2);
                                attrs.put(p[0], p[1]);
                            }
                        }
                        FittedCellArcLibrary.Result r = lib.lookup(t[1], t[2], t[3],
                                FittedCellArcLibrary.Role.valueOf(t[4]), t[5], Integer.parseInt(t[6]), t[7],
                                Integer.parseInt(t[8]), attrs, nul(t[10]), nul(t[11]));
                        Assertions.assertEquals(t[12], r.getStatus().name(), family + " " + line);
                        Assertions.assertEquals(t[13], tenths(r.getDelays()), family + " " + line);
                        lookups++;
                        break;
                    }
                    case "C": {
                        FittedCellArcLibrary.ConfigResult r = lib.config(t[1], t[2], t[3], t[4].equals("-") ? "" : t[4]);
                        Assertions.assertEquals(t[5], r.getStatus().name(), family + " " + line);
                        Assertions.assertEquals(t[6], tenths(r.getDelays()), family + " " + line);
                        configs++;
                        break;
                    }
                    case "S": {
                        float f = lib.getSpeedFactor(t[1], nul(t[2]), t[3], FittedCellArcLibrary.Role.valueOf(t[4]),
                                Integer.parseInt(t[5]));
                        if (t[2].equals("-")) {
                            f = lib.getSpeedFactor(t[1], null, t[3], null, Integer.parseInt(t[5]));
                        }
                        Assertions.assertEquals(Long.parseLong(t[6]), Math.round(f * 1e6), family + " " + line);
                        factors++;
                        break;
                    }
                    case "D": {
                        float f = lib.getDeviceFactor(t[1], t[2], FittedCellArcLibrary.Role.valueOf(t[3]),
                                Integer.parseInt(t[4]));
                        Assertions.assertEquals(Long.parseLong(t[5]), Math.round(f * 1e6), family + " " + line);
                        factors++;
                        break;
                    }
                    case "G":
                        Assertions.assertEquals(t[3], FittedCellArcLibrary.getCategory(t[1], t[2]), line);
                        categories++;
                        break;
                    default:
                        Assertions.fail(line);
                }
            }
        }
        Assertions.assertTrue(lookups > 1000);
        Assertions.assertTrue(configs > 500);
        Assertions.assertTrue(factors > 300);
        Assertions.assertTrue(categories > 100);
    }

    @Test
    public void testSizes() {
        Assertions.assertEquals(1867, library("usplus").getNumArcs());
        Assertions.assertEquals(2095, library("usplus").getNumConfigDelays());
        Assertions.assertEquals(3477, library("versal_gen1").getNumArcs());
        Assertions.assertEquals(3066, library("versal_gen2").getNumArcs());
        Assertions.assertEquals("2", library("usplus").getSpeedRef());
        Assertions.assertEquals("2MP", library("versal_gen1").getSpeedRef());
    }

    @Test
    public void testGatedArcs() {
        // Versal LOOKAHEAD8: the carry input CYB only reaches COUTB while the group's lookahead is off
        FittedCellArcLibrary lib = library("versal_gen1");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("LOOKB", "FALSE");
        attrs.put("LOOKD", "TRUE");
        attrs.put("LOOKF", "TRUE");
        attrs.put("LOOKH", "TRUE");
        FittedCellArcLibrary.Result r = lib.lookup("LOOKAHEAD8", "LOOKAHEAD8", "SLICEL", FittedCellArcLibrary.Role.COMB,
                "CYB", -1, "COUTB", -1, attrs, "CYB", "COUTB");
        Assertions.assertNotNull(r.getArc());
        Assertions.assertTrue(r.getDelays()[FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX)] > 0);
        attrs.put("LOOKB", "TRUE");
        r = lib.lookup("LOOKAHEAD8", "LOOKAHEAD8", "SLICEL", FittedCellArcLibrary.Role.COMB, "CYB", -1, "COUTB", -1,
                attrs, "CYB", "COUTB");
        Assertions.assertEquals(FittedCellArcLibrary.Status.GATED, r.getStatus());
        Assertions.assertNull(r.getArc());
        // A gate value never seen without the arc (here an unset property) keeps the nearest arc
        attrs.remove("LOOKB");
        r = lib.lookup("LOOKAHEAD8", "LOOKAHEAD8", "SLICEL", FittedCellArcLibrary.Role.COMB, "CYB", -1, "COUTB", -1,
                attrs, "CYB", "COUTB");
        Assertions.assertEquals(FittedCellArcLibrary.Status.NEAREST, r.getStatus());
        Assertions.assertNotNull(r.getArc());
        attrs.put("LOOKB", "MAYBE");
        Assertions.assertNotNull(lib.lookup("LOOKAHEAD8", "LOOKAHEAD8", "SLICEL", FittedCellArcLibrary.Role.COMB, "CYB",
                -1, "COUTB", -1, attrs, "CYB", "COUTB").getArc());
        // DSP58 output register: no ALU_OUT -> PCOUT path through a DSP whose P is registered
        Map<String, String> p = new HashMap<>();
        p.put("PREG", "1");
        Assertions.assertEquals(FittedCellArcLibrary.Status.GATED, lib.lookup("DSP_OUTPUT58", "DSP_OUTPUT", "DSP58",
                FittedCellArcLibrary.Role.COMB, "ALU_OUT", 0, "PCOUT", 0, p, null, null).getStatus());
        p.put("PREG", "0");
        Assertions.assertNotNull(lib.lookup("DSP_OUTPUT58", "DSP_OUTPUT", "DSP58", FittedCellArcLibrary.Role.COMB,
                "ALU_OUT", 0, "PCOUT", 0, p, null, null).getArc());
        // UltraScale+ DSP48E2: a registered A input has a setup check and no combinational arc, and vice versa
        FittedCellArcLibrary us = library("usplus");
        Map<String, String> dsp = new HashMap<>();
        dsp.put("AREG", "1");
        Assertions.assertNotNull(us.lookup("DSP_A_B_DATA", "DSP_A_B_DATA", "DSP48E2", FittedCellArcLibrary.Role.SETUP,
                "CLK", -1, "A", 0, dsp, null, null).getArc());
        Assertions.assertEquals(FittedCellArcLibrary.Status.GATED, us.lookup("DSP_A_B_DATA", "DSP_A_B_DATA", "DSP48E2",
                FittedCellArcLibrary.Role.COMB, "A", 0, "A2_DATA", 0, dsp, null, null).getStatus());
        dsp.put("AREG", "0");
        Assertions.assertNotNull(us.lookup("DSP_A_B_DATA", "DSP_A_B_DATA", "DSP48E2", FittedCellArcLibrary.Role.COMB,
                "A", 0, "A2_DATA", 0, dsp, null, null).getArc());
        Assertions.assertEquals(FittedCellArcLibrary.Status.GATED, us.lookup("DSP_A_B_DATA", "DSP_A_B_DATA", "DSP48E2",
                FittedCellArcLibrary.Role.SETUP, "CLK", -1, "A", 0, dsp, null, null).getStatus());
    }

    @Test
    public void testArcSpeedFactors() {
        // Versal LOOKAHEAD8: the lookahead arcs to COUTH slow down less from -2MP to -2HP than the cell's other arcs,
        // so they have their own speed grade factors
        FittedCellArcLibrary lib = library("versal_gen1");
        int max = FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX);
        FittedCellArcLibrary.Result r = lib.lookup("LOOKAHEAD8", "LOOKAHEAD8", "SLICEL", FittedCellArcLibrary.Role.COMB,
                "PROPB", -1, "COUTH", -1, new HashMap<>(), "PROPB", "COUTH");
        FittedCellArcLibrary.Arc arc = r.getArc();
        Assertions.assertNotNull(arc);
        Assertions.assertEquals("LOOKAHEAD8:PROPB:COUTH", FittedCellArcLibrary.getArcScaleKey(arc));
        float cellFactor = lib.getSpeedFactor("2HP", "LOOKAHEAD8", "carry", FittedCellArcLibrary.Role.COMB, max);
        float arcFactor = lib.getSpeedFactor("2HP", "LOOKAHEAD8", arc, "carry", FittedCellArcLibrary.Role.COMB, max);
        Assertions.assertTrue(arcFactor > cellFactor + 0.03f, arcFactor + " " + cellFactor);
        // About 171 ps at -2HP (the reference data), not about 162 ps (the cell type's factor)
        float d = lib.scale(r.getDelays()[max], "xcvp1002", "2HP", "LOOKAHEAD8", arc, "carry",
                FittedCellArcLibrary.Role.COMB, max);
        Assertions.assertEquals(171f, d, 1f);
        Assertions.assertEquals(r.getDelays()[max] * cellFactor, lib.scale(r.getDelays()[max], "xcvp1002", "2HP",
                "LOOKAHEAD8", "carry", FittedCellArcLibrary.Role.COMB, max), 1e-3f);
        // The reference grade has no factors, for arcs either
        Assertions.assertEquals(1f, lib.getSpeedFactor("2MP", "LOOKAHEAD8", arc, "carry",
                FittedCellArcLibrary.Role.COMB, max));
        Assertions.assertEquals(1f, lib.getSpeedFactor(null, "LOOKAHEAD8", arc, "carry",
                FittedCellArcLibrary.Role.COMB, max));
        // An arc that scales like its cell type has no factor of its own and takes the cell type's
        FittedCellArcLibrary.Arc cin = lib.lookup("LOOKAHEAD8", "LOOKAHEAD8", "SLICEL", FittedCellArcLibrary.Role.COMB,
                "CIN", -1, "COUTF", -1, new HashMap<>(), "CIN", "COUTF").getArc();
        Assertions.assertNotNull(cin);
        Assertions.assertEquals(cellFactor, lib.getSpeedFactor("2HP", "LOOKAHEAD8", cin, "carry",
                FittedCellArcLibrary.Role.COMB, max));
        // LUT arcs are shared by the LUT cell types: their key has the wildcard cell type
        FittedCellArcLibrary.Arc lut = library("usplus").lookup("LUT6", "A6LUT", "SLICEL",
                FittedCellArcLibrary.Role.COMB, "A1", -1, "O6", -1, null, "I0", "O").getArc();
        Assertions.assertEquals("*:A1:O6", FittedCellArcLibrary.getArcScaleKey(lut));
    }

    @Test
    public void testConfigSpeedFactors() {
        // UltraScale+ configuration delays do not all scale like the grade's factor of the configuration delays:
        // the connection from a LUT output to the carry chain's S input is slower at -3 than at -2
        FittedCellArcLibrary lib = library("usplus");
        int max = FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX);
        FittedCellArcLibrary.ConfigResult r = lib.config("SLICEL", "A6LUT/O6", "CARRY8/S0", "");
        Assertions.assertEquals(FittedCellArcLibrary.ConfigStatus.EXACT, r.getStatus());
        Assertions.assertEquals("config:SLICEL:A6LUT/O6:CARRY8/S0",
                FittedCellArcLibrary.getConfigScaleKey("SLICEL", "A6LUT/O6", "CARRY8/S0"));
        float category = lib.getSpeedFactor("3", null, "config", null, max);
        Assertions.assertTrue(category < 1f, Float.toString(category));
        Assertions.assertTrue(lib.getConfigSpeedFactor("3", r, max) > 1f);
        // About 13 ps at -3 (the reference data), not about 8 ps (the factor of all configuration delays)
        Assertions.assertEquals(13f, lib.scaleConfig(r, "xcvu3p", "3", max), 1f);
        // The reference grade has no factors
        Assertions.assertEquals(r.getDelays()[max], lib.scaleConfig(r, "xcvu3p", null, max), 1e-3f);
        Assertions.assertEquals(r.getDelays()[max], lib.scaleConfig(r, "xcvu3p", "2", max), 1e-3f);
        // An entry without factors of its own (here the default of a site pin) takes those of all configuration
        // delays
        FittedCellArcLibrary.ConfigResult def = lib.config("SLICEL", "NONE/O", "AMUX", "");
        Assertions.assertEquals(FittedCellArcLibrary.ConfigStatus.DEFAULT, def.getStatus());
        Assertions.assertEquals(category, lib.getConfigSpeedFactor("3", def, max));
        Assertions.assertEquals(def.getDelays()[max] * category, lib.scaleConfig(def, "xcvu3p", "3", max), 1e-3f);
    }

    @Test
    public void testCarryOutputLeavingTheSlice() {
        // UltraScale+ CARRY8: the arcs into CO[7] are about 7 ps slower (slow corners) when the slice's output or
        // flip-flop input mux takes CO7 than when CO7 only drives the next CARRY8. The configuration delays from CO7
        // to the mux outputs carry the difference; the delay to the cascade output does not.
        FittedCellArcLibrary lib = library("usplus");
        int max = FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX);
        int min = FittedCellArcLibrary.getDataIndex(Corner.FAST_MIN);
        for (String site : new String[]{"SLICEL", "SLICEM"}) {
            Assertions.assertEquals(21f, lib.config(site, "CARRY8/CO7", "COUT", "").getDelays()[max], 0.05f);
            Assertions.assertEquals(57f, lib.config(site, "CARRY8/CO7", "HMUX", "").getDelays()[max], 0.05f);
            Assertions.assertEquals(51f, lib.config(site, "CARRY8/CO7", "HFF/D", "").getDelays()[max], 0.05f);
            // The fast corner has no such difference
            Assertions.assertEquals(16f, lib.config(site, "CARRY8/CO7", "HMUX", "").getDelays()[min], 0.05f);
        }
    }

    @Test
    public void testInternalClockArcs() {
        // RFDC: the fabric data ports are launched by the internal fabric clock
        FittedCellArcLibrary us = library("usplus");
        Assertions.assertNotNull(us.lookup("HSADC", "HSADC", "HSADC", FittedCellArcLibrary.Role.CLK_TO_Q,
                "INTERNAL_FABRIC_CLK0", -1, "DATA_ADC0", 0, null, null, null).getArc());
    }

    @Test
    public void testLookupBasics() {
        FittedCellArcLibrary lib = library("usplus");
        // A LUT's arcs depend on its BEL and physical pins only
        FittedCellArcLibrary.Result r = lib.lookup("LUT6", "A6LUT", "SLICEL", FittedCellArcLibrary.Role.COMB,
                "A1", -1, "O6", -1, null, "I0", "O");
        Assertions.assertEquals(FittedCellArcLibrary.Status.EXACT, r.getStatus());
        Assertions.assertTrue(r.getDelays()[FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX)] > 0);
        Assertions.assertTrue(r.getDelays()[FittedCellArcLibrary.getDataIndex(Corner.FAST_MIN)]
                < r.getDelays()[FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX)]);
        Assertions.assertTrue(lib.lookup("LUT6", "A6LUT", "SLICEL", FittedCellArcLibrary.Role.COMB, "A1", -1, "O6",
                -1, null, null, null).getArc() == r.getArc());
        Assertions.assertEquals(FittedCellArcLibrary.Status.MISSING, lib.lookup("NOPE", "X", "DSP48E2",
                FittedCellArcLibrary.Role.COMB, "A", 0, "P", 0, null, null, null).getStatus());
        // The reference grade has no factors; fast corners do not depend on the grade on UltraScale+
        Assertions.assertEquals(1f, lib.getSpeedFactor("2", "LUT6", "lut", FittedCellArcLibrary.Role.COMB, 1));
        Assertions.assertTrue(lib.getSpeedFactor("1", "LUT6", "lut", FittedCellArcLibrary.Role.COMB,
                FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX)) > 1.1f);
        Assertions.assertEquals(1f, lib.getSpeedFactor("1", "LUT6", "lut", FittedCellArcLibrary.Role.COMB,
                FittedCellArcLibrary.getDataIndex(Corner.FAST_MIN)));
        Assertions.assertEquals("dsp", FittedCellArcLibrary.getCategory("DSP_ALU", "DSP48E2"));
        Assertions.assertEquals("DOUTADOUT", FittedCellArcLibrary.stripBus("DOUTADOUT12"));
        Assertions.assertEquals("DSP/A", FittedCellArcLibrary.normalizeConfigPin("DSP48E2", "DSP/A[3]"));
        Assertions.assertEquals("A1", FittedCellArcLibrary.normalizeConfigPin("SLICEL", "A1"));
    }
}
