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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.xilinx.rapidwright.timing.clock.ClockData;

/**
 * A timing data file: the fitted delay data of one timing data set (the devices that share one fit, e.g.
 * versal_gen1), which {@link TimingData} loads on demand. {@link #writeText(Path)} prints a file as text, for
 * debugging:
 * <pre>
 *   TimingDataFile.read(Paths.get("versal_gen1_timing.dat")).writeText(Paths.get("versal_gen1_timing.txt"));
 * </pre>
 * <p>
 * A file holds named sections ({@link TimingData#INTERCONNECT}, {@link TimingData#CELL_ARCS},
 * {@link TimingData#CLOCK}; a reader ignores the sections it does not know, so that data can be added). A
 * section has the version and the provenance of its data and named entries in a fixed order, each a string, a
 * float, or an array of strings, ints, shorts, floats or float arrays. The meaning of the entries is that of
 * their reader, e.g. {@link TimingData#getInterconnectParams(Corner)}.
 * <p>
 * The checksum of a section is the {@link DataChecksum} of the values of its entries, in their order. It is
 * stored with the section and checked when the file is read, and it does not depend on how the file encodes
 * the values.
 * <p>
 * On disk, a file is a Zstandard-compressed Kryo stream (as Kryo's {@link Output} writes it, the same on every
 * platform): the magic number and the format version (ints), the name of the data set and the number of
 * sections; for each section its name, data version, provenance, checksum (a long) and number of entries; for
 * each entry its name, its type (a byte) and its value, an array as its length followed by its elements.
 * Counts and the elements of int arrays are variable-length ints. The same data gives the same file (with the
 * same version of the Zstandard library), so that the md5sum of a file identifies its data.
 */
public final class TimingDataFile {

    /** First int of a timing data file ("RWTD") */
    public static final int MAGIC = 0x52575444;
    /** The version of the file format that this class writes, and the latest it reads */
    public static final int FORMAT_VERSION = 1;

    /** The Zstandard compression level of the files, whatever the level of the other files that RapidWright writes */
    private static final int COMPRESSION_LEVEL = 19;

    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_FLOAT = 2;
    private static final byte TYPE_STRINGS = 3;
    private static final byte TYPE_INTS = 4;
    private static final byte TYPE_SHORTS = 5;
    private static final byte TYPE_FLOATS = 6;
    private static final byte TYPE_FLOAT_ARRAYS = 7;

    private final String name;
    private final Map<String, Section> sections = new LinkedHashMap<>();

    /**
     * Creates an empty file.
     * @param name The name of the timing data set, e.g. versal_gen1.
     */
    public TimingDataFile(String name) {
        if (name == null) {
            throw new IllegalArgumentException("A timing data set needs a name");
        }
        this.name = name;
    }

    /**
     * @return The name of the timing data set.
     */
    public String getName() {
        return name;
    }

    /**
     * Adds an empty section.
     * @param name The name of the section.
     * @param dataVersion The family, kind and version of its data.
     * @param provenance Where its data comes from.
     * @return The section.
     * @throws IllegalArgumentException If the file has a section of that name.
     */
    public Section addSection(String name, String dataVersion, String provenance) {
        if (name == null || dataVersion == null || provenance == null) {
            throw new IllegalArgumentException("A section needs a name, a data version and a provenance");
        }
        Section s = new Section(this.name, name, dataVersion, provenance);
        if (sections.putIfAbsent(name, s) != null) {
            throw new IllegalArgumentException("Timing data " + this.name + " already has a section " + name);
        }
        return s;
    }

    /**
     * Gets a section.
     * @param name The name of the section.
     * @return The section, or null if the file has none of that name.
     */
    public Section getSection(String name) {
        return sections.get(name);
    }

    /**
     * @return The sections, in the order of the file.
     */
    public List<Section> getSections() {
        return Collections.unmodifiableList(new ArrayList<>(sections.values()));
    }

    /** A section of a timing data file: named entries in a fixed order */
    public static final class Section {
        private final String fileName;
        private final String name;
        private final String dataVersion;
        private final String provenance;
        private final Map<String, Object> entries = new LinkedHashMap<>();

        private Section(String fileName, String name, String dataVersion, String provenance) {
            this.fileName = fileName;
            this.name = name;
            this.dataVersion = dataVersion;
            this.provenance = provenance;
        }

