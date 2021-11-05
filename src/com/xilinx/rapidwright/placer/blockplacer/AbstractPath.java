/*
 * Copyright (c) 2021 Xilinx, Inc.
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.AbstractModuleInst;

/**
 * A connection (net) between ports of some kind of module instances
 * @param <PortT> Port Type
 * @param <ModuleInstT> Module Instance Type
 */
public abstract class AbstractPath<PortT, ModuleInstT extends AbstractModuleInst<?,?>> implements Iterable<PortT> {

    protected List<PortT> ports = new ArrayList<>();
    protected Set<ModuleInstT> moduleInsts = new HashSet<>();

    /**
     *
     */
    private static final long serialVersionUID = 4016705713685431809L;


    public abstract int getLength();

    public int getSize(){
        return ports.size();
    }


    @Override
    public Iterator<PortT> iterator() {
        return ports.iterator();
    }

    public abstract void calculateLength();

    public abstract String getName();

    public boolean connectsTo(ModuleInstT hm) {
        return moduleInsts.contains(hm);
    }

    public int countConnectedModules() {
        return moduleInsts.size();
    }
}
