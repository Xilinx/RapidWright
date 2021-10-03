/*
 * Copyright (c) 2021 Xilinx, Inc.
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
package com.xilinx.rapidwright.debug;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.util.Pair;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dump BELs and connections from a single Site or SiteInst to a Graphviz Dot Graph
 */
public class DotSiteDumper extends DotGraphDumper<BEL, BELPin, BELPin, List<BELPin>, SiteInst>{

    private static class SiteNets {
        private final List<List<BELPin>> nets;
        private final Map<BELPin, List<BELPin>> pinToNet;

        private SiteNets(List<List<BELPin>> nets, Map<BELPin, List<BELPin>> pinToNet) {
            this.nets = nets;
            this.pinToNet = pinToNet;
        }

        private static Stream<List<BELPin>> toConnections(BELPin pin) {
            if (!pin.isOutput()) {
                return Stream.empty();
            }
            final List<BELPin> net = Stream.concat(Stream.of(pin), pin.getSiteConns().stream()).collect(Collectors.toList());
            return Stream.of(net);
        }

        public static SiteNets computeSiteNets(Site site) {
            final List<List<BELPin>> nets = Arrays.stream(site.getBELs())
                    .flatMap(b -> Arrays.stream(b.getPins()))
                    .flatMap(SiteNets::toConnections)
                    .filter(n -> n.size() > 1)
                    .collect(Collectors.toList());

            final Map<BELPin, List<BELPin>> pinToNet = nets.stream()
                    .flatMap(net -> net.stream().map(pin -> new Pair<>(pin, net)))
                    .collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
            return new SiteNets(nets, pinToNet);
        }
    }

    public DotSiteDumper() {
        super(false);
    }

    public DotSiteDumper(boolean makeNetNode) {
        super(makeNetNode);
    }

    private final Map<Site, SiteNets> netCache = new HashMap<>();


    private SiteNets getSiteInfo(Site site) {
        return netCache.computeIfAbsent(site, SiteNets::computeSiteNets);
    }

    @Override
    protected Stream<BEL> getInstances(SiteInst design) {
        return Arrays.stream(design.getBELs());
    }

    @Override
    protected Stream<BELPin> getPorts(BEL instance) {
        return Arrays.stream(instance.getPins());
    }

    @Override
    protected Stream<BELPin> getPortTemplates(BEL instance) {
        return Arrays.stream(instance.getPins());
    }

    @Override
    protected Stream<List<BELPin>> getNets(SiteInst design) {
        return getSiteInfo(design.getSite()).nets.stream();
    }

    @Override
    protected Stream<BELPin> getNetPorts(List<BELPin> net) {
        return net.stream();
    }

    @Override
    protected boolean isOutputPort(BELPin port) {
        return port.isOutput();
    }

    @Override
    protected boolean isOutputPortTemplate(BELPin port) {
        return port.isOutput();
    }

    @Override
    protected String getInstanceName(BEL instance) {
        return instance.getName();
    }

    @Override
    protected String getPortName(BELPin port) {
        return port.getName();
    }

    @Override
    protected String getPortTemplateName(BELPin port) {
        return port.getName();
    }

    @Override
    protected Stream<BELPin> getRootPorts(SiteInst design) {
        return Stream.empty();
    }

    @Override
    protected String getNetName(List<BELPin> net) {
        return net.get(0).getSiteWireName();
    }

    @Override
    protected Map<?, ?> getInstanceProperties(BEL instance, SiteInst design) {
        final Cell cell = design.getCell(instance);
        if (cell == null) {
            return null;
        }
        return cell.getProperties();
    }

    private static SiteInst makeDummySiteInst(Site site) {
        SiteInst dummy = new SiteInst("", site.getSiteTypeEnum());
        dummy.place(site);
        return dummy;
    }


    /**
     * Dump a SiteInst to a file while filtering the cellInsts that are shown
     * @param to the target file
     * @param siteInst the siteInst to dump
     * @param filter A function that filters the bels that are shown
     */
    public static void dump(Path to, SiteInst siteInst, BiPredicate<BEL, SiteInst> filter) {
        new DotSiteDumper().doDump(siteInst, to, filter);
    }
    /**
     * Dump a SiteInst to a file while filtering the cellInsts that are shown
     * @param to the target file
     * @param siteInst the siteInst to dump
     * @param filter A function that filters the bels that are shown
     */
    public static void dump(Path to, SiteInst siteInst, Predicate<BEL> filter) {
        dump(to, siteInst, (bel, si) -> filter.test(bel));
    }
    /**
     * Dump a SiteInst to a file
     * @param to the target file
     * @param siteInst the siteInst to dump
     */
    public static void dump(Path to, SiteInst siteInst) {
        dump(to, siteInst, (BiPredicate<BEL, SiteInst>) null);
    }

    /**
     * Dump a Site to a file while filtering the cellInsts that are shown
     * @param to the target file
     * @param site the Site to dump
     * @param filter A function that filters the bels that are shown
     */
    public static void dump(Path to, Site site, BiPredicate<BEL, SiteInst> filter) {
        new DotSiteDumper().doDump(makeDummySiteInst(site), to, filter);
    }
    /**
     * Dump a Site to a file while filtering the cellInsts that are shown
     * @param to the target file
     * @param site the Site to dump
     * @param filter A function that filters the bels that are shown
     */
    public static void dump(Path to, Site site, Predicate<BEL> filter) {
        dump(to, site, (bel, si) -> filter.test(bel));
    }
    /**
     * Dump a Site to a file
     * @param to the target file
     * @param site the Site to dump
     */
    public static void dump(Path to, Site site) {
        dump(to, site, (BiPredicate<BEL, SiteInst>) null);
    }

    @Override
    protected BEL getPortInstance(BELPin port) {
        return port.getBEL();
    }

    @Override
    protected List<BELPin> getPortNet(BELPin p) {
        return p.getSiteConns();
    }
}
