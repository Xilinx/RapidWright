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
package com.xilinx.rapidwright.design.blocks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.Utils;
import com.xilinx.rapidwright.device.browser.PBlockGenEmitter;
import com.xilinx.rapidwright.device.helper.TileColumnPattern;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Attempts to estimate a fitting pblock for a given design.
 * 
 * Created on: Jun 15, 2015
 */
public class PBlockGenerator {

	public static final int LUTS_PER_CLE = 8;
	public static final int FF_PER_CLE = 16;
	public static final float CLES_PER_BRAM = 5;
	public static final float CLES_PER_DSP = 2.5f;
	
	/** X dimension over Y dimension */
	public float ASPECT_RATIO = 0.125f;//1.5f;	
	public float OVERHEAD_RATIO = 1.5f;//1.25f;
	public int STARTING_X = -1;	    // Parameterized with command line argument when present.
	public int STARTING_Y = -1;		// Parameterized with command line argument when present.
	public int PBLOCK_COUNT = 1;
	public int MAX_COLUMNS = 30;
	public String GLOBAL_PBLOCK = ""; 
	public int IP_NR_INSTANCES = 0;
	
	public static final int AVOID_NULL_COLUMN_COUNT = 2;
	
	public static final PBlockGenEmitter emitter = new PBlockGenEmitter();

	// Command line argument names
	public static final String UTILIZATION_REPORT_OPT = "u";
	public static final String SHAPES_REPORT_OPT      = "s";
	public static final String ASPECT_RATIO_OPT       = "a";
	public static final String OVERHEAD_RATIO_OPT     = "o";
	public static final String STARTING_X_OPT         = "x";
	public static final String STARTING_Y_OPT         = "y";
	public static final String COUNT_REQUEST_OPT      = "c";
	public static final String GLOBAL_PBLOCK_OPT 	  = "p";  //  File containig already implemented pblocks for free resources computation
	public static final String IP_NR_INSTANCES_OPT    = "i";	 
	Device dev = null;
	int lutCount = 0;
	int lutRAMCount = 0;
	int regCount = 0;
	int dspCount = 0;
	int carryCount = 0;
	int bram18kCount = 0;
	int bram36kCount = 0;
	int tallestShape = 0;
	int widestShape = 0;
	int shapeArea = 0;
	boolean horiz_density = true; // Select if horizontal density algorithm is desired
	Site startingPoint = null;

	String blockRangeSlice = null;
	String blockRangeDSP = null;
	String blockRangeRAMB = null;
	String blockRangeRAMB36 = null;
	
	/** Current direction of the spiral */
	enum Direction{up, down, left, right};

	/** Tiles Bounded */
	private HashSet<String> bounded;
	
	public static boolean debug = false;
	
	/**
	 * @return
	 */
	public PBlockGenEmitter getEmitter() {
		return emitter;
	}
	
	private void getResourceUsages(String reportFileName){
		ArrayList<String> lines = FileTools.getLinesFromTextFile(reportFileName);
		
		for(String line : lines){
			if(line.startsWith("| Device")){
				String partName = line.split("\\s+")[3];
				Part part = PartNameTools.getPart(partName);
				dev = Device.getDevice(part);
				if(dev == null){
					throw new RuntimeException("ERROR: Couldn't load device for part: " +
							line.split("\\s+")[3] + " (" +  partName + ")");
				}
			}else if(line.startsWith("| CLB LUTs") || line.startsWith("| Slice LUTs")){
				lutCount = Integer.parseInt(line.split("\\s+")[4]);
			}else if(line.startsWith("| CLB Registers") || line.startsWith("| Slice Registers")){
				regCount = Integer.parseInt(line.split("\\s+")[4]);				
			}else if(line.startsWith("|   LUT as Memory")){
				lutRAMCount = Integer.parseInt(line.split("\\s+")[5]);
			}else if(line.startsWith("|   RAMB36/FIFO")){
				bram36kCount = Integer.parseInt(line.split("\\s+")[3]);
			}else if(line.startsWith("|   RAMB18")){
				bram18kCount = Integer.parseInt(line.split("\\s+")[3]);
			}else if(line.startsWith("| DSPs")){
				dspCount = Integer.parseInt(line.split("\\s+")[3]);
			}else if(line.startsWith("| CARRY")){
				carryCount = Integer.parseInt(line.split("\\s+")[3]);
			}
		}
		
		if(debug){
			System.out.println("Parsed report: " + reportFileName);
			System.out.println("  Device: " + dev.getName());
			System.out.println("    LUTs: " + lutCount);
			System.out.println("    DSPs: " + dspCount);
			System.out.println("18kBRAMs: " + bram18kCount);
			System.out.println("36kBRAMs: " + bram36kCount);
		}
		if(lutCount < LUTS_PER_CLE) lutCount = LUTS_PER_CLE;
	}
	
