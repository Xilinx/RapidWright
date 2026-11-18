/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.ReflectObject;
import tcl.lang.TclException;
import tcl.lang.TclList;
import tcl.lang.TclNumArgsException;
import tcl.lang.TclObject;

/**
 * Tcl command to get something that will probably be passed onto a <code>set_property</code> call later
 */
public class ObjectGetterCommand implements Command {

    private final EdifCellLookup<?> lookup;
    private final boolean takesObjects;
    private final ObjType objType;

    public ObjectGetterCommand(EdifCellLookup<?> lookup, boolean takesObjects, ObjType objType) {
        this.lookup = lookup;
        this.takesObjects = takesObjects;

        this.objType = objType;
    }

    @Override
    public void cmdProc(Interp interp, TclObject[] argv) throws TclException {
        DesignObject res;
        if (!takesObjects) {
            if (argv.length != 1) {
                throw new TclNumArgsException(interp, 1, argv, "");
            }
            res = new NameDesignObject(objType, null);
        } else {
            boolean argCountOk = argv.length == 2 || (argv.length==3 && argv[1].toString().equals("-quiet"));
            if (!argCountOk || TclHashIdentifiedObject.containsStringifiedObject(argv)) {
                interp.setResult(UnsupportedCmdResult.makeTclObj(interp, argv, lookup, false, false));
                return;
            }

            TclObject objs = argv[argv.length-1];
            if (objs.getInternalRep() instanceof TclList) {
                TclObject[] elements = TclList.getElements(interp, objs);
                List<String> strings = Arrays.stream(elements).map(Object::toString).collect(Collectors.toList());
                res = new NameDesignObject(objType, strings);
            } else {
                res = new NameDesignObject(objType, Arrays.asList(argv[1].toString().split(" ")));
            }
        }
        interp.setResult(TclHashIdentifiedObject.createReflectObject(interp, res.getClass(), res));
    }
}
