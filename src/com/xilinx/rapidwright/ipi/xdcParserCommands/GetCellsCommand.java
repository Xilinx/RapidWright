/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel
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

package com.xilinx.rapidwright.ipi.xdcParserCommands;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.ipi.EdifCellLookup;
import com.xilinx.rapidwright.ipi.TclHashIdentifiedObject;
import com.xilinx.rapidwright.util.Pair;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclList;
import tcl.lang.TclObject;

public class GetCellsCommand<T> implements Command {

    private final EdifCellLookup<T> cellLookup;

    public GetCellsCommand(EdifCellLookup<T> cellLookup) {
        this.cellLookup = cellLookup;
    }

    @Override
    public void cmdProc(Interp interp, TclObject[] argv) throws TclException {
        boolean hierFlag = false;
        boolean regexpFlag = false;
        TclObject filter = null;
        String cellNameStr = null;

        for (int i = 1; i < argv.length; i++) {
            String item = argv[i].toString();
            switch (item) {
                case "-hier":
                    hierFlag = true;
                    break;
                case "-filter":
                    filter = argv[++i];
                    break;
                case "-regexp":
                    regexpFlag = true;
                    break;
                case "-quiet":
                    //Ignore
                    break;
                case "-of_objects":
                    interp.setResult(UnsupportedCmdResult.makeTclObj(interp, argv, cellLookup, false, false));
                    return;
                default:
                    if (cellNameStr != null) {
                        throw new RuntimeException("Duplicate cell-name string");
                    }
                    cellNameStr = TclHashIdentifiedObject.unpackAsString(interp, argv[i].toString(), cellLookup);
                    break;
            }

        }

        if (cellNameStr != null && !regexpFlag && filter == null && !hierFlag) {

            simpleGetCells(interp, cellNameStr);
        } else {
            complexGetCells(interp, hierFlag, regexpFlag, cellNameStr, filter, argv);
        }
    }

    /**
     * This only supports OR-in together different == clauses.
     *
     * @param expr
     * @return
     */
    private static Map<String, Set<String>> parseFilterExpression(String expr) {
        //Remove unneeded parens
        if (expr.matches("\\([^)]*\\)")) {
            expr = expr.substring(1, expr.length() - 1);
        }
        if (expr.contains("&&") || expr.contains("(")) {
            throw new RuntimeException("expression too complex: " + expr);
        }
        String[] split = expr.split("\\s*\\|\\|\\s*");

        Map<String, Set<String>> oredClauses = new HashMap<>();
        for (String s : split) {
            String[] clause = s.split("\\s*==\\s*");
            if (clause.length != 2) {
                throw new RuntimeException("unexpected clause " + s + " in " + expr);
            }
            oredClauses.computeIfAbsent(clause[0], x -> new HashSet<>()).add(clause[1]);
        }

        Set<String> refname = oredClauses.get("REF_NAME");
        Set<String> origrefname = oredClauses.get("ORIG_REF_NAME");
        if (!Objects.equals(refname, origrefname)) {
            throw new RuntimeException("expression too complex2: " + expr);
        }
        oredClauses.remove("ORIG_REF_NAME");

        return oredClauses;
    }

    private static int cellDebugCallCout = 0;

