/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

import java.util.Objects;
import java.util.stream.Collector;

/**
 * A {@link TileRectangle} that is relocatable.
 * <p>
 * As padding tiles may be inserted when relocating Rectangles, we do not store coordinates of tiles but rather the
 * tiles themselves. For every border (top/bottom/left/right) we save one example tile. When Relocation is not needed,
 * use {@link SimpleTileRectangle} instead
 */
public class RelocatableTileRectangle extends TileRectangle {

    private Tile minColumn;
    private Tile maxColumn;
    private Tile minRow;
    private Tile maxRow;
    private boolean empty = true;


    public RelocatableTileRectangle(Tile tile) {
        this.minColumn = tile;
        this.maxColumn = tile;
        this.minRow = tile;
        this.maxRow = tile;
        empty = false;
    }

    public RelocatableTileRectangle(Tile minColumn, Tile maxColumn, Tile minRow, Tile maxRow) {
        this.minColumn = Objects.requireNonNull(minColumn);
        this.maxColumn = Objects.requireNonNull(maxColumn);
        this.minRow = Objects.requireNonNull(minRow);
        this.maxRow = Objects.requireNonNull(maxRow);
        empty = false;
    }

    public RelocatableTileRectangle() {
    }

    /**
     * Collect a Stream&lt;Tile&gt; to a TileRectangle
     *
     * @return A Collector
     */
    public static Collector<Tile, ?, RelocatableTileRectangle> collector() {
        return TileRectangle.collector(RelocatableTileRectangle::new, RelocatableTileRectangle::extendTo);
    }

    public static RelocatableTileRectangle of(Tile... tiles) {
        final RelocatableTileRectangle result = new RelocatableTileRectangle();
        for (Tile tile : tiles) {
            result.extendTo(tile);
        }
        return result;
    }

    @Override
    public String toString() {
        return "RelocatableTileRectangle{" +
                "minColumn=" + minColumn +
                ", maxColumn=" + maxColumn +
                ", minRow=" + minRow +
                ", maxRow=" + maxRow +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RelocatableTileRectangle that = (RelocatableTileRectangle) o;
        return minColumn.getColumn() == that.minColumn.getColumn()
                && maxColumn.getColumn() == that.maxColumn.getColumn()
                && minRow.getRow() == that.minRow.getRow()
                && maxRow.getRow() == that.maxRow.getRow();
    }

    @Override
    public int hashCode() {
        return Objects.hash(minColumn.getColumn(), maxColumn.getColumn(), minRow.getRow(), maxRow.getRow());
    }

    private Tile[][] minColumnArr;
    private Tile[][] maxColumnArr;
    private Tile[][] minRowArr;
    private Tile[][] maxRowArr;


    private String failedReloc(Tile template, Tile newAnchor, Tile originalAnchor, Tile[][] arr) {
        //We try to find the name the new tile would have
        int tileXOffset = template.getTileXCoordinate() - originalAnchor.getTileXCoordinate();
        int tileYOffset = template.getTileYCoordinate() - originalAnchor.getTileYCoordinate();
        int newTileX = newAnchor.getTileXCoordinate() + tileXOffset;
        int newTileY = newAnchor.getTileYCoordinate() + tileYOffset;

        String newName = template.getRootName() + "_X" + newTileX + "Y" + newTileY;


        return "Failed to find corresponding tile \""+newName+"\" for "+template+" when relocating from "+originalAnchor+" to "+newAnchor+". Rect: "+ this;
    }

    public RelocatableTileRectangle getCorresponding(Tile newAnchor, Tile originalAnchor) {
        if (minColumnArr == null) {
            minColumnArr = newAnchor.getDevice().getTilesByRootName(minColumn.getRootName());
            maxColumnArr = newAnchor.getDevice().getTilesByRootName(maxColumn.getRootName());
            minRowArr = newAnchor.getDevice().getTilesByRootName(minRow.getRootName());
            maxRowArr = newAnchor.getDevice().getTilesByRootName(maxRow.getRootName());
        }

        return new RelocatableTileRectangle(
                Objects.requireNonNull(Module.getCorrespondingTile(minColumn, newAnchor, originalAnchor, minColumnArr), ()->failedReloc(minColumn, newAnchor, originalAnchor, minColumnArr)),
                Objects.requireNonNull(Module.getCorrespondingTile(maxColumn, newAnchor, originalAnchor, maxColumnArr), ()->failedReloc(maxColumn, newAnchor, originalAnchor, maxColumnArr)),
                Objects.requireNonNull(Module.getCorrespondingTile(minRow, newAnchor, originalAnchor, minRowArr), ()->failedReloc(minRow, newAnchor, originalAnchor, minRowArr)),
                Objects.requireNonNull(Module.getCorrespondingTile(maxRow, newAnchor, originalAnchor, maxRowArr), ()->failedReloc(maxRow, newAnchor, originalAnchor, maxRowArr))
        );
    }


