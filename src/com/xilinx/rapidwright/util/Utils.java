/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.TileTypeEnum;

import java.util.EnumSet;
import java.util.Set;

/**
 * This is a helper class for creating PrimitiveTypes and TileTypes
 * as well as helping to categorize TileTypes.
 */
public class Utils{

    private static Set<TileTypeEnum> clbs;

    private static Set<TileTypeEnum> clbms;

    private static Set<TileTypeEnum> dsps;

    private static Set<TileTypeEnum> brams;

    private static Set<TileTypeEnum> ints;

    private static Set<TileTypeEnum> gts;

    private static Set<TileTypeEnum> interconnects;

    private static Set<TileTypeEnum> urams;

    private static Set<TileTypeEnum> lagunas;

    private static Set<TileTypeEnum> clocking;

    private static Set<SiteTypeEnum> lockedSiteTypes;

    private static Set<SiteTypeEnum> moduleSiteTypes;

    private static Set<SiteTypeEnum> sliceTypes;

    public static Set<SiteTypeEnum> dspTypes;

    public static Set<SiteTypeEnum> bramTypes;

    public static Set<SiteTypeEnum> iobTypes;

    public static Set<SiteTypeEnum> uramTypes;

    public static Set<SiteTypeEnum> sliceDspBramUramTypes;

    /**
     * Returns a SiteTypeEnum enum based on the given string. If such
     * an enum does not exist, it will return null.
     * @param s The string to be converted to an enum type
     * @return The SiteTypeEnum corresponding to the string s, null if none exists.
     */
    public static SiteTypeEnum createSiteType(String s) {
        return SiteTypeEnum.valueOf(s.toUpperCase());
    }

    /**
     * Returns a TileTypeEnum enum based on the given string s.  If such an enum
     * does not exist, it will return null
     * @param s The string to be converted to an enum type
     * @return The TileTypeEnum corresponding to String s, null if none exists.
     */
    public static TileTypeEnum createTileType(String s) {
        return TileTypeEnum.valueOf(s.toUpperCase());
    }

    /**
     * Determines if the provided tile type contains SLICE primitive sites
     * of any type.
     * @param type The tile type to test for.
     * @return True if this tile type has SLICE (any kind) primitive sites.
     */
    public static boolean isCLB(TileTypeEnum type) {
        return clbs.contains(type);
    }

    /**
     * Determines if the tile type has a SLICEM site type.
     * @param type
     * @return
     */
    public static boolean isCLBM(TileTypeEnum type) {
        return clbms.contains(type);
    }

    /**
     * Determines if the provided tile type contains DSP primitive sites
     * of any type.
     * @param type The tile type to test for.
     * @return True if this tile type has DSP (any kind) primitive sites.
     */
    public static boolean isDSP(TileTypeEnum type) {
        return dsps.contains(type);
    }

    /**
     * Determines if the provided tile type contains BRAM primitive sites
     * of any type.
     * @param type The tile type to test for.
     * @return True if this tile type has BRAM (any kind) primitive sites.
     */
    public static boolean isBRAM(TileTypeEnum type) {
        return brams.contains(type);
    }

    /**
     * Determines if the provided tile type contains BRAM primitive sites
     * of any type.
     * @param type The tile type to test for.
     * @return True if this tile type has BRAM (any kind) primitive sites.
     */
    public static boolean isSwitchBox(TileTypeEnum type) {
        return ints.contains(type);
    }

    /**
     * Determines if the provided tile type contains GTs primitive sites
     * of any type.
     * @param type The tile type to test for.
     * @return True if this tile type has GT (any kind) primitive sites.
     */
    public static boolean isGt(TileTypeEnum type) {
        return gts.contains(type);
    }

    /**
     * Determines if the provided tile type contains INTERCONNECTs primitive sites
     * of any type.
     * @param type The tile type to test for.
     * @return True if this tile type has INTERCONNECT (any kind) primitive sites.
     */
    public static boolean isInterConnect(TileTypeEnum type) {
        return interconnects.contains(type);
    }

    public static boolean isURAM(TileTypeEnum type) {
        return urams.contains(type);
    }

    public static boolean isLaguna(TileTypeEnum type) {
        return lagunas.contains(type);
    }

    public static boolean isClocking(TileTypeEnum type) {
        return clocking.contains(type);
    }

    public static boolean isLockedSiteType(SiteTypeEnum type) {
        return lockedSiteTypes.contains(type);
    }

    public static boolean isModuleSiteType(SiteTypeEnum type) {
        return moduleSiteTypes.contains(type);
    }

    public static Set<TileTypeEnum> getIntTileTypes() {
        return interconnects;
    }

