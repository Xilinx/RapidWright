/*
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

package com.xilinx.rapidwright.design.xdc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.xdc.parser.AddCellsToPblockCommand;
import com.xilinx.rapidwright.design.xdc.parser.CreateClockCommand;
import com.xilinx.rapidwright.design.xdc.parser.CreatePBlockCommand;
import com.xilinx.rapidwright.design.xdc.parser.DebugDumpCommand;
import com.xilinx.rapidwright.design.xdc.parser.DumpObjsCommand;
import com.xilinx.rapidwright.design.xdc.parser.EdifCellLookup;
import com.xilinx.rapidwright.design.xdc.parser.GetCellsCommand;
import com.xilinx.rapidwright.design.xdc.parser.ObjType;
import com.xilinx.rapidwright.design.xdc.parser.ObjectGetterCommand;
import com.xilinx.rapidwright.design.xdc.parser.RegularEdifCellLookup;
import com.xilinx.rapidwright.design.xdc.parser.ResizePBlockCommand;
import com.xilinx.rapidwright.design.xdc.parser.SetPropertyCommand;
import com.xilinx.rapidwright.design.xdc.parser.UnsupportedGetterCommand;
import com.xilinx.rapidwright.design.xdc.parser.UnsupportedIfCommand;
import com.xilinx.rapidwright.design.xdc.parser.UnsupportedSetterCommand;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.FileTools;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.Namespace;
import tcl.lang.Resolver;
import tcl.lang.TCL;
import tcl.lang.TclException;
import tcl.lang.TclObject;
import tcl.lang.Var;
import tcl.lang.WrappedCommand;


/**
 * Parses an XDC file for a limited subset of constraint types. It uses a full Tcl interpreter, so
 * complex language constructs are possible.
 * <p />
 * If the Parser encounters unsupported commands or command options, the parsed TCL code is converted back to strings
 * and returned as UnsupportedConstraintElements.
 * <p />
 * The parser can match cell references in constraints to modified designs. Users can supply their own
 * {@link EdifCellLookup} to specify how to rewrite constraints.
 * <p />
 * For a regular, non-rewritten netlist, users should use {@link RegularEdifCellLookup}.
 * <p />
 * If no netlist is present, a <code>null</code> lookup will leave more complex <code>get_cells</code> calls unevaluated
 * as unsupported constraints.
 * <p />
 * Created on: Jul 27, 2015
 */
public class XDCParser {


    /**
     * Create a tcl interpreter with XDC parsing commands
     *
     * Cell references are intentionally never disposed
     * (see {@link com.xilinx.rapidwright.design.xdc.parser.TclHashIdentifiedObject}), so lifetime of this interpreter
     * should be limited.
     *
     * @param constraints Constraints object to output to
     * @param dev Device
     * @param cellLookup the cell lookup (see  {@link XDCParser class level documentation} for parameter details)
     * @return interpreter
     * @param <T> lookup's cell representation
     */
    public static <T> Interp makeTclInterp(XDCConstraints constraints, Device dev, EdifCellLookup<T> cellLookup) {
        Interp interp = new Interp();
        interp.createCommand("set_property", new SetPropertyCommand<>(constraints, dev, cellLookup));
        interp.createCommand("current_design", new ObjectGetterCommand(cellLookup, false,ObjType.Design));

        if (cellLookup!=null) {
            interp.createCommand("get_cells", new GetCellsCommand<>(cellLookup));
        } else {
            interp.createCommand("get_cells", new ObjectGetterCommand(cellLookup, true, ObjType.Cell));
        }
        interp.createCommand("get_ports", new ObjectGetterCommand(cellLookup, true, ObjType.Port));
        interp.createCommand("get_pins", new ObjectGetterCommand(cellLookup, true, ObjType.Pin));
        interp.createCommand("get_pblocks", new ObjectGetterCommand(cellLookup, true, ObjType.PBlock));
        interp.createCommand("create_clock", new CreateClockCommand(constraints, cellLookup));

        interp.createCommand("create_pblock", new CreatePBlockCommand(constraints));
        interp.createCommand("add_cells_to_pblock", new AddCellsToPblockCommand<>(constraints, cellLookup));
        interp.createCommand("resize_pblock", new ResizePBlockCommand(constraints, dev));

        interp.createCommand("dump_objs", new DumpObjsCommand(cellLookup));

        UnsupportedSetterCommand unsupportedSetterCommand = new UnsupportedSetterCommand(constraints, cellLookup, null);
        interp.createCommand("set_false_path", unsupportedSetterCommand);
        interp.createCommand("set_input_delay", unsupportedSetterCommand);
        interp.createCommand("set_output_delay", unsupportedSetterCommand);
        interp.createCommand("set_max_delay", unsupportedSetterCommand);
        interp.createCommand("set_bus_skew", unsupportedSetterCommand);
        interp.createCommand("current_instance", unsupportedSetterCommand);
        interp.createCommand("set_clock_groups", unsupportedSetterCommand);
        interp.createCommand("set_input_jitter", unsupportedSetterCommand);

        UnsupportedGetterCommand unsupportedGetterCommand = new UnsupportedGetterCommand(cellLookup);
        interp.createCommand("get_clocks", unsupportedGetterCommand);
        interp.createCommand("get_property", unsupportedGetterCommand);
        interp.createCommand("get_nets", unsupportedGetterCommand);
        interp.createCommand("all_fanout", unsupportedGetterCommand);
        interp.createCommand("filter", unsupportedGetterCommand);
        UnsupportedGetterCommand.replaceInInterp(interp, cellLookup, "llength");
        UnsupportedGetterCommand.replaceInInterp(interp, cellLookup,  "expr");

        UnsupportedIfCommand.replaceInInterp(interp, constraints, cellLookup);
        UnsupportedSetterCommand.replaceInInterp(interp, constraints, cellLookup, "foreach");

        interp.createCommand("debugDump", new DebugDumpCommand());

        //We need to allow [*] and bracketed numbers (e.h. [1] ) as suffix on quoted strings, so we need to hook into the command lookup
        //This actually mirrors Vivado's behaviour very closely! Just enter * on Vivado's tcl prompt to see
        interp.createCommand("wrapInput", new Command() {
            @Override
            public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
                interp.setResult("["+objv[0]+"]");
            }
        });
        try {
            WrappedCommand wrapInputCmd = Objects.requireNonNull(Namespace.findCommand(interp, "wrapInput", null, 0));
            interp.addInterpResolver("", new Resolver() {
                @Override
                public WrappedCommand resolveCmd(Interp interp, String name, Namespace context, int flags) throws TclException {
                    if (name.matches("\\d+") || name.equals("*")) {
                        return wrapInputCmd;
                    }
                    return null;
                }

                @Override
                public Var resolveVar(Interp interp, String name, Namespace context, int flags) throws TclException {
                    return null;
                }
            });
        } catch (TclException e) {
            throw new RuntimeException(e);
        }

