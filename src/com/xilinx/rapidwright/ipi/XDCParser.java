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
/**
 *
 */
package com.xilinx.rapidwright.ipi;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.ipi.xdcParserCommands.CreateClockCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.DesignObject;
import com.xilinx.rapidwright.ipi.xdcParserCommands.DumpObjsCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.GetCellsCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.ObjType;
import com.xilinx.rapidwright.ipi.xdcParserCommands.ObjectGetterCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.SetPropertyCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.UnsupportedCmdResult;
import com.xilinx.rapidwright.ipi.xdcParserCommands.UnsupportedCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.UnsupportedGetterCommand;
import com.xilinx.rapidwright.ipi.xdcParserCommands.UnsupportedIfCommand;
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
 * Parses an XDC file for package constraints only.  Does not
 * perform full XDC parsing.
 *
 * Created on: Jul 27, 2015
 */
public class XDCParser {

    private static <T> void replaceCommand(Interp interp, EdifCellLookup<T> cellLookup, String commandName) {
        Command replacedCommand = Objects.requireNonNull(interp.getCommand(commandName));
        if (commandName.equals("if")) {
            interp.createCommand(commandName, new UnsupportedIfCommand(cellLookup, replacedCommand));
        } else {
            interp.createCommand(commandName, new UnsupportedGetterCommand(cellLookup, replacedCommand));
        }
    }


    public static <T> Interp makeTclInterp(XDCConstraints constraints, Device dev, EdifCellLookup<T> cellLookup) {
        Interp interp = new Interp();
        interp.createCommand("set_property", new SetPropertyCommand<>(constraints, dev, cellLookup));
        interp.createCommand("current_design", new ObjectGetterCommand(false,ObjType.Design));

        if (cellLookup!=null) {
            interp.createCommand("get_cells", new GetCellsCommand<>(cellLookup));
        } else {
            interp.createCommand("get_cells", new ObjectGetterCommand(true, ObjType.Cell));
        }
        interp.createCommand("get_ports", new ObjectGetterCommand(true, ObjType.Port));
        interp.createCommand("create_clock", new CreateClockCommand(constraints, cellLookup));

        interp.createCommand("dump_objs", new DumpObjsCommand(cellLookup));

        UnsupportedCommand unsupportedCommand = new UnsupportedCommand(constraints, cellLookup);
        interp.createCommand("set_false_path", unsupportedCommand);
        interp.createCommand("set_input_delay", unsupportedCommand);
        interp.createCommand("set_output_delay", unsupportedCommand);
        interp.createCommand("set_max_delay", unsupportedCommand);
        interp.createCommand("set_bus_skew", unsupportedCommand);

        UnsupportedGetterCommand unsupportedGetterCommand = new UnsupportedGetterCommand(cellLookup);
        interp.createCommand("get_pins", unsupportedGetterCommand);
        interp.createCommand("get_clocks", unsupportedGetterCommand);
        interp.createCommand("get_property", unsupportedGetterCommand);
        interp.createCommand("get_nets", unsupportedGetterCommand);
        replaceCommand(interp, cellLookup, "if");
        replaceCommand(interp, cellLookup, "llength");
        replaceCommand(interp, cellLookup, "expr");

        interp.createCommand("debugDump", new DebugDumpCommand());


        interp.setCommandDoneCallback(()-> {
            try {
                DesignObject.unwrapTclObject(interp, interp.getResult(), cellLookup).ifPresent(obj -> {
                    if (obj instanceof UnsupportedCmdResult<?>) {
                        constraints.getUnsupportedConstraints().add(((UnsupportedCmdResult<?>) obj).withoutOutsideBrackets().getCmd());
                    }
                });
            } catch (TclException e) {
                throw new RuntimeException(e);
            }
        });


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
     * @param cellLookup optional cell lookup, if given allows for more complex get_cells calls
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
     * @param cellLookup optional cell lookup, if given allows for more complex get_cells calls
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDCNew(String fileName, Device dev, EdifCellLookup<?> cellLookup){
        return parseXDC(dev, FileTools.getLinesFromTextFile(fileName), cellLookup);
    }

    /**
     * @param fileName Name of the XDC file to parse
     * @param dev the design
     * @param netlist optional netlist to enable more advanced get_cells calls
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDCNew(String fileName, Device dev, EDIFNetlist netlist){
        return parseXDC(dev, FileTools.getLinesFromTextFile(fileName), new RegularEdifCellLookup(netlist));
    }

    /**
     * @param fileName Name of the XDC file to parse
     * @param design The design
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDCNew(String fileName, Design design){
        return parseXDC(design.getDevice(), FileTools.getLinesFromTextFile(fileName), new RegularEdifCellLookup(design.getNetlist()));
    }

    public static Map<String,PackagePinConstraint> parseXDC(String fileName, Design design) {
        return parseXDCNew(fileName, design).getPinConstraints();
    }

    /**
     * @param fileName Name of the XDC file to parse
     * @param dev the device
     * @return A map of port names to package pin information.
     */
    public static XDCConstraints parseXDCNew(String fileName, Device dev){
        return parseXDC(dev, FileTools.getLinesFromTextFile(fileName), null);
    }

    public static Map<String,PackagePinConstraint> parseXDC(String fileName, Device dev) {
        return parseXDCNew(fileName, dev).getPinConstraints();
    }

    public static void writeXDC(List<String> constraints, OutputStream out){
        if(constraints == null) return;
        try {
            for(String s : constraints){
                out.write(s.getBytes());
                out.write('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
