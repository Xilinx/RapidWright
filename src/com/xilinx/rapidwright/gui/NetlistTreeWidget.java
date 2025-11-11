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
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Widget that allows a tree view of the provided netlist.
 */
public class NetlistTreeWidget extends QTreeWidget {

    private EDIFNetlist netlist;

    private QTreeWidgetItem rootItem;

    private Map<String, QTreeWidgetItem> objectLookup = new HashMap<>();

    private static final String DUMMY = "_*DUMMY*_";

    public NetlistTreeWidget(String header, EDIFNetlist netlist) {
        this.netlist = netlist;
        setColumnCount(1);
        setHeaderLabel(header);
        QTreeWidgetItem root = new QTreeWidgetItem(this);
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
        ports.setText(0, "Ports (" + cell.getPorts().size() + ")");

        for (EDIFHierPortInst portInst : inst.getHierPortInsts()) {
            QTreeWidgetItem n = new QTreeWidgetItem(ports);
            n.setData(0, 0, portInst);
            String portLookup = "PORT:" + (isTop ? portInst.getPortInst().getName() : portInst.toString());
            n.setData(1, 0, portLookup);
            n.setText(0, portInst.getPortInst().getName() + " (" + portInst.getPortInst().getDirection() + ")");
            objectLookup.put(portLookup, n);
        }
        ports.setExpanded(false);


        QTreeWidgetItem nets = new QTreeWidgetItem(curr);
        nets.setText(0, "Nets (" + cell.getNets().size() + ")");
        List<EDIFNet> edifNets = new ArrayList<>(cell.getNets());
        Collections.sort(edifNets);
        for (EDIFNet net : edifNets) {
            QTreeWidgetItem n = new QTreeWidgetItem(nets);
            EDIFHierNet hierNet = inst.getNet(net.getName());
            n.setData(0, 0, hierNet);
            String netLookup = "NET:" + hierNet.toString();
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
        leafCells.setText(0, "Leaf Cells (" + leaves.size() + ")");
        for (EDIFHierCellInst i : leaves) {
            QTreeWidgetItem leaf = new QTreeWidgetItem(leafCells);
            leaf.setText(0, i.getInst().getName() + " (" + i.getCellName() + ")");
            leaf.setData(0, 0, i);
            String leafLookup = "INST:" + i.toString();
            leaf.setData(1, 0, leafLookup);
            objectLookup.put(leafLookup, leaf);
        }
        leafCells.setExpanded(false);


        for (EDIFHierCellInst i : nonLeaves) {
            HierCellInstTreeWidgetItem cellInst = new HierCellInstTreeWidgetItem(curr);
            cellInst.setText(0, i.getInst().getName() + " (" + i.getCellName() + ")");
            cellInst.setInst(i);
            String instLookup = "INST:" + i.toString();
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
        return objectLookup.get(lookup);
    }
}
