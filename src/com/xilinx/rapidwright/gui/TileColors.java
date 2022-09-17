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

import io.qt.core.Qt;
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


		tileColors.put(TileTypeEnum.BRAM, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRKH_BRAM, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_B_TERM_INT, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_CLB, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_CMT, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_GTX, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_INT, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.B_TERM_INT, new QColor(Qt.GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.CLEM_R, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.DSP, new QColor(Qt.GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.GTP_INT_INTERFACE, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_INT_INTERFACE, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HCLK_BRAM, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_CLB, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_GTX, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_INT_INTERFACE, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_IOB, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_IOI, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_TERM, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_VBRK, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_VFRAME, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.INT, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.LIOI, new QColor(Qt.GlobalColor.darkGreen));
		tileColors.put(TileTypeEnum.L_TERM_INT, new QColor(Qt.GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.NULL, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_L, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_R, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_TOP, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI, new QColor(Qt.GlobalColor.darkGreen));
		tileColors.put(TileTypeEnum.R_TERM_INT, new QColor(Qt.GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.T_TERM_INT, new QColor(Qt.GlobalColor.darkGray));
		tileColors.put(TileTypeEnum.VBRK, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.VFRAME, new QColor(Qt.GlobalColor.black));
		// Virtex 7, Kintex 7
		tileColors.put(TileTypeEnum.BRAM_INT_INTERFACE_L, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.BRAM_INT_INTERFACE_R, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.BRAM_L, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRAM_R, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRKH_CLK, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_DSP_L, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_DSP_R, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.BRKH_TERM_INT, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.B_TERM_INT_NOUTURN, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.B_TERM_INT_SLV, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_BOT, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_MID, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_MID_SLAVE, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_TOP, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFG_CENTER_TOP_SLAVE, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLBLL_L, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLBLL_R, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLBLM_L, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLBLM_R, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLK_BALI_REBUF, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_BUFG_BOT_R, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_BUFG_REBUF, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_BUFG_TOP_R, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_FEED, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_HROW_BOT_R, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_HROW_TOP_R, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_PMV, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLK_TERM, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_FIFO_L, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_FIFO_R, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_PMV, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_PMV_L, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_LOWER_B, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_LOWER_T, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_UPPER_B, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_L_UPPER_T, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_LOWER_B, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_LOWER_T, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_UPPER_B, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMT_TOP_R_UPPER_T, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.DSP_L, new QColor(Qt.GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.DSP_R, new QColor(Qt.GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_0, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_1, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_2, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_CHANNEL_3, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_COMMON, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTX_INT_INTERFACE_L, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HCLK_CMT, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_CMT_L, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_DSP_L, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_DSP_R, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_FEEDTHRU_1, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_FEEDTHRU_2, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_FIFO_L, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_IOI3, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_L, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_L_BOT_UTURN, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_L_SLV, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_R, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_R_BOT_UTURN, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_R_SLV, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.HCLK_TERM_GTX, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.INT_FEEDTHRU_1, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_FEEDTHRU_2, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_L, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_L_SLV, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_L_SLV_FLY, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_R, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_R_SLV, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_R_SLV_FLY, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.IO_INT_INTERFACE_L, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.IO_INT_INTERFACE_R, new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.LIOB18, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOB18_SING, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOB33, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOB33_SING, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3_SING, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3_TBYTESRC, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI3_TBYTETERM, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI_SING, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI_TBYTESRC, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LIOI_TBYTETERM, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_BOT, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_BOT_FUJI2, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_BOT_SLAVE, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_MID, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_MID_FUJI2, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_TOP, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.MONITOR_TOP_FUJI2, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_BOT, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_BOT_LEFT, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_INT_INTERFACE_LEFT_L, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_NULL, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.PCIE_TOP_LEFT, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOB18, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOB18_SING, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI_SING, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI_TBYTESRC, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RIOI_TBYTETERM, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.R_TERM_INT_GTX, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.TERM_CMT, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.T_TERM_INT_NOUTURN, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.T_TERM_INT_SLV, new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.VBRK_EXT, new QColor(Qt.GlobalColor.black));
		// Virtex Ultra Scale
		tileColors.put(TileTypeEnum.AMS,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.BRAM_RBRK,new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRAM_TERM_B,new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.BRAM_TERM_T,new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.CFGIO_CFG_RBRK,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFGIO_IOB,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CFG,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CFG_PCIE_RBRK,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CTR_TERM_B,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_CTR_TERM_T,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_GAP_CFGBOT,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFG_GAP_CFGTOP,new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CFRM_AMS_CFGIO,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_CBRK_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_CBRK_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_CFG,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_L_RBRK,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_L_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_L_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_RBRK_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_RBRK_CFGIO,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_RBRK_PCIE,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_R_RBRK,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_R_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_R_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CFRM_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLEL_L,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_L_RBRK,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_L_TERM_B,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_L_TERM_T,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R_RBRK,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R_TERM_B,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLEL_R_TERM_T,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_R,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_RBRK,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_TERM_B,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_M_TERM_T,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLK_IBRK_FSR2IO,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CLEM,new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CMAC_CMAC_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMAC_CMAC_LEFT_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.CMAC_CMAC_LEFT_TERM_T_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.DSP_RBRK,new QColor(Qt.GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.DSP_TERM_B,new QColor(Qt.GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.DSP_TERM_T,new QColor(Qt.GlobalColor.darkCyan));
		tileColors.put(TileTypeEnum.FSR_GAP,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_TERM_B_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP50_MINICBRK_TERM_T_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP_RBRK,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.FSR_GAP_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R_RBRK,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTH_R_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_TERM_B_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.GTY_QUAD_LEFT_TERM_T_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HPHRIO_RBRK_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_CBRK_IO,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_RBRK_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_TERM_B_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HPIO_TERM_T_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HRIO_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.HRIO_TERM_B_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_CMAC_ILMAC_LEFT_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_AMS_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_CMAC_LEFT_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_ILMAC_LEFT_TERM_B_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.ILMAC_PCIE3_ILMAC_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.INT_IBRK_FSR2IO,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_IO2XIPHY,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_LEFT_L_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_L_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_FSR2IO,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_IO2XIPHY,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_LEFT_L_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_L_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_R_L,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_RBRK_XIPHY2INT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_R_L,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_FSR2IO,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_IO2XIPHY,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_LEFT_L_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_L_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_R_L,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_B_XIPHY2INT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_FSR2IO,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_IO2XIPHY,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_LEFT_L_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_L_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_R_L,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_TERM_T_XIPHY2INT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_IBRK_XIPHY2INT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_RBRK,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_TERM_B,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_GT_R_TERM_T,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_RBRK,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_TERM_B,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_L_TERM_T,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_RBRK,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_TERM_B,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_L_TERM_T,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_RBRK,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_TERM_B,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_PCIE_R_TERM_T,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_RBRK,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_TERM_B,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTERFACE_R_TERM_T,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_RBRK_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_TERM_B_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_GT_LEFT_TERM_T_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_RBRK_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_TERM_B_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INT_INTERFACE_XIPHY_TERM_T_FT,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTF_L,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_INTF_R,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_RBRK,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_TERM_B,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INT_TERM_T,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.LAGUNA_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LAGUNA_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LAGUNA_TILE,new QColor(Qt.GlobalColor.red));
		tileColors.put(TileTypeEnum.LAG_CLEM2LAGUNA_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.LAG_LAG,new QColor(Qt.GlobalColor.red));
		tileColors.put(TileTypeEnum.LAG_LAGUNA2CLEM_RBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_AMS_CFGIO,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_BRAM_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_BRAM_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CBRK_IO,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CBRK_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CBRK_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_R_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEL_R_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLEM_CLKBUF_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLE_M_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_CLE_M_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_DSP_CLKBUF_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_GAP4,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_HPIO_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_HRIO_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_IO2XIPHY,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_L_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_R_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_IBRK_XIPHY2INT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_GT_R_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_L_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_L_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_PCIE_L_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_PCIE_R_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INTF_R_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INT_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_INT_R,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_BRAM_L_AUXCLMP_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_BRAM_L_BRAMCLMP_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_GAP50_MINICBRK_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_IBRK_LEFT_L_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_GT_LEFT_L_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_PCIE3_LEFT_L_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_INTF_XIPHY_LEFT_L_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_LAGUNA_L_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.RCLK_RCLK_LAGUNA_R_FT,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L_RBRK,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L_TERM_B,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.XIPHY_L_TERM_T,new QColor(Qt.GlobalColor.black));
		tileColors.put(TileTypeEnum.URAM_URAM_FT,new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_URAM_DELAY_FT,new QColor(Qt.GlobalColor.darkMagenta));

		// Versal
		tileColors.put(TileTypeEnum.CLE_E_CORE, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.CLE_W_CORE, new QColor(Qt.GlobalColor.blue));
		tileColors.put(TileTypeEnum.DSP_ROCF_B_TILE, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.DSP_ROCF_T_TILE, new QColor(Qt.GlobalColor.cyan));
		tileColors.put(TileTypeEnum.BRAM_ROCF_BL_TILE, new QColor(Qt.GlobalColor.magenta));
		tileColors.put(TileTypeEnum.BRAM_ROCF_BR_TILE, new QColor(Qt.GlobalColor.magenta));
		tileColors.put(TileTypeEnum.BRAM_ROCF_TL_TILE, new QColor(Qt.GlobalColor.magenta));
		tileColors.put(TileTypeEnum.BRAM_ROCF_TR_TILE, new QColor(Qt.GlobalColor.magenta));
		tileColors.put(TileTypeEnum.INTF_ROCF_BL_TILE,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INTF_ROCF_BR_TILE,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INTF_ROCF_TL_TILE,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.INTF_ROCF_TR_TILE,new QColor(Qt.GlobalColor.darkYellow));
		tileColors.put(TileTypeEnum.CLE_BC_CORE, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.CLE_BC_CORE_MX, new QColor(Qt.GlobalColor.darkBlue));
		tileColors.put(TileTypeEnum.URAM_LOCF_TL_TILE, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_LOCF_BL_TILE, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_ROCF_TL_TILE, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_ROCF_BL_TILE, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_DELAY_ROCF_TL_TILE, new QColor(Qt.GlobalColor.darkMagenta));
		tileColors.put(TileTypeEnum.URAM_DELAY_LOCF_TL_TILE, new QColor(Qt.GlobalColor.darkMagenta));
	}
}
