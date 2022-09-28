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
package com.xilinx.rapidwright.placer.blockplacer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.SimpleTileRectangle;
import com.xilinx.rapidwright.design.TileRectangle;
import com.xilinx.rapidwright.device.Tile;

/**
 * Placements of a Module stored by position on the fabric together with some additional indices.
 *
 * This allows for optimized querying of placements near a central placement.
 * @param <PlacementT> The placement class
 */
public abstract class SortedValidPlacementCache<PlacementT> extends AbstractValidPlacementCache<PlacementT> {
    public static <PlacementT> AbstractValidPlacementCache<PlacementT> fromList(List<PlacementT> allPlacements, BlockPlacer2<?,?,PlacementT, ?> placer, boolean designIsDense) {
        final SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> data = allPlacements.stream().collect(SortedValidPlacementCache.collector(placer));
        if (designIsDense) {
            return new DenseSortedValidPlacementCache<>(placer, data, allPlacements);
        } else {
            return new SparseSortedValidPlacementCache<>(placer, data, allPlacements);
        }
    }

    private static class SortedValidPlacementCache1D<T>{
        /**
         * Actual Items
         */
        public final List<T> items;
        private final ToIntFunction<T> countItem;

        /**
         * Lower bound for searching for keys
         *
         * The key of items.get(minIdx[key]) is >= key.
         */
        public final int[] minIdx;
        /**
         * Upper bound for searching for keys
         *
         * The key of items.get(minIdx[key]) is <= key.
         */
        public final int[] maxIdx;

        private final int[] itemCounts;

        /**
         * Create a new 1D placement collection
         * @param items items, ordered by keys
         * @param keys the key values
         */
        public SortedValidPlacementCache1D(List<T> items, int[] keys, ToIntFunction<T> countItem) {
            this.items = items;
            this.countItem = countItem;

            int biggestKey = keys.length == 0 ? 0 : keys[keys.length-1];

            minIdx = new int[biggestKey+2];
            maxIdx = new int[biggestKey+2];

            for (int key = 0; key < minIdx.length; key++) {
                final int searchResult = Arrays.binarySearch(keys, key);
                //Found?
                if (searchResult >=0) {
                    minIdx[key] = searchResult;
                    maxIdx[key] = searchResult;
                } else {
                    //Extract the insertion point
                    int insertionPoint = -(searchResult + 1);
                    minIdx[key] = insertionPoint;
                    maxIdx[key] = insertionPoint - 1; //Exclude the insertion point
                }
            }

            if (countItem != null) {
                itemCounts = new int[items.size()];
                int count = 0;
                for (int i = 0; i < items.size(); i++) {
                    count += countItem.applyAsInt(items.get(i));
                    itemCounts[i] = count;
                }
            } else {
                itemCounts = null;
            }
        }

        private int fromArr(int key, int[] arr) {
            if (key < 0) {
                return 0;
            }
            if (key >= arr.length) {
                return arr[arr.length-1];
            }
            return arr[key];
        }

        public int getMaxIdx(int key) {
            return fromArr(key, maxIdx);
        }
        public int getMinIdx(int key) {
            return fromArr(key, minIdx);
        }

        public static <T,U>Collector<T,?, SortedValidPlacementCache1D<U>> collector(ToIntFunction<T> keyExtractor, ToIntFunction<U> countItem, Collector<T,?,U> downstreamCollector) {
            return Collectors.collectingAndThen(Collectors.groupingBy(keyExtractor::applyAsInt, downstreamCollector), (Map<Integer, U> byKey) -> {
                int[] keys = byKey.keySet().stream().sorted().mapToInt(x -> x).toArray();
                List<U> items = Arrays.stream(keys).mapToObj(byKey::get).collect(Collectors.toList());
                return new SortedValidPlacementCache1D<>(items, keys, countItem);
            });
        }

        public T get(int idx) {
            return items.get(idx);
        }

        public T getByKey(int key) {
            final int max = getMaxIdx(key);
            final int min = getMinIdx(key);
            if (max != min) {
                return null;
            }
            return items.get(min);
        }


