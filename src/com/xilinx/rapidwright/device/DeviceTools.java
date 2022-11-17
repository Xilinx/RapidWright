/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AECG Research Labs.
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

package com.xilinx.rapidwright.device;

import java.util.HashMap;
import java.util.Map;

public class DeviceTools {

    /**
     * Maps from the Name Root to a name that is equal for all relocatable tiles
     */
    private static Map<String, String> versalHalfFSRTileTypes;

    static {

        versalHalfFSRTileTypes = new HashMap<>();
        // URAMs
        versalHalfFSRTileTypes.put("URAM_ROCF_BL_TILE", "URAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("URAM_ROCF_TL_TILE", "URAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("URAM_LOCF_BL_TILE", "URAM_LOCF_?L_TILE");
        versalHalfFSRTileTypes.put("URAM_LOCF_TL_TILE", "URAM_LOCF_?L_TILE");
        // DSPs
        versalHalfFSRTileTypes.put("DSP_ROCF_B_TILE", "DSP_ROCF_?_TILE");
        versalHalfFSRTileTypes.put("DSP_ROCF_T_TILE", "DSP_ROCF_?_TILE");
        // INTFs
        versalHalfFSRTileTypes.put("INTF_ROCF_BL_TILE", "INTF_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("INTF_ROCF_TL_TILE", "INTF_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("INTF_ROCF_BR_TILE", "INTF_ROCF_?R_TILE");
        versalHalfFSRTileTypes.put("INTF_ROCF_TR_TILE", "INTF_ROCF_?R_TILE");
        // BRAMs
        versalHalfFSRTileTypes.put("BRAM_ROCF_BL_TILE", "BRAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("BRAM_ROCF_TL_TILE", "BRAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("BRAM_ROCF_BR_TILE", "BRAM_ROCF_?R_TILE");
        versalHalfFSRTileTypes.put("BRAM_ROCF_TR_TILE", "BRAM_ROCF_?R_TILE");
        versalHalfFSRTileTypes.put("BRAM_LOCF_BR_TILE", "BRAM_LOCF_?R_TILE");
        versalHalfFSRTileTypes.put("BRAM_LOCF_TR_TILE", "BRAM_LOCF_?R_TILE");

        // URAMs on 7 series
        versalHalfFSRTileTypes.put("URAM_URAM_DELAY_FT", "URAM_URAM_FT");
    }

    public static Map<String, Tile[][]> createTileByRootNameCache(Device device) {
        // Figure out the array dimensions we need
        Map<String, Integer> rootNameMaxX = new HashMap<>();
        Map<String, Integer> rootNameMaxY = new HashMap<>();
        for (Tile t : device.getAllTiles()) {
            final String p = versalHalfFSRTileTypes.getOrDefault(t.getRootName(), t.getRootName());
            Integer existingX = rootNameMaxX.get(p);
            if (existingX == null || existingX < t.getTileXCoordinate()) {
                rootNameMaxX.put(p, t.getTileXCoordinate());
            }
            Integer existingY = rootNameMaxY.get(p);
            if (existingY == null || existingY < t.getTileYCoordinate()) {
                rootNameMaxY.put(p, t.getTileYCoordinate());
            }
        }

        // Create the arrays
        Map<String, Tile[][]> tileByRootNameCache = new HashMap<>();
        for (String prefix : rootNameMaxX.keySet()) {
            final int x = rootNameMaxX.get(prefix);
            final int y = rootNameMaxY.get(prefix);
            tileByRootNameCache.put(prefix, new Tile[y + 1][x + 1]);
        }

        // Link Versal Half FSR Tiles
        versalHalfFSRTileTypes.forEach((original, generalized) -> {
            tileByRootNameCache.put(original, tileByRootNameCache.get(generalized));
        });

        // Fill the arrays
        for (Tile t : device.getAllTiles()) {
            final Tile[][] arr = tileByRootNameCache.get(t.getRootName());
            arr[t.getTileYCoordinate()][t.getTileXCoordinate()] = t;
        }

        return tileByRootNameCache;
    }
}
