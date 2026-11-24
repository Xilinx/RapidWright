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
 * The static timing analysis engine of {@link com.xilinx.rapidwright.timing.TimingManager} and RWRoute.
 * <ul>
 * <li>{@link com.xilinx.rapidwright.timing.sta.StaGraph}: the timing graph, a levelized, compressed sparse
 * row (CSR) struct of arrays whose topology is fixed once built by
 * {@link com.xilinx.rapidwright.timing.sta.StaGraphBuilder}; only the delays of its edges and connections
 * change.</li>
 * <li>{@link com.xilinx.rapidwright.timing.sta.FamilyArcProvider}: the arcs and delays of the graph, from the
 * fitted cell arcs and the delay estimator of the device's timing family (with DSP control and LUT input
 * decoding).</li>
 * <li>{@link com.xilinx.rapidwright.timing.sta.StaClockAnalysis}, with the clocks bound by
 * {@link com.xilinx.rapidwright.timing.sta.StaClockBinding} (a design's clocks, or a single ideal clock):
 * level-parallel, deterministic and incremental propagation of arrival and required times, setup slacks and
 * criticalities, and on demand hold slacks at FAST_MIN and SLOW_MIN, which are reported but not
 * optimized.</li>
 * <li>{@link com.xilinx.rapidwright.timing.sta.TimingReport} and
 * {@link com.xilinx.rapidwright.timing.sta.StaClockReport}: the critical path and the timing summary per
 * clock group.</li>
 * </ul>
 * See <a href="https://www.rapidwright.io/docs/Timing_Model.html">Timing Model and Timing Analysis</a>
 * in the RapidWright documentation.
 */
package com.xilinx.rapidwright.timing.sta;
