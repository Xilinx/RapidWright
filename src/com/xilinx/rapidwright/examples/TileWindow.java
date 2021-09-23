package com.xilinx.rapidwright.examples;

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

import com.trolltech.qt.gui.QAction;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QFileDialog;
import com.trolltech.qt.gui.QIcon;
import com.trolltech.qt.gui.QKeySequence;
import com.trolltech.qt.gui.QLabel;
import com.trolltech.qt.gui.QMainWindow;
import com.trolltech.qt.gui.QMenu;
import com.trolltech.qt.gui.QStatusBar;
import com.trolltech.qt.gui.QToolBar;
import com.trolltech.qt.gui.QWidget;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.FileFilters;
import com.xilinx.rapidwright.gui.TileScene;
import com.xilinx.rapidwright.gui.TileView;
import com.xilinx.rapidwright.gui.UiTools;
import com.xilinx.rapidwright.util.FileTools;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Creates a zoomable UI view of a provided TileScene
 */
public class TileWindow extends QMainWindow{
    /** This is the Qt View object  */
    private TileView view;
    /** This is the container for the text in the Status Bar at the bottom of the screen */
    private QLabel statusLabel;
    /** This is the Qt Scene object */
    private TileScene scene;


    /**
     * Constructor of a new PartTileBrowser
     * @param parent Parent widget to which this object belongs.
     */
    public TileWindow(QWidget parent, TileScene scene) {
        super(parent);
        setWindowTitle("Tile View - " +scene.getClass().getSimpleName());

        this.scene = scene;

        view = new TileView(scene);

        setCentralWidget(view);

        scene.updateStatus.connect(this, "updateStatus()");
        statusLabel = new QLabel("Status Bar");
        statusLabel.setText("Status Bar");
        QStatusBar statusBar = new QStatusBar();
        statusBar.addWidget(statusLabel);
        setStatusBar(statusBar);

        setupMenu();

    }

    @SuppressWarnings("unused")
    void updateStatus() {
        int x = (int) scene.getCurrX();
        int y = (int) scene.getCurrY();
        if (x >= 0 && x < scene.getDevice().getColumns() && y >= 0 && y < scene.getDevice().getRows()){
            final Tile tile = scene.getDevice().getTile(y, x);
            String tileName = tile.getName();
            final String sites = tile.getSites() ==null ? "" : tile.getSites().length>5 ? "too may too show" : Arrays.stream(tile.getSites()).map(Site::getName).sorted().collect(Collectors.joining(", "));
            statusLabel.setText("Part: "+scene.getDevice().getName().toUpperCase() +"  Tile: "+ tileName+" ("+x+","+y+")"+", Sites: "+sites);
        }
    }

    public static void showBlocking(TileScene scene) {
        scene.setUseImage(true);
        final TileWindow tileWindow = new TileWindow(null, scene);
        tileWindow.show();
        QApplication.exec();
    }
    protected void saveAsPDFDesign(){
        if(scene.getDesign() == null)
            return;
        String fileName = QFileDialog.getSaveFileName(this, tr("Save As PDF"),".", FileFilters.pdfFilter);
        if (fileName.length() == 0)
            return;
        UiTools.saveAsPdf(scene, new File(fileName));
        statusBar().showMessage(fileName + " saved.", 2000);
    }
    private QAction action(String name, String image, Object shortcut,
                           String slot, QMenu menu, QToolBar toolBar) {
        QAction a = new QAction(name, this);

        if (image != null)
            a.setIcon(new QIcon(FileTools.getRapidWrightPath()+File.separator+FileTools.IMAGES_FOLDER_NAME + File.separator + image + ".png"));
        if (menu != null)
            menu.addAction(a);
        if (toolBar != null)
            toolBar.addAction(a);
        if (slot != null)
            a.triggered.connect(this, slot);

        if (shortcut instanceof String)
            a.setShortcut((String) shortcut);
        else if (shortcut instanceof QKeySequence.StandardKey)
            a.setShortcuts((QKeySequence.StandardKey) shortcut);

        return a;
    }

    private void setupMenu() {
        QToolBar toolbar = new QToolBar(this);
        toolbar.setWindowTitle(tr("File Actions"));
        addToolBar(toolbar);

        QMenu fileMenu = new QMenu(tr("&File"), this);
        menuBar().addMenu(fileMenu);
        action(tr("&Save As PDF"), "exportpdf", null, "saveAsPDFDesign()", fileMenu, toolbar);
        fileMenu.addSeparator();
        action(tr("&Quit"), null, "Ctrl+Q", "close()", fileMenu, null);
    }

}

