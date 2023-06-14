/*
 * Copyright (c) 2019-2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.merge.MergeDesigns;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rwroute.RWRoute;

import static com.xilinx.rapidwright.examples.ArithmeticGenerator.INPUT_A_NAME;
import static com.xilinx.rapidwright.examples.ArithmeticGenerator.INPUT_B_NAME;
import static com.xilinx.rapidwright.examples.ArithmeticGenerator.RESULT_NAME;

public class ParamCounterDesignMerger {
    public static void main(String[] args) {
        String sliceName = "SLICE_X0Y0";
        String clkName = "clk";
        double clkPeriodConstraint = 10;
        String adderDCPFileName = "adder.dcp";
        String wrapperDCPFileName = "cntrWrapper.dcp";
        String outputDCPFileName = "cntr.dcp";
        int width = 32;
        String part = "xczu3eg-sbva484-1-i";

//        CodePerfTracker t = new CodePerfTracker(paramCounter.class.getSimpleName(),true).start("Init");
        Design adderDesign = new Design("adder", part);

        adderDesign.setAutoIOBuffers(false);
        Device dev = adderDesign.getDevice();

//        t.stop().start("Create Add/Sub");
        Site slice = dev.getSite(sliceName);
        AddSubGenerator.createAddSub(adderDesign, slice, width, false, false, true, false);

        // Add a clock constraint
        String tcl = "create_clock -name "+clkName+" -period "+clkPeriodConstraint+" [get_ports "+clkName+"]";
        adderDesign.addXDCConstraint(ConstraintGroup.LATE,tcl);
        adderDesign.setAutoIOBuffers(false);

        //create a wrapper for the cntr
        Design cntrWrapper = new Design("counterParam", part);
        cntrWrapper.setAutoIOBuffers(false);

        EDIFCell top = cntrWrapper.getTopEDIFCell();
        String bus = "["+(width-1)+":0]";

        //create ports in cntr wrapper
        EDIFPort aPort = top.createPort(INPUT_A_NAME + bus, EDIFDirection.OUTPUT, width);
        EDIFPort bPort = top.createPort(INPUT_B_NAME + bus, EDIFDirection.OUTPUT, width);
        EDIFPort resultPort = top.createPort(RESULT_NAME + bus, EDIFDirection.INPUT, width);
        EDIFPort outPort = top.createPort("cntrOut" + bus, EDIFDirection.OUTPUT, width);

        EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, cntrWrapper.getTopEDIFCell(), cntrWrapper.getNetlist());
        EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, cntrWrapper.getTopEDIFCell(), cntrWrapper.getNetlist());

        //create feedback nets and connect them to ports
        for(int i = 0; i<width; i++) {
            Net net = cntrWrapper.createNet("feedback" + i);
            net.getLogicalNet().createPortInst(resultPort, i);
            net.getLogicalNet().createPortInst(aPort, i);
            net.getLogicalNet().createPortInst(outPort, i);

            //connect gnd or vcc to B port
            if (i == 0) {
                vcc.createPortInst(bPort, i);
            } else {
                gnd.createPortInst(bPort, i);
            }
        }
        cntrWrapper.createNet(vcc.getName());
        cntrWrapper.createNet(gnd.getName());

        //write pre-merge checkpoints
        cntrWrapper.writeCheckpoint(wrapperDCPFileName);
        adderDesign.writeCheckpoint(adderDCPFileName);

        //merge designs
        Design d = MergeDesigns.mergeDesigns(adderDesign, cntrWrapper);

        //reroute sites
        d.unrouteSites();
        d.routeSites();

//        DesignTools.updateSitePinInsts(d); //TODO: handle in merge.
        d = RWRoute.routeDesignFullNonTimingDriven(d);

//        t.stop();
        // write final checkpoint
        d.writeCheckpoint(outputDCPFileName);
    }

}