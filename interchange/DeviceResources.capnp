@0x9d262c6ba6512325;
using Java = import "/capnp/java.capnp";
using Dir = import "LogicalNetlist.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("DeviceResources");

struct Device {

  name         @0 : Text;
  strList      @1 : List(Text);
  siteTypeList @2 : List(SiteType);
  tileTypeList @3 : List(TileType);
  tileList     @4 : List(Tile);
  wires        @5 : List(Wire);
  nodes        @6 : List(Node);

  #######################################
  # Placement definition objects
  #######################################  
  struct SiteType {
    name      @0 : UInt32;
    pins      @1 : List(SitePin); 
    lastInput @2 : UInt32; # Index of the last input pin
    bels      @3 : List(BEL);
    belPins   @4 : List(BELPin); # All BEL Pins in site type
    sitePIPs  @5 : List(SitePIP);
    siteWires @6 : List(SiteWire);
  }

  struct TileType {
    name      @0 : UInt32;
    siteTypes @1 : List(UInt32);
    wires     @2 : List(UInt32);
    pips      @3 : List(PIP);
  }
  
  #######################################
  # Placement instance objects
  #######################################  
  struct BEL {
    name      @0 : UInt32;
    type      @1 : UInt32;
    pins      @2 : List(UInt32); # String index names
    category  @3 : BELCategory; # This would be BELClass/class, but conflicts with Java language
  }
  
  enum BELCategory {
    logic    @0;
    routing  @1;
    sitePort @2;
  }
      
  struct Site {
    name      @0 : UInt32;
    type      @1 : UInt32; # index in device of site type
  }

  struct Tile {
    name       @0 : UInt32;
    type       @1 : UInt32;
    sites      @2 : List(Site);
    row        @3 : UInt16;
    col        @4 : UInt16;
    tilePatIdx @5 : UInt32;
  }
  
  ######################################
  # Intra-site routing resources
  ######################################
  struct BELPin {
    name   @0 : UInt32;
    dir    @1 : Dir.Netlist.Direction;
    bel    @2 : UInt32; # String name index
  }

  struct SiteWire {
    name   @0 : UInt32;
    pins   @1 : List(UInt32);
  }
       
  struct SitePIP {
    inpin  @0 : UInt32;
    outpin @1 : UInt32;
  }
  
  struct SitePin {
    name     @0 : UInt32;
    dir      @1 : Dir.Netlist.Direction;
    sitewire @2 : UInt32; # String index name
  }
  
  
  ######################################
  # Inter-site routing resources
  ######################################  
  struct Wire {
    tile      @0 : UInt32;
    wire      @1 : UInt32;
  }
  
  struct Node {
    wires    @0 : List(UInt32);
  }
  
  struct PIP {
    wire0        @0 : UInt32;
    wire1        @1 : UInt32;
    directional  @2 : Bool;
    buffered20   @3 : Bool;
    buffered21   @4 : Bool;
  }
}