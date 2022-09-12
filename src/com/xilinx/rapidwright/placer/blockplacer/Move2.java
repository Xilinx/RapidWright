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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.AbstractModuleInst;

/**
 * Represents a move within the {@link BlockPlacer}.
 * @author clavin
 *
 */
public class Move2<ModuleInstT extends AbstractModuleInst<?,?,?>, PlacementT, PathT extends AbstractPath<?, ModuleInstT>> {

	private final BlockPlacer2<?, ModuleInstT, PlacementT, PathT> placer;


	List<ModuleInstT> blocks = new ArrayList<>();
	Set<ModuleInstT> blocksSet = new HashSet<>();
	List<PlacementT> placements = new ArrayList<>();

	private int deltaCost;


	public Move2(BlockPlacer2<?, ModuleInstT, PlacementT, PathT> placer){

		this.placer = placer;
	}

	public void undoMove(){
		for (int i = 0; i < placements.size(); i++) {
			placer.setTempAnchorSite(blocks.get(i), placements.get(i));
		}

		int undoCount = placer.incUndoCount();

		for (int i = 0; i < blocks.size(); i++) {
			ModuleInstT block = blocks.get(i);
			for (PathT path : placer.getConnectedPaths(block)) {

				if (path.undoCount == undoCount) {
					continue;
				}
				path.undoCount = undoCount;

				path.restoreUndo();
				if (BlockPlacer2.PARANOID) {
					final int length = path.getLength();
					path.calculateLength();
					if (path.getLength() != length) {
						throw new RuntimeException("Improper cost change.");
					}
				}
			}
		}

	}

	public void clear() {
		blocks.clear();
		placements.clear();
		blocksSet.clear();
	}


	private int calcConnectedCost(boolean moved) {
		int cost = 0;
		int undoCount = placer.incUndoCount();
		for (ModuleInstT block : blocks) {
			for (PathT path : placer.getConnectedPaths(block)) {
				if (path.undoCount==undoCount) {
					continue;
				}
				path.undoCount = undoCount;
				if (moved) {
					path.saveUndo();
					path.calculateLength();
				}
				int length = path.getLength();
				cost+= length;
			}

		}
		return cost;
	}

	public int getDeltaCost() {
		return deltaCost;
	}

	public void addBlock(ModuleInstT block, PlacementT placement) {
		if (!blocksSet.add(block)) {
			throw new RuntimeException("tried to double move "+block);
		}
		blocks.add(block);
		placements.add(placement);
	}

	public void calcDeltaCost() {
		int costBefore = calcConnectedCost(false);
		int costAfter = calcConnectedCost(true);
		deltaCost = costAfter - costBefore;
	}
}
