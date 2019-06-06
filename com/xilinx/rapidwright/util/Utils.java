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
package com.xilinx.rapidwright.util;

import java.util.HashSet;

import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.TileTypeEnum;

/**
 * This is a helper class for creating PrimitiveTypes and TileTypes
 * as well as helping to categorize TileTypes. 
 */
public class Utils{

	private static HashSet<TileTypeEnum> clbs;
	
	private static HashSet<TileTypeEnum> clbms;
	
	private static HashSet<TileTypeEnum> dsps;
	
	private static HashSet<TileTypeEnum> brams;
	
	private static HashSet<TileTypeEnum> ints;
	
	private static HashSet<TileTypeEnum> gts;
	
	private static HashSet<TileTypeEnum> interconnects;

	private static HashSet<SiteTypeEnum> lockedSiteTypes;

	private static HashSet<SiteTypeEnum> moduleSiteTypes;

	private static HashSet<SiteTypeEnum> sliceTypes;
	
	public static HashSet<SiteTypeEnum> dspTypes;

	public static HashSet<SiteTypeEnum> bramTypes;
	
	public static HashSet<SiteTypeEnum> iobTypes;

	public static HashSet<SiteTypeEnum> uramTypes;
	
	/**
	 * Returns a SiteTypeEnum enum based on the given string. If such
	 * an enum does not exist, it will return null.
	 * @param s The string to be converted to an enum type
	 * @return The SiteTypeEnum corresponding to the string s, null if none exists.
	 */
	public static SiteTypeEnum createSiteType(String s){
		return SiteTypeEnum.valueOf(s.toUpperCase());
	}

	/**
	 * Returns a TileTypeEnum enum based on the given string s.  If such an enum
	 * does not exist, it will return null
	 * @param s The string to be converted to an enum type
	 * @return The TileTypeEnum corresponding to String s, null if none exists.
	 */
	public static TileTypeEnum createTileType(String s){
		return TileTypeEnum.valueOf(s.toUpperCase());
	}
	
	/**
	 * Determines if the provided tile type contains SLICE primitive sites
	 * of any type.
	 * @param type The tile type to test for.
	 * @return True if this tile type has SLICE (any kind) primitive sites.
	 */
	public static boolean isCLB(TileTypeEnum type){
		return clbs.contains(type);
	}
	
	/**
	 * Determines if the tile type has a SLICEM site type.
	 * @param type
	 * @return
	 */
	public static boolean isCLBM(TileTypeEnum type){
		return clbms.contains(type);
	}
	
	/**
	 * Determines if the provided tile type contains DSP primitive sites
	 * of any type.
	 * @param type The tile type to test for.
	 * @return True if this tile type has DSP (any kind) primitive sites.
	 */
	public static boolean isDSP(TileTypeEnum type){
		return dsps.contains(type);
	}
	
	/**
	 * Determines if the provided tile type contains BRAM primitive sites
	 * of any type.
	 * @param type The tile type to test for.
	 * @return True if this tile type has BRAM (any kind) primitive sites.
	 */
	public static boolean isBRAM(TileTypeEnum type){
		return brams.contains(type);
	}
	
	/**
	 * Determines if the provided tile type contains BRAM primitive sites
	 * of any type.
	 * @param type The tile type to test for.
	 * @return True if this tile type has BRAM (any kind) primitive sites.
	 */
	public static boolean isSwitchBox(TileTypeEnum type){
		return ints.contains(type);
	}
	
	/**
	 * Determines if the provided tile type contains GTs primitive sites
	 * of any type.
	 * @param type The tile type to test for.
	 * @return True if this tile type has GT (any kind) primitive sites.
	 */
	public static boolean isGt(TileTypeEnum type){
		return gts.contains(type);
	}
	
	/**
	 * Determines if the provided tile type contains INTERCONNECTs primitive sites
	 * of any type.
	 * @param type The tile type to test for.
	 * @return True if this tile type has INTERCONNECT (any kind) primitive sites.
	 */
	public static boolean isInterConnect(TileTypeEnum type){
		return interconnects.contains(type);
	}
	
	public static boolean isLockedSiteType(SiteTypeEnum type){
		return lockedSiteTypes.contains(type);
	}
	
