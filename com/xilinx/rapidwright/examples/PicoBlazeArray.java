package com.xilinx.rapidwright.examples;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.helper.TileColumnPattern;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;

public class PicoBlazeArray {

	private static final String CLK = "clk";
	private static final String RST = "reset";

	private static final String PBLOCK_DCP_PREFIX = "pblock";
	private static final String PICOBLAZE_PREFIX = "picoblaze_";
	private static final String TOP_INPUT_PREFIX = "top_input_";
	private static final String TOP_OUTPUT_PREFIX = "top_output_";

	private static final String[] PICOBLAZE_INPUTS = new String[]{
			"input_port_a","input_port_b", "input_port_c", "input_port_d"
		};
	private static final String[] PICOBLAZE_OUTPUTS = new String[]{
			"output_port_w","output_port_x", "output_port_y", "output_port_z"
		};
	private static final int[] CONN_ARRAY = new int[]{-2,-1,1,2};
	private static final int PICOBLAZE_BUS_WIDTH = 8;
	private static final int BRAMS_IN_CLOCK_REGION_HEIGHT = 12;
	
	/**
	 * To make it easier to specify placement, we change 
	 * the anchor to the BRAM instance 
	 * @param m The module whose anchor should be updated
	 */
	public static void updateAnchorToBRAM(Module m){
		for(SiteInst i : m.getSiteInsts()){
			if(i.getSite().getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36){
				m.setAnchor(i);
				m.calculateAllValidPlacements(m.getDevice());
			}
		}
	}
	
	/**
	 * Part of an example tutorial of how to build an array of picoblaze modules. 
	 * @param args
	 */
	public static void main(String[] args) {
		if(args.length != 3 && args.length != 4){
			System.out.println("USAGE: <pblock dcp directory> <part> <output_dcp> [--no_hand_placer]");
			return;
		}
		String srcDirName = args[0];
		File srcDir = new File(srcDirName);
		if(!srcDir.isDirectory()){
			System.err.println("ERROR: Couldn't read directory: " + srcDir);
			System.exit(-1);
		}
		CodePerfTracker t = new CodePerfTracker("PicoBlaze Array", true).start("Creating design");
		
		// Create a new design with references to device and netlist
		Design design = new Design("top", args[1]);
		Device device = design.getDevice();
		EDIFNetlist netlist = design.getNetlist();
		EDIFCell top = netlist.getTopCell();
		
		// Load pre-implemented modules
		FilenameFilter ff = FileTools.getFilenameFilter(PBLOCK_DCP_PREFIX+"[0-9]+.dcp");
		int implementationCount = srcDir.list(ff).length;
		Module[] picoBlazeImpls = new Module[implementationCount];
		for(int i=0; i < implementationCount; i++){
			String dcpName = srcDir + File.separator + PBLOCK_DCP_PREFIX+i+".dcp";
			String metaName = srcDir + File.separator + PBLOCK_DCP_PREFIX+i+"_"+i+"_metadata.txt";
			t.stop().start("Loading " + PBLOCK_DCP_PREFIX+i+".dcp");
			picoBlazeImpls[i] = new Module(Design.readCheckpoint(dcpName,CodePerfTracker.SILENT), metaName);
			updateAnchorToBRAM(picoBlazeImpls[i]);
			netlist.migrateCellAndSubCells(picoBlazeImpls[i].getNetlist().getTopCell());
		}
		
		t.stop().start("Place PicoBlaze modules");
		
		// Specify placement of picoblaze modules
		TileColumnPattern bramPattern = TileColumnPattern.createTileColumnPattern(Arrays.asList(TileTypeEnum.BRAM));
		int bramColumns = TileColumnPattern.genColumnPatternMap(device).get(bramPattern).size();
		int bramRows = design.getDevice().getNumOfClockRegionRows() * BRAMS_IN_CLOCK_REGION_HEIGHT;
		for(int x=0; x < bramColumns; x++){
			// we will skip top and bottom clock region rows to avoid laguna tiles and U-turn routing
			for(int y=BRAMS_IN_CLOCK_REGION_HEIGHT; y < bramRows-BRAMS_IN_CLOCK_REGION_HEIGHT; y++){ 
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
		
		t.stop().start("Stitch design");
		
		// Create clk and rst
		String bufgInstName = "bufgce_inst";
		SLRCrosserGenerator.createBUFGCE(design, CLK, CLK + "in", CLK + "out", bufgInstName);
		SLRCrosserGenerator.placeBUFGCE(design, device.getSite("BUFGCE_X0Y8"), bufgInstName);
		EDIFNet clk = top.getNet(CLK);
		top.createPort(RST, EDIFDirection.INPUT, 1);		
		
		// Connect pre-implemented modules together
		String busRange = "["+(PICOBLAZE_BUS_WIDTH-1)+":0]";
		for(int x=0; x < bramColumns; x++){
			top.createPort(TOP_INPUT_PREFIX + x + busRange, EDIFDirection.INPUT, PICOBLAZE_BUS_WIDTH);
			top.createPort(TOP_OUTPUT_PREFIX + x + busRange, EDIFDirection.OUTPUT, PICOBLAZE_BUS_WIDTH);
			// we will skip top and bottom clock region rows to avoid laguna tiles and U-turn routing
			for(int y=BRAMS_IN_CLOCK_REGION_HEIGHT; y < bramRows-BRAMS_IN_CLOCK_REGION_HEIGHT; y++){ 
				ModuleInst curr = getPicoblazeInst(design, x, y);
				if(curr==null) continue;
				
				clk.createPortInst(CLK, curr.getCellInst());
				curr.connect(RST, RST);
				
				for(int i=0; i < PICOBLAZE_BUS_WIDTH; i++){
					for(int j=0; j < CONN_ARRAY.length; j++){
						ModuleInst other = getPicoblazeInst(design, x, y+CONN_ARRAY[j]);
						if(other == null){
							curr.connect(PICOBLAZE_INPUTS [j], TOP_INPUT_PREFIX  + x, i);
							if(y == bramRows-BRAMS_IN_CLOCK_REGION_HEIGHT-1 && j==3){
								curr.connect(PICOBLAZE_OUTPUTS[j], TOP_OUTPUT_PREFIX + x, i);
							}
						}else{
							curr.connect(PICOBLAZE_INPUTS [j], other, PICOBLAZE_OUTPUTS[CONN_ARRAY.length-j-1], i);
						}						
					}
				}
			}
		}
		

		
		if(!(args.length == 4 && args[3].equals("--no_hand_placer"))){
			t.stop().start("Hand Placer");
			HandPlacer.openDesign(design);
		}
		
		t.stop().start("Write DCP");
		
		design.setAutoIOBuffers(false);
		design.addXDCConstraint("create_clock -name "+CLK+" -period 2.850 [get_nets "+CLK+"]");
		design.writeCheckpoint(args[2], CodePerfTracker.SILENT);
		t.stop().printSummary();
	}
	
	private static ModuleInst getPicoblazeInst(Design design, int x, int y){
		return design.getModuleInst(PICOBLAZE_PREFIX + x + "_" + y);
	}
}
