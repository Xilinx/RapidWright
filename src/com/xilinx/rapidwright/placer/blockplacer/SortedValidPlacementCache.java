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
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.device.Tile;

/**
 * Placements of a Module stored by position on the fabric together with some additional indices.
 *
 * This allows for optimized querying of placements near a central placement.
 * @param <PlacementT> The placement class
 */
public class SortedValidPlacementCache<PlacementT> extends AbstractValidPlacementCache<PlacementT> {
    private static class SortedValidPlacementCache1D<T>{
        /**
         * Actual Items
         */
        public final List<T> items;

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

        /**
         * Create a new 1D placement collection
         * @param items items, ordered by keys
         * @param keys the key values
         */
        public SortedValidPlacementCache1D(List<T> items, int[] keys) {
            this.items = items;

            int biggestKey = keys[keys.length-1];

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

        public static <T,U>Collector<T,?, SortedValidPlacementCache1D<U>> collector(ToIntFunction<T> keyExtractor, Collector<T,?,U> downstreamCollector) {
            return Collectors.collectingAndThen(Collectors.groupingBy(keyExtractor::applyAsInt, downstreamCollector), (Map<Integer, U> byKey) -> {
                int[] keys = byKey.keySet().stream().sorted().mapToInt(x -> x).toArray();
                List<U> items = Arrays.stream(keys).mapToObj(byKey::get).collect(Collectors.toList());
                return new SortedValidPlacementCache1D<>(items, keys);
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
    }

    private final BlockPlacer2<?,?,PlacementT, ?> placer;

    /**
     * The actual data that we store.
     *
     * When multiple module implementations are present, they may map to the same anchor location, so we store a list of items for each position.
     */
    private final SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> collection;

    private SortedValidPlacementCache(BlockPlacer2<?, ?, PlacementT, ?> placer, SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>> collection) {
        this.placer = placer;

        this.collection = collection;
    }

    public static <PlacementT> Collector<PlacementT, ?, SortedValidPlacementCache<PlacementT>> collector(BlockPlacer2<?,?,PlacementT, ?> placer) {
        Collector<PlacementT, ?, SortedValidPlacementCache1D<SortedValidPlacementCache1D<List<PlacementT>>>> createColl =
                SortedValidPlacementCache1D.collector(
                        p -> placer.getPlacementTile(p).getColumn(),
                        SortedValidPlacementCache1D.collector(
                                p -> placer.getPlacementTile(p).getRow(),
                                Collectors.toList()
                        )
                );
        return Collectors.collectingAndThen(createColl, c -> new SortedValidPlacementCache<>(placer, c));
    }


    @Override
    public List<PlacementT> getByRangeAround(int rangeLimit, PlacementT centerPlacement) {

        Tile center = placer.getPlacementTile(centerPlacement);

        List<PlacementT> result = new ArrayList<>();

        final int maxColumn = collection.getMaxIdx(center.getColumn()+rangeLimit);
        for (int col = collection.getMinIdx(center.getColumn() - rangeLimit); col <= maxColumn; col++) {
            final SortedValidPlacementCache1D<List<PlacementT>> currentCol = collection.get(col);

            final int maxRow = currentCol.getMaxIdx(center.getRow()+rangeLimit);
            for (int row = currentCol.getMinIdx(center.getRow()-rangeLimit); row <= maxRow; row++) {
                result.addAll(currentCol.items.get(row));
            }
        }

        return result;
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
}