        private int getEntryCountUpTo(int idx) {
            if (idx<0) {
                return 0;
            }
            return itemCounts[idx];
        }
    }

    protected final BlockPlacer2<?,?,PlacementT, ?> placer;

    /**
     * The actual data that we store.
     *
     * When multiple module implementations are present, they may map to the same anchor location, so we store a list of items for each position.
     */
    protected final SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> collection;
    protected final List<PlacementT> allData;

    private SortedValidPlacementCache(
            BlockPlacer2<?, ?, PlacementT, ?> placer,
            SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> collection,
            List<PlacementT> allData
    ) {
        this.placer = placer;

        this.collection = collection;
        this.allData = allData;
    }

    public static <PlacementT> Collector<PlacementT, ?, SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>>> collector(BlockPlacer2<?,?,PlacementT, ?> placer) {
        return SortedValidPlacementCache1D.collector(
                        p -> placer.getPlacementTile(p).getColumn(),
                        null,
                        SortedValidPlacementCache1D.collector(
                                p -> placer.getPlacementTile(p).getRow(),
                                (List<PlacementT> l)->l.size(),
                                Collectors.toList()
                        )
                );
    }

    @FunctionalInterface
    interface ArrayIndexToPlacement<PlacementT> {
        PlacementT apply(int index, int innerIndex);
    }


    private static <PlacementT> PlacementT findInnerArrayIndex(int targetIndex, int[] itemCounts, ArrayIndexToPlacement<PlacementT> f) {
        //We are storing counts but are looking up by index -> add one
        int index = Arrays.binarySearch(itemCounts, targetIndex+1);
        if (index<0) {
            index = -index - 1;
        } else {
            //We may have the same value repeat in the array if the filtered part of a column is empty
            //Move to the lowest repeat index in that case.
            //This is a rare case, so it's not worth it to update the arrays
            while (index>0 && itemCounts[index-1]==(targetIndex+1)) {
                index--;
            }
        }
        int itemsBefore = index == 0 ? 0 : itemCounts[index-1];
        int innerIndex = targetIndex - itemsBefore;
        return f.apply(index, innerIndex);
    }


    @Override
    public boolean contains(PlacementT placement) {
        final Tile tile = placer.getPlacementTile(placement);

        final SortedValidPlacementCache1D<List<PlacementT>> column = collection.getByKey(tile.getColumn());
        if (column == null) {
            return false;
        }
        List<PlacementT> row = column.getByKey(tile.getRow());
        if (row == null) {
            return false;
        }
        return row.contains(placement);
    }

