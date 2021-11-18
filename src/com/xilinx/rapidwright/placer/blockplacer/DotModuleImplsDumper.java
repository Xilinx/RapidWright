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

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.xilinx.rapidwright.debug.DotGraphDumper;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.ModuleImplsInst;


/**
 * Dump a Design's {@link ModuleImplsInst} representation to a Graphviz Dot Graph
 */
public class DotModuleImplsDumper extends DotGraphDumper<ModuleImplsInst, ImplsInstancePort, Void, ImplsPath, DotModuleImplsDumper.ModuleImplsDumpData> {
    public DotModuleImplsDumper(boolean makeNetNode) {
        super(makeNetNode);
    }

    @Override
    protected ImplsPath getPortNet(ImplsInstancePort p) {
        return p.getPath();
    }

    @Override
    protected Stream<ModuleImplsInst> getInstances(ModuleImplsDumpData design) {
        return design.modules.stream();
    }


    @Override
    protected Stream<ImplsInstancePort.InstPort> getPorts(ModuleImplsInst instance) {
        return instance.getPorts().stream();
    }

    @Override
    protected Stream<Void> getPortTemplates(ModuleImplsInst instance) {
        return Stream.empty();
    }

    @Override
    protected Stream<ImplsPath> getNets(ModuleImplsDumpData design) {
        return design.paths.stream();
    }

    @Override
    protected Stream<ImplsInstancePort> getNetPorts(ImplsPath net) {
        return net.ports.stream();
    }

    @Override
    protected boolean isOutputPort(ImplsInstancePort port) {
        return port.isOutputPort();
    }

    @Override
    protected boolean isOutputPortTemplate(Void port) {
        throw new UnsupportedOperationException("Should not be called as we have no templates");
    }

    @Override
    protected String getInstanceName(ModuleImplsInst instance) {
        return instance.getName();
    }

    @Override
    protected String getPortName(ImplsInstancePort port) {
        return port.getName();
    }

    @Override
    protected String getPortTemplateName(Void port) {
        throw new UnsupportedOperationException("Should not be called as we have no templates");
    }

    @Override
    protected Stream<ImplsInstancePort> getRootPorts(ModuleImplsDumpData design) {
        return design.paths.stream().flatMap(path->path.ports.stream()).filter(port->port instanceof ImplsInstancePort.SitePinInstPort);
    }

    @Override
    protected String getNetName(ImplsPath net) {
        return net.getName();
    }

    @Override
    protected Map<?, ?> getInstanceProperties(ModuleImplsInst instance, ModuleImplsDumpData design) {
        return null;
    }

    @Override
    protected ModuleImplsInst getPortInstance(ImplsInstancePort port) {
        if (port instanceof ImplsInstancePort.InstPort) {
            return ((ImplsInstancePort.InstPort) port).getInstance();
        }
        return null;
    }

    public static class ModuleImplsDumpData {
        final Design design;
        final Collection<ModuleImplsInst> modules;
        final Collection<ImplsPath> paths;
        final Map<ModuleImplsInst, Set<ImplsPath>> modulesToPaths;


        public ModuleImplsDumpData(Design design, Collection<ModuleImplsInst> modules, Collection<ImplsPath> paths, Map<ModuleImplsInst, Set<ImplsPath>> modulesToPaths) {
            this.design = design;
            this.modules = modules;
            this.paths = paths;
            this.modulesToPaths = modulesToPaths;
        }
    }
}
