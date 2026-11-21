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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.util.FileTools;

/**
 * The fitted delay data of a timing data set: the devices of a series that share one fit, e.g. all UltraScale+
 * devices (ultrascaleplus), or the Versal devices of one architecture generation (versal_gen1, versal_gen2).
 * The speed grades and the devices of a data set differ by factors that are part of its data.
 * <p>
 * The data of a set is one file, {@code data/timing/<series>/<name>_timing.dat} (see {@link TimingDataFile}
 * for its format), which is a RapidWright data file like the device files: it is downloaded when a data set
 * is first used, and checked against the version that this release of RapidWright expects (see
 * {@link FileTools#getRapidWrightResourceInputStream(String)}). A timing family names its data sets and the
 * data set of a part ({@link TimingFamily#getTimingDataNames()}, {@link TimingFamily#getTimingDataName(Part)}).
 * <p>
 * A file has up to three sections:
 * <ul>
 * <li>{@link #INTERCONNECT}: the parameters of the class-based interconnect delay model at each corner, see
 * {@link #getInterconnectParams(Corner)};</li>
 * <li>{@link #CELL_ARCS}: the timing arcs of the cells and the configuration delays, see
 * {@link #getFittedCellArcs()};</li>
 * <li>{@link #CLOCK}: the clock network delays, which
 * {@link com.xilinx.rapidwright.timing.clock.ClockData} reads.</li>
 * </ul>
 * The data of a set is read once and shared. To look at the data of a set, print its file as text:
 * <pre>
 *   TimingData.readFile("versal_gen1").writeText(Paths.get("versal_gen1_timing.txt"));
 * </pre>
 */
public final class TimingData {

    /** The name of the section of the interconnect delay parameters */
    public static final String INTERCONNECT = "interconnect";
    /** The name of the section of the cell timing arcs and configuration delays */
    public static final String CELL_ARCS = "cellarcs";
    /** The name of the section of the clock network delays */
    public static final String CLOCK = "clock";

    /** The folder of the timing data files, relative to the RapidWright path */
    public static final String FOLDER_NAME = FileTools.DATA_FOLDER_NAME + File.separator + "timing";
    /** The suffix of the name of a timing data file, after the name of its data set */
    public static final String FILE_SUFFIX = "_timing.dat";

    private static final Map<String, TimingData> CACHE = new ConcurrentHashMap<>();

    private final String name;
    /** The data version, provenance and checksum of each section */
    private final Map<String, String[]> sectionInfo = new LinkedHashMap<>();
    private final Map<String, Long> checksums = new LinkedHashMap<>();
    private final TimingDataFile.Section interconnect;
    private final TimingDataFile.Section clock;
    /** The cell arc section, until its library is built */
    private TimingDataFile.Section cellArcs;
    private final Map<Corner, InterconnectParams> params = new EnumMap<>(Corner.class);
    private FittedCellArcLibrary library;

    /**
     * Creates the data of a set from the contents of its file.
     * @param file The contents.
     */
    public TimingData(TimingDataFile file) {
        name = file.getName();
        for (TimingDataFile.Section s : file.getSections()) {
            sectionInfo.put(s.getName(), new String[]{s.getDataVersion(), s.getProvenance()});
            checksums.put(s.getName(), s.getChecksum());
        }
        interconnect = file.getSection(INTERCONNECT);
        cellArcs = file.getSection(CELL_ARCS);
        clock = file.getSection(CLOCK);
    }

    // ------------------------------------------------------------------------------------------
    // Data sets and their files
    // ------------------------------------------------------------------------------------------

    private static TimingFamily getFamily(String name) {
        for (Series series : TimingFamilies.getSupportedSeries()) {
            TimingFamily family = TimingFamilies.of(series);
            if (family.getTimingDataNames().contains(name)) {
                return family;
            }
        }
        throw new IllegalArgumentException("No timing data set " + name + "; the timing data sets are "
                + getNames());
    }

    /**
     * @return The names of the timing data sets of all timing families.
     */
    public static List<String> getNames() {
        List<String> names = new ArrayList<>();
        for (Series series : TimingFamilies.getSupportedSeries()) {
            names.addAll(TimingFamilies.of(series).getTimingDataNames());
        }
        return names;
    }

    /**
     * Gets the name of the data set of a part.
     * @param part The part.
     * @return The name, or null if the part's series has no timing data.
     */
    public static String getName(Part part) {
        if (!TimingFamilies.isSupported(part.getSeries())) {
            return null;
        }
        return TimingFamilies.of(part.getSeries()).getTimingDataName(part);
    }

