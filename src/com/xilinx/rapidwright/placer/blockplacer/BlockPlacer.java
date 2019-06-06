/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;

/**
 * Initial attempt at a pre-implemented module placer.  
 * It is more accurate in that it tries to traverse critical 
 * paths into both ends of the connections made between hard macros.
 * @author Chris Lavin
 */
public class BlockPlacer {
	/** The current design */
	private Design design;
	/** The current device being targeted by the design */
	private Device dev;
	/** A list of all the hard macros in the design */
	private ArrayList<HardMacro> hardMacros;
	/** A set of all the paths between hard macros in the design */
	private HashSet<Path> allPaths;
	/** A map to go from module instance to hard macro objects */
	private HashMap<ModuleInst, HardMacro> macroMap;
	/** The random number generator used throughout this class */
	private Random rand;
	/** The current move that is being evaluated */
	private Move currentMove;
	/** The current location of all hard macros */
	private HashMap<Site, HardMacro> currentPlacements;
	/** The current temperature of the simulated annealing schedule */
	private double currentTemp;
	/** Number of target accepted moves per temperature step */
	private int movesPerTemperature = 25;
	/** Number of accepted moves in the current temperature step */
	private int currentAcceptedMoveCount = 0;
	/** Total number of moves through the entire execution of the anneal */
	private int totalMoves;
	/** Measures the current move acceptance rate */
	private double moveAcceptanceRate = 1.0;
	/** */
	private double tempReduce = 0.90;
	/** */
	private double goldenRate = 0.44; 
	/** */
	private double END_ACCEPTANCE_RATE = 0.01;
	/** */
	private double startTempFactor = 10.5;
	/** */
	private long seed = 2;
	
	// Final Results
	/** */
	public double finalSystemCost;
	/** */
	public double finalBestCost;
	/** */
	public double placerRuntime;
	/** */
	public boolean verbose = true;
	/** A set of all TileTypes that have switch matrices in them */
	public static Set<TileTypeEnum> switchMatrixTypes;

	public static final int DEBUG_LEVEL = 1;
	// DEBUG
	//private HashMap<HardMacro, Integer> moveCount = new HashMap<HardMacro, Integer>();
	
	
	/** 
	 * Empty Constructor
	 * 
	 */
	public BlockPlacer(){
		
	}
	
	/**
	 * New Constructor
	 * @param movesPerTemperature Number of desired moves per temperature step
	 * @param seed Random seed to use
	 * @param tempReduceRate The multiplier to determine the next temperature
	 * @param startTempFactor Multiplied by the system cost to get the starting temperature
	 * @param verbose A flag to increase verbosity
	 */
	public BlockPlacer(int movesPerTemperature, long seed, double tempReduceRate, double startTempFactor, boolean verbose){
		this.movesPerTemperature = movesPerTemperature;
		setSeed(seed);
		this.tempReduce = tempReduceRate;
		this.startTempFactor = startTempFactor;
	}
	
	/**
	 * Sets the random seed to be used in this placer
	 * @param seed
	 */
	private void setSeed(long seed){
		this.seed = seed;
	}
	
