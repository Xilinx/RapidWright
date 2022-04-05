/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
