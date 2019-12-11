package com.xilinx.rapidwright.tests;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Installer;
import com.xilinx.rapidwright.util.MessageGenerator;

import org.jgrapht.GraphPath;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;


public class CheckAccuracyUsingGnlDesigns {

	public static String GNL_DESIGN_PATH = File.separator + "designs"+File.separator+"timing"+
                                           File.separator+"gnl" + File.separator;
	
	private static String DOWNLOAD_PATH = 
			"https://github.com/Xilinx/RapidWright/releases/download/v2019.1.2-beta/";
	
    public CheckAccuracyUsingGnlDesigns() {
    }

    static double runOneDesign(String dcpFileName, String designName) {
        Design d = Design.readCheckpoint(dcpFileName);

        TimingManager tm = new TimingManager(d);
        TimingGraph tg   = tm.getTimingGraph();
        GraphPath<TimingVertex, TimingEdge> maxDelayPath = tg.getMaxDelayPath();
        return maxDelayPath.getWeight();
    }

    static void runOneSuite(String suiteFileName) {
        PrintStream printStream=null;

        Double errPrecision = 1d;
        Integer numErrors   = 0;

        DecimalFormat zeroDecimalPoint = new DecimalFormat("0");
        DecimalFormat fourDecimalPoint = new DecimalFormat("0.0000");

        String path = FileTools.getRapidWrightPath() + GNL_DESIGN_PATH;
        String outFileName = suiteFileName.substring(0,suiteFileName.lastIndexOf('.'));
        try {
            printStream = new PrintStream(outFileName + ".out");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.err.println("Could not write file " + outFileName + ".out");
            return;
        }

        List<Double> absErrorList = new ArrayList<Double>();;

        try {
        	String goldenFileName = path + "golden/" + suiteFileName;
        	if(!new File(goldenFileName).exists()) {
        		System.out.print("ERROR: GNL designs could not be found.  Would you like to"
        				+ " download them ");
        		MessageGenerator.agreeToContinue();
        		String gnlDesignFileName = "gnl_timing_designs.zip";
        		Installer.downloadFile(DOWNLOAD_PATH + gnlDesignFileName, gnlDesignFileName);
        		FileTools.unzipFile(gnlDesignFileName, FileTools.getRapidWrightPath());
        	}
            FileInputStream inputStream = new FileInputStream(goldenFileName);
            printStream.println("#designs                                vivado  model(golden)   "
                                + "model   error   abs_error");

            Scanner sc = new Scanner(inputStream, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                boolean lineIsBlank = line.replaceAll("\\s+", "").isEmpty();

                if (lineIsBlank || line.trim().matches("^#.*")) { // if not a comment line

                } else {
                    List<String> items     = Arrays.asList(line.trim().split("\\s+"));
                    String desName         = items.get(0);
                    String goldenEstString = items.get(2);
                    Double goldenEst       = Double.valueOf(goldenEstString);
                    String refDelayString  = zeroDecimalPoint.format(Double.valueOf(items.get(1)));
                    Double refDelay        = Double.valueOf(refDelayString );

                    Double tempDelay      = runOneDesign(path + desName + ".dcp", desName);
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
                                        + "\t\t" + delayString + "\t" + errorString + "\t" 
                                        + absErrorString);
                }
            }
            sc.close();
            // Note that Scanner suppresses exceptions
            if (sc.ioException() != null) {
                throw sc.ioException();
            }
        } catch (IOException ex) {
            System.out.println(ex.toString());
            System.out.println("IOException during reading file " + suiteFileName);
        }

        Double avgAbsError = absErrorList.stream().mapToDouble(val -> val).average().orElse(0.0);
        printStream.println("# Average absolute percent error : "
                             + fourDecimalPoint.format(avgAbsError*100) + " %");
        printStream.println("# Minimum absolute percent error : "
                             + fourDecimalPoint.format(Collections.min(absErrorList)*100) + " %");
        printStream.println("# Maximum absolute percent error : "
                             + fourDecimalPoint.format(Collections.max(absErrorList)*100) + " %");
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
