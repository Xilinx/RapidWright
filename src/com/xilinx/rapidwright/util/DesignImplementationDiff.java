/*
 * 
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
package com.xilinx.rapidwright.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePIPStatus;
import com.xilinx.rapidwright.interchange.PhysNetlistWriter;
import com.xilinx.rapidwright.interchange.SiteBELPin;
import com.xilinx.rapidwright.interchange.SiteSitePIP;

/**
 * This class takes as input two DCPs, one which is derived from the other and
 * reports the number of changes to the original implementation.  Specifically,
 * the number of cells that have moved from their original placement, and the
 * number of nets that have had routing PIPs changed beyond the original routing.
 * Created on: May 9, 2017
 */
public class DesignImplementationDiff {

	public static void diffPart(Design lhs, Design rhs) {
		if (lhs.getPartName() != rhs.getPartName()) {
			System.out.println("< Part " + lhs.getPartName());
			System.out.println("> Part " + rhs.getPartName());
		} else {
			System.out.println("Parts match (" + lhs.getPartName() + ")");
		}
	}

	public static void diffSiteInsts(Design lhs, Design rhs) {
		int nleft = 0;
		int nright = 0;
		for (SiteInst lsi : lhs.getSiteInsts()) {
			SiteInst rsi = rhs.getSiteInst(lsi.getName());
			if (rsi == null) {
				System.out.println("< SiteInst '" + lsi.getName() + "'");
				nleft++;
				continue;
			}

			if (lsi.getSiteTypeEnum() != rsi.getSiteTypeEnum()) {
				System.out.println("< SiteInstType '" + lsi.getName() + "'=" + lsi.getSiteTypeEnum());
				System.out.println("> SiteInstType '" + rsi.getName() + "'=" + rsi.getSiteTypeEnum());
				nleft++;
				nright++;
			}

			if (lsi.isSiteLocked() != rsi.isSiteLocked()) {
				System.out.println("< SiteInstLocked '" + lsi.getName() + "'=" + lsi.isSiteLocked());
				System.out.println("> SiteInstLocked '" + rsi.getName() + "'=" + rsi.isSiteLocked());
				nleft++;
				nright++;
			}
		}

		for (SiteInst rs : rhs.getSiteInsts()) {
			if (lhs.getSiteInst(rs.getName()) == null) {
				System.out.println("> SiteInst '" + rs.getName() + "'");
				nright++;
			}
		}

		if (nleft == 0 && nright == 0) {
			System.out.println("SiteInsts match (" + lhs.getSiteInsts().size() + ")");
		} else {
			System.out.println("SiteInsts DO NOT match: left=" + nleft + " right=" + nright);
		}
	}

	public static void diffCells(Design lhs, Design rhs) {
		nleft = 0;
		nright = 0;
		for (Cell lc : lhs.getCells()) {
			Cell rc = rhs.getCell(lc.getName());
			if (rc == null) {
				System.out.println("< Cell '" + lc.getName() + "'");
				nleft++;
				continue;
			}

			// Since there could be more than one cell with this name,
			// don't bother checking its other fields
			if (lc.getName().equals(PhysNetlistWriter.LOCKED)) {
				continue;
			}

			if (!lc.getType().equals(rc.getType())) {
				System.out.println("< CellType '" + lc.getName() + "'=" + lc.getType());
				System.out.println("> CellType '" + lc.getName() + "'=" + rc.getType());
				nleft++;
				nright++;
			}

			if (lc.getSite() != rc.getSite()) {
				System.out.println("< CellSite '" + lc.getName() + "'=" + lc.getSite());
				System.out.println("> CellSite '" + lc.getName() + "'=" + rc.getSite());
				nleft++;
				nright++;
			}

			if (lc.getBEL() != rc.getBEL()) {
				System.out.println("< CellBEL '" + lc.getName() + "'=" + lc.getBEL());
				System.out.println("> CellBEL '" + lc.getName() + "'=" + rc.getBEL());
				nleft++;
				nright++;
			}

			if (lc.isBELFixed() != rc.isBELFixed()) {
				System.out.println("< CellBELFixed '" + lc.getName() + "'=" + lc.isBELFixed());
				System.out.println("> CellBELFixed '" + lc.getName() + "'=" + rc.isBELFixed());
				nleft++;
				nright++;
			}

			if (lc.isSiteFixed() != rc.isSiteFixed()) {
				System.out.println("< CellSiteFixed '" + lc.getName() + "'=" + lc.isSiteFixed());
				System.out.println("> CellSiteFixed '" + lc.getName() + "'=" + rc.isSiteFixed());
				nleft++;
				nright++;
			}

			for (Map.Entry<String, String> e : lc.getPinMappingsP2L().entrySet()) {
				String lv = e.getValue();
				String rv = rc.getLogicalPinMapping(e.getKey());
				if (lv == null) {
					System.out.println("< CellPinMapping '" + lc.getName() + "':" + e.getKey());
					nleft++;
				}

				if (!lv.equals(rv)) {
					System.out.println("< CellPinMapping '" + lc.getName() + "':" + e.getKey() + "=" + lv);
					System.out.println("> CellPinMapping '" + lc.getName() + "':" + e.getKey() + "=" + rv);
					nleft++;
					nright++;
				}
			}

			for (Map.Entry<String, String> e : rc.getPinMappingsP2L().entrySet()) {
				String lv = lc.getLogicalPinMapping(e.getKey());
				if (lv == null) {
					System.out.println("> CellPinMapping '" + rc.getName() + "':" + e.getKey());
					nright++;
				}
			}
		}

		for (Cell rc : rhs.getCells()) {
			if (lhs.getCell(rc.getName()) == null) {
				System.out.println("> Cell '" + rc.getName() + "'");
				nright++;
			}
		}

		if (nleft == 0 && nright == 0) {
			System.out.println("Cells match (" + lhs.getCells().size() + ")");
		} else {
			System.out.println("Cells DO NOT match: left=" + nleft + " right=" + nright);
		}
	}