    private void extendToRect(Tile otherMinX, Tile otherMaxX, Tile otherMinY, Tile otherMaxY) {
        minColumnArr = null;
        maxColumnArr = null;
        minRowArr = null;
        maxRowArr = null;
        if (empty) {
            minColumn = otherMinX;
            maxColumn = otherMaxX;
            minRow = otherMinY;
            maxRow = otherMaxY;
            empty = false;
            return;
        }

        if (otherMinX.getColumn() < minColumn.getColumn()) {
            minColumn = otherMinX;
        }
        if (otherMaxX.getColumn() > maxColumn.getColumn()) {
            maxColumn = otherMaxX;
        }
        if (otherMinY.getRow() < minRow.getRow()) {
            minRow = otherMinY;
        }
        if (otherMaxY.getRow() > maxRow.getRow()) {
            maxRow = otherMaxY;
        }
    }

    /**
     * Extend the Rectangle so that the specified Tile is inside
     *
     * @param tile The tile to include
     */
    public void extendTo(Tile tile) {
        extendToRect(tile, tile, tile, tile);
    }

    /**
     * Extend the Rectangle so that the specified Rectangle is inside
     *
     * @param rect The Rectangle to include
     */
    public void extendTo(RelocatableTileRectangle rect) {
        if (rect.empty) {
            return;
        }
        extendToRect(rect.minColumn, rect.maxColumn, rect.minRow, rect.maxRow);
    }


    /**
     * Extend the Rectangle so that a shifted tile is inside. The Tile is assumed to be located relative to some anchor.
     * The anchor is shifted from {@code templateAnchor} to {@code currentAnchor}. This location relative to the new
     * anchor is then included in the Rectangle.
     *
     * @param tile           tile to include after shifting
     * @param currentAnchor  target anchor
     * @param templateAnchor source anchor
     */
    public void extendToCorresponding(Tile tile, Site currentAnchor, SiteInst templateAnchor) {
        Tile corresponding = Module.getCorrespondingTile(tile, currentAnchor.getTile(), templateAnchor.getTile());
        extendToRect(
                corresponding,
                corresponding,
                corresponding,
                corresponding
        );
    }

    /**
     * Extend the Rectangle so that a shifted rectangle is inside. The Rectangle is assumed to be located relative to some anchor.
     * The anchor is shifted from {@code templateAnchor} to {@code currentAnchor}. This location relative to the new
     * anchor is then included in the Rectangle.
     *
     * @param rect           Rectangle to include after shifting
     * @param currentAnchor  target anchor
     * @param templateAnchor source anchor
     */
    @Override
    public void extendToCorresponding(RelocatableTileRectangle rect, Site currentAnchor, SiteInst templateAnchor) {
        extendToRect(
                Objects.requireNonNull(Module.getCorrespondingTile(rect.minColumn, currentAnchor.getTile(), templateAnchor.getTile())),
                Objects.requireNonNull(Module.getCorrespondingTile(rect.maxColumn, currentAnchor.getTile(), templateAnchor.getTile())),
                Objects.requireNonNull(Module.getCorrespondingTile(rect.minRow, currentAnchor.getTile(), templateAnchor.getTile())),
                Objects.requireNonNull(Module.getCorrespondingTile(rect.maxRow, currentAnchor.getTile(), templateAnchor.getTile()))
        );
    }

    @Override
    public int getMinRow() {
        return minRow.getRow();
    }

    @Override
    public int getMaxRow() {
        return maxRow.getRow();
    }

    @Override
    public int getMinColumn() {
        return minColumn.getColumn();
    }

    @Override
    public int getMaxColumn() {
        return maxColumn.getColumn();
    }

    public boolean isEmpty() {
        return empty;
    }

    public Tile getMinColumnTile() {
        return minColumn;
    }

    public Tile getMaxColumnTile() {
        return maxColumn;
    }

    public Tile getMinRowTile() {
        return minRow;
    }

    public Tile getMaxRowTile() {
        return maxRow;
    }
}
