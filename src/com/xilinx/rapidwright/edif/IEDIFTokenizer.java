package com.xilinx.rapidwright.edif;

import java.io.IOException;

public interface IEDIFTokenizer extends AutoCloseable{
    EDIFToken getOptionalNextToken(boolean isShortLived);
    String getOptionalNextTokenString(boolean isShortLived);

    void close() throws IOException;

    void skip(long i);

    NameUniquifier getUniquifier();

    long getByteOffset();
}
