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

import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.ReflectObject;
import tcl.lang.TclException;
import tcl.lang.TclList;
import tcl.lang.TclObject;

/**
 * Tcl command to dump the arguments' internal representation
 */
public class DebugDumpCommand implements Command {
    @Override
    public void cmdProc(Interp interp, TclObject[] tclObjects) throws TclException {
        debugDump(interp, tclObjects);
    }

    public static void debugDump(Interp interp, TclObject[] tclObjects) {
        for (int i = 0; i < tclObjects.length; i++) {
            System.out.print("index "+i+": ");
            debugDump(tclObjects[i], interp, "    ");
        }
    }

    public static void debugDump(TclObject tclObject, Interp interp, String indent) {
        if (tclObject.getInternalRep() instanceof TclList) {
            System.out.println("list");
            try {
                TclObject[] items = TclList.getElements(interp, tclObject);
                for (int i = 0; i < items.length; i++) {
                    System.out.print(indent+" index "+i+": ");
                    debugDump(items[i], interp,indent+"    ");
                }
            } catch (TclException e) {
                throw new RuntimeException(e);
            }
        } else if (tclObject.getInternalRep() instanceof ReflectObject) {
            try {
                Object refl = ReflectObject.get(interp, tclObject);
                System.out.println("reflect object "+tclObject+" "+refl);
            } catch (TclException e) {
                throw new RuntimeException(e);
            }
        } else {
            System.out.println(tclObject.getInternalRep().getClass().getName()+": "+tclObject);
        }
    }
}
