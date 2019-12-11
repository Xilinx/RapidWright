/*
 * 
 * Copyright (c) 2017 Xilinx, Inc. 
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
/**
 * 
 */
package com.xilinx.rapidwright.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installer for RapidWright for development setup.
 * 
 * Created on: Sep 1, 2017
 */
public class Installer {

	public static boolean verbose = false;
	public static boolean KEEP_ZIP_FILES = false;
	public static boolean SKIP_ZIP_DOWNLOAD = false;
	public static boolean SKIP_TEST = false;
	

	
	private static String rwPathVarName = "RAPIDWRIGHT_PATH";
	private static String classpathVarName = "CLASSPATH";
	private static String MD5_FILE_NAME = "MD5SUM.TXT";
	
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
	 * Convert bytes of an MD5 checksum into common alpha-numeric String representation.
	 * @param checksum The digested result from an MD5 instance.
	 * @return The alpha-numeric String representation of the checksum.
	 */
	public static String bytesToString(byte[] checksum){
		StringBuilder result = new StringBuilder(32);
		for (int i=0; i < checksum.length; i++) {
			result.append(Integer.toString( ( checksum[i] & 0xff ) + 0x100, 16).substring( 1 ));
		}
		return result.toString();		
	}
	
	/**
	 * Performs an MD5 checksum on the provided file and returns the result.
	 * @param fileName Name of the file on which to perform the checksum
	 * @return Checksum result String or null if no file was found.
	 */
	public static String calculateMD5OfFile(String fileName){
		try {
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			InputStream in = Files.newInputStream(Paths.get(fileName)); 
			DigestInputStream dig = new DigestInputStream(in, md5);
			byte[] buffer = new byte[1024];
			while(dig.read(buffer) != -1){}
			byte[] checksum = md5.digest();
			return bytesToString(checksum);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (IOException e) {
			return null;
		}
		return null;
	}
	
	public static long downloadFile(String url, String dstFileName) throws IOException{
		URL website = new URL(url);
		ReadableByteChannel rbc = Channels.newChannel(website.openStream());
		FileOutputStream fos = new FileOutputStream(dstFileName);
		return fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
	}
	
    private static void unzipFile(String zipFile, String destDir) {
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if(!dir.exists()) dir.mkdirs();
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFile);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while(ze != null){
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);
                System.out.println("Unzipping to "+newFile.getAbsolutePath());
                
                if(ze.isDirectory()){
                	newFile.mkdirs();
                }else{
                	new File(newFile.getParent()).mkdirs();
                	 FileOutputStream fos = new FileOutputStream(newFile);
                     int len;
                     while ((len = zis.read(buffer)) > 0) {
                     fos.write(buffer, 0, len);
                     }
                     fos.close();
                }
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
	
	public static Integer runCommand(List<String> command){
		if(verbose) System.out.println("External Command: " + command);
		ProcessBuilder pb = new ProcessBuilder(command);
		// Make sure we don't have the RAPIDWRIGHT_PATH already set
		String cwd = System.getProperty("user.dir");
		pb.environment().put(rwPathVarName,cwd + File.separator + "RapidWright");
		pb.redirectErrorStream(true);
		pb.redirectOutput(Redirect.INHERIT);
		Process p;
		int returnVal = -1;
		try {
			p = pb.start();
			returnVal = p.waitFor();
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
		return returnVal;
	}
	
	public static boolean isWindows(){
		return System.getProperty("os.name").startsWith("Windows");
	}
	
	/**
	 * Identifies all necessary jar files in /jars directory and creates a CLASSPATH string.
	 * @return The dependent jar files 
	 */
	public static String getJarsClasspath(){
		String cwd = System.getProperty("user.dir");
		String jarsDir = cwd + File.separator + "RapidWright" + File.separator +"jars";
		boolean isWindows = isWindows();
		StringBuilder sb = new StringBuilder();
		for(String jar : new File(jarsDir).list()){
			if(jar.contains("javadoc")) continue;
			if(jar.contains("macosx") || jar.contains("linux32") || jar.contains("win32")) continue;
			if(isWindows && jar.contains("linux64")) continue;
			if(!isWindows && jar.contains("win64")) continue;
			if(!jar.toLowerCase().endsWith(".jar")) continue;
			if(sb.length() > 0) sb.append(File.pathSeparator);
			sb.append(jarsDir);
			sb.append(File.separator);
			sb.append(jar);
		}
		return sb.toString();
	}
	
	/**
	 * Gets all Java (.java) source files recursively under the 
	 * provided directory
	 * @param dir The directory to look under for Java source files
	 * @return A list of all Java source files with absolute path names.
	 */
	public static List<String> getAllJavaSources(String dir) {
		Path root = FileSystems.getDefault().getPath(dir);
		List<String> javaSrcs = null;
		
		try{
			javaSrcs = Files.walk(root)
			        .filter(foundPath -> foundPath.toString().endsWith(".java"))
			        .map(javaPath -> javaPath.toAbsolutePath().toString())
			        .collect(Collectors.toList());			
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return javaSrcs;
	}
	
	
	private static String getURLRedirect(String url) throws IOException{
		HttpURLConnection con = (HttpURLConnection)(new URL( url ).openConnection());
		con.setInstanceFollowRedirects( false );
		con.connect();
		return con.getHeaderField("Location");
	}
	
    public static final String REPO = "https://github.com/Xilinx/RapidWright.git";
    public static final String RELEASE = "https://github.com/Xilinx/RapidWright/releases/latest";
    public static final String DATA_ZIP = "rapidwright_data.zip";
    public static final String JARS_ZIP = "rapidwright_jars.zip";
       
	public static void main(String[] args) throws IOException {
		for(String arg : args){
			if(arg.equals("-v") || arg.equals("--verbose")){
				verbose = true;
			}else if(arg.equals("-k") || arg.equals("--keep-zip-file")){
				KEEP_ZIP_FILES = true;
			}else if(arg.equals("-s") || arg.equals("--skip-zip-download")){
				SKIP_ZIP_DOWNLOAD = true;
			}else if(arg.equals("-t") || arg.equals("--skip-test")){
				SKIP_TEST = true;
			}else if(arg.equals("-h") || arg.equals("--help")){
				System.out.println("================================================================================");
				System.out.println(" RapidWright Installer");
				System.out.println("================================================================================");
				System.out.println("  This will automate the manual steps of setting up a repo and getting \n"
								 + "  RapidWright up and running. \n\n"
								 + "  Options\n"
								 + "  --------\n"
								 + "  -v, --verbose           : Prints the commands run from a Java ProcessBuilder\n"
								 + "  -k, --keep-zip-file     : Does not delete downloaded data and jar zip files\n"
								 + "                            at the end of a successful install\n"
								 + "  -s, --skip-zip-download : Uses local copies of the data and jar zip  in the\n"
								 + "                            same directory instead of downloading them.\n"
								 + "  -t, --skip-test         : Skips the attempt to test RapidWright by opening\n"
								 + "                            the DeviceBrowser (scripted installs).\n" 
								 + "  -h, --help              : Prints this help message.");
				return;
			}
		}
		
		System.out.println("================================================================================");
		System.out.println(" Setting up RapidWright ...");
		System.out.println("================================================================================");

		
		String value = System.getenv("HTTPS_PROXY");
		if(value != null && !value.equals("")){
			int idx = value.lastIndexOf(':');
			String host = value.substring(0, idx).replace("http://", "").replace("https://", "");
			
			// If host name is not an ip address, get the IP address
			boolean isIP4 = host.matches("^.[0-9]{1,3}/..[0-9]{1,3}/..[0-9]{1,3}/..[0-9]{1,3}");
			if(!isIP4){
				try {
					host = InetAddress.getByName(host).getHostAddress();
				} catch (UnknownHostException e) {
					e.printStackTrace();
					System.err.println("ERROR: Couldn't find host designated by HTTPS_PROXY="
							+value +", please use syntax: 'proxyname.com:8080'");
					System.exit(1);
				}
			}

			// Check that the port is valid
			String port = value.substring(idx+1);
			try{
				int p = Integer.parseInt(port);
				if(p < 0 || p > 65535) throw new RuntimeException("Bad port number");
			}catch (Exception e){
				System.err.println("ERROR: Couldn't identify a valid port number designated by HTTPS_PROXY="
							+value +", please use syntax: 'proxyname.com:8080'");
				System.exit(1);
			}
			System.setProperty("https.proxyHost",host);			
			System.setProperty("https.proxyPort",port);
			System.setProperty("https.proxySet","true");			
			System.out.println("  Using proxy settings from HTTPS_PROXY="+value+", host=" + host + ", port=" + port);
		}
		
		boolean missingDep = false;
		Integer returnVal = runCommand(Arrays.asList("git","--version"));
		if(returnVal != 0){
			System.err.println("ERROR: Couldn't find 'git' on PATH, please install or set PATH environment variable accordingly.");
			if(isWindows()){
				System.err.println("\tgit can be downloaded from: https://git-scm.com/download/win");
			}else{
				System.err.println("\tgit can be installed by:\n");
				System.err.println("\tDebian/Ubuntu: 'sudo apt-get install git'\n");
				System.err.println("\tRedHat/Fedora: 'sudo yum install git'\n");
			}
			missingDep = true;
		}
		returnVal = runCommand(Arrays.asList("javac","-version"));
		if(returnVal != 0){
			System.err.println("ERROR: Couldn't find 'javac' on PATH, please install or set PATH environment variable accordingly.");
			if(isWindows()){
				System.err.println("\tJava JDK can be downloaded from: https://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html");
			}else{
				System.err.println("\tJDK can be installed by:\n");
				System.err.println("  \tDebian/Ubuntu: 'sudo apt-get install openjdk-8-jdk'\n");
				System.err.println("  \tRedHat/Fedora: 'sudo yum install java-1.8.0-openjdk-devel'\n");
			}
			missingDep = true;
		}
		if(missingDep){
			System.out.println("Missing pre-requisite(s), please address issues above to continue install.");
			return;
		}
		
		
		System.out.println("================================================================================");
		System.out.println("  1. Checking out code from "+REPO+" ...");
		System.out.println("================================================================================");
		returnVal = runCommand(Arrays.asList("git","clone", REPO));
		if(returnVal != 0){
			System.err.println("ERROR: Problem cloning repository. See output above for cause");
			System.err.println("  Some common reasons for failure:");
			System.err.println("    * A directory named RapidWright already exists, delete it or move it elsewhere");
			System.err.println("    * git may not be installed, or is not on your PATH");
			System.err.println("    * Your network uses a proxy. Try setting the appropriate environment\n"
					         + "      variables HTTP_PROXY and HTTPS_PROXY to your proxy server");
			System.exit(1);
		}
		
		System.out.println("================================================================================");
		System.out.println("  2. Download and unzip "+DATA_ZIP+" and "+JARS_ZIP+"");
		System.out.println("================================================================================");
		System.out.println("  Please be patient, download may take several minutes...");
		String latestRelease = getURLRedirect(RELEASE);
		latestRelease = latestRelease.replace("/tag/", "/download/");
		
		
		for(String name : new String[]{DATA_ZIP,JARS_ZIP}){
			boolean alreadyDownloaded = false;
			if(new File(name).exists()){
				System.out.println("Checking if existing "+name+" can be used...");
				downloadFile(latestRelease+"/"+MD5_FILE_NAME, MD5_FILE_NAME);
				String md5sum = null;
				for(String line : Files.readAllLines(Paths.get(MD5_FILE_NAME), Charset.forName("US-ASCII"))){
					String[] parts = line.split("\\s+"); 
					if(parts[1].trim().equals(name)){
						md5sum = parts[0].trim();
						break;
					}
				}
				String calcMD5Sum = calculateMD5OfFile(name);
				if(md5sum.equals(calcMD5Sum)){
					System.out.println(name + " is valid, skipping download.");
					alreadyDownloaded = true;
				}else{
					System.out.println(name + " md5sum is invalid: " + calcMD5Sum + ", should be: " + md5sum);
				}
			}
			if(alreadyDownloaded || SKIP_ZIP_DOWNLOAD){
				if(!new File(name).exists()){
					System.err.println("  ERROR: Option --skip-zip-download set but could not find file " + name +"\n"
							+ "  Please remove the option or download the zip file manually and place it in the \n"
							+ "  current directory.");
					System.exit(1);
				}
			}else{
				String url = latestRelease+"/"+name;
				System.out.println("Downloading " + url + " ...");
				long size = downloadFile(url, name);
				if(size == 0){				
					System.err.println("ERROR: Problem downloading file:" + name);
					System.err.println("  You may have a connectivity problem, or you are using a proxy. \n "
									 + "  Try setting the environment variable HTTPS_PROXY=<proxyhost>:<proxyport>\n"
									 + "  See http://www.rapidwright.io/docs/Getting_Started.html#full-installation-development\n"
									 + "  for details on how to install manually.");
					System.exit(1);
				}
			}
			System.out.println("Unzipping " + name + " ...");
			unzipFile(name, "RapidWright");
		}
		
		System.out.println("================================================================================");
		System.out.println("  3. Compile all Java source code ...");
		System.out.println("================================================================================");

		String jarsClassPath = getJarsClasspath();
		String rwDir = System.getProperty("user.dir") + File.separator + "RapidWright";
		List<String> allJavaSources = getAllJavaSources(rwDir + "/src");
		String binDir = rwDir + "/bin";
		new File(binDir).mkdirs();
		ArrayList<String> cmd = new ArrayList<>(Arrays.asList("javac","-cp", jarsClassPath, "-d", binDir));
		cmd.addAll(allJavaSources);
		returnVal = runCommand(cmd);
		if(returnVal != 0){
			System.err.println("ERROR: Problem compiling java code. See output above for cause.");
			System.exit(1);
		}
		
		String classpath = binDir+File.pathSeparator + jarsClassPath;
		if(SKIP_TEST){
			System.out.println("Skipping DeviceBrowser test...");
		}else{
			System.out.println("================================================================================");
			System.out.println("  4. Let's test the DeviceBrowser in RapidWright ...");
			System.out.println("================================================================================");
			System.out.println("  In a few seconds you should see a window open called DeviceBrowser...");
			cmd = new ArrayList<>(Arrays.asList("java","-cp", classpath, "com.xilinx.rapidwright.device.browser.DeviceBrowser"));
			String rwPathVarName = "RAPIDWRIGHT_PATH";
			String existingPath = System.getenv(rwPathVarName);
			if(existingPath != null && !existingPath.equals("")){
				System.out.println("  NOTE: You already have the " + rwPathVarName + " set, be sure to update it. ");
			}
			
			returnVal = runCommand(cmd);
			if(returnVal != 0){
				System.err.println("  ERROR: Looks like the DeviceBrowser did not run or crashed. Please examine\n"
						+ "  the output for clues as to what went wrong.  If you are stumped, please request help\n"
						+ "  on the RapidWright Google Group Forum: https://groups.google.com/forum/#!forum/rapidwright.");
				if(!isWindows()){
					System.err.println("\n*** If you are running Linux ***"); 
					System.err.println("If you are running Linux, a common problem is to be missing libpng12.so.0.\n" +
									   "If you are running a CentOS/RedHat/Fedora distro, try the following:\n" + 
									   "    sudo yum install libpng12\n\n" + 
									   "If you are running a Debian/Ubuntu distro, try the following:\n" + 
									   "    wget -q -O /tmp/libpng12.deb http://mirrors.kernel.org/ubuntu/pool/main/libp/libpng/libpng12-0_1.2.54-1ubuntu1_amd64.deb && sudo dpkg -i /tmp/libpng12.deb && rm /tmp/libpng12.deb");
				}
				System.exit(1);
			}		
		}
		
		System.out.println("================================================================================");
		System.out.println("  Install Finished!");
		System.out.println("================================================================================");
		System.out.println("  To run RapidWright, just set these two environment variables:\n");
		System.out.println("  "+rwPathVarName+" = "+rwDir);
		System.out.println("  "+classpathVarName+" = "+classpath+"\n");
		
		// BASH
		ArrayList<String> lines = new ArrayList<>();
		String bash = "rapidwright.sh";
		lines.add("export " + rwPathVarName +"=" + rwDir);	
		lines.add("if [ -n \"${"+classpathVarName+"}\" ]; then");
		lines.add("  export " + classpathVarName +"=" + classpath);
		lines.add("else");
		lines.add("  export " + classpathVarName +"=" + classpath +":$" + classpathVarName);
		lines.add("fi");
		writeLinesToTextFile(lines, bash);
		new File(bash).setExecutable(true);
		
		// CSH
		lines = new ArrayList<>();
		String csh = "rapidwright.csh";
		lines.add("setenv " + rwPathVarName + " " + rwDir);		
		lines.add("if $?"+classpathVarName+" then");
		lines.add("  setenv "+classpathVarName+" "+classpath+":$" + classpathVarName);
		lines.add("else");
		lines.add("  setenv "+classpathVarName+" "+classpath);
		lines.add("endif");
		writeLinesToTextFile(lines, csh);
		new File(csh).setExecutable(true);
		
		// BAT
		lines = new ArrayList<>();
		String bat = "rapidwright.bat";
		lines.add("SETX " + rwPathVarName + " \"" + rwDir + "\"");		
		lines.add("SETX "+classpathVarName+" "+classpath+";%" + classpathVarName + "%");
		writeLinesToTextFile(lines, bat);
		new File(bat).setExecutable(true);
		
		System.out.println("  As a convenience, here are some scripts that can be sourced/run to set those");
		System.out.println("  variables in various shells:");
		System.out.println("    BASH (Linux): "+bash);
		System.out.println("    CSH (Linux): "+csh);
		System.out.println("    BAT (Windows): "+bat + "\n");
		
		String cwd = System.getProperty("user.dir") + File.separator;
		if(returnVal == 0 && (!SKIP_ZIP_DOWNLOAD && !KEEP_ZIP_FILES)){
			System.out.print("Cleaning up zip files ...");
			boolean success = new File(cwd + DATA_ZIP).delete();
			success &= new File(cwd + JARS_ZIP).delete();
			success &= new File(cwd + MD5_FILE_NAME).delete();
			if(success) System.out.println("Done.");
			else System.out.println("Problem deleting zip files.");
			
		}
	}
}
