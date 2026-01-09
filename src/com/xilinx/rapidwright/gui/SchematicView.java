/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.io.File;

import com.trolltech.qt.core.QPoint;
import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.core.Qt;
import com.trolltech.qt.core.Qt.AspectRatioMode;
import com.trolltech.qt.core.Qt.CursorShape;
import com.trolltech.qt.core.Qt.Key;
import com.trolltech.qt.gui.QAction;
import com.trolltech.qt.gui.QCursor;
import com.trolltech.qt.gui.QFileDialog;
import com.trolltech.qt.gui.QGraphicsScene;
import com.trolltech.qt.gui.QGraphicsView;
import com.trolltech.qt.gui.QKeyEvent;
import com.trolltech.qt.gui.QMenu;
import com.trolltech.qt.gui.QMouseEvent;
import com.trolltech.qt.gui.QWheelEvent;

public class SchematicView extends QGraphicsView {

    private boolean rightPressed;
    private QPoint lastPan;
    private static final int PAN_THRESHOLD = 5;

    /** The maximum value to which we can zoom out */
    protected static double zoomMin = 0.05;
    /** The maximum value to which we can zoom in */
    protected static double zoomMax = 30;
    /** The rate at which we zoom */
    protected static double scaleFactor = 1.15;

    public SchematicView(QGraphicsScene scene) {
        super(scene);
    }

    /**
     * This method is called when any mouse button is pressed. In this case, a right
     * click will allow the user to pan the array of tiles.
     */
    public void mousePressEvent(QMouseEvent event) {
        if (event.button().equals(Qt.MouseButton.RightButton)) {
            // For panning the view
            rightPressed = true;
            lastPan = event.pos();
            setCursor(new QCursor(CursorShape.ClosedHandCursor));
        }
        super.mousePressEvent(event);
    }

    /**
     * This method is called when any mouse button is released. In this case, this
     * will disallow the user to pan. If the mouse didn't move significantly, show
     * a context menu.
     */
    public void mouseReleaseEvent(QMouseEvent event) {
        if (event.button().equals(Qt.MouseButton.RightButton)) {
            rightPressed = false;
            setCursor(new QCursor(CursorShape.ArrowCursor));
            
            // Show context menu if the mouse didn't move significantly (not panning)
            int dx = Math.abs(event.pos().x() - lastPan.x());
            int dy = Math.abs(event.pos().y() - lastPan.y());
            if (dx < PAN_THRESHOLD && dy < PAN_THRESHOLD) {
                showContextMenu(event.globalPos());
            }
        }
        super.mouseReleaseEvent(event);
    }

    /**
     * Shows the context menu with export options.
     */
    private void showContextMenu(QPoint globalPos) {
        QMenu menu = new QMenu(this);
        
        QAction exportSvgAction = menu.addAction("Export to SVG...");
        exportSvgAction.triggered.connect(this, "exportToSvg()");
        
        QAction exportPdfAction = menu.addAction("Export to PDF...");
        exportPdfAction.triggered.connect(this, "exportToPdf()");
        
        menu.exec(globalPos);
    }

    /**
     * Exports the schematic scene to an SVG file (vector graphics).
     */
    public void exportToSvg() {
        String fileName = QFileDialog.getSaveFileName(this, "Export to SVG", "", 
                new QFileDialog.Filter("SVG Files (*.svg)"));
        if (fileName != null && !fileName.isEmpty()) {
            if (!fileName.toLowerCase().endsWith(".svg")) {
                fileName += ".svg";
            }
            UiTools.saveAsSvg(scene(), new File(fileName));
        }
    }

    /**
     * Exports the schematic scene to a PDF file.
     */
    public void exportToPdf() {
        String fileName = QFileDialog.getSaveFileName(this, "Export to PDF", "", 
                new QFileDialog.Filter("PDF Files (*.pdf)"));
        if (fileName != null && !fileName.isEmpty()) {
            if (!fileName.toLowerCase().endsWith(".pdf")) {
                fileName += ".pdf";
            }
            UiTools.saveAsPdf(scene(), new File(fileName));
        }
    }

