package com.xilinx.rapidwright.edif;

import java.util.HashMap;
import java.util.Map;

public class EDIFWriteLegalNameCache {

    /**
     * Maps from unsuffixed legal name to the number of times we saw it from different source names
     */
    Map<String, Integer> usedRenames = new HashMap<>();
    /**
     * The actual renamed names
     */
    Map<String, String> renames = new HashMap<>();
    private String calcRename(String name) {
        final String rename = EDIFTools.makeNameEDIFCompatible(name);
        if (rename.equals(name)) {
            return null;
        }
        Integer previousCount = usedRenames.get(rename);
        if (previousCount == null) {
            usedRenames.put(rename,0);
            return rename;
        }
        usedRenames.put(rename, previousCount+1);
        return rename+"_HDI_"+previousCount;
    }

    public String getEDIFRename(String name) {
        //We have null values, can't use get directly, nor computeIfAbsent
        if (renames.containsKey(name)) {
            return renames.get(name);
        }

        final String rename = calcRename(name);
        renames.put(name, rename);
        return rename;
    }
}
