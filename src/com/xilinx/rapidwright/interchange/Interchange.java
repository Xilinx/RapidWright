/* 
 * Copyright (c) 2020 Xilinx, Inc. 
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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;
import org.capnproto.ReaderOptions;
import org.capnproto.Serialize;
import org.capnproto.SerializePacked;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;

public class Interchange {

	/** Flag indicating use of Packed Cap'n Proto Serialization */ 
	public static boolean IS_PACKED = false;
	/** Fla indicating that files are gziped on output */
	public static boolean IS_GZIPPED = true;

	/**
	 * Common method to write out Interchange files
	 * @param fileName Name of the output file to write
	 * @param message The message to write to the file
	 * @throws IOException
	 */
	public static void writeInterchangeFile(String fileName, MessageBuilder message) throws IOException {
		WritableByteChannel wbc = null;
		
        if(IS_GZIPPED) {
        	GZIPOutputStream go = new GZIPOutputStream(new FileOutputStream(fileName));
        	wbc = Channels.newChannel(go);
        	
        }else {
        	FileOutputStream fo = new java.io.FileOutputStream(fileName);
        	wbc = fo.getChannel();
        }
        if(IS_PACKED) {
        	SerializePacked.writeToUnbuffered(wbc, message);
        }else {
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
		if(IS_GZIPPED) {
			GZIPInputStream gis = new GZIPInputStream(new FileInputStream(fileName));
			channel = Channels.newChannel(gis);
		}else {
			FileInputStream fis = new java.io.FileInputStream(fileName);
			channel = fis.getChannel();
		}
		MessageReader readMsg = null;
		if(IS_PACKED) {
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

	private static void benchmarkDCPvsInterchange(String dcpFileName, String edifFileName) throws IOException {
		String title = dcpFileName + " IS_PACKED=" + IS_PACKED + " IS_GZIPPED=" + IS_GZIPPED;
		CodePerfTracker t = new CodePerfTracker(title);
		t.useGCToTrackMemory(true);
		t.start(READ_DCP);
		Design design = edifFileName != null ? 
				Design.readCheckpoint(dcpFileName, edifFileName, CodePerfTracker.SILENT) : 
			    Design.readCheckpoint(dcpFileName, CodePerfTracker.SILENT);
		t.stop().start(WRITE_LOGICAL_NETLIST);
		String logNetlistFileName = dcpFileName.replace(".dcp", ".netlist");
		LogNetlistWriter.writeLogNetlist(design.getNetlist(), logNetlistFileName);
		t.stop().start(WRITE_PHYSICAL_NETLIST);
		String physNetlistFileName = dcpFileName.replace(".dcp", ".phys");
		PhysNetlistWriter.writePhysNetlist(design, physNetlistFileName);
		t.stop();
		design = null;
		System.gc();
		t.start(READ_LOGICAL_NETLIST);
		EDIFNetlist netlist = LogNetlistReader.readLogNetlist(logNetlistFileName);
		t.stop().start(READ_PHYSICAL_NETLIST);
		Design designReturn = PhysNetlistReader.readPhysNetlist(physNetlistFileName, netlist);
		t.stop().start(WRITE_DCP);
		String dcpOutputFileName = dcpFileName.replace(".dcp", "_rt.dcp");
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
	}
	
	
	private static double printFileSize(String title, String fileName) {
		double fileSize = FileTools.getFileSize(fileName)/(1024.0*1024.0); 
		System.out.printf(title + "_FILE_SIZE: %10.3fMBs\n", fileSize);
		return fileSize;
	}
	
	public static void main(String[] args) throws IOException {
		if(args.length < 1 || args.length > 2) {
			System.out.println("USAGE: <input DCP> [input EDIF]");
			return;
		}
		benchmarkDCPvsInterchange(args[0], args.length == 2 ? args[1] : null);
	}
}
