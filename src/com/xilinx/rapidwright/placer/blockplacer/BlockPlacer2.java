/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.io.UncheckedIOException;
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
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.AbstractModuleInst;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SimpleTileRectangle;
import com.xilinx.rapidwright.design.TileRectangle;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;

/**
 * An alternate implementation of {@link BlockPlacer}.  This placer
 * tends to do better but with longer runtime.
 * @author Chris Lavin
 */
public abstract class BlockPlacer2<ModuleT, ModuleInstT extends AbstractModuleInst<ModuleT, ?,?>, PlacementT, PathT extends AbstractPath<?, ModuleInstT>> extends AbstractBlockPlacer<ModuleInstT, PlacementT> {

    /**
     * Default value for constructor parameter denseDesign
     */
    public static final boolean DEFAULT_DENSE = false;
    /**
     * Default value for constructor parameter effort
     */
    public static final float DEFAULT_EFFORT = 5;
    /**
     * Default value for constructor parameter focusOnWorstModules
     */
    public static final boolean DEFAULT_FOCUS_ON_WORST = false;

    /** Enable extra sanity checks? */
    protected static final boolean PARANOID = false;

    /**
     * The number of recursion steps we may take to push other modules out of the way
     */
    private static final int PUSH_AWAY_RECURSION_DEPTH = 1;
    /**
     * The maximum number of modules that may be pushed out of the way in each recursion step
     */
    private static final int MAX_PUSHED_MIS = 1;
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
    private Move2<ModuleInstT, PlacementT, PathT> currentMove;
    /** The current temperature of the simulated annealing schedule */
    private double currentTemp;
    /** Number of accepted moves in the current temperature step */
    private int currentAcceptedMoveCount = 0;
    /** Total number of moves through the entire execution of the annealer */
    private int totalMoves;
    /** Measures the current move acceptance rate */
    private double moveAcceptanceRate = 1.0;
    /** */
    private double goldenRate = 0.44;
    //private final double goldenRate = 0.20;
    private long seed;

    protected final TileRectangle placementArea;

    // Final Results
    /** */
    public double finalSystemCost;
    /** */
    public double finalBestCost;
    /** */
    public double placerRuntime;
    /** */
    /** */
    public double rangeLimit;
    public boolean verbose = true;

    public static int DEBUG_LEVEL = 1;
    // DEBUG
    //private HashMap<HardMacro, Integer> moveCount = new HashMap<HardMacro, Integer>();
    private final double alpha;
    private final double beta;

