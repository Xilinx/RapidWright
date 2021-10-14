/*
 * Copyright (c) 2017 Xilinx, Inc.
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
package com.xilinx.rapidwright.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.VivadoProp;

public class PinMapTester {

    public static void main(String[] args) {
        if(args.length < 5){
            System.out.println("USAGE: <partname> <cell name> <site> <site type> <bel> <parameters>");
            return;
        }
        String partName = args[0];
        Part part = PartNameTools.getPart(partName);
        if(part == null){
            throw new RuntimeException("The partname " + args[0] + " is invalid or unrecognized, cannot load device.");
        }

        Device device = Device.getDevice(part);

        Design design = new Design("top", partName);

        EDIFLibrary prims = Design.getPrimitivesLibrary(design.getDevice().getName());
        EDIFLibrary library = new EDIFLibrary("work");

        EDIFNetlist netlist = new EDIFNetlist("netlist");
        netlist.setDevice(device);
        netlist.addLibrary(library);
        netlist.addLibrary(prims);

        EDIFCell topLevelCell = new EDIFCell(library, "top");

        EDIFDesign edifDesign = new EDIFDesign("design");
        edifDesign.setTopCell(topLevelCell);

        String cellTypeName = args[1];
        Unisim cellType = Unisim.valueOf(cellTypeName);

        String siteName = args[2];
        Site site = device.getSite(siteName);
        if(site == null) {
            throw new RuntimeException("Site " + siteName + " is not found in specified part.");
        }

        String siteTypeName = args[3];
        SiteTypeEnum siteType = SiteTypeEnum.valueOf(siteTypeName);
        SiteInst siteInst = design.createSiteInst("site_instance", siteType, site);

        String belName = args[4];

        BEL bel = siteInst.getBEL(belName);
        if(bel == null) {
            throw new RuntimeException("BEL " + belName + " is not found in within specified site.");
        }

        //List<String> parameters = new ArrayList<String>();
        Map<String, String> parameterMap = new HashMap<String, String>();

        Map<String,VivadoProp> defaultParameters = design.getDefaultCellProperties(device.getSeries(), cellTypeName);
        for(Map.Entry<String, VivadoProp> defaultParameter : defaultParameters.entrySet()) {
            parameterMap.put(defaultParameter.getKey(), defaultParameter.getValue().getValue());
        }

        for(int i = 5; i < args.length; ++i) {
            String[] parameterSplit = args[i].split("=", 2);
            if(parameterSplit.length != 2) {
                throw new RuntimeException("Invalid parameter " + args[i]);
            }

            parameterMap.put(parameterSplit[0], parameterSplit[1]);
        }

        List<String> parameters = new ArrayList<String>();
        for(Map.Entry<String, String> pair : parameterMap.entrySet()) {
            parameters.add(pair.getKey() + "=" + pair.getValue());
        }
        String[] parameterArray = parameters.toArray(new String[parameters.size()]);

        Cell physCell = design.createAndPlaceCell(topLevelCell, "test", cellType, site.getName() + "/" + belName, parameterArray);

        System.out.printf("Cell type %s at %s/%s in part %s, pin map:\n",
                cellTypeName, site.getName(), belName, partName);
        for(Map.Entry<String, String> pinMap : physCell.getPinMappingsP2L().entrySet()) {
            System.out.printf(" - %s <= %s\n", pinMap.getKey(), pinMap.getValue());
        }

        //for(Map.Entry<String, Set<String>> pinMap : physCell.getPinMappingsL2P().entrySet()) {
        //    System.out.printf("
        //}
    }
}

