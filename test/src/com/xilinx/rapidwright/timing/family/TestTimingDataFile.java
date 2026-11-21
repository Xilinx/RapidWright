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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.esotericsoftware.kryo.io.Output;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Tests the format of the timing data files ({@link TimingDataFile}), the data that {@link TimingData} reads
 * from them, and their text ({@link TimingDataFile#writeText(PrintWriter)}), on a synthetic data set.
 */
public class TestTimingDataFile {

    private static final String[] NODE_CLASSES = {"OTHER", "SINGLE", "LONG"};

    /** A data set with an interconnect section of two corners and a section of another kind */
    private static TimingDataFile createFile() {
        TimingDataFile file = new TimingDataFile("synthetic");
        TimingDataFile.Section s = file.addSection(TimingData.INTERCONNECT, "synthetic interconnect v1", "made up");
        s.put("speedRef", "2")
                .put("nodeClasses", NODE_CLASSES)
                .put("colClassNames", new String[]{"BRAM"})
                .put("colClassRegexes", new String[]{"^BRAM$"})
                .put("rowClassNames", new String[]{"RCLK"})
                .put("rowClassRegexes", new String[]{"^RCLK_"})
                .put("trackClass", "BRAM")
                .put("trackKeys", new String[]{"INT.WIRE_A"});
        int i = 0;
        for (Corner corner : new Corner[]{Corner.SLOW_MAX, Corner.FAST_MIN}) {
            float f = corner == Corner.SLOW_MAX ? 1f : 0.5f;
            String c = corner + ".";
            s.put("corner." + i++, corner.name())
                    .put(c + "base", new float[]{0f, 10f * f, 40f * f})
                    .put(c + "length", new float[]{0f, 2.5f * f, 1.25f * f})
                    .put(c + "crossing", new float[][]{{0f, 0f}, {30f * f, 12f * f}, {35f * f, 0f}})
                    .put(c + "transitionFrom", new int[]{1})
                    .put(c + "transitionTo", new int[]{2})
                    .put(c + "transitionDelay", new float[]{7f * f})
                    .put(c + "fanout", new float[]{0f, 3f * f, 4f * f})
                    .put(c + "fanoutLoad", new float[]{0f, 0.5f * f, 0.25f * f})
                    .put(c + "fanoutCrossing", new float[]{1f * f, 2f * f})
                    .put(c + "speedGrades", new String[]{"1", "2"})
                    .put(c + "speedScales", new float[]{1.125f, 1f})
                    .put(c + "devices", new String[]{"xcvu9p"})
                    .put(c + "deviceScales", new float[]{1.01f})
                    .put(c + "trackDeltas", new short[]{(short) (corner == Corner.SLOW_MAX ? -25 : -12)});
        }
        s.put("lookahead", new float[]{30f, 12f, 10f, 4f}).put("preRouteDelay", 90.5f);
        file.addSection("other", "synthetic other v2", "made up as well")
                .put("ints", new int[]{0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x434c4b31})
                .put("shorts", new short[]{Short.MIN_VALUE, -1, 0, Short.MAX_VALUE})
                .put("strings", new String[]{"", "a b", null, "\u00b5s"})
                .put("empty", new float[0][])
                .put("ragged", new float[][]{{}, {1.5f}, {Float.MIN_VALUE, -0f}});
        return file;
    }

    private static byte[] toBytes(TimingDataFile file) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        file.write(os);
        return os.toByteArray();
    }

    private static TimingDataFile fromBytes(byte[] bytes) {
        return TimingDataFile.read(new ByteArrayInputStream(bytes), "test data");
    }

    @Test
    public void testRoundTrip(@TempDir Path tempDir) throws IOException {
        TimingDataFile file = createFile();
        Path path = tempDir.resolve("synthetic_timing.dat");
        file.write(path);
        TimingDataFile read = TimingDataFile.read(path);
        Assertions.assertEquals("synthetic", read.getName());
        Assertions.assertEquals(2, read.getSections().size());
        for (TimingDataFile.Section a : file.getSections()) {
            TimingDataFile.Section b = read.getSection(a.getName());
            Assertions.assertEquals(a.getDataVersion(), b.getDataVersion());
            Assertions.assertEquals(a.getProvenance(), b.getProvenance());
            Assertions.assertEquals(a.getChecksum(), b.getChecksum());
            Assertions.assertEquals(Arrays.asList(a.getEntryNames().toArray()),
                    Arrays.asList(b.getEntryNames().toArray()));
            for (String entry : a.getEntryNames()) {
                Assertions.assertTrue(Arrays.deepEquals(new Object[]{a.get(entry)}, new Object[]{b.get(entry)}),
                        a.getName() + " " + entry);
            }
        }
        Assertions.assertNull(read.getSection("clock"));
        TimingDataFile.Section other = read.getSection("other");
        Assertions.assertArrayEquals(new int[]{0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x434c4b31},
                other.getInts("ints"));
        Assertions.assertArrayEquals(new String[]{"", "a b", null, "\u00b5s"}, other.getStrings("strings"));
        Assertions.assertEquals(0, other.getFloatArrays("empty").length);
        Assertions.assertEquals(Float.floatToIntBits(-0f), Float.floatToIntBits(other.getFloatArrays("ragged")[2][1]));
        Assertions.assertEquals(90.5f, read.getSection(TimingData.INTERCONNECT).getFloat("preRouteDelay"));
        // The same data gives the same file
        byte[] bytes = Files.readAllBytes(path);
        Assertions.assertArrayEquals(bytes, toBytes(createFile()));
    }

    @Test
    public void testEntries() {
        TimingDataFile file = createFile();
        TimingDataFile.Section s = file.getSection("other");
        Assertions.assertTrue(s.has("ints"));
        Assertions.assertFalse(s.has("floats"));
        Assertions.assertNull(s.get("floats"));
        // Entries are typed, and neither they nor sections are replaced
        Assertions.assertThrows(IllegalArgumentException.class, () -> s.getFloats("floats"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> s.getFloats("ints"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> s.getString("strings"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> s.put("ints", new int[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> s.put("null", (String[]) null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> s.put("nullRow", new float[][]{null}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> file.addSection("other", "v", "p"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> s.getEntryNames().clear());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> file.getSections().clear());
    }

    /**
     * The checksum of a section is the {@link DataChecksum} of its values in their order: it changes with a
     * value and with the order of the entries, not with their names.
     */
    @Test
    public void testChecksum() {
        TimingDataFile file = new TimingDataFile("t");
        long expected = new DataChecksum().add("2").add(new int[]{1, 2}).add(1.5f).add(new float[][]{{1f}, {}})
                .add(new short[]{3}).add(new String[]{"a", null}).getValue();
        Assertions.assertEquals(expected, file.addSection("a", "v", "p").put("speedRef", "2")
                .put("ints", new int[]{1, 2}).put("f", 1.5f).put("m", new float[][]{{1f}, {}}).put("s", new short[]{3})
                .put("strings", new String[]{"a", null}).getChecksum());
        Assertions.assertEquals(expected, file.addSection("renamed", "v2", "q").put("x", "2").put("y", new int[]{1, 2})
                .put("z", 1.5f).put("u", new float[][]{{1f}, {}}).put("v", new short[]{3})
                .put("w", new String[]{"a", null}).getChecksum());
        Assertions.assertNotEquals(expected, file.addSection("value", "v", "p").put("speedRef", "2")
                .put("ints", new int[]{1, 3}).put("f", 1.5f).put("m", new float[][]{{1f}, {}}).put("s", new short[]{3})
                .put("strings", new String[]{"a", null}).getChecksum());
        Assertions.assertNotEquals(expected, file.addSection("order", "v", "p").put("ints", new int[]{1, 2})
                .put("speedRef", "2").put("f", 1.5f).put("m", new float[][]{{1f}, {}}).put("s", new short[]{3})
                .put("strings", new String[]{"a", null}).getChecksum());
        Assertions.assertEquals(new DataChecksum().getValue(), file.addSection("empty", "v", "p").getChecksum());
    }

    /** Writes the start of a timing data file */
    private static byte[] header(int magic, int version) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Output out = FileTools.getKryoZstdOutputStream(os)) {
            out.writeInt(magic);
            out.writeInt(version);
            out.writeString("t");
            out.writeVarInt(0, true);
        }
        return os.toByteArray();
    }

    @Test
    public void testRefusesOtherFiles() {
        Assertions.assertEquals(0, fromBytes(header(TimingDataFile.MAGIC, TimingDataFile.FORMAT_VERSION))
                .getSections().size());
        // Not a timing data file: no Zstandard stream, an empty one, another magic number
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> fromBytes("not compressed".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> fromBytes(new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> fromBytes(header(0x12345678, TimingDataFile.FORMAT_VERSION)));
        // A later format version names the versions that can be read
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> fromBytes(header(TimingDataFile.MAGIC, TimingDataFile.FORMAT_VERSION + 1)));
        Assertions.assertTrue(e.getMessage().contains("format version " + (TimingDataFile.FORMAT_VERSION + 1)),
                e.getMessage());
        Assertions.assertThrows(IllegalArgumentException.class, () -> fromBytes(header(TimingDataFile.MAGIC, 0)));
    }

    @Test
    public void testRefusesCorruptFiles() {
        // A section whose data does not have its checksum
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (Output out = FileTools.getKryoZstdOutputStream(os)) {
            out.writeInt(TimingDataFile.MAGIC);
            out.writeInt(TimingDataFile.FORMAT_VERSION);
            out.writeString("t");
            out.writeVarInt(1, true);
            out.writeString("a");
            out.writeString("v");
            out.writeString("p");
            out.writeLong(new DataChecksum().add("2").getValue());
            out.writeVarInt(1, true);
            out.writeString("speedRef");
            out.writeByte(1);
            out.writeString("3");
        }
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> fromBytes(os.toByteArray()));
        Assertions.assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        // An entry of a type that this version does not know
        ByteArrayOutputStream os2 = new ByteArrayOutputStream();
        try (Output out = FileTools.getKryoZstdOutputStream(os2)) {
            out.writeInt(TimingDataFile.MAGIC);
            out.writeInt(TimingDataFile.FORMAT_VERSION);
            out.writeString("t");
            out.writeVarInt(1, true);
            out.writeString("a");
            out.writeString("v");
            out.writeString("p");
            out.writeLong(0);
            out.writeVarInt(1, true);
            out.writeString("x");
            out.writeByte(99);
        }
        Assertions.assertThrows(IllegalStateException.class, () -> fromBytes(os2.toByteArray()));
        // A file that ends early
        ByteArrayOutputStream os3 = new ByteArrayOutputStream();
        try (Output out = FileTools.getKryoZstdOutputStream(os3)) {
            out.writeInt(TimingDataFile.MAGIC);
            out.writeInt(TimingDataFile.FORMAT_VERSION);
            out.writeString("t");
            out.writeVarInt(2, true);
        }
        e = Assertions.assertThrows(IllegalStateException.class, () -> fromBytes(os3.toByteArray()));
        Assertions.assertTrue(e.getMessage().contains("test data"), e.getMessage());
        // Data after the last section
        ByteArrayOutputStream os4 = new ByteArrayOutputStream();
        try (Output out = FileTools.getKryoZstdOutputStream(os4)) {
            out.writeInt(TimingDataFile.MAGIC);
            out.writeInt(TimingDataFile.FORMAT_VERSION);
            out.writeString("t");
            out.writeVarInt(0, true);
            out.writeString("a");
        }
        Assertions.assertThrows(IllegalStateException.class, () -> fromBytes(os4.toByteArray()));
    }

    @Test
    public void testTimingData() {
        TimingData data = new TimingData(fromBytes(toBytes(createFile())));
        Assertions.assertEquals("synthetic", data.getName());
        Assertions.assertEquals(Arrays.asList(TimingData.INTERCONNECT, "other"), data.getSectionNames());
        Assertions.assertEquals("synthetic other v2", data.getDataVersion("other"));
        Assertions.assertEquals("made up", data.getProvenance(TimingData.INTERCONNECT));
        Assertions.assertEquals(createFile().getSection("other").getChecksum(), data.getChecksum("other"));
        InterconnectParams p = data.getInterconnectParams(Corner.SLOW_MAX);
        Assertions.assertSame(p, data.getInterconnectParams(Corner.SLOW_MAX));
        Assertions.assertEquals("synthetic interconnect v1", p.getDataVersion());
        Assertions.assertEquals(Corner.SLOW_MAX, p.getCorner());
        Assertions.assertEquals("2", p.getSpeedRef());
        Assertions.assertEquals(Arrays.asList(NODE_CLASSES), p.getNodeClassNames());
        Assertions.assertEquals(Arrays.asList("BRAM"), p.getColClassNames());
        Assertions.assertEquals(Arrays.asList("RCLK"), p.getRowClassNames());
        Assertions.assertArrayEquals(new float[]{30f, 12f}, p.getCrossingWeights(1));
        Assertions.assertEquals(30f, p.getLookaheadPerTileX());
        Assertions.assertEquals(12f, p.getLookaheadPerTileY());
        Assertions.assertEquals(10f, p.getAdmissibleLookaheadPerTileX());
        Assertions.assertEquals(4f, p.getAdmissibleLookaheadPerTileY());
        Assertions.assertEquals(90.5f, p.getPreRouteDelay());
        Assertions.assertEquals(1.125f, p.getSpeedScale("1"));
        Assertions.assertEquals(1.01f, p.getDeviceScale("xcvu9p"));
        Assertions.assertFalse(p.hasPinDeltas());
        Assertions.assertEquals(-2.5f, p.getTrackDeltas().get("INT").get("WIRE_A"));
        Assertions.assertEquals(0, p.getTrackDeltaClass());
        InterconnectParams fast = data.getInterconnectParams(Corner.FAST_MIN);
        Assertions.assertEquals(Corner.FAST_MIN, fast.getCorner());
        Assertions.assertArrayEquals(new float[]{15f, 6f}, fast.getCrossingWeights(1));
        Assertions.assertEquals(-1.2f, fast.getTrackDeltas().get("INT").get("WIRE_A"));
        // The data has neither the other corners, nor cell arcs, nor clock data
        UnsupportedOperationException e = Assertions.assertThrows(UnsupportedOperationException.class,
                () -> data.getInterconnectParams(Corner.SLOW_MIN));
        Assertions.assertTrue(e.getMessage().contains("SLOW_MIN"), e.getMessage());
        Assertions.assertThrows(UnsupportedOperationException.class, data::getFittedCellArcs);
        Assertions.assertNull(data.getClockSection());
        Assertions.assertThrows(IllegalArgumentException.class, () -> data.getDataVersion(TimingData.CLOCK));
    }

    @Test
    public void testWriteText() {
        StringWriter text = new StringWriter();
        createFile().writeText(new PrintWriter(text));
        List<String> lines = Arrays.asList(text.toString().split("\\r?\\n"));
        long checksum = createFile().getSection(TimingData.INTERCONNECT).getChecksum();
        for (String line : new String[]{
            "# RapidWright timing data synthetic",
            "[interconnect] synthetic interconnect v1, checksum " + Long.toHexString(checksum),
            "# made up",
            "# delays in ps at speed grade 2",
            "lookahead perColumn=30 perRow=12 admissiblePerColumn=10 admissiblePerRow=4",
            "preRouteDelay 90.5",
            "columnClass BRAM ^BRAM$",
            "rowClass RCLK ^RCLK_",
            "trackClasses BRAM",
            "corner SLOW_MAX",
            "  class SINGLE base=10 length=2.5 fanout=3 fanoutLoad=0.5 crossing BRAM=30 RCLK=12",
            "  fanoutCrossing BRAM=1 RCLK=2",
            "  transition SINGLE LONG 7",
            "  speedGrade 1 1.125",
            "  device xcvu9p 1.01",
            "  trackDelta INT.WIRE_A -2.5",
            "corner FAST_MIN",
            "  class LONG base=20 length=0.625 fanout=2 fanoutLoad=0.125 crossing BRAM=17.5 RCLK=0",
            "  trackDelta INT.WIRE_A -1.2",
            "ints = 0 -1 1 -2147483648 2147483647 1129073457",
            "strings =  a b null \u00b5s",
            "empty =",
            "ragged = [ ] [ 1.5 ] [ 1.4E-45 0 ]"}) {
            Assertions.assertTrue(lines.contains(line), line + " not in\n" + text);
        }
        Assertions.assertFalse(lines.contains("corner SLOW_MIN"));
    }

    /** The text of the timing data files of this release has every section, and is the same each time */
    @Test
    public void testWriteTextOfDataFiles(@TempDir Path tempDir) throws IOException {
        for (String name : TimingData.getNames()) {
            FittedCellArcLibrary library = TimingData.get(name).getFittedCellArcs();
            Path text = tempDir.resolve(name + ".txt");
            TimingData.readFile(name).writeText(text);
            List<String> lines = Files.readAllLines(text, StandardCharsets.UTF_8);
            Assertions.assertEquals("# RapidWright timing data " + name, lines.get(0));
            int arcs = 0;
            int configs = 0;
            int sections = 0;
            for (String line : lines) {
                arcs += line.startsWith("arc ") ? 1 : 0;
                configs += line.startsWith("config ") ? 1 : 0;
                sections += line.startsWith("[") ? 1 : 0;
            }
            Assertions.assertEquals(3, sections, name);
            Assertions.assertEquals(library.getNumArcs(), arcs, name);
            Assertions.assertEquals(library.getNumConfigDelays(), configs, name);
            Assertions.assertTrue(lines.contains("corner FAST_MAX"), name);
            Assertions.assertTrue(lines.contains("  \"speedRef\": \"" + library.getSpeedRef() + "\","), name);
            // The file is there once its data set is loaded
            Path dataFile = Paths.get(FileTools.getRapidWrightResourceFileName(TimingData.getFileName(name)));
            Path again = tempDir.resolve(name + "_again.txt");
            TimingDataFile.read(dataFile).writeText(again);
            Assertions.assertArrayEquals(Files.readAllBytes(text), Files.readAllBytes(again), name);
        }
    }
}
