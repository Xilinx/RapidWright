/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.core.QSize;
import com.trolltech.qt.core.QSizeF;
import com.trolltech.qt.gui.QGraphicsScene;
import com.trolltech.qt.gui.QPainter;
import com.trolltech.qt.gui.QPrinter;
import com.trolltech.qt.svg.QSvgGenerator;

public class UiTools {

    private UiTools() {

    }

    private static final double EXPORT_MARGIN = 10.0;

    private static QRectF getExportRect(QGraphicsScene scene) {
        QRectF rect = scene.itemsBoundingRect();
        rect.adjust(-EXPORT_MARGIN, -EXPORT_MARGIN, EXPORT_MARGIN, EXPORT_MARGIN);
        return rect;
    }

    public static void saveAsPdf(QGraphicsScene scene, File file) {
        QRectF exportRect = getExportRect(scene);
        QPrinter printer = new QPrinter(QPrinter.PrinterMode.HighResolution);
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat);
        printer.setOutputFileName(file.toString());
        // Set custom page size to fit the schematic content
        printer.setPaperSize(new QSizeF(exportRect.width(), exportRect.height()), QPrinter.Unit.Point);
        QPainter pdfPainter = new QPainter(printer);
        scene.render(pdfPainter, new QRectF(), exportRect);
        pdfPainter.end();
    }

    public static void saveAsSvg(QGraphicsScene scene, File file) {
        QRectF exportRect = getExportRect(scene);
        QSvgGenerator svgGen = new QSvgGenerator();
        svgGen.setFileName(file.toString());
        svgGen.setSize(new QSize((int) exportRect.width(), (int) exportRect.height()));
        svgGen.setViewBox(new QRectF(0, 0, exportRect.width(), exportRect.height()));
        svgGen.setTitle("RapidWright Schematic");
        svgGen.setDescription("Schematic Scene");

        QPainter svgPainter = new QPainter(svgGen);
        scene.render(svgPainter, new QRectF(), exportRect);
        svgPainter.end();
    }
}
