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

    private class RoutableNodeImpl extends RoutableNode {

        public RoutableNodeImpl(Node node, RoutableType type) {
            super(node, type);
        }

        @Override
        public void setChildren(Map<Node, Routable> createdRoutable, Set<Node> reserved) {
            if (children == null) {
                List<Node> allDownHillNodes = node.getAllDownhillNodes();
                List<Routable> childrenList = new ArrayList<>(allDownHillNodes.size());
                for (Node node : allDownHillNodes) {
                    if (preservedNodes.containsKey(node)) continue;
                    if (isExcluded(node)) continue;

                    RoutableType type = RoutableType.WIRE;
                    Routable child = create(node, type).getFirst();
                    childrenList.add(child);//the sink rnode of a target connection has been created up-front
                }
                children = childrenList.toArray(new Routable[0]);
            }
        }

        @Override
        public Routable[] getChildren() {
            rnodesTimer.start();
            setChildren(rnodesCreated, getPreservedNodes());
            rnodesTimer.stop();
            return super.getChildren();
        }

        public int numChildren() {
            return children.length;
        }
    }

    /**
     * A map of nodes to created rnodes
     */
    final private Map<Node, Routable> rnodesCreated;

    /**
     * A map of preserved nodes to their nets
     */
    final private Map<Node, Net> preservedNodes;

    /**
     * Visited rnodes data during connection routing
     */
    final private Collection<Routable> rnodesVisited;

    final private RuntimeTracker rnodesTimer;

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

    public Pair<Routable,Boolean> create(Node node, RoutableType type) {
        final boolean[] inserted = {false};
        Routable rnode = rnodesCreated.compute(node, (k,v) -> {
            if (v == null) {
                // this is for initializing sources and sinks of those to-be-routed nets' connections
                v = new RoutableNodeImpl(node, type);
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
