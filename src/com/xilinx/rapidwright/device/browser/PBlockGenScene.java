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
package com.xilinx.rapidwright.device.browser;

import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsRectItem;
import com.trolltech.qt.gui.QPen;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;

/**
 * WIP.
 * 
 * Created on: Jun 18, 2015
 */
public class PBlockGenScene extends DeviceBrowserScene {

	/**
	 * @param device
	 * @param we
	 * @param hideTiles
	 * @param drawPrimitives
	 * @param browser
	 */
	public PBlockGenScene(Device device, boolean hideTiles,
			boolean drawPrimitives, DeviceBrowser browser) {
		super(device, hideTiles, drawPrimitives, browser);
	}
	
	
	private static QColor highlightColor = new QColor(0, 255, 0, 190);
	private static QPen highlightPen = new QPen(highlightColor);
	private static QBrush highlightBrush = new QBrush(highlightColor);
	
	public void highlightTile(Tile t){		
		QGraphicsRectItem rect = addRect(tileSize,tileSize, tileSize - 2, tileSize - 2, highlightPen, highlightBrush);
		rect.setPos(t.getColumn() * tileSize, t.getRow() * tileSize);
	}
	
}
