/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Generates mapping.txt containing a hierarchy aware 
 * mapping of instances to partitions post-MtKaHyPar partitioning.
 *
 * Example,
 * If fft_top/stage_16 is wholly contained into partition 1 'FPGA_B' 
 * then only one line should be written which references stage_16, "fft_top/stage_16 FPGA_B"
 * 
 * If stage_16 is not wholly contained we go one level deeper until wholly contained 
 * or we reach leaf instances.
 * "fft_top/stage_16/butterfly_0 FPGA_A"
 * "fft_top/stage_16/butterfly_1 FPGA_B"
 */
public final class HierMappingWriter {

    /**
     * Trie node representing a hierarchical instance path segment.
     *
     * - name: the segment at this level (for example, "stage_16").
     * - children: sub-segments under this node.
     * - leafCounts: one count per partition per explicit path)
     * - counts: aggregated counts over this node’s subtree (leafCounts + all children)
     *           lets us easily decide if wholly contained or not
     */
    private static final class Node {
        String name;
        Map<String, Node> children = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> leafCounts = new LinkedHashMap<>();
        String assignedPartition;
    }

    /**
     * Recursively assigns the majority partition to each node in the trie.
     * @param node The current node.
     */
    private static void assignPartitions(Node node) {
        for (Node child : node.children.values()) {
            assignPartitions(child);
        }
        node.assignedPartition = chooseHomePartition(node.counts);
    }

    /**
     * Emits mapping lines only when the partition assignment changes from the parent.
     * @param node The current node.
     * @param parentPartition The partition assigned to the parent node.
     * @param currentPath The current hierarchical path.
     * @param out The list to collect mapping lines.
     */
    private static void emitOptimizedMappings(Node node, String parentPartition, String currentPath, List<String> out) {
        // 1. Recurse to children first (Deepest-First)
        for (Node child : node.children.values()) {
            String childPath = currentPath.isEmpty() ? child.name : currentPath + "/" + child.name;
            emitOptimizedMappings(child, node.assignedPartition, childPath, out);
        }

        // 2. Check for difference
        boolean isRoot = parentPartition == null;
        boolean isDifferent = isRoot || (node.assignedPartition != null && !node.assignedPartition.equals(parentPartition));

        if (isDifferent && node.assignedPartition != null) {
            // Emit line
            if (!currentPath.isEmpty()) {
                if (!logicDiscoveryPolicy.pathHasExcludedSegment(currentPath)) {
                    out.add(currentPath + " " + node.assignedPartition);
                }
            }
        }
    }

    /**
     * Builds mapping lines from input partition sets (read from files in partitionDir) and
     * returns a list of "<path> <partition>" lines.
     *
     * @param partitionDir The directory containing partition files.
     * @param cellsFile The path to the cells.txt file.
     * @param defaultPartitionName The name of the default partition (optional).
     * @param optimize Whether to use the optimized differential mapping generation.
     * @return A list of mapping lines.
     */
    public static List<String> buildMappingFromPartitionFiles(String partitionDir
            , String cellsFile
            , String defaultPartitionName
            , boolean optimize) {
        List<String> partitions = readPartitionNames(Paths.get(cellsFile));
        Map<String, Set<String>> partToPaths = readPartitionInstanceSets(Paths.get(partitionDir),
                new LinkedHashSet<>(partitions));
        Node root = buildTrie(partToPaths);
        computeCounts(root);
        List<String> out = new ArrayList<>();
        if (optimize) {
            assignPartitions(root);
            emitOptimizedMappings(root, null, "", out);
        } else {
            selectAndEmitMappings(root, "", out, false);
        }

        if (defaultPartitionName != null && !partitions.contains(defaultPartitionName)) {
            PartitionTools.logPartitionerDebug(null,
                    "warning: default partition '%s' not found in cells.txt",
                    defaultPartitionName);
        }

        return out;
    }

