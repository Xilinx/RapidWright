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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import com.trolltech.qt.QVariant;
import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsPolygonItem;
import com.trolltech.qt.gui.QGraphicsSceneMouseEvent;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QPolygonF;
import com.trolltech.qt.gui.QGraphicsItem.GraphicsItemChange;
import com.trolltech.qt.gui.QGraphicsItem.GraphicsItemFlag;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.gui.TileScene;
import com.xilinx.rapidwright.placer.handplacer.GUIMultiNetLine;
import com.xilinx.rapidwright.util.Utils;

public class GUIModuleInst extends QGraphicsPolygonItem {

	public Signal1<Boolean> selected = new Signal1<Boolean>();
	public Signal0 moved = new Signal0();
	private ModuleInst moduleInst;
	private TileScene scene;
	private ArrayList<HMTile> hmTiles;
	private boolean isValidlyPlaced;
	private boolean gutsHidden;
	private QPointF anchorOffset;
	private QPolygonF shape;
	private boolean grabbed;
	private ArrayList<Integer> occupiedTilesX;
	private ArrayList<Integer> occupiedTilesY;
	private ArrayList<GUIMultiNetLine> myLines;

	public GUIModuleInst(ModuleInst modInst, TileScene scene, boolean movable){
		this.moduleInst = modInst;
		this.scene = scene;
		this.hmTiles = new ArrayList<HMTile>();
		this.gutsHidden = true;
		this.isValidlyPlaced = true;
		
		this.occupiedTilesX = new ArrayList<Integer>();
		this.occupiedTilesY = new ArrayList<Integer>();
		init();

		this.setFlag(GraphicsItemFlag.ItemIsMovable, movable);
		this.setFlag(GraphicsItemFlag.ItemIsSelectable, true);
		//this.setFlag(GraphicsItemFlag.ItemSendsGeometryChanges, true);
		this.moved.connect(this, "checkPlacement()");
		this.selected.connect(this, "bringToFront(boolean)");
	}

	@SuppressWarnings("unused")
	private void printPos() {
		System.out.println("this:" + this.pos());
		for (int i = 0; i < hmTiles.size() && i < 5; i++) {
			System.out.println("   tile(" + i + "):"
					+ this.hmTiles.get(i).pos());
		}
	}

	private void init() {
		HashSet<Tile> occupiedTiles = new HashSet<Tile>();
		HashSet<Tile> tilesWithSLICEM = new HashSet<Tile>();
		Collection<SiteInst> instances = null;
		Collection<Net> nets = null;
		Tile anchorTile = null;
		int minRow = Integer.MAX_VALUE;
		int minCol = Integer.MAX_VALUE;
		int maxRow = -1;
		int maxCol = -1;
		if (moduleInst.getInsts().get(0).isPlaced()) {
			instances = moduleInst.getInsts();
			nets = moduleInst.getNets();
			anchorTile = moduleInst.getAnchor().getTile();
		} else {
			instances = moduleInst.getModule().getSiteInsts();
			nets = moduleInst.getModule().getNets();
			anchorTile = moduleInst.getModule().getAnchor().getTile();
		}

		for (SiteInst inst : instances) {
			Tile tile = inst.getTile();
			if(inst.getSiteTypeEnum().equals(SiteTypeEnum.SLICEM)){
				tilesWithSLICEM.add(tile);
			}
			if (!occupiedTiles.contains(tile)) {
				occupiedTiles.add(tile);
				//int col = tile.getColumn();
				//int row = tile.getRow();
				int col = scene.getDrawnTileX(tile);
				int row = scene.getDrawnTileY(tile);

				maxCol = (maxCol >= col) ? maxCol : col;
				maxRow = (maxRow >= row) ? maxRow : row;
				String tileTypeStr = tile.getTileTypeEnum().toString();
				if (tileTypeStr.startsWith("BRAM")
						|| tileTypeStr.startsWith("DSP")) {
					row = row - 3;
				}
				minCol = (minCol <= col) ? minCol : col;
				minRow = (minRow <= row) ? minRow : row;
			}
		}
		for (Net net : nets) {
			for (PIP pip : net.getPIPs()) {
				Tile tile = pip.getTile();
				if (!occupiedTiles.contains(tile)) {
					occupiedTiles.add(tile);
					//int col = tile.getColumn();
					//int row = tile.getRow();
					int col = scene.getDrawnTileX(tile);
					int row = scene.getDrawnTileY(tile);
					minCol = (minCol <= col) ? minCol : col;
					minRow = (minRow <= row) ? minRow : row;
					maxCol = (maxCol >= col) ? maxCol : col;
					maxRow = (maxRow >= row) ? maxRow : row;
				}
			}
		}

		int widthInTiles = maxCol - minCol + 1;
		int heightInTiles = maxRow - minRow + 1;
		boolean[][] hmTileMap = new boolean[heightInTiles][widthInTiles];
		for (int i = 0; i < heightInTiles; i++) {
			for (int j = 0; j < widthInTiles; j++) {
				hmTileMap[i][j] = false;
			}
		}

		for (Tile tile : occupiedTiles) {
			//int tileX = tile.getColumn() - minCol;
			//int tileY = tile.getRow() - minRow;
			int tileX = scene.getDrawnTileX(tile) - minCol;
			int tileY = scene.getDrawnTileY(tile) - minRow;
			if (tile.getTileTypeEnum().toString().startsWith("BRAM")
					|| tile.getTileTypeEnum().toString().startsWith("DSP")) {
				hmTileMap[tileY][tileX] = true;
				hmTileMap[tileY - 1][tileX] = true;
				hmTileMap[tileY - 2][tileX] = true;
				hmTileMap[tileY - 3][tileX] = true;

			} else if (tileX >= 0 && tileX < widthInTiles && tileY >= 0
					&& tileY < heightInTiles) {
				hmTileMap[tileY][tileX] = true;
			}
			
			addHMTile(tile, tileX, tileY, tilesWithSLICEM.contains(tile), tile.equals(anchorTile));
		}

		QPolygonF hmPolygon = createOutline(hmTileMap);
		this.setPolygon(hmPolygon);
		this.setShape(hmPolygon);
		this.moveBy(minCol * scene.tileSize, minRow * scene.tileSize);
		this.hideGuts();
		this.setAnchorOffset();
		this.setToolTip(moduleInst.getName()+"\n"+moduleInst.getModule().getName());
	}

