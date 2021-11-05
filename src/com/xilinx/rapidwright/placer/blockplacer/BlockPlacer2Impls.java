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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.AbstractModuleInst;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.ModuleImpls;
import com.xilinx.rapidwright.design.ModuleImplsInst;
import com.xilinx.rapidwright.design.ModulePlacement;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class BlockPlacer2Impls extends BlockPlacer2<ModuleImpls, ModuleImplsInst, ModulePlacement, ImplsPath> {

    private final List<ModuleImplsInst> moduleInstances;
    private Map<Site, ModuleImplsInst> currentAnchors = new HashMap<>();

    private final AbstractOverlapCache overlaps;

    private final Map<ModuleImplsInst, Set<ImplsPath>> modulesToPaths = new HashMap<>();

    public BlockPlacer2Impls(Design design, List<ModuleImplsInst> moduleInstances, boolean ignoreMostUsedNets, Path graphData, AbstractOverlapCache overlapCache) {
        super(design, ignoreMostUsedNets, graphData);

        this.moduleInstances = moduleInstances;
        overlaps = overlapCache;
    }

    public BlockPlacer2Impls(Design design, List<ModuleImplsInst> moduleInstances, boolean ignoreMostUsedNets, Path graphData) {
        this(design, moduleInstances, ignoreMostUsedNets, graphData, new RegionBasedOverlapCache(design.getDevice(), moduleInstances));
    }

    public BlockPlacer2Impls(Design design, List<ModuleImplsInst> moduleInstances) {
        this(design, moduleInstances, true, null);
    }

    @Override
    public void setTempAnchorSite(ModuleImplsInst hm, ModulePlacement placement) {
        placeHm(hm, placement);
    }

    @Override
    List<ModuleImplsInst> getModuleImpls(boolean debugFlow) {
        return moduleInstances;
    }

    @Override
    Collection<ModulePlacement> getAllPlacements(ModuleImplsInst hm) {
        return hm.getModule().getAllPlacements();
    }

    @Override
    void unsetTempAnchorSite(ModuleImplsInst hm) {
        unplaceHm(hm);
    }

    @Override
    Comparator<ModulePlacement> getInitialPlacementComparator() {
        Tile center = dev.getTile(dev.getRows()/2, dev.getColumns()/2);
        return Comparator.comparingInt(i -> i.placement.getTile().getManhattanDistance(center));
    }

    @Override
    void placeHm(ModuleImplsInst hm, ModulePlacement placement) {
        if (hm.getPlacement() != null) {
            currentAnchors.remove(hm.getPlacement().placement);
            overlaps.unplace(hm);
        }
        if (!currentAnchors.containsKey(placement.placement)) {
            currentAnchors.put(placement.placement, hm);
        }
        hm.place(placement);
        overlaps.place(hm);
    }

    @Override
    void unplaceHm(ModuleImplsInst hm) {
        if (hm.getPlacement() != null) {
            overlaps.unplace(hm);
            currentAnchors.remove(hm.getPlacement().placement);
        }
        hm.unplace();
    }

    private ImplsInstancePort toImplsInstancePort(EDIFPortInst portInst, Map<EDIFCellInst, Cell> edifToPhysical, Map<EDIFCellInst, ModuleImplsInst> edifToModule) {
        if (portInst.getCellInst() != null) {
            EDIFCellInst cellInst = portInst.getCellInst();

            //The EDIF cell can either be represented by a physical cell, or by a module. Checking both cases.
            Cell cell = edifToPhysical.get(cellInst);
            ModuleImplsInst module = edifToModule.get(cellInst);

            if (cell == null && module == null) {
                if (cellInst.getName().equals("VCC") || cellInst.getName().equals("GND")) {
                    return null;
                }
                throw new RuntimeException("No physical representation of EDIF cellinst " + cellInst.getName());
            } else if (cell != null && module != null) {
                throw new RuntimeException("Duplicate representation of EDIF cellinst "+cellInst.getName());
            }

            if (cell != null) {
                SitePinInst spi = cell.getSitePinFromPortInst(portInst, null);
                if (spi == null) {
                    throw new RuntimeException("while creating an ImplsInstancePort for " + portInst
                            + ", could not find the port in cell " + cell
                            + ". Are physical net names consistent? Consider using DesignTools.makePhysNetNamesConsistent before placement"
                    );
                }
                return new ImplsInstancePort.SitePinInstPort(spi);
            } else {
                return module.getPort(portInst.getName());
            }
        } else {
            //Toplevel IO without an IOB, ignoring this
            return null;
        }
    }

    @Override
    protected void populateAllPaths() {

        Map<EDIFCellInst, Cell> edifToPhysical = design.getCells().stream().collect(Collectors.toMap(Cell::getEDIFCellInst, Function.identity()));
        Map<EDIFCellInst, ModuleImplsInst> edifToModule = moduleInstances.stream().collect(Collectors.toMap(AbstractModuleInst::getCellInst, Function.identity()));
        for (EDIFCellInst cellInst : design.getTopEDIFCell().getCellInsts()) {
            Cell cell = edifToPhysical.get(cellInst);
            ModuleImplsInst module = edifToModule.get(cellInst);
            if (cell == null && module == null) {
                if (!cellInst.getName().equals("VCC") && !cellInst.getName().equals("GND")) {
                    throw new RuntimeException("No physical representation of EDIF cellinst " + cellInst.getName());
                }
            } else if (cell != null && module != null) {
                throw new RuntimeException("Duplicate representation of EDIF cellinst "+cellInst.getName());
            }
        }

        allPaths = new HashSet<>();
        for (EDIFNet net : design.getTopEDIFCell().getNets()) {
            ImplsPath path = new ImplsPath(net.getName());
            for (EDIFPortInst portInst : net.getPortInsts()) {
                ImplsInstancePort implsInstancePort = toImplsInstancePort(portInst, edifToPhysical, edifToModule);
                if (implsInstancePort != null) {
                    path.addPort(implsInstancePort);
                }
            }
            if (path.getSize() > 1) {
                allPaths.add(path);

                for (ImplsInstancePort port : path.ports) {
                    if (!(port instanceof ImplsInstancePort.InstPort)) {
                        continue;
                    }
                    ModuleImplsInst instance = ((ImplsInstancePort.InstPort) port).getInstance();
                    modulesToPaths.computeIfAbsent(instance, x -> new HashSet<>()).add(path);
                }
            }
        }
    }

    @Override
    protected boolean checkValidPlacement(ModuleImplsInst hm) {
        if (hm.getPlacement() == null) {
            return false;
        }

        return overlaps.isValidPlacement(hm);
    }

    private boolean checkValidPlacementLegacy(ModuleImplsInst hm) {
        final boolean debugValidPlacement = false;
        for(ModuleImplsInst other : hardMacros){
            if (other == hm) {
                continue;
            }
            if (other.getPlacement() == null) {
                continue;
            }
            if (hm.getPlacement().placement == other.getPlacement().placement) {
                if (debugValidPlacement) System.out.println("not valid because "+ hm.getName()+" has same anchor as "+other.getName()+": "+ hm.getPlacement().placement);

                return false;
            }
            if (hm.overlaps(other)){
                if (debugValidPlacement) System.out.println("not valid because "+ hm.getName()+" overlaps "+other.getName());
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFinalPlacement() {
        overlaps.printStats();
    }

    @Override
    protected ModulePlacement getTempAnchorSite(ModuleImplsInst mi) {
        return mi.getPlacement();
    }

    @Override
    protected int getTileSize(ModuleImplsInst hm) {
        if (hm.getCurrentModuleImplementation() == null) {
            return 0;
        }
        return hm.getCurrentModuleImplementation().getTileFootprintSize();
    }

    @Override
    protected boolean isInRange(ModulePlacement current, ModulePlacement newPlacement) {
        return getDistance(current.placement.getTile(), newPlacement.placement.getTile()) <= rangeLimit;
    }

    @Override
    protected Tile getPlacementTile(ModulePlacement placement) {
        return placement.placement.getTile();
    }

    @Override
    protected ModuleImplsInst getHmCurrentlyAtPlacement(ModulePlacement placement) {
        return currentAnchors.get(placement.placement);
    }

    @Override
    protected ModulePlacement getCurrentPlacement(ModuleImplsInst selected) {
        return selected.getPlacement();
    }

    @Override
    protected Collection<ImplsPath> getConnectedPaths(ModuleImplsInst module) {
        return modulesToPaths.get(module);
    }

    @Override
    protected Tile getCurrentAnchorTile(ModuleImplsInst mi) {
        return mi.getPlacement().placement.getTile();
    }

    private void dumpDot(java.nio.file.Path path) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path))) {
            new DotModuleImplsDumper(false).doDump(new DotModuleImplsDumper.ModuleImplsDumpData(design, moduleInstances, allPaths, modulesToPaths), pw, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void initialPlacement() {
        super.initialPlacement();
        //dumpDot(Paths.get("/tmp/initial.dot"));
    }

    @Override
    protected void ignorePath(ImplsPath path) {
        for (Set<ImplsPath> value : modulesToPaths.values()) {
            value.remove(path);
        }
    }
}
