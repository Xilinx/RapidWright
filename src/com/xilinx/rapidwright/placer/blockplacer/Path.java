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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.SimpleTileRectangle;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents a delay path between pre-implemented modules.
 * @author clavin
 *
 */
public class Path extends AbstractPath<PathPort, HardMacro>{
    private final String name;

    protected int hpwl;
    protected SimpleTileRectangle current;
    protected SimpleTileRectangle undoCache;
    protected int undoHpwl;

    protected ArrayList<Integer> delay;
    protected int maxDelay;

    public Path(String name) {
        this.name = name;
    }

    public Path() {
        this.name = null;
    }

    public int getLength() {
        return hpwl;
    }

    public int getHPWL() {
        return hpwl;
    }

    public ArrayList<Integer> getDelay() {
        return delay;
    }

    public int getMaxDelay() {
        return maxDelay;
    }


    public void setDelay(ArrayList<Integer> estimatedDelay) {
        delay = estimatedDelay;
    }

    public void setMaxDelay(int pathMaxDelay) {
        maxDelay = pathMaxDelay;
    }

    public void calculateLength() {
        calculateHPWL();
    }

    @Override
    public String getName() {
        return name;
    }

    private void rectToHpwl() {


        int fanOutPenalty  = 1;
        if (getSize() > 30) {
            fanOutPenalty = 3;
        }
        hpwl = current.hpwl()*fanOutPenalty*weight;
    }

    public void calculateHPWL() {

        current = new SimpleTileRectangle();
        for (PathPort port : ports) {
            current.extendTo(port.getPortTile());
        }
        rectToHpwl();
    }

    public void saveUndo() {
        undoCache = current;
        undoHpwl = hpwl;
    }


    public void restoreUndo() {

        if (undoCache == null) {
            throw new RuntimeException("No cached undo value present");
        }

        hpwl = undoHpwl;
        current = undoCache;
        undoCache = null;
    }

    /**
     * Adds a pin the to path.
     * @param p The pin to add
     * @param map Map of module instance to hard macros
     */
    public void addPin(SitePinInst p, Map<ModuleInst, HardMacro> map) {
        final HardMacro block = map.get(p.getSiteInst().getModuleInst());
        Tile tile = p.getTile();
        if (block != null) {
            tile = p.getSiteInst().getModuleTemplateInst().getTile();

            moduleInsts.add(block);
        }
        ports.add(new PathPort(p, block, tile));
    }

    public PathPort get(int index) {
        return ports.get(index);
    }

    public List<PathPort> getPorts() {
        return ports;
    }

    @Override
    public Set<?> getPathConnections() {
        return getPorts().stream().map(port -> port.getBlock() + "." + port.getTemplateTile()).collect(Collectors.toSet());
    }
}
