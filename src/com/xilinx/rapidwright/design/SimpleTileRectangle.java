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
 
package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

import java.util.Objects;
import java.util.stream.Collector;

/**
 * A {@link TileRectangle} that uses Row/Column indices for storage. Fast, but not relocatable.
 */
public class SimpleTileRectangle extends TileRectangle {
    private int minColumn;
    private int maxColumn;
    private int minRow;
    private int maxRow;
    private boolean empty = true;

    public SimpleTileRectangle() {
    }

    public SimpleTileRectangle(int minColumn, int maxColumn, int minRow, int maxRow) {
        this.minColumn = minColumn;
        this.maxColumn = maxColumn;
        this.minRow = minRow;
        this.maxRow = maxRow;
        this.empty = false;
    }

    public SimpleTileRectangle(SimpleTileRectangle rect) {
        this.minColumn = rect.minColumn;
        this.maxColumn = rect.maxColumn;
        this.minRow = rect.minRow;
        this.maxRow = rect.maxRow;
        this.empty = rect.empty;
    }

    public static Collector<Tile, ?, SimpleTileRectangle> collector() {
        return TileRectangle.collector(SimpleTileRectangle::new, SimpleTileRectangle::extendTo);
    }


    private void extendToRect(int otherMinColumn, int otherMaxColumn, int otherMinRow, int otherMaxRow) {
        if (empty) {
            minColumn = otherMinColumn;
            maxColumn = otherMaxColumn;
            minRow = otherMinRow;
            maxRow = otherMaxRow;
            empty = false;
            return;
        }

        if (otherMinColumn < minColumn) {
            minColumn = otherMinColumn;
        }
        if (otherMaxColumn > maxColumn) {
            maxColumn = otherMaxColumn;
        }
        if (otherMinRow < minRow) {
            minRow = otherMinRow;
        }
        if (otherMaxRow > maxRow) {
            maxRow = otherMaxRow;
        }
    }

    /**
     * Extend the Rectangle so that the specified Tile is inside
     * @param tile The tile to include
     */
    @Override
    public void extendTo(Tile tile) {
        extendToRect(tile.getColumn(), tile.getColumn(), tile.getRow(), tile.getRow());
    }

    /**
     * Extend the Rectangle so that the specified Rectangle is inside
     * @param rect The Rectangle to include
     */
    @Override
    public void extendTo(RelocatableTileRectangle rect) {
        extendTo((TileRectangle) rect);
    }

    /**
     * Extend the Rectangle so that the specified Rectangle is inside
     * @param rect The Rectangle to include
     */
    public void extendTo(TileRectangle rect) {
        if (rect.isEmpty()) {
            return;
        }
        extendToRect(rect.getMinColumn(), rect.getMaxColumn(), rect.getMinRow(), rect.getMaxRow());
    }

    /**
     * Extend the Rectangle so that a shifted rectangle is inside. The Rectangle is assumed to be located relative to some anchor.
     * The anchor is shifted from {@code templateAnchor} to {@code currentAnchor}. This location relative to the new
     * anchor is then included in the Rectangle.
     * @param rect Rectangle to include after shifting
     * @param currentAnchor target anchor
     * @param templateAnchor source anchor
     */
    public void extendToCorresponding(RelocatableTileRectangle rect, Site currentAnchor, SiteInst templateAnchor) {
        extendToRect(
                Objects.requireNonNull(Module.getCorrespondingTile(rect.getMinColumnTile(), currentAnchor.getTile(), templateAnchor.getTile())).getColumn(),
                Objects.requireNonNull(Module.getCorrespondingTile(rect.getMaxColumnTile(), currentAnchor.getTile(), templateAnchor.getTile())).getColumn(),
                Objects.requireNonNull(Module.getCorrespondingTile(rect.getMinRowTile(), currentAnchor.getTile(), templateAnchor.getTile())).getRow(),
                Objects.requireNonNull(Module.getCorrespondingTile(rect.getMaxRowTile(), currentAnchor.getTile(), templateAnchor.getTile())).getRow()
        );
    }

    @Override
    public int getMinColumn() {
        return minColumn;
    }

    @Override
    public int getMaxColumn() {
        return maxColumn;
    }

    @Override
    public int getMinRow() {
        return minRow;
    }

    @Override
    public int getMaxRow() {
        return maxRow;
    }

    @Override
    public boolean isEmpty() {
        return empty;
    }


    public static SimpleTileRectangle of(Tile... tiles) {
        final SimpleTileRectangle result = new SimpleTileRectangle();
        for (Tile tile : tiles) {
            result.extendTo(tile);
        }
        return result;
    }
}
