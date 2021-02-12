# Copyright 2020-2021 Xilinx, Inc. and Google, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

@0x9d262c6ba6512325;
using Java = import "/capnp/java.capnp";
using Ref = import "References.capnp";
using Dir = import "LogicalNetlist.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("DeviceResources");

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

struct SiteTypeRef {
    type  @0 :Ref.ReferenceType = root;
    field @1 :Text = "siteTypeList";
}
annotation siteTypeRef(*) :SiteTypeRef;
using SiteTypeIdx = UInt32;

struct BELPinRef {
    type  @0 :Ref.ReferenceType = parent;
    field @1 :Text = "belPins";
    depth @2 :Int32 = 1;
}
annotation belPinRef(*) :BELPinRef;
using BELPinIdx = UInt32;

struct WireRef {
    type  @0 :Ref.ReferenceType = parent;
    field @1 :Text = "wires";
    depth @2 :Int32 = 1;
}
annotation wireRef(*) :WireRef;
using WireIdx = UInt32;

using WireIDInTileType = UInt32; # ID in Tile Type
using SitePinIdx = UInt32;

struct TileTypeRef {
    type  @0 :Ref.ReferenceType = parent;
    field @1 :Text = "tileTypeList";
    depth @2 :Int32 = 1;
}
annotation tileTypeRef(*) :TileTypeRef;
using TileTypeIdx = UInt32;

using TileTypeSiteTypeIdx = UInt32;

struct Device {

  name            @0 : Text;
  strList         @1 : List(Text) $hashSet();
  siteTypeList    @2 : List(SiteType);
  tileTypeList    @3 : List(TileType);
  tileList        @4 : List(Tile);
  wires           @5 : List(Wire);
  nodes           @6 : List(Node);
  primLibs        @7 : Dir.Netlist; # Netlist libraries of Unisim primitives and macros
  exceptionMap    @8 : List(PrimToMacroExpansion); # Prims to macros expand w/same name, except these
  cellBelMap      @9 : List(CellBelMapping);
  cellInversions @10 : List(CellInversion);
  packages       @11 : List(Package);
  constants      @12 : Constants;
  constraints    @13 : Constraints;

  #######################################
  # Placement definition objects
  #######################################
  struct SiteType {
    name         @0 : StringIdx $stringRef();
    belPins      @1 : List(BELPin); # All BEL Pins in site type
    pins         @2 : List(SitePin);
    lastInput    @3 : UInt32; # Index of the last input pin
    bels         @4 : List(BEL);
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
    primaryType @0 : SiteTypeIdx $siteTypeRef();

    # primaryPinsToTileWires[0] is the tile wire that matches
    # siteTypeList[primaryType].pins[0], etc.
    primaryPinsToTileWires @1 : List(StringIdx) $stringRef();

    # altPinsToPrimaryPins[0] is the mapping for
    # siteTypeList[primaryType].altSiteTypes[0], etc.
    altPinsToPrimaryPins @2 : List(ParentPins);
  }

  struct TileType {
    name       @0 : StringIdx $stringRef();
    siteTypes  @1 : List(SiteTypeInTileType);
    wires      @2 : List(StringIdx) $stringRef();
    pips       @3 : List(PIP);
    constants  @4 : List(WireConstantSources);
  }

  #######################################
  # Placement instance objects
  #######################################
  struct BELInverter {
    nonInvertingPin @0 : BELPinIdx $belPinRef(depth = 2);
    invertingPin    @1 : BELPinIdx $belPinRef(depth = 2);
  }

  struct BEL {
    name      @0 : StringIdx $stringRef();
    type      @1 : StringIdx $stringRef();
    pins      @2 : List(BELPinIdx) $belPinRef();
    category  @3 : BELCategory; # This would be BELClass/class, but conflicts with Java language
    union {
        nonInverting @4 : Void;
        inverting    @5 : BELInverter;
    }
  }

  enum BELCategory {
    logic    @0;
    routing  @1;
    sitePort @2;
  }

  struct Site {
    name      @0 : StringIdx $stringRef();
    type      @1 : TileTypeSiteTypeIdx; # Index into TileType.siteTypes
  }

