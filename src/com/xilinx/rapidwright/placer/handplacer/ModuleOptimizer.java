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
package com.xilinx.rapidwright.placer.handplacer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import io.qt.core.QPointF;
import io.qt.core.Qt;
import io.qt.gui.QAction;
import io.qt.widgets.QApplication;
import io.qt.widgets.QComboBox;
import io.qt.gui.QCursor;
import io.qt.widgets.QDockWidget;
import io.qt.widgets.QFileDialog;
import io.qt.widgets.QGraphicsItem;
import io.qt.gui.QIcon;
import io.qt.gui.QKeySequence;
import io.qt.widgets.QLabel;
import io.qt.widgets.QMainWindow;
import io.qt.widgets.QMenu;
import io.qt.widgets.QMessageBox;
import io.qt.gui.QPainter;
import io.qt.printsupport.QPrinter;
import io.qt.widgets.QProgressDialog;
import io.qt.widgets.QStatusBar;
import io.qt.widgets.QTableWidget;
import io.qt.widgets.QTableWidgetItem;
import io.qt.widgets.QToolBar;
import io.qt.widgets.QTreeWidget;
import io.qt.widgets.QTreeWidgetItem;
import io.qt.gui.QUndoStack;
import io.qt.widgets.QWidget;
import io.qt.widgets.QAbstractItemView;
import io.qt.gui.QPageSize;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.blocks.UtilizationType;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.FileFilters;
import com.xilinx.rapidwright.gui.GUIModuleInst;
import com.xilinx.rapidwright.gui.TileView;
import com.xilinx.rapidwright.util.FileTools;

public class ModuleOptimizer extends QMainWindow {

	private TileView view;
	private QLabel statusLabel;
	private FloorPlanScene scene;
	private String rsrcPath = FileTools.getRapidWrightPath()+File.separator+FileTools.IMAGES_FOLDER_NAME;
	private QAction actionUndo;
	private QAction actionRedo;
	private QAction actionZoomIn;
	private QAction actionZoomOut;
	private QAction actionZoomSelection;
	private QAction actionAddPBlock;
	private QUndoStack undoStack;
	private QTreeWidget macroList;
	private QTableWidget utilTable;
	Device device;
	private QComboBox netViewCombo;
	
	private static String title = "Module Optimizer";
	
	private String currOpenFileName = null;
		
	
	public static void main(String[] args) {
		QApplication.initialize(args);

		boolean debugPlacer = false;
		String fileToOpen = null;
		if(args.length > 0){
			fileToOpen = args[0];
		}
		if(args.length == 2 && args[1].equalsIgnoreCase("-g")){
			System.out.println("DEBUG MODE");
			debugPlacer = true;
		}
		
		ModuleOptimizer ModuleOptimizer = new ModuleOptimizer(null, fileToOpen, debugPlacer);

		ModuleOptimizer.show();

		QApplication.exec();
	}
	
	public ModuleOptimizer(QWidget parent, String fileToOpen, boolean debugPlacer){
		super(parent);
		
		undoStack = new QUndoStack();
		scene = new FloorPlanScene(null, debugPlacer);
		view = new TileView(scene);
		setCentralWidget(view);
		
		setupFileActions();
		setupEditActions();
		setupViewActions();
		setWindowTitle(title);
		
		undoStack.canRedoChanged.connect(actionRedo, "setEnabled(boolean)");
		undoStack.canUndoChanged.connect(actionUndo, "setEnabled(boolean)");

		createModuleList();
		createUtilizationTable();
		scene.selectionChanged.connect(this,"updateListSelection()");
		macroList.itemSelectionChanged.connect(this,"updateSceneSelection()");
		statusLabel = new QLabel("Status Bar");
		statusLabel.setText("Status Bar");
		
		scene.updateStatus.connect(this, "setStatusText(String, Tile)");
		scene.hmMoved.connect(this, "hmMoved(java.util.List, java.util.List)");

		
		QStatusBar statusBar = new QStatusBar();
		statusBar.addWidget(statusLabel);
		setStatusBar(statusBar);


		if(fileToOpen != null && new File(fileToOpen).exists()){
			internalOpenDesign(fileToOpen);
		}
		// Set the opening default window size to 1024x768 pixels
		resize(1024, 768);
	}
	
