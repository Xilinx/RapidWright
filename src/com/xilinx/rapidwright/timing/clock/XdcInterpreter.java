/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * A minimal Tcl interpreter for the XDC timing constraints of {@link TimingConstraints}: it splits a
 * script into commands and words (braces, quotes, backslashes, comments, nested command
 * substitutions in brackets, simple variables), evaluates the object queries (get_cells, get_pins,
 * get_ports, get_nets, get_clocks, all_clocks, all_fanin, current_design, current_instance, set) itself and
 * hands every other top-level command to a {@link Handler}.
 * <p>
 * A value is either a String or a list of objects (the result of an object query, or of a {@code list}
 * of them). Options of commands may be abbreviated to any unique prefix, as in Vivado. A command whose
 * evaluation fails (an undefined variable, a nested command that is not supported, an expr or an
 * object query that cannot be evaluated) is not applied, as Tcl stops at the error.
 * <p>
 * It does not use the Jacl-based reader of {@code com.xilinx.rapidwright.design.xdc.XDCParser}, as
 * query results are typed object lists passed between commands, each command is evaluated (and
 * dropped on an error) on its own, and the object queries of commands that are not timing constraints
 * are skipped without being evaluated.
 */
final class XdcInterpreter {

    /** Handles the top-level commands that are not object queries */
    interface Handler {
        /**
         * @param name The command name.
         * @param args Its evaluated arguments.
         * @param index Index of the command in the script(s), counting from 0.
         */
        void handle(String name, List<Object> args, int index);
    }

    private final XdcObjectFinder finder;
    private final Handler handler;
    private final Map<String, Integer> warnings;
    private final Map<String, Object> variables = new HashMap<>();
    private String currentInstance = "";
    private int commandIndex;
    /**
     * The top-level commands whose arguments are evaluated, or null for all. A command for which it is false is
     * handed to the handler without arguments, and the object queries in its arguments are not run (unless they
     * contain a set or current_instance command).
     */
    private Predicate<String> evaluated;

    XdcInterpreter(XdcObjectFinder finder, Handler handler, Map<String, Integer> warnings) {
        this.finder = finder;
        this.handler = handler;
        this.warnings = warnings;
    }

    int getCommandIndex() {
        return commandIndex;
    }

    /** @return The current instance ("" for the top). */
    String getCurrentInstance() {
        return currentInstance;
    }

    /**
     * Sets the top-level commands whose arguments are evaluated. The others (e.g. pblock or waiver commands, whose
     * object queries can take most of the time of reading a large design's constraints) are handed to the handler
     * without arguments. The predicate is asked about the command name, and for set_property about
     * "set_property &lt;property name&gt;" as well when the property name is given as plain text.
     * @param evaluated The predicate, or null to evaluate the arguments of all commands.
     */
    void setEvaluatedCommands(Predicate<String> evaluated) {
        this.evaluated = evaluated;
    }

    /**
     * A set or current_instance command, which change the interpreter's state, or a command whose name is a
     * substitution (which may be one of them), in the text of a command substitution
     */
    private static final Pattern STATE_COMMAND = Pattern.compile(
            "(?<![\\w$-])(set|current_instance)(?![\\w])|(?:^|[\\[;\\n])\\s*\"?[$\\[]");

    /** A command substitution that is a bus index (an integer): kept as text in an unquoted name (UG894) */
    private static final Pattern BUS_INDEX = Pattern.compile("\\d+");

