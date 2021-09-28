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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public abstract class DotGraphDumper<InstanceT, PortT, PortTemplateT, NetT, DesignT> {
    private final boolean makeNetNode;

    protected DotGraphDumper(boolean makeNetNode) {
        this.makeNetNode = makeNetNode;
    }

    /**
     * Extend an existing filter to also output instances that connect to the ones that are shown
     * @param initialFilter the filter to extend
     * @return the extended filter
     */
    public BiPredicate<InstanceT, DesignT> extendFilterToConnected(BiPredicate<InstanceT, DesignT> initialFilter) {
        Objects.requireNonNull(initialFilter);
        return (i,design) -> {
            if (initialFilter.test(i, design)) {
                return true;
            }

            Stream<PortT> ports = i != null ? getPorts(i) : getRootPorts(design);

            return ports
                    .map(this::getPortNet)
                    .flatMap(this::getNetPorts)
                    .map(this::getPortInstance)
                    .anyMatch(conn->initialFilter.test(conn, design));
        };
    }

    /**
     * Extend an existing filter to also output instances that connect to the ones that are shown
     * @param initialFilter the filter to extend
     * @return the extended filter
     */
    public BiPredicate<InstanceT, DesignT> extendFilterToConnected(Predicate<InstanceT> initialFilter) {
        return extendFilterToConnected((i,d)->initialFilter.test(i));
    }

    protected abstract NetT getPortNet(PortT p);

    protected abstract Stream<InstanceT> getInstances(DesignT design);
    protected abstract Stream<PortT> getPorts(InstanceT instance);
    protected abstract Stream<PortTemplateT> getPortTemplates(InstanceT instance);
    protected abstract Stream<NetT> getNets(DesignT design);
    protected abstract Stream<PortT> getNetPorts(NetT net);
    protected abstract boolean isOutputPort(PortT port);
    protected abstract boolean isOutputPortTemplate(PortTemplateT port);
    protected abstract String getInstanceName(InstanceT instance);
    protected abstract String getPortName(PortT port);
    protected abstract String getPortTemplateName(PortTemplateT port);
    protected abstract Stream<PortT> getRootPorts(DesignT design);
    protected abstract String getNetName(NetT net);
    protected abstract Map<?, ?> getInstanceProperties(InstanceT instance, DesignT design);
    protected abstract InstanceT getPortInstance(PortT port);

    private String escapeText(String s) {
        return s.replaceAll("([\\\\\"<>])", "\\\\$1");
    }
    private String escapeHtml(String s) {
        return s.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
    }


    private String formatPorts(List<PortT> ports, List<PortTemplateT> missingPorts, Map<PortT, String> portIds) {
        Stream<String> actualPorts = ports.stream().map(p -> "<td border=\"1\" port=\"" + portIds.get(p) + "\">" + escapeHtml(getPortName(p)) + "</td>");
        Stream<String> missingPortStream = missingPorts.stream().map(p -> "<td border=\"1\" bgcolor=\"gray\">" + escapeHtml("MISSING " + getPortTemplateName(p)) + "</td>");

        String res = Stream.concat(actualPorts, missingPortStream)
                .collect(Collectors.joining("\n"));
        if (res.isEmpty()) {
            return "<tr><td border=\"1\">&nbsp;</td></tr>";
        }
        return "<tr><td><table cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tr>\n"+res+"\n</tr></table></td></tr>";
    }

    private String replaceChars(String name) {
        return name.replaceAll("[^0-9a-zA-Z_]","");
    }
    private Map<PortT, String> dumpInstance(PrintWriter pw, java.util.PrimitiveIterator.OfInt ids, InstanceT inst, DesignT design) {
        String id = "inst_" +replaceChars(getInstanceName(inst))+"_"+ ids.next();


        Map<Boolean, List<PortT>> partitioned = getPorts(inst)
                .sorted(Comparator.comparing(this::getPortName))
                .collect(Collectors.partitioningBy(this::isOutputPort));
        List<PortT> inputs = partitioned.get(false);
        List<PortT> outputs = partitioned.get(true);

        Set<String> portNames = Stream.concat(inputs.stream(), outputs.stream())
                .map(this::getPortName)
                .collect(Collectors.toSet());

        Map<Boolean, List<PortTemplateT>> partitionedTemplates = getPortTemplates(inst)
                .sorted(Comparator.comparing(this::getPortTemplateName))
                .filter(p -> !portNames.contains(getPortTemplateName(p)))
                .collect(Collectors.partitioningBy(this::isOutputPortTemplate));

        List<PortTemplateT> missingInputs = partitionedTemplates.get(false);
        List<PortTemplateT> missingOutputs = partitionedTemplates.get(true);

        Iterator<String> portIdIter = IntStream.iterate(0, i -> i + 1).mapToObj(Integer::toString).iterator();
        Map<PortT, String> portIds = Stream.concat(inputs.stream(), outputs.stream())
                .collect(Collectors.toMap(Function.identity(), p -> portIdIter.next()));


        Stream<String> head = Stream.of(
                formatPorts(inputs, missingInputs, portIds),
                "<tr><td border=\"1\"><b>"+escapeHtml(getInstanceName(inst))+"</b></td></tr>"
        );
        Stream<String> props;
        Map<?,?> propMap = getInstanceProperties(inst, design);
        if (propMap == null) {
            props = Stream.empty();
        } else {
            props = propMap
                    .entrySet()
                    .stream()
                    .map(e -> "<tr><td border=\"1\">"+escapeHtml(e.getKey() + " -&gt; " + e.getValue())+"</td></tr>")
                    .sorted();
        }
        Stream<String> tail = Stream.of(
                formatPorts(outputs, missingOutputs, portIds)
        );

        String label = Stream.of(
                head,
                props,
                tail
        )
                .flatMap(s->s)
                .collect(Collectors.joining("\n","<table cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n","\n</table>"));

        pw.println(id+"[label=<\n"+label+">, shape=none];");


        return portIds.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->id+":"+e.getValue()));
    }

    private Map<PortT,String> dumpRootPins(PrintWriter pw, DesignT design, BiPredicate<InstanceT, DesignT> filter) {

        Iterator<String> portIdIter = IntStream.iterate(0, i -> i + 1).mapToObj(i -> "rootPort" + i).iterator();
        Map<PortT, String> portIds = getRootPorts(design)
                .collect(Collectors.toMap(Function.identity(), p -> portIdIter.next()));


        //Don't check filter if there are no root ports, filter may not expect that check
        if (portIds.isEmpty() || !filter.test(null, design)) {
            return new HashMap<>();
        }


        portIds.forEach((p,id)-> pw.println(id+"[label=\"ROOT PIN "+escapeText(getPortName(p))+"\"];"));
        return portIds;
    }

    private Map<PortT, String> dumpCells(DesignT design, PrintWriter pw, BiPredicate<InstanceT, DesignT> filter) {
        PrimitiveIterator.OfInt ids = IntStream.iterate(0, i -> i + 1).iterator();
        Stream<InstanceT> instances = getInstances(design)
                .filter(i->filter.test(i, design));
        return instances
                .map(inst -> dumpInstance(pw, ids, inst, design))
                .reduce(dumpRootPins(pw, design, filter), (m, n) -> {m.putAll(n); return m;});
    }

    private List<PortT> getNetSources(NetT net) {
        List<PortT> sources = getNetPorts(net).filter(this::isOutputPort).collect(Collectors.toList());
        if (sources.size() > 1) {
            System.err.println("multiple sources for "+net+": "+sources);
        }
        if (sources.isEmpty()) {
            System.err.println("no source for "+net);
        }
        return sources;
    }

    private boolean netHasFilteredPorts(NetT net, DesignT design, BiPredicate<InstanceT, DesignT> filter) {
        return filter!= null && !getNetPorts(net).map(this::getPortInstance).allMatch(i->filter.test(i, design));
    }

    private void dumpConnections(DesignT design, Map<PortT, String> portIds, PrintWriter pw, BiPredicate<InstanceT, DesignT> filter) {

        Stream<NetT> nets = getNets(design)
                .filter(n->getNetPorts(n).anyMatch(port->filter.test(getPortInstance(port), design)));

        Iterator<String> netIdIter = IntStream.iterate(0, i -> i + 1).mapToObj(i -> "net" + i).iterator();
        if (makeNetNode) {
            dumpConnectionsNetNode(design, portIds, pw, filter, nets, netIdIter);
        } else {
            dumpConnectionsDirect(design, portIds, pw, filter, nets, netIdIter);
        }

    }

    private void dumpConnectionsDirect(DesignT design, Map<PortT, String> portIds, PrintWriter pw, BiPredicate<InstanceT, DesignT> filter, Stream<NetT> nets, Iterator<String> netIdIter) {
        nets
                .forEach(net -> {
                    List<String> sourceIDs = new ArrayList<>();
                    List<String> sinkIDs = new ArrayList<>();

                    int filteredOutSources = 0;
                    int filteredOutSinks = 0;

                    for (PortT port : (Iterable<PortT>) getNetPorts(net)::iterator) {
                        boolean isSource = isOutputPort(port);
                        boolean isShown = filter.test(getPortInstance(port), design);

                        if (!isShown) {
                            if (isSource) {
                                filteredOutSources++;
                            } else {
                                filteredOutSinks++;
                            }
                        } else {
                            String id = portIds.get(port);
                            if (id == null) {
                                id = netIdIter.next();
                                pw.println(id + "[label=\"unknown port of " + escapeText(getNetName(net)) + "\"];");


                                System.err.println("no id for port " + port + " in net " + net);
                            }
                            if (isSource) {
                                sourceIDs.add(id);
                            } else {
                                sinkIDs.add(id);
                            }
                        }
                    }


                    if (sinkIDs.size() > 1 && sourceIDs.isEmpty() && filteredOutSources > 0) {
                        //There are multiple sinks, but all sources are hidden. Let's add a source node
                        String id = netIdIter.next();
                        pw.println(id + "[label=\"hidden Source of " + escapeText(getNetName(net)) + "\"];");
                        sourceIDs.add(id);
                    }

                    if (sourceIDs.size() > 1 && sinkIDs.isEmpty() && filteredOutSinks > 0) {
                        //There are multiple sources, but all sinks are hidden. Let's add a sink node
                        String id = netIdIter.next();
                        pw.println(id + "[label=\"hidden Sink of " + escapeText(getNetName(net)) + "\", style=\"dashed\"];");
                        sinkIDs.add(id);
                    }

                    for (String sourceID : sourceIDs) {
                        for (String sinkID : sinkIDs) {
                            pw.println(sourceID + " -> " + sinkID + ";");
                        }
                    }

                });
    }

    private void dumpConnectionsNetNode(DesignT design, Map<PortT, String> portIds, PrintWriter pw, BiPredicate<InstanceT, DesignT> filter, Stream<NetT> nets, Iterator<String> netIdIter) {
        nets
                .forEach(net -> {
                    String nid = netIdIter.next();
                    boolean hasFilteredPorts = netHasFilteredPorts(net, design, filter);
                    String filterSuffix = hasFilteredPorts ? ", style=\"dashed\"" : "";
                    pw.println(nid+"[label=\"NET "+escapeText(getNetName(net))+"\""+filterSuffix+"];");

                    getNetPorts(net)
                            .filter(p-> filter.test(getPortInstance(p), design))
                            .forEach(port -> {
                        String pid = portIds.get(port);
                        if (pid == null) {
                            System.err.println("no id for port "+port+" in "+net);
                        } else {
                            if (isOutputPort(port)) {
                                pw.println(pid + " -> " + nid);
                            } else {
                                pw.println(nid + " -> " + pid);
                            }
                        }
                    });

                });
    }

    /**
     * Dump the design to a PrintWriter. Optionally filter the instances shown
     * @param design the design to dump
     * @param pw the PrintWriter to output to
     * @param filter A function that filters the instances that are shown. Pass null to show everything
     */
    public void doDump(DesignT design, PrintWriter pw, BiPredicate<InstanceT, DesignT> filter) {

        if (filter == null) {
            filter = (i,d) -> true;
        }

        pw.println("digraph G {");

        Map<PortT, String> portIds = dumpCells(design, pw, filter);

        dumpConnections(design, portIds, pw, filter);

        pw.println("}");
    }

    /**
     * Dump the design to a file. Optionally filter the instances shown
     * @param design the design to dump
     * @param output the target file
     * @param filter A function that filters the instances that are shown. Pass null to show everything
     */
    public void doDump(DesignT design, Path output, BiPredicate<InstanceT, DesignT> filter) {
        try(PrintWriter pw = new PrintWriter(Files.newBufferedWriter(output))) {
            doDump(design, pw, filter);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

