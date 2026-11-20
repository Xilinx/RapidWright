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

/**
 * The timing families: everything about timing that differs between device series.
 * <ul>
 * <li>{@link com.xilinx.rapidwright.timing.family.TimingFamily} and
 * {@link com.xilinx.rapidwright.timing.family.TimingFamilies}: the family of a device (UltraScale+,
 * Versal, and a 7 series stub), with the system property selecting the clocks
 * ({@code rapidwright.timing.clocks}).</li>
 * <li>{@link com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator}: the class-based interconnect
 * model of the fitted families, with {@link com.xilinx.rapidwright.timing.family.InterconnectParams} and
 * {@link com.xilinx.rapidwright.timing.family.CrossingIndex}.</li>
 * <li>{@link com.xilinx.rapidwright.timing.family.FittedCellArcLibrary}: the fitted cell arcs, with
 * gating by register and mode properties.</li>
 * <li>{@link com.xilinx.rapidwright.timing.family.TimingData} and
 * {@link com.xilinx.rapidwright.timing.family.TimingDataFile}: the fitted delay data of the families, in
 * timing data files ({@code data/timing/<series>/<data set>_timing.dat}) that are downloaded on first use
 * like the device files.</li>
 * <li>{@link com.xilinx.rapidwright.timing.family.Corner} and
 * {@link com.xilinx.rapidwright.timing.family.SpeedGrade}: the four corners, and speed grades with the
 * nearest-grade rule.</li>
 * <li>{@link com.xilinx.rapidwright.timing.family.RouterKnobs}: the settings of timing-driven routing that
 * depend on the model, and their {@code rapidwright.rwroute.*} overrides.</li>
 * </ul>
 * See <a href="https://www.rapidwright.io/docs/Timing_Model.html">Timing Model and Timing Analysis</a>
 * in the RapidWright documentation.
 */
package com.xilinx.rapidwright.timing.family;
