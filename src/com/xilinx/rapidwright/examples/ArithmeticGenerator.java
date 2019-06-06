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

package com.xilinx.rapidwright.examples;


/**
 * Serves as a parent class for {@link AddSubGenerator} and {@link MultGenerator}. 
 * 
 * @author clavin
 */
public abstract class ArithmeticGenerator {

	protected static final String PART_OPT = "p";
	protected static final String DESIGN_NAME_OPT = "d";
	protected static final String OUT_DCP_OPT = "o";
	protected static final String CLK_NAME_OPT = "c";
	protected static final String CLK_CONSTRAINT_OPT = "x";
	protected static final String WIDTH_OPT = "w";
	protected static final String VERBOSE_OPT = "v";
	protected static final String HELP_OPT = "h";

	
	public static final int BITS_PER_CLE = 8;
	public static String INPUT_A_NAME = "A";
	public static String INPUT_B_NAME = "B";
	public static String RESULT_NAME = "S";
}
