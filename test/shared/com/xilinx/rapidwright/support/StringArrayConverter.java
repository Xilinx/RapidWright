/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AEAI CTO Group.
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

package com.xilinx.rapidwright.support;

import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.SimpleArgumentConverter;

/**
 * Used for converting comma separated String[] definitions in JUnit's CsvSource
 * parameterized tests
 *
 */
public class StringArrayConverter extends SimpleArgumentConverter {

    @Override
    protected Object convert(Object arg0, Class<?> arg1) throws ArgumentConversionException {
        if (arg0 instanceof String && String[].class.isAssignableFrom(arg1)) {
            String value = (String) arg0;
            return value.split("\\s*,\\s*");
        }
        throw new RuntimeException("ERROR: Unrecognized parameter '"+arg0
                                    +"', could not be converted to a String[].");
    }
}
