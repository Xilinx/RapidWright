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

import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.ipi.EdifCellLookup;
import com.xilinx.rapidwright.ipi.PackagePinConstraint;
import com.xilinx.rapidwright.ipi.UnsupportedConstraintElement;
import com.xilinx.rapidwright.ipi.XDCConstraints;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class SetPropertyCommand<T> implements Command {
    private final XDCConstraints constraints;
    private final Device dev;
    private final  EdifCellLookup<T> cellLookup;

    public SetPropertyCommand(XDCConstraints constraints, Device dev, EdifCellLookup<T> cellLookup) {
        this.constraints = constraints;
        this.dev = dev;
        this.cellLookup = cellLookup;
    }

    public void cmdProc(Interp interp, TclObject[] argv)
            throws TclException {
        String k = argv[1].toString();
        String v = argv[2].toString();
        DesignObject<?> obj = DesignObject.requireUnwrapTclObject(interp, argv[argv.length - 1], cellLookup);

        if (k.equals("-dict")) {
            String[] items = v.split(" ");
            if (items.length % 2 != 0) {
                throw new TclException(interp, "found odd number of arguments in kv-dict");
            }
            for (int i = 0; i < items.length; i += 2) {
                storeKV(items[i], items[i + 1], obj);
            }
        } else {
            storeKV(k, v, obj);
        }

        interp.setResult(0);
    }

    private void eachPin(NameDesignObject<?> pins, BiConsumer<PackagePinConstraint, String> setter, String value) {
        for (String object : pins.getObjects()) {
            PackagePinConstraint ppc = constraints.getPinConstraints().computeIfAbsent(object, PackagePinConstraint::new);
            setter.accept(ppc, value);
        }
    }

    private void addUnsupportedKV(String k, String v, DesignObject<?> someObj) {
        Stream<UnsupportedConstraintElement> line = Stream.concat(
                Stream.of(
                        new UnsupportedConstraintElement.NameConstraintElement("set_property"),
                        new UnsupportedConstraintElement.SyntaxConstraintElement(" "),
                        new UnsupportedConstraintElement.NameConstraintElement(k),
                        new UnsupportedConstraintElement.SyntaxConstraintElement(" "),
                        new UnsupportedConstraintElement.NameConstraintElement(v),
                        new UnsupportedConstraintElement.SyntaxConstraintElement(" ")
                ),
                someObj.toUnsupportedConstraintElement()
        );
        List<UnsupportedConstraintElement> l = line.collect(Collectors.toList());
        constraints.getUnsupportedConstraints().add(l);
    }

    private void storeKV(String k, String v, DesignObject<?> someObj) {
        if (someObj instanceof CellObject) {
            for (T cell : ((CellObject<T>) someObj).getCells()) {
                constraints.getCellProperties().computeIfAbsent(cellLookup.getAbsoluteFinalName(cell), x -> new HashMap<>()).put(k, v);
            }
            return;
        }
        if (someObj instanceof UnsupportedCmdResult<?>) {
            addUnsupportedKV(k, v, someObj);
            return;
        }
        if (!(someObj instanceof NameDesignObject<?>)) {
            throw new RuntimeException("expected NameDesignObject but got "+someObj.getClass()+": "+someObj);
        }
        NameDesignObject<?> obj = (NameDesignObject<?>) someObj;
        switch (obj.getType()) {
            case Design:
                addUnsupportedKV(k, v, obj);
                break;
            case Cell:
                if (k.equals("ASYNC_REG")) {
                    addUnsupportedKV(k,v,obj);
                } else {
                    for (String object : obj.getObjects()) {
                        constraints.getCellProperties().computeIfAbsent(object, x -> new HashMap<>()).put(k, v);
                    }
                }
                break;
            case Port:
                switch (k) {
                    case "IOSTANDARD":
                        eachPin(obj, PackagePinConstraint::setIOStandard, v);
                        break;
                    case "PACKAGE_PIN":
                    case "LOC":
                        if (dev !=null && !dev.getActivePackage().getPackagePinMap().containsKey(v)) {
                            throw new RuntimeException("Invalid pin for " + obj + ": " + v);
                        }
                        eachPin(obj, PackagePinConstraint::setPackagePin, v);
                        break;
                    default:
                        addUnsupportedKV(k, v, obj);

                }
                break;
            default:
                throw new RuntimeException("Unexpected obj type");
        }
    }
}