	private void addHMTile(Tile tile, int tileX, int tileY, boolean hasSLICEM, boolean isAnchor) {
		HMTile hmTile = new HMTile(tile, scene, this, hasSLICEM, isAnchor);
		hmTile.moveBy(tileX * scene.tileSize, tileY * scene.tileSize);
		hmTile.setBrush(new QBrush(QColor.white));
		hmTiles.add(hmTile);

	}

	private QPolygonF createOutline(boolean[][] hmTileMap) {
		int height = hmTileMap.length;
		int width = hmTileMap[0].length;
		boolean changed;
		do{	
			changed = false;
			// fill in holes in tile rows
			for (int i = 0; i < height; i++) {
				int rightJ = -1;
				int leftJ = width;
				for (int j = width - 1; j >= 0; j--) {
					if (hmTileMap[i][j]) {
						rightJ = j;
						break;
					}
				}
				if (rightJ == -1)
					continue;
				for (int j = 0; j < width; j++) {
					if (hmTileMap[i][j]) {
						leftJ = j;
						break;
					}
				}
				for (int j = leftJ + 1; j < rightJ; j++){
					if(!hmTileMap[i][j]){
						hmTileMap[i][j] = true;
						changed = true;
					}
				}
			}
			
	
			// fill in holes in tile cols
			for (int j = 0; j < width; j++) {
				int bottomI = -1;
				int topI = height;
				for (int i = height - 1; i >= 0; i--) {
					if (hmTileMap[i][j]) {
						bottomI = i;
						break;
					}
				}
				if (bottomI == -1)
					continue;
				for (int i = 0; i < height; i++) {
					if (hmTileMap[i][j]) {
						topI = i;
						break;
					}
				}
				for (int i = topI + 1; i < bottomI; i++)
					if(!hmTileMap[i][j]){
						hmTileMap[i][j] = true;
						changed = true;
					}
			}
		}while(changed);		
		
		
		int tileSize = scene.tileSize;
		QPolygonF hmPolygon = new QPolygonF();
		// Go down right side, adding profile points
		for (int i = 0; i < height; i++) {
			for (int j = 0; j < width; j++) {
				if (hmTileMap[i][j]
						&& (j + 1 > width - 1 || !hmTileMap[i][j + 1])) {
					QPointF pTR = new QPointF((j + 1) * tileSize - 1, i
							* tileSize - 1);
					hmPolygon.add(this.pos().add(pTR));
					QPointF pBR = new QPointF((j + 1) * tileSize - 1, (i + 1)
							* tileSize - 1);
					hmPolygon.add(this.pos().add(pBR));
					break;
				}
			}
		}
		// Go up left side, adding profile points
		for (int i = height - 1; i >= 0; i--) {
			for (int j = width - 1; j >= 0; j--) {
				if (hmTileMap[i][j] && (j - 1 < 0 || !hmTileMap[i][j - 1])) {
					QPointF pBL = new QPointF((j) * tileSize - 1, (i + 1)
							* tileSize - 1);
					hmPolygon.add(this.pos().add(pBL));
					QPointF pTL = new QPointF((j) * tileSize - 1, (i)
							* tileSize - 1);
					hmPolygon.add(this.pos().add(pTL));
					break;
				}
			}
		}
		return hmPolygon;
	}

