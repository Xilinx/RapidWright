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
package com.xilinx.rapidwright.design;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.placer.blockplacer.ImplsInstancePort;

/**
 * A module instance with flexible implementation. This allows us to dynamically swap implementations.
 *
 * When placing this, we don't just have to specify a location, but an index into the assigned {@link ModuleImpls}' list
 * of implementations as well.
 *
 * Before exporting designs containing instances of this class to a DCP, they have to be lowered to {@link ModuleInst}s.
 * This is achieved by calling {@link DesignTools#createModuleInstsFromModuleImplsInsts(Design, Collection, Collection)}
 *
 */
public class ModuleImplsInst extends AbstractModuleInst<ModuleImpls, ModuleImplsInst> {
    final ModuleImpls module;
    private ModulePlacement placement;

    private final Map<String, ImplsInstancePort.InstPort> ports;


    public ModuleImplsInst(String name, EDIFCellInst cellInst, ModuleImpls module) {
        super(name, cellInst);
        this.module = module;
        ports = module.get(0).getPorts().stream()
                .collect(Collectors.toMap(Port::getName, p->new ImplsInstancePort.InstPort(this, p.getName())));
    }

    public ModuleImplsInst(String name, ModuleImpls module) {
        this(name, null, module);
    }

    @Override
    public void unplace() {
        placement = null;
        boundingBox = null;
        for (ImplsInstancePort.InstPort port : ports.values()) {
            port.resetBoundingBox();
        }
    }

    public ModulePlacement getPlacement() {
        return placement;
    }

    public void place(ModulePlacement placement) {
        unplace();
        this.placement = placement;
    }

    @Override
    public ModuleImpls getModule() {
        return module;
    }

    public Module getCurrentModuleImplementation() {
        if (placement == null) {
            return null;
        }
        return module.get(getPlacement().implementationIndex);
    }

    public boolean overlaps(ModuleImplsInst other) {
        return getBoundingBox().overlaps(other.getBoundingBox());
    }


    RelocatableTileRectangle boundingBox = null;
    public RelocatableTileRectangle getBoundingBox() {
        if (boundingBox == null) {
            this.boundingBox = getCurrentModuleImplementation().getBoundingBox()
                    .getCorresponding(placement.placement.getTile(), getCurrentModuleImplementation().getAnchor().getTile());
        }
        return boundingBox;
    }

    public ImplsInstancePort getPort(String name) {
        final ImplsInstancePort.InstPort instPort = ports.get(name);
        if (instPort == null) {
            throw new RuntimeException("Invalid port for "+module+": "+name);
        }
        return instPort;

    }

    public Collection<ImplsInstancePort.InstPort> getPorts() {
        return ports.values();
    }
}

