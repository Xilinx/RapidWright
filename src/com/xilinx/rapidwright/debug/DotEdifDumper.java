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

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.Pair;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Dump an EDIF cell's contents to a Graphviz Dot Graph
 */
public class DotEdifDumper extends DotGraphDumper<EDIFCellInst, EDIFPortInst, Pair<EDIFPort, Integer>, EDIFNet, EDIFCell> {
    public DotEdifDumper() {
        super(true);
    }

    public DotEdifDumper(boolean makeNetNode) {
        super(makeNetNode);
    }

    @Override
    protected Stream<EDIFCellInst> getInstances(EDIFCell top) {
        return top.getCellInsts().stream();
    }

    @Override
    protected Stream<EDIFPortInst> getPorts(EDIFCellInst edifCellInst) {
        return edifCellInst.getPortInsts().stream();
    }

    @Override
    protected Stream<Pair<EDIFPort,Integer>> getPortTemplates(EDIFCellInst edifCellInst) {
        return edifCellInst.getCellType().getPorts().stream().flatMap(p-> {
            if (p.isBus()) {
                return Arrays.stream(p.getBitBlastedIndicies()).mapToObj(i -> new Pair<>(p, i));
            }
            return Stream.of(new Pair<>(p, 0));
        });
    }

    @Override
    protected Stream<EDIFNet> getNets(EDIFCell top) {
        return top.getNets().stream();
    }

    @Override
    protected Stream<EDIFPortInst> getNetPorts(EDIFNet edifNet) {
        return edifNet.getPortInsts().stream();
    }

    @Override
    protected boolean isOutputPort(EDIFPortInst edifPortInst) {
        return edifPortInst.isOutput() ^ (edifPortInst.getCellInst() == null);
    }

    @Override
    protected boolean isOutputPortTemplate(Pair<EDIFPort,Integer> port) {
        return port.getFirst().isOutput();
    }

    @Override
    protected String getInstanceName(EDIFCellInst edifCellInst) {
        return edifCellInst.getName()+" ("+edifCellInst.getCellType()+")";
    }

    @Override
    protected String getPortName(EDIFPortInst edifPortInst) {
        return edifPortInst.getName();
    }

    @Override
    protected String getPortTemplateName(Pair<EDIFPort,Integer> port) {
        if (port.getFirst().getWidth()==1) {
            return port.getFirst().getName();
        }
        return port.getFirst().getBusName()+"["+port.getSecond()+"]";
    }

    @Override
    protected Stream<EDIFPortInst> getRootPorts(EDIFCell top) {
        return top.getNets().stream().flatMap(n->n.getPortInsts().stream())
                .filter(p->p.getCellInst() == null).distinct();
    }

    @Override
    protected String getNetName(EDIFNet edifNet) {
        return edifNet.getName();
    }

    @Override
    protected Map<?, ?> getInstanceProperties(EDIFCellInst edifCellInst, EDIFCell top) {
        return edifCellInst.getProperties();
    }


    /**
     * Dump an edif cell to a file while filtering the cellInsts that are shown
     * @param to the target file
     * @param cell the cell to dump
     * @param filter A function that filters the instances that are shown
     */
    public static void dump(Path to, EDIFCell cell, BiPredicate<EDIFCellInst, EDIFCell> filter) {
        new DotEdifDumper().doDump(cell, to, filter);
    }

    /**
     * Dump an edif cell to a file while filtering the cellInsts that are shown
     * @param to the target file
     * @param cell the cell to dump
     * @param filter A function that filters the instances that are shown
     */
    public static void dump(Path to, EDIFCell cell, Predicate<EDIFCellInst> filter) {
        dump(to, cell, (i, d)->filter.test(i));
    }

    /**
     * Dump an edif cell to a file
     * @param to the target file
     * @param cell the cell to dump
     */
    public static void dump(Path to, EDIFCell cell) {
        dump(to, cell, (BiPredicate<EDIFCellInst, EDIFCell>) null);
    }

    @Override
    protected EDIFCellInst getPortInstance(EDIFPortInst port) {
        return port.getCellInst();
    }

    @Override
    protected EDIFNet getPortNet(EDIFPortInst p) {
        return p.getNet();
    }
}
