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
 * Clocks for multi-clock static timing analysis: the timing constraints, the clock network of a design,
 * and a model of its clock insertion delays.
 * <ul>
 * <li>{@link com.xilinx.rapidwright.timing.clock.TimingConstraints}: the clock-related XDC constraints
 * (clocks, generated clocks, clock groups, path exceptions, uncertainty, jitter, clock latency and
 * case analysis), read by a small Tcl interpreter ({@link com.xilinx.rapidwright.timing.clock.XdcInterpreter}).</li>
 * <li>{@link com.xilinx.rapidwright.timing.clock.ClockNetwork}: the clocks propagated through the
 * netlist to the clock pins, with the clocks that clock buffers, generators (MMCM, PLL, XPLL, X5PLL,
 * DPLL), dividers and GT transceivers derive, as Vivado names and aligns them.</li>
 * <li>{@link com.xilinx.rapidwright.timing.clock.ClockRelations}: the setup and hold relations of each
 * pair of clock edges, with the clock groups and exceptions.</li>
 * <li>{@link com.xilinx.rapidwright.timing.clock.ClockModel}: the insertion delay of each clock pin at four
 * corners and the clock pessimism removal (CRPR), on a tree of the clock routes timed by
 * {@link com.xilinx.rapidwright.timing.clock.ClockNodeDelayModel} with the fitted
 * {@link com.xilinx.rapidwright.timing.clock.ClockData} of the device family (from its timing data file).</li>
 * <li>{@link com.xilinx.rapidwright.timing.clock.UncertaintyModel}: the clock uncertainty of each pair of
 * clocks (system, input and discrete jitter, phase error, user uncertainty).</li>
 * </ul>
 * {@link com.xilinx.rapidwright.timing.sta.StaClockAnalysis} uses them on a
 * {@link com.xilinx.rapidwright.timing.sta.StaGraph}.
 * <p>
 * Unrelated clocks (no common primary clock or source) are false paths by default: the paths between
 * them are not timed, whereas Vivado times them (as unsafe clock interactions) unless clock groups or
 * false paths say otherwise. Most such crossings are asynchronous and constrained as such; timing them
 * would charge the router with requirements that the design does not have.
 * {@link com.xilinx.rapidwright.timing.clock.ClockRelations#setUnrelatedFalse(boolean)} times them as
 * Vivado does.
 * <p>
 * Known limitations:
 * <ul>
 * <li>Hold is timed at the slow corner (SLOW_MIN data and launch, SLOW_MAX capture) besides the fast one only
 * with the fitted cell arcs (whose arc provider has a second min corner), and only on demand (at the end of
 * routing); otherwise at the fast corner only.</li>
 * <li>The setup CRPR of a check is that of the launch with the latest arrival, and, on multi-SLR devices, of
 * the latest launch of another key (launch SLR, data path crossing an SLR boundary), whichever is worse; a
 * launch with a lower arrival and a smaller credit of the same key is not considered. For hold, the endpoints
 * with less than 500 ps of slack get the exact worst launch among those arriving within 600 ps of the earliest
 * (whose inter-SLR compensation ignores SLR crossings of the data path); later launches are not
 * considered.</li>
 * <li>set_clock_latency on pins or ports is ignored, as are -rise/-fall distinctions.</li>
 * </ul>
 */
package com.xilinx.rapidwright.timing.clock;
