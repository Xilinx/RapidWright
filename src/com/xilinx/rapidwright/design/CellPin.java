package com.xilinx.rapidwright.design;

public class CellPin {

	private Cell cell;
	
	private String logicalPinName;

	public CellPin(Cell cell, String physicalPinName) {
		super();
		this.cell = cell;
		this.logicalPinName = physicalPinName;
	}

	public Cell getCell() {
		return cell;
	}

	public String getLogicalPinName() {
		return logicalPinName;
	}
}
