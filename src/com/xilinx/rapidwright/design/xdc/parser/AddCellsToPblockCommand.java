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

import java.util.List;

import com.xilinx.rapidwright.design.xdc.PBlockConstraint;
import com.xilinx.rapidwright.design.xdc.UnsupportedConstraintElement;
import com.xilinx.rapidwright.design.xdc.XDCConstraints;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class AddCellsToPblockCommand<T> implements Command {

    private final XDCConstraints constraints;
    private final EdifCellLookup<T> cellLookup;

    public AddCellsToPblockCommand(XDCConstraints constraints, EdifCellLookup<T> cellLookup) {

        this.constraints = constraints;
        this.cellLookup = cellLookup;
    }
    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {

        if (objv.length!=3) {
            throw new RuntimeException("wrong argument count");
        }
        DesignObject<T> pblockDO = DesignObject.unwrapTclObject(interp, objv[1], cellLookup).orElseThrow(()->new RuntimeException("expected designObject"));
        if (! (pblockDO instanceof NameDesignObject<?>)) {
            throw new RuntimeException("wrong argument type: "+pblockDO.getClass());
        }
        NameDesignObject<?> pblock = (NameDesignObject<?>)pblockDO;
        if (pblock.getType()!=ObjType.PBlock) {
            throw new RuntimeException("wrong argument type: "+pblock.getType());
        }

        PBlockConstraint pBlockConstraint = constraints.getPBlockConstraints().get(pblock.getObjects().get(0));

        DesignObject<?> cellsDO = DesignObject.requireUnwrapTclObject(interp, objv[2], cellLookup);

        if (cellsDO instanceof UnsupportedCmdResult<?>) {

            List<UnsupportedConstraintElement> constraint = UnsupportedConstraintElement.commandToUnsupportedConstraints(interp, objv, cellLookup);
            constraints.getUnsupportedConstraints().add(constraint);

            interp.resetResult();
            return;
        } else if (cellsDO instanceof CellObject) {
            for (T cell : ((CellObject<T>) cellsDO).getCells()) {
                String finalCell = cellLookup.getAbsoluteFinalName(cell);
                pBlockConstraint.getCells().add(finalCell);
            }
            return;
        }

        if (!(cellsDO instanceof NameDesignObject<?>)) {
            throw new RuntimeException("expected NameDesignObject but got "+cellsDO.getClass()+": "+cellsDO);
        }
        NameDesignObject<?> cellsNDO = (NameDesignObject<?>) cellsDO;
        if (cellsNDO.getType()!=ObjType.Cell) {
            throw new RuntimeException("expected CellObject but got "+cellsNDO.getType());
        }
        for (String object : cellsNDO.getObjects()) {
            pBlockConstraint.getCells().add(object);
        }
    }
}