	public static boolean isModuleSiteType(SiteTypeEnum type){
		return moduleSiteTypes.contains(type);
	}
	
	public static HashSet<TileTypeEnum> getIntTileTypes(){
		return interconnects;
	}
	
	public static HashSet<TileTypeEnum> getCLBTileTypes(){
		return clbs;
	}

	public static HashSet<TileTypeEnum> getCLBMTileTypes(){
		return clbms;
	}
	
	public static HashSet<TileTypeEnum> getDSPTileTypes(){
		return dsps;
	}

	public static HashSet<TileTypeEnum> getBRAMTileTypes(){
		return brams;
	}
	
	public static HashSet<SiteTypeEnum> getLockedSiteTypes(){
		return lockedSiteTypes;
	}

	public static HashSet<SiteTypeEnum> getModuleSiteTypes(){
		return moduleSiteTypes;
	}
	
	public static boolean isSLICE(SiteInst s){
		return sliceTypes.contains(s.getSiteTypeEnum());
	}

	public static boolean isSLICE(SiteTypeEnum s){
		return sliceTypes.contains(s);
	}

	public static boolean isDSP(SiteInst s){
		return dspTypes.contains(s.getSiteTypeEnum());
	}

	public static boolean isBRAM(SiteInst s){
		return bramTypes.contains(s.getSiteTypeEnum());
	}
	
	public static boolean isURAM(SiteInst s){
		return uramTypes.contains(s.getSiteTypeEnum());
	}


	
	static{
		clbs = new HashSet<TileTypeEnum>();
		clbs.add(TileTypeEnum.CLBLL_L);
		clbs.add(TileTypeEnum.CLBLL_R);
		clbs.add(TileTypeEnum.CLBLM_L);
		clbs.add(TileTypeEnum.CLBLM_R);
		clbs.add(TileTypeEnum.CLEL_L);
		clbs.add(TileTypeEnum.CLEL_R);
		clbs.add(TileTypeEnum.CLE_M);
		clbs.add(TileTypeEnum.CLE_M_R);
		clbs.add(TileTypeEnum.CLEM);
		clbs.add(TileTypeEnum.CLEM_R);
		
		clbms = new HashSet<TileTypeEnum>();
		clbms.add(TileTypeEnum.CLBLM_L);
		clbms.add(TileTypeEnum.CLBLM_R);
		clbms.add(TileTypeEnum.CLE_M);
		clbms.add(TileTypeEnum.CLE_M_R);
		clbms.add(TileTypeEnum.CLEM);
		clbms.add(TileTypeEnum.CLEM_R);
		
		dsps = new HashSet<TileTypeEnum>();
		dsps.add(TileTypeEnum.DSP);
		dsps.add(TileTypeEnum.DSP_L);
		dsps.add(TileTypeEnum.DSP_R);
		
		brams = new HashSet<TileTypeEnum>();
		brams.add(TileTypeEnum.BRAM);
		brams.add(TileTypeEnum.BRAM_L);
		brams.add(TileTypeEnum.BRAM_R);

		ints = new HashSet<TileTypeEnum>();
		ints.add(TileTypeEnum.INT);
		ints.add(TileTypeEnum.INT_L);
		ints.add(TileTypeEnum.INT_R);
		ints.add(TileTypeEnum.INT_L_SLV);
		ints.add(TileTypeEnum.INT_R_SLV);
		ints.add(TileTypeEnum.INT_L_SLV_FLY);
		ints.add(TileTypeEnum.INT_R_SLV_FLY);
		
		gts = new HashSet<TileTypeEnum>();
		gts.add(TileTypeEnum.GTZ_TOP);
		gts.add(TileTypeEnum.GTZ_BOT);
		gts.add(TileTypeEnum.GTX_CHANNEL_0);
		gts.add(TileTypeEnum.GTX_CHANNEL_1);
		gts.add(TileTypeEnum.GTX_CHANNEL_2);
		gts.add(TileTypeEnum.GTX_CHANNEL_3);
		gts.add(TileTypeEnum.GTX_COMMON);
		gts.add(TileTypeEnum.GTH_CHANNEL_0);
		gts.add(TileTypeEnum.GTH_CHANNEL_1);
		gts.add(TileTypeEnum.GTH_CHANNEL_2);
		gts.add(TileTypeEnum.GTH_CHANNEL_3);
		gts.add(TileTypeEnum.GTH_COMMON);
	   
		interconnects = new HashSet<TileTypeEnum>();
		interconnects.add(TileTypeEnum.INT);
		interconnects.add(TileTypeEnum.INT_L);
		//interconnects.add(TileTypeEnum.INT_L_SLV);
		//interconnects.add(TileTypeEnum.INT_L_SLV_FLY);
		interconnects.add(TileTypeEnum.INT_R);
		//interconnects.add(TileTypeEnum.INT_R_SLV);
		//interconnects.add(TileTypeEnum.INT_R_SLV_FLY);
		
		lockedSiteTypes = new HashSet<SiteTypeEnum>();
		lockedSiteTypes.add(SiteTypeEnum.CONFIG_SITE);
		lockedSiteTypes.add(SiteTypeEnum.BUFG);
		lockedSiteTypes.add(SiteTypeEnum.BUFGCE);
		lockedSiteTypes.add(SiteTypeEnum.BUFGCTRL);
		

		moduleSiteTypes = new HashSet<SiteTypeEnum>();
		moduleSiteTypes.add(SiteTypeEnum.LAGUNA);
		moduleSiteTypes.add(SiteTypeEnum.SLICEL);
		moduleSiteTypes.add(SiteTypeEnum.SLICEM);
		moduleSiteTypes.add(SiteTypeEnum.RAMB180);
		moduleSiteTypes.add(SiteTypeEnum.RAMB181);
		moduleSiteTypes.add(SiteTypeEnum.RAMB18E1);
		moduleSiteTypes.add(SiteTypeEnum.RAMB36);
		moduleSiteTypes.add(SiteTypeEnum.RAMB36E1);
		moduleSiteTypes.add(SiteTypeEnum.RAMBFIFO18);
		moduleSiteTypes.add(SiteTypeEnum.RAMBFIFO36);
		moduleSiteTypes.add(SiteTypeEnum.RAMBFIFO36E1);
		moduleSiteTypes.add(SiteTypeEnum.FIFO18_0);
		moduleSiteTypes.add(SiteTypeEnum.FIFO18E1);
		moduleSiteTypes.add(SiteTypeEnum.FIFO36);
		moduleSiteTypes.add(SiteTypeEnum.FIFO36E1);
		moduleSiteTypes.add(SiteTypeEnum.DSP48E1);
		moduleSiteTypes.add(SiteTypeEnum.DSP48E2);
		moduleSiteTypes.add(SiteTypeEnum.BUFGCE);	
		
		sliceTypes = new HashSet<SiteTypeEnum>();
		sliceTypes.add(SiteTypeEnum.SLICEL);
		sliceTypes.add(SiteTypeEnum.SLICEM);
		
		dspTypes = new HashSet<SiteTypeEnum>();
		dspTypes.add(SiteTypeEnum.DSP48E1);
		dspTypes.add(SiteTypeEnum.DSP48E2);
		
		bramTypes = new HashSet<SiteTypeEnum>();
		bramTypes.add(SiteTypeEnum.FIFO18E1);
		bramTypes.add(SiteTypeEnum.FIFO36E1);		
		bramTypes.add(SiteTypeEnum.RAMB18E1);
		bramTypes.add(SiteTypeEnum.RAMB36);
		bramTypes.add(SiteTypeEnum.RAMB36E1);
		bramTypes.add(SiteTypeEnum.RAMBFIFO18);
		bramTypes.add(SiteTypeEnum.RAMBFIFO36);
		bramTypes.add(SiteTypeEnum.RAMBFIFO36E1);
		
		iobTypes = new HashSet<SiteTypeEnum>();
		iobTypes.add(SiteTypeEnum.IOB18);
		iobTypes.add(SiteTypeEnum.IOB18M);
		iobTypes.add(SiteTypeEnum.IOB18S);
		iobTypes.add(SiteTypeEnum.IOB33);
		iobTypes.add(SiteTypeEnum.IOB33M);
		iobTypes.add(SiteTypeEnum.IOB33S);
		
		uramTypes = new HashSet<>();
		uramTypes.add(SiteTypeEnum.URAM288);

	}

}