	@SuppressWarnings("unused")
	private void setStatusText(String text, Tile tile){
		statusLabel.setText(text);
	}
	
	private void populateMacroList(){
		macroList.clear();
		for(GUIModuleInst macro : scene.getMacroList()){
			QTreeWidgetItem treeItem = new QTreeWidgetItem();
			treeItem.setText(0, macro.getModuleInst().getName());
			String sizeFMT = String.format("%5d", macro.getSizeInTiles());
			treeItem.setText(1, sizeFMT);
			//treeItem.setText(1, macro.getModuleInst().getModule().getName());
			macroList.addTopLevelItem(treeItem);
		}
	}
	
	@SuppressWarnings("unused")
	private void updateListSelection(){
		if(macroList.hasFocus())
			return;
		macroList.clearSelection();
		for(QGraphicsItem item : scene.selectedItems()){
			String modInstName = ((GUIModuleInst)item).getModuleInst().getName();
			List<QTreeWidgetItem> itemList = macroList.findItems(modInstName, new Qt.MatchFlags(Qt.MatchFlag.MatchExactly), 0);
			if(itemList.size() > 0){
				itemList.get(0).setSelected(true);
			}
		}
	}
	
	@SuppressWarnings("unused")
	private void updateSceneSelection(){
		if(scene.hasFocus())
			return;
		scene.clearSelection();
		for(QTreeWidgetItem item : macroList.selectedItems()){
			String modInstName = item.text(0);
			GUIModuleInst gmi = scene.getGMI(modInstName);
			if(gmi != null){
				gmi.setSelected(true);
			}
		}
	}
	
	private void updateWireEstimate(){
		ArrayList<GUINetLine> netLineList = scene.getNetLineList();
		double estimate = 0;
		for(GUINetLine netLine : netLineList){
			estimate += netLine.line().length();
		}
	}
	
	public void hmMoved(List<GUIModuleInst> movedHMList, List<QPointF> oldPosList) {
		undoStack.push(new MoveCommand(movedHMList, oldPosList, scene));
		updateWireEstimate();
	}

