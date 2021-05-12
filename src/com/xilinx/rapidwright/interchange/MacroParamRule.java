package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.design.Unisim;

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

    public static MacroParamRule bit_range(String primParam, String instName, String instParam, int start, int count) {
        MacroParamRule result = new MacroParamRule(primParam, instName, instParam);
        result.bitSlice = new int[count];
        for (int i = 0; i < count; i++)
            result.bitSlice[i] = start + i;
        return result;
    }

    public static MacroParamRule table(String primParam, String instName, String instParam, ParameterMapEntry[] table) {
        MacroParamRule result = new MacroParamRule(primParam, instName, instParam);
        result.tableLookup = table;
        return result;
    }

    public static MacroParamRule[] getRules(Unisim prim) {
        switch (prim) {
            case LUT6_2:
                return new MacroParamRule[] {
                    bit_range("INIT", "LUT5", "INIT", 0, 32),
                    bit_range("INIT", "LUT6", "INIT", 0, 64),
                };
            case RAM64X1D:
                return new MacroParamRule[] {
                    bit_range("INIT", "DP", "INIT", 0, 64),
                    bit_range("INIT", "SP", "INIT", 0, 64),
                };
            case RAM128X1D:
                return new MacroParamRule[] {
                    bit_range("INIT", "DP.LOW", "INIT", 0,  64),
                    bit_range("INIT", "DP.HIGH", "INIT", 64, 64),
                    bit_range("INIT", "SP.LOW", "INIT", 0,  64),
                    bit_range("INIT", "SP.HIGH", "INIT", 64, 64),
                };
            case RAM64M:
                return new MacroParamRule[] {
                    bit_range("INIT_A", "RAMA", "INIT", 0, 64),
                    bit_range("INIT_B", "RAMB", "INIT", 0, 64),
                    bit_range("INIT_C", "RAMC", "INIT", 0, 64),
                    bit_range("INIT_D", "RAMD", "INIT", 0, 64),
                };
            default:
                return new MacroParamRule[0];
        }
    }
}
