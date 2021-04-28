package com.xilinx.rapidwright.design;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collector;

import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

/**
 * A Rectangle of tiles, i.e. a Bounding Box around some Set of Tiles.
 *
 * The tiles at the edge of the rectangle (e.g. at minX/minY and maxX/maxY) are all assumed to be inside the rectangle.
 * For both X and Y: min <= Tiles <= max
 *
 * This class is immutable, but can be converted to and from {@link MutableRectangle}
 */
public class TileRectangle {

    public final int minX;
    public final int maxX;
    public final int minY;
    public final int maxY;

    private TileRectangle(Tile tile) {
        this.minX = tile.getColumn();
        this.maxX = tile.getColumn();
        this.minY = tile.getRow();
        this.maxY = tile.getRow();
    }

    public TileRectangle(int minX, int maxX, int minY, int maxY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    /**
     * Create a TileRectangle from a single TIle
     * @param tile Tile to make into TileRectangle
     * @return a TileRectangle representing the tile
     */
    public static TileRectangle fromSingleTile(Tile tile) {
        return new TileRectangle(tile);
    }

    /**
     * Create a new TileRectangle that is larger than this Rectangle in all directions
     * @param rangeLimit Number of Tiles to expand by
     * @return Expanded Rectangle
     */
    public TileRectangle expand(int rangeLimit) {
        return new TileRectangle(
                minX - rangeLimit,
                maxX + rangeLimit,
                minY - rangeLimit,
                maxY + rangeLimit
        );
    }

    /**
     * Collect a Stream&lt;Tile&gt; to a TileRectangle
     * @return A Collector
     */
    public static Collector<Tile, ?, Optional<TileRectangle>> collector() {
        return Collector.of(
                MutableRectangle::new,
                MutableRectangle::extendTo,
                (a, b) -> { a.extendTo(b); return a;},
                MutableRectangle::toImmutable,
                Collector.Characteristics.UNORDERED
        );
    }

    /**
     * Check whether a tile is contained in the Rectangle
     * @param tile the tile to check
     * @return true if it is inside
     */
    public boolean isInside(Tile tile) {
        return (tile.getColumn()>=minX && tile.getColumn()<=maxX &&
                tile.getRow()>=minY && tile.getRow()<=maxY);
    }

    /**
     * Create a new TileRectangle that contains both this Rectangle as well as another one.
     * @param other Other Rectangle
     * @return Bounding Box of both Rectangles
     */
    public TileRectangle merge(TileRectangle other) {
        return new TileRectangle(
                Math.min(minX, other.minX),
                Math.max(maxX, other.maxX),
                Math.min(minY, other.minY),
                Math.max(maxY, other.maxY)
        );
    }

    @Override
    public String toString() {
        return "TileRectangle{" +
                "minX=" + minX +
                ", maxX=" + maxX +
                ", minY=" + minY +
                ", maxY=" + maxY +
                '}';
    }

    public int hpwl() {
        return maxX - minX + maxY - minY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TileRectangle that = (TileRectangle) o;
        return minX == that.minX && maxX == that.maxX && minY == that.minY && maxY == that.maxY;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minX, maxX, minY, maxY);
    }

    private static boolean intervalOverlaps(int minA, int maxA, int minB, int maxB) {
        return minA <= maxB && minB <= maxA;
    }

    /**
     * Check whether this Rectangle has any Tiles in common with another one
     * @param other Rectangle to check
     * @return true if there is any overlap
     */
    public boolean overlaps(TileRectangle other) {
        return intervalOverlaps(minX, maxX, other.minX, other.maxX) && intervalOverlaps(minY, maxY, other.minY, other.maxY);
    }

    public TileRectangle getCorresponding(Tile newAnchor, Tile originalAnchor) {
        int diffX = newAnchor.getColumn() - originalAnchor.getColumn();
        int diffY = newAnchor.getRow() - originalAnchor.getRow();
        return new TileRectangle(
          minX + diffX,
          maxX + diffX,
          minY + diffY,
          maxY + diffY
        );
    }

    /**
     * Create a Mutable Rectangle from this Immutable one
     * @return A Mutable Rectangle Representing the same Tiles
     */
    public MutableRectangle toMutable() {
        return new MutableRectangle(minX, maxX, minY, maxY);
    }

    /**
     * A Rectangle of tiles, i.e. a Bounding Box around some Set of Tiles.
     *
     * The tiles at the edge of the rectangle (e.g. at minX/minY and maxX/maxY) are all assumed to be inside the rectangle.
     * For both X and Y: min <= Tiles <= max
     *
     * This class is mutable, but can be converted to and from {@link TileRectangle}
     */
    public static class MutableRectangle {
        private int minX;
        private int maxX;
        private int minY;
        private int maxY;
        private boolean empty = true;

        public MutableRectangle() {
        }

        public MutableRectangle(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.empty = false;
        }

        /**
         *
         * @return
         */
        public Optional<TileRectangle> toImmutable() {
            if (empty) {
                return Optional.empty();
            }
            return Optional.of(new TileRectangle(minX, maxX, minY, maxY));
        }

        private void extendToPoint(int x, int y) {
            if (empty) {
                minX = x;
                maxX = x;
                minY = y;
                maxY = y;
                empty = false;
            } else {

                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        /**
         * Extend the Rectangle so that the specified Tile is inside
         * @param tile The tile to include
         */
        public void extendTo(Tile tile) {
            extendToPoint(tile.getColumn(), tile.getRow());
        }

        /**
         * Extend the Rectangle so that the specified Rectangle is inside
         * @param rect The Rectangle to include
         */
        public void extendTo(MutableRectangle rect) {
            extendToPoint(rect.minX, rect.minY);
            extendToPoint(rect.maxX, rect.maxY);
        }


        /**
         * Extend the Rectangle so that a shifted tile is inside. The Tile is assumed to be located relative to some anchor.
         * The anchor is shifted from {@code templateAnchor} to {@code currentAnchor}. This location relative to the new
         * anchor is then included in the Rectangle.
         * @param tile tile to include after shifting
         * @param currentAnchor target anchor
         * @param templateAnchor source anchor
         */
        public void extendToCorresponding(Tile tile, Site currentAnchor, SiteInst templateAnchor) {
            int x = tile.getColumn() + currentAnchor.getTile().getColumn() - templateAnchor.getTile().getColumn();
            int y = tile.getRow() + currentAnchor.getTile().getRow() - templateAnchor.getTile().getRow();
            extendToPoint(x,y);
        }

        /**
         * Extend the Rectangle so that a shifted rectangle is inside. The Rectangle is assumed to be located relative to some anchor.
         * The anchor is shifted from {@code templateAnchor} to {@code currentAnchor}. This location relative to the new
         * anchor is then included in the Rectangle.
         * @param rect Rectangle to include after shifting
         * @param currentAnchor target anchor
         * @param templateAnchor source anchor
         */
        public void extendToCorresponding(TileRectangle rect, Site currentAnchor, SiteInst templateAnchor) {
            int columnDiff = currentAnchor.getTile().getColumn() - templateAnchor.getTile().getColumn();
            int rowDiff = currentAnchor.getTile().getRow() - templateAnchor.getTile().getRow();

            extendToPoint(rect.minX + columnDiff, rect.minY + rowDiff);
            extendToPoint(rect.maxX + columnDiff, rect.maxY + rowDiff);
        }
    }
}
