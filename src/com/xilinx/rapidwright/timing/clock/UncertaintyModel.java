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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Site;

/**
 * The clock uncertainty of the timing checks between clocks, in ps, as Vivado computes it:
 * <pre>
 *   unc = sqrt(TSJ^2 + TIJ^2 + DJ^2) / 2 + PE + UU       (UltraScale+)
 *   unc = sqrt((SJ + DJ)^2 + TIJ^2) / 2 + PE + UU        (Versal, see Coefficients#systemJitterAdds)
 * </pre>
 * <ul>
 * <li>TSJ, the total system jitter: the family's ({@link Coefficients#totalSystemJitter}), or
 * {@code sqrt(2)*sj} with sj the system jitter of {@code set_system_jitter};</li>
 * <li>SJ (Versal), the system jitter of {@code set_system_jitter}, else the family's (0 in the fitted data,
 * as Vivado's routed designs show), adding to the discrete jitter;</li>
 * <li>TIJ, the total input jitter of primary clocks ({@code set_input_jitter}, inherited by clocks
 * generated other than by a clock generator): ij for a single clock,
 * {@code sqrt(ij_launch^2 + ij_capture^2)} for two;</li>
 * <li>DJ, the discrete jitter of the clock generator outputs the clocks derive from
 * ({@link Coefficients#getDiscreteJitter(Clock)}, {@code a + b*T(out) ns} per kind of source): that of
 * the clock for a single clock; for two clocks, the larger one if they come from the same generator,
 * else their quadrature sum; on Versal devices with port clock jitter data ({@link PortJitter}), that
 * of a port clock depends on its period and on the clock regions its clock pins span;</li>
 * <li>PE, the phase error ({@link Coefficients#getPhaseErrorSetup}): between two outputs of one
 * generator, between a generator output and its input clock, else between other clocks;</li>
 * <li>UU, the user uncertainty ({@code set_clock_uncertainty}): an inter-clock one (-from/-to) if
 * any, else the simple uncertainty of the capture clock, whatever the order of the commands; a later
 * command replaces the value of an earlier one of the same kind, or adds to it with {@code -add}.</li>
 * </ul>
 * Hold checks of a clock to itself get {@link Coefficients#sameClockHold} plus the user uncertainty
 * only (jitter does not apply to the same edge).
 */
public final class UncertaintyModel {

    /** The family formula's coefficients (the fitted clock data's "uncertainty", see {@link ClockData}), in ps */
    public static class Coefficients {
        /** Default system jitter of a clock, in ps (Vivado: 50 ps), when {@link #totalSystemJitter} is unset */
        public float systemJitter = 50f;
        /** The total system jitter TSJ of the family, in ps, NaN to use sqrt(2)*{@link #systemJitter} */
        public float totalSystemJitter = Float.NaN;
        /**
         * Discrete jitter of the clocks per kind (MMCM, PLL, PS, GT, PORT, FABRIC, see {@link Clock#getKind()}):
         * {a ps, b ps per ns of period}
         */
        public final Map<String, float[]> jitter = new HashMap<>();
        /**
         * Discrete jitter of the outputs of clock generators per kind (MMCM, PLL) by their settings, which replaces
         * {@link #jitter} for the clocks whose generator settings are known (see {@link DiscreteJitterTable})
         */
        public final Map<String, DiscreteJitterTable> jitterTables = new HashMap<>();
        /**
         * Whether the system jitter adds to the discrete jitter (Versal: CJ = sqrt((SJ + DJ)^2 + IJ^2)) instead of
         * combining with it in quadrature (sqrt(TSJ^2 + IJ^2 + DJ^2), TSJ = sqrt(2)*SJ for a user system jitter)
         */
        public boolean systemJitterAdds;
        /** Phase error between two outputs of one generator, in ps */
        public float phaseErrorSame;
        /** Phase error between a generator output and the generator's input clock, in ps */
        public float phaseErrorIn;
        /** Phase error between other clocks, in ps */
        public float phaseErrorDiff;
        /** Uncertainty of the hold checks of a clock to itself, in ps */
        public float sameClockHold;
        /** The jitter of the port clocks of devices by device name (Versal), see {@link PortJitter} */
        public final Map<String, PortJitter> portJitter = new HashMap<>();