	@SuppressWarnings("unused")
	private void bringToFront(boolean selected) {
		if (selected) {
			double z = this.zValue() + 1;
			this.setZValue(z);

		} else {
			double z = this.zValue() - 1;
			this.setZValue(z);

		}
	}

	/**
	 * TODO - This method is overly optimistic and needs to be updated - 3/8/16 - CL
	 */
	public void checkPlacement() {
	
		HashSet<GUIModuleInst> prevCollidingGMIs = new HashSet<GUIModuleInst>();
		HashSet<GUIModuleInst> newCollidingGMIs = new HashSet<GUIModuleInst>();
		for(int i=0; i<occupiedTilesX.size(); i++){
			HashSet<GUIModuleInst> prevGMISet = scene.tileOccupantCount[occupiedTilesY.get(i)][occupiedTilesX.get(i)];
			prevGMISet.remove(this);
			prevCollidingGMIs.addAll(prevGMISet);
		}
		occupiedTilesX.clear();
		occupiedTilesY.clear();
		
		boolean isPlacementValid = true;
		boolean isColliding = false;
		
		for (HMTile hmTile : this.hmTiles) {
			//Check to see if this HMTile collides with any other GMIs (other than parent)
			
			boolean tileColliding = false;
			
			int x = (int) Math.floor(hmTile.scenePos().x()
					/ scene.tileSize);
			int y = (int) Math.floor(hmTile.scenePos().y()
					/ scene.tileSize);
			if (x >= scene.cols || y >= scene.rows || x < 0 || y < 0){
				System.out.println("ERROR - Moved out of bounds:"+this.moduleInst.getName());
				break;
			}
			TileTypeEnum myType = hmTile.getTile().getTileTypeEnum();
			//if (myType.toString().startsWith("DSP")
			//		|| myType.toString().startsWith("BRAM")) {
			//	y += 3;
			//}
			//TileTypeEnum devType = fpScene.device.getTile(y, x).getType();
			
			occupiedTilesX.add(x);
			occupiedTilesY.add(y);
			HashSet<GUIModuleInst> gmiSet = scene.tileOccupantCount[y][x];
			newCollidingGMIs.addAll(gmiSet);
			gmiSet.add(this);
			int tileOccupation = gmiSet.size();
			if(tileOccupation > 1)
				tileColliding = true;
			
			TileTypeEnum devType = scene.drawnTiles[y][x].getTileTypeEnum();
			if (myType.equals(devType) 
					|| Utils.isInterConnect(myType) && Utils.isInterConnect(devType)){
				if(tileColliding){
					hmTile.setState(GUIShapeState.COLLIDING);
					isColliding = true;
				}else{
					hmTile.setState(GUIShapeState.VALID);
				}
			} else {
				hmTile.setState(GUIShapeState.INVALID);
				isPlacementValid = false;
			}
		}
		isValidlyPlaced = isPlacementValid;

		if (isPlacementValid) {
			if(isColliding){
				this.setState(GUIShapeState.COLLIDING);
			}else{
				this.setState(GUIShapeState.VALID);	
			}
		} else {
			this.setState(GUIShapeState.INVALID);
		}
		
		
		StackTraceElement aParentStack = new Throwable().fillInStackTrace().getStackTrace()[1];
		//This is here to prevent infinite recursion.  It makes sure
		// that checkPlacement is only called on the colliding GMIs iff
		// this function was called by something other than itself
		if(!aParentStack.getMethodName().equals("checkPlacement")){
			for(GUIModuleInst gmi : prevCollidingGMIs){
				gmi.checkPlacement();
			}
			for(GUIModuleInst gmi : newCollidingGMIs){
				gmi.checkPlacement();
			}
		}

	}
	public void showGuts(){
		for (HMTile hmTile : this.hmTiles) {
			hmTile.show();
		}
		this.setBrush(new QBrush(QColor.transparent));
		checkPlacement();
	}
	public void hideGuts(){
		for (HMTile hmTile : this.hmTiles) {
			hmTile.hide();
		}
		checkPlacement();
	}
	public void mouseDoubleClickEvent(QGraphicsSceneMouseEvent event) {
		if (gutsHidden) {
			gutsHidden = false;
			showGuts();
		} else {
			gutsHidden = true;
			hideGuts();
		}
		super.mouseDoubleClickEvent(event);
	}

