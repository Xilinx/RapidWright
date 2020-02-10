/* 
 * Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.device;

import java.util.regex.Pattern;

/** Xilinx Architecture Series or Generations.  Will require an update with each new series. */

public enum Series {
	
	// See https://www.xilinx.com/support/documentation/user_guides/ug470_7Series_Config.pdf Table 5-24
	// See https://www.xilinx.com/support/documentation/user_guides/ug570-ultrascale-configuration.pdf Table 9-20, 9-21
	//             RegEx|BlockType|Top/Bottom|Row Addr|Col Addr|Minor Addr
	Series7       ("\\-7",          23,  3, 22, 17,  5,  7, 10,  0,  7, 50),
	UltraScale    ("UltraScale$",   23,  3, -1, 17,  6,  7, 10,  0,  7, 60), 
	UltraScalePlus("UltraScale\\+", 24,  3, -1, 18,  6,  8, 10,  0,  8, 60),
	Versal        ("Versal",        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
	
	private Pattern archRegex;
	
	private int blockTypeLSB;
	
	private int blockTypeWidth;
	
	private int topBotBitLSB;
	
	private int rowLSB; 
	
	private int rowWidth;
	
	private int columnLSB;
	
	private int columnWidth;
	
	private int minorAddrLSB;
	
	private int minorAddrWidth;
	
	private int cleHeight;

	private Series(String regex, int blockTypeLSB, int blockTypeWidth, 
					int topBotBit, int rowLSB, int rowWidth,
					int colLSB, int colWidth, int minorLSB, int minorWidth,
					int cleHeight) {
		archRegex = Pattern.compile(regex);
		this.blockTypeLSB = blockTypeLSB;
		this.blockTypeWidth = blockTypeWidth;
		this.topBotBitLSB = topBotBit;
		this.rowLSB = rowLSB;
		this.rowWidth = rowWidth;
		this.columnLSB = colLSB;
		this.columnWidth = colWidth;
		this.minorAddrLSB = minorLSB;
		this.minorAddrWidth = minorWidth;
		this.cleHeight = cleHeight;
	}

	public Pattern getArchRegex(){
		return archRegex;
	}

	public int getBlockTypeLSB() {
		return blockTypeLSB;
	}

	public int getBlockTypeWidth() {
		return blockTypeWidth;
	}
	
	public int getBlockTypeMask() {
		return ((1 << getBlockTypeWidth()) - 1) << getBlockTypeLSB();
	}

	public int getTopBotBitLSB() {
		return topBotBitLSB;
	}
	
	public int getTopBotBitMask() {
		return hasTopBotBit() ? 1 << topBotBitLSB : 0;
	}
	
	public boolean hasTopBotBit() {
		return topBotBitLSB != -1;
	}

	public int getRowLSB() {
		return rowLSB;
	}

	public int getRowWidth() {
		return rowWidth;
	}

	public int getRowMask() {
		return ((1 << getRowWidth()) - 1) << getRowLSB();
	}
	
	public int getColumnLSB() {
		return columnLSB;
	}

	public int getColumnWidth() {
		return columnWidth;
	}

	public int getColumnMask() {
		return ((1 << getColumnWidth()) - 1) << getColumnLSB();
	}
	
	public int getMinorAddrLSB() {
		return minorAddrLSB;
	}

	public int getMinorAddrWidth() {
		return minorAddrWidth;
	}
	
	public int getMinorAddrMask() {
		return ((1 << getMinorAddrWidth()) - 1) << getMinorAddrLSB();
	}
	
	public int getCLEHeight() {
		return cleHeight;
	}
	
}