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
package com.xilinx.rapidwright.gui;

import io.qt.core.Qt.GlobalColor;
import io.qt.gui.QColor;

import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

import java.util.HashMap;

/**
 * This class is simply a suggested coloring of tile types for displaying
 * a grid of tiles.
 * @author Chris Lavin
 * Created on: Jan 22, 2011
 */
public class TileColors {
	
	private static HashMap<TileTypeEnum, QColor> tileColors;
	private static HashMap<GlobalColor, QColor> qtColors;

	/**
	 * Gets a suggested color based on the tile's tileType. 
	 * @param tile The tile for which to get the color suggestion.
	 * @return A suggested color, or null if none exists.
	 */
	public static QColor getSuggestedTileColor(Tile tile){
		return tileColors.get(tile.getTileTypeEnum());
	}

	public static QColor getQColor(GlobalColor color) {
	    return qtColors.get(color);
	}
	
	static{
	    qtColors = new HashMap<>();
	    for(GlobalColor color : GlobalColor.values()) {
	        qtColors.put(color, new QColor(color));
	    }	    
		tileColors = new HashMap<TileTypeEnum, QColor>();
		tileColors.put(TileTypeEnum.BRAM, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRKH_BRAM, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_B_TERM_INT, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_CLB, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_CMT, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_GTX, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_INT, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.B_TERM_INT, qtColors.get(GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.CLEM_R, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.DSP, qtColors.get(GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.GTP_INT_INTERFACE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_INT_INTERFACE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HCLK_BRAM, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_CLB, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_GTX, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_INT_INTERFACE, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_IOB, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_IOI, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_TERM, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_VBRK, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_VFRAME, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.INT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.LIOI, qtColors.get(GlobalColor.darkGreen));
		tileColors.put(TileTypeEnum.L_TERM_INT, qtColors.get(GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.NULL, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_TOP, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI, qtColors.get(GlobalColor.darkGreen));
		tileColors.put(TileTypeEnum.R_TERM_INT, qtColors.get(GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.T_TERM_INT, qtColors.get(GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.VBRK, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.VFRAME, qtColors.get(GlobalColor.black));
		// Virtex 7, qtColors.get(Kintex 7
		tileColors.put(TileTypeEnum.BRAM_INT_INTERFACE_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.BRAM_INT_INTERFACE_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.BRAM_L, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRAM_R, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRKH_CLK, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_DSP_L, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_DSP_R, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_TERM_INT, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.B_TERM_INT_NOUTURN, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.B_TERM_INT_SLV, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_BOT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_MID, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_MID_SLAVE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_TOP, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_TOP_SLAVE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLBLL_L, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLBLL_R, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLBLM_L, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLBLM_R, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLK_BALI_REBUF, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_BUFG_BOT_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_BUFG_REBUF, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_BUFG_TOP_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_FEED, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_HROW_BOT_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_HROW_TOP_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_PMV, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_TERM, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_FIFO_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_FIFO_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_PMV, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_PMV_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_LOWER_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_LOWER_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_UPPER_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_UPPER_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_LOWER_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_LOWER_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_UPPER_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_UPPER_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.DSP_L, qtColors.get(GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.DSP_R, qtColors.get(GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_0, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_1, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_2, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_3, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_COMMON, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_INT_INTERFACE_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HCLK_CMT, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_CMT_L, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_DSP_L, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_DSP_R, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_FEEDTHRU_1, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_FEEDTHRU_2, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_FIFO_L, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_IOI3, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_L, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_L_BOT_UTURN, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_L_SLV, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_R, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_R_BOT_UTURN, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_R_SLV, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_TERM_GTX, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.INT_FEEDTHRU_1, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_FEEDTHRU_2, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_L_SLV, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_L_SLV_FLY, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_R_SLV, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_R_SLV_FLY, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.IO_INT_INTERFACE_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.IO_INT_INTERFACE_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.LIOB18, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOB18_SING, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOB33, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOB33_SING, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3_SING, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3_TBYTESRC, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3_TBYTETERM, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI_SING, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI_TBYTESRC, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI_TBYTETERM, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_BOT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_BOT_FUJI2, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_BOT_SLAVE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_MID, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_MID_FUJI2, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_TOP, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_TOP_FUJI2, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_BOT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_BOT_LEFT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_LEFT_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_NULL, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_TOP_LEFT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOB18, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOB18_SING, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI_SING, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI_TBYTESRC, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI_TBYTETERM, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.R_TERM_INT_GTX, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.TERM_CMT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.T_TERM_INT_NOUTURN, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.T_TERM_INT_SLV, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.VBRK_EXT, qtColors.get(GlobalColor.black));
		// Virtex Ultra Scale
		tileColors.put(TileTypeEnum.AMS, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.BRAM_RBRK, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRAM_TERM_B, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRAM_TERM_T, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.CFGIO_CFG_RBRK, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFGIO_IOB, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CFG, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CFG_PCIE_RBRK, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CTR_TERM_B, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CTR_TERM_T, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_GAP_CFGBOT, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_GAP_CFGTOP, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFRM_AMS_CFGIO, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_CBRK_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_CBRK_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_CFG, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_L_RBRK, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_L_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_L_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_RBRK_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_RBRK_CFGIO, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_RBRK_PCIE, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_R_RBRK, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_R_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_R_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLEL_L, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_L_RBRK, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_L_TERM_B, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_L_TERM_T, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R_RBRK, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R_TERM_B, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R_TERM_T, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_R, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_RBRK, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_TERM_B, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_TERM_T, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLK_IBRK_FSR2IO, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CLEM, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CMAC_CMAC_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMAC_CMAC_LEFT_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.CMAC_CMAC_LEFT_TERM_T_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.DSP_RBRK, qtColors.get(GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.DSP_TERM_B, qtColors.get(GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.DSP_TERM_T, qtColors.get(GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.FSR_GAP, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_TERM_B_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_TERM_T_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP_RBRK, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R_RBRK, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_TERM_B_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_TERM_T_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HPHRIO_RBRK_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_CBRK_IO, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_RBRK_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_TERM_B_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_TERM_T_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HRIO_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.HRIO_TERM_B_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_CMAC_ILMAC_LEFT_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_AMS_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_CMAC_LEFT_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_LEFT_TERM_B_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_PCIE3_ILMAC_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.INT_IBRK_FSR2IO, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_IO2XIPHY, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_LEFT_L_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_L_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_FSR2IO, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_IO2XIPHY, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_LEFT_L_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_L_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_R_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_XIPHY2INT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_R_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_FSR2IO, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_IO2XIPHY, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_LEFT_L_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_L_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_R_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_XIPHY2INT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_FSR2IO, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_IO2XIPHY, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_LEFT_L_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_L_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_R_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_XIPHY2INT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_XIPHY2INT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_RBRK, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_TERM_B, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_TERM_T, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_RBRK, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_TERM_B, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_TERM_T, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_RBRK, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_TERM_B, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_TERM_T, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_RBRK, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_TERM_B, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_TERM_T, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_RBRK, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_TERM_B, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_TERM_T, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_RBRK_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_TERM_B_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_TERM_T_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_RBRK_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_TERM_B_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_TERM_T_FT, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTF_L, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTF_R, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_RBRK, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_TERM_B, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_TERM_T, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.LAGUNA_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LAGUNA_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LAGUNA_TILE, qtColors.get(GlobalColor.red));
		tileColors.put(TileTypeEnum.LAG_CLEM2LAGUNA_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.LAG_LAG, qtColors.get(GlobalColor.red));
		tileColors.put(TileTypeEnum.LAG_LAGUNA2CLEM_RBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_AMS_CFGIO, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_BRAM_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_BRAM_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CBRK_IO, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CBRK_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CBRK_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_R_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_R_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEM_CLKBUF_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLE_M_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLE_M_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_DSP_CLKBUF_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_GAP4, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_HPIO_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_HRIO_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_IO2XIPHY, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_L_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_R_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_XIPHY2INT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_GT_R_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_L_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_L_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_PCIE_L_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_PCIE_R_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_R_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INT_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INT_R, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_BRAM_L_AUXCLMP_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_BRAM_L_BRAMCLMP_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_GAP50_MINICBRK_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_IBRK_LEFT_L_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_GT_LEFT_L_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_PCIE3_LEFT_L_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_XIPHY_LEFT_L_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_LAGUNA_L_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_LAGUNA_R_FT, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L_RBRK, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L_TERM_B, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L_TERM_T, qtColors.get(GlobalColor.black));
		tileColors.put(TileTypeEnum.URAM_URAM_FT, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_URAM_DELAY_FT, qtColors.get(GlobalColor.darkMagenta));

		// Versal
		tileColors.put(TileTypeEnum.CLE_E_CORE, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_W_CORE, qtColors.get(GlobalColor.blue));
		tileColors.put(TileTypeEnum.DSP_ROCF_B_TILE, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.DSP_ROCF_T_TILE, qtColors.get(GlobalColor.cyan));
		tileColors.put(TileTypeEnum.BRAM_ROCF_BL_TILE, qtColors.get(GlobalColor.magenta));
		tileColors.put(TileTypeEnum.BRAM_ROCF_BR_TILE, qtColors.get(GlobalColor.magenta));
		tileColors.put(TileTypeEnum.BRAM_ROCF_TL_TILE, qtColors.get(GlobalColor.magenta));
		tileColors.put(TileTypeEnum.BRAM_ROCF_TR_TILE, qtColors.get(GlobalColor.magenta));
		tileColors.put(TileTypeEnum.INTF_ROCF_BL_TILE, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INTF_ROCF_BR_TILE, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INTF_ROCF_TL_TILE, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INTF_ROCF_TR_TILE, qtColors.get(GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.CLE_BC_CORE, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CLE_BC_CORE_MX, qtColors.get(GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.URAM_LOCF_TL_TILE, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_LOCF_BL_TILE, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_ROCF_TL_TILE, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_ROCF_BL_TILE, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_DELAY_ROCF_TL_TILE, qtColors.get(GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_DELAY_LOCF_TL_TILE, qtColors.get(GlobalColor.darkMagenta));
	}
}