        /**
         * Gets the discrete jitter of a clock: that of the clock generator output it derives from (or that a primary
         * clock it derives from is defined downstream of), from the {@link #jitterTables table} of its kind and the
         * generator's settings (see {@link Clock#getGeneratorSettingsSource()}) if there is one, else {@code a + b*T} of that kind (T in ns); else that of its own kind (PS, GT,
         * PORT, FABRIC), see {@code getJitterKind}; 0 for kinds without data.
         * @param c The clock.
         * @return The jitter in ps.
         */
        public float getDiscreteJitter(Clock c) {
            Clock src = getJitterSource(c);
            DiscreteJitterTable table = jitterTables.get(src.getKind());
            Clock settings = table == null ? null : c.getGeneratorSettingsSource();
            if (settings != null) {
                return table.getJitter(settings.getVcoFrequency(), settings.getGeneratorMultiplier(),
                        settings.getGeneratorDivide());
            }
            String kind = getJitterKind(src.getKind());
            float[] ab = kind == null ? null : jitter.get(kind);
            return ab == null ? 0f : ab[0] + ab[1] * (float) (src.getPeriod() / 1000);
        }

        /** The clock whose kind and period give a clock's discrete jitter: its generator output, else itself */
        static Clock getJitterSource(Clock c) {
            Clock out = c.getGeneratorOutput();
            return out != null ? out : c;
        }

        /**
         * Gets the kind whose discrete jitter data a kind of clock takes: its own if the data has it, else
         * that of the closest kind of the same family that has it: MMCM and PLL (clock generators) for each
         * other, PORT for FABRIC and GT clocks.
         * @param kind A kind of clock (see {@link Clock#getKind()}).
         * @return The kind, null if none has data.
         */
        String getJitterKind(String kind) {
            if (jitter.containsKey(kind)) {
                return kind;
            }
            for (String[] f : JITTER_FALLBACK) {
                if (f[0].equals(kind) && jitter.containsKey(f[1])) {
                    return f[1];
                }
            }
            return null;
        }

        /**
         * Gets the phase error between two different clocks for setup checks.
         * @param launch The launch clock.
         * @param capture The capture clock.
         * @return The phase error in ps.
         */
        public float getPhaseErrorSetup(Clock launch, Clock capture) {
            Clock gl = launch.getGeneratorOutput();
            Clock gc = capture.getGeneratorOutput();
            if (gl != null && gc != null && gl.getGeneratorCell().equals(gc.getGeneratorCell())) {
                return gl == gc ? 0f : phaseErrorSame;
            }
            if (gl != null && gc == null && gl.getMaster() != null && isOrDerives(capture, gl.getMaster())
                    || gc != null && gl == null && gc.getMaster() != null && isOrDerives(launch, gc.getMaster())) {
                return phaseErrorIn;
            }
            return gl != null || gc != null ? phaseErrorDiff : 0f;
        }

        /** Gets the phase error between two different clocks for hold checks, in ps (as for setup). */
        public float getPhaseErrorHold(Clock launch, Clock capture) {
            return getPhaseErrorSetup(launch, capture);
        }

        private static boolean isOrDerives(Clock c, Clock other) {
            return c == other || c.isDerivedFrom(other) && c.getGeneratorOutput() == null;
        }
    }

    /**
     * The jitter of the port clocks of a device (Versal): a clock entering on a port and reaching its clock pins
     * through clock buffers is charged a jitter that depends on its period, plus one that grows with the extent of
     * its clock tree: the larger of one by the number of clock region rows and one by the number of clock region
     * columns that the clock regions of its clock pins span (both 0 for a single clock region).
     */
    public static final class PortJitter {
        /**
         * The largest RAM activity, the average frequency in MHz of a device's RAMs that a design switches, at which
         * Vivado's port clock jitter does not grow with it; the jitter of designs with more is not modeled
         */
        public static final float MAX_RAM_ACTIVITY = 40f;
        /** Clock periods in ns, increasing */
        private final float[] periods;
        /** The jitter at those periods for a single clock region, in ps */
        private final float[] jitter;
        /** The extra jitter by the number of clock region rows spanned (index 0: one row), in ps */
        private final float[] byRows;
        /** The extra jitter by the number of clock region columns spanned (index 0: one column), in ps */
        private final float[] byColumns;

