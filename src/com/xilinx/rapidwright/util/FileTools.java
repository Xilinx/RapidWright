/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
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
package com.xilinx.rapidwright.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.CodeSource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.input.ProxyInputStream;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.unsafe.UnsafeInput;
import com.esotericsoftware.kryo.unsafe.UnsafeOutput;
import com.esotericsoftware.kryo.util.Util;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.timing.TimingModel;

/**
 * This class is specifically written to allow for efficient file import/export of different semi-primitive
 * data types and structures.  The read and write functions of this class are only guaranteed to work with
 * those specified in this class and none else.  The goal of this class is to load faster than Serialized
 * Java and produce smaller files as well.
 *
 * @author Chris Lavin
 * Created on: Apr 22, 2010
 */
public class FileTools {

    /** Environment Variable Name which points to the RapidWright project on disk */
    public static final String RAPIDWRIGHT_VARIABLE_NAME = "RAPIDWRIGHT_PATH";
    /** Suffix of the device part files */
    public static final String DEVICE_FILE_SUFFIX = "_db.dat";
    /** Suffix of the device part files */
    public static final String DEVICE_CACHE_FILE_SUFFIX = "_db_cache.dat";
    /** Data folder name */
    public static final String DATA_FOLDER_NAME = "data";
    /** Tcl source folder name */
    public static final String TCL_FOLDER_NAME = "tcl";
    /** Java library folder name */
    public static final String JARS_FOLDER_NAME = "jars";
    /** Images source folder name */
    public static final String IMAGES_FOLDER_NAME = "images";
    /** Python source folder name */
    public static final String PYTHON_FOLDER_NAME = "python";
    /** Folder where device files are kept */
    public static final String DEVICE_FOLDER_NAME = DATA_FOLDER_NAME + File.separator + "devices";
    /** File name of the UnisimManager initialization data file (replaced HDI_PRIMITIVES_FILE_NAME and VALID_CELL_PLACEMENTS_FILE_NAME) */
    public static final String UNISIM_DATA_FILE_NAME = DATA_FOLDER_NAME + File.separator + "unisim_data.dat";
    /** File name created from Vivado for all supported parts for RapidWright */
    public static final String PART_DUMP_FILE_NAME = DATA_FOLDER_NAME + File.separator + "partdump.csv";
    /** Location of the main parts database file */
    public static final String PART_DB_PATH = DATA_FOLDER_NAME + File.separator + "parts.db";
    /** Location of the cell pins default data file */
    public static final String CELL_PIN_DEFAULTS_FILE_NAME = DATA_FOLDER_NAME + File.separator + "cell_pin_defaults.dat";
    /** Location of cached routethru helper files */
    public static final String ROUTETHRU_FOLDER_NAME = DATA_FOLDER_NAME + File.separator + "routeThrus";
    /** Common instance of the Kryo class for serialization purposes */
    private static Kryo kryo;
    /** Supporting data folders packed in standalone jars of RapidWright */
    public static final String[] UNPACK_FOLDERS = new String[]{DATA_FOLDER_NAME, TCL_FOLDER_NAME,
            IMAGES_FOLDER_NAME, TimingModel.TIMING_DATA_DIR};
    /** Static empty array to save on memory */
    public static int[] emptyIntArray = new int[0];
    /** Static empty array to save on memory */
    public static short[] emptyShortArray = new short[0];
    /** Static empty array to save on memory */
    public static String[] emptyStringArray = new String[0];
    /** Part Database File Version */
    public static final int PART_DB_FILE_VERSION = 1;
    /** Unisim Data File Version */
    public static final int UNISIM_DATA_FILE_VERSION = 1;
    /** Base URL for download data files */
    public static final String RAPIDWRIGHT_DATA_URL = "http://data.rapidwright.io/";
    /** Suffix added to data file names to capture md5 status */
    public static final String MD5_DATA_FILE_SUFFIX = ".md5";

    private static boolean OVERRIDE_DATA_FILE_DOWNLOAD = false;

    private static Boolean useKryoUnsafeStreams = null;

    static {
        // TODO - This turns off illegal reflective access warnings in Java 9+
        // This is due to reflective use in Kryo which is a direct dependency of this
        // project.  Data files are encoded using the Unsafe interface because of the
        // performance benefits it provides.  There is currently no work-around to
        // maintain performance. This quieting of the illegal reflective access warning
        // can be made verbose by specifying the JVM option "--illegal-access=warn"
        boolean allowWarnings = false;
        RuntimeMXBean rmxBean = ManagementFactory.getRuntimeMXBean();
        for (String input : rmxBean.getInputArguments()) {
            if (input.startsWith("--illegal-access")) {
                allowWarnings = true;
            }
        }

        if (!allowWarnings) {
            Device.quietReflectiveAccessWarning();
        }
    }



    //===================================================================================//
    /* Get Streams                                                                       */
    //===================================================================================//
    /**
     * Creates a Kryo output stream that instantiates a Zstandard compression stream
     * to an output file.
     * 
     * @param fileName Name of the file to target.
     * @return The created kryo-zstd output file stream.
     */
    public static Output getKryoZstdOutputStream(String fileName) {
        return getKryoOutputStreamWithoutDeflater(getZstdOutputStream(fileName));
    }

    /**
     * Creates a Kryo output stream that instantiates a Zstandard compression stream
     * from an output stream.
     * 
     * @param os The existing output stream to wrap.
     * @return The created kryo-zstd output file stream.
     */
    public static Output getKryoZstdOutputStream(OutputStream os) {
        return getKryoOutputStreamWithoutDeflater(getZstdOutputStream(os));
    }

