/*
 *
 * Copyright (c) 2022 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.MapSerializer;
import com.xilinx.rapidwright.bitstream.Bitstream;
import com.xilinx.rapidwright.bitstream.BlockType;
import com.xilinx.rapidwright.bitstream.ConfigArray;
import com.xilinx.rapidwright.bitstream.ConfigRow;
import com.xilinx.rapidwright.bitstream.FAR;
import com.xilinx.rapidwright.bitstream.Frame;
import com.xilinx.rapidwright.bitstream.OpCode;
import com.xilinx.rapidwright.bitstream.Packet;
import com.xilinx.rapidwright.bitstream.RegisterType;
import com.xilinx.rapidwright.tests.CodePerfTracker;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;


/**
 * Replace or extract a section of a partial bitstream to be used in another bitstream.
 *
 * One use case is to extract a "NO OP" bitstream of a hard block column.
 * The "NO OP" can be later used to replace the section in a relocated bitstream.
 *
 * A particular example is a design with 3 PR regions on ZCU7. The 3 PR are align vertically to the right of the device.
 * Most of the resource are the same among the PRs except one column where oen PR cover HDIO, another CONFIG and the other PCIE.
 * A partial bitstream is generated (by Vivado) for the first PR and will later relocated to the other two PRs.
 * The config bits over HDIO is not applicable to either CONFIG or PCIE, although it cause no harm in a short duration.
 * It was observed that the config bits over CONFIG and PCIE are the same and in addition they are independent of a design.
 * Thus, a partial bitstream can be written for a PR covering either of them so that a "NO OP" config bits can be extracted.
 * The extracted bits is later used to replace corresponding bits in the bitstream generated with HDIO before subsequent relocation.
 * In particular, the section covering a hard block column, eg., PCIE, that are
 */
public class ReplaceFrameData {

	/** "NO OP" bits for config row and column, collectively called Address */
	private Map<Address, int[]> templateData;

	public ReplaceFrameData() {
		templateData = new HashMap<>();
	}

	/**
	 * Extract "NO OP" bits from the given bitstream according to the example platform.
	 * The bits must be save to be later used in different sessions.
	 * @param b     The bitstream to be replaced
	 */
	public void extractForExamplePlatform(Bitstream b) {
		ReplacementSpec spec = getExampleSpec();
		extract(b, spec.rows, spec.cols);
		// Extract from row 0,1, to be used at row 4,5
		incTemplateDataRowIndex(spec.templateToTargetRowOffset);
	}

	/**
	 * Replace "NO OP" bits on the bitstream according to the given example platform.
	 * The bits must be save to be later used in different sessions.
	 * @param b     The bitstream to be replaced
	 */
	public void replaceForExamplePlatform(Bitstream b) {
		ReplacementSpec spec = getExampleSpec();
		List<Integer> rows = spec.rows;
		// Need to adjust because the spec is set for extraction, not for replacement
		for (int i = 0; i < rows.size(); i += 1) {
			rows.set(i, rows.get(i) + spec.templateToTargetRowOffset);
		}
		replace(b, rows, spec.cols);
	}

	/**
	 * Extract "NO OP" bits from the given config rows and columns of the given bitstream.
	 * The bits must be save to be later used in different sessions.
	 * @param b     The bitstream to be replaced
	 * @param rows  The list of config rows
	 * @param cols  The list of config cols
	 */
	public void extract(Bitstream b, List<Integer> rows, List<Integer> cols) {
		FrameDataProcessor processor = new FrameDataProcessor(b);
		processor.processPackets(rows, cols,
				(frameData, addr, data, frameIdx, wordsPerFrame) -> {
					updateTemplateData(frameData, addr, data, frameIdx, wordsPerFrame);
				}
		);
		templateData = processor.getFrameData();
	}

