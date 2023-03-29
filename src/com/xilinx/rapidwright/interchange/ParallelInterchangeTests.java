package com.xilinx.rapidwright.interchange;

import java.io.File;
import java.io.IOException;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.compare.EDIFNetlistComparator;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.ParallelismTools;

public class ParallelInterchangeTests {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("USAGE: <input DCP>");
            return;
        }
        String dcpName = args[0];
        String edfName = dcpName.replace(".dcp", ".edf");
        String logName = dcpName.replace(".dcp", ".netlist");
        String physName = dcpName.replace(".dcp", ".phys");

        CodePerfTracker t = new CodePerfTracker("Interchange: " + dcpName);
        t.start("Read DCP");
        Design design = Design.readCheckpoint(dcpName);
        t.stop();
        t.start("WriteLogNetlist");
        LogNetlistWriter.writeLogNetlistParallel(design.getNetlist(), logName, true);
        t.stop();
        t.start("WritePhysNetlist");
        PhysNetlistWriter.writePhysNetlistParallel(design, physName);
        t.stop();
        t.start("ReadLogNetlist");
        EDIFNetlist netlist = LogNetlistReader.readLogNetlistParallel(logName);
        t.stop();
        t.start("ReadPhysNetlist");
        PhysNetlistReader.readPhysNetlistParallel(physName, netlist);
        t.stop();
        t.printSummary();
    }

    public static void runtimeTest(String[] args) throws IOException {
        for (String dcpName : args) {
            String edfName = dcpName.replace(".dcp", ".edf");
            String logName = dcpName.replace(".dcp", ".netlist");
            String physName = dcpName.replace(".dcp", ".phys");

            CodePerfTracker t = new CodePerfTracker("Interchange: " + dcpName);
            t.start("Read EDIF");
            EDIFNetlist n = EDIFTools.readEdifFile(edfName);
            t.stop();
            ParallelismTools.setParallel(false);
            for (String intType : new String[] { "", "gzip", "zstd" }) {
                File directory = new File(System.getProperty("user.dir"));
                for (File f : directory.listFiles()) {
                    if (f.getName().startsWith(logName)) {
                        f.delete();
                    }
                }

                switch (intType) {
                case "":
                    Interchange.IS_GZIPPED = false;
                    Interchange.IS_ZSTD = false;
                    break;
                case "gzip":
                    Interchange.IS_GZIPPED = true;
                    Interchange.IS_ZSTD = false;
                    break;
                case "zstd":
                    Interchange.IS_GZIPPED = false;
                    Interchange.IS_ZSTD = true;
                    break;
                default:
                    throw new RuntimeException("Unrecognized state");
                }

                t.start("Write LogNetlist" + " " + intType);
                LogNetlistWriter.writeLogNetlistParallel(n, logName, true);
                t.stop();

                long sum = 0;
                for (File f : directory.listFiles()) {
                    if (f.getName().startsWith(logName)) {
                        sum += f.length();
                    }
                }
                System.out.println(logName + " " + sum + " " + (sum / (1024 * 1024)));

                t.start("Read LogNetlist" + " " + intType);
                EDIFNetlist n2 = LogNetlistReader.readLogNetlistParallel(logName);
                t.stop();

                t.start("Write EDIF");
                n2.collapseMacroUnisims(Series.UltraScalePlus);
                n2.exportEDIF(edfName + ".2");
                t.stop().printSummary();

                EDIFNetlistComparator.main(new String[] { edfName, edfName + ".2", "diff.log" });
            }

            t.start("Write EDIF");
            n.collapseMacroUnisims(Series.UltraScalePlus);
            n.exportEDIF(edfName + ".2");
            t.stop().printSummary();
        }
    }
}
