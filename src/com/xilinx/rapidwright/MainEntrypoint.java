/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.debug.DesignInstrumentor;
import com.xilinx.rapidwright.debug.ILAInserter;
import com.xilinx.rapidwright.debug.ProbeRouter;
import com.xilinx.rapidwright.design.MetadataParser;
import com.xilinx.rapidwright.design.blocks.ImplGuide;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockGenerator;
import com.xilinx.rapidwright.design.merge.MergeDesigns;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.PseudoPIPHelper;
import com.xilinx.rapidwright.device.browser.DeviceBrowser;
import com.xilinx.rapidwright.device.browser.PBlockGenDebugger;
import com.xilinx.rapidwright.device.helper.TileColumnPattern;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFParser;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.examples.AddSubGenerator;
import com.xilinx.rapidwright.examples.CopyMMCMCell;
import com.xilinx.rapidwright.examples.CustomRouting;
import com.xilinx.rapidwright.examples.DecomposeLUT;
import com.xilinx.rapidwright.examples.ExampleNetlistCreation;
import com.xilinx.rapidwright.examples.IsolateLeafClkBuffer;
import com.xilinx.rapidwright.examples.Lesson1;
import com.xilinx.rapidwright.examples.MultGenerator;
import com.xilinx.rapidwright.examples.PicoBlazeArray;
import com.xilinx.rapidwright.examples.PipelineGenerator;
import com.xilinx.rapidwright.examples.PipelineGeneratorWithRouting;
import com.xilinx.rapidwright.examples.PolynomialGenerator;
import com.xilinx.rapidwright.examples.PrintEDIFInstances;
import com.xilinx.rapidwright.examples.ReportTimingExample;
import com.xilinx.rapidwright.examples.RunSATRouterExample;
import com.xilinx.rapidwright.examples.SLRCrosserGenerator;
import com.xilinx.rapidwright.examples.StampPlacement;
import com.xilinx.rapidwright.examples.UpdateRoutingUsingSATRouter;
import com.xilinx.rapidwright.examples.tilebrowser.PartTileBrowser;
import com.xilinx.rapidwright.interchange.DcpToInterchange;
import com.xilinx.rapidwright.interchange.DeviceResourcesExample;
import com.xilinx.rapidwright.interchange.EdifToLogicalNetlist;
import com.xilinx.rapidwright.interchange.EnumerateCellBelMapping;
import com.xilinx.rapidwright.interchange.GenerateInterchangeDevices;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.interchange.LogicalNetlistExample;
import com.xilinx.rapidwright.interchange.PhysicalNetlistExample;
import com.xilinx.rapidwright.interchange.PhysicalNetlistToDcp;
import com.xilinx.rapidwright.ipi.BlockCreator;
import com.xilinx.rapidwright.ipi.BlockStitcher;
import com.xilinx.rapidwright.ipi.BlockUpdater;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;
import com.xilinx.rapidwright.placer.handplacer.ModuleOptimizer;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.rwroute.PartialRouter;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.tests.CheckAccuracyUsingGnlDesigns;
import com.xilinx.rapidwright.tests.DeviceLoader;
import com.xilinx.rapidwright.tests.PinMapTester;
import com.xilinx.rapidwright.tests.ReportDevicePerformance;
import com.xilinx.rapidwright.util.BrowseDevice;
import com.xilinx.rapidwright.util.CompareRouteStatusReports;
import com.xilinx.rapidwright.util.DesignImplementationDiff;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.Jython;
import com.xilinx.rapidwright.util.MakeBlackBox;
import com.xilinx.rapidwright.util.PartPrinter;
import com.xilinx.rapidwright.util.PerformanceExplorer;
import com.xilinx.rapidwright.util.ReplaceEDIFInDCP;
import com.xilinx.rapidwright.util.StringTools;
import com.xilinx.rapidwright.util.Unzip;
import com.xilinx.rapidwright.util.performance_evaluation.PerformanceEvaluation;

public class MainEntrypoint {
    interface MainStyleFunction<E extends Throwable> {
        void main(String[] args) throws E;
    }

    private static final Map<String, MainStyleFunction<?>> functions = new HashMap<>();
    private static final List<String> functionNames = new ArrayList<>();

    private static void addFunction(String name, MainStyleFunction<?> func) {
        functions.put(name.toLowerCase(), func);
        functionNames.add(name);
    }

