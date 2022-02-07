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