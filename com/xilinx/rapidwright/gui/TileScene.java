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

import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;

import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.core.QSize;
import com.trolltech.qt.core.QSizeF;
import com.trolltech.qt.core.Qt.PenStyle;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsPixmapItem;
import com.trolltech.qt.gui.QGraphicsRectItem;
import com.trolltech.qt.gui.QGraphicsScene;
import com.trolltech.qt.gui.QGraphicsSceneMouseEvent;
import com.trolltech.qt.gui.QImage;
import com.trolltech.qt.gui.QPainter;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QPixmap;
import com.trolltech.qt.gui.QImage.Format;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.Utils;

/**
 * This class is used for the design explorer although, it could 
 * be used for building other applications as well.
 * @author Chris Lavin
 */
public class TileScene extends QGraphicsScene{
	/** The actual square used to highlight a tile */
	public QGraphicsRectItem highlit;
	/** Pen used to draw tile cursor */
	public QPen cursorPen = new QPen(QColor.yellow, 2);
	/** The current X location of the mouse */
	public int currX;
	/** The current Y location of the mouse */
	public int currY;
	/** The previous X location of the mouse */
	public int prevX;
	/** The previous Y location of the mouse */
	public int prevY;
	/** The rendered size of each Tile */
	public int tileSize = 20;
	/** Number of tile columns being referenced on the device */
	public int cols;
	/** Number of tile rows being referenced on the device */
	public int rows;
	/** When hiding tiles, this contains the grid of drawn tiles */
	public Tile[][] drawnTiles;
	/** Gets the X coordinate of the tile in the drawnTiles grid */
	public HashMap<Tile,Integer> tileXMap;
	/** Gets the Y coordinate of the tile in the drawnTiles grid */
	public HashMap<Tile,Integer> tileYMap;
	/** Width of the lines drawn in between tiles when columns/rows are hidden */
	public double lineWidth = 1;
	/** The device corresponding to this scene */
	public Device device;
	/** The signal which updates the status bar */
	public Signal2<String, Tile> updateStatus = new Signal2<String, Tile>();
	/** The signal which is made when a mouse button is pressed */
	public Signal0 mousePressed = new Signal0();
	/** The current design associated with this scene */
	private Design design;
	/** This is the actual image shown in the scene of the FPGA fabric */
	public QImage qImage;
	/** This is the set of column tile types which should not be drawn */
	private HashSet<TileTypeEnum> tileColumnTypesToHide;
	/** This is the set of row tile types which should not be drawn */
	private HashSet<TileTypeEnum> tileRowTypesToHide;
	/** Qt Size container for the scene */
	private QSize sceneSize;
	/**  */
	public HashSet<GUIModuleInst>[][] tileOccupantCount;
	/**
	 * Empty constructor
	 */
	public TileScene(){
		setDesign(null);
		initializeScene(true, true);
	}
	
	/**
	 * Creates a new tile scene with a design. 
	 * @param design The design and device to associate with this scene.
	 * @param hideTiles A flag to hide/show certain tiles to make the fabric appear more homogeneous.
	 * @param drawPrimitives A flag to draw boxes to represent primitives. 
	 */
	public TileScene(Design design, boolean hideTiles, boolean drawPrimitives){
		setDesign(design);
		initializeScene(hideTiles, drawPrimitives);
	}
	
	/**
	 * Creates a new tile scene with a device. 
	 * @param device The device to associate with this scene.
	 * @param hideTiles A flag to hide/show certain tiles to make the fabric appear more homogeneous.
	 * @param drawPrimitives A flag to draw boxes to represent primitives. 
	 */
	public TileScene(Device device, boolean hideTiles, boolean drawPrimitives){
		setDevice(device);
		initializeScene(hideTiles, drawPrimitives);
	}
	
	public QSize getSceneSize(){
		return sceneSize;
	}
	
