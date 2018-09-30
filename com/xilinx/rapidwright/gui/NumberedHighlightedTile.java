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

import com.trolltech.qt.gui.QFont;
import com.trolltech.qt.gui.QGraphicsRectItem;
import com.trolltech.qt.gui.QGraphicsTextItem;
import com.xilinx.rapidwright.device.Tile;

public class NumberedHighlightedTile  extends QGraphicsRectItem{
	/** */
	protected QGraphicsTextItem text;
	/** */
	protected TileScene scene;
	/** */
	protected static QFont font4 = new QFont("Arial", 4);
	/** */
	protected static QFont font6 = new QFont("Arial", 6);
	/** */
	protected static QFont font8 = new QFont("Arial", 8);
	
	
	public NumberedHighlightedTile(Tile t, TileScene scene, int number){
		super(0, 0, scene.tileSize - 2, scene.tileSize - 2);
		this.scene = scene;
		this.text = new QGraphicsTextItem(Integer.toString(number));
		int x = scene.getDrawnTileX(t) * scene.tileSize;
		int y = scene.getDrawnTileY(t) * scene.tileSize;
		text.setPos(x-4, y);
		if(number < 100){
			text.setFont(font8);			
		}else if(number < 1000){
			text.setFont(font6);
		}else {
			text.setFont(font4);
		}

		this.moveBy(x, y);
		this.scene.addItem(this);
		this.scene.addItem(text);
	}
	
	public void remove(){
		scene.removeItem(text);
		scene.removeItem(this);
	}
}