    /**
     * Creates a Zstandard compression stream to an output file.
     * 
     * @param fileName Name of the file to target.
     * @return The created zstd output file stream.
     */
    public static OutputStream getZstdOutputStream(String fileName) {
        try {
            return getZstdOutputStream(new FileOutputStream(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Wraps the provided output stream with a Zstandard compression stream.
     * 
     * @param os The existing output stream.
     * @return The new output stream that will use Zstandard compression.
     */
    public static OutputStream getZstdOutputStream(OutputStream os) {
        try {
            return new ZstdOutputStream(os, Params.RW_ZSTD_COMPRESSION_LEVEL);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a Kryo output stream that instantiates a gzip compression stream to
     * an output file.
     * 
     * @param fileName Name of the file to target.
     * @return The created kryo-gzip output file stream.
     */
    public static Output getKryoGzipOutputStream(String fileName) {
        try {
            return getKryoGzipOutputStream(new FileOutputStream(fileName));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a Kryo output stream that instantiates a gzip compression stream to
     * an output stream.
     * 
     * @param os The output stream to wrap.
     * @return The created kryo-gzip output file stream.
     */
    public static Output getKryoGzipOutputStream(OutputStream os) {
        return getKryoOutputStreamWithoutDeflater(new DeflaterOutputStream(os));
    }

    /**
     * Use {@link #getKryoGzipOutputStream(String)} instead.
     * @deprecated To be removed in 2023.2.0
     */
    public static Output getKryoOutputStream(String fileName) {
        return getKryoGzipOutputStream(fileName);
    }


    /**
     * Use {@link #getKryoGzipOutputStream(OutputStream)} instead.
     * @deprecated To be removed in 2023.2.0
     */
    public static Output getKryoOutputStream(OutputStream os) {
        return getKryoGzipOutputStream(os);
    }

    /**
     * Wraps the provided output stream with a kryo stream. Will call
     * {@link #useUnsafeStreams()} to decide on using unsafe or not.
     * 
     * @param os The output stream to wrap.
     * @return The created kryo stream.
     */
    public static Output getKryoOutputStreamWithoutDeflater(OutputStream os) {
        return useUnsafeStreams() ? new UnsafeOutput(os)
                                  : new Output(os);
    }

    /**
     * Creates a Kryo input stream from decompressing Zstandard compressed input
     * file.
     * 
     * @param fileName Name of the file to read from.
     * @return The created kryo-zstd input file stream.
     */
    public static Input getKryoZstdInputStream(String fileName) {
        return getKryoInputStreamWithoutInflater(getZstdInputStream(fileName));
    }

    /**
     * Creates a Kryo input stream from decompressing a Zstandard compressed input
     * stream.
     * 
     * @param input The input stream to read from.
     * @return The created kryo-zstd input file stream.
     */
    public static Input getKryoZstdInputStream(InputStream input) {
        try {
            return getKryoInputStreamWithoutInflater(new ZstdInputStream(input));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates an input stream that decompresses a Zstandard compressed input file.
     * 
     * @param fileName Name of the file to read from.
     * @return The created zstd input file stream.
     */
    public static InputStream getZstdInputStream(String fileName) {
        try {
            return new ZstdInputStream(new FileInputStream(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a Kryo input stream that decompresses a gzip compressed input file.
     * 
     * @param fileName Name of the file read from.
     * @return The created kryo-gzip input file stream.
     */
    public static Input getKryoGzipInputStream(String fileName) {
        try {
            return getKryoGzipInputStream((new FileInputStream(fileName)));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a Kryo input stream that decompresses a gzip compressed input stream.
     * 
     * @param is The gzip compressed input stream to read from.
     * @return The created kryo-gzip input file stream.
     */
    public static Input getKryoGzipInputStream(InputStream is) {
        return getKryoInputStreamWithoutInflater(new InflaterInputStream(is));
    }

    /**
     * Please use {@link #getKryoGzipInputStream(String)} instead.
     * @deprecated - To be removed in 2023.2.0
     */
    public static Input getKryoInputStream(String fileName) {
        return getKryoGzipInputStream(fileName);
    }

    /**
     * Please use {@link #getKryoGzipInputStream(InputStream)} instead.
     * @deprecated - To be removed in 2023.2.0
     */
    public static Input getKryoInputStream(InputStream in) {
        return getKryoGzipInputStream(in);
    }

    /**
     * Wraps the provided input stream with a kryo stream. Will call
     * {@link #useUnsafeStreams()} to decide on using unsafe or not.
     * 
     * @param in The input stream to wrap.
     * @return The created kryo stream.
     */
    public static Input getKryoInputStreamWithoutInflater(InputStream in) {
        return useUnsafeStreams() ? new UnsafeInput(in)
                                  : new Input(in);
    }


    /**
     * Checks if Kryo Unsafe Streams can/should be used.  They provide a performance advantage
     * but are not (as easily) available in Java 16+.
     * @return True if unsafe streams are to be used, false otherwise.
     */
    public static boolean useUnsafeStreams() {
        if (useKryoUnsafeStreams == null) {
            try {
                useKryoUnsafeStreams = Util.unsafe && getJavaVersion() < 16;
            } catch (Exception e) {
                // Don't crash on failure to check, just don't use them
                useKryoUnsafeStreams = false;
            }
        }
        return useKryoUnsafeStreams;
    }

    /**
     * Gets the current runtime version number.  Correctly handles 1.8.x formats and Java 9+
     * @return The integer version of the Java version (8, 9, 10, 11, ...)
     */
    public static int getJavaVersion() {
        String ver = System.getProperty("java.version");
        if (ver.startsWith("1.")) {
            ver = ver.substring(2, 3);
        } else {
            int dotIdx = ver.indexOf('.');
            if (dotIdx != -1) {
                ver = ver.substring(0, dotIdx);
            }
        }
        return Integer.parseInt(ver);
    }

    /**
     * Creates a BufferedReader that reads an input file and determines based on file
     * extension (*.gz) if the file is gzipped or not.
     * @param fileName Name of the text or gzipped file
     * @return An opened BufferedReader to the file.
     */
    public static BufferedReader getProperInputStream(String fileName) {
        BufferedReader in = null;
        try {
            if (fileName.endsWith(".gz")) {
                in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(fileName))));
            } else {
                in = new BufferedReader(new FileReader(fileName));
            }
        }
        catch (FileNotFoundException e) {
            throw new UncheckedIOException("ERROR: Could not find file: " + fileName, e);
        }
        catch (IOException e) {
            throw new UncheckedIOException("ERROR: Problem reading file: " + fileName, e);
        }

        return in;
    }

    /**
     * Creates a new BufferedWriter that will either write out text or a gzipped
     * compressed version of text based on the file extension (*.gz {@code ->} gzipped, all
     * others target an uncompressed output.
     * @param fileName Name of the output file.  Will be gzipped if has *.gz extension.
     * @return The opened BufferedWriter to the named file.
     */
    public static BufferedWriter getProperOutputStream(String fileName) {
        BufferedWriter out = null;

        try {
            if (fileName.endsWith(".gz")) {
                out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(fileName))));
            } else {
                out = new BufferedWriter(new FileWriter(fileName));
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }


        return out;
    }

    //===================================================================================//
    /* Custom Read/Write File Functions for Device/WireEnumeration Class                 */
    //===================================================================================//
    public static HashMap<String,Integer> readHashMap(Input dis, Integer[] allInts) {
        int count;
        HashMap<String,Integer> tileMap = null;
        String[] keys;
        count = dis.readInt();
        tileMap = new HashMap<String,Integer>(count);
        keys = new String[count];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = dis.readString();
        }
        for (int i=0; i < count; i++) {
            tileMap.put(keys[i], allInts[dis.readInt()]);
        }
        return tileMap;
    }

    public static boolean writeHashMap(Output dos, HashMap<String,Integer> map) {
        int size = map.size();
        dos.writeInt(size);
        ArrayList<Integer> values = new ArrayList<Integer>(map.size());
        for (String s : map.keySet()) {
            values.add(map.get(s));
            dos.writeString(s);
        }
        for (Integer i : values) {
            dos.writeInt(i.intValue());
        }
        return true;
    }

    public static boolean writeStringArray(Output dos, String[] stringArray) {
        dos.writeInt(stringArray.length);
        for (int i=0; i<stringArray.length; i++) {
            dos.writeString(stringArray[i]);
        }
        return true;
    }

    public static String[] readStringArray(Input dis) {
        int size;
        String[] wireArray = null;
        size = dis.readInt();
        if (size == 0) {
            return emptyStringArray;
        }
        wireArray = new String[size];
        for (int i = 0; i < wireArray.length; i++) {
            wireArray[i] = dis.readString();
        }
        return wireArray;
    }

    public static boolean writeIntArray(Output dos, int[] intArray) {
        if (intArray == null) {
            dos.writeInt(0);
            return true;
        }
        dos.writeInt(intArray.length);
        dos.writeInts(intArray, 0, intArray.length);
        return true;
    }

    public static boolean writeShortArray(Output dos, short[] intArray) {
        if (intArray == null) {
            dos.writeShort(0);
            return true;
        }
        dos.writeShort(intArray.length);
        dos.writeShorts(intArray, 0, intArray.length);
        return true;
    }

    public static int[] readIntArray(Input dis) {
        int length = dis.readInt();
        if (length == 0) return emptyIntArray;
        return dis.readInts(length);
    }

    public static short[] readShortArray(Input dis) {
        int length = dis.readShort();
        if (length == 0) return emptyShortArray;
        return dis.readShorts(length);
    }

    public static boolean writeString(DataOutputStream dos, String str) {
        try {
            dos.writeInt(str.length());
            dos.write(str.getBytes());
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public static String readString(DataInputStream dis) {
        byte[] buffer;
        try {
            buffer = new byte[dis.readInt()];
            dis.read(buffer);
        } catch (IOException e) {
            return null;
        }
        return new String(buffer);
    }

    //===================================================================================//
    /* Generic Read/Write Serialization Methods                                          */
    //===================================================================================//
    /**
     * Loads a serialized Java object from fileName.
     * @param fileName The file to read from.
     * @return The Object de-serialized from the file or null if there was an error.
     */
    @SuppressWarnings("resource")
    public static Object loadFromFile(String fileName) {
        File inputFile = new File(fileName);

        try (FileInputStream fis = new FileInputStream(inputFile);
            BufferedInputStream bis = new BufferedInputStream(fis);
            ObjectInputStream ois  = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
        catch (FileNotFoundException e) {
            MessageGenerator.briefError("Could not open file: " + fileName + " , does it exist?");

        }
        catch (IOException e) {
            MessageGenerator.briefError("Trouble reading from file: " + fileName);

        }
        catch (ClassNotFoundException e) {
            MessageGenerator.briefError("Improper file found: ");

        }
        catch (OutOfMemoryError e) {
            MessageGenerator.briefError("The JVM ran out of memory trying to load the object in " +
                fileName + ". Try using the JVM switch to increase the heap space (" +
                        "ex: java -Xmx1600M).");
        }
        return null;
    }

    /**
     * Serialize the Object o to a the file specified by fileName.
     * @param o The object to serialize.
     * @param fileName Name of the file to serialize the object to.
     * @return True if operation was successful, false otherwise.
     */
    public static boolean saveToFile(Object o, String fileName) {
        File objectFile = new File(fileName);
        try (FileOutputStream fos = new FileOutputStream(objectFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(o);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * This is a simple method that writes the elements of an ArrayList of Strings
     * into lines in the text file fileName.
     * @param lines The ArrayList of Strings to be written
     * @param fileName Name of the text file to save the ArrayList to
     */
    public static void writeLinesToTextFile(List<String> lines, String fileName) {
        String nl = System.getProperty("line.separator");
        try (FileWriter fw = new FileWriter(fileName);
            BufferedWriter bw = new BufferedWriter(fw)) {
            for (String line : lines) {
                bw.write(line + nl);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Error writing file: " +
                fileName + File.separator + e.getMessage(), e);
        }
    }

    /**
     * This is a simple method that writes a String to a file and adds a new line.
     * @param text the String to write to the file
     * @param fileName Name of the text file to save the ArrayList to
     */
    public static void writeStringToTextFile(String text, String fileName) {
        String nl = System.getProperty("line.separator");
        try (FileWriter fw = new FileWriter(fileName);
            BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(text + nl);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Error writing file: " +
                fileName + File.separator + e.getMessage(), e);
        }
    }

    /**
     * This is a simple method that will read in a text file and put each line in a
     * string and put all the lines in an ArrayList.  The user is cautioned not
     * to open extremely large files with this method.
     * @param fileName Name of the text file to load.
     * @return An ArrayList containing strings of each line in the file.
     */
    public static ArrayList<String> getLinesFromTextFile(String fileName) {
        String line;

        ArrayList<String> lines = new ArrayList<String>();
        try (FileReader fr = new FileReader(fileName);
            BufferedReader br = new BufferedReader(fr)) {
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        catch (FileNotFoundException e) {
            throw new UncheckedIOException("ERROR: Could not find file: " + fileName, e);
        }
        catch (IOException e) {
            throw new UncheckedIOException("ERROR: Could not read from file: " + fileName, e);
        }

        return lines;
    }

    /**
     * Gets the last n number of lines from a text file and returns them.
     * @param fileName Name of the text file
     * @param n Number of last lines to get
     * @return A list of the last n lines in the text file.  If the file has less than or equal
     * to n lines in the file, it returns all lines in the file.
     */
    public static List<String> getLastNLinesFromTextFile(String fileName, int n) {
        if (n <= 0) return Collections.emptyList();
        ArrayList<String> lines = getLinesFromTextFile(fileName);
        if (lines.size() <= n) {
            return lines;
        }
        ArrayList<String> toReturn = new ArrayList<>();
        for (int i=(lines.size()-(n+1)); i < lines.size(); i++) {
            toReturn.add(lines.get(i));
        }
        return toReturn;
    }

    public static ArrayList<String> getLinesFromInputStream(InputStream in) {
        String line = null;
        ArrayList<String> lines = new ArrayList<String>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        try {
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return lines;
    }

    //===================================================================================//
    /* Generic File Manipulation Methods                                                 */
    //===================================================================================//

    /**
     * Takes a file name and removes everything after the last '.' inclusive
     * @param fileName The input file name
     * @return the substring of fileName if it contains a '.', it returns fileName otherwise
     */
    public static String removeFileExtension(String fileName) {
        int endIndex = fileName.lastIndexOf('.');
        if (endIndex != -1) {
            return fileName.substring(0, endIndex);
        }
        else {
            return fileName;
        }
    }

    /**
     * Creates a directory in the current path called dirName.
     * @param dirName Name of the directory to be created.
     * @return True if the directory was created or already exists, false otherwise.
     */
    public static boolean makeDir(String dirName) {
        File dir = new File(dirName);
        if (!(dir.exists())) {
            return dir.mkdir();
        }
        return true;
    }

    /**
     * Creates a directory in the current path called dirName.
     * @param dirName Name of the directory to be created.
     * @return True if the directory and implicit parent directories were created, false otherwise.
     */
    public static boolean makeDirs(String dirName) {
        return new File(dirName).mkdirs();
    }

    /**
     * Gets the size of the file in bytes.
     * @param fileName Name of the file to get the size of.
     * @return The number of bytes used by the file.
     */
    public static long getFileSize(String fileName) {
        return new File(fileName).length();
    }

    /**
     * Delete the file/folder in the file system called fileName
     * @param fileName Name of the file to delete
     * @return True for successful deletion, false otherwise.
     */
    public static boolean deleteFile(String fileName) {
        // A File object to represent the filename
        File f = new File(fileName);

        // Make sure the file or directory exists and isn't write protected
        if (!f.exists()) {
            MessageGenerator.briefError("WARNING: Attempted to delete file " + fileName + " but it wasn't there.");
            return false;
        }

        if (!f.canWrite())
          throw new IllegalArgumentException("Delete: write protected: "
              + fileName);

        // If it is a directory, make sure it is empty
        if (f.isDirectory()) {
          String[] files = f.list();
          if (files.length > 0)
            throw new IllegalArgumentException(
                "Delete: directory not empty: " + fileName);
        }

        // Attempt to delete it
        boolean success = f.delete();

        if (!success)
          throw new IllegalArgumentException("Delete: deletion failed");

        return success;
    }

    /**
     * Deletes everything in the directory given by path, but does not
     * delete the folder itself.
     * @param path The path to the folder where all its contents will be deleted.
     * @return True if operation was successful, false otherwise.
     */
    public static boolean deleteFolderContents(String path) {
        File currDirectory = new File(path);
        if (currDirectory.exists()) {
            try {
                for (File file : currDirectory.listFiles()) {
                    if (file.isDirectory()) {
                        if (!deleteFolder(file.getCanonicalPath())) {
                            return false;
                        }
                    }
                    else {
                        if (!deleteFile(file.getCanonicalPath())) {
                            return false;
                        }
                    }
                }
            }
            catch (IOException e) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Delete the folder and recursively files and folders below
     * @param folderName
     * @return true for successful deletion, false otherwise
     */
    public static boolean deleteFolder(String folderName) {
        // A file object to represent the filename
        File f = new File(folderName);

        if (!f.exists() || !f.isDirectory()) {
            MessageGenerator.briefError("WARNING: Attempted to delete folder " + folderName + " but it wasn't there.");
            return false;
        }

        for (File i: f.listFiles()) {
            if (i.isDirectory()) {
                deleteFolder(i.getAbsolutePath());
            } else if (i.isFile()) {
                if (!i.delete()) {
                    throw new IllegalArgumentException("Delete: deletion failed: " + i.getAbsolutePath());
                }
            }
        }
        return deleteFile(folderName);
    }

    public static boolean renameFile(String oldFileName, String newFileName) {
        File oldFile = new File(oldFileName);
        return oldFile.renameTo(new File(newFileName));
    }

    /**
     * Copies a file from one location (src) to another (dst).  This implementation uses the java.nio
     * channels (because supposedly it is faster).
     * @param src Source file to read from
     * @param dst Destination file to write to
     * @return True if operation was successful, false otherwise.
     */
    public static boolean copyFile(String src, String dst) {
        File srcFile = new File(src);
        try (FileInputStream fis = new FileInputStream(srcFile);
            FileChannel inChannel = fis.getChannel()) {
            if (new File(dst).isDirectory()) {
                dst = dst + File.separator + srcFile.getName();
            }
            try (FileOutputStream fos = new FileOutputStream(dst);
                FileChannel outChannel = fos.getChannel()) {
                inChannel.transferTo(0, inChannel.size(), outChannel);
            }
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
            MessageGenerator.briefError("ERROR could not find/access file(s): " + src + " and/or " + dst);
            return false;
        }
        catch (IOException e) {
            MessageGenerator.briefError("ERROR copying file: " + src + " to " + dst);
            return false;
        }
        return true;
    }

    /**
     * Copies a folder and its files from the path defined in srcDirectoryPath to a new folder
     * at dstDirectoryPath.  If the recursive flag is set it will also recursively copy subfolders
     * from the source to the destination.
     * @param srcDirectoryPath The name of the source folder to copy.
     * @param dstDirectoryPath The destination of where the copy of the folder should be located.
     * @param recursive A flag denoting if the sub folders of source should be copied.
     * @return True if operation was successful, false otherwise.
     */
    public static boolean copyFolder(String srcDirectoryPath, String dstDirectoryPath, boolean recursive) {
        File srcDirectory = new File(srcDirectoryPath);
        File dstDirectory = new File(dstDirectoryPath + File.separator + srcDirectory.getName());
        if (srcDirectory.exists() && srcDirectory.isDirectory()) {
            if (!dstDirectory.exists()) {
                dstDirectory.mkdirs();
            }
            for (File file : srcDirectory.listFiles()) {
                if (!file.isDirectory()) {
                    if (!copyFile(file.getAbsolutePath(), dstDirectory.getAbsolutePath() + File.separator + file.getName())) {
                        return false;
                    }
                }
                else if (file.isDirectory() && recursive) {
                    if (!copyFolder(file.getAbsolutePath(), dstDirectory.getAbsolutePath(), true)) {
                        return false;
                    }
                }

            }
            return true;
        }
        MessageGenerator.briefError("ERROR: copyFolder() - Cannot find directory: " + srcDirectoryPath);
        return false;
    }

    /**
     * Copies the folder contents of the folder specified by src to folder specified as dst.  It will
     * copy all files in it to the new location.  If the recursive
     * flag is set, it will copy everything recursively in the folder src to dst.
     * @param src The source folder to copy.
     * @param dst The location of where the copy of the contents of src will be located.
     * @param recursive A flag indicating if sub folders and their contents should be
     * copied.
     * @return True if operation is successful, false otherwise.
     */
    public static boolean copyFolderContents(String src, String dst, boolean recursive) {
        File srcDirectory = new File(src);
        File dstDirectory = new File(dst);
        if (srcDirectory.exists() && srcDirectory.isDirectory()) {
            if (!dstDirectory.exists()) {
                MessageGenerator.briefError("ERROR: Could find destination directory " + dstDirectory.getAbsolutePath());
            }
            for (File file : srcDirectory.listFiles()) {
                if (!file.isDirectory()) {
                    if (!copyFile(file.getAbsolutePath(), dstDirectory.getAbsolutePath() + File.separator + file.getName())) {
                        return false;
                    }
                }
                else if (file.isDirectory() && recursive) {
                    if (!copyFolder(file.getAbsolutePath(), dst, true)) {
                        MessageGenerator.briefError("ERROR: While copying folder " + file.getAbsolutePath() +
                                " to " + dst + File.separator + file.getName());
                        return false;
                    }
                }
            }
            return true;
        }
        MessageGenerator.briefError("ERROR: copyFolderContents() - Cannot find directory: " + src);
        return false;
    }

    /**
     * Convenience assertion to assert that a file exists
     * @param fileName Name of the file to check
     */
    public static void errorIfFileDoesNotExist(String fileName) {
        File f = new File(fileName);
        if (!f.exists())
            MessageGenerator.generalErrorAndExit("ERROR: Couldn't find file '" + fileName +
                    "'. Did it get mispelled or deleted?");
    }

    //===================================================================================//
    /* Simple Device Load Methods & Helpers                                              */
    //===================================================================================//

    /**
     * Gets and returns the value of the environment variable RAPIDWRIGHT_PATH. If this
     * variable is not set, it searches the file system from where the RapidWright code
     * is located and checks if the data directory is present if it is, then it returns
     * the full path name of the directory containing /data.  If not (such as when running
     * from a jar file) it returns null.
     * @return The string of the path to the RapidWright, or null if running from within
     * a jar file and RAPIDWRIGHT_PATH is not set.
     */
    public static String getRapidWrightPath() {
        String path = System.getenv(RAPIDWRIGHT_VARIABLE_NAME);
        if (path == null) {
            final File f = new File(FileTools.class.getProtectionDomain().getCodeSource().getLocation().getPath());
            if (f.isDirectory()) {
                File rootFolder = f.getParentFile();
                if (rootFolder != null) {
                    return rootFolder.getAbsolutePath();
                }
            }
            // We appear to be running within a jar file, let's default to os-specific dir
            unPackSupportingJarData();
            return getExecJarStoragePath();
        }
        if (path.endsWith(File.separator)) {
            path.substring(0, path.length()-1);
        }
        return path;
    }

    public static String getExecJarStoragePath() {
        String rootPath = "";
        if (isWindows()) {
            rootPath = System.getenv("APPDATA");
        } else {
            rootPath = System.getenv("XDG_DATA_HOME");
            if (rootPath == null || rootPath.length() == 0) {
                rootPath = System.getenv("HOME") + File.separator + ".local" + File.separator + "share";
            }
            // TODO for Mac OS, the default for XDG_DATA_HOME would be '~/Library/My App/'
        }
        rootPath += File.separator + "RapidWright";
        makeDirs(rootPath);
        return rootPath;
    }

    public static void updateAllDataFiles() {
        System.out.println("Updating all RapidWright data files (this may take several minutes)...");
        for (String fileName : DataVersions.dataVersionMap.keySet()) {
            if (ensureCorrectDataFile(fileName) != null) {
                System.out.println("  Downloaded " + fileName);
            }
        }
        System.out.println("COMPLETED!");
    }

    /**
     * RapidWright downloads data files on demand to avoid large downloads.  However, if a user
     * wishes to download all necessary files upfront, this method will download and update all
     * necessary files.
     */
    public static void forceUpdateAllDataFiles() {
        System.out.println("Force update of all RapidWright data files "
                + "(this may take several minutes)...");
        int size = DataVersions.dataVersionMap.keySet().size();
        int i=0;
        for (String fileName : DataVersions.dataVersionMap.keySet()) {
            downloadDataFile(fileName);
            System.out.println("  Downloaded ["+i+"/"+size+"] " + fileName);
            i++;
        }
        System.out.println("COMPLETED!");
    }

    /**
     * Downloads and generates all potential data files to make this RapidWright
     * installation static friendly. After running this method, RapidWright should
     * not download any files, create any new directories or generate any new files.
     * This is useful when a single RapidWright installation will be used by
     * multiple processes simultaneously and/or when RapidWright needs to reside in
     * a read-only space.
     * 
     * @param devices The set of devices intended to be used for this installation
     *                (this simply saves download and generation time).
     */
    public static void ensureDataFilesAreStaticInstallFriendly(String... devices) {
        System.out.println("Download data files to " + getRapidWrightPath());
        // Download all non-device data files
        for (String fileName : DataVersions.dataVersionMap.keySet()) {
            if (fileName.contains("data/devices")) continue;
            downloadDataFile(fileName);
        }
        
        // Download all requested device data files and generate associated cache files
        for (String deviceName : devices) {
            Device device = Device.getDevice(deviceName);
            device.ensureDeviceCacheFileIsGenerated();
            new RouteThruHelper(device);
            Device.releaseDeviceReferences();
        }
    }

    /**
     * Downloads and generates all potential data files to make this RapidWright
     * installation static friendly. After running this method, RapidWright should
     * not download any files, create any new directories or generate any new files.
     * This is useful when a single RapidWright installation will be used by
     * multiple processes simultaneously and/or when RapidWright needs to reside in
     * a read-only space. This method will download all devices files and generate
     * all cache files for each device.
     */
    public static void ensureDataFilesAreStaticInstallFriendly() {
        Set<String> devices = new HashSet<>();
        for (Part p : PartNameTools.getParts()) {
            devices.add(p.getDevice());
        }
        ensureDataFilesAreStaticInstallFriendly(devices.toArray(new String[devices.size()]));
    }

    /**
     * Gets the list of all relative dependent data files given the set of devices
     * provided.
     * 
     * @param devices The list of devices to be used to compile the list of needed
     *                data files.
     * @return The list of all necessary data files to operate RapidWright
     *         independently from downloads or generating cache files.
     */
    public static List<String> getAllDependentDataFiles(String... devices) {
        List<String> expectedFiles = new ArrayList<>();
        for (String dataFile : new String[] { CELL_PIN_DEFAULTS_FILE_NAME, PART_DUMP_FILE_NAME, 
                                              PART_DB_PATH, UNISIM_DATA_FILE_NAME }) {
            expectedFiles.add(dataFile);
            expectedFiles.add(dataFile + MD5_DATA_FILE_SUFFIX);
        }

        for (String deviceName : devices) {
            Part part = PartNameTools.getPart(deviceName);
            String devResName = getDeviceResourceSuffix(part);
            expectedFiles.add(devResName + DEVICE_FILE_SUFFIX);
            expectedFiles.add(devResName + DEVICE_FILE_SUFFIX + MD5_DATA_FILE_SUFFIX);
            expectedFiles.add(devResName + DEVICE_CACHE_FILE_SUFFIX);
            expectedFiles.add(getRouteThruFileName(deviceName));
        }
        return expectedFiles;
    }

    /**
     * Downloads the specified data file and version according to
     * {@link #DATA_VERSION_FILE}. This will overwrite any existing file locally of
     * the same name. This also validates the download is correct by calculating the
     * md5sum of the downloaded file and comparing it to the expected one in
     * {@link #DATA_VERSION_FILE}.
     * 
     * @param fileName Name of the data file to download
     * @return The md5 checksum of the downloaded file
     */
    private static String downloadDataFile(String fileName) {
        String md5 = getCurrentDataVersion(fileName);
        String url = RAPIDWRIGHT_DATA_URL + getContainerName(fileName) + "/" +
                    md5;
        String dstFileName = getRapidWrightPath() + File.separator + fileName;
        String downloadedMD5 = _downloadDataFile(url, dstFileName);
        if (!md5.equals(downloadedMD5)) {
            System.err.println("WARNING: Download validation of file " + fileName + " failed.  Trying again...");
            downloadedMD5 = _downloadDataFile(url, dstFileName);
            if (!md5.equals(downloadedMD5)) {
                throw new RuntimeException("ERROR: Failed to reliably download file: " + fileName);
            }
        }
        FileTools.writeStringToTextFile(downloadedMD5, dstFileName + MD5_DATA_FILE_SUFFIX);
        return downloadedMD5;
    }

    private static String _downloadDataFile(String url, String dstFileName) {
        Installer.downloadFile(url, dstFileName);
        String downloadedMD5 = Installer.calculateMD5OfFile(dstFileName);
        return downloadedMD5;
    }

    /**
     * Ensures that the specified RapidWright data file is the correct version and present based
     * on the MD5 hash in @link {@link DataVersions}
     * @param name Name of the RapidWright data file resource
     * @return The MD5 hash of a downloaded file, null if the file present is the correct version
     */
    public static String ensureCorrectDataFile(String name) {
        if (overrideDataFileDownload()) return null;
        String rwPath = getRapidWrightPath();
        String fileName = rwPath + File.separator + name;
        Path resourceFile = Paths.get(fileName);
        if (Files.exists(resourceFile)) {
            if (expectedMD5Matches(name, fileName, resourceFile)) {
                return null;
            }
        }
        return downloadDataFile(name.replace("\\", "/"));
    }

    private static boolean expectedMD5Matches(String name, String fileName, Path resourceFile) {
        File md5File = new File(fileName + MD5_DATA_FILE_SUFFIX);
        String expectedMD5 = getCurrentDataVersion(name);
        if (md5File.exists()) {
            String currMD5 = getStoredMD5FromFile(md5File.toPath());
            if (currMD5.equals(expectedMD5)) {
                return true;
            }
        }
        // .md5 file is missing
        String currMD5 = Installer.calculateMD5OfFile(resourceFile);
        if (expectedMD5.equals(currMD5)) {
            FileTools.writeStringToTextFile(currMD5, resourceFile.toString()
                    + MD5_DATA_FILE_SUFFIX);
            // File matches expected md5
            return true;
        }
        return false;
    }

    /**
     * Extracts the md5 checksum from a previously created MD5 sum file.
     * @param md5File The path of the existing md5 sum file
     * @return the md5 checksum found in the file or null if no file existed or couldn't be read.
     */
    public static String getStoredMD5FromFile(Path md5File) {
        if (Files.exists(md5File)) {
            try {
                return Files.readAllLines(md5File).get(0);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Identifies the proper location for a RapidWright data resource and
     * returns an opened InputStream to that resource.  This works regardless
     * if RAPIDWRIGHT_PATH is set or not, if the resource is inside a jar or
     * not.
     * @param name Name of the resource.
     * @return An InputStream to the resource or null if it could not be found.
     */
    public static InputStream getRapidWrightResourceInputStream(String name) {
        ensureCorrectDataFile(name);
        File resourceFile = new File(getRapidWrightPath() + File.separator + name);
        try {
            return new FileInputStream(resourceFile);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException("ERROR: Attempted to load RapidWright resource file: "
                    + resourceFile.getAbsolutePath() + " but it does not exist.", e);
        }
    }

    /**
     * Finds and returns a file name that can be read for the corresponding
     * RapidWright resource.
     * @param name Name of the RapidWright resource
     * @return The full file name path, or null if one could not be found.
     */
    public static String getRapidWrightResourceFileName(String name) {
        String rwPath = getRapidWrightPath();
        if (rwPath != null) {
            return rwPath + File.separator + name;
        }

        return null;
    }

    /**
     * Gets an input stream to the file containing valid cell placements of the hdi primitives.
     * @return An input stream to the valid cell placements map file.
     */
    public static InputStream getUnisimDataResourceStream() {
        return getRapidWrightResourceInputStream(UNISIM_DATA_FILE_NAME);
    }

    /**
     * Gets an input stream to the file containing a CSV file of valid parts for RapidWright.
     * @return An input stream to the valid cell placements map file.
     */
    public static InputStream getPartDumpResourceStream() {
        return getRapidWrightResourceInputStream(PART_DUMP_FILE_NAME);
    }


    /**
     * Gets and returns the path of the folder where the part files resides for deviceName.
     * @param part The part to get its corresponding folder path.
     * @return The path of the folder where the parts files resides.
     */
    public static String getPartFolderResourceName(Part part) {
        FamilyType ft = part.getRevision().isEmpty() ? part.getArchitecture() : part.getFamily();
        return     DEVICE_FOLDER_NAME +
                File.separator +
                ft.toString().toLowerCase() +
                File.separator;
    }

    /**
     * Gets and returns the path of the folder where the family type resides.
     * @param familyType The family type corresponding folder path.
     * @return The path of the folder where the parts of familyType reside.
     */
    public static String getPartFolderResourceName(FamilyType familyType) {
        familyType = PartNameTools.getArchitectureFromFamilyType(familyType);
        return     DEVICE_FOLDER_NAME +
                File.separator +
                familyType.toString().toLowerCase() +
                File.separator;
    }

    public static String getDeviceFolderResourceName() {
        return DEVICE_FOLDER_NAME;
    }

    public static String getDeviceResourceName(Part part) {
        return getDeviceResourceSuffix(part) + DEVICE_FILE_SUFFIX;
    }

    public static String getDeviceResourceSuffix(Part part) {
        return getPartFolderResourceName(part) + part.getDevice();
    }

    public static String getDeviceResourceCache(Part part) {
        return getDeviceResourceSuffix(part) + DEVICE_CACHE_FILE_SUFFIX;
    }

    /**
     * Gets the relative routethru file name for the given device.
     * 
     * @param deviceName Name of the device
     * @return Relative routethru data file name for the given device.
     */
    public static String getRouteThruFileName(String deviceName) {
        return ROUTETHRU_FOLDER_NAME + File.separator + deviceName + ".rt";
    }

    /**
     * Checks for all device files present in the current RapidWright family path and returns
     * a list of strings of those part names available to be used by the tool within the specified family.
     * @param type The specified family type.
     * @return A list of available Xilinx parts for the given family type
     */
    public static List<String> getAvailableParts(FamilyType type) {
        ArrayList<String> allParts = new ArrayList<String>();
        String pattern = DEVICE_FILE_SUFFIX;
        File dir = new File(FileTools.getDeviceFolderResourceName() + File.separator + type.toString().toLowerCase());
        if (!dir.exists()) {
            MessageGenerator.briefError("ERROR: No part files exist.  Please download "
                    + "see RapidWright installation instructions for help.");
            return Collections.emptyList();
        }
        for (String part : dir.list()) {
            if (part.endsWith(pattern)) {
                allParts.add(part.replace(pattern, ""));
            }
        }
        return allParts;
    }

    /**
     * This method returns an ArrayList of family types currently supported
     * @return ArrayList of all family types installed
     */
    public static List<FamilyType> getAvailableFamilies() {
        ArrayList<FamilyType> allFamilies = new ArrayList<FamilyType>();
        File dir = new File(FileTools.getDeviceFolderResourceName());
        if (!dir.exists()) {
            MessageGenerator.briefError("ERROR: No part files exist.  Please download "
                    + "see RapidWright installation instructions for help.");
            return Collections.emptyList();
        }
        for (String partFamily : dir.list()) {
            if (PART_DB_PATH.endsWith(partFamily)) continue;
            FamilyType type = FamilyType.valueOf(partFamily.toUpperCase());
            if (type != null) allFamilies.add(type);
        }

        return allFamilies;
    }

    /**
     * This method will get and return the current time as a string
     * formatted in the same way used in most Xilinx report and XDL
     * files.  The format used in the using the same syntax as SimpleDateFormat
     * which is "EEE MMM dd HH:mm:ss yyyy".
     * @return Current date and time as a formatted string.
     */
    public static String getTimeString() {
        SimpleDateFormat formatter = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy");
        return formatter.format(new java.util.Date());
    }

    /**
     * Gets and returns the file separator character for the given OS
     */
    public static String getDirectorySeparator() {
        if (FileTools.cygwinInstalled()) {
            return "/";
        }
        else {
            return File.separator;
        }
    }

    /**
     * Checks if Cygwin is installed on the system
     */
    public static boolean cygwinInstalled() {
        return System.getenv("CYGWIN") != null;
    }

    public static void writeObjectToKryoFile(Path fileName, Object o) {
        writeObjectToKryoFile(fileName, o, false);
    }

    public static void writeObjectToKryoFile(String fileName, Object o) {
        writeObjectToKryoFile(Paths.get(fileName), o);
    }


    public static void writeObjectToKryoFile(Path fileName, Object o, boolean writeClass) {
        Kryo kryo = getKryoInstance();
        try (Output out = new Output(Files.newOutputStream(fileName))) {
            if (writeClass)
                kryo.writeClassAndObject(out, o);
            else
                kryo.writeObject(out, o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeObjectToKryoFile(String fileName, Object o, boolean writeClass) {
        writeObjectToKryoFile(Paths.get(fileName), o, writeClass);
    }

    public static Object readObjectFromKryoFile(String fileName) {
        return readObjectFromKryoFile(Paths.get(fileName));
    }

    public static Object readObjectFromKryoFile(Path fileName) {
        Kryo kryo = getKryoInstance();
        try (Input i = new Input(Files.newInputStream(fileName))) {
            return kryo.readClassAndObject(i);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T readObjectFromKryoFile(String fileName, Class<T> c) {
        return readObjectFromKryoFile(Paths.get(fileName), c);
    }
    public static <T> T readObjectFromKryoFile(Path fileName, Class<T> c) {
        try (InputStream is = Files.newInputStream(fileName)) {
            return readObjectFromKryoFile(is, c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T readObjectFromKryoFile(InputStream in, Class<T> c) {
        Kryo kryo = getKryoInstance();
        try (Input i = new Input(in)) {
            return kryo.readObject(i, c);
        }
    }

    public static Object readObjectFromKryoFile(InputStream in) {
        Kryo kryo = getKryoInstance();
        try (Input i = new Input(in)) {
            return kryo.readClassAndObject(i);
        }
    }

    public static Kryo getKryoInstance() {
        if (kryo == null) {
            kryo = new Kryo();
            kryo.setRegistrationRequired(false);
        }
        return kryo;
    }

    /**
     * Is fileName1 newer than fileName2?
     * @param fileName1
     * @param fileName2
     * @return
     */
    public static boolean isFileNewer(Path fileName1, Path fileName2) {
        try {
            FileTime time1 = Files.getLastModifiedTime(fileName1);
            FileTime time2 = Files.getLastModifiedTime(fileName2);
            return time1.compareTo(time2) >= 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Is fileName1 newer than fileName2?
     * @param fileName1
     * @param fileName2
     * @return
     */
    public static boolean isFileNewer(String fileName1, String fileName2) {
        return isFileNewer(Paths.get(fileName1), Paths.get(fileName2));
    }

    public static Pair<InputStream,Long> getInputStreamFromZipFile(String zipFileName, String fileEndsWith) {
        try {
            final ZipFile zip = new ZipFile(zipFileName);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry match = null;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(fileEndsWith)) {
                    if (match != null) {
                        throw new RuntimeException("ERROR: Found 2 or more matching files in zip file: " +
                                zipFileName + " with ending: '" + fileEndsWith + "'");
                    }
                    match = entry;
                }
            }
            if (match == null) return null;
            InputStream i = zip.getInputStream(match);
            return new Pair<>(new ProxyInputStream(i) {
                @Override
                public void close() throws IOException {
                    super.close();
                    zip.close();
                }
            }, match.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void close(InputStream is) {
        try {
            if (is != null) is.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void close(ZipFile zip) {
        try {
            if (zip != null) zip.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Generic method to run a command in the current shell and return its standard output and standard error
     * @param command The command to run
     * @return A list of standard output lines followed by the standard error lines
     */
    public static ArrayList<String> getCommandOutput(String[] command) {
        Process p;
        try {
            p = Runtime.getRuntime().exec(command);

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader readerErr = new BufferedReader(new InputStreamReader(p.getErrorStream()));


            String line = "";
            ArrayList<String> lines = new ArrayList<String>();
            while ((line = reader.readLine())!= null) {
                lines.add(line);
            }
            while ((line = readerErr.readLine())!= null) {
                lines.add(line);
            }

            reader.close();
            readerErr.close();
            p.waitFor();
            return lines;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * A generic method to run a command from the system command line.
     * @param command The command to execute.  This method blocks until the command finishes.
     * @param verbose When true, it will first print to std.out the command and also all of the
     * command's output (both std.out and std.err) to std.out.
     * @return The return value of the process if it terminated, if there was a problem it returns null.
     */
    public static Integer runCommand(String command, boolean verbose) {
        return runCommand(command, verbose, null, null);
    }

    /**
     * A generic method to run a command from the system command line.
     * 
     * @param command The command to execute. This method blocks until the command
     *                finishes.
     * @param verbose When true, it will first print to std.out the command and also
     *                all of the command's output (both std.out and std.err) to
     *                std.out.
     * @param environ array of strings, each element of which has environment
     *                variable settings in the format name=value, or null if the
     *                subprocess should inherit the environment of the current
     *                process.
     * @param runDir  the working directory of the subprocess, or null if the
     *                subprocess should inherit the working directory of the current
     *                process.
     * @return The return value of the process if it terminated, if there was a
     *         problem it returns null.
     */
    public static Integer runCommand(String command, boolean verbose, String[] environ, File runDir) {
        if (verbose) System.out.println(command);
        int returnValue = 0;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(command, environ, runDir);
            StreamGobbler input = new StreamGobbler(p.getInputStream(), verbose);
            StreamGobbler err = new StreamGobbler(p.getErrorStream(), verbose);
            input.start();
            err.start();
            returnValue = p.waitFor();
        } catch (IOException e) {
            e.printStackTrace();
            MessageGenerator.briefError("ERROR: In running the command \"" + command + "\"");
            return null;
        } catch (InterruptedException e) {
            e.printStackTrace();
            MessageGenerator.briefError("ERROR: The command was interrupted: \"" + command + "\"");
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return returnValue;
    }

    /**
     * A generic method to run a command from the system command line.
     * @param command The command to execute.  This method blocks until the command finishes.
     * @param logFileName Name of the log file to produce that will capture stderr and stdout.
     * @return The return value of the process if it terminated, if there was a problem it returns null.
     */
    public static Integer runCommand(List<String> command, String logFileName) {
        System.out.println("External Command: " + command);
        System.out.println("Log File: " + logFileName);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(logFileName));
        Process p = null;
        int returnVal = -1;
        try {
            p = pb.start();
            returnVal = p.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return returnVal;
    }

    public static String getUniqueProcessAndHostID() {
        return ManagementFactory.getRuntimeMXBean().getName() + "_" + Thread.currentThread().getId();
    }

    /**
     * Uses a similar algorithm to diff to determine if the file
     * is a binary file by looking at the first 4k bytes to see if
     * there are any null characters.  If so, it is considered binary.
     * @param fileName Name of the file to check.
     * @return True if the file is considered binary, false otherwise.
     */
    public static boolean isFileBinary(Path fileName) {
        try (BufferedInputStream br = new BufferedInputStream(Files.newInputStream(fileName))) {
            return isDataBinary(br);
        } catch (IOException e) {
            throw new RuntimeException("ERROR: Trying to read file " + fileName + " and it errored.", e);
        }
    }

    /**
     * Uses a similar algorithm to diff to determine if the file
     * is a binary file by looking at the first 4k bytes to see if
     * there are any null characters.  If so, it is considered binary.
     * @param fileName Name of the file to check.
     * @return True if the file is considered binary, false otherwise.
     */
    public static boolean isFileBinary(String fileName) {
        return isFileBinary(Paths.get(fileName));
    }

    public static final int BINARY_CHECK_LENGTH = 8192;
    private static byte[] binaryCheckData;

    public static boolean isDataBinary(InputStream is) {
        if (!is.markSupported()) {
            throw new RuntimeException("ERROR: Cannot determine if input stream is binary without mark support.");
        }
        if (binaryCheckData == null) {
            binaryCheckData = new byte[BINARY_CHECK_LENGTH];
        }
        boolean isBinary = false;
        try {
            is.mark(BINARY_CHECK_LENGTH+1);
            int count = is.read(binaryCheckData);
            for (int i=0; i < count; i++) {
                if (binaryCheckData[i] == 0x00) {
                    isBinary = true;
                    break;
                }
            }
            is.reset();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Extra check to not mistake encrypted EDIF as unencrypted
        if (!isBinary
                && binaryCheckData[0] == 'X'
                && binaryCheckData[1] == 'l'
                && binaryCheckData[2] == 'x'
                && binaryCheckData[3] == 'V') {
            isBinary = true;
        }
        return isBinary;
    }

    public static final int[] GZIP_START_BYTES = {0x78, 0x9c};
        
    public static boolean isFileGzipped(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return in.read() == GZIP_START_BYTES[0] && in.read() == GZIP_START_BYTES[1];
        } catch (IOException e) {
            throw new RuntimeException("ERROR: Trying to read file " + path + " and it errored.", e);
        }
    }
    
    /**
     * Runs the provided command (arguments must be separate) and gathers the
     * standard output followed by the standard error.
     * @param includeError Option to set if the returned list includes the standard error
     * at the end.
     * @param command The command with arguments separated.
     * @return The list of lines of output from the command.
     */
    public static List<String> execCommandGetOutput(boolean includeError, String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process p = null;
        String line = null;
        List<String> output = new ArrayList<>();

        try {
            p = pb.start();
            p.waitFor();  // wait for process to finish then continue.
            try (BufferedReader bri = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while ((line = bri.readLine()) != null) {
                    output.add(line);
                }
            }
            if (includeError) {
                try (BufferedReader bre = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    while ((line = bre.readLine()) != null) {
                        output.add(line);
                    }
                }
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (p != null) p.destroyForcibly();
        }

        return output;
    }

    /**
     * Checks if a particular executable is available on the current path
     * @param execName Name of the executable (ex: vivado)
     * @return True if the executable is available in the current path, false otherwise.
     */
    public static boolean isExecutableOnPath(String execName) {
        List<String> lines = execCommandGetOutput(true, isWindows() ? "where" : "which",execName);
        for (String line : lines) {
            if (line.startsWith("which:")) return false;
            if (line.contains("INFO: Could not find files")) return false;
            if (line.contains(File.separator + execName)) return true;
        }
        return false;
    }

    /**
     * Checks if vivado is available on current PATH (uses unix 'which' or windows 'where').
     * @return true if vivado is on current PATH, false otherwise.
     */
    public static boolean isVivadoOnPath() {
        return isExecutableOnPath("vivado");
    }

    /**
     * Checks that Vivado is on current PATH and returns true if RapidWright should be
     * compatible with the version of vivado available.  Vivado versions that match or
     * are older than RapidWright are presumed compatible.
     */
    public static boolean isVivadoCompatible() {
        if (isVivadoOnPath()) {
            List<String> lines = execCommandGetOutput(true, "vivado", "-version");
            for (String line : lines) {
                if (line.startsWith("Vivado ")) {
                    int dot = line.indexOf('.');
                    int year = Integer.parseInt(line.substring(line.indexOf(" v")+2,dot));
                    int quarter = Integer.parseInt(line.substring(dot+1, dot+2));
                    if (year > Device.RAPIDWRIGHT_YEAR_VERSION) {
                        return false;
                    }
                    if (year == Device.RAPIDWRIGHT_YEAR_VERSION && quarter > Device.RAPIDWRIGHT_QUARTER_VERSION) {
                        return false;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets the current version of Vivado on the system PATH.
     * @return The string version representation of the Vivado version available on the system PATH.
     */
    public static String getVivadoVersion() {
        if (isVivadoOnPath()) {
            List<String> lines = execCommandGetOutput(true, "vivado", "-version");
            for (String line : lines) {
                if (line.startsWith("Vivado ")) {
                    String[] tokens = line.split("\\s+");
                    return tokens[1];
                }
            }
        }
        return null;
    }

    /**
     * Gets the full path to the vivado executable if it is set in the PATH
     * environment variable. Works for Windows and Linux.
     * @return Full path to vivado executable, or throws RuntimeException if not found.
     */
    public static String getVivadoPath() {
        String[] cmd = new String[]{isWindows() ? "where" : "which",isWindows() ? "vivado.bat" : "vivado"};
        final List<String> fullOutput = execCommandGetOutput(true, cmd);
        if (fullOutput.isEmpty() || fullOutput.get(0).contains("INFO:") || fullOutput.get(0).contains("which: no")) {
            throw new RuntimeException("ERROR: Couldn't find vivado on PATH");
        }
        return fullOutput.get(0).trim().replace("\\", "/");
    }

    private static String currentOS = null;

    public static String getOSName() {
        if (currentOS == null) {
            currentOS = System.getProperty("os.name");
        }
        return currentOS;
    }

    public static boolean isWindows() {
        return getOSName().startsWith("Windows");
    }

    public static void unzipFile(String zipFileName, String destDirectory) {
        File destDir = new File(destDirectory);
        byte[] buffer = new byte[1024*16];
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        try (FileInputStream fis = new FileInputStream(zipFileName);
            ZipInputStream zin = new ZipInputStream(fis)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String destFilePath = destDirectory + File.separator + e.getName();
                if (e.isDirectory()) {
                    new File(destFilePath).mkdirs();
                } else {
                    File currFile = new File(destFilePath);
                    String parentName = currFile.getParentFile().getAbsolutePath();
                    makeDirs(parentName);
                    try (FileOutputStream fos = new FileOutputStream(destFilePath);
                        BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        int read;
                        while ( (read = zin.read(buffer)) != -1) {
                            bos.write(buffer,0,read);
                        }
                    }
                }
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    private static FilenameFilter ednFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".edn");
        }
    };

    /**
     * Gets a filename filter for EDN files (ends with .edn).
     * @return The EDN filename filter
     */
    public static FilenameFilter getEDNFilenameFilter() {
        return ednFilter;
    }

    private static FilenameFilter dcpFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".dcp");
        }
    };

    /**
     * Gets a filename filter for DCP files (ends with .dcp).
     * @return The DCP filename filter
     */
    public static FilenameFilter getDCPFilenameFilter() {
        return dcpFilter;
    }

    /**
     * Creates a custom filename filter that uses the provided
     * matches string on the name of the file (not the path).
     * @param matches Uses the String.matches() to match filename.
     * @return The newly created filename filter object.
     */
    public static FilenameFilter getFilenameFilter(String matches) {
        FilenameFilter filter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.matches(matches);
            }
        };
        return filter;
    }


    /**
     * Used for unpacking data files inside a standalone jar to be used
     * for regular use by RapidWright.
     * @return True if operation succeeds, false otherwise.
     */
    public static boolean unPackSupportingJarData() {
        String outputPath = getExecJarStoragePath();
        for (String folderName : FileTools.UNPACK_FOLDERS) {
            if (new File(outputPath + File.separator + folderName).exists()) continue;
            try {
                CodeSource src = Device.class.getProtectionDomain().getCodeSource();
                if (src == null) {
                    MessageGenerator.briefError("Couldn't locate code source domain");
                    return false;
                }
                URL jar = src.getLocation();
                try (InputStream is = jar.openStream();
                    ZipInputStream zip = new ZipInputStream(is)) {
                    ZipEntry e;
                    byte[] buffer = new byte[1024];
                    while ((e = zip.getNextEntry()) != null) {
                        String name = e.getName();
                        if (name.startsWith(folderName)) {
                            if (!e.isDirectory()) {
                                String fileName = outputPath + File.separator + e.getName();
                                System.out.println("Unpacking " + fileName);
                                File newFile = new File(fileName);
                                new File(newFile.getParent()).mkdirs();
                                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                                    int len;
                                    while ((len = zip.read(buffer)) > 0) {
                                        fos.write(buffer, 0, len);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    /**
     * Appends an extension to the file. if there is already an extension, the result will have two extensions.
     * @param path
     * @param extension
     * @return
     */
    public static Path appendExtension(Path path, String extension) {
        return path.resolveSibling(path.getFileName().toString()+extension);
    }


    public static Path replaceExtension(Path path, String newExtension) {
        String fn = path.getFileName().toString();
        int idx = fn.lastIndexOf('.');
        if (idx == -1) {
            return path.resolveSibling(fn + newExtension);
        }
        return path.resolveSibling(fn.substring(0, idx) + newExtension);
    }

    /**
     * Translates RapidWright data file names to Azure Blob Container-friendly names
     * @param fileName Name of the RapidWright data file
     * @return Azure blob container name for the provided RapidWright data file
     */
    public static String getContainerName(String fileName) {
        String containerName = fileName.substring(fileName.lastIndexOf("/")+1);
        return containerName.replace("_", "-").replace(".", "-").toLowerCase();
    }

    /**
     * Returns the expected MD5 sum of the named data file
     * @param dataFileName Name of the RapidWright data file name
     * (for example: data/devices/artix7/xa7a100t_db.dat)
     * @return The MD5 sum of the expected file contents.
     */
    public static String getCurrentDataVersion(String dataFileName) {
        if (File.separator.equals("\\")) {
            dataFileName = dataFileName.replace(File.separator,"/");
        }

        Pair<String,String> result = DataVersions.dataVersionMap.get(dataFileName);
        return result != null ? result.getSecond() : null;
    }

    public static void setOverrideDataFileDownload(boolean value) {
        OVERRIDE_DATA_FILE_DOWNLOAD = value;
    }

    public static boolean overrideDataFileDownload() {
        return OVERRIDE_DATA_FILE_DOWNLOAD;
    }

    /**
     * Gets all files (Path objects) recursively (including all sub-directories)
     * starting at a root directory of a particular extension (or file name suffix).
     * 
     * @param root   The root directory from which to start the query
     * @param suffix The file name extension (or suffix pattern) of the files to get
     * @return A list of all files found in the directory and all sub-directories
     *         with the provided suffix (extension)
     */
    private static List<Path> getAllFilesWithSuffixUsingNIO(Path root, String suffix) {
        assert (Files.isDirectory(root));
        try (Stream<Path> stream = Files.walk(root, Integer.MAX_VALUE)) {
            return stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(suffix))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Gets all files (Path objects) recursively (including all sub-directories)
     * starting at a root directory of a particular extension (or file name suffix). 
     * This tries to use the native Linux 'find' command if available as it is 4-5X 
     * faster than the Java Files.walk() method.  Otherwise it defaults to using the 
     * conventional Files.walk() approach.
     * 
     * @param root   The root directory from which to start the query
     * @param suffix The file name extension (or suffix pattern) of the files to get
     * @return A list of all files found in the directory and all sub-directories
     *         with the provided suffix (extension)
     */
    public static List<Path> getAllFilesWithSuffix(Path root, String suffix) {
        // Calling find externally is 4-5X faster for most operations than Files.walk()
        if (!isWindows() && isExecutableOnPath("find")) {
            ProcessBuilder pb = new ProcessBuilder("find", root.toString(), "-type", "f", "-name",
                    "*" + suffix);
            pb.redirectErrorStream();
            Process p = null;
            List<Path> paths = new ArrayList<>();
            try {
                p = pb.start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line = null;
                    while ((line = br.readLine()) != null) {
                        paths.add(Paths.get(line));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return paths;
        } else {
            return getAllFilesWithSuffixUsingNIO(root, suffix);
        }
    }

    /**
     * For Java 16 and below, calling this method will prevent System.exit() calls
     * from exiting the JVM and instead throws a {@link SecurityException} in its
     * place. This method allows for a check to avoid the JVM WARNING message in
     * Java 17.
     */
    public static void blockSystemExitCalls() {
        if (getJavaVersion() < 17) {
            BlockExitSecurityManager.blockSystemExitCalls();
        }
    }

    public static void main(String[] args) {
        if (args[0].equals("--get_vivado_path"))
            System.out.println(getVivadoPath());
    }

    /**
     * Decompresses a gzipped file to a file with the '.gz' extension removed. Does
     * not delete the original file and overwrites any existing file with the same
     * file name as the original with the '.gz' extension removed.
     * 
     * @param gzipFile Path to the original gzipped file
     * @return Path to the decompressed file (same as
     *         {@link FileTools#getDecompressedGZIPFileName(Path)}) or null if the provided
     *         file is not a gzipped file.
     */
    public static Path decompressGZIPFile(Path gzipFile) {
        String fileNameStr = gzipFile.toString();
        if (!fileNameStr.endsWith(".gz")) return null;
        Path target = FileTools.getDecompressedGZIPFileName(gzipFile);
        // Using a larger buffer size for GZIPInputStream improved runtime 5-10%
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(fileNameStr), 65536)) {
            Files.copy(gis, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }
    
    /**
     * Compresses the provided file using GZIP (adds '.gz' extension) 
     * @param uncompressedFile The path to the uncompressed file
     * @return The path to the compressed file which has a '.gz' extension
     */
    public static Path compressFileUsingGZIP(Path uncompressedFile) {
        Path compressedFile = Paths.get(uncompressedFile.toString() + ".gz");
        try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(compressedFile.toFile()), 65536)) {
            Files.copy(uncompressedFile, gos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return compressedFile;
    }

    /**
     * Gets a Path to the corresponding uncompressed name of the provided path. If
     * the path does not have a '.gz' extension, it returns the provided path.
     * 
     * @param gzipFile The path to the gzipped file.
     * @return The path to the corresponding uncompressed file if the provided path
     *         is a gzipped file. If the provided path doesn't have the '.gz'
     *         extension, it returns the provided path.
     */
    public static Path getDecompressedGZIPFileName(Path gzipFile) {
        String fileName = gzipFile.toString();
        if (!fileName.endsWith(".gz")) return gzipFile;
        return Paths.get(fileName.substring(0, fileName.length() - 3));
    }

    public static Path replaceDir(Path path, Path newDir) {
        Path fn = path.getFileName();
        return newDir.resolve(fn);
    }
}