	static class DeepEqualsPIP {
		PIP pip;

		public static Collection<DeepEqualsPIP> fromPIPs(Collection<PIP> pips) {
			List<DeepEqualsPIP> ret = new ArrayList<>(pips.size());
			pips.forEach((p) -> ret.add(new DeepEqualsPIP(p)));
			return ret;
		}

		private DeepEqualsPIP(PIP pip) {
			this.pip = pip;
		}

		@Override
		public boolean equals(Object obj) {
			return pip.deepEquals(obj);
		}

		@Override
		public int hashCode() {
			return pip.deepHashCode();
		}

		@Override
		public String toString() {
			return pip.toString();
		}
	}

	static int nleft;
	static int nright;

	private static <T> int diffCollections(Collection<T> lhs, Collection<T> rhs,
										   String prefix) {
		Set<T> lset = new HashSet<>(lhs);
		Set<T> rset = new HashSet<>(rhs);

		if (lset.size() != lhs.size()) {
			System.out.println("< " + prefix + " has duplicates");
		}
		if (rset.size() != rhs.size()) {
			System.out.println("> " + prefix + " has duplicates");
		}

		for (T l : lset) {
			if (!rset.contains(l)) {
				System.out.println("< " + prefix + "=" + l);
				nleft++;
			}
		}
		rset.removeAll(lset);
		for (T r : rset) {
			System.out.println("> " + prefix + "=" + r);
			nright++;
		}

		return lset.size();
	}

