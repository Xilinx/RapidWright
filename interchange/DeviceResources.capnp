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
using SitePinIdx = UInt32;
using TileTypeIdx = UInt32;
using TileTypeSiteTypeIdx = UInt32;

struct Device {

  name            @0 : Text;
  strList         @1 : List(Text);
  siteTypeList    @2 : List(SiteType);
  tileTypeList    @3 : List(TileType);
  tileList        @4 : List(Tile);
  wires           @5 : List(Wire);
  nodes           @6 : List(Node);
  primLibs        @7 : Dir.Netlist; # Netlist libraries of Unisim primitives and macros
  exceptionMap    @8 : List(PrimToMacroExpansion); # Prims to macros expand w/same name, except these
  cellBelMap      @9 : List(CellBelMapping);
  cellInversions @10 : Void;
  packages       @11 : List(Package);
  constants      @12 : Constants;

  #######################################
  # Placement definition objects
  #######################################
  struct SiteType {
    name         @0 : StringIdx;
    pins         @1 : List(SitePin);
    lastInput    @2 : UInt32; # Index of the last input pin
    bels         @3 : List(BEL);
    belPins      @4 : List(BELPin); # All BEL Pins in site type
    sitePIPs     @5 : List(SitePIP);
    siteWires    @6 : List(SiteWire);
    altSiteTypes @7 : List(SiteTypeIdx);
  }

  # Maps site pins from alternative site types to the parent primary site pins
  struct ParentPins {
    # pins[0] is the mapping of the siteTypeList[altSiteType].pins[0] to the
    # primary site pin index.
    #
    # To determine the tile wire for a alternative site type, first get the
    # site pin index for primary site, then use primaryPinsToTileWires.
    pins  @0 : List(SitePinIdx);
  }

  struct SiteTypeInTileType {
    primaryType @0 : SiteTypeIdx;

    # primaryPinsToTileWires[0] is the tile wire that matches
    # siteTypeList[primaryType].pins[0], etc.
    primaryPinsToTileWires @1 : List(StringIdx);

    # altPinsToPrimaryPins[0] is the mapping for
    # siteTypeList[primaryType].altSiteTypes[0], etc.
    altPinsToPrimaryPins @2 : List(ParentPins);
  }

  struct TileType {
    name       @0 : StringIdx;
    siteTypes  @1 : List(SiteTypeInTileType);
    wires      @2 : List(StringIdx);
    pips       @3 : List(PIP);
    constants  @4 : List(WireConstantSources);
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
    type      @1 : TileTypeSiteTypeIdx; # Index into TileType.siteTypes
  }

  struct Tile {
    name       @0 : StringIdx;
    type       @1 : TileTypeIdx;
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
    belpin   @2 : BELPinIdx;
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
    union {
      conventional @5 : Void;
      pseudoCells  @6 : List(PseudoCell);
    }
  }

  struct PseudoCell {
    bel          @0 : StringIdx;
    pins         @1 : List(StringIdx);
  }

  struct WireConstantSources {
      wires        @0 : List(WireIDInTileType);
      constant     @1 : ConstantType;
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

  # Cell <-> BEL and Cell pin <-> BEL Pin mapping
  struct CellBelMapping {
    cell          @0 : StringIdx;
    commonPins    @1 : List(CommonCellBelPinMaps);
    parameterPins @2 : List(ParameterCellBelPinMaps);
  }

  # Map one cell pin to one BEL pin.
  # Note: There may be more than one BEL pin mapped to one cell pin.
  struct CellBelPinEntry {
    cellPin @0 : StringIdx;
    belPin  @1 : StringIdx;
  }

  # Specifies BELs located in a specific site type.
  struct SiteTypeBelEntry {
    siteType @0 : StringIdx;
    bels     @1 : List(StringIdx);
  }

  # This is the portion of Cell <-> BEL pin mapping that is common across all
  # parameter settings for a specific site type and BELs within that site
  # type.
  struct CommonCellBelPinMaps {
    siteTypes @0 : List(SiteTypeBelEntry);
    pins      @1 : List(CellBelPinEntry);
  }

  # This is the portion of the Cell <-> BEL pin mapping that is parameter
  # specific.
  struct ParameterSiteTypeBelEntry {
    bel       @0 : StringIdx;
    siteType  @1 : StringIdx;
    parameter @2 : Dir.Netlist.PropertyMap.Entry;
  }

  struct ParameterCellBelPinMaps {
    parametersSiteTypes @0 : List(ParameterSiteTypeBelEntry);
    pins                @1 : List(CellBelPinEntry);
  }

  struct Package {
    struct PackagePin {
        packagePin @0 : StringIdx;
        site : union {
            noSite     @1 : Void;
            site       @2 : StringIdx;
        }
        bel : union {
            noBel      @3 : Void;
            bel        @4 : StringIdx;
        }
    }

    struct Grade {
        name             @0 : StringIdx;
        speedGrade       @1 : StringIdx;
        temperatureGrade @2 : StringIdx;
    }

    name        @0 : StringIdx;
    packagePins @1 : List(PackagePin);
    grades      @2 : List(Grade);
  }

  # Constants
  enum ConstantType {
      # Routing a VCC or GND are equal cost.
      noPreference @0;
      # Routing a GND has the best cost.
      gnd          @1;
      # Routing a VCC has the best cost.
      vcc          @2;
  }

  struct Constants {
    struct SitePinConstantExceptions {
        siteType     @0 : StringIdx;
        sitePin      @1 : StringIdx;
        bestConstant @2 : ConstantType;
    }

    struct SiteConstantSource {
        siteType     @0 : StringIdx;
        bel          @1 : StringIdx;
        belPin       @2 : StringIdx;
        constant     @3 : ConstantType;
    }

    # When either constant signal can be routed to an input site pin, which
    # constant should be used by default?
    #
    # For example, if a site pin has a local inverter and a cell requires a
    # constant signal, then either a gnd or vcc could be routed to the site.
    # The inverter can be used to select which ever constant is needed,
    # regardless of what constant the cell requires.  In some fabrics, routing
    # a VCC or routing a GND is significantly easier than the other.
    defaultBestConstant    @0 : ConstantType;

    # If there are exceptions to the default best constant, then this list
    # specifies which site pins use a different constant.
    bestConstantExceptions @1 : List(SitePinConstantExceptions);

    # List of constants that can be found within the routing graph without
    # consuming a BEL.
    #
    # Tools can always generate a constant source from a LUT BEL type.
    siteSources            @2 : List(SiteConstantSource);
  }
}
