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
package com.xilinx.rapidwright.examples.tilebrowser;

import io.qt.core.Qt;
import io.qt.core.QPointF;
import io.qt.core.QRectF;
import io.qt.core.QSize;
import io.qt.gui.QBrush;
import io.qt.gui.QColor;
import io.qt.widgets.QGraphicsPixmapItem;
import io.qt.widgets.QGraphicsRectItem;
import io.qt.widgets.QGraphicsScene;
import io.qt.widgets.QGraphicsSceneMouseEvent;
import io.qt.gui.QImage;
import io.qt.gui.QPainter;
import io.qt.gui.QPen;
import io.qt.gui.QPixmap;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;

/**
 * 
 * 
 * @author marc
 * 
 */
public class PartTileBrowserScene extends QGraphicsScene {
	double currX, currY, prevX, prevY;
	int tileSize, numCols, numRows;
	double lineWidth;
	Device device;
	public final Signal0 updateStatus = new Signal0();
	private QGraphicsRectItem highlit;
	private QImage qImage;

	public PartTileBrowserScene(Device device) {
		this.device = device;
		this.highlit = null;
		this.prevX = 0;
		this.prevY = 0;
		this.tileSize = 20;
		this.lineWidth = 1;
		if (device != null) {
			this.numRows = device.getRows();
			this.numCols = device.getColumns();
		} else {
			this.numRows = 8;
			this.numCols = 8;
		}
		setSceneRect(new QRectF(0, 0, (numCols + 1) * (tileSize + 1),
				(numRows + 1) * (tileSize + 1)));
		drawSliceBackground();
	}

	public void setDevice(Device newDevice) {
		this.device = newDevice;
		this.highlit = null;
		this.prevX = 0;
		this.prevY = 0;
		if (device != null) {
			this.numRows = device.getRows();
			this.numCols = device.getColumns();
		} else {
			this.numRows = 8;
			this.numCols = 8;
		}
		this.clear();
		setSceneRect(new QRectF(0, 0, (numCols + 1) * (tileSize + 1),
				(numRows + 1) * (tileSize + 1)));
		drawSliceBackground();
	}

	private void drawSliceBackground() {

		setBackgroundBrush(new QBrush(new QColor(Qt.GlobalColor.black)));
		//Create transparent QPixmap that accepts hovers 
		//  so that moveMouseEvent is triggered
		QPixmap qpm = new QPixmap(new QSize((numCols + 1) * (tileSize + 1),
				(numRows + 1) * (tileSize + 1)));
		qpm.fill(new QColor(255, 255,255, 0));
		QGraphicsPixmapItem background = addPixmap(qpm);
		background.setAcceptHoverEvents(true);
		background.setZValue(-1);
		// Draw colored tiles onto QImage		
		qImage = new QImage(new QSize((numCols + 1) * (tileSize + 1),
				(numRows + 1) * (tileSize + 1)), QImage.Format.Format_RGB16);
		QPainter painter = new QPainter(qImage);

		painter.setPen(new QPen(new QColor(Qt.GlobalColor.black), lineWidth));
		// Draw lines between tiles
		for (int i = 0; i <= numCols; i++) {
			painter.drawLine((i) * tileSize, tileSize, (i) * tileSize,
					(numRows) * tileSize);
		}

		for (int j = 0; j <= numRows; j++) {
			painter.drawLine(tileSize, (j) * tileSize, (numCols) * tileSize,
					(j) * tileSize);
		}

		for (int i = 0; i < numRows; i++) {
			for (int j = 0; j < numCols; j++) {
				Tile tile = device.getTile(i, j);
				String name = tile.getName();
				int hash = name.hashCode();
				int idx = name.indexOf("_");
				if (idx != -1) {
					hash = name.substring(0, idx).hashCode();
				}
				QColor color = QColor.fromRgb(hash);

				if (name.startsWith("DSP")) {
					// color = new QColor(Qt.GlobalColor.fromRgb)(145, 145, 145);
					color = new QColor(Qt.GlobalColor.darkCyan);
				} else if (name.startsWith("BRAM")) {
					// color = new QColor(Qt.GlobalColor.fromRgb)(165, 165, 165);
					color = new QColor(Qt.GlobalColor.darkMagenta);
				} else if (name.startsWith("INT")) {
					// color = new QColor(Qt.GlobalColor.fromRgb)(125, 125, 125);
					color = new QColor(Qt.GlobalColor.darkYellow);
				} else if (name.startsWith("CLB")) {
					color = new QColor(Qt.GlobalColor.blue);
					// color = new QColor(Qt.GlobalColor.fromRgb)(185, 185, 185);
				} else if (name.startsWith("DCM")) {
					// color = new QColor(Qt.GlobalColor.fromRgb)(205, 205, 205);
				} else if (name.startsWith("EMPTY")) {
					// color = new QColor(Qt.GlobalColor.white);
				} else {
					// color = new QColor(Qt.GlobalColor.black);
				}

				painter.fillRect(j * tileSize, i * tileSize, tileSize - 2, tileSize - 2, new QBrush(color));
			}
		}

		painter.end();
		
	}
	
	public void drawBackground(QPainter painter, QRectF rect){
		super.drawBackground(painter, rect);
		painter.drawImage(0, 0, qImage);
	}

	@Override
	public void mouseMoveEvent(QGraphicsSceneMouseEvent event) {
		QPointF mousePos = event.scenePos();
		currX = Math.floor((mousePos.x()) / tileSize);
		currY = Math.floor((mousePos.y()) / tileSize);
		if (currX >= 0 && currY >= 0 && currX < numCols && currY < numRows
				&& (currX != prevX || currY != prevY)) {
			this.updateStatus.emit();
			updateCursor();
			prevX = currX;
			prevY = currY;
		}

		super.mouseMoveEvent(event);
	}

	private void updateCursor() {
		if (highlit == null) {
			QPen cursorPen = new QPen(new QColor(Qt.GlobalColor.yellow), 3);
			highlit = addRect(currX * tileSize, currY * tileSize, tileSize - 2,
					tileSize - 2, cursorPen);
		} else {
			highlit.moveBy((currX - prevX) * tileSize, (currY - prevY)
					* tileSize);
		}
	}

	public double getCurrX() {
		return currX;
	}

	public double getCurrY() {
		return currY;
	}

}
