/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import org.capnproto.MessageBuilder;
import org.capnproto.Serialize;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestCapnp {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10})
    public void testStructList(int size) {
        MessageBuilder message = new MessageBuilder();
        PhysicalNetlist.PhysNetlist.RouteBranch.Builder routeBranch = message.initRoot(PhysicalNetlist.PhysNetlist.RouteBranch.factory);

        long beforeWords = Serialize.computeSerializedSizeInWords(message);
        routeBranch.initBranches(size);
        long afterWords = Serialize.computeSerializedSizeInWords(message);
        if (size == 0) {
            Assertions.assertEquals(beforeWords + 1 /* struct tag */, afterWords);

            // It is cheaper to not initialize a struct list (staying at beforeWords)
            // than it is to initialize one with zero elements due to the struct tag
        } else {
            Assertions.assertEquals(beforeWords + 1 /* struct tag */ +
                            size * PhysicalNetlist.PhysNetlist.RouteBranch.STRUCT_SIZE.total(),
                    afterWords);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10})
    public void testPrimitiveList(int size) {
        MessageBuilder message = new MessageBuilder();
        LogicalNetlist.Netlist.Bitstring.Builder bitstring = message.initRoot(LogicalNetlist.Netlist.Bitstring.factory);

        long beforeWords = Serialize.computeSerializedSizeInWords(message);
        bitstring.initData(size);
        long afterWords = Serialize.computeSerializedSizeInWords(message);
        if (size == 0) {
            Assertions.assertEquals(beforeWords, afterWords);
            // No size difference between initializing or not
        } else {
            // Bitstring is a List(UInt8) thus round up to next 8-byte word
            Assertions.assertEquals(beforeWords + (size + 7) / 8,
                    afterWords);
        }
    }
}
