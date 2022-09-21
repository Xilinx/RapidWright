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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    List<PathT> paths = null;

    private int deltaCost;


    public Move2(BlockPlacer2<?, ModuleInstT, PlacementT, PathT> placer) {

        this.placer = placer;
    }

    public void undoMove() {
        for (int i = 0; i < placements.size(); i++) {
            placer.setTempAnchorSite(blocks.get(i), placements.get(i));
        }

        //Have we even changed the paths?
        if (paths != null) {
            for (PathT path : paths) {
                path.restoreUndo();
                if (BlockPlacer2.PARANOID) {
                    final int length = path.getLength();
                    path.calculateLength();
                    if (path.getLength() != length) {
                        throw new RuntimeException("Improper cost change.");
                    }
                }
            }
            paths = null;
        }

    }

    public void clear() {
        blocks.clear();
        placements.clear();
        blocksSet.clear();
    }

    public int getDeltaCost() {
        return deltaCost;
    }

    public boolean addBlock(ModuleInstT block, PlacementT placement) {
        if (!blocksSet.add(block)) {
            return false;
        }
        blocks.add(block);
        placements.add(placement);
        return true;
    }

    public void calcDeltaCost() {
        paths = new ArrayList<>();

        deltaCost = 0;
        int undoCount = placer.incUndoCount();
        for (ModuleInstT block : blocks) {
            for (PathT path : placer.getConnectedPaths(block)) {
                if (path.undoCount==undoCount) {
                    continue;
                }
                path.undoCount = undoCount;

                path.saveUndo();
                deltaCost -= path.getLength();
                path.calculateLength();
                deltaCost += path.getLength();
                paths.add(path);
            }

        }
    }

    public boolean addBlock(ModuleInstT block) {
        return addBlock(block, placer.getCurrentPlacement(block));
    }

    public void removeLastBlock() {
        placer.setTempAnchorSite(blocks.remove(blocks.size()-1), placements.remove(placements.size()-1));
    }

    @Override
    public String toString() {
        return IntStream.range(0, blocks.size())
                .mapToObj(i-> {
                    final ModuleInstT block = blocks.get(i);
                    final PlacementT from = placements.get(i);
                    final PlacementT to = placer.getCurrentPlacement(block);
                    return block.getName()+": "+from+"->"+to;
                }).collect(Collectors.joining(", ", "[","]"));
    }

    public int countBlocks() {
        return blocks.size();
    }
}
