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

package com.xilinx.rapidwright.design.xdc;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.Pair;

public class XDCConstraints {
    private Map<String, PackagePinConstraint> pinConstraints = new HashMap<>();
    private Map<String, ClockConstraint> clockConstraints = new HashMap<>();
    private Map<String, Map<String, String>> cellProperties = new HashMap<>();
    private List<List<UnsupportedConstraintElement>> unsupportedConstraints = new ArrayList<>();


    public XDCConstraints(Map<String, PackagePinConstraint> pinConstraints,
                          Map<String, ClockConstraint> clockConstraints,
                          Map<String, Map<String, String>> cellProperties,
                          List<List<UnsupportedConstraintElement>> unsupportedConstraints) {
        this.pinConstraints = pinConstraints;
        this.clockConstraints = clockConstraints;
        this.cellProperties = cellProperties;
        this.unsupportedConstraints = unsupportedConstraints;
    }

    private static <T extends Constraint<T>> Map<String,T> cloneMap(Map<String,T> map) {
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->e.getValue().clone()));
    }
    private static Map<String,Map<String, String>> cloneStringMap(Map<String,Map<String, String>> map) {
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->new HashMap<>(e.getValue())));
    }

    private static List<List<UnsupportedConstraintElement>> cloneList(List<List<UnsupportedConstraintElement>> list) {
        return list.stream().map(ArrayList::new).collect(Collectors.toList());
    }
    public XDCConstraints clone() {
        return new XDCConstraints(
                cloneMap(pinConstraints),
                cloneMap(clockConstraints),
                cloneStringMap(cellProperties),
                cloneList(unsupportedConstraints)
        );
    }

    public XDCConstraints() {
    }

    public Map<String, PackagePinConstraint> getPinConstraints() {
        return pinConstraints;
    }

    public Map<String, ClockConstraint> getClockConstraints() {
        return clockConstraints;
    }

    public Map<String, Map<String, String>> getCellProperties() {
        return cellProperties;
    }

    public List<List<UnsupportedConstraintElement>> getUnsupportedConstraints() {
        return unsupportedConstraints;
    }

    private static Stream<String> cellPropsToXdc(int counter, String cell, Map<String, String> properties) {
        String varName = "rw_getcell_"+counter;
        String initVarLine = "set "+varName+  " [get_cells {" + cell + "}]";
        return Stream.concat(
                Stream.of(initVarLine),
                properties.entrySet().stream().map(propToValue->
                                "set_property " + propToValue.getKey() + " " + propToValue.getValue() + " $"+varName
                        )
        );
    }

    public Stream<String> getAllAsXdc() {
        Stream<String> clocks = clockConstraints.values().stream().map(ClockConstraint::asXdc);
        Stream<String> unsupported = unsupportedConstraints.stream().map(e->UnsupportedConstraintElement.toXdc(e.stream()));

        AtomicInteger varCounter = new AtomicInteger();
        Stream<String> cellProps = cellProperties.entrySet().stream().flatMap(
                cellToProps -> cellPropsToXdc(varCounter.getAndIncrement(), cellToProps.getKey(), cellToProps.getValue()));
        Stream<String> pinConstrs = pinConstraints.values().stream().flatMap(PackagePinConstraint::asXdc);


        return Stream.of(clocks, unsupported, cellProps, pinConstrs).flatMap(e->e);
    }

    public void writeToFile(Path file) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
            getAllAsXdc().forEach(pw::println);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<UnsupportedConstraintElement> rewriteUnsupported(List<UnsupportedConstraintElement> list, UnaryOperator<String> cellNameMapper) {
        return list.stream().map(elem -> {
            if (!(elem instanceof UnsupportedConstraintElement.CellConstraintElement)) {
                return elem;
            }
            UnsupportedConstraintElement.CellConstraintElement cellElem = (UnsupportedConstraintElement.CellConstraintElement) elem;
            return new UnsupportedConstraintElement.CellConstraintElement(cellNameMapper.apply(cellElem.getCellName()));
        }).collect(Collectors.toList());
    }

    public XDCConstraints duplicateWithReplacedCellNames(UnaryOperator<String> cellNameMapper) {
        Map<String, Map<String, String>> rewrittenProperties = cellProperties.entrySet().stream()
                .flatMap(e -> e.getValue().entrySet().stream().map(e2 -> new Pair<>(e.getKey(), e2)))
                .collect(Collectors.groupingBy(
                        e -> cellNameMapper.apply(e.getFirst()), Collectors.toMap(
                                e->e.getSecond().getKey(),
                                e->e.getSecond().getValue(),
                                (a,b) -> {
                                    if (!a.equals(b)) {
                                        throw new IllegalStateException("Cannot merge values "+a+" and "+b);
                                    }
                                    return a;
                                }
                        )
                ));
        List<List<UnsupportedConstraintElement>> rewrittenUnsupported = unsupportedConstraints.stream()
                .map(l->rewriteUnsupported(l, cellNameMapper)).collect(Collectors.toList());
        return new XDCConstraints(
                pinConstraints,
                clockConstraints,
                rewrittenProperties,
                rewrittenUnsupported
        );
    }

    public boolean isCellReferencedInConstraints(String name) {
        return unsupportedConstraints.stream().anyMatch(elements -> elements.stream().anyMatch(elem -> elem.referencesCell(name)));
    }
}
