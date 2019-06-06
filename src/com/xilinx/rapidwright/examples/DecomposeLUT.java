package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class DecomposeLUT {

	public static void main(String[] args) {
		Design d = new Design("DecomposeLUT",Device.PYNQ_Z1);
		EDIFCell top = d.getTopEDIFCell();
		Cell lut6 = d.createAndPlaceCell("myLUT6", Unisim.LUT6, "SLICE_X0Y0/A6LUT");
		LUTTools.configureLUT(lut6, "O=I0 & I1 & I2 & I3 & I4 & I5");
		for(EDIFPort port : lut6.getEDIFCellInst().getCellPorts()){
			EDIFPort topPort = top.createPort(port.getName(), port.getDirection(), 1);
			EDIFNet net = top.createNet(port.getName());
			net.createPortInst(topPort);
			net.createPortInst(port,lut6.getEDIFCellInst());
		}
		
		d.setAutoIOBuffers(false);
		d.writeCheckpoint("single_LUT_6_connected_to_top_level_ports.dcp");
		
		// Convert LUT6 to two LUT5s and a LUT3 (2:1 mux)
		Cell lut5_0 = d.createAndPlaceCell("myLUT5_0", Unisim.LUT5, "SLICE_X0Y0/B6LUT");
		Cell lut5_1 = d.createAndPlaceCell("myLUT5_1", Unisim.LUT5, "SLICE_X0Y0/C6LUT");
		Cell mux = d.createAndPlaceCell("mux", Unisim.LUT3, "SLICE_X0Y0/D6LUT");
		
		LUTTools.configureLUT(lut5_0, "O=0"); // Evaluate LUT6 equation with I5=0
		LUTTools.configureLUT(lut5_1, "O=I0 & I1 & I2 & I3 & I4"); // Evaluate LUT6 equation with I5=1
		LUTTools.configureLUT(mux, "O=(IO & ~I2) + (I1 & I2) + (I0 & I1)"); // 2:1 Mux truth table
		
		// Disconnect LUT6, connect LUT5 inputs
		for(EDIFPortInst port : lut6.getEDIFCellInst().getPortInsts()){
			EDIFNet net = port.getNet();
			net.removePortInst(port);
			if(port.isInput() && !port.getName().equals("I5")){
				net.createPortInst(port.getName(), lut5_0);
				net.createPortInst(port.getName(), lut5_1);
			}else if(port.getName().equals("I5")){
				// This is our select line
				net.createPortInst("I2", mux); 
			}else if(port.isOutput()){
				// Connect output of mux to same top-level output
				net.createPortInst("O", mux);
			}
		}
		
		// Connect LUT5 outputs to Mux inputs
		EDIFNet lut5Output0 = top.createNet("lut5_0");
		EDIFNet lut5Output1 = top.createNet("lut5_1");
		lut5Output0.createPortInst("O",lut5_0);
		lut5Output0.createPortInst("I0",mux);
		lut5Output1.createPortInst("O",lut5_1);
		lut5Output1.createPortInst("I1",mux);
		
		d.writeCheckpoint("decomposed_6LUT_to_2_5LUTs_and_mux.dcp");
	}
}
