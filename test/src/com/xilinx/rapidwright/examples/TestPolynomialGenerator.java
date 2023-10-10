/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development
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

package com.xilinx.rapidwright.examples;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

public class TestPolynomialGenerator {

    @ParameterizedTest
    @MethodSource
    public void testPolynomialGenerator(String polynomial, int bitWidth, @TempDir Path dir) {
        Path dcp = dir.resolve("polynomial.dcp");

        PolynomialGenerator.generatePolynomial(polynomial, "test", bitWidth, true, dcp.toString(), null, false);

        Design d = Design.readCheckpoint(dcp);

        for (Cell c : d.getCells()) {
            if (c.isLocked() || c.isRoutethru())
                continue;
            assert (c.isPlaced());
        }

        DesignTools.updatePinsIsRouted(d);
        for (Net n : d.getNets()) {
            for (SitePinInst spi : n.getPins()) {
                assert (spi.isRouted());
            }
        }
    }
    
    public static Stream<Arguments> testPolynomialGenerator() {
        return Stream.of(
                Arguments.of("x^2+3*x+5", 16),
                Arguments.of("8*x^4+43*x^3+7*x^2-14", 18),
                Arguments.of("8*y^4+43*y*x^3+7*x^2-14", 18)
                );
    }
}
