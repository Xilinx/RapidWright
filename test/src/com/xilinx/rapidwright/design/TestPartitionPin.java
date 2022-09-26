/*
 * Copyright (c) 2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.design;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestPartitionPin {

    private void testPortPartitionPin(Design design, PartitionPin ppin, EDIFPort port, int idx, Node node) {
        Assertions.assertNull(ppin.getInstanceName());
        Assertions.assertNull(ppin.getLibCellName());
        Assertions.assertEquals(port.getPortInstNameFromPort(idx), ppin.getTerminalName());
        Assertions.assertEquals(node, ppin.getNode());
        Assertions.assertTrue(design.removePartitionPin(ppin));
        Assertions.assertTrue(design.addPartitionPin(ppin));
        Assertions.assertTrue(ppin.isFixed());
        Assertions.assertTrue(ppin.isWireFixed());
        ppin.setIsFixed(false);
        Assertions.assertFalse(ppin.isFixed());
        ppin.setIsWireFixed(false);
        Assertions.assertFalse(ppin.isWireFixed());
        Assertions.assertTrue(ppin.isPort());
        Assertions.assertEquals(node.getTile(), ppin.getTile());
        Assertions.assertEquals(node.getWire(), ppin.getWireIndex());
        Assertions.assertEquals(node.getTile().getName(), ppin.getTileName());
        Assertions.assertEquals(node.getWireName(), ppin.getWireName());
    }

    private void testHierPinPartitionPin(Design design, PartitionPin ppin, EDIFHierPortInst pin, Node node) {
        Assertions.assertEquals(pin.getFullHierarchicalInstName(), ppin.getInstanceName());
        Assertions.assertEquals(pin.getCellType().getName(), ppin.getLibCellName());
        Assertions.assertEquals(pin.getPortInst().getName(), ppin.getTerminalName());
        Assertions.assertEquals(node, ppin.getNode());
        Assertions.assertTrue(design.removePartitionPin(ppin));
        Assertions.assertTrue(design.addPartitionPin(ppin));
        Assertions.assertTrue(ppin.isFixed());
        Assertions.assertTrue(ppin.isWireFixed());
        ppin.setIsFixed(false);
        Assertions.assertFalse(ppin.isFixed());
        ppin.setIsWireFixed(false);
        Assertions.assertFalse(ppin.isPort());
        Assertions.assertEquals(node.getTile(), ppin.getTile());
        Assertions.assertEquals(node.getWire(), ppin.getWireIndex());
        Assertions.assertEquals(node.getTile().getName(), ppin.getTileName());
        Assertions.assertEquals(node.getWireName(), ppin.getWireName());
    }

    @Test
    public void testPartitionPins() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Tile t = design.getDevice().getTile("INT_X8Y239");
        int wireIdx = 10;
        int count = 0;
        for (EDIFPort port : design.getTopEDIFCell().getPorts()) {
            if (port.isBus()) {
                for (int i : port.getBitBlastedIndicies()) {
                    Node node = Node.getNode(t, wireIdx++);
                    PartitionPin ppin = design.createPartitionPin(port, i, node);
                    testPortPartitionPin(design, ppin, port, i, node);
                    count++;
                }
            } else {
                Node node = Node.getNode(t, wireIdx++);
                PartitionPin ppin = design.createPartitionPin(port, node);
                testPortPartitionPin(design, ppin, port, -1, node);
                count++;
            }
        }
        Assertions.assertEquals(count, design.getPartitionPins().size());


        EDIFHierCellInst memory = design.getNetlist().getHierCellInstFromName("your_program");
        for (EDIFHierPortInst hierPortInst : memory.getHierPortInsts()) {
            Node node = Node.getNode(t, wireIdx);
            PartitionPin ppin = design.createPartitionPin(hierPortInst, node);
            testHierPinPartitionPin(design, ppin, hierPortInst, node);
            count++;
        }
        Assertions.assertEquals(count, design.getPartitionPins().size());
    }
}