    /**
     * Gets the file of a data set.
     * @param name The name of the data set, e.g. versal_gen1.
     * @return The name of its file relative to the RapidWright path, e.g.
     * data/timing/versal/versal_gen1_timing.dat.
     * @throws IllegalArgumentException If no timing family has a data set of that name.
     */
    public static String getFileName(String name) {
        return FOLDER_NAME + File.separator + getFamily(name).getSeries().name().toLowerCase() + File.separator
                + name + FILE_SUFFIX;
    }

    /**
     * @return The names of the files of all timing data sets, relative to the RapidWright path.
     */
    public static List<String> getFileNames() {
        List<String> files = new ArrayList<>();
        for (String name : getNames()) {
            files.add(getFileName(name));
        }
        return files;
    }

    /**
     * Gets the data of a data set, reading its file (see {@link #getFileName(String)}) on first use; the file
     * is downloaded if it is missing or not the version that this release of RapidWright expects.
     * @param name The name of the data set, e.g. versal_gen1.
     * @return The data, shared by all callers.
     * @throws IllegalArgumentException If no timing family has a data set of that name.
     */
    public static TimingData get(String name) {
        TimingData data = CACHE.get(name);
        if (data == null) {
            data = CACHE.computeIfAbsent(name, n -> new TimingData(readFile(n)));
        }
        return data;
    }

    /**
     * Reads the file of a data set (see {@link #getFileName(String)}); the file is downloaded if it is missing
     * or not the version that this release of RapidWright expects. This is for looking at the contents of the
     * file, e.g. as text ({@link TimingDataFile#writeText(java.nio.file.Path)}): {@link #get(String)} gives
     * the data, and reads the file only once.
     * @param name The name of the data set, e.g. versal_gen1.
     * @return The contents of the file.
     * @throws IllegalArgumentException If no timing family has a data set of that name.
     */
    public static TimingDataFile readFile(String name) {
        String fileName = getFileName(name);
        TimingDataFile file = TimingDataFile.read(FileTools.getRapidWrightResourceInputStream(fileName),
                FileTools.getRapidWrightResourceFileName(fileName));
        if (!file.getName().equals(name)) {
            throw new IllegalStateException("Timing data file " + fileName + " holds the data set "
                    + file.getName() + ", not " + name);
        }
        return file;
    }

    /**
     * Gets the data of the data set of a part.
     * @param part The part.
     * @return The data, or null if the part's series has no timing data.
     */
    public static TimingData get(Part part) {
        String name = getName(part);
        return name == null ? null : get(name);
    }

    /**
     * Gets the data of the data set of a device.
     * @param device The device.
     * @return The data, or null if the device's series has no timing data.
     */
    public static TimingData get(Device device) {
        return get(PartNameTools.getPart(device.getName()));
    }

    // ------------------------------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------------------------------

    /**
     * @return The name of the data set.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The names of the sections of the data, in the order of the file.
     */
    public List<String> getSectionNames() {
        return new ArrayList<>(sectionInfo.keySet());
    }

    private String[] getInfo(String section) {
        String[] info = sectionInfo.get(section);
        if (info == null) {
            throw new IllegalArgumentException("Timing data " + name + " has no section " + section);
        }
        return info;
    }

    /**
     * Gets the version of the data of a section.
     * @param section The name of the section, e.g. {@link #INTERCONNECT}.
     * @return The family, kind and version of its data.
     * @throws IllegalArgumentException If the data has no such section.
     */
    public String getDataVersion(String section) {
        return getInfo(section)[0];
    }

    /**
     * Gets the provenance of the data of a section.
     * @param section The name of the section.
     * @return Where its data comes from.
     * @throws IllegalArgumentException If the data has no such section.
     */
    public String getProvenance(String section) {
        return getInfo(section)[1];
    }

    /**
     * Gets the checksum of the data of a section (see {@link TimingDataFile.Section#getChecksum()}), which
     * changes only with the data.
     * @param section The name of the section.
     * @return The checksum.
     * @throws IllegalArgumentException If the data has no such section.
     */
    public long getChecksum(String section) {
        getInfo(section);
        return checksums.get(section);
    }