    static {
        addFunction("AddSubGenerator", AddSubGenerator::main);
        addFunction("BlockCreator", BlockCreator::main);
        addFunction("BlockStitcher", BlockStitcher::main);
        addFunction("BlockUpdater", BlockUpdater::main);
        addFunction("BrowseDevice", BrowseDevice::main);
        addFunction("CheckAccuracyUsingGnlDesigns", CheckAccuracyUsingGnlDesigns::main);
        addFunction("CompareRouteStatusReports", CompareRouteStatusReports::main);
        addFunction("CopyMMCMCell", CopyMMCMCell::main);
        addFunction("CustomRouting", CustomRouting::main);
        addFunction("DcpToInterchange", DcpToInterchange::main);
        addFunction("DecomposeLUT", DecomposeLUT::main);
        addFunction("DesignImplementationDiff", DesignImplementationDiff::main);
        addFunction("DesignInstrumentor", DesignInstrumentor::main);
        addFunction("DeviceBrowser", DeviceBrowser::main);
        addFunction("DeviceLoader", DeviceLoader::main);
        addFunction("DeviceResourcesExample", DeviceResourcesExample::main);
        addFunction("EDIFNetlist", EDIFNetlist::main);
        addFunction("EDIFParser", EDIFParser::main);
        addFunction("EDIFPropertyValue", EDIFPropertyValue::main);
        addFunction("EDIFToLogicalNetlist", EdifToLogicalNetlist::main);
        addFunction("EDIFTools", EDIFTools::main);
        addFunction("EnumerateCellBelMapping", EnumerateCellBelMapping::main);
        addFunction("ExampleNetlistCreation", ExampleNetlistCreation::main);
        addFunction("FileTools", FileTools::main);
        addFunction("GenerateInterchangeDevices", GenerateInterchangeDevices::main);
        addFunction("HandPlacer", HandPlacer::main);
        addFunction("ILAInserter", ILAInserter::main);
        addFunction("ImplGuide", ImplGuide::main);
        addFunction("IntentCode", IntentCode::main);
        addFunction("Interchange", Interchange::main);
        addFunction("IsolateLeafClkBuffer", IsolateLeafClkBuffer::main);
        addFunction("Jython", Jython::main);
        addFunction("JobQueue", JobQueue::main);
        addFunction("Lesson1", Lesson1::main);
        addFunction("LogicalNetlistExample", LogicalNetlistExample::main);
        addFunction("LUTTools", LUTTools::main);
        addFunction("MakeBlackBox", MakeBlackBox::main);
        addFunction("MergeDesigns", MergeDesigns::main);
        addFunction("MetadataParser", MetadataParser::main);
        addFunction("ModuleOptimizer", ModuleOptimizer::main);
        addFunction("MultGenerator", MultGenerator::main);
        addFunction("PartPrinter", PartPrinter::main);
        addFunction("PartTileBrowser", PartTileBrowser::main);
        addFunction("PartialRouter", PartialRouter::main);
        addFunction("PBlockGenDebugger", PBlockGenDebugger::main);
        addFunction("PBlockGenerator", PBlockGenerator::main);
        addFunction("PBlock", PBlock::main);
        addFunction("PerformanceEvaluation", PerformanceEvaluation::main);
        addFunction("PerformanceExplorer", PerformanceExplorer::main);
        addFunction("PhysicalNetlistExample", PhysicalNetlistExample::main);
        addFunction("PhysicalNetlistToDcp", PhysicalNetlistToDcp::main);
        addFunction("PicoBlazeArray", PicoBlazeArray::main);
        addFunction("PinMapTester", PinMapTester::main);
        addFunction("PipelineGenerator", PipelineGenerator::main);
        addFunction("PipelineGeneratorWithRouting", PipelineGeneratorWithRouting::main);
        addFunction("PolynomialGenerator", PolynomialGenerator::main);
        addFunction("PrintEDIFInstances", PrintEDIFInstances::main);
        addFunction("ProbeRouter", ProbeRouter::main);
        addFunction("PseudoPIPHelper", PseudoPIPHelper::main);
        addFunction("ReplaceEDIFInDCP", ReplaceEDIFInDCP::main);
        addFunction("ReportDevicePerformance", ReportDevicePerformance::main);
        addFunction("ReportTimingExample", ReportTimingExample::main);
        addFunction("Router", Router::main);
        addFunction("RouteThruHelper", RouteThruHelper::main);
        addFunction("RunSATRouterExample", RunSATRouterExample::main);
        addFunction("RWRoute", RWRoute::main);
        addFunction("SLRCrosserGenerator", SLRCrosserGenerator::main);
        addFunction("SmallestEnclosingCircle", SmallestEnclosingCircle::main);
        addFunction("StampPlacement", StampPlacement::main);
        addFunction("StandaloneEntrypoint", StandaloneEntrypoint::main);
        addFunction("StringTools", StringTools::main);
        addFunction("TileColumnPattern", TileColumnPattern::main);
        addFunction("Unzip", Unzip::main);
        addFunction("UpdateRoutingUsingSATRouter", UpdateRoutingUsingSATRouter::main);
    }

    private static void listModes(PrintStream ps) {
        StringTools.printListInColumns(functionNames, ps, 5);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length == 0) {
            System.err.println("Need one argument to determine the application. Valid applications are (case-insensitive):");
            listModes(System.err);
            System.exit(1);
        }

        if (args.length >= 1 && args[0].equals("--list-apps")) {
            System.out.println("Current list of available RapidWright applications (case-insensitive):");
            listModes(System.out);
            return;
        }

        String application = args[0];
        MainStyleFunction<?> func = functions.get(application.toLowerCase());
        if (func == null) {
            System.err.println("Invalid application '"+application+"'. Valid applications are (case-insensitive): ");
            listModes(System.err);
            System.exit(1);
        }

        String[] childArgs = new String[args.length-1];
        System.arraycopy(args, 1, childArgs, 0, args.length-1);
        func.main(childArgs);
    }
}
