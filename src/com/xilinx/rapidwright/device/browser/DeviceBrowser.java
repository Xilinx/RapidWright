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
package com.xilinx.rapidwright.device.browser;

import java.util.ArrayList;
import java.util.List;

import com.trolltech.qt.core.QModelIndex;
import com.trolltech.qt.core.Qt.DockWidgetArea;
import com.trolltech.qt.core.Qt.ItemDataRole;
import com.trolltech.qt.core.Qt.SortOrder;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QDockWidget;
import com.trolltech.qt.gui.QLabel;
import com.trolltech.qt.gui.QMainWindow;
import com.trolltech.qt.gui.QStatusBar;
import com.trolltech.qt.gui.QTreeWidget;
import com.trolltech.qt.gui.QTreeWidgetItem;
import com.trolltech.qt.gui.QWidget;
import com.trolltech.qt.gui.QDockWidget.DockWidgetFeature;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.gui.TileView;
import com.xilinx.rapidwright.gui.WidgetMaker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * This class creates an interactive Xilinx FPGA device browser for all of the
 * devices currently installed on RapidWright.  It provides the user with a 2D view
 * of all tile array in the device.  Allows each tile to be selected (double click)
 * and populate the site and wire lists.  Wire connections can also be drawn
 * by selecting a specific wire in the tile (from the list) and the program will draw
 * all connections that can be made from that wire.  The wire positions on the tile
 * are determined by a hash and are not related to FPGA Editor positions.   
 * @author Chris Lavin and Marc Padilla
 * Created on: Nov 26, 2010
 */
public class DeviceBrowser extends QMainWindow{
	/** The Qt View for the browser */
	protected TileView view;
	/** The Qt Scene for the browser */
	protected DeviceBrowserScene scene;
	/** The label for the status bar at the bottom */
	private QLabel statusLabel;
	/** The current device loaded */
	Device device;
	/** The current part name of the device loaded */
	private String currPart;
	/** This is the tree of parts to select */
	private QTreeWidget treeWidget;
	/** This is the list of primitive sites in the current tile selected */
	private QTreeWidget primitiveList;
	/** This is the list of wires in the current tile selected */
	private QTreeWidget wireList;
	/** This is the current tile that has been selected */
	private Tile currTile = null;
	
	protected boolean hideTiles = false;
	
	protected boolean drawPrimitives = true; 
	/**
	 * Main method setting up the Qt environment for the program to run.
	 * @param args
	 */
	public static void main(String[] args){
		QApplication.setGraphicsSystem("raster");
		QApplication.initialize(args);
		DeviceBrowser testPTB = new DeviceBrowser(null);
		testPTB.show();
		QApplication.exec();
	}

	/**
	 * Constructor which initializes the GUI and loads the first part found.
	 * @param parent The Parent widget, used to add this window into other GUIs.
	 */
	public DeviceBrowser(QWidget parent){
		super(parent);
		
		// set the title of the window
		setWindowTitle("Device Browser");
		
		initializeSideBar();
		
		// Gets the available parts in RapidWright and populates the selection tree
		List<String> parts = Device.getAvailableDevices();
		if(parts.size() < 1){
			MessageGenerator.briefErrorAndExit("Error: No available parts. " +
					"Please generate part database files.");
		}
		if(parts.contains("xcku040-ffva1156")){
			currPart = "xcku040-ffva1156";
		}
		else{
			currPart = parts.get(0);
		}
		
		device = Device.getDevice(currPart);
		
		// Setup the scene and view for the GUI
		scene = new DeviceBrowserScene(device, hideTiles, drawPrimitives, this);
		view = new TileView(scene);
		setCentralWidget(view);

		// Setup some signals for when the user interacts with the view
		scene.updateStatus.connect(this, "updateStatus(String, Tile)");
		scene.updateTile.connect(this, "updateTile(Tile)");
		
		// Initialize the status bar at the bottom
		statusLabel = new QLabel("Status Bar");
		statusLabel.setText("Status Bar");
		QStatusBar statusBar = new QStatusBar();
		statusBar.addWidget(statusLabel);
		setStatusBar(statusBar);
		
		// Set the opening default window size to 1024x768 pixels
		resize(1024, 768);
	}

