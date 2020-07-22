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
}