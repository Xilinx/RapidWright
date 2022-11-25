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
    protected int highestNonZeroWord = -1;

    public SparseBitSet() {
    }

    public boolean get(int bit) {
        final int firstDim = bit >> SHIFT_FIRST_DIM;
        if (firstDim > highestNonZeroWord) {
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
            // Double capacity of first dimension
            words = Arrays.copyOf(words, words.length << 1);
        }
        if (words[firstDim] == null) {
            words[firstDim] = new long[1 << BITS_SECOND_DIM];
        }
        final int secondDim = (bit & MASK_SECOND_DIM) >> SHIFT_SECOND_DIM;
        final long bitMask = 1L << (bit & MASK_LAST_DIM);
        words[firstDim][secondDim] |= bitMask;
        highestNonZeroWord = Math.max(highestNonZeroWord, firstDim);
    }

    public void clear() {
        Arrays.fill(words, 0, highestNonZeroWord + 1,null);
        highestNonZeroWord = -1;
    }

    public boolean isEmpty() {
        return highestNonZeroWord == -1;
    }
}
