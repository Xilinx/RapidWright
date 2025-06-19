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

import com.trolltech.qt.core.Qt.DockWidgetArea;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QDockWidget;
import com.trolltech.qt.gui.QDockWidget.DockWidgetFeature;
import com.trolltech.qt.gui.QMainWindow;
import com.trolltech.qt.gui.QTreeWidget;
import com.trolltech.qt.gui.QWidget;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class NetlistBrowser extends QMainWindow {

    private QTreeWidget treeWidget;

    private Design design;

    private EDIFNetlist netlist;

    public NetlistBrowser(QWidget parent, Design design) {
        super(parent);
        this.design = design;
        this.netlist = design.getNetlist();
        init();
    }

    public NetlistBrowser(QWidget parent, EDIFNetlist netlist) {
        super(parent);
        this.netlist = netlist;
        init();
    }

    public static void browseNetlist(Design design) {
        browseNetlist(design.getNetlist());
    }

    public static void browseNetlist(EDIFNetlist netlist) {
        QApplication.setGraphicsSystem("raster");
        QApplication.initialize(new String[] {});
        NetlistBrowser browser = new NetlistBrowser(null, netlist);
        browser.show();
        QApplication.exec();
    }

    public static void browseNetlist(Design design, boolean nonBlocking) {
        browseNetlist(design.getNetlist(), nonBlocking);
    }

    public static void browseNetlist(EDIFNetlist netlist, boolean nonBlocking) {
        if (nonBlocking) {
            new Thread(new Runnable() {
                public void run() {
                    browseNetlist(netlist);
                }
            }).start();
        } else {
            browseNetlist(netlist);
        }
    }

    private void init() {
        // set the title of the window
        setWindowTitle("Netlist Browser");

        // Set the opening default window size to 1280x1024 pixels
        resize(1280, 1024);

        treeWidget = new NetlistTreeWidget("Netlist", netlist);
        // treeWidget.doubleClicked.connect(this,"showPart(QModelIndex)");

        QDockWidget dockWidget = new QDockWidget(tr("Design"), this);
        dockWidget.setWidget(treeWidget);
        dockWidget.setFeatures(DockWidgetFeature.DockWidgetMovable);
        addDockWidget(DockWidgetArea.LeftDockWidgetArea, dockWidget);
    }

    /**
     * Main method setting up the Qt environment for the program to run.
     * 
     * @param args
     */
    public static void main(String[] args) {
        QApplication.setGraphicsSystem("raster");
        QApplication.initialize(args);

        if (args.length != 1) {
            System.out.println("USAGE: <input.dcp|input.edf>");
        }

        EDIFNetlist netlist = null;
        if (args[0].endsWith(".dcp")) {
            netlist = Design.readCheckpoint(args[0], true).getNetlist();
        } else {
            netlist = EDIFTools.readEdifFile(args[0]);
            if (!netlist.expandMacroUnisims()) {
                System.err.println("WARNING: Unable to expand macro unisims, target Series is unknown. "
                        + "Try setting the part in the EDIFNetlist (see EDIFTools.ensureCorrectPartInEDIF()");
            }
        }

        NetlistBrowser testPTB = new NetlistBrowser(null, netlist);
        testPTB.show();
        QApplication.exec();
    }
}