        public PortJitter(float[] periods, float[] jitter, float[] byRows, float[] byColumns) {
            if (periods.length == 0 || periods.length != jitter.length) {
                throw new IllegalArgumentException("Expected as many port clock jitters as periods");
            }
            this.periods = periods.clone();
            this.jitter = jitter.clone();
            this.byRows = byRows.clone();
            this.byColumns = byColumns.clone();
        }

        /**
         * Gets the jitter of a port clock.
         * @param periodNs The clock's period in ns.
         * @param rows The number of clock region rows its clock pins span (0 if none is placed).
         * @param columns The number of clock region columns they span (0 if none is placed).
         * @return The jitter in ps: interpolated linearly in the logarithm of the period, and constant beyond the
         * listed periods, rows and columns.
         */
        public float getJitter(double periodNs, int rows, int columns) {
            float base;
            if (periodNs <= periods[0]) {
                base = jitter[0];
            } else if (periodNs >= periods[periods.length - 1]) {
                base = jitter[jitter.length - 1];
            } else {
                int i = 1;
                while (periods[i] < periodNs) {
                    i++;
                }
                double f = Math.log(periodNs / periods[i - 1]) / Math.log(periods[i] / periods[i - 1]);
                base = (float) (jitter[i - 1] + f * (jitter[i] - jitter[i - 1]));
            }
            return base + Math.max(extent(byRows, rows), extent(byColumns, columns));
        }

        private static float extent(float[] table, int n) {
            return n <= 0 || table.length == 0 ? 0f : table[Math.min(n, table.length) - 1];
        }
    }

    /**
     * The discrete jitter of the outputs of a kind of clock generator, measured on a grid of its settings: the VCO
     * frequency (the input clock's frequency times the feedback multiplier over the input divide), the feedback
     * multiplier and the output divide. Vivado's discrete jitter of a generator output depends on these three only
     * (not on the bandwidth setting, nor on the speed grade).
     */
    public static final class DiscreteJitterTable {
        /** VCO frequencies in MHz, increasing */
        private final float[] vco;
        /** Feedback multipliers, increasing */
        private final float[] mult;
        /** Output divides, increasing */
        private final float[] divide;
        /** The jitter in ps, by VCO frequency, then multiplier, then divide */
        private final float[] jitter;

        /**
         * @param vco The VCO frequencies in MHz, increasing.
         * @param mult The feedback multipliers, increasing.
         * @param divide The output divides, increasing.
         * @param jitter The jitter in ps at each grid point, by VCO frequency, then multiplier, then divide.
         */
        public DiscreteJitterTable(float[] vco, float[] mult, float[] divide, float[] jitter) {
            if (vco.length == 0 || mult.length == 0 || divide.length == 0
                    || jitter.length != vco.length * mult.length * divide.length) {
                throw new IllegalArgumentException("Expected a discrete jitter for each VCO frequency, multiplier and"
                        + " divide");
            }
            this.vco = vco.clone();
            this.mult = mult.clone();
            this.divide = divide.clone();
            this.jitter = jitter.clone();
        }