	/**
	 * Populates the treeWidget with the various parts and families of devices
	 * currently available in this installation of RapidWright.  It also creates
	 * the windows for the primitive site list and wire list.
	 */
	private void initializeSideBar(){
		treeWidget = WidgetMaker.createAvailablePartTreeWidget("Select a part...");
		treeWidget.doubleClicked.connect(this,"showPart(QModelIndex)");
		
		QDockWidget dockWidget = new QDockWidget(tr("Part Browser"), this);
		dockWidget.setWidget(treeWidget);
		dockWidget.setFeatures(DockWidgetFeature.DockWidgetMovable);
		addDockWidget(DockWidgetArea.LeftDockWidgetArea, dockWidget);
		
		// Create the primitive site list window
		primitiveList = new QTreeWidget();
		primitiveList.setColumnCount(2);
		ArrayList<String> headerList = new ArrayList<String>();
		headerList.add("Site");
		headerList.add("Type");
		primitiveList.setHeaderLabels(headerList);
		primitiveList.setSortingEnabled(true);
		
		QDockWidget dockWidget2 = new QDockWidget(tr("Primitive List"), this);
		dockWidget2.setWidget(primitiveList);
		dockWidget2.setFeatures(DockWidgetFeature.DockWidgetMovable);
		addDockWidget(DockWidgetArea.LeftDockWidgetArea, dockWidget2);
		
		// Create the wire list window
		wireList = new QTreeWidget();
		wireList.setColumnCount(2);
		ArrayList<String> headerList2 = new ArrayList<String>();
		headerList2.add("Wire");
		headerList2.add("Sink Connections");
		wireList.setHeaderLabels(headerList2);
		wireList.setSortingEnabled(true);
		QDockWidget dockWidget3 = new QDockWidget(tr("Wire List"), this);
		dockWidget3.setWidget(wireList);
		dockWidget3.setFeatures(DockWidgetFeature.DockWidgetMovable);
		addDockWidget(DockWidgetArea.LeftDockWidgetArea, dockWidget3);

		// Draw wire connections when the wire name is double clicked
		wireList.doubleClicked.connect(this, "wireDoubleClicked(QModelIndex)");
	}
	
	/**
	 * This method will draw all of the wire connections based on the wire given.
	 * @param index The index of the wire in the wire list.
	 */
	public void wireDoubleClicked(QModelIndex index){
		scene.clearCurrentLines();
		if(currTile == null) return;
		int currWire = currTile.getWireIndex(index.data().toString());
		if(currWire < 0) return;
		if(currTile.getWireConnections(index.data().toString()) == null) return;
		for(Wire wire : currTile.getWireConnections(index.data().toString())){
			scene.drawWire(currTile, currWire, wire.getTile(), wire.getWireIndex());
		}
	}
	
	/**
	 * This method gets called each time a user double clicks on a tile.
	 */
	protected void updateTile(Tile tile){
		currTile = tile;
		updatePrimitiveList();
		updateWireList();
	}
	
	/**
	 * This will update the primitive list window based on the current
	 * selected tile.
	 */
	protected void updatePrimitiveList(){
		primitiveList.clear();
		if(currTile == null) return;
		for(Site ps : currTile.getSites()){
			QTreeWidgetItem treeItem = new QTreeWidgetItem();
			treeItem.setText(0, ps.getName());
			treeItem.setText(1, ps.getSiteTypeEnum().toString());
			primitiveList.insertTopLevelItem(0, treeItem);
		}
	}

	/**
	 * This will update the wire list window based on the current
	 * selected tile.
	 */
	protected void updateWireList(){
		wireList.clear();
		if(currTile == null || currTile.getWireNames() == null) return;
		for(String wire : currTile.getWireNames()) {
			QTreeWidgetItem treeItem = new QTreeWidgetItem();
			treeItem.setText(0, wire);
			List<Wire> connections = currTile.getWireConnections(wire);
			treeItem.setText(1, String.format("%3d", connections == null ? 0 : connections.size()));
			wireList.insertTopLevelItem(0, treeItem);
		}
		wireList.sortByColumn(0, SortOrder.AscendingOrder);
	}

	/**
	 * This method loads a new device based on the part name selected in the 
	 * treeWidget.
	 * @param qmIndex The index of the part to load.
	 */
	protected void showPart(QModelIndex qmIndex){
		Object data = qmIndex.data(ItemDataRole.AccessibleDescriptionRole);
		if( data != null){
			if(currPart.equals(data))
				return;
			currPart = (String) data;			
			device = Device.getDevice(currPart);
			scene.setDevice(device);
			scene.initializeScene(hideTiles, drawPrimitives);
			statusLabel.setText("Loaded: "+currPart.toUpperCase());
		}
	}
	
	/**
	 * This method updates the status bar each time the mouse moves from a 
	 * different tile.
	 */
	protected void updateStatus(String text, Tile tile){
		statusLabel.setText(text);
		//currTile = tile;
		//System.out.println("currTile=" + tile);
	}
}