	/**
	 * Initializes the scene
	 * @param hideTiles
	 * @param drawPrimitives
	 */
	@SuppressWarnings("unchecked")
	public void initializeScene(boolean hideTiles, boolean drawPrimitives){
		this.clear();
		prevX = 0;
		prevY = 0;
		
		// Used to avoid a bug in Qt
		System.gc();

		if(device != null){
			tileColumnTypesToHide = new HashSet<TileTypeEnum>();
			tileRowTypesToHide = new HashSet<TileTypeEnum>();

			if(hideTiles){ 
				populateTileTypesToHide();
			}
				
			rows = device.getRows();
			cols = device.getColumns();
			sceneSize = new QSize((cols + 1) * (tileSize + 1), (rows + 1) * (tileSize + 1));
			setSceneRect(new QRectF(new QPointF(0, 0), new QSizeF(sceneSize)));
			drawFPGAFabric(drawPrimitives);
		} 
		else{
			setSceneRect(new QRectF(0, 0, tileSize + 1, tileSize + 1));
		}
		//this array is used to determine how many hard macros are
		// attempting to use each tile.
		tileOccupantCount = new HashSet[rows][cols];
		for(int y=0;y<rows;y++){
			for(int x=0;x<cols;x++){
				tileOccupantCount[y][x] = new HashSet<GUIModuleInst>();
			}
		}
	}
	
	private void drawFPGAFabric(boolean drawPrimitives){
		setBackgroundBrush(new QBrush(QColor.black));
		
		//Create transparent QPixmap that accepts hovers 
		//  so that moveMouseEvent is triggered
		QPixmap pixelMap = new QPixmap(sceneSize);
		pixelMap.fill(QColor.transparent);
		QGraphicsPixmapItem background = addPixmap(pixelMap);
		background.setAcceptsHoverEvents(true);
		background.setZValue(-1);
		
		// Draw colored tiles onto QPixMap
		qImage = new QImage(sceneSize, Format.Format_RGB16);
		QPainter painter = new QPainter(qImage);

		// Determine which columns and rows to not draw
		TreeSet<Integer> colsToSkip = new TreeSet<Integer>();
		TreeSet<Integer> rowsToSkip = new TreeSet<Integer>();
		for(Tile[] tileRow : device.getTiles()){
			for(Tile tile : tileRow){
				TileTypeEnum type = tile.getTileTypeEnum();
				if(tileColumnTypesToHide.contains(type)){
					colsToSkip.add(tile.getColumn());
				}
				if(tileRowTypesToHide.contains(type)){
					rowsToSkip.add(tile.getRow());
				}
			}
		}
		
		// Create new tile layout without hidden tiles
		int i=0,j=0;
		drawnTiles = new Tile[rows-rowsToSkip.size()][cols-colsToSkip.size()];
		tileXMap = new HashMap<Tile, Integer>();
		tileYMap = new HashMap<Tile, Integer>();
		for(int row = 0; row < rows; row++) {
			if(rowsToSkip.contains(row)) continue;
			for (int col = 0; col < cols; col++) {
				if(colsToSkip.contains(col)) continue;
				Tile tile = device.getTile(row, col);
				drawnTiles[i][j] = tile;
				tileXMap.put(tile, j);
				tileYMap.put(tile, i);
				j++;
			}
			i++;
			j=0;
		}
		rows = rows-rowsToSkip.size();
		cols = cols-colsToSkip.size();
		
		//Draw dashed lines where rows/columns have been removed
		QPen missingTileLinePen = new QPen(QColor.lightGray, 2, PenStyle.DashLine);
		painter.setPen(missingTileLinePen);
		i = 0;
		for(int col : colsToSkip){
			int realCol = col - i;
			painter.drawLine(tileSize*realCol-1, 0, tileSize*realCol-1, rows*tileSize-3);
			i++;
		}
		i=0;
		for(int row : rowsToSkip){
			int realRow = row - i;
			painter.drawLine(0,tileSize*realRow-1, cols*tileSize-3,tileSize*realRow-1);
			i++;
		}
		
		// Draw the tile layout
		int offset = (int) Math.ceil((lineWidth / 2.0));
		
		for(int y = 0; y < rows; y++){
			for(int x = 0; x < cols; x++){
				Tile tile = drawnTiles[y][x];
				TileTypeEnum tileTypeEnum = tile.getTileTypeEnum();

				// Set pen color based on current tile
				QColor color = TileColors.getSuggestedTileColor(tile);
				painter.setPen(color);
				
				int rectX = x * tileSize;
				int rectY = y * tileSize;
				int rectSide = tileSize - 2 * offset;

				if(drawPrimitives){
					if(Utils.isCLB(tileTypeEnum)){
						drawCLB(painter, rectX, rectY, rectSide);
					}else if(Utils.isSwitchBox(tileTypeEnum)){
						drawSwitchBox(painter, rectX, rectY, rectSide);
					}else if(Utils.isBRAM(tileTypeEnum)){
						drawBRAM(painter, rectX, rectY, rectSide, offset, color);
					}else if(Utils.isDSP(tileTypeEnum)){
						drawDSP(painter, rectX, rectY, rectSide, offset, color);
					}else{ // Just fill the tile in with a color
						colorTile(painter, x, y, offset, color);
					}					
				}
				else{
					colorTile(painter, x, y, offset, color);
				}
			}
		}

		painter.end();
	}
	
