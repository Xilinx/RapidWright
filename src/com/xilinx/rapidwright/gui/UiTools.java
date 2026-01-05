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

    public static void saveAsPdf(QGraphicsScene scene, File file) {
        QRectF sceneRect = scene.sceneRect();
        QPrinter printer = new QPrinter(QPrinter.PrinterMode.HighResolution);
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat);
        printer.setOutputFileName(file.toString());
        // Set custom page size to match scene dimensions for vector output
        printer.setPaperSize(new QSizeF(sceneRect.width(), sceneRect.height()), QPrinter.Unit.Point);
        QPainter pdfPainter = new QPainter(printer);
        scene.render(pdfPainter);
        pdfPainter.end();
    }

    public static void saveAsSvg(QGraphicsScene scene, File file) {
        QRectF sceneRect = scene.sceneRect();
        QSvgGenerator svgGen = new QSvgGenerator();
        svgGen.setFileName(file.toString());
        svgGen.setSize(new QSize((int) sceneRect.width(), (int) sceneRect.height()));
        svgGen.setViewBox(sceneRect);
        svgGen.setTitle("RapidWright Schematic");
        svgGen.setDescription("Schematic Scene");

        QPainter svgPainter = new QPainter(svgGen);
        scene.render(svgPainter);
        svgPainter.end();
    }
}
