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
package com.xilinx.rapidwright.debug;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dump a Design's physical representation to a Graphviz Dot Graph
 */
public class DotPhysicalDumper extends DotGraphDumper<SiteInst, SitePinInst, Void, Net, Design> {

    public DotPhysicalDumper() {
        super(true);
    }

    public DotPhysicalDumper(boolean makeNetNode) {
        super(makeNetNode);
    }

    @Override
    protected Stream<SiteInst> getInstances(Design design) {
        return design.getSiteInsts().stream();
    }

    @Override
    protected Stream<SitePinInst> getPorts(SiteInst siteInst) {
        return siteInst.getSitePinInsts().stream();
    }

    @Override
    protected Stream<Void> getPortTemplates(SiteInst siteInst) {
        return Stream.empty();
    }

    @Override
    protected Stream<Net> getNets(Design design) {
        return design.getNets().stream().filter(n->n.getPins().size()>0);
    }

    @Override
    protected Stream<SitePinInst> getNetPorts(Net net) {
        return net.getPins().stream();
    }

    @Override
    protected boolean isOutputPort(SitePinInst sitePinInst) {
        return sitePinInst.isOutPin();
    }

    @Override
    protected boolean isOutputPortTemplate(Void port) {
        throw new IllegalStateException("Does not have templates");
    }

    @Override
    protected String getInstanceName(SiteInst si) {
        return si.getName();
    }

    @Override
    protected String getPortName(SitePinInst sitePinInst) {
        return sitePinInst.getName();
    }

    @Override
    protected String getPortTemplateName(Void port) {
        throw new IllegalStateException("Does not have templates");
    }

    @Override
    protected Stream<SitePinInst> getRootPorts(Design design) {
        return Stream.empty();
    }

    @Override
    protected String getNetName(Net net) {
        return net.getName();
    }

    @Override
    protected Map<?, ?> getInstanceProperties(SiteInst siteInst, Design design) {
        return siteInst.getCells().stream().distinct().collect(Collectors.toMap(Function.identity(), x->""));
    }



    /**
     * Dump a physical netlist to a file while filtering the SiteInsts that are shown
     * @param to the target file
     * @param design the design to dump
     * @param filter A function that filters the instances that are shown
     */
    public static void dump(Path to, Design design, BiPredicate<SiteInst, Design> filter) {
        new DotPhysicalDumper().doDump(design, to, filter);
    }

    /**
     * Dump a physical netlist to a file while filtering the SiteInsts that are shown
     * @param to the target file
     * @param design the design to dump
     * @param filter A function that filters the instances that are shown
     */
    public static void dump(Path to, Design design,Predicate<SiteInst> filter) {
        dump(to, design, (i, d)->filter.test(i));
    }

    /**
     * Dump a physical netlist to a file
     * @param to the target file
     * @param design the design to dump
     */
    public static void dump(Path to, Design design) {
        dump(to, design, (BiPredicate<SiteInst, Design>) null);
    }

    @Override
    protected SiteInst getPortInstance(SitePinInst port) {
        return port.getSiteInst();
    }

    @Override
    protected Net getPortNet(SitePinInst p) {
        return p.getNet();
    }
}
