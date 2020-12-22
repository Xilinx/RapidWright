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
/**
 * 
 */
package com.xilinx.rapidwright.device;

import com.xilinx.rapidwright.device.FamilyType;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Generated on: Wed Dec 16 09:47:52 2020
 * by: com.xilinx.rapidwright.release.PartNamePopulator
 * 
 * Class used to uniquely represent a Xilinx part.
 */
public class Part {
	/** Vivado part attribute NAME */
	String name;
	/** Vivado part attribute ARCHITECTURE */
	FamilyType architecture;
	/** Vivado part attribute ARCHITECTURE_FULL_NAME */
	String architectureFullName;
	/** Vivado part attribute FAMILY */
	FamilyType family;
	/** Vivado part attribute DEVICE */
	String device;
	/** Vivado part attribute PACKAGE */
	String pkg;
	/** Vivado part attribute SPEED */
	String speed;
	/** Vivado part attribute TEMPERATURE_GRADE_LETTER */
	String temperatureGradeLetter;
	/** Vivado part attribute REVISION */
	String revision;
	/** Vivado part attribute AVAILABLE_IOBS */
	String availableIobs;
	/** Vivado part attribute BLOCK_RAMS */
	String blockRams;
	/** Vivado part attribute DSP */
	String dsp;
	/** Vivado part attribute FLIPFLOPS */
	String flipflops;
	/** Vivado part attribute GB_TRANSCEIVERS */
	String gbTransceivers;
	/** Vivado part attribute LUT_ELEMENTS */
	String lutElements;
	/** Vivado part attribute MMCM */
	String mmcm;
	/** Vivado part attribute ULTRA_RAMS */
	String ultraRams;
	/** Vivado part attribute SERIES */
	Series series;

