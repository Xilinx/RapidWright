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

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.timing.series7.Series7TimingFamily;
import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusTimingFamily;
import com.xilinx.rapidwright.timing.versal.VersalTimingFamily;

/**
 * The registry of {@link TimingFamily} implementations, one per device series.
 */
public final class TimingFamilies {

    /**
     * System property selecting the clocks of the timing analysis of the router and of
     * {@link com.xilinx.rapidwright.timing.TimingManager}: {@value #CLOCKS_IDEAL} for
     * ideal clocks (no insertion delay, skew, uncertainty nor setup/hold times; a design with at most
     * one clock is timed against its single requirement, and only designs with several clocks get the
     * relations and exceptions of their clocks), or
     * {@value #CLOCKS_MODELED} (the default) for the clocks of the design's constraints with their insertion delays
     * (from the family's clock data, see {@link com.xilinx.rapidwright.timing.clock.ClockData#get}), CRPR
     * (signed), inter-SLR compensation, uncertainty and the
     * setup/hold times of the fitted cell arcs, and a hold report, e.g.
     * {@code java -Drapidwright.timing.clocks=ideal ...} for the former.
     */
    public static final String CLOCKS_PROPERTY = "rapidwright.timing.clocks";
    /** Value of {@link #CLOCKS_PROPERTY} selecting ideal clocks */
    public static final String CLOCKS_IDEAL = "ideal";
    /** Value of {@link #CLOCKS_PROPERTY} selecting modeled clocks, the default */
    public static final String CLOCKS_MODELED = "modeled";

    private static final Map<Series, Supplier<TimingFamily>> FAMILIES = new EnumMap<>(Series.class);

    static {
        FAMILIES.put(Series.Series7, Series7TimingFamily::getInstance);
        FAMILIES.put(Series.UltraScalePlus, UltraScalePlusTimingFamily::getInstance);
        FAMILIES.put(Series.Versal, VersalTimingFamily::getInstance);
    }

    /** The series whose families have timing data (the 7 series family is a stub without any) */
    private static final Set<Series> SUPPORTED_SERIES =
            Collections.unmodifiableSet(EnumSet.of(Series.UltraScalePlus, Series.Versal));

    private TimingFamilies() {
    }

    /**
     * @return The series that have a timing model: UltraScale+ and Versal.
     */
    public static Set<Series> getSupportedSeries() {
        return SUPPORTED_SERIES;
    }

    /**
     * Checks if a series has a timing model, so that its devices can be timed (e.g. by
     * {@link com.xilinx.rapidwright.timing.TimingManager}) and routed timing-driven.
     * @param series The series.
     * @return True if it has one, see {@link #getSupportedSeries()}.
     */
    public static boolean isSupported(Series series) {
        return SUPPORTED_SERIES.contains(series);
    }

    /**
     * Gets the message that a series has no timing model.
     * @param series The series.
     * @return The message, naming the series that have one.
     */
    public static String getUnsupportedMessage(Series series) {
        return "No timing model for the " + series + " series; RapidWright has timing models for the "
                + SUPPORTED_SERIES + " series";
    }

    /**
     * Checks that a device's series has a timing model.
     * @param device The device.
     * @throws UnsupportedOperationException If it has none, with {@link #getUnsupportedMessage(Series)}.
     */
    public static void checkSupported(Device device) {
        if (!isSupported(device.getSeries())) {
            throw new UnsupportedOperationException(getUnsupportedMessage(device.getSeries()));
        }
    }

    /**
     * Checks which clocks {@link #CLOCKS_PROPERTY} selects.
     * @return True for modeled clocks (the default), false for ideal ones.
     * @throws IllegalArgumentException If the property has another value.
     */
    public static boolean isModeledClocksSelected() {
        String clocks = System.getProperty(CLOCKS_PROPERTY, CLOCKS_MODELED);
        if (clocks.equals(CLOCKS_MODELED)) {
            return true;
        }
        if (!clocks.equals(CLOCKS_IDEAL)) {
            throw new IllegalArgumentException("Unknown value '" + clocks + "' of " + CLOCKS_PROPERTY + ", expected "
                    + CLOCKS_IDEAL + " or " + CLOCKS_MODELED);
        }
        return false;
    }

    /**
     * Gets the timing family of a series.
     * @param series The series.
     * @return The family.
     * @throws UnsupportedOperationException If no family models the series, with
     * {@link #getUnsupportedMessage(Series)}.
     */
    public static TimingFamily of(Series series) {
        Supplier<TimingFamily> family = FAMILIES.get(series);
        if (family == null) {
            throw new UnsupportedOperationException(getUnsupportedMessage(series));
        }
        return family.get();
    }

    /**
     * Gets the timing family of a device.
     * @param device The device.
     * @return The family of the device's series.
     */
    public static TimingFamily of(Device device) {
        return of(device.getSeries());
    }

    /**
     * Gets the timing family of a design's device.
     * @param design The design.
     * @return The family of the series of the design's device.
     */
    public static TimingFamily of(Design design) {
        return of(design.getDevice());
    }
}
