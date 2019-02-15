package com.xilinx.rapidwright.examples;

import java.io.File;
import java.util.ArrayList;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;

public class PicoBlazeArray {

	public static void updateAnchorToBRAM(Module m){
		for(SiteInst i : m.getSiteInsts()){
			if(i.getSite().getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36){
				m.setAnchor(i);
				m.calculateAllValidPlacements(m.getDevice());
			}
		}
	}
	
	public static void main(String[] args) {
		Design design = new Design("top", "xcvu3p-ffvc1517-2-i");
		Device device = design.getDevice();
		EDIFNetlist netlist = design.getNetlist();
		
		String srcDir = args[0] + File.separator;
		
		int implementationCount = 3; 
		Module[] picoBlazeImpls = new Module[implementationCount];
		for(int i=0; i < implementationCount; i++){
			picoBlazeImpls[i] = new Module(Design.readCheckpoint(srcDir + "pblock"+i+".dcp"),srcDir + "pblock"+i+"_"+i+"_metadata.txt");
			updateAnchorToBRAM(picoBlazeImpls[i]);
			netlist.migrateCellAndSubCells(picoBlazeImpls[i].getNetlist().getTopCell());
		}
		
		int bramColumns = 12;
		int bramRows = design.getDevice().getNumOfClockRegionRows() * 12;
		
		for(int x=0; x < bramColumns; x++){
			// we will skip  top and bottom rows to avoid edge effects (U-turn routing)
			for(int y=1; y < bramRows-1; y++){ 
				Site bram = device.getSite("RAMB36_X" + x + "Y" + y);
				Module impl = null;
				for(Module m : picoBlazeImpls){
					if(m.isValidPlacement(bram, device, design)){
						impl = m;
						break;
					}
				}
				if(impl == null) continue; // Laguna site
				ModuleInst mi = design.createModuleInst("picoblaze_"+x+"_"+y, impl);
				mi.getCellInst().setCellType(impl.getNetlist().getTopCell());
				mi.place(bram);
			}
		}
		HandPlacer.openDesign(design);
		design.writeCheckpoint("picoBlazeArray.dcp");
	}
}
