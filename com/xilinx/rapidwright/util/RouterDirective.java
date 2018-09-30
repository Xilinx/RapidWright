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
 * These are the directives found as options to 'route_design -directive',
 * Created on: Mar 20, 2018
 */
public enum RouterDirective {
	
	/** Causes the Vivado router to explore different critical path
	      routes based on timing, after an initial route. */
	Explore, 
	/** Prevents the router from relaxing timing to
	      complete routing. If the router has difficulty meeting timing, it will
	      run longer to try to meet the original timing constraints. */
	NoTimingRelaxation, 
	/** Uses detailed timing analysis throughout all
	      stages instead of just the final stages, and will run more global
	      iterations even when timing improves only slightly. */
	MoreGlobalIterations, 
	/** Adjusts the router`s internal cost functions to
	      emphasize delay over iterations, allowing a trade-off of runtime for
	      better performance. */
	HigherDelayCost, 
	/** Uses more accurate skew modeling throughout all
	      routing stages which may improve design performance on higher-skew
	      clock networks. */
	AdvancedSkewModeling, 
	/** (UltraScale only) Chooses alternate routing
	      algorithms that require extra runtime but may help resolve routing
	      congestion. */
	AlternateCLBRouting, 
	/** Run fewest iterations, trade higher design
	      performance for faster runtime. */
	RuntimeOptimized, 
	/** Absolute fastest runtime, non-timing-driven, performs the
	      minimum required routing for a legal design. */
	Quick, 
	/** Run route_design with default settings. */
	Default, 
}
