/* 
 * Copyright (c) 2017 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 * 
 */
package com.xilinx.rapidwright.design;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFNetlist;


/**
 * A wrapper class for multiple implementations of a module.  
 * Since the device, name, and netlist should be all be the same,
 * it offers convenience accessors to the first implementation
 * if it exists.
 * Created on: Jun 21, 2016
 */
public class ModuleImpls extends ArrayList<Module> {

	/**
	 * 
	 */
	private static final long serialVersionUID = -8931048535637180230L;

	
	public String getName(){
		return size() > 0 ? get(0).getName() : null;
	}
	
	public Device getDevice(){
		return size() > 0 ? get(0).getDevice() : null;
	}
	
	public EDIFNetlist getNetlist(){
		return size() > 0 ? get(0).getNetlist() : null;
	}

	private void checkSameNetlist() {
		for (Module mod : this) {
			if (mod.getNetlist() != getNetlist()) {
				throw new RuntimeException("In the ModuleImpls "+mod.getName()+", the netlists are not pointer-equal");
			}
		}
	}

	@Override
	public boolean add(Module mod) {
		boolean res = super.add(mod);
		checkSameNetlist();
		mod.setImplementationIndex(size()-1);
		return res;
	}

	@Override
	public boolean addAll(int index, Collection<? extends Module> c) {
		boolean res = super.addAll(index, c);
		checkSameNetlist();
		return res;
	}

	@Override
	public boolean addAll(Collection<? extends Module> c) {
		boolean res = super.addAll(c);
		checkSameNetlist();
		return res;
	}

	public ModuleImpls(Collection<? extends Module> c) {
		super(c);
		checkSameNetlist();
	}

	public ModuleImpls(int initialCapacity) {
		super(initialCapacity);
	}

	public ModuleImpls() {
		super();
	}

	@Override
	public Module set(int index, Module element) {
		Module res = super.set(index, element);
		checkSameNetlist();
		return res;
	}

	@Override
	public void replaceAll(UnaryOperator<Module> operator) {
		super.replaceAll(operator);
		checkSameNetlist();
	}

	private Collection<ModulePlacement> allPlacements;

	public Collection<ModulePlacement> getAllPlacements() {
		if (allPlacements == null) {
			allPlacements = stream()
					.flatMap(mod ->
							mod.getAllValidPlacements().stream()
									.map(site -> new ModulePlacement(mod.getImplementationIndex(), site))
					)
					.sorted(Comparator.comparing(p->p.placement.getTile().getColumn()))
					.collect(Collectors.toList());
		}
		return allPlacements;
	}
}
