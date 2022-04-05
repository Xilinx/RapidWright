package com.xilinx.rapidwright.edif;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

import com.xilinx.rapidwright.util.function.ThrowingFunction;

/**
 * Compare the results of two different EDIF tokenizers
 */
public class ComparingEDIFTokenizer implements IEDIFTokenizer{
    private final IEDIFTokenizer a;
    private final IEDIFTokenizer b;

    public ComparingEDIFTokenizer(IEDIFTokenizer a, IEDIFTokenizer b) {
        this.a = a;
        this.b = b;
    }

    private <T,E extends Throwable> T forAll(ThrowingFunction<IEDIFTokenizer, T, E> f) throws E{
        T aRes = f.apply(a);
        T bRes = f.apply(b);
        if (!Objects.equals(aRes, bRes)) {
            throw new RuntimeException("not equal result: "+aRes+" vs "+bRes);
        }
        return aRes;
    }
    @Override
    public EDIFToken getOptionalNextToken(boolean isShortLived) {
        return forAll(t->t.getOptionalNextToken(isShortLived));
    }

    @Override
    public String getOptionalNextTokenString(boolean isShortLived) {
        return forAll(t->t.getOptionalNextTokenString(isShortLived));
    }


    @Override
    public void close() throws IOException {
        this.<Void, IOException>forAll(t->{t.close(); return null;});
    }

    @Override
    public void skip(long i) {
        forAll(t->{t.skip(i);return null;});
    }

    @Override
    public NameUniquifier getUniquifier() {
        return a.getUniquifier();
    }

    @Override
    public long getByteOffset() {
        return forAll(IEDIFTokenizer::getByteOffset);
    }

    public static ComparingEDIFTokenizer createTokenizers(byte[] bytes, NameUniquifier uniquifier, int maxTokenLength) throws IOException {
        IEDIFTokenizer a = new LegacyEDIFTokenizer(null, new ByteArrayInputStream(bytes), uniquifier, maxTokenLength);
        IEDIFTokenizer b = new EDIFTokenizerV2(null, new ByteArrayInputStream(bytes), uniquifier, maxTokenLength);
        return new ComparingEDIFTokenizer(a,b);
    }
    public static ComparingEDIFTokenizer createTokenizers(byte[] bytes, NameUniquifier uniquifier) throws IOException {
        return createTokenizers(bytes, uniquifier, EDIFTokenizerV2.DEFAULT_MAX_TOKEN_LENGTH);
    }
}
