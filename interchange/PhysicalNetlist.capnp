@0xcb2ccd67aa912968;
using Java = import "/capnp/java.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("PhysicalNetlist");

using StringIdx = UInt32;

struct PhysNetlist {

  part         @0 : Text;
  placements   @1 : List(CellPlacement);
  physNets     @2 : List(PhysNet);
  physCells    @3 : List(PhysCell);
  strList      @4 : List(Text);
  siteInsts    @5 : List(SiteInstance);
  properties   @6 : List(Property);

  struct PinMapping {
    cellPin @0 : StringIdx;
    bel     @1 : StringIdx;
    belPin  @2 : StringIdx;
    isFixed @3 : Bool;
  }

  struct CellPlacement {
    cellName      @0 : StringIdx;
    type          @1 : StringIdx;
    site          @2 : StringIdx;
    bel           @3 : StringIdx;
    pinMap        @4 : List(PinMapping);
    otherBels     @5 : List(StringIdx);
    isBelFixed    @6 : Bool;
    isSiteFixed   @7 : Bool;
  }
  
  struct PhysCell {
    cellName    @0 : StringIdx;
    physType    @1 : PhysCellType;
  }
  
  enum PhysCellType {
    locked  @0;
    port    @1;
    gnd     @2;
    vcc     @3;
  }
  
  struct PhysNet {
    name      @0 : StringIdx;
    sources   @1 : List(RouteBranch);
    stubs     @2 : List(RouteBranch);
  }
  
  struct RouteBranch {
    routeSegment : union {
      belPin  @0 : PhysBelPin;
      sitePin @1 : PhysSitePin;
      pip     @2 : PhysPIP;
      sitePIP @3 : PhysSitePIP;
    }
    branches @4 : List(RouteBranch);  
  }
  
  struct PhysBel {
    site @0 : StringIdx;
    bel  @1 : StringIdx;    
  }
  
  struct PhysBelPin {
    site @0 : StringIdx;
    bel  @1 : StringIdx;
    pin  @2 : StringIdx;
  }
  
  struct PhysSitePin { 
    site @0 : StringIdx;
    pin  @1 : StringIdx;
  }
  
  struct PhysPIP {
    tile    @0 : StringIdx;
    wire0   @1 : StringIdx;
    wire1   @2 : StringIdx;
    forward @3 : Bool;
    isFixed @4 : Bool;
  }
  
  struct PhysSitePIP {
    site    @0 : StringIdx;
    bel     @1 : StringIdx;
    pin     @2 : StringIdx;
    isFixed @3 : Bool;
  }
  
  struct SiteInstance {
    site  @0 : StringIdx;
    type  @1 : StringIdx;
  }
  
  struct Property {
    key   @0 : StringIdx;
    value @1 : StringIdx;
  }
}