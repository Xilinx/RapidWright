/*
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

package com.xilinx.rapidwright.lec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;

/**
 * Loads a logical netlist from a design checkpoint or an EDIF file.
 *
 * Only the logical netlist is read, never the device or the physical netlist.
 * For a checkpoint that means pulling the EDIF straight out of the archive with
 * {@link EDIFTools#readEdifFromZipFile(String)} rather than going through
 * {@code Design.readCheckpoint}, which would additionally load a device
 * database and the placement and routing data. On a large part that difference
 * is several gigabytes, and none of it is needed to reason about logic.
 *
 * <h2>Why macros are not expanded by default</h2>
 *
 * {@code EDIFNetlist.expandMacroUnisims} installs a single expanded cell per
 * macro type and shares it across every instance of that type, without
 * propagating per instance parameters. After expansion, every {@code LUT6CY}
 * instance's children carry the macro template's INIT rather than the
 * instance's own, so a checker that descended into them would read the wrong
 * function. Macro types are therefore left intact and modelled natively.
 */
public class NetlistLoader {

    /** Signature Vivado writes at the start of an encrypted EDIF file. */
    private static final byte[] ENCRYPTED_EDIF_MAGIC = { 'X', 'l', 'x', 'V' };

    /**
     * Embedded EDIF size above which it is worth extracting the archive entry
     * so that the parallel parser can be used.
     */
    private static final long PARALLEL_PARSE_THRESHOLD_BYTES = 32L << 20;

    /** How many malformed connections to retain when explaining a parse failure. */
    private static final int MAX_REPORTED_NETLIST_DEFECTS = 500;

    private NetlistLoader() {
    }

    /**
     * Reads a netlist, choosing the reader by file extension.
     *
     * @param file   A {@code .dcp} or {@code .edf} file.
     * @param config Controls whether macros are expanded.
     * @param log    Receives progress messages.
     */
    public static EDIFNetlist load(Path file, LecConfig config, LecLog log) {
        if (file == null) {
            throw new LecException("No netlist file given");
        }
        if (!Files.exists(file)) {
            throw new LecException("Netlist file does not exist: " + file);
        }
        String name = file.getFileName().toString().toLowerCase();
        EDIFNetlist netlist;
        try {
            if (name.endsWith(".dcp")) {
                Path plainEdif = resolveEncryptedCheckpoint(file, log);
                netlist = plainEdif != null ? EDIFTools.readEdifFile(plainEdif)
                        : readCheckpoint(file, log);
            } else if (name.endsWith(".edf") || name.endsWith(".edif") || name.endsWith(".edn")) {
                log.info("Reading EDIF " + file);
                netlist = EDIFTools.readEdifFile(file);
            } else {
                throw new LecException("Unrecognised netlist extension on '" + file
                        + "', expected .dcp, .edf or .edif");
            }
        } catch (LecException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new LecException("Failed to read netlist '" + file + "': " + e.getMessage(), e);
        }
        if (netlist == null) {
            throw new LecException("No logical netlist found in '" + file + "'");
        }
        if (config.isExpandMacros()) {
            expandMacros(netlist, file, log);
        }
        return netlist;
    }