    public static Set<TileTypeEnum> getCLBTileTypes() {
        return clbs;
    }

    public static Set<TileTypeEnum> getCLBMTileTypes() {
        return clbms;
    }

    public static Set<TileTypeEnum> getDSPTileTypes() {
        return dsps;
    }

    public static Set<TileTypeEnum> getBRAMTileTypes() {
        return brams;
    }

    public static Set<TileTypeEnum> getURAMTileTypes() {
        return urams;
    }

    public static Set<TileTypeEnum> getLagunaTileTypes() {
        return lagunas;
    }

    public static Set<TileTypeEnum> getClockingTileTypes() {
        return clocking;
    }

    public static Set<SiteTypeEnum> getLockedSiteTypes() {
        return lockedSiteTypes;
    }

    public static Set<SiteTypeEnum> getModuleSiteTypes() {
        return moduleSiteTypes;
    }

    public static boolean isSLICE(SiteInst s) {
        return sliceTypes.contains(s.getSiteTypeEnum());
    }

    public static boolean isSLICE(SiteTypeEnum s) {
        return sliceTypes.contains(s);
    }

    public static boolean isDSP(SiteInst s) {
        return dspTypes.contains(s.getSiteTypeEnum());
    }

    public static boolean isBRAM(SiteInst s) {
        return bramTypes.contains(s.getSiteTypeEnum());
    }

    public static boolean isURAM(SiteInst s) {
        return uramTypes.contains(s.getSiteTypeEnum());
    }

    public static boolean isIOB(SiteInst s) {
        return iobTypes.contains(s.getSiteTypeEnum());
    }

    public static boolean isIOB(SiteTypeEnum s) {
        return iobTypes.contains(s);
    }