	public Part(String name, FamilyType architecture, String architectureFullName, FamilyType family, String device, String pkg, String speed, String temperatureGradeLetter, String revision, String availableIobs, String blockRams, String dsp, String flipflops, String gbTransceivers, String lutElements, String mmcm, String ultraRams, Series series) {
		this.name = name;
		this.architecture = architecture;
		this.architectureFullName = architectureFullName;
		this.family = family;
		this.device = device;
		this.pkg = pkg;
		this.speed = speed;
		this.temperatureGradeLetter = temperatureGradeLetter;
		this.revision = revision;
		this.availableIobs = availableIobs;
		this.blockRams = blockRams;
		this.dsp = dsp;
		this.flipflops = flipflops;
		this.gbTransceivers = gbTransceivers;
		this.lutElements = lutElements;
		this.mmcm = mmcm;
		this.ultraRams = ultraRams;
		this.series = series;
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}
	/**
	 * @return the architecture
	 */
	public FamilyType getArchitecture() {
		return architecture;
	}
	/**
	 * @return the architectureFullName
	 */
	public String getArchitectureFullName() {
		return architectureFullName;
	}
	/**
	 * @return the family
	 */
	public FamilyType getFamily() {
		return family;
	}
	/**
	 * @return the device
	 */
	public String getDevice() {
		String suffix = getRevision().isEmpty() ? "" : ("-" + getRevision());
		return device + suffix;
	}
	/**
	 * @return the pkg
	 */
	public String getPkg() {
		return pkg;
	}
	/**
	 * @return the speed
	 */
	public String getSpeed() {
		return speed;
	}
	/**
	 * @return the temperatureGradeLetter
	 */
	public String getTemperatureGradeLetter() {
		return temperatureGradeLetter;
	}
	/**
	 * @return the revision
	 */
	public String getRevision() {
		return revision;
	}
	/**
	 * @return the availableIobs
	 */
	public String getAvailableIobs() {
		return availableIobs;
	}
	/**
	 * @return the blockRams
	 */
	public String getBlockRams() {
		return blockRams;
	}
	/**
	 * @return the dsp
	 */
	public String getDsp() {
		return dsp;
	}
	/**
	 * @return the flipflops
	 */
	public String getFlipflops() {
		return flipflops;
	}
	/**
	 * @return the gbTransceivers
	 */
	public String getGbTransceivers() {
		return gbTransceivers;
	}
	/**
	 * @return the lutElements
	 */
	public String getLutElements() {
		return lutElements;
	}
	/**
	 * @return the mmcm
	 */
	public String getMmcm() {
		return mmcm;
	}
	/**
	 * @return the ultraRams
	 */
	public String getUltraRams() {
		return ultraRams;
	}
	/**
	 * @return the series
	 */
	public Series getSeries() {
		return series;
	}
	private static HashSet<FamilyType> series7;
	private static FamilyType[] series7Types;
	private static HashSet<FamilyType> ultraScale;
	private static FamilyType[] ultraScaleTypes;
	private static HashSet<FamilyType> ultraScalePlus;
	private static FamilyType[] ultraScalePlusTypes;
	private static HashSet<FamilyType> versal;
	private static FamilyType[] versalTypes;
	static {
		series7Types = new FamilyType[] {
				FamilyType.AARTIX7, 
				FamilyType.AKINTEX7, 
				FamilyType.ARTIX7, 
				FamilyType.ARTIX7L, 
				FamilyType.ASPARTAN7, 
				FamilyType.AZYNQ, 
				FamilyType.KINTEX7, 
				FamilyType.KINTEX7L, 
				FamilyType.QARTIX7, 
				FamilyType.QKINTEX7, 
				FamilyType.QKINTEX7L, 
				FamilyType.QVIRTEX7, 
				FamilyType.QZYNQ, 
				FamilyType.SPARTAN7, 
				FamilyType.VIRTEX7, 
				FamilyType.ZYNQ, 
		};
		series7 = new HashSet<FamilyType>(Arrays.asList(series7Types));
		ultraScaleTypes = new FamilyType[] {
				FamilyType.KINTEXU, 
				FamilyType.QKINTEXU, 
				FamilyType.QRKINTEXU, 
				FamilyType.VIRTEXU, 
		};
		ultraScale = new HashSet<FamilyType>(Arrays.asList(ultraScaleTypes));
		ultraScalePlusTypes = new FamilyType[] {
				FamilyType.AZYNQUPLUS, 
				FamilyType.KINTEXUPLUS, 
				FamilyType.QKINTEXUPLUS, 
				FamilyType.QVIRTEXUPLUS, 
				FamilyType.QVIRTEXUPLUSHBM, 
				FamilyType.QZYNQUPLUS, 
				FamilyType.QZYNQUPLUSRFSOC, 
				FamilyType.VIRTEXUPLUS, 
				FamilyType.VIRTEXUPLUS58G, 
				FamilyType.VIRTEXUPLUS58GES1, 
				FamilyType.VIRTEXUPLUSHBM, 
				FamilyType.VIRTEXUPLUSHBMES1, 
				FamilyType.ZYNQUPLUS, 
				FamilyType.ZYNQUPLUSRFSOC, 
				FamilyType.ZYNQUPLUSRFSOCES1, 
		};
		ultraScalePlus = new HashSet<FamilyType>(Arrays.asList(ultraScalePlusTypes));
		versalTypes = new FamilyType[] {
				FamilyType.VERSAL, 
				FamilyType.VERSALAICORE, 
				FamilyType.VERSALPRIME, 
		};
		versal = new HashSet<FamilyType>(Arrays.asList(versalTypes));
	}
	
	public boolean isSeries7(){
		return series7.contains(architecture);
	}
	
	public boolean isUltraScale(){
		return ultraScale.contains(architecture);
	}
	
	public boolean isUltraScalePlus(){
		return ultraScalePlus.contains(architecture);
	}
	
	public boolean isVersal(){
		return versal.contains(architecture);
	}
	
	public String toString(){
		return name;
	}
	
}