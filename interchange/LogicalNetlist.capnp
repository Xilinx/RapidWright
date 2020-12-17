@0xcb2ccd67aa912967;
using Java = import "/capnp/java.capnp";
using Ref = import "References.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("LogicalNetlist");

struct HashSet {
    type  @0 : Ref.ImplementationType = enumerator;
    hide  @1 : Bool = true;
}
annotation hashSet(*) :HashSet;

struct StringRef {
    type  @0 :Ref.ReferenceType = root;
    field @1 :Text = "strList";
}
annotation stringRef(*) :StringRef;
using StringIdx = UInt32;

using PortIdx = UInt32;
using CellIdx = UInt32;
using InstIdx = UInt32;

struct Netlist {

  name     @0 : Text;
  propMap  @1 : PropertyMap;
  topInst  @2 : CellInstance;
  strList  @3 : List(Text) $hashSet();
  cellList @4 : List(Cell);
  portList @5 : List(Port);
  instList @6 : List(CellInstance);


  struct Cell {
    name     @0 : StringIdx $stringRef();
    propMap  @1 : PropertyMap;
    view     @2 : StringIdx $stringRef();
    lib      @3 : StringIdx $stringRef();
    insts    @4 : List(InstIdx);
    nets     @5 : List(Net);
    ports    @6 : List(PortIdx);
  }

  struct CellInstance {
    name     @0 : StringIdx $stringRef();
    propMap  @1 : PropertyMap;
    view     @2 : StringIdx $stringRef();
    cell     @3 : CellIdx;
  }

  struct Net {
    name      @0 : StringIdx $stringRef();
    propMap   @1 : PropertyMap;
    portInsts @2 : List(PortInstance);
  }

  struct Port {
    name     @0 : StringIdx $stringRef();
    dir      @1 : Direction;
    propMap  @2 : PropertyMap;
    union {
      bit @3 : Void;
      bus @4 : Bus;
    }
  }

  enum Direction {
    input  @0;
    output @1;
    inout  @2;
  }

  struct Bus {
    busStart @0 : UInt32;
    busEnd   @1 : UInt32;
  }

  struct PortInstance {
    port  @0 : PortIdx;
    busIdx : union {
        singleBit @1 : Void; # Single bit
        idx       @2 : UInt32; # Index within bussed port
    }
    union {
      extPort     @3 : Void;
      inst        @4 : InstIdx;
    }
  }

  struct PropertyMap {
    entries @0 : List(Entry);
    struct Entry {
      key @0 : StringIdx $stringRef();
      union {
        textValue  @1 : StringIdx $stringRef();
        intValue   @2 : Int32;
        boolValue  @3 : Bool;
      }
    }
  }
}
