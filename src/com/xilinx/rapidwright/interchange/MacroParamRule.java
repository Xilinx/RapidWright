package com.xilinx.rapidwright.interchange;

public class MacroParamRule {
    public String primParam;
    public String instName;
    public String instParam;
    public int[] bitSlice;
    public ParameterMapEntry[] tableLookup;

    public MacroParamRule(String primParam, String instName, String instParam) {
        this.primParam = primParam;
        this.instName = instName;
        this.instParam = instParam;
        this.bitSlice = null;
        this.tableLookup = null;
    }

    public static MacroParamRule copy(String primParam, String instName, String instParam) {
        return new MacroParamRule(primParam, instName, instParam);
    }

    public static MacroParamRule bit_range(String primParam, String instName, String instParam, int start, int end) {
        MacroParamRule result = new MacroParamRule(primParam, instName, instParam);
        result.bitSlice = new int[(end-start) + 1];
        for (int i = 0; i <= (end-start); i++)
            result.bitSlice[i] = start + i;
        return result;
    }

    public static MacroParamRule table(String primParam, String instName, String instParam, ParameterMapEntry[] table) {
        MacroParamRule result = new MacroParamRule(primParam, instName, instParam);
        result.tableLookup = table;
        return result;
    }
}
