/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
 * 
 */
package com.xilinx.rapidwright.util;

/**
 * These are the directives found as options to 'place_design -directive',
 * Created on: Mar 20, 2018
 */
public enum PlacerDirective {

	/** Increased placer effort in detail placement and post-placement optimization.*/
	Explore,
	/** Timing-driven placement of RAM and DSP blocks.
	  The RAM and DSP block locations are finalized early in the placement
	  process and are used as anchors to place the remaining logic.*/
	EarlyBlockPlacement, 
	/** Wire length-driven placement of RAM and DSP
    blocks. Override timing-driven placement by directing the Vivado placer
    to minimize the distance of connections to and from blocks. */
    WLDrivenBlockPlacement,
    /** Increases estimated delay of high fanout and
    long-distance nets. Three levels of pessimism are supported: high,
    medium, and low. ExtraNetDelay_high applies the highest level of
    pessimism. */
    ExtraNetDelay_high, 
    /** Increases estimated delay of high fanout and
    long-distance nets. Three levels of pessimism are supported: high,
    medium, and low. ExtraNetDelay_low applies the lowest level of
    pessimism. */
    ExtraNetDelay_low, 
    /** Spreads logic throughout the device to avoid
    creating congested regions. Three levels are supported: high, medium,
    and low. AltSpreadLogic_high achieves the highest level of spreading. */
    AltSpreadLogic_high, 
    /** Spreads logic throughout the device to avoid
    creating congested regions. Three levels are supported: high, medium,
    and low. AltSpreadLogic_medium achieves a medium level of spreading
    compared to low and high. */
    AltSpreadLogic_medium, 
    /** Spreads logic throughout the device to avoid
    creating congested regions. Three levels are supported: high, medium,
    and low. AltSpreadLogic_low achieves the lowest level of spreading. */
    AltSpreadLogic_low, 
    /** Increased placer effort in post-placement optimization. */
    ExtraPostPlacementOpt, 
    /** Use an alternate algorithm for timing-driven placement
    with greater effort for timing. */
    ExtraTimingOpt, 
    /** Distribute logic across SLRs.
    SSI_SpreadLogic_high achieves the highest level of distribution. */
    SSI_SpreadLogic_high, 
    /** Distribute logic across SLRs. SSI_SpreadLogic_low
    achieves a minimum level of logic distribution, while reducing
    placement runtime. */
    SSI_SpreadLogic_low, 
    /** Partition across SLRs and allocate extra area for
    regions of higher connectivity.  */
    SSI_SpreadSLLs, 
    /** Partition across SLRs while attempting to balance
    SLLs between SLRs. */
    SSI_BalanceSLLs, 
    /** Partition across SLRs to balance number of cells
    between SLRs. */
    SSI_BalanceSLRs, 
    /** Direct the placer to attempt to place logic closer
    together in each SLR. */
    SSI_HighUtilSLRs, 
    /** Run fewest iterations, trade higher design
    performance for faster runtime. */
    RuntimeOptimized, 
    /** Absolute, fastest runtime, non-timing-driven, performs the
    minimum required placement for a legal design. */
    Quick, 
    /** Run place_design with default settings. */
    Default, 
}
