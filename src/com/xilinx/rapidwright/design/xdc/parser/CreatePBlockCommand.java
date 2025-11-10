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

import java.util.Collections;

import com.xilinx.rapidwright.design.xdc.PBlockConstraint;
import com.xilinx.rapidwright.design.xdc.XDCConstraints;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class CreatePBlockCommand implements Command {
    private final XDCConstraints constraints;

    public CreatePBlockCommand(XDCConstraints constraints) {
        this.constraints = constraints;
    }
    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        if (objv.length!=2) {
            throw new RuntimeException("wrong argument count");
        }
        String pBlockName = objv[1].toString();
        if (constraints.getPBlockConstraints().containsKey(pBlockName)) {
            throw new RuntimeException("duplicate pblock name: "+pBlockName);
        }
        PBlockConstraint constraint = new PBlockConstraint();
        constraint.getPblock().setName(pBlockName);
        constraints.getPBlockConstraints().put(pBlockName, constraint);
        NameDesignObject<?> res = new NameDesignObject<>(ObjType.PBlock, Collections.singletonList(pBlockName));
        interp.setResult(TclHashIdentifiedObject.createReflectObject(interp, NameDesignObject.class, res));
    }
}
