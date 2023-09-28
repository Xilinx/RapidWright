/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;
import org.capnproto.ReaderOptions;
import org.capnproto.Serialize;
import org.capnproto.SerializePacked;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;

public class Interchange {

    /** Flag indicating use of Packed Cap'n Proto Serialization */
    public static boolean IS_PACKED = false;
    /** Flag indicating that files are gzipped on output */
    public static boolean IS_GZIPPED = true;
    /** Standard file extension for a logical netlist in the FPGA Interchange Format */
    public static final String LOG_NETLIST_EXT = ".netlist";
    /** Standard file extension for a physical netlist in the FPGA Interchange Format */
    public static final String PHYS_NETLIST_EXT = ".phys";

    /**
     * Reads an FPGA Interchange Format design from a specified file. The file could
     * be a logical netlist or physical netlist and this method will assume the
     * corresponding file with the same root name to load as a companion. If the
     * provided name is a logical netlist and no physical netlist is found, it will
     * only load the logical netlist. It will also load any XDC file with the same
     * root name.
     * 
     * @param fileName The name of the logical or physical netlist file.
     * @return The loaded design.
     */
    public static Design readInterchangeDesign(String fileName) {
        return readInterchangeDesign(Paths.get(fileName));
    }

    /**
     * Reads an FPGA Interchange Format design from a specified file. The file could
     * be a logical netlist or physical netlist and this method will assume the
     * corresponding file with the same root name to load as a companion. If the
     * provided name is a logical netlist and no physical netlist is found, it will
     * only load the logical netlist. It will also load any XDC file with the same
     * root name.
     * 
     * @param filePath The path to the logical or physical netlist file.
     * @return The loaded design.
     */
    public static Design readInterchangeDesign(Path filePath) {
        String lowerName = filePath.toString().toLowerCase();
        Path logFileName = lowerName.endsWith(LOG_NETLIST_EXT) ? filePath : getExistingCompanionFile(filePath);
        Path physFileName = lowerName.endsWith(PHYS_NETLIST_EXT) ? filePath : getExistingCompanionFile(filePath);

        if (logFileName == null) {
            throw new RuntimeException("ERROR: Could not find logical netlist file: " + logFileName);
        }

        String xdcFileName = FileTools.replaceExtension(logFileName, ".xdc").toString();

        return readInterchangeDesign(logFileName.toString(), physFileName.toString(), xdcFileName, false, null);
    }
    
    /**
     * Gets the existing Interchange companion file name based on the provided
     * filename. For example, if the logical netlist file name is provided, it will
     * return the physical netlist filename if it exists. If the physical netlist is
     * provided, it returns the logical netlist filename if the file exists.
     * 
     * @param filePath Path of an existing FPGA Interchange file (logical netlist
     *                 with the {@link #LOG_NETLIST_EXT} extension or physical
     *                 netlist with the {@link #PHYS_NETLIST_EXT})
     * @return The companion file if it exists or null if it could not be found.
     */
    private static Path getExistingCompanionFile(Path filePath) {
        String lowerFileName = filePath.toString().toLowerCase();
        if (lowerFileName.endsWith(LOG_NETLIST_EXT)) {
            Path physFileName = FileTools.replaceExtension(filePath, PHYS_NETLIST_EXT);
            if (Files.exists(physFileName)) {
                return physFileName;
            }
        } else if (lowerFileName.endsWith(PHYS_NETLIST_EXT)) {
            Path logFileName = FileTools.replaceExtension(filePath, LOG_NETLIST_EXT);
            if (Files.exists(logFileName)) {
                return logFileName;
            }
        }
        return null;
    }