    /**
     * Gets the interconnect delay parameters of a corner, from the section {@link #INTERCONNECT}. Its entries
     * are the reference speed grade of the delays ({@code speedRef}), the node classes ({@code nodeClasses}),
     * the classes of the tile types between INT columns and rows with their tile type patterns
     * ({@code colClassNames}, {@code colClassRegexes}, {@code rowClassNames}, {@code rowClassRegexes}), the keys
     * of the pin and track deltas, the corners ({@code corner.<n>}) with their terms per node class, their
     * transitions, speed grade and device factors and deltas ({@code <corner>.base} etc., see
     * {@link InterconnectParams.Builder}), and the lookahead and pre-route delays.
     * @param corner The corner.
     * @return The parameters, shared by all callers.
     * @throws UnsupportedOperationException If the data has no interconnect delays of that corner.
     */
    public synchronized InterconnectParams getInterconnectParams(Corner corner) {
        InterconnectParams p = params.get(corner);
        if (p == null) {
            if (interconnect == null || !interconnect.has(corner + ".base")) {
                throw new UnsupportedOperationException("No " + corner + " interconnect delays in "
                        + (interconnect == null ? "timing data " + name : interconnect.getDataVersion()));
            }
            p = buildInterconnectParams(interconnect, corner);
            params.put(corner, p);
        }
        return p;
    }

    private static InterconnectParams buildInterconnectParams(TimingDataFile.Section s, Corner corner) {
        String c = corner + ".";
        float[] lookahead = s.getFloats("lookahead");
        InterconnectParams.Builder b = new InterconnectParams.Builder(s.getDataVersion(), corner,
                s.getString("speedRef"), s.getStrings("nodeClasses"))
                .setColClasses(s.getStrings("colClassNames"), s.getStrings("colClassRegexes"))
                .setRowClasses(s.getStrings("rowClassNames"), s.getStrings("rowClassRegexes"))
                .setClassDelays(s.getFloats(c + "base"), s.getFloats(c + "length"), s.getFloats(c + "fanout"))
                .setCrossingWeights(s.getFloatArrays(c + "crossing"))
                .setTransitions(s.getInts(c + "transitionFrom"), s.getInts(c + "transitionTo"),
                        s.getFloats(c + "transitionDelay"))
                .setLookahead(lookahead[0], lookahead[1], lookahead[2], lookahead[3])
                .setPreRouteDelay(s.getFloat("preRouteDelay"))
                .setSpeedScales(s.getStrings(c + "speedGrades"), s.getFloats(c + "speedScales"))
                .setDeviceScales(s.getStrings(c + "devices"), s.getFloats(c + "deviceScales"));
        if (s.has(c + "fanoutLoad")) {
            b.setFanoutLoadDelays(s.getFloats(c + "fanoutLoad"));
        }
        if (s.has(c + "fanoutCrossing")) {
            b.setFanoutCrossingDelays(s.getFloats(c + "fanoutCrossing"));
        }
        if (s.has(c + "sinkPinDeltas")) {
            b.setPinDeltas(s.getStrings("sinkPinKeys"), s.getShorts(c + "sinkPinDeltas"),
                    s.getStrings("sourcePinKeys"), s.getShorts(c + "sourcePinDeltas"));
        }
        if (s.has(c + "trackDeltas")) {
            if (s.has("trackClass")) {
                b.setTrackDeltas(s.getString("trackClass"), s.getStrings("trackKeys"), s.getShorts(c + "trackDeltas"));
            } else {
                b.setTrackDeltas(s.getStrings("trackClasses"), s.getStrings("trackKeys"),
                        s.getShorts(c + "trackDeltas"));
            }
        }
        if (s.has(c + "fanoutSpeedGrades")) {
            b.setFanoutSpeedScales(s.getStrings(c + "fanoutSpeedGrades"), s.getFloats(c + "fanoutSpeedScales"));
        }
        return b.build();
    }

    /**
     * Gets the fitted timing arcs of the cells, from the section {@link #CELL_ARCS}. Its entries are the
     * reference speed grade of the delays ({@code speedRef}) and the strings and integers that
     * {@link FittedCellArcLibrary#decode} reads.
     * @return The library, shared by all callers.
     * @throws UnsupportedOperationException If the data has no cell arcs.
     */
    public synchronized FittedCellArcLibrary getFittedCellArcs() {
        if (library == null) {
            if (cellArcs == null) {
                throw new UnsupportedOperationException("No fitted cell arcs in timing data " + name);
            }
            library = FittedCellArcLibrary.decode(cellArcs.getDataVersion(), checksums.get(CELL_ARCS),
                    cellArcs.getString("speedRef"), cellArcs.getStrings("strings"), cellArcs.getInts("ints"));
            // The library holds the data from here on
            cellArcs = null;
        }
        return library;
    }

    /**
     * Gets the section {@link #CLOCK} of the data, whose entries are the strings and integers that
     * {@link com.xilinx.rapidwright.timing.clock.ClockData#decode} reads.
     * @return The section, or null if the data has no clock network delays.
     */
    public TimingDataFile.Section getClockSection() {
        return clock;
    }
}
