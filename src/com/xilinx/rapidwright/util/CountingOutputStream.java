/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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
package com.xilinx.rapidwright.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * OutputStream wrapper that counts number of bytes written
 */
public class CountingOutputStream extends FilterOutputStream {
    private long bytesWritten;

    public CountingOutputStream(OutputStream os) {
        super(os);
        bytesWritten = 0;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        bytesWritten++;
    }

    @Override
    public void write(byte b[]) throws IOException {
        out.write(b);
        bytesWritten += b.length;
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        out.write(b, off, len);
        bytesWritten += len;
    }

    public long getBytesWritten() {
        return bytesWritten;
    }
}
