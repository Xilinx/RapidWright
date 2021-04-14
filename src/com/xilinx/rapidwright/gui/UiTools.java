package com.xilinx.rapidwright.gui;

import java.io.File;

import com.trolltech.qt.gui.QPainter;
import com.trolltech.qt.gui.QPrinter;

public class UiTools {
    private UiTools(){

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
