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

import java.util.HashMap;
import java.util.Map;

import com.trolltech.qt.QSignalEmitter.Signal1;
import com.trolltech.qt.QThread;
import com.trolltech.qt.core.QModelIndex;
import com.trolltech.qt.core.Qt.DockWidgetArea;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QDockWidget;
import com.trolltech.qt.gui.QDockWidget.DockWidgetFeature;
import com.trolltech.qt.gui.QMainWindow;
import com.trolltech.qt.gui.QTreeWidgetItem;
import com.trolltech.qt.gui.QWidget;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class NetlistBrowser extends QMainWindow {

    private NetlistTreeWidget treeWidget;
    private QDockWidget schematicWidget;
    private SchematicScene schematicScene;
    private SchematicView schematicView;

    private Design design;

    private EDIFNetlist netlist;

    private static Map<EDIFNetlist, NetlistBrowser> browsers = new HashMap<>();

    public Signal1<String> selectInst = new Signal1<>();
    public Signal1<String> selectNet = new Signal1<>();
    public Signal1<String> selectPort = new Signal1<>();

    protected static NetlistBrowser getBrowser(EDIFNetlist netlist) {
        NetlistBrowser browser = browsers.get(netlist);
        if (browser == null) {
            browseNetlist(netlist, /* nonBlocking= */true);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        return browsers.get(netlist);
    }

    private NetlistBrowser(QWidget parent, Design design) {
        super(parent);
        this.design = design;
        this.netlist = design.getNetlist();
        init();
    }

    private NetlistBrowser(QWidget parent, EDIFNetlist netlist) {
        super(parent);
        this.netlist = netlist;
        init();
    }

    public static void browseNetlist(Design design) {
        QApplication.setGraphicsSystem("raster");
        QApplication.initialize(new String[] {});
        NetlistBrowser browser = new NetlistBrowser(null, design);
        browsers.put(design.getNetlist(), browser);
        browser.show();
        QApplication.exec();
    }

    public static void browseNetlist(EDIFNetlist netlist) {
        browseNetlist(new Design(netlist));
    }

    public static void browseNetlist(Design design, boolean nonBlocking) {
        browseNetlist(design, nonBlocking);
    }

    public static void browseNetlist(EDIFNetlist netlist, boolean nonBlocking) {
        if (nonBlocking) {
            new QThread(new Runnable() {
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
        treeWidget.clicked.connect(this, "selectNetlistItem(QModelIndex)");

        QDockWidget dockWidget = new QDockWidget(tr("Design"), this);
        dockWidget.setWidget(treeWidget);
        dockWidget.setFeatures(DockWidgetFeature.DockWidgetMovable);
        addDockWidget(DockWidgetArea.LeftDockWidgetArea, dockWidget);

        // Add schematic viewer on the right
        schematicScene = new SchematicScene(netlist);
        schematicView = new SchematicView(schematicScene);
        schematicWidget = new QDockWidget(tr("Schematic"), this);
        schematicWidget.setWidget(schematicView);
        schematicWidget.setFeatures(DockWidgetFeature.DockWidgetMovable);
        addDockWidget(DockWidgetArea.RightDockWidgetArea, schematicWidget);

        schematicScene.objectSelected.connect(this, "selectFromSchematic(String)");
        schematicScene.cellDrawn.connect(schematicView, "zoomToFit()");
        this.selectInst.connect(this, "selectExternal(String)");
    }

    public void selectNetlistItem(QModelIndex index) {
        selectNetlistItem(treeWidget.getItemFromIndex(index));
    }

    public void selectExternal(String lookup) {
        QTreeWidgetItem item = selectFromSchematic(lookup);
        if (item != null) {
            selectNetlistItem(item);
        }
    }

    public void selectNetlistItem(QTreeWidgetItem item) {
        if (item instanceof HierCellInstTreeWidgetItem) {
            EDIFHierCellInst cellInst = ((HierCellInstTreeWidgetItem) item).getInst();
            schematicScene.drawCell(cellInst, true);
        } else {
            schematicScene.selectObject(item.data(1, 0).toString(), true);
        }
    }

    public QTreeWidgetItem selectFromSchematic(String lookup) {
        QTreeWidgetItem item = treeWidget.getItemByStringLookup(lookup);
        if (item != null) {
            treeWidget.setCurrentItem(item);
            treeWidget.scrollToItem(item);
        }
        return item;
    }
    
    public static void select(EDIFHierCellInst inst) {
        NetlistBrowser browser = getBrowser(inst.getCellType().getNetlist());
        String lookup = NetlistTreeWidget.INST_ID + inst.toString();
        browser.selectInst.emit(lookup);
    }

    public static void select(EDIFHierNet net) {
        NetlistBrowser browser = getBrowser(net.getHierarchicalInst().getCellType().getNetlist());
        String lookup = NetlistTreeWidget.NET_ID + net.toString();
        QTreeWidgetItem item = browser.selectFromSchematic(lookup);
        if (item != null) {
            browser.selectNetlistItem(item);
        }
    }

    public void select(EDIFHierPortInst portInst) {
        NetlistBrowser browser = getBrowser(portInst.getCellType().getNetlist());
        String lookup = NetlistTreeWidget.PORT_ID + portInst.toString();
        QTreeWidgetItem item = browser.selectFromSchematic(lookup);
        if (item != null) {
            browser.selectNetlistItem(item);
        }
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
            System.exit(1);
        }

        EDIFNetlist netlist = null;
        if (args[0].endsWith(".dcp")) {
            boolean skipXdef = true;
            netlist = Design.readCheckpoint(args[0], skipXdef).getNetlist();
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