	private void getTallestShape(String shapesReportFileName){
		
		try {
			BufferedReader br = new BufferedReader(new FileReader(shapesReportFileName));
			String line = null;
			int ffCount = 0;
			int lutCount = 0;
			int carryCount = 0;
			double fractionalShapeArea = 0.0;
			while((line = br.readLine()) != null){
				if(line.startsWith("WxH: ")){
					int pos = line.lastIndexOf('x');
					String[] parts = line.split("\\s+");
					int widthDim = Integer.parseInt(parts[1].substring(0, parts[1].indexOf('x')));
					int heightDim = Integer.parseInt(line.substring(pos+1));
					if(tallestShape < heightDim){
						tallestShape = heightDim;
					}
					if(widestShape < widthDim){
						widestShape = widthDim;
					}
					
					double sliceUsage = Math.max(((double)lutCount)/LUTS_PER_CLE, ((double)ffCount)/FF_PER_CLE);
					sliceUsage = Math.max(sliceUsage, ((double)carryCount));
					fractionalShapeArea += (carryCount > 0 || lutCount > 0 || ffCount > 0) ? sliceUsage : widthDim * heightDim;
					
					lutCount = 0;
					ffCount = 0;
					carryCount = 0;
				}else if(line.startsWith("(SLICE")){
					if(line.contains("FF")) ffCount++;
					else if(line.contains("LUT")) lutCount++;
					else if(line.contains("CARRY")) carryCount++;
				}else if(line.contains("Shape builder is called from")){
					// It seems in some shape DB dumps, there is a stack trace followed by another, updated set of shapes.
					// If we see this, reset and start over
					tallestShape = 0;
					widestShape = 0;
					shapeArea = 0;
					fractionalShapeArea = 0.0;
					ffCount = 0;
					lutCount = 0;
					carryCount = 0; 
					continue;
				}
			}
			shapeArea = (int) fractionalShapeArea;
			br.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void calculateStartingPoint(){
		// TODO - Make this method more data-driven, robust
		if(STARTING_X < 0 || STARTING_Y < 0){
			if(dspCount > 0 || (bram18kCount > 0 || bram36kCount > 0)){
				if(dspCount > 0){
					this.startingPoint = dev.getSite("DSP48E2_X5Y55");
				}else{
					this.startingPoint = dev.getSite("RAMB36_X6Y32");
				}
			}else{
				this.startingPoint = dev.getSite("SLICE_X39Y149");
			}
		}else{
			this.startingPoint = dev.getSite("SLICE_X" + STARTING_X + "Y" + STARTING_Y);
		}
	}
		
	private ArrayList<String> createConstraints(){
		bounded = new HashSet<String>();
		int rStart = -1;
		int cStart = -1;
		
		if(startingPoint == null){
			MessageGenerator.briefErrorAndExit("PBlock Generator Error: Could not find a valid starting tile for constraint generation.");
		}else{
			rStart = startingPoint.getTile().getRow();
			cStart = startingPoint.getTile().getColumn();
		}
		
		int totalRows = dev.getRows();
		int totalColumns = dev.getColumns();
		FamilyType devArchitecture = PartNameTools.getPart(dev.getName()).getArchitecture();
		
		// algorithm spirals outward to fill needs
		int c = cStart;
		int r = rStart;
		int maxC = cStart;
		int maxR = rStart;
		int minC = cStart;
		int minR = rStart;
		Direction dir = Direction.down;
		
		int firstCLBColumn = -1;
		int DSPmaxC = -1;
		int DSPminC = -1;
		int DSPmaxR = -1;
		int DSPminR = -1;
		int RAMmaxC = -1;
		int RAMminC = -1;
		int RAMmaxR = -1;
		int RAMminR = -1;
		int CLBmaxC = -1;
		int CLBminC = -1;
		int CLBmaxR = -1;
		int CLBminR = -1;
		

		
		int slicesRequired = Math.round((((float)lutCount / (float)LUTS_PER_CLE) * OVERHEAD_RATIO) + 0.5f);
		if(regCount/FF_PER_CLE > lutCount/LUTS_PER_CLE){
			slicesRequired = Math.round((((float)regCount / (float)FF_PER_CLE) * OVERHEAD_RATIO) + 0.5f);
		}
		int debugSlicesRequired = slicesRequired;
		int DSPRequired = dspCount;
		int RAMRequired = bram18kCount;
		int RAM36Required = bram36kCount;
		int carryBlocks = tallestShape;
		int sliceMRequired = lutRAMCount / LUTS_PER_CLE;
		int nullColumnsOnLeft = 0;
		int nullColumnsOnRight = 0;
		
		while(slicesRequired > 0 || RAM36Required > 0 ||  RAMRequired > 0 || DSPRequired > 0 || carryBlocks > 0 || sliceMRequired > 0){
			// check if the position of the spiral is within the tile bounds.
			if(c >= 0 && r >=0 && r < totalRows && c < totalColumns){
				// check if the tile is a needed resource
				if(dev.getTile(r,c).getTileTypeEnum().equals(TileTypeEnum.DSP)){
					if(DSPRequired > 0){
						if(c > DSPmaxC){
							DSPmaxC = c;
						}
						if(c < DSPminC || DSPminC == -1){
							DSPminC = c;
						}
						if(r > DSPmaxR){
							DSPmaxR = r;
						}
						if(r < DSPminR || DSPminR == -1){
							DSPminR = r;
						}
					}
					DSPRequired--;
				}else if(dev.getTile(r,c).getTileTypeEnum().equals(TileTypeEnum.BRAM)){
					if(RAMRequired > 0 || RAM36Required > 0){
						if(c > RAMmaxC){
							RAMmaxC = c;
						}
						if(c < RAMminC || RAMminC == -1){
							RAMminC = c;
						}
						if(r > RAMmaxR){
							RAMmaxR = r;
						}
						if(r < RAMminR || RAMminR == -1){
							RAMminR = r;
						}
					}
					if(RAM36Required <= 0){
						RAMRequired-=2;
					}else if(RAM36Required > 0){
						RAM36Required--;
					}
					
				}else if(dev.getTile(r,c).getTileTypeEnum().equals(TileTypeEnum.CLE_M) || dev.getTile(r,c).getTileTypeEnum().equals(TileTypeEnum.CLEL_R) || dev.getTile(r,c).getTileTypeEnum().equals(TileTypeEnum.CLEL_L)){
					if(firstCLBColumn == -1){
						firstCLBColumn = c;
					}
					if(slicesRequired > 0 || sliceMRequired > 0){
						if(c > CLBmaxC){
							CLBmaxC = c;
						}
						if(c < CLBminC || CLBminC == -1){
							CLBminC = c;
						}
						if(r > CLBmaxR){
							CLBmaxR = r;
						}
						if(r < CLBminR || CLBminR == -1){
							CLBminR = r;
						}
					}else if(carryBlocks > 0){
						// if carryBits are still needed but slices have been satisfied, 
						// then only increase the bounds in the up or down direction
						if(r > CLBmaxR){
							CLBmaxR = r;
						}
						if(r < CLBminR || CLBminR == -1){
							CLBminR = r;
						}
					}
					if(devArchitecture.equals(FamilyType.KINTEXU)){
						slicesRequired-=1;
						if(c == firstCLBColumn){
							carryBlocks-=1;
						}
						if(dev.getTile(r,c).getTileTypeEnum().equals(TileTypeEnum.CLE_M)){
							sliceMRequired-=8;
						}
					}
				}
			
			// check to see if the maximum bounds of the spiral have exceeded the tile dimensions
			}else if(minR < 0 && minC < 0 && maxR >= totalRows && maxC >=totalColumns){
				MessageGenerator.briefErrorAndExit("PBlock Generator Error: Design is too large to be constrained on the device " + dev.getName());
			}
			
			// Let's keep tabs on how many NULL tile columns we are including
			
			if(r == rStart){
				if(dev.getTile(r,c).getTileTypeEnum() == TileTypeEnum.NULL){
					if(c == maxC){
						nullColumnsOnRight++;
					}else if(c == minC){
						nullColumnsOnLeft++;
					}
				}else{
					if(c == maxC){
						nullColumnsOnRight = 0;
					}else if(c == minC){
						nullColumnsOnLeft = 0;
					}
				}

			}
			
			if(debug) {
				System.out.printf("SLICEL %4.1f%% SLICEM %4.1f%% DSP %4.1f%% BRAM %4.1f%% %s\n",
						100.0*(debugSlicesRequired-slicesRequired)/((float)debugSlicesRequired),
						100.0*((lutRAMCount/LUTS_PER_CLE)-sliceMRequired)/((float)(lutRAMCount/LUTS_PER_CLE)),
						dspCount == 0 ? 100.0 : (100.0*(dspCount-DSPRequired)/((float)dspCount)),
						bram36kCount == 0 ? 100.0 : (100.0*(bram36kCount-RAM36Required)/((float)bram36kCount)),
						dev.getTile(r,c).getName()
						);
				emitter.emitTile(dev.getTile(r,c));
				try {
					Thread.sleep(25);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			//System.out.println("Target Aspect: " + aspectRatio + "\tActual Aspect: " + ((maxR-minR) > 0 ? (((double)(maxC - minC))/((double)(maxR - minR))) : "0"));
			
			//determines direction changing of the spiral taking into account the target aspect ratio
			if((maxR-minR) == 0){
				//first case
				dir = Direction.left;
				minR--;
				r--;
			}else if(dir == Direction.down){
				if(r == minR){
					if((((double)(maxC - minC))/((double)(maxR - minR))) > ASPECT_RATIO){
						//proceed to create a new row
						dir = Direction.left;
						minR--;
						r--;
					}else{
						if(nullColumnsOnLeft > AVOID_NULL_COLUMN_COUNT){
							// Create a column on the other side
							dir = Direction.down;
							maxC++;
							c = maxC;		
							r = maxR;
						}else{
							//do not create another row. instead, jump to the other side and make a new column
							dir = Direction.up;
							minC--;
							c = minC;							
						}
					}
				}else{
					r--;
				}
			}else if(dir == Direction.left){
				if(c == minC){
					if((((double)(maxC - minC))/((double)(maxR - minR))) > ASPECT_RATIO){
						//do not create another column.  instead, jump to the other side and make a new row.
						dir = Direction.right;
						maxR++;
						r=maxR;
					}else{
						if(nullColumnsOnLeft > AVOID_NULL_COLUMN_COUNT){
							// Create a column on the other side
							dir = Direction.down;
							maxC++;
							c = maxC;		
							r = maxR;
						}else{
							//proceed to create a new column
							dir = Direction.up;
							minC--;
							c--;							
						}
					}
				}else{
					c--;
				}			
			}else if(dir == Direction.up){
				if(r == maxR){
					if((((double)(maxC - minC))/((double)(maxR - minR))) > ASPECT_RATIO){
						//proceed to create a new row
						dir = Direction.right;
						maxR++;
						r++;
					}else{
						if(nullColumnsOnRight > AVOID_NULL_COLUMN_COUNT){
							// We are up against NULL tiles, make another column on this side
							dir = Direction.up;
							minC--;
							c = minC;	
							r = minR;
						}else{
							//do not create a new row, instead, jump to the other side and make a new column
							dir = Direction.down;
							maxC++;
							c = maxC;							
						}
					}
				}else{
					r++;
				}	
			}else if(dir == Direction.right){
				if(c == maxC){
					if((((double)(maxC - minC))/((double)(maxR - minR))) > ASPECT_RATIO){
						//do not create another column.  instead, jump to the other side and make a new row.
						dir = Direction.left;
						minR--;
						r=minR;
					}else{
						if(nullColumnsOnRight > AVOID_NULL_COLUMN_COUNT) {
							// We are up against NULL tiles, make another column on this side
							dir = Direction.up;
							minC--;
							c = minC;	
							r = minR;
						}else {
							dir = Direction.down;
							maxC++;
							c = maxC;							
						}
					}
				}else{
					c++;
				}
			}
			
			
		}
		

		// now that all of the needed tiles have bounded by boxes,
		// translate the tile bounds to primitive sites bounds so 
		// they can be used as bounds in an AREA_GROUP.
		// it is important to keep in mind that the 0,0 tile in
		// the fpga fabric is the 0,0 tile in memory.  Make sure
		// your Y/Row dimension is oriented correctly.
		int xMin = 0;
		int yMin = 0;
		int xMax = 0;
		int yMax = 0;
		
		int numSLICEColumns = 0;
		int numDSPColumns = 0;
		int numBRAMColumns = 0;
		
		int numSLICERows = 0;
		int numDSPRows = 0;
		int numBRAMRows = 0;
		
		// Find the SLICE bounds
		if(CLBmaxC > -1){
			// get a first primitive to compare the others to
			Site slice = dev.getTile(CLBmaxR,CLBminC).getSites()[0];
			xMin = slice.getInstanceX();
			yMin = slice.getInstanceY();
			
			// get the upper right corner
			slice = dev.getTile(CLBminR,CLBmaxC).getSites()[0];
			xMax = slice.getInstanceX();
			yMax = slice.getInstanceY();
						
			//add the tiles to the bounded set
			for(int i = CLBminR; i <= CLBmaxR; i++){
				for(int j = CLBminC; j <= CLBmaxC; j++){
					bounded.add(dev.getTile(i, j).getName());
				}
			}
			
			numSLICEColumns = (xMax - xMin) + 1;
			numSLICERows = (yMax - yMin) + 1;
			
			// construct the SLICE AREA_GROUP constraint
			blockRangeSlice = "SLICE_X" + Integer.toString(xMin) + "Y" + Integer.toString(yMin) + 
			                 ":SLICE_X" + Integer.toString(xMax) + "Y" + Integer.toString(yMax);
		}
		
		
		// Find the DSP bounds
		if(DSPmaxC > -1){		
			xMin = Integer.MAX_VALUE;
			yMin = Integer.MAX_VALUE;
			xMax = 0;
			yMax = 0;
			// get the lower left corner
			Site[] dsps = dev.getTile(DSPmaxR,DSPminC).getSites();
			for(Site dsp : dsps){
				if(!dsp.getSiteTypeEnum().equals(SiteTypeEnum.DSP48E2)) continue;
				if(xMin > dsp.getInstanceX()) xMin = dsp.getInstanceX();
				if(yMin > dsp.getInstanceY()) yMin = dsp.getInstanceY();
			}

			// get the upper right corner
			dsps = dev.getTile(DSPminR,DSPmaxC).getSites();
			for(Site dsp : dsps){
				if(!dsp.getSiteTypeEnum().equals(SiteTypeEnum.DSP48E2)) continue;
				if(xMax < dsp.getInstanceX()) xMax = dsp.getInstanceX();
				if(yMax < dsp.getInstanceY()) yMax = dsp.getInstanceY();
			}
						
			//add the tiles to the bounded set
			for(int i = DSPminR; i <= DSPmaxR; i++){
				for(int j = DSPminC; j <= DSPmaxC; j++){
					bounded.add(dev.getTile(i, j).getName());
				}
			}
			
			numDSPColumns = (xMax - xMin) + 1;
			numDSPRows = (yMax - yMin) + 1;
			
			blockRangeDSP = "DSP48E2_X" + Integer.toString(xMin) + "Y" + Integer.toString(yMin) + 
						   ":DSP48E2_X" + Integer.toString(xMax) + "Y" + Integer.toString(yMax);
		
		}
				
		// Find the RAMB36 bounds
		if(RAMmaxC > -1){
			xMin = Integer.MAX_VALUE;
			yMin = Integer.MAX_VALUE;
			xMax = 0;
			yMax = 0;
			// get the lower left corner
			for(Site s: dev.getTile(RAMmaxR,RAMminC).getSites()){
				if(!s.getSiteTypeEnum().equals(SiteTypeEnum.RAMBFIFO36)) continue;
				if(xMin > s.getInstanceX()) xMin = s.getInstanceX();
				if(yMin > s.getInstanceY()) yMin = s.getInstanceY();
			}
			
			// get the upper right corner
			for(Site s: dev.getTile(RAMminR,RAMmaxC).getSites()){
				if(!s.getSiteTypeEnum().equals(SiteTypeEnum.RAMBFIFO36)) continue;
				if(xMax < s.getInstanceX()) xMax = s.getInstanceX();
				if(yMax < s.getInstanceY()) yMax = s.getInstanceY();
			}
			
			//add the tiles to the bounded set
			for(int i = RAMminR; i <= RAMmaxR; i++){
				for(int j = RAMminC; j <= RAMmaxC; j++){
					bounded.add(dev.getTile(i, j).getName());
				}
			}
			blockRangeRAMB36 = "RAMB36_X" + Integer.toString(xMin) + "Y" + Integer.toString(yMin) + 
							  ":RAMB36_X" + Integer.toString(xMax) + "Y" + Integer.toString(yMax);
			
			numBRAMColumns = (xMax - xMin) + 1;
			numBRAMRows = (yMax - yMin) + 1;
		}

		// Now, let's optimize the pBlock, move it to a compatible column with the best place-ability
		int commonRow = TileColumnPattern.getCommonRow(dev);
		int numSLICEMColumns = 0;
		if(sliceMRequired > 0){
			for(int x= CLBminC; x <= CLBmaxC; x++){
				TileTypeEnum t = dev.getTile(commonRow, x).getTileTypeEnum();
				if(Utils.isCLBM(t)){
					numSLICEMColumns++;
				}
			}
		}
		
		HashMap<TileColumnPattern, TreeSet<Integer>> patMap = TileColumnPattern.genColumnPatternMap(dev);
		ArrayList<TileColumnPattern> matches = getCompatiblePatterns(numSLICEColumns, numSLICEMColumns, numDSPColumns, numBRAMColumns, patMap);
		
		boolean trivial = (matches.size() > 0) && matches.get(0).size() < 2;;
		ArrayList<String> pBlocks = new ArrayList<String>(PBLOCK_COUNT);
		for(TileColumnPattern p : matches){
			int col = patMap.get(p).iterator().next();
			int row = TileColumnPattern.getCommonRow(dev);
			Site upperLeft = null;
			StringBuilder sb = new StringBuilder();
			if(numSLICEColumns > 0){
				int pIdx = 0;
				for(int i=0; pIdx < p.size(); i++){
					TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
					if(Utils.isCLB(t)){
						upperLeft = dev.getTile(row - 4 /* TODO - Make Data Driven*/, col+i).getSites()[0];
						break;
					}
					if(p.get(pIdx) == t) pIdx++;
				}
				sb.append("SLICE_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numSLICERows-1)) + 
						 ":SLICE_X" + (upperLeft.getInstanceX()+numSLICEColumns-1) + "Y" + upperLeft.getInstanceY());
			}
			if(numBRAMColumns > 0){
				int pIdx = 0;
				for(int i=0; pIdx < p.size(); i++){
					TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
					if(Utils.isBRAM(t)){
						for(Site s : dev.getTile(row, col+i).getSites()){
							if(s.getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36) upperLeft = s;
						}
						break;
					}
					if(p.get(pIdx) == t) pIdx++;
				}
				sb.append(" RAMB36_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numBRAMRows-1)) + 
						 ":RAMB36_X" + (upperLeft.getInstanceX()+numBRAMColumns-1) + "Y" + upperLeft.getInstanceY());
			}
			if(numDSPColumns > 0){
				int pIdx = 0;
				for(int i=0; pIdx < p.size(); i++){
					TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
					if(Utils.isDSP(t)){
						upperLeft = dev.getTile(row, col+i).getSites()[0];
						break;
					}
					if(p.get(pIdx) == t) pIdx++;
				}
				sb.append(" DSP48E2_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numDSPRows-1)) + 
						 ":DSP48E2_X" + (upperLeft.getInstanceX()+numDSPColumns-1) + "Y" + upperLeft.getInstanceY());
				
			}
			pBlocks.add(sb.toString());
			if(trivial) break;
		}
		return pBlocks;
	}
	
	private ArrayList<TileColumnPattern> getCompatiblePatterns(int sliceColumns, int slicemColumns, int dspColumns, int bramColumns, HashMap<TileColumnPattern, TreeSet<Integer>> patMap){
		TileColumnPattern[] sortedPatterns = TileColumnPattern.getSortedMostCommonPatterns(patMap);
		ArrayList<TileColumnPattern> matches = new ArrayList<TileColumnPattern>();
		for(TileColumnPattern p : sortedPatterns){
			int slices = sliceColumns;
			int slicems = slicemColumns;
			int dsps = dspColumns;
			int brams = bramColumns; 
			
			for(TileTypeEnum t : p){
				if(slicems > 0 && Utils.isCLBM(t)){
					slicems--;
				}else if(Utils.isCLB(t)){
					slices--;
				}else if(Utils.isDSP(t)){
					dsps--;
				}else if(Utils.isBRAM(t)){
					brams--;
				}
			}
			
			if(slices <= 0 && slicems <= 0 && dsps <= 0 && brams <= 0){
				matches.add(p);
			}
		}	
		return matches;
	}
	
	public ArrayList<String> generatePBlockFromReport2(String reportFileName, String shapesReportFileName){
		getResourceUsages(reportFileName);
		getTallestShape(shapesReportFileName);
		calculateStartingPoint();
		return createConstraints();
		
		/*StringBuilder sb = new StringBuilder();
		if(blockRangeSlice != null) sb.append(blockRangeSlice);
		if(blockRangeDSP != null) sb.append(" " + blockRangeDSP);
		if(blockRangeRAMB != null) sb.append(" " + blockRangeRAMB);
		if(blockRangeRAMB36 != null) sb.append(" " + blockRangeRAMB36);
		return sb.toString();*/
	}
	
	public static void main_testing(String[] args) {
		PBlockGenerator pb = new PBlockGenerator();
		int[] luts = {0, 1, 8, 16, 17, 128, 511};
		int[] ffs = {0, 15, 31, 64, 120};
		int[] lutRAMs = {0, 1, 7, 8, 15, 17, 56};
		int[] brams = {0, 1, 2, 4, 7, 13, 20};
		int[] dsps = {0, 1, 2, 3, 4, 8, 20};
		
		for(int lut : luts){
			for(int lutRAM : lutRAMs){
				for(int ff : ffs){
					pb.lutCount = lut;
					pb.regCount = ff;
					pb.lutRAMCount = lutRAM;
					pb.bram36kCount = 0;
					pb.dspCount = 0;
					pb.generatePBlockFromReport2("", "");					
				}
			}
		}
		
		for(int bram : brams){
			pb.lutCount = 0;
			pb.regCount = 0;
			pb.lutRAMCount = 0;
			pb.bram36kCount = bram;
			pb.dspCount = 0;
			pb.generatePBlockFromReport2("", "");
		}
		for(int dsp : dsps){
			pb.lutCount = 0;
			pb.regCount = 0;
			pb.lutRAMCount = 0;
			pb.bram36kCount = 0;
			pb.dspCount = dsp;
			pb.generatePBlockFromReport2("", "");
		}

	}
	
	public ArrayList<String> generatePBlockFromReport(String reportFileName, String shapesReportFileName){
		getResourceUsages(reportFileName);
		getTallestShape(shapesReportFileName);
		
		// Let's calculate exactly how many sites we need of each type
		int slicesRequired = Math.round((((float)lutCount / (float)LUTS_PER_CLE) * OVERHEAD_RATIO) + 0.5f);
		if(regCount/FF_PER_CLE > lutCount/LUTS_PER_CLE){
			slicesRequired = Math.round((((float)regCount / (float)FF_PER_CLE) * OVERHEAD_RATIO) + 0.5f);
		}
		if(carryCount > slicesRequired){
			slicesRequired = (int)((float)carryCount*OVERHEAD_RATIO);
		}
		int dspsRequired = dspCount;
		if((dspsRequired & 0x1) == 0x1){
			// if we have an odd number of dsp, round up by 1 more
			dspsRequired++;
		} 
		int ramb36sRequired = bram36kCount + (int)Math.ceil((float)bram18kCount / 2.0);
		int sliceMsRequired = (int)Math.ceil((float)lutRAMCount / (float)LUTS_PER_CLE);
		
		int pblockCLEHeight = 0;
		//Figure out how tall we need to make the pblock
		//Make DSPs and BRAMs upto as tall as a region before creating a new column
		int dspCLECount = (int) Math.ceil(dspsRequired * CLES_PER_DSP);
		int bramCLECount = (int) Math.ceil(ramb36sRequired * CLES_PER_BRAM);
		if(dspCLECount == 0 && bramCLECount == 0){
			// ASPECT_RATIO = width(X) / height(Y)
			// width = ASPECT_RATIO * height
			// AREA (slices required) = width * height
			// AREA = ASPECT_RATIO * height^2
			// height = sqrt ( AREA / height )
			if(slicesRequired > sliceMsRequired){
				pblockCLEHeight = (int) Math.ceil(Math.sqrt(slicesRequired/ASPECT_RATIO));
			}else{
				pblockCLEHeight = (int) Math.ceil(Math.sqrt(sliceMsRequired/ASPECT_RATIO));
			}
		}else if(dspCLECount > bramCLECount){
			pblockCLEHeight = dspCLECount;
		}else {
			pblockCLEHeight = bramCLECount;
		}

		// Sanity check on height with respect to SLICE needs
		//      w
		//  |-------|           Area         = A (Math.max(slicesRequired,sliceMsRequired))
		//  |       |           Width        = w 
		//  |   A   | h         Height       = h
		//  |       |           Aspect Ratio = r (ASPECT_RATIO)
		//  |-------|
		//                      A = w * h;  w = h * r
		//                      A = (h * r) * h
		//                      A = h^2 * r
		//                      h = sqrt(A / r)
		//
		int checkedCLEHeight = (int) Math.ceil(Math.sqrt(((double) Math.max(slicesRequired,sliceMsRequired)) / ASPECT_RATIO));
		if(pblockCLEHeight < checkedCLEHeight){
			pblockCLEHeight = checkedCLEHeight;
		}
		int cleHeight = dev.getSeries().getCLEHeight();
		if(pblockCLEHeight > cleHeight){
			// If we are taller than a region, let's just stop at region height
			pblockCLEHeight = cleHeight;
			// TODO - In the future we can optimize this for larger blocks if needed
		}
		
		
		
		// Now, given an aspect ratio, we know how many columns of each we'll need
		int numSLICEColumns = (int) Math.ceil((float)slicesRequired / pblockCLEHeight);
		numSLICEColumns = (numSLICEColumns > 0) ? numSLICEColumns : 1;
		
		int numSLICEMColumns = (int) Math.ceil((float)sliceMsRequired / pblockCLEHeight);
		numSLICEMColumns = (sliceMsRequired > 0 && numSLICEMColumns == 0) ? 1 : numSLICEMColumns;
		
		int numBRAMColumns = (int) Math.ceil(((float)ramb36sRequired * CLES_PER_BRAM) / pblockCLEHeight);;
		numBRAMColumns = (ramb36sRequired > 0 && numBRAMColumns == 0) ? 1 : numBRAMColumns;
		
		int numDSPColumns = (int) Math.ceil(((float)dspsRequired * CLES_PER_DSP) / pblockCLEHeight);
		numDSPColumns = (dspsRequired > 0 && numDSPColumns == 0) ? 1 : numDSPColumns;

		// Let's trim back some of the height on SLICE pblocks if we can
		if(dspCLECount == 0 && bramCLECount == 0){
			int areaSLICEExcess = (numSLICEColumns * pblockCLEHeight) - slicesRequired;
			int areaSLICEMExcess = numSLICEMColumns * pblockCLEHeight - sliceMsRequired;
			
			int sliceExcessHeight = numSLICEColumns > 0 ? areaSLICEExcess / numSLICEColumns : 0; 
			int sliceMExcessHeight = numSLICEMColumns > 0 ? areaSLICEMExcess / numSLICEMColumns : 0;
			
			if(numSLICEColumns > 0 && numSLICEMColumns == 0){
				pblockCLEHeight -= sliceExcessHeight;
			}else if(numSLICEMColumns > 0 && numSLICEColumns == 0){
				pblockCLEHeight -= sliceMExcessHeight;
			}else if(numSLICEColumns > 0 && numSLICEMColumns > 0){
				pblockCLEHeight -= Integer.min(sliceExcessHeight, sliceMExcessHeight);
				// In the case where we have both SLICEL and SLICEM, extra SLICEM spots
				// can absorb some SLICELs, further reducing height
				areaSLICEMExcess = numSLICEMColumns * pblockCLEHeight - sliceMsRequired;
				int excessHeight = areaSLICEMExcess / (numSLICEColumns + numSLICEMColumns);
				if(excessHeight > 0){
					pblockCLEHeight -= excessHeight;
				}
			}
		}
		
		// Fail safe in case we get too short, make sure shapes (carry chains,etc) can fit 
		if(tallestShape > pblockCLEHeight){
			pblockCLEHeight = tallestShape;
		}
		if(widestShape > (numSLICEColumns+numSLICEMColumns)){
			int extra = numSLICEColumns+numSLICEMColumns - widestShape;
			numSLICEColumns = numSLICEColumns + extra;
		}
		int pblockArea = (numSLICEColumns+numSLICEMColumns) * pblockCLEHeight;
		if(shapeArea > pblockArea){
			// Let's choose to make the pblock taller rather than wider
			double areaShortage = shapeArea - pblockArea;
			int increaseHeightBy = (int) Math.ceil(areaShortage  / (numSLICEColumns+numSLICEMColumns));
			pblockCLEHeight += increaseHeightBy;
		}
		
		
		int numSLICERows = pblockCLEHeight;
		int numDSPRows = (int) (pblockCLEHeight == cleHeight ? (cleHeight / CLES_PER_DSP) : dspsRequired);
		int numBRAMRows = (int) (pblockCLEHeight == cleHeight ? (cleHeight / CLES_PER_BRAM) : ramb36sRequired);
		
		HashMap<TileColumnPattern, TreeSet<Integer>> patMap = TileColumnPattern.genColumnPatternMap(dev);
		ArrayList<TileColumnPattern> matches = getCompatiblePatterns(numSLICEColumns, numSLICEMColumns, numDSPColumns, numBRAMColumns, patMap);
		if(matches.size() == 0){
			throw new RuntimeException("ERROR: PBlockGenerator couldn't match a compatible pattern with numSLICECols=" + numSLICEColumns +
					" numSLICEMCols=" + numSLICEMColumns + " numDSPColumns="+numDSPColumns + " numBRAMColumns=" + numBRAMColumns);
		}
		boolean trivial = matches.get(0).size() < 2;
		ArrayList<String> pBlocks = new ArrayList<String>(PBLOCK_COUNT);
		
		// Code inserted to obtain the pblock pattern having the highest number of free resources on the device
		boolean do_horiz_dens = !GLOBAL_PBLOCK.contentEquals("");
		TreeMap<Float,TileColumnPattern> store_best_pattern =new TreeMap<Float,TileColumnPattern> () ; // Store an ordered list of the patterns. The order is given by the number of free resources
		if(do_horiz_dens) {
			// Store pblocks already implemented for other IPs. Parse the file only once.
			HashMap<Integer, Integer> x_l = new HashMap<Integer, Integer>();
			HashMap<Integer, Integer> x_r = new HashMap<Integer, Integer>();
			HashMap<Integer, Integer> y_d = new HashMap<Integer, Integer>();
			HashMap<Integer, Integer> y_u = new HashMap<Integer, Integer>();
			HashMap<Integer, Integer> nr_inst = new HashMap<Integer, Integer>();
			get_already_impl_PBlocks(x_l,x_r,y_d,y_u,nr_inst);
			
			// Order the patterns according to the available resources			 
			for(TileColumnPattern p : matches){				
				HashMap<Integer, Integer []> clb_pblock = new HashMap<Integer, Integer []> ();
				create_allCLB_PBlocks (clb_pblock,patMap,p, numSLICEColumns, numSLICEMColumns,numBRAMColumns,numDSPColumns,numSLICERows); // Generate all the pblocks for this pattern (pattern may repeat on the device). Use this to compute free resources using the command bellow
				float free_resources = (float) check_free_resources(clb_pblock,x_l,x_r,y_u,y_d,nr_inst); // nr of resources is an integer value, but convert it to float to be able to have more keys for the same amount of free resources
				while (store_best_pattern.containsKey(free_resources)) { 	// if there is another pattern with the same nr of free resources, this shall be less important as the previous one, because matches is also ordered. 
					free_resources -= (float)1/matches.size();  			// there are max matches.size() elems that could have the same nr of resources. of course, this worst case scenario of having this nr of equal keys never happens, but better as random value like -0.01
				}
				store_best_pattern.put(free_resources, p);				
			}
			// Select the patterns with most available resources and only the amount requested by the user
			int nr_added_patterns = 1;
			for(float key : store_best_pattern.descendingKeySet()){ // descending order = start with the ones with most free resources
				TileColumnPattern p = store_best_pattern.get(key);
				Iterator<Integer> patternInstancesItr = patMap.get(p).iterator();
				int col = patternInstancesItr.next();
				if(patternInstancesItr.hasNext()){
					// If there are two instances, choose the second one to avoid edge effects
					col = patternInstancesItr.next();
				}
				int row = TileColumnPattern.getCommonRow(dev);
				// Let's go down by one region to avoid edge effects
				row = getTileRowInRegionBelow(col, row);
				Site upperLeft = null;
				StringBuilder sb = new StringBuilder();
				
				// Create pblock for CLBs
				if(numSLICEColumns > 0 || numSLICEMColumns > 0){
					HashMap<Integer, Integer []> clb_pblock = new HashMap<Integer, Integer []> ();
					create_allCLB_PBlocks (clb_pblock,patMap,p, numSLICEColumns, numSLICEMColumns,numBRAMColumns,numDSPColumns,numSLICERows);		// Re-generate pblock to write it in the file
					// clb_pblock value:  Integer[] {x_l,x_r,y_d,y_u}
					int LeftX   = clb_pblock.get(0)[0];
					int RightX  = clb_pblock.get(0)[1];
					int UpperY  = clb_pblock.get(0)[3];
					int LowerY  = clb_pblock.get(0)[2];

					sb.append("SLICE_X" + LeftX + "Y" + LowerY + ":SLICE_X" + RightX + "Y" + UpperY);
					
					// Write PBlock in Global PBlock file, so that the next IPs will try to use other columns if free
					// Current solution works actually if only 1 pblock is used for the implementation of the IP. If more are used, this won't give an accurate value
					// Also, current solution computes free resources only in case of CLB pblocks. The BRAMS and DRAMs columns are selected to match the most suitable clb pblock
					List<String> Write_PBlocks = new ArrayList<String>();
					for (Integer i: clb_pblock.keySet()) {
						Write_PBlocks.add("SLICE_X" + clb_pblock.get(i)[0] + "Y" + clb_pblock.get(i)[2] + ":SLICE_X" + clb_pblock.get(i)[1] + "Y" + clb_pblock.get(i)[3]);			
					} 
					if(IP_NR_INSTANCES==0) {
						System.out.println(" CRITICAL WARNING: IP_NR_INSTANCES is 0! Default value 1 was set.");
						IP_NR_INSTANCES = 1;
					}
					try (FileWriter fw = new FileWriter(GLOBAL_PBLOCK, true); // append to file
						    BufferedWriter bw = new BufferedWriter(fw);
						    PrintWriter out = new PrintWriter(bw)) {
							int nr_instances = (int) Math.ceil((double)IP_NR_INSTANCES / Write_PBlocks.size()); // distribute instances over number of pblocks of this pattern
							for(int string_nr = 0; string_nr<Write_PBlocks.size();string_nr++)
								out.println(Write_PBlocks.get(string_nr)+" "+nr_instances);										
					} catch (IOException e) {
						System.out.println("Problem appending all the pblocks to the " + GLOBAL_PBLOCK +" file");
					}
				
				}
				if(numBRAMColumns > 0){
					int pIdx = 0;
					for(int i=0; pIdx < p.size(); i++){
						TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
						if(Utils.isBRAM(t)){
							for(Site s : dev.getTile(row, col+i).getSites()){
								if((s.getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36)||(s.getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36E1)) upperLeft = s; // Update. Goal: support for 7 series
							}
							break;
						}
						if(p.get(pIdx) == t) pIdx++;
					}
					sb.append(" RAMB36_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numBRAMRows-1)) + 
							 ":RAMB36_X" + (upperLeft.getInstanceX()+numBRAMColumns-1) + "Y" + upperLeft.getInstanceY());
				}
				if(numDSPColumns > 0){
					int pIdx = 0;
					for(int i=0; pIdx < p.size(); i++){
						TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
						if(Utils.isDSP(t)){
							upperLeft = dev.getTile(row, col+i).getSites()[1];
							break;
						}
						if(p.get(pIdx) == t) pIdx++;
					}
					sb.append(" DSP48E2_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numDSPRows-1)) + 
							 ":DSP48E2_X" + (upperLeft.getInstanceX()+numDSPColumns-1) + "Y" + upperLeft.getInstanceY());
					
				}
				pBlocks.add(sb.toString());
				if(nr_added_patterns == PBLOCK_COUNT) break;
				nr_added_patterns++;
			}
		}
		// Code in case horizontal density algorithm not desired
		for(TileColumnPattern p : matches){
			Iterator<Integer> patternInstancesItr = patMap.get(p).iterator();
			int col = patternInstancesItr.next();
			if(patternInstancesItr.hasNext()){
				// If there are two instances, choose the second one to avoid edge effects
				col = patternInstancesItr.next();
			}
			int row = TileColumnPattern.getCommonRow(dev);
			// Let's go down by one region to avoid edge effects
			row = getTileRowInRegionBelow(col, row);
			Site upperLeft = null;
			StringBuilder sb = new StringBuilder();
			if(numSLICEColumns > 0 || numSLICEMColumns > 0){
				int pIdx = 0;
				for(int i=0; pIdx < p.size(); i++){
					TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
					if(Utils.isCLB(t)){
						upperLeft = dev.getTile(row - 4 /* TODO - Make Data Driven*/, col+i).getSites()[0];
						break;
					}
					if(p.get(pIdx) == t) pIdx++;
				}
				sb.append("SLICE_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numSLICERows-1)) + 
						 ":SLICE_X" + (upperLeft.getInstanceX()+(numSLICEColumns+numSLICEMColumns)-1) + "Y" + upperLeft.getInstanceY());
            }
			if(numBRAMColumns > 0){
				int pIdx = 0;
				for(int i=0; pIdx < p.size(); i++){
					TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
					if(Utils.isBRAM(t)){
						for(Site s : dev.getTile(row, col+i).getSites()){
							if((s.getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36)||(s.getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36E1)) upperLeft = s; // Update. Goal: support for 7 series
						}
						break;
					}
					if(p.get(pIdx) == t) pIdx++;
				}
				sb.append(" RAMB36_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numBRAMRows-1)) + 
						 ":RAMB36_X" + (upperLeft.getInstanceX()+numBRAMColumns-1) + "Y" + upperLeft.getInstanceY());
			}
			if(numDSPColumns > 0){
				int pIdx = 0;
				for(int i=0; pIdx < p.size(); i++){
					TileTypeEnum t = dev.getTile(row, col+i).getTileTypeEnum();
					if(Utils.isDSP(t)){
						upperLeft = dev.getTile(row, col+i).getSites()[1];
						break;
					}
					if(p.get(pIdx) == t) pIdx++;
				}
				sb.append(" DSP48E2_X" + upperLeft.getInstanceX() + "Y" + (upperLeft.getInstanceY()-(numDSPRows-1)) + 
						 ":DSP48E2_X" + (upperLeft.getInstanceX()+numDSPColumns-1) + "Y" + upperLeft.getInstanceY());
				
			}
			pBlocks.add(sb.toString());
			if(trivial) break;
		}
		return pBlocks;
	}
	
	private int getTileRowInRegionBelow(int col, int row){
		Tile tmp = dev.getTile(row, col);
		for(int c = 0; c < dev.getColumns(); c++){
			if(Utils.isCLB(dev.getTile(row, c).getTileTypeEnum())){
				tmp = dev.getTile(row, c);
			}
		}
		int cleHeight = dev.getSeries().getCLEHeight(); 
		
		int sliceX = tmp.getSites()[0].getInstanceX();
		int sliceY = tmp.getSites()[0].getInstanceY() - cleHeight;
		Site tmpSite = dev.getSite("SLICE_X" + sliceX + "Y" + sliceY);
		tmp = tmpSite.getTile();
		
		if(dev.getNumOfSLRs() > 1){
			SLR master = null;
			for(int i=0; i < dev.getNumOfSLRs(); i++){
				if(dev.getSLR(i).isMasterSLR()){
					master = dev.getSLR(i);
					break;
				}
			}
			
			// Relocate to master SLR if necessary
			int slrCLBHeight = (dev.getNumOfClockRegionRows() / dev.getNumOfSLRs()) * cleHeight;
			// If we're below master, add
			if(tmp.getRow() < master.getLowerRight().getRow()){
				while(tmp.getRow() < master.getLowerRight().getRow()){
					tmpSite = tmpSite.getNeighborSite(0, -slrCLBHeight);
					tmp = tmpSite.getTile();
				}
			} else { // Subtract
				while(tmp.getRow() > master.getUpperLeft().getRow()){
					tmpSite = tmpSite.getNeighborSite(0, slrCLBHeight);
					tmp = tmpSite.getTile();
				}
			}
		}
		row = tmp.getRow();		
		return row;
	}
	
	/**
	 * Some patterns use also BRAMs or DSPs, but the CLB pblock might end up far away from the BRAM/DSP column. 
	 * In the case described above, routing might become longer and use unnecessarily resources
	 * This function tries to overcome this issue. It is not an optimal solution, but is supposed to give better results as the default ones	
	 * @param p - TileColumnPattern of the pblock
	 * @param row 
	 * @param col
	 * @param numSLICEColumns - Number of requried CLB resources
	 * @param numSLICEMColumns - Number of requried CLBM resources
	 * @param numBRAMColumns - Number of requried BRAM resources
	 * @param numDSPColumns - Number of requried DSP resources
	 * @param for_clb - Input determines if the xl output must be a CLB
	 */
	private int get_xl_offset_pblock(TileColumnPattern p, int row, int col, int numSLICEColumns,int numSLICEMColumns, int numBRAMColumns,int numDSPColumns, boolean for_clb) {
		int xl_offset = 0;		// default value
		int req_numSLICEColumns  = numSLICEColumns;
		int req_numSLICEMColumns = numSLICEMColumns;
		int req_numBRAMColumns   = numBRAMColumns;
		int req_numDSPColumns    = numDSPColumns;
		int col_all_fulfilled;
		// Go through Pattern components and check at which column all required resources are fulfilled
		int pIdx = 0;
		for(col_all_fulfilled=0; pIdx < p.size(); col_all_fulfilled++){
			TileTypeEnum t = dev.getTile(row, col+col_all_fulfilled).getTileTypeEnum();
			
			if(Utils.isCLBM(t) && (req_numSLICEMColumns>0)){
				req_numSLICEMColumns--;
			}else if(Utils.isCLB(t)){
				req_numSLICEColumns--;
			}else if(Utils.isDSP(t)){
				req_numDSPColumns--;
			}else if(Utils.isBRAM(t)){
				req_numBRAMColumns--;
			}	
			if(req_numBRAMColumns <= 0 && req_numDSPColumns <= 0 && req_numSLICEMColumns <= 0 && req_numSLICEColumns <= 0) {
				break;
			}
			if(p.get(pIdx) == t) 
				pIdx++;
		}
		
		// Start from the column at which all resources were fulfilled. go back.
		// Check which is the most left column required to fulfil all our resources constraints
		req_numSLICEColumns  = numSLICEColumns;
		req_numSLICEMColumns = numSLICEMColumns;
		req_numBRAMColumns   = numBRAMColumns;
		req_numDSPColumns    = numDSPColumns;
		int col_offset;
		for(col_offset=0; pIdx >= 0; col_offset++){
			TileTypeEnum t = dev.getTile(row, col+col_all_fulfilled-col_offset).getTileTypeEnum();
			if(Utils.isCLBM(t) && (req_numSLICEMColumns>0)){
				req_numSLICEMColumns--;
				if((req_numSLICEColumns<=0) && (for_clb) && (req_numSLICEMColumns==0))	// This logic solves the task of returning a clb pblock offset only. Without it, if BRAM is the first in the pattern (with offset), BRAM column will be returned
					break;
			}else if(Utils.isCLB(t)){
				req_numSLICEColumns--;
				if((req_numSLICEMColumns<=0) && (for_clb) && (req_numSLICEColumns==0))
					break;
			}else if(Utils.isDSP(t)){
				req_numDSPColumns--;
			}else if(Utils.isBRAM(t)){
				req_numBRAMColumns--;
			}	
			if(req_numBRAMColumns <= 0 && req_numDSPColumns <= 0 && req_numSLICEMColumns <= 0 && req_numSLICEColumns <= 0) {
				break;
			}	
			if(p.get(pIdx) == t) 
				pIdx--;
		}
		xl_offset = col_all_fulfilled - col_offset; 
		return xl_offset;
	}
	
	/**
	 * Construct CLB PBlocks
	 * Create a map to store not only the first pblock, but all the pblocks for the other columns of a certain pattern. This is useful for example when determining whether the IP has enough space on the device.
	 * @param clb_pblock - Key is simply an integer to go over all patterns. 0 should always be the first one to be chosen in the main code, as it shall avoid edge effects. Value is an array holding all the pblocks for a given pattern
	 * @param patMap - Map holding all patterns of a device. 
	 * @param p - TileColumnPattern
	 * @param numSLICEColumns - Number of requried CLB resources
	 * @param numSLICEMColumns - Number of requried CLBM resources
	 * @param numBRAMColumns - Number of requried BRAM resources
	 * @param numDSPColumns - Number of requried DSP resources
	 * @param numSLICERows - Number of requried rows in the pblock
	 */
	private void create_allCLB_PBlocks (HashMap<Integer, Integer []> clb_pblock, HashMap<TileColumnPattern, TreeSet<Integer>> patMap, TileColumnPattern p, int numSLICEColumns, int numSLICEMColumns, int numBRAMColumns, int numDSPColumns, int numSLICERows) {
		int x_r;  // right column
		int x_l;  // left column
		int y_u;  // upper row
		int y_d;  // lower row
		
		boolean avoid_edge = false;
		Iterator<Integer> patternInstancesItr = patMap.get(p).iterator();
		int main_pblock_col = patternInstancesItr.next(); 
		if(patternInstancesItr.hasNext()){ 						// does it have a next pattern?
			avoid_edge = true; 									// If there are two instances, choose the second one to avoid edge effects		
		}
		int row = TileColumnPattern.getCommonRow(dev);
		row = getTileRowInRegionBelow(main_pblock_col, row);	// Let's go down by one region to avoid edge effects

		Site upperLeft = null;
		if(numSLICEColumns > 0 || numSLICEMColumns > 0){ 		// Generate CLB PBlocks only if slices required
			int run_nr = 0;
			while (patternInstancesItr.hasNext() || (run_nr==0) ) {
				if(run_nr!=0)
					main_pblock_col = patternInstancesItr.next(); 
				// Select the most left column that would give you a compact implemented design (as offset)
				int offset_col = get_xl_offset_pblock(p,row,main_pblock_col,numSLICEColumns,numSLICEMColumns,numBRAMColumns,numDSPColumns,true); 		// 'true' in order to get the value only for CLB offset			
				// Allign it to a tile
				if(dev.getTile(row, main_pblock_col+offset_col).toString().contains("_R"))  	// _L has slices | 1 | 0 |, while tile _R has slices | 0 | 1 |
					upperLeft = dev.getTile(row /* TODO - Make Data Driven*/, main_pblock_col+offset_col).getSites()[0];								// Get the site corresponding to these col & row			
				else 
					upperLeft = dev.getTile(row /* TODO - Make Data Driven*/, main_pblock_col+offset_col).getSites()[1];								// Get the site corresponding to these col & row
					
				y_u = upperLeft.getInstanceY();
				y_d = (y_u-(numSLICERows-1));
				x_l = upperLeft.getInstanceX();
				// Computing xr by simply adding (numSLICEColumns+numSLICEMColumns) is not enough for some patterns. 
				// If the pattern contains | clbl | clbl | clbl | clbm, but the pblock needs only 1 clbl, and 1 clbm, the generated pblock won't be correct. The code bellow addresses this issue
				int req_numSLICEMColumns = numSLICEMColumns;
				int req_numSLICEColumns = numSLICEColumns;			
				int i;		
				for(i=main_pblock_col+offset_col; i<dev.getColumns(); i++){ // i<dev.getColumns() was introduced, as we don't know anymore at this step the value of xl in the original pattern. 
					TileTypeEnum t = dev.getTile(row, i).getTileTypeEnum();
					if(Utils.isCLBM(t) && (req_numSLICEMColumns>0)){
						req_numSLICEMColumns--;
						if((req_numSLICEColumns<=0) && (req_numSLICEMColumns==0))
							break;
					}else if(Utils.isCLB(t)){
						req_numSLICEColumns--;
						if((req_numSLICEMColumns<=0) && (req_numSLICEColumns<=0))
							break;
					}
					
				}
				
				// Allign it to a tile
				if(dev.getTile(row, i).toString().contains("_R"))  	// _L has slices | 1 | 0 |, while tile _R has slices | 0 | 1 |
					x_r = dev.getTile(row, i).getSites()[1].getInstanceX();
				else 
					x_r = dev.getTile(row, i).getSites()[0].getInstanceX();
				
				// If this is the only pblock , simply add it. If not, the next elem shall be added with index 0, to avoid edge effects
				if(dev.getSite(upperLeft.getName())!=null && dev.getSite("SLICE_X"+x_r+"Y"+y_d)!=null ) {
					if(run_nr==0) {
						if(!avoid_edge) 
							clb_pblock.put(0, new Integer[] {x_l,x_r,y_d,y_u});
						 else 		
							clb_pblock.put(1, new Integer[] {x_l,x_r,y_d,y_u});	
					} else if((run_nr==1)&&(avoid_edge)) // actually run_nr==1 is enough, as avoid_edge is true in this case by default
						clb_pblock.put(0, new Integer[] {x_l,x_r,y_d,y_u});
					else 
						clb_pblock.put(run_nr, new Integer[] {x_l,x_r,y_d,y_u});
					run_nr++;
				}
			}
					
			if(!(clb_pblock.containsKey(0))) {			// if the next element was not a real one...copy value of key 1 into key 0
				for(int key : clb_pblock.keySet() ) {	// contains only 1 elem, probably key = 1. But to avoid special cases error, this 'for' was attached
					Integer[] val = clb_pblock.get(key);
					clb_pblock.remove(key);
					clb_pblock.put(0, val);
					break;
				}
			}
		} 
	}

	/**
	 * Function takes as input one pblock (multiple if pattern repeats) and checks whether this is already used by other IPs & How many free resources (= rows) are left  
	 * @param clb_pblock - Key is simply an integer to go over all patterns. 0 should always be the first one to be chosen in the main code, as it shall avoid edge effects. Value is an array holding all the pblocks for a given pattern
	 * @param x_l - Leftmost columns of the pblocks
	 * @param x_r - Rightmost columns of the pblocks
	 * @param y_u - Upper rows of the pblocks
	 * @param y_d - Lowest rows of the pblocks
	 * @param nr_inst - Number of IP instances
	 */
	public int check_free_resources (HashMap<Integer, Integer []> clb_pblock,HashMap<Integer, Integer> x_l,HashMap<Integer, Integer> x_r,HashMap<Integer, Integer> y_u,HashMap<Integer, Integer> y_d,HashMap<Integer, Integer> nr_inst) {
		int my_free_rows = dev.getRows();     				// Tricky, xc7z has less rows on the left side of the device. Also, nr_rows could be bigger than nr of rows having slices. This needs to be improved!
		int pattern_freq = clb_pblock.size(); 				// How often the pattern of the current pblock repeats on the device
		my_free_rows = my_free_rows*pattern_freq;			// My available resources = nr_rows * how often my pattern repeats
		boolean overlap = false;
		
		for (int i : x_l.keySet()) { 						// Go through all the pblocks in the global pblock file
			for(int my_pattern_col : clb_pblock.keySet()) { // Go through all my  pblocks. clb_pblock value:  Integer[] {x_l,x_r,y_d,y_u}
				overlap = false;
				if(  ((clb_pblock.get(my_pattern_col)[0]<=x_l.get(i)) && (clb_pblock.get(my_pattern_col)[1]>=x_l.get(i))) || ((clb_pblock.get(my_pattern_col)[0]<=x_r.get(i)) && (clb_pblock.get(my_pattern_col)[1]>=x_r.get(i))) || ((clb_pblock.get(my_pattern_col)[0]>=x_l.get(i)) && (clb_pblock.get(my_pattern_col)[1]<=x_r.get(i)))  )
					overlap = true;
				if(overlap) {
					my_free_rows -=  (y_u.get(i)-y_d.get(i)+1)*nr_inst.get(i);
					my_free_rows -= 10; 					// for each overlapp, add buffer between IPs 
				}
			}					
		}
		return my_free_rows;
	}
	
	/**
	 * Read PBlock File for obtaining already implemented pblocks  
	 * @param x_l  
	 * @param x_r  
	 * @param y_d
	 * @param y_u
	 * @param nr_inst
	 */
	public int get_already_impl_PBlocks ( HashMap<Integer, Integer> x_l, HashMap<Integer, Integer> x_r,HashMap<Integer, Integer> y_d, HashMap<Integer, Integer> y_u, HashMap<Integer, Integer> nr_inst ) {
		if(GLOBAL_PBLOCK.contentEquals("")) {
			MessageGenerator.briefErrorAndExit(" ERROR: Name of the PBlock file not given.");
		}
			
		ArrayList<String> lines = new ArrayList<String>();
		lines = FileTools.getLinesFromTextFile(GLOBAL_PBLOCK);	// File containig all the already implemented pblocks
		int line_nr = 0;
		for(String line : lines){
			if(line.contains("Failed"))
				continue;
			String[] blocks = line.split(" ");
			if(line.contains("SLICE")||line.contains("DSP")||line.contains("RAM"))
				nr_inst.put(line_nr, Integer.parseInt(blocks[blocks.length-1])); // last value in the text line shall be instance nr. of the corresponding IP	
			for (String block: blocks) {
				if(block.startsWith("SLICE")) {
					 String[] strs = block.split("[XY:]+");
					 if(strs.length!=6) {
						 //System.out.println("Error in parsing one of the PBlocks in the file, as not all (x_lest,x_right,y_left,y_right) are present ");						  
						 continue; // ignore it, don.t stop the tool
					 } else {
						 x_l.put(line_nr, (int) Integer.parseInt(strs[1]));
						 y_d.put(line_nr, (int) Integer.parseInt(strs[2]));
						 x_r.put(line_nr, (int) Integer.parseInt(strs[4]));
						 y_u.put(line_nr, (int) Integer.parseInt(strs[5]));						 												 
					 }	 
				}
			}
			line_nr++;
		}
		return line_nr;
	}
		
	public static void main(String[] args) {		
		OptionParser optParser = new OptionParser() {
			{
				accepts(UTILIZATION_REPORT_OPT).
					withRequiredArg().ofType(String.class).
					describedAs("Utilization Report File Name");
				accepts(SHAPES_REPORT_OPT).
					withRequiredArg().ofType(String.class).
					describedAs("Shapes Report File Name");
				accepts(ASPECT_RATIO_OPT)
					.withOptionalArg().ofType(Float.class).
					describedAs("PBlock Aspect Ratio Requested (Width/Height)");
				accepts(OVERHEAD_RATIO_OPT)
					.withOptionalArg().ofType(Float.class).
					describedAs("PBlock Overhead Ratio Requested (1.0 == No overhead)");
				accepts(COUNT_REQUEST_OPT)
					.withRequiredArg().ofType(int.class).
					describedAs("Number of different PBlocks to generate based on design data.");
				// Added by Bobby 4.15.16
				accepts(STARTING_X_OPT)
					.withOptionalArg().ofType(int.class).
					describedAs("PBlock X (SLICE) Axis Starting Point Requested.");
				accepts(STARTING_Y_OPT)
					.withOptionalArg().ofType(int.class).
					describedAs("PBlock Y (SLICE) Axis Starting Point Requested.");
				accepts(GLOBAL_PBLOCK_OPT)
					.withOptionalArg().ofType(String.class).
					describedAs("File containing already implemented PBlocks.");
				accepts(IP_NR_INSTANCES_OPT)
					.withOptionalArg().ofType(int.class).
					describedAs("Number of IP instances for this pblock.");
				acceptsAll( Arrays.asList("h", "?"), "Print Help" ).forHelp();
			}
		};
		OptionSet opts = optParser.parse(args);
		if(!(opts.hasArgument(UTILIZATION_REPORT_OPT) && opts.hasArgument(SHAPES_REPORT_OPT))){
			try {
				optParser.printHelpOn(System.out);
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.exit(1);
		}
		
		String fileName = (String) opts.valueOf(UTILIZATION_REPORT_OPT);
		String shapesReportFileName = (String) opts.valueOf(SHAPES_REPORT_OPT);
		PBlockGenerator pbGen = new PBlockGenerator();
		if(opts.has(ASPECT_RATIO_OPT)){
			pbGen.ASPECT_RATIO = (float) opts.valueOf(ASPECT_RATIO_OPT);
		}
		if(opts.has(OVERHEAD_RATIO_OPT)){
			pbGen.OVERHEAD_RATIO = (float) opts.valueOf(OVERHEAD_RATIO_OPT);
		}
		if(opts.has(COUNT_REQUEST_OPT)){
			pbGen.PBLOCK_COUNT = (int) opts.valueOf(COUNT_REQUEST_OPT);
		}
		// Added by Bobby 4.12.16
		if(opts.has(STARTING_X_OPT)){
			pbGen.STARTING_X = (int) opts.valueOf(STARTING_X_OPT);
		}
		if(opts.has(STARTING_Y_OPT)){
			pbGen.STARTING_Y = (int) opts.valueOf(STARTING_Y_OPT);
		}		

		if(opts.has(GLOBAL_PBLOCK_OPT)){
			pbGen.GLOBAL_PBLOCK = (String) opts.valueOf(GLOBAL_PBLOCK_OPT);
		}	
		
		if(opts.has(IP_NR_INSTANCES_OPT)){
			pbGen.IP_NR_INSTANCES = (int) opts.valueOf(IP_NR_INSTANCES_OPT);
		}
		
		HashSet<String> alreadySeen = new HashSet<String>();
		int requested = pbGen.PBLOCK_COUNT;
		for(String s : pbGen.generatePBlockFromReport(fileName, shapesReportFileName)){
			if(alreadySeen.contains(s)) continue;
			System.out.println(s);
			alreadySeen.add(s);
			requested--;
			if(requested == 0) break;
		}
	}
}