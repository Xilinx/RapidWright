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
package com.xilinx.rapidwright.device.browser;

import com.trolltech.qt.core.Qt.PenStyle;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsLineItem;
import com.trolltech.qt.gui.QGraphicsSceneHoverEvent;
import com.trolltech.qt.gui.QGraphicsSceneMouseEvent;
import com.trolltech.qt.gui.QPen;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.TileScene;

/**
 * This class is used with the DeviceBrowser to draw wire connections
 * on the array of tiles.
 * @author Chris Lavin
 * Created on: Nov 26, 2010
 */
public class WireConnectionLine extends QGraphicsLineItem{
	/** Keeps a red pen handy for highlighting wire connections on mouse over */
	private static QPen highlighted  = new QPen(QColor.red, 0.25, PenStyle.SolidLine);
	/** Keeps a yellow pen for drawing the wire connections */
	private static QPen unHighlighted = new QPen(QColor.yellow, 0.25, PenStyle.SolidLine);
	/** The current DeviceBrowser scene */
	private TileScene scene;
	/** The current tile */
	private Tile tile;
	/** The current wire */
	private int wire;
	
	/** 
	 * Creates a new wire connection line.
	 * @param x1 Starting X coordinate.
	 * @param y1 Starting Y coordinate.
	 * @param x2 Ending X coordinate.
	 * @param y2 Ending Y coordinate.
	 * @param scene The DeviceBrowser scene.
	 * @param tile The tile.
	 * @param wire The wire.
	 */
	public WireConnectionLine(double x1, double y1, double x2, double y2, 
			TileScene scene, Tile tile, int wire){
		super(x1, y1, x2, y2);
		this.scene = scene;
		this.tile = tile;
		this.wire = wire;
		highlighted = new QPen(QColor.red, 0.25, PenStyle.SolidLine);
	}
	
	@Override
	public void hoverEnterEvent(QGraphicsSceneHoverEvent event){
		this.setPen(highlighted);
	}
	
	@Override
	public void hoverLeaveEvent(QGraphicsSceneHoverEvent event){
		this.setPen(unHighlighted);
	}
	
	@Override
	public void mousePressEvent(QGraphicsSceneMouseEvent event){
		if(scene.getClass().equals(DeviceBrowserScene.class)){
			((DeviceBrowserScene)scene).drawConnectingWires(tile, wire);			
		}
	}

	/**
	 * @return the scene
	 */
	public TileScene getScene() {
		return scene;
	}

	/**
	 * @param scene the scene to set
	 */
	public void setScene(TileScene scene) {
		this.scene = scene;
	}
}
