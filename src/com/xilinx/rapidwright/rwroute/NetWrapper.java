/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

/**
 * A wrapper class of Net with additional information for the router
 */
public class NetWrapper{
	/** A unique index for this NetWrapepr */
	private int id;
	/** The associated Net */
	private Net net;
	/** The list of connections of the net */
	private List<Connection> connections;
	/** Geometric center coordinates*/
	private float x_geo;
	private float y_geo;
	/** The half-perimeter wirelength*/
	private short hpwl;
	/** A flag to indicate if the source has been swapped*/
	private boolean sourceChanged;
	/** Stores the old source SitePinInst after output pin swapping */
	private SitePinInst oldSource;
	
	public NetWrapper(int id, short bbRange, Net net){
		this.id = id;
		this.net = net;
		this.connections = new ArrayList<>();
		this.setSourceChanged(false, null);
	}
	
	public void setBoundaryXYs(short bbRange){
		short x_min = 1<<10;
		short x_max = 0;
		short y_min = 1<<10;
		short y_max = 0;
		float x_geo_sum = 0;
		float y_geo_sum = 0;		
		List<Short> xArray = new ArrayList<>();
		List<Short> yArray = new ArrayList<>();
		
		boolean sourceRnodeAdded = false;	
		for(Connection c : this.connections) {
			if(c.isDirect()) continue;
			short x = 0;
			short y = 0;
			if(!sourceRnodeAdded) {
				x = c.getSourceRnode().getX();
				y = c.getSourceRnode().getY();
				xArray.add(x);
				yArray.add(y);
				x_geo_sum += x;
				y_geo_sum += y;		
				sourceRnodeAdded = true;
			}	
			x = c.getSinkRnode().getX();
			y = c.getSinkRnode().getY();
			xArray.add(x);
			yArray.add(y);
			x_geo_sum += x;
			y_geo_sum += y;	
		}
		
		Collections.sort(xArray);
		Collections.sort(yArray);
		x_min = xArray.get(0);
		x_max = xArray.get(xArray.size() - 1);
		y_min = yArray.get(0);
		y_max = yArray.get(xArray.size() - 1);
		
		this.setHpwl((short) ((x_max - x_min + 1) + (y_max - y_min + 1)));
		this.setX_geo(x_geo_sum / xArray.size());
		this.setY_geo(y_geo_sum / yArray.size());
	}
	
	public Net getNet(){
		return this.net;
	}
	
	public int getId() {
		return id;
	}
	
	public void addCons(Connection c){
		this.connections.add(c);
	}
	
	public List<Connection> getConnection(){
		return this.connections;
	}

	public short getHpwl() {
		return hpwl;
	}

	public void setHpwl(short hpwl) {
		this.hpwl = hpwl;
	}

	public boolean isSourceChanged() {
		return sourceChanged;
	}

	public void setSourceChanged(boolean sourceChanged, SitePinInst oldSource) {
		this.sourceChanged = sourceChanged;
		this.setOldSource(oldSource);
	}

	public float getY_geo() {
		return y_geo;
	}

	public void setY_geo(float y_geo) {
		this.y_geo = y_geo;
	}

	public float getX_geo() {
		return x_geo;
	}

	public void setX_geo(float x_geo) {
		this.x_geo = x_geo;
	}
	
	public SitePinInst getOldSource() {
		return oldSource;
	}

	public void setOldSource(SitePinInst oldSource) {
		this.oldSource = oldSource;
	}
	
	@Override
	public int hashCode(){
		return this.id;
	}
}
