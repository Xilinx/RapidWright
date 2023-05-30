/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents endpoints on a {@link Path}.
 * @author clavin
 *
 */
public class PathPort {

    private final SitePinInst sitePinInst;
    private final HardMacro block;
    private final Tile tile;
    private final Tile[][] rootNameTiles;

    public PathPort(SitePinInst sitePinInst, HardMacro block, Tile tile) {
        this.sitePinInst = sitePinInst;
        this.block = block;
        this.tile = tile;
        this.rootNameTiles = tile.getDevice().getTilesByRootName(tile.getRootName());
    }

    private Tile cachedAnchor;
    private Tile cachedRelocate;

    public Tile getPortTile() {
        if (block == null) {
            return tile;
        }
        Tile anchor = block.getTempAnchorSite().getTile();

        if (cachedAnchor == anchor) {
            return cachedRelocate;
        }

        final Tile res = Module.getCorrespondingTile(tile, anchor, block.getModule().getAnchor().getTile(), rootNameTiles);
        cachedAnchor = anchor;
        cachedRelocate = res;
        return res;
    }


    /**
     * @return the pin
     */
    public SitePinInst getSitePinInst() {
        return sitePinInst;
    }


    /**
     * @return the block
     */
    public HardMacro getBlock() {
        return block;
    }

    public Tile getTemplateTile() {
        return tile;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((sitePinInst == null) ? 0 : sitePinInst.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PathPort other = (PathPort) obj;
        if (sitePinInst == null) {
            if (other.sitePinInst != null)
                return false;
        } else if (!sitePinInst.equals(other.sitePinInst))
            return false;
        return true;
    }


}