        /**
         * Gets the discrete jitter of a generator output: interpolated linearly in the VCO period, in the multiplier
         * and in the logarithm of the divide. Beyond the grid, the multiplier and the divide take the jitter of the
         * nearest grid point (a multiplier beyond the last one has its jitter: e.g. an MMCM's above 64), and the VCO
         * period is extrapolated linearly from the nearest two (e.g. the VCO frequency of a generator whose input
         * clock is faster than its settings allow, which Vivado still computes the jitter of), at least 0.
         * @param vcoMHz The VCO frequency in MHz.
         * @param multiplier The feedback multiplier.
         * @param outDivide The output divide.
         * @return The jitter in ps.
         */
        public float getJitter(double vcoMHz, double multiplier, double outDivide) {
            double[] v = weights(vco, vcoMHz, true);
            double[] m = weights(mult, multiplier, false);
            double[] d = weights(divide, Math.log(outDivide), false);
            double sum = 0;
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    for (int c = 0; c < 2; c++) {
                        double w = (a == 0 ? 1 - v[2] : v[2]) * (b == 0 ? 1 - m[2] : m[2]) * (c == 0 ? 1 - d[2] : d[2]);
                        if (w == 0) continue;
                        int i = ((int) v[a] * mult.length + (int) m[b]) * divide.length + (int) d[c];
                        sum += w * jitter[i];
                    }
                }
            }
            return (float) Math.max(0, sum);
        }

        /**
         * The two grid indices around a value and the weight of the second: linear in the value or, for the divides
         * (whose value is given as a logarithm), in the logarithm, clamped to the grid; or linear in its reciprocal
         * (inverse, the VCO frequencies), extrapolated from the first or last two grid values beyond the grid.
         */
        private double[] weights(float[] grid, double x, boolean inverse) {
            int n = grid.length;
            boolean log = grid == divide;
            double first = log ? Math.log(grid[0]) : grid[0];
            double last = log ? Math.log(grid[n - 1]) : grid[n - 1];
            if (n == 1) return new double[]{0, 0, 0};
            if (inverse && (x < first || x > last)) {
                int j = x < first ? 1 : n - 1;
                return new double[]{j - 1, j, (1 / grid[j - 1] - 1 / x) / (1 / grid[j - 1] - 1 / grid[j])};
            }
            if (x <= first) return new double[]{0, 0, 0};
            if (x >= last) return new double[]{n - 1, n - 1, 0};
            int j = 1;
            while ((log ? Math.log(grid[j]) : grid[j]) < x) {
                j++;
            }
            double lo = log ? Math.log(grid[j - 1]) : grid[j - 1];
            double hi = log ? Math.log(grid[j]) : grid[j];
            double f = inverse ? (1 / lo - 1 / x) / (1 / lo - 1 / hi) : (x - lo) / (hi - lo);
            return new double[]{j - 1, j, f};
        }
    }

    /** The kind whose discrete jitter data stands for that of a kind without data: {kind, fallback} */
    private static final String[][] JITTER_FALLBACK = {
        {"MMCM", "PLL"}, {"PLL", "MMCM"}, {"FABRIC", "PORT"}, {"GT", "PORT"}
    };

    private final int numClocks;
    private final float[][] setup;
    private final float[][] hold;
    private final Map<String, Integer> warnings = new LinkedHashMap<>();

    /**
     * Computes the uncertainties of all pairs of clocks.
     * @param clocks The clocks, by id.
     * @param cn The clock network resolving the constraints' clock queries, or null for none.
     * @param constraints The constraints (uncertainty and jitter), or null for none.
     * @param coef The family formula's coefficients.
     */
    public UncertaintyModel(List<Clock> clocks, ClockNetwork cn, TimingConstraints constraints, Coefficients coef) {
        numClocks = clocks.size();
        setup = new float[numClocks][numClocks];
        hold = new float[numClocks][numClocks];
        double tsj2;
        // The system jitter added to the discrete jitter (Versal), else 0
        double sjAdd = 0;
        if (coef.systemJitterAdds) {
            tsj2 = 0;
            sjAdd = constraints != null && !Double.isNaN(constraints.getSystemJitter())
                    ? constraints.getSystemJitter() * 1000
                    : Float.isNaN(coef.totalSystemJitter) ? 0 : coef.totalSystemJitter;
        } else if (constraints != null && !Double.isNaN(constraints.getSystemJitter())) {
            double sj = constraints.getSystemJitter() * 1000;
            tsj2 = 2.0 * sj * sj;
        } else if (!Float.isNaN(coef.totalSystemJitter)) {
            tsj2 = (double) coef.totalSystemJitter * coef.totalSystemJitter;
        } else {
            tsj2 = 2.0 * coef.systemJitter * coef.systemJitter;
        }
        // Input jitter of the primary clocks, inherited by generated clocks other than generator outputs
        float[] ij = new float[numClocks];
        float[] dj = new float[numClocks];
        Design design = cn == null ? null : cn.getDesign();
        PortJitter portJitter = design == null ? null : coef.portJitter.get(design.getDevice().getName());
        if (portJitter != null && getRamActivityBound(cn, clocks) > PortJitter.MAX_RAM_ACTIVITY) {
            // The jitter that RAM activity adds is not modeled: keep the family's port clock jitter
            portJitter = null;
            warnings.merge("designs whose RAMs may switch at more than " + PortJitter.MAX_RAM_ACTIVITY
                    + " MHz on average: the family's port clock jitter used", 1, Integer::sum);
        }
        int[][] spans = portJitter == null ? null : getClockRegionSpans(cn, numClocks);
        if (constraints != null && cn != null) {
            for (TimingConstraints.InputJitterDef d : constraints.getInputJitters()) {
                for (int c : cn.resolveClocks(d.clocks)) {
                    ij[c] = (float) (d.value * 1000);
                }
            }
        }
        for (Clock c : clocks) {
            Clock m = c.getMaster();
            if (m != null && ij[c.getId()] == 0 && !ClockNetwork.isGenerator(String.valueOf(c.getGeneratorType()))) {
                // Clocks are numbered after their masters
                ij[c.getId()] = ij[m.getId()];
            }
            dj[c.getId()] = coef.getDiscreteJitter(c);
            if (portJitter != null && "PORT".equals(Coefficients.getJitterSource(c).getKind())) {
                int[] box = spans[c.getId()];
                dj[c.getId()] = portJitter.getJitter(c.getPeriod() / 1000, box[1] - box[0] + 1, box[3] - box[2] + 1);
            }
            String kind = Coefficients.getJitterSource(c).getKind();
            String used = coef.getJitterKind(kind);
            if (!kind.equals(used) && !kind.equals("FABRIC")) {
                // (FABRIC clocks are port clocks charged more where the data says so)
                warnings.merge(used == null ? "clocks without discrete jitter data (0 ps): " + kind
                        : "clocks without discrete jitter data, that of another kind used: " + kind + " as " + used,
                        1, Integer::sum);
            }
        }
        // User uncertainty: inter-clock (-from/-to) overrides the simple uncertainty of the capture clock
        float[] simpleSetup = new float[numClocks];
        float[] simpleHold = new float[numClocks];
        float[][] uuSetup = new float[numClocks][numClocks];
        float[][] uuHold = new float[numClocks][numClocks];
        boolean[][] interSetup = new boolean[numClocks][numClocks];
        boolean[][] interHold = new boolean[numClocks][numClocks];
        // Edge-specific inter-clock uncertainty: the rise-to-rise and fall-to-fall values of each pair (NaN: not set);
        // the pair gets the larger one
        float[][][] edgeSetup = null;
        float[][][] edgeHold = null;
        if (constraints != null && cn != null) {
            for (TimingConstraints.UncertaintyDef u : constraints.getUncertainties()) {
                if (u.clocks == null && u.edge != TimingConstraints.UncertaintyDef.ANY_EDGE && edgeSetup == null) {
                    edgeSetup = newEdgeValues(numClocks);
                    edgeHold = newEdgeValues(numClocks);
                }
            }
            for (TimingConstraints.UncertaintyDef u : constraints.getUncertainties()) {
                float v = (float) (u.value * 1000);
                if (u.clocks != null) {
                    for (int c : cn.resolveClocks(u.clocks)) {
                        if (u.setup) simpleSetup[c] = u.add ? simpleSetup[c] + v : v;
                        if (u.hold) simpleHold[c] = u.add ? simpleHold[c] + v : v;
                    }
                } else {
                    Set<Integer> from = u.from == null ? null : cn.resolveClocks(u.from);
                    Set<Integer> to = u.to == null ? null : cn.resolveClocks(u.to);
                    for (int l = 0; l < numClocks; l++) {
                        if (from != null && !from.contains(l)) continue;
                        for (int c = 0; c < numClocks; c++) {
                            if (to != null && !to.contains(c)) continue;
                            if (u.setup) {
                                uuSetup[l][c] = interValue(u, v, uuSetup[l][c], edgeSetup, l, c);
                                interSetup[l][c] = true;
                            }
                            if (u.hold) {
                                uuHold[l][c] = interValue(u, v, uuHold[l][c], edgeHold, l, c);
                                interHold[l][c] = true;
                            }
                        }
                    }
                }
            }
            for (int l = 0; l < numClocks; l++) {
                for (int c = 0; c < numClocks; c++) {
                    if (!interSetup[l][c]) uuSetup[l][c] = simpleSetup[c];
                    if (!interHold[l][c]) uuHold[l][c] = simpleHold[c];
                }
            }
        }
        for (int l = 0; l < numClocks; l++) {
            for (int c = 0; c < numClocks; c++) {
                boolean same = l == c;
                Clock lc = clocks.get(l);
                Clock cc = clocks.get(c);
                double tij2 = same ? ij[l] * ij[l] : ij[l] * ij[l] + ij[c] * ij[c];
                double dj2;
                if (same) {
                    dj2 = dj[l] * dj[l];
                } else if (sameGenerator(lc, cc)) {
                    double m = Math.max(dj[l], dj[c]);
                    dj2 = m * m;
                } else {
                    dj2 = dj[l] * dj[l] + dj[c] * dj[c];
                }
                double djs = sjAdd + Math.sqrt(dj2);
                float jitter = (float) (Math.sqrt(tsj2 + tij2 + djs * djs) / 2);
                float peS = same ? 0f : coef.getPhaseErrorSetup(lc, cc);
                float peH = same ? 0f : coef.getPhaseErrorHold(lc, cc);
                setup[l][c] = jitter + peS + uuSetup[l][c];
                hold[l][c] = same ? coef.sameClockHold + uuHold[l][c] : jitter + peH + uuHold[l][c];
            }
        }
    }

    /**
     * Gets an upper bound of the RAM activity that Vivado charges Versal clocks jitter for: the average frequency
     * in MHz over the device's RAMs (a 36 Kb block RAM counting half, an UltraRAM one) of the block RAMs and
     * UltraRAMs of the design, each at the highest frequency of the clocks of its clock pins; Vivado counts only
     * RAMs whose enables are not constant.
     * @return The bound in MHz, 0 if the design has no RAMs.
     */
    static float getRamActivityBound(ClockNetwork cn, List<Clock> clocks) {
        Design design = cn.getDesign();
        double sum = 0;
        for (Cell cell : design.getCells()) {
            String type = cell.getType();
            if (type == null) {
                continue;
            }
            double weight = type.startsWith("RAMB36") ? 0.5 : type.startsWith("RAMB18") ? 0.25
                    : type.startsWith("URAM288") ? 1 : 0;
            if (weight == 0) {
                continue;
            }
            double mhz = 0;
            for (int pin : cn.getClockPinsOfCell(cell.getName())) {
                for (int edge : cn.getClockPinEdges(pin)) {
                    mhz = Math.max(mhz, 1e6 / clocks.get(edge / 2).getPeriod());
                }
            }
            sum += weight * mhz;
        }
        if (sum == 0) {
            return 0;
        }
        double rams = 0;
        for (Site site : design.getDevice().getAllSites()) {
            String type = site.getSiteTypeEnum().name();
            rams += type.startsWith("RAMB36") ? 0.5 : type.startsWith("URAM288") ? 1 : 0;
        }
        return rams == 0 ? Float.MAX_VALUE : (float) (sum / rams);
    }

    /**
     * Gets the clock regions that the placed clock pins of each clock span.
     * @return Per clock id: {min row, max row, min column, max column}; {0, -1, 0, -1} for none.
     */
    private static int[][] getClockRegionSpans(ClockNetwork cn, int numClocks) {
        int[][] spans = new int[numClocks][];
        for (int i = 0; i < numClocks; i++) {
            spans[i] = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        }
        Design design = cn.getDesign();
        for (int i = 0; i < cn.getNumClockPins(); i++) {
            Cell cell = design.getCell(cn.getClockPin(i).getFullHierarchicalInstName());
            Site site = cell == null ? null : cell.getSite();
            ClockRegion cr = site == null ? null : site.getTile().getClockRegion();
            if (cr == null) {
                continue;
            }
            for (int edge : cn.getClockPinEdges(i)) {
                int[] s = spans[edge / 2];
                s[0] = Math.min(s[0], cr.getRow());
                s[1] = Math.max(s[1], cr.getRow());
                s[2] = Math.min(s[2], cr.getColumn());
                s[3] = Math.max(s[3], cr.getColumn());
            }
        }
        for (int[] s : spans) {
            if (s[0] > s[1]) {
                s[0] = s[2] = 0;
                s[1] = s[3] = -1;
            }
        }
        return spans;
    }

    private static float[][][] newEdgeValues(int numClocks) {
        float[][][] e = new float[2][numClocks][numClocks];
        for (float[][] a : e) {
            for (float[] b : a) {
                Arrays.fill(b, Float.NaN);
            }
        }
        return e;
    }

    /**
     * The inter-clock uncertainty of a pair after a constraint: one for all edges replaces (or adds to) the pair's
     * value, which both edges then have; an edge-specific one sets (or adds to) the value of its edge, and the pair
     * gets the larger of its rise and fall values (of the one edge if only it was set).
     */
    private static float interValue(TimingConstraints.UncertaintyDef u, float v, float old, float[][][] edges, int l,
                                    int c) {
        if (u.edge == TimingConstraints.UncertaintyDef.ANY_EDGE) {
            float value = u.add ? old + v : v;
            if (edges != null) {
                edges[0][l][c] = value;
                edges[1][l][c] = value;
            }
            return value;
        }
        float[][] mine = edges[u.edge == TimingConstraints.UncertaintyDef.RISE ? 0 : 1];
        float prev = mine[l][c];
        mine[l][c] = u.add ? (Float.isNaN(prev) ? 0f : prev) + v : v;
        float rise = edges[0][l][c];
        float fall = edges[1][l][c];
        return Float.isNaN(rise) ? fall : Float.isNaN(fall) ? rise : Math.max(rise, fall);
    }

    /** Whether two clocks derive from outputs of the same clock generator */
    private static boolean sameGenerator(Clock a, Clock b) {
        Clock ga = a.getGeneratorOutput();
        Clock gb = b.getGeneratorOutput();
        return ga != null && gb != null && ga.getGeneratorCell().equals(gb.getGeneratorCell());
    }

    /** Creates a model with no uncertainty at all (e.g. to reproduce single-clock analyses). */
    public static UncertaintyModel none(int numClocks) {
        return new UncertaintyModel(numClocks);
    }

    private UncertaintyModel(int numClocks) {
        this.numClocks = numClocks;
        setup = new float[numClocks][numClocks];
        hold = new float[numClocks][numClocks];
    }

    /**
     * Sets the uncertainties of a pair of clocks (e.g. for tests).
     */
    public void set(int launch, int capture, float setupPs, float holdPs) {
        setup[launch][capture] = setupPs;
        hold[launch][capture] = holdPs;
    }

    public int getNumClocks() {
        return numClocks;
    }

    /**
     * @return The warnings of the model with their number of occurrences (e.g. the clocks whose kind the
     * data has no discrete jitter for), see {@link ClockNetwork#printWarnings(String, Map, boolean)}.
     */
    public Map<String, Integer> getWarnings() {
        return Collections.unmodifiableMap(warnings);
    }

    /** @return The setup uncertainty from a launch clock to a capture clock, in ps. */
    public float getSetup(int launch, int capture) {
        return setup[launch][capture];
    }

    /** @return The hold uncertainty from a launch clock to a capture clock, in ps. */
    public float getHold(int launch, int capture) {
        return hold[launch][capture];
    }
}
