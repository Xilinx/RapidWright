/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.edif;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestEDIFPropertyValue {
    @ParameterizedTest
    @CsvSource({
            "1'b1,INTEGER,true",
            "1'b0,INTEGER,false",
            "32'hDeAdBeEf,INTEGER,true",

            "1'b1,STRING,true",
            "1'b0,STRING,false",
            "32'hDeAdBeEf,STRING,true",
            "false,STRING,false",
            "FaLsE,STRING,false",
            "true,STRING,true",
            "deadbeef,STRING,true",

            "false,BOOLEAN,false",
            "FALSE,BOOLEAN,false",
            "true,BOOLEAN,true",
    })
    public void testGetBooleanValue(String value, EDIFValueType type, boolean expected) {
        EDIFPropertyValue v = new EDIFPropertyValue(value, type);
        Assertions.assertEquals(expected, v.getBooleanValue());
    }
}
