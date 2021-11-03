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

import com.trolltech.qt.gui.QColor;
import com.xilinx.rapidwright.design.TileRectangle;

/**
 * A named and colored PBlock that can be shown in a {@link PBlockScene}
 */
public class UiPBlock {
    public final TileRectangle rect;
    public final String name;
    public final QColor color;
    public final Integer opacity;

    public UiPBlock(TileRectangle rect, String name, QColor color) {
        this.rect = rect;
        this.name = name;
        this.color = color;
        this.opacity = null;
    }
    public UiPBlock(TileRectangle rect, String name, QColor color, Integer opacity) {
        this.rect = rect;
        this.name = name;
        this.color = color;
        this.opacity = opacity;
    }
}
