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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.Objects;

import com.xilinx.rapidwright.design.ModuleImplsInst;
import com.xilinx.rapidwright.design.Port;
import com.xilinx.rapidwright.design.SimpleTileRectangle;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.TileRectangle;


/**
 * Port of a {@link ImplsPath}. Abstract, since we need to differentiate between ports of modules and outside non-module
 * ports
 */
public abstract class ImplsInstancePort {
    private ImplsPath path;

    public abstract String getName();

    public abstract boolean isOutputPort();

    public abstract void enterToRect(SimpleTileRectangle rect);

    public ImplsPath getPath() {
        return path;
    }

    public void setPath(ImplsPath path) {
        this.path = path;
    }

    /**
     * A port that represents a connection to something that is not contained in a module, like IO.
     * These are not going to be moved around in the placer.
     */
    public static class SitePinInstPort extends ImplsInstancePort {
        private final SitePinInst sitePinInst;

        public SitePinInstPort(SitePinInst sitePinInst) {
            this.sitePinInst = Objects.requireNonNull(sitePinInst);
        }

        @Override
        public String getName() {
            return sitePinInst.getSite().getName()+"."+sitePinInst.getName();
        }

        @Override
        public boolean isOutputPort() {
            return sitePinInst.isOutPin();
        }

        @Override
        public void enterToRect(SimpleTileRectangle rect) {
            rect.extendTo(sitePinInst.getTile());
        }

        public SitePinInst getSitePinInst() {
            return sitePinInst;
        }
    }

    /**
     * Port of a {@link ModuleImplsInst}
     */
    public static class InstPort extends ImplsInstancePort {
        private final ModuleImplsInst instance;
        private final String port;
        private boolean boundingBoxCalculated;
        private TileRectangle boundingBox;

        public InstPort(ModuleImplsInst instance, String port) {
            this.instance = instance;
            this.port = port;
        }

        public void resetBoundingBox() {
            boundingBoxCalculated = false;
            boundingBox = null;
        }

        @Override
        public String getName() {
            return port;
        }

        @Override
        public boolean isOutputPort() {
            return instance.getModule().get(0).getPort(port).isOutPort();
        }

        @Override
        public void enterToRect(SimpleTileRectangle rect) {
            if (!boundingBoxCalculated) {
                boundingBoxCalculated = true;
                if (instance.getPlacement() == null) {
                    return;
                }
                Port portImpl = instance.getCurrentModuleImplementation().getPort(this.port);
                if (portImpl == null) {
                    throw new IllegalStateException("In "+instance.getName()+" of type "+instance.getModule().getName()+", currently mapped to impl"+instance.getCurrentModuleImplementation()+", did not find abstract port "+this.port);
                }
                if (!portImpl.getSitePinInsts().isEmpty()) {
                    boundingBox = portImpl.getBoundingBox().getCorresponding(instance.getPlacement().placement.getTile(), instance.getCurrentModuleImplementation().getAnchor().getTile());
                }
            }
            if (boundingBox != null) {
                rect.extendTo(boundingBox);
            }
        }

        public ModuleImplsInst getInstance() {
            return instance;
        }

        public String getPort() {
            return port;
        }
    }
}
