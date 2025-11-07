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

import com.xilinx.rapidwright.design.xdc.ClockConstraint;
import com.xilinx.rapidwright.design.xdc.XDCConstraints;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

/**
 * Tcl command: create_clock
 */
public class CreateClockCommand implements Command {

    private final XDCConstraints constraints;
    private final EdifCellLookup<?> cellLookup;

    public CreateClockCommand(XDCConstraints constraints, EdifCellLookup<?> cellLookup) {

        this.constraints = constraints;
        this.cellLookup = cellLookup;
    }

    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        List<String> ports = null;
        String period = null;
        String clockName = null;
        for (int i = 1; i < objv.length; i++) {
            if (objv[i].toString().startsWith("-")) {
                switch (objv[i].toString()) {
                    case "-period":
                        period = objv[++i].toString();
                        break;
                    case "-name":
                        clockName = objv[++i].toString();

                        break;
                    case "-waveform":
                        //Just skip the waveform specification
                        ++i;
                        break;
                    default:
                        throw new RuntimeException("expected -name | -period | -waveform but got " + objv[i]);
                }
            } else {
                DesignObject obj = DesignObject.requireUnwrapTclObject(interp, objv[i], cellLookup);
                if (!(obj instanceof NameDesignObject) || ((NameDesignObject) obj).getType() != ObjType.Port) {
                    throw new RuntimeException("expected port but got " + obj.toXdc());
                }
                ports = ((NameDesignObject) obj).getObjects();

                if (i + 1 != objv.length) {
                    throw new RuntimeException("Extra elements after port name");
                }
            }
        }
        if (ports == null) {
            throw new RuntimeException("did not have ports!");
        }
        for (String port : ports) {
            constraints.getClockConstraints().put(port, new ClockConstraint(clockName, Double.parseDouble(period), port));
        }

    }
}