	/**
	 * Replace "NO OP" bits to the given config rows and columns of the given bitstream.
	 * Assume that the bits is either extracted or loaded.
	 * @param b     The bitstream to be replaced
	 * @param rows  The list of config rows
	 * @param cols  The list of config cols
	 */
	public void replace(Bitstream b, List<Integer> rows, List<Integer> cols) {
		FrameDataProcessor processor = new FrameDataProcessor(b);
		processor.setFrameData(templateData);
		processor.processPackets(rows, cols,
				(frameData, addr, data, frameIdx, wordsPerFrame) -> {
					updatePacketData(frameData, addr, data, frameIdx, wordsPerFrame);
				}
		);
	}

	/**
	 * Save the "NO OP" frame data to a file
	 */
	public void save(String filename) {
		try {
			Output output = new Output(new FileOutputStream(filename));
			Kryo kryo = new Kryo();
			kryo.register(HashMap.class, new MapSerializer());
			kryo.register(Address.class);
			kryo.register(int[].class);
			kryo.writeClassAndObject(output, templateData);
			output.close();
		} catch (IOException e) {
			System.out.println("Cannot save to file " + filename);
			e.printStackTrace();
		}
	}

	/**
	 * Load the "NO OP" frame data from a file.
	 */
	public void load(String filename) {
		try {
			Input input = new Input(new FileInputStream(filename));
			Kryo kryo = new Kryo();
			kryo.register(HashMap.class, new MapSerializer());
			kryo.register(Address.class);
			kryo.register(int[].class);
			templateData = (Map<Address, int[]>) kryo.readClassAndObject(input);
			input.close();
		} catch (IOException e) {
			System.out.println("Cannot read from file " + filename);
			e.printStackTrace();
		}
	}

	/**
	 * Increase row address of the "NO OP" bits by the number of row specified.
	 * @param inc The number of rows to increment
	 */
	public void incTemplateDataRowIndex(int inc) {
		Map<Address, int[]>  newFrameData = new HashMap<>();
		for (Map.Entry<Address, int[]> entry : templateData.entrySet()) {
			Address addr = entry.getKey();
			Address newAddr = new Address(addr.row + inc, addr.col, addr.minor);
			newFrameData.put(newAddr, entry.getValue());
		}
		templateData = newFrameData;
	}

	/**
	 * An interface to update template or packet data.
	 */
	private interface OpOnFrame {
		/**
		 * Operate on template or packet data.
		 * @param frameData      A map of frame data for some config rows and columns. It can be read or written
		 * @param addr           The current frame address
		 * @param data           The data of the whole packet
		 * @param frameIdx       The frame index in the packet
		 * @param wordsPerFrame  The number of words per frame
		 */
		void operate (Map<Address, int[]> frameData, Address addr, int[] data, int frameIdx, int wordsPerFrame);
	}

	/**
	 * Update the frame data for the given frame.
	 * @param frameData      The frame data to be updated
	 * @param data           The source of data to update frameData
	 */
	private void updateTemplateData(Map<Address, int[]> frameData, Address addr, int[] data, int frameIdx, int wordsPerFrame) {
		frameData.put(addr,Arrays.copyOfRange(data, frameIdx*wordsPerFrame, (frameIdx+1)*wordsPerFrame));
	}

	/**
	 * Update data from frame data.
	 * @param frameData      The source to update data
	 * @param data           The packet data to be updated
	 */
	private void updatePacketData(Map<Address, int[]> frameData, Address addr, int[] data, int frameIdx, int wordsPerFrame) {
		if (frameData.containsKey(addr)) {
			System.arraycopy(frameData.get(addr),0,data,frameIdx*wordsPerFrame,wordsPerFrame);
		}
	}

	/**
	 * Represent all info necessary for extraction and replacement.
	 */
	private class ReplacementSpec {
		final List<Integer> rows;
		final List<Integer> cols;
		final int templateToTargetRowOffset;
		public ReplacementSpec(List<Integer> rows, List<Integer> cols, int offset) {
		   this.rows = rows;
		   this.cols = cols;
		   this.templateToTargetRowOffset = offset;
		}
	}


