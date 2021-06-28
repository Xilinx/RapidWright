package com.xilinx.rapidwright.examples;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

public class FindBlackBoxes {

	public static List<EDIFHierCellInst> getBlackBoxes(Design design){
		ArrayList<EDIFHierCellInst> blackBoxInsts = new ArrayList<EDIFHierCellInst>(); 
		for(EDIFHierCellInst inst : design.getNetlist().getAllDescendants("", "*", false)) {
			if(inst.getInst().isBlackBox()) {
				blackBoxInsts.add(inst);
			}
		}
		return blackBoxInsts;
	}
	
	/**
	 * Reads a DCP and prints out hierarchical path to each black box cell instance found
	 * @param args
	 */
	public static void main(String[] args) {
		if(args.length < 1 || args.length > 2) {
			System.out.println("USAGE: <input.dcp> [input.edf]");
			return;
		}
		Design designWithBB = args.length == 1 ? 
				Design.readCheckpoint(args[0]) : Design.readCheckpoint(args[0], args[1]);
		
		for(EDIFHierCellInst inst : getBlackBoxes(designWithBB)) {
			System.out.println(inst.getFullHierarchicalInstName());
		}
	}
}
