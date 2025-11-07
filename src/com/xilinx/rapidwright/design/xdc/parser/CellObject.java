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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.xdc.UnsupportedConstraintElement;

/**
 * Tcl representation of a list of cells
 * @param <T> the cell representation
 */
public class CellObject<T> extends DesignObject<T> {
    private final List<T> cells;
    private final EdifCellLookup<? super T> cellLookup;

    public CellObject(List<T> cells, EdifCellLookup<? super T> cellLookup) {
        this.cells = cells;
        this.cellLookup = cellLookup;
    }

    public String toXdc() {
        if (cells.size()==1) {
            String onlyCell = cellLookup.getAbsoluteFinalName(cells.get(0));
            if (cells.size() == 1 && !onlyCell.contains("[")) {
                return "[get_cells " + onlyCell + "]";
            }
        }

        return "[get_cells {" + cells.stream().map(cellLookup::getAbsoluteFinalName).collect(Collectors.joining(" ")) + "}]";
    }

    @Override
    public Stream<UnsupportedConstraintElement> toUnsupportedConstraintElement() {
        if (cells.size()==1) {
            String onlyCell = cellLookup.getAbsoluteFinalName(cells.get(0));
            if (!onlyCell.contains("[")) {
                return Stream.of(
                        new UnsupportedConstraintElement.SyntaxConstraintElement("["),
                        new UnsupportedConstraintElement.NameConstraintElement("get_cells"),
                        new UnsupportedConstraintElement.SyntaxConstraintElement(" "),
                        new UnsupportedConstraintElement.CellConstraintElement(onlyCell),
                        new UnsupportedConstraintElement.SyntaxConstraintElement("]")
                );
            }
        }

        Stream<UnsupportedConstraintElement> cellStream = cells.stream()
                .map(cellLookup::getAbsoluteFinalName)
                .map(UnsupportedConstraintElement.CellConstraintElement::new)
                .flatMap(UnsupportedConstraintElement.addSpacesBetween());
        return UnsupportedConstraintElement.wrapStream(cellStream, Stream.of(
                new UnsupportedConstraintElement.SyntaxConstraintElement("["),
                new UnsupportedConstraintElement.NameConstraintElement("get_cells"),
                new UnsupportedConstraintElement.SyntaxConstraintElement(" "),
                new UnsupportedConstraintElement.SyntaxConstraintElement("{")
                ), Stream.of(

                new UnsupportedConstraintElement.SyntaxConstraintElement("}"),
                new UnsupportedConstraintElement.SyntaxConstraintElement("]")
        ));
    }

    public List<T> getCells() {
        return cells;
    }

    @Override
    public String toString() {
        return cells.toString();
    }
}
