package com.xilinx.rapidwright.tests;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.util.FileTools;
import org.jgrapht.GraphPath;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.Set;


public class CheckAccuracyUsingGnlDesigns {

    public CheckAccuracyUsingGnlDesigns() {
    }

    static double runOneDesign(String dcpFileName, String designName) {
        Design d = Design.readCheckpoint(dcpFileName);

        TimingManager tm = new TimingManager(d);
        TimingGraph tg   = tm.getTimingGraph();
        GraphPath maxDelayPath = tg.getMaxDelayPath();
        return maxDelayPath.getWeight();
    }

    static void runOneSuite(String suiteFileName) {
        PrintStream printStream=null;

        Double errPrecision = 1d;
        Integer numErrors   = 0;

        DecimalFormat zeroDecimalPoint = new DecimalFormat("0");
        DecimalFormat fourDecimalPoint = new DecimalFormat("0.0000");

        String RWPath  = FileTools.getRapidWrightPath();
        String desPath = "/designs/timing/gnl/";
//        String outPath = "/./";
        try {
            String outFileName = suiteFileName.substring(0,suiteFileName.lastIndexOf('.'));
//            printStream = new PrintStream(RWPath + outPath + outFileName + ".out");
            printStream = new PrintStream(outFileName + ".out");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        List<Double> absErrorList = new ArrayList<Double>();;

        try {

            FileInputStream inputStream = new FileInputStream(RWPath + desPath + "golden/" + suiteFileName);
            printStream.println("#designs                                vivado  model(golden)   model   error   abs_error");

            Scanner sc = new Scanner(inputStream, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                boolean lineIsBlank = line.replaceAll("\\s+", "").isEmpty();

                if (lineIsBlank || line.trim().matches("^#.*")) { // if not a comment line
//                    System.out.println("skip " + line);
                } else {
                    List<String> items     = Arrays.asList(line.trim().split("\\s+"));
                    String desName         = items.get(0);
                    String goldenEstString = items.get(2);
                    Double goldenEst       = Double.valueOf(goldenEstString);
                    String refDelayString  = zeroDecimalPoint.format(Double.valueOf(items.get(1)));
                    Double refDelay        = Double.valueOf(refDelayString );

                    Double tempDelay      = runOneDesign(RWPath + desPath + desName + ".dcp", desName);
                    String delayString    = zeroDecimalPoint.format(tempDelay);
                    Double delay          = Double.valueOf(delayString);
                    String errorString    = fourDecimalPoint.format((delay - refDelay) / refDelay);
                    Double error          = Double.valueOf(errorString);
                    String absErrorString = fourDecimalPoint.format(Math.abs(error));
                    Double absError       = Double.valueOf(absErrorString);

                    absErrorList.add(absError);

                    if (Math.abs(goldenEst - delay) > errPrecision) {
                        numErrors++;
                    }

                    printStream.println(desName +"\t"+ refDelayString + "\t" + goldenEstString
                                        + "\t\t" + delayString + "\t" + errorString + "\t" + absErrorString);
                }
            }
            // Note that Scanner suppresses exceptions
            if (sc.ioException() != null) {
                throw sc.ioException();
            }
        } catch (IOException ex) {
            System.out.println(ex.toString());
            System.out.println("IOException during reading file " + suiteFileName);
        }

        Double avgAbsError = absErrorList.stream().mapToDouble(val -> val).average().orElse(0.0);
        printStream.println("# Average absolute error : "
                             + fourDecimalPoint.format(avgAbsError));
        printStream.println("# Minimum absolute error : "
                             + fourDecimalPoint.format(Collections.min(absErrorList)));
        printStream.println("# Maximum absolute error : "
                             + fourDecimalPoint.format(Collections.max(absErrorList)));
        printStream.println("# Number of designs with |estimated delay - golden estimated delay| > "
                             + errPrecision + " is " + numErrors);

        printStream.close();
    }

    public static void main(String[] args) {

        String[] fileNames = {
                "gnl_500MHz_2018.3.txt",
                "gnl_775MHz_2018.3.txt"
        };

        for (String fileName : fileNames) {
            runOneSuite(fileName);
        }

        System.out.println("\n\nCheckAccuracyUsingGnlDesigns completed.\n");
    }
}