    /**
     * @return Whether the command substitutions of a command's words may contain a set or current_instance command:
     * its arguments are then evaluated even if the command is ignored, so that later commands see their effect.
     */
    private static boolean hasStateCommand(List<List<Part>> words) {
        for (List<Part> w : words) {
            for (Part p : w) {
                if (p.type == Part.COMMAND && STATE_COMMAND.matcher(p.text).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return The property name of a set_property command given as plain text after its -quiet/-verbose options,
     * "-dict" for a set_property -dict, or null if it is not plain text.
     */
    private static String propertyName(List<List<Part>> words) {
        for (int i = 1; i < words.size(); i++) {
            String w = literal(words.get(i));
            if (w == null) {
                return null;
            }
            if (w.length() > 1 && w.startsWith("-")) {
                if ("-dict".startsWith(w)) {
                    return "-dict";
                }
                if ("-quiet".startsWith(w) || "-verbose".startsWith(w)) {
                    continue;
                }
                return null;
            }
            return w;
        }
        return null;
    }

    /** @return The text of a word that is plain text (no substitutions), or null. */
    private static String literal(List<Part> word) {
        return word.size() == 1 && word.get(0).type == Part.TEXT ? word.get(0).text : null;
    }

    void warn(String kind) {
        warnings.merge(kind, 1, Integer::sum);
        if (recording != null) {
            recording.add(kind);
        }
    }

    /** The warnings of the object query being evaluated, or null */
    private List<String> recording;

    /** A cached object query result (a list of objects or an error) and the warnings its evaluation gave */
    private static final class Cached {
        final Object result;
        final List<String> warnings;

        Cached(Object result, List<String> warnings) {
            this.result = result;
            this.warnings = warnings;
        }
    }

    /**
     * Results of the object queries whose arguments are all text, by command, current instance and arguments: the
     * same query gives the same objects (the netlist does not change while constraints are read), so a repeated one
     * returns a copy of the first result and repeats its warnings.
     */
    private final Map<List<Object>, Cached> queryCache = new HashMap<>();

    private Object cachedQuery(String name, List<Object> args,
                               java.util.function.Function<List<Object>, Object> query) {
        for (Object a : args) {
            if (!(a instanceof String)) {
                return query.apply(args);
            }
        }
        List<Object> key = new ArrayList<>(args.size() + 2);
        key.add(name);
        key.add(currentInstance);
        key.addAll(args);
        Cached c = queryCache.get(key);
        if (c == null) {
            List<String> saved = recording;
            recording = new ArrayList<>();
            Object r;
            List<String> w;
            try {
                r = query.apply(args);
            } finally {
                w = recording;
                recording = saved;
                if (saved != null) {
                    saved.addAll(w);
                }
            }
            queryCache.put(key, new Cached(copy(r), w));
            return r;
        }
        for (String w : c.warnings) {
            warn(w);
        }
        return copy(c.result);
    }

    /** A copy of an object list (a query result the caller may change), or the value itself */
    private static Object copy(Object v) {
        return v instanceof List ? new ArrayList<>((List<?>) v) : v;
    }

    // ------------------------------------------------------------------------------------------
    // Lexing
    // ------------------------------------------------------------------------------------------

    /** A part of a word: literal text, a command substitution or a variable */
    private static final class Part {
        static final int TEXT = 0;
        static final int COMMAND = 1;
        static final int VARIABLE = 2;
        final int type;
        final String text;

        Part(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    /** A cursor over a script */
    private static final class Lexer {
        final String s;
        int pos;

        Lexer(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        /** Parses the next command's words; returns null at the end of the script. */
        List<List<Part>> nextCommand() {
            // Skip separators and comments
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\\' && pos + 1 < s.length() && s.charAt(pos + 1) == '\n') {
                    pos += 2;
                } else if (Character.isWhitespace(c) || c == ';') {
                    pos++;
                } else if (c == '#') {
                    while (pos < s.length() && s.charAt(pos) != '\n') {
                        if (s.charAt(pos) == '\\' && pos + 1 < s.length()) {
                            pos++;
                        }
                        pos++;
                    }
                } else {
                    break;
                }
            }
            if (pos >= s.length()) {
                return null;
            }
            List<List<Part>> words = new ArrayList<>();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\n' || c == ';') {
                    pos++;
                    break;
                }
                if (c == '\\' && pos + 1 < s.length() && s.charAt(pos + 1) == '\n') {
                    pos += 2;
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                words.add(nextWord());
            }
            return words;
        }

        List<Part> nextWord() {
            List<Part> parts = new ArrayList<>();
            char c = s.charAt(pos);
            if (c == '{') {
                parts.add(new Part(Part.TEXT, braced()));
                return parts;
            }
            boolean quoted = c == '"';
            if (quoted) {
                pos++;
            }
            StringBuilder text = new StringBuilder();
            while (pos < s.length()) {
                c = s.charAt(pos);
                if (quoted) {
                    if (c == '"') {
                        pos++;
                        break;
                    }
                } else if (Character.isWhitespace(c) || c == ';') {
                    break;
                }
                if (c == '[') {
                    if (text.length() > 0) {
                        parts.add(new Part(Part.TEXT, text.toString()));
                        text.setLength(0);
                    }
                    parts.add(new Part(Part.COMMAND, bracketed()));
                } else if (c == '$' && pos + 1 < s.length() && isVarChar(s.charAt(pos + 1), true)) {
                    if (text.length() > 0) {
                        parts.add(new Part(Part.TEXT, text.toString()));
                        text.setLength(0);
                    }
                    pos++;
                    String name;
                    if (s.charAt(pos) == '{') {
                        int end = s.indexOf('}', pos);
                        name = s.substring(pos + 1, end < 0 ? s.length() : end);
                        pos = end < 0 ? s.length() : end + 1;
                    } else {
                        int start = pos;
                        while (pos < s.length() && isVarChar(s.charAt(pos), false)) {
                            pos++;
                        }
                        name = s.substring(start, pos);
                    }
                    parts.add(new Part(Part.VARIABLE, name));
                } else if (c == '\\' && pos + 1 < s.length()) {
                    char n = s.charAt(pos + 1);
                    if (n == '\n') {
                        text.append(' ');
                    } else if (n == 'n') {
                        text.append('\n');
                    } else if (n == 't') {
                        text.append('\t');
                    } else {
                        text.append(n);
                    }
                    pos += 2;
                } else {
                    text.append(c);
                    pos++;
                }
            }
            if (text.length() > 0 || parts.isEmpty()) {
                parts.add(new Part(Part.TEXT, text.toString()));
            }
            return parts;
        }

        private static boolean isVarChar(char c, boolean first) {
            return Character.isLetterOrDigit(c) || c == '_' || c == ':' || (first && c == '{');
        }

        /** Reads a braced word, without the outer braces and without substitutions. */
        String braced() {
            int depth = 0;
            int start = pos + 1;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\\') {
                    pos += 2;
                    continue;
                }
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String w = s.substring(start, pos);
                        pos++;
                        return w.replace("\\\n", " ");
                    }
                }
                pos++;
            }
            return s.substring(Math.min(start, s.length()));
        }

        /** Reads a command substitution, without the outer brackets. */
        String bracketed() {
            int depth = 0;
            int start = pos + 1;
            boolean inBrace = false;
            int braceDepth = 0;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\\') {
                    pos += 2;
                    continue;
                }
                if (c == '{') {
                    braceDepth++;
                    inBrace = true;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                    inBrace = braceDepth > 0;
                } else if (!inBrace && c == '[') {
                    depth++;
                } else if (!inBrace && c == ']') {
                    depth--;
                    if (depth == 0) {
                        String w = s.substring(start, pos);
                        pos++;
                        return w;
                    }
                }
                pos++;
            }
            return s.substring(Math.min(start, s.length()));
        }
    }

    /**
     * Splits a string into the elements of a Tcl list (whitespace separated, braces grouping).
     */
    static List<String> splitList(String list) {
        List<String> out = new ArrayList<>();
        Lexer lx = new Lexer(list);
        while (lx.pos < list.length()) {
            char c = list.charAt(lx.pos);
            if (Character.isWhitespace(c)) {
                lx.pos++;
            } else if (c == '{') {
                out.add(lx.braced());
            } else {
                int start = lx.pos;
                while (lx.pos < list.length() && !Character.isWhitespace(list.charAt(lx.pos))) {
                    lx.pos++;
                }
                out.add(list.substring(start, lx.pos));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------------------------------

    /** Evaluates a script, handing its top-level commands to the handler. */
    void evalScript(String script) {
        Lexer lx = new Lexer(script);
        List<List<Part>> words;
        while ((words = lx.nextCommand()) != null) {
            if (words.isEmpty()) continue;
            try {
                evalCommand(words, true);
            } catch (RuntimeException e) {
                warn("error evaluating a command (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
            commandIndex++;
        }
    }

    /** Evaluates a nested script (a command substitution); returns the value of its last command. */
    private Object evalNested(String script) {
        Lexer lx = new Lexer(script);
        List<List<Part>> words;
        Object result = "";
        while ((words = lx.nextCommand()) != null) {
            if (words.isEmpty()) continue;
            result = evalCommand(words, false);
            if (result instanceof EvalError) {
                // Tcl stops at the error
                return result;
            }
        }
        return result;
    }

    private Object evalWord(List<Part> parts) {
        if (parts.size() == 1) {
            Part p = parts.get(0);
            switch (p.type) {
                case Part.COMMAND:
                    return evalNested(p.text);
                case Part.VARIABLE:
                    return getVariable(p.text);
                default:
                    return p.text;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            Object v = p.type == Part.COMMAND ? evalNested(p.text)
                    : (p.type == Part.VARIABLE ? getVariable(p.text) : p.text);
            if (v instanceof EvalError) {
                return v;
            }
            sb.append(toText(v));
        }
        return sb.toString();
    }

    private Object getVariable(String name) {
        Object v = variables.get(name);
        if (v == null) {
            warn("undefined Tcl variable " + name);
            return UNDEFINED_VARIABLE;
        }
        return v;
    }

    /** The string form of a value */
    static String toText(Object v) {
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) v) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(o instanceof XdcObject ? ((XdcObject) o).getName() : toText(o));
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    private Object evalCommand(List<List<Part>> words, boolean topLevel) {
        if (topLevel && evaluated != null) {
            String name = literal(words.get(0));
            if (name != null && !BUILTINS.contains(name)) {
                String property = name.equals("set_property") ? propertyName(words) : null;
                boolean skip = !evaluated.test(name)
                        || property != null && !evaluated.test("set_property " + property);
                if (skip && !hasStateCommand(words)) {
                    handler.handle(name, new ArrayList<>(), commandIndex);
                    return "";
                }
            }
        }
        List<Object> values = new ArrayList<>(words.size());
        for (List<Part> w : words) {
            Object v = evalWord(w);
            if (v instanceof EvalError) {
                // As in Tcl, the error ends the evaluation of the command (and of the commands it is nested in): the
                // top-level command is not applied
                if (!topLevel) {
                    return v;
                }
                String name = values.isEmpty() ? "a command" : toText(values.get(0));
                warn(name + " with " + ((EvalError) v).reason + " (ignored)");
                return "";
            }
            values.add(v);
        }
        String name = toText(values.get(0));
        List<Object> args = values.subList(1, values.size());
        switch (name) {
            case "get_cells":
                return cachedQuery(name, args, this::getCells);
            case "get_pins":
                return cachedQuery(name, args, this::getPins);
            case "get_ports":
                return cachedQuery(name, args, this::getPorts);
            case "get_nets":
                return cachedQuery(name, args, this::getNets);
            case "get_clocks":
                return getClocks(args);
            case "all_clocks":
                return Collections.singletonList(XdcObject.ofClockPattern("*", false, commandIndex));
            case "current_design":
                return "";
            case "current_instance":
                return currentInstance(args);
            case "set":
                if (args.size() >= 2) {
                    variables.put(toText(args.get(0)), args.get(1));
                    return args.get(1);
                } else if (args.size() == 1) {
                    return getVariable(toText(args.get(0)));
                }
                return "";
            case "list":
                return list(args);
            case "expr":
                return expr(args);
            case "all_fanin":
                return allFanin(args);
            default:
                if (topLevel) {
                    handler.handle(name, new ArrayList<>(args), commandIndex);
                    return "";
                }
                if (args.isEmpty() && (BUS_INDEX.matcher(name).matches() || name.equals("*"))) {
                    // A bus index in an unquoted object name (e.g. u0/g[1].b/O, u0/d_reg[*]): Vivado's Tcl keeps a
                    // bracketed integer or '*' in a name as text instead of calling a command of that name (UG894)
                    return "[" + name + "]";
                }
                warn("unsupported nested command " + name);
                return UNSUPPORTED_COMMAND;
        }
    }

    /**
     * all_fanin -flat -startpoints_only [-only_cells] sinks: the timing startpoints in the transitive fan-in of the
     * sink pins or nets, across the hierarchy (UG835). The fan-in is traced backward through the leaf cells of the
     * netlist: a combinational cell with one output is crossed through all its inputs; a sequential cell (a primitive
     * with a clock input, see {@link #isSequential}) is a startpoint, given as its clock input pins
     * ({@link #isClockInput}); a clock generator (MMCM, PLL) is a startpoint, given as its output pin; a top-level
     * input port is a startpoint; constants end a branch. With -only_cells the startpoint cells are returned (ports
     * are not). The other forms (without -flat or
     * -startpoints_only, -levels or -pin_levels other than 0, -trace_arcs, sinks other than pins and nets) and a
     * fan-in through a combinational cell with several outputs (whose outputs may depend on different inputs, e.g. a
     * carry chain) are not supported: the command using it is not applied.
     */
    private Object allFanin(List<Object> args) {
        Options o = parseOptions(args, new String[]{"-flat", "-startpoints_only", "-only_cells", "-quiet", "-verbose"},
                new String[]{"-levels", "-pin_levels", "-trace_arcs"});
        if (!o.unknown.isEmpty() || !o.has("-flat") || !o.has("-startpoints_only") || o.has("-trace_arcs")
                || !isZero(o.get("-levels")) || !isZero(o.get("-pin_levels"))) {
            warn("all_fanin without -flat and -startpoints_only, or with -levels, -pin_levels or -trace_arcs"
                    + " (not supported)");
            return UNSUPPORTED_COMMAND;
        }
        ArrayDeque<EDIFHierNet> work = new ArrayDeque<>();
        for (Object a : o.positional) {
            if (!(a instanceof List)) {
                if (toText(a).trim().isEmpty()) continue;
                warn("all_fanin of objects given by name (not supported)");
                return UNSUPPORTED_COMMAND;
            }
            for (Object e : (List<?>) a) {
                XdcObject x = e instanceof XdcObject ? (XdcObject) e : null;
                if (x != null && x.getKind() == XdcObject.Kind.PIN) {
                    EDIFHierNet net = x.getPin() == null ? null : x.getPin().getHierarchicalNet();
                    if (net != null) work.add(net);
                } else if (x != null && x.getKind() == XdcObject.Kind.NET && x.getNet() != null) {
                    work.add(x.getNet());
                } else {
                    warn("all_fanin of objects other than pins and nets (not supported)");
                    return UNSUPPORTED_COMMAND;
                }
            }
        }
        boolean onlyCells = o.has("-only_cells");
        Set<EDIFHierNet> seenNets = new HashSet<>();
        Set<EDIFHierCellInst> seenCells = new HashSet<>();
        Set<String> seenPorts = new HashSet<>();
        Set<EDIFHierPortInst> seenGeneratorPins = new HashSet<>();
        List<XdcObject> out = new ArrayList<>();
        while (!work.isEmpty()) {
            EDIFHierNet net = work.poll();
            if (!seenNets.add(net)) continue;
            List<EDIFHierPortInst> sources;
            try {
                // The leaf cell output pins and top-level input ports driving the net
                sources = net.getLeafHierPortInsts(true, false, true);
            } catch (RuntimeException e) {
                warn("all_fanin through a net with several drivers (not supported)");
                return UNSUPPORTED_COMMAND;
            }
            for (EDIFHierPortInst src : sources) {
                if (src.getPortInst().getCellInst() == null) {
                    if (!onlyCells && seenPorts.add(src.getPortInst().getName())) {
                        out.add(XdcObject.ofPort(src.getPortInst().getName()));
                    }
                    continue;
                }
                EDIFCell type = src.getCellType();
                if (type.getName().equals("GND") || type.getName().equals("VCC")) continue;
                EDIFHierCellInst drv = src.getFullHierarchicalInst();
                if (ClockNetwork.isGenerator(type.getName())) {
                    if (onlyCells ? seenCells.add(drv) : seenGeneratorPins.add(src)) {
                        out.add(onlyCells ? XdcObject.ofCell(drv) : XdcObject.ofPin(src));
                    }
                } else if (isSequential(type)) {
                    if (!seenCells.add(drv)) continue;
                    if (onlyCells) {
                        out.add(XdcObject.ofCell(drv));
                        continue;
                    }
                    for (EDIFPortInst pi : drv.getInst().getPortInsts()) {
                        if (isClockInput(pi.getPort())) {
                            out.add(XdcObject.ofPin(new EDIFHierPortInst(drv.getParent(), pi)));
                        }
                    }
                } else if (seenCells.add(drv)) {
                    if (numOutputBits(type) > 1) {
                        // Its outputs may depend on different inputs (e.g. the bits of a carry chain): crossing all
                        // its inputs could return startpoints that are not in the fan-in
                        warn("all_fanin through a combinational cell with several outputs (not supported)");
                        return UNSUPPORTED_COMMAND;
                    }
                    for (EDIFPortInst pi : drv.getInst().getPortInsts()) {
                        EDIFHierNet in = pi.isInput() ? new EDIFHierPortInst(drv.getParent(), pi).getHierarchicalNet()
                                : null;
                        if (in != null) work.add(in);
                    }
                }
            }
        }
        return out;
    }

    /** The number of output (and inout) bits of a cell type */
    private static int numOutputBits(EDIFCell type) {
        int n = 0;
        for (EDIFPort p : type.getPorts()) {
            if (!p.isInput()) n += p.getWidth();
        }
        return n;
    }

    /** Whether an option value is absent (null) or the number 0 */
    private static boolean isZero(Object v) {
        if (v == null) return true;
        String s = toText(v).trim();
        return isNumber(s) && Double.parseDouble(s) == 0;
    }

    /**
     * The list command: the text of a Tcl list of its arguments or, if an argument is a list of objects (e.g.
     * {@code [list [get_cells a] [get_cells b]]}), one flat list of the objects and the other arguments.
     */
    private static Object list(List<Object> args) {
        boolean objects = false;
        for (Object a : args) {
            objects |= a instanceof List;
        }
        if (!objects) {
            return args.size() == 1 ? args.get(0) : toText(new ArrayList<>(args));
        }
        List<Object> out = new ArrayList<>();
        for (Object a : args) {
            if (a instanceof List) {
                out.addAll((List<?>) a);
            } else {
                out.add(a);
            }
        }
        return out;
    }

    /** The commands evalCommand evaluates itself */
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("get_cells", "get_pins", "get_ports",
            "get_nets", "get_clocks", "all_clocks", "current_design", "current_instance", "set", "list", "expr"));

    /**
     * The value of a substitution that cannot be evaluated. Tcl stops at the error, so the top-level command using
     * it is not applied (evaluating it with an empty value instead could match all objects).
     */
    static final class EvalError {
        /** What could not be evaluated, for the warning */
        final String reason;

        private EvalError(String reason) {
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason;
        }
    }

    /** The value of an expr that cannot be evaluated */
    static final EvalError EXPR_ERROR = new EvalError("an expr that cannot be evaluated");
    /** The value of an undefined variable */
    private static final EvalError UNDEFINED_VARIABLE = new EvalError("an undefined Tcl variable");
    /** The value of a nested command that is not supported */
    private static final EvalError UNSUPPORTED_COMMAND = new EvalError("an unsupported nested command");

    /**
     * Evaluates an arithmetic expression (expr): numbers, + - * /, parentheses, unary minus and plus, and
     * $variable and [command] substitutions (also inside braces, as Tcl's expr does).
     * @return The value as text, or {@link #EXPR_ERROR} if it cannot be evaluated.
     */
    private Object expr(List<Object> args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(toText(a));
        }
        try {
            ExprParser ep = new ExprParser(sb.toString());
            double v = ep.parseExpression();
            ep.skipSpaces();
            if (!ep.atEnd() || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("invalid expression");
            }
            return Double.toString(v);
        } catch (RuntimeException e) {
            warn("expr that cannot be evaluated");
            return EXPR_ERROR;
        }
    }

    /** A recursive descent parser of the arithmetic subset of Tcl's expr */
    private final class ExprParser {
        private final String s;
        private int pos;

        ExprParser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        /** expression := term (('+' | '-') term)* */
        double parseExpression() {
            double v = parseTerm();
            while (true) {
                skipSpaces();
                if (atEnd()) return v;
                char c = s.charAt(pos);
                if (c == '+') {
                    pos++;
                    v += parseTerm();
                } else if (c == '-') {
                    pos++;
                    v -= parseTerm();
                } else {
                    return v;
                }
            }
        }

        /** term := factor (('*' | '/') factor)* */
        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipSpaces();
                if (atEnd()) return v;
                char c = s.charAt(pos);
                if (c == '*') {
                    pos++;
                    v *= parseFactor();
                } else if (c == '/') {
                    pos++;
                    double d = parseFactor();
                    if (d == 0) throw new IllegalArgumentException("division by zero");
                    v /= d;
                } else {
                    return v;
                }
            }
        }

        /** factor := ('-' | '+') factor | '(' expression ')' | number | $variable | [command] */
        private double parseFactor() {
            skipSpaces();
            if (atEnd()) throw new IllegalArgumentException("missing operand");
            char c = s.charAt(pos);
            if (c == '-' || c == '+') {
                pos++;
                double v = parseFactor();
                return c == '-' ? -v : v;
            }
            if (c == '(') {
                pos++;
                double v = parseExpression();
                skipSpaces();
                if (atEnd() || s.charAt(pos) != ')') throw new IllegalArgumentException("missing )");
                pos++;
                return v;
            }
            if (c == '$') {
                pos++;
                String name;
                if (pos < s.length() && s.charAt(pos) == '{') {
                    int end = s.indexOf('}', pos);
                    if (end < 0) throw new IllegalArgumentException("missing }");
                    name = s.substring(pos + 1, end);
                    pos = end + 1;
                } else {
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_'
                            || s.charAt(pos) == ':')) {
                        pos++;
                    }
                    name = s.substring(start, pos);
                }
                return valueOf(getVariable(name));
            }
            if (c == '[') {
                int depth = 0;
                int start = pos + 1;
                for (; pos < s.length(); pos++) {
                    char d = s.charAt(pos);
                    if (d == '[') {
                        depth++;
                    } else if (d == ']' && --depth == 0) {
                        break;
                    }
                }
                if (atEnd()) throw new IllegalArgumentException("missing ]");
                String cmd = s.substring(start, pos);
                pos++;
                return valueOf(evalNested(cmd));
            }
            int start = pos;
            while (pos < s.length()) {
                char d = s.charAt(pos);
                boolean exp = (d == '+' || d == '-') && pos > start
                        && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E');
                if (Character.isDigit(d) || d == '.' || d == 'e' || d == 'E' || exp) {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) throw new IllegalArgumentException("unexpected character " + c);
            return Double.parseDouble(s.substring(start, pos));
        }

        /** The value of a substitution, itself an expression (e.g. a variable holding "2.5") */
        private double valueOf(Object v) {
            if (v instanceof EvalError) throw new IllegalArgumentException("invalid operand");
            ExprParser p = new ExprParser(toText(v));
            double d = p.parseExpression();
            p.skipSpaces();
            if (!p.atEnd()) throw new IllegalArgumentException("invalid operand");
            return d;
        }
    }

    private Object currentInstance(List<Object> args) {
        String path = null;
        for (Object a : args) {
            String t = toText(a);
            if (t.startsWith("-")) continue;
            path = t;
        }
        if (path == null || path.isEmpty()) {
            currentInstance = "";
            return currentInstance;
        }
        // Relative to the current instance (UG835), each ".." going up a level. As in Vivado, an instance that does
        // not exist (also a name with a leading '/') leaves the current instance unchanged
        String cur = currentInstance;
        for (String level : path.split("/", -1)) {
            if (level.equals("..")) {
                int i = cur.lastIndexOf('/');
                cur = i < 0 ? "" : cur.substring(0, i);
            } else {
                cur = cur.isEmpty() && !level.isEmpty() ? level : cur + "/" + level;
            }
        }
        if (path.startsWith("/") || finder.getNetlist() != null && finder.getInstance(cur) == null) {
            warn("current_instance of a cell that is not found (unchanged)");
            return currentInstance;
        }
        currentInstance = cur;
        return currentInstance;
    }

    // ------------------------------------------------------------------------------------------
    // Option parsing
    // ------------------------------------------------------------------------------------------

    /** Parsed options and positional arguments of a command */
    static final class Options {
        final Map<String, Object> values = new LinkedHashMap<>();
        final List<Object> positional = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();
        /** For options given several times (e.g. -group), all their values in order */
        final List<Map.Entry<String, Object>> sequence = new ArrayList<>();

        boolean has(String option) {
            return values.containsKey(option);
        }

        Object get(String option) {
            return values.get(option);
        }
    }

    static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parses options.
     * @param args The arguments.
     * @param flags Options without a value.
     * @param valued Options with a value.
     */
    static Options parseOptions(List<Object> args, String[] flags, String[] valued) {
        Options o = new Options();
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof String && ((String) a).startsWith("-") && ((String) a).length() > 1
                    && !isNumber((String) a)) {
                String opt = (String) a;
                String flag = match(opt, flags);
                String val = match(opt, valued);
                if (val != null && (flag == null || val.equals(opt))) {
                    Object v = i + 1 < args.size() ? args.get(++i) : "";
                    o.values.put(val, v);
                    o.sequence.add(new java.util.AbstractMap.SimpleEntry<>(val, v));
                } else if (flag != null) {
                    o.values.put(flag, Boolean.TRUE);
                    o.sequence.add(new java.util.AbstractMap.SimpleEntry<>(flag, Boolean.TRUE));
                } else {
                    o.unknown.add(opt);
                }
            } else {
                o.positional.add(a);
            }
        }
        return o;
    }

    /** Finds the option that a (possibly abbreviated) option names: an exact or unique prefix match. */
    private static String match(String opt, String[] options) {
        String found = null;
        for (String s : options) {
            if (s.equals(opt)) {
                return s;
            }
            if (s.startsWith(opt)) {
                if (found != null) {
                    return null;
                }
                found = s;
            }
        }
        // A few common aliases
        if (found == null && opt.equals("-of")) {
            for (String s : options) {
                if (s.equals("-of_objects")) return s;
            }
        }
        return found;
    }

    /** The patterns of positional arguments, splitting Tcl lists. */
    private static List<String> patterns(List<Object> positional) {
        List<String> out = new ArrayList<>();
        for (Object p : positional) {
            if (p instanceof List) {
                for (Object o : (List<?>) p) {
                    out.add(o instanceof XdcObject ? ((XdcObject) o).getName() : String.valueOf(o));
                }
            } else {
                out.addAll(splitList(String.valueOf(p)));
            }
        }
        return out;
    }

    /** The objects of a value: an object list, else the empty list. */
    @SuppressWarnings("unchecked")
    static List<XdcObject> objects(Object v) {
        if (v instanceof List) {
            List<XdcObject> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (o instanceof XdcObject) out.add((XdcObject) o);
            }
            return out;
        }
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------------------------------
    // Object queries
    // ------------------------------------------------------------------------------------------

    /** The flags of all object queries */
    private static final String[] GETTER_FLAGS = {"-hierarchical", "-quiet", "-verbose", "-regexp", "-nocase",
        "-leaf", "-include_generated_clocks"};
    private static final String[] GETTER_VALUED = {"-filter", "-of_objects", "-match_style"};

    /**
     * The flags of a query: those of all queries and its own. The reader ignores get_cells and get_pins
     * -include_replicated_objects, get_nets -segments (the net of a pin or a name is the segment at its level) and
     * get_ports -prop_thru_buffers, which can only make Vivado's result larger.
     */
    private static String[] flags(String... own) {
        String[] f = Arrays.copyOf(GETTER_FLAGS, GETTER_FLAGS.length + own.length);
        System.arraycopy(own, 0, f, GETTER_FLAGS.length, own.length);
        return f;
    }

    private static final String[] CELL_FLAGS = flags("-include_replicated_objects");
    private static final String[] NET_FLAGS = flags("-segments");
    private static final String[] PORT_FLAGS = flags("-scoped_to_current_instance", "-no_traverse",
            "-prop_thru_buffers");

    /** The value of an object query that Vivado rejects */
    private static final EvalError INVALID_QUERY = new EvalError("an object query that is not valid");

    /**
     * Checks the queries that Vivado rejects (the command using the query is then not applied): an unknown or
     * ambiguous option, a pattern with -of_objects, -nocase without -regexp, a -filter expression that does not
     * parse.
     * @return The error value, or null if the query is valid.
     */
    private EvalError checkQuery(String cmd, Options o) {
        String error = !o.unknown.isEmpty() ? "with an unknown or ambiguous option " + o.unknown.get(0)
                : o.has("-of_objects") && !o.positional.isEmpty() ? "-of_objects with a pattern"
                : o.has("-nocase") && !o.has("-regexp") ? "-nocase without -regexp" : null;
        if (error == null && o.has("-filter")) {
            try {
                FilterNode.parse(toText(o.get("-filter")));
            } catch (IllegalArgumentException e) {
                error = "-filter with a syntax error";
            }
        }
        if (error == null) {
            return null;
        }
        warn(cmd + " " + error + " (not valid)");
        return INVALID_QUERY;
    }

    /**
     * The patterns of a query: "*" (or the given default) if no pattern argument is given; none if the arguments
     * hold no pattern (e.g. an empty list), as Vivado matches no object then.
     */
    private static List<String> queryPatterns(Options o, String all) {
        return o.positional.isEmpty() ? Collections.singletonList(all) : patterns(o.positional);
    }

    private Object getCells(List<Object> args) {
        Options o = parseOptions(args, CELL_FLAGS, GETTER_VALUED);
        EvalError invalid = checkQuery("get_cells", o);
        if (invalid != null) {
            return invalid;
        }
        boolean hier = o.has("-hierarchical");
        if (o.has("-of_objects")) {
            return applyFilter(o, finder.cellsOf(objects(o.get("-of_objects"))));
        }
        List<XdcObject> result = new ArrayList<>();
        List<String> pats = queryPatterns(o, "*");
        if (hier && !o.has("-regexp") && o.has("-filter") && finder.getNetlist() != null && pats.size() == 1
                && pats.get(0).equals("*")) {
            List<XdcObject> r = allCellsByNameFilter(o);
            if (r != null) {
                return r;
            }
        }
        for (String p : pats) {
            if (finder.getNetlist() == null) {
                result.add(XdcObject.ofCellName(prefixed(p)));
                continue;
            }
            if (o.has("-regexp")) {
                for (EDIFHierCellInst c : finder.findCellsRegexp(p, hier, o.has("-nocase"), currentInstance)) {
                    result.add(XdcObject.ofCell(c));
                }
            } else {
                result.addAll(finder.findCellObjects(p, hier, currentInstance));
            }
        }
        return applyFilter(o, result);
    }

    /**
     * get_cells -hierarchical -filter {... NAME =~ glob ...} (all cells, a filter that requires a full name glob):
     * the filter is evaluated on the cells whose full name matches the glob only, found in the name index, instead of
     * on every cell of the design. Same result as the plain evaluation.
     * @return The cells, or null if the filter requires no NAME =~ glob.
     */
    private List<XdcObject> allCellsByNameFilter(Options o) {
        FilterNode f;
        try {
            f = FilterNode.parse(toText(o.get("-filter")));
            checkProperties(f);
        } catch (IllegalArgumentException e) {
            warn("-filter " + e.getMessage() + " (empty result)");
            return new ArrayList<>();
        }
        List<String> globs = f.requiredNameGlobs();
        if (globs.isEmpty()) {
            return null;
        }
        // The glob with the longest literal prefix narrows the name range most
        String glob = globs.get(0);
        for (String g : globs) {
            if (XdcObjectFinder.literalPrefix(g).length() > XdcObjectFinder.literalPrefix(glob).length()) glob = g;
        }
        int[] all = finder.allCells(currentInstance);
        if (all.length > 0) {
            // As the plain evaluation, a filter that cannot be evaluated on the first cell gives an empty result
            try {
                eval(f, finder.cellObjects(new int[]{all[0]}).get(0));
            } catch (IllegalArgumentException e) {
                warn("-filter " + e.getMessage() + " (empty result)");
                return new ArrayList<>();
            }
        }
        return applyFilter(o, finder.cellObjects(finder.cellsByFullName(glob, currentInstance)));
    }

    private String prefixed(String name) {
        return currentInstance.isEmpty() ? name : currentInstance + "/" + name;
    }

    private Object getPins(List<Object> args) {
        Options o = parseOptions(args, CELL_FLAGS, GETTER_VALUED);
        EvalError invalid = checkQuery("get_pins", o);
        if (invalid != null) {
            return invalid;
        }
        boolean hier = o.has("-hierarchical");
        if (o.has("-of_objects")) {
            return applyFilter(o, finder.pinsOf(objects(o.get("-of_objects")), o.has("-leaf")));
        }
        if (o.positional.isEmpty() && o.has("-filter") && finder.getNetlist() != null) {
            // get_pins [-hierarchical] -filter {NAME =~ <glob>}: the pins whose full name matches (Vivado-written
            // XDC files rename derived clocks this way), without listing all pins first
            Matcher m = NAME_GLOB.matcher(toText(o.get("-filter")));
            if (m.matches()) {
                return finder.findPinsByFullName(m.group(1), hier, currentInstance);
            }
        }
        // Without a pattern (or with "*"), the pins of all cells below the current instance with -hierarchical, else
        // of its children. Pin objects given as arguments (e.g. the result of all_fanin) are those pins: their full
        // names are not patterns relative to the current instance.
        List<XdcObject> result = new ArrayList<>();
        List<Object> patternArgs = new ArrayList<>(o.positional.size());
        for (Object a : o.positional) {
            if (!(a instanceof List)) {
                patternArgs.add(a);
                continue;
            }
            List<Object> rest = new ArrayList<>();
            for (Object e : (List<?>) a) {
                if (e instanceof XdcObject && ((XdcObject) e).getKind() == XdcObject.Kind.PIN) {
                    result.add((XdcObject) e);
                } else {
                    rest.add(e);
                }
            }
            if (!rest.isEmpty()) patternArgs.add(rest);
        }
        for (String p : o.positional.isEmpty() ? Collections.singletonList("*") : patterns(patternArgs)) {
            if (!hier && p.equals("*")) {
                p = "*/*";
            }
            result.addAll(o.has("-regexp") && finder.getNetlist() != null
                    ? finder.findPinsRegexp(p, hier, o.has("-nocase"), currentInstance)
                    : finder.findPins(p, hier, currentInstance));
        }
        return applyFilter(o, result);
    }

    /** A -filter that is one NAME =~ glob comparison */
    private static final Pattern NAME_GLOB = Pattern.compile("\\s*NAME\\s*=~\\s*\"?([^\"\\s]+)\"?\\s*");

    private Object getPorts(List<Object> args) {
        Options o = parseOptions(args, PORT_FLAGS, GETTER_VALUED);
        EvalError invalid = checkQuery("get_ports", o);
        if (invalid != null) {
            return invalid;
        }
        List<XdcObject> result = new ArrayList<>();
        if (o.has("-of_objects")) {
            if (finder.getNetlist() == null) {
                warn("get_ports -of_objects without a netlist (empty result)");
                return result;
            }
            return applyFilter(o, finder.portsOf(objects(o.get("-of_objects"))));
        }
        boolean scoped = o.has("-scoped_to_current_instance") && !currentInstance.isEmpty()
                && finder.getNetlist() != null;
        for (String p : queryPatterns(o, "*")) {
            if (scoped) {
                // The pins of the current instance (as the ports of a design scoped to it), each traversed to the
                // top-level port it connects to unless -no_traverse
                result.addAll(finder.findScopedPorts(p, o.has("-regexp"), o.has("-nocase"), currentInstance,
                        !o.has("-no_traverse")));
                continue;
            }
            result.addAll(o.has("-regexp") && finder.getNetlist() != null
                    ? finder.findPortsRegexp(p, o.has("-nocase")) : finder.findPorts(p));
        }
        return applyFilter(o, result);
    }

    private Object getNets(List<Object> args) {
        Options o = parseOptions(args, NET_FLAGS, GETTER_VALUED);
        EvalError invalid = checkQuery("get_nets", o);
        if (invalid != null) {
            return invalid;
        }
        boolean hier = o.has("-hierarchical");
        if (o.has("-of_objects")) {
            return applyFilter(o, finder.netsOf(objects(o.get("-of_objects"))));
        }
        List<XdcObject> result = new ArrayList<>();
        for (String p : queryPatterns(o, "*")) {
            if (finder.getNetlist() == null) {
                result.add(XdcObject.ofNetName(prefixed(p)));
                continue;
            }
            result.addAll(o.has("-regexp") ? finder.findNetsRegexp(p, hier, o.has("-nocase"), currentInstance)
                    : finder.findNets(p, hier, currentInstance));
        }
        return applyFilter(o, result);
    }

    private Object getClocks(List<Object> args) {
        Options o = parseOptions(args, GETTER_FLAGS, GETTER_VALUED);
        EvalError invalid = checkQuery("get_clocks", o);
        if (invalid != null) {
            return invalid;
        }
        boolean gen = o.has("-include_generated_clocks");
        List<XdcObject> result = new ArrayList<>();
        if (o.has("-filter")) {
            warn("get_clocks -filter (empty result)");
            return result;
        }
        if (o.has("-of_objects")) {
            result.add(XdcObject.ofClocksOf(objects(o.get("-of_objects")), gen, commandIndex));
            return result;
        }
        for (String p : queryPatterns(o, "*")) {
            result.add(XdcObject.ofClockPattern(p, gen, commandIndex, o.has("-regexp"), o.has("-nocase")));
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // -filter
    // ------------------------------------------------------------------------------------------

    private List<XdcObject> applyFilter(Options o, List<XdcObject> objs) {
        if (!o.has("-filter")) {
            return objs;
        }
        unsetProperties.clear();
        try {
            FilterNode f = FilterNode.parse(toText(o.get("-filter")));
            checkProperties(f);
            return filter(f, objs);
        } catch (IllegalArgumentException e) {
            warn("-filter " + e.getMessage() + " (empty result)");
            return new ArrayList<>();
        } finally {
            for (String prop : unsetProperties) {
                warn("-filter property " + prop + " not in the netlist on some cells (taken as empty)");
            }
        }
    }

    /** The other cell properties a filter evaluation found unset on some cells (taken as empty there) */
    private final Set<String> unsetProperties = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The properties a filter can read that are not computed from the netlist (see {@link #property}) */
    private static final Set<String> COMPUTED_PROPERTIES = new HashSet<>(Arrays.asList("NAME", "REF_NAME",
            "REF_PIN_NAME", "DIRECTION", "IS_CONNECTED", "IS_CLOCK", "IS_SEQUENTIAL", "IS_PRIMITIVE", "IS_LEAF",
            "PARENT", "ORIG_REF_NAME", "PRIMITIVE_TYPE", "PRIMITIVE_GROUP", "PRIMITIVE_SUBGROUP"));

    /** Whether some cell instance of the netlist has a property, by property name */
    private final Map<String, Boolean> propertyInNetlist = new HashMap<>();

    /**
     * Checks that the other properties a filter reads are on some cell instance of the netlist. Any other property
     * is one Vivado computes (e.g. PRIMITIVE_LEVEL) or does not have: the filter cannot be evaluated (Vivado matches
     * no object for a property that does not exist, also with != and !~).
     */
    private void checkProperties(FilterNode f) {
        Set<String> props = new java.util.LinkedHashSet<>();
        f.properties(props);
        props.removeAll(COMPUTED_PROPERTIES);
        for (String prop : props) {
            if (finder.getNetlist() != null
                    && !propertyInNetlist.computeIfAbsent(prop, finder::hasCellProperty)) {
                throw new IllegalArgumentException("property " + prop + " not in the netlist");
            }
        }
    }

    private static final int FILTER_CHUNK = 1 << 13;

    /**
     * The objects a filter accepts (in parallel chunks for many objects). As a sequential evaluation, it throws the
     * error of the first object (in order) the filter cannot be evaluated on.
     */
    private List<XdcObject> filter(FilterNode f, List<XdcObject> objs) {
        int chunks = (objs.size() + FILTER_CHUNK - 1) / FILTER_CHUNK;
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<XdcObject>[] parts = new List[chunks];
        IllegalArgumentException[] errors = new IllegalArgumentException[chunks];
        java.util.stream.IntStream c = java.util.stream.IntStream.range(0, chunks);
        (chunks > 1 ? c.parallel() : c).forEach(k -> {
            List<XdcObject> l = new ArrayList<>();
            try {
                for (int i = k * FILTER_CHUNK, e = Math.min(objs.size(), i + FILTER_CHUNK); i < e; i++) {
                    if (eval(f, objs.get(i))) {
                        l.add(objs.get(i));
                    }
                }
            } catch (IllegalArgumentException ex) {
                errors[k] = ex;
            }
            parts[k] = l;
        });
        List<XdcObject> out = new ArrayList<>();
        for (int k = 0; k < chunks; k++) {
            if (errors[k] != null) {
                throw errors[k];
            }
            out.addAll(parts[k]);
        }
        return out;
    }

    /**
     * A parsed filter expression: comparisons (==, !=, =~, !~) of properties, boolean properties, !, &amp;&amp;, ||
     * and parentheses. Comparisons are case-sensitive, as in Vivado.
     */
    static final class FilterNode {
        /** '|', '&amp;', '!', 'c' (a comparison) or 'b' (a boolean property) */
        final char type;
        final FilterNode left;
        final FilterNode right;
        /** The property (upper case) of a comparison or boolean property */
        final String property;
        /** The operator of a comparison: "==", "!=", "=~" or "!~" */
        final String op;
        /** The value of a comparison */
        final String value;

        private FilterNode(char type, FilterNode left, FilterNode right, String property, String op, String value) {
            this.type = type;
            this.left = left;
            this.right = right;
            this.property = property;
            this.op = op;
            this.value = value;
        }

        /**
         * Parses a filter expression.
         * @throws IllegalArgumentException "syntax" if it is not a valid expression (as Vivado, which rejects it).
         */
        static FilterNode parse(String expr) {
            Parser p = new Parser(tokenize(expr));
            FilterNode f = p.parseOr();
            if (p.pos != p.tokens.size()) {
                throw new IllegalArgumentException("syntax");
            }
            return f;
        }

        /** Tokens: operators, parentheses, words, and quoted strings (marked by a leading NUL) */
        private static List<String> tokenize(String expr) {
            List<String> tokens = new ArrayList<>();
            int i = 0;
            while (i < expr.length()) {
                char c = expr.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(' || c == ')') {
                    tokens.add(String.valueOf(c));
                    i++;
                } else if (expr.startsWith("&&", i) || expr.startsWith("||", i) || expr.startsWith("==", i)
                        || expr.startsWith("!=", i) || expr.startsWith("=~", i) || expr.startsWith("!~", i)) {
                    tokens.add(expr.substring(i, i + 2));
                    i += 2;
                } else if (c == '!') {
                    tokens.add("!");
                    i++;
                } else if (c == '"') {
                    int end = expr.indexOf('"', i + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("syntax");
                    }
                    tokens.add("\u0000" + expr.substring(i + 1, end));
                    i = end + 1;
                } else {
                    int start = i;
                    while (i < expr.length() && !Character.isWhitespace(expr.charAt(i))
                            && "()!=&|\"".indexOf(expr.charAt(i)) < 0) {
                        i++;
                    }
                    if (i == start) {
                        throw new IllegalArgumentException("syntax");
                    }
                    tokens.add(expr.substring(start, i));
                }
            }
            return tokens;
        }

        private static boolean isOperator(String t) {
            return t.equals("&&") || t.equals("||") || t.equals("==") || t.equals("!=") || t.equals("=~")
                    || t.equals("!~") || t.equals("!") || t.equals("(") || t.equals(")");
        }

        /** A recursive descent parser: or := and ('||' and)*; and := unary ('&amp;&amp;' unary)* */
        private static final class Parser {
            final List<String> tokens;
            int pos;

            Parser(List<String> tokens) {
                this.tokens = tokens;
            }

            String peek() {
                return pos < tokens.size() ? tokens.get(pos) : null;
            }

            FilterNode parseOr() {
                FilterNode f = parseAnd();
                while ("||".equals(peek())) {
                    pos++;
                    f = new FilterNode('|', f, parseAnd(), null, null, null);
                }
                return f;
            }

            FilterNode parseAnd() {
                FilterNode f = parseUnary();
                while ("&&".equals(peek())) {
                    pos++;
                    f = new FilterNode('&', f, parseUnary(), null, null, null);
                }
                return f;
            }

            /** unary := '!' unary | '(' or ')' | property [op value] */
            FilterNode parseUnary() {
                String t = peek();
                if ("!".equals(t)) {
                    pos++;
                    return new FilterNode('!', parseUnary(), null, null, null, null);
                }
                if ("(".equals(t)) {
                    pos++;
                    FilterNode f = parseOr();
                    if (!")".equals(peek())) {
                        throw new IllegalArgumentException("syntax");
                    }
                    pos++;
                    return f;
                }
                if (t == null || isOperator(t)) {
                    throw new IllegalArgumentException("syntax");
                }
                pos++;
                String prop = (t.startsWith("\u0000") ? t.substring(1) : t).toUpperCase();
                String op = peek();
                if ("==".equals(op) || "!=".equals(op) || "=~".equals(op) || "!~".equals(op)) {
                    pos++;
                    String value = peek();
                    if (value == null || isOperator(value)) {
                        throw new IllegalArgumentException("syntax");
                    }
                    pos++;
                    if (value.startsWith("\u0000")) value = value.substring(1);
                    return new FilterNode('c', null, null, prop, op, value);
                }
                return new FilterNode('b', null, null, prop, null, null);
            }
        }

        /**
         * The globs of the NAME =~ comparisons that every object the filter accepts must match: those joined by
         * &amp;&amp; (not under ! or ||).
         */
        List<String> requiredNameGlobs() {
            List<String> globs = new ArrayList<>();
            addRequiredNameGlobs(globs);
            return globs;
        }

        private void addRequiredNameGlobs(List<String> globs) {
            if (type == '&') {
                left.addRequiredNameGlobs(globs);
                right.addRequiredNameGlobs(globs);
            } else if (type == 'c' && property.equals("NAME") && op.equals("=~")) {
                globs.add(value);
            }
        }

        /** Adds the properties the expression reads */
        void properties(Set<String> out) {
            if (property != null) out.add(property);
            if (left != null) left.properties(out);
            if (right != null) right.properties(out);
        }
    }

    /**
     * Evaluates a filter on an object. Both operands of &amp;&amp; and || are evaluated, so that a property that
     * cannot be evaluated on the object is an error whatever the other operand.
     * @throws IllegalArgumentException If a property cannot be evaluated on the object.
     */
    private boolean eval(FilterNode f, XdcObject o) {
        switch (f.type) {
            case '|': {
                boolean l = eval(f.left, o);
                boolean r = eval(f.right, o);
                return l || r;
            }
            case '&': {
                boolean l = eval(f.left, o);
                boolean r = eval(f.right, o);
                return l && r;
            }
            case '!':
                return !eval(f.left, o);
            case 'b':
                return toBoolean(property(f.property, o));
            default:
                String actual = property(f.property, o);
                boolean eq = compare(f.op, f.value, actual);
                if (!eq && f.op.charAt(0) != '!' && f.property.equals("REF_PIN_NAME") && o.getPin() != null) {
                    // A processing system pin named as in Vivado (see XdcObjectFinder.getPinAlias)
                    String alias = XdcObjectFinder.getPinAlias(o.getPin().getCellType(), f.value);
                    eq = alias != null && compare(f.op, alias, actual);
                }
                return f.op.charAt(0) == '!' ? !eq : eq;
        }
    }

    private boolean compare(String op, String value, String actual) {
        if (op.charAt(1) == '~') {
            return finder.globMatches(value, actual, true);
        }
        return value.equals(actual) || isBoolean(value) && isBoolean(actual) && toBoolean(value) == toBoolean(actual);
    }

    private static boolean isBoolean(String s) {
        return s.equals("1") || s.equals("0") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
    }

    private static boolean toBoolean(String s) {
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    /**
     * The value of a property of an object for a filter: NAME, REF_NAME, REF_PIN_NAME, DIRECTION (pins and ports),
     * IS_CONNECTED (pins), IS_CLOCK (pins), IS_SEQUENTIAL, IS_PRIMITIVE, IS_LEAF, PARENT, ORIG_REF_NAME,
     * PRIMITIVE_TYPE, PRIMITIVE_GROUP, PRIMITIVE_SUBGROUP, or another property of a cell instance in the netlist
     * (empty if unset).
     * @throws IllegalArgumentException If the object does not have the property.
     */
    private String property(String prop, XdcObject o) {
        switch (prop) {
            case "NAME":
                return o.getName();
            case "REF_NAME":
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    return o.getCell().getCellName();
                }
                break;
            case "REF_PIN_NAME":
                if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                    return o.getPin().getPortInst().getName();
                }
                break;
            case "DIRECTION":
                if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                    return o.getPin().isOutput() ? "OUT" : "IN";
                }
                if (o.getKind() == XdcObject.Kind.PORT && finder.getNetlist() != null) {
                    EDIFPort port = finder.getNetlist().getTopCell().getPortByPortInstName(o.getName());
                    if (port != null) {
                        return port.isInput() ? "IN" : port.isOutput() ? "OUT" : "INOUT";
                    }
                }
                break;
            case "IS_CONNECTED":
                // A pin is connected when a net is attached to it (Vivado: the pin has a net)
                if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                    return o.getPin().getNet() != null ? "1" : "0";
                }
                break;
            case "IS_CLOCK":
                // A clock input of a sequential cell (see isClockInput), such as the startpoints all_fanin
                // -startpoints_only returns for registers
                if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                    EDIFHierPortInst p = o.getPin();
                    return isSequential(p.getCellType()) && isClockInput(p.getPortInst().getPort()) ? "1" : "0";
                }
                break;
            case "IS_SEQUENTIAL":
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    return isSequential(o.getCell().getCellType()) ? "1" : "0";
                }
                break;
            case "IS_PRIMITIVE":
            case "IS_LEAF":
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    return o.getCell().getCellType().isLeafCellOrBlackBox() ? "1" : "0";
                }
                if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                    return o.getPin().getCellType().isLeafCellOrBlackBox() ? "1" : "0";
                }
                break;
            case "PARENT":
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    return o.getCell().getParent().getFullHierarchicalInstName();
                }
                break;
            case "ORIG_REF_NAME":
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    // As the netlist holds it on the instance or (as Vivado writes it) on the cell type, else
                    // the reference name
                    String v = instProperty(o.getCell(), prop);
                    if (v == null && o.getCell().getCellType() != null) {
                        com.xilinx.rapidwright.edif.EDIFPropertyValue t =
                                o.getCell().getCellType().getProperty(prop);
                        v = t == null ? null : t.getValue();
                    }
                    return v != null ? v : o.getCell().getCellName();
                }
                break;
            case "PRIMITIVE_TYPE":
            case "PRIMITIVE_GROUP":
            case "PRIMITIVE_SUBGROUP":
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    String[] t = primitiveType(o.getCell().getCellType());
                    if (t == null) return "";
                    return prop.equals("PRIMITIVE_GROUP") ? t[0] : prop.equals("PRIMITIVE_SUBGROUP") ? t[1]
                            : t[0] + "." + t[1] + "." + o.getCell().getCellName();
                }
                break;
            default:
                if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                    // Another property (on some cell of the netlist, see checkProperties): as the netlist holds
                    // it on the cell, else unset (empty)
                    String v = instProperty(o.getCell(), prop);
                    if (v != null) return v;
                    unsetProperties.add(prop);
                    return "";
                }
                break;
        }
        throw new IllegalArgumentException("property " + prop);
    }

    /** The value of a property of a cell instance in the netlist, or null */
    private static String instProperty(EDIFHierCellInst cell, String prop) {
        com.xilinx.rapidwright.edif.EDIFPropertyValue v = cell.getInst().getProperty(prop);
        return v == null ? null : v.getValue();
    }

    /**
     * Vivado's PRIMITIVE_GROUP and PRIMITIVE_SUBGROUP of a primitive (PRIMITIVE_TYPE is
     * group.subgroup.REF_NAME), from its type name; null for a hierarchical cell (whose PRIMITIVE_TYPE is empty).
     */
    static String[] primitiveType(EDIFCell type) {
        if (type == null || !type.isLeafCellOrBlackBox()) return null;
        return PRIMITIVE_TYPES.computeIfAbsent(type.getName(), XdcInterpreter::primitiveTypeOf);
    }

    private static final Map<String, String[]> PRIMITIVE_TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    private static String[] primitiveTypeOf(String n) {
        if (n.matches("FD[RSCP]E.*")) return new String[]{"REGISTER", "SDR"};
        if (n.matches("LD[CP]E(_1)?")) return new String[]{"REGISTER", "LATCH"};
        if (n.startsWith("IDDR") || n.startsWith("ODDR")) return new String[]{"REGISTER", "DDR"};
        if (n.matches("LUT[1-6](_2)?|LUT6CY")) return new String[]{"CLB", "LUT"};
        if (n.matches("RAM(D|S)(32|64E|64)(X2)?")) return new String[]{"CLB", "dram"};
        if (n.startsWith("RAM") && !n.startsWith("RAMB")) return new String[]{"CLB", "LUTRAM"};
        if (n.startsWith("ROM")) return new String[]{"CLB", "drom"};
        if (n.startsWith("SRL")) return new String[]{"CLB", "SRL"};
        if (n.startsWith("CARRY") || n.startsWith("LOOKAHEAD")) return new String[]{"CLB", "CARRY"};
        if (n.matches("MUXF[789]")) return new String[]{"CLB", "MUXFX"};
        if (n.startsWith("BUFG") || n.startsWith("MBUFG") || n.startsWith("BUFCE") || n.startsWith("BUFDIV")
                || n.equals("BUFR") || n.equals("BUFH")) return new String[]{"CLOCK", "BUFFER"};
        if (n.startsWith("MMCM") || n.startsWith("PLL") || n.startsWith("XPLL") || n.startsWith("DPLL"))
            return new String[]{"CLOCK", "PLL"};
        if (n.startsWith("GT") || n.startsWith("IBUFDS_GT") || n.startsWith("OBUFDS_GT"))
            return new String[]{"ADVANCED", "GT"};
        if (n.startsWith("RAMB") || n.startsWith("FIFO")) return new String[]{"BLOCKRAM", "BRAM"};
        if (n.startsWith("URAM")) return new String[]{"BLOCKRAM", "URAM"};
        if (n.startsWith("DSP")) return new String[]{"ARITHMETIC", "DSP"};
        if (n.startsWith("IBUF") || n.startsWith("IBUFDS")) return new String[]{"I/O", "INPUT_BUFFER"};
        if (n.startsWith("OBUF")) return new String[]{"I/O", "OUTPUT_BUFFER"};
        if (n.startsWith("IOBUF")) return new String[]{"I/O", "BIDIR_BUFFER"};
        return new String[]{"OTHERS", "others"};
    }

    /** Whether a cell type is sequential: a primitive with a clock input pin. */
    static boolean isSequential(EDIFCell type) {
        if (type == null || !type.isLeafCellOrBlackBox()) {
            return false;
        }
        for (EDIFPort p : type.getPorts()) {
            if (p.isInput() && ClockNetwork.isClockPinName(p.getBusName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a port of a primitive is a clock input, by its name (C, CB, or a name containing CLK): a scalar input,
     * as a bus with such a name is data (e.g. the C operand of a DSP).
     */
    static boolean isClockInput(EDIFPort p) {
        return p.isInput() && !p.isBus() && ClockNetwork.isClockPinName(p.getName());
    }
}
