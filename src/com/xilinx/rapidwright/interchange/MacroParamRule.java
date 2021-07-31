/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
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

public class MacroParamRule {
    private String primParam;
    private String instName;
    private String instParam;
    private int[] bitSlice;
    private MacroParamTableEntry[] tableLookup;

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

    public static MacroParamRule bitRange(String primParam, String instName, String instParam, int start, int count) {
        MacroParamRule result = new MacroParamRule(primParam, instName, instParam);
        result.bitSlice = new int[count];
        for (int i = 0; i < count; i++)
            result.bitSlice[i] = start + i;
        return result;
    }

    public static MacroParamRule table(String primParam, String instName, String instParam, MacroParamTableEntry[] table) {
        MacroParamRule result = new MacroParamRule(primParam, instName, instParam);
        result.tableLookup = table;
        return result;
    }

    /**
     * @return the primParam
     */
    public String getPrimParam() {
        return primParam;
    }

    /**
     * @return the instName
     */
    public String getInstName() {
        return instName;
    }

    /**
     * @return the instParam
     */
    public String getInstParam() {
        return instParam;
    }

    /**
     * @return the bitSlice
     */
    public int[] getBitSlice() {
        return bitSlice;
    }

    /**
     * @return the tableLookup
     */
    public MacroParamTableEntry[] getTableLookup() {
        return tableLookup;
    }
}
