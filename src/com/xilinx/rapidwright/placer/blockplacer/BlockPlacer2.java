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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import com.xilinx.rapidwright.design.AbstractModuleInst;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SimpleTileRectangle;
import com.xilinx.rapidwright.design.TileRectangle;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * An alternate implementation of {@link BlockPlacer}.  This placer
 * tends to do better but with longer runtime.
 * @author Chris Lavin
 */
public abstract class BlockPlacer2<ModuleT, ModuleInstT extends AbstractModuleInst<ModuleT, ?>, PlacementT, PathT extends AbstractPath<?, ModuleInstT>> extends AbstractBlockPlacer<ModuleInstT, PlacementT> {
	/** Enable extra sanity checks? */
	protected static final boolean PARANOID = false;
	/** The current design */
	protected final Design design;
	/** The current device being targeted by the design */
	protected final Device dev;
	/** A list of all the hard macros in the design */
	protected List<ModuleInstT> hardMacros;
	/** A set of all the paths between hard macros in the design */
	protected Set<PathT> allPaths;
	/** The random number generator used throughout this class */
	private Random rand;
	/** The current move that is being evaluated */
	private Move<ModuleInstT, PlacementT> currentMove;
	/** The current temperature of the simulated annealing schedule */
	private double currentTemp;
	/** Number of accepted moves in the current temperature step */
	private int currentAcceptedMoveCount = 0;
	/** Total number of moves through the entire execution of the annealer */
	private int totalMoves;
	/** Measures the current move acceptance rate */
	private double moveAcceptanceRate = 1.0;
	/** */
	//private double goldenRate = 0.44;
	private final double goldenRate = 0.20;
	private long seed;

	// Final Results
	/** */
	public double finalSystemCost;
	/** */
	public double finalBestCost;
	/** */
	public double placerRuntime;
	/** */
	/** */
	protected double rangeLimit;
	public boolean verbose = true;

	public static int DEBUG_LEVEL = 1;
	// DEBUG
	//private HashMap<HardMacro, Integer> moveCount = new HashMap<HardMacro, Integer>();
	private final double alpha;
	private final double beta;

	private final boolean ignoreMostUsedNets;

    // Update. Added variable to support partial .dcp
    public boolean save_partial_dcp = true;

    private PrintWriter graphDataWriter = null;
	private double prevSystemCost;
	private double currSystemCost;
	private int moveCount;
	private double bestSoFar;

	private Map<ModuleT, AbstractValidPlacementCache<PlacementT>> possiblePlacements;

    private Map<ModuleInstT, Site> lockedPlacements = null;