	public void drawBackground(QPainter painter, QRectF rect){
		super.drawBackground(painter, rect);
		painter.drawImage(0, 0, qImage);
	}

	/**
	 * Gets the tile based on the x and y coordinates given (typically from mouse input)
	 * @param x The x location on the screen.
	 * @param y The y location on the screen.
	 * @return The tile at the x,y location or null if none exist.
	 */
	public Tile getTile(double x, double y){
		currX = (int) Math.floor(x / tileSize);
		currY = (int) Math.floor(y / tileSize);
		if (currX >= 0 && currY >= 0 && currX < cols && currY < rows){// && (currX != prevX || currY != prevY)){
			return drawnTiles[currY][currX];
		}
		return null;
	}
	
	/**
	 * Gets the tile based on the mouse position in the event.
	 * @param event The recent mouse event
	 * @return The tile under which the mouse event occurred.
	 */
	public Tile getTile(QGraphicsSceneMouseEvent event){
		return getTile(event.scenePos().x(), event.scenePos().y());
	}
	
	@Override
	public void mouseMoveEvent(QGraphicsSceneMouseEvent event) {
		QPointF mousePos = event.scenePos();
		if (device != null) {
			Tile tile = getTile(mousePos.x(), mousePos.y());
			if(tile != null){
				String siteNames = "";
				if(tile.getSites().length > 0){
					siteNames = " | " + tile.getSites()[0].getName();
				}
				if(tile.getSites().length > 1){
					siteNames += " ...";
				}				
				String tileName = device.getDeviceName() + " | " +  tile.getName() +
				" | " + tile.getTileTypeEnum() + " (" + currX + "," + currY + ")" + siteNames;
				this.updateStatus.emit(tileName, tile);
				prevX = currX;
				prevY = currY;
			}
		}
		super.mouseMoveEvent(event);
	}
	
	@Override
	public void mouseDoubleClickEvent(QGraphicsSceneMouseEvent event){
		QPointF mousePos = event.scenePos();
		currX = (int) Math.floor((mousePos.x()) / tileSize);
		currY = (int) Math.floor((mousePos.y()) / tileSize);

		if (currX >= 0 && currY >= 0 && currX < cols && currY < rows){
			updateCursor();
		}			
	
		super.mousePressEvent(event);
	}
	
	public void updateCursor(){
		if(highlit != null){
			highlit.dispose();
		}
		highlit = addRect(currX * tileSize, currY * tileSize, tileSize - 2,
				tileSize - 2, cursorPen);
		highlit.setZValue(10);
	}
	
