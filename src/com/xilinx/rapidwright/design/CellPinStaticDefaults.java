/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
package com.xilinx.rapidwright.design;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Helper class to provide default static (GND or VCC) connection values to newly created cells.
 *
 */
public class CellPinStaticDefaults {

    private static Map<Series, Map<Unisim, Map<String, NetType>>> cellPinDefaultsMap;
    
    @SuppressWarnings("unchecked")
    public static Map<Series, Map<Unisim, Map<String, NetType>>> getCellPinDefaultsMap(){
        if(cellPinDefaultsMap == null) {
            InputStream is = FileTools.getRapidWrightResourceInputStream(
                    FileTools.CELL_PIN_DEFAULTS_FILE_NAME);
            cellPinDefaultsMap = (Map<Series, Map<Unisim, Map<String, NetType>>>) 
                    FileTools.readObjectFromKryoFile(is);
        }
        return cellPinDefaultsMap;
    }
    
    public static NetType getCellPinDefault(Series s, Unisim u, String pinName) {
        Map<Series, Map<Unisim, Map<String, NetType>>> map = getCellPinDefaultsMap();
        Map<Unisim, Map<String, NetType>> map2 = map.get(s);
        if(map2 != null) {
            Map<String, NetType> map3 = map2.get(u);
            if(map3 != null) {
                return map3.get(pinName);
            }
        }
        return null;
    }
    
    private static void writeToFileDefaultsMap(
            Map<Series, Map<Unisim, Map<String,NetType>>> pinDefaults, 
            String fileName) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(fileName));
        for(Entry<Series, Map<Unisim, Map<String,NetType>>> e : pinDefaults.entrySet()) {
            bw.write(e.getKey() + ":\n");
            for(Entry<Unisim, Map<String,NetType>> e2 : e.getValue().entrySet()) {
                bw.write("  " + e2.getKey() + ":\n");
                for(Entry<String,NetType> e3 : e2.getValue().entrySet()) {
                    bw.write("    " + e3.getKey() +"=" + e3.getValue() + "\n");
                }
            }
        }
        bw.close();
    }
    
    public static void main(String[] args) throws IOException {
        Map<Series, Map<Unisim, Map<String, NetType>>> map = getCellPinDefaultsMap();
        writeToFileDefaultsMap(map, "map.txt");
    }
}
