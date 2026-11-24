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

import java.util.Collections;
import java.util.List;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary;
import com.xilinx.rapidwright.timing.family.InterconnectParams;
import com.xilinx.rapidwright.timing.family.RouterKnobs;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingData;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * The timing family of UltraScale+ devices: the class-based interconnect model fitted like the Versal one
 * ({@link UltraScalePlusDelayEstimator}, {@code UltraScalePlusNodeClass}), with all four corners, speed grade
 * and device scales, and pin deltas, and the fitted cell arcs. Its data is the timing data set
 * {@value #DATA_NAME} (see {@link TimingData}).
 */
public class UltraScalePlusTimingFamily implements TimingFamily {

    private static final UltraScalePlusTimingFamily INSTANCE = new UltraScalePlusTimingFamily();

    /** The name of the timing data set of the UltraScale+ devices */
    public static final String DATA_NAME = "ultrascaleplus";

    protected UltraScalePlusTimingFamily() {
    }

    public static UltraScalePlusTimingFamily getInstance() {
        return INSTANCE;
    }

    @Override
    public Series getSeries() {
        return Series.UltraScalePlus;
    }

    @Override
    public List<String> getTimingDataNames() {
        return Collections.singletonList(DATA_NAME);
    }

    /**
     * Gets the interconnect parameters of the UltraScale+ devices, reading their timing data file on first
     * use (see {@link TimingData}).
     * @param corner The corner.
     * @return The parameters.
     * @throws UnsupportedOperationException If the data has no such corner.
     */
    public static InterconnectParams getInterconnectParams(Corner corner) {
        return TimingData.get(DATA_NAME).getInterconnectParams(corner);
    }

    /**
     * Creates an {@link UltraScalePlusDelayEstimator}.
     */
    @Override
    public DelayEstimator createDelayEstimator(Device device, SpeedGrade speedGrade, Corner corner,
                                               boolean useUTurnNodes) {
        return new UltraScalePlusDelayEstimator(device, getInterconnectParams(corner),
                speedGrade == null ? null : speedGrade.toString(), useUTurnNodes);
    }

    /**
     * Creates an {@link UltraScalePlusDelayEstimator} from given parameters, whose node classes must be the
     * {@code UltraScalePlusNodeClass}es.
     */
    @Override
    public ClassBasedDelayEstimator createDelayEstimator(Device device, InterconnectParams params,
                                                         SpeedGrade speedGrade, boolean useUTurnNodes) {
        return new UltraScalePlusDelayEstimator(device, params, speedGrade == null ? null : speedGrade.toString(),
                useUTurnNodes);
    }

    @Override
    public FittedCellArcLibrary getFittedCellArcs(Device device) {
        return TimingData.get(DATA_NAME).getFittedCellArcs();
    }

    /**
     * The router knobs: a maximum criticality of 0.99 and a present congestion multiplier of 1.2 for
     * timing-driven routing (as on Versal, below the configured 1.3); all nets are timed in partial
     * routing, so that timing paths through preserved and intra-site nets stay whole. The lookahead
     * ratios are the best route's delays on an empty xcvu3p (Dijkstra over the fitted node delays between
     * slices, fanout 1, fitted against the column and row distances): about 27 ps per INT column and 7 ps
     * per INT row, 1.85 and 1.1 times the fitted model's admissible lookahead (14.5, 6.3 ps).
     */
    private static final RouterKnobs ROUTER_KNOBS = new RouterKnobs(0.99f, 1.2f, true, 1.85f, 1.1f);

    @Override
    public RouterKnobs getRouterKnobs() {
        return ROUTER_KNOBS;
    }
}
