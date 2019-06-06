package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * A simple netlist creation example (no placement and routing information included).  
 * Demonstrates basic EDIF netlist functionality with RapidWright APIs.
 */
public class ExampleNetlistCreation {

	public static final String clk = "clk";
	
	public static void main(String[] args) {
		Design design = new Design("HelloWorld",Device.PYNQ_Z1);
		EDIFNetlist netlist = design.getNetlist();
		
		EDIFCell top = netlist.getTopCell();
		
		EDIFDirection in  = EDIFDirection.INPUT;
		EDIFDirection out = EDIFDirection.OUTPUT;
		String[]        pinNames = new String[]       {clk, "in0", "in1", "out0"};
		EDIFDirection[] pinDirs  = new EDIFDirection[]{in , in   , in   , out   };

		// Add FDRE to our library of cells
		EDIFCell ff = netlist.getHDIPrimitive(Unisim.FDRE);

		// Create a custom wrapper cell 
		EDIFCell and2Wrapper = new EDIFCell(netlist.getWorkLibrary(), "and2Wrapper");
		EDIFCellInst and2WrapperInst = top.createChildCellInst("and2WrapperInst", and2Wrapper);
		
		// Create LUT2 AND gate
		EDIFCell and2 = netlist.getHDIPrimitive(Unisim.AND2);
		EDIFCellInst and2Inst = and2Wrapper.createChildCellInst("and2Inst", and2);
		
		EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, top, netlist);
		EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, netlist);
		
		for(int i=0; i < pinNames.length; i++){
			// Create net to connect top-level pin to FF
			EDIFNet top2ffNet = top.createNet(pinNames[i]);
			
			// Add a top-level port to the netlist
			EDIFPort port = top.createPort(pinNames[i], pinDirs[i], 1);
			
			// Connects net to the port
			top2ffNet.createPortInst(port); 
		
			// Clk is a special case
			if(pinNames[i].equals(clk)) continue;
			
			// Create an FDRE instance
			EDIFCellInst ffInst = top.createChildCellInst(pinNames[i]+"FF", ff);
			gnd.createPortInst("R", ffInst);
			vcc.createPortInst("CE", ffInst);
			top.getNet(clk).createPortInst("C", ffInst);
			
			// Connect net to FF
			top2ffNet.createPortInst(pinDirs[i] == in ? "D" : "Q", ffInst);

			// Create equivalent ports on and wrapper cell
			EDIFPort innerPort = and2Wrapper.createPort(pinNames[i], pinDirs[i], 1);
			
			// Connect FF to andWrapper cell instance
			EDIFNet ff2AndNet = top.createNet(pinNames[i] + "2");
			ff2AndNet.createPortInst(pinNames[i], and2WrapperInst);
			ff2AndNet.createPortInst(pinDirs[i] == out ? "D" : "Q", ffInst);
			
			EDIFNet innerNet = and2Wrapper.createNet(pinNames[i]);
			innerNet.createPortInst(innerPort);
			innerNet.createPortInst(i== pinNames.length-1 ? "O" : ("I" + (i-1)), and2Inst);
		}		 		
		
		design.setAutoIOBuffers(false);
		design.writeCheckpoint("test.dcp");
		
	}
}
