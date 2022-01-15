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
import java.util.function.BiFunction;
import java.util.function.Function;

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
        // extract from row 0,1, to be used at row 4,5
        incFrameDataRowIndex(spec.templateToTargetRowOffset);
    }

    public void replaceForOpenDFX_ZCU104(Bitstream b) {
        ReplacementSpec spec = getAddressOpenDFX_ZCU104();
        List<Integer> rows = spec.rows;
        // Need to adjust because the spec is set for extraction, not for replacement
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
        try {
            Output output = new Output(new FileOutputStream(filename));
            Kryo kryo = new Kryo();
            kryo.register(HashMap.class, new MapSerializer());
            kryo.register(Address.class);
            kryo.register(int[].class);
            kryo.writeClassAndObject(output, frameData);
            output.close();
        } catch (IOException e) {
            System.out.println("Cannot save to file " + filename);
            e.printStackTrace();
        }
    }

    /**
     * Load the frame data from a file
     */
    public void load(String filename) {
        try {
            Input input = new Input(new FileInputStream(filename));
            Kryo kryo = new Kryo();
            kryo.register(HashMap.class, new MapSerializer());
            kryo.register(Address.class);
            kryo.register(int[].class);
            frameData = (Map<Address, int[]>) kryo.readClassAndObject(input);
            input.close();
        } catch (IOException e) {
            System.out.println("Cannot read from file " + filename);
            e.printStackTrace();
        }
    }

    public void incFrameDataRowIndex(int inc) {
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


    // without static, can't serialize
    private static class Address {
        final int row;
        final int col;
        final int minor;
        public Address(int row, int col, int minor) {
            this.row   = row;
            this.col   = col;
            this.minor = minor;
        }

        // need by serialization
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

        String usage = String.join(System.getProperty("line.separator"),
            " Replace or Extract frame data of a bitstream. It is used to replace the NOP frame contents for unused hard block columns.",
            "",
            "Usage",
            " ReplaceFrameData [-extract, -replace] -in <bitfile> [-out <bitfile>] -template <file> [-platform <name>, -row <list> -col <list> -offset <int>]",
            "",
            " Examples:",
            "   ReplaceFrameData -extract -in test.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4",
            "   ReplaceFrameData -replace -in test.bit -template template.ser -platform opendfx_zcu104",
            "",
            " There are 4 groups of required arguments, can be in any order.",
            " 1) The operation to perform is either -extract or -replace.",
            " 2) There are two way to specify the frames to operate on.",
            "   a) Specify the predefined platform. Currently, there is only -platform opendfx_zcu104",
            "   b) Specify the list of rows and the list of cols, along with the row offset, ie., row to use the tempalte - row to extract the template.",
            " 3) The bit file to operate on is specified by -in <bitfile>. For -replace, -out <bitFile> is also required.",
            " 4) The file to store the extract data or to retrieve for replacement is specified by -template <file>"
        );
/*
correct
-extract -in in.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4
-extract -in in.bit -template template.ser -platform opendfx_zcu104
wrong
*         -in in.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4
*-extract            -template template.ser -row 0 1 -col 191 192 193 -offset 4
*-extract -in in.bit                        -row 0 1 -col 191 192 193 -offset 4
*-extract -in in.bit -template template.ser          -col 191 192 193 -offset 4
*-extract -in in.bit -template template.ser -row 0 1                  -offset 4
*-extract -in in.bit -template template.ser -row 0 1 -col 191 192 193
*-extract -in in.bit -template template.ser -row 0 1 -col 191 192 193 -platform opendfx_zcu104
*-extract -in in.bit -template template.ser
*-extract -in in.bit -template template.ser -platform notavail
-extract -in in.bit -template template.ser -row 0 1 -col 191 192 193 -platform opendfx_zcu104 -out out.bit

correct
-replace -in in.bit -out out.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4
-replace -in in.bit -out out.bit -template template.ser -platform opendfx_zcu104
wrong
*         -in in.bit -out out.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4
*-replace            -out out.bit -template template.ser -row 0 1 -col 191 192 193 -offset 4
*-replace -in in.bit              -template template.ser -row 0 1 -col 191 192 193 -offset 4
*-replace -in in.bit -out out.bit                        -row 0 1 -col 191 192 193 -offset 4
*-replace -in in.bit -out out.bit -template template.ser          -col 191 192 193 -offset 4
*-replace -in in.bit -out out.bit -template template.ser -row 0 1                  -offset 4
*-replace -in in.bit -out out.bit -template template.ser -row 0 1 -col 191 192 193 -platform opendfx_zcu104
*-replace -in in.bit -out out.bit -template template.ser
-replace -in in.bit -out out.bit -template template.ser -platform notavail
 */


        // scan for "-" token
        List<Integer> token = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-"))
                token.add(i);
        }
        token.add(args.length); // mark the end

        // set default argument values
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


        // parse arguments
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

        // report collected arguments
        System.out.println("ReplaceFrameData");
        if (!op.isEmpty())
            System.out.println("-" + op);
        if (!inBit.isEmpty())
            System.out.println("-in       " + inBit);
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

        // check collected arguments
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
        if (platform.isEmpty() && ((rows.isEmpty())||(cols.isEmpty())||(offset==-255)))
            reportThenExit.apply("Error: either -platform or the complete set of -row, -col and -offset must be set.");
        if (!platform.isEmpty() && !platform.equals("opendfx_zcu104"))
            reportThenExit.apply("Error: only opendfx_zcu104 option is available for -platform.");
        if (op.equals("replace") && outBit.isEmpty())
            reportThenExit.apply("Error: -replace needs -out to be specified.");
        System.out.println();

        // run
        if (op.equals("extract")) {

            long startExtract = System.nanoTime();
            ReplaceFrameData extractor = new ReplaceFrameData();

            long startTime = System.nanoTime();
            Bitstream b = Bitstream.readBitstream(inBit);
            System.out.println("\nread " + inBit + " took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        b.writePacketsToTextFile("templateSrcBit.txt");
//        System.out.println("write templateSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

            startTime = System.nanoTime();
            if (platform == "opendfx_zcu104") {
                // TODO: if more platforms are supported, passing platform forward
                extractor.extractForOpenDFX_ZCU104(b); // get frame data stored in this.frameData
            } else {
                extractor.extract(b, rows, cols);
                // extract from row 0,1, to be used at row 4,5
                extractor.incFrameDataRowIndex(offset);
            }
            System.out.println("extract template took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");
            extractor.save(file);
            System.out.println("Extraction  took " + (System.nanoTime() - startExtract) * 1e-6 + " ms.\n");

        } else if (op.equals("replace")) {

            long startReplace = System.nanoTime();
            ReplaceFrameData replacer = new ReplaceFrameData();
            System.out.println("before loading frameData size is " + replacer.frameData.size());
            replacer.load(file);
            System.out.println("after loading  frameData size is " + replacer.frameData.size());

            long startTime = System.nanoTime();
            Bitstream a = Bitstream.readBitstream(inBit);
            System.out.println("read " + inBit + " took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("relocSrcBit.txt");
//        System.out.println("write relocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

            startTime = System.nanoTime();
            if (platform == "opendfx_zcu104") {
                // TODO: if more platforms are supported, passing platform forward
                replacer.replaceForOpenDFX_ZCU104(a); // get frame data stored in this.frameData
            } else {
                replacer.replace(a, rows, cols);
            }
            a.writeBitstream(outBit);
            System.out.println("replace with template took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("newRelocSrcBit.txt");
//        System.out.println("write newRelocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");
            System.out.println("Replacement  took " + (System.nanoTime() - startReplace) * 1e-6 + " ms.\n");
        }

    }

}
/*
        boolean onesession = false;

        if (onesession) {
            System.out.println("Extract and Replace in one session");
            long startTotalTime = System.nanoTime();
            ReplaceFrameData replacer = new ReplaceFrameData();

            long startTime = System.nanoTime();
            String templateSrcBit = "dcpreloc_aes128_pblock_0_partial.bit";
            Bitstream b = Bitstream.readBitstream(templateSrcBit);
            System.out.println("\nread " + templateSrcBit + " took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        b.writePacketsToTextFile("templateSrcBit.txt");
//        System.out.println("write templateSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

            startTime = System.nanoTime();
            replacer.extractForOpenDFX_ZCU104(b); // get frame data stored in this.frameData
            System.out.println("extract template took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

            startTime = System.nanoTime();
            String relocSrcBit = "dcpreloc_aes128_pblock_2_partial.bit";
            Bitstream a = Bitstream.readBitstream(relocSrcBit);
            System.out.println("read " + relocSrcBit + " took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("relocSrcBit.txt");
//        System.out.println("write relocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

//        storage.setWrongDataForTest(); // to see if the replacement is in the right place.

            startTime = System.nanoTime();
            replacer.replaceForOpenDFX_ZCU104(a); // get frame data stored in this.frameData
            a.writeBitstream(relocSrcBit.replace(".bit", "_withtemplate.bit"));
            System.out.println("replace with template took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("newRelocSrcBit.txt");
//        System.out.println("write newRelocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

            System.out.println("\nTotal time " + (System.nanoTime() - startTotalTime) * 1e-6 + " ms.\n");
        } else {
            System.out.println("Extract and later Replace in two session");
            long startTotalTime = System.nanoTime();
            {
                long startExtract = System.nanoTime();
                ReplaceFrameData extractor = new ReplaceFrameData();

                long startTime = System.nanoTime();
                String templateSrcBit = "dcpreloc_aes128_pblock_0_partial.bit";
                Bitstream b = Bitstream.readBitstream(templateSrcBit);
                System.out.println("\nread " + templateSrcBit + " took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        b.writePacketsToTextFile("templateSrcBit.txt");
//        System.out.println("write templateSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

                startTime = System.nanoTime();
                extractor.extractForOpenDFX_ZCU104(b); // get frame data stored in this.frameData
                System.out.println("extract template took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");
                extractor.save("opendfx_zcu104.ser");
                System.out.println("Extraction  took " + (System.nanoTime() - startExtract) * 1e-6 + " ms.\n");
            }

            {
                long startReplace = System.nanoTime();
                ReplaceFrameData replacer = new ReplaceFrameData();
                System.out.println("before loading frameData size is " + replacer.frameData.size());
                replacer.load("opendfx_zcu104.ser");
                System.out.println("after loading  frameData size is " + replacer.frameData.size());

                long startTime = System.nanoTime();
                String relocSrcBit = "dcpreloc_aes128_pblock_2_partial.bit";
                Bitstream a = Bitstream.readBitstream(relocSrcBit);
                System.out.println("read " + relocSrcBit + " took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("relocSrcBit.txt");
//        System.out.println("write relocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");

//        storage.setWrongDataForTest(); // to see if the replacement is in the right place.

                startTime = System.nanoTime();
                replacer.replaceForOpenDFX_ZCU104(a); // get frame data stored in this.frameData
                a.writeBitstream(relocSrcBit.replace(".bit", "_withtemplate.bit"));
                System.out.println("replace with template took " + (System.nanoTime() - startTime) * 1e-6 + " ms.\n");

//        startTime = System.nanoTime();
//        a.writePacketsToTextFile("newRelocSrcBit.txt");
//        System.out.println("write newRelocSrcBit.txt took " + (System.nanoTime() - startTime)*1e-6 + " ms.\n");
                System.out.println("Replacement  took " + (System.nanoTime() - startReplace) * 1e-6 + " ms.\n");
            }

            System.out.println("\nTotal time " + (System.nanoTime() - startTotalTime) * 1e-6 + " ms.\n");
        }

 */