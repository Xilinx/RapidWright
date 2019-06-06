/* 
 * Copyright (c) 2017 Xilinx, Inc. 
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
/**
 * 
 */
package com.xilinx.rapidwright.device.browser;


import com.trolltech.qt.gui.QAction;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QMenu;
import com.trolltech.qt.gui.QToolBar;
import com.trolltech.qt.gui.QWidget;
import com.xilinx.rapidwright.design.blocks.PBlockGenerator;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.TileView;

/**
 * WIP. 
 * Created on: Jun 18, 2015
 */
public class PBlockGenDebugger extends DeviceBrowser {

	PBlockGenerator pbGen = null;
	
	public PBlockGenDebugger(QWidget parent) {
		super(parent);
		
		// set the title of the window
		setWindowTitle("PBlock Debugger");

		// Setup the scene and view for the GUI
		scene = new PBlockGenScene(device, hideTiles, drawPrimitives, this);
		view = new TileView(scene);
		setCentralWidget(view);

		// Setup some signals for when the user interacts with the view
		scene.updateStatus.connect(this, "updateStatus(String, Tile)");
		scene.updateTile.connect(this, "updateTile(Tile)");

		pbGen = new PBlockGenerator();
		pbGen.getEmitter().highlightTile.connect(this, "highlightTile(Tile)");

		QToolBar tb = new QToolBar(this);
		tb.setWindowTitle(tr("File Actions"));
		addToolBar(tb);

		QMenu fileMenu = new QMenu(tr("&File"), this);
		menuBar().addMenu(fileMenu);
		
		QAction a = new QAction("Debug PBlockGenerator", this);
		fileMenu.addAction(a);
		a.triggered.connect(this, "debugPBlockGenerator()");
	}

	private class DebugPBGen extends Thread{
		PBlockGenerator pbgen; 
		
		public DebugPBGen(PBlockGenerator pbgen){
			this.pbgen = pbgen;
		}
		
		public void run(){
			PBlockGenerator.debug = true;
			
			 

			
			String utilReportFile = "/home/clavin/build_fma/build_fma.runs/design_1_fma_ip_0_0_synth_1/design_1_fma_ip_0_0_utilization.report";
			String shapeFile = "/home/clavin/build_fma/build_fma.runs/design_1_fma_ip_0_0_synth_1/design_1_fma_ip_0_0_shapes.txt";
			
			String[] pBlockArgs = new String[]{
					"-u", utilReportFile, 
					"-s", shapeFile,
					"-c", "4",
					"-a", "1.0",
					"-o", "1.5"
			};
			PBlockGenerator.main(pBlockArgs);
			/*for(int x=0; x < 100; x++){
				pBlockArgs[5] = Integer.toString(x);
				if(x==49){
					System.out.println(Arrays.toString(pBlockArgs));
					//PBlockGenerator.main(pBlockArgs);
					PBlockGenerator.main(pBlockArgs);
				}

			}*/
			
			throw new RuntimeException("To use this debugging feature, you need to modify PBlockGenDebugger.run()");

		}
	}
	
	public void debugPBlockGenerator(){
		DebugPBGen d = new DebugPBGen(pbGen);
		d.start();
	}
	
	public void highlightTile(Tile t){
		((PBlockGenScene)scene).highlightTile(t);
	}
	
	
	public static void main(String[] args){
		QApplication.setGraphicsSystem("raster");
		QApplication.initialize(args);
		PBlockGenDebugger pbDebugger = new PBlockGenDebugger(null);
		pbDebugger.show();
		QApplication.exec();
	}
}