	private void createUtilizationTable(){
		
		List<String> headerList = Arrays.asList("Used", "Avail", "%Util");
		List<String> vHeaderList = new ArrayList<String>(UtilizationType.values().length);
		for(UtilizationType type : UtilizationType.values()){
			vHeaderList.add(type.getString());
		}
		utilTable = new QTableWidget(vHeaderList.size(), headerList.size());
		
		utilTable.setHorizontalHeaderLabels(headerList);
		utilTable.setVerticalHeaderLabels(vHeaderList);
		for(int i=0; i < vHeaderList.size(); i++){
			utilTable.setRowHeight(i, 20);
		}
		for(int i=0; i < headerList.size(); i++){
			utilTable.setColumnWidth(i, 50);
		}
		
		QDockWidget dockWidget = new QDockWidget(tr("PBlock Utilization"), this);
		dockWidget.setAllowedAreas(Qt.DockWidgetArea.RightDockWidgetArea,
				Qt.DockWidgetArea.LeftDockWidgetArea);
		dockWidget.setWidget(utilTable);
		addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, dockWidget);
	}
	
	private void updateUtilizationTable(Design d){
		HashMap<UtilizationType,Integer> map = DesignTools.calculateUtilization(d);
		
		for(int i=0; i < UtilizationType.values.length; i++){
			Integer count = map.get(UtilizationType.values[i]);
			utilTable.setItem(i, 0, new QTableWidgetItem(count.toString()));
		}
		
		
	}
	
	private void createModuleList() {
		
		macroList = new QTreeWidget();
		macroList.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection);
		macroList.setColumnCount(2);
		ArrayList<String> headerList = new ArrayList<String>();
		headerList.add("Module Instance");
		headerList.add("Size(occupied tiles)");
		macroList.setHeaderLabels(headerList);
		macroList.setSortingEnabled(true);
		
		
		QDockWidget dockWidget = new QDockWidget(tr("Module List"), this);
		dockWidget.setAllowedAreas(Qt.DockWidgetArea.RightDockWidgetArea,
				Qt.DockWidgetArea.LeftDockWidgetArea);
		dockWidget.setWidget(macroList);
		addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, dockWidget);
	}
	
	protected void about() {
		QMessageBox.information(this, "Info",
				"Interactive Module Optimization Tool\n built on RapidWright.");
	}

	protected void openDesign(){
		String fileName = QFileDialog.getOpenFileName(this, "Choose a file...",
				".", FileFilters.dcpFilter).toString();
		if(fileName.endsWith(".dcp")){
			internalOpenDesign(fileName);
		}
	}
	
	private void internalOpenDesign(String fileName){
		currOpenFileName = fileName;
		String shortFileName = fileName.substring(fileName.lastIndexOf('/')+1);
		QProgressDialog progress = new QProgressDialog("Loading "+currOpenFileName+"...", "", 0, 100, this);
		progress.setWindowTitle("Load Progress");
		progress.setWindowModality(Qt.WindowModality.WindowModal);
		progress.setCancelButton(null);
		progress.show();
		progress.setValue(0);	
		undoStack.clear();
		progress.setValue(10);
		Design design = Design.readCheckpoint(fileName);
		progress.setValue(20);
		updateUtilizationTable(design);
		progress.setValue(50);
		System.out.println(design.getPartName());
		netViewCombo.setEnabled(true);
		actionZoomIn.setEnabled(true);
		actionZoomOut.setEnabled(true);
		actionZoomSelection.setEnabled(true);
		progress.setValue(70);
		netViewCombo.setCurrentIndex(0);
		progress.setValue(80);
		scene.openNewDesign(design);
		progress.setValue(90);
		setWindowTitle(shortFileName + " - " + title);
		populateMacroList();
		scene.changeNetView(0);
		progress.setValue(100);
	}
	
	protected void saveDesign(){
		if(scene.getDesign() == null || currOpenFileName == null)
			return;
		scene.getDesign().writeCheckpoint(currOpenFileName);
        statusBar().showMessage(currOpenFileName + " saved.", 2000);
	}
	
	protected void saveAsDesign(){
		if(scene.getDesign() == null)
			return;
		String fileName = QFileDialog.getSaveFileName(this, tr("Save As"),".", FileFilters.dcpFilter).toString();
        if (fileName.length() == 0)
            return;
        scene.getDesign().flattenDesign();
        scene.getDesign().writeCheckpoint(fileName);
        statusBar().showMessage(fileName + " saved.", 2000);
	}
	
	protected void saveAsPDFDesign(){
		if(scene.getDesign() == null)
			return;
		String fileName = QFileDialog.getSaveFileName(this, tr("Save As PDF"),".", FileFilters.pdfFilter).toString();
        if (fileName.length() == 0)
            return;
        QPrinter printer = new QPrinter();
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat);
        printer.setOutputFileName(fileName);
        printer.setPageSize(new QPageSize(QPageSize.PageSizeId.Letter));
        QPainter pdfPainter = new QPainter(printer);
        scene.render(pdfPainter);
        pdfPainter.end();
        statusBar().showMessage(fileName + " saved.", 2000);
	}
	
	private QAction action(String name, String image, Object shortcut,
			String slot, QMenu menu, QToolBar toolBar) {
		QAction a = new QAction(name, this);

		if (image != null)
			a.setIcon(new QIcon(rsrcPath + File.separator + image + ".png"));
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

	private void setupFileActions() {
		QToolBar tb = new QToolBar(this);
		tb.setWindowTitle(tr("File Actions"));
		addToolBar(tb);

		QMenu fileMenu = new QMenu(tr("&File"), this);
		menuBar().addMenu(fileMenu);

		action(tr("Open"), "fileopen", QKeySequence.StandardKey.Open, "openDesign()",fileMenu, tb);
		fileMenu.addSeparator();
		action(tr("&Save"), "filesave", QKeySequence.StandardKey.Save, "saveDesign()", fileMenu, tb);
		action(tr("&Save As"), "filesaveas", QKeySequence.StandardKey.SaveAs, "saveAsDesign()", fileMenu, tb);
		action(tr("&Save As PDF"), "exportpdf", null, "saveAsPDFDesign()", fileMenu, tb);
		fileMenu.addSeparator();
		action(tr("&Print"), "fileprint", QKeySequence.StandardKey.Print, null, fileMenu, tb);
		fileMenu.addSeparator();
		action(tr("&Quit"), null, "Ctrl+Q", "close()", fileMenu, null);
	}

	private void setupEditActions() {
		QToolBar b = new QToolBar(this);
		b.setWindowTitle(tr("Edit Actions"));
		addToolBar(b);

		QMenu m = new QMenu(tr("&Edit"), this);
		menuBar().addMenu(m);

		actionUndo = action(tr("&Undo"), "editundo", QKeySequence.StandardKey.Undo, null, m,
				b);
		actionUndo.setEnabled(false);
		actionUndo.triggered.connect(undoStack, "undo()");
		actionRedo = action(tr("&Redo"), "editredo", QKeySequence.StandardKey.Redo, null, m,
				b);
		actionRedo.setEnabled(false);
		actionRedo.triggered.connect(undoStack, "redo()");
		
		actionAddPBlock = action(tr("Add PBlock"), "addPblock", "Ctrl+b", "addPblock()", m, b);
		actionAddPBlock.setEnabled(true);
	}
	
	private void setupViewActions(){
		QToolBar tb = new QToolBar(this);
		tb.setWindowTitle(tr("View Actions"));
		addToolBar(tb);
		QMenu m = new QMenu(tr("&View"), this);
		menuBar().addMenu(m);
		
		netViewCombo = new QComboBox();
		netViewCombo.addItem(tr("Nets hidden"));
		netViewCombo.addItem(tr("Module-to-module"));
		//netViewCombo.addItem(tr("All nets(not clk)"));
		netViewCombo.setEnabled(false);
		netViewCombo.currentIndexChanged.connect(scene,"changeNetView(int)");
		tb.addWidget(netViewCombo);
		
		actionZoomIn = action(tr("&Zoom Out"), "zoomout", QKeySequence.StandardKey.ZoomOut, "zoomout()", m, tb);
		actionZoomOut = action(tr("&Zoom In"), "zoomin", "Ctrl+=", "zoomin()", m, tb);
		actionZoomSelection = action(tr("&Zoom Selection"), "zoomselection", null, "zoomselection()", m, tb);
		actionZoomIn.setEnabled(false);
		actionZoomOut.setEnabled(false);
		actionZoomSelection.setEnabled(false);
	}

	@SuppressWarnings("unused")
	private void zoomin(){
		view.zoomIn();
	}
	@SuppressWarnings("unused")
	private void zoomout(){
		view.zoomOut();
	}
	@SuppressWarnings("unused")
	private void zoomselection(){
		double top=-1,left=-1,right=-1,bottom=-1;
		for(QGraphicsItem item : scene.selectedItems()){
			QPointF gmiTL = item.pos();
			QPointF gmiBR = item.pos().add(item.boundingRect().bottomRight());
			if(top < 0 || gmiTL.y() < top)
				top = gmiTL.y();
			if(left < 0 || gmiTL.x() < left)
				left = gmiTL.x();
			if(bottom < 0 || gmiBR.y() > bottom)
				bottom = gmiBR.y();
			if(right < 0 || gmiBR.x() > right)
				right = gmiBR.x();
		}
		view.fitInView(left, top, right-left, bottom-top, Qt.AspectRatioMode.KeepAspectRatio);
	}
	@SuppressWarnings("unused")
	private void addPblock(){
		System.out.println("Adding a PBlock...");
		view.addPBlockMode(true);
		view.setCursor(new QCursor(Qt.CursorShape.CrossCursor));
	}
}
