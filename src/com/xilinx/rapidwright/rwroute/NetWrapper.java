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

/**
 * A wrapper class of {@link Net} with additional information for the router.
 */
public class NetWrapper{
	/** A unique index for a NetWrapepr Object*/
	private int id;
	/** The associated {@link Net} Object */
	private Net net;
	/** A list of {@link Connection} Objects of the net */
	private List<Connection> connections;
	/** Geometric center coordinates */
	private float xCenter;
	private float yCenter;
	/** The half-perimeter wirelength */
	private short doubleHpwl;
	/** A flag to indicate if the source has been swapped */
	private boolean sourceChanged;
	
	public NetWrapper(int id, Net net){
		this.id = id;
		this.net = net;
		connections = new ArrayList<>();
		setSourceChanged(false);
	}
	
	public void computeHPWLAndCenterCoordinates(){
		short xMin = 1<<10;
		short xMax = 0;
		short yMin = 1<<10;
		short yMax = 0;
		float xSum = 0;
		float ySum = 0;		
		List<Short> xArray = new ArrayList<>();
		List<Short> yArray = new ArrayList<>();
		
		boolean sourceRnodeAdded = false;	
		for(Connection connection : connections) {
			if(connection.isDirect()) continue;
			short x = 0;
			short y = 0;
			if(!sourceRnodeAdded) {
				x = connection.getSourceRnode().getEndTileXCoordinate();
				y = connection.getSourceRnode().getEndTileYCoordinate();
				xArray.add(x);
				yArray.add(y);
				xSum += x;
				ySum += y;		
				sourceRnodeAdded = true;
			}	
			x = connection.getSinkRnode().getEndTileXCoordinate();
			y = connection.getSinkRnode().getEndTileYCoordinate();
			xArray.add(x);
			yArray.add(y);
			xSum += x;
			ySum += y;	
		}
		
		Collections.sort(xArray);
		Collections.sort(yArray);
		xMin = xArray.get(0);
		xMax = xArray.get(xArray.size() - 1);
		yMin = yArray.get(0);
		yMax = yArray.get(xArray.size() - 1);
		
		setDoubleHpwl((short) ((xMax - xMin + 1 + yMax - yMin + 1) * 2));
		setXCenter(xSum / xArray.size());
		setYCenter(ySum / yArray.size());
	}
	
	public Net getNet(){
		return net;
	}
	
	public int getId() {
		return id;
	}
	
	public void addConnection(Connection connection){
		connections.add(connection);	
	}
	
	public List<Connection> getConnections(){
		return connections;
	}

	public short getDoubleHpwl() {
		return doubleHpwl;
	}

	public void setDoubleHpwl(short hpwl) {
		doubleHpwl = hpwl;
	}

	public boolean isSourceChanged() {
		return sourceChanged;
	}

	public void setSourceChanged(boolean sourceChanged) {
		this.sourceChanged = sourceChanged;
	}

	public float getYCenter() {
		return yCenter;
	}

	public void setYCenter(float yCenter) {
		this.yCenter = yCenter;
	}

	public float getXCenter() {
		return xCenter;
	}

	public void setXCenter(float xCenter) {
		this.xCenter = xCenter;
	}
	
	@Override
	public int hashCode(){
		return id;
	}
	
}
