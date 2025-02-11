/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

/**
 * Simple tool for generating a routing heat map as a CSV for a given DCP.
 */
public class RoutingHeatMap {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("USAGE: <routed_input.dcp> <output.csv>");
            return;
        }
        Design d = Design.readCheckpoint(args[0]);
        Map<Tile, Integer> heatMap = new HashMap<>();

        for (Net n : d.getNets()) {
            for (PIP p : n.getPIPs()) {
                if (p.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
                    heatMap.merge(p.getTile(), 1, Integer::sum);
                }
            }
        }

        Tile[][] intTiles = d.getDevice().getTilesByRootName(TileTypeEnum.INT.name());

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(args[1]))) {
            for (int i = 0; i < intTiles.length; i++) {
                Tile[] intArray = intTiles[i];
                for (int j = 0; j < intArray.length; j++) {
                    Tile t = intArray[j];
                    Integer val = heatMap.get(t);
                    bw.write((val == null ? 0 : val) + ",");
                }
                bw.write("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
