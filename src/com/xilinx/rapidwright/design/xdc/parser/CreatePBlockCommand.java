package com.xilinx.rapidwright.design.xdc.parser;

import java.util.Arrays;
import java.util.Collections;

import com.xilinx.rapidwright.design.xdc.PBlockConstraint;
import com.xilinx.rapidwright.design.xdc.XDCConstraints;
import com.xilinx.rapidwright.device.Device;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class CreatePBlockCommand implements Command {
    private final XDCConstraints constraints;

    public CreatePBlockCommand(XDCConstraints constraints) {
        this.constraints = constraints;
    }
    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        if (objv.length!=2) {
            throw new RuntimeException("wrong argument count");
        }
        String pBlockName = objv[1].toString();
        if (constraints.getPBlockConstraints().containsKey(pBlockName)) {
            throw new RuntimeException("duplicate pblock name: "+pBlockName);
        }
        constraints.getPBlockConstraints().put(pBlockName, new PBlockConstraint());
        NameDesignObject<?> res = new NameDesignObject<>(ObjType.PBlock, Collections.singletonList(pBlockName));
        interp.setResult(TclHashIdentifiedObject.createReflectObject(interp, NameDesignObject.class, res));
    }
}
