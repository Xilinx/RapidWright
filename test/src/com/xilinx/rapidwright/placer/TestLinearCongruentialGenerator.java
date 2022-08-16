/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.placer;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import com.xilinx.rapidwright.placer.blockplacer.LinearCongruentialGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestLinearCongruentialGenerator {
    private void verifyOutput(int max, IntStream sequence) {
        final List<Integer> list = sequence.boxed().collect(Collectors.toList());
        System.out.println(list);
        Assertions.assertEquals(max, list.size());

        for (int i = 0; i < max; i++) {
            Assertions.assertTrue(list.contains(i), "Sequence should contain "+i);
        }
    }
    @Test
    public void testLcg() {
        for (int i=1;i<100;i++) {
            final IntStream stream = StreamSupport.intStream(new LinearCongruentialGenerator(i, new Random(42)), false);
            verifyOutput(i, stream);
        }
    }
}