	/**
	 * Get list of target rows and cols to extract from bitstream built at row 0
	 */
	private ReplacementSpec getExampleSpec() {
		int row = 0; // Either 0 or 2 because it was verified that the template at RP0 and RP1 are the same for this example.
		List<Integer> rows = new ArrayList<Integer>() {{add(row);add(row+1);}};
		List<Integer> cols = new ArrayList<Integer>() {{add(191);add(192);add(193);}};
		int offsetFromRP0ToRP2 = 4;
		return new ReplacementSpec(rows, cols, offsetFromRP0ToRP2);
	}


   //------------------------------ Helper --------------------------------------


	/**
	 * Represent frame address
	 */
	// Without static, can't serialize
	private static class Address {
		final int row;
		final int col;
		final int minor;
		public Address(int row, int col, int minor) {
			this.row   = row;
			this.col   = col;
			this.minor = minor;
		}

		// Need by serialization
		public Address() {
			row = -1;
			col = -1;
			minor = -1;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Address address = (Address) o;
			return row == address.row &&
					col == address.col &&
					minor == address.minor;
		}

		@Override
		public int hashCode() {
			return Objects.hash(row, col, minor);
		}
	}

	/**
	 * Process frame data of the given frame address.
	 * Assumption: a packet contain only frame data of only one row. This is from observation.
	 */
	private class FrameDataProcessor {

		private final int wordsPerFrame;
		private final int columnCounts;
		private final Map<BlockType, int[][]> frameCounts; //array[rowIdx][colIdx]
		private Bitstream b;
		private FAR far;
		private Map<Address, int[]>  frameData;


		private FrameDataProcessor(Bitstream b) {
			this.b = b;
			frameData = new HashMap<>();
			frameCounts = new EnumMap<>(BlockType.class);
			// todo: replace with empty cArray to be faster.  Took 1.3 sec out of 1.8 sec total
			ConfigArray cfgArray = b.getConfigArray();
			far = new FAR(cfgArray);

			wordsPerFrame = Frame.getWordsPerFrame(cfgArray.getDevice().getSeries());
			columnCounts = cfgArray.getConfigRow(BlockType.CLB.getBlockTypeEncoding(), 0, 0).numOfBlocks();

			buildNumFrameArray(cfgArray);
		}

		/**
		 * Process each packet if its content cover the given row and column addresses.
		 * Every minor address of the given column will be extracted.
		 * Every occurrence of the given address will be recorded. Effective, only the last occurrence is recorded.
		 * @param rows  list of row indices to extract
		 * @param cols  list of col indices to extract
		 */
		private void processPackets(List<Integer> rows, List<Integer> cols, OpOnFrame op) {
			Collections.sort(cols);
			TreeSet<Integer> targetColumns = new TreeSet<>(cols);
			Set<Integer> targetRows = new HashSet<>(rows);


			// The column coverage of the packet only be known when
			// 1) ONE FDRI WRITE 186     # type one packet
			// 2) TWO null WRITE 340287  # type two packet that come after ONE FDRI WRITE 0
            // Thus, need to keep FAR from FAR packet.
			for (Packet packet : b.getPackets()) {
				if (packet.isTypeOnePacket() && (packet.getRegister() == RegisterType.FAR)
						&& (packet.getOpCode() == OpCode.WRITE) && (packet.getWordCount() == 1)) {
					far.setFAR(packet.getData()[0]);
				} else if ((
						((packet.getRegister() == RegisterType.FDRI) && packet.isTypeOnePacket())
								||
								(packet.isTypeTwoPacket() && (packet.getOpCode() == OpCode.WRITE))
				)
						&& (packet.getOpCode() == OpCode.WRITE) && (packet.getWordCount() > 0)
				) {
					if (targetRows.contains(far.getRow()) && (far.getBlockType() == BlockType.CLB.getBlockTypeEncoding())) {
						List<Integer> numFrames = getNumFramesInPacket(packet);
						int beginColumn = far.getColumn();
						// Use List to process in order with indexing to match endIndices
						List<Integer> colToExtract = new ArrayList<>(targetColumns.subSet(beginColumn, beginColumn + numFrames.size() - 1));
						if (!colToExtract.isEmpty()) {
							processColumns(far, packet, colToExtract, numFrames, wordsPerFrame, op);
						}
					}
				}
			}
		}

