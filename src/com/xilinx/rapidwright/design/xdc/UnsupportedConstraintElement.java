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

package com.xilinx.rapidwright.design.xdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.xdc.parser.CellObject;
import com.xilinx.rapidwright.design.xdc.parser.DesignObject;
import com.xilinx.rapidwright.design.xdc.parser.EdifCellLookup;
import com.xilinx.rapidwright.design.xdc.parser.TclHashIdentifiedObject;
import org.apache.commons.io.FilenameUtils;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclList;
import tcl.lang.TclObject;

/**
 * Stringified representation of a (partial) constraint that our parser does not currently support.
 *
 * Subclasses specify what type of data a class instance contains
 */
public abstract class UnsupportedConstraintElement {
    public static String toXdc(Stream<UnsupportedConstraintElement> unsupportedConstraintElements) {
        return unsupportedConstraintElements.map(e->e.toXdc()).collect(Collectors.joining());
    }

    public static Stream<UnsupportedConstraintElement> wrapStream(Stream<UnsupportedConstraintElement> inner, Stream<UnsupportedConstraintElement> prefix, Stream<UnsupportedConstraintElement> suffix) {
        if (prefix !=null) {
            inner = Stream.concat(
                    prefix,
                    inner
            );
        }
        if (suffix != null) {
            inner = Stream.concat(
                    inner,
                    suffix
            );
        }
        return inner;

    }
    public static Stream<UnsupportedConstraintElement> wrapStream(Stream<UnsupportedConstraintElement> inner, String prefix, String suffix) {
        if (prefix !=null) {
            inner = Stream.concat(
                    Stream.of(new SyntaxConstraintElement(prefix)),
                    inner
            );
        }
        if (suffix != null) {
            inner = Stream.concat(
                    inner,
                    Stream.of(new SyntaxConstraintElement(suffix))
            );
        }
        return inner;
    }


    public static <T> Function<T, Stream<UnsupportedConstraintElement>> addSpacesBetween(Function<T, Stream<UnsupportedConstraintElement>> innerFunc) {
        final boolean[] first = {true};
        return e->{
            if (first[0]) {
                first[0] =false;
                return innerFunc.apply(e);
            }
            return Stream.concat(Stream.of(new SyntaxConstraintElement(" ")), innerFunc.apply(e));
        };
    }

    public static Function<UnsupportedConstraintElement, Stream<UnsupportedConstraintElement>> addSpacesBetween() {
        final boolean[] first = {true};
        return e->{
            if (first[0]) {
                first[0] =false;
                return Stream.of(e);
            }
            return Stream.of(new SyntaxConstraintElement(" "), e);
        };
    }

    public abstract String toXdc();

    public abstract boolean referencesCell(String name);


    /**
     * Unsupported Constraint Element that contains any text or command name except cells
     */
    public static class NameConstraintElement extends UnsupportedConstraintElement {
        private final String text;

        public NameConstraintElement(String text) {
            this.text = text;
        }

        @Override
        public String toXdc() {
            return text;
        }

        @Override
        public boolean referencesCell(String name) {
            return false;
        }

        @Override
        public String toString() {
            return "N<"+toXdc()+">";
        }
    }

    /**
     * Unsupported Constraint Element that contains TCL syntax
     */
    public static class SyntaxConstraintElement extends UnsupportedConstraintElement {
        private final String text;

        public SyntaxConstraintElement(String text) {
            this.text = text;
        }

        @Override
        public String toXdc() {
            return text;
        }

        @Override
        public boolean referencesCell(String name) {
            return false;
        }

        @Override
        public String toString() {
            return "S<"+toXdc()+">";
        }
    }

    /**
     * Unsupported Constraint Element that references a cell name
     */
    public static class CellConstraintElement extends UnsupportedConstraintElement{
        private final String cellName;

        public CellConstraintElement(String cellName) {
            this.cellName = cellName;
        }

        @Override
        public String toXdc() {
            return cellName;
        }

        @Override
        public boolean referencesCell(String name) {
            return cellName.equals(name);
        }

        public String getCellName() {
            return cellName;
        }

        @Override
        public String toString() {
            return "C<"+toXdc()+">";
        }
    }


