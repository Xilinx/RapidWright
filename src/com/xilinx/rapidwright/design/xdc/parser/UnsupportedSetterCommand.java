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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.xdc.UnsupportedConstraintElement;
import com.xilinx.rapidwright.design.xdc.XDCConstraints;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;
import tcl.lang.TclString;

/**
 * A setter command that is not supported in detail
 */
public class UnsupportedSetterCommand implements Command {

    private final XDCConstraints constraints;
    private final EdifCellLookup<?> cellLookup;

    public UnsupportedSetterCommand(XDCConstraints constraints, EdifCellLookup<?> cellLookup) {

        this.constraints = constraints;
        this.cellLookup = cellLookup;
    }

    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        List<UnsupportedConstraintElement> constraint = Arrays.stream(objv)
                .flatMap(UnsupportedConstraintElement.addSpacesBetween(obj -> UnsupportedConstraintElement.objToUnsupportedConstraintElement(interp, obj, cellLookup, false, false)))
                .collect(Collectors.toList());
        constraints.getUnsupportedConstraints().add(constraint);
    }

    private String toSource(Interp interp, TclObject o) {
        if (o.getInternalRep() instanceof TclString) {
            String s = o.toString();
            if (s.contains(" ")) {
                return '{' + s + '}';
            }
            return s;
        }
        try {
            DesignObject designObject = DesignObject.requireUnwrapTclObject(interp, o, cellLookup);
            return designObject.toXdc();
        } catch (TclException e) {
            throw new RuntimeException(e);
        }
    }
}
