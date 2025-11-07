package com.xilinx.rapidwright.design.xdc.parser;

import java.util.Collections;
import java.util.Objects;

import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.design.xdc.PBlockConstraint;
import com.xilinx.rapidwright.design.xdc.XDCConstraints;
import com.xilinx.rapidwright.device.Device;
import tcl.lang.Command;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class ResizePBlockCommand implements Command {
    private final XDCConstraints constraints;
    private Device dev;

    public ResizePBlockCommand(XDCConstraints constraints, Device dev) {
        this.constraints = constraints;
        this.dev = dev;
    }
    @Override
    public void cmdProc(Interp interp, TclObject[] objv) throws TclException {
        String pblockName = null;
        String toAdd = null;
        for (int i = 1; i < objv.length; i++) {
            if (objv[i].toString().startsWith("-")) {
                switch (objv[i].toString()) {
                    case "-quiet":
                    case "-verbose":
                        break;
                    case "-add":
                        if (toAdd != null) {
                            throw new RuntimeException("multiple -add calls");
                        }
                        toAdd = objv[++i].toString();
                        break;
                    default:
                        throw new RuntimeException("unsupported resize_block mode " + objv[i]);
                }
            } else {
                DesignObject obj = DesignObject.requireUnwrapTclObject(interp, objv[i], null);
                if (!(obj instanceof NameDesignObject) || ((NameDesignObject) obj).getType() != ObjType.PBlock) {
                    throw new RuntimeException("expected pblock but got " + obj.toXdc());
                }
                pblockName = ((NameDesignObject) obj).requireOneObject();
            }
        }

        if (pblockName == null) {
            throw new RuntimeException("pblock name is null");
        }
        PBlockConstraint constraint = Objects.requireNonNull(constraints.getPBlockConstraints().get(pblockName));
        constraint.getPblock().add(new PBlockRange(dev, toAdd));
    }
}