        /**
         * @return The name of the section.
         */
        public String getName() {
            return name;
        }

        /**
         * @return The family, kind and version of the data.
         */
        public String getDataVersion() {
            return dataVersion;
        }

        /**
         * @return Where the data comes from.
         */
        public String getProvenance() {
            return provenance;
        }

        private Section add(String entry, Object value) {
            if (entry == null || value == null) {
                throw new IllegalArgumentException("An entry of " + this + " needs a name and a value");
            }
            if (entries.putIfAbsent(entry, value) != null) {
                throw new IllegalArgumentException(this + " already has an entry " + entry);
            }
            return this;
        }

        /**
         * Appends a string.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, String value) {
            return add(entry, value);
        }

        /**
         * Appends a float.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, float value) {
            return add(entry, (Float) value);
        }

        /**
         * Appends an array of strings, which may be null. The array is not copied.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, String[] value) {
            return add(entry, value);
        }

        /**
         * Appends an array of ints. The array is not copied.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, int[] value) {
            return add(entry, value);
        }

        /**
         * Appends an array of shorts. The array is not copied.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, short[] value) {
            return add(entry, value);
        }

        /**
         * Appends an array of floats. The array is not copied.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, float[] value) {
            return add(entry, value);
        }

        /**
         * Appends an array of float arrays, which may differ in length. The arrays are not copied.
         * @param entry The name of the entry.
         * @param value The value.
         * @return This section.
         * @throws IllegalArgumentException If the section has an entry of that name.
         */
        public Section put(String entry, float[][] value) {
            for (float[] row : value == null ? new float[0][] : value) {
                if (row == null) {
                    throw new IllegalArgumentException("Entry " + entry + " of " + this + " has a null array");
                }
            }
            return add(entry, value);
        }

        /**
         * @param entry The name of an entry.
         * @return True if the section has the entry.
         */
        public boolean has(String entry) {
            return entries.containsKey(entry);
        }

        /**
         * @return The names of the entries, in their order.
         */
        public Set<String> getEntryNames() {
            return Collections.unmodifiableSet(entries.keySet());
        }

        /**
         * Gets the value of an entry.
         * @param entry The name of the entry.
         * @return The value (a String, a Float, a String[], an int[], a short[], a float[] or a float[][]; arrays
         * are not to be modified), or null if the section has no such entry.
         */
        public Object get(String entry) {
            return entries.get(entry);
        }

        private <T> T get(String entry, Class<T> type) {
            Object v = entries.get(entry);
            if (!type.isInstance(v)) {
                throw new IllegalArgumentException(this + (v == null ? " has no entry " + entry
                        : ": entry " + entry + " is a " + v.getClass().getSimpleName() + ", not a "
                                + type.getSimpleName()));
            }
            return type.cast(v);
        }

        /**
         * Gets a string.
         * @param entry The name of the entry.
         * @return The value.
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public String getString(String entry) {
            return get(entry, String.class);
        }

        /**
         * Gets a float.
         * @param entry The name of the entry.
         * @return The value.
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public float getFloat(String entry) {
            return get(entry, Float.class);
        }

        /**
         * Gets an array of strings.
         * @param entry The name of the entry.
         * @return The value (not to be modified).
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public String[] getStrings(String entry) {
            return get(entry, String[].class);
        }

        /**
         * Gets an array of ints.
         * @param entry The name of the entry.
         * @return The value (not to be modified).
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public int[] getInts(String entry) {
            return get(entry, int[].class);
        }

        /**
         * Gets an array of shorts.
         * @param entry The name of the entry.
         * @return The value (not to be modified).
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public short[] getShorts(String entry) {
            return get(entry, short[].class);
        }

        /**
         * Gets an array of floats.
         * @param entry The name of the entry.
         * @return The value (not to be modified).
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public float[] getFloats(String entry) {
            return get(entry, float[].class);
        }

        /**
         * Gets an array of float arrays.
         * @param entry The name of the entry.
         * @return The value (not to be modified).
         * @throws IllegalArgumentException If the section has no such entry of that type.
         */
        public float[][] getFloatArrays(String entry) {
            return get(entry, float[][].class);
        }

