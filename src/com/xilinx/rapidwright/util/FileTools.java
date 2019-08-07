/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.security.CodeSource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.io.UnsafeInput;
import com.esotericsoftware.kryo.io.UnsafeOutput;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;

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
	/** File name of the EDIF library containing all Uniprims -- Will be removed in 2019.2 */
	public static final String HDI_PRIMITIVES_FILE_NAME = DATA_FOLDER_NAME + File.separator + "hdi_primitives.edf";
	/** File name of containing a kryo compressed map of valid cell placements per family type -- Will be removed in 2019.2 */
	public static final String VALID_CELL_PLACEMENTS_FILE_NAME = DATA_FOLDER_NAME + File.separator + "valid_cell_placements.dat";
	/** File name created from Vivado for all supported parts for RapidWright */
	public static final String PART_DUMP_FILE_NAME = DATA_FOLDER_NAME + File.separator + "partdump.csv";
	/** Location of the main parts database file */
	public static final String PART_DB_PATH = DATA_FOLDER_NAME + File.separator + "parts.db";
	/** Common instance of the Kryo class for serialization purposes */	
	private static Kryo kryo;
	/** Supporting data folders packed in standalone jars of RapidWright */ 
	public static final String[] UNPACK_FOLDERS = new String[]{DATA_FOLDER_NAME, TCL_FOLDER_NAME, IMAGES_FOLDER_NAME};
	/** Static empty array to save on memory */
	public static int[] emptyIntArray = new int[0];
	/** Static empty array to save on memory */
	public static short[] emptyShortArray = new short[0];
	/** Static empty array to save on memory */
	public static String[] emptyStringArray = new String[0];
	//===================================================================================//
	/* Get Streams                                                                       */
	//===================================================================================//
	public static UnsafeOutput getUnsafeOutputStream(String fileName){
		FileOutputStream fos = null; 
		try {
			fos = new FileOutputStream(fileName);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return getUnsafeOutputStream(fos);
	}
	
	public static UnsafeOutput getUnsafeOutputStream(OutputStream os){
		return new UnsafeOutput(new DeflaterOutputStream(os));
	}
	
	public static UnsafeInput getUnsafeInputStream(String fileName){
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(fileName);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return getUnsafeInputStream(fis);
	}
	
	public static UnsafeInput getUnsafeInputStream(InputStream in){
		return new UnsafeInput(new InflaterInputStream(in));
	}
	
	/**
	 * Creates a BufferedReader that reads an input file and determines based on file
	 * extension (*.gz) if the file is gzipped or not.  
	 * @param fileName Name of the text or gzipped file
	 * @return An opened BufferedReader to the file.
	 */
	public static BufferedReader getProperInputStream(String fileName){
		BufferedReader in = null;
		try{
			if(fileName.endsWith(".gz")){
				in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(fileName))));
			}else{
				in = new BufferedReader(new FileReader(fileName));	
			}			
		}
		catch(FileNotFoundException e){
			MessageGenerator.briefErrorAndExit("ERROR: Could not find file: " + fileName);
		}
		catch(IOException e){
			e.printStackTrace();
		}

		return in;
	}
	
	/**
	 * Creates a new BufferedWriter that will either write out text or a gzipped
	 * compressed version of text based on the file extension (*.gz -> gzipped, all
	 * others target an uncompressed output.
	 * @param fileName Name of the output file.  Will be gzipped if has *.gz extension.
	 * @return The opened BufferedWriter to the named file.
	 */
	public static BufferedWriter getProperOutputStream(String fileName){
		BufferedWriter out = null;

		try{
			if(fileName.endsWith(".gz")){
				out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(fileName))));
			}else{
				out = new BufferedWriter(new FileWriter(fileName));	
			}			
		}
		catch(IOException e){
			e.printStackTrace();
		}

		
		return out;
	}
	
	//===================================================================================//
	/* Custom Read/Write File Functions for Device/WireEnumeration Class                 */
	//===================================================================================//
	public static HashMap<String,Integer> readHashMap(UnsafeInput dis, Integer[] allInts){
		int count;
		HashMap<String,Integer> tileMap = null;
		String[] keys;
		count = dis.readInt();
		tileMap = new HashMap<String,Integer>(count);
		keys = new String[count];
		for(int i = 0; i < keys.length; i++){
			keys[i] = dis.readString();
		}
		for(int i=0; i < count; i++){
			tileMap.put(keys[i], allInts[dis.readInt()]);
		}
		return tileMap;
	}

	public static boolean writeHashMap(UnsafeOutput dos, HashMap<String,Integer> map){
		int size = map.size();
		dos.writeInt(size);
		ArrayList<Integer> values = new ArrayList<Integer>(map.size());
		for(String s : map.keySet()){
			values.add(map.get(s));
			dos.writeString(s);
		}
		for(Integer i : values){
			dos.writeInt(i.intValue());
		}
		return true;
	}
	
	public static boolean writeStringArray(UnsafeOutput dos, String[] stringArray){
		/*int size = 0;
		for(String s : stringArray){
			size += s.length() + 1;
		}*/
		dos.writeInt(stringArray.length);
		for(int i=0; i<stringArray.length; i++){
			dos.writeString(stringArray[i]);
		}
		return true;
	}
		
	public static String[] readStringArray(UnsafeInput dis){
		int size;
		String[] wireArray = null;
		size = dis.readInt();
		if(size == 0){
			return emptyStringArray;
		}
		wireArray = new String[size];
		for(int i = 0; i < wireArray.length; i++){
			wireArray[i] = dis.readString();
		}
		return wireArray;
	}
	
	public static boolean writeIntArray(UnsafeOutput dos, int[] intArray){
		if(intArray == null){
			dos.writeInt(0);
			return true;
		}
		dos.writeInt(intArray.length);
		dos.writeInts(intArray);
		return true;
	}

	public static boolean writeShortArray(UnsafeOutput dos, short[] intArray){
		if(intArray == null){
			dos.writeShort(0);
			return true;
		}
		dos.writeShort(intArray.length);
		dos.writeShorts(intArray);
		return true;
	}
	
	public static int[] readIntArray(UnsafeInput dis){
		int length = dis.readInt();
		if(length == 0) return emptyIntArray;
		return dis.readInts(length);
	}

	public static short[] readShortArray(UnsafeInput dis){
		int length = dis.readShort();
		if(length == 0) return emptyShortArray;
		return dis.readShorts(length);
	}
	
	public static boolean writeString(DataOutputStream dos, String str){
		try {
			dos.writeInt(str.length());
			dos.write(str.getBytes());
		} catch (IOException e) {
			return false;
		}
		return true;
	}
	
	public static String readString(DataInputStream dis){
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
	public static Object loadFromFile(String fileName){
		File inputFile = new File(fileName);
		FileInputStream fis;
		BufferedInputStream bis;
		ObjectInputStream ois;
		Object o; 
		try {
			fis = new FileInputStream(inputFile);
			bis = new BufferedInputStream(fis);
			ois = new ObjectInputStream(bis);
			o = ois.readObject();
			ois.close();
			bis.close();
			fis.close();
		} 
		catch (FileNotFoundException e) {
			MessageGenerator.briefError("Could not open file: " + fileName + " , does it exist?");
			return null;			
		}
		catch (IOException e) {
			MessageGenerator.briefError("Trouble reading from file: " + fileName);
			return null;						
		}		
		catch (ClassNotFoundException e) {
			MessageGenerator.briefError("Improper file found: ");
			return null;									
		}
		catch (OutOfMemoryError e){
			MessageGenerator.briefError("The JVM ran out of memory trying to load the object in " +
				fileName + ". Try using the JVM switch to increase the heap space (" +
						"ex: java -Xmx1600M).");
			return null;
		}
		return o;
	}

	/**
	 * Serialize the Object o to a the file specified by fileName.
	 * @param o The object to serialize.
	 * @param fileName Name of the file to serialize the object to.
	 * @return True if operation was successful, false otherwise.
	 */
	public static boolean saveToFile(Object o, String fileName){
		FileOutputStream fos = null;
		BufferedOutputStream bos = null;
		ObjectOutputStream oos = null;
		File objectFile = null;
		
		objectFile = new File(fileName);
		try {
			fos = new FileOutputStream(objectFile);
			bos = new BufferedOutputStream(fos);
			oos = new ObjectOutputStream(bos);
			oos.writeObject(o);
			oos.close();
			bos.close();
			fos.close();
		} catch (FileNotFoundException e) {
			return false;
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	/**
	 * This is a simple method that writes the elements of an ArrayList of Strings
	 * into lines in the text file fileName.
	 * @param lines The ArrayList of Strings to be written
	 * @param fileName Name of the text file to save the ArrayList to
	 */
	public static void writeLinesToTextFile(List<String> lines, String fileName) {
		String nl = System.getProperty("line.separator");
		try{
			BufferedWriter bw = new BufferedWriter(new FileWriter(fileName));
			for(String line : lines) {
				bw.write(line + nl);
			}
			bw.close();
		}
		catch(IOException e){
			MessageGenerator.briefErrorAndExit("Error writing file: " +
				fileName + File.separator + e.getMessage());
		}
	}
	
	/**
	 * This is a simple method that writes a String to a file and adds a new line.
	 * @param text the String to write to the file
	 * @param fileName Name of the text file to save the ArrayList to
	 */
	public static void writeStringToTextFile(String text, String fileName) {
		String nl = System.getProperty("line.separator");
		try{
			BufferedWriter bw = new BufferedWriter(new FileWriter(fileName));
				bw.write(text + nl);
			bw.close();
		}
		catch(IOException e){
			MessageGenerator.briefErrorAndExit("Error writing file: " +
				fileName + File.separator + e.getMessage());
		}
	}
	
	/**
	 * This is a simple method that will read in a text file and put each line in a
	 * string and put all the lines in an ArrayList.  The user is cautioned not
	 * to open extremely large files with this method.
	 * @param fileName Name of the text file to load into the ArrayList<String>.
	 * @return An ArrayList containing strings of each line in the file. 
	 */
	public static ArrayList<String> getLinesFromTextFile(String fileName){
		String line = null;
		BufferedReader br;
		ArrayList<String> lines = new ArrayList<String>();
		try{
			br = new BufferedReader(new FileReader(fileName));
			
			while((line = br.readLine()) != null){
				lines.add(line);
			}
			
			br.close();
		}
		catch(FileNotFoundException e){
			MessageGenerator.briefErrorAndExit("ERROR: Could not find file: " + fileName);
		} 
		catch(IOException e){
			MessageGenerator.briefErrorAndExit("ERROR: Could not read from file: " + fileName);
		}
		
		return lines;
	}
	
	/**
	 * Gets the last n number of lines from a text file and returns them.
	 * @param fileName Name of the text file
	 * @param n Number of last lines to get
	 * @return A list of the last n lines in the text file
	 */
	public static List<String> getLastNLinesFromTextFile(String fileName, int n){
		if(n <= 0) return Collections.emptyList();
		ArrayList<String> lines = getLinesFromTextFile(fileName);
		ArrayList<String> toReturn = new ArrayList<>();
		for(int i=(lines.size()-(n+1)); i < lines.size(); i++){
			toReturn.add(lines.get(i));
		}
		return toReturn;
	}
	
	public static ArrayList<String> getLinesFromInputStream(InputStream in){
		String line = null;
		ArrayList<String> lines = new ArrayList<String>();
		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		try {
			while((line = br.readLine()) != null) {
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
	public static String removeFileExtension(String fileName){
		int endIndex = fileName.lastIndexOf('.');
		if(endIndex != -1){
			return fileName.substring(0, endIndex);
		}
		else{
			return fileName;
		}
	}
	
	/**
	 * Creates a directory in the current path called dirName.
	 * @param dirName Name of the directory to be created.
	 * @return True if the directory was created or already exists, false otherwise.
	 */
	public static boolean makeDir(String dirName){
		File dir = new File(dirName); 
		if(!(dir.exists())){
			return dir.mkdir();
		}
		return true;
	}
	
	/**
	 * Creates a directory in the current path called dirName.
	 * @param dirName Name of the directory to be created.
	 * @return True if the directory and implicit parent directories were created, false otherwise.
	 */
	public static boolean makeDirs(String dirName){
		return new File(dirName).mkdirs();
	}
	
	/**
	 * Gets the size of the file in bytes.
	 * @param fileName Name of the file to get the size of.
	 * @return The number of bytes used by the file.
	 */
	public static long getFileSize(String fileName){
		return new File(fileName).length();
	}
	
	/**
	 * Delete the file/folder in the file system called fileName 
	 * @param fileName Name of the file to delete
	 * @return True for successful deletion, false otherwise.
	 */
	public static boolean deleteFile(String fileName){
	    // A File object to represent the filename
	    File f = new File(fileName);

	    // Make sure the file or directory exists and isn't write protected
	    if (!f.exists()){
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
	public static boolean deleteFolderContents(String path){
		File currDirectory = new File(path);
		if(currDirectory.exists()){
			try {
				for(File file : currDirectory.listFiles()){
					if(file.isDirectory()){
						if(!deleteFolder(file.getCanonicalPath())){
							return false;
						}
					}
					else{
						if(!deleteFile(file.getCanonicalPath())){
							return false;
						}
					}
				}				
			}
			catch(IOException e){
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
	public static boolean deleteFolder(String folderName){
		// A file object to represent the filename
		File f = new File(folderName);
		
		if(!f.exists() || !f.isDirectory()){
			MessageGenerator.briefError("WARNING: Attempted to delete folder " + folderName + " but it wasn't there.");
			return false;
		}
		
		for(File i: f.listFiles()){
			if(i.isDirectory()){
				deleteFolder(i.getAbsolutePath());
			}else if(i.isFile()){
				if(!i.delete()){
					throw new IllegalArgumentException("Delete: deletion failed: " + i.getAbsolutePath());
				}
			}
		}
		return deleteFile(folderName);
	}

	public static boolean renameFile(String oldFileName, String newFileName){
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
	public static boolean copyFile(String src, String dst){
	    FileChannel inChannel = null;
	    FileChannel outChannel = null;
		try {
			File srcFile = new File(src);
			FileInputStream fis = new FileInputStream(srcFile); 
			inChannel = fis.getChannel();
			if(new File(dst).isDirectory()){
				dst = dst + File.separator + srcFile.getName();
			}
			FileOutputStream fos = new FileOutputStream(new File(dst)); 
			outChannel = fos.getChannel();
			inChannel.transferTo(0, inChannel.size(), outChannel);
			fis.close();
			fos.close();
		} 
		catch (FileNotFoundException e){
			e.printStackTrace();
			MessageGenerator.briefError("ERROR could not find/access file(s): " + src + " and/or " + dst);
			return false;
		} 
		catch (IOException e){
			MessageGenerator.briefError("ERROR copying file: " + src + " to " + dst);
			return false;
		}
		finally {
			try {
				if(inChannel != null)
					inChannel.close();
				if(outChannel != null) 
					outChannel.close();
			} 
			catch (IOException e) {
				MessageGenerator.briefError("Error closing files involved in copying: " + src + " and " + dst);
				return false;
			}
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
	public static boolean copyFolder(String srcDirectoryPath, String dstDirectoryPath, boolean recursive){
		File srcDirectory = new File(srcDirectoryPath);
		File dstDirectory = new File(dstDirectoryPath + File.separator + srcDirectory.getName());
		if(srcDirectory.exists() && srcDirectory.isDirectory()){
			if(!dstDirectory.exists()){
				dstDirectory.mkdirs();
			}
			for(File file : srcDirectory.listFiles()){
				if(!file.isDirectory()){
					if(!copyFile(file.getAbsolutePath(), dstDirectory.getAbsolutePath() + File.separator + file.getName())){
						return false;
					}
				}
				else if(file.isDirectory() && recursive){
					if(!copyFolder(file.getAbsolutePath(), dstDirectory.getAbsolutePath(), true)){
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
	public static boolean copyFolderContents(String src, String dst, boolean recursive){
		File srcDirectory = new File(src);
		File dstDirectory = new File(dst);
		if(srcDirectory.exists() && srcDirectory.isDirectory()){
			if(!dstDirectory.exists()){
				MessageGenerator.briefError("ERROR: Could find destination directory " + dstDirectory.getAbsolutePath());
			}
			for(File file : srcDirectory.listFiles()){
				if(!file.isDirectory()){
					if(!copyFile(file.getAbsolutePath(), dstDirectory.getAbsolutePath() + File.separator + file.getName())){
						return false;
					}
				}
				else if(file.isDirectory() && recursive){
					if(!copyFolder(file.getAbsolutePath(), dst, true)){
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
	public static void errorIfFileDoesNotExist(String fileName){
		File f = new File(fileName);
		if(!f.exists())
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
	public static String getRapidWrightPath(){
		String path = System.getenv(RAPIDWRIGHT_VARIABLE_NAME);
		if(path == null){
			final File f = new File(FileTools.class.getProtectionDomain().getCodeSource().getLocation().getPath());
			if(f.isDirectory()){
				File rootFolder = f.getParentFile();
				for(String dir : rootFolder.list()){
					if(dir.equals(DATA_FOLDER_NAME)) {
						MessageGenerator.briefMessage("WARNING: " + RAPIDWRIGHT_VARIABLE_NAME +
							" is not set.  Proceeding with inferred location from Java execution path: " + 
							rootFolder.getAbsolutePath());
						return rootFolder.getAbsolutePath();
					}
				}
			}
			// This appears to be a jar file.
			return null;
		}
		if(path.endsWith(File.separator)){
			path.substring(0, path.length()-1);
		}
		return path;
	}
	
	/**
	 * Identifies the proper location for a RapidWright data resource and
	 * returns an opened InputStream to that resource.  This works regardless
	 * if RAPIDWRIGHT_PATH is set or not, if the resource is inside a jar or
	 * not.
	 * @param name Name of the resource.
	 * @return An InputStream to the resource or null if it could not be found.
	 */
	public static InputStream getRapidWrightResourceInputStream(String name){
		String rwPath = getRapidWrightPath();
		if(rwPath != null){
			try {
				File resourceFile = new File(rwPath + File.separator + name);
				if(resourceFile.exists()) {
					return new FileInputStream(resourceFile);
				} else {
					System.err.println("WARNING: " + RAPIDWRIGHT_VARIABLE_NAME + " is set to " + rwPath
							+ " but the resource " + name + " is not present, will attempt to load from jar...");
				}
			} catch (FileNotFoundException e) {
				System.err.println("ERROR: Failed to find RapidWright resource file "
						+ rwPath + File.separator + name + ". Please check the installation path "
						+ "and/or RAPIDWRIGHT_PATH environment variable.");
				e.printStackTrace();
				return null;
			}
		}
		// Try getting it from inside the jar (classpath)
		return FileTools.class.getResourceAsStream("/" + name.replace(File.separator, "/"));
	}
	
	/**
	 * Checks if a particular RapidWright file or jar resource exists. 
	 * This will prioritize checking first in the location indicated by the 
	 * RAPIDWRIGHT_PATH environment variable, then check in the location from
	 * the running class files.  
	 * @param name Name of the RapidWright resource file.  
	 * @return True if the resource exists, false otherwise.
	 */
	public static boolean checkIfRapidWrightResourceExists(String name) {
		String rwPath = getRapidWrightPath();
		if(rwPath != null){
			boolean foundFile = new File(rwPath + File.separator + name).exists();
			if (foundFile) return foundFile;
		}
		return null != FileTools.class.getResourceAsStream("/" + name.replace(File.separator, "/"));
	}
	
	/**
	 * Finds and returns a file name that can be read for the corresponding
	 * RapidWright resource. 
	 * @param name Name of the RapidWright resource
	 * @return The full file name path, or null if one could not be found.
	 */
	public static String getRapidWrightResourceFileName(String name){
		String rwPath = getRapidWrightPath();
		if(rwPath == null){
			// Looks like we may be running from a jar, attempt to extract needed files from jar
			unPackSupportingJarData();
			// Try again
			rwPath = getRapidWrightPath();
		}
		if(rwPath != null){
			return rwPath + File.separator + name;
		}
		
		return null;
	}
	
	/**
	 * Gets the HDIPrimitivesResource (FileTools.HDI_PRIMITIVES_FILE_NAME) 
	 * as an InputStream.  
	 * @return Returns the input stream for the HDI Primitives resource 
	 * @deprecated
	 */
	public static InputStream getHDIPrimitivesResourceStream(){
		return getRapidWrightResourceInputStream(HDI_PRIMITIVES_FILE_NAME);
	}
	
	/**
	 * Gets an input stream to the file containing valid cell placements of the hdi primitives.
	 * @return An input stream to the valid cell placements map file.
	 * @deprecated
	 */
	public static InputStream getValidCellPlacementsResourceStream(){
		return getRapidWrightResourceInputStream(VALID_CELL_PLACEMENTS_FILE_NAME);
	}

	/**
	 * Gets an input stream to the file containing valid cell placements of the hdi primitives.
	 * @return An input stream to the valid cell placements map file.
	 */
	public static InputStream getUnisimDataResourceStream(){
		return getRapidWrightResourceInputStream(UNISIM_DATA_FILE_NAME);
	}
	
	/**
	 * Gets an input stream to the file containing a CSV file of valid parts for RapidWright.
	 * @return An input stream to the valid cell placements map file.
	 */
	public static InputStream getPartDumpResourceStream(){
		return getRapidWrightResourceInputStream(PART_DUMP_FILE_NAME);
	}

	
	/**
	 * Gets and returns the path of the folder where the part files resides for deviceName.
	 * @param deviceName Name of the part to get its corresponding folder path.
	 * @return The path of the folder where the parts files resides.
	 */
	public static String getPartFolderResourceName(Part part){
		FamilyType ft = part.getRevision().equals("") ? part.getArchitecture() : part.getFamily();
		return 	DEVICE_FOLDER_NAME + 
				File.separator + 
				ft.toString().toLowerCase() + 
				File.separator;
	}
	
	/**
	 * Gets and returns the path of the folder where the family type resides.
	 * @param familyType The family type corresponding folder path.
	 * @return The path of the folder where the parts of familyType reside.
	 */
	public static String getPartFolderResourceName(FamilyType familyType){
		familyType = PartNameTools.getArchitectureFromFamilyType(familyType);
		return 	DEVICE_FOLDER_NAME + 
				File.separator + 
				familyType.toString().toLowerCase() + 
				File.separator;
	}
	
	public static String getDeviceFolderResourceName(){
		return DEVICE_FOLDER_NAME;		
	}
	
	public static String getDeviceResourceName(Part part){
		return getPartFolderResourceName(part) + part.getDevice() + DEVICE_FILE_SUFFIX;
	}
	
	/**
	 * Checks for all device files present in the current RapidWright family path and returns
	 * a list of strings of those part names available to be used by the tool within the specified family.
	 * @param type The specified family type.
	 * @return A list of available Xilinx parts for the given family type 
	 */
	public static List<String> getAvailableParts(FamilyType type){
		ArrayList<String> allParts = new ArrayList<String>();
		String pattern = DEVICE_FILE_SUFFIX;
		File dir = new File(FileTools.getDeviceFolderResourceName() + File.separator + type.toString().toLowerCase());
		if(!dir.exists()){
			MessageGenerator.briefError("ERROR: No part files exist.  Please download "
					+ "see RapidWright installation instructions for help.");
			return Collections.emptyList();
		}
		for(String part : dir.list()){
			if(part.endsWith(pattern)){
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
		if(!dir.exists()){
			MessageGenerator.briefError("ERROR: No part files exist.  Please download "
					+ "see RapidWright installation instructions for help.");
			return Collections.emptyList();
		}
		for(String partFamily : dir.list()){
			if(PART_DB_PATH.endsWith(partFamily)) continue;
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
	public static String getTimeString(){
		SimpleDateFormat formatter = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy");
		return formatter.format(new java.util.Date());
	}

	/**
	 * Gets and returns the file separator character for the given OS
	 */
	public static String getDirectorySeparator(){
		if(FileTools.cygwinInstalled()){
			return "/";
		}
		else{
			return File.separator;
		}
	}

	/**
	 * Checks if Cygwin is installed on the system
	 */
	public static boolean cygwinInstalled(){
		return System.getenv("CYGWIN") != null;
	}
	
	public static void writeObjectToKryoFile(String fileName, Object o){
		writeObjectToKryoFile(fileName, o, false);
	}
	
	public static void writeObjectToKryoFile(String fileName, Object o, boolean writeClass){
		Kryo kryo = getKryoInstance();
		Output out;
		try {
			out = new Output(new FileOutputStream(fileName));
			if(writeClass)
				kryo.writeClassAndObject(out, o);
			else
				kryo.writeObject(out, o);
			out.close();
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
	}
	
	public static Object readObjectFromKryoFile(String fileName){
		Kryo kryo = getKryoInstance();
		Object o = null;
		try {
			Input i = new Input(new FileInputStream(fileName));
			o = kryo.readClassAndObject(i);
			i.close();
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		return o;
	}
	
	public static <T> T readObjectFromKryoFile(String fileName, Class<T> c){
		try {
			return readObjectFromKryoFile(new FileInputStream(fileName), c);
		} catch (FileNotFoundException e) {
			return null;
		}
	}

	public static <T> T readObjectFromKryoFile(InputStream in, Class<T> c){
		Kryo kryo = getKryoInstance();
		T o = null;
		Input i = new Input(in);
		o = kryo.readObject(i, c);
		i.close();
		return o;
	}

	
	public static Kryo getKryoInstance(){
		if(kryo == null){
			kryo = new Kryo();
		}
		return kryo;
	}

	/**
	 * Is fileName1 newer than fileName2?
	 * @param fileName1
	 * @param fileName2
	 * @return
	 */
	public static boolean isFileNewer(String fileName1, String fileName2){
		long time1 = new File(fileName1).lastModified();
		long time2 = new File(fileName2).lastModified();
		return time1 >= time2;
	}
	
	@SuppressWarnings("resource")
	public static InputStream getInputStreamFromZipOrDcpFile(String zipOrDcpFileName, String fileEndsWith){
		ZipFile zip = null;
		ZipEntry match = null;
		InputStream i = null;
		try {
			zip = new ZipFile(zipOrDcpFileName);
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while(entries.hasMoreElements()){
				ZipEntry entry = entries.nextElement();
				if(entry.getName().endsWith(fileEndsWith)){
					if(match != null){
						throw new RuntimeException("ERROR: Found 2 or more matching files in zip/dcp file: " +
								zipOrDcpFileName + " with ending: '" + fileEndsWith + "'");
					}
					match = entry;
				}
			}
			if(match == null) return null;
			i = zip.getInputStream(match);
		} catch (IOException e) {
			e.printStackTrace();
		} 
		return i;
	}
	
	public static void close(InputStream is) {
	    try {
	        if(is != null) is.close();
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
	
	public static void close(ZipFile zip) {
	    try {
	        if(zip != null) zip.close();
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}

	/**
	 * Generic method to run a command in the current shell and return its standard output and standard error
	 * @param command The command to run
	 * @return A list of standard output lines followed by the standard error lines
	 */
	public static ArrayList<String> getCommandOutput(String[] command){
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
	public static Integer runCommand(String command, boolean verbose){
		if(verbose) System.out.println(command);
		int returnValue = 0;
		try {
			Process p = Runtime.getRuntime().exec(command);
			StreamGobbler input = new StreamGobbler(p.getInputStream(), verbose);
			StreamGobbler err = new StreamGobbler(p.getErrorStream(), verbose);
			input.start();
			err.start();
			try {
				returnValue = p.waitFor();
				p.destroy();
			} catch (InterruptedException e){
				e.printStackTrace();
				MessageGenerator.briefError("ERROR: The command was interrupted: \"" + command + "\"");
				return null;
			}
		} catch (IOException e){
			e.printStackTrace();
			MessageGenerator.briefError("ERROR: In running the command \"" + command + "\"");
			return null;
		}
		return returnValue;
	}
	
	/**
	 * A generic method to run a command from the system command line.
	 * @param command The command to execute.  This method blocks until the command finishes.
	 * @param verbose When true, it will first print to std.out the command and also all of the 
	 * command's output (both std.out and std.err) to std.out.  
	 * @return The return value of the process if it terminated, if there was a problem it returns null.
	 */
	public static Integer runCommand(List<String> command, String logFileName){
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
			p.destroyForcibly();
		}
		return returnVal;
		/*
		try {
			Process p = Runtime.getRuntime().exec(command);
			StreamGobbler input = new StreamGobbler(p.getInputStream(), false);
			StreamGobbler err = new StreamGobbler(p.getErrorStream(), false);
			input.start();
			err.start();
			try {
				returnValue = p.waitFor();
				p.destroy();
			} catch (InterruptedException e){
				e.printStackTrace();
				MessageGenerator.briefError("ERROR: The command was interrupted: \"" + command + "\"");
				return null;
			}
		} catch (IOException e){
			e.printStackTrace();
			MessageGenerator.briefError("ERROR: In running the command \"" + command + "\"");
			return null;
		}
		return returnValue;*/
	}
	
	public static String getUniqueProcessAndHostID(){
		return ManagementFactory.getRuntimeMXBean().getName();
	}
	
	/**
	 * Uses a similar algorithm to diff to determine if the file
	 * is a binary file by looking at the first 4k bytes to see if
	 * there are any null characters.  If so, it is considered binary.
	 * @param fileName Name of the file to check.
	 * @return True if the file is considered binary, false otherwise.
	 */
	public static boolean isFileBinary(String fileName){
		try {
			return isDataBinary(new BufferedInputStream(new FileInputStream(fileName)));
		} catch (FileNotFoundException e) {
			throw new RuntimeException("ERROR: Trying to read file " + fileName + " and it was not found.");
		}
	}
	
	private static final int BINARY_CHECK_LENGTH = 8192;
	private static byte[] binaryCheckData; 

	public static boolean isDataBinary(InputStream is){
		if(!is.markSupported()){
			throw new RuntimeException("ERROR: Cannot determine if input stream is binary without mark support.");
		}
		if(binaryCheckData == null){
			binaryCheckData = new byte[BINARY_CHECK_LENGTH];
		}
		boolean isBinary = false;
		try {
			is.mark(BINARY_CHECK_LENGTH+1);
			int count = is.read(binaryCheckData);
			for(int i=0; i < count; i++){
				if(binaryCheckData[i] == 0x00) {
					isBinary = true;
					break;
				}
			}
			is.reset();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return isBinary;
	}
	
	/**
	 * Runs the provided command (arguments must be separate) and gathers the 
	 * standard output followed by the standard error.
	 * @param includeError Option to set if the returned list includes the standard error
	 * at the end.
	 * @param command The command with arguments separated.
	 * @return The list of lines of output from the command.
	 */
	public static List<String> execCommandGetOutput(boolean includeError, String... command){
		ProcessBuilder pb = new ProcessBuilder(command);
		Process p = null;
		String line = null;
		List<String> output = new ArrayList<>();

		try {
			p = pb.start();
			p.waitFor();  // wait for process to finish then continue.
			BufferedReader bri = new BufferedReader(new InputStreamReader(p.getInputStream()));
			while ((line = bri.readLine()) != null) {
			    output.add(line);
			}
			if(includeError){
				BufferedReader bre = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				while ((line = bre.readLine()) != null) {
				    output.add(line);
				}
			}

		} catch (IOException e1) {
			e1.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}  

		return output;
	}
	
	/**
	 * Checks if a particular executable is available on the current path
	 * @param execName Name of the executable (ex: vivado)
	 * @return True if the executable is available in the current path, false otherwise.
	 */
	public static boolean isExecutableOnPath(String execName){
		List<String> lines = execCommandGetOutput(true, isWindows() ? "where" : "which",execName);
		for(String line : lines){
			if(line.startsWith("which:")) return false;
			if(line.contains("INFO: Could not find files")) return false;
			if(line.contains(File.separator + execName)) return true;
		}
		return false;
	}
	
	/**
	 * Checks if vivado is available on current PATH (uses unix 'which' or windows 'where').  
	 * @return true if vivado is on current PATH, false otherwise.
	 */
	public static boolean isVivadoOnPath(){
		return isExecutableOnPath("vivado");
	}
	
	/**
	 * Checks that Vivado is on current PATH and returns true if RapidWright should be
	 * compatible with the version of vivado available.  Vivado versions that match or
	 * are older than RapidWright are presumed compatible.
	 */
	public static boolean isVivadoCompatible(){
		if(isVivadoOnPath()){
			List<String> lines = execCommandGetOutput(true, "vivado", "-version");
			for(String line : lines){
				if(line.startsWith("Vivado ")){
					int dot = line.indexOf('.');
					int year = Integer.parseInt(line.substring(line.indexOf(" v")+2,dot));
					int quarter = Integer.parseInt(line.substring(dot+1, dot+2));
					if(year > Device.RAPIDWRIGHT_YEAR_VERSION){
						return false;
					}
					if(year == Device.RAPIDWRIGHT_YEAR_VERSION && quarter > Device.RAPIDWRIGHT_QUARTER_VERSION){
						return false;
					}
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * Gets the full path to the vivado executable if it is set in the PATH
	 * environment variable. Works for Windows and Linux.
	 * @return Full path to vivado executable, or throws RuntimeException if not found.  
	 */
	public static String getVivadoPath(){
		String[] cmd = new String[]{isWindows() ? "where" : "which",isWindows() ? "vivado.bat" : "vivado"};
		String output = execCommandGetOutput(true, cmd).get(0);
		if(output.contains("INFO:") || output.contains("which: no")){
			throw new RuntimeException("ERROR: Couldn't find vivado on PATH");
		}
		return output.trim().replace("\\", "/");
	}
	
	private static String currentOS = null;
	
	public static String getOSName(){
		if(currentOS == null){
			currentOS = System.getProperty("os.name");
		}
		return currentOS;
	}
	
	public static boolean isWindows(){
		return getOSName().startsWith("Windows");
	}
	
	public static void unzipFile(String zipFileName, String destDirectory){
		File destDir = new File(destDirectory);
		byte[] buffer = new byte[1024*16];
		if(!destDir.exists()){
			destDir.mkdirs();
		}
		try {
			ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFileName));
			ZipEntry e = null;
			while((e = zin.getNextEntry()) != null){
				String destFilePath = destDirectory + File.separator + e.getName();
				if(e.isDirectory()){
					new File(destFilePath).mkdirs();
				}else{
					File currFile = new File(destFilePath);
					String parentName = currFile.getParentFile().getAbsolutePath();
					makeDirs(parentName);
					BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(destFilePath));
					int read = 0;
					while( (read = zin.read(buffer)) != -1){
						bos.write(buffer,0,read);
					}
					bos.close();
				}
			}
			zin.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}
	
	private static FilenameFilter dcpFilter = new FilenameFilter() {
		@Override
		public boolean accept(File dir, String name){
			return name.toLowerCase().endsWith(".dcp");
		}
	};
	
	/**
	 * Gets a filename filter for DCP files (ends with .dcp).
	 * @return The DCP filename filter
	 */
	public static FilenameFilter getDCPFilenameFilter(){
		return dcpFilter;
	}
	
	/**
	 * Creates a custom filename filter that uses the provided
	 * matches string on the name of the file (not the path).  
	 * @param matches Uses the String.matches() to match filename.
	 * @return The newly created filename filter object.
	 */
	public static FilenameFilter getFilenameFilter(String matches){
		FilenameFilter filter = new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name){
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
	public static boolean unPackSupportingJarData(){
		for(String folderName : FileTools.UNPACK_FOLDERS){
			if(!folderCheck(folderName)) return false;
			try{
				CodeSource src = Device.class.getProtectionDomain().getCodeSource();
				if(src == null) {
					MessageGenerator.briefError("Couldn't locate code source domain");
					return false;
				}
				URL jar = src.getLocation();
				ZipInputStream zip = new ZipInputStream(jar.openStream());
				ZipEntry e;
				byte[] buffer = new byte[1024];
				while((e = zip.getNextEntry()) != null){
					String name = e.getName();
					if(name.startsWith(folderName)){
						if(!e.isDirectory()){
							System.out.println("Unpacking " + e.getName());
							File newFile = new File(e.getName());
							new File(newFile.getParent()).mkdirs();
							FileOutputStream fos = new FileOutputStream(newFile);
							
							int len = 0;
							while((len = zip.read(buffer)) > 0){
								fos.write(buffer, 0, len);
							}
							fos.close();
						}
					}
				}
				zip.close();
			} catch(IOException e){
				e.printStackTrace();
				return false;
			}
		}
		return true;
	}

	/** 
	 * Check if file/folder name is available to be used
	 * @param name Name of the file/directory to check
	 * @return True if the the file/folder name is free (unused), false otherwise.
	 */
	public static boolean folderCheck(String name){
		if(new File(name).exists()){
			MessageGenerator.briefError("File/folder ./"+name+"/ already exists.");
			return false;
		}
		return true;
	}
	
	public static void main(String[] args) {
		if(args[0].equals("--get_vivado_path"))
			System.out.println(getVivadoPath());
	}
}

