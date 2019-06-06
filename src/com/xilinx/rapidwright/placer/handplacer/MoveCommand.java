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

import java.util.*;

import com.trolltech.qt.core.*;
import com.trolltech.qt.gui.*;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.gui.GUIModuleInst;

/**
 * @author marc
 * 
 */
class MoveCommand extends QUndoCommand {
	private List<GUIModuleInst> myGhmList;
	private List<QPointF> myPosList;
	private List<QPointF> newPosList;
	private Design design;
	private List<Site> prevAnchorSiteList;
	private List<Site> newAnchorSiteList;
	private int tileSize;
	private FloorPlanScene scene;

	public MoveCommand(List<GUIModuleInst> ghmList, List<QPointF> oldPosList,
			FloorPlanScene scene) {
		this.scene = scene;
		this.design = scene.getDesign();
		this.tileSize = scene.tileSize;
		
		
		newPosList = new ArrayList<QPointF>();
		myGhmList = new ArrayList<GUIModuleInst>(ghmList);
		for (GUIModuleInst ghm : ghmList) {
			newPosList.add(ghm.pos());
		}
		myPosList = new ArrayList<QPointF>(oldPosList);
		prevAnchorSiteList = new ArrayList<Site>();
		newAnchorSiteList = new ArrayList<Site>();
		updateDesign();

	}

	@Override
	public int id() {
		return 1;
	}

	@Override
	public void undo() {
		for (int i = 0; i < myGhmList.size(); i++) {
			myGhmList.get(i).setPos(myPosList.get(i));
			myGhmList.get(i).setSelected(false);
		}
		myGhmList.get(0).scene().update();
		undoDesign();
		setText(tr("Move " + createCommandString(myGhmList, myPosList)));
	}

	@Override
	public void redo() {
		for (int i = 0; i < myGhmList.size(); i++)
			myGhmList.get(i).setPos(newPosList.get(i));
		redoDesign();
		setText(tr("Move " + createCommandString(myGhmList, newPosList)));
	}

	

	@Override
	public boolean mergeWith(QUndoCommand other) {
		return false;
	}

	public static String createCommandString(List<GUIModuleInst> ghmList,
			List<QPointF> posList) {
		if (ghmList.size() == 1)
			return "Moved " + ghmList.get(0).getModuleInst().getName() + " from "
					+ posList.get(0);
		return "Moved " + ghmList.size() + " items from " + posList.get(0);
	}

	private void updateDesign() {
		Device device = design.getDevice();
		for (int i = 0; i < myGhmList.size(); i++) {
			GUIModuleInst ghm = myGhmList.get(i);
			prevAnchorSiteList.add(ghm.getModuleInst().getAnchor().getSite());
			// only update placement of hard macro if its validly placed (green)
			if (ghm.isValidlyPlaced()) {
				double tileXd = ((ghm.pos().x()+ghm.getAnchorOffset().x())/tileSize);
				double tileYd = ((ghm.pos().y()+ghm.getAnchorOffset().y())/tileSize);
				int tileX = (int)tileXd;
				int tileY = (int)tileYd;
				//Tile newAnchorTile = device.getTile(tileY, tileX);
				Tile newAnchorTile = scene.drawnTiles[tileY][tileX];
				Site newAnchorSite = ghm.getModuleInst().getModule().getAnchor().getSite().getCorrespondingSite(ghm.getModuleInst().getModule().getAnchor().getSiteTypeEnum(), newAnchorTile);
				newAnchorSiteList.add(newAnchorSite);
				ghm.getModuleInst().place(newAnchorSite);
			}else{
				newAnchorSiteList.add(null);
				ghm.getModuleInst().unplace();
			}
				
		}
	}
	
	private void undoDesign(){
		Device device = design.getDevice();
		for (int i = 0; i < myGhmList.size(); i++) {
			GUIModuleInst ghm = myGhmList.get(i);
			Site oldAnchorSite = prevAnchorSiteList.get(i);
			if(oldAnchorSite == null){
				ghm.getModuleInst().unplace();
			}else{
				ghm.getModuleInst().place(oldAnchorSite);
			}
		}
	}
	
	private void redoDesign() {
		Device device = design.getDevice();
		for (int i = 0; i < myGhmList.size(); i++) {
			GUIModuleInst ghm = myGhmList.get(i);
			Site newAnchorSite = newAnchorSiteList.get(i);
			if(newAnchorSite == null){
				ghm.getModuleInst().unplace();
			}else{
				ghm.getModuleInst().place(newAnchorSite);
			}
		}
	}
}