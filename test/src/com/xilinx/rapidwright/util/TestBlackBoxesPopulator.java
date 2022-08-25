package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class TestBlackBoxesPopulator {

    private static String topDCPName = RapidWrightDCP.getString("hwct.dcp");
    private static String cellDCPName = RapidWrightDCP.getString("hwct_pr1.dcp");
    private static String cellAnchor = "INT_X0Y60";
    private static List<Pair<String, String>> targets = new ArrayList<>()
    {{
        add(new Pair<>("hw_contract_pr0", "INT_X0Y120"));
        add(new Pair<>("hw_contract_pr1", "INT_X0Y60"));
        add(new Pair<>("hw_contract_pr2", "INT_X0Y0"));
    }};

    private Pair<Design,Design> helper() {
        Design top = Design.readCheckpoint(topDCPName);
        Design template = Design.readCheckpoint(cellDCPName);
        Module mod = new Module(template, false);

        BlackboxesPopulator.relocateModuleInsts(top, mod, cellAnchor, targets);

        return new Pair<>(top, template);
    }

    @Test
    void testRelocateModuleInsts() {
        Pair<Design,Design> designs = helper();
        Design top = designs.getFirst();
        Design template = designs.getSecond();

        Assertions.assertEquals(targets.size()*template.getCells().size(), top.getCells().size());
        // There are 2 static nets that will not be copied.
        Assertions.assertEquals(targets.size()*(template.getNets().size()-2)+2, top.getNets().size());
    }

}
