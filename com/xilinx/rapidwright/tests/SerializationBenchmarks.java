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
package com.xilinx.rapidwright.tests;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.esotericsoftware.kryo.io.UnsafeInput;
import com.esotericsoftware.kryo.io.UnsafeOutput;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Tool for testing different serialization formats.
 * @author clavin
 *
 */
public class SerializationBenchmarks {

	private int[] randomInts;
	
	private String[] randomStrings;

	private int[] goldenInts;
	
	private String[] goldenStrings;

	
	private Random rng = new Random();
	private SecureRandom random = new SecureRandom();
	
	
	private void populateRandomInts(int size){
		randomInts = new int[size];
		for(int i=0; i < size; i++){
			randomInts[i] = rng.nextInt();
		}
	}
	
	private void populateRandomStrings(int size){
		randomStrings = new String[size];
		for(int i=0; i < size; i++){
			randomStrings[i] = new BigInteger(72, random).toString(12);
		}
	}
	
	private void hessianSerializer(int size){
		long start = System.nanoTime();
		Hessian2Output o = FileTools.getHessianOutputStream("hessian-" + size + ".ser");
		FileTools.writeIntArray(o, randomInts);
		FileTools.writeStringArray(o, randomStrings);
		try {
			o.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		long stop = System.nanoTime();
		long fileSize = new File("hessian-" + size + ".ser").length() / 1024;
		double runtime = ((stop-start)/1000000000.0);
		System.out.printf(" hessian: %6dKB %6.3fs ",fileSize, runtime);
	}
	
	private void hessianDeserializer(int size){
		long start = System.nanoTime();
		Hessian2Input i = FileTools.getHessianInputStream("hessian-" + size + ".ser");
		randomInts = FileTools.readIntArray(i);
		randomStrings = FileTools.readStringArray(i);
		try {
			i.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		long stop = System.nanoTime();
		double runtime = ((stop-start)/1000000000.0);
		System.out.printf(" %6.3fs\n",runtime);
	}
	
	private void kryoSerializer(int size){
		long start = System.nanoTime();
		try {
			UnsafeOutput uo = new UnsafeOutput(new FileOutputStream("kryo-"+size+".ser"));
			uo.writeInt(randomInts.length);
			uo.writeInts(randomInts);
			uo.writeInt(randomStrings.length);
			for(String s : randomStrings){
				uo.writeString(s);
			}
			uo.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		long stop = System.nanoTime();
		long fileSize = new File("kryo-" + size + ".ser").length() / 1024;
		double runtime = ((stop-start)/1000000000.0);
		System.out.printf("    kryo: %6dKB %6.3fs ",fileSize, runtime);
	}
	
	private void kryoDeserializer(int size){
		long start = System.nanoTime();
		try {
			UnsafeInput ui = new UnsafeInput(new FileInputStream("kryo-"+size+".ser"));
			randomInts = ui.readInts(ui.readInt());
			int count = ui.readInt();
			randomStrings = new String[count];
			for(int i=0; i < count; i++){
				randomStrings[i] = ui.readString();
			}
			ui.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		long stop = System.nanoTime();
		double runtime = ((stop-start)/1000000000.0);
		System.out.printf(" %6.3fs",runtime);
	}
	
	private void javaSerializer(int size){
		long start = System.nanoTime();
		try {
			ObjectOutputStream o = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream("java-" + size + ".ser")));
			o.writeObject(randomInts);
			o.writeObject(randomStrings);
			o.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		long stop = System.nanoTime();
		long fileSize = new File("java-" + size + ".ser").length() / 1024;
		double runtime = ((stop-start)/1000000000.0);
		System.out.printf("    java: %6dKB %6.3fs ",fileSize, runtime);
	}
	
	private void javaDeserializer(int size){
		long start = System.nanoTime();
		try {
			ObjectInputStream i = new ObjectInputStream(new BufferedInputStream(new FileInputStream("java-" + size + ".ser")));
			randomInts = (int[]) i.readObject();
			randomStrings = (String[]) i.readObject();
			i.close();
		} catch (IOException | ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		long stop = System.nanoTime();
		double runtime = ((stop-start)/1000000000.0);
		System.out.printf(" %6.3fs\n",runtime);
	}
	
	public static void main(String[] args) {
		int[] trials = {1000, 10000, 100000, 1000000};
		SerializationBenchmarks sb = new SerializationBenchmarks();
		for(int t : trials){
			MessageGenerator.printHeader("Trial Size of " + t);
			System.out.println("Protocol      Size   Write     Read");
			sb.populateRandomInts(t);
			sb.populateRandomStrings(t);
			// Make a copy to validate
			sb.goldenInts = Arrays.copyOf(sb.randomInts,sb.randomInts.length);
			sb.goldenStrings = Arrays.copyOf(sb.randomStrings,sb.randomStrings.length);
			// Java Serialization
			sb.javaSerializer(t);
			sb.javaDeserializer(t);
			// Hessian
			sb.hessianSerializer(t);
			sb.hessianDeserializer(t);
			// Kryo
			sb.kryoSerializer(t);
			sb.kryoDeserializer(t);
			
			System.out.println();
		}
	}
}
