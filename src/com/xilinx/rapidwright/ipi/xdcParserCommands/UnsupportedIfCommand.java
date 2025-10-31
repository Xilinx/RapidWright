/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.ipi.EdifCellLookup;
import tcl.lang.CharPointer;
import tcl.lang.Command;
import tcl.lang.ExprValue;
import tcl.lang.Interp;
import tcl.lang.Parser;
import tcl.lang.TCL;
import tcl.lang.TclDouble;
import tcl.lang.TclException;
import tcl.lang.TclInteger;
import tcl.lang.TclObject;
import tcl.lang.TclParse;
import tcl.lang.TclRuntimeError;
import tcl.lang.TclString;
import tcl.lang.TclToken;

public class UnsupportedIfCommand extends UnsupportedGetterCommand {
    public UnsupportedIfCommand(EdifCellLookup<?> lookup, Command replacedCommand) {
        super(lookup, replacedCommand);
    }

    private TclObject exprValueToObj(ExprValue exprValue) {
        switch (exprValue.getType()) {
            case 0:
                return TclInteger.newInstance(exprValue.getIntValue());
            case 1:
                return TclDouble.newInstance(exprValue.getDoubleValue());
            case 2:
                return TclString.newInstance("{"+exprValue.getStringValue()+"}");
            default:
                throw new RuntimeException("invalid expr type");
        }
    }

    //Copied from Parser.java
    static final int TCL_TOKEN_WORD = 1;
    static final int TCL_TOKEN_SIMPLE_WORD = 2;
    static final int TCL_TOKEN_TEXT = 4;
    static final int TCL_TOKEN_BS = 8;
    static final int TCL_TOKEN_COMMAND = 16;
    static final int TCL_TOKEN_VARIABLE = 32;
    static final int TCL_TOKEN_SUB_EXPR = 64;
    static final int TCL_TOKEN_OPERATOR = 128;

    String tokensToStr(TclParse tclParse, Interp interp) {
        try {
            StringBuilder res = new StringBuilder();
            boolean needsSpace = false;
            for (int i = 0; i < tclParse.numTokens; i++) {
                TclToken token = tclParse.tokenList[i];
                switch (token.type) {
                    case TCL_TOKEN_VARIABLE:

                        String varName = tclParse.tokenList[++i].getTokenString();
                        if (token.numComponents != 1) {
                            throw new RuntimeException("we don't currently support arrays here");
                        }
                        TclObject value = interp.getVar(varName, null, 0);
                        if (needsSpace) {
                            res.append(' ');
                        }
                        res.append(value.toString());
                        needsSpace = true;
                        break;
                    case TCL_TOKEN_SIMPLE_WORD:
                    case TCL_TOKEN_COMMAND:
                        if (needsSpace) {
                            res.append(' ');
                        }
                        res.append(token.getTokenString());
                        needsSpace = true;
                        break;
                    case TCL_TOKEN_TEXT:
                    case TCL_TOKEN_WORD:
                        //Ignore
                        break;
                    default:
                        throw new RuntimeException("unsupported token type: "+token.type);
                }
            }
            return res.toString();
        } catch (TclException e) {
            throw new RuntimeException(e);
        }
    }
    void makeUnsupportedResult(Interp interp, TclObject[] objv, int i) throws TclException {

        for (++i;i<objv.length;++i) {
            String obj = objv[i].toString();
            CharPointer script = new CharPointer(obj);
            TclParse tclParse = Parser.parseCommand(interp, script.array, script.index, script.length(), (String) null, 0, true);

            String s = tokensToStr(tclParse, interp);
            System.out.println("from "+obj+" to "+s);
            objv[i] = TclString.newInstance(s);
        }
        interp.setResult(UnsupportedCmdResult.makeTclObj(interp, objv, lookup, true, true));
    }

    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        //Code copied from IfCmd.java
        int i;
        boolean value;

        i = 1;
        while (true) {
            // At this point in the loop, objv and argc refer to an
            // expression to test, either for the main expression or
            // an expression following an "elseif".  The arguments
            // after the expression must be "then" (optional) and a
            // script to execute if the expression is true.

            if (i >= objv.length) {
                throw new TclException(interp,
                        "wrong # args: no expression after \"" +
                                objv[i - 1] + "\" argument");
            }
            try {
                ExprValue exprValue = interp.evalExpression(objv[i].toString());
                try {
                    value = exprValue.getBooleanValue(interp);
                } catch (TclException | TclRuntimeError e) {
                    //Not evaluatable, forward if to Vivado
                    objv[i] = exprValueToObj(exprValue);

                    makeUnsupportedResult(interp, objv, i);
                    return;
                } finally {
                    interp.releaseExprValue(exprValue);
                }
            } catch (TclException e) {
                switch (e.getCompletionCode()) {
                    case TCL.ERROR:
                        interp.addErrorInfo("\n    (\"if\" test expression)");
                        break;
                }
                throw e;
            }

            i++;
            if ((i < objv.length) && (objv[i].toString().equals("then"))) {
                i++;
            }
            if (i >= objv.length) {
                throw new TclException(interp,
                        "wrong # args: no script following \"" +
                                objv[i - 1] + "\" argument");
            }
            if (value) {
                try {
                    interp.eval(objv[i], 0);
                } catch (TclException e) {
                    switch (e.getCompletionCode()) {
                        case TCL.ERROR:
                            interp.addErrorInfo("\n    (\"if\" then script line " +
                                    interp.getErrorLine() + ")");
                            break;
                    }
                    throw e;
                }
                return;
            }

            // The expression evaluated to false.  Skip the command, then
            // see if there is an "else" or "elseif" clause.

            i++;
            if (i >= objv.length) {
                interp.resetResult();
                return;
            }
            if (objv[i].toString().equals("elseif")) {
                i++;
                continue;
            }
            break;
        }

        // Couldn't find a "then" or "elseif" clause to execute.
        // Check now for an "else" clause.  We know that there's at
        // least one more argument when we get here.

        if (objv[i].toString().equals("else")) {
            i++;
            if (i >= objv.length) {
                throw new TclException(interp,
                        "wrong # args: no script following \"else\" argument");
            } else if (i != (objv.length - 1)) {
                throw new TclException(interp,
                        "wrong # args: extra words after \"else\" clause in " +
                                "\"if\" command");
            }
        } else {
            // Not else, if there is more than 1 more argument
            // then generate an error.

            if (i != (objv.length - 1)) {
                throw new TclException(interp,
                        "wrong # args: extra words after \"else\" clause in \"if\" command");
            }
        }
        try {
            interp.eval(objv[i], 0);
        } catch (TclException e) {
            switch (e.getCompletionCode()) {
                case TCL.ERROR:
                    interp.addErrorInfo("\n    (\"if\" else script line " +
                            interp.getErrorLine() + ")");
                    break;
            }
            throw e;
        }
    }
}
