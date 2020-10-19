@0x9d262c6ba6512325;
using Java = import "/capnp/java.capnp";
using Dir = import "LogicalNetlist.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("DeviceResources");

using StringIdx = UInt32;
using SiteTypeIdx = UInt32;
using BELPinIdx = UInt32;
using WireIdx = UInt32;
using WireIDInTileType = UInt32; # ID in Tile Type

struct Device {

  name         @0 : Text;
  strList      @1 : List(Text);
  siteTypeList @2 : List(SiteType);
  tileTypeList @3 : List(TileType);
  tileList     @4 : List(Tile);
  wires        @5 : List(Wire);
  nodes        @6 : List(Node);
  primLibs     @7 : Dir.Netlist; # Netlist libraries of Unisim primitives and macros
  exceptionMap @8 : List(PrimToMacroExpansion); # Prims to macros expand w/same name, except these

  #######################################
  # Placement definition objects
  #######################################  
  struct SiteType {
    name      @0 : StringIdx;
    pins      @1 : List(SitePin); 
    lastInput @2 : UInt32; # Index of the last input pin
    bels      @3 : List(BEL);
    belPins   @4 : List(BELPin); # All BEL Pins in site type
    sitePIPs  @5 : List(SitePIP);
    siteWires @6 : List(SiteWire);
  }

  struct TileType {
    name      @0 : StringIdx;
    siteTypes @1 : List(SiteTypeIdx);
    wires     @2 : List(StringIdx);
    pips      @3 : List(PIP);
  }
  
  #######################################
  # Placement instance objects
  #######################################  
  struct BEL {
    name      @0 : StringIdx;
    type      @1 : StringIdx;
    pins      @2 : List(BELPinIdx); 
    category  @3 : BELCategory; # This would be BELClass/class, but conflicts with Java language
  }
  
  enum BELCategory {
    logic    @0;
    routing  @1;
    sitePort @2;
  }
      
  struct Site {
    name      @0 : StringIdx;
    type      @1 : SiteTypeIdx; 
  }

  struct Tile {
    name       @0 : StringIdx;
    type       @1 : StringIdx;
    sites      @2 : List(Site);
    row        @3 : UInt16;
    col        @4 : UInt16;
    tilePatIdx @5 : UInt32;
  }
  
  ######################################
  # Intra-site routing resources
  ######################################
  struct BELPin {
    name   @0 : StringIdx;
    dir    @1 : Dir.Netlist.Direction;
    bel    @2 : StringIdx;
  }

  struct SiteWire {
    name   @0 : StringIdx;
    pins   @1 : List(BELPinIdx);
  }
       
  struct SitePIP {
    inpin  @0 : BELPinIdx;
    outpin @1 : BELPinIdx;
  }
  
  struct SitePin {
    name     @0 : StringIdx;
    dir      @1 : Dir.Netlist.Direction;
    sitewire @2 : StringIdx; 
    belpin   @3 : BELPinIdx;
  }
  
  
  ######################################
  # Inter-site routing resources
  ######################################  
  struct Wire {
    tile      @0 : StringIdx;
    wire      @1 : StringIdx;
  }
  
  struct Node {
    wires    @0 : List(WireIdx);
  }
  
  struct PIP {
    wire0        @0 : WireIDInTileType;
    wire1        @1 : WireIDInTileType;
    directional  @2 : Bool;
    buffered20   @3 : Bool;
    buffered21   @4 : Bool;
  }
  
  ######################################
  # Macro expansion exception map for 
  # primitives that don't expand to a 
  # macro of the same name.
  ######################################  
  struct PrimToMacroExpansion {
    primName  @0 : StringIdx;
    macroName @1 : StringIdx;
  }
}