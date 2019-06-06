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

import java.util.ArrayList;
import java.util.List;

import com.trolltech.qt.core.QModelIndex;
import com.trolltech.qt.core.Qt.DockWidgetArea;
import com.trolltech.qt.core.Qt.ItemDataRole;
import com.trolltech.qt.core.Qt.WindowModality;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QDockWidget;
import com.trolltech.qt.gui.QLabel;
import com.trolltech.qt.gui.QMainWindow;
import com.trolltech.qt.gui.QProgressDialog;
import com.trolltech.qt.gui.QStatusBar;
import com.trolltech.qt.gui.QTreeWidget;
import com.trolltech.qt.gui.QWidget;
import com.trolltech.qt.gui.QDockWidget.DockWidgetFeature;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.gui.WidgetMaker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * This class is an example of how RapidWright could be used to build
 * interactive tools using Qt or other GUI packages.  This class
 * creates a zoom-able 2D array of the tiles found in the devices installed 
 * with RapidWright.  This example requires the Qt Jambi (Qt for Java)
 * jars to run.
 * @author marc
 */
public class PartTileBrowser extends QMainWindow{
	/** This is the Qt View object for the tile browser */
	private PartTileBrowserView view;
	/** This is the container for the text in the Status Bar at the bottom of the screen */
	private QLabel statusLabel;
	/** This is the Qt Scene object for the tile browser */
	private PartTileBrowserScene scene;
	/** The current device that has been loaded */
	Device device;
	/** The current part name */
	private String currPartName;
	/** This is the part chooser widget */
	private QTreeWidget treeWidget;

	/**
	 * Main method
	 * @param args
	 */
	public static void main(String[] args){
		// This line fixes slow performance under Linux
		QApplication.setGraphicsSystem("raster");
		
		QApplication.initialize(args);
		PartTileBrowser testPTB = new PartTileBrowser(null);
		testPTB.show();
		QApplication.exec();
	}

	/**
	 * Constructor of a new PartTileBrowser
	 * @param parent Parent widget to which this object belongs.
	 */
	public PartTileBrowser(QWidget parent) {
		super(parent);
		setWindowTitle("Part Tile Browser");

		createTreeView();
		List<String> parts = Device.getAvailableDevices();
		if(parts.size() < 1){
			MessageGenerator.briefErrorAndExit("Error: No available parts. Please generate part database files.");
		}
		currPartName = parts.get(0);
		device = Device.getDevice(currPartName);
		
		scene = new PartTileBrowserScene(device);

		view = new PartTileBrowserView(scene);

		setCentralWidget(view);
		
		scene.updateStatus.connect(this, "updateStatus()");
		statusLabel = new QLabel("Status Bar");
		statusLabel.setText("Status Bar");
		QStatusBar statusBar = new QStatusBar();
		statusBar.addWidget(statusLabel);
		setStatusBar(statusBar);

	}

	private void createTreeView() {
		treeWidget = WidgetMaker.createAvailablePartTreeWidget("Select a part...");	
		treeWidget.doubleClicked.connect(this,"showPart(QModelIndex)");
		
		QDockWidget dockWidget = new QDockWidget(tr("Part Browser"), this);
		dockWidget.setAllowedAreas(DockWidgetArea.LeftDockWidgetArea);
		dockWidget.setWidget(treeWidget);
		dockWidget.setFeatures(DockWidgetFeature.NoDockWidgetFeatures);
		addDockWidget(DockWidgetArea.LeftDockWidgetArea, dockWidget);
	}

	@SuppressWarnings("unused")
	private void showPart(QModelIndex qmIndex){
		Object data = qmIndex.data(ItemDataRole.AccessibleDescriptionRole);
		if( data != null){
			if(currPartName.equals(data))
				return;
			currPartName = (String) data;
			QProgressDialog progress = new QProgressDialog("Loading "+currPartName.toUpperCase()+"...", "", 0, 100, this);
			progress.setWindowTitle("Load Progress");
			progress.setWindowModality(WindowModality.WindowModal);
			progress.setCancelButton(null);
			progress.show();
			progress.setValue(10);
			
			device = Device.getDevice(currPartName);
			progress.setValue(100);
			scene.setDevice(device);
			statusLabel.setText("Loaded: "+currPartName.toUpperCase());

			
		}
	}
	void updateStatus() {
		int x = (int) scene.getCurrX();
		int y = (int) scene.getCurrY();
		if (x >= 0 && x < device.getColumns() && y >= 0 && y < device.getRows()){
			String tileName = device.getTile(y, x).getName();
			statusLabel.setText("Part: "+currPartName.toUpperCase() +"  Tile: "+ tileName+" ("+x+","+y+")");
		}
	}

}
