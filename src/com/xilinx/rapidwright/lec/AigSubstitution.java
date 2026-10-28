/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.lec;

import java.util.Arrays;

/**
 * Replaces chosen primary inputs of an {@link Aig} by literals, all at once.
 *
 * The replacement is simultaneous: an input is replaced by its value exactly
 * once, and the value itself is not rewritten again, so swapping {@code x} and
 * {@code y} really swaps them. Rebuilt nodes go back through
 * {@link Aig#and(int, int)}, so the result stays structurally hashed and
 * constants fold away.
 *
 * Work is proportional to the cones it is applied to, not to the graph: a node
 * is visited once per substitution, memoised, and a node whose cone reaches no
 * replaced input maps to itself. The memo is paged, so a substitution applied
 * only to small cones costs only those cones' pages.
 *
 * Values may be supplied after construction, as long as every input a cone
 * reaches has its value before the cone is applied. The set of replaced inputs
 * is fixed up front, which is what keeps the memo valid as values arrive.
 */
public class AigSubstitution {

    private static final int PAGE_BITS = 12;

    private static final int PAGE_SIZE = 1 << PAGE_BITS;

    private static final int UNSET = -1;

    private final Aig aig;

    /** Replacement literal per input index, {@link #UNSET} if not yet given. */
    private int[] valueByPi;

    /** True for the inputs this substitution replaces. */
    private boolean[] replaced;

    /** Rebuilt positive literal per node, plus one; zero means not visited. */
    private int[][] memo = new int[16][];

    private int[] stack = new int[256];

    /**
     * @param aig            The graph to rewrite.
     * @param replacedInputs The dense indices of the inputs to replace.
     */
    public AigSubstitution(Aig aig, int[] replacedInputs) {
        this.aig = aig;
        int count = aig.getPiCount();
        valueByPi = new int[count];
        replaced = new boolean[count];
        Arrays.fill(valueByPi, UNSET);
        for (int pi : replacedInputs) {
            replaced[pi] = true;
        }
    }

    /** Sets the literal that replaces one of the inputs given at construction. */
    public void setValue(int piIndex, int lit) {
        if (piIndex >= replaced.length || !replaced[piIndex]) {
            throw new IllegalArgumentException("Input " + aig.getPiName(piIndex)
                    + " was not declared as replaced");
        }
        valueByPi[piIndex] = lit;
    }

    /** @return True if this substitution replaces the input. */
    public boolean replaces(int piIndex) {
        return piIndex < replaced.length && replaced[piIndex];
    }

    /** @return The literal with every replaced input substituted. */
    public int apply(int lit) {
        int node = Aig.nodeOf(lit);
        int result = rebuild(node);
        return Aig.isComplement(lit) ? Aig.not(result) : result;
    }

    /** @return The positive literal a node rebuilds to. */
    private int rebuild(int root) {
        int known = memoOf(root);
        if (known != UNSET) {
            return known;
        }
        int top = 0;
        stack[top++] = root;
        while (top > 0) {
            int node = stack[top - 1];
            if (memoOf(node) != UNSET) {
                top--;
                continue;
            }
            if (node == 0) {
                setMemo(node, Aig.FALSE);
                top--;
                continue;
            }
            int pi = aig.getPiIndex(node);
            if (pi >= 0) {
                setMemo(node, valueOfInput(pi, node));
                top--;
                continue;
            }
            int f0 = aig.getFanin0(node);
            int f1 = aig.getFanin1(node);
            int r0 = memoOf(Aig.nodeOf(f0));
            int r1 = memoOf(Aig.nodeOf(f1));
            if (r0 == UNSET || r1 == UNSET) {
                if (top + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                if (r0 == UNSET) {
                    stack[top++] = Aig.nodeOf(f0);
                }
                if (r1 == UNSET) {
                    stack[top++] = Aig.nodeOf(f1);
                }
                continue;
            }
            int n0 = Aig.isComplement(f0) ? Aig.not(r0) : r0;
            int n1 = Aig.isComplement(f1) ? Aig.not(r1) : r1;
            boolean unchanged = n0 == f0 && n1 == f1;
            setMemo(node, unchanged ? Aig.litOf(node) : aig.and(n0, n1));
            top--;
        }
        return memoOf(root);
    }

    private int valueOfInput(int pi, int node) {
        if (!replaces(pi)) {
            return Aig.litOf(node);
        }
        if (valueByPi[pi] == UNSET) {
            throw new IllegalStateException("No value yet for replaced input "
                    + aig.getPiName(pi));
        }
        return valueByPi[pi];
    }

    private int memoOf(int node) {
        int page = node >>> PAGE_BITS;
        if (page >= memo.length || memo[page] == null) {
            return UNSET;
        }
        return memo[page][node & (PAGE_SIZE - 1)] - 1;
    }

    private void setMemo(int node, int lit) {
        int page = node >>> PAGE_BITS;
        if (page >= memo.length) {
            memo = Arrays.copyOf(memo, Math.max(memo.length * 2, page + 1));
        }
        if (memo[page] == null) {
            memo[page] = new int[PAGE_SIZE];
        }
        memo[page][node & (PAGE_SIZE - 1)] = lit + 1;
    }
}
