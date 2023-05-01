/*
 *
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AECG Research Labs.
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

package com.xilinx.rapidwright.util;

/**
 * Aims to be a centralized helper class to manage global RapidWright settings.
 */
public class Params {

    public static String RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK_NAME = "RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK";

    public static String RW_ZSTD_COMPRESSION_LEVEL_NAME = "RW_ZSTD_COMPRESSION_LEVEL";

    public static int RW_ZSTD_DEFAULT_COMPRESSION_LEVEL = 3;

    /**
     * Flag to have RapidWright decompress gzipped EDIF files to disk prior to
     * parsing. This is a tradeoff where pre-decompression improves runtime over the
     * default method which is to decompress in memory. The disadvantage is that
     * this will temporarily consume ~18x more disk space than original gzipped EDIF
     * file, but loading of the EDIF file will be ~2x faster.
     */
    public static boolean RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK = isParamSet(RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK_NAME);
    
    /**
     * ZStandard compression effort level to use when compressing files. This can
     * range from -7 to 22, with higher numbers producing a more compact result for
     * more runtime.
     */
    public static int RW_ZSTD_COMPRESSION_LEVEL = getParamOrDefaultIntSetting(RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK_NAME,
            RW_ZSTD_DEFAULT_COMPRESSION_LEVEL);

    /**
     * Checks if the named RapidWright parameter is set via an environment variable
     * or by a JVM parameter of the same name.
     * 
     * @param key Name of the global RapidWright parameter
     * @return True if the parameter is set (as defined by {@link #isSet(String)}),
     *         false otherwise
     */
    public static boolean isParamSet(String key) {
        return isSet(System.getenv(key)) || isSet(System.getProperty(key));
    }

    /**
     * Checks if a parameter is set by examining the provided value.
     * 
     * @param value An environment variable or JVM parameter value
     * @return True if (1) value is not null, (2) is not an empty string, (3) is not
     *         0 and (4) is not false (case-insensitive).
     */
    public static boolean isSet(String value) {
        return !( value == null 
               || value.length() == 0 
               || value.equals("0") 
               || value.toLowerCase().equals("false")
               );         
    }

    /**
     * Gets the integer value of the provided parameter name.
     * 
     * @param key Name of the system parameter to get.
     * @return The set integer value of the parameter, or null if none was set. If
     *         the property is set to a value that is not a parsable integer, a
     *         warning message is produced and returns null.
     */
    public static Integer getParamIntValue(String key) {
        String envValue = getParamValue(key);
        if (envValue != null) {
            try {
                return Integer.parseInt(envValue);
            } catch (NumberFormatException e) {
                System.err.println("WARNING: Couldn't interpret the value '" + envValue 
                        + "' from the parameter '" + key + "' as an integer.");
            }
        }
        return null;
    }

    /**
     * Gets the string value of the provided parameter name.
     * 
     * @param key Name of the system parameter to get.
     * @return The set string value of the parameter, or null if none was set.
     */
    public static String getParamValue(String key) {
        String value = System.getenv(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        return value;
    }

    /**
     * Checks the parameter value of the provided key. If it is set, it returns the
     * set value. Otherwise it will return the default value.
     * 
     * @param key          Name of the system parameter to check.
     * @param defaultValue The default value to return if the paramter is not set.
     * @return The system parameter value if is set, otherwise it returns
     *         defaultValue.
     */
    public static int getParamOrDefaultIntSetting(String key, int defaultValue) {
        Integer setValue = getParamIntValue(key);
        return setValue == null ? defaultValue : setValue;
    }

}
