package com.xilinx.rapidwright.util;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Delegate all operations to the underlying OutputStream, except close.
 */
public class NoCloseOutputStream extends OutputStream {
    private final OutputStream out;

    @Override
    public void write(int b) throws IOException {
        out.write(b);
    }

    public NoCloseOutputStream(OutputStream out) {
        super();
        this.out = out;
    }

    @Override
    public void write(byte[] b) throws IOException {
        out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() {
        //Do not delegate this!
    }
}
