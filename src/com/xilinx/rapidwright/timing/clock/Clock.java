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
import java.util.Collections;
import java.util.List;

/**
 * A clock: a primary clock ({@code create_clock}), a user generated clock
 * ({@code create_generated_clock}) or a clock auto-derived from a clock generator (MMCM, PLL, ...)
 * or a divider (BUFGCE_DIV). Times are in ps.
 * <p>
 * The waveform lists the edge times within the first period, starting with a rising edge and
 * alternating (rise, fall, rise, ...); the rising edges are at {@code waveform[2i] + k*period} and
 * the falling ones at {@code waveform[2i+1] + k*period}.
 */
public final class Clock {

    /** The kinds of source of clocks, see {@link #getKind()}; a clock cannot have another */
    static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList("MMCM", "PLL", "PS", "GT", "PORT",
            "FABRIC"));

    private final int id;
    private final String name;
    private final double period;
    private final double[] waveform;
    private final Clock master;
    private final boolean generated;
    private final boolean autoDerived;
    private final boolean virtual;
    private final List<String> sources;
    /** The clock source latency at the generating pin (e.g. an MMCM phase shift in latency mode), in ps */
    private final double sourceLatency;
    /** Index of the constraint defining the clock (for auto-derived clocks, their master's) */
    private final int defIndex;
    /** The generating cell of an auto-derived clock (e.g. the MMCM), else null */
    private final String generatorCell;
    private final String generatorType;
    /** User source latency (set_clock_latency -source), in ps: {min corners, max corners} */
    private final double[] userSourceLatency = {0, 0};
    /** User network latency (set_clock_latency without -source), in ps, making the clock ideal; NaN if none */
    private final double[] networkLatency = {Double.NaN, Double.NaN};
    /** Kind of source for jitter: MMCM, PLL, PS, GT, PORT or FABRIC */
    private String kind;
    /**
     * The settings of the clock generator output the clock comes from, which its discrete jitter depends on: the
     * VCO frequency in MHz, the feedback multiplier and the output divide; NaN if unknown
     */
    private double vcoMHz = Double.NaN;
    private double generatorMultiplier = Double.NaN;
    private double generatorDivide = Double.NaN;

    Clock(int id, String name, double period, double[] waveform, Clock master, boolean generated,
          boolean autoDerived, List<String> sources, double sourceLatency, int defIndex, String generatorCell,
          String generatorType) {
        this.id = id;
        this.name = name;
        this.period = period;
        this.waveform = waveform;
        this.master = master;
        this.generated = generated;
        this.autoDerived = autoDerived;
        this.virtual = sources.isEmpty();
        this.sources = new ArrayList<>(sources);
        this.sourceLatency = sourceLatency;
        this.defIndex = defIndex;
        this.generatorCell = generatorCell;
        this.generatorType = generatorType;
        if (generatorType != null && ClockNetwork.isGenerator(generatorType)) {
            String k = ClockDelayParams.getGeneratorKind(generatorType);
            this.kind = k.equals("MMCM") ? "MMCM" : "PLL";
        } else {
            this.kind = master != null ? master.kind : "PORT";
        }
        checkKind(kind);
    }

    /** Throws IllegalArgumentException if a kind of clock is not one of {@link #KINDS} */
    private static void checkKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown kind of clock " + kind + ", expected one of " + KINDS);
        }
    }

    /**
     * Creates a primary clock not attached to a design (e.g. for tests of {@link ClockRelations}).
     * @param id Its index.
     * @param name Its name.
     * @param period Its period in ps.
     * @param waveform Its edges in ps (rise, fall).
     * @param source A name identifying its source (clocks of the same source are related), or null for
     *               a virtual clock.
     */
    public static Clock createPrimary(int id, String name, double period, double[] waveform, String source) {
        List<String> s = new ArrayList<>();
        if (source != null) s.add(source);
        return new Clock(id, name, period, waveform.clone(), null, false, false, s, 0, 0, null, null);
    }

    /**
     * Creates a clock generated from a master, not attached to a design (e.g. for tests).
     * @param id Its index.
     * @param name Its name.
     * @param master The master clock.
     * @param period Its period in ps.
     * @param waveform Its edges in ps.
     * @param sourceLatency Its source latency in ps.
     */
    public static Clock createGenerated(int id, String name, Clock master, double period, double[] waveform,
                                        double sourceLatency) {
        List<String> s = new ArrayList<>();
        s.add(name);
        return new Clock(id, name, period, waveform.clone(), master, true, false, s, sourceLatency,
                master.defIndex, null, null);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** @return The period in ps. */
    public double getPeriod() {
        return period;
    }

    /** @return The edge times in ps (rise, fall, ...), a copy. */
    public double[] getWaveform() {
        return waveform.clone();
    }

    /** The edge times in ps (rise, fall, ...), not to be modified */
    double[] waveform() {
        return waveform;
    }

    /** @return The master clock of a generated clock, else null. */
    public Clock getMaster() {
        return master;
    }

    /** @return The primary clock this clock derives from (itself for a primary clock). */
    public Clock getRoot() {
        Clock c = this;
        while (c.master != null) {
            c = c.master;
        }
        return c;
    }

    public boolean isGenerated() {
        return generated;
    }

    public boolean isAutoDerived() {
        return autoDerived;
    }

    public boolean isVirtual() {
        return virtual;
    }

    /** @return The names of its source objects (ports, pins). */
    public List<String> getSources() {
        return sources;
    }

    /** @return The source latency in ps (e.g. an MMCM phase shift in latency mode). */
    public double getSourceLatency() {
        return sourceLatency;
    }

    /**
     * @param late True for the max corners' (late) value, false for the min corners' (early).
     * @return The user source latency (set_clock_latency -source) in ps, its master's if it has none.
     */
    public double getUserSourceLatency(boolean late) {
        return userSourceLatency[late ? 1 : 0];
    }

    /** @return Whether the clock is ideal: a network latency replaces its insertion delays. */
    public boolean isIdeal() {
        return !Double.isNaN(networkLatency[0]) || !Double.isNaN(networkLatency[1]);
    }

    /**
     * @param late True for the max corners' (late) value, false for the min corners' (early).
     * @return The user network latency of an ideal clock in ps (0 if not set at that corner).
     */
    public double getNetworkLatency(boolean late) {
        double v = networkLatency[late ? 1 : 0];
        return Double.isNaN(v) ? 0 : v;
    }

    void setUserSourceLatency(boolean late, double ps) {
        userSourceLatency[late ? 1 : 0] = ps;
    }

    void setNetworkLatency(boolean late, double ps) {
        networkLatency[late ? 1 : 0] = ps;
    }

    /** @return The index of the constraint defining it. */
    public int getDefIndex() {
        return defIndex;
    }

    /** @return The name of the cell generating an auto-derived clock, else null. */
    public String getGeneratorCell() {
        return generatorCell;
    }

    /** @return The type of the cell generating an auto-derived clock, else null. */
    public String getGeneratorType() {
        return generatorType;
    }

    /**
     * @return The kind of source of the clock, for its jitter: MMCM or PLL (outputs of clock generators,
     * PLL standing for PLL, XPLL and DPLL), PS or GT (clocks of processing system or transceiver pins); a primary
     * clock defined on a clock buffer or divider has the kind of such a block driving it through clock buffers;
     * PORT (other primary clocks), FABRIC (a port clock reaching its clock pins through no clock buffer);
     * clocks generated otherwise have their master's kind.
     */
    public String getKind() {
        return kind;
    }

    /** @throws IllegalArgumentException If the kind is not one of {@link #KINDS}. */
    void setKind(String kind) {
        checkKind(kind);
        this.kind = kind;
    }

    /**
     * Sets the settings of the clock generator output the clock comes from (an output of a clock generator, or a
     * primary clock defined downstream of one through clock buffers), see {@link #getVcoFrequency()}.
     */
    void setGeneratorSettings(double vcoMHz, double multiplier, double divide) {
        this.vcoMHz = vcoMHz;
        this.generatorMultiplier = multiplier;
        this.generatorDivide = divide;
    }

    /**
     * @return The VCO frequency in MHz of the clock generator output the clock is (or is defined downstream of): its
     * input clock's frequency times its feedback multiplier over its input divide; NaN if unknown.
     */
    public double getVcoFrequency() {
        return vcoMHz;
    }

    /** @return The feedback multiplier of that clock generator (see {@link #getVcoFrequency()}), NaN if unknown. */
    public double getGeneratorMultiplier() {
        return generatorMultiplier;
    }

    /** @return The divide of that clock generator output (see {@link #getVcoFrequency()}), NaN if unknown. */
    public double getGeneratorDivide() {
        return generatorDivide;
    }

    /**
     * @return The clock whose generator settings (see {@link #getVcoFrequency()}) this clock's discrete jitter
     * depends on: the first of this clock and its masters that has them, up to the clock generator output it
     * derives from (e.g. the primary clock on a clock buffer behind a generator, for a clock a divider derives from
     * it); null if none has them.
     */
    public Clock getGeneratorSettingsSource() {
        for (Clock c = this; c != null; c = c.master) {
            if (!Double.isNaN(c.vcoMHz)) {
                return c;
            }
            if (c.generatorType != null && ClockNetwork.isGenerator(c.generatorType)) {
                return null;
            }
        }
        return null;
    }

    /**
     * @return The clock generator output this clock derives from (itself, or a master), null if none.
     */
    public Clock getGeneratorOutput() {
        for (Clock c = this; c != null; c = c.master) {
            if (c.generatorType != null && ClockNetwork.isGenerator(c.generatorType)) {
                return c;
            }
        }
        return null;
    }

    /** Whether this clock derives, directly or not, from another one. */
    public boolean isDerivedFrom(Clock other) {
        for (Clock c = master; c != null; c = c.master) {
            if (c == other) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%s %.3f %s%s", name, period / 1000, Arrays.toString(
                Arrays.stream(waveform).map(w -> Math.round(w) / 1000.0).toArray()),
                generated ? (autoDerived ? " G,A" : " G") : "");
    }
}