	public Object itemChange(GraphicsItemChange change, Object value) {
		if (change == GraphicsItemChange.ItemSelectedHasChanged) {
			selected.emit(QVariant.toBoolean(value));
		} else if (change == GraphicsItemChange.ItemPositionHasChanged
				&& scene() != null) {
			moved.emit();
		} else if (change == GraphicsItemChange.ItemPositionChange
				&& scene() != null) {
			// value is the new position.
			QPointF newPos = (QPointF) value;
			QRectF rect = scene().sceneRect();

			double width = this.boundingRect().width();
			width = Math.floor(width / scene.tileSize);
			double height = this.boundingRect().height();
			height = Math.floor(height / scene.tileSize);
			QPointF p = rect.bottomRight();
			//p.setX((fpScene.device.getColumns() - width) * fpScene.tileSize);
			//p.setY((fpScene.device.getRows() - height) * fpScene.tileSize);
			
			p.setX((scene.cols - width) * scene.tileSize);
			p.setY((scene.rows - height) * scene.tileSize);
			rect.setBottomRight(p);
			if (!rect.contains(newPos)) {
				// Keep the item inside the scene rect.
				newPos.setX(Math.min(rect.right(), Math.max(newPos.x(), rect
						.left())));
				newPos.setY(Math.min(rect.bottom(), Math.max(newPos.y(), rect
						.top())));
			}
			long x = Math.round(newPos.x() / scene.tileSize)
					* scene.tileSize;
			long y = Math.round(newPos.y() / scene.tileSize)
					* scene.tileSize;
			newPos.setX(x);
			newPos.setY(y);
			return newPos;
		}
		return super.itemChange(change, value);
	}
	
	public boolean isGrabbed() {
		return grabbed;
	}

	public void mousePressEvent(QGraphicsSceneMouseEvent event) {
		grabbed = true;
		super.mousePressEvent(event);
	}
	
	public void mouseReleaseEvent(QGraphicsSceneMouseEvent event) {
		grabbed = false;
		super.mouseReleaseEvent(event);
	}

	public ModuleInst getModuleInst() {
		return moduleInst;
	}
	
	public boolean isValidlyPlaced() {
		return isValidlyPlaced;
	}

	
	public void setAnchorOffset() {
		SiteInst anchorInst = null;
		if (moduleInst.getInsts().get(0).isPlaced()) {
			anchorInst  = moduleInst.getAnchor();
		} else {
			anchorInst = moduleInst.getModule().getAnchor();
		}
		//int x = anchorInst.getTile().getColumn();
		//int y = anchorInst.getTile().getRow();
		int x = scene.getDrawnTileX(anchorInst.getTile());
		int y = scene.getDrawnTileY(anchorInst.getTile());
		this.anchorOffset = (new QPointF(x*scene.tileSize,y*scene.tileSize)).subtract(this.pos());
	}

	public QPointF getAnchorOffset() {
		return anchorOffset;
	}
	
	public HMTile getHMTile(Tile tile){
		if(tile == null)
			return null;
		for(HMTile hmTile : hmTiles){
			if(hmTile.getTile().equals(tile))
				return hmTile;
		}
		return null;
	}
	
	public int getSizeInTiles(){
		return hmTiles.size();
	}
	
	public void setState(GUIShapeState newState){
		switch (newState) {
			case VALID:
				this.setPen(new QPen(HMTile.GREEN));
				if(gutsHidden)
					this.setBrush(new QBrush(HMTile.GREEN));
				else
					this.setBrush(new QBrush(QColor.transparent));
				break;
			case COLLIDING:
				this.setPen(new QPen(HMTile.ORANGE));
				if(gutsHidden)
					this.setBrush(new QBrush(HMTile.ORANGE));
				else
					this.setBrush(new QBrush(QColor.transparent));
				break;
			case INVALID:
				this.setPen(new QPen(HMTile.RED));
				if(gutsHidden)
					this.setBrush(new QBrush(HMTile.RED));
				else
					this.setBrush(new QBrush(QColor.transparent));
				break;
			default:
				break;
		}
	}

	/**
	 * @return the shape
	 */
	public QPolygonF getShape() {
		return shape;
	}

	/**
	 * @param shape the shape to set
	 */
	public void setShape(QPolygonF shape) {
		this.shape = shape;
	}
	
	public void addLine(GUIMultiNetLine line){
		if(myLines == null){
			myLines = new ArrayList<GUIMultiNetLine>();
		}
		myLines.add(line);
	}
	
	public void showMyLines(){
		if(myLines != null){
			for(GUIMultiNetLine line : myLines){
				line.show();
			}
		}
	}
	
	public void hideMyLines(){
		if(myLines != null){
			for(GUIMultiNetLine line : myLines){
				line.hide();
			}
		}
	}
}