    /**
     * Writes mapping lines to a file.
     *
     * Ensure the parent directories exist so the file emits cleanly even on fresh runs.
     *
     * @param lines The list of mapping lines to write.
     * @param outputPath The path to the output file.
     */
    public static void writeMappingFile(List<String> lines, String outputPath) {
        try {
            Path out = Paths.get(outputPath);
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { // more guards the better.
            throw new RuntimeException(e);
        }
    }

    /**
     * RapidWright partitioner api: writes mapping.txt in outDir.
     *
     * @param outDir The output directory.
     * @param netlist The EDIF netlist.
     * @param partitions A map of partition IDs to sets of instance names.
     * @param instLutCountMap A map of instance LUT counts (unused but kept for API consistency).
     */
    public static void write(Path outDir
            , EDIFNetlist netlist
            , Map<Integer, Set<String>> partitions
            , Map<EDIFHierCellInst, Integer> instLutCountMap
            , boolean optimize) {
        // ============================================================================
        // STEP 1
        // Ensure cells.txt has fpga_* names for all partitions based on indices;
        // will generate or extend labels when needed.
        // ============================================================================
        Path cellsFile = outDir.resolve("cells.txt");

        int maxIdx = -1;
        for (Integer idx : partitions.keySet()) {
            if (idx != null && idx > maxIdx) {
                maxIdx = idx;
            }
        }
        int numParts = Math.max(0, maxIdx + 1);

        List<String> names = readPartitionNames(cellsFile);
        if (names.size() < numParts) {
            List<String> gen = CellsWriter.generateNames(numParts);
            if (names.isEmpty()) {
                names = gen;
            } else {
                List<String> extended = new ArrayList<>(names);
                for (int i = names.size(); i < numParts; i++) {
                    extended.add(gen.get(i));
                }
                names = extended;
            }
            CellsWriter.write(outDir, names);
        }

        // ============================================================================
        // STEP 2
        // Build part-to-paths keyed by partition names (canonical labels); missing indices
        // get generated names so the map is complete.
        // ============================================================================
        Map<String, Set<String>> partToPaths = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : partitions.entrySet()) {
            int idx = entry.getKey();
            String name = (idx >= 0 && idx < names.size())
                    ? names.get(idx) : CellsWriter.generateName(idx);
            partToPaths.put(name, entry.getValue() != null
                    ? entry.getValue() : new LinkedHashSet<>());
        }

        // ============================================================================
        // STEP 3
        // Build the trie and compute per-subtree counts, then select lines using the
        // containment + home rules.
        // ============================================================================
        Node root = buildTrie(partToPaths);
        computeCounts(root);
        List<String> lines = new ArrayList<>();
        if (optimize) {
            assignPartitions(root);
            emitOptimizedMappings(root, null, "", lines);
        } else {
            selectAndEmitMappings(root, "", lines, false);
        }

        // ============================================================================
        // STEP 4
        // Prefix every path with the top cell name (prefer the top cell’s type name) and
        // append a single top 'home' line at the end for readability.
        // ============================================================================
        String topName = null;
        try {
            EDIFHierCellInst topHierCellInst = netlist.getTopHierCellInst();
            topName = (topHierCellInst != null) ? topHierCellInst.getCellName() : null;
            if (topName == null || topName.isEmpty()) {
                topName = (topHierCellInst != null) ? topHierCellInst.getInst().getName() : null;
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (topName == null || topName.isEmpty()) {
            topName = "top";
        }
        List<String> prefixed = new ArrayList<>(lines.size() + 1);
        for (String line : lines) {
            int spaceIndex = line.indexOf(' ');
            String path = spaceIndex >= 0 ? line.substring(0, spaceIndex) : line;
            String destination = spaceIndex >= 0 ? line.substring(spaceIndex + 1) : "";
            if (logicDiscoveryPolicy.pathHasExcludedSegment(path)) {
                continue;
            }
            String fullSourcePath = topName + "/" + path;
            prefixed.add(fullSourcePath + " " + destination);
        }
        String topHomePartition = optimize ? root.assignedPartition : chooseHomePartition(root.counts);
        if (topHomePartition != null) {
            prefixed.add(topName + " " + topHomePartition);
        }

        // ============================================================================
        // STEP 5
        // Sort and debug print the mapping order.
        // ============================================================================
        int showLimit = Math.min(5, prefixed.size());
        PartitionTools.logPartitionerDebug(null,
                "MAPPING_ORDER_DEBUG: pre-sort -> totalLines=%d topName=%s topHome=%s",
                prefixed.size(), topName, topHomePartition);
        for (int i = 0; i < showLimit; i++) {
            PartitionTools.logPartitionerDebug(null,
                    "MAPPING_ORDER_DEBUG: pre-sort[%d] depth=%d line=%s",
                    i, depthOfPath(prefixed.get(i)), prefixed.get(i));
        }
        if (prefixed.size() > 10) {
            PartitionTools.logPartitionerDebug(null,
                    "MAPPING_ORDER_DEBUG: pre-sort ... (middle lines omitted)");
            for (int i = prefixed.size() - showLimit; i < prefixed.size(); i++) {
                PartitionTools.logPartitionerDebug(null,
                        "MAPPING_ORDER_DEBUG: pre-sort[%d] depth=%d line=%s",
                        i, depthOfPath(prefixed.get(i)), prefixed.get(i));
            }
        }
        prefixed = sortByPathDepth(prefixed);
        PartitionTools.logPartitionerDebug(null,
                "MAPPING_ORDER_DEBUG: post-sort -> totalLines=%d", prefixed.size());
        for (int i = 0; i < showLimit; i++) {
            PartitionTools.logPartitionerDebug(null,
                    "MAPPING_ORDER_DEBUG: post-sort[%d] depth=%d line=%s",
                    i, depthOfPath(prefixed.get(i)), prefixed.get(i));
        }
        if (prefixed.size() > 10) {
            PartitionTools.logPartitionerDebug(null,
                    "MAPPING_ORDER_DEBUG: post-sort ... (middle lines omitted)");
            for (int i = prefixed.size() - showLimit; i < prefixed.size(); i++) {
                PartitionTools.logPartitionerDebug(null,
                        "MAPPING_ORDER_DEBUG: post-sort[%d] depth=%d line=%s",
                        i, depthOfPath(prefixed.get(i)), prefixed.get(i));
            }
        }
        java.util.Map<Integer, Integer> depthCounts = new java.util.HashMap<>();
        int maxDepth = 0;
        for (String line : prefixed) {
            int d = depthOfPath(line);
            depthCounts.put(d, depthCounts.getOrDefault(d, 0) + 1);
            if (d > maxDepth) {
                maxDepth = d;
            }
        }
        PartitionTools.logPartitionerDebug(null,
                "MAPPING_ORDER_DEBUG: depth-stats -> maxDepth=%d uniqueDepths=%d",
                maxDepth, depthCounts.size());
        for (int d = maxDepth; d >= 0; d--) {
            Integer count = depthCounts.get(d);
            if (count != null) {
                PartitionTools.logPartitionerDebug(null,
                        "MAPPING_ORDER_DEBUG: depth=%d count=%d", d, count);
            }
        }

        Path mappingFile = outDir.resolve("mapping.txt");
        writeMappingFile(prefixed, mappingFile.toString());
    }

    /**
     * Convenience main to emit mapping lines from a directory of partition files and a cells.txt.
     *
     * @param args Command line arguments: partitionDir, cells.txt, output_mapping.txt,
     *             [defaultPartitionName].
     */
    public static void main(String[] args) {
        if (args.length < 3 || args.length > 5) {
            PartitionTools.logPartitionerDebug(null,
                    "usage: <partitionDir> <cells.txt> <output_mapping.txt> " +
                            "[defaultPartitionName] [--optimized]");
            return;
        }
        String partitionDir = args[0];
        String cellsFile = args[1];
        String outputFile = args[2];
        String defaultPartitionName = (args.length >= 4 && !args[3].startsWith("--")) ? args[3] : null;
        boolean optimize = false;
        for (String arg : args) {
            if ("--optimized".equals(arg)) {
                optimize = true;
            }
        }

        List<String> mapping = buildMappingFromPartitionFiles(partitionDir,
                cellsFile,
                defaultPartitionName,
                optimize);
        writeMappingFile(mapping, outputFile);
    }

    /**
     * Reads partition labels from cells.txt and returns them in order.
     *
     * We ignore comments and blanks and keep order deterministic.
     *
     * @param cellsFile The path to the cells.txt file.
     * @return A list of partition names.
     */
    private static List<String> readPartitionNames(Path cellsFile) {
        List<String> partitions = new ArrayList<>();
        if (cellsFile != null && Files.isRegularFile(cellsFile)) {
            for (String line : FileTools.getLinesFromTextFile(cellsFile.toString())) {
                String trimmed = trimComment(line);
                if (trimmed.isEmpty()) {
                    continue;
                }
                partitions.add(trimmed);
            }
        }
        return partitions;
    }

    /**
     * Reads instance paths for each partition label from the given directory.
     *
     * @param partitionDir The directory containing partition files.
     * @param partitions A set of partition names to look for.
     * @return A map of partition names to sets of instance paths.
     */
    private static Map<String, Set<String>> readPartitionInstanceSets(Path partitionDir
            , Set<String> partitions) {
        Map<String, Set<String>> partToPaths = new LinkedHashMap<>();

        for (String p : partitions) {
            Set<String> paths = new LinkedHashSet<>();

            Path file1 = partitionDir.resolve(p);
            Path file2 = partitionDir.resolve(p + ".txt");

            boolean readOk = false;
            if (Files.isRegularFile(file1)) {
                readOk = true;
                for (String line : safeReadLines(file1)) {
                    String trimmedLine = trimComment(line);
                    if (trimmedLine.isEmpty()) {
                        continue;
                    }
                    paths.add(trimmedLine);
                }
            } else if (Files.isRegularFile(file2)) {
                readOk = true;
                for (String line : safeReadLines(file2)) {
                    String trimmedLine = trimComment(line);
                    if (trimmedLine.isEmpty()) {
                        continue;
                    }
                    paths.add(trimmedLine);
                }
            } else {
                Path matched = findFileCaseInsensitive(partitionDir, p);
                if (matched != null && Files.isRegularFile(matched)) {
                    readOk = true;
                    for (String line : safeReadLines(matched)) {
                        String trimmedLine = trimComment(line);
                        if (trimmedLine.isEmpty()) {
                            continue;
                        }
                        paths.add(trimmedLine);
                    }
                }
            }

            if (!readOk) {
                PartitionTools.logPartitionerDebug(null,
                        "info: no file found for partition '%s' in dir %s",
                        p, partitionDir);
            }

            partToPaths.put(p, paths);
        }

        return partToPaths;
    }

    /**
     * Safe read of file lines with utf-8.
     *
     * @param file The file to read.
     * @return A list of lines from the file.
     */
    private static List<String> safeReadLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Case-insensitive search for a file that matches either "<name>" or "<name>.txt"
     * in a directory.
     *
     * @param dir The directory to search in.
     * @param targetName The name to search for.
     * @return The path to the matching file, or null if not found.
     */
    private static Path findFileCaseInsensitive(Path dir, String targetName) {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dir)) {
            for (Path path : directoryStream) {
                String name = path.getFileName().toString();
                if (name.equalsIgnoreCase(targetName)
                        || name.equalsIgnoreCase(targetName + ".txt")) {
                    if (Files.isRegularFile(path)) {
                        return path;
                    }
                }
            }
        } catch (IOException e) {
            // ignore, something to fill in this spot? error print?
        }
        return null;
    }

