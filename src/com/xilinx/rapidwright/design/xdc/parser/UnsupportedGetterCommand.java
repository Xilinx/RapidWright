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
import java.util.Objects;
import java.util.Optional;

import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.ReflectObject;
import tcl.lang.TclException;
import tcl.lang.TclList;
import tcl.lang.TclObject;
import tcl.lang.TclString;

/**
 * A setter command that is not supported in detail
 */
public class UnsupportedGetterCommand implements Command {
    protected final EdifCellLookup<?> lookup;
    protected final Command replacedCommand;

    public UnsupportedGetterCommand(EdifCellLookup<?> lookup, Command replacedCommand) {
        this.lookup = lookup;
        this.replacedCommand = replacedCommand;
    }
    public UnsupportedGetterCommand(EdifCellLookup<?> lookup) {
        this.lookup = lookup;
        this.replacedCommand = null;
    }

    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        if (replacedCommand!=null && Arrays.stream(objv).noneMatch(obj -> containsUnsupportedCmdResults(lookup, interp, obj, false))) {
            replacedCommand.cmdProc(interp, objv);
        } else {
            interp.setResult(UnsupportedCmdResult.makeTclObj(interp, objv, lookup, true, true));
        }
    }

    public static boolean containsUnsupportedCmdResults(EdifCellLookup<?> lookup, Interp interp, TclObject obj, boolean isInList) {
        try {
            if (obj.getInternalRep() instanceof TclList) {
                TclObject[] elements = TclList.getElements(interp, obj);
                return Arrays.stream(elements).anyMatch(elem -> containsUnsupportedCmdResults(lookup, interp, elem, true));
            } else if (obj.getInternalRep() instanceof ReflectObject) {
                Optional<?> designObject = DesignObject.unwrapTclObject(interp, obj, lookup);
                if (!designObject.isPresent()) {
                    return true;
                }
                return !isInList || !(designObject.get() instanceof CellObject<?>);
            } else if (obj.getInternalRep() instanceof TclString) {
                return TclHashIdentifiedObject.containsStringifiedObject(obj.toString());
            } else {
                return false;
            }
        } catch (TclException e) {
            throw new RuntimeException(e);
        }
    }

    public static void replaceInInterp(Interp interp, EdifCellLookup<?> lookup, String name) {
        Command replacedCommand = Objects.requireNonNull(interp.getCommand(name));
        interp.createCommand(name, new UnsupportedGetterCommand(lookup, replacedCommand));
    }
}
