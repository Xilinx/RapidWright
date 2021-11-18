package com.xilinx.rapidwright.gui;

import java.util.ArrayList;

import com.trolltech.qt.core.QPointF;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QGraphicsPolygonItem;
import com.trolltech.qt.gui.QPen;
import com.trolltech.qt.gui.QPolygonF;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

/**
 * Show a single Module Instance. Highlight current placement and show all other possible ones.
 */
public class ModuleInstanceScene extends TileScene {

    private final ModuleInst moduleInstance;
    private final ArrayList<Site> validPlacements;

    private QPointF getTilePos(Tile tile) {
        int x = getDrawnTileX(tile);
        int y = getDrawnTileY(tile);
        return new QPointF(x*tileSize, y*tileSize);
    }

    public ModuleInstanceScene(ModuleInst moduleInstance, boolean showPlacements) {
        super(moduleInstance.getDesign(), false, true);

        this.moduleInstance = moduleInstance;

        GUIModuleInst ghmpi = new GUIModuleInst(moduleInstance, this, false);
        addItem(ghmpi);
        ghmpi.showGuts();
        ghmpi.setAnchorOffset();


        validPlacements = moduleInstance.getAllValidPlacements();

        if (showPlacements) {
            for (Site placement : validPlacements) {
                QPolygonF poly = ghmpi.getShape().clone();
                QGraphicsPolygonItem polyItem = new QGraphicsPolygonItem();
                polyItem.setPolygon(poly);
                polyItem.setBrush(QBrush.NoBrush);
                polyItem.setPen(new QPen(QColor.red, 5));
                QPointF tilePos = getTilePos(placement.getTile());
                polyItem.setPos(tilePos.subtract(ghmpi.getAnchorOffset()));
                addItem(polyItem);
            }
        }


    }
    public ModuleInstanceScene(ModuleInst moduleInstance) {
        this(moduleInstance, true);
    }


    public ArrayList<Site> getValidPlacements() {
        return validPlacements;
    }
}
