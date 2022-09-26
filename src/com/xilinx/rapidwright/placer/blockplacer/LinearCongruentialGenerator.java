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

package com.xilinx.rapidwright.placer.blockplacer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * This class creates a random permutation of all ints from 0 (inclusive) to a given maximum (exclusive) with no repeats.
 *
 * It implements a linear congruential generator and achieves O(N) in runtime, O(1) in memory.
 */
public class LinearCongruentialGenerator implements PrimitiveIterator.OfInt {
    private final int max;
    private int value;
    private final int offset;
    private final int multiplier;
    private final int modulus;
    private int outputCount;

    public static int nextPowerOf2(int i) {
        int r = 1;
        while (r<i) {
            r=r*2;
        }
        return r;
    }

    private LinearCongruentialGenerator(
            int max, int value, int offset, int multiplier, int modulus, int outputCount
    ) {
        this.max = max;
        this.value = value;
        this.offset = offset;
        this.multiplier = multiplier;
        this.modulus = modulus;
        this.outputCount = outputCount;
    }

    public LinearCongruentialGenerator(int max, Random random) {
        this.max = max;
        value = random.nextInt(max);

        //See https://en.wikipedia.org/wiki/Linear_congruential_generator#cite_ref-KnuthV2_1-3

        offset = random.nextInt(max) * 2 + 1;
        multiplier = 4*(max/4)+1;
        modulus = nextPowerOf2(max);
    }


    @Override
    public boolean hasNext() {
        return outputCount<max;
    }

    @Override
    public int nextInt() {
        int output = value;
        do {
            //Using long here, because value * multiplier might take us above the limit of int
            long v = value;
            value = (int) ((v * multiplier + offset) % modulus);
        } while (value>=max);
        outputCount ++;
        return output;
    }

    @Override
    public LinearCongruentialGenerator clone() {
        return new LinearCongruentialGenerator(max, value, offset, multiplier, modulus, outputCount);
    }

    public void dump(Path p) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(p))) {
            forEachRemaining((int i)->pw.println(i));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Spliterator.OfInt spliterator() {
        return Spliterators.spliterator(this, max-outputCount, Spliterator.DISTINCT|Spliterator.ORDERED|Spliterator.SIZED);
    }
}