        /**
         * Computes the checksum of the data of this section: the {@link DataChecksum} of the values of its
         * entries, in their order.
         * @return The checksum.
         */
        public long getChecksum() {
            DataChecksum c = new DataChecksum();
            for (Object v : entries.values()) {
                if (v instanceof String) {
                    c.add((String) v);
                } else if (v instanceof Float) {
                    c.add((float) (Float) v);
                } else if (v instanceof String[]) {
                    c.add((String[]) v);
                } else if (v instanceof int[]) {
                    c.add((int[]) v);
                } else if (v instanceof short[]) {
                    c.add((short[]) v);
                } else if (v instanceof float[]) {
                    c.add((float[]) v);
                } else {
                    c.add((float[][]) v);
                }
            }
            return c.getValue();
        }

        private void write(Output out) {
            out.writeString(name);
            out.writeString(dataVersion);
            out.writeString(provenance);
            out.writeLong(getChecksum());
            out.writeVarInt(entries.size(), true);
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                out.writeString(e.getKey());
                Object v = e.getValue();
                if (v instanceof String) {
                    out.writeByte(TYPE_STRING);
                    out.writeString((String) v);
                } else if (v instanceof Float) {
                    out.writeByte(TYPE_FLOAT);
                    out.writeFloat((Float) v);
                } else if (v instanceof String[]) {
                    out.writeByte(TYPE_STRINGS);
                    String[] a = (String[]) v;
                    out.writeVarInt(a.length, true);
                    for (String s : a) {
                        out.writeString(s);
                    }
                } else if (v instanceof int[]) {
                    out.writeByte(TYPE_INTS);
                    int[] a = (int[]) v;
                    out.writeVarInt(a.length, true);
                    for (int e2 : a) {
                        out.writeVarInt(e2, false);
                    }
                } else if (v instanceof short[]) {
                    out.writeByte(TYPE_SHORTS);
                    short[] a = (short[]) v;
                    out.writeVarInt(a.length, true);
                    out.writeShorts(a, 0, a.length);
                } else if (v instanceof float[]) {
                    out.writeByte(TYPE_FLOATS);
                    writeFloats(out, (float[]) v);
                } else {
                    out.writeByte(TYPE_FLOAT_ARRAYS);
                    float[][] a = (float[][]) v;
                    out.writeVarInt(a.length, true);
                    for (float[] row : a) {
                        writeFloats(out, row);
                    }
                }
            }
        }

        private static void writeFloats(Output out, float[] a) {
            out.writeVarInt(a.length, true);
            out.writeFloats(a, 0, a.length);
        }

        private void read(Input in) {
            int numEntries = readCount(in);
            for (int i = 0; i < numEntries; i++) {
                String entry = in.readString();
                byte type = in.readByte();
                switch (type) {
                    case TYPE_STRING:
                        add(entry, in.readString());
                        break;
                    case TYPE_FLOAT:
                        add(entry, (Float) in.readFloat());
                        break;
                    case TYPE_STRINGS: {
                        String[] a = new String[readCount(in)];
                        for (int k = 0; k < a.length; k++) {
                            a[k] = in.readString();
                        }
                        add(entry, a);
                        break;
                    }
                    case TYPE_INTS: {
                        int[] a = new int[readCount(in)];
                        for (int k = 0; k < a.length; k++) {
                            a[k] = in.readVarInt(false);
                        }
                        add(entry, a);
                        break;
                    }
                    case TYPE_SHORTS:
                        add(entry, in.readShorts(readCount(in)));
                        break;
                    case TYPE_FLOATS:
                        add(entry, in.readFloats(readCount(in)));
                        break;
                    case TYPE_FLOAT_ARRAYS: {
                        float[][] a = new float[readCount(in)][];
                        for (int k = 0; k < a.length; k++) {
                            a[k] = in.readFloats(readCount(in));
                        }
                        add(entry, a);
                        break;
                    }
                    default:
                        throw new KryoException("Entry " + entry + " of " + this + " has the unknown type " + type);
                }
            }
        }

