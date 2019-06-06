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
/**
 * 
 */
package com.xilinx.rapidwright.placer.handplacer;

import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.Qt.PenCapStyle;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsLineItem;
import com.trolltech.qt.gui.QLineF;
import com.trolltech.qt.gui.QPainter;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QStyleOptionGraphicsItem;
import com.trolltech.qt.gui.QWidget;
import com.xilinx.rapidwright.gui.HMTile;


/**
 * @author marc
 * 
 */
public class GUINetLine extends QGraphicsLineItem {

	private HMTile srcTile;
	private HMTile destTile;
	private int alpha;

	public GUINetLine(HMTile srcTile, HMTile destTile) {
		super();
		this.srcTile = srcTile;
		this.destTile = destTile;
		updateLine();
		alpha = 128;
		QColor color = QColor.magenta;
		color.setAlpha(alpha);
		QPen pen = new QPen(color, 1.0);
		pen.setCapStyle(PenCapStyle.RoundCap);
		this.setPen(pen);
		setZValue(3.0);
	}

	public void updateLine() {
		QPointF src = srcTile.scenePos().add(srcTile.boundingRect().center());
		QPointF dest = destTile.scenePos().add(destTile.boundingRect().center());
		QLineF line = this.line();
		line.setPoints(src, dest);
		this.setLine(line);
	}

	public void paint(QPainter painter, QStyleOptionGraphicsItem option,
			QWidget widget) {
		if (srcTile != null && destTile != null) {
			updateLine();
		}
		super.paint(painter, option, widget);
	}

}
