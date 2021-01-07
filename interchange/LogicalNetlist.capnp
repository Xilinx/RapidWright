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
    type  @0 :Ref.ReferenceType = rootValue;
    field @1 :Text = "strList";
}
annotation stringRef(*) :StringRef;
using StringIdx = UInt32;

struct PortRef {
    type  @0 :Ref.ReferenceType = parent;
    field @1 :Text = "portList";
    depth @2 :Int32 = 1;
}
annotation portRef(*) :PortRef;
using PortIdx = UInt32;

struct CellRef {
    type  @0 :Ref.ReferenceType = parent;
    field @1 :Text = "cellDecls";
    depth @2 :Int32 = 1;
}
annotation cellRef(*) :CellRef;
using CellIdx = UInt32;

struct InstRef {
    type  @0 :Ref.ReferenceType = parent;
    field @1 :Text = "instList";
    depth @2 :Int32 = 1;
}
annotation instRef(*) :InstRef;
using InstIdx = UInt32;

struct Netlist {

  name      @0 : Text;
  propMap   @1 : PropertyMap;
  strList   @2 : List(Text) $hashSet();
  portList  @3 : List(Port);
  cellDecls @4 : List(CellDeclaration);
  topInst   @5 : CellInstance;
  instList  @6 : List(CellInstance);
  cellList  @7 : List(Cell);

  struct CellDeclaration {
    name     @0 : StringIdx $stringRef();
    propMap  @1 : PropertyMap;
    view     @2 : StringIdx $stringRef();
    lib      @3 : StringIdx $stringRef();
    ports    @4 : List(PortIdx) $portRef();
  }

  struct CellInstance {
    name     @0 : StringIdx $stringRef();
    propMap  @1 : PropertyMap;
    view     @2 : StringIdx $stringRef();
    cell     @3 : CellIdx $cellRef();
  }

  struct Cell {
    index   @0 : CellIdx $cellRef();
    insts   @1 : List(InstIdx) $instRef();
    nets    @2 : List(Net);
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
    port  @0 : PortIdx $portRef(depth = 3);
    busIdx : union {
        singleBit @1 : Void; # Single bit
        idx       @2 : UInt32; # Index within bussed port
    }
    union {
      extPort     @3 : Void;
      inst        @4 : InstIdx $instRef(depth = 3);
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
