package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.tests.CodePerfTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class TestCopyStaticNets {
    public static class TestCopyStaticNets {

        public static int numPIPsOfStaticNets (Module mod) {
            int numPIPs = 0;
            ArrayList<Net> staticNets = new ArrayList<Net>();
            for(Net templateNet : mod.getNets()) {
                if (templateNet.getName().equals(Net.GND_NET) || templateNet.getName().equals(Net.VCC_NET)) {
                    if (templateNet.hasPIPs())
                        numPIPs += templateNet.getPIPs().size();
                }
            }
            return numPIPs;
        }

        public static Set<String> numStaticSource (Collection<Net> nets) {
            // TODO: Consider using SiteInst instead of String
            Set<String> allSRCs = new HashSet<>();
            for(Net net : nets) {
                if (net.getName().equals(Net.GND_NET) || net.getName().equals(Net.VCC_NET)) {
                    for(SitePinInst sitePinInst : net.getPins()) {
                        if (sitePinInst.getSiteInst().getName().startsWith(SiteInst.STATIC_SOURCE)) {
                            //                        System.out.println(sitePinInst.getSiteInst().getSite().getName() + " " + sitePinInst.getSiteInst().getName());
                            allSRCs.add(sitePinInst.getSiteInst().getSite().getName());
                        }
                    }
                }
            }
            return allSRCs;
        }

        public static boolean compareUsingBlackBoxes(String topDCPName, String cellDCPName, String frAnchor, String toCell, String toAnchor) {

            Module refMod = new Module(Design.readCheckpoint(cellDCPName), false);

            // Check if Module mod has PIPs for static net
            // Assumption: any module will have GND and VCC nets, ie., static sources exist.
            int refNumPIPs = numPIPsOfStaticNets(refMod);
            System.out.println("Number of PIPs in the module is " + refNumPIPs );
            assert refNumPIPs > 0 : "The module is not construct properly. The total number of PIPs for static net is " + refNumPIPs;

            Set<String> refNumSRCsSet = numStaticSource(refMod.getNets());
            int refNumSRCs = refNumSRCsSet.size();
            System.out.println("Number of static sources in the module is " + refNumSRCs );
            assert refNumPIPs > 0 : "The module is not construct properly. The total number of PIPs for static net is " + refNumPIPs;

            // Check if the route of static nets is copied properly.
            Design top = Design.readCheckpoint(topDCPName);
            Net vccNet = top.getVccNet();
            Net gndNet = top.getGndNet();
            int orgNumPIPs = vccNet.getPIPs().size() + gndNet.getPIPs().size();
            System.out.println("Number of PIPs in the top design is " + orgNumPIPs);



            Site anchorSite = refMod.getAnchor().getSite();
            Tile anchorTile = anchorSite.getTile();
            Tile tFrom  = top.getDevice().getTile(frAnchor);
            Tile tTo    = top.getDevice().getTile(toAnchor);
            Tile toTile = anchorTile.getTileNeighbor(0, tFrom.getRow() - tTo.getRow());
            Site toSite = toTile.getSites()[anchorTile.getSiteIndex(anchorSite)];

            ModuleInst mod = top.createModuleInst(toCell, refMod, true);
            mod.getCellInst().setCellType(refMod.getNetlist().getTopCell());
            mod.place(toSite);

            int numPIPs = vccNet.getPIPs().size() + gndNet.getPIPs().size();
            System.out.println("Number of PIPs after inserting the module is " + numPIPs);
            assert numPIPs == orgNumPIPs + refNumPIPs : "The module is not copied properly."
                    + " The total number of PIPs for static net is " + numPIPs +", but expect " + (orgNumPIPs + refNumPIPs);


            return true;
        }

        public static void compare() {
            String dcpPath = "test/RapidWrightDCP/picoblaze_ooc_X10Y235.dcp";
            String partName = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT).getPartName();

            // Set unrouteStaticNets to false
            Module refMod = new Module(Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT), false);
            // Check if Module mod has PIPs for static net
            // Assumption: any module will have GND and VCC nets, ie., static sources exist.
            int refNumPIPs = numPIPsOfStaticNets(refMod);
            System.out.println("Number of PIPs in the module is " + refNumPIPs );
            assert refNumPIPs > 0 : "The module is not construct properly. The total number of PIPs for static net is " + refNumPIPs;


            Design design2 = new Design("design2", partName);
            ModuleInst mi = design2.createModuleInst("inst", refMod);
            mi.placeOnOriginalAnchor();
            Net vccNet = design2.getVccNet();
            Net gndNet = design2.getGndNet();
            int numPIPs = vccNet.getPIPs().size() + gndNet.getPIPs().size();
            System.out.println("Number of PIPs in the top design is " + numPIPs);
            assert numPIPs == refNumPIPs : "The module is not copied properly."
                    + " The total number of PIPs for static net is " + numPIPs +", but expect " + refNumPIPs;
        }

        public static void main(String[] args) {
            //        compareUsingBlackBoxes("hwct.dcp", "hwct_pr0.dcp", "INT_X28Y120",  "hw_contract_pr1", "INT_X28Y60");
            //        compareUsingBlackBoxes("full_shell.dcp", "AES128_rp2_RP2.dcp", "INT_X32Y240",  "openacap_shell_i/RP_0", "INT_X32Y0");
            compare();
        }
    }
}