  struct Tile {
    name       @0 : StringIdx $stringRef();
    type       @1 : TileTypeIdx $tileTypeRef();
    sites      @2 : List(Site);
    row        @3 : UInt16;
    col        @4 : UInt16;
    tilePatIdx @5 : UInt32;
  }

  ######################################
  # Intra-site routing resources
  ######################################
  struct BELPin {
    name   @0 : StringIdx $stringRef();
    dir    @1 : Dir.Netlist.Direction;
    bel    @2 : StringIdx $stringRef();
  }

  struct SiteWire {
    name   @0 : StringIdx $stringRef();
    pins   @1 : List(BELPinIdx) $belPinRef();
  }

  struct SitePIP {
    inpin  @0 : BELPinIdx $belPinRef();
    outpin @1 : BELPinIdx $belPinRef();
  }

  struct SitePin {
    name     @0 : StringIdx $stringRef();
    dir      @1 : Dir.Netlist.Direction;
    belpin   @2 : BELPinIdx $belPinRef();
  }

  ######################################
  # Inter-site routing resources
  ######################################
  struct Wire {
    tile      @0 : StringIdx $stringRef();
    wire      @1 : StringIdx $stringRef();
  }

  struct Node {
    wires    @0 : List(WireIdx) $wireRef();
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
    bel          @0 : StringIdx $stringRef();
    pins         @1 : List(StringIdx) $stringRef();
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
    primName  @0 : StringIdx $stringRef();
    macroName @1 : StringIdx $stringRef();
  }

  # Cell <-> BEL and Cell pin <-> BEL Pin mapping
  struct CellBelMapping {
    cell          @0 : StringIdx $stringRef();
    commonPins    @1 : List(CommonCellBelPinMaps);
    parameterPins @2 : List(ParameterCellBelPinMaps);
  }

  # Map one cell pin to one BEL pin.
  # Note: There may be more than one BEL pin mapped to one cell pin.
  struct CellBelPinEntry {
    cellPin @0 : StringIdx $stringRef();
    belPin  @1 : StringIdx $stringRef();
  }

  # Specifies BELs located in a specific site type.
  struct SiteTypeBelEntry {
    siteType @0 : StringIdx $stringRef();
    bels     @1 : List(StringIdx) $stringRef();
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
    bel       @0 : StringIdx $stringRef();
    siteType  @1 : StringIdx $stringRef();
    parameter @2 : Dir.Netlist.PropertyMap.Entry;
  }

  struct ParameterCellBelPinMaps {
    parametersSiteTypes @0 : List(ParameterSiteTypeBelEntry);
    pins                @1 : List(CellBelPinEntry);
  }

  struct Package {
    struct PackagePin {
        packagePin @0 : StringIdx $stringRef();
        site : union {
            noSite     @1 : Void;
            site       @2 : StringIdx $stringRef();
        }
        bel : union {
            noBel      @3 : Void;
            bel        @4 : StringIdx $stringRef();
        }
    }

    struct Grade {
        name             @0 : StringIdx $stringRef();
        speedGrade       @1 : StringIdx $stringRef();
        temperatureGrade @2 : StringIdx $stringRef();
    }

    name        @0 : StringIdx $stringRef();
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
        siteType     @0 : StringIdx $stringRef();
        sitePin      @1 : StringIdx $stringRef();
        bestConstant @2 : ConstantType;
    }

    struct SiteConstantSource {
        siteType     @0 : StringIdx $stringRef();
        bel          @1 : StringIdx $stringRef();
        belPin       @2 : StringIdx $stringRef();
        constant     @3 : ConstantType;
    }

    struct NodeConstantSource {
        tile     @0 : StringIdx $stringRef();
        wire     @1 : StringIdx $stringRef();
        constant @2 : ConstantType;
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

    # Most tied nodes are handled under TileType.constants, however in some
    # exceptional cases, the tying is inconsistent between tile types.
    # nodeSources should be used to explicitly list nodes that fall into this
    # case.
    nodeSources            @3 : List(NodeConstantSource);
  }

