/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.edif;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestEDIFPort {

    @Test
    public void testEDIFPortInternalNets() {
        Design design = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));

        for (EDIFLibrary lib : design.getNetlist().getLibraries()) {
            for (EDIFCell cell : lib.getCells()) {
                boolean isLeaf = cell.isLeafCellOrBlackBox();
                Map<String,EDIFNet> internalNetMap = cell.getInternalNetMap();
                for (EDIFPort port : cell.getPorts()) {
                   if (port.isBus()) {
                       List<EDIFNet> nets = port.getInternalNets();
                       for (int i=0; i < port.getWidth(); i ++) {
                           EDIFNet net = nets.get(i);
                           Assertions.assertEquals(port.getInternalNet(i), nets.get(i));
                           String portInstName = port.getPortInstNameFromPort(i);
                           Assertions.assertEquals(internalNetMap.get(portInstName), net);
                           if (isLeaf) {
                               Assertions.assertNull(net);
                           }
                       }
                   } else {
                       EDIFNet net = port.getInternalNet();
                       String portInstName = port.getPortInstNameFromPort(0);
                       Assertions.assertEquals(internalNetMap.get(portInstName), net);
                       if (isLeaf) {
                           Assertions.assertNull(net);
                       }
                   }
                }
            }
        }
    }

    @Test
    public void testCreatePort() {
        String designName = "design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, Device.KCU105);
        design.setNetlist(netlist);

        EDIFCell cell = new EDIFCell(netlist.getWorkLibrary(), "cell_1");
        int outer = 0;
        // Creates ports: {bus_output[0][3:0], bus_output[2][5:2],
        // bus_output[1][0:3], bus_output[3][2:5]}
        for (String range : new String[] { "3:0", "0:3", "5:2", "2:5" }) {
            int left = Integer.parseInt(range.substring(0, range.indexOf(':')));
            int right = Integer.parseInt(range.substring(range.indexOf(':') + 1));
            int width = Math.abs(left - right) + 1;
            EDIFPort busOutput = cell.createPort("bus_output[" + outer + "][" + range + "]",
                    EDIFDirection.OUTPUT, width);
            Assertions.assertEquals(left, busOutput.getLeft());
            Assertions.assertEquals(right, busOutput.getRight());
            Assertions.assertTrue(busOutput.isBus());
            Assertions.assertEquals(left > right, busOutput.isLittleEndian());
            EDIFPort copy = cell.getPort("bus_output[" + outer + "][");
            Assertions.assertEquals(busOutput, copy);

            int[] portIndices = busOutput.getBitBlastedIndicies();
            Assertions.assertEquals(width, portIndices.length);
            Assertions.assertEquals(left, portIndices[0]);
            Assertions.assertEquals(right, portIndices[portIndices.length - 1]);
            for (int i : portIndices) {
                EDIFNet net = cell.createNet("net[" + outer + "][" + i + "]");
                String portInstName = "bus_output[" + outer + "][" + i + "]";
                EDIFPortInst portInst = net.createPortInst(portInstName, cell);
                Assertions.assertEquals(busOutput, portInst.getPort());
                Assertions.assertEquals(portInst.getPort().getPortIndexFromNameIndex(i),
                        portInst.getIndex());
            }

            outer++;
        }

        // Check for potential collisions (prevalent in older versions), for example:
        // 'foo' vs 'foo[0]' (both single bit ports)
        // 'foo[0]' vs 'foo[1]' (both single bit ports)
        // 'foo[0]' vs 'foo[0][0]' (both single bit ports)
        // 'foo[0]' vs 'foo[0][7:0]' (single bit vs bussed port)
        for (String singleBitPort : new String[] { "foo", "foo[0]", "foo[1]", "foo[0][0]", "bar[1]" }) {
            EDIFPort port = cell.createPort(singleBitPort, EDIFDirection.OUTPUT, 1);
            // Ensure single bit bracketed port is not converted to a bus
            Assertions.assertFalse(port.isBus());
            Assertions.assertEquals(1, port.getWidth());
            Assertions.assertEquals(port, cell.getPort(singleBitPort));
            Assertions.assertEquals(port.getBusName(), port.getName());
        }

        EDIFPort busPort = cell.createPort("foo[0][7:0]", EDIFDirection.OUTPUT, 8);
        Assertions.assertTrue(busPort.isBus());
        Assertions.assertEquals(7, busPort.getLeft());
        Assertions.assertEquals(0, busPort.getRight());
        Assertions.assertEquals(busPort, cell.getPort("foo[0]["));
        Assertions.assertNotEquals(busPort, cell.getPort("foo[0]"));
    }

    @ParameterizedTest
    @CsvSource({
            "bus[7:0],bus,8",
            "bus[0:7],bus,8",
            "bus[15][15:0],bus[15],8",
            "foo,foo,1",
            "foo[0],foo[0],1",
            "foo[1],foo[1],1",
            "foo[2][2],foo[2][2],1",
            "foo[3][3:3],foo[3],1",
    })
    void testGetBusName(String portName, String busName, int width) {
        String designName = "design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, Device.KCU105);
        design.setNetlist(netlist);

        EDIFCell cell = new EDIFCell(netlist.getWorkLibrary(), "cell_1");
        EDIFPort busOutput = cell.createPort(portName, EDIFDirection.OUTPUT, width);
        Assertions.assertEquals(busName, busOutput.getBusName());
    }
}