    /**
     * Reads a set of existing FPGA Interchange files and returns a new design.
     * 
     * @param logFileName    The logical netlist file to be loaded.
     * @param physFileName   The physical netlist file to be loaded, this can be
     *                       null for no placement or routing information.
     * @param xdcFileName    The constraints to associate with the design, this can
     *                       be null for no constraints.
     * @param isOutOfContext A flag indicating if the design should be marked out of
     *                       context.
     * @param t              If using an existing CodePerfTracker, this allows
     *                       continuity otherwise the default is null (an instance
     *                       will be created each time) and will track runtime of
     *                       each loading step. To silence this measurement, provide
     *                       {@link CodePerfTracker#SILENT}).
     * @return The newly created design based on the provided files.
     */
    public static Design readInterchangeDesign(String logFileName, String physFileName, String xdcFileName,
            boolean isOutOfContext, CodePerfTracker t) {
        String msg = "Reading Interchange: " + logFileName;
        CodePerfTracker tt = t == null ? new CodePerfTracker(msg, true) : t;
        Design design = null;
        try {
            tt.start("Read Logical Netlist");
            EDIFNetlist n = LogNetlistReader.readLogNetlist(logFileName);
            if (physFileName != null) {
                tt.stop().start("Read Physical Netlist");
                design = PhysNetlistReader.readPhysNetlist(physFileName, n);
            } else {
                // No physical netlist information, let's attach the logical netlist to a new
                // design
                design = new Design(n);
            }
            if (xdcFileName != null) {
                File xdcFile = new File(xdcFileName);
                if (xdcFile.exists()) {
                    // Add XDC constraints
                    tt.stop().start("Read Constraints");
                    List<String> lines = Files.readAllLines(xdcFile.toPath(), Charset.defaultCharset());
                    design.setXDCConstraints(lines, ConstraintGroup.NORMAL);
                }
            }
            if (isOutOfContext) {
                design.setAutoIOBuffers(false);
                design.setDesignOutOfContext(true);
            }
            tt.stop().printSummary();
            return design;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes out a set of Interchange files for the given design. It will write up
     * to 3 files with the logical netlist always being written out. The two
     * optional files are the physical netlist and XDC (constraints) file.
     * 
     * @param design       The design in memory to write out.
     * @param rootFileName The root or common name among the output files.
     */
    public static void writeDesignToInterchange(Design design, String rootFileName) {
        String logFileName = rootFileName + LOG_NETLIST_EXT;
        try {
            LogNetlistWriter.writeLogNetlist(design.getNetlist(), logFileName);

            if (design.getSiteInsts().size() > 0 || design.getNets().size() > 0) {
                String physFileName = rootFileName + PHYS_NETLIST_EXT;
                PhysNetlistWriter.writePhysNetlist(design, physFileName);
            }

            if (!design.getXDCConstraints(ConstraintGroup.NORMAL).isEmpty()) {
                String xdcFileName = rootFileName + ".xdc";
                FileTools.writeLinesToTextFile(design.getXDCConstraints(ConstraintGroup.NORMAL), xdcFileName);
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks if the provided file name is a logical or physical FPGA Interchange
     * file.
     * 
     * @param fileName The file name in question.
     * @return True if the file name matches the logical or physical netlist file
     *         name type.
     */
    public static boolean isInterchangeFile(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        return lowerFileName.endsWith(LOG_NETLIST_EXT) || lowerFileName.endsWith(PHYS_NETLIST_EXT);
    }

    /**
     * Common method to write out Interchange files
     * @param fileName Name of the output file to write
     * @param message The message to write to the file
     * @throws IOException
     */
    public static void writeInterchangeFile(String fileName, MessageBuilder message) throws IOException {
        WritableByteChannel wbc = null;

        if (IS_GZIPPED) {
            GZIPOutputStream go = new GZIPOutputStream(new FileOutputStream(fileName));
            wbc = Channels.newChannel(go);

        } else {
            FileOutputStream fo = new java.io.FileOutputStream(fileName);
            wbc = fo.getChannel();
        }
        if (IS_PACKED) {
            SerializePacked.writeToUnbuffered(wbc, message);
        } else {
            Serialize.write(wbc, message);
        }

        wbc.close();
    }

    /**
     * Common method used to read Interchange files
     * @param fileName Name of the file to read
     * @param readOptions The reader options
     * @return The read message from the file
     * @throws IOException
     */
    public static MessageReader readInterchangeFile(String fileName, ReaderOptions readOptions) throws IOException {
        ReadableByteChannel channel = null;
        if (IS_GZIPPED) {
            GZIPInputStream gis = new GZIPInputStream(new FileInputStream(fileName));
            channel = Channels.newChannel(gis);
        } else {
            FileInputStream fis = new java.io.FileInputStream(fileName);
            channel = fis.getChannel();
        }
        MessageReader readMsg = null;
        if (IS_PACKED) {
            readMsg = SerializePacked.readFromUnbuffered(channel, readOptions);
        } else {
            readMsg = Serialize.read(channel, readOptions);
        }

        channel.close();
        return readMsg;
    }

    private static String READ_DCP = "READ_DCP";
    private static String WRITE_DCP = "WRITE_DCP";
    private static String WRITE_LOGICAL_NETLIST = "WRITE_LOGICAL_NETLIST";
    private static String WRITE_PHYSICAL_NETLIST = "WRITE_PHYSICAL_NETLIST";
    private static String READ_LOGICAL_NETLIST = "READ_LOGICAL_NETLIST";
    private static String READ_PHYSICAL_NETLIST = "READ_PHYSICAL_NETLIST";

    public static Path benchmarkDCPvsInterchange(Path dcpPath,
                                                 Path edifPath,
                                                 Path workingPath) throws IOException {
        String title = dcpPath + " IS_PACKED=" + IS_PACKED + " IS_GZIPPED=" + IS_GZIPPED;
        CodePerfTracker t = new CodePerfTracker(title);
        t.useGCToTrackMemory(true);
        t.start(READ_DCP);
        Design design = edifPath != null ?
                Design.readCheckpoint(dcpPath, edifPath, CodePerfTracker.SILENT) :
                Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);
        t.stop().start(WRITE_LOGICAL_NETLIST);
        Path logNetlistPath = FileTools.replaceExtension(dcpPath, ".netlist");
        if (workingPath != null) {
            logNetlistPath = FileTools.replaceDir(logNetlistPath, workingPath);
        }
        String logNetlistFileName = logNetlistPath.toString();
        LogNetlistWriter.writeLogNetlist(design.getNetlist(), logNetlistFileName);
        t.stop().start(WRITE_PHYSICAL_NETLIST);

        Path physNetlistPath = FileTools.replaceExtension(dcpPath, ".phys");
        if (workingPath != null) {
            physNetlistPath = FileTools.replaceDir(physNetlistPath, workingPath);
        }
        String physNetlistFileName = physNetlistPath.toString();
        PhysNetlistWriter.writePhysNetlist(design, physNetlistFileName);
        t.stop();
        design = null;
        System.gc();
        t.start(READ_LOGICAL_NETLIST);
        EDIFNetlist netlist = LogNetlistReader.readLogNetlist(logNetlistFileName);
        t.stop().start(READ_PHYSICAL_NETLIST);
        Design designReturn = PhysNetlistReader.readPhysNetlist(physNetlistFileName, netlist);
        t.stop().start(WRITE_DCP);
        Path dcpOutputPath = dcpPath;
        if (workingPath != null) {
            dcpOutputPath = FileTools.replaceDir(dcpOutputPath, workingPath);
        }
        String dcpOutputFileName = dcpOutputPath.toString().replace(".dcp", "_rt.dcp");
        designReturn.writeCheckpoint(dcpOutputFileName, CodePerfTracker.SILENT);
        t.stop().printSummary();

        System.out.print("# " + title + " "
                + t.getRuntime(READ_DCP) + " "
                + t.getMemUsage(READ_DCP) + " "
                + t.getRuntime(WRITE_LOGICAL_NETLIST) + " "
                + t.getMemUsage(WRITE_LOGICAL_NETLIST) + " "
                + t.getRuntime(WRITE_PHYSICAL_NETLIST) + " "
                + t.getMemUsage(WRITE_PHYSICAL_NETLIST) + " "
                + t.getRuntime(READ_LOGICAL_NETLIST) + " "
                + t.getMemUsage(READ_LOGICAL_NETLIST) + " "
                + t.getRuntime(READ_PHYSICAL_NETLIST) + " "
                + t.getMemUsage(READ_PHYSICAL_NETLIST) + " "
                + t.getRuntime(WRITE_DCP) + " "
                + t.getMemUsage(WRITE_DCP) + " "
                + printFileSize("             DCP", dcpOutputFileName) + " "
                + printFileSize(" LOGICAL_NETLIST", logNetlistFileName) + " "
                + printFileSize("PHYSICAL_NETLIST", physNetlistFileName) + " "
        );

        return Paths.get(dcpOutputFileName);
    }


    private static double printFileSize(String title, String fileName) {
        double fileSize = FileTools.getFileSize(fileName)/(1024.0*1024.0);
        System.out.printf(title + "_FILE_SIZE: %10.3fMBs\n", fileSize);
        return fileSize;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.out.println("USAGE: <input DCP> [input EDIF]");
            return;
        }
        benchmarkDCPvsInterchange(Paths.get(args[0]),
                args.length == 2 ? Paths.get(args[1]) : null,
                null);
    }
}
