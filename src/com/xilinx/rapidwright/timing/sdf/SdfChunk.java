/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing.sdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One parallel worker's share of a parsed SDF file, before the shares are stitched together.
 *
 * A chunk boundary always falls on a construct boundary, but not necessarily on a cell boundary:
 * the top-level cell holds every {@code INTERCONNECT} in the design and is usually most of the
 * file, so it is deliberately split across workers. The worker that starts the top cell records it
 * as {@link #getPartialCell()}; every later worker records only its run of entries as
 * {@link #getFragmentEntries()}. {@link ParallelSdfParser} concatenates them in chunk order, which
 * restores the original entry order without needing to sort.
 */
class SdfChunk {

    private final long startByteOffset;

    private final List<SdfCell> cells = new ArrayList<>();

    private SdfFile header;

    private SdfCell partialCell;

    private List<SdfDelayEntry> fragmentEntries = Collections.emptyList();

    private boolean sawDelayFileClose;

    /**
     * Byte offset one past the end of the cell this chunk finished, when it closed one that an
     * earlier chunk had started. Carried so that the reassembled cell reports where it really
     * ended rather than where the chunk that began it happened to stop.
     */
    private long completedCellEndByteOffset = -1;

    /**
     * @param startByteOffset Byte offset at which this chunk begins.
     */
    public SdfChunk(long startByteOffset) {
        this.startByteOffset = startByteOffset;
    }

    /**
     * @return Byte offset at which this chunk begins.
     */
    public long getStartByteOffset() {
        return startByteOffset;
    }

    /**
     * @return The cells fully contained in this chunk, in file order.
     */
    public List<SdfCell> getCells() {
        return cells;
    }

    /**
     * @param cell A fully parsed cell to append.
     */
    public void addCell(SdfCell cell) {
        cells.add(cell);
    }

    /**
     * @return The file header, present only on the first chunk, which is the one that owns it.
     */
    public SdfFile getHeader() {
        return header;
    }

    /**
     * @param header The parsed file header.
     */
    public void setHeader(SdfFile header) {
        this.header = header;
    }

    /**
     * @return A cell that starts in this chunk but whose delay entries continue into the next, or
     *         null if this chunk contains no such cell.
     */
    public SdfCell getPartialCell() {
        return partialCell;
    }

    /**
     * @param partialCell A cell whose delay entries are continued by a later chunk.
     */
    public void setPartialCell(SdfCell partialCell) {
        this.partialCell = partialCell;
    }

    /**
     * @return Delay entries belonging to a cell that started in an earlier chunk, in file order.
     */
    public List<SdfDelayEntry> getFragmentEntries() {
        return fragmentEntries;
    }

    /**
     * @param fragmentEntries Delay entries continuing a cell from an earlier chunk.
     */
    public void setFragmentEntries(List<SdfDelayEntry> fragmentEntries) {
        this.fragmentEntries = fragmentEntries;
    }

    /**
     * @return True if this chunk consumed the closing parenthesis of {@code DELAYFILE}, which
     *         exactly one chunk must do.
     */
    public boolean sawDelayFileClose() {
        return sawDelayFileClose;
    }

    /**
     * @param sawDelayFileClose Whether this chunk closed the file.
     */
    public void setSawDelayFileClose(boolean sawDelayFileClose) {
        this.sawDelayFileClose = sawDelayFileClose;
    }

    /**
     * @return Byte offset one past the end of the cell this chunk closed, or -1 if it closed none.
     */
    public long getCompletedCellEndByteOffset() {
        return completedCellEndByteOffset;
    }

    /**
     * @param offset Byte offset one past the end of the cell this chunk closed.
     */
    public void setCompletedCellEndByteOffset(long offset) {
        this.completedCellEndByteOffset = offset;
    }

    @Override
    public String toString() {
        return "SdfChunk[@" + startByteOffset + ", " + cells.size() + " cells"
                + (partialCell != null ? ", partial cell" : "")
                + (fragmentEntries.isEmpty() ? "" : ", " + fragmentEntries.size() + " fragment")
                + (sawDelayFileClose ? ", closes file" : "") + "]";
    }
}