	public void updateCurrXY(int currX, int currY){
		this.currX = currX;
		this.currY = currY;
	}
	
	/*
	 * Getters and Setters
	 */
	
	public int getDrawnTileX(Tile tile){
		Integer tmp = tileXMap.get(tile);
		if(tmp == null)
			return -1;
		return tmp;
	}
	
	public int getDrawnTileY(Tile tile){
		Integer tmp = tileYMap.get(tile);
		if(tmp == null)
			return -1;
		return tmp;
	}
	

	public Design getDesign(){
		return design;
	}

	public void setDesign(Design design){
		this.design = design;
		if(this.design != null){
			setDevice(design.getDevice());
		}
	}
	
	public Device getDevice(){
		return device;
	}
	
	public void setDevice(Device device){
		this.device = device;
	}

	public double getCurrX(){
		return currX;
	}

	public double getCurrY(){
		return currY;
	}
	
	public int getTileSize(){
		return tileSize;
	}
	
	/*
	 * Helper Drawing Methods
	 */
	
	private void drawCLB(QPainter painter, int rectX, int rectY, int rectSide){
		
		switch(device.getSeries()){
			case Series7:
				painter.drawRect(rectX, rectY + rectSide / 2, rectSide / 2 - 1, rectSide / 2 - 1);
				painter.drawRect(rectX + rectSide / 2, rectY, rectSide / 2 - 1, rectSide / 2 - 1);					
				break;				
			default:
				painter.drawRect(rectX, rectY, rectSide, rectSide);
		}
	}
	
	private void drawBRAM(QPainter painter, int rectX, int rectY, int rectSide, int offset, QColor color){
		switch(device.getSeries()){
			case Series7:
			case UltraScale:
			case UltraScalePlus:
				painter.drawRect(rectX, rectY - 4 * tileSize, rectSide - 1, 5 * rectSide + 3 * 2 * offset - 1);
				painter.setPen(color.darker());
				painter.drawRect(rectX+2, rectY-4 * tileSize + 2, rectSide - 5, ((int)(2.5 * rectSide)) + 3 * 2 * offset - 5);
				painter.drawRect(rectX+2, (rectY-2 * tileSize) + 7, rectSide - 5, ((int)(2.5 * rectSide)) + 3 * 2 * offset - 5);
				break;
		}
	}
	
	private void drawDSP(QPainter painter, int rectX, int rectY, int rectSide, int offset, QColor color){
		switch(device.getSeries()){
			case Series7:
			case UltraScale:
			case UltraScalePlus:
				painter.drawRect(rectX, rectY - 4 * tileSize, rectSide - 1, 5 * rectSide + 3 * 2 * offset - 1);
				painter.setPen(color.darker());
				painter.drawRect(rectX+2, rectY-4 * tileSize + 2, rectSide - 5, ((int)(2.5 * rectSide)) + 3 * 2 * offset - 5);
				painter.drawRect(rectX+2, (rectY-2 * tileSize) + 7, rectSide - 5, ((int)(2.5 * rectSide)) + 3 * 2 * offset - 5);
				break;
		}

	}
	
	private void drawSwitchBox(QPainter painter, int rectX, int rectY, int rectSide){
		painter.drawRect(rectX + rectSide / 6, rectY, 4 * rectSide / 6 - 1, rectSide - 1);
	}
	
	private void colorTile(QPainter painter, int x, int y, int offset, QColor color){
		painter.fillRect(x * tileSize, y * tileSize,
				tileSize - 2 * offset, tileSize - 2 * offset, new QBrush(color));
	}
	
	
	@SuppressWarnings("incomplete-switch")
	private void populateTileTypesToHide(){
		switch(device.getSeries()){
			case UltraScale:
				tileColumnTypesToHide.add(TileTypeEnum.CFRM_CBRK_L);
				tileRowTypesToHide.add(TileTypeEnum.RCLK_INT_L);
				tileRowTypesToHide.add(TileTypeEnum.INT_RBRK);
				break;
		}
	}
}