    public static <PlacementT> void writeList(List<PlacementT> list, java.nio.file.Path fn, int rangeLimit, PlacementT center, BlockPlacer2<?, ?, PlacementT, ?> placer) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(fn))) {
            final Tile centerTile = placer.getPlacementTile(center);
            for (PlacementT placementT : list) {
                final Tile t = placer.getPlacementTile(placementT);

                TileRectangle rect = new SimpleTileRectangle();
                rect.extendTo(centerTile);
                rect.extendTo(t);
                int dist = rect.getLargerDimension();

                pw.println(placementT+" "+dist);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Collection<PlacementT> getAll() {
        return allData;
    }

    private static class SparseSortedValidPlacementCache<PlacementT> extends SortedValidPlacementCache<PlacementT> {

        private SparseSortedValidPlacementCache(BlockPlacer2<?, ?, PlacementT, ?> placer, SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> collection, List<PlacementT> allData) {
            super(placer, collection, allData);
        }

        @Override
        public List<PlacementT> getByRangeAround(int rangeLimit, PlacementT centerPlacement) {

            /*if (rangeLimit>= placer.getMaxRangeLimit()) {
                return allData;
            }*/
            Tile center = placer.getPlacementTile(centerPlacement);

            final int maxColumn = collection.getMaxIdx(center.getColumn()+ rangeLimit);
            final int minColumn = collection.getMinIdx(center.getColumn() - rangeLimit);
            //This stores how many matching entries are in each column
            int[] columnCounts = new int[maxColumn-minColumn+1];
            //This stores the first matching row for each column
            int[] minRows = new int[maxColumn-minColumn+1];
            //This stores the last matching row for each column
            int[] maxRows = new int[maxColumn-minColumn+1];


            int count = 0;
            for (int col = minColumn; col <= maxColumn; col++) {
                final SortedValidPlacementCache1D<List<PlacementT>> currentCol = collection.get(col);

                final int arrIdx = col - minColumn;
                minRows[arrIdx] = currentCol.getMinIdx(center.getRow() - rangeLimit);
                maxRows[arrIdx] = currentCol.getMaxIdx(center.getRow() + rangeLimit);

                int thisColCount =
                        currentCol.getEntryCountUpTo(maxRows[arrIdx])
                                - currentCol.getEntryCountUpTo(minRows[arrIdx]-1);

                count += thisColCount;
                columnCounts[arrIdx] = count;

            }
            int totalCount = count;
            final AbstractList<PlacementT> res = new AbstractList<PlacementT>() {
                @Override
                public int size() {
                    return totalCount;
                }

                @Override
                public PlacementT get(int index) {
                    if (index<0 || index>=size()) {
                        throw new IndexOutOfBoundsException("index "+index+" out of bounds for svp result of size "+totalCount+" for range "+rangeLimit+" around "+centerPlacement);
                    }


                    return findInnerArrayIndex(index, columnCounts, (colIdx, inColumnIdx) -> {
                        final SortedValidPlacementCache1D<List<PlacementT>> column = collection.get(colIdx + minColumn);

                        int inColumnIdxShift = inColumnIdx + column.getEntryCountUpTo(minRows[colIdx] - 1);

                        return findInnerArrayIndex(inColumnIdxShift, column.itemCounts,
                                (rowIdx, listIdx) -> {
                                    return column.get(rowIdx).get(listIdx);
                                }
                        );
                    });
                }
            };

            /*if (rangeLimit>= placer.getMaxRangeLimit()) {

                final VerifySameLists<PlacementT> verifySameLists = new VerifySameLists<>("svp return " + rangeLimit + " around " + centerPlacement, allData, res);
                / *try {
                    for (PlacementT verifySameList : verifySameLists) {

                    }
                } catch (RuntimeException e) {
                    SortedValidPlacementCache.writeList(allData, Paths.get("/tmp/vpl_all.txt"), rangeLimit, centerPlacement, placer);
                    SortedValidPlacementCache.writeList(res, Paths.get("/tmp/vpl_res.txt"), rangeLimit, centerPlacement, placer);
                    throw e;
                }* /
                return verifySameLists;
            }*/
            return res;
        }

    }
    private static class DenseSortedValidPlacementCache<PlacementT> extends SortedValidPlacementCache<PlacementT> {
        private DenseSortedValidPlacementCache(BlockPlacer2<?, ?, PlacementT, ?> placer, SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> collection, List<PlacementT> allData) {
            super(placer, collection, allData);
        }

        @Override
        public List<PlacementT> getByRangeAround(int rangeLimit, PlacementT centerPlacement) {
            if (rangeLimit>= placer.getMaxRangeLimit()) {
                return allData;
            }
            Tile center = placer.getPlacementTile(centerPlacement);

            List<PlacementT> result = new ArrayList<>();

            final int maxColumn = collection.getMaxIdx(center.getColumn()+ rangeLimit);
            for (int col = collection.getMinIdx(center.getColumn() - rangeLimit); col <= maxColumn; col++) {
                final SortedValidPlacementCache1D<List<PlacementT>> currentCol = collection.get(col);

                final int maxRow = currentCol.getMaxIdx(center.getRow()+ rangeLimit);
                for (int row = currentCol.getMinIdx(center.getRow()- rangeLimit); row <= maxRow; row++) {
                    result.addAll(currentCol.items.get(row));
                }
            }

            return result;
        }
    }



}
