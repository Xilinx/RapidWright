/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.gui;

import com.trolltech.qt.gui.QFileDialog.Filter;

public class FileFilters {
	/** Xilinx Design Language File Filter */
	public static Filter xpnFilter = new Filter("Xilinx Physical Netlist Files (*.xpn)");
	/** Xilinx Design Language File Filter */
	public static Filter xdlFilter = new Filter("Xilinx Design Language Files (*.xdl)");
	/** Native Circuit Description File Filter */
	public static Filter ncdFilter = new Filter("Design Files (*.ncd)");
	/** Hard Macro File Filter */
	public static Filter nmcFilter = new Filter("Hard Macro Files (*.nmc)");
	/** Portable Document Format File Filter */
	public static Filter pdfFilter = new Filter("Portable Document Format Files (*.pdf)");
	/** Xilinx Trace Report File Filter */
	public static Filter twrFilter = new Filter("Xilinx Trace Report Files (*.twr)");
	/** EDK Microprocessor Hardware Specification File Filter */
	public static Filter mhsFilter = new Filter("Microprocessor Hardware Specification Files (*.mhs)");
	/** Design Checkpoint File Filter */ 
	public static Filter dcpFilter = new Filter("Design Checkpoint Files (*.dcp)");
	/** Impl Guide File Filter */
	public static Filter igFilter = new Filter("Implementation Guide Files (*.igf)");
}
