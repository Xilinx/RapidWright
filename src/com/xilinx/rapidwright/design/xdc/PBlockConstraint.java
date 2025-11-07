package com.xilinx.rapidwright.design.xdc;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.blocks.PBlock;

public class PBlockConstraint implements Constraint<PBlockConstraint> {
    private PBlock pblock = new PBlock();
    private List<String> cells = new ArrayList<>();

    @Override
    public PBlockConstraint clone() {
        throw new RuntimeException("TODO");
    }

    public PBlock getPblock() {
        return pblock;
    }

    public List<String> getCells() {
        return cells;
    }
}