    /**
     * Trims comments and whitespace; treats lines starting with '#' or '//' as comments.
     *
     * @param s The string to trim.
     * @return The trimmed string, or an empty string if it was a comment.
     */
    private static String trimComment(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("#")) {
            return "";
        }
        if (trimmed.startsWith("//")) {
            return "";
        }
        return trimmed;
    }

    /**
     * Builds a trie over all instance paths for all partitions.
     *
     * @param partToPaths A map of partition names to sets of instance paths.
     * @return The root node of the trie.
     */
    private static Node buildTrie(Map<String, Set<String>> partToPaths) {
        Node root = new Node();
        root.name = "";

        for (Map.Entry<String, Set<String>> entry : partToPaths.entrySet()) {
            String partition = entry.getKey();
            for (String path : entry.getValue()) {
                addPath(root, path, partition);
            }
        }
        return root;
    }

    /**
     * Inserts an instance path into the trie and records a leaf contribution at the terminal
     * node for the given partition.
     *
     * @param root The root node of the trie.
     * @param fullPath The full instance path.
     * @param partition The partition name.
     */
    private static void addPath(Node root, String fullPath, String partition) {
        List<String> segs = splitPath(fullPath);
        Node cur = root;
        for (String seg : segs) {
            Node nxt = cur.children.get(seg);
            if (nxt == null) {
                nxt = new Node();
                nxt.name = seg;
                cur.children.put(seg, nxt);
            }
            cur = nxt;
        }
        cur.leafCounts.put(partition, cur.leafCounts.getOrDefault(partition, 0) + 1);
    }

    /**
     * Splits a hierarchical path on '/' while leaving each segment intact.
     *
     * @param path The path to split.
     * @return A list of path segments.
     */
    private static List<String> splitPath(String path) {
        if (path == null || path.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = path.split("/");
        return Arrays.asList(parts);
    }

    /**
     * Computes per-subtree aggregated counts for every node in the trie.
     *
     * @param node The root node of the subtree.
     * @return A map of aggregated counts per partition.
     */
    private static Map<String, Integer> computeCounts(Node node) {
        Map<String, Integer> aggregatedCounts = new LinkedHashMap<>();
        mergeCounts(aggregatedCounts, node.leafCounts);
        for (Node child : node.children.values()) {
            Map<String, Integer> childCounts = computeCounts(child);
            mergeCounts(aggregatedCounts, childCounts);
        }
        node.counts = aggregatedCounts;
        return aggregatedCounts;
    }

    /**
     * Adds a delta to a counts map for a given partition key.
     *
     * @param map The map to update.
     * @param key The partition key.
     * @param delta The value to add.
     */
    private static void addCount(Map<String, Integer> map, String key, int delta) {
        if (key == null) {
            return;
        }
        map.put(key, map.getOrDefault(key, 0) + delta);
    }

    /**
     * Merges entries from mapB into mapA by adding counts per partition.
     *
     * @param mapA The destination map.
     * @param mapB The source map.
     */
    private static void mergeCounts(Map<String, Integer> mapA, Map<String, Integer> mapB) {
        for (Map.Entry<String, Integer> entry : mapB.entrySet()) {
            addCount(mapA, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Subtracts entries of mapB from mapA on a copy and prunes non-positive results.
     *
     * Useful to compute leftovers after child prints.
     *
     * @param mapA The map to subtract from.
     * @param mapB The map to subtract.
     * @return A new map containing the result.
     */
    private static Map<String, Integer> subtractCounts(Map<String, Integer> mapA
            , Map<String, Integer> mapB) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : mapA.entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : mapB.entrySet()) {
            out.put(entry.getKey(), out.getOrDefault(entry.getKey(), 0) - entry.getValue());
        }
        pruneNonPositive(out);
        return out;
    }

    /**
     * Removes entries from a counts map where the value is null, zero, or negative.
     *
     * @param map The map to prune.
     */
    private static void pruneNonPositive(Map<String, Integer> map) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            map.remove(key);
        }
    }

    /**
     * Returns true if the counts map has exactly one partition present (wholly contained).
     *
     * @param counts The counts map.
     * @return True if wholly contained, false otherwise.
     */
    private static boolean isWhollyContained(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return false;
        }
        return counts.size() == 1;
    }

    /**
     * Returns the sole partition label if counts are wholly contained; otherwise null.
     *
     * @param counts The counts map.
     * @return The partition label, or null.
     */
    private static String singlePartition(Map<String, Integer> counts) {
        if (!isWhollyContained(counts)) {
            return null;
        }
        for (String key : counts.keySet()) {
            return key;
        }
        return null;
    }

    /**
     * Picks the “home” partition for a node using
     * the largest count (ties broken lexicographically).
     *
     * TODO : May need revision based on requested changes to regroupinstances / mapping.txt
     * needing to change based on that. this part is likely to change...
     *
     * @param counts The counts map.
     * @return The home partition label, or null.
     */
    private static String chooseHomePartition(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        int max = -1;
        String best = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (value > max || (value == max
                    && (best == null || entry.getKey().compareTo(best) < 0))) {
                max = value;
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Selects mapping lines by walking the trie and emits them into 'out'.
     *
     * @param node The current trie node.
     * @param prefixPath The path prefix for this node.
     * @param out The list to collect mapping lines.
     * @param inSplit Whether we are currently in a split node.
     * @return A map of relocated counts.
     */
    private static Map<String, Integer> selectAndEmitMappings(Node node
            , String prefixPath
            , List<String> out
            , boolean inSplit) {
        String currentPath = prefixPath;
        if (node.name != null && !node.name.isEmpty()) {
            if (currentPath.isEmpty()) {
                currentPath = node.name;
            } else {
                currentPath = currentPath + "/" + node.name;
            }
        }

        if (isWhollyContained(node.counts) && !currentPath.isEmpty()) {
            String p = singlePartition(node.counts);
            if (!logicDiscoveryPolicy.pathHasExcludedSegment(currentPath)) {
                out.add(currentPath + " " + p);
            }
            return new LinkedHashMap<>(node.counts);
        }

        Map<String, Integer> relocatedFromChildren = new LinkedHashMap<>();
        for (Node child : node.children.values()) {
            Map<String, Integer> childRelocatedCounts = selectAndEmitMappings(child
                    , currentPath
                    , out
                    , true);
            mergeCounts(relocatedFromChildren, childRelocatedCounts);
        }

        Map<String, Integer> leftover = subtractCounts(node.counts, relocatedFromChildren);

        if (!currentPath.isEmpty()) {
            String home = chooseHomePartition(node.counts);
            if (home != null && !leftover.isEmpty()) {
                if (!logicDiscoveryPolicy.pathHasExcludedSegment(currentPath)) {
                    out.add(currentPath + " " + home);
                }
                return new LinkedHashMap<>(node.counts);
            }
        }

        return relocatedFromChildren;
    }

    /**
     * Returns lines sorted by path depth, then lexicographically. Useful when post-processing
     * is needed for readability.
     *
     * @param lines The list of lines to sort.
     * @return A new sorted list of lines.
     */
    public static List<String> sortByPathDepth(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        Collections.sort(out, (a, b) -> {
            int depthA = depthOfPath(a);
            int depthB = depthOfPath(b);
            if (depthA != depthB) {
                return Integer.compare(depthB, depthA);
            }
            return a.compareTo(b);
        });
        return out;
    }

    /**
     * Computes a simple depth metric for "<path> <partition>" lines by counting '/' in the path.
     *
     * @param mappingLine The mapping line string.
     * @return The depth of the path.
     */
    private static int depthOfPath(String mappingLine) {
        int spaceIndex = mappingLine.indexOf(' ');
        String path = spaceIndex >= 0 ? mappingLine.substring(0, spaceIndex) : mappingLine;
        if (path.isEmpty()) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth + 1;
    }
}