    /**
     * Reads the logical netlist out of a checkpoint.
     *
     * Reading the archive entry as a stream forces RapidWright's single
     * threaded parser, because the parallel one has to seek in order to split
     * the file between workers. On the designs this tool is aimed at the
     * embedded EDIF runs to hundreds of megabytes, where that difference
     * dominates the whole run, so a large entry is extracted to a temporary
     * file first and parsed in parallel. Small ones are streamed directly,
     * since the copy would cost more than it saves.
     */
    private static EDIFNetlist readCheckpoint(Path file, LecLog log) throws IOException {
        long size = edifEntrySize(file);
        if (size < PARALLEL_PARSE_THRESHOLD_BYTES) {
            log.info("Reading logical netlist from checkpoint " + file);
            return EDIFTools.readEdifFromZipFile(file.toString());
        }
        log.info("Reading logical netlist from checkpoint " + file + " (" + (size >> 20)
                + " MB of EDIF, extracting to parse in parallel)");
        Path temp = Files.createTempFile("rapidwright_lec_", ".edf");
        try {
            extractEdif(file, temp);
            try {
                return EDIFTools.loadEDIFFile(temp);
            } catch (RuntimeException e) {
                throw explainParseFailure(file, temp, e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Turns a parse failure into an explanation of what is wrong with the
     * netlist.
     *
     * A reader stops at the first malformed connection, which says nothing about
     * how much else is wrong. Since the extracted EDIF is still on disk at this
     * point, it is worth scanning the whole of it so the entire defect can be
     * reported at once rather than one instance per attempted load.
     */
    private static LecException explainParseFailure(Path source, Path extracted,
            RuntimeException cause) {
        List<EdifPortDiagnostics.Finding> findings =
                EdifPortDiagnostics.scan(extracted, MAX_REPORTED_NETLIST_DEFECTS);
        if (findings.isEmpty()) {
            return new LecException("Failed to read netlist '" + source + "': "
                    + rootMessage(cause), cause);
        }
        return new LecException("Failed to read netlist '" + source + "': " + rootMessage(cause)
                + System.lineSeparator() + EdifPortDiagnostics.describe(source, findings), cause);
    }

    /** @return The innermost message of a chained exception. */
    private static String rootMessage(Throwable cause) {
        Throwable current = cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    /**
     * @return The uncompressed size of the checkpoint's EDIF entry, or -1 if
     *         there is none.
     */
    private static long edifEntrySize(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = findEdifEntry(zip);
            return entry == null ? -1 : entry.getSize();
        }
    }

    /** Copies the checkpoint's EDIF entry out to a file. */
    private static void extractEdif(Path file, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = findEdifEntry(zip);
            if (entry == null) {
                throw new LecException("No EDIF entry found inside '" + file + "'");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static ZipEntry findEdifEntry(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().toLowerCase().endsWith(".edf")) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Handles a checkpoint whose embedded EDIF is encrypted.
     *
     * Vivado encrypts the logical netlist of checkpoints containing protected
     * IP, marking the file with an {@code XlxV} signature that no EDIF parser
     * can read. When that happens, look for a plain EDIF alongside the
     * checkpoint under RapidWright's usual {@code <checkpoint>.edf} naming, and
     * otherwise fail with an explanation rather than a parser stack trace.
     *
     * @return The plain EDIF to read instead, or null if the checkpoint's own
     *         EDIF is readable.
     */
    private static Path resolveEncryptedCheckpoint(Path file, LecLog log) {
        if (!isEncryptedEdif(file)) {
            return null;
        }
        Path sibling = file.resolveSibling(file.getFileName().toString() + ".edf");
        Path plain = findPlainEdif(sibling);
        if (plain != null) {
            log.info("Logical netlist inside " + file + " is encrypted; reading " + plain
                    + " instead");
            return plain;
        }
        throw new LecException("The logical netlist inside '" + file + "' is encrypted and "
                + "cannot be read. Supply an unencrypted EDIF instead, either as '"
                + sibling.getFileName() + "' beside the checkpoint or directly as the netlist "
                + "argument. One can be produced in Vivado with 'write_edif'.");
    }

    /**
     * Resolves RapidWright's {@code <checkpoint>.edf} companion, which is either
     * the EDIF file itself or a directory holding it alongside the {@code .edn}
     * files of any encrypted IP cells. Those encrypted cells stay unreadable and
     * become black boxes, which the classification report will show.
     *
     * @return The EDIF to read, or null if none was found.
     */
    private static Path findPlainEdif(Path companion) {
        if (!Files.exists(companion)) {
            return null;
        }
        if (!Files.isDirectory(companion)) {
            return companion;
        }
        try (Stream<Path> entries = Files.list(companion)) {
            return entries.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".edf"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @return True if the checkpoint's embedded EDIF carries Vivado's encrypted
     *         file signature. Matches the check in
     *         {@code FileTools.isFileBinary}.
     */
    private static boolean isEncryptedEdif(Path file) {
        Pair<InputStream, Long> entry;
        try {
            entry = FileTools.getInputStreamFromZipFile(file.toString(), ".edf");
        } catch (RuntimeException e) {
            return false;
        }
        try (InputStream is = entry.getFirst()) {
            byte[] magic = new byte[ENCRYPTED_EDIF_MAGIC.length];
            int read = is.read(magic);
            if (read < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (magic[i] != ENCRYPTED_EDIF_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Expands macro primitives when explicitly requested. Warns loudly, because
     * expansion loses per instance parameters on every macro whose function
     * depends on one.
     */
    private static void expandMacros(EDIFNetlist netlist, Path file, LecLog log) {
        Series series = getSeries(netlist, file);
        if (series == null) {
            throw new LecException("Cannot expand macros for '" + file
                    + "': the netlist does not name a part");
        }
        log.getPrintStream().println("WARNING: --expand-macros is enabled. RapidWright's macro "
                + "expansion does not propagate per instance parameters, so any macro whose "
                + "function depends on one (LUT6CY INIT, distributed RAM INIT) will be modelled "
                + "with the macro template's values instead.");
        netlist.expandMacroUnisims(series);
    }

    /**
     * @return The series the netlist targets, or null if the netlist names no
     *         part or the part is unrecognised.
     */
    public static Series getSeries(EDIFNetlist netlist, Path file) {
        String partName = EDIFTools.getPartName(netlist);
        if (partName == null) {
            return null;
        }
        try {
            return PartNameTools.getPart(partName).getSeries();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return The part name recorded in the netlist, or null if absent. */
    public static String getPartName(EDIFNetlist netlist) {
        return EDIFTools.getPartName(netlist);
    }

    /**
     * Checks that the two netlists target the same series. A mismatch means the
     * primitive vocabularies differ, so the one to one terminal correspondence
     * between the two netlists cannot hold.
     */
    public static void checkSeriesMatch(Series refSeries, Series impSeries, LecConfig config,
            LecLog log) {
        if (refSeries == impSeries) {
            return;
        }
        String message = "Reference targets " + refSeries + " but implementation targets "
                + impSeries + ". The primitive vocabularies differ, so terminals cannot "
                + "correspond one to one.";
        if (config.isAllowSeriesMismatch()) {
            log.getPrintStream().println("WARNING: " + message);
        } else {
            throw new LecException(message + " Use --allow-series-mismatch to proceed anyway.");
        }
    }
}
