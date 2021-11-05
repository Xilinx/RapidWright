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
package com.xilinx.rapidwright.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import com.trolltech.qt.core.QRect;
import com.trolltech.qt.core.QRectF;
import com.trolltech.qt.core.Qt;
import com.trolltech.qt.gui.QBrush;
import com.trolltech.qt.gui.QColor;
import com.trolltech.qt.gui.QFont;
import com.trolltech.qt.gui.QPainter;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.TileRectangle;
import com.xilinx.rapidwright.device.Tile;


/**
 * UI Scene that can show named PBlocks
 */
public class PBlockScene extends TileScene {
    private List<UiPBlock> blocks;
    private int blockOpacity = 200;

    public void setDrawIntConnections(boolean drawIntConnections) {
        this.drawIntConnections = drawIntConnections;
    }

    private boolean drawIntConnections = false;

    public PBlockScene(Design design) {
        super(design, false, true);
        this.blocks = new ArrayList<>();
    }

    public List<UiPBlock> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<UiPBlock> blocks) {
        this.blocks = blocks;
    }

    @Override
    protected void drawFPGAFabric(QPainter painter) {
        super.drawFPGAFabric(painter);
    }

    private void drawIntConnections(QPainter painter) {
        painter.setPen(QColor.white);
        for (Tile tile: device.getAllTiles()) {
            Arrays.stream(tile.getSites())
                    .flatMap(s-> {
                        final Tile intTile = s.getIntTile();
                        if (intTile == null) {
                            return Stream.empty();
                        }
                        return Stream.of(intTile);
                    })
                    .distinct()
                    .forEach(intTile -> {
                        painter.drawLine(
                               tile.getColumn() * tileSize + tileSize/2,
                               tile.getRow() * tileSize + tileSize/2,
                               intTile.getColumn() * tileSize + tileSize/2,
                               intTile.getRow() * tileSize + tileSize/2
                        );
                    });
        }
    }

    @Override
    public void drawBackground(QPainter painter, QRectF rect) {
        super.drawBackground(painter, rect);

        if (drawIntConnections) {
            drawIntConnections(painter);
        }
        drawBlocks(painter);
    }

    public void setBlockOpacity(int blockOpacity) {

        this.blockOpacity = blockOpacity;
    }


    private void forAllRects(BiConsumer<UiPBlock, QRect> consumer) {
        for (final UiPBlock block : blocks) {
            final QRect qRect = tileRectToQRect(block.rect);
            consumer.accept(block, qRect);
        }
    }
    abstract class ForAllRects {
        abstract void doPaint(UiPBlock block, QRect rect);
        void run() {
            for (final UiPBlock block : blocks) {

                final TileRectangle rect = block.rect;
                final QRect qRect = tileRectToQRect(rect);

                doPaint(block, qRect);
            }

        }
    }

    private void drawBlocks(QPainter painter) {

        final QFont font = painter.font().clone();
        font.setPointSize(font.pointSize()*8);
        painter.setFont(font);


        //We draw all backgrounds, all outlines and then all texts to better support overlapped pblocks.

        forAllRects((block, rect) -> {
            final QColor transparent = block.color.clone();
            if (block.opacity == null) {
                transparent.setAlpha(blockOpacity);
            } else {
                transparent.setAlpha(block.opacity);
            }
            painter.fillRect(rect, new QBrush(transparent));
        });

        forAllRects((block, rect) -> {
            painter.setPen(block.color);
            painter.drawRect(rect);
        });
        forAllRects((block, rect) -> {
            painter.setPen(block.color);

            painter.drawText(
                    rect,
                    Qt.AlignmentFlag.createQFlags(Qt.AlignmentFlag.AlignVCenter, Qt.AlignmentFlag.AlignCenter).value(),
                    block.name
            );
        });

    }

    public QRect tileRectToQRect(TileRectangle rect) {
        return new QRect(
                rect.getMinColumn() * tileSize,
                (rect.getMinRow()) * tileSize,
                (rect.getWidth()+1) * tileSize-1,
                (rect.getHeight()+1) * tileSize-1
        );
    }
}
