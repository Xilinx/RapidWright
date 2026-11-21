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

package com.xilinx.rapidwright.timing.versal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
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
 * The timing family of Versal devices, with one timing data set per architecture generation (see
 * {@link Generation} and {@link TimingData}): {@link VersalDelayEstimator} for the interconnect and the
 * fitted cell arcs ({@link #getFittedCellArcs(Device)}).
 */
public class VersalTimingFamily implements TimingFamily {

    /** Generations of the Versal architecture, which have their own timing data */
    public enum Generation {
        /** Versal AI Core, Premium, Prime, AI Edge and HBM devices, e.g. xcvc1902, xcvp1202, xcv80 */
        GEN1,
        /** Versal AI Edge Gen 2 and Prime Gen 2 devices, e.g. xc2ve3858, xc2vm3858 */
        GEN2
    }

    private static final VersalTimingFamily INSTANCE = new VersalTimingFamily();

    /**
     * The router knobs of Versal devices: a maximum criticality of 0.95, since 0.99 can keep routing
     * from converging, and a present congestion multiplier of 1.2 for timing-driven routing (below the
     * configured 1.3 that the present congestion factor otherwise grows by from 2), since its routes
     * suffer more from congestion detours than they gain from resolving congestion faster. All
     * nets are timed in partial routing, so that timing paths through preserved and intra-site nets
     * stay whole. The best
     * route on an empty device (Dijkstra over the fitted node delays between slices, fanout 1, fitted
     * against the column and row distances) costs about 2.0 times
     * the admissible lookahead delay per INT column (gen1 34/17.4 ps, gen2 41/19.9 ps) and 1.0 times
     * per INT row (5.6/5.6 and 5.6/5.8 ps).
     */
    private static final RouterKnobs ROUTER_KNOBS = new RouterKnobs(0.95f, 1.2f, true, 2.0f, 1.0f);

    /** The name of the timing data set of the first generation */
    public static final String GEN1_DATA_NAME = "versal_gen1";
    /** The name of the timing data set of the second generation */
    public static final String GEN2_DATA_NAME = "versal_gen2";

    private static final List<String> DATA_NAMES =
            Collections.unmodifiableList(Arrays.asList(GEN1_DATA_NAME, GEN2_DATA_NAME));

    protected VersalTimingFamily() {
    }

    public static VersalTimingFamily getInstance() {
        return INSTANCE;
    }

    /**
     * Gets the architecture generation of a Versal device.
     * @param device The Versal device.
     * @return {@link Generation#GEN2} for second generation devices, such as the xc2ve and xc2vm parts.
     */
    public static Generation getGeneration(Device device) {
        return getGeneration(PartNameTools.getPart(device.getName()));
    }

    /**
     * Gets the architecture generation of a Versal part.
     * @param part The Versal part.
     * @return {@link Generation#GEN2} for second generation parts, such as the xc2ve and xc2vm parts.
     */
    public static Generation getGeneration(Part part) {
        FamilyType family = part.getFamily();
        return (family == FamilyType.VERSALAIEDGE2 || family == FamilyType.VERSALPRIME2)
                ? Generation.GEN2 : Generation.GEN1;
    }

    /**
     * Gets the timing data of a generation, reading its file on first use (see {@link TimingData}).
     * @param generation The generation.
     * @return The data.
     */
    public static TimingData getTimingData(Generation generation) {
        return TimingData.get(generation == Generation.GEN2 ? GEN2_DATA_NAME : GEN1_DATA_NAME);
    }

    /**
     * Gets the interconnect parameters of a generation.
     * @param generation The generation.
     * @param corner The corner.
     * @return The parameters.
     * @throws UnsupportedOperationException If the generation's data has no such corner.
     */
    public static InterconnectParams getInterconnectParams(Generation generation, Corner corner) {
        return getTimingData(generation).getInterconnectParams(corner);
    }

    /**
     * Gets the fitted cell arcs of a generation.
     * @param generation The generation.
     * @return The cell arcs.
     */
    public static FittedCellArcLibrary getFittedCellArcs(Generation generation) {
        return getTimingData(generation).getFittedCellArcs();
    }

    @Override
    public FittedCellArcLibrary getFittedCellArcs(Device device) {
        return getFittedCellArcs(getGeneration(device));
    }

    @Override
    public Series getSeries() {
        return Series.Versal;
    }

    @Override
    public List<String> getTimingDataNames() {
        return DATA_NAMES;
    }

    @Override
    public String getTimingDataName(Part part) {
        return getGeneration(part) == Generation.GEN2 ? GEN2_DATA_NAME : GEN1_DATA_NAME;
    }

    @Override
    public DelayEstimator createDelayEstimator(Device device, SpeedGrade speedGrade, Corner corner,
                                               boolean useUTurnNodes) {
        return new VersalDelayEstimator(device, getInterconnectParams(getGeneration(device), corner),
                speedGrade == null ? null : speedGrade.toString());
    }

    /**
     * Creates a {@link VersalDelayEstimator} from given parameters, whose node classes must be the
     * {@code VersalNodeClass}es; Versal estimators do not tell U-turn nodes apart.
     */
    @Override
    public ClassBasedDelayEstimator createDelayEstimator(Device device, InterconnectParams params,
                                                         SpeedGrade speedGrade, boolean useUTurnNodes) {
        return new VersalDelayEstimator(device, params, speedGrade == null ? null : speedGrade.toString());
    }

    @Override
    public RouterKnobs getRouterKnobs() {
        return ROUTER_KNOBS;
    }
}
