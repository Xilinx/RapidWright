/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.family;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusTimingFamily;
import com.xilinx.rapidwright.timing.versal.VersalTimingFamily;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Checks the timing data files (see {@link TimingData}).
 */
public class TestTimingData {

    private static void checkChecksums(String name, long interconnect, long cellArcs, long clock) {
        TimingData data = TimingData.get(name);
        Assertions.assertEquals(new HashSet<>(Arrays.asList(TimingData.INTERCONNECT, TimingData.CELL_ARCS,
                TimingData.CLOCK)), new HashSet<>(data.getSectionNames()), name);
        Assertions.assertEquals(Long.toHexString(interconnect),
                Long.toHexString(data.getChecksum(TimingData.INTERCONNECT)), name);
        Assertions.assertEquals(Long.toHexString(cellArcs), Long.toHexString(data.getChecksum(TimingData.CELL_ARCS)),
                name);
        Assertions.assertEquals(Long.toHexString(clock), Long.toHexString(data.getChecksum(TimingData.CLOCK)), name);
    }

    /**
     * The checksums of the data of each section of the timing data files, which change with the data only (not
     * with the format of the files): a change of the data shows here.
     */
    @Test
    public void testChecksums() {
        checkChecksums("ultrascaleplus", 0x9f0b6eaa3dbbadbbL, 0xf1dad110a2b14e7aL, 0x0efd6076d3c6b9bcL);
        checkChecksums("versal_gen1", 0x30e645994ede44a4L, 0xbc0708538958ec22L, 0x64d3151282fa3232L);
        checkChecksums("versal_gen2", 0x84dcb322dfced79aL, 0xb5e0e2bff21382edL, 0x87596191ac44bf4cL);
    }

    @Test
    public void testDataSets() {
        Assertions.assertEquals(Arrays.asList("ultrascaleplus", "versal_gen1", "versal_gen2"), TimingData.getNames());
        Assertions.assertEquals(String.join(File.separator, "data", "timing", "versal", "versal_gen1_timing.dat"),
                TimingData.getFileName("versal_gen1"));
        Assertions.assertEquals(String.join(File.separator, "data", "timing", "ultrascaleplus",
                "ultrascaleplus_timing.dat"), TimingData.getFileName(UltraScalePlusTimingFamily.DATA_NAME));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TimingData.getFileName("series7"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TimingData.get("series7"));
        for (String fileName : TimingData.getFileNames()) {
            // Timing data files are data files of the release, downloaded on demand like the device files
            Assertions.assertNotNull(FileTools.getCurrentDataVersion(fileName), fileName);
        }
        Assertions.assertEquals("ultrascaleplus", TimingData.getName(PartNameTools.getPart("xcvu3p")));
        Assertions.assertEquals("versal_gen1", TimingData.getName(PartNameTools.getPart("xcvc1902")));
        Assertions.assertEquals("versal_gen2", TimingData.getName(PartNameTools.getPart("xc2ve3858")));
        // No timing data for the series without a timing model
        Assertions.assertNull(TimingData.getName(PartNameTools.getPart(Device.KCU105)));
        Assertions.assertNull(TimingData.getName(PartNameTools.getPart("xc7a35t")));
        TimingData data = TimingData.get(PartNameTools.getPart("xcvc1902"));
        Assertions.assertSame(TimingData.get("versal_gen1"), data);
        Assertions.assertSame(data, VersalTimingFamily.getTimingData(VersalTimingFamily.Generation.GEN1));
        Assertions.assertEquals("versal_gen1 interconnect v1", data.getDataVersion(TimingData.INTERCONNECT));
        Assertions.assertFalse(data.getProvenance(TimingData.CELL_ARCS).isEmpty());
        Assertions.assertThrows(IllegalArgumentException.class, () -> data.getChecksum("other"));
        // The data of a set is read once
        Assertions.assertSame(data.getInterconnectParams(Corner.FAST_MIN), data.getInterconnectParams(Corner.FAST_MIN));
        Assertions.assertSame(data.getFittedCellArcs(), data.getFittedCellArcs());
        Assertions.assertEquals(data.getChecksum(TimingData.CELL_ARCS), data.getFittedCellArcs().getDataChecksum());
        Assertions.assertNotNull(data.getClockSection());
    }

