/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.design.drc.NetRoutesThruLutAtMostOnce;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestDRC {
    @Test
    @CheckOpenFiles
    public void testRoutethruPass() {
        /*
         * DCP created from:
         *    module top(input [2:0] i);
         *      (* DONT_TOUCH, LOC="SLICE_X0Y0" *) CARRY4 c4(.S(i[0]));
         *      (* DONT_TOUCH, LOC="SLICE_X0Y0", BEL="A5FF"*) FDRE ff5(.D(i[1]));
         *      (* DONT_TOUCH, LOC="SLICE_X0Y0", BEL="AFF" *) FDRE ff6(.D(i[2]));
         *    endmodule
         * containing A3 -> O6 -> S0 and A1 -> O5 -> AFF.D intra-site routethrus of
         * different nets (along with AX -> A5FF.D)
         */
        final String dcpPath = RapidWrightDCP.getString("routethru_luts.dcp");
        Design design = Design.readCheckpoint(dcpPath);

        NetRoutesThruLutAtMostOnce drc = new NetRoutesThruLutAtMostOnce();
        boolean strict = true;
        Assertions.assertEquals(drc.run(design, strict), 0);
    }

    @Test
    @CheckOpenFiles
    public void testRoutethruFail() {
        /*
         * DCP derived from https://github.com/Xilinx/RapidWright/issues/226#issuecomment-906164846
         * (specifically, the interchange input has been re-converted into a new DCP after
         * PRs #253 and #254)
         *
         * Contains an inter-site routethru PIP A4 -> A, and an intra-site A5 -> O5 -> AFF.D
         * routethru of the *same* net.
         */
        final String dcpPath = RapidWrightDCP.getString("bug226.dcp");
        Design design = Design.readCheckpoint(dcpPath);

        NetRoutesThruLutAtMostOnce drc = new NetRoutesThruLutAtMostOnce();
        boolean strict = true;
        Assertions.assertEquals(drc.run(design, strict), 4);
    }

}
