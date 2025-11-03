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

package com.xilinx.rapidwright.design.xdc.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.xdc.UnsupportedConstraintElement;
import tcl.lang.Interp;
import tcl.lang.ReflectObject;
import tcl.lang.TclException;
import tcl.lang.TclList;
import tcl.lang.TclObject;

/**
 * Base class for any object that will be referenced from TCL
 * @param <T> Representation of Cells
 */
public abstract class DesignObject<T> {
    /**
     * Cast an
     * @param obj
     * @param lookup
     * @return
     * @param <T>
     */
    public static <T> DesignObject<?> requireCastUnwrappedObject(Object obj, EdifCellLookup<T> lookup) {
        if (lookup != null && lookup.getCellClass().isInstance(obj)) {
            return new CellObject<T>(Collections.singletonList(lookup.castCellInst(obj)), lookup);
        }
        return (DesignObject<?>) obj;
    }

    /**
     * Try to convert a TclObject into the DesignObject it represents
     * @param interp the interpreter
     * @param obj the object to unwrap
     * @param lookup the cell lookup
     * @return the design object or an empty optional if it isn't one
     * @param <T> the lookup's cell representation
     * @throws TclException
     */
    public static <T> Optional<DesignObject<?>> unwrapTclObject(Interp interp, TclObject obj, EdifCellLookup<T> lookup) throws TclException {
        if (obj.getInternalRep() instanceof TclList) {
            TclObject[] elements = TclList.getElements(interp, obj);
            if (!Arrays.stream(elements).allMatch(e-> {
                try {
                    return e.getInternalRep() instanceof ReflectObject &&lookup.getCellClass().isInstance(ReflectObject.get(interp,e));
                } catch (TclException ex) {
                    throw new RuntimeException(ex);
                }
            })) {
                return Optional.empty();
            }
            List<T> cells = Arrays.stream(elements).map(x -> {
                try {
                    return lookup.castCellInst(ReflectObject.get(interp, x));
                } catch (TclException e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());
            return Optional.<DesignObject<?>>of(new CellObject<>(cells, lookup));
        }
        if (obj.getInternalRep() instanceof ReflectObject) {
            return Optional.of(requireCastUnwrappedObject(ReflectObject.get(interp, obj), lookup));
        }
        return Optional.empty();
    }

    private static String objDebugInfo(Interp interp, TclObject obj) {
        try {
            if (obj.getInternalRep() instanceof ReflectObject) {
                Object ref = ReflectObject.get(interp, obj);
                return " is reflect object type " + ref.getClass() + ": " + ref;
            } else if (obj.getInternalRep() instanceof TclList) {
                return " is list: ["+ Arrays.stream(TclList.getElements(interp, obj)).map(o->objDebugInfo(interp,o)).collect(Collectors.joining(", "))+"]";
            } else {
                return " internal rep: "+obj.getInternalRep().getClass();
            }
        } catch (TclException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert a TclObject into the DesignObject it represents or throw if it isn't one
     * @param interp the interpreter
     * @param obj the object to unwrap
     * @param lookup the cell lookup
     * @return the design object
     * @param <T> the lookup's cell representation
     * @throws TclException
     */
    public static <T> DesignObject<?> requireUnwrapTclObject(Interp interp, TclObject obj, EdifCellLookup<T> lookup) throws TclException {
        return unwrapTclObject(interp, obj, lookup)
                .orElseThrow(()-> {
                    String moreInfo = objDebugInfo(interp, obj);
                    return new IllegalArgumentException("expected DesignObject but got " + obj + moreInfo);
                });

    }

    public abstract String toXdc();

    public abstract Stream<UnsupportedConstraintElement> toUnsupportedConstraintElement();
}