    private void complexGetCells(Interp interp, boolean hierFlag, boolean regexFlag, String cellNames, TclObject filterArg, TclObject[] argv) throws TclException {
        Stream<T> candidateSupplier = null;
        List<Pair<Predicate<T>, Function<T, String>>> filter = new ArrayList<>();

        if (!hierFlag) {
            throw new RuntimeException("no hier flag? not implemented");
        }


        if (filterArg != null) {
            Map<String, Set<String>> s = parseFilterExpression(filterArg.toString());

            if (s.size() > 1) {
                throw new RuntimeException("Filter too complex");
            }
            String entryType = s.keySet().iterator().next();
            Set<String> values = s.get(entryType);
            if (Objects.equals(entryType, "REF_NAME")) {
                filter.add(new Pair<>(cellLookup.getCellTypeFilter(values), e -> {
                    return "checked if " + cellLookup.getCellType(e) + " is in " + values;
                }));
            } else if (Objects.equals(entryType, "PARENT")) {
                List<T> roots = values.stream()
                        .map(r-> TclHashIdentifiedObject.unpackAsString(interp, r, cellLookup))
                        .flatMap(cellLookup::getHierCellInstsFromWildcardName)
                        .collect(Collectors.toList());
                candidateSupplier = roots.stream().flatMap(cellLookup::getChildrenOf);
                System.out.println("Supplying candidates from direct children of " + roots + ". raw roots: " + values);
            }
        }

        if (cellNames != null) {
            if (regexFlag) {
                filter.add(new Pair<>(cellLookup.getAbsoluteRegexFilter(cellNames), eci -> "Checking if " + eci + " matches regex " + cellNames));
            } else {
                filter.add(new Pair<>(cellLookup.getAbsoluteWildcardFilter(cellNames), eci -> "Checking if " + eci + " matches wildcard " + cellNames));
            }
        }

        if (candidateSupplier == null) {
            System.out.println("Supplying candidates from all cells");
            candidateSupplier = cellLookup.getAllCellInsts();
        }
        boolean debugFiltering = false;
        List<T> cells;
        if (!debugFiltering) {
            Stream<T> resultStream = candidateSupplier;
            for (Pair<Predicate<T>,?> filterStage : filter) {
                resultStream = resultStream.filter(filterStage.getFirst());
            }
            cells = resultStream.collect(Collectors.toList());
        } else{
            Path debugFile = Paths.get("./debugCellFiltering/"+(cellDebugCallCout++)+".txt");
            try {
                Files.createDirectories(debugFile.getParent());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Writing debug info to "+debugFile);
            try (PrintStream ps = new PrintStream(Files.newOutputStream(debugFile))) {
                ps.println("Debugging for "+ Arrays.toString(argv));

                ps.println("hierFlag = " + hierFlag);
                ps.println("regexFlag = " + regexFlag);
                ps.println("cellNames = " + cellNames);
                ps.println("filterArg = " + filterArg);

                cells = new ArrayList<>();
                Iterable<T> iterable = candidateSupplier::iterator;
                for (T eci : iterable) {
                    ps.println("checking eci: " + eci);
                    boolean in = true;
                    for (int i = 0; i < filter.size(); i++) {
                        Predicate<T> f = filter.get(i).getFirst();
                        boolean test = f.test(eci);
                        if (!test) {
                            ps.println("\tFiltered out by filter " + i + ": " + filter.get(i).getSecond().apply(eci));
                            in = false;
                            break;
                        } else {
                            ps.println("\taccepted by filter " + i + ": " + filter.get(i).getSecond().apply(eci));
                        }
                    }
                    if (in) {
                        ps.println("\tadding!");
                        cells.add(eci);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        TclObject list = TclList.newInstance();
        if (cells.isEmpty()) {
            System.out.println("did not find cell for call "+Arrays.toString(argv));
        } else {
            cells.stream().sorted(Comparator.comparing(cellLookup::getAbsoluteOriginalName))
                    .forEach(cell-> {
                        try {
                            TclList.append(interp, list, cellLookup.toReflectObj(interp, cell));
                        } catch (TclException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        interp.setResult(list);
    }


    private void simpleGetCells(Interp interp, String cellNameStr) throws TclException {
        String[] cellNames = cellNameStr.split(" ");
        TclObject list = TclList.newInstance();
        for (String cellName : cellNames) {
            List<T> cells = cellLookup.getHierCellInstsFromWildcardName(cellName).collect(Collectors.toList());
            if (cells.isEmpty()) {
                System.out.println("did not find cell for " + cellName);
            } else {
                cells.sort(Comparator.comparing(cellLookup::getAbsoluteOriginalName));
                for (T cell : cells) {
                    TclList.append(interp, list, cellLookup.toReflectObj(interp, cell));
                }
            }
        }
        interp.setResult(list);
    }
}
