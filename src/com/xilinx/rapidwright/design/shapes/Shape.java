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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * A set of cells that are intended to be placed together as a structured unit.
 */
public class Shape {

    private Map<Cell, ShapeLocation> map;

    private Set<ShapeTag> tags;

    private int width;

    private int height;

    private Shape nextChain;

    public Shape() {
        map = new LinkedHashMap<>();
        tags = new HashSet<>();
    }

    public Set<Cell> getCells() {
        return map.keySet();
    }

    public Map<Cell, ShapeLocation> getCellMap() {
        return map;
    }

    protected void setCellMap(Map<Cell, ShapeLocation> map) {
        this.map = map;
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

    public Set<ShapeTag> getTags() {
        return tags;
    }

    public void setTags(Set<ShapeTag> tags) {
        this.tags = tags;
    }

    public boolean addTag(ShapeTag tag) {
        return tags.add(tag);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Entry<Cell, ShapeLocation> e : map.entrySet()) {
            sb.append("(" + e.getValue().getCompatibleSiteTypes() + "_X" + e.getValue().getDx() + "Y"
                    + e.getValue().getDy() + ", " + e.getValue().getBelName() + ")\t" + e.getKey().getName() + " ("
                    + e.getKey().getType() + ")\n");
        }
        sb.append("Tags(s): " + getTags() + "\n");
        sb.append("WxH: " + getWidth() + "x" + getHeight() + "\n");
        return sb.toString();
    }

    protected Shape getNextChain() {
        return nextChain;
    }

    protected void setNextChain(Shape nextChain) {
        this.nextChain = nextChain;
    }

    /**
     * Gets the highest/largest LUT letter name from the BEL names used in all the
     * cells. Examines both 6LUT and 5LUT locations.
     * 
     * @return The highest LUT letter used in the shape.
     */
    public char getLargestLUTLetter() {
        char c = Character.MIN_VALUE;
        for (ShapeLocation loc : map.values()) {
            String belName = loc.getBelName();
            if (belName.contains("LUT")) {
                char lutLetter = belName.charAt(0);
                if (c < lutLetter) {
                    c = lutLetter;
                }
            }
        }
        return c;
    }
}