	public static void diffNets(Design lhs, Design rhs, boolean ignoreLeftUnrouted) {
		Function<Design, Map<Net, Set<SiteSitePIP>>> netToSitePips = (Design design) -> {
			Map<Net, Set<SiteSitePIP>> m = new HashMap<>();
			for (SiteInst si : design.getSiteInsts()) {
				for(SitePIP sp : si.getUsedSitePIPs()) {
					String siteWire = sp.getInputPin().getSiteWireName();
					Net net = si.getNetFromSiteWire(siteWire);
					if(net == null) {
						String sitePinName = sp.getInputPin().getConnectedSitePinName();
						SitePinInst spi = si.getSitePinInst(sitePinName);
						if(spi != null) {
							net = spi.getNet();
						}
					}
					SitePIPStatus status = si.getSitePIPStatus(sp);
					if (!m.computeIfAbsent(net, (k) -> new HashSet<>()).add(
							new SiteSitePIP(si.getSite(), sp, status.isFixed()))) {
						// System.out.println("DUPE");
					}
				}
			}
			return m;
		};
		Map<Net, Set<SiteSitePIP>> lsitepips = netToSitePips.apply(lhs);
		Map<Net, Set<SiteSitePIP>> rsitepips = netToSitePips.apply(rhs);

		Function<Design, Map<Net, Set<SiteBELPin>>> netToBelPins = (Design design) -> {
			Map<Net, Set<SiteBELPin>> m = new HashMap<>();
			for (SiteInst si : design.getSiteInsts()) {
				for(Map.Entry<Net,HashSet<String>> e : si.getSiteCTags().entrySet()) {
					if(e.getValue() != null && e.getValue().size() > 0) {
						for(String siteWire : e.getValue()) {
							BELPin[] belPins = si.getSiteWirePins(siteWire);
							Net net = si.getNetFromSiteWire(siteWire);
							for(BELPin belPin : belPins) {
								BEL bel = belPin.getBEL();
								Cell cell = si.getCell(bel);
								if(belPin.isInput()) {
									// Skip if no BEL placed here
									if (cell == null) {
										continue;
									}
									// Skip if pin not used (e.g. A1 connects to A[56]LUT.A1;
									// both cells can exist but not both need be using this pin)
									if (cell.getLogicalPinMapping(belPin.getName()) == null) {
										continue;
									}
								}

								m.computeIfAbsent(net, (k) -> new HashSet<>()).add(
										new SiteBELPin(si.getSite(), belPin));
							}
						}
					}
				}
			}
			return m;
		};
		Map<Net, Set<SiteBELPin>> lbelpins = netToBelPins.apply(lhs);
		Map<Net, Set<SiteBELPin>> rbelpins = netToBelPins.apply(rhs);

		nleft = 0;
		nright = 0;
		int npips = 0;
		for (Net ln : lhs.getNets()) {
			Net rn = rhs.getNet(ln.getName());
			if (rn == null) {
				if (ignoreLeftUnrouted && !ln.hasPIPs()) {
					continue;
				}
				System.out.println("< Net '" + ln.getName() + "'");
				nleft++;
				continue;
			}

			diffCollections(ln.getPins(), rn.getPins(), "NetPin '" + ln.getName() + "'");

			// HashSet<DeepEqualsPIP> lpips = new HashSet<>(DeepEqualsPIP.fromPIPs(ln.getPIPs()));
			// HashSet<DeepEqualsPIP> rpips = new HashSet<>(DeepEqualsPIP.fromPIPs(rn.getPIPs()));
			npips += diffCollections(ln.getPIPs(), rn.getPIPs(), "NetPIP '" + ln.getName() + "'");

			diffCollections(lsitepips.getOrDefault(ln, Collections.EMPTY_SET),
					rsitepips.getOrDefault(rn, Collections.EMPTY_SET),
					"NetSitePIP '" + ln.getName() + "'");

			diffCollections(lbelpins.getOrDefault(ln, Collections.EMPTY_SET),
					rbelpins.getOrDefault(rn, Collections.EMPTY_SET),
					"NetBELPin '" + ln.getName() + "'");
		}

		for (Net rn : rhs.getNets()) {
			if (lhs.getNet(rn.getName()) == null) {
				System.out.println("> Net '" + rn.getName() + "'");
				nright++;
			}
		}

		if (nleft == 0 && nright == 0) {
			System.out.println("Nets and pins and PIPs match (" + lhs.getNets().size() + "/" + npips + ")");
		} else {
			System.out.println("Nets and/or pins and/or PIPs DO NOT match: left=" + nleft + " right=" + nright);
		}
	}

	public static void diff(Design lhs, Design rhs) {
		diffPart(lhs, rhs);
		diffSiteInsts(lhs, rhs);
		diffCells(lhs, rhs);
		diffNets(lhs, rhs, false);
	}

	public static void main(String[] args) {
		if(args.length != 2){
			System.out.println("USAGE: <original.dcp> <superset.dcp>");
			return;
		}
		Design original = Design.readCheckpoint(args[0]);
		Design superset = Design.readCheckpoint(args[1]);
		int cellMovements = 0;
		int netRoutingChanges = 0;
		for(Cell c : original.getCells()){
			BEL e = c.getBEL();
			Site s = c.getSite();
			boolean placementChange = false;
			Cell cc = superset.getCell(c.getName());
			if(cc == null){
				System.out.println("Cell " + c.getName() + " is missing");
				continue;
			}
			if(!cc.getSite().equals(s)){
				System.out.println("Cell " + c.getName() + " has moved to " + cc.getSite());
				placementChange = true;
			}
			
			if(!cc.getBEL().equals(e)){
				System.out.println("Cell " + c.getName() + " has moved to " + cc.getBEL());
				placementChange = true;
			}
			cellMovements += placementChange ? 1 : 0;
		}
		
		for(Net n : original.getNets()){
			Net nn = superset.getNet(n.getName());
			if(nn == null){
				System.out.println("Net " + nn + " is missing");
				continue;
			}
			if(nn.isStaticNet()) continue;
			boolean netChange = false;
			HashSet<PIP> pips = new HashSet<>(nn.getPIPs());	
			for(PIP p : n.getPIPs()){
				if(!pips.contains(p)){
					System.out.println("Missing PIP " + p.toString() + " from net " + n.getName());
					netChange = true;
				}
			}
			netRoutingChanges += netChange ? 1 : 0;
		}
		
		int cellCount = original.getCells().size();
		int netCount = original.getNets().size();
		
		float placementPercent = ((float)cellMovements/(float)cellCount) * 100.0f;
		float routingPercent = ((float)netRoutingChanges/(float)netCount) * 100.0f;
		System.out.printf("Placement changes: %d/%d %3.2f%%\n", cellMovements, cellCount, placementPercent);
		System.out.printf("Net routing changes: %d/%d %3.2f%%\n", netRoutingChanges, netCount, routingPercent);
		
	}
}
