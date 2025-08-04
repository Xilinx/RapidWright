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

package com.xilinx.rapidwright.ipi.xdcParserCommands;

import java.util.Arrays;

import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.ReflectObject;
import tcl.lang.TclException;
import tcl.lang.TclNumArgsException;
import tcl.lang.TclObject;

public class ObjectGetterCommand implements Command {

    private final boolean takesObjects;
    private final ObjType objType;

    public ObjectGetterCommand(boolean takesObjects, ObjType objType) {
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
            if (argv.length != 2) {
                throw new TclNumArgsException(interp, 2, argv, "");
            }

            res = new NameDesignObject(objType, Arrays.asList(argv[1].toString().split(" ")));
        }
        interp.setResult(ReflectObject.newInstance(interp, res.getClass(), res));
    }
}
