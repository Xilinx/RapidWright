/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.device.browser;

import com.trolltech.qt.gui.QApplication;

/**
 * Helper class to enable {@link DeviceBrowser} to be threaded and non-blocking
 * when using an interpreter.
 */
public class ThreadedDeviceBrowser extends Thread {

    private DeviceBrowser deviceBrowser;

    private String[] args;

    public ThreadedDeviceBrowser(String[] args) {
        this.args = args;
    }

    @Override
    public void run() {
        QApplication.setGraphicsSystem("raster");
        QApplication.initialize(args);

        String defaultPart = null;
        if (args.length > 0) {
            defaultPart = args[0];
        }

        deviceBrowser = new DeviceBrowser(null, defaultPart);

        deviceBrowser.show();
        QApplication.exec();
        DeviceBrowser.removeThreadedInstance();
    }

    public DeviceBrowser getDeviceBrowser() {
        return deviceBrowser;
    }
}
