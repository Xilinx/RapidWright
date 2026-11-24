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
 * The timing model of Versal devices, which follows the approach of "An Open-source Lightweight Timing
 * Model for RapidWright" (P. Maidee, C. Neely, A. Kaviani, C. Lavin, FPT 2019).
 * <ul>
 * <li>{@link com.xilinx.rapidwright.timing.versal.VersalTimingFamily}: the entry point, with one set of
 * data per architecture generation (gen1: AI Core, Premium, Prime, AI Edge, HBM; gen2: AI Edge Gen 2,
 * Prime Gen 2).</li>
 * <li>{@code VersalNodeClass} and
 * {@link com.xilinx.rapidwright.timing.versal.VersalDelayEstimator}: the class-based interconnect model,
 * a {@link com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator}, at four corners.</li>
 * <li>The data of each generation (interconnect parameters, fitted cell arcs and clock network delays) is a
 * timing data file that is downloaded on first use, see
 * {@link com.xilinx.rapidwright.timing.family.TimingData}.</li>
 * </ul>
 * The model, how it was fitted and its accuracy are described in
 * <a href="https://www.rapidwright.io/docs/Timing_Model.html">Timing Model and Timing Analysis</a>
 * in the RapidWright documentation.
 */
package com.xilinx.rapidwright.timing.versal;
