package com.xilinx.rapidwright.edif.partition;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Command-line tool to partition an EDIFNetlist
 */
public class Partitioner {

    public static AbstractPartitioner getDefaultPartitioner() {
        MtKaHyParPartitioner p = new MtKaHyParPartitioner();
        return p;
    }
    
    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("<input.edf> <# of partitions> <leafLUTCountLimit>");
            return;
        }
        Path inputEDIF = Paths.get(args[0]);
        int k = Integer.parseInt(args[1]);
        int leafLUTCountLimit = Integer.parseInt(args[2]);
        CodePerfTracker t = new CodePerfTracker("Partitioner");

        t.start("Read EDIF");
        EDIFNetlist n = EDIFTools.readEdifFile(inputEDIF);
        t.stop();

        t.start("Coarsen Netlist");
        Map<EDIFHierCellInst, Integer> instLutCountMap = new HashMap<>();
        Map<EDIFHierCellInst, Integer> leafInsts = PartitionTools.identifyLeafInstances(n,
                leafLUTCountLimit, instLutCountMap);
        System.out.println("Identified " + leafInsts.size() + " leaves");
        t.stop();

        t.start("Find Edges");
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap = new HashMap<>();
        for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
            for (EDIFHierPortInst pi : e.getKey().getHierPortInsts()) {
                EDIFHierNet connectedNet = pi.getHierarchicalNet();
                EDIFHierNet parentNet = n.getParentNet(connectedNet);
                if (edgesMap.containsKey(parentNet))
                    continue;
                edgesMap.put(parentNet, connectedNet.getConnectedInsts(leafInsts.keySet()));
            }
        }
        t.stop();

        t.start("Write hMETIS File");
        Path hMetisFile = Paths.get(inputEDIF.toString() + ".hgr");
        PartitionTools.writeHMetisFile(hMetisFile, edgesMap, leafInsts);
        t.stop();
        
        t.start("Run Partitioner");
        AbstractPartitioner p = getDefaultPartitioner();
        p.setInputFile(hMetisFile);
        p.setKPartitions(k);
        p.runPartitioner();
        t.stop();
        
        t.start("Read Partition Solution");
        Path outputFile = p.getOutputFile();
        String[] instLookup = PartitionTools.createInstLookupArray(leafInsts);
        Map<Integer, Set<String>> partitions = PartitionTools.readSolutionFile(outputFile, instLookup);

        MessageGenerator.printHeader("Partition Solution Report");
        for (int i = 0; i < partitions.size(); i++) {
            int lutCount = 0;
            Set<String> names = partitions.get(i);
            Path partitionFile = Paths.get(inputEDIF.toString() + ".part" + i);
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(partitionFile.toFile()))) {
                for (String name : names) {
                    bw.write(name + "\n");
                    EDIFHierCellInst inst = n.getHierCellInstFromName(name);
                    if (inst.getCellType().isLeafCellOrBlackBox()) {
                        lutCount += inst.getCellName().contains("LUT") ? 1 : 0;
                    } else {
                        lutCount += instLutCountMap.get(inst);
                    }
                }
                System.out.printf("  Partition %3d %10d LUTs %s\n", i, lutCount, partitionFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        System.out.println("-----------------------------------------------------------");
        System.out.printf("        Total : %10d LUTs\n\n\n",
                instLutCountMap.get(n.getTopHierCellInst()));
        t.stop();
        t.printSummary();
    }
}
