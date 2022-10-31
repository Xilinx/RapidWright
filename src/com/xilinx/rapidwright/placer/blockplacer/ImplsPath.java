/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.ModuleImplsInst;
import com.xilinx.rapidwright.design.SimpleTileRectangle;

/**
 * Net between Ports of {@link ModuleImplsInst}s
 */
public class ImplsPath extends AbstractPath<ImplsInstancePort, ModuleImplsInst>{
    int length;
    int undoLength;

    SimpleTileRectangle current;
    SimpleTileRectangle undoCache;

    public ImplsPath(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public void addPort(ImplsInstancePort port) {
        ports.add(port);
        if (port instanceof ImplsInstancePort.InstPort) {
            moduleInsts.add(((ImplsInstancePort.InstPort) port).getInstance());
        }
        port.setPath(this);
    }


    final String name;


    @Override
    public int getLength() {
        return length;
    }

    public void calculateLength() {

        current = new SimpleTileRectangle();
        for (ImplsInstancePort port : ports) {
            port.enterToRect(current);
        }

        if (current.isEmpty()) {
            length = 0;
            return;
        }

        int fanOutPenalty = 1;
        if (getSize() > 30) {
            fanOutPenalty = 3;
        }

        length = current.hpwl() * fanOutPenalty * weight;
    }

    public String getName() {
        return name;
    }

    @Override
    public Set<?> getPathConnections() {
        return ports.stream()
                .map(ImplsInstancePort::getAllTiles)
                .collect(Collectors.toSet());
    }

    public ImplsInstancePort findSource() {
        final List<ImplsInstancePort> sources = ports.stream().filter(ImplsInstancePort::isOutputPort).collect(Collectors.toList());
        if (sources.size()>1) {
            throw new IllegalStateException("Multiple sources at " + getName() + ": " + sources);
        } else if (sources.isEmpty()) {
            return null;
        }
        return sources.get(0);
    }

    @Override
    public void saveUndo() {
        undoCache = current;
        undoLength = length;
    }

    @Override
    public void restoreUndo() {
        if (undoCache == null) {
            throw new RuntimeException("No cached undo value present in "+getName());
        }

        current = undoCache;
        length = undoLength;

        undoCache = null;
    }
}
