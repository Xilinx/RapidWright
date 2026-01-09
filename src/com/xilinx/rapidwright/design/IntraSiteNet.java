/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

import java.util.HashSet;
import java.util.Set;

import com.xilinx.rapidwright.device.BELPin;

/**
 * Helper or Accessory class to help capture intra-site nets inside a site. This
 * is primarily to represent a single {@link BELPin} (or the equivalent
 * {@link SitePinInst}) source and all the BELPin sinks connected to a placed
 * cell pin. Multiple instances of {@link IntraSiteNet} would be needed if a net
 * drives multiple {@link SitePinInst} inputs.
 */
public class IntraSiteNet {

    private SiteInst si;

    private Net net;

    private BELPin src;

    private Set<BELPin> sinks;

    public IntraSiteNet(SiteInst si, Net net, BELPin src, BELPin snk) {
        this.si = si;
        this.net = net;
        this.src = src;
        addSink(snk);
    }

    public SiteInst getSiteInst() {
        return si;
    }

    public void setSiteInst(SiteInst si) {
        this.si = si;
    }

    public Net getNet() {
        return net;
    }

    public void setNet(Net net) {
        this.net = net;
    }

    public BELPin getSrc() {
        return src;
    }

    public void setSrc(BELPin src) {
        this.src = src;
    }

    public Set<BELPin> getSinks() {
        return sinks;
    }

    public void setSinks(Set<BELPin> sinks) {
        this.sinks = sinks;
    }

    public boolean addSink(BELPin sink) {
        if (sinks == null) {
            sinks = new HashSet<>();
        }
        return sinks.add(sink);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((net == null) ? 0 : net.hashCode());
        result = prime * result + ((si == null) ? 0 : si.hashCode());
        result = prime * result + ((src == null) ? 0 : src.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        IntraSiteNet other = (IntraSiteNet) obj;
        if (net == null) {
            if (other.net != null)
                return false;
        } else if (!net.equals(other.net))
            return false;
        if (si == null) {
            if (other.si != null)
                return false;
        } else if (!si.equals(other.si))
            return false;
        if (src == null) {
            if (other.src != null)
                return false;
        } else if (!src.equals(other.src))
            return false;
        return true;
    }

}
