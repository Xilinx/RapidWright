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
/**
 * 
 */
package com.xilinx.rapidwright.placer.handplacer;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.trolltech.qt.core.Qt;
import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsItemInterface;
import com.trolltech.qt.gui.QGraphicsLineItem;
import com.trolltech.qt.gui.QGraphicsRectItem;
import com.trolltech.qt.gui.QGraphicsSceneMouseEvent;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QPolygonF;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Port;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.GUIModuleInst;
import com.xilinx.rapidwright.gui.HMTile;
import com.xilinx.rapidwright.gui.TileScene;

/**
 * @author marc
 * 
 */
public class FloorPlanScene extends TileScene {

	/**  */
	List<GUIModuleInst> movingHMList;
	/**  */
	public Signal2<List<GUIModuleInst>, List<QPointF>> hmMoved = new Signal2<List<GUIModuleInst>, List<QPointF>>();
	/**  */
	public Signal0 mousePressed = new Signal0();
	/**  */
	protected ArrayList<QPointF> movingPosList;
	/**  */
	private ArrayList<GUINetLine> netLineList;
	/**  */
	private ArrayList<GUIModuleInst> polyList;
	/**  */
	HashMap<String,GUIMultiNetLine> multiNetLineMap;
	/**  */
	protected boolean debugPlacer;
	/**  */
	private HashMap<String, GUIModuleInst> macroMap;

	private ArrayList<ValidPlacementPolygon> validPlacements;
	
	private static final int HIDE_NETS = 0;
	private static final int MODULE_TO_MODULE = 1;

	private int netViewState = HIDE_NETS;
	
	public FloorPlanScene(){
		this(null, false);
	}
	
	public FloorPlanScene(Design design, boolean debugPlacer){
		super(design, true, true);
		this.debugPlacer = debugPlacer;
		initializeScene();
		// Let's not use a cursor
		cursorPen = new QPen(QColor.transparent);
	}
	
	public void initializeScene() {
		polyList = new ArrayList<GUIModuleInst>();
		multiNetLineMap = new HashMap<String, GUIMultiNetLine>();
		netLineList = new ArrayList<GUINetLine>();
		movingHMList = new ArrayList<GUIModuleInst>();
		movingPosList = new ArrayList<QPointF>();
		macroMap = new HashMap<String, GUIModuleInst>();
		validPlacements = new ArrayList<ValidPlacementPolygon>();
		initializeScene(true, true);
	}
	
	@Override
	public void mousePressEvent(QGraphicsSceneMouseEvent event) {
		super.mousePressEvent(event);
		movingHMList.clear();
		movingPosList.clear();
		for (GUIModuleInst ghmpi : polyList) {
			if (ghmpi.isSelected() || ghmpi.isGrabbed()) {
				movingHMList.add(ghmpi);
				movingPosList.add(ghmpi.pos());
				//highlightValidPlacements(ghmpi);
				if(netViewState == HIDE_NETS) ghmpi.showMyLines();
			}
		}
		mousePressed.emit();
	}

	@Override
	public void mouseReleaseEvent(QGraphicsSceneMouseEvent event) {
		if(validPlacements.size() > 0){
			for(ValidPlacementPolygon p : validPlacements){
				removeItem(p);
			}
		}
		validPlacements.clear();
		if (movingHMList != null && !movingHMList.isEmpty()
				&& event.button() == Qt.MouseButton.LeftButton) {
			if (!movingPosList.get(0).equals(movingHMList.get(0).pos())) {
				hmMoved.emit(movingHMList, movingPosList);
			}
			if(netViewState == HIDE_NETS){
				for(GUIModuleInst gmi : movingHMList){
					gmi.hideMyLines();
				}				
			}
		}
		super.mouseReleaseEvent(event);
	}

	public void highlightValidPlacements(GUIModuleInst ghmpi){
		QPolygonF shape = new QPolygonF(ghmpi.getShape());
		
		for(Site s : ghmpi.getModuleInst().getAllValidPlacements()){
			ValidPlacementPolygon item = new ValidPlacementPolygon(shape,ghmpi.getAnchorOffset());
			addItem(item);
		}
	}
	
	public QGraphicsRectItem highlightTile(int x, int y) {
		QColor color = new QColor(0, 255, 0, 190);
		int offset = (int) Math.ceil((lineWidth / 2.0));
		QGraphicsRectItem rect = addRect((tileSize + offset),
				(tileSize + offset), tileSize - 2 * offset, tileSize - 2
						* offset, new QPen(color), new QBrush(color));
		rect.setPos(x * tileSize, y * tileSize);
		return rect;
	}