    /**
     * This method is called when the mouse moves in the window. This will reset the
     * window based on the mouse panning.
     */
    public void mouseMoveEvent(QMouseEvent event) {
        if (rightPressed) {
            if (lastPan != null && !lastPan.isNull()) {
                // Get how much we panned
                QPointF s1 = mapToScene(new QPoint((int) lastPan.x(), (int) lastPan.y()));
                QPointF s2 = mapToScene(new QPoint((int) event.pos().x(), (int) event.pos().y()));
                QPointF delta = new QPointF(s1.x() - s2.x(), s1.y() - s2.y());
                lastPan = event.pos();
                // Scroll the scrollbars ie. do the pan
                double zoom = this.matrix().m11();
                this.horizontalScrollBar().setValue((int) (this.horizontalScrollBar().value() + zoom * delta.x()));
                this.verticalScrollBar().setValue((int) (this.verticalScrollBar().value() + zoom * delta.y()));
            }
        }
        super.mouseMoveEvent(event);
    }

    /**
     * This method is called when the mouse wheel or scroll is used. In this case,
     * it allows the user to zoom in and out of the array of tiles.
     */
    public void wheelEvent(QWheelEvent event) {
        // Get the position of the mouse before scaling, in scene coords
        QPointF pointBeforeScale = mapToScene(event.pos());

        // Scale the view ie. do the zoom
        double zoom = this.matrix().m11();
        if (event.delta() > 0) {
            // Zoom in (if not at limit)
            if (zoom < zoomMax)
                scale(scaleFactor, scaleFactor);
        } else {
            // Zoom out (if not at limit)
            if (zoom > zoomMin)
                scale(1.0 / scaleFactor, 1.0 / scaleFactor);
        }

        // Read the new zoom value
        zoom = this.matrix().m11();

        // Get the position after scaling, in scene coords
        QPointF pointAfterScale = mapToScene(event.pos());

        // Get the offset of how the screen moved
        QPointF offset = new QPointF(pointBeforeScale.x() - pointAfterScale.x(),
                pointBeforeScale.y() - pointAfterScale.y());
        this.horizontalScrollBar().setValue((int) (this.horizontalScrollBar().value() + zoom * offset.x()));
        this.verticalScrollBar().setValue((int) (this.verticalScrollBar().value() + zoom * offset.y()));
    }

    /**
     * This method gets called when a key on the keyboard is pressed. In this case,
     * if the '=' key is pressed, it zooms in. If the '-' key is pressed, it zooms
     * out.
     */
    public void keyPressEvent(QKeyEvent event) {
        double scaleFactor = 1.15;
        if (event.key() == Key.Key_Equal.value()) {
            // Zoom in (if not at limit)
            if (this.matrix().m11() < zoomMax)
                scale(scaleFactor, scaleFactor);
        } else if (event.key() == Key.Key_Minus.value()) {
            // Zoom out (if not at limit)
            if (this.matrix().m11() > zoomMin)
                scale(1.0 / scaleFactor, 1.0 / scaleFactor);
        }
    }

    public void zoomIn() {
        // Zoom in (if not at limit)
        if (this.matrix().m11() < zoomMax)
            scale(scaleFactor, scaleFactor);
    }

    public void zoomOut() {
        // Zoom out (if not at limit)
        if (this.matrix().m11() > zoomMin)
            scale(1.0 / scaleFactor, 1.0 / scaleFactor);
    }

    public void zoomToFit() {
        QRectF sceneRect = scene().sceneRect();
        if (sceneRect != null) {
            fitInView(sceneRect, AspectRatioMode.KeepAspectRatio);
            double zoom = this.matrix().m11();
            if (zoom > zoomMax) {
                resetMatrix();
                scale(zoomMax, zoomMax);
                fitInView(sceneRect, AspectRatioMode.KeepAspectRatio);
            } else if (zoom < zoomMin) {
                resetMatrix();
                scale(zoomMin, zoomMin);
                fitInView(sceneRect, AspectRatioMode.KeepAspectRatio);
            }
        }
    }
}