	/**
	 * Performs all of the initialization steps to prepare for placement
	 */
	private void initializePlacer(boolean debugFlow){
		currentPlacements = new HashMap<Site, HardMacro>();
		currentMove = new Move();
		totalMoves = 0;
		rand = new Random(seed);
		dev = design.getDevice();
		allPaths = new HashSet<Path>();
		hardMacros = new ArrayList<HardMacro>();
		macroMap = new HashMap<ModuleInst, HardMacro>();
		
		// Find all valid placements for each module
		for(ArrayList<Module> moduleImpls : design.getModules()){
			for(Module module : moduleImpls){
				ArrayList<Site> sites = module.getAllValidPlacements();
				if(sites.size() == 0){
					//throw new RuntimeException("ERROR: Cached macros not implemented yet.");
					//Module m = new Module();
					//m.readFromCompactFile(fileName);
					//sites = m.getAllValidPlacements();
					sites = module.calculateAllValidPlacements(dev);
				}
				if(debugFlow){
					// Need to check if placements will work with existing implementation
					ArrayList<Site> openSites = new ArrayList<Site>();
					for(Site s : sites){
						if(module.isValidPlacement(s, dev, design)){
							openSites.add(s);
						}
					}
					if(openSites.size() == 0){
						throw new RuntimeException("ERROR: Couldn't find an open placement location for module: " + module.getName());
					}
					sites = openSites;
				}
			}
		}
		
		// Create Hard Macro objects from module instances
		for(ModuleInst mi : design.getModuleInsts()){
			HardMacro hm = new HardMacro(mi);
			hardMacros.add(hm);
			hm.setValidPlacements();
			macroMap.put(mi, hm);
		}

		// Place hard macros for initial placement
		for(HardMacro hm : hardMacros){			
			for(Site site : hm.getValidPlacements()){
				hm.setTempAnchorSite(site, currentPlacements);
				if(checkValidPlacement(hm)){
					hm.place(site);
					hm.calculateTileSize();
					break;
				}
			}
		}
		
		// Find all port wires
		populateAllPaths();
	}

	/**
	 * Finds all paths between unplaced components.
	 */
	private void populateAllPaths(){
		for(Net net : design.getNets()){
			if(net.isStaticNet() || net.isClockNet()) continue;
			SitePinInst src = net.getSource();
			ArrayList<SitePinInst> snks = new ArrayList<SitePinInst>();
			if(src == null){
				// TODO - This should not happen
				//System.out.println("ERROR: Need to find out why net: " + net.getName() + " has no driver\n\n" + net.toString() );
				continue;
			}
			String srcModInstName = src.getSiteInst().getModuleInst() == null ? "null" : src.getModuleInstName();
			for(SitePinInst p : net.getPins()){
				if(p.equals(src)) continue;
				String snkModInstName = p.getSiteInst().getModuleInst() == null ? "null" : p.getModuleInstName();
				if(!snkModInstName.equals(srcModInstName)){
					snks.add(p);
				}
			}
			
			if(snks.size() > 0){
				Path newPath = new Path();
				newPath.addPin(src,macroMap);
				for(SitePinInst snk : snks){
					newPath.addPin(snk, macroMap);
				}
				allPaths.add(newPath);
			}
		}
		
		for(Path pa : allPaths){
			for(PathPort po : pa){
				if(po.getBlock() != null){
					po.getBlock().addConnectedPath(pa);
				}
			}
		}
	}
	
