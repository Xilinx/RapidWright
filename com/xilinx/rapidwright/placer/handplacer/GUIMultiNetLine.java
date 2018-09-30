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
import com.trolltech.qt.gui.QGraphicsItemInterface;
import com.trolltech.qt.gui.QGraphicsLineItem;
import com.trolltech.qt.gui.QLineF;
import com.trolltech.qt.gui.QPainter;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QStyleOptionGraphicsItem;
import com.trolltech.qt.gui.QWidget;

/**
 * @author marc
 * 
 */
public class GUIMultiNetLine extends QGraphicsLineItem {

	private QGraphicsItemInterface srcItem;
	private QGraphicsItemInterface destItem;
	private int hue;
	private int alpha;
	
	private static final int CEILING_LINE_WIDTH_SIZE = 128;

	public GUIMultiNetLine(QGraphicsItemInterface srcItem, QGraphicsItemInterface destItem) {
		super();
		this.srcItem = srcItem;
		this.destItem = destItem;
		updateLine();
		hue = 110;
		alpha = 128;
		QColor color = QColor.fromHsv(hue, 240, 255);
		color.setAlpha(alpha);
		QPen pen = new QPen(color, 1.0);
		pen.setCapStyle(PenCapStyle.RoundCap);
		this.setPen(pen);
		setZValue(3.0);
		updateToolTip();

	}

	private void updateToolTip() {
		setToolTip(this.pen().width() + " connections");
	}

	public void addNet() {
		int oldWidth = this.pen().width();
		QPen pen = this.pen();
		hue = (hue > 0) ? hue - 2 : 0;
		QColor color = QColor.fromHsv(hue, 240, 255);
		color.setAlpha(alpha);
		pen.setColor(color);
		pen.setWidth(oldWidth + 1 > CEILING_LINE_WIDTH_SIZE ? oldWidth : oldWidth + 1);
		this.setPen(pen);
		updateToolTip();
	}

	public void updateLine() {
		QPointF src = srcItem.pos().add(srcItem.boundingRect().center());
		QPointF dest = destItem.pos().add(destItem.boundingRect().center());
		QLineF line = this.line();
		line.setPoints(src, dest);
		this.setLine(line);
	}

	public void paint(QPainter painter, QStyleOptionGraphicsItem option,
			QWidget widget) {
		if (srcItem != null && destItem != null) {
			updateLine();
		}
		super.paint(painter, option, widget);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((destItem == null) ? 0 : destItem.hashCode());
		result = prime * result + ((srcItem == null) ? 0 : srcItem.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		GUIMultiNetLine other = (GUIMultiNetLine) obj;
		if (destItem == null) {
			if (other.destItem != null)
				return false;
		} else if (!destItem.equals(other.destItem))
			return false;
		if (srcItem == null) {
			if (other.srcItem != null)
				return false;
		} else if (!srcItem.equals(other.srcItem))
			return false;
		return true;
	}

	

}
