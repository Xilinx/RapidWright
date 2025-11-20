/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.blocks.PBlock;

public class PBlockConstraint implements Constraint<PBlockConstraint> {
    private PBlock pblock = new PBlock();
    private List<String> cells = new ArrayList<>();

    @Override
    public PBlockConstraint clone() {
        PBlockConstraint res = new PBlockConstraint();
        res.pblock = pblock;
        res.cells = new ArrayList<>(cells);
        return res;
    }

    public PBlock getPblock() {
        return pblock;
    }

    public List<String> getCells() {
        return cells;
    }

    public Stream<String> asXdc() {
        List<String> res = getPblock().getTclConstraints();
        if (cells.size()==1 && cells.get(0).isEmpty()) {
            res.add("add_cells_to_pblock [get_pblocks "+pblock.getName()+"] -top");
        } else if (!cells.isEmpty()) {
            res.add("add_cells_to_pblock [get_pblocks "+pblock.getName()+"] [get_cells {"+String.join(" ", cells)+"}]");
        }
        return res.stream();
    }
}
