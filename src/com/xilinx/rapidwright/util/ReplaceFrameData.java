package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.bitstream.Bitstream;
import com.xilinx.rapidwright.bitstream.BlockType;
import com.xilinx.rapidwright.bitstream.ConfigArray;
import com.xilinx.rapidwright.bitstream.ConfigRow;
import com.xilinx.rapidwright.bitstream.FAR;
import com.xilinx.rapidwright.bitstream.Frame;
import com.xilinx.rapidwright.bitstream.OpCode;
import com.xilinx.rapidwright.bitstream.Packet;
import com.xilinx.rapidwright.bitstream.RegisterType;

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

/**
 * Store and load sections of bitstream
 */
public class ReplaceFrameData {

    Map<Address, int[]>  frameData;

    public ReplaceFrameData() {
        frameData = new HashMap<>();
    }

    public void extractForOpenDFX_ZCU104(Bitstream b) {
        ReplacementSpec spec = getAddressOpenDFX_ZCU104();
        extract(b, spec.rows, spec.cols);
        incrementRowIndex(spec.templateToTargetRowOffset);
    }

    public void replaceForOpenDFX_ZCU104(Bitstream b) {
        ReplacementSpec spec = getAddressOpenDFX_ZCU104();
        List<Integer> rows = spec.rows;
        for (int i = 0; i < rows.size(); i += 1) {
            rows.set(i, rows.get(i) + spec.templateToTargetRowOffset);
        }
        replace(b, rows, spec.cols);
    }

    public void extract(Bitstream b, List<Integer> rows, List<Integer> cols) {
        FrameDataProcessor processor = new FrameDataProcessor(b);
        processor.processPackets(rows, cols,
                (frameData, addr, data, frameIdx, wordsPerFrame) -> {
                    extractColumn(frameData, addr, data, frameIdx, wordsPerFrame);
                }
        );
        frameData = processor.getFrameData();
    }

    public void replace(Bitstream b, List<Integer> rows, List<Integer> cols) {
        FrameDataProcessor processor = new FrameDataProcessor(b);
        processor.setFrameData(frameData);
        processor.processPackets(rows, cols,
                (frameData, addr, data, frameIdx, wordsPerFrame) -> {
                    replaceColumn(frameData, addr, data, frameIdx, wordsPerFrame);
                }
        );
    }

    /**
     * Save the frame data to a file
     */
    public void save(String filename) {
    }

    /**
     * Load the frame data from a file
     */
    public void load(String filename) {
    }

    private void incrementRowIndex(int inc) {
        Map<Address, int[]>  newFrameData = new HashMap<>();
        for (Map.Entry<Address, int[]> entry : frameData.entrySet()) {
            Address addr = entry.getKey();
            Address newAddr = new Address(addr.row + inc, addr.col, addr.minor);
            newFrameData.put(newAddr, entry.getValue());
        }
        frameData = newFrameData;
    }

    private interface OpOnFrame {
        void operate (Map<Address, int[]> frameData, Address addr, int[] data, int frameIdx, int wordsPerFrame);
    }

    /**
     * The result of extraction will be updated to frameData. To bind to OpOnFrame interface.
     * @param frameData
     * @param addr
     * @param data
     * @param frameIdx
     * @param wordsPerFrame
     */
    private void extractColumn (Map<Address, int[]> frameData, Address addr, int[] data, int frameIdx, int wordsPerFrame) {
        frameData.put(addr,Arrays.copyOfRange(data, frameIdx*wordsPerFrame, (frameIdx+1)*wordsPerFrame));
    }

    /**
     * The result of replacement will be updated in data.  To bind to OpOnFrame interface.
     * @param frameData
     * @param addr
     * @param data
     * @param frameIdx
     * @param wordsPerFrame
     */
    private void replaceColumn (Map<Address, int[]> frameData, Address addr, int[] data, int frameIdx, int wordsPerFrame) {
        if (frameData.containsKey(addr)) {
            System.arraycopy(frameData.get(addr),0,data,frameIdx*wordsPerFrame,wordsPerFrame);
        }
    }

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
     * Get list of target rows and cols to extract from bitstream for RP0
     */
    private ReplacementSpec getAddressOpenDFX_ZCU104() {
        int row = 0; // either 0 or 2 because it was verified that the template at RP0 and RP1 are the same.
        List<Integer> rows = new ArrayList<Integer>() {{add(row);add(row+1);}};
        List<Integer> cols = new ArrayList<Integer>() {{add(191);add(192);add(193);}};
        int offsetFromRP0ToRP2 = 4;
        return new ReplacementSpec(rows, cols, offsetFromRP0ToRP2);
    }


   //------------------------------ Helper --------------------------------------


