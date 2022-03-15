package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RoutableGraph {

    protected class RoutableNodeImpl extends RoutableNode {

        public RoutableNodeImpl(Node node, RoutableType type) {
            super(node, type);
        }

        @Override
        public Routable create(Node node, RoutableType type) {
            return RoutableGraph.this.create(node, type).getFirst();
        }

        @Override
        public boolean isExcluded(Node node) {
            return preservedNodes.containsKey(node) || super.isExcluded(node);
        }

        @Override
        public Routable[] getChildren() {
            rnodesTimer.start();
            setChildren();
            rnodesTimer.stop();
            return super.getChildren();
        }
    }

    /**
     * A map of nodes to created rnodes
     */
    final protected Map<Node, Routable> rnodesCreated;

    /**
     * A map of preserved nodes to their nets
     */
    final protected Map<Node, Net> preservedNodes;

    /**
     * Visited rnodes data during connection routing
     */
    final protected Collection<Routable> rnodesVisited;

    final protected RuntimeTracker rnodesTimer;

    private long totalNodesVisited;

    public RoutableGraph(RuntimeTracker rnodesTimer) {
        rnodesCreated = new HashMap<>();
        preservedNodes = new HashMap<>();
        rnodesVisited = new ArrayList<>();
        this.rnodesTimer = rnodesTimer;
    }

    public void initialize() {
        totalNodesVisited = 0;
        rnodesVisited.clear();
    }

    public Net preserve(Node node, Net net) {
        return preservedNodes.putIfAbsent(node, net);
    }

    public void unpreserve(Node node) {
        preservedNodes.remove(node);
    }

    public Set<Node> getPreservedNodes() {
        return Collections.unmodifiableSet(preservedNodes.keySet());
    }

    public Net getPreservedNet(Node node) {
        return preservedNodes.get(node);
    }

    public Set<Node> getNodes() {
        return Collections.unmodifiableSet(rnodesCreated.keySet());
    }

    public Set<Map.Entry<Node,Routable>> getNodeEntries() {
        return Collections.unmodifiableSet(rnodesCreated.entrySet());
    }

    public int numNodes() {
        return rnodesCreated.size();
    }

    public Routable getNode(Node node) {
        return rnodesCreated.get(node);
    }

    protected Routable newNode(Node node, RoutableType type) {
        return new RoutableNodeImpl(node, type);
    }

    public Pair<Routable,Boolean> create(Node node, RoutableType type) {
        final boolean[] inserted = {false};
        Routable rnode = rnodesCreated.compute(node, (k,v) -> {
            if (v == null) {
                // this is for initializing sources and sinks of those to-be-routed nets' connections
                v = newNode(node, type);
                inserted[0] = true;
            }
            return v;
        });
        return new Pair<>(rnode, inserted[0]);
    }

    public void visit(Routable rnode) {
        rnodesVisited.add(rnode);
    }

    /**
     * Resets the expansion history.
     */
    public void resetExpansion() {
        for (Routable node : rnodesVisited) {
            node.setVisited(false);
        }
        totalNodesVisited += rnodesVisited.size();
        rnodesVisited.clear();
    }

    public long getTotalNodesVisited() {
        return totalNodesVisited;
    }

    public int averageChildren() {
        int sum = 0;
        for(Map.Entry<Node,Routable> e : getNodeEntries()){
            RoutableNodeImpl rnode = (RoutableNodeImpl) e.getValue();
            sum += (rnode.children != null) ? rnode.children.length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

}
