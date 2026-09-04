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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.elk.alg.layered.options.GreedySwitchType;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.IGraphLayoutEngine;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.NodeLabelPlacement;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.core.util.IElkProgressMonitor;
import org.eclipse.elk.graph.ElkBendPoint;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkGraphElement;
import org.eclipse.elk.graph.ElkGraphFactory;
import org.eclipse.elk.graph.ElkLabel;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;

import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.core.QSizeF;
import com.trolltech.qt.core.Qt.ItemSelectionMode;
import com.trolltech.qt.core.Qt.KeyboardModifier;
import com.trolltech.qt.gui.QAbstractGraphicsShapeItem;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QFont;
import com.trolltech.qt.gui.QFontMetrics;
import com.trolltech.qt.gui.QGraphicsItemInterface;
import com.trolltech.qt.gui.QGraphicsLineItem;
import com.trolltech.qt.gui.QGraphicsPathItem;
import com.trolltech.qt.gui.QGraphicsPolygonItem;
import com.trolltech.qt.gui.QGraphicsRectItem;
import com.trolltech.qt.gui.QGraphicsScene;
import com.trolltech.qt.gui.QGraphicsSceneMouseEvent;
import com.trolltech.qt.gui.QGraphicsSimpleTextItem;
import com.trolltech.qt.gui.QPainterPath;
import com.trolltech.qt.gui.QPainterPath_Element;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QPolygonF;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class SchematicScene extends QGraphicsScene {

    private EDIFNetlist netlist;

    private EDIFHierCellInst currCellInst;

    private ElkNode elkRoot;

    private Map<EDIFHierPortInst, ElkPort> portInstMap = new HashMap<>();
    private Map<ElkNode, EDIFHierPortInst> elkNodeTopPortMap = new HashMap<>();
    private Map<ElkNode, EDIFHierCellInst> elkNodeCellMap = new HashMap<>();
    private Map<String, List<Object>> lookupMap = new HashMap<>();
    private Set<String> expandedCellInsts = new HashSet<>();
    private Set<String> selectedObjects = new HashSet<>();

    public Signal1<String> objectSelected = new Signal1<>();
    public Signal0 cellDrawn = new Signal0();

    private static QFont FONT = new QFont("Arial", 8);
    private static QFont BUTTON_TEXT_FONT = new QFont("Arial", 10, QFont.Weight.Bold.value());

    private static final QBrush BLACK_BRUSH = new QBrush(QColor.black);
    private static final QPen BLACK_PEN = new QPen(QColor.black);

    private static QFontMetrics fm = new QFontMetrics(FONT);
    private static QBrush canvasBackgroundBrush = new QBrush(QColor.white);
    private static final QBrush PORT_BRUSH = new QBrush(QColor.white);
    private static final QPen PORT_PEN = BLACK_PEN;
    private static final QPen NET_PEN = new QPen(new QColor(91, 203, 75));
    private static final QPen SELECTED_PEN = new QPen(new QColor(0, 0, 255), 3);

    private static final QBrush CELL_BRUSH = new QBrush(new QColor(255, 255, 210));
    private static final QPen CELL_PEN = new QPen(QColor.black);
    private static final QBrush HIER_CELL_BRUSH = new QBrush(new QColor(173, 216, 230));
    private static final QPen HIER_CELL_PEN = new QPen(new QColor(100, 149, 237));
    private static final QBrush EXPANDED_HIER_CELL_BRUSH = new QBrush(new QColor(255, 255, 255, 0));
    private static final QPen EXPANDED_HIER_CELL_PEN = new QPen(new QColor(100, 149, 237), 2);

    private static final QBrush BUTTON_BRUSH = new QBrush(new QColor(72, 61, 139));
    private static final QPen BUTTON_PEN = new QPen(new QColor(72, 61, 139));
    private static final QBrush BUTTON_TEXT_BRUSH = new QBrush(QColor.white);

    private static final double PORT_SIZE = 6.0;
    private static final double MIN_NODE_HEIGHT = 30.0;
    private static final double MIN_NODE_WIDTH = 40.0;

    private static final double PORT_HEIGHT = 20.0;
    private static final double PORT_NAME_BUFFER = 40.0;
    private static final double TOP_PORT_WIDTH = 20.0;
    private static final double TOP_PORT_HEIGHT = 14.0;
    private static final double PORT_LABEL_SPACING = 4.0;

    private static final double NODE_TO_NODE_SPACING = 40.0;
    private static final double EDGE_TO_NODE_SPACING = 20.0;
    private static final double SIDE_PADDING = 10.0;


    private static final double POINT_DIST = TOP_PORT_HEIGHT * 0.2; // Pointy part of the port

    private static final double BUTTON_SIZE = 16.0;
    private static final double BUTTON_RADIUS = 3.0;
    private static final double LABEL_BUFFER = 2.0;
    private static final double PIN_LINE_LENGTH = 10.0;

    private static final String HIER_BUTTON = "HIER_BUTTON";

    /**
     * Rather than drawing an invisible, thick copy of every clickable item just to make it easier
     * to hit with the mouse, clicks are resolved by searching a small box around the cursor (see
     * {@link #pickItem(QPointF)}). Since the item that should win a click is not always the item
     * drawn on top (a leaf cell's pin labels are drawn over its rectangle, for example), each
     * clickable item stores its own pick priority in this data slot--highest priority wins.
     */
    private static final int PICK_PRIORITY = 2;
    /** Half the width/height, in scene units, of the box used to resolve a mouse click */
    private static final double PICK_TOLERANCE = 5.0;

    /**
     * Data slot holding the pen a selectable item was drawn with, so that de-selecting it restores
     * the right color (a hierarchical cell's border is not the same color as a leaf cell's).
     */
    private static final int UNSELECTED_PEN = 3;

    private static final int PICK_BUTTON = 10;
    private static final int PICK_CELL = 6;
    private static final int PICK_NET = 5;
    private static final int PICK_EXPANDED_CELL = 4;
    private static final int PICK_PIN = 3;
    private static final int PICK_TOP_PORT = 1;

    /**
     * Number of children above which an {@link ElkNode} gets the faster (but slightly lower
     * quality) layout settings applied by {@link #applyLargeGraphElkProperties(ElkNode)}.
     */
    private static final int LARGE_GRAPH_NODE_THRESHOLD = 200;

    public SchematicScene(EDIFNetlist netlist) {
        super();
        this.netlist = netlist;
        setBackgroundBrush(canvasBackgroundBrush);
    }

    private ElkNode createElkRoot(EDIFHierCellInst cellInst) {
        ElkNode root = ElkGraphFactory.eINSTANCE.createElkNode();
        root.setIdentifier(cellInst.toString());
        applyElkNodeProperties(root);
        return root;
    }

    private void applyElkNodeProperties(ElkNode root) {
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        root.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_TO_NODE_SPACING);
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_TO_NODE_SPACING);
    }

    /**
     * By default, ELK's layered algorithm runs a fairly exhaustive crossing minimization which
     * becomes the dominant cost when drawing a large cell (measured at 38.5s for a cell with 8201
     * instances). Reducing the number of layer sweeps and turning off the greedy switch heuristic
     * brings that down to 15.6s at the cost of some additional edge crossings. This is only worth
     * trading away on graphs large enough for the user to notice the delay, so smaller schematics
     * keep the higher quality layout.
     *
     * @param node The node to check (recursively, along with its children).
     */
    private static void applyLargeGraphElkProperties(ElkNode node) {
        if (node.getChildren().size() > LARGE_GRAPH_NODE_THRESHOLD) {
            node.setProperty(LayeredOptions.THOROUGHNESS, 1);
            node.setProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE, GreedySwitchType.OFF);
        }
        for (ElkNode child : node.getChildren()) {
            applyLargeGraphElkProperties(child);
        }
    }

    public void drawCell(EDIFHierCellInst cellInst, boolean zoomFit) {
        clear();
        portInstMap.clear();
        elkNodeTopPortMap.clear();
        elkNodeCellMap.clear();
        lookupMap.clear();
        selectedObjects.clear();
        this.currCellInst = cellInst;
        elkRoot = createElkRoot(cellInst);
        populateCellContent(cellInst, elkRoot, "");
        elkNodeCellMap.put(elkRoot, cellInst);
        applyLargeGraphElkProperties(elkRoot);

        IGraphLayoutEngine engine = new RecursiveGraphLayoutEngine();
        IElkProgressMonitor monitor = new BasicProgressMonitor();
        engine.layout(elkRoot, monitor);

        renderSchematic();
        if (zoomFit) {
            cellDrawn.emit();
        }
    }

    public void renderSchematic() {
        double extraWidthBuffer = elkRoot.getWidth() * 0.25 + 100;
        double extraHeightBuffer = elkRoot.getHeight() * 0.25 + 100;
        QSizeF size = new QSizeF(elkRoot.getWidth() + extraWidthBuffer, elkRoot.getHeight() + extraHeightBuffer);

        setSceneRect(new QRectF(new QPointF(0, 0), size));

        // We create arrow-shaped top port ElkNodes to serve as targets for top ports
        for (ElkNode topPort : elkRoot.getChildren()) {
            EDIFHierPortInst hierPortInst = elkNodeTopPortMap.get(topPort);
            if (hierPortInst != null) {
                QPolygonF portShape = createPortShape(topPort, hierPortInst.isOutput());
                QGraphicsPolygonItem port = addPolygon(portShape, PORT_PEN, PORT_BRUSH);
                String lookup = NetlistTreeWidget.PORT_ID + hierPortInst.toString();
                port.setData(0, lookup);
                port.setData(PICK_PRIORITY, PICK_TOP_PORT);
                port.setData(UNSELECTED_PEN, PORT_PEN);
                port.setZValue(1);
                String portInstName = hierPortInst.getPortInst().getName();
                port.setToolTip(portInstName + (hierPortInst.isOutput() ? "(Output)" : "(Input)"));
                port.setAcceptsHoverEvents(true);
                lookupMap.computeIfAbsent(lookup, l -> new ArrayList<>()).add(port);

                QGraphicsSimpleTextItem portLabel = addSimpleText(portInstName);
                portLabel.setBrush(BLACK_BRUSH);
                portLabel.setFont(FONT);
                double portShapeX = topPort.getX() + (topPort.getWidth() - TOP_PORT_WIDTH) / 2.0;
                double portShapeY = topPort.getY() + (topPort.getHeight() - TOP_PORT_HEIGHT) / 2.0;
                double labelX = portShapeX;
                if (hierPortInst.isOutput()) {
                    // Position label to the right of top ports
                    labelX += TOP_PORT_WIDTH + PORT_LABEL_SPACING;
                } else {
                    // To the left for inputs
                    labelX -= portLabel.boundingRect().width() + PORT_LABEL_SPACING;
                }
                double labelY = portShapeY + (TOP_PORT_HEIGHT - portLabel.boundingRect().height()) / 2.0;
                portLabel.setPos(labelX, labelY);
                portLabel.setZValue(4);
            }
        }

        renderNode(elkRoot, 0, 0, "");
        renderEdges(elkRoot, 0, 0);
    }

    private void renderNode(ElkNode parent, double xOffset, double yOffset, String prefix) {
        EDIFCell cell = elkNodeCellMap.get(parent).getCellType();
        for (ElkNode child : parent.getChildren()) {
            if (elkNodeTopPortMap.containsKey(child)) {
                // Rendered in renderSchematic()
                continue;
            }
            String relCellInstName = child.getIdentifier();
            if (relCellInstName.startsWith(parent.getIdentifier()) && parent.getIdentifier().length() > 0) {
                // Remove hierarchical reference
                relCellInstName = relCellInstName.substring(parent.getIdentifier().length() + 1);
            }
            EDIFCellInst eci = cell.getCellInst(relCellInstName);

            boolean isLeaf = true;
            String cellInstName = prefix + relCellInstName;
            boolean isExpanded = expandedCellInsts.contains(cellInstName);
            if (child.getChildren().size() > 0) {
                isLeaf = false;
            } else if (eci != null && !eci.getCellType().isLeafCellOrBlackBox()) {
                isLeaf = false;
            }

            double x = xOffset + child.getX();
            double y = yOffset + child.getY();

            QGraphicsRectItem rect = null;
            QPen rectPen = CELL_PEN;
            int pickPriority = PICK_CELL;
            if (isLeaf) {
                rect = addRect(x, y, child.getWidth(), child.getHeight(), CELL_PEN, CELL_BRUSH);
            } else {
                if (isExpanded) {
                    rectPen = EXPANDED_HIER_CELL_PEN;
                    rect = addRect(x, y, child.getWidth(), child.getHeight(), rectPen, EXPANDED_HIER_CELL_BRUSH);
                    // An expanded cell loses clicks to the nets crossing over it
                    pickPriority = PICK_EXPANDED_CELL;
                } else {
                    rectPen = HIER_CELL_PEN;
                    rect = addRect(x, y, child.getWidth(), child.getHeight(), rectPen, HIER_CELL_BRUSH);
                }
                createHierButton(child, isExpanded, cellInstName, xOffset, yOffset);
            }
            String instLookup = NetlistTreeWidget.INST_ID + child.getIdentifier();
            rect.setData(0, instLookup);
            rect.setData(PICK_PRIORITY, pickPriority);
            rect.setData(UNSELECTED_PEN, rectPen);
            lookupMap.computeIfAbsent(instLookup, l -> new ArrayList<>()).add(rect);

            ElkLabel instNameLabel = child.getLabels().get(0); // instance name
            ElkLabel cellTypeLabel = child.getLabels().get(1); // cell type

            // Instance name centered above cell rectangle
            QGraphicsSimpleTextItem instLabel = addSimpleText(instNameLabel.getText());
            instLabel.setBrush(BLACK_BRUSH);
            instLabel.setFont(FONT);
            double instLabelX = x + (child.getWidth() - instLabel.boundingRect().width()) / 2.0;
            double instLabelY = y - instLabel.boundingRect().height();
            instLabel.setPos(instLabelX, instLabelY - LABEL_BUFFER);
            instLabel.setZValue(5);

            // Cell type centered below cell rectangle
            QGraphicsSimpleTextItem cellLabel = addSimpleText(cellTypeLabel.getText());
            cellLabel.setBrush(BLACK_BRUSH);
            cellLabel.setFont(FONT);
            double cellLabelX = x + (child.getWidth() - cellLabel.boundingRect().width()) / 2.0;
            double cellLabelY = y + child.getHeight();
            cellLabel.setPos(cellLabelX, cellLabelY + LABEL_BUFFER);
            cellLabel.setZValue(5);

            for (ElkPort port : child.getPorts()) {
                double yPort = y + port.getY() + port.getHeight() / 2.0;
                PortSide side = port.getProperty(CoreOptions.PORT_SIDE);
                drawPin(child, port, yPort, side, isExpanded, xOffset, child.getIdentifier());
                                
                QGraphicsSimpleTextItem pinLabel = addSimpleText(port.getIdentifier());
                pinLabel.setBrush(BLACK_BRUSH);
                pinLabel.setFont(FONT);
                pinLabel.setZValue(5);
                double textWidth = pinLabel.boundingRect().width();
                double textHeight = pinLabel.boundingRect().height();
                double labelX = x;
                double labelY = y;
                
                if (!isLeaf) {
                    labelX += side == PortSide.EAST ? child.getWidth() + LABEL_BUFFER : - textWidth - LABEL_BUFFER;
                    labelY = yPort - textHeight + LABEL_BUFFER;
                } else {
                    labelX += side == PortSide.EAST ? child.getWidth() - textWidth - 2*LABEL_BUFFER : 2*LABEL_BUFFER;
                    labelY = yPort - textHeight / 2.0;
                }
                pinLabel.setPos(labelX, labelY);
            }

            if (child.getChildren().size() > 0) {
                renderNode(child, x, y, prefix + relCellInstName + "/");
            }
        }
    }

    private void drawPin(ElkNode cell, ElkPort port, double y, PortSide side, boolean isExpanded, double xOffset, String parentInst) {
        double x1 = cell.getX() + xOffset + (side == PortSide.EAST ? cell.getWidth() : -PIN_LINE_LENGTH);
        double x2 = cell.getX() + xOffset + (side == PortSide.EAST ? cell.getWidth() + PIN_LINE_LENGTH : 0);

        // Draw outer pins
        QGraphicsLineItem pinLine = addLine(x1, y, x2, y, BLACK_PEN);
        pinLine.setZValue(2);
        String lookup = NetlistTreeWidget.PORT_ID + parentInst + "/" + port.getIdentifier();
        pinLine.setData(0, lookup);
        pinLine.setData(PICK_PRIORITY, PICK_PIN);
        pinLine.setData(UNSELECTED_PEN, BLACK_PEN);
        lookupMap.computeIfAbsent(lookup, l -> new ArrayList<>()).add(pinLine);

        if (isExpanded) {
            // Draw inner pins
            x1 = cell.getX() + xOffset + (side == PortSide.EAST ? cell.getWidth() - PIN_LINE_LENGTH : 0);
            x2 = cell.getX() + xOffset + (side == PortSide.EAST ? cell.getWidth() : PIN_LINE_LENGTH);
            QGraphicsLineItem innerPinLine = addLine(x1, y, x2, y, BLACK_PEN);
            innerPinLine.setZValue(2);
        }
    }

    private QGraphicsPathItem createHierButton(ElkNode node, boolean isExpanded, String expandedCellName, double xOffset, double yOffset) {
        double buttonX = xOffset + node.getX() + BUTTON_SIZE / 2;
        double buttonY = yOffset + node.getY() + BUTTON_SIZE / 2;
        QPainterPath path = new QPainterPath();
        path.addRoundedRect(buttonX, buttonY, BUTTON_SIZE, BUTTON_SIZE, BUTTON_RADIUS, BUTTON_RADIUS);

        // Create a rounded rectangle for the hierarchy button
        QGraphicsPathItem button = addPath(path, BUTTON_PEN, BUTTON_BRUSH);
        String data = HIER_BUTTON + ": " + expandedCellName + " : " + (isExpanded ? "COLLAPSE" : "EXPAND");
        button.setData(0, data);
        button.setData(PICK_PRIORITY, PICK_BUTTON);
        String tooltip = isExpanded ? "Collapse" : "Expand";
        button.setToolTip(tooltip);
        button.setZValue(10);

        // Add text to the button
        QGraphicsSimpleTextItem buttonText = addSimpleText(isExpanded ? "-" : "+");
        buttonText.setBrush(BUTTON_TEXT_BRUSH);
        buttonText.setFont(BUTTON_TEXT_FONT);
        double textX = buttonX + (BUTTON_SIZE - buttonText.boundingRect().width()) / 2.0;
        double textY = buttonY + (BUTTON_SIZE - buttonText.boundingRect().height()) / 2.0;
        buttonText.setPos(textX, textY);
        buttonText.setZValue(11);
        buttonText.setData(0, data);
        buttonText.setData(PICK_PRIORITY, PICK_BUTTON);
        buttonText.setToolTip(tooltip);

        return button;
    }

    private void renderEdges(ElkNode parent, double xOffset, double yOffset) {
        for (ElkEdge e : parent.getContainedEdges()) {
            if (e.getSections().isEmpty()) continue;
            ElkEdgeSection s = e.getSections().get(0);

            double startX = xOffset + s.getStartX();
            double startY = yOffset + s.getStartY();
            double endX = xOffset + s.getEndX();
            double endY = yOffset + s.getEndY();

            if (!e.getSources().isEmpty()) {
                ElkPort srcPort = (ElkPort) e.getSources().get(0);
                ElkNode portParent = (ElkNode) srcPort.getParent();
                EDIFHierPortInst portInst = elkNodeTopPortMap.get(portParent);
                if (portInst != null && portInst.getPortInst().isTopLevelPort()) {
                    QPointF topPortLoc = getTopPortConnectionPoint(portParent, portInst.isOutput());
                    startX = topPortLoc.x();
                    startY = topPortLoc.y();
                }
            }

            if (!e.getTargets().isEmpty()) {
                ElkPort snkPort = (ElkPort) e.getTargets().get(0);
                ElkNode portParent = (ElkNode) snkPort.getParent();
                EDIFHierPortInst portInst = elkNodeTopPortMap.get(portParent);
                if (portInst != null && portInst.getPortInst().isTopLevelPort()) {
                    QPointF topPortLoc = getTopPortConnectionPoint(portParent, portInst.isOutput());
                    endX = topPortLoc.x();
                    endY = topPortLoc.y();
                }
            }

            // The whole edge is drawn as a single polyline item. Drawing each segment as its own
            // item is what makes a large schematic expensive, both to create and (because Qt
            // removes items from the scene one at a time) to tear down on the next draw.
            QPainterPath path = new QPainterPath();
            path.moveTo(startX, startY);
            for (ElkBendPoint bp : s.getBendPoints()) {
                path.lineTo(bp.getX() + xOffset, bp.getY() + yOffset);
            }
            path.lineTo(endX, endY);

            String id = e.getIdentifier();
            String lookup = NetlistTreeWidget.NET_ID + (id == null ? "" : id);
            QGraphicsPathItem net = addPath(path, NET_PEN);
            net.setData(0, lookup);
            net.setData(PICK_PRIORITY, PICK_NET);
            net.setData(UNSELECTED_PEN, NET_PEN);
            net.setZValue(0);
            lookupMap.computeIfAbsent(lookup, l -> new ArrayList<>()).add(net);
        }

        for (ElkNode child : parent.getChildren()) {
            if (child.getChildren().size() > 0) {
                renderEdges(child, child.getX() + xOffset, child.getY() + yOffset);
            }
        }
    }

    private QPointF getTopPortConnectionPoint(ElkNode port, boolean isOutput) {
        double portX = port.getX() + (port.getWidth() - TOP_PORT_WIDTH) / 2.0;
        double portY = port.getY() + (port.getHeight() - TOP_PORT_HEIGHT) / 2.0;
        return new QPointF(portX + (isOutput ? -POINT_DIST : POINT_DIST) + TOP_PORT_WIDTH, portY + TOP_PORT_HEIGHT / 2);
    }

    private static QPolygonF createPortShape(ElkNode topPort, boolean isOutput) {
        double portX = topPort.getX();
        double portY = topPort.getY();
        double height = topPort.getHeight();
        double width = topPort.getWidth();
        double x = portX + (width - TOP_PORT_WIDTH) / 2.0;
        double y = portY + (height - TOP_PORT_HEIGHT) / 2.0;

        QPolygonF portShape = new QPolygonF();

        if (isOutput) {
            // Point to the left
            portShape.add(new QPointF(x + POINT_DIST, y));
            portShape.add(new QPointF(x + TOP_PORT_WIDTH, y));
            portShape.add(new QPointF(x + TOP_PORT_WIDTH, y + TOP_PORT_HEIGHT));
            portShape.add(new QPointF(x + POINT_DIST, y + TOP_PORT_HEIGHT));
            portShape.add(new QPointF(x - POINT_DIST, y + (TOP_PORT_HEIGHT / 2)));
        } else {
            // Input points to the right
            portShape.add(new QPointF(x, y));
            portShape.add(new QPointF(x + TOP_PORT_WIDTH - POINT_DIST, y));
            portShape.add(new QPointF(x + TOP_PORT_WIDTH + POINT_DIST, y + (TOP_PORT_HEIGHT / 2)));
            portShape.add(new QPointF(x + TOP_PORT_WIDTH - POINT_DIST, y + TOP_PORT_HEIGHT));
            portShape.add(new QPointF(x, y + TOP_PORT_HEIGHT));
        }

        return portShape;
    }

    private void populateCellContent(EDIFHierCellInst cellInst, ElkNode parent, String prefix) {
        ElkGraphFactory f = ElkGraphFactory.eINSTANCE;
        EDIFCell cell = cellInst.getCellType();

        // Only create top level port shapes if this is the top cell
        if (prefix.isEmpty()) {
            for (EDIFPort topPort : cell.getPorts()) {
                for (int i : topPort.getBitBlastedIndices()) {
                    String portInstName = topPort.getPortInstNameFromPort(i);
                    ElkNode elkTopPortNode = f.createElkNode();
                    EDIFHierPortInst hierPortInst = cellInst.getPortInst(portInstName);
                    if (hierPortInst == null) {
                        EDIFPortInst portInst = topPort.isBus() ? topPort.getInternalPortInstFromIndex(i)
                                : topPort.getInternalPortInst();
                        if (portInst == null) {
                            portInst = new EDIFPortInst(topPort, null, topPort.isBus() ? i : -1);
                        }
                        hierPortInst = new EDIFHierPortInst(cellInst, portInst);
                    }
                    elkNodeTopPortMap.put(elkTopPortNode, hierPortInst);
                    elkTopPortNode.setDimensions(TOP_PORT_WIDTH + POINT_DIST, TOP_PORT_HEIGHT);
                    elkTopPortNode.setIdentifier(portInstName);
                    elkTopPortNode.setParent(parent);
                    parent.getChildren().add(elkTopPortNode);
                    labelElkNode(elkTopPortNode, portInstName);
                    elkTopPortNode.setProperty(CoreOptions.NODE_LABELS_PLACEMENT,
                            EnumSet.of(topPort.isOutput() ? NodeLabelPlacement.H_RIGHT : NodeLabelPlacement.H_LEFT));
                    ElkPort elkTopPort = f.createElkPort();
                    elkTopPort.setParent(elkTopPortNode);
                    elkTopPort.setIdentifier(portInstName);
                    elkTopPort.setProperty(CoreOptions.PORT_SIDE, topPort.isOutput() ? PortSide.EAST : PortSide.WEST);
                    elkTopPort.setProperty(CoreOptions.PORT_INDEX, 1);
                    elkTopPort.setDimensions(PORT_SIZE, PORT_SIZE);
                    portInstMap.put(hierPortInst, elkTopPort);
                    elkTopPortNode.getPorts().add(elkTopPort);
                }
            }
        }

        Map<EDIFHierCellInst, ElkNode> instNodeMap = new HashMap<>();
        for (EDIFCellInst inst : cell.getCellInsts()) {
            ElkNode elkInst = f.createElkNode();
            elkInst.setParent(parent);
            parent.getChildren().add(elkInst);
            EDIFHierCellInst childInst = cellInst.getChild(inst);
            elkInst.setIdentifier(childInst.toString());
            elkInst.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_ORDER);
            instNodeMap.put(childInst, elkInst);
            elkNodeCellMap.put(elkInst, childInst);

            boolean isHierCell = !inst.getCellType().isLeafCellOrBlackBox();

            // Create labels
            elkInst.setProperty(CoreOptions.NODE_LABELS_PLACEMENT, NodeLabelPlacement.outsideTopCenter());
            labelElkNode(elkInst, inst.getName());
            labelElkNode(elkInst, inst.getCellName());

            // Create ports
            Map<String, EDIFHierPortInst> westPorts = new TreeMap<>();
            Map<String, EDIFHierPortInst> eastPorts = new TreeMap<>();
            double longestWestName = 0;
            double longestEastName = 0;
            // for (EDIFPort port : inst.getCellPorts()) {
            for (EDIFHierPortInst hierPortInst : childInst.getHierPortInsts()) {
                String name = hierPortInst.getPortInst().getName();
                if (hierPortInst.isInput()) {
                    westPorts.put(name, hierPortInst);
                    longestWestName = Math.max(longestWestName, fm.width(name));
                } else {
                    eastPorts.put(name, hierPortInst);
                    longestEastName = Math.max(longestEastName, fm.width(name));
                }
            }

            int portStartIndex = 0;
            portStartIndex = createElkPorts(portStartIndex, isHierCell, elkInst, westPorts, PortSide.WEST);
            portStartIndex = createElkPorts(portStartIndex, isHierCell, elkInst, eastPorts, PortSide.EAST);

            // Calculate cell rectangle size
            int maxPins = Math.max(westPorts.size(), eastPorts.size());
            double height = Math.max(MIN_NODE_HEIGHT, PORT_HEIGHT * maxPins);
            double width = Math.max(MIN_NODE_WIDTH, longestWestName + longestEastName + PORT_NAME_BUFFER);
            elkInst.setDimensions(width, height);

            if (isHierCell && expandedCellInsts.contains(prefix + inst.getName())) {
                applyElkNodeProperties(elkInst);
                // Extra spacing for button placement
                elkInst.setProperty(CoreOptions.PADDING, new ElkPadding(
                        BUTTON_SIZE * 2, // Top
                        SIDE_PADDING, // Side
                        fm.height() + LABEL_BUFFER * 2, // Bottom
                        SIDE_PADDING // Side
                ));
                createExpandedCellInnerPorts(childInst);
                populateCellContent(childInst, elkInst, prefix + inst.getName() + "/");
            }
        }

        for (EDIFNet net : cell.getNets()) {
            List<EDIFHierPortInst> drivers = new ArrayList<>();
            List<EDIFHierPortInst> sinks = new ArrayList<>();

            for (EDIFPortInst p : net.getPortInsts()) {
                EDIFHierPortInst hierPortInst = new EDIFHierPortInst(cellInst, p);
                if (p.isTopLevelPort()) {
                    if (p.isOutput()) {
                        sinks.add(hierPortInst);
                    } else {
                        drivers.add(hierPortInst);
                    }
                } else {
                    if (p.isOutput()) {
                        drivers.add(hierPortInst);
                    } else {
                        sinks.add(hierPortInst);
                    }
                }
            }

            for (EDIFHierPortInst d : drivers) {
                ElkPort driver = getOrCreateElkPort(d, prefix, instNodeMap, cellInst);
                for (EDIFHierPortInst s : sinks) {
                    ElkPort sink = getOrCreateElkPort(s, prefix, instNodeMap, cellInst);
                    if (driver == null || sink == null)
                        continue;

                    ElkEdge edge = ElkGraphFactory.eINSTANCE.createElkEdge();
                    edge.setContainingNode(parent);
                    String id = cellInst.isTopLevelInst() ? net.getName() : (cellInst + "/" + net.getName());
                    edge.setIdentifier(id);
                    edge.getSources().add(driver);
                    edge.getTargets().add(sink);
                    parent.getContainedEdges().add(edge);
                }
            }
        }
    }

    private void createExpandedCellInnerPorts(EDIFHierCellInst inst) {
        for (EDIFPort port : inst.getCellType().getPorts()) {
            for (int i : port.getBitBlastedIndices()) {
                EDIFPortInst outerPortInst = inst.getInst().getPortInst(port.getPortInstNameFromPort(i));
                ElkPort outerElkPort = portInstMap.get(outerPortInst);
                // Map the inner port inst to the outer one so nets are aligned
                if (outerElkPort != null) {
                    EDIFPortInst innerPortInst = port.getInternalPortInstFromIndex(i);
                    if (innerPortInst != null) {
                        portInstMap.put(inst.getPortInst(innerPortInst.getName()), outerElkPort);
                    }
                }
            }
        }
    }

    private ElkPort getOrCreateElkPort(EDIFHierPortInst p, String prefix, Map<EDIFHierCellInst, ElkNode> instNodeMap,
            EDIFHierCellInst cellInst) {
        ElkPort port = portInstMap.get(p);
        if (port == null) {
            port = ElkGraphFactory.eINSTANCE.createElkPort();
            if (p.getPortInst().isTopLevelPort()) {
                p = cellInst.getPortInst(p.getPortInst().getName());
                port = portInstMap.get(p);
            } else {
                ElkNode inst = instNodeMap.get(p.getFullHierarchicalInst());
                port.setParent(inst);
                inst.getPorts().add(port);
                port.setIdentifier(p.getPortInst().getName());
                port.setDimensions(PORT_SIZE, PORT_SIZE);
                port.setProperty(CoreOptions.PORT_SIDE, p.isOutput() ? PortSide.EAST : PortSide.WEST);
            }

            portInstMap.put(p, port);
        }
        return port;
    }

    private int createElkPorts(int startIdx, boolean isHierCell, ElkNode parent, Map<String, EDIFHierPortInst> portNames,
            PortSide side) {
        for (Entry<String, EDIFHierPortInst> e : portNames.entrySet()) {
            ElkPort port = ElkGraphFactory.eINSTANCE.createElkPort();
            portInstMap.put(e.getValue(), port);
            port.setParent(parent);
            parent.getPorts().add(port);
            port.setIdentifier(e.getKey());
            port.setProperty(CoreOptions.PORT_SIDE, side);
            port.setProperty(CoreOptions.PORT_INDEX, startIdx++);
            port.setDimensions(PORT_SIZE, PORT_SIZE);

            if (isHierCell) {
                labelElkNode(port, e.getKey());
            }

        }
        return startIdx;
    }

    private void labelElkNode(ElkGraphElement n, String name) {
        ElkLabel label = ElkGraphFactory.eINSTANCE.createElkLabel();
        label.setText(name);
        label.setParent(n);
        n.getLabels().add(label);
        label.setDimensions(fm.width(label.getText()), fm.height());
    }

    /**
     * Finds the clickable item nearest the provided point. Items are searched within
     * {@link #PICK_TOLERANCE} of the point so that thin items (nets and pins) don't have to be hit
     * exactly, and the candidate with the highest {@link #PICK_PRIORITY} wins.
     *
     * @param pos The point clicked on, in scene coordinates.
     * @return The item that should receive the click, or null if there isn't one.
     */
    private QGraphicsItemInterface pickItem(QPointF pos) {
        QRectF box = new QRectF(pos.x() - PICK_TOLERANCE, pos.y() - PICK_TOLERANCE, 2 * PICK_TOLERANCE,
                2 * PICK_TOLERANCE);
        QGraphicsItemInterface picked = null;
        int pickedPriority = Integer.MIN_VALUE;
        double pickedDistance = 0.0;
        // items() returns descending stacking order, so keeping the first item found at a given
        // priority also keeps the top-most of any items that tie
        for (QGraphicsItemInterface item : items(box, ItemSelectionMode.IntersectsItemShape)) {
            Object priority = item.data(PICK_PRIORITY);
            if (priority == null || item.data(0) == null) {
                continue;
            }
            double distance = 0.0;
            if ((Integer) priority == PICK_NET) {
                // Qt hit tests a path item by filling its path, and an open polyline gets
                // implicitly closed -- so the box test above reports a net as hit anywhere inside
                // the area its route encloses, which can be most of the schematic. Measure the
                // real distance to the wire instead, and among the nets that are actually within
                // reach let the closest one win.
                distance = distanceToRoute(((QGraphicsPathItem) item).path(), pos.x(), pos.y());
                if (distance > PICK_TOLERANCE) {
                    continue;
                }
            }
            if ((Integer) priority > pickedPriority
                    || ((Integer) priority == pickedPriority && distance < pickedDistance)) {
                pickedPriority = (Integer) priority;
                pickedDistance = distance;
                picked = item;
            }
        }
        return picked;
    }

    /**
     * Gets the shortest distance from a point to a net's route.
     *
     * @param route The net's route, as the polyline drawn for it.
     * @param x     X coordinate of the point.
     * @param y     Y coordinate of the point.
     * @return The distance from the point to the nearest segment of the route.
     */
    private static double distanceToRoute(QPainterPath route, double x, double y) {
        double closest = Double.MAX_VALUE;
        int count = route.elementCount();
        for (int i = 1; i < count; i++) {
            QPainterPath_Element from = route.elementAt(i - 1);
            QPainterPath_Element to = route.elementAt(i);
            if (!to.isLineTo()) {
                // Net routes are polylines, so anything else is a new sub path with no segment
                continue;
            }
            closest = Math.min(closest, distanceToSegment(from.x(), from.y(), to.x(), to.y(), x, y));
        }
        return closest;
    }

    private static double distanceToSegment(double x1, double y1, double x2, double y2, double x, double y) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        // Project the point onto the segment, clamping to its end points
        double t = lengthSquared == 0.0 ? 0.0 : ((x - x1) * dx + (y - y1) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy));
    }

    public void mousePressEvent(QGraphicsSceneMouseEvent event) {
        QGraphicsItemInterface item = pickItem(event.scenePos());
        if (item != null) {
            String data = item.data(0).toString();
            if (data.startsWith(HIER_BUTTON)) {
                String[] parts = data.split(":");
                toggleCellInstExpansion(parts[1].trim());
            } else if (data.startsWith(NetlistTreeWidget.INST_ID) || data.startsWith(NetlistTreeWidget.NET_ID)
                    || data.startsWith(NetlistTreeWidget.PORT_ID)) {
                boolean ctrlPressed = event.modifiers().isSet(KeyboardModifier.ControlModifier);
                toggleSelection(data, ctrlPressed);
            }
        }
        super.mousePressEvent(event);
    }

    private void toggleCellInstExpansion(String cellInstName) {
        if (expandedCellInsts.contains(cellInstName)) {
            expandedCellInsts.remove(cellInstName);
        } else {
            expandedCellInsts.add(cellInstName);
        }
        boolean zoomFit = false;
        drawCell(currCellInst, zoomFit);
    }

    private void toggleSelection(String lookup, boolean multipleSelection) {
        if (!multipleSelection) {
            clearSelections();
        }
        
        if (selectedObjects.contains(lookup)) {
            updateSelectionHighlight(lookup, false);
            selectedObjects.remove(lookup);
        } else {
            updateSelectionHighlight(lookup, true);
            selectedObjects.add(lookup);
        }
        
        objectSelected.emit(lookup);
    }

    private void clearSelections() {
        for (String unselected : selectedObjects) {
            updateSelectionHighlight(unselected, false);
        }
        selectedObjects.clear();
    }

    public void selectObject(String lookup, boolean clearPreviousSelections) {
        if (clearPreviousSelections) {
            clearSelection();
        }
        selectedObjects.remove(lookup);
        toggleSelection(lookup, !clearPreviousSelections);
    }

    private void updateSelectionHighlight(String lookup, boolean isSelected) {
        List<Object> guiObjects = lookupMap.get(lookup);
        for (Object guiObject : guiObjects == null ? Collections.EMPTY_LIST : guiObjects) {
            if (guiObject instanceof QAbstractGraphicsShapeItem) {
                QAbstractGraphicsShapeItem shape = (QAbstractGraphicsShapeItem) guiObject;
                shape.setPen(isSelected ? SELECTED_PEN : (QPen) shape.data(UNSELECTED_PEN));
            } else if (guiObject instanceof QGraphicsLineItem) {
                QGraphicsLineItem line = (QGraphicsLineItem) guiObject;
                line.setPen(isSelected ? SELECTED_PEN : (QPen) line.data(UNSELECTED_PEN));
            }

        }
    }
}
