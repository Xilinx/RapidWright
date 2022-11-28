package com.xilinx.rapidwright.util;

import java.util.Arrays;

public class SparseBitSet {
    protected static final int INITIAL_SIZE_FIRST_DIM = 32;

    protected static final int BITS_MAX = 31;
    protected static final int BITS_LAST_DIM = 6; // log2(64) where 64 bits exist in a long
    protected static final int BITS_FIRST_DIM = 16;
    protected static final int BITS_SECOND_DIM = BITS_MAX - BITS_LAST_DIM - BITS_FIRST_DIM;
    protected static final int SHIFT_SECOND_DIM = BITS_LAST_DIM;
    protected static final int SHIFT_FIRST_DIM = BITS_SECOND_DIM + SHIFT_SECOND_DIM;
    protected static final int MASK_LAST_DIM = (1 << BITS_LAST_DIM) - 1;
    protected static final int MASK_SECOND_DIM = ((1 << BITS_SECOND_DIM) - 1) << BITS_LAST_DIM;

    protected long[][] words = new long[INITIAL_SIZE_FIRST_DIM][];
    protected int highestSetWord = -1;

    public SparseBitSet() {
    }

    public boolean get(int bit) {
        final int firstDim = bit >> SHIFT_FIRST_DIM;
        if (firstDim > highestSetWord) {
            return false;
        }
        if (words[firstDim] == null)
            return false;
        final int secondDim = (bit & MASK_SECOND_DIM) >> SHIFT_SECOND_DIM;
        final long bitMask = 1L << (bit & MASK_LAST_DIM);
        return (words[firstDim][secondDim] & bitMask) != 0;
    }

    public void set(int bit) {
        final int firstDim = bit >> SHIFT_FIRST_DIM;
        if (firstDim >= words.length) {
            // Round up to next power of 2
            words = Arrays.copyOf(words, Integer.highestOneBit(firstDim) << 1);
        }
        if (words[firstDim] == null) {
            words[firstDim] = new long[1 << BITS_SECOND_DIM];
        }
        final int secondDim = (bit & MASK_SECOND_DIM) >> SHIFT_SECOND_DIM;
        final long bitMask = 1L << (bit & MASK_LAST_DIM);
        words[firstDim][secondDim] |= bitMask;
        highestSetWord = Math.max(highestSetWord, firstDim);
    }

    public void clear() {
        Arrays.fill(words, 0, highestSetWord + 1,null);
        highestSetWord = -1;
    }

    public boolean isEmpty() {
        return highestSetWord == -1;
    }
}