    private class Address {
        final int row;
        final int col;
        final int minor;
        public Address(int row, int col, int minor) {
            this.row   = row;
            this.col   = col;
            this.minor = minor;
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
     * Extract frame data of the given frame address.
     * Assumption: a packet contain only frame data of only one row. This is from observation.
     */
    private class FrameDataProcessor {

        private final int wordsPerFrame;
        private final int columnCounts;
        // Populate by buildNumFrameArray and never change. Its value is array[rowIdx][colIdx]
        private final Map<BlockType, int[][]> frameCounts;
        private Bitstream b;
        // Keep track of the last FAR WRITE command. The user of far is within a single method.
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
         * Extract the frame data of the given addresses.
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

        private void processColumns(FAR far, Packet packet, List<Integer> colToOperate, List<Integer> numFrames, int wordsPerFrame, OpOnFrame op) {
            int firstCol = far.getColumn();
            int row = far.getRow();

            for (int col : colToOperate) {
                int minor = 0;
                if (col == firstCol) {
                    // only the first col can have non-zero minor
                    minor = far.getMinor();
                }
                int i = col - firstCol;
                // copy for each minor not numFrames because the minor of the first col can be non-zero.
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

        // return an array indexed by column address containing the number of frames in a column.
        // TODO: use an empty configArray to save time and memory
        private void buildNumFrameArray(ConfigArray c) {

            for (BlockType blkType : new ArrayList<BlockType>() {{
                add(BlockType.CLB);
            }}) {
                int array[][] = new int[c.getNumOfConfigRows()][c.getConfigRows().get(0).numOfBlocks()];
                // high order row is for blk ram
                for (int rowIdx = 0; rowIdx < c.getNumOfConfigRows() / 2; rowIdx++) {
                    ConfigRow row = c.getConfigRow(blkType.getBlockTypeEncoding(), 0, rowIdx);
                    if (row == null) {
                        throw new RuntimeException("Row " + rowIdx + " is NULL");
                    } else {
                        for (int colIdx = 0; colIdx < row.numOfBlocks(); colIdx++) {
                            array[rowIdx][colIdx] = row.getBlock(colIdx).getFrameCount();
                        }
                        // increase the overhead to the last column will simplify later processing.
                        array[rowIdx][row.numOfBlocks()-1] += ConfigArray.FRAME_OVERHEAD_COUNT_PER_ROW;
                    }
                }
                frameCounts.put(blkType, array);
            }
        }

        /**
         * Mark the beginning index of each column in the packet data array.
         * @param packet
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
            // Fact 3) A packet can span only a part of a column.
            int nFrameInCol = Math.min(nFrames, frameCounts.get(BlockType.CLB)[far.getRow()][far.getColumn()] - far.getMinor());
            int nFrameInc = moveToNextCol(far);
            assert( nFrameInc == nFrameInCol);

            int remaining = nFrames - nFrameInCol;
            int endColumn = beginColumn + 1;
            numFrames.add(nFrameInCol);

            // invariance: remaining is the number of frame to the beginning of the column specified by endColumn
            //             if remaining < 0, this packet does not cover every frame of the column
            // Fact 3) A packet can span only a part of a column. That remaining can be negative.
            // assume a packet will not more than one row
            while (remaining > 0 && endColumn < columnCounts) {
                assert(endColumn == far.getColumn());
                assert(beginRow  == far.getRow()); // assume a packet cover only one row
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

        private int moveToNextCol(FAR far) {
            int orgCol = far.getColumn();
            int limit = 256; // the most I ever see is 256 frames in a column
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


    private void setWrongDataForTest() {
        int [] data = new int[93];
        Arrays.fill(data, 0xFFFFFFFF );
        for (Address addr : frameData.keySet())
            frameData.put(addr, data);
    }

    public static void main(String[] args) {
        long startTotalTime = System.nanoTime();
        ReplaceFrameData replacer = new ReplaceFrameData();

        long startTime = System.nanoTime();
        String templateSrcBit = "dcpreloc_aes128_pblock_0_partial.bit";
        Bitstream b = Bitstream.readBitstream(templateSrcBit);
        System.out.println("\nread " + templateSrcBit + " took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        b.writePacketsToTextFile("templateSrcBit.txt");
//        System.out.println("write templateSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

        startTime = System.nanoTime();
        replacer.extractForOpenDFX_ZCU104(b); // get frame data stored in this.frameData
        System.out.println("extract template took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

        startTime = System.nanoTime();
        String relocSrcBit = "dcpreloc_aes128_pblock_2_partial.bit";
        Bitstream a = Bitstream.readBitstream(relocSrcBit);
        System.out.println("read " + relocSrcBit + " took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("relocSrcBit.txt");
//        System.out.println("write relocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

//        storage.setWrongDataForTest(); // to see if the replacement is in the right place.

        startTime = System.nanoTime();
        replacer.replaceForOpenDFX_ZCU104(a); // get frame data stored in this.frameData
        a.writeBitstream(relocSrcBit.replace(".bit", "_withtemplate.bit"));
        System.out.println("replace with template took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("newRelocSrcBit.txt");
//        System.out.println("write newRelocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

        System.out.println("\nTotal time " + (System.nanoTime() - startTotalTime)*1e-6 + " ms.\n");
    }

}
