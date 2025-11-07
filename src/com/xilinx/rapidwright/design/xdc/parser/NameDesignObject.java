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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.xdc.UnsupportedConstraintElement;

public class NameDesignObject<T> extends DesignObject<T> {
    private final ObjType type;
    private final List<String> objects;

    public NameDesignObject(ObjType type, List<String> objects) {
        this.type = type;
        this.objects = objects;
    }

    @Override
    public String toString() {
        return "DesignObjects{" +
                "type='" + type + '\'' +
                ", objects=" + objects +
                '}';
    }

    public String toXdc() {
        return UnsupportedConstraintElement.toXdc(toUnsupportedConstraintElement());
    }

    @Override
    public Stream<UnsupportedConstraintElement> toUnsupportedConstraintElement() {
        if (objects == null) {
            return Stream.of(
                    new UnsupportedConstraintElement.SyntaxConstraintElement("["),
                    new UnsupportedConstraintElement.NameConstraintElement(type.getXdcCommand()),
                    new UnsupportedConstraintElement.SyntaxConstraintElement("]")
                    );
        }
        boolean braces = objects.size()!=1 || objects.stream().anyMatch(o->o.contains("*") || o.contains("$"));

        List<UnsupportedConstraintElement> before = new ArrayList<>(Arrays.asList(
                new UnsupportedConstraintElement.SyntaxConstraintElement("["),
                new UnsupportedConstraintElement.NameConstraintElement(type.getXdcCommand()),
                new UnsupportedConstraintElement.SyntaxConstraintElement(" ")
        ));
        List<UnsupportedConstraintElement> after = new ArrayList<>(Collections.singleton(

                new UnsupportedConstraintElement.SyntaxConstraintElement("]")
        ));
        if (braces) {
            before.add(new UnsupportedConstraintElement.SyntaxConstraintElement("{"));
            after.add(0, new UnsupportedConstraintElement.SyntaxConstraintElement("}"));
        }

        Function<? super String, ? extends UnsupportedConstraintElement> uceConstructor = type == ObjType.Cell ? UnsupportedConstraintElement.CellConstraintElement::new : UnsupportedConstraintElement.NameConstraintElement::new;
        Stream<UnsupportedConstraintElement> objs = objects.stream().map(uceConstructor).flatMap(UnsupportedConstraintElement.addSpacesBetween());
        return UnsupportedConstraintElement.wrapStream(objs, before.stream(), after.stream());
    }

    public ObjType getType() {
        return type;
    }

    public List<String> getObjects() {
        return objects;
    }
}
