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

import java.util.HashMap;

import com.trolltech.qt.gui.QColor;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

/**
 * This class is simply a suggested coloring of tile types for displaying
 * a grid of tiles.
 * @author Chris Lavin
 * Created on: Jan 22, 2011
 */
public class TileColors {
	
	private static HashMap<TileTypeEnum, QColor> tileColors;

	/**
	 * Gets a suggested color based on the tile's tileType. 
	 * @param tile The tile for which to get the color suggestion.
	 * @return A suggested color, or null if none exists.
	 */
	public static QColor getSuggestedTileColor(Tile tile){
		return tileColors.get(tile.getTileTypeEnum());
	}
	
	static{
		tileColors = new HashMap<TileTypeEnum, QColor>();


		tileColors.put(TileTypeEnum.BRAM, QColor.darkMagenta);
		tileColors.put(TileTypeEnum.BRKH_BRAM, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_B_TERM_INT, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_CLB, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_CMT, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_GTX, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_INT, QColor.darkBlue);
		tileColors.put(TileTypeEnum.B_TERM_INT, QColor.darkGray);
		tileColors.put(TileTypeEnum.CLEM_R, QColor.blue);
		tileColors.put(TileTypeEnum.DSP, QColor.darkCyan);
		tileColors.put(TileTypeEnum.GTP_INT_INTERFACE, QColor.black);
		tileColors.put(TileTypeEnum.GTX_INT_INTERFACE, QColor.black);
		tileColors.put(TileTypeEnum.HCLK_BRAM, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_CLB, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_GTX, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_INT_INTERFACE, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_IOB, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_IOI, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_TERM, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_VBRK, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_VFRAME, QColor.cyan);
		tileColors.put(TileTypeEnum.INT, QColor.darkYellow);
		tileColors.put(TileTypeEnum.LIOI, QColor.darkGreen);
		tileColors.put(TileTypeEnum.L_TERM_INT, QColor.darkGray);
		tileColors.put(TileTypeEnum.NULL, QColor.black);
		tileColors.put(TileTypeEnum.PCIE, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_L, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_R, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_TOP, QColor.black);
		tileColors.put(TileTypeEnum.RIOI, QColor.darkGreen);
		tileColors.put(TileTypeEnum.R_TERM_INT, QColor.darkGray);
		tileColors.put(TileTypeEnum.T_TERM_INT, QColor.darkGray);
		tileColors.put(TileTypeEnum.VBRK, QColor.darkBlue);
		tileColors.put(TileTypeEnum.VFRAME, QColor.black);
		// Virtex 7, Kintex 7
		tileColors.put(TileTypeEnum.BRAM_INT_INTERFACE_L, QColor.darkYellow);
		tileColors.put(TileTypeEnum.BRAM_INT_INTERFACE_R, QColor.darkYellow);
		tileColors.put(TileTypeEnum.BRAM_L, QColor.darkMagenta);
		tileColors.put(TileTypeEnum.BRAM_R, QColor.darkMagenta);
		tileColors.put(TileTypeEnum.BRKH_CLK, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_DSP_L, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_DSP_R, QColor.darkBlue);
		tileColors.put(TileTypeEnum.BRKH_TERM_INT, QColor.darkBlue);
		tileColors.put(TileTypeEnum.B_TERM_INT_NOUTURN, QColor.black);
		tileColors.put(TileTypeEnum.B_TERM_INT_SLV, QColor.black);
		tileColors.put(TileTypeEnum.CFG_CENTER_BOT, QColor.black);
		tileColors.put(TileTypeEnum.CFG_CENTER_MID, QColor.black);
		tileColors.put(TileTypeEnum.CFG_CENTER_MID_SLAVE, QColor.black);
		tileColors.put(TileTypeEnum.CFG_CENTER_TOP, QColor.black);
		tileColors.put(TileTypeEnum.CFG_CENTER_TOP_SLAVE, QColor.black);
		tileColors.put(TileTypeEnum.CLBLL_L, QColor.blue);
		tileColors.put(TileTypeEnum.CLBLL_R, QColor.blue);
		tileColors.put(TileTypeEnum.CLBLM_L, QColor.blue);
		tileColors.put(TileTypeEnum.CLBLM_R, QColor.blue);
		tileColors.put(TileTypeEnum.CLK_BALI_REBUF, QColor.black);
		tileColors.put(TileTypeEnum.CLK_BUFG_BOT_R, QColor.black);
		tileColors.put(TileTypeEnum.CLK_BUFG_REBUF, QColor.black);
		tileColors.put(TileTypeEnum.CLK_BUFG_TOP_R, QColor.black);
		tileColors.put(TileTypeEnum.CLK_FEED, QColor.black);
		tileColors.put(TileTypeEnum.CLK_HROW_BOT_R, QColor.black);
		tileColors.put(TileTypeEnum.CLK_HROW_TOP_R, QColor.black);
		tileColors.put(TileTypeEnum.CLK_PMV, QColor.black);
		tileColors.put(TileTypeEnum.CLK_TERM, QColor.black);
		tileColors.put(TileTypeEnum.CMT_FIFO_L, QColor.black);
		tileColors.put(TileTypeEnum.CMT_FIFO_R, QColor.black);
		tileColors.put(TileTypeEnum.CMT_PMV, QColor.black);
		tileColors.put(TileTypeEnum.CMT_PMV_L, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_L_LOWER_B, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_L_LOWER_T, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_L_UPPER_B, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_L_UPPER_T, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_R_LOWER_B, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_R_LOWER_T, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_R_UPPER_B, QColor.black);
		tileColors.put(TileTypeEnum.CMT_TOP_R_UPPER_T, QColor.black);
		tileColors.put(TileTypeEnum.DSP_L, QColor.darkCyan);
		tileColors.put(TileTypeEnum.DSP_R, QColor.darkCyan);
		tileColors.put(TileTypeEnum.GTX_CHANNEL_0, QColor.black);
		tileColors.put(TileTypeEnum.GTX_CHANNEL_1, QColor.black);
		tileColors.put(TileTypeEnum.GTX_CHANNEL_2, QColor.black);
		tileColors.put(TileTypeEnum.GTX_CHANNEL_3, QColor.black);
		tileColors.put(TileTypeEnum.GTX_COMMON, QColor.black);
		tileColors.put(TileTypeEnum.GTX_INT_INTERFACE_L, QColor.black);
		tileColors.put(TileTypeEnum.HCLK_CMT, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_CMT_L, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_DSP_L, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_DSP_R, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_FEEDTHRU_1, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_FEEDTHRU_2, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_FIFO_L, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_IOI3, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_L, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_L_BOT_UTURN, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_L_SLV, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_R, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_R_BOT_UTURN, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_R_SLV, QColor.cyan);
		tileColors.put(TileTypeEnum.HCLK_TERM_GTX, QColor.cyan);
		tileColors.put(TileTypeEnum.INT_FEEDTHRU_1, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_FEEDTHRU_2, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_L, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_R, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_L, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_L_SLV, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_L_SLV_FLY, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_R, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_R_SLV, QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_R_SLV_FLY, QColor.darkYellow);
		tileColors.put(TileTypeEnum.IO_INT_INTERFACE_L, QColor.darkYellow);
		tileColors.put(TileTypeEnum.IO_INT_INTERFACE_R, QColor.darkYellow);
		tileColors.put(TileTypeEnum.LIOB18, QColor.black);
		tileColors.put(TileTypeEnum.LIOB18_SING, QColor.black);
		tileColors.put(TileTypeEnum.LIOB33, QColor.black);
		tileColors.put(TileTypeEnum.LIOB33_SING, QColor.black);
		tileColors.put(TileTypeEnum.LIOI3, QColor.black);
		tileColors.put(TileTypeEnum.LIOI3_SING, QColor.black);
		tileColors.put(TileTypeEnum.LIOI3_TBYTESRC, QColor.black);
		tileColors.put(TileTypeEnum.LIOI3_TBYTETERM, QColor.black);
		tileColors.put(TileTypeEnum.LIOI_SING, QColor.black);
		tileColors.put(TileTypeEnum.LIOI_TBYTESRC, QColor.black);
		tileColors.put(TileTypeEnum.LIOI_TBYTETERM, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_BOT, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_BOT_FUJI2, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_BOT_SLAVE, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_MID, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_MID_FUJI2, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_TOP, QColor.black);
		tileColors.put(TileTypeEnum.MONITOR_TOP_FUJI2, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_BOT, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_BOT_LEFT, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_LEFT_L, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_NULL, QColor.black);
		tileColors.put(TileTypeEnum.PCIE_TOP_LEFT, QColor.black);
		tileColors.put(TileTypeEnum.RIOB18, QColor.black);
		tileColors.put(TileTypeEnum.RIOB18_SING, QColor.black);
		tileColors.put(TileTypeEnum.RIOI_SING, QColor.black);
		tileColors.put(TileTypeEnum.RIOI_TBYTESRC, QColor.black);
		tileColors.put(TileTypeEnum.RIOI_TBYTETERM, QColor.black);
		tileColors.put(TileTypeEnum.R_TERM_INT_GTX, QColor.black);
		tileColors.put(TileTypeEnum.TERM_CMT, QColor.black);
		tileColors.put(TileTypeEnum.T_TERM_INT_NOUTURN, QColor.black);
		tileColors.put(TileTypeEnum.T_TERM_INT_SLV, QColor.black);
		tileColors.put(TileTypeEnum.VBRK_EXT, QColor.black);
		// Virtex Ultra Scale
		tileColors.put(TileTypeEnum.AMS,QColor.black);
		tileColors.put(TileTypeEnum.BRAM_RBRK,QColor.darkMagenta);
		tileColors.put(TileTypeEnum.BRAM_TERM_B,QColor.darkMagenta);
		tileColors.put(TileTypeEnum.BRAM_TERM_T,QColor.darkMagenta);
		tileColors.put(TileTypeEnum.CFGIO_CFG_RBRK,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFGIO_IOB,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFG_CFG,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFG_CFG_PCIE_RBRK,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFG_CTR_TERM_B,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFG_CTR_TERM_T,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFG_GAP_CFGBOT,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFG_GAP_CFGTOP,QColor.darkBlue);
		tileColors.put(TileTypeEnum.CFRM_AMS_CFGIO,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_B,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_CBRK_L,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_CBRK_R,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_CFG,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_L_RBRK,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_L_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_L_TERM_T,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_RBRK_B,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_RBRK_CFGIO,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_RBRK_PCIE,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_R_RBRK,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_R_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_R_TERM_T,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_T,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.CFRM_TERM_T,QColor.black);
		tileColors.put(TileTypeEnum.CLEL_L,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_L_RBRK,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_L_TERM_B,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_L_TERM_T,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_R,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_R_RBRK,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_R_TERM_B,QColor.blue);
		tileColors.put(TileTypeEnum.CLEL_R_TERM_T,QColor.blue);
		tileColors.put(TileTypeEnum.CLE_M,QColor.blue);
		tileColors.put(TileTypeEnum.CLE_M_R,QColor.blue);
		tileColors.put(TileTypeEnum.CLE_M_RBRK,QColor.blue);
		tileColors.put(TileTypeEnum.CLE_M_TERM_B,QColor.blue);
		tileColors.put(TileTypeEnum.CLE_M_TERM_T,QColor.blue);
		tileColors.put(TileTypeEnum.CLK_IBRK_FSR2IO,QColor.black);
		tileColors.put(TileTypeEnum.CLEM,QColor.blue);
		tileColors.put(TileTypeEnum.CMAC_CMAC_FT,QColor.black);
		tileColors.put(TileTypeEnum.CMAC_CMAC_LEFT_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.CMAC_CMAC_LEFT_TERM_T_FT,QColor.black);
		tileColors.put(TileTypeEnum.DSP_RBRK,QColor.darkCyan);
		tileColors.put(TileTypeEnum.DSP_TERM_B,QColor.darkCyan);
		tileColors.put(TileTypeEnum.DSP_TERM_T,QColor.darkCyan);
		tileColors.put(TileTypeEnum.FSR_GAP,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_TERM_B_FT,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_TERM_T_FT,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP_RBRK,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.FSR_GAP_TERM_T,QColor.black);
		tileColors.put(TileTypeEnum.GTH_R,QColor.black);
		tileColors.put(TileTypeEnum.GTH_R_RBRK,QColor.black);
		tileColors.put(TileTypeEnum.GTH_R_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.GTH_R_TERM_T,QColor.black);
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_FT,QColor.black);
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_TERM_B_FT,QColor.black);
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_TERM_T_FT,QColor.black);
		tileColors.put(TileTypeEnum.HPHRIO_RBRK_L,QColor.black);
		tileColors.put(TileTypeEnum.HPIO_CBRK_IO,QColor.black);
		tileColors.put(TileTypeEnum.HPIO_L,QColor.black);
		tileColors.put(TileTypeEnum.HPIO_RBRK_L,QColor.black);
		tileColors.put(TileTypeEnum.HPIO_TERM_B_L,QColor.black);
		tileColors.put(TileTypeEnum.HPIO_TERM_T_L,QColor.black);
		tileColors.put(TileTypeEnum.HRIO_L,QColor.black);
		tileColors.put(TileTypeEnum.HRIO_TERM_B_L,QColor.black);
		tileColors.put(TileTypeEnum.ILMAC_CMAC_ILMAC_LEFT_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_AMS_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_CMAC_LEFT_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_FT,QColor.black);
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_LEFT_TERM_B_FT,QColor.black);
		tileColors.put(TileTypeEnum.ILMAC_PCIE3_ILMAC_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.INT_IBRK_FSR2IO,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_IO2XIPHY,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_LEFT_L_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_L_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_FSR2IO,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_IO2XIPHY,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_LEFT_L_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_L_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_R_L,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_XIPHY2INT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_R_L,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_FSR2IO,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_IO2XIPHY,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_LEFT_L_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_L_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_R_L,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_XIPHY2INT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_FSR2IO,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_IO2XIPHY,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_LEFT_L_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_L_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_R_L,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_XIPHY2INT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_IBRK_XIPHY2INT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_RBRK,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_TERM_B,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_TERM_T,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_RBRK,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_TERM_B,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_TERM_T,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_RBRK,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_TERM_B,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_TERM_T,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_RBRK,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_TERM_B,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_TERM_T,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_RBRK,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_TERM_B,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_TERM_T,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_RBRK_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_TERM_B_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_TERM_T_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_RBRK_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_TERM_B_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_TERM_T_FT,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTF_L,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_INTF_R,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_RBRK,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_TERM_B,QColor.darkYellow);
		tileColors.put(TileTypeEnum.INT_TERM_T,QColor.darkYellow);
		tileColors.put(TileTypeEnum.LAGUNA_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.LAGUNA_TERM_T,QColor.black);
		tileColors.put(TileTypeEnum.LAGUNA_TILE,QColor.red);
		tileColors.put(TileTypeEnum.LAG_CLEM2LAGUNA_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.LAG_LAG,QColor.red);
		tileColors.put(TileTypeEnum.LAG_LAGUNA2CLEM_RBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_AMS_CFGIO,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_BRAM_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_BRAM_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CBRK_IO,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CBRK_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CBRK_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLEL_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLEL_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLEL_R_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLEL_R_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLEM_CLKBUF_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLE_M_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_CLE_M_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_DSP_CLKBUF_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_GAP4,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_HPIO_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_HRIO_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_IBRK_IO2XIPHY,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_IBRK_L_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_IBRK_R_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_IBRK_XIPHY2INT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INTF_GT_R_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INTF_L_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INTF_L_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INTF_PCIE_L_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INTF_PCIE_R_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INTF_R_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INT_L,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_INT_R,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_BRAM_L_AUXCLMP_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_BRAM_L_BRAMCLMP_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_GAP50_MINICBRK_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_IBRK_LEFT_L_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_GT_LEFT_L_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_PCIE3_LEFT_L_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_XIPHY_LEFT_L_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_LAGUNA_L_FT,QColor.black);
		tileColors.put(TileTypeEnum.RCLK_RCLK_LAGUNA_R_FT,QColor.black);
		tileColors.put(TileTypeEnum.XIPHY_L,QColor.black);
		tileColors.put(TileTypeEnum.XIPHY_L_RBRK,QColor.black);
		tileColors.put(TileTypeEnum.XIPHY_L_TERM_B,QColor.black);
		tileColors.put(TileTypeEnum.XIPHY_L_TERM_T,QColor.black);

	}
}
