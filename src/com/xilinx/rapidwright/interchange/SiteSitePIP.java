/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;

public class SiteSitePIP {
    public Site site;
    public SitePIP sitePIP;
    public boolean isFixed;

    public SiteSitePIP(Site site, SitePIP sitePIP, boolean isFixed) {
        this.site = site;
        this.sitePIP = sitePIP;
        this.isFixed = isFixed;
    }

    public String toString() {
        return site.getName() + "/" + sitePIP.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((site == null) ? 0 : site.hashCode());
        result = prime * result
                + ((sitePIP == null) ? 0 : sitePIP.hashCode());
        result = prime * result + (isFixed ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        SiteSitePIP other = (SiteSitePIP) obj;
        if (!site.equals(other.site))
            return false;
        if (!sitePIP.equals(other.sitePIP))
            return false;
        return true;
    }
}
