/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.router.Router;

/**
 * Example of building a minimum viable design in RapidWright without RTL.
 * Uses the PYNQ-Z1 platform board to connect two buttons to a LUT 
 * and configure it as a 2-input AND function and output the result to an
 * LED.  This design can also be reused on any other development board
 * with buttons and LEDs by changing the device/part and updating
 * the IO package pins appropriately.
 * 
 * PYNQ_Z1: Device.PYNQ_Z1, Button0=D19,  Button1=D20, LED0=R14, LVCMOS33
 * KCU105:  Device.KCU105,  Button0=AE10, Button1=AF9, LED0=AP8, LVCMOS18 
 */
public class Lesson1 {

	public static void main(String[] args) {
		// Create a new empty design using the PYNQ-Z1 device part
		Design d = new Design("HelloWorld",Device.PYNQ_Z1);
				
		// Create all the design elements (LUT2, and 3 IOs)
		Cell and2 = d.createAndPlaceCell("and2", Unisim.AND2, "SLICE_X100Y100/A6LUT");		
		Cell button0 = d.createAndPlaceIOB("button0", PinType.IN , "D19",  "LVCMOS33");
		Cell button1 = d.createAndPlaceIOB("button1", PinType.IN , "D20",  "LVCMOS33");
		Cell led0    = d.createAndPlaceIOB("led0"   , PinType.OUT, "R14",  "LVCMOS33");
		
		// Connect Button 0 to the LUT2 input I0
		Net net0 = d.createNet("button0_IBUF");
		net0.connect(button0, "O");
		net0.connect(and2, "I0");
		
		// Connect Button 1 to the LUT2 input I1
		Net net1 = d.createNet("button1_IBUF");
		net1.connect(button1, "O");
		net1.connect(and2, "I1");
		
		// Connect the LUT2 (AND2) to the LED IO
		Net net2 = d.createNet("and2");
		net2.connect(and2, "O");
		net2.connect(led0, "I");
		
		// Route site internal nets
		d.routeSites();
		
		// Route nets between sites
		new Router(d).routeDesign();
		
		// Save our work in a Checkpoint
		d.writeCheckpoint("HelloWorld.dcp");
	}
}
