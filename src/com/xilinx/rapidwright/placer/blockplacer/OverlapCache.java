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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.function.Predicate;

import com.xilinx.rapidwright.design.ModuleImplsInstance;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.device.Device;

/**
 * Optimized Detection of overlaps between modules
 *
 * This divides the fabric into square regions of a given side length. For each region, all modules that touch
 * the region are stored. When a module is moved, overlap detection only needs to be performed for modules that
 * touch the same regions as the module that is being moved.
 */
public class OverlapCache extends AbstractOverlapCache {
    private final Device device;
    private final Collection<ModuleImplsInstance> instances;
    private final Collection<ModuleImplsInstance>[][] modulesInArea;

    /**
     * Magic Size found by benchmarking
     */
    public static int DEFAULT_SIZE = 23;

    private final int columnDivider;
    private final int rowDivider;

    private int getColumn(int fabricColumn) {
        return fabricColumn /columnDivider;
    }

    private int getRow(int fabricRow) {
        return fabricRow / rowDivider;
    }


    private int getColumns() {
        return getColumn(device.getColumns()-1)+1;
    }
    private int getRows() {
        return getRow(device.getRows()-1)+1;
    }

    private boolean allTouchedRegionsMatch(ModuleImplsInstance mii, Predicate<Collection<ModuleImplsInstance>> predicate) {
        final RelocatableTileRectangle bb = mii.getBoundingBox();

        final int crMinCol = getColumn(bb.getMinColumn());
        final int crMaxCol = getColumn(bb.getMaxColumn());
        final int crMinRow = getRow(bb.getMinRow());
        final int crMaxRow = getRow(bb.getMaxRow());

        for (int col = crMinCol; col <= crMaxCol; col++) {
            for (int row = crMinRow; row <= crMaxRow; row++) {
                boolean r = predicate.test(modulesInArea[col][row]);
                if (!r) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Remove an Instance from the cache. Has to be called before actually unplacing the instance
     * @param mii  the instance
     */
    @Override
    public void unPlace(ModuleImplsInstance mii) {
        allTouchedRegionsMatch(mii,l->{l.remove(mii); return true;});
    }

    /**
     * Remove an Instance from the cache. Has to be called before after placing the instance
     * @param mii  the instance
     */
    @Override
    public void place(ModuleImplsInstance mii) {
        allTouchedRegionsMatch(mii,l->{l.add(mii); return true;});
    }

    public OverlapCache(Device device, Collection<ModuleImplsInstance> instances, int size) {
        this.device = device;
        this.instances = instances;
        this.columnDivider = size;
        this.rowDivider = size;
        modulesInArea = new Collection[getColumns()][getRows()];
        for (int col = 0; col < modulesInArea.length; col++) {
            for (int row = 0; row < modulesInArea[col].length; row++) {
                modulesInArea[col][row] = new HashSet<>();
            }
        }
        for (ModuleImplsInstance instance : instances) {
            if (instance.getPlacement()!= null) {
                place(instance);
            }
        }
    }

    @Override
    public boolean isValidPlacement(ModuleImplsInstance mii) {
        return allTouchedRegionsMatch(mii, l-> doesNotOverlapAny(mii, l));
    }

    private void checkCorrectness() {
        boolean error = false;
        for (int col = 0; col < modulesInArea.length; col++) {
            for (int row = 0; row < modulesInArea[col].length; row++) {
                Collection<ModuleImplsInstance> c = modulesInArea[col][row];
                for (ModuleImplsInstance moduleImplsInstance : c) {

                    if (moduleImplsInstance.getPlacement() == null) {
                        System.out.println(moduleImplsInstance+" is wrongly in "+col+"/"+row+", is not placed at all");
                        error = true;
                        continue;
                    }

                    final int minCol = getColumn(moduleImplsInstance.getBoundingBox().getMinColumn());
                    final int maxCol = getColumn(moduleImplsInstance.getBoundingBox().getMaxColumn());
                    final int minRow = getRow(moduleImplsInstance.getBoundingBox().getMinRow());
                    final int maxRow = getRow(moduleImplsInstance.getBoundingBox().getMaxRow());
                    boolean shouldBeIn = minCol <= col && col <= maxCol && minRow <= row && row <= maxRow;

                    if (!shouldBeIn) {
                        System.out.println(moduleImplsInstance+" is wrongly in "+col+"/"+row);
                        error = true;
                    }
                }

            }
        }

        for (ModuleImplsInstance moduleImplsInstance : instances) {
            for (ModuleImplsInstance other : instances) {
                if (other != moduleImplsInstance && other.overlaps(moduleImplsInstance)) {
                    System.out.println(moduleImplsInstance+" overlaps "+other);
                    error = true;
                }

            }

            if (moduleImplsInstance.getPlacement() == null) {
                continue;
            }

            final RelocatableTileRectangle bb = moduleImplsInstance.getBoundingBox();
            final int crMinCol = getColumn(bb.getMinColumn());
            final int crMaxCol = getColumn(bb.getMaxColumn());
            final int crMinRow = getRow(bb.getMinRow());
            final int crMaxRow = getRow(bb.getMaxRow());

            for (int col = crMinCol ; col <= crMaxCol; col++) {
                for (int row = crMinRow; row <= crMaxRow; row++) {
                    Collection<ModuleImplsInstance> c = modulesInArea[col][row];
                    if (!c.contains(moduleImplsInstance)) {
                        System.out.println(moduleImplsInstance+" should be in "+col+"/"+row);
                        error = true;
                    }
                }
            }
        }

        if (error) {
            throw new RuntimeException("error in overlaps");
        }
    }

    @Override
    public void printStats() {
        checkCorrectness();
        System.out.println("Fabric: "+device.getColumns()+"x"+device.getRows());
        System.out.println("Regions: "+getColumns()+"x"+getRows());
        final IntSummaryStatistics instsPerArea = Arrays.stream(modulesInArea).flatMap(Arrays::stream)
                .mapToInt(Collection::size).summaryStatistics();
        System.out.println("Insts per Area: "+instsPerArea);
        final IntSummaryStatistics areasPerInst = instances.stream().mapToInt(inst -> {
            final int[] c = {0};
            allTouchedRegionsMatch(inst, l -> {
                c[0]++;
                return true;
            });
            return c[0];
        }).summaryStatistics();
        System.out.println("Areas per Inst: "+areasPerInst);

    }
}
