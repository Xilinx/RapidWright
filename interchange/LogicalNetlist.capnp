@0xcb2ccd67aa912967;
using Java = import "/capnp/java.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("LogicalNetlist");

using StringIdx = UInt32;
using PortIdx = UInt32;
using CellIdx = UInt32;
using InstIdx = UInt32;

struct Netlist {

  name     @0 : Text;
  propMap  @1 : PropertyMap;
  topInst  @2 : CellInstance;
  strList  @3 : List(Text);
  cellList @4 : List(Cell);
  portList @5 : List(Port);
  instList @6 : List(CellInstance);

  
  struct Cell {
    name     @0 : StringIdx;
    propMap  @1 : PropertyMap;
    view     @2 : StringIdx;
    lib      @3 : StringIdx;
    insts    @4 : List(InstIdx);
    nets     @5 : List(Net);
    ports    @6 : List(PortIdx);
  }
  
  struct CellInstance {
    name     @0 : StringIdx;
    propMap  @1 : PropertyMap;
    view     @2 : StringIdx;
    cell     @3 : CellIdx;
  }
  
  struct Net {
    name      @0 : StringIdx;
    propMap   @1 : PropertyMap;
    portInsts @2 : List(PortInstance);
  }
  
  struct Port {
    name     @0 : StringIdx;
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
    name  @0 : StringIdx;
    port  @1 : StringIdx;
    idx   @2 : UInt32; # Index within bussed port
    union {
      extPort @3 : Void;
      inst    @4 : InstIdx;
    }
  }
  
  struct PropertyMap {
    entries @0 : List(Entry);  	
    struct Entry {	
      key @0 : StringIdx;
      union {
        textValue  @1 : StringIdx;
        intValue   @2 : Int32;
        boolValue  @3 : Bool;
      }
    }
  }
}