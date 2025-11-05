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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.util.Pair;

/**
 * For a given clock region Y coordinate domain range (e.g. Y1-Y5), provides a
 * set of clock root options with corresponding vertical distribution paths for
 * each target clock region.
 */
public class VersalClockTree {

    private int minMaxYKey;

    private int preferredClockRootY;

    /**
     * The key to this map is the clock root y coordinate. The value is a list of
     * vertical distribution paths for each target clock region.
     */
    private Map<Integer, int[][]> clockRootPathSets;

    public VersalClockTree(int minY, int maxY) {
        this.minMaxYKey = getMinMaxYRangeKey(minY, maxY);
    }

    public int getMinY() {
        return minMaxYKey >>> 16;
    }

    public int getMaxY() {
        return minMaxYKey & 0xffff;
    }

    public int getMinMaxYKey() {
        return minMaxYKey;
    }

    public int getPreferredClockRootYCoord() {
        return preferredClockRootY;
    }

    public void setPreferredClockRootYCoord(int clockRootYCoord) {
        this.preferredClockRootY = clockRootYCoord;
    }

    public Map<Integer, int[][]> getClockRootPathSets() {
        return clockRootPathSets;
    }

    public void setClockRootPathSets(Map<Integer, int[][]> clockRootPathSets) {
        this.clockRootPathSets = clockRootPathSets;
    }

    public Set<Integer> getClockRootOptions() {
        return clockRootPathSets.keySet();
    }

    /**
     * Gets the vertical distribution path of the target clock region for the
     * preferred clock root.
     * 
     * @param target The destination clock region that needs to be routed via
     *               vertical distribution lines.
     * @return A sequence of vertical distribution constraints (target IntentCode
     *         and ClockRegion).
     */
    public List<Pair<IntentCode, ClockRegion>> getClockRegionVDistrPath(ClockRegion target) {
        return getClockRegionVDistrPath(target, preferredClockRootY);
    }

    /**
     * Gets the vertical distribution path of the target clock region for the
     * provided clock root.
     * 
     * @param target          The destination clock region that needs to be routed
     *                        via vertical distribution lines.
     * @param clockRootYCoord The desired clock root Y coordinate path set to use.
     * @return A sequence of vertical distribution constraints (target IntentCode
     *         and ClockRegion).
     */
    public List<Pair<IntentCode, ClockRegion>> getClockRegionVDistrPath(ClockRegion target,
            int clockRootYCoord) {
        int[][] pathData = clockRootPathSets.get(clockRootYCoord);

        if (pathData == null) {
            System.err.println(
                    "Missing VDISTR tree for " + target.getDevice() + " targeting CR " + target);
            Pair<IntentCode, ClockRegion> simple = new Pair<>(IntentCode.NODE_GLOBAL_VDISTR, target);
            return Collections.singletonList(simple);
        }

        int[] crPathData = pathData[target.getInstanceY()];
        List<Pair<IntentCode, ClockRegion>> vdistrPath = new ArrayList<>(crPathData.length);
        for (int value : crPathData) {
            IntentCode vdistrCode = IntentCode.values()[value >>> 16];
            int crY = value & 0xffff;
            ClockRegion cr = target.getDevice().getClockRegion(crY, target.getInstanceX());
            vdistrPath.add(new Pair<>(vdistrCode, cr));
        }
        return vdistrPath;
    }

    public static int getMinMaxYRangeKey(int minY, int maxY) {
        assert (minY >= 0 && maxY >= 0 && maxY >= minY);
        return (minY << 16) | maxY;
    }
}
