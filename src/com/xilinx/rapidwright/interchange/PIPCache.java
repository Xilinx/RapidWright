/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

import java.util.List;
import java.util.Map;

/**
 * Class for caching PIP lookups, given a Tile object, and string
 * indices for the start and end wire names.
 */
public class PIPCache {
    private static class Key {
        private final TileTypeEnum tileTypeEnum;
        private final int wire0StringIdx;
        private final int wire1StringIdx;

        public Key(Tile tile, int wire0StringIdx, int wire1StringIdx) {
            tileTypeEnum = tile.getTileTypeEnum();
            this.wire0StringIdx = wire0StringIdx;
            this.wire1StringIdx = wire1StringIdx;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + tileTypeEnum.ordinal();
            result = prime * result + wire0StringIdx;
            result = prime * result + wire1StringIdx;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Key other = (Key) obj;
            return wire0StringIdx == other.wire0StringIdx &&
                    wire1StringIdx == other.wire1StringIdx &&
                    tileTypeEnum == other.tileTypeEnum;
        }
    }

    private final Map<Key, PIP> map;

    private final List<String> strings;

    public PIPCache(Map<Key, PIP> map, List<String> strings) {
        this.map = map;
        this.strings = strings;
    }

    private Integer getWireIndex(Tile tile, int wireStringIdx) {
        String wireName = strings.get(wireStringIdx);
        return tile.getWireIndex(wireName);
    }

    public PIP getPIP(Tile tile, int wire0StringIdx, int wire1StringIdx) {
        Key key = new Key(tile, wire0StringIdx, wire1StringIdx);
        PIP pip = map.computeIfAbsent(key, (k) -> {
            Integer wire0Idx = getWireIndex(tile, k.wire0StringIdx);
            if (wire0Idx == null) {
                String wire0 = strings.get(k.wire0StringIdx);
                throw new RuntimeException("ERROR: Wire0 " + wire0 + " in tile " + tile + " not found.");
            }

            Integer wire1Idx = getWireIndex(tile, k.wire1StringIdx);
            if (wire1Idx == null) {
                String wire1 = strings.get(k.wire1StringIdx);
                throw new RuntimeException("ERROR: Wire1 " + wire1 + " in tile " + tile + " not found.");
            }

            return tile.getPIP(wire0Idx, wire1Idx);
        });
        PIP newPIP = new PIP(pip);
        newPIP.setTile(tile);
        return newPIP;
    }
}
