/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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
package com.xilinx.rapidwright.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.trolltech.qt.core.QModelIndex;
import com.trolltech.qt.gui.QTreeWidget;
import com.trolltech.qt.gui.QTreeWidgetItem;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Widget that allows a tree view of the provided netlist.
 */
public class NetlistTreeWidget extends QTreeWidget {

    private EDIFNetlist netlist;

    private QTreeWidgetItem rootItem;

    private Map<String, QTreeWidgetItem> objectLookup = new HashMap<>();

    private static final String DUMMY = "_*DUMMY*_";
    private static final String LEAF_CELLS = "Leaf Cells";
    private static final String PORTS = "Ports";
    private static final String NETS = "Nets";

    public static final String INST_ID = "INST:";
    public static final String NET_ID = "NET:";
    public static final String PORT_ID = "PORT:";

    public NetlistTreeWidget(String header, EDIFNetlist netlist) {
        this.netlist = netlist;
        setColumnCount(1);
        setHeaderLabel(header);
        HierCellInstTreeWidgetItem root = new HierCellInstTreeWidgetItem(this);
        root.setInst(netlist.getTopHierCellInst());
        QTreeWidgetItem dummy = new QTreeWidgetItem(root);
        dummy.setText(0, DUMMY);
        rootItem = populateCellInst(root, netlist.getTopHierCellInst());
        this.clicked.connect(this, "expandCell(QModelIndex)");
        this.expanded.connect(this, "expandCell(QModelIndex)");
    }

    public QTreeWidgetItem populateCellInst(QTreeWidgetItem curr, EDIFHierCellInst inst) {
        if (curr.childCount() > 1) {
            return curr;
        }
        // Remove DUMMY
        curr.removeChild(curr.child(0));

        EDIFCell cell = inst.getCellType();
        curr.setText(0, inst.getInst().getName() + " (" + cell.getName() + ")");
        boolean isTop = inst.isTopLevelInst();

        QTreeWidgetItem ports = new QTreeWidgetItem(curr);
        ports.setText(0, PORTS + " (" + cell.getPorts().size() + ")");

        for (EDIFHierPortInst portInst : inst.getHierPortInsts()) {
            QTreeWidgetItem n = new QTreeWidgetItem(ports);
            n.setData(0, 0, portInst);
            String portLookup = PORT_ID + (isTop ? portInst.getPortInst().getName() : portInst.toString());
            n.setData(1, 0, portLookup);
            n.setText(0, portInst.getPortInst().getName() + " (" + portInst.getPortInst().getDirection() + ")");
            objectLookup.put(portLookup, n);
        }
        ports.setExpanded(false);


        QTreeWidgetItem nets = new QTreeWidgetItem(curr);
        nets.setText(0, NETS + " (" + cell.getNets().size() + ")");
        List<EDIFNet> edifNets = new ArrayList<>(cell.getNets());
        Collections.sort(edifNets);
        for (EDIFNet net : edifNets) {
            QTreeWidgetItem n = new QTreeWidgetItem(nets);
            EDIFHierNet hierNet = inst.getNet(net.getName());
            n.setData(0, 0, hierNet);
            String netLookup = NET_ID + hierNet.toString();
            n.setData(1, 0, netLookup);
            n.setText(0, net.getName());
            objectLookup.put(netLookup, n);
        }
        nets.setExpanded(false);


        List<EDIFHierCellInst> leaves = new ArrayList<>();
        List<EDIFHierCellInst> nonLeaves = new ArrayList<>();
        for (EDIFCellInst child : cell.getCellInsts()) {
            if (child.getCellType().isLeafCellOrBlackBox()) {
                leaves.add(inst.getChild(child));
            } else {
                nonLeaves.add(inst.getChild(child));
            }
        }
        Collections.sort(leaves);
        Collections.sort(nonLeaves);

        QTreeWidgetItem leafCells = new QTreeWidgetItem(curr);
        leafCells.setText(0, LEAF_CELLS + " (" + leaves.size() + ")");
        for (EDIFHierCellInst i : leaves) {
            QTreeWidgetItem leaf = new QTreeWidgetItem(leafCells);
            leaf.setData(0, 0, i);
            leaf.setText(0, i.getInst().getName() + " (" + i.getCellName() + ")");
            String leafLookup = INST_ID + i.toString();
            leaf.setData(1, 0, leafLookup);
            objectLookup.put(leafLookup, leaf);
        }
        leafCells.setExpanded(false);


        for (EDIFHierCellInst i : nonLeaves) {
            HierCellInstTreeWidgetItem cellInst = new HierCellInstTreeWidgetItem(curr);
            cellInst.setText(0, i.getInst().getName() + " (" + i.getCellName() + ")");
            cellInst.setInst(i);
            String instLookup = INST_ID + i.toString();
            cellInst.setData(1, 0, instLookup);
            objectLookup.put(instLookup, cellInst);
            QTreeWidgetItem dummy = new QTreeWidgetItem(cellInst);
            dummy.setText(0, DUMMY);
        }

        curr.setExpanded(true);
        return curr;
    }