	public QGraphicsRectItem highlightQuadTile(int x, int y) {
		QColor color = new QColor(0, 255, 0, 190);
		int offset = (int) Math.ceil((lineWidth / 2.0));
		QGraphicsRectItem rect = addRect((tileSize + offset),
				(tileSize + offset), tileSize - 2 * offset, 4 * tileSize - 2
						* offset, new QPen(color), new QBrush(color));
		rect.setPos(x * tileSize, y * tileSize);
		return rect;
	}

	public void openNewDesign(Design design) {
		setDesign(design);
		initializeScene();
		openHMDesign();
	}

	public void openHMDesign() {
		HashMap<String, ModuleInst> modInstances = getDesign().getModuleInstMap();
		HashSet<Net> netsFound = new HashSet<Net>();
		// iterate through ModuleInsts
		for (String key : modInstances.keySet()){
			ModuleInst modInst = modInstances.get(key);
			if(modInst.getInsts().size() == 0){
				continue;
			}
			GUIModuleInst ghmpi = new GUIModuleInst(modInst, this, true);
			addItem(ghmpi);
			polyList.add(ghmpi);
			macroMap.put(ghmpi.getModuleInst().getName(), ghmpi);
		}
		
		
		for (String key : modInstances.keySet()){
			ArrayList<SiteInst> instList = modInstances.get(key).getInsts();
			if(instList.size() == 0) continue;
			SiteInst inst0 = instList.get(0);
			String moduleName = inst0.getModuleInstName();
			Module module = inst0.getModuleTemplate();

			for (Port port : module.getPorts()) {
				if (port.getSitePinInstName().toUpperCase().contains("CLK")	|| port.getSitePinInstName().toUpperCase().contains("RST"))
					continue;
				String instName = moduleName + "/" + port.getSiteInstName();
				SiteInst portInst = getDesign().getSiteInst(instName);
				if(portInst == null) continue;
				for (Net portInstNet : portInst.getNetList()) {
					if (!portInstNet.isStaticNet()
							&& portInstNet.getModuleInst() == null
							&& !portInstNet.getName().contains("clk")
							&& !netsFound.contains(portInstNet)
							&& !portInstNet.getName().contains("rst")) {
						netsFound.add(portInstNet);
						addNetToScene(portInstNet);
					}
				}
			}
		}
		if(netsFound.size() < 2){
			nextNet: for(Net n : getDesign().getNets()){
				if(n.isClockNet()) continue;
				if(n.isStaticNet()) continue;
				String modInstName = null;
				for(SitePinInst p : n.getPins()){
					String curr = p.getModuleInstName();
					if(modInstName != null && curr != null && !modInstName.equals(curr)){
						netsFound.add(n);
						addNetToScene(n);
						continue nextNet;
					}
					if(curr != null) modInstName = curr;
				}
			}			
		}
		if(debugPlacer){
			Collection<SiteInst> insts = getDesign().getSiteInsts();
			for(SiteInst inst : insts){
				if(inst.getModuleTemplate() == null && inst.isPlaced()){
					Tile t = inst.getTile();
					HMTile myTile = new HMTile(t, this, null);
					myTile.setBrush(new QBrush(new QColor(255,125,0,125)));
					myTile.moveBy(getDrawnTileX(t) * tileSize, getDrawnTileY(t) * tileSize);
					addItem(myTile);
				}
			}			
		}
	}