        @Override
        public String toString() {
            return "section " + name + " of timing data " + fileName;
        }
    }

    private static int readCount(Input in) {
        int n = in.readVarInt(true);
        if (n < 0) {
            throw new KryoException("Negative count " + n);
        }
        return n;
    }

    /**
     * Writes this data to a file.
     * @param file The file.
     */
    public void write(Path file) {
        try (OutputStream os = Files.newOutputStream(file)) {
            write(os);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes this data to a stream, in the format of a timing data file.
     * @param os The stream, which is closed.
     */
    public void write(OutputStream os) {
        // The data is encoded in memory and compressed at once, so that the bytes of the file depend on the data
        // only, not on how a stream happens to be flushed
        Output out = new Output(1 << 16, -1);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeString(name);
        out.writeVarInt(sections.size(), true);
        for (Section s : sections.values()) {
            s.write(out);
        }
        try (OutputStream zstd = new ZstdOutputStream(os, COMPRESSION_LEVEL)) {
            zstd.write(out.getBuffer(), 0, out.position());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a timing data file.
     * @param file The file.
     * @return The data.
     * @throws IllegalArgumentException If the file is not a timing data file, or one of a later format version.
     * @throws IllegalStateException If the file is truncated or corrupt.
     */
    public static TimingDataFile read(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            return read(is, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a timing data file from a stream.
     * @param is The stream, which is closed.
     * @param source What the stream is, for messages (e.g. the name of the file).
     * @return The data.
     * @throws IllegalArgumentException If the stream is not a timing data file, or one of a later format
     * version.
     * @throws IllegalStateException If the data is truncated or corrupt.
     */
    public static TimingDataFile read(InputStream is, String source) {
        try (Input in = new Input(new ZstdInputStream(is))) {
            int magic;
            try {
                magic = in.readInt();
            } catch (KryoException e) {
                throw new IllegalArgumentException(source + " is not a timing data file", e);
            }
            if (magic != MAGIC) {
                throw new IllegalArgumentException(source + " is not a timing data file");
            }
            int version = in.readInt();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IllegalArgumentException("Timing data file " + source + " has format version " + version
                        + ", but this version of RapidWright reads versions up to " + FORMAT_VERSION);
            }
            TimingDataFile file = new TimingDataFile(in.readString());
            int numSections = readCount(in);
            for (int i = 0; i < numSections; i++) {
                String name = in.readString();
                String dataVersion = in.readString();
                String provenance = in.readString();
                long checksum = in.readLong();
                Section s = file.addSection(name, dataVersion, provenance);
                s.read(in);
                if (s.getChecksum() != checksum) {
                    throw new IllegalStateException("Timing data file " + source + " is corrupt: the checksum of its "
                            + "section " + name + " is " + Long.toHexString(s.getChecksum()) + ", not "
                            + Long.toHexString(checksum));
                }
            }
            if (!in.end()) {
                throw new KryoException("Data after the last section");
            }
            return file;
        } catch (KryoException e) {
            throw new IllegalStateException("Timing data file " + source + " is truncated or corrupt", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------

    /**
     * Prints this data as text, for debugging and for comparing two versions of the data with a text diff.
     * <p>
     * The text has a part per section, which starts with a line {@code [<section>]} that has the version and
     * the checksum of its data:
     * <ul>
     * <li>{@link TimingData#INTERCONNECT}: the lookahead and pre-route delays, the column and row classes with
     * their tile type patterns, and for each corner the terms of each node class ({@code class}, with its delay
     * per crossing of a column or row class), the fanout delay per crossing, the delays between node classes
     * ({@code transition}), the speed grade and device factors and the pin and track deltas, a line each;</li>
     * <li>{@link TimingData#CELL_ARCS}: a line per speed grade factor, device factor, arc, per-bit delay of an
     * arc and configuration delay, see {@link FittedCellArcLibrary#writeText(PrintWriter)};</li>
     * <li>{@link TimingData#CLOCK}: the clock data that {@link ClockData} describes, as indented JSON;</li>
     * <li>other sections: their entries, a line each.</li>
     * </ul>
     * Delays are in ps at the reference speed grade of the section. The text is for people to read: it is not
     * read back.
     * @param out Where to print.
     */
    public void writeText(PrintWriter out) {
        out.println("# RapidWright timing data " + name);
        for (Section s : sections.values()) {
            out.println();
            out.println("[" + s.getName() + "] " + s.getDataVersion() + ", checksum "
                    + Long.toHexString(s.getChecksum()));
            out.println("# " + s.getProvenance());
            switch (s.getName()) {
                case TimingData.INTERCONNECT:
                    writeInterconnect(s, out);
                    break;
                case TimingData.CELL_ARCS:
                    FittedCellArcLibrary.decode(s.getDataVersion(), s.getChecksum(), s.getString("speedRef"),
                            s.getStrings("strings"), s.getInts("ints")).writeText(out);
                    break;
                case TimingData.CLOCK:
                    writeTree(ClockData.decode(s.getStrings("strings"), s.getInts("ints")), "", out);
                    out.println();
                    break;
                default:
                    writeEntries(s, out);
            }
        }
        out.flush();
    }

    /**
     * Writes this data as text to a file, see {@link #writeText(PrintWriter)}.
     * @param textFile The text file.
     */
    public void writeText(Path textFile) {
        // The writer is closed here, not by the PrintWriter, which would ignore an error
        try (BufferedWriter writer = Files.newBufferedWriter(textFile, StandardCharsets.UTF_8)) {
            PrintWriter out = new PrintWriter(writer);
            writeText(out);
            if (out.checkError()) {
                throw new IOException("Could not write " + textFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String number(float v) {
        return v == (int) v ? Integer.toString((int) v) : Float.toString(v);
    }

    private static void writeInterconnect(Section s, PrintWriter out) {
        out.println("# delays in ps at speed grade " + s.getString("speedRef"));
        float[] lookahead = s.getFloats("lookahead");
        out.println("lookahead perColumn=" + number(lookahead[0]) + " perRow=" + number(lookahead[1])
                + " admissiblePerColumn=" + number(lookahead[2]) + " admissiblePerRow=" + number(lookahead[3]));
        out.println("preRouteDelay " + number(s.getFloat("preRouteDelay")));
        String[] colNames = s.getStrings("colClassNames");
        String[] rowNames = s.getStrings("rowClassNames");
        writePairs(out, "columnClass", colNames, s.getStrings("colClassRegexes"));
        writePairs(out, "rowClass", rowNames, s.getStrings("rowClassRegexes"));
        if (s.has("trackClass")) {
            out.println("trackClasses " + s.getString("trackClass"));
        } else if (s.has("trackClasses")) {
            out.println("trackClasses " + String.join(" ", s.getStrings("trackClasses")));
        }
        String[] classes = s.getStrings("nodeClasses");
        for (Corner corner : Corner.values()) {
            String c = corner + ".";
            if (!s.has(c + "base")) {
                continue;
            }
            out.println("corner " + corner);
            float[] base = s.getFloats(c + "base");
            float[] length = s.getFloats(c + "length");
            float[] fanout = s.getFloats(c + "fanout");
            float[] fanoutLoad = s.has(c + "fanoutLoad") ? s.getFloats(c + "fanoutLoad") : null;
            float[][] crossing = s.getFloatArrays(c + "crossing");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < classes.length; i++) {
                sb.setLength(0);
                sb.append("  class ").append(classes[i]).append(" base=").append(number(base[i]))
                        .append(" length=").append(number(length[i])).append(" fanout=").append(number(fanout[i]));
                if (fanoutLoad != null) {
                    sb.append(" fanoutLoad=").append(number(fanoutLoad[i]));
                }
                appendCrossings(sb, " crossing", crossing[i], colNames, rowNames);
                out.println(sb);
            }
            if (s.has(c + "fanoutCrossing")) {
                sb.setLength(0);
                appendCrossings(sb, "  fanoutCrossing", s.getFloats(c + "fanoutCrossing"), colNames, rowNames);
                out.println(sb);
            }
            int[] from = s.getInts(c + "transitionFrom");
            int[] to = s.getInts(c + "transitionTo");
            float[] delay = s.getFloats(c + "transitionDelay");
            for (int i = 0; i < from.length; i++) {
                out.println("  transition " + classes[from[i]] + " " + classes[to[i]] + " " + number(delay[i]));
            }
            writePairs(out, "  speedGrade", s.getStrings(c + "speedGrades"), s.getFloats(c + "speedScales"));
            if (s.has(c + "fanoutSpeedGrades")) {
                writePairs(out, "  fanoutSpeedGrade", s.getStrings(c + "fanoutSpeedGrades"),
                        s.getFloats(c + "fanoutSpeedScales"));
            }
            writePairs(out, "  device", s.getStrings(c + "devices"), s.getFloats(c + "deviceScales"));
            if (s.has(c + "sinkPinDeltas")) {
                writeDeltas(out, "  sinkPinDelta", s.getStrings("sinkPinKeys"), s.getShorts(c + "sinkPinDeltas"));
                writeDeltas(out, "  sourcePinDelta", s.getStrings("sourcePinKeys"),
                        s.getShorts(c + "sourcePinDeltas"));
            }
            if (s.has(c + "trackDeltas")) {
                writeDeltas(out, "  trackDelta", s.getStrings("trackKeys"), s.getShorts(c + "trackDeltas"));
            }
        }
    }

    /** Appends a value per column and row class */
    private static void appendCrossings(StringBuilder sb, String kind, float[] values, String[] colNames,
                                        String[] rowNames) {
        for (int k = 0; k < values.length; k++) {
            sb.append(k == 0 ? kind + " " : " ")
                    .append(k < colNames.length ? colNames[k] : rowNames[k - colNames.length]).append('=')
                    .append(number(values[k]));
        }
    }

    private static void writePairs(PrintWriter out, String kind, String[] names, String[] values) {
        for (int i = 0; i < names.length; i++) {
            out.println(kind + " " + names[i] + " " + values[i]);
        }
    }

    private static void writePairs(PrintWriter out, String kind, String[] names, float[] values) {
        for (int i = 0; i < names.length; i++) {
            out.println(kind + " " + names[i] + " " + number(values[i]));
        }
    }

    /** Prints deltas, which the data has in tenths of a ps, in ps */
    private static void writeDeltas(PrintWriter out, String kind, String[] keys, short[] tenths) {
        for (int i = 0; i < keys.length; i++) {
            out.println(kind + " " + keys[i] + " " + number(tenths[i] / 10f));
        }
    }

    /** Prints the entries of a section, a line each */
    private static void writeEntries(Section s, PrintWriter out) {
        for (String entry : s.getEntryNames()) {
            Object v = s.get(entry);
            StringBuilder sb = new StringBuilder(entry).append(" =");
            if (v instanceof String[]) {
                for (String e : (String[]) v) {
                    sb.append(' ').append(e);
                }
            } else if (v instanceof int[]) {
                for (int e : (int[]) v) {
                    sb.append(' ').append(e);
                }
            } else if (v instanceof short[]) {
                for (short e : (short[]) v) {
                    sb.append(' ').append(e);
                }
            } else if (v instanceof float[]) {
                appendFloats(sb, (float[]) v);
            } else if (v instanceof float[][]) {
                for (float[] row : (float[][]) v) {
                    sb.append(" [");
                    appendFloats(sb, row);
                    sb.append(" ]");
                }
            } else if (v instanceof Float) {
                sb.append(' ').append(number((Float) v));
            } else {
                sb.append(' ').append(v);
            }
            out.println(sb);
        }
    }

    private static void appendFloats(StringBuilder sb, float[] values) {
        for (float e : values) {
            sb.append(' ').append(number(e));
        }
    }

    /** Prints a tree of maps, lists, strings, numbers and booleans as JSON; lists of scalars on one line */
    private static void writeTree(Object v, String indent, PrintWriter out) {
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                out.print("{}");
                return;
            }
            out.println("{");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.print(indent + "  " + quote(String.valueOf(e.getKey())) + ": ");
                writeTree(e.getValue(), indent + "  ", out);
                out.println(++i < m.size() ? "," : "");
            }
            out.print(indent + "}");
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            boolean nested = false;
            for (Object e : l) {
                nested |= e instanceof Map || e instanceof List;
            }
            out.print("[");
            for (int i = 0; i < l.size(); i++) {
                if (nested) {
                    out.println(i > 0 ? "," : "");
                    out.print(indent + "  ");
                } else if (i > 0) {
                    out.print(", ");
                }
                writeTree(l.get(i), indent + "  ", out);
            }
            if (nested) {
                out.println();
                out.print(indent);
            }
            out.print("]");
        } else if (v instanceof Number) {
            out.print(number(((Number) v).floatValue()));
        } else if (v instanceof String) {
            out.print(quote((String) v));
        } else {
            out.print(v);
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch < ' ') {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }
}
