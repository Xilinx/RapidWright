/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development
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
package com.xilinx.rapidwright.design.shapes;

import java.util.List;

import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * Set of attributes applied to a cell that is part of a shape.
 */
public class ShapeLocation {

    private List<SiteTypeEnum> compatibleSiteTypes;

    private String belName;

    private int dx;

    private int dy;

    public ShapeLocation(List<SiteTypeEnum> siteTypes, String belName, int dx, int dy) {
        this.compatibleSiteTypes = siteTypes;
        this.belName = belName;
        this.dx = dx;
        this.dy = dy;
    }

    /**
     * @return the compatibleSiteTypes
     */
    public List<SiteTypeEnum> getCompatibleSiteTypes() {
        return compatibleSiteTypes;
    }

    /**
     * @return the belName
     */
    public String getBelName() {
        return belName;
    }

    public void setBelName(String belName) {
        this.belName = belName;
    }

    /**
     * @return the dx
     */
    public int getDx() {
        return dx;
    }

    /**
     * @return the dy
     */
    public int getDy() {
        return dy;
    }

    public void setDx(int dx) {
        this.dx = dx;
    }

    public void setDy(int dy) {
        this.dy = dy;
    }

}