	private boolean checkValidPlacement(HardMacro hm){
		if(!hm.isValidPlacement()) return false;
		for(HardMacro hardMacro : hardMacros){
			if(hardMacro.equals(hm)) continue;
			if(hm.getTempAnchorSite().equals(hardMacro.getTempAnchorSite())) return false;
			if(hm.overlaps(hardMacro)){
				return false;
			}
		}
		return true;
	}
	
	
	public Design placeDesign(Design design, boolean debugFlow){
		this.design = design;
		boolean finished = false;
		double changeInCost = 0.0;
		double r;
		MessageGenerator.printHeader(this.getClass().getCanonicalName());
		long start = System.currentTimeMillis();
		initializePlacer(debugFlow);
		//design.saveXPN("debug.xpn");
		System.out.println("Initialization Time: " + ((System.currentTimeMillis()-start)/1000.0) + " secs");
		
		start = System.currentTimeMillis();
		for(Path path : allPaths){
			path.calculateLength();
		}
		double prevSystemCost = currentSystemCost();
		//if(verbose) System.out.println(" Initial Cost: " + prevSystemCost);

		double currSystemCost = prevSystemCost;
		double bestSoFar = currSystemCost;
		
		// Initialize temperature
		currentTemp = currSystemCost * startTempFactor;	
		
		int extraMoves = 0;
		
		while(!finished){
			currentAcceptedMoveCount = 0;
			int moveCount = 0;
			int badMoveCount = 0;
			int badAcceptedMoveCount = 0;
			double totalMovesCost = 0.0;
			while(currentAcceptedMoveCount < (movesPerTemperature + extraMoves)){
				getNextMove();
				totalMoves++;
				currSystemCost = currentSystemCost();
				changeInCost = currSystemCost - prevSystemCost;
				moveCount++;

				totalMovesCost += changeInCost;
				if(currSystemCost < bestSoFar){
					bestSoFar = currSystemCost;
				}
				
				r = rand.nextDouble();
				double scaleFactor = 0.0;
				if(currentMove.getBlock0() != null){
					scaleFactor += currentMove.getBlock0().getConnectedPaths().size();
				}
				if(currentMove.getBlock1() != null){
					scaleFactor += currentMove.getBlock1().getConnectedPaths().size();
				}
				
				//System.out.println(changeInCost + " " + scaleFactor + " " + currentTemp + " " + r);
				boolean acceptMove = (r < Math.exp(-changeInCost/(scaleFactor*currentTemp)));
				
				if(changeInCost > 0) badMoveCount++; 
				
				if(acceptMove){
					currentAcceptedMoveCount++;
					prevSystemCost = currSystemCost;

					if(changeInCost > 0) badAcceptedMoveCount++;
				}
				else{
					// Undo the move, we are not accepting it
					currentMove.undoMove(currentPlacements);
					double testCost = currentSystemCost();
					if(testCost != prevSystemCost){
						MessageGenerator.briefError("ERROR: Undo move caused improper system cost change: prev=" + prevSystemCost + " incorrect=" + testCost + " move= " + currentMove.toString());
						MessageGenerator.waitOnAnyKeySilent();
					}
				}
				moveAcceptanceRate = ((double)currentAcceptedMoveCount) / moveCount;
			}
			if(DEBUG_LEVEL > 0) System.out.printf("MOVES(ACCEPTED/TOTAL): %7d/%7d SYS COST: % 7.1f AVG COST/MOVE: % 7.1f TEMP: % 7.1f ACCEPTANCE RATE: %5.1f%% BEST: % 7.1f BAD: %4.1f%%\n",currentAcceptedMoveCount,moveCount,prevSystemCost, totalMovesCost/moveCount, currentTemp, moveAcceptanceRate*100, bestSoFar, 100.0*badAcceptedMoveCount/badMoveCount);
			
			currentTemp = updateTemperature();		
			
			extraMoves = (int)Math.abs((10.0/(moveAcceptanceRate - goldenRate)));
			if(extraMoves > 2500) extraMoves = 2500;

			
			// Determine if we should exit
			//if(currentTemp < END_TEMP){
			if(moveAcceptanceRate < END_ACCEPTANCE_RATE || currentTemp < 0.01){
				finished = true;
			}
		}
		// Store final results
		finalSystemCost = currSystemCost;
		finalBestCost = bestSoFar;
		placerRuntime  = ((System.currentTimeMillis()-start)/1000.0);
		if(DEBUG_LEVEL > 0) System.out.println(seed + ": " + currSystemCost + " / " + bestSoFar + " Runtime: " + placerRuntime + "secs");
		if(DEBUG_LEVEL > 0) System.out.printf("  Perturbation Time: %.3f secs (%9.0f moves/sec)\n", placerRuntime,(totalMoves/placerRuntime));	
		
		System.out.println("Final System Cost: " + finalSystemCost);
		
		HashSet<HardMacro> fineTunePlacement = new HashSet<HardMacro>(); 
		
		for(HardMacro hm : hardMacros){
			if(hm.getTileSize() < 60){
				fineTunePlacement.add(hm);
			}
		}
		
		
		for(HardMacro hm : fineTunePlacement){
			// Keep the original spot, in the case we suggest a worst spot
			Site original = hm.getTempAnchorSite();
			int originalMaxLength = 0;
			// Determine all of the connecting points to this hard macro
			HashSet<Point> pointsList = new HashSet<Point>();
			for(Path path : hm.getConnectedPaths()){
				if(path.getLength() > originalMaxLength){
					originalMaxLength = path.getLength();
				}
				for(PathPort pp : path){
					if(pp.getBlock()== null || !pp.getBlock().equals(hm)){
						pointsList.add(new Point(pp.getPortTile()));
					}
				}
			}
			
			
			
			Point center = SmallestEnclosingCircle.getCenterPoint(pointsList);
			Tile centroid = dev.getTile("INT_X" + center.x + "Y" + center.y);
			Site newCandidateSite = null;
			if(centroid != null && centroid.getSites().length > 0){
				newCandidateSite = centroid.getSites()[0];
			}
			

			if(newCandidateSite != null){
				if(DEBUG_LEVEL > 0) System.out.println("Moving " + hm.getName() + " from " + original.getTile() + " to " + newCandidateSite.getTile());
				currentMove.setMove(newCandidateSite, original, null, hm);
				hm.setTempAnchorSite(newCandidateSite, currentPlacements);
				currentSystemCost();
				int longestPath = 0;
				for(Path path : hm.getConnectedPaths()){
					if(path.getLength() > longestPath){
						longestPath = path.getLength();
					}
				}
				if(originalMaxLength+5 < longestPath){
					if(DEBUG_LEVEL > 0) System.out.println("  Undo move: old max length: " + originalMaxLength + " new max length " + longestPath);
					currentMove.undoMove(currentPlacements);
					currentSystemCost();
				}
			}
		}
		
		System.out.println("Final System Cost (after fine tuning): " + this.currentSystemCost());
		
		//MessageGenerator.waitOnAnyKey();
		
		
		// Sort hard macros, largest first to place them first
		HardMacro[] array = new HardMacro[hardMacros.size()];
		array = hardMacros.toArray(array);
		Arrays.sort(array);
		
		HashSet<Tile> usedTiles = new HashSet<Tile>();
		// Perform final placement of all hard macros
		for(HardMacro hm : array){	
			//System.out.println(moveCount.get(hm) + " " + hm.tileSize + " " + hm.getName());
			HashSet<Tile> footPrint = isValidPlacement((ModuleInst)hm, hm.getModule().getAnchor().getSite(), hm.getTempAnchorSite().getTile(), usedTiles);
			if(footPrint == null){
				
				if(!placeModuleNear((ModuleInst)hm, hm.getTempAnchorSite().getTile(), usedTiles)){
					System.out.println("Saving as debug.");
					MessageGenerator.briefErrorAndExit("ERROR: Placement failed, couldn't find valid site for " + hm.getName());					
				}
			}
			else{
				usedTiles.addAll(footPrint);
				if(!hm.place(hm.getTempAnchorSite())){
					MessageGenerator.briefErrorAndExit("ERROR: Problem placing " + hm.getName() + " on site: " + hm.getTempAnchorSite());
				}
			}
		}
		
		design.clearUsedSites();
		for(SiteInst i : design.getSiteInsts()){
			i.place(i.getSite());
		}
		
		return design;
	}
	
