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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.xdc.UnsupportedConstraintElement;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

/**
 * This wraps an unsupported command's call to support passing it on
 */
public class UnsupportedCmdResult<T> extends DesignObject{
    private final List<UnsupportedConstraintElement> cmd;

    private final EdifCellLookup<T> lookup;

    public UnsupportedCmdResult(List<UnsupportedConstraintElement> cmd, EdifCellLookup<T> lookup) {
        this.cmd = cmd;
        this.lookup = lookup;
    }

    private static Function<UnsupportedConstraintElement, Stream<UnsupportedConstraintElement>> wrapDollarSigns() {
        final boolean[] inBraces = {false};
        return uce -> {
            if (uce instanceof UnsupportedConstraintElement.CellConstraintElement || uce instanceof UnsupportedConstraintElement.NameConstraintElement) {
                if (!inBraces[0] && uce.toXdc().contains("$")) {
                    inBraces[0] = true;
                    return Stream.of(new UnsupportedConstraintElement.SyntaxConstraintElement("{"), uce);
                }
                return Stream.of(uce);
            } else {
                String s = uce.toXdc();
                StringBuilder sb = new StringBuilder();
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '{':
                            inBraces[0] = true;
                            sb.append(c);
                            break;
                        case '}':
                            inBraces[0] = false;
                            sb.append(c);
                            break;
                        case ']':
                            if (inBraces[0]) {
                                sb.append('}');
                                inBraces[0] = false;
                            }
                            sb.append(c);
                            break;
                        default:
                            sb.append(c);
                            break;
                    }
                }
                return Stream.of(new UnsupportedConstraintElement.SyntaxConstraintElement(sb.toString()));
            }
        };
    }

    public UnsupportedCmdResult(Interp interp, TclObject[] argv, EdifCellLookup<T> lookup, boolean replaceProbableCells, boolean applyWildcardsChooseAny) {
        this.lookup = lookup;
        Stream<UnsupportedConstraintElement> objs = Arrays.stream(argv)
                .flatMap(UnsupportedConstraintElement.addSpacesBetween(
                        obj->UnsupportedConstraintElement.objToUnsupportedConstraintElement(interp, obj, lookup, replaceProbableCells, applyWildcardsChooseAny)
                ));
        this.cmd = UnsupportedConstraintElement.wrapStream(objs, "[", "]")
                .flatMap(wrapDollarSigns())
                .collect(Collectors.toList());
    }

    public static <T> TclObject makeTclObj(Interp interp, TclObject[] objv, EdifCellLookup<T> lookup, boolean replaceProbableCells, boolean applyWildcardsChooseAny) throws TclException {
        return new UnsupportedCmdResult<>(interp, objv, lookup, replaceProbableCells, applyWildcardsChooseAny).toReflectObj(interp);
    }

    @Override
    public String toString() {
        return toXdc();
    }

    @Override
    public String toXdc() {
        return UnsupportedConstraintElement.toXdc(cmd.stream());
    }

    @Override
    public Stream<UnsupportedConstraintElement> toUnsupportedConstraintElement() {
        return cmd.stream();
    }

    public TclObject toReflectObj(Interp interp) throws TclException {
        return TclHashIdentifiedObject.createReflectObject(interp, DesignObject.class, this);
    }

    public List<UnsupportedConstraintElement> getCmd() {
        return cmd;
    }

    private static boolean isSyntaxStr(UnsupportedConstraintElement elem, String s) {
        if (!(elem instanceof UnsupportedConstraintElement.SyntaxConstraintElement)) {
            return false;
        }
        return elem.toXdc().equals(s);
    }

    public UnsupportedCmdResult<T> withoutOutsideBrackets() {
        if (isSyntaxStr(cmd.get(0), "[") && isSyntaxStr(cmd.get(cmd.size()-1), "]")) {
            List<UnsupportedConstraintElement> newList = cmd.stream().skip(1).limit(cmd.size() - 2).collect(Collectors.toList());
            return new UnsupportedCmdResult<>(newList, lookup);
        }
        return new UnsupportedCmdResult<>(new ArrayList<>(cmd), lookup);
    }
}
