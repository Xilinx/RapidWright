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
package com.xilinx.rapidwright.placer.handplacer;

import com.trolltech.qt.core.Qt.PenStyle;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QPen;
import com.xilinx.rapidwright.device.Tile;

public class PartitionLine{
	
	public Tile start;
	
	public Tile end;
	
	public PartitionLine(Tile start, Tile end){
		this.start = start;
		this.end = end;
	}
	
	public void drawPartitionLine(FloorPlanScene scene){
		QPen pen = new QPen(QColor.white, 20);
		pen.setStyle(PenStyle.DashLine);
		scene.addLine(start.getColumn()*scene.tileSize,
				      start.getRow()*scene.tileSize,
				      end.getColumn()*scene.tileSize,
				      end.getRow()*scene.tileSize,
				      pen);
	}
}