    /**
     * Convert a TclObject into UnsupportedConstraintElements
     */
    public static <T> Stream<UnsupportedConstraintElement> objToUnsupportedConstraintElement(Interp interp, TclObject obj, EdifCellLookup<T> lookup, boolean replaceProbableCells, boolean applyWildcardsChooseAny) {
        try {
            Optional<DesignObject<?>> designObject = DesignObject.unwrapTclObject(interp, obj, lookup);
            if (designObject.isPresent()) {
                return designObject.get().toUnsupportedConstraintElement();
            }
            if (obj.getInternalRep() instanceof TclList) {
                TclObject[] elements = TclList.getElements(interp, obj);

                Stream<UnsupportedConstraintElement> inner = Arrays.stream(elements).flatMap(e -> objToUnsupportedConstraintElement(interp, e, lookup, false, applyWildcardsChooseAny));
                return wrapStream(inner, "{","}");
            }

            final boolean[] startsWithCell = {false};
            final T[] cell = (T[]) new Object[]{null};

            String s = obj.toString();
            List<UnsupportedConstraintElement> res = new ArrayList<>();
            TclHashIdentifiedObject.unpack(interp, s, partS -> {
                res.add(new UnsupportedConstraintElement.NameConstraintElement(partS));
            }, partObj -> {
                DesignObject<?> po = DesignObject.requireCastUnwrappedObject(partObj, lookup);
                if (po instanceof CellObject && cell[0]==null) {
                    List<T> cells = ((CellObject<T>) po).getCells();
                    if (cells.size()!=1) {
                        throw new RuntimeException("should have one cell??");
                    }
                    startsWithCell[0] = true;

                    T c = cells.iterator().next();
                    cell[0] = c;
                } else {
                    po.toUnsupportedConstraintElement().forEach(res::add);
                }
            });

            if (replaceProbableCells) {
                absorbIntoCells(lookup, res, (T) cell[0], applyWildcardsChooseAny);
            } else if (cell[0]!=null) {
                res.add(0, new UnsupportedConstraintElement.CellConstraintElement(lookup.getAbsoluteFinalName(cell[0])));
            }

            if (s.contains(" ")) {
                return wrapStream(res.stream(), "{", "}");
            }
            return res.stream();

        } catch (TclException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * If a cell name is followed by text that looks like a subcell reference, absorb that text into the cell name
     */
    private static <T> void absorbIntoCells(EdifCellLookup<T> lookup, List<UnsupportedConstraintElement> res, T cell, boolean applyWildcardsChooseAny) {
        if (res.size()!=1) {
            return;
        }
        String suffix = res.get(0).toXdc();
        if (cell != null) {
            if (!suffix.startsWith("/")) {
                //Suffix should always start with slash, something unsupported is happening

                //Currently unimplemented! The code is trying to access a sibling cell on the same hierarchy level.
                //Need to strip last level off cell, prepend suffix with its name
                throw new RuntimeException("Suffix should start with slash!");
            }
            suffix = suffix.substring(1);
        } else {
            cell = lookup.getRoot();
        }

        while (suffix!=null) {
            System.out.println("try to expand " + cell + " and " + suffix);
            int slashPos = suffix.indexOf("/");
            String currLevel, remaining;
            if (slashPos == -1) {
                currLevel = suffix;
                remaining = null;
            } else {
                currLevel = suffix.substring(0, slashPos);
                remaining = suffix.substring(slashPos+1);
            }
            System.out.println("decomposed into "+currLevel+" and "+remaining);
            T child = lookup.getChild(cell, currLevel);
            if (child == null) {
                if (applyWildcardsChooseAny) {
                    List<T> matches = lookup.getChildrenOf(cell)
                            .filter(x-> FilenameUtils.wildcardMatch(lookup.getRelativeOriginalName(x), currLevel))
                            .collect(Collectors.toList());
                    if (matches.isEmpty()) {
                        break;
                    }
                    child = matches.get(0);
                    if (matches.size() > 1) {
                        System.out.println("chose arbitrary wildcard match for "+currLevel+": "+child);
                    }
                } else {
                    break;
                }
            }
            if (child == null) {
                break;
            }
            cell = child;
            suffix = remaining;
        }

        if (cell==lookup.getRoot()) {
            return;
        }
        System.out.println("applying absorption: "+cell+" and "+suffix);

        res.clear();
        res.add(new UnsupportedConstraintElement.CellConstraintElement(lookup.getAbsoluteFinalName(cell)));
        if (suffix!=null) {
            res.add(new NameConstraintElement("/"+suffix));
        }

        System.out.println(res);

    }
}
