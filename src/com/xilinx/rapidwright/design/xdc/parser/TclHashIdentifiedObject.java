/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc.parser;

import java.util.function.Consumer;

import tcl.lang.Interp;
import tcl.lang.ReflectObject;
import tcl.lang.TclException;
import tcl.lang.TclObject;

/**
 * Helper methods for registering java objects via their identity hash code and looking them up.
 */
public class TclHashIdentifiedObject {
    private static final String NAME_START = "+%%javahashstart%%+";
    private static final String NAME_END = "+%%javahashend%%+";

    public static <T> TclObject createReflectObject(Interp interp, Class<?> clazz, Object obj) throws TclException {
        TclObject tclObject = ReflectObject.newInstance(interp, clazz, obj, NAME_START + ReflectObject.getHashString(clazz, obj) + NAME_END);
        //Normally, Tcl objects are removed from internal table once no variable refers to them.
        //We want to keep them around till the end of life of the interpreter (since a stringified reference may still exist)
        //Therefore, increase reference count by 1
        tclObject.preserve();
        return tclObject;
    }

    public static void unpack(Interp interp, String s, Consumer<String> outputString, Consumer<Object> outputObject) {
        int offset = 0;
        while (offset < s.length()) {
            int objStart = s.indexOf(NAME_START, offset);
            if (objStart==-1) {
                outputString.accept(s.substring(offset, s.length()));
                break;
            }
            if (objStart>offset) {
                outputString.accept(s.substring(offset, objStart));
            }
            int objEnd = s.indexOf(NAME_END, objStart);

            String objName = s.substring(objStart+NAME_START.length(), objEnd);
            Object obj = ReflectObject.findObjectByHash(interp, objName);
            if (obj==null) {
                throw new RuntimeException("Did not find hash identified object "+objName+". Was this mistakenly freed?");
            }
            outputObject.accept(obj);

            offset = objEnd+NAME_END.length();
        }
    }

    public static boolean containsStringifiedObject(String s) {
        return s.contains(NAME_START);
    }

    public static <T> String unpackAsString(Interp interp, String s, EdifCellLookup<T> lookup) {
        StringBuilder sb = new StringBuilder();
        unpack(interp, s, sb::append, obj -> {
            T t = lookup.castCellInst(obj);
            sb.append(lookup.getAbsoluteOriginalName(t));
        });
        return sb.toString();
    }
}
