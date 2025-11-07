/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc.parser;

import java.util.stream.Stream;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;

/**
 * Minimal cell lookup that does as much as it can without a netlist present.
 */
public class NoNetlistLookup  extends EdifCellLookup<String>{

    @Override
    public Stream<String> getHierCellInstsFromWildcardName(String cellName) {
        return Stream.of(cellName);
    }

    @Override
    public String getInstFromOriginalName(String cellName) {
        return cellName;
    }

    @Override
    public Stream<? extends String> getChildrenOf(String f) {
        throw new RuntimeException("not supported");
    }

    @Override
    public String getChild(String cell, String name) {
        if (cell.isEmpty()) {
            return name;
        }
        return cell+"/"+name;
    }

    @Override
    public EDIFHierCellInst toEdifHierCellInst(String cell) {
        throw new RuntimeException("not supported");
    }

    @Override
    public String getRoot() {
        return "";
    }

    @Override
    public String getAbsoluteFinalName(String current) {
        return current;
    }

    @Override
    public String getRelativeFinalName(String current) {
        return getRelativeOriginalName(current);
    }

    @Override
    public String getAbsoluteOriginalName(String current) {
        return current;
    }

    @Override
    public String getRelativeOriginalName(String current) {
        String[] split = current.split("/");
        return split[split.length-1];
    }

    @Override
    public String getCellType(String current) {
        throw new RuntimeException("not supported");
    }

    @Override
    public Class<String> getCellClass() {
        return String.class;
    }

    @Override
    public String getInstFromFinalName(String s) {
        return s;
    }
}