	enum Direction{UP, DOWN, LEFT, RIGHT};
	
	public Site getPrimitiveSiteFromTile(Tile tile, SiteTypeEnum type){
		for(Site p : tile.getSites()){
			if(p.isCompatibleSiteType(type)){
				return p;
			}
		}
		return null;
	}
	
	public boolean placeModuleNear(ModuleInst modInst, Tile tile, HashSet<Tile> usedTiles){
		Site anchorSite = modInst.getModule().getAnchor().getSite();
		Tile proposedAnchorTile = tile;
		Direction dir = Direction.UP;
		HashSet<Tile> triedTiles = new HashSet<Tile>();
		int column = tile.getColumn();
		int row = tile.getRow();
		int maxColumn = column+1;
		int maxRow = row+1;
		int minColumn = column-1;
		int minRow = row;
		HashSet<Tile> tiles = null;
		while(proposedAnchorTile != null && tiles == null){
			switch(dir){
				case UP:
					if(row == minRow){
						dir = Direction.RIGHT;
						minRow--;
						column++;
					}
					else{
						row--;
					}
					break;
				case DOWN:
					if(row == maxRow){
						dir = Direction.LEFT;
						maxRow++;
						column--;
					}
					else{
						row++;
					}
					break;
				case LEFT:
					if(column == minColumn){
						dir = Direction.UP;
						minColumn--;
						row--;
					}
					else{
						column--;
					}
					break;
				case RIGHT:
					if(column == maxColumn){
						dir = Direction.DOWN;
						maxColumn++;
						row++;
					}
					else{
						column++;
					}
					break;
			}
			proposedAnchorTile = dev.getTile(row, column);
			if(proposedAnchorTile != null){
				triedTiles.add(proposedAnchorTile);
				tiles = isValidPlacement(modInst, anchorSite, proposedAnchorTile, usedTiles);
				
				Site newAnchorSite = anchorSite.getCorrespondingSite(modInst.getModule().getAnchor().getSiteTypeEnum(), proposedAnchorTile);
				if(tiles != null && modInst.place(newAnchorSite)){
					usedTiles.addAll(tiles);
					return true;
				}
				else{
					tiles = null;
				}
			}
		}
		
		if(proposedAnchorTile == null){
			Site[] candidateSites = dev.getAllCompatibleSites(modInst.getAnchor().getSiteTypeEnum());
			for(Site site : candidateSites){
				proposedAnchorTile = site.getTile();
				if(!triedTiles.contains(proposedAnchorTile)){
					tiles = isValidPlacement(modInst, anchorSite, proposedAnchorTile, usedTiles);
					if(tiles != null){
						break;
					}
					triedTiles.add(proposedAnchorTile);
				}
			}
		}
		
		
		if(tiles == null){
			if(DEBUG_LEVEL > 0) System.out.println("Placement failed: tiles==null " + modInst.getName());
			return false;
		}
		Site newAnchorSite = anchorSite.getCorrespondingSite(modInst.getModule().getAnchor().getSiteTypeEnum(), proposedAnchorTile);
		if(modInst.place(newAnchorSite)){
			usedTiles.addAll(tiles);
			return true;
		}
		if(DEBUG_LEVEL > 0) System.out.println("Placement failed: place() " + modInst.getName());
		return false;
	}
	
	
	private HashSet<Tile> isValidPlacement(ModuleInst modInst, Site anchorSite, Tile proposedAnchorTile, HashSet<Tile> usedTiles){
		if(usedTiles.contains(proposedAnchorTile)){
			return null;
		}
		
		modInst.getAnchor().getSiteTypeEnum();
		Site newSite2 = modInst.getAnchor().getSite().getCorrespondingSite(modInst.getAnchor().getSiteTypeEnum(), proposedAnchorTile);
		
		if(newSite2 == null){
			return null;
		}
		
		HashSet<Tile> footPrint = new HashSet<Tile>();
		// Check instances
		for(SiteInst i : modInst.getModule().getSiteInsts()){
			if(Utils.getLockedSiteTypes().contains(i.getSiteTypeEnum())){
				continue;
			}
			Tile newTile = modInst.getCorrespondingTile(i.getTile(), proposedAnchorTile, dev);
			if(newTile == null || usedTiles.contains(newTile)){
				return null;
			}
			
			Site newSite = i.getSite().getCorrespondingSite(i.getSiteTypeEnum(), newTile);
			if(newSite == null){
				return null;
			}
			
			footPrint.add(newTile);
		}
		
		// Check nets
		for(Net n : modInst.getModule().getNets()){
			for(PIP p : n.getPIPs()){
				Tile newTile = modInst.getCorrespondingTile(p.getTile(), proposedAnchorTile, dev);
				if(newTile == null || usedTiles.contains(newTile)){
					return null;
				}
				if(!newTile.getTileTypeEnum().equals(p.getTile().getTileTypeEnum())){
					boolean a = Utils.isInterConnect(p.getTile().getTileTypeEnum());
					boolean b = Utils.isInterConnect(newTile.getTileTypeEnum());
					if(a || b){
						if(!(a && b)){
							return null;
						}
					}
					else{
						return null;
					}
				}
				footPrint.add(newTile);
			}
		}		
		
		return footPrint;
	}
	
	
	private void getNextMove(){
		HardMacro selected = hardMacros.get(rand.nextInt(hardMacros.size()));
		ArrayList<Site> validSites = selected.getValidPlacements();
		Site site0 = selected.getTempAnchorSite();
		Site site1 = null;
		HardMacro hm0 = selected;
		HardMacro hm1 = null;
		int iterations = 0;

		while(true){
			if(iterations > 10*validSites.size()){
				selected = hardMacros.get(rand.nextInt(hardMacros.size()));
				validSites = selected.getValidPlacements();
				site0 = selected.getTempAnchorSite();
				hm0 = selected;
				iterations = 0;
			}
			iterations++;
			
			site1 = validSites.get(rand.nextInt(validSites.size()));
			
			
			if(site0.equals(site1)) {
				//if(DEBUG_LEVEL > 1) System.out.println("  SAME SITE");
				continue;
			}

			hm1 = currentPlacements.get(site1);
				
			if(hm1 != null){
				hm1.setTempAnchorSite(site0, currentPlacements);
				hm0.setTempAnchorSite(site1, currentPlacements);
				if((!checkValidPlacement(hm0)) || (!checkValidPlacement(hm1))){
					hm1.setTempAnchorSite(site1, currentPlacements);
					hm0.setTempAnchorSite(site0, currentPlacements);
					//if(DEBUG_LEVEL > 1) System.out.println("  BAD SWAP");
					continue;
				}
				break;
			}
			else{
				hm0.setTempAnchorSite(site1, currentPlacements);
				if(!checkValidPlacement(hm0)){
					hm0.setTempAnchorSite(site0, currentPlacements);
					//if(DEBUG_LEVEL > 1) System.out.println("  BAD SITE0");
					continue;
				}
				break;
			}
		}
		currentMove.setMove(site0, site1, hm0, hm1);
	}

