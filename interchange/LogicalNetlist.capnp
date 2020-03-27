@0xcb2ccd67aa912967;
using Java = import "/capnp/java.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("LogicalNetlist");

struct Netlist {

  name     @0 : Text;
  propMap  @1 : PropertyMap;
  topInst  @2 : CellInstance;
  strList  @3 : List(Text);
  cellList @4 : List(Cell);
  portList @5 : List(Port);
  instList @6 : List(CellInstance);

  
  struct Cell {
    name    @0 : UInt32;
    propMap @1 : PropertyMap;
    view    @2 : UInt32;
    lib     @3 : UInt32;
    insts   @4 : List(UInt32);
    nets    @5 : List(Net);
    ports   @6 : List(UInt32);
  }
  
  struct CellInstance {
    name    @0 : UInt32;
    propMap @1 : PropertyMap;
    view    @2 : UInt32;
    cell    @3 : UInt32;
  }
  
  struct Net {
    name      @0 : UInt32;
    propMap   @1 : PropertyMap;
    portInsts @2 : List(PortInstance);
  }
  
  struct Port {
    name    @0 : UInt32;
    dir     @1 : Direction;
    propMap @2 : PropertyMap;
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
    name  @0 : UInt32;
    port  @1 : UInt32;
    idx   @2 : UInt32;
    union {
      extPort @3 : Void;
      inst    @4 : UInt32;
    }
  }
  
  struct PropertyMap {
    entries @0 : List(Entry);  	
    struct Entry {	
      key @0 : UInt32;
      union {
        textValue @1 : UInt32;
        intValue  @2 : Int32;
        boolValue @3 : Bool;
      }
    }
  }
}