package com.xilinx.rapidwright.edif;

import java.util.HashMap;
import java.util.Map;

public class EDIFWriteLegalNameCache {
    public String getEDIFName(String name) {
        return renames.computeIfAbsent(name, n -> {
            final String rename = EDIFTools.makeNameEDIFCompatible(n);
            if (rename.equals(n)) {
                return null;
            }
            Integer previousCount = usedRenames.get(rename);
            if (previousCount == null) {
                usedRenames.put(rename,0);
                return rename;
            }
            usedRenames.put(rename, previousCount+1);
            return rename+"_HDI_"+previousCount;
        });
    }


    Map<String, Integer> usedRenames = new HashMap<>();
    Map<String, String> renames = new HashMap<>();

    public String getEDIFName(EDIFName named) {
        if (named instanceof EDIFCell) {
            throw new RuntimeException("don't call it with edifcells!");
        }
        return getEDIFName(named.getName());
    }
    public String getLegalEDIFName(EDIFName named) {
        final String rename = getEDIFName(named);
        if (rename != null) {
            return rename;
        }
        return named.getName();
    }
}
