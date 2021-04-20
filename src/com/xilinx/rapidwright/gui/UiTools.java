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
