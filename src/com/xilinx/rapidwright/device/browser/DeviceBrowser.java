/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.trolltech.qt.core.QEvent;
import com.trolltech.qt.core.QModelIndex;
import com.trolltech.qt.core.Qt.DockWidgetArea;
import com.trolltech.qt.core.Qt.ItemDataRole;
import com.trolltech.qt.core.Qt.SortOrder;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QDockWidget;
import com.trolltech.qt.gui.QDockWidget.DockWidgetFeature;
import com.trolltech.qt.gui.QLabel;
import com.trolltech.qt.gui.QMainWindow;
import com.trolltech.qt.gui.QStatusBar;
import com.trolltech.qt.gui.QTreeWidget;
import com.trolltech.qt.gui.QTreeWidgetItem;
import com.trolltech.qt.gui.QWidget;
import com.xilinx.rapidwright.design.tools.TileGroup;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.gui.TileView;
import com.xilinx.rapidwright.gui.WidgetMaker;

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
    /** This is the tree of nodes to select */
    private QTreeWidget nodeTreeWidget;
    /** This is the list of primitive sites in the current tile selected */
    private QTreeWidget primitiveList;
    /** This is the list of wires in the current tile selected */
    private QTreeWidget wireList;

    private Map<String, QTreeWidgetItem> nodeMap;

    /** This is the current tile that has been selected */
    private Tile currTile = null;

    protected boolean hideTiles = false;

    protected boolean drawPrimitives = true;

    protected static final String UPHILL_NODES = "Uphill Nodes";
    protected static final String DOWNHILL_NODES = "Downhill Nodes";
    /**
     * Main method setting up the Qt environment for the program to run.
     * @param args
     */
    public static void main(String[] args) {
        QApplication.setGraphicsSystem("raster");
        QApplication.initialize(args);

        String defaultPart = null;
        if (args.length>0) {
            defaultPart = args[0];
        }

        DeviceBrowser testPTB = new DeviceBrowser(null, defaultPart);
        testPTB.show();
        QApplication.exec();
    }

    /**
     * Constructor which initializes the GUI and loads the first part found.
     * @param parent The Parent widget, used to add this window into other GUIs.
     */
    public DeviceBrowser(QWidget parent, String defaultPart) {
        super(parent);

        // set the title of the window
        setWindowTitle("Device Browser");

        initializeSideBar();

        // Gets the available parts in RapidWright and populates the selection tree
        Set<String> parts = WidgetMaker.getSupportedDevices();
        if (parts.size() < 1) {
            throw new RuntimeException("Error: No available parts. " +
                    "Please generate part database files.");
        }

        if (defaultPart == null) {
            defaultPart = "xcku040";
        }
        if (parts.contains(defaultPart)) {
            currPart = defaultPart;
        } else {
            currPart = parts.iterator().next();
            System.out.println(defaultPart+" not available, showing "+currPart);
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

        // Set the opening default window size to 1280x1024 pixels
        resize(1280, 1024);
    }

    public DeviceBrowser(QWidget parent) {
        this(parent, null);
    }

    /**
     * Populates the treeWidget with the various parts and families of devices
     * currently available in this installation of RapidWright.  It also creates
     * the windows for the primitive site list and wire list.
     */
    private void initializeSideBar() {
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

        // Create the node list window
        nodeMap = new HashMap<>();
        nodeTreeWidget = new QTreeWidget();
        nodeTreeWidget.setColumnCount(1);
        nodeTreeWidget.setHeaderLabel("Nodes");
        QDockWidget nodeWidget = new QDockWidget(tr("Node List"), this);
        nodeWidget.setWidget(nodeTreeWidget);
        nodeWidget.setFeatures(DockWidgetFeature.DockWidgetMovable);
        addDockWidget(DockWidgetArea.LeftDockWidgetArea, nodeWidget);

        // Draw wire connections when the wire name is double clicked
        nodeTreeWidget.doubleClicked.connect(this, "nodeDoubleClicked(QModelIndex)");

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
    public void wireDoubleClicked(QModelIndex index) {
        scene.clearCurrentLines();
        if (currTile == null) return;
        int currWire = currTile.getWireIndex(index.data().toString());
        if (currWire < 0) return;
        if (currTile.getWireConnections(index.data().toString()) == null) return;
        for (Wire wire : currTile.getWireConnections(index.data().toString())) {
            scene.drawWire(currTile, currWire, wire.getTile(), wire.getWireIndex());
        }
    }

    /**
     * Expands items in the node tree to add children nodes under uphill and
     * downhill.
     * 
     * @param index
     */
    public void nodeDoubleClicked(QModelIndex index) {
        String currNodeName = index.data().toString();
        if (!currNodeName.equals(DOWNHILL_NODES) && !currNodeName.equals(UPHILL_NODES)) {
            QTreeWidgetItem parent = nodeMap.get(index.data().toString());
            updateNodeItem(currTile.getDevice().getNode(currNodeName), parent);
        }
    }

    /**
     * This method gets called each time a user double clicks on a tile.
     */
    protected void updateTile(Tile tile) {
        currTile = tile;
        updatePrimitiveList();
        updateWireList();
        updateNodeTreeWidget();
    }

    /**
     * This will update the primitive list window based on the current
     * selected tile.
     */
    protected void updatePrimitiveList() {
        primitiveList.clear();
        if (currTile == null) return;
        for (Site ps : currTile.getSites()) {
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
    protected void updateWireList() {
        wireList.clear();
        if (currTile == null || currTile.getWireNames() == null) return;
        for (String wire : currTile.getWireNames()) {
            QTreeWidgetItem treeItem = new QTreeWidgetItem();
            treeItem.setText(0, wire);
            List<Wire> connections = currTile.getWireConnections(wire);
            treeItem.setText(1, String.format("%3d", connections == null ? 0 : connections.size()));
            wireList.insertTopLevelItem(0, treeItem);
        }
        wireList.sortByColumn(0, SortOrder.AscendingOrder);
    }

    protected void updateNodeTreeWidget() {
        nodeTreeWidget.clear();

        if (currTile == null || currTile.getWireNames() == null)
            return;
        Device d = currTile.getDevice();
        for (int i = 0; i < currTile.getWireCount(); i++) {
            Node n = d.getNode(currTile.toString() + "/" + currTile.getWireName(i));
            if (n == null || n.isInvalidNode())
                continue;

            QTreeWidgetItem treeItem = new QTreeWidgetItem(nodeTreeWidget);
            treeItem.setText(0, n.toString());
            updateNodeItem(n, treeItem);
        }
    }

    private void updateNodeItem(Node n, QTreeWidgetItem parent) {
        QTreeWidgetItem uphillItem = new QTreeWidgetItem(parent);
        uphillItem.setText(0, UPHILL_NODES);
        for (Node up : n.getAllUphillNodes()) {
            QTreeWidgetItem upNodeItem = new QTreeWidgetItem(uphillItem);
            upNodeItem.setText(0, up.toString());
            nodeMap.put(upNodeItem.text(0), upNodeItem);
        }
        QTreeWidgetItem downhillItem = new QTreeWidgetItem(parent);
        downhillItem.setText(0, DOWNHILL_NODES);
        for (Node down : n.getAllDownhillNodes()) {
            QTreeWidgetItem downNodeItem = new QTreeWidgetItem(downhillItem);
            downNodeItem.setText(0, down.toString());
            nodeMap.put(downNodeItem.text(0), downNodeItem);
        }
    }

    /**
     * This method loads a new device based on the part name selected in the
     * treeWidget.
     * @param qmIndex The index of the part to load.
     */
    protected void showPart(QModelIndex qmIndex) {
        Object data = qmIndex.data(ItemDataRole.AccessibleDescriptionRole);
        if ( data != null) {
            if (currPart.equals(data))
                return;
            setPart((String) data);
        }
    }

    private void setPart(String partName) {
        currPart = partName;
        device = Device.getDevice(currPart);
        scene.setDevice(device);
        scene.initializeScene(hideTiles, drawPrimitives);
        statusLabel.setText("Loaded: " + currPart.toUpperCase());
    }

    /**
     * This method updates the status bar each time the mouse moves from a
     * different tile.
     */
    protected void updateStatus(String text, Tile tile) {
        statusLabel.setText(text);
        //currTile = tile;
        //System.out.println("currTile=" + tile);
    }

    public Device getDevice() {
        return device;
    }

    public DeviceBrowserScene getScene() {
        return scene;
    }

    /**
     * Triggers an event on the scene to clear any highlighted tiles
     */
    public void clearHighlightedTiles() {
        QEvent event = new QEvent(QEvent.Type.resolve(DeviceBrowserScene.CLEAR_HIGHLIGHTED_TILES));
        QApplication.postEvent(scene, event);
    }

    /**
     * Populates the scene with the tile group and triggers a highlight event on the
     * scene object to highlight the perimeter tiles of the tile groups
     * 
     * @param tileGroup The tile group to highlight
     */
    public void highlightTileGroup(TileGroup tileGroup) {
        List<TileGroup> tgs = new ArrayList<>();
        tgs.add(tileGroup);
        highlightTileGroups(tgs);
    }

    /**
     * Populates the scene with the tile groups and triggers a highlight event on
     * the scene object to highlight the perimeter tiles of the tile groups.
     * 
     * @param tileGroups The tile groups to highlight
     */
    public void highlightTileGroups(List<TileGroup> tileGroups) {
        scene.tileGroups = tileGroups;
        QEvent event = new QEvent(QEvent.Type.resolve(DeviceBrowserScene.HIGHLIGHT_TILE_GROUPS));
        QApplication.postEvent(scene, event);
    }

    /** Static reference to a threaded instance */
    private static ThreadedDeviceBrowser threadedBrowser;

    /**
     * Creates a new DeviceBrowser window in a separate thread so that it can be
     * called interactively from an interpreter.
     * 
     * @param partName The device or part name to load
     * @return The DeviceBrowser window object
     * @throws InterruptedException
     */
    public static DeviceBrowser createWindow(String partName) throws InterruptedException {
        if (threadedBrowser != null) {
            System.err.println("ERROR: Only a single instance of the DeviceBrowser is currently supported");
            return null;
        }
        threadedBrowser = new ThreadedDeviceBrowser(new String[] { partName });
        threadedBrowser.start();
        // Since start() returns immediately, we need to wait until the constructor has
        // finished populating class member variables before continuting
        while (threadedBrowser.getDeviceBrowser() == null) {
            Thread.sleep(10);
        }
        return threadedBrowser.getDeviceBrowser();
    }

    public static void removeThreadedInstance() {
        threadedBrowser = null;
    }
}
