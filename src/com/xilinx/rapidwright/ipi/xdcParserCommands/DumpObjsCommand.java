/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

import java.util.Optional;

import com.xilinx.rapidwright.ipi.EdifCellLookup;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class DumpObjsCommand implements Command {
    private final EdifCellLookup<?> cellLookup;
    public DumpObjsCommand(EdifCellLookup<?> cellLookup) {
        this.cellLookup = cellLookup;
    }



    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        TclObject tclObject = objv[1];
        Optional<DesignObject<?>> obj = DesignObject.unwrapTclObject(interp, tclObject, cellLookup);
        if (obj.isPresent()) {
            System.out.println(obj.get());
        } else {
            System.out.println("no java obj: "+tclObject);
        }
    }
}
