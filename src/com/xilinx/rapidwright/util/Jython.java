/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

import org.python.core.PySystemState;
import org.python.google.common.reflect.ClassPath;
import org.python.google.common.reflect.ClassPath.ClassInfo;
import org.python.util.jython;

import com.xilinx.rapidwright.device.Device;

/**
 * Main entry point for the RapidWright Jython (Python) interactive shell.
 * @author clavin
 *
 */
public class Jython {

    /**
     * When invoking the '-c' option for the Jython interpreter, this method will
     * analyze the command for RapidWright classes that need to be imported and
     * automatically add the import statements.
     * 
     * @param args Command line options.
     * @return Augments the argument of the '-c' option with the proper import
     *         statement for a RapidWright class. If not RapidWright class is needed
     *         or the '-c' option is not found in the arguments, the original
     *         arguments are returned.
     */
    public static String[] addImportsForCommandLineOption(String[] args) {
        String origCmd = null;
        int origCmdIdx = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-c")) {
                if (i + 1 >= args.length) {
                    // No command arg, let default code handle it
                    return args;
                }
                origCmd = args[i + 1];
                origCmdIdx = i + 1;
            }
        }

        if (origCmdIdx != -1) {
            ClassPath cp = null;
            try {
                cp = ClassPath.from(Thread.currentThread().getContextClassLoader());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            StringBuilder jythonCmd = new StringBuilder();
            for (ClassInfo s : cp.getAllClasses()) {
                if (s.getPackageName().startsWith("com.xilinx.rapidwright")) {
                    // Filter out inner classes and this class to avoid run away recursion
                    if (s.getSimpleName() == null || s.getSimpleName().isEmpty())
                        continue;
                    if (Character.isLowerCase(s.getSimpleName().charAt(0)))
                        continue;
                    if (s.toString().contains("$"))
                        continue;
                    if (s.getSimpleName().equals("Run"))
                        continue;
                    if (s.getPackageName().startsWith("com.xilinx.rapidwright.gui"))
                        continue;
        
                    // Only import those classes being called out
                    if (origCmd.contains(s.getSimpleName())) {
                        jythonCmd.append("from " + s.getPackageName() + " import " + s.getSimpleName() + ";");
                        break;
                    }
                }
            }
            jythonCmd.append(origCmd);
            // Overwrite the original command with version supplemented with import statements
            args[origCmdIdx] = jythonCmd.toString();
        }
        
        return args;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            // If no arguments, import all major rapidwright packages for ease of use
            @SuppressWarnings("rawtypes")
            Class[] primerClass = new Class[]{
                    com.xilinx.rapidwright.debug.DesignInstrumentor.class,
                    com.xilinx.rapidwright.debug.ProbeRouter.class,
                    com.xilinx.rapidwright.design.Cell.class,
                    com.xilinx.rapidwright.design.Design.class,
                    com.xilinx.rapidwright.design.Module.class,
                    com.xilinx.rapidwright.design.ModuleInst.class,
                    com.xilinx.rapidwright.design.ModuleCache.class,
                    com.xilinx.rapidwright.design.Net.class,
                    com.xilinx.rapidwright.design.NetType.class,
                    com.xilinx.rapidwright.design.SitePinInst.class,
                    com.xilinx.rapidwright.device.PIP.class,
                    com.xilinx.rapidwright.design.Port.class,
                    com.xilinx.rapidwright.design.PortType.class,
                    com.xilinx.rapidwright.design.SiteInst.class,
                    com.xilinx.rapidwright.design.blocks.PBlock.class,
                    com.xilinx.rapidwright.device.ClockRegion.class,
                    com.xilinx.rapidwright.device.Device.class,
                    com.xilinx.rapidwright.device.BELClass.class,
                    com.xilinx.rapidwright.device.BEL.class,
                    com.xilinx.rapidwright.device.FamilyType.class,
                    com.xilinx.rapidwright.device.Grade.class,
                    com.xilinx.rapidwright.device.IntentCode.class,
                    com.xilinx.rapidwright.device.Node.class,
                    com.xilinx.rapidwright.device.Package.class,
                    com.xilinx.rapidwright.device.Part.class,
                    com.xilinx.rapidwright.device.PIPType.class,
                    com.xilinx.rapidwright.device.Series.class,
                    com.xilinx.rapidwright.device.Site.class,
                    com.xilinx.rapidwright.device.SiteTypeEnum.class,
                    com.xilinx.rapidwright.device.SLR.class,
                    com.xilinx.rapidwright.device.Tile.class,
                    com.xilinx.rapidwright.device.TileTypeEnum.class,
                    com.xilinx.rapidwright.device.Wire.class,
                    com.xilinx.rapidwright.util.Utils.class,
                    com.xilinx.rapidwright.device.browser.DeviceBrowser.class,
                    com.xilinx.rapidwright.edif.EDIFNetlist.class,
                    com.xilinx.rapidwright.edif.EDIFTools.class,
                    com.xilinx.rapidwright.examples.AddSubGenerator.class,
                    com.xilinx.rapidwright.examples.PolynomialGenerator.class,
                    com.xilinx.rapidwright.examples.SLRCrosserGenerator.class,
                    com.xilinx.rapidwright.ipi.BlockCreator.class,
                    com.xilinx.rapidwright.placer.handplacer.HandPlacer.class,
                    com.xilinx.rapidwright.router.Router.class,
                    com.xilinx.rapidwright.tests.CodePerfTracker.class,
                    com.xilinx.rapidwright.design.Unisim.class,
                    com.xilinx.rapidwright.util.FileTools.class,
                    com.xilinx.rapidwright.util.DeviceTools.class,
                    com.xilinx.rapidwright.device.PartNameTools.class,
                    com.xilinx.rapidwright.util.PerformanceExplorer.class,
                    com.xilinx.rapidwright.util.StringTools.class,
                    com.xilinx.rapidwright.design.DesignTools.class,
                    com.xilinx.rapidwright.design.tools.LUTTools.class,
                    com.xilinx.rapidwright.device.helper.TileColumnPattern.class,
            };

            args = new String[3];
            args[0] = "-i";
            args[1] = "-c";
            StringBuilder importCmd = new StringBuilder();
            for (@SuppressWarnings("rawtypes") Class c : primerClass) {
                String pkg = c.getPackage().getName();
                importCmd.append("from " + pkg + " import " + c.getSimpleName() + ";");
            }
            args[2] = importCmd.toString();
            System.err.println(Device.FRAMEWORK_NAME + " " + Device.RAPIDWRIGHT_VERSION + " (Jython "+PySystemState.version+")");
        } else {
            args = addImportsForCommandLineOption(args);
        }

        FileTools.blockSystemExitCalls();
        jython.main(args);
    }
}
