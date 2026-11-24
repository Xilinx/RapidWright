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
import java.util.List;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

/**
 * The timing model of one device series: everything about timing that differs between series. Get
 * the family of a device with {@link TimingFamilies#of(Device)}.
 * <p>
 * The fitted delay data of a family is in timing data files, one per timing data set (see {@link TimingData}),
 * which are downloaded on first use like the device files.
 */
public interface TimingFamily {

    /**
     * @return The series this family models.
     */
    Series getSeries();

    /**
     * Gets the speed grade of a part.
     * @param part The part.
     * @return The speed grade, or null if the part has none. A grade the family's data does not cover
     * gets a warning, and the data's reference grade, when its delays are computed.
     */
    default SpeedGrade resolveSpeedGrade(Part part) {
        return SpeedGrade.of(part);
    }

    /**
     * Creates the interconnect delay estimator of a device.
     * @param device The device.
     * @param speedGrade The speed grade, or null for the grade the family's data is for.
     * @param corner The timing corner of the delays.
     * @param useUTurnNodes Whether nodes that turn back towards their driver are allowed; when not,
     *                      models that tell them apart give them a prohibitive delay.
     * @return The delay estimator.
     * @throws UnsupportedOperationException If the family has no data for the corner.
     */
    DelayEstimator createDelayEstimator(Device device, SpeedGrade speedGrade, Corner corner, boolean useUTurnNodes);

    /**
     * Creates the interconnect delay estimator of a device from given parameters of the family's class-based
     * model, e.g. parameters derived from the family's own (see {@link ClassBasedDelayEstimator#getParams()})
     * through {@link InterconnectParams#toBuilder(String)}. The estimator is made as
     * {@link #createDelayEstimator(Device, SpeedGrade, Corner, boolean)} makes the family's own: the speed grade
     * and device scale the delays and the fanout delays with the factors of the given parameters, and the
     * lookahead and pre-route delays, which the family's data has at SLOW_MAX only, scale to the corner of the
     * parameters by their class base delays relative to those of the family's own SLOW_MAX data. Given the
     * family's own parameters of a corner, the estimator gives the same delays as the family's own at that corner.
     * The parameters are not copied: they are immutable.
     * @param device The device, of this family.
     * @param params The parameters, with the node classes of the family's own (their corner is the corner of the
     *               delays).
     * @param speedGrade The speed grade, or null for the grade the parameters are for.
     * @param useUTurnNodes Whether nodes that turn back towards their driver are allowed; when not, models that
     *                      tell them apart give them a prohibitive delay.
     * @return A new delay estimator.
     * @throws UnsupportedOperationException If the family has no class-based interconnect model.
     * @throws IllegalArgumentException If the node classes of the parameters are not those of the family.
     */
    default ClassBasedDelayEstimator createDelayEstimator(Device device, InterconnectParams params,
                                                          SpeedGrade speedGrade, boolean useUTurnNodes) {
        throw new UnsupportedOperationException("No class-based interconnect delay model for " + getSeries());
    }

    /**
     * Gets the fitted timing arcs of a device's cells, at four corners (see {@link FittedCellArcLibrary}),
     * which the arc provider of the timing engine uses.
     * @param device The device.
     * @return The library, shared by all callers.
     * @throws UnsupportedOperationException If the family has none.
     */
    default FittedCellArcLibrary getFittedCellArcs(Device device) {
        throw new UnsupportedOperationException("No fitted cell arcs for " + getSeries());
    }

    /**
     * Gets the names of the timing data sets of this family: the groups of its devices that share one fit,
     * each with its own timing data file (see {@link TimingData}).
     * @return The names, e.g. versal_gen1 and versal_gen2; empty if the family has no timing data.
     */
    default List<String> getTimingDataNames() {
        return Collections.emptyList();
    }

    /**
     * Gets the timing data set of a part.
     * @param part A part of this family's series.
     * @return The name of its data set (by default the family's first one, for families with a single
     * data set), or null if the family has no timing data.
     */
    default String getTimingDataName(Part part) {
        List<String> names = getTimingDataNames();
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * @return The settings of timing-driven routing that depend on this family.
     */
    RouterKnobs getRouterKnobs();

    /**
     * Gets the SLR crossings of a device as a delay estimator of this family times them, e.g. for the
     * lookahead of a router across SLRs.
     * @param device The device.
     * @param estimator The delay estimator (see {@link #createDelayEstimator}).
     * @return The model.
     */
    default SlrModel getSlrModel(Device device, DelayEstimator estimator) {
        return SlrModel.create(device, estimator);
    }
}