		/**
		 * Process a packet using the given operation for the specified columns.
		 * @param far            The FAR corresponding to the data packet
		 * @param packet         A data packet to operate on
		 * @param colToOperate   A list of target columns
		 * @param numFrames      A list specifying number of frame each column in the packet
		 * @param wordsPerFrame  The number of words per frame
		 * @param op             Operation to perform
		 */
		private void processColumns(FAR far, Packet packet, List<Integer> colToOperate, List<Integer> numFrames, int wordsPerFrame, OpOnFrame op) {
			int firstCol = far.getColumn();
			int row = far.getRow();

			for (int col : colToOperate) {
				int minor = 0;
				if (col == firstCol) {
					// Only the first col can have non-zero minor
					minor = far.getMinor();
				}
				int i = col - firstCol;
				// Copy each minor not numFrames because the minor of the first col can be non-zero.
				// TODO: it can be optimized by do this for the first column, and copy for the whole numFrames for the rest.
				int[] data = packet.getData();
				for (int j = numFrames.get(i); j < numFrames.get(i+1); j++, minor++) {
					Address addr = new Address(row, col, minor);
					op.operate(frameData, addr, data, j, wordsPerFrame);
				}
				packet.setData(data);
			}
		}

		private Map<Address, int[]>  getFrameData() {
			return frameData;
		}

		private void  setFrameData(Map<Address, int[]> frameData) {
			this.frameData = frameData;
		}

		/**
		 * Populate frameCounts.
		 * @param c An empty ConfigArray to get the dimension of the bitstream
		 */
		private void buildNumFrameArray(ConfigArray c) {

			for (BlockType blkType : new ArrayList<BlockType>() {{
				add(BlockType.CLB);
			}}) {
				int array[][] = new int[c.getNumOfConfigRows()][c.getConfigRows().get(0).numOfBlocks()];
				// High order row is for Block RAM
				for (int rowIdx = 0; rowIdx < c.getNumOfConfigRows() / 2; rowIdx++) {
					ConfigRow row = c.getConfigRow(blkType.getBlockTypeEncoding(), 0, rowIdx);
					if (row == null) {
						throw new RuntimeException("Row " + rowIdx + " is NULL");
					} else {
						for (int colIdx = 0; colIdx < row.numOfBlocks(); colIdx++) {
							array[rowIdx][colIdx] = row.getBlock(colIdx).getFrameCount();
						}
						// Increase the overhead to the last column will simplify later processing.
						array[rowIdx][row.numOfBlocks()-1] += ConfigArray.FRAME_OVERHEAD_COUNT_PER_ROW;
					}
				}
				frameCounts.put(blkType, array);
			}
		}

		/**
		 * Mark the beginning index of each column in the packet data array.
		 * @param packet bitstream packet
		 * @return an array whose size is the number of columns + 1.
		 *         The last entry is an extra to make the one-past-end of the last column.
		 */
		private List<Integer> getNumFramesInPacket(Packet packet) {
			int orgFar = far.getCurrentFAR();

			List<Integer> numFrames = new ArrayList<>();
			numFrames.add(0);

			int nFrames = packet.getWordCount() / wordsPerFrame;
			int beginColumn = far.getColumn();
			int beginRow    = far.getRow();

			// First column of the packet can start at any minor address. Thus, process it separately.
			int nFrameInCol = Math.min(nFrames, frameCounts.get(BlockType.CLB)[far.getRow()][far.getColumn()] - far.getMinor());
			int nFrameInc = moveToNextCol(far);
			assert( nFrameInc == nFrameInCol);

			int remaining = nFrames - nFrameInCol;
			int endColumn = beginColumn + 1;
			numFrames.add(nFrameInCol);

			// Invariance: remaining is the number of frame to the beginning of the column specified by endColumn
			//             if remaining < 0, this packet does not cover every frame of the column
			while (remaining > 0 && endColumn < columnCounts) {
				assert(endColumn == far.getColumn());
				assert(beginRow  == far.getRow()); // Assume a packet cover only one row
				nFrameInCol = Math.min(remaining, frameCounts.get(BlockType.CLB)[far.getRow()][endColumn++]);
				numFrames.add(nFrameInCol + numFrames.get(numFrames.size() - 1));
				remaining -= nFrameInCol;

				if (remaining > 0) {
					nFrameInc = moveToNextCol(far);
					assert (nFrameInc == nFrameInCol);
				}
			}

			far.setFAR(orgFar);
			return numFrames;
		}

