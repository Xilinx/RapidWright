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

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

/**
 * A Rectangle of tiles, i.e. a Bounding Box around some Set of Tiles.
 *
 * The tiles at the edge of the rectangle (e.g. at minX/minY and maxX/maxY) are all assumed to be inside the rectangle.
 * For both X and Y: {@code min <= Tiles <= max}
 *
 * The way to store tiles is set by subclasses. Depending on the storage method, they may or may not be relocatable
 */
public abstract class TileRectangle {

    /**
     * Base Collector implementation to be used by subclasses
     */
    static <T extends TileRectangle> Collector<Tile, ?, T> collector(Supplier<T> factory, BiConsumer<T, T> extendTo) {
        return Collector.of(
                factory,
                TileRectangle::extendTo,
                (a, b) -> {
                    extendTo.accept(a, b);
                    return a;
                },
                Function.identity(),
                Collector.Characteristics.UNORDERED,
                Collector.Characteristics.IDENTITY_FINISH
        );
    }

    public abstract int getMinRow();

    public abstract int getMaxRow();

    public abstract int getMinColumn();

    public abstract int getMaxColumn();


    public abstract boolean isEmpty();

    public abstract void extendTo(Tile tile);

    /**
     * Check whether a tile is contained in the Rectangle
     *
     * @param tile the tile to check
     * @return true if it is inside
     */
    public boolean isInside(Tile tile) {
        return (tile.getColumn() >= getMinColumn() && tile.getColumn() <= getMaxColumn() &&
                tile.getRow() >= getMinRow() && tile.getRow() <= getMaxRow());
    }


    public int hpwl() {
        return getMaxColumn() - getMinColumn() + getMaxRow() - getMinRow();
    }


    private static boolean intervalOverlaps(int minA, int maxA, int minB, int maxB) {
        return minA <= maxB && minB <= maxA;
    }

    /**
     * Check whether this Rectangle has any Tiles in common with another one
     *
     * @param other Rectangle to check
     * @return true if there is any overlap
     */
    public boolean overlaps(TileRectangle other) {
        return intervalOverlaps(getMinColumn(), getMaxColumn(), other.getMinColumn(), other.getMaxColumn())
                && intervalOverlaps(getMinRow(), getMaxRow(), other.getMinRow(), other.getMaxRow());
    }


    public int getWidth() {
        return getMaxColumn() - getMinColumn();
    }

    public int getHeight() {
        return getMaxRow() - getMinRow();
    }

    public int getLargerDimension() {
        return Math.max(getWidth(), getHeight());
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
    public abstract void extendToCorresponding(RelocatableTileRectangle rect, Site currentAnchor, SiteInst templateAnchor);


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
        extendTo(corresponding);
    }


    /**
     * Extend the Rectangle so that the specified Rectangle is inside
     *
     * @param rect The Rectangle to include
     */
    public abstract void extendTo(RelocatableTileRectangle rect);
}
