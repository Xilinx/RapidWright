/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleImpls;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;

public class BlockPlacer2Module extends BlockPlacer2<Module, HardMacro, Site, Path>{
    /** The current location of all hard macros */
    private HashMap<Site, HardMacro> currentPlacements;
    private Map<ModuleInst, HardMacro> macroMap;

    public BlockPlacer2Module(Design design, boolean ignoreMostUsedNets, java.nio.file.Path graphData) {
        super(design, ignoreMostUsedNets, graphData);
    }
    public BlockPlacer2Module(Design design) {
        super(design, true, null);
    }

    @Override
    List<HardMacro> getModuleImpls(boolean debugFlow) {
        List<HardMacro> hardMacros = new ArrayList<>();
        macroMap = new HashMap<ModuleInst, HardMacro>();

        // Find all valid placements for each module
        for(ModuleImpls moduleImpls : design.getModules()){
            for(Module module : moduleImpls){
                ArrayList<Site> sites = module.getAllValidPlacements();
                if(sites.size() == 0){
                    sites = module.calculateAllValidPlacements(dev);
                }
                if(debugFlow){
                    // Need to check if placements will work with existing implementation
                    ArrayList<Site> openSites = new ArrayList<Site>();
                    for(Site s : sites){
                        if(module.isValidPlacement(s, design)){
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

        // Create Hard Macro objects from module instances;
        for(ModuleInst mi : design.getModuleInsts()){
            HardMacro hm = new HardMacro(mi);
            hardMacros.add(hm);
            hm.setValidPlacements();
            macroMap.put(mi, hm);
        }
        return hardMacros;
    }

    @Override
    Comparator<Site> getInitialPlacementComparator() {
        Tile center = dev.getTile(dev.getRows()/2, dev.getColumns()/2);
        return Comparator.comparingInt(i -> i.getTile().getManhattanDistance(center));
    }

    @Override
    public void setTempAnchorSite(HardMacro hm, Site site) {
        hm.setTempAnchorSite(site, currentPlacements);
    }

    @Override
    protected void initialPlacement() {
        currentPlacements = new HashMap<>();
        super.initialPlacement();
    }

    @Override
    void placeHm(HardMacro hm, Site site) {
        if(!hm.place(site)){
            throw new RuntimeException("ERROR: Failed to place " + hm.getName() + " at " + site);
        }
        hm.calculateTileSize();
    }

    @Override
    void unplaceHm(HardMacro hm) {
        hm.setTileSize(0);
        hm.unplace();
    }

    @Override
    void unsetTempAnchorSite(HardMacro hm) {
        hm.unsetTempAnchorSite();
    }

    @Override
    protected boolean checkValidPlacement(HardMacro hm) {
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

    @Override
    protected boolean isInRange(Site current, Site newPlacement) {
        return getDistance(current.getTile(), newPlacement.getTile()) <= rangeLimit;
    }

    @Override
    protected Tile getPlacementTile(Site placement) {
        return placement.getTile();
    }

    @Override
    protected Site getCurrentPlacement(HardMacro selected) {
        return selected.getTempAnchorSite();
    }



    @Override
    protected void doFinalPlacement() {

        // Sort hard macros, largest first to place them first
        HardMacro[] array = new HardMacro[hardMacros.size()];
        array = hardMacros.toArray(array);
        Arrays.sort(array);

        HashSet<Tile> usedTiles = new HashSet<Tile>();
        // Added variable for genreating partial dcp
        boolean save_and_exit = false;
        // Perform final placement of all hard macros
        for(HardMacro hm : array){
            //System.out.println(moveCount.get(hm) + " " + hm.tileSize + " " + hm.getName());
            HashSet<Tile> footPrint = isValidPlacement((ModuleInst)hm, hm.getModule().getAnchor().getSite(), hm.getTempAnchorSite().getTile(), usedTiles);
            if(footPrint == null){

                if(!placeModuleNear((ModuleInst)hm, hm.getTempAnchorSite().getTile(), usedTiles)){
                    System.out.println("Saving as debug.");
                    // Updated code. Goal: if placement fails, unplace that IP and generate .dcp in order to let vivado continue PAR
                    if(save_partial_dcp) {
                        save_and_exit = true;
                        System.out.println("ERROR: Placement failed for "+hm.getName());
                        hm.unplace();
                    } else
                        MessageGenerator.briefErrorAndExit("ERROR: Placement failed, couldn't find valid site for " + hm.getName());
                }
            }
            else{
                usedTiles.addAll(footPrint);
                if(!hm.place(hm.getTempAnchorSite())){
                    // Updated code. Goal: if placement fails, unplace that IP and generate .dcp in order to let vivado continue PAR
                    if(save_partial_dcp) {
                        save_and_exit = true;
                        System.out.println("ERROR: Placement failed for "+hm.getName());
                        hm.unplace();
                    } else
                        MessageGenerator.briefErrorAndExit("ERROR: Problem placing " + hm.getName() + " on site: " + hm.getTempAnchorSite());
                }
            }
        }

        System.out.println("Cost = " + currentSystemCost());

        design.clearUsedSites();
        for(SiteInst i : design.getSiteInsts()){
            i.place(i.getSite());
        }

        // Updated code. Goal: if placement fails, unplace that IP and generate .dcp in order to let vivado continue PAR
        if(save_and_exit) {
            String placedDCPName = "partialy_placed.dcp";
            design.writeCheckpoint(placedDCPName);
            MessageGenerator.briefErrorAndExit("ERROR: Placement failed, couldn't find valid site for all the IPs. Partially placed .dcp saved for debug " );
        }
    }

    @Override
    protected Tile getCurrentAnchorTile(HardMacro mi) {
        Site tempPlacement = getTempAnchorSite(mi);
        if (tempPlacement != null) {
            return tempPlacement.getTile();
        }
        return mi.getAnchor().getTile();
    }

    @Override
    protected Site getTempAnchorSite(HardMacro mi) {
        return mi.getTempAnchorSite();
    }

    @Override
    protected Collection<Path> getConnectedPaths(HardMacro module) {
        return module.getConnectedPaths();
    }

    @Override
    Collection<Site> getAllPlacements(HardMacro hm) {
        return hm.getValidPlacements();
    }

    @Override
    protected int getTileSize(HardMacro hm) {
        return hm.getTileSize();
    }

    @Override
    protected void populateAllPaths(){
        for(Net net : design.getNets()){
            if(net.isStaticNet() || net.isClockNet()) continue;
            SitePinInst src = net.getSource();
            ArrayList<SitePinInst> snks = new ArrayList<SitePinInst>();
            if(src == null){
                // TODO - This should not happen
                //System.out.println("ERROR: Need to find out why net: " + net.getName() + " has no driver\n\n" + net.toString() );
                continue;
            }
            ModuleInst srcModInst = src.getSiteInst().getModuleInst();
            for(SitePinInst p : net.getPins()){
                if(p == src) continue;
                if(srcModInst != p.getSiteInst().getModuleInst()){
                    snks.add(p);
                }
            }

            if(snks.size() > 0){
                Path newPath = new Path(net.getName());
                newPath.addPin(src, macroMap);
                for(SitePinInst snk : snks){
                    newPath.addPin(snk, macroMap);
                }
                allPaths.add(newPath);
            }
        }

        if (allPaths.isEmpty()) {
            throw new RuntimeException("no paths found");
        }
        for(Path pa : allPaths){
            for(PathPort po : pa){
                if(po.getBlock() != null){
                    po.getBlock().addConnectedPath(pa);
                }
            }
        }
    }

    @Override
    protected HardMacro getHmCurrentlyAtPlacement(Site placement) {

        return currentPlacements.get(placement);
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
            Site[] candidateSites = dev.getAllCompatibleSites(modInst .getAnchor().getSiteTypeEnum());
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

    protected HashSet<Tile> isValidPlacement(ModuleInst modInst, Site anchorSite, Tile proposedAnchorTile, HashSet<Tile> usedTiles){
        if(usedTiles.contains(proposedAnchorTile)){
            return null;
        }

        modInst.getAnchor().getSiteTypeEnum();
        //Previously:
        //Site newSite2 = modInst.getAnchor().getSite().getCorrespondingSite(modInst.getAnchor().getSiteTypeEnum(), proposedAnchorTile);
        //Now
        Site newSite2 = modInst.getModule().getAnchor().getSite().getCorrespondingSite(modInst.getAnchor().getSiteTypeEnum(), proposedAnchorTile);

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

    @Override
    protected void ignorePath(Path path) {
        for (HardMacro hardMacro : hardMacros) {
            hardMacro.getConnectedPaths().remove(path);
        }
    }
}