    @Test
    public void testDataChecksum() {
        // FNV-1a test vectors
        Assertions.assertEquals(0xcbf29ce484222325L, new DataChecksum().getValue());
        Assertions.assertEquals(0xaf63dc4c8601ec8cL, new DataChecksum().addByte('a').getValue());
        // A String adds a terminating 0 byte
        Assertions.assertEquals(new DataChecksum().addByte('a').addByte(0).getValue(), new DataChecksum().add("a").getValue());
    }

    @Test
    public void testUltraScalePlusSpeedScales() {
        // Interconnect factors of -3 and -2LV at the slow corner: the route delays of the critical connections of
        // routed designs re-timed at these grades and at -2 (timing data matching Vivado 2026.1 report_timing)
        InterconnectParams p = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        Assertions.assertEquals(1f, p.getSpeedScale("2"));
        Assertions.assertEquals(0.961f, p.getSpeedScale("3"));
        Assertions.assertEquals(1.138f, p.getSpeedScale("2LV"));
        // -2LVI inherits the factor of -2LV
        Assertions.assertEquals(p.getSpeedScale("2LV"), p.getSpeedScale("2LVI"));
        // The other grades and corners keep their factors
        Assertions.assertEquals(1.126f, p.getSpeedScale("1"));
        Assertions.assertEquals(0.952f, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MIN).getSpeedScale("3"));
        // UltraScale+ fanout delays scale like the other route delays
        Assertions.assertEquals(p.getSpeedScale("3"), p.getFanoutSpeedScale("3"));
    }

    @Test
    public void testVersalData() {
        for (VersalTimingFamily.Generation gen : VersalTimingFamily.Generation.values()) {
            InterconnectParams params = VersalTimingFamily.getInterconnectParams(gen, Corner.SLOW_MAX);
            Assertions.assertEquals(Corner.SLOW_MAX, params.getCorner());
            Assertions.assertEquals(1f, params.getSpeedScale("-2MP"));
            Assertions.assertTrue(params.getAdmissibleLookaheadPerTileX() > 0);
            Assertions.assertTrue(params.getAdmissibleLookaheadPerTileX() < params.getLookaheadPerTileX());
            for (Corner corner : Corner.values()) {
                InterconnectParams p = VersalTimingFamily.getInterconnectParams(gen, corner);
                Assertions.assertEquals(corner, p.getCorner());
                Assertions.assertEquals(params.getNumNodeClasses(), p.getNumNodeClasses());
                Assertions.assertFalse(p.hasPinDeltas());
            }
            // Fast corners depend less on the speed grade
            Assertions.assertTrue(VersalTimingFamily.getInterconnectParams(gen, Corner.FAST_MIN).getSpeedScale("1LP")
                    < params.getSpeedScale("1LP"));
        }
        Assertions.assertEquals(1.192f, VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1,
                Corner.SLOW_MAX).getSpeedScale("1LP"));
        Assertions.assertEquals(1.123f, VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1,
                Corner.FAST_MIN).getSpeedScale("1LP"));
        Assertions.assertEquals(1.08f, VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1,
                Corner.SLOW_MAX).getFanoutSpeedScale("2HP"));
        Assertions.assertEquals(1.0199f, VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1,
                Corner.SLOW_MAX).getDeviceScale("xcvc1902"));
        Assertions.assertEquals(1.0047f, VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1,
                Corner.SLOW_MIN).getDeviceScale("xcvc1902"));
    }

    @Test
    public void testNoTimingFolderUnpacked() {
        for (String folder : FileTools.UNPACK_FOLDERS) {
            Assertions.assertFalse(folder.startsWith("timing"), folder);
        }
    }
}