  ######################################
  # Inverting pins description
  #
  # This block describes local site wire
  # inverters, site routing BELs, and
  # parameters.
  ######################################
  struct CellPinInversionParameter {
    union {
      # This inverter cannot be controlled by parameter, only via tool merging
      # of INV cells or other means.
      invOnly      @0 : Void;
      # This inverter can be controlled by a parameter.
      # What parameter value configures this setting?
      parameter    @1 : Dir.Netlist.PropertyMap.Entry;
    }
  }

  struct CellPinInversion {
    # Which cell pin supports a local site inverter?
    cellPin      @0 : StringIdx $stringRef();

    # What parameters are used for the non-inverting case, and how to route
    # through the inversion routing bels (if any).
    notInverting @1 : CellPinInversionParameter;

    # What parameters are used for the inverting case, and how to route
    # through the inversion routing bels (if any).
    inverting    @2 : CellPinInversionParameter;
  }

  struct CellInversion {
    # Which cell is being described?
    cell     @0 : StringIdx $stringRef();

    # Which cell have site local inverters?
    cellPins @1 : List(CellPinInversion);
  }

  ######################################
  # Placement constraints
  #
  # This section defines constraints required for valid placement above and
  # beyond routing constraints.
  #
  # This section has three sections:
  #  - Tags
  #  - Routed tags
  #  - Cell constraints
  #
  # The tags sections defines a list of tags that take one of an exclusive set
  # of states.  Tags are attached to an object within the FPGA fabric.
  # Currently tags can only be attached to either a site type or tile type.
  # All instances of that site type or tile type will have it's own unique
  # instance of the tag.  In order for the constraints to be considered "met",
  # each tag instance must be constrained to either 0 or 1 states.  In the
  # event that a tag instance is constrained to 0 states, then that tag
  # instance value will be the "default" state.
  #
  # Routed tags are required to express instances where a cell constraint
  # relates through a site routing mux to a tag. Routed tags route from a tag
  # or routed tag to either another routed tag or a cell constraint.
  #
  # Cell constraints are the list of constraints that are applied (or removed)
  # when a cell is placed (or unplaced).  The format of the cell constraint
  # can be read as:
  #  - When a cell of a specific type is (cell/cells field)
  #  - Is placed at (siteTypes and bel field)
  #  - Apply the following constraints (implies/requires field).
  #
  # In the case where a cell constraint references a routed tag, then the
  # constraint also applies to the upstream tag or routed tag.
  ######################################
  struct Constraints {
    struct State {
      state       @0 :Text;
      description @1 :Text;
    }

    struct Tag {
      tag         @0 :Text;
      description @1 :Text;
      default     @2 :Text;
      union {
        siteTypes @3 :List(Text);
        tileTypes @4 :List(Text);
      }
      states      @5 :List(State);
    }

    struct RoutedTagPin {
      pin @0 :Text;
      tag @1 :Text;
    }

    struct RoutedTag {
      routedTag  @0 :Text;
      routingBel @1 :Text;
      belPins    @2 :List(RoutedTagPin);
    }

    struct RoutedTagPort {
      tag  @0 :Text;
      port @1 :Text;
    }

    struct TagPair {
      union {
        tag       @0 :Text;
        routedTag @1 :RoutedTagPort;
      }
      state       @2 :Text;
    }

    struct TagStates {
      union {
        tag       @0 :Text;
        routedTag @1 :RoutedTagPort;
      }
      states      @2 :List(Text);
    }

    struct BELLocation {
      union {
        anyBel @0 :Void;
        name   @1 :Text;
        bels   @2 :List(Text);
      }
    }

    struct ConstraintLocation {
      siteTypes  @0 :List(Text);
      bel        @1 :BELLocation;
      union {
        implies  @2 :List(TagPair);
        requires @3 :List(TagStates);
      }
    }

    struct CellConstraint {
      union {
        cell    @0 :Text;
        cells   @1 :List(Text);
      }
      locations @2 :List(ConstraintLocation);
    }

    tags            @0 :List(Tag);
    routedTags      @1 :List(RoutedTag);
    cellConstraints @2 :List(CellConstraint);
  }
}
