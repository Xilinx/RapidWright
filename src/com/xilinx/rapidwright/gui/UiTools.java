/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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

import com.trolltech.qt.gui.QPainter;
import com.trolltech.qt.gui.QPrinter;
import com.trolltech.qt.svg.QSvgGenerator;

public class UiTools {
    private UiTools(){

    }

    public static void saveAsSvg(TileScene scene, File file) {

        QSvgGenerator svgGen = new QSvgGenerator();

        svgGen.setFileName(file.toString());
        svgGen.setSize(scene.getSceneSize());
        svgGen.setViewBox(scene.sceneRect());
        svgGen.setTitle("RapidWright Scene");
        svgGen.setDescription(scene.getClass().getSimpleName());

        QPainter svgPainter = new QPainter(svgGen);
        scene.render(svgPainter);
        svgPainter.end();
    }

    public static void saveAsPdf(TileScene scene, File file) {
        QPrinter printer = new QPrinter();
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat);
        printer.setOutputFileName(file.toString());
        printer.setPageSize(QPrinter.PageSize.Letter);
        QPainter pdfPainter = new QPainter(printer);
        scene.render(pdfPainter);
        pdfPainter.end();
    }
}
