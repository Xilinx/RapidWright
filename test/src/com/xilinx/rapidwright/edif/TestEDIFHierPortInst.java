package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFHierPortInst {
    @Test
    void testGetPhysicalCell() {
        Design d = new Design("design", Device.KCU105);
        EDIFNetlist n = d.getNetlist();
        String cellName = "name\\.with\\.backslashes";
        // Note: need to place cell as Cell.updateName() requires a SiteInst in order to acquire the Design
        Cell c = d.createAndPlaceCell(cellName, Unisim.FDRE, "SLICE_X0Y0/AFF");
        EDIFHierCellInst ehci = n.getHierCellInstFromName(cellName);
        new EDIFPortInst(ehci.getCellType().getPort("Q"), null, ehci.getInst());

        EDIFHierPortInst ehpi = n.getHierPortInstFromName(cellName + EDIFTools.EDIF_HIER_SEP + "Q");
        Assertions.assertEquals(c, ehpi.getPhysicalCell(d));

        // It's been observed that Vivado can doubly escape the physical Cell name
        c.updateName(cellName.replace("\\", "\\\\"));
        // Check that we can still find it in this case
        Assertions.assertEquals(c, ehpi.getPhysicalCell(d));
    }
}
