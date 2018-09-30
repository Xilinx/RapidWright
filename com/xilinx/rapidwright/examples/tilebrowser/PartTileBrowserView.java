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

import com.trolltech.qt.core.QPoint;
import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.Qt;
import com.trolltech.qt.core.Qt.CursorShape;
import com.trolltech.qt.core.Qt.Key;
import com.trolltech.qt.gui.QCursor;
import com.trolltech.qt.gui.QGraphicsScene;
import com.trolltech.qt.gui.QGraphicsView;
import com.trolltech.qt.gui.QKeyEvent;
import com.trolltech.qt.gui.QMouseEvent;
import com.trolltech.qt.gui.QWheelEvent;

/**
 * @author marc
 *
 */
public class PartTileBrowserView extends QGraphicsView {

	QPointF currCenter;
	QPoint lastPan;
	private boolean rightPressed;
	double zoomMin;
	double zoomMax;
	static double scaleFactor = 1.15;//how fast we zoom

	public PartTileBrowserView(QGraphicsScene scene) {
		super(scene);
		zoomMin = 0.05;
		zoomMax = 30;
	}

	

	

	public void mousePressEvent(QMouseEvent event) {
		if (event.button().equals(Qt.MouseButton.RightButton)) {
			// For panning the view
			rightPressed = true;
			lastPan = event.pos();
			setCursor(new QCursor(CursorShape.ClosedHandCursor));
		}
		super.mousePressEvent(event);
	}

	public void mouseReleaseEvent(QMouseEvent event) {
		if (event.button().equals(Qt.MouseButton.RightButton)) {
			rightPressed = false;
			setCursor(new QCursor(CursorShape.ArrowCursor));
		}
		super.mouseReleaseEvent(event);
	}

	public void mouseMoveEvent(QMouseEvent event) {
		if (rightPressed) {
			if (lastPan != null && !lastPan.isNull()) {
				// Get how much we panned
				QPointF s1 = mapToScene(new QPoint((int) lastPan.x(),
						(int) lastPan.y()));
				QPointF s2 = mapToScene(new QPoint((int) event.pos().x(),
						(int) event.pos().y()));
				QPointF delta = new QPointF(s1.x() - s2.x(), s1.y() - s2.y());
				lastPan = event.pos();
				// Scroll the scrollbars ie. do the pan
				double zoom = this.matrix().m11();
				this.horizontalScrollBar().setValue((int) (this.horizontalScrollBar().value()+zoom*delta.x()));
				this.verticalScrollBar().setValue((int) (this.verticalScrollBar().value()+zoom*delta.y()));
			}
		}
		super.mouseMoveEvent(event);
	}

	public void wheelEvent(QWheelEvent event) {
		// Get the position of the mouse before scaling, in scene coords
		QPointF pointBeforeScale = mapToScene(event.pos());

		// Scale the view ie. do the zoom
		double zoom = this.matrix().m11();
		if (event.delta() > 0) {
			// Zoom in (if not at limit)
			if(zoom < zoomMax)
				scale(scaleFactor, scaleFactor);
		} else {
			// Zoom out (if not at limit)
			if(zoom > zoomMin)
				scale(1.0 / scaleFactor, 1.0 / scaleFactor);
		}

		// Get the position after scaling, in scene coords
		QPointF pointAfterScale = mapToScene(event.pos());

		// Get the offset of how the screen moved
		QPointF offset = new QPointF(
				pointBeforeScale.x() - pointAfterScale.x(), pointBeforeScale
						.y()
						- pointAfterScale.y());
		this.horizontalScrollBar().setValue((int) (this.horizontalScrollBar().value()+zoom*offset.x()));
		this.verticalScrollBar().setValue((int) (this.verticalScrollBar().value()+zoom*offset.y()));
	}

	
	public void keyPressEvent(QKeyEvent event){
		double scaleFactor = 1.15; 
		if (event.key() == Key.Key_Equal.value()) {
			// Zoom in (if not at limit)
			if(this.matrix().m11() < zoomMax)
				scale(scaleFactor, scaleFactor);
		} else if(event.key() == Key.Key_Minus.value()){
			// Zoom out (if not at limit)
			if(this.matrix().m11() > zoomMin)
				scale(1.0 / scaleFactor, 1.0 / scaleFactor);
		}		
	}
}

