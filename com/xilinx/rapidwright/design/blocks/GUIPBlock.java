/*
 * 
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
package com.xilinx.rapidwright.design.blocks;

import com.trolltech.qt.QVariant;
import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.core.Qt.PenStyle;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsPolygonItem;
import com.trolltech.qt.gui.QGraphicsSceneMouseEvent;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QPolygonF;
import com.trolltech.qt.gui.QGraphicsItem.GraphicsItemChange;
import com.trolltech.qt.gui.QGraphicsItem.GraphicsItemFlag;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.TileScene;

/**
 * WIP.  Represents a PBlock in a GUI context.
 * 
 * Created on: Apr 27, 2017
 */
public class GUIPBlock extends QGraphicsPolygonItem {
	
	public static final QColor transYellow = new QColor(255,255,0,100);
	
	private PBlock pb;
	
	public Signal1<Boolean> selected = new Signal1<Boolean>();
	
	public Signal0 moved = new Signal0();
	
	private TileScene scene;

	private boolean isSelected;
	
	private Tile pressedTile;
	
	private int lastSnapToDx = 0;
	
	private int lastSnapToDy = 0;
	
	private Tile lastSnapToBottomLeftTile = null;
	
	public GUIPBlock(PBlock pb, TileScene scene){
		this.pb = pb;
		this.scene = scene;
		
		updatePBlock();
		
		this.setFlag(GraphicsItemFlag.ItemIsMovable, true);
		this.setFlag(GraphicsItemFlag.ItemIsSelectable, true);
		
		this.selected.connect(this, "showCompatibleLocations(boolean)");
	}
	
	public void updatePBlock(){
		lastSnapToBottomLeftTile = pb.getBottomLeftTile();
		Tile tl = pb.getTopLeftTile();
		Tile br = pb.getBottomRightTile();
		int ts = scene.getTileSize();
		QPointF topLeft = new QPointF(ts*scene.getDrawnTileX(tl), ts*scene.getDrawnTileY(tl));
		QPointF botRight = new QPointF(ts*scene.getDrawnTileX(br)+ts, ts*scene.getDrawnTileY(br)+ts);
		QRectF pRect = new QRectF(topLeft, botRight);
		QPolygonF pPolygon = new QPolygonF(pRect);
		setPolygon(pPolygon);
		setToolTip(pb.toString());
		this.setPen(new QPen(QColor.yellow,5.0,PenStyle.DotLine));
		this.setBrush(new QBrush(transYellow));
	}
	
	public Object itemChange(GraphicsItemChange change, Object value) {
		if (change == GraphicsItemChange.ItemSelectedHasChanged) {
			selected.emit(QVariant.toBoolean(value));
		} else if (change == GraphicsItemChange.ItemPositionHasChanged && scene() != null) {
			moved.emit();
		} else if (change == GraphicsItemChange.ItemPositionChange && scene() != null) {
			// value is the new position.
			QPointF newPos = (QPointF) value;
			int ts = scene.tileSize;

			int tileX = (int) Math.round(newPos.x() / ts);
			int tileY = (int) Math.round(newPos.y() / ts);
			int dx = tileX * ts;
			int dy = tileY * ts;

			int sceneWidth = ts * scene.cols;
			int sceneHeight = ts * scene.rows;
			int leftEdge = ts * pb.getTopLeftTile().getColumn();
			int rightEdge = ts * pb.getBottomRightTile().getColumn();
			int topEdge = ts *  pb.getTopLeftTile().getRow();
			int botEdge = ts * pb.getBottomRightTile().getRow();

			// Check boundary conditions
			if(topEdge+dy < 0) dy = -topEdge;
			if(dy+botEdge > sceneHeight) dy = sceneHeight - botEdge;		
			if(leftEdge+dx < 0) dx = -leftEdge;
			if(dx+rightEdge > sceneWidth) dx = sceneWidth - rightEdge;
			
			// Check how many tile columns we might be skipping (not drawn)
			Tile bottomLeft = pb.getBottomLeftTile();
			Tile topRight = pb.getTopRightTile();

			//int xEdge = tileX > 0 ? pb.getBottomRightTile().getColumn() : bottomLeft.getColumn();  
			//int xCurr = scene.getCurrTile().getColumn();
			
			//int yEdge = tileY > 0 ? pb.getBottomLeftTile().getRow() : topRight.getRow();  
			//int yCurr = scene.getCurrTile().getRow();
			
			//dx = xCurr - Math.round(xEdge / ((float)ts));
			//dy = yCurr - Math.round(yEdge / ((float)ts));
//			System.out.println("Moving pblock: " + tileX + " " + tileY);
			System.out.println("****************************");
			System.out.println("* " + pb.toString());
			if(pb.movePBlock(tileX, tileY)){
				Tile newLowerLeft = pb.getBottomLeftTile();
				lastSnapToDx = (newLowerLeft.getColumn() - lastSnapToBottomLeftTile.getColumn()) * ts;
				lastSnapToDy = (newLowerLeft.getRow() - lastSnapToBottomLeftTile.getRow()) * ts;
				lastSnapToBottomLeftTile = newLowerLeft;
				System.out.println("We Moved the pblock by " + dx + "," + dy + " " + pb.toString());
			}
			newPos.setX(lastSnapToDx);
			newPos.setY(lastSnapToDy);

			return newPos;
		}
		return super.itemChange(change, value);
	}

	
	public void showCompatibleLocations(boolean value){
		System.out.println("TODO - GuiPBlock.showCompatibleLocations()");
	}
	
	public boolean isGrabbed() {
		return isSelected;
	}

	public void mousePressEvent(QGraphicsSceneMouseEvent event) {
		isSelected = true;
		QPointF mousePos = event.scenePos();
		System.out.println("You Pressed me!");
		super.mousePressEvent(event);
	}
	
	public void mouseReleaseEvent(QGraphicsSceneMouseEvent event) {
		isSelected = false;

		System.out.println("You UNPressed me!");
		super.mouseReleaseEvent(event);
	}
	
	@Override
	public void mouseMoveEvent(QGraphicsSceneMouseEvent event) {
		QPointF mousePos = event.scenePos();
		Tile curr = scene.getTile(mousePos.x(), mousePos.y());
		
		//System.out.println("Moving to tile: " + curr);
		//pb.movePBlock(pressedTile.getColumn()-curr.getColumn(), pressedTile.getRow()-curr.getRow());
		pressedTile = curr;
		super.mouseMoveEvent(event);
	}
}