		/**
		 * Advance the given FAR to the next row.
		 * @param far The FAR to work on. It will be updated
		 * @return The number of frame until the next row
		 */
		private int moveToNextCol(FAR far) {
			int orgCol = far.getColumn();
			int limit = 256; // The most ever observed is 256 frames in a column
			int count = 0;
			for (int j = 0; far.getColumn() == orgCol ; j++, count++) {
				if (j >= limit) {
					throw new RuntimeException("Wrong number of frames at row " + far.getRow() + " col " + far.getColumn());
				}
				far.incrementFAR();
			}
			return count;
		}

	}


	public static void main(String[] args) {

		String usage = String.join(System.getProperty("line.separator"),
			" Replace or Extract frame data of a bitstream. It is used to replace the NOP frame contents for unused hard block columns.",
			"",
			"Usage",
			" ReplaceFrameData [-extract, -replace] -in <bitFile> [-out <bitFile>] -template <file> [-platform <name>, -row <list> -col <list> -offset <int>]",
			"",
			" Examples:",
			"   ReplaceFrameData -extract -in dcpreloc_aes128_pblock_0_partial.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4",
			"   ReplaceFrameData -extract -in dcpreloc_aes128_pblock_0_partial.bit -template template.ser -platform example_platform",
			"   ReplaceFrameData -replace -in dcpreloc_aes128_pblock_2_partial.bit -out out.bit -template template.ser -row 4 5 -col 191 192 193",
			"   ReplaceFrameData -replace -in dcpreloc_aes128_pblock_2_partial.bit -out out.bit -template template.ser -platform example_platform",
			"",
			" There are 4 groups of required arguments, can be in any order.",
			" 1) The operation to perform is either -extract or -replace.",
			" 2) There are two way to specify the frames to operate on.",
			"   a) Specify the predefined platform. Currently, there is only -platform example_platform",
			"   b) For extraction, specify the list of rows and the list of cols, along with the row offset, ie., row to use the template - row to extract the template.",
			"      Setting -row 0 1 -col 191 192 193 means that column 191, 192 and 193 of both row 0 and 1 will be processed.",
			"   c) For replacement, specify the list of rows and the list of cols to be replaced. Only the set of rows and columns that are common with those in the template will be replaced.",
			" 3) The bit file to operate on is specified by -in <bitFile>. For -replace, -out <bitFile> is also required.",
			" 4) The file to store the extract data or to retrieve for replacement is specified by -template <file>"
		);


		// Scan for "-" token
		List<Integer> token = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("-"))
				token.add(i);
		}
		token.add(args.length); // mark the end

		// Set default argument values
		String op = "";
		String inBit = "";
		String outBit = "";
		String file = "";
		String platform = "";
		List<Integer> rows = new ArrayList<>();
		List<Integer> cols = new ArrayList<>();
		int offset = -255;
		int dataIdx = 0;


		Function reportThenExit = txt -> {
			System.out.println(txt);
			System.out.println("For more information please use \"ReplaceFrameData -help\".");
			System.exit(1);
			return 0;
		};


		// Parse arguments
		for (int i = 0; i < token.size()-1; i++) {
			switch (args[token.get(i)]) {
				case "-help":
				case "-h":
					System.out.println(usage);
					System.exit(1);
					break;
				case "-extract":
					op = "extract";
					break;
				case "-replace":
					op = "replace";
					break;
				case "-in":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing bitstream file name for -in");
					inBit = args[dataIdx];
					break;
				case "-out":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing bitstream file name for -out");
					outBit = args[dataIdx];
					break;
				case "-template":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing template file name for -template");
					file = args[dataIdx];
					break;
				case "-platform":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing platform name for -platform");
					platform = args[dataIdx];
					break;
				case "-row":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing integer for -row");
					for (int j = dataIdx; j < token.get(i+1); j++) {
						rows.add(Integer.parseInt(args[j]));
					}
					break;
				case "-col":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing integer for -col");
					for (int j = dataIdx; j < token.get(i+1); j++) {
						cols.add(Integer.parseInt(args[j]));
					}
					break;
				case "-offset":
					dataIdx = token.get(i)+1;
					if (token.contains(dataIdx))
						reportThenExit.apply("Missing integer for -offset");
					offset = Integer.parseInt(args[dataIdx]);
					break;
				default:
					break;
			}
		}

		// Report collected arguments
		System.out.println("ReplaceFrameData");
		if (!op.isEmpty())
			System.out.println("-" + op);
		if (!inBit.isEmpty())
			System.out.println("-in       " + inBit);
		if (!outBit.isEmpty())
			System.out.println("-out      " + outBit);
		if (!file.isEmpty())
			System.out.println("-template " + file);
		if (!platform.isEmpty())
			System.out.println("-platform " + platform);
		if (!rows.isEmpty())
			System.out.println("-row      " + Arrays.toString(rows.toArray()));
		if (!cols.isEmpty())
			System.out.println("-col      " + Arrays.toString(cols.toArray()));
		if (offset != -255)
			System.out.println("-offset   " + offset);
		System.out.println();

		// Check collected arguments
		if ((platform != "") && ((!rows.isEmpty())||(!cols.isEmpty())||(offset!=-255)))
			System.out.println("Warning: -platform is set. -row, -col and -offset will be ignored.");
		if (op.equals("extract") && !outBit.isEmpty())
			System.out.println("Warning: -extract does not needs -out. -out option is ignored.");
		if (op.isEmpty())
			reportThenExit.apply("Error: either -extract or -replace must be specified.");
		if (inBit.isEmpty())
			reportThenExit.apply("Error: -in must be specified.");
		if (file.isEmpty())
			reportThenExit.apply("Error: -template must be specified.");
		if (op.equals("extract") && platform.isEmpty() && ((rows.isEmpty())||(cols.isEmpty())||(offset==-255)))
			reportThenExit.apply("Error: either -platform or the complete set of -row, -col and -offset must be set.");
		if (op.equals("replace") && platform.isEmpty() && ((rows.isEmpty())||(cols.isEmpty())))
			reportThenExit.apply("Error: either -platform or the complete set of -row and -col must be set.");
		if (op.equals("replace") && platform.isEmpty() && (offset!=-255))
			System.out.println("Warning: -replace does not need -offset. -offset will be ignored.");
		if (!platform.isEmpty() && !platform.equals("example_platform"))
			reportThenExit.apply("Error: only example_platform option is available for -platform.");
		if (op.equals("replace") && outBit.isEmpty())
			reportThenExit.apply("Error: -replace needs -out to be specified.");
		System.out.println();


		// Run
		Bitstream b = Bitstream.readBitstream(inBit);
		ReplaceFrameData frameDataOp = new ReplaceFrameData();

		if (op.equals("extract")) {

			if (platform.equals("example_platform")) {
				frameDataOp.extractForExamplePlatform(b);
			} else {
				frameDataOp.extract(b, rows, cols);
				// Extract from row i, to be used at row i+offset
				frameDataOp.incTemplateDataRowIndex(offset);
			}
			frameDataOp.save(file);

		} else if (op.equals("replace")) {

			frameDataOp.load(file);

			if (platform.equals("example_platform")) {
				frameDataOp.replaceForExamplePlatform(b);
			} else {
				frameDataOp.replace(b, rows, cols);
			}

			b.writeBitstream(outBit);
		}
	}

}