    static{
        clbs = EnumSet.of(
            TileTypeEnum.CLBLL_L,
            TileTypeEnum.CLBLL_R,
            TileTypeEnum.CLBLM_L,
            TileTypeEnum.CLBLM_R,
            TileTypeEnum.CLEL_L,
            TileTypeEnum.CLEL_R,
            TileTypeEnum.CLE_M,
            TileTypeEnum.CLE_M_R,
            TileTypeEnum.CLEM,
            TileTypeEnum.CLEM_R,
            TileTypeEnum.CLE_E_CORE,
            TileTypeEnum.CLE_W_CORE
        );

        clbms = EnumSet.of(
            TileTypeEnum.CLBLM_L,
            TileTypeEnum.CLBLM_R,
            TileTypeEnum.CLE_M,
            TileTypeEnum.CLE_M_R,
            TileTypeEnum.CLEM,
            TileTypeEnum.CLEM_R
        );

        dsps = EnumSet.of(
            TileTypeEnum.DSP,
            TileTypeEnum.DSP_L,
            TileTypeEnum.DSP_R,
            TileTypeEnum.DSP_ROCF_B_TILE,
            TileTypeEnum.DSP_ROCF_T_TILE
        );

        brams = EnumSet.of(
            TileTypeEnum.BRAM,
            TileTypeEnum.BRAM_L,
            TileTypeEnum.BRAM_R,
            TileTypeEnum.BRAM_ROCF_BL_TILE,
            TileTypeEnum.BRAM_ROCF_BR_TILE,
            TileTypeEnum.BRAM_ROCF_TL_TILE,
            TileTypeEnum.BRAM_ROCF_TR_TILE
        );

        ints = EnumSet.of(
            TileTypeEnum.INT,
            TileTypeEnum.INT_L,
            TileTypeEnum.INT_R,
            TileTypeEnum.INT_L_SLV,
            TileTypeEnum.INT_R_SLV,
            TileTypeEnum.INT_L_SLV_FLY,
            TileTypeEnum.INT_R_SLV_FLY,
            TileTypeEnum.INT_INTF_R,
            TileTypeEnum.INT_INTF_L
        );

        gts = EnumSet.of(
            TileTypeEnum.GTZ_TOP,
            TileTypeEnum.GTZ_BOT,
            TileTypeEnum.GTX_CHANNEL_0,
            TileTypeEnum.GTX_CHANNEL_1,
            TileTypeEnum.GTX_CHANNEL_2,
            TileTypeEnum.GTX_CHANNEL_3,
            TileTypeEnum.GTX_COMMON,
            TileTypeEnum.GTH_CHANNEL_0,
            TileTypeEnum.GTH_CHANNEL_1,
            TileTypeEnum.GTH_CHANNEL_2,
            TileTypeEnum.GTH_CHANNEL_3,
            TileTypeEnum.GTH_COMMON
        );

        interconnects = EnumSet.of(
            TileTypeEnum.INT,
            TileTypeEnum.INT_L,
            //TileTypeEnum.INT_L_SLV,
            //TileTypeEnum.INT_L_SLV_FLY,
            TileTypeEnum.INT_R
            //TileTypeEnum.INT_R_SLV,
            //TileTypeEnum.INT_R_SLV_FLY,
        );

        urams = EnumSet.of(
            TileTypeEnum.URAM_URAM_FT,
            TileTypeEnum.URAM_URAM_DELAY_FT,
            TileTypeEnum.URAM_LOCF_TL_TILE,
            TileTypeEnum.URAM_LOCF_BL_TILE,
            TileTypeEnum.URAM_ROCF_TL_TILE,
            TileTypeEnum.URAM_ROCF_BL_TILE,
            TileTypeEnum.URAM_DELAY_LOCF_TL_TILE,
            TileTypeEnum.URAM_DELAY_ROCF_TL_TILE
        );

        lagunas = EnumSet.of(
                TileTypeEnum.LAG_LAG,       // UltraScale+
                TileTypeEnum.LAGUNA_TILE    // UltraScale
        );

        clocking = EnumSet.of(
                TileTypeEnum.RCLK_CLEM_CLKBUF_L,
                // Versal
                TileTypeEnum.CLK_REBUF_BUFGS_HSR_CORE,
                TileTypeEnum.CLK_PLL_AND_PHY,
                TileTypeEnum.CMT_MMCM
        );

        lockedSiteTypes = EnumSet.of(
            SiteTypeEnum.CONFIG_SITE,
            SiteTypeEnum.BUFG
        );

        sliceTypes = EnumSet.of(
            SiteTypeEnum.SLICEL,
            SiteTypeEnum.SLICEM
        );

        dspTypes = EnumSet.of(
            SiteTypeEnum.DSP48E1,
            SiteTypeEnum.DSP48E2,
            SiteTypeEnum.DSP58,
            SiteTypeEnum.DSP58_CPLX,
            SiteTypeEnum.DSPFP,
            SiteTypeEnum.DSP58_PRIMARY
        );

        bramTypes = EnumSet.of(
            SiteTypeEnum.FIFO18_0,
            SiteTypeEnum.FIFO18E1,
            SiteTypeEnum.FIFO36,
            SiteTypeEnum.FIFO36E1,
            SiteTypeEnum.RAMB180,
            SiteTypeEnum.RAMB181,
            SiteTypeEnum.RAMB18E1,
            SiteTypeEnum.RAMB36,
            SiteTypeEnum.RAMB36E1,
            SiteTypeEnum.RAMBFIFO18,
            SiteTypeEnum.RAMBFIFO36,
            SiteTypeEnum.RAMBFIFO36E1,
            SiteTypeEnum.RAMB18_L,
            SiteTypeEnum.RAMB18_U
        );

        iobTypes = EnumSet.of(
            SiteTypeEnum.IOB18,
            SiteTypeEnum.IOB18M,
            SiteTypeEnum.IOB18S,
            SiteTypeEnum.IOB33,
            SiteTypeEnum.IOB33M,
            SiteTypeEnum.IOB33S,
            SiteTypeEnum.IOB,
            SiteTypeEnum.IOBM,
            SiteTypeEnum.IOBS,
            SiteTypeEnum.HDIOB,
            SiteTypeEnum.HDIOB_M,
            SiteTypeEnum.HDIOB_S,
            SiteTypeEnum.HPIOB,
            SiteTypeEnum.HPIOB_M,
            SiteTypeEnum.HPIOB_S,
            SiteTypeEnum.HPIOB,
            SiteTypeEnum.HPIOB_DCI_SNGL,
            SiteTypeEnum.HPIOB_SNGL,
            SiteTypeEnum.HPIOBDIFFINBUF,
            SiteTypeEnum.HPIOBDIFFOUTBUF,
            SiteTypeEnum.HRIO,
            SiteTypeEnum.HRIODIFFINBUF,
            SiteTypeEnum.HRIODIFFOUTBUF,
            SiteTypeEnum.XPIOB
        );

        uramTypes = EnumSet.of(
            SiteTypeEnum.URAM288
        );

        sliceDspBramUramTypes = EnumSet.noneOf(SiteTypeEnum.class);
        sliceDspBramUramTypes.addAll(sliceTypes);
        sliceDspBramUramTypes.addAll(dspTypes);
        sliceDspBramUramTypes.addAll(bramTypes);
        sliceDspBramUramTypes.addAll(uramTypes);

        moduleSiteTypes = EnumSet.of(
            SiteTypeEnum.LAGUNA,
            SiteTypeEnum.BUFGCE
            // SiteTypeEnum.PS7
        );
        moduleSiteTypes.addAll(sliceDspBramUramTypes);
    }

}
