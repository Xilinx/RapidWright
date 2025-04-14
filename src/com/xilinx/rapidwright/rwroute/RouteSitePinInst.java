/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;

import java.util.ArrayList;
import java.util.List;

public class RouteSitePinInst implements RoutePinInterface {
    private SitePinInst spi;

    RouteSitePinInst(SitePinInst spi) {
        this.spi = spi;
    }

    @Override
    public String getName() {
        return spi.getName();
    }

    @Override
    public Tile getTile() {
        return spi.getTile();
    }

    @Override
    public Node getConnectedNode() {
        return spi.getConnectedNode();
    }

    @Override
    public SitePinInst getSitePinInst() {
        return spi;
    }

    @Override
    public boolean isLUTInputPin() {
        return spi.isLUTInputPin();
    }

    @Override
    public void setRouted(boolean isRouted) {
        spi.setRouted(isRouted);
    }

    @Override
    public boolean isRouted() {
        return spi.isRouted();
    }

    @Override
    public String toString() {
        return spi.toString();
    }

    @Override
    public int hashCode() {
        return spi.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RouteSitePinInst that = (RouteSitePinInst) obj;
        return spi.equals(that.spi);
    }

    public static List<RoutePinInterface> asList(List<SitePinInst> pins) {
        List<RoutePinInterface> terms = new ArrayList<>(pins.size());
        for (SitePinInst spi : pins) {
            terms.add(new RouteSitePinInst(spi));
        }
        return terms;
    }

    public static List<SitePinInst> fromList(List<RoutePinInterface> terms) {
        List<SitePinInst> pins = new ArrayList<>(terms.size());
        for (RoutePinInterface term : terms) {
            SitePinInst spi = term.getSitePinInst();
            if (spi == null) {
                throw new RuntimeException();
            }
            pins.add(spi);
        }
        return pins;
    }
}
