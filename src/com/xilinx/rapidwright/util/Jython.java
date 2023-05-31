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


import com.xilinx.rapidwright.device.Device;
import org.python.core.PySystemState;
import org.python.util.jython;

/**
 * Main entry point for the RapidWright Jython (Python) interactive shell.
 * @author clavin
 *
 */
public class Jython {
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
        }

        FileTools.blockSystemExitCalls();
        jython.main(args);
    }
}