	private double updateTemperature(){
		return currentTemp = currentTemp * tempReduce;
	}
	
	private double currentSystemCost(){
		int totalWireLength = 0;
		if(currentMove.getBlock0() != null){
			for(Path wire : currentMove.getBlock0().getConnectedPaths()){
				wire.calculateLength();				
			}
		}
		if(currentMove.getBlock1() != null){
			for(Path wire : currentMove.getBlock1().getConnectedPaths()){
				wire.calculateLength();
			}
		}
		int maxPathLength = 0;
		for(Path path : allPaths){
			totalWireLength += path.getLength();
			if(path.getLength() > maxPathLength){
				maxPathLength = path.getLength();
			}
		}
		return totalWireLength + 16 * maxPathLength;
	}

	/**
	 * WIP - Need a way to load modular designs
	 * @param fileName
	 */
	private void measureCost(String fileName){
		// Load Design TODO

		currentPlacements = new HashMap<Site, HardMacro>();
		currentMove = new Move();
		totalMoves = 0;
		rand = new Random(123456);
		dev = design.getDevice();
		allPaths = new HashSet<Path>();
		hardMacros = new ArrayList<HardMacro>();
		macroMap = new HashMap<ModuleInst, HardMacro>();
		
		
		// Create Hard Macro objects from module instances
		for(ModuleInst mi : design.getModuleInsts()){
			HardMacro hm = new HardMacro(mi);
			hardMacros.add(hm);
			macroMap.put(mi, hm);
		}

		// Place hard macros for initial placement
		for(HardMacro hm : hardMacros){
			hm.setTempAnchorSite(hm.getAnchor().getSite(), currentPlacements);
		}
		
		// Find all port wires
		populateAllPaths();
		
		for(Path path : allPaths){
			path.calculateLength();
			System.out.printf("%4d ",path.getLength());
			for(PathPort pp : path){
				String name = pp.getBlock() == null ? "null" : pp.getBlock().getModule().getName(); 
				System.out.print(name + "("+pp.getPortTile().getName()+")->");
			}
			System.out.println();
		}
		
		System.out.println("System cost for file: " + fileName + " is " + currentSystemCost());
	}
	
	/**
	 * This will return a set of all unique TileTypes which are considered
	 * to have a switch matrix or routing switch box in them.
	 * @return A set of all TileTypes which have a switch matrix in them.
	 */
	public static Set<TileTypeEnum> getSwitchMatrixTypes(){
		return Utils.getIntTileTypes();
	}

}
