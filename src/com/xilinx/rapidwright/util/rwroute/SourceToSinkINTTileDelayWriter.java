/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022, 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.util.rwroute;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

/**
 * A helper class to write delay from the source to the sink of a routed net
 * (Usage: input.dcp --net net_name outputFilePath --allSinkDelay).
 * When the "--allSinkDelay" option specified, it writes the delay values from
 * the source to the sinks (until the interconnect tile node) of the net under the name.
 * Otherwise, it is specifically for the CLK_IN sink of the net under the name.
 */
public class SourceToSinkINTTileDelayWriter {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("BASIC USAGE:\n <input.dcp> --net <net name> <output file> --allSinkDelay\n");
            return;
        }

        boolean writeAllSinkDelay = args.length > 4 && args[4].equals("--allSinkDelay");

        String inputDcpName = args[0].substring(args[0].lastIndexOf("/")+1);
        Design design = Design.readCheckpoint(args[0]);
        boolean useUTurnNodes = false;
        DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), useUTurnNodes, 0);

        Net net = design.getNet(args[2]);
        if (net == null) {
            System.err.println("ERROR: Cannot find net under name " + args[2]);
            return;
        } else if (!net.hasPIPs()) {
            System.err.println("ERROR: No PIPs found of net " + net.getName());
            return;
        }

        Map<SitePinInst, Pair<Node,Short>> sourceToSinkINTDelays = RouterHelper.getSourceToSinkINTNodeDelays(net, estimator);

        String outputFile = args[3].endsWith("/")? args[3] : args[3] + "/";
        outputFile += inputDcpName.replace(".dcp", "_getDelayToSinkINT.txt");

        try {
            FileWriter myWriter = new FileWriter(outputFile);

            if (writeAllSinkDelay) {
                System.out.println("INFO: Write delay from source to all sink to file \n      " + outputFile);
                for (Entry<SitePinInst, Pair<Node,Short>> sinkINTNodeDelay : sourceToSinkINTDelays.entrySet()) {
                    Node node = sinkINTNodeDelay.getValue().getFirst();
                    Short delay = sinkINTNodeDelay.getValue().getSecond();
                    myWriter.write(node + " \t\t" + delay + "\n");
                    System.out.printf(String.format("      %-50s %5d\n", node, delay));
                }

            } else {
                System.out.println("INFO: Write delay from source to IMUX node of CLK_IN to file \n      " + outputFile);
                for (Entry<SitePinInst, Pair<Node,Short>> sinkINTNodeDelay : sourceToSinkINTDelays.entrySet()) {
                    Node node = sinkINTNodeDelay.getValue().getFirst();
                    Short delay = sinkINTNodeDelay.getValue().getSecond();
                    if (sinkINTNodeDelay.getKey().toString().contains("CLK_IN")) {
                        myWriter.write(node + " \t\t" + delay + "\n");
                    }
                }
            }

            myWriter.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
