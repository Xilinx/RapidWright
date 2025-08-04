/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel
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

package com.xilinx.rapidwright.ipi;

import java.util.stream.Stream;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

public class RegularEdifCellLookup extends EdifCellLookup<EDIFHierCellInst> {

    private final EDIFNetlist netlist;

    public RegularEdifCellLookup(EDIFNetlist netlist) {
        this.netlist = netlist;
    }

    @Override
    public Stream<? extends EDIFHierCellInst> getChildrenOf(EDIFHierCellInst f) {
        return f.getCellType().getCellInsts().stream().map(f::getChild);
    }

    @Override
    public EDIFHierCellInst getChild(EDIFHierCellInst cell, String name) {
        EDIFCellInst child = cell.getCellType().getCellInst(name);
        if (child==null) {
            return null;
        }
        return cell.getChild(child);
    }

    @Override
    public EDIFHierCellInst toEdifHierCellInst(EDIFHierCellInst cell) {
        return cell;
    }


    @Override
    public EDIFHierCellInst getRoot() {
        return netlist.getTopHierCellInst();
    }

    @Override
    public String getAbsoluteFinalName(EDIFHierCellInst current) {
        return current.getFullHierarchicalInstName();
    }

    @Override
    public String getRelativeFinalName(EDIFHierCellInst current) {
        return current.getInst().getName();
    }

    @Override
    public String getAbsoluteOriginalName(EDIFHierCellInst current) {
        return current.getFullHierarchicalInstName();
    }

    @Override
    public String getRelativeOriginalName(EDIFHierCellInst current) {
        return current.getInst().getName();
    }

    @Override
    public String getCellType(EDIFHierCellInst current) {
        EDIFPropertyValue prop = current.getCellType().getProperty("ORIG_REF_NAME");
        if (prop != null) {
            return prop.getValue();
        }
        return current.getCellType().getName();
    }

    @Override
    public Class<EDIFHierCellInst> getCellClass() {
        return EDIFHierCellInst.class;
    }

    @Override
    public EDIFHierCellInst getInstFromOriginalName(String cellName) {
        return netlist.getHierCellInstFromName(cellName);
    }

    @Override
    public EDIFHierCellInst getInstFromFinalName(String s) {
        return netlist.getHierCellInstFromName(s);
    }
}
