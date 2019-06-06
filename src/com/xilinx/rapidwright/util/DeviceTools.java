/*
 * 
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
package com.xilinx.rapidwright.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.router.RouteNode;

/**
 * A collection of utility functions that operate on a device.
 * 
 * Created on: May 26, 2017
 */
public class DeviceTools {

	public static void printFanout(RouteNode start, int depth){
		start.setLevel(0);
		///Queue<RouteNode> q = new LinkedList<>();
		PriorityQueue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return j.getLevel() - i.getLevel();}});
		q.add(start);
		while(!q.isEmpty()){
			RouteNode curr = q.poll();
			if(curr.getLevel() > depth) continue;
			System.out.println(MessageGenerator.makeWhiteSpace(curr.getLevel()) + curr);
			for(Wire w : curr.getConnections()){
				RouteNode next = new RouteNode(w.getTile(),w.getWireIndex(),curr,curr.getLevel()+1);
				if(next.getConnections().isEmpty()) continue;
				next.setLevel(curr.getLevel()+1);
				q.add(next);
			}
		}
	}
	
	/**
	 * Creates a list of tiles within the rectangle created by the two provided tiles (inclusive).
	 * @param lowerLeft The lower left tile in the rectangle
	 * @param upperRight The upper right tile in the rectangle
	 * @return List of all tiles (inclusive) within the bounding rectangle defined by the two provided tiles.
	 */
	public static List<Tile> getAllTilesInRectangle(Tile lowerLeft, Tile upperRight){
		ArrayList<Tile> tiles = new ArrayList<>();
		for(int col = lowerLeft.getColumn(); col <= upperRight.getColumn(); col++){
			for(int row = upperRight.getRow(); row <= lowerLeft.getRow(); row++){
				tiles.add(lowerLeft.getDevice().getTile(row,col));
			}
		}
		return tiles;
	}
}