	private void addNetToScene(Net net) {
		if(net.isClockNet()) return;
		String srcMIName = null;
		Tile srcTile = null;
		ArrayList<String> destMINameList = new ArrayList<String>();
		ArrayList<Tile> destTileList = new ArrayList<Tile>();
		for (SitePinInst sitePinInst : net.getPins()) {
			SiteInst pinInst = sitePinInst.getSiteInst();
			String pinMIName = pinInst.getModuleInstName();
			
			
			if(pinMIName == null && pinInst.isPlaced()){
				if(debugPlacer)
					pinMIName = "NOMODULE";
				else
					pinMIName = pinInst.getName()+"_HMTILE";
			}
			if (pinMIName != null) {					
				if (sitePinInst.isOutPin()) {
					// outpin
					srcMIName = pinMIName;
					srcTile = (pinInst.isPlaced())? pinInst.getTile() : pinInst.getModuleTemplateInst().getTile();
				} else {
					// inpins
					destMINameList.add(pinMIName);
					destTileList.add((pinInst.isPlaced())? pinInst.getTile() : pinInst.getModuleTemplateInst().getTile());
				}
			}
			
		}
		if (srcMIName != null) {
			//for (String destKey : destMINameList) {
			for(int i=0;i<destMINameList.size();i++){
				String destMIName = destMINameList.get(i);
				Tile destTile = destTileList.get(i);
				//Non-module-to-module connections
				if(debugPlacer){
					if(srcMIName.equals("NOMODULE") || destMIName.equals("NOMODULE")){
					
						int srcX = getDrawnTileX(srcTile);
						if(srcX < 0) 
							srcX = (srcTile.getColumn() >= cols)? (cols-1)*tileSize : srcTile.getColumn()*tileSize;
						int srcY = getDrawnTileY(srcTile);
						if(srcY < 0) 
							srcY = (srcTile.getRow() >= rows)? (rows-1)*tileSize : srcTile.getRow()*tileSize;
						int destX = getDrawnTileX(destTile);
						if(destX < 0) 
							destX = (destTile.getColumn() >= cols)? (cols-1)*tileSize : destTile.getColumn()*tileSize;
						int destY = getDrawnTileY(destTile);
						if(destY < 0) 
							destY = (destTile.getRow() >= rows)? (rows-1)*tileSize : destTile.getRow()*tileSize;
						QGraphicsLineItem line = new QGraphicsLineItem(10+srcX, 10+srcY, 10+destX, 10+destY);
						line.setPen(new QPen(QColor.cyan, 2));
						addItem(line);
						continue;
					}
				}
				//Module-to-module + Module-to-IOB connections
				QGraphicsItemInterface gmiSrc = getGMI(srcMIName);
				//for IOB connections, create immovable HMTile for net connection
				if(gmiSrc == null){
					HMTile hmTile = new HMTile(srcTile, this, null);
					hmTile.moveBy(getDrawnTileX(srcTile) * this.tileSize, getDrawnTileY(srcTile) * this.tileSize);
					gmiSrc = hmTile;
				}
				QGraphicsItemInterface gmiDest = getGMI(destMIName);
				if(gmiDest == null){
					HMTile hmTile = new HMTile(destTile, this, null);
					hmTile.moveBy(getDrawnTileX(destTile) * this.tileSize, getDrawnTileY(destTile) * this.tileSize);
					gmiDest = hmTile;
				}
				String key = srcMIName + destMIName;
				GUIMultiNetLine line = multiNetLineMap.get(key);
				if (line != null) {
					line.addNet();
				} else {
					line = new GUIMultiNetLine(gmiSrc,gmiDest);
					multiNetLineMap.put(key, line);
					addItem(line);
					if(gmiSrc instanceof GUIModuleInst) ((GUIModuleInst) gmiSrc).addLine(line);
					if(gmiDest instanceof GUIModuleInst) ((GUIModuleInst) gmiDest).addLine(line);
				}
				//Single nets (All nets(not clk/rst)
				/*HMTile tileSrc = ((GuiModuleInst) gmiSrc).getHMTile(srcTile);
				HMTile tileDest = ((GuiModuleInst) gmiDest).getHMTile(destTileList.get(i));
				GuiNetLine singleLine = new GuiNetLine(tileSrc, tileDest);
				netLineList.add(singleLine);
				addItem(singleLine);
				singleLine.hide();*/
			}
		}
	}

	public GUIModuleInst getGMI(String name) {
		return macroMap.get(name);
	}
	
	public void changeNetView(int index){
		netViewState = index;
		switch (index) {
		case HIDE_NETS://Nets hidden
			for(String key : multiNetLineMap.keySet()){
				GUIMultiNetLine line = multiNetLineMap.get(key);
				line.hide();
			}
			for(GUINetLine line : netLineList)
				line.hide();
			break;
		case MODULE_TO_MODULE://Module-to-module
			for(String key : multiNetLineMap.keySet()){
				GUIMultiNetLine line = multiNetLineMap.get(key);
				line.show();
			}
			for(GUINetLine line : netLineList)
				line.hide();
			break;
		case 2://All nets(not clk/rst)
			for(String key : multiNetLineMap.keySet()){
				GUIMultiNetLine line = multiNetLineMap.get(key);
				line.hide();
			}
			for(GUINetLine line : netLineList)
				line.show();
			break;
		default:
			System.out.println("FloorPlanScene::changeNetView(int) - Whoa!...was not expecting index = "+index);
			break;
		}
	}

	public ArrayList<GUINetLine> getNetLineList() {
		return netLineList;
	}

	public ArrayList<GUIModuleInst>	getMacroList(){
		return polyList;
	}
}
