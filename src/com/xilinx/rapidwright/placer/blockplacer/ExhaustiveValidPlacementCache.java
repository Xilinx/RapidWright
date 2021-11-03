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
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Naive implementation of placement collection
 * @param <PlacementT> The placement class
 */
public class ExhaustiveValidPlacementCache<PlacementT> extends AbstractValidPlacementCache<PlacementT> {
    private final List<PlacementT> placements;
    private final BlockPlacer2<?,?,PlacementT, ?> placer;

    public ExhaustiveValidPlacementCache(List<PlacementT> placements, BlockPlacer2<?, ?, PlacementT, ?> placer) {
        this.placements = placements;
        this.placer = placer;
    }

    public static <PlacementT> Collector<PlacementT, ?, ExhaustiveValidPlacementCache<PlacementT>> collector(BlockPlacer2<?,?, PlacementT, ?> placer) {
        return Collectors.collectingAndThen(Collectors.toList(), list-> new ExhaustiveValidPlacementCache<>(list, placer));
    }

    @Override
    public List<PlacementT> getByRangeAround(int rangeLimit, PlacementT placement) {
        List<PlacementT> result = new ArrayList<>();
        for(PlacementT s : placements){
            if (placer.isInRange(placement, s)) {
                result.add(s);
            }
        }
        return result;
    }

    @Override
    public boolean contains(PlacementT site0) {
        return placements.contains(site0);
    }
}