        return interp;
    }

    /**
     * Parse XDC
     * @param dev the device
     * @param lines XDC content
     * @param cellLookup the cell lookup (see  {@link XDCParser class level documentation} for parameter details)
     * @return parsed constraints
     */
    public static XDCConstraints parseXDC(Device dev, List<String> lines, EdifCellLookup<?> cellLookup) {
        XDCConstraints constraints = new XDCConstraints();


        Interp interp = makeTclInterp(constraints, dev, cellLookup);
        try {
            String data = String.join("\n", lines);
            try {
                interp.eval(data);
            } catch (TclException ex) {
                int code = ex.getCompletionCode();
                switch (code) {
                    case TCL.ERROR:
                        throw new RuntimeException(interp.getResult().toString()+" in line "+interp.getErrorLine(), ex);
                    case TCL.BREAK:
                        throw new RuntimeException(
                                "invoked \"break\" outside of a loop", ex);
                    case TCL.CONTINUE:
                        throw new RuntimeException(
                                "invoked \"continue\" outside of a loop", ex);
                    default:
                        throw new RuntimeException(
                                "command returned bad error code: " + code, ex);
                }
            }
        }  finally {
            interp.dispose();
        }

        return constraints;
    }



    /**
     * @param fileName Name of the XDC file to parse
     * @param dev the design
     * @param cellLookup the cell lookup (see  {@link XDCParser class level documentation} for parameter details)
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDC(String fileName, Device dev, EdifCellLookup<?> cellLookup){
        return parseXDC(dev, FileTools.getLinesFromTextFile(fileName), cellLookup);
    }

    /**
     * @param fileName Name of the XDC file to parse
     * @param dev the design
     * @param netlist optional netlist to enable more advanced get_cells calls
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDC(String fileName, Device dev, EDIFNetlist netlist){
        return parseXDC(dev, FileTools.getLinesFromTextFile(fileName), new RegularEdifCellLookup(netlist));
    }

    /**
     * @param fileName Name of the XDC file to parse
     * @param design The design
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDC(String fileName, Design design){
        return parseXDC(design.getDevice(), FileTools.getLinesFromTextFile(fileName), new RegularEdifCellLookup(design.getNetlist()));
    }

    /**
     * @param fileName Name of the XDC file to parse
     * @param dev the device
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDC(String fileName, Device dev){
        return parseXDC(dev, FileTools.getLinesFromTextFile(fileName), null);
    }

    public static void writeXDC(List<String> constraints, OutputStream out){
        if(constraints == null) return;
        try {
            for(String s : constraints){
                out.write(s.getBytes());
                out.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
