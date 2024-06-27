/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development
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
package com.xilinx.rapidwright.design.shapes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * A set of cells that are intended to be placed together as a structured unit.
 */
public class Shape {

    private Map<Cell, ShapeLocation> map;

    private List<String> tags;

    private int width;

    private int height;

    public Shape() {
        map = new LinkedHashMap<>();
    }

    public Set<Cell> getCells() {
        return map.keySet();
    }

    public Map<Cell, ShapeLocation> getCellMap() {
        return map;
    }

    /**
     * @return the width
     */
    public int getWidth() {
        return width;
    }

    /**
     * @param width the width to set
     */
    public void setWidth(int width) {
        this.width = width;
    }

    /**
     * @return the height
     */
    public int getHeight() {
        return height;
    }

    /**
     * @param height the height to set
     */
    public void setHeight(int height) {
        this.height = height;
    }

    public Cell addCell(String name, Design design, int dx, int dy, String belName, String cellType) {
        Cell cell = design.getCell(name);
        if (cell == null) {
            cell = new Cell(name);
            cell.setType(cellType);
            design.addCell(cell);
            cell.setEDIFHierCellInst(design.getNetlist().getHierCellInstFromName(name));
            assert (cellType.equals(cell.getEDIFHierCellInst().getCellName()));
        } else if (cell.getEDIFHierCellInst() == null) {
            cell.setEDIFHierCellInst(design.getNetlist().getHierCellInstFromName(name));
        }
        Map<SiteTypeEnum, Set<String>> placements = cell.getCompatiblePlacements();

        List<SiteTypeEnum> compatibleSiteTypes = new ArrayList<>();
        for (Entry<SiteTypeEnum, Set<String>> e : placements.entrySet()) {
            if (e.getValue().contains(belName)) {
                compatibleSiteTypes.add(e.getKey());
            }
        }

        ShapeLocation loc = new ShapeLocation(compatibleSiteTypes, belName, dx, dy);
        map.put(cell, loc);
        return cell;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

}