    /**
     * This method is invoked when the tree is clicked or expanded and will populate
     * the current instance tree.
     * 
     * @param qmIndex The index of the part to load.
     */
    public void expandCell(QModelIndex qmIndex) {
        QTreeWidgetItem item = this.itemFromIndex(qmIndex);
        if (item instanceof HierCellInstTreeWidgetItem) {
            populateCellInst(item, ((HierCellInstTreeWidgetItem) item).getInst());
        }
    }

    public EDIFNetlist getNetlist() {
        return netlist;
    }

    public QTreeWidgetItem getRootItem() {
        return rootItem;
    }

    public QTreeWidgetItem getItemFromIndex(QModelIndex index) {
        return this.itemFromIndex(index);
    }

    public QTreeWidgetItem getItemByStringLookup(String lookup) {
        QTreeWidgetItem item = objectLookup.get(lookup);
        if (item != null) {
            return item;
        }

        // We need to expand the tree to create the item first
        String hierPath = lookup.substring(lookup.indexOf(":") + 1);

        String[] parts = hierPath.split(EDIFTools.EDIF_HIER_SEP);
        QTreeWidgetItem currItem = rootItem;
        EDIFHierCellInst currInst = netlist.getTopHierCellInst();

        boolean isInst = lookup.startsWith(INST_ID);
        for (int i = 0; i < parts.length; i++) {
            EDIFHierCellInst nextInst = currInst.getChild(parts[i]);
            currInst = nextInst == null ? currInst : nextInst;
            currItem = getChildItem(i == parts.length - 1, currInst, parts[i], currItem, isInst);
        }

        return currItem;
    }

    private QTreeWidgetItem getChildItem(boolean isLastLevel, EDIFHierCellInst cellInst, String instName,
            QTreeWidgetItem currItem, boolean isInst) {
        // Expand this instance if it hasn't been expanded yet
        if (currItem.childCount() == 1 && currItem.child(0).text(0).equals(DUMMY)) {
            currItem = populateCellInst(currItem, isInst ? cellInst.getParent() : cellInst);
        }
        for (int j = 0; j < currItem.childCount(); j++) {
            QTreeWidgetItem child = currItem.child(j);
            String text = child.text(0);
            if (isLastLevel) {
                if (text.startsWith(LEAF_CELLS) || text.startsWith(NETS) || text.startsWith(PORTS)) {
                    child.setExpanded(true);
                    for (int k = 0; k < child.childCount(); k++) {
                        QTreeWidgetItem leaf = child.child(k);
                        String leafText = leaf.text(0);
                        if (leafText.startsWith(instName)) {
                            return leaf;
                        }
                    }
                }
            }
            if (text.startsWith(instName)) {
                return child;
            }
        }
        return null;
    }
}
