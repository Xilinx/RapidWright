/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

import java.util.Objects;

class EDIFToken {
    public final String text;
    public final long byteOffset;

    public EDIFToken(String token, long byteOffset) {
        this.text = Objects.requireNonNull(token);
        this.byteOffset = byteOffset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EDIFToken edifToken = (EDIFToken) o;
        return byteOffset == edifToken.byteOffset && text.equals(edifToken.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, byteOffset);
    }

    @Override
    public String toString() {
        String displayText = text;
        if (text.length()>120) {
            displayText = text.substring(0,100)+"[shortened, length is "+text.length()+"]";
        }
        return displayText +"@"+byteOffset;
    }

}