	/**
	 * Empty Constructor
	 *
	 */
	public BlockPlacer2(Design design, boolean ignoreMostUsedNets, Path graphData){
		this.design = design;
		this.dev = design.getDevice();
		this.ignoreMostUsedNets = ignoreMostUsedNets;
		alpha = 1.0;
		beta = 1.0;
		seed = 2;
		if (graphData != null) {
			try {
				graphDataWriter = new PrintWriter(Files.newBufferedWriter(graphData));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	public BlockPlacer2(Design design, boolean ignoreMostUsedNets, Path graphData, Map<ModuleInstT, Site> lockedPlacements) {
	    this(design, ignoreMostUsedNets, graphData);
	    this.lockedPlacements = lockedPlacements;
	}

	/**
	 * Sets the random seed to be used in this placer
	 * @param seed
	 */
	private void setSeed(long seed){
		this.seed = seed;
	}

	abstract List<ModuleInstT> getModuleImpls(boolean debugFlow);

	protected abstract void ignorePath(PathT path);
	private void findPathsToIgnore() {
		allPaths.removeIf(path -> {
			double connectedRatio = (double)path.countConnectedModules()/hardMacros.size();
			if (connectedRatio > 0.9) {
				System.out.println("ignoring path "+path.getName()+", connects to "+path.countConnectedModules()+"/"+hardMacros.size()+" = "+connectedRatio);
				ignorePath(path);
				return true;
			}
			return false;
		});
	}
	/**
	 * Performs all of the initialization steps to prepare for placement
	 */
	public void initializePlacer(boolean debugFlow){
		currentMove = new Move<>(this);
		totalMoves = 0;
		allPaths = new HashSet<PathT>();
		hardMacros = getModuleImpls(debugFlow);

		populateAllPaths();

		if (ignoreMostUsedNets) {
			findPathsToIgnore();
		}
	}

	/**
	 * All Placements of a hard macro. MUST BE SORTED BY TILE COLUMN!
	 *
	 * Order is used for speeding up applying the range limit
	 * @param hm macro
	 * @return possible placements, ordered by column
	 */
	abstract Collection<PlacementT> getAllPlacements(ModuleInstT hm);
	abstract void unsetTempAnchorSite(ModuleInstT hm);
	abstract Comparator<PlacementT> getInitialPlacementComparator();
	abstract void placeHm(ModuleInstT hm, PlacementT placement);
	abstract void unplaceHm(ModuleInstT hm);

	protected void initialPlacement(){
		if (hardMacros.stream().allMatch(hm->getCurrentPlacement(hm) != null)) {
			for(PathT path : allPaths){
				path.calculateLength();
			}

			System.out.println("Pre-placed design! Cost: "+currentSystemCost());
			unplaceDesign();
		}

		possiblePlacements = new HashMap<>();

		// Place hard macros for initial placement
		for(ModuleInstT hm : hardMacros){
			PriorityQueue<PlacementT> sites = new PriorityQueue<>(1024, getInitialPlacementComparator());
			final Collection<PlacementT> allPlacements = getAllPlacements(hm);

			possiblePlacements.put(hm.getModule(), allPlacements.stream().collect(SortedValidPlacementCache.collector(this)));
			sites.addAll(allPlacements);
			boolean found = false;
			while(!sites.isEmpty()){
				PlacementT site = sites.remove();
				setTempAnchorSite(hm, site);
				if(checkValidPlacement(hm)){
					placeHm(hm, site);
					found = true;
					break;
				}
			}
			if (!found) {
				throw new RuntimeException("no initial place for "+hm.getName());
			}
		}

		for(PathT path : allPaths){
			path.calculateLength();
		}

		ArrayList<ModuleInstT> prunedList = new ArrayList<>();
		for(ModuleInstT hm : new ArrayList<>(hardMacros)){
			if(getAllPlacements(hm).size() > 2) prunedList.add(hm);
			else {
				System.err.println("Not adding HM since it only has one placement: "+hm.getName());
			}
		}
		hardMacros = prunedList;
	}

	private void unplaceDesign(){
		// Place hard macros for initial placement
		currentMove = new Move<ModuleInstT, PlacementT>(this);
		totalMoves = 0;
		for(ModuleInstT hm : hardMacros){
			unplaceHm(hm);
			unsetTempAnchorSite(hm);
		}
	}

	/**
	 * Finds all paths between unplaced components.
	 */
	protected abstract void populateAllPaths();

	protected abstract boolean checkValidPlacement(ModuleInstT hm);

	public double calculateStartTemp(){
		double stdDev = 0.0;
		double myTemp = 1e30;// very high temperature to accept all moves
		double currentCost = 0.0;
		double previousCost = 0.0;
		double avgCost = 0.0;
		double sqCost = 0.0;
		double r;
		ArrayList<Double> arrayCosts = new ArrayList<>();
		int acceptedMoveCount = 0;
		previousCost = currentSystemCost();
		for(ModuleInstT selectedHD : hardMacros) {
			saveAllCosts();
			if (getNextMove(selectedHD)){
				saveAllCosts();
				currentCost = currentSystemCost();
				arrayCosts.add(currentCost);
				avgCost = avgCost + currentCost;
				sqCost = sqCost + (currentCost*currentCost);


				r = rand.nextDouble();
				double costChange = currentCost - previousCost;
				boolean acceptMove = (r < Math.exp(-costChange/myTemp));

				if(acceptMove){
					//System.out.println("start temp accept for "+costChange+": "+r+"<"+Math.exp(-costChange/myTemp)+" curr: "+currentCost);
					acceptedMoveCount++;
					previousCost = currentCost;
				}
				else{
					//System.out.println("start temp not accept");
					// Undo the move, we are not accepting it
					currentMove.undoMove();
					for (PathT path : getConnectedPaths(currentMove.getBlock0())) {
						path.calculateLength();
					}
					if (currentMove.getBlock1() != null) {
						for (PathT path : getConnectedPaths(currentMove.getBlock1())) {
							path.calculateLength();
						}
					}
					saveAllCosts();
					double testCost = currentSystemCost();
					if(testCost != previousCost){
						dumpCostChanges();
						MessageGenerator.briefError("ERROR_startTemp: gUndo move caused improper system cost change: prev=" + previousCost + " incorrect=" + testCost + " move= " + currentMove.toString());
						MessageGenerator.waitOnAnyKeySilent();
					}
				}
			}// Move loop
		}
		if(acceptedMoveCount>0){
			avgCost = avgCost/acceptedMoveCount;
		}else{
			avgCost = 0;
		}
		for(double c : arrayCosts){
			double tmp = c - avgCost;
			stdDev = stdDev + (tmp*tmp);
		}
		stdDev = Math.sqrt(stdDev/(Math.max(acceptedMoveCount-1,1)));
		//currentPlacements.clear();
		//allPaths.clear();
		//hardMacros.clear();
		//macroMap.clear();
		return (20.0*stdDev);
	}

	private String hmName(ModuleInstT hm) {
		if (hm == null)
			return "null";
		return hm.getName();
	}

	private void printAllCosts(PrintWriter pw) {
		pw.println(hmName(currentMove.getBlock0())+", "+hmName(currentMove.getBlock1()));
		pw.println(currentMove.site0+", "+currentMove.site1);
		hardMacros.stream().sorted(Comparator.comparing(hm->hm.getName()))
				.forEach(hm -> pw.println(hm.getName()+": "+getCurrentPlacement(hm)));
		allPaths.stream().sorted(Comparator.comparing(p->p.getName()))
				.forEach(path -> {
					pw.println(path.getName()+": "+path.getLength());
					/*path.streamTiles().map(Object::toString).sorted()
							.forEach(o->pw.println("    "+o));*/
				});
	}
	List<String> costList = new ArrayList<>();
	private void saveAllCosts() {
		if (currentTemp > -1) {
			return;
		}
		while (costList.size() > 4) {
			costList.remove(0);
		}
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);

		printAllCosts(pw);

		costList.add(sw.toString());
	}

	private void dumpCostChanges() {
		for (int i=0;i<costList.size();i++) {
			Path out = Paths.get("/tmp/costDump"+i+".txt");
			try {
				Files.write(out, costList.get(i).getBytes(StandardCharsets.UTF_8));
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("dumped costs to "+out);
		}
	}

	protected abstract void doFinalPlacement();

	protected abstract Tile getCurrentAnchorTile(ModuleInstT mi);

	protected abstract PlacementT getTempAnchorSite(ModuleInstT mi);

	public double placeDesign(boolean debugFlow){
		rand = new Random(seed);
		boolean finished = false;
		int maxInnerIteration = 0;
		double r;
		//MessageGenerator.printHeader(this.getClass().getCanonicalName());
		long start = System.currentTimeMillis();
		//System.out.println("Initialization Time: " + ((System.currentTimeMillis()-start)/1000.0) + " secs");
		start = System.currentTimeMillis();
		initializePlacer(debugFlow);
		initialPlacement();
		//HandPlacer.openDesign(design);
		int totalFootprint = 0;
		for(ModuleInstT hm : hardMacros){
			totalFootprint += getTileSize(hm);
		}
		int squareWidth = (int) (Math.sqrt(totalFootprint) * 1.5);

		//rangeLimit = Math.max(dev.getColumns(), dev.getRows());
		rangeLimit = Math.max(squareWidth, squareWidth);
		currentTemp = calculateStartTemp();
		if (Double.isNaN(currentTemp)) {
			throw new RuntimeException("initialized to NAN temperature");
		}
		System.out.println("currentTemp = " + currentTemp);
		System.out.println("hardMacros.size() = " + hardMacros.size());
		//initializePlacer(debugFlow);
		//unplaceDesign();
		//initialPlacement();
		prevSystemCost = currentSystemCost();
		currSystemCost = prevSystemCost;
		bestSoFar = currSystemCost;
		rangeLimit = Math.max(dev.getColumns(), dev.getRows());
		maxInnerIteration = (int)(1 * Math.pow(hardMacros.size(), 1.3333));
		//maxInnerIteration = (int)(Math.pow(Math.max(dev.getColumns(), dev.getRows()), 1.3333));
		if(hardMacros.size() < 2 || allPaths.size() == 0){
			finished = true;
			maxInnerIteration = 0;
		}
		OUTER: while(!finished){
			temperatureStep(maxInnerIteration);

			rangeLimit = rangeLimit * (1.0-goldenRate + moveAcceptanceRate);
			double upperLimit = Math.max(dev.getColumns(), dev.getRows());
			rangeLimit = Math.min(rangeLimit, upperLimit);
			rangeLimit = Math.max(rangeLimit, 1.0);

			currentTemp = updateTemperature();

			if (currentTemp < 0.005 * (prevSystemCost /allPaths.size())){
				finished = true;
				//WriteFinalCost(prevSystemCost);
			}
		} //Outer loop

		//Freezing phase
		prevSystemCost = currentSystemCost();
		currSystemCost = prevSystemCost;
		currentTemp = 0.0;

		temperatureStep(maxInnerIteration);

		// Store final results
		finalSystemCost = prevSystemCost;
		finalBestCost = bestSoFar;
		placerRuntime  = ((System.currentTimeMillis()-start)/1000.0);
		if(DEBUG_LEVEL > 0) System.out.println(seed + ": " + currSystemCost + " / " + bestSoFar + " Runtime: " + placerRuntime + "secs");
		if(DEBUG_LEVEL > 0) System.out.printf("  Perturbation Time: %.3f secs (%9.0f moves/sec)\n", placerRuntime,(totalMoves/placerRuntime));

		if(DEBUG_LEVEL > 0) System.out.println("Final System Cost: " + finalSystemCost);
		/*
		HashSet<HardMacro> fineTunePlacement = new HashSet<HardMacro>();

		for(HardMacro hm : hardMacros){
			if(hm.tileSize < 60){
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
			if(centroid != null && centroid.getSites() != null && centroid.getSites().length > 0){
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
		*/
		//MessageGenerator.waitOnAnyKey();

		doFinalPlacement();


		if (graphDataWriter != null) {
			graphDataWriter.close();
		}
		return finalSystemCost;
	}

	private void temperatureStep(int maxInnerIteration) {
		double r;
		currentAcceptedMoveCount = 0;
		moveCount = 0;
		int badMoveCount = 0;
		int badAcceptedMoveCount = 0;
		double totalMovesCost = 0.0;
		for(int inner_iterate = 0; inner_iterate< maxInnerIteration; inner_iterate++){
		//for(int inner_iterate = 0; inner_iterate< (10*rangeLimit); inner_iterate++){
		//for(int inner_iterate = 0; inner_iterate< (dev.getColumns()*dev.getRows()); inner_iterate++){
			ModuleInstT selectedHD = hardMacros.get(rand.nextInt(hardMacros.size()-1));
			saveAllCosts();


			if (PARANOID) {
				double testCost = currentSystemCost();
				if (testCost != prevSystemCost) {
					dumpCostChanges();
					MessageGenerator.briefError("ERROR: Improper system cost before creating new move: prev=" + prevSystemCost + " incorrect=" + testCost);
					MessageGenerator.waitOnAnyKeySilent();
				}
			}

			if (getNextMove(selectedHD)){
				saveAllCosts();
				totalMoves++;
				double changeInCost = currentMove.getDeltaCost() * alpha;
				currSystemCost = prevSystemCost + changeInCost; //TODO Loss of precision?
				if (PARANOID) {
					double realCurrentSystemCost = currentSystemCost();
					if (Math.abs(realCurrentSystemCost - currSystemCost) > 1E-6) {
						throw new RuntimeException("cost not equal");
					}
					double changeInCostRecalc = currSystemCost - prevSystemCost;
					if (Math.abs(changeInCost - changeInCostRecalc) > 1E-6) {
						dumpCostChanges();
						//calcConnectedCost(currentMove.getBlock0(), currentMove.getBlock1())
						throw new RuntimeException("Cost change differs. Recalc: " + changeInCostRecalc + ", efficient: " + changeInCost + " at move " + totalMoves);
					}
				}



				moveCount++;
				totalMovesCost += changeInCost;
				if(currSystemCost < bestSoFar){
					bestSoFar = currSystemCost;
					//if (bestSoFar==18875.0){
						//break OUTER;
					//}
				}

				r = rand.nextDouble();
				int numPath0 = 0;
				int numPath1 = 0;
				double tmp =0.0;
				double AvgChange = 0.0;
				/*if(currentMove.getBlock0() != null){
					for(PathT wire : getConnectedPaths(currentMove.getBlock0())){
						wire.calculateLength();
						tmp = tmp + wire.getLength();
					}
					numPath0 = getConnectedPaths(currentMove.getBlock0()).size();
					AvgChange = AvgChange + tmp/numPath0;
				}
				tmp =0.0;
				if(currentMove.getBlock1() != null){
					for(PathT wire : getConnectedPaths(currentMove.getBlock1())){
						wire.calculateLength();
						tmp = tmp + wire.getLength();
					}
					numPath1 = getConnectedPaths(currentMove.getBlock1()).size();
					AvgChange = AvgChange + tmp/numPath1;
				}*/
				int numPaths = Math.max(numPath0, numPath1);
				//double costChange = (changeInCost)*(numPath0+numPath1);
				double costChange = (changeInCost);
				//boolean acceptMove = (r < Math.exp(-changeInCost/(scaleFactor*currentTemp)));
				//double test_value = Math.exp(-changeInCost/currentTemp);
				//boolean acceptMove = (r < Math.exp(-changeInCost/currentTemp*numPaths));// good for Mcro with real changeInCost
				// glodenRate = 0.3 and loop 10* & updateTemp has rangelimit parameter
				//boolean acceptMove = (r < Math.exp(-changeInCost/currentTemp));
				//boolean acceptMove = (r < Math.exp(-changeInCost*numPaths/currentTemp));
				//boolean acceptMove = (r < Math.exp(-costChange/currentTemp));
				boolean acceptMove;
				if (currentTemp == 0.0) {
					acceptMove = changeInCost < 0;
				} else {
					acceptMove = (r < Math.exp(-changeInCost/currentTemp));
				}
				//boolean acceptMove = (randomDouble < Math.exp(-AvgChange/currentTemp*numPaths));
				if(changeInCost > 0) badMoveCount++;

				if(acceptMove){
					currentAcceptedMoveCount++;
					prevSystemCost = currSystemCost;
					if(changeInCost > 0) badAcceptedMoveCount++;
				}
				else{
					// Undo the move, we are not accepting it
					currentMove.undoMove();
					for (PathT path : getConnectedPaths(currentMove.getBlock0())) {
						path.calculateLength();
					}
					if (currentMove.getBlock1() != null) {
						for (PathT path : getConnectedPaths(currentMove.getBlock1())) {
							path.calculateLength();
						}
					}
					saveAllCosts();
					if (PARANOID) {
						double testCost = currentSystemCost();
						if (testCost != prevSystemCost) {
							dumpCostChanges();
							MessageGenerator.briefError("ERROR: 3 Undo move caused improper system cost change: prev=" + prevSystemCost + " incorrect=" + testCost + " move= " + currentMove.toString());
							MessageGenerator.waitOnAnyKeySilent();
						}
					}
				}
				//moveAcceptanceRate = ((double)currentAcceptedMoveCount) / moveCount;
				//moveAcceptanceRate = ((double)currentAcceptedMoveCount) / (Math.min(moveCount, hardMacros.size()));
			}// Move loop

		}//inner loop
		if (moveCount >0){
			moveAcceptanceRate = ((double)currentAcceptedMoveCount) / moveCount;
		} else {
			moveAcceptanceRate = 0;
		}
		//MOVES = ACCEPTED/TOTAL
		if (Double.isNaN(currentTemp)) {
			throw new RuntimeException("nan temperature!");
		}


		if (graphDataWriter != null) {
			graphDataWriter.printf(
					"%7d\t%7d\t%7.1f\t%7.1f\t%7.1f\t%5.1f\t%7.1f\t%4.1f\t%f\n",
					currentAcceptedMoveCount,
					moveCount,
					prevSystemCost,
					totalMovesCost/ moveCount,
					currentTemp,
					moveAcceptanceRate*100,
					bestSoFar,
					100.0*badAcceptedMoveCount/badMoveCount,
					rangeLimit
			);
		}
		if(DEBUG_LEVEL > 0) System.out.printf("MOVES:%7d/%7d COST:%7.1f AVG_COST/MOVE:%7.1f TEMP:%7.1f ACCEPTANCE_RATE:%5.1f%% BEST:%7.1f BAD:%4.1f%%\n",currentAcceptedMoveCount, moveCount, prevSystemCost, totalMovesCost/ moveCount, currentTemp, moveAcceptanceRate*100, bestSoFar, 100.0*badAcceptedMoveCount/badMoveCount);
	}

	protected abstract int getTileSize(ModuleInstT hm);

	public Collection<PathT> getPaths() {
		return allPaths;
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


	protected abstract boolean isInRange(PlacementT current, PlacementT newPlacement);


	private int calcConnectedCost(ModuleInstT hm0, ModuleInstT hm1) {
		int cost = 0;
		for (PathT objects : getConnectedPaths(hm0)) {
			objects.calculateLength();
			int length = objects.getLength();
			cost+= length;
		}
		if (hm1 != null) {
			for (PathT path : getConnectedPaths(hm1)) {
				if (path.connectsTo(hm0)) {
					//We have already counted the path in the above loop. Don't double count it!
					continue;
				}
				path.calculateLength();
				int length = path.getLength();
				cost += length;
			}
		}
		return cost;
	}

	protected abstract Tile getPlacementTile(PlacementT placement);

	private boolean getNextMove(ModuleInstT selected){
		//HardMacro selected = hardMacros.get(rand.nextInt(hardMacros.size()-1));

		PlacementT site0 = getCurrentPlacement(selected);
		PlacementT site1 = null;
		ModuleInstT hm0 = selected;
		ModuleInstT hm1 = null;
		int iterations = 0;
		/*int minX = 0;
		int maxX = 0;
		int minY = 0;
		int maxY = 0;
		minX = Math.max(1,Math.abs((site0.getTile().getRow()-(int)rangeLimit)));
		maxX = Math.min((int)rangeLimit, site0.getTile().getRow()+(int)rangeLimit);
		minY = Math.max(1,site0.getTile().getColumn()-(int)rangeLimit);
		maxY = Math.min(dev.getColumns(), site0.getTile().getColumn()+(int)rangeLimit);
		for(Site s : validSites){
			if (s.getTile().getColumn()>=minY && s.getTile().getColumn()<=maxY){
				validSiteRange.add(s);
			}
		}*/
		List<PlacementT> validSiteRange = possiblePlacements.get(hm0.getModule()).getByRangeAround((int) rangeLimit, site0);


		// Updated code. Store initial number of valid Sites
		int nr_valid_sites = validSiteRange.size();
		int rand_site = 0;

		PlacementT site1Previous = null;
		int costBefore = 0;
		while(true){
			/*if(iterations > 10*validSites.size()){
				selected = hardMacros.get(rand.nextInt(hardMacros.size()-1));
				validSites = selected.getValidPlacements();
				site0 = selected.getTempAnchorSite();
				hm0 = selected;
				iterations = 0;
			}*/
			if(iterations >=  nr_valid_sites){  // Updated code. Maximum trial nr = nr valid Sites
				//System.out.println("could not find placement 1");
                return false;
			}
			iterations++;

			//site1 = validSites.get(rand.nextInt(validSites.size()-1));
			if (validSiteRange.size()> 0){
				if (validSiteRange.size()>1){
                    rand_site = rand.nextInt(validSiteRange.size()-1);
					site1 = validSiteRange.get(rand_site);
				}else{
					//site1 = validSiteRange.get(rand.nextInt(validSiteRange.size()));
                    rand_site = 0;
					site1 = validSiteRange.get(0);
				}
			}else{
				return false;
			}
			if(site0.equals(site1)) {
				//if(DEBUG_LEVEL > 1) System.out.println("  SAME SITE");
                validSiteRange.remove(rand_site); // Updated code. Remove sites that were already checked
                continue;
			}
			//TODO this only works when the same anchor is chosen for the other module.
			hm1 = getHmCurrentlyAtPlacement(site1);
			if (hm1 == hm0) {
				hm1 = null;
			}

			costBefore = calcConnectedCost(hm0, hm1);

			if(hm1 != null){
				site1Previous = getCurrentPlacement(hm1);
				//System.out.println("swapping with "+hm1.getName()+", which is currently at "+site1Previous);

				//Can we swap?
				boolean newContains = possiblePlacements.get(hm1.getModule()).contains(site0);
				/*boolean oldContains = getAllPlacements(hm1).contains(site0);
				if (oldContains != newContains) {
					throw new RuntimeException("contains bug");
				}*/
				if (!newContains) {
					continue;
				}
				setTempAnchorSite(hm1, site0);
				setTempAnchorSite(hm0, site1);
				if((!checkValidPlacement(hm0)) || (!checkValidPlacement(hm1))){
					setTempAnchorSite(hm1, site1Previous);
					setTempAnchorSite(hm0, site0);
					//if(DEBUG_LEVEL > 1) System.out.println("  BAD SWAP");
					validSiteRange.remove(rand_site); // Updated code. Remove sites that were already checked
					site1Previous = null;
                    continue;
				}
				//System.out.println(hm0.getName()+"<->"+hm1.getName());
				break;
			}
			else{
				setTempAnchorSite(hm0, site1);
				//hm0.setTempAnchorSite(site1, currentPlacements);
				if(!checkValidPlacement(hm0)){
					setTempAnchorSite(hm0, site0);
					//hm0.setTempAnchorSite(site0, currentPlacements);
					//if(DEBUG_LEVEL > 1) System.out.println("  BAD SITE0");
                    validSiteRange.remove(rand_site); // Updated code. Remove sites that were already checked
                    continue;
				}
				//System.out.println(hm0.getName()+"-> EMPTY");
				break;
			}
		}
        currentMove.setMove(site0, site1, hm0, hm1, site1Previous);
		int costAfter = calcConnectedCost(hm0, hm1);
		currentMove.setDeltaCost(costAfter - costBefore);
		return true;
	}

	private Path printPlacements(String name, List<PlacementT> validSiteRange, PlacementT center) {
		Path outPath = Paths.get("/tmp").resolve("placement_range_"+name+".txt");

		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath))) {

			validSiteRange.stream().sorted(
					Comparator.<PlacementT, Integer>comparing(k -> getPlacementTile(k).getColumn()).thenComparing(k -> getPlacementTile(k).getRow())
			).forEach(p -> pw.println(p+" "+isInRange(center, p)+" "+getDistance(getPlacementTile(center), getPlacementTile(p))));

		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return outPath;

	}

	protected abstract ModuleInstT getHmCurrentlyAtPlacement(PlacementT placement);

	protected abstract PlacementT getCurrentPlacement(ModuleInstT selected);

	private double updateTemperature(){
		double tmpCurrentTemp = currentTemp;
		if(moveAcceptanceRate>0.96){
			tmpCurrentTemp = tmpCurrentTemp * 0.5;
		}else if(moveAcceptanceRate > 0.8){
			tmpCurrentTemp = tmpCurrentTemp * 0.9;
		//}else if(moveAcceptanceRate > 0.15 || rangeLimit > 1.0){
		}else if(moveAcceptanceRate > 0.15){
			tmpCurrentTemp = tmpCurrentTemp * 0.95;
		}else {
			tmpCurrentTemp = tmpCurrentTemp * 0.8;
		}
		return tmpCurrentTemp;
	}

	protected abstract Collection<PathT> getConnectedPaths(ModuleInstT module);

	protected double currentSystemCost(){
		int totalWireLength = 0;
		/*int tmp = 0;
		if(currentMove.getBlock0() != null){
			for(PathT wire : getConnectedPaths(currentMove.getBlock0())){
				wire.calculateLength();
				tmp = tmp + wire.getLength();
			}
			//System.out.println(currentMove.block0.getName()+":"+currentMove.block0.getConnectedPaths().size());
		}
		tmp = 0;
		if(currentMove.getBlock1() != null){
			for(PathT wire : getConnectedPaths(currentMove.getBlock1())){
				wire.calculateLength();
				tmp = tmp + wire.getLength();
			}
			//System.out.println(currentMove.block1.getName()+":"+currentMove.block1.getConnectedPaths().size());
		}*/
		int maxPathLength = 0;
		for(PathT path : allPaths){
			if (PARANOID) {
				int prevLength = path.getLength();
				path.calculateLength();
				if (path.getLength() != prevLength) {
					throw new RuntimeException("Path was not up to date: "+path.getName()+" was "+prevLength+", but changed to "+path.getLength()+" when recalculated");
				}
			}
			totalWireLength += path.getLength();
			if(path.getLength() > maxPathLength){
				maxPathLength = path.getLength();
			}
		}
		return alpha * totalWireLength;
	}

	protected static int getDistance(Tile a, Tile b) {
		TileRectangle rect = new SimpleTileRectangle();
		rect.extendTo(a);
		rect.extendTo(b);
		final int largerDimension = rect.getLargerDimension();
		return largerDimension;
	}
}
