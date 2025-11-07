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
package com.xilinx.rapidwright.design.xdc;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

public class NicerStringifyList<T> implements List<T> {

    @Override
    public String toString() {
        return list.stream().map(Object::toString).map(s -> s.replaceAll("] ", "] \" + \n  \"")).collect(Collectors.joining("\",\n\"", "\"", "\""));
    }

    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public boolean contains(Object o) {
        return list.contains(o);
    }

    public Iterator<T> iterator() {
        return list.iterator();
    }

    public Object[] toArray() {
        return list.toArray();
    }

    public <T1> T1[] toArray(@NotNull T1[] a) {
        return list.toArray(a);
    }

    public boolean add(T t) {
        return list.add(t);
    }

    public boolean remove(Object o) {
        return list.remove(o);
    }

    public boolean containsAll(@NotNull Collection<?> c) {
        return list.containsAll(c);
    }

    public boolean addAll(@NotNull Collection<? extends T> c) {
        return list.addAll(c);
    }

    public boolean addAll(int index, @NotNull Collection<? extends T> c) {
        return list.addAll(index, c);
    }

    public boolean removeAll(@NotNull Collection<?> c) {
        return list.removeAll(c);
    }

    public boolean retainAll(@NotNull Collection<?> c) {
        return list.retainAll(c);
    }

    public void replaceAll(UnaryOperator<T> operator) {
        list.replaceAll(operator);
    }

    public void sort(Comparator<? super T> c) {
        list.sort(c);
    }

    public void clear() {
        list.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NicerStringifyList<?> that = (NicerStringifyList<?>) o;
        return Objects.equals(list, that.list);
    }

    @Override
    public int hashCode() {
        return list.hashCode();
    }

    public T get(int index) {
        return list.get(index);
    }

    public T set(int index, T element) {
        return list.set(index, element);
    }

    public void add(int index, T element) {
        list.add(index, element);
    }

    public T remove(int index) {
        return list.remove(index);
    }

    public int indexOf(Object o) {
        return list.indexOf(o);
    }

    public int lastIndexOf(Object o) {
        return list.lastIndexOf(o);
    }

    public ListIterator<T> listIterator() {
        return list.listIterator();
    }

    public ListIterator<T> listIterator(int index) {
        return list.listIterator(index);
    }

    public List<T> subList(int fromIndex, int toIndex) {
        return list.subList(fromIndex, toIndex);
    }

    public Spliterator<T> spliterator() {
        return list.spliterator();
    }

    public boolean removeIf(Predicate<? super T> filter) {
        return list.removeIf(filter);
    }

    public Stream<T> stream() {
        return list.stream();
    }

    public Stream<T> parallelStream() {
        return list.parallelStream();
    }

    public void forEach(Consumer<? super T> action) {
        list.forEach(action);
    }

    private final List<T> list;

    public NicerStringifyList(List<T> list) {
        this.list = list;
    }
}