    private final boolean ignoreMostUsedNets;
    private final boolean denseDesign;
    private final float effort;
    private final boolean focusOnWorstModules;

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
     * @param design the design
     * @param ignoreMostUsedNets ignore nets that are used almost everywhere. they are likely clocks, which use special
     *                           routing ressources. making them small does not gain QoR, but costs placer runtime
     * @param graphData output file for key placer stats to later graph them
     * @param denseDesign if set to true, tune algorithm towards having many overlaps. For sparse designs,
     *                    setting this will make the placer slower
     * @param effort Placer effort. Higher values will achieve better QoR. Linearly increases runtime.
     * @param focusOnWorstModules Spend more time on modules with worst placement
     * @param placementArea Only place in specific area
     */
    public BlockPlacer2(Design design, boolean ignoreMostUsedNets, Path graphData, boolean denseDesign, float effort, boolean focusOnWorstModules, TileRectangle placementArea) {
        this.design = design;
        this.dev = design.getDevice();
        this.ignoreMostUsedNets = ignoreMostUsedNets;
        this.denseDesign = denseDesign;
        this.effort = effort;
        this.focusOnWorstModules = focusOnWorstModules;
        this.placementArea = placementArea;
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

    public BlockPlacer2(Design design, boolean ignoreMostUsedNets, Path graphData, boolean denseDesign, float effort, boolean focusOnWorstModules, TileRectangle placementArea, Map<ModuleInstT, Site> lockedPlacements) {
        this(design, ignoreMostUsedNets, graphData, denseDesign, effort, focusOnWorstModules, placementArea);
        this.lockedPlacements = lockedPlacements;
    }

    /**
     * Sets the random seed to be used in this placer
     * @param seed
     */
    public void setSeed(long seed) {
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
    public void initializePlacer(boolean debugFlow) {
        currentMove = new Move2<>(this);
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
    abstract List<PlacementT> getAllPlacements(ModuleInstT hm);
    abstract void unsetTempAnchorSite(ModuleInstT hm);

    private Comparator<PlacementT> getInitialPlacementComparator(TileRectangle placementArea) {
        Tile center = placementArea != null ? placementArea.getCenter(dev) : dev.getTile(dev.getRows()/2, dev.getColumns()/2);
        return Comparator.comparingInt(i -> getPlacementTile(i).getManhattanDistance(center));
    }

    abstract void placeHm(ModuleInstT hm, PlacementT placement);
    abstract void unplaceHm(ModuleInstT hm);

    protected void initialPlacement() {
        if (hardMacros.stream().allMatch(hm->getCurrentPlacement(hm) != null)) {
            for (PathT path : allPaths) {
                path.calculateLength();
            }

            System.out.println("Pre-placed design! Cost: "+currentSystemCost());
            unplaceDesign();
        }

        possiblePlacements = new HashMap<>();

        // Place hard macros for initial placement
        for (ModuleInstT hm : hardMacros) {
            PriorityQueue<PlacementT> sites = new PriorityQueue<>(1024, getInitialPlacementComparator(placementArea));

            final AbstractValidPlacementCache<PlacementT> placementCache = possiblePlacements.computeIfAbsent(hm.getModule(), module -> {
                List<PlacementT> allPlacements = getAllPlacements(hm);

                if (placementArea != null) {
                    allPlacements = allPlacements.stream()
                            .filter(p -> placementArea.isInside(getPlacementTile(p)))
                            .collect(Collectors.toList());
                }
                return SortedValidPlacementCache.fromList(allPlacements, this, denseDesign);
            });


            sites.addAll(placementCache.getAll());
            boolean found = false;
            while (!sites.isEmpty()) {
                PlacementT site = sites.remove();
                setTempAnchorSite(hm, site);
                if (checkValidPlacement(hm)) {
                    placeHm(hm, site);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new RuntimeException("no initial place for "+hm.getName());
            }
        }

        for (PathT path : allPaths) {
            path.calculateLength();
        }

        ArrayList<ModuleInstT> prunedList = new ArrayList<>();
        for (ModuleInstT hm : new ArrayList<>(hardMacros)) {
            if (getAllPlacements(hm).size() > 2) prunedList.add(hm);
            else {
                System.err.println("Not adding HM since it only has one placement: "+hm.getName());
            }
        }
        hardMacros = prunedList;
    }

    private void unplaceDesign() {
        // Place hard macros for initial placement
        currentMove = new Move2<>(this);
        totalMoves = 0;
        for (ModuleInstT hm : hardMacros) {
            unplaceHm(hm);
            unsetTempAnchorSite(hm);
        }
    }

    /**
     * Finds all paths between unplaced components.
     */
    protected abstract void populateAllPaths();

    protected abstract boolean checkValidPlacement(ModuleInstT hm);
    protected abstract List<ModuleInstT> getAllOverlaps(ModuleInstT hm);

    public double calculateStartTemp(int maxInnerIteration) {
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
        final int iter = maxInnerIteration / hardMacros.size();
        for (ModuleInstT selectedHD : hardMacros) {
            for (int i = 0; i< iter; i++) {
                if (getNextMove(selectedHD)) {
                    currentCost = currentSystemCost();
                    arrayCosts.add(currentCost);
                    avgCost = avgCost + currentCost;
                    sqCost = sqCost + (currentCost * currentCost);


                    r = rand.nextDouble();
                    double costChange = currentCost - previousCost;
                    boolean acceptMove = (r < Math.exp(-costChange / myTemp));

                    if (acceptMove) {
                        //System.out.println("start temp accept for "+costChange+": "+r+"<"+Math.exp(-costChange/myTemp)+" curr: "+currentCost);
                        acceptedMoveCount++;
                        previousCost = currentCost;
                    } else {
                        //System.out.println("start temp not accept");
                        // Undo the move, we are not accepting it
                        currentMove.undoMove();

                        double testCost = currentSystemCost();
                        if (testCost != previousCost) {
                            throw new RuntimeException("ERROR_startTemp: gUndo move caused improper system cost change: prev=" + previousCost + " incorrect=" + testCost + " move= " + currentMove.toString());
                        }
                    }
                } else {
                    break;
                }
            }// Move loop
        }
        if (acceptedMoveCount>0) {
            avgCost = avgCost/acceptedMoveCount;
        } else {
            avgCost = 0;
        }
        for (double c : arrayCosts) {
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
        pw.println(currentMove.blocks.stream().map(AbstractModuleInst::getName).collect(Collectors.joining(", ")));
        pw.println(currentMove.placements.stream().map(Object::toString).collect(Collectors.joining(", ")));

        hardMacros.stream().sorted(Comparator.comparing(hm->hm.getName()))
                .forEach(hm -> pw.println(hm.getName()+": "+getCurrentPlacement(hm)));
        final int[] cost = {0};
        allPaths.stream().sorted(Comparator.comparing(p->p.getName()))
                .forEach(path -> {
                    pw.println(path.getName()+": "+path.getLength());
                    cost[0] +=path.getLength();
                    /*path.streamTiles().map(Object::toString).sorted()
                            .forEach(o->pw.println("    "+o));*/
                });
        System.out.println("===");
        System.out.println("cost = " + cost[0]);
    }

    protected abstract void doFinalPlacement();

    protected abstract Tile getCurrentAnchorTile(ModuleInstT mi);

    protected abstract PlacementT getTempAnchorSite(ModuleInstT mi);

    public double placeDesign(boolean debugFlow) {
        rand = new Random(seed);
        boolean finished = false;
        double r;
        //MessageGenerator.printHeader(this.getClass().getCanonicalName());
        long start = System.currentTimeMillis();
        //System.out.println("Initialization Time: " + ((System.currentTimeMillis()-start)/1000.0) + " secs");
        start = System.currentTimeMillis();
        initializePlacer(debugFlow);
        initialPlacement();
        //HandPlacer.openDesign(design);
        int totalFootprint = 0;
        for (ModuleInstT hm : hardMacros) {
            totalFootprint += getTileSize(hm);
        }
        int squareWidth = (int) (Math.sqrt(totalFootprint) * 1.5);

        System.out.println("squareWidth = " + squareWidth);

        int maxInnerIteration = (int)(effort * Math.pow(hardMacros.size(), 1.3333));
        //maxInnerIteration = (int)(Math.pow(Math.max(dev.getColumns(), dev.getRows()), 1.3333));
        if (hardMacros.size() < 2 || allPaths.size() == 0) {
            finished = true;
            maxInnerIteration = 0;
        }

        //rangeLimit = Math.max(dev.getColumns(), dev.getRows());
        rangeLimit = Math.max(squareWidth, squareWidth);
        currentTemp = calculateStartTemp(maxInnerIteration);
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
        rangeLimit = getMaxRangeLimit();
        OUTER: while (!finished) {
            temperatureStep(maxInnerIteration);

            rangeLimit = rangeLimit * (1.0-goldenRate + moveAcceptanceRate);
            rangeLimit = Math.min(rangeLimit, getMaxRangeLimit());
            rangeLimit = Math.max(rangeLimit, 5.0);

            currentTemp = updateTemperature();

            if (currentTemp < 0.005 * (prevSystemCost /allPaths.size())) {
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
        if (DEBUG_LEVEL > 0) System.out.println(seed + ": " + currSystemCost + " / " + bestSoFar + " Runtime: " + placerRuntime + "secs");
        if (DEBUG_LEVEL > 0) System.out.printf("  Perturbation Time: %.3f secs (%9.0f moves/sec)\n", placerRuntime,(totalMoves/placerRuntime));

        if (DEBUG_LEVEL > 0) System.out.println("Final System Cost: " + finalSystemCost);
        /*
        HashSet<HardMacro> fineTunePlacement = new HashSet<HardMacro>();

        for (HardMacro hm : hardMacros) {
            if (hm.tileSize < 60) {
                fineTunePlacement.add(hm);
            }
        }

        for (HardMacro hm : fineTunePlacement) {
            // Keep the original spot, in the case we suggest a worst spot
            Site original = hm.getTempAnchorSite();
            int originalMaxLength = 0;
            // Determine all of the connecting points to this hard macro
            HashSet<Point> pointsList = new HashSet<Point>();
            for (Path path : hm.getConnectedPaths()) {
                if (path.getLength() > originalMaxLength) {
                    originalMaxLength = path.getLength();
                }
                for (PathPort pp : path) {
                    if (pp.getBlock()== null || !pp.getBlock().equals(hm)) {
                        pointsList.add(new Point(pp.getPortTile()));
                    }
                }
            }

            Point center = SmallestEnclosingCircle.getCenterPoint(pointsList);
            Tile centroid = dev.getTile("INT_X" + center.x + "Y" + center.y);
            Site newCandidateSite = null;
            if (centroid != null && centroid.getSites() != null && centroid.getSites().length > 0) {
                newCandidateSite = centroid.getSites()[0];
            }


            if (newCandidateSite != null) {
                if (DEBUG_LEVEL > 0) System.out.println("Moving " + hm.getName() + " from " + original.getTile() + " to " + newCandidateSite.getTile());
                currentMove.setMove(newCandidateSite, original, null, hm);
                hm.setTempAnchorSite(newCandidateSite, currentPlacements);
                currentSystemCost();
                int longestPath = 0;
                for (Path path : hm.getConnectedPaths()) {
                    if (path.getLength() > longestPath) {
                        longestPath = path.getLength();
                    }
                }
                if (originalMaxLength+5 < longestPath) {
                    if (DEBUG_LEVEL > 0) System.out.println("  Undo move: old max length: " + originalMaxLength + " new max length " + longestPath);
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

    private List<ModuleInstT> weighByAvgConnection() {
        if (focusOnWorstModules) {
            final Map<ModuleInstT, Float> avgLength = avgConnectionLength();
            List<ModuleInstT> weighted = new ArrayList<>();
            avgLength.forEach((mi, weight) -> {
                int count = Math.max(1, weight.intValue());
                for (int i = 0; i < count; i++) {
                    weighted.add(mi);
                }
            });
            return weighted;
        }
        return hardMacros;
    }

    private void temperatureStep(int maxInnerIteration) {
        final List<ModuleInstT> weighted = weighByAvgConnection();

        currentAcceptedMoveCount = 0;
        moveCount = 0;
        int badMoveCount = 0;
        int badAcceptedMoveCount = 0;
        double totalMovesCost = 0.0;
        for (int inner_iterate = 0; inner_iterate< maxInnerIteration; inner_iterate++) {
        //for (int inner_iterate = 0; inner_iterate< (10*rangeLimit); inner_iterate++) {
        //for (int inner_iterate = 0; inner_iterate< (dev.getColumns()*dev.getRows()); inner_iterate++) {
            //ModuleInstT selectedHD = hardMacros.get(rand.nextInt(hardMacros.size()-1));
            ModuleInstT selectedHD = weighted.get(rand.nextInt(weighted.size()-1));


            if (PARANOID) {
                double testCost = currentSystemCost();
                if (testCost != prevSystemCost) {
                    throw new RuntimeException("ERROR: Improper system cost before creating new move: prev=" + prevSystemCost + " incorrect=" + testCost);
                }
            }

            if (getNextMove(selectedHD)) {
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
                        //calcConnectedCost(currentMove.getBlock0(), currentMove.getBlock1())
                        throw new RuntimeException("Cost change differs. Recalc: " + changeInCostRecalc + ", efficient: " + changeInCost + " at move " + totalMoves);
                    }
                }



                moveCount++;
                totalMovesCost += changeInCost;
                if (currSystemCost < bestSoFar) {
                    bestSoFar = currSystemCost;
                    //if (bestSoFar==18875.0) {
                        //break OUTER;
                    //}
                }

                double r = rand.nextDouble();
                int numPath0 = 0;
                int numPath1 = 0;
                double tmp =0.0;
                double AvgChange = 0.0;
                /*if (currentMove.getBlock0() != null) {
                    for (PathT wire : getConnectedPaths(currentMove.getBlock0())) {
                        wire.calculateLength();
                        tmp = tmp + wire.getLength();
                    }
                    numPath0 = getConnectedPaths(currentMove.getBlock0()).size();
                    AvgChange = AvgChange + tmp/numPath0;
                }
                tmp =0.0;
                if (currentMove.getBlock1() != null) {
                    for (PathT wire : getConnectedPaths(currentMove.getBlock1())) {
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
                if (changeInCost > 0) badMoveCount++;

                if (acceptMove) {
                    currentAcceptedMoveCount++;
                    prevSystemCost = currSystemCost;
                    if (changeInCost > 0) badAcceptedMoveCount++;
                }
                else {
                    // Undo the move, we are not accepting it

                    currentMove.undoMove();
                    if (PARANOID) {
                        double testCost = currentSystemCost();
                        if (testCost != prevSystemCost) {
                            throw new RuntimeException("ERROR: 3 Undo move caused improper system cost change: prev=" + prevSystemCost + " incorrect=" + testCost + " move= " + currentMove.toString());
                        }
                    }
                }
                //moveAcceptanceRate = ((double)currentAcceptedMoveCount) / moveCount;
                //moveAcceptanceRate = ((double)currentAcceptedMoveCount) / (Math.min(moveCount, hardMacros.size()));
            }// Move loop

        }//inner loop
        if (moveCount >0) {
            moveAcceptanceRate = ((double)currentAcceptedMoveCount) / moveCount;
        } else {
            moveAcceptanceRate = 0;
        }
        //MOVES = ACCEPTED/TOTAL
        if (Double.isNaN(currentTemp)) {
            throw new RuntimeException("nan temperature!");
        }

        if (currSystemCost < 0 || Double.isNaN(currSystemCost)) {
            throw new RuntimeException("invalid cost: "+currSystemCost);
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
        if (DEBUG_LEVEL > 0) System.out.printf("MOVES:%7d/%7d COST:%7.1f AVG_COST/MOVE:%7.1f TEMP:%7.1f ACCEPTANCE_RATE:%5.1f%% BEST:%7.1f BAD:%4.1f%%\n",currentAcceptedMoveCount, moveCount, prevSystemCost, totalMovesCost/ moveCount, currentTemp, moveAcceptanceRate*100, bestSoFar, 100.0*badAcceptedMoveCount/badMoveCount);
    }

    protected abstract int getTileSize(ModuleInstT hm);

    public Collection<PathT> getPaths() {
        return allPaths;
    }

    public int getMaxRangeLimit() {
        if (placementArea!=null) {
            return placementArea.getLargerDimension();
        }
        return Math.max(dev.getColumns(), dev.getRows());
    }

    enum Direction{UP, DOWN, LEFT, RIGHT};



    public Site getPrimitiveSiteFromTile(Tile tile, SiteTypeEnum type) {
        for (Site p : tile.getSites()) {
            if (p.isCompatibleSiteType(type)) {
                return p;
            }
        }
        return null;
    }


    protected abstract boolean isInRange(PlacementT current, PlacementT newPlacement);


    protected abstract Tile getPlacementTile(PlacementT placement);

    private boolean getNextMoveRec(ModuleInstT selected, int pushAwayDepth, PlacementT center) {
        PlacementT site0 = getCurrentPlacement(selected);
        if (!currentMove.addBlock(selected, site0)) {
            return false;
        }

        final AbstractValidPlacementCache<PlacementT> pp = possiblePlacements.get(selected.getModule());
        int rl = pushAwayDepth == 0 ? 5 : (int) rangeLimit;
        List<PlacementT> validSiteRange = pp.getByRangeAround(rl, center);


        int nr_valid_sites = validSiteRange.size();
        if (nr_valid_sites==0) {
            currentMove.removeLastBlock();
            return false;
        }
        LinearCongruentialGenerator iterator = new LinearCongruentialGenerator(nr_valid_sites, rand);

        while (iterator.hasNext()) {
            int rand_site = iterator.nextInt();
            PlacementT site1 = validSiteRange.get(rand_site);

            if (site0.equals(site1)) {
                //if (DEBUG_LEVEL > 1) System.out.println("  SAME SITE");
                continue;
            }

            setTempAnchorSite(selected, site1);


            final List<ModuleInstT> overlaps = getAllOverlaps(selected);
            if (overlaps.isEmpty()) {
                return true;
            }

            if (pushAwayDepth == 0) {
                //Not allowed to move other
                continue;
            }

            if (pushAwayOthers(selected, site0, overlaps, pushAwayDepth)) {
                return true;
            }
        }
        currentMove.removeLastBlock();
        return false;
    }

    private boolean pushAwayOthers(ModuleInstT selected, PlacementT site0, List<ModuleInstT> overlaps, int pushAwayDepth) {

        if (overlaps.size()>MAX_PUSHED_MIS) {
            return false;
        }
        int count = currentMove.countBlocks();
        for (ModuleInstT other : overlaps) {

            PlacementT first = getCurrentPlacement(selected);
            PlacementT second = site0;

            //Randomly swap
            /*if (rand.nextBoolean()) {
                PlacementT temp = second;
                second = first;
                first = temp;
            }*/

            /*if (getNextMoveRec(other, pushAwayAllowance - 1, first)) {
                continue;
            }*/

            if (getNextMoveRec(other, pushAwayDepth - 1, second)) {
                continue;
            }


            //Abort
            while (currentMove.countBlocks()>count) {
                currentMove.removeLastBlock();
            }
            return false;
        }
        return true;
    }

    private boolean getNextMove(ModuleInstT selected) {
        currentMove.clear();

        if (getNextMoveRec(selected, PUSH_AWAY_RECURSION_DEPTH, getCurrentPlacement(selected))) {
            currentMove.calcDeltaCost();
            return true;
        }
        return false;
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

    protected abstract PlacementT getCurrentPlacement(ModuleInstT selected);

    private double updateTemperature() {
        double tmpCurrentTemp = currentTemp;
        if (moveAcceptanceRate>0.96) {
            tmpCurrentTemp = tmpCurrentTemp * 0.5;
        } else if (moveAcceptanceRate > 0.8) {
            tmpCurrentTemp = tmpCurrentTemp * 0.9;
        //} else if (moveAcceptanceRate > 0.15 || rangeLimit > 1.0) {
        } else if (moveAcceptanceRate > 0.15) {
            tmpCurrentTemp = tmpCurrentTemp * 0.95;
        } else {
            tmpCurrentTemp = tmpCurrentTemp * 0.8;
        }
        return tmpCurrentTemp;
    }

    public abstract Collection<PathT> getConnectedPaths(ModuleInstT module);

    protected double currentSystemCost() {
        int totalWireLength = 0;
        /*int tmp = 0;
        if (currentMove.getBlock0() != null) {
            for (PathT wire : getConnectedPaths(currentMove.getBlock0())) {
                wire.calculateLength();
                tmp = tmp + wire.getLength();
            }
            //System.out.println(currentMove.block0.getName()+":"+currentMove.block0.getConnectedPaths().size());
        }
        tmp = 0;
        if (currentMove.getBlock1() != null) {
            for (PathT wire : getConnectedPaths(currentMove.getBlock1())) {
                wire.calculateLength();
                tmp = tmp + wire.getLength();
            }
            //System.out.println(currentMove.block1.getName()+":"+currentMove.block1.getConnectedPaths().size());
        }*/
        int maxPathLength = 0;
        for (PathT path : allPaths) {
            if (PARANOID) {
                int prevLength = path.getLength();
                path.calculateLength();
                if (path.getLength() != prevLength) {
                    throw new RuntimeException("Path was not up to date: "+path.getName()+" was "+prevLength+", but changed to "+path.getLength()+" when recalculated");
                }
            }
            totalWireLength += path.getLength();
            if (path.getLength() > maxPathLength) {
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

    private Map<ModuleInstT, Float> avgConnectionLength() {
        Map<ModuleInstT, Float> result = new HashMap<>();
        for (ModuleInstT hardMacro : hardMacros) {
            float length = 0;
            final Collection<PathT> paths = getConnectedPaths(hardMacro);
            int weight = 0;
            for (PathT p : paths) {
                length += p.getLength();
                weight += p.getWeight();
            }
            final float value = paths.size()>0 ? length / weight : 0;
            result.put(hardMacro, value);
        }
        return result;
    }

    protected void pruneSameConnectionPaths() {
        System.out.println("before pruning: " + allPaths.size());

        //Only keep one copy of paths that connect the same tiles, while increasing its weight
        Map<Set<?>, PathT> seenTileSets = new HashMap<>();
        allPaths.removeIf(p -> {
            Set<?> tileSet = p.getPathConnections();
            PathT seenPath = seenTileSets.get(tileSet);
            if (seenPath!=null) {
                seenPath.increaseWeight();
                return true;
            } else {
                seenTileSets.put(tileSet, p);
                return false;
            }
        });
        System.out.println("after pruning: " + allPaths.size());
    }

    private int undoCount = 0;
    public int incUndoCount() {
        undoCount++;
        return undoCount;
    }
}
