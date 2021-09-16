package com.xilinx.rapidwright.design;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.util.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test Module.getCorrespondingTile
 * The testcases in this class are auto-generated. The main function in this class is the entry point for that.
 */
public class TestRelocateTile {


    private static final String DEVICE_VERSAL = "xcvc1902";
    private static final String DEVICE_ULTRASCALE = "xcvu3p";

    private void testRelocateTile(Device device, String templateStr, String newAnchorStr, String oldAnchorStr, String expectedResultStr) {
        Tile template = device.getTile(templateStr);
        Tile newAnchor = device.getTile(newAnchorStr);
        Tile oldAnchor = device.getTile(oldAnchorStr);
        Tile expectedResult = device.getTile(expectedResultStr);
        Tile actual = Module.getCorrespondingTile(template, newAnchor, oldAnchor);
        Assertions.assertSame(expectedResult, actual, () -> "Relocating " + template + " with anchor move from " + oldAnchor + " to " + newAnchor);
    }


    @ParameterizedTest(name="Relocate {0} with anchor from {2} to {1}")
    @MethodSource()
    public void testUltrascale(String template, String newAnchor, String oldAnchor, String expectedResult) {
        Device device = Device.getDevice(DEVICE_ULTRASCALE);
        testRelocateTile(device, template, newAnchor, oldAnchor, expectedResult);
    }


    public static Stream<Arguments> testUltrascale() {

        return Stream.of(
                Arguments.of("BRAM_X38Y175", "BRAM_X32Y270", "BRAM_X10Y65", null),
                Arguments.of("BRAM_X38Y65", "BRAM_X74Y145", "BRAM_X55Y45", null),
                Arguments.of("BRAM_X68Y135", "BRAM_X89Y270", "BRAM_X68Y145", "BRAM_X89Y260"),
                Arguments.of("BRAM_X32Y200", "BRAM_X74Y280", "BRAM_X53Y290", "BRAM_X53Y190"),
                Arguments.of("BRAM_X32Y220", "BRAM_X68Y25", "BRAM_X98Y15", null),
                Arguments.of("CLEL_R_X38Y226", "BRAM_X98Y15", "BRAM_X53Y70", "CLEL_R_X83Y171"),
                Arguments.of("CLEL_R_X66Y247", "BRAM_X102Y0", "BRAM_X89Y0", "CLEL_R_X79Y247"),
                Arguments.of("CLEL_R_X73Y171", "BRAM_X32Y0", "BRAM_X19Y75", "CLEL_R_X86Y96"),
                Arguments.of("CLEL_R_X70Y285", "BRAM_X53Y0", "BRAM_X68Y260", "CLEL_R_X55Y25"),
                Arguments.of("CLEL_R_X71Y298", "BRAM_X68Y160", "BRAM_X68Y240", "CLEL_R_X71Y218"),
                Arguments.of("DSP_X14Y70", "BRAM_X19Y160", "BRAM_X98Y220", null),
                Arguments.of("DSP_X69Y5", "BRAM_X38Y85", "BRAM_X38Y45", "DSP_X69Y45"),
                Arguments.of("DSP_X96Y140", "BRAM_X53Y165", "BRAM_X74Y225", "DSP_X75Y80"),
                Arguments.of("DSP_X105Y150", "BRAM_X19Y270", "BRAM_X68Y260", "DSP_X56Y160"),
                Arguments.of("DSP_X69Y245", "BRAM_X10Y40", "BRAM_X10Y95", "DSP_X69Y190"),
                Arguments.of("CLEM_X24Y69", "BRAM_X53Y265", "BRAM_X19Y155", "CLEM_X58Y179"),
                Arguments.of("CLEM_X40Y105", "BRAM_X102Y120", "BRAM_X102Y140", "CLEM_X40Y85"),
                Arguments.of("CLEM_X65Y215", "BRAM_X98Y100", "BRAM_X68Y105", "CLEM_X95Y210"),
                Arguments.of("CLEM_X65Y210", "BRAM_X32Y75", "BRAM_X6Y170", "CLEM_X91Y115"),
                Arguments.of("CLEM_X92Y19", "BRAM_X19Y125", "BRAM_X74Y85", "CLEM_X37Y59"),
                Arguments.of("CMT_L_X72Y0", "BRAM_X89Y25", "BRAM_X102Y30", null),
                Arguments.of("CMT_L_X72Y180", "BRAM_X68Y145", "BRAM_X89Y280", null),
                Arguments.of("CMT_L_X72Y120", "BRAM_X55Y65", "BRAM_X53Y90", null),
                Arguments.of("CMT_L_X72Y0", "BRAM_X32Y245", "BRAM_X19Y200", null),
                Arguments.of("CMT_L_X36Y60", "BRAM_X10Y205", "BRAM_X68Y20", null),
                Arguments.of("BRAM_X68Y240", "BRAM_X32Y170", "BRAM_X32Y145", "BRAM_X68Y265"),
                Arguments.of("BRAM_X19Y65", "BRAM_X32Y150", "BRAM_X32Y155", "BRAM_X19Y60"),
                Arguments.of("BRAM_X55Y65", "BRAM_X19Y40", "BRAM_X6Y80", "BRAM_X68Y25"),
                Arguments.of("BRAM_X98Y170", "BRAM_X32Y225", "BRAM_X32Y210", "BRAM_X98Y185"),
                Arguments.of("BRAM_X53Y245", "BRAM_X38Y105", "BRAM_X38Y255", "BRAM_X53Y95"),
                Arguments.of("BRAM_X74Y140", "BRAM_X98Y265", "BRAM_X74Y225", "BRAM_X98Y180"),
                Arguments.of("BRAM_X68Y195", "BRAM_X89Y95", "BRAM_X68Y215", "BRAM_X89Y75"),
                Arguments.of("BRAM_X6Y140", "BRAM_X32Y100", "BRAM_X19Y0", "BRAM_X19Y240"),
                Arguments.of("BRAM_X19Y85", "BRAM_X89Y250", "BRAM_X55Y185", "BRAM_X53Y150"),
                Arguments.of("BRAM_X38Y260", "BRAM_X68Y190", "BRAM_X38Y230", "BRAM_X68Y220"),
                Arguments.of("LAG_LAG_X12Y57", "BRAM_X102Y90", "BRAM_X74Y90", "LAG_LAG_X40Y57"),
                Arguments.of("LAG_LAG_X40Y59", "BRAM_X98Y270", "BRAM_X32Y0", null),
                Arguments.of("LAG_LAG_X85Y25", "BRAM_X32Y100", "BRAM_X102Y90", null),
                Arguments.of("LAG_LAG_X85Y270", "BRAM_X38Y230", "BRAM_X32Y65", null),
                Arguments.of("LAG_LAG_X85Y249", "BRAM_X68Y270", "BRAM_X98Y115", null),
                Arguments.of("BRAM_X10Y0", "CLEL_R_X65Y61", "CLEL_R_X49Y231", null),
                Arguments.of("BRAM_X19Y265", "CLEL_R_X22Y136", "CLEL_R_X12Y295", null),
                Arguments.of("BRAM_X55Y160", "CLEL_R_X41Y263", "CLEL_R_X22Y89", null),
                Arguments.of("BRAM_X74Y5", "CLEL_R_X3Y154", "CLEL_R_X98Y234", null),
                Arguments.of("BRAM_X32Y85", "CLEL_R_X93Y121", "CLEL_R_X49Y16", null),
                Arguments.of("CLEL_R_X52Y256", "CLEL_R_X63Y147", "CLEL_R_X22Y221", "CLEL_R_X93Y182"),
                Arguments.of("CLEL_R_X48Y32", "CLEL_R_X32Y224", "CLEL_R_X8Y242", "CLEL_R_X72Y14"),
                Arguments.of("CLEL_R_X46Y155", "CLEL_R_X66Y238", "CLEL_R_X9Y186", "CLEL_R_X103Y207"),
                Arguments.of("CLEL_R_X18Y101", "CLEL_R_X46Y201", "CLEL_R_X53Y53", null),
                Arguments.of("CLEL_R_X30Y95", "CLEL_R_X79Y172", "CLEL_R_X66Y103", "CLEL_R_X43Y164"),
                Arguments.of("DSP_X51Y270", "CLEL_R_X5Y59", "CLEL_R_X70Y219", null),
                Arguments.of("DSP_X87Y200", "CLEL_R_X102Y115", "CLEL_R_X19Y249", null),
                Arguments.of("DSP_X87Y70", "CLEL_R_X62Y295", "CLEL_R_X89Y227", null),
                Arguments.of("DSP_X75Y285", "CLEL_R_X53Y260", "CLEL_R_X27Y259", null),
                Arguments.of("DSP_X20Y45", "CLEL_R_X95Y117", "CLEL_R_X79Y30", null),
                Arguments.of("CLEM_X73Y33", "CLEL_R_X38Y103", "CLEL_R_X22Y61", null),
                Arguments.of("CLEM_X46Y238", "CLEL_R_X40Y65", "CLEL_R_X37Y30", "CLEM_X49Y273"),
                Arguments.of("CLEM_X23Y221", "CLEL_R_X71Y18", "CLEL_R_X50Y179", "CLEM_X44Y60"),
                Arguments.of("CLEM_X35Y71", "CLEL_R_X97Y137", "CLEL_R_X52Y88", "CLEM_X80Y120"),
                Arguments.of("CLEM_X92Y216", "CLEL_R_X22Y199", "CLEL_R_X17Y138", "CLEM_X97Y277"),
                Arguments.of("CMT_L_X72Y120", "CLEL_R_X79Y214", "CLEL_R_X34Y221", null),
                Arguments.of("CMT_L_X36Y60", "CLEL_R_X62Y159", "CLEL_R_X2Y273", null),
                Arguments.of("CMT_L_X72Y180", "CLEL_R_X81Y170", "CLEL_R_X79Y126", null),
                Arguments.of("CMT_L_X36Y240", "CLEL_R_X92Y197", "CLEL_R_X19Y179", null),
                Arguments.of("CMT_L_X36Y120", "CLEL_R_X93Y205", "CLEL_R_X67Y179", null),
                Arguments.of("BRAM_X53Y35", "CLEL_R_X21Y167", "CLEL_R_X40Y251", null),
                Arguments.of("BRAM_X55Y145", "CLEL_R_X31Y13", "CLEL_R_X60Y163", null),
                Arguments.of("BRAM_X32Y75", "CLEL_R_X106Y294", "CLEL_R_X35Y180", null),
                Arguments.of("BRAM_X19Y150", "CLEL_R_X70Y42", "CLEL_R_X82Y272", null),
                Arguments.of("BRAM_X68Y265", "CLEL_R_X19Y51", "CLEL_R_X97Y3", null),
                Arguments.of("BRAM_X98Y40", "CLEL_R_X62Y293", "CLEL_R_X74Y33", null),
                Arguments.of("BRAM_X6Y50", "CLEL_R_X89Y72", "CLEL_R_X53Y282", null),
                Arguments.of("BRAM_X74Y90", "CLEL_R_X21Y273", "CLEL_R_X74Y186", null),
                Arguments.of("BRAM_X53Y85", "CLEL_R_X98Y151", "CLEL_R_X62Y1", "BRAM_X89Y235"),
                Arguments.of("BRAM_X98Y135", "CLEL_R_X5Y127", "CLEL_R_X15Y122", null),
                Arguments.of("LAG_LAG_X4Y8", "CLEL_R_X13Y218", "CLEL_R_X3Y158", null),
                Arguments.of("LAG_LAG_X30Y52", "CLEL_R_X37Y123", "CLEL_R_X83Y189", null),
                Arguments.of("LAG_LAG_X102Y45", "CLEL_R_X24Y55", "CLEL_R_X53Y99", null),
                Arguments.of("LAG_LAG_X30Y13", "CLEL_R_X55Y126", "CLEL_R_X81Y138", "LAG_LAG_X4Y1"),
                Arguments.of("LAG_LAG_X66Y244", "CLEL_R_X103Y32", "CLEL_R_X4Y190", null),
                Arguments.of("BRAM_X55Y135", "DSP_X16Y75", "DSP_X33Y170", "BRAM_X38Y40"),
                Arguments.of("BRAM_X38Y130", "DSP_X16Y35", "DSP_X20Y95", null),
                Arguments.of("BRAM_X98Y60", "DSP_X96Y195", "DSP_X96Y215", "BRAM_X98Y40"),
                Arguments.of("BRAM_X19Y125", "DSP_X64Y100", "DSP_X51Y165", "BRAM_X32Y60"),
                Arguments.of("BRAM_X53Y110", "DSP_X105Y165", "DSP_X1Y85", null),
                Arguments.of("CLEL_R_X29Y176", "DSP_X96Y165", "DSP_X39Y120", "CLEL_R_X86Y221"),
                Arguments.of("CLEL_R_X41Y180", "DSP_X28Y145", "DSP_X20Y280", "CLEL_R_X49Y45"),
                Arguments.of("CLEL_R_X30Y62", "DSP_X23Y165", "DSP_X16Y85", "CLEL_R_X37Y142"),
                Arguments.of("CLEL_R_X52Y111", "DSP_X56Y185", "DSP_X59Y95", "CLEL_R_X49Y201"),
                Arguments.of("CLEL_R_X10Y287", "DSP_X90Y180", "DSP_X33Y200", "CLEL_R_X67Y267"),
                Arguments.of("DSP_X90Y110", "DSP_X105Y220", "DSP_X59Y290", null),
                Arguments.of("DSP_X33Y280", "DSP_X11Y230", "DSP_X16Y270", "DSP_X28Y240"),
                Arguments.of("DSP_X87Y135", "DSP_X11Y25", "DSP_X59Y50", "DSP_X39Y110"),
                Arguments.of("DSP_X96Y135", "DSP_X87Y200", "DSP_X96Y295", "DSP_X87Y40"),
                Arguments.of("DSP_X11Y80", "DSP_X28Y250", "DSP_X28Y270", "DSP_X11Y60"),
                Arguments.of("CLEM_X97Y17", "DSP_X75Y255", "DSP_X105Y125", "CLEM_X67Y147"),
                Arguments.of("CLEM_X58Y108", "DSP_X16Y130", "DSP_X16Y25", "CLEM_X58Y213"),
                Arguments.of("CLEM_X88Y142", "DSP_X16Y185", "DSP_X16Y110", "CLEM_X88Y217"),
                Arguments.of("CLEM_X94Y268", "DSP_X16Y30", "DSP_X75Y110", "CLEM_X35Y188"),
                Arguments.of("CLEM_X71Y40", "DSP_X39Y220", "DSP_X51Y75", "CLEM_X59Y185"),
                Arguments.of("CMT_L_X72Y0", "DSP_X96Y295", "DSP_X56Y245", null),
                Arguments.of("CMT_L_X36Y60", "DSP_X69Y110", "DSP_X11Y35", null),
                Arguments.of("CMT_L_X36Y60", "DSP_X33Y55", "DSP_X75Y10", null),
                Arguments.of("CMT_L_X72Y60", "DSP_X28Y245", "DSP_X33Y290", null),
                Arguments.of("CMT_L_X36Y0", "DSP_X39Y215", "DSP_X1Y270", null),
                Arguments.of("BRAM_X68Y20", "DSP_X69Y210", "DSP_X39Y220", "BRAM_X98Y10"),
                Arguments.of("BRAM_X55Y10", "DSP_X16Y70", "DSP_X33Y20", "BRAM_X38Y60"),
                Arguments.of("BRAM_X102Y290", "DSP_X64Y80", "DSP_X33Y275", null),
                Arguments.of("BRAM_X10Y75", "DSP_X87Y65", "DSP_X59Y95", "BRAM_X38Y45"),
                Arguments.of("BRAM_X38Y10", "DSP_X69Y50", "DSP_X75Y250", null),
                Arguments.of("BRAM_X102Y25", "DSP_X20Y275", "DSP_X16Y140", null),
                Arguments.of("BRAM_X19Y260", "DSP_X1Y155", "DSP_X14Y285", "BRAM_X6Y130"),
                Arguments.of("BRAM_X74Y175", "DSP_X64Y35", "DSP_X28Y285", null),
                Arguments.of("BRAM_X68Y155", "DSP_X39Y120", "DSP_X75Y270", "BRAM_X32Y5"),
                Arguments.of("BRAM_X102Y295", "DSP_X23Y20", "DSP_X23Y210", "BRAM_X102Y105"),
                Arguments.of("LAG_LAG_X85Y294", "DSP_X75Y180", "DSP_X87Y155", null),
                Arguments.of("LAG_LAG_X40Y9", "DSP_X64Y170", "DSP_X105Y75", null),
                Arguments.of("LAG_LAG_X40Y57", "DSP_X56Y280", "DSP_X39Y45", "LAG_LAG_X57Y292"),
                Arguments.of("LAG_LAG_X94Y17", "DSP_X96Y0", "DSP_X51Y20", null),
                Arguments.of("LAG_LAG_X102Y243", "DSP_X28Y260", "DSP_X1Y260", null),
                Arguments.of("BRAM_X74Y125", "CLEM_X60Y239", "CLEM_X14Y98", null),
                Arguments.of("BRAM_X68Y220", "CLEM_X60Y125", "CLEM_X58Y191", null),
                Arguments.of("BRAM_X6Y135", "CLEM_X18Y200", "CLEM_X11Y250", null),
                Arguments.of("BRAM_X53Y50", "CLEM_X69Y192", "CLEM_X54Y147", "BRAM_X68Y95"),
                Arguments.of("BRAM_X102Y65", "CLEM_X86Y121", "CLEM_X47Y134", null),
                Arguments.of("CLEL_R_X92Y19", "CLEM_X20Y77", "CLEM_X95Y77", "CLEL_R_X17Y19"),
                Arguments.of("CLEL_R_X9Y287", "CLEM_X60Y124", "CLEM_X22Y125", "CLEL_R_X47Y286"),
                Arguments.of("CLEL_R_X10Y251", "CLEM_X97Y129", "CLEM_X83Y271", "CLEL_R_X24Y109"),
                Arguments.of("CLEL_R_X83Y206", "CLEM_X78Y217", "CLEM_X96Y269", "CLEL_R_X65Y154"),
                Arguments.of("CLEL_R_X50Y112", "CLEM_X14Y46", "CLEM_X59Y185", null),
                Arguments.of("DSP_X20Y145", "CLEM_X73Y91", "CLEM_X61Y201", null),
                Arguments.of("DSP_X96Y50", "CLEM_X67Y108", "CLEM_R_X106Y278", null),
                Arguments.of("DSP_X105Y195", "CLEM_X92Y198", "CLEM_X12Y246", null),
                Arguments.of("DSP_X69Y45", "CLEM_X47Y68", "CLEM_X9Y173", null),
                Arguments.of("DSP_X51Y240", "CLEM_X78Y224", "CLEM_X61Y297", null),
                Arguments.of("CLEM_X73Y228", "CLEM_X24Y230", "CLEM_X31Y166", "CLEM_X66Y292"),
                Arguments.of("CLEM_X39Y190", "CLEM_X91Y11", "CLEM_X73Y13", "CLEM_X57Y188"),
                Arguments.of("CLEM_X82Y80", "CLEM_X33Y222", "CLEM_X18Y294", "CLEM_X97Y8"),
                Arguments.of("CLEM_X88Y241", "CLEM_X3Y178", "CLEM_X70Y241", "CLEM_X21Y178"),
                Arguments.of("CLEM_X82Y271", "CLEM_X25Y190", "CLEM_X60Y100", null),
                Arguments.of("CMT_L_X72Y60", "CLEM_R_X101Y80", "CLEM_X4Y143", null),
                Arguments.of("CMT_L_X36Y120", "CLEM_X51Y271", "CLEM_X44Y35", null),
                Arguments.of("CMT_L_X72Y60", "CLEM_X52Y231", "CLEM_X14Y118", null),
                Arguments.of("CMT_L_X72Y60", "CLEM_X40Y141", "CLEM_X15Y194", null),
                Arguments.of("CMT_L_X36Y180", "CLEM_X96Y204", "CLEM_R_X107Y41", null),
                Arguments.of("BRAM_X6Y200", "CLEM_X69Y108", "CLEM_X37Y68", "BRAM_X38Y240"),
                Arguments.of("BRAM_X6Y205", "CLEM_X80Y188", "CLEM_X37Y152", null),
                Arguments.of("BRAM_X19Y220", "CLEM_X84Y171", "CLEM_X42Y269", null),
                Arguments.of("BRAM_X19Y195", "CLEM_X94Y289", "CLEM_X34Y75", null),
                Arguments.of("BRAM_X32Y125", "CLEM_X45Y122", "CLEM_X65Y232", null),
                Arguments.of("BRAM_X6Y235", "CLEM_X2Y44", "CLEM_X11Y111", null),
                Arguments.of("BRAM_X55Y40", "CLEM_X3Y237", "CLEM_X22Y64", null),
                Arguments.of("BRAM_X53Y50", "CLEM_X44Y179", "CLEM_X45Y47", null),
                Arguments.of("BRAM_X68Y170", "CLEM_R_X103Y118", "CLEM_X90Y228", null),
                Arguments.of("BRAM_X102Y45", "CLEM_X95Y170", "CLEM_X97Y58", null),
                Arguments.of("LAG_LAG_X76Y278", "CLEM_X30Y122", "CLEM_X65Y96", null),
                Arguments.of("LAG_LAG_X102Y265", "CLEM_X14Y135", "CLEM_X50Y66", null),
                Arguments.of("LAG_LAG_X40Y39", "CLEM_X51Y299", "CLEM_R_X107Y23", null),
                Arguments.of("LAG_LAG_X21Y3", "CLEM_X42Y264", "CLEM_X67Y239", null),
                Arguments.of("LAG_LAG_X21Y262", "CLEM_X84Y163", "CLEM_X56Y127", "LAG_LAG_X49Y298"),
                Arguments.of("BRAM_X6Y35", "CMT_L_X36Y240", "CMT_L_X36Y180", "BRAM_X6Y95"),
                Arguments.of("BRAM_X19Y80", "CMT_L_X36Y0", "CMT_L_X36Y0", "BRAM_X19Y80"),
                Arguments.of("BRAM_X55Y80", "CMT_L_X72Y0", "CMT_L_X72Y240", null),
                Arguments.of("BRAM_X6Y105", "CMT_L_X36Y240", "CMT_L_X36Y180", "BRAM_X6Y165"),
                Arguments.of("BRAM_X53Y35", "CMT_L_X36Y180", "CMT_L_X36Y120", "BRAM_X53Y95"),
                Arguments.of("CLEL_R_X62Y159", "CMT_L_X36Y180", "CMT_L_X72Y180", "CLEL_R_X26Y159"),
                Arguments.of("CLEL_R_X46Y85", "CMT_L_X36Y240", "CMT_L_X72Y240", "CLEL_R_X10Y85"),
                Arguments.of("CLEL_R_X18Y222", "CMT_L_X72Y60", "CMT_L_X72Y60", "CLEL_R_X18Y222"),
                Arguments.of("CLEL_R_X30Y87", "CMT_L_X72Y60", "CMT_L_X36Y60", "CLEL_R_X66Y87"),
                Arguments.of("CLEL_R_X86Y52", "CMT_L_X36Y120", "CMT_L_X36Y60", "CLEL_R_X86Y112"),
                Arguments.of("DSP_X56Y255", "CMT_L_X36Y120", "CMT_L_X36Y120", "DSP_X56Y255"),
                Arguments.of("DSP_X33Y85", "CMT_L_X72Y60", "CMT_L_X72Y0", "DSP_X33Y145"),
                Arguments.of("DSP_X39Y175", "CMT_L_X72Y240", "CMT_L_X72Y240", "DSP_X39Y175"),
                Arguments.of("DSP_X16Y220", "CMT_L_X72Y120", "CMT_L_X36Y0", null),
                Arguments.of("DSP_X87Y35", "CMT_L_X36Y180", "CMT_L_X36Y180", "DSP_X87Y35"),
                Arguments.of("CLEM_X60Y212", "CMT_L_X72Y180", "CMT_L_X72Y120", "CLEM_X60Y272"),
                Arguments.of("CLEM_X2Y109", "CMT_L_X36Y60", "CMT_L_X36Y0", "CLEM_X2Y169"),
                Arguments.of("CLEM_X13Y98", "CMT_L_X72Y180", "CMT_L_X72Y60", "CLEM_X13Y218"),
                Arguments.of("CLEM_X48Y146", "CMT_L_X36Y60", "CMT_L_X72Y180", "CLEM_X12Y26"),
                Arguments.of("CLEM_X66Y79", "CMT_L_X72Y0", "CMT_L_X72Y60", "CLEM_X66Y19"),
                Arguments.of("CMT_L_X36Y180", "CMT_L_X72Y60", "CMT_L_X72Y120", "CMT_L_X36Y120"),
                Arguments.of("CMT_L_X36Y0", "CMT_L_X36Y180", "CMT_L_X36Y60", "CMT_L_X36Y120"),
                Arguments.of("CMT_L_X36Y0", "CMT_L_X72Y120", "CMT_L_X36Y0", "CMT_L_X72Y120"),
                Arguments.of("CMT_L_X72Y180", "CMT_L_X36Y0", "CMT_L_X72Y0", "CMT_L_X36Y180"),
                Arguments.of("CMT_L_X36Y120", "CMT_L_X72Y60", "CMT_L_X72Y120", "CMT_L_X36Y60"),
                Arguments.of("BRAM_X55Y135", "CMT_L_X36Y0", "CMT_L_X72Y60", "BRAM_X19Y75"),
                Arguments.of("BRAM_X38Y145", "CMT_L_X72Y120", "CMT_L_X72Y180", "BRAM_X38Y85"),
                Arguments.of("BRAM_X19Y35", "CMT_L_X36Y0", "CMT_L_X36Y0", "BRAM_X19Y35"),
                Arguments.of("BRAM_X53Y170", "CMT_L_X72Y120", "CMT_L_X36Y0", "BRAM_X89Y290"),
                Arguments.of("BRAM_X38Y270", "CMT_L_X36Y60", "CMT_L_X36Y60", "BRAM_X38Y270"),
                Arguments.of("BRAM_X68Y35", "CMT_L_X36Y240", "CMT_L_X36Y0", "BRAM_X68Y275"),
                Arguments.of("BRAM_X38Y295", "CMT_L_X72Y60", "CMT_L_X72Y60", "BRAM_X38Y295"),
                Arguments.of("BRAM_X89Y90", "CMT_L_X36Y60", "CMT_L_X36Y60", "BRAM_X89Y90"),
                Arguments.of("BRAM_X89Y190", "CMT_L_X72Y180", "CMT_L_X72Y240", "BRAM_X89Y130"),
                Arguments.of("BRAM_X68Y110", "CMT_L_X72Y240", "CMT_L_X72Y180", "BRAM_X68Y170"),
                Arguments.of("LAG_LAG_X85Y32", "CMT_L_X36Y120", "CMT_L_X72Y120", "LAG_LAG_X49Y32"),
                Arguments.of("LAG_LAG_X102Y255", "CMT_L_X36Y60", "CMT_L_X36Y60", "LAG_LAG_X102Y255"),
                Arguments.of("LAG_LAG_X49Y58", "CMT_L_X36Y120", "CMT_L_X36Y120", "LAG_LAG_X49Y58"),
                Arguments.of("LAG_LAG_X57Y266", "CMT_L_X36Y240", "CMT_L_X72Y240", "LAG_LAG_X21Y266"),
                Arguments.of("LAG_LAG_X66Y268", "CMT_L_X72Y60", "CMT_L_X36Y240", null),
                Arguments.of("BRAM_X10Y205", "BRAM_X74Y100", "BRAM_X74Y40", "BRAM_X10Y265"),
                Arguments.of("BRAM_X74Y20", "BRAM_X89Y165", "BRAM_X53Y220", null),
                Arguments.of("BRAM_X6Y125", "BRAM_X53Y215", "BRAM_X38Y150", null),
                Arguments.of("BRAM_X102Y180", "BRAM_X55Y175", "BRAM_X102Y125", "BRAM_X55Y230"),
                Arguments.of("BRAM_X38Y50", "BRAM_X55Y85", "BRAM_X19Y115", "BRAM_X74Y20"),
                Arguments.of("CLEL_R_X81Y228", "BRAM_X98Y80", "BRAM_X98Y180", "CLEL_R_X81Y128"),
                Arguments.of("CLEL_R_X30Y95", "BRAM_X98Y235", "BRAM_X68Y215", "CLEL_R_X60Y115"),
                Arguments.of("CLEL_R_X73Y55", "BRAM_X6Y255", "BRAM_X74Y75", "CLEL_R_X5Y235"),
                Arguments.of("CLEL_R_X15Y234", "BRAM_X89Y210", "BRAM_X68Y180", "CLEL_R_X36Y264"),
                Arguments.of("CLEL_R_X102Y257", "BRAM_X55Y30", "BRAM_X68Y5", "CLEL_R_X89Y282"),
                Arguments.of("DSP_X96Y35", "BRAM_X98Y225", "BRAM_X98Y165", "DSP_X96Y95"),
                Arguments.of("DSP_X90Y105", "BRAM_X53Y145", "BRAM_X10Y80", null),
                Arguments.of("DSP_X75Y135", "BRAM_X98Y240", "BRAM_X98Y285", "DSP_X75Y90"),
                Arguments.of("DSP_X75Y105", "BRAM_X74Y150", "BRAM_X53Y215", "DSP_X96Y40"),
                Arguments.of("DSP_X56Y280", "BRAM_X32Y25", "BRAM_X74Y285", "DSP_X14Y20"),
                Arguments.of("CLEM_X57Y164", "BRAM_X19Y105", "BRAM_X6Y70", "CLEM_X70Y199"),
                Arguments.of("CLEM_X56Y180", "BRAM_X53Y245", "BRAM_X38Y150", "CLEM_X71Y275"),
                Arguments.of("CLEM_X97Y190", "BRAM_X6Y0", "BRAM_X10Y75", "CLEM_X93Y115"),
                Arguments.of("CLEM_X2Y280", "BRAM_X55Y10", "BRAM_X10Y200", "CLEM_X47Y90"),
                Arguments.of("CLEM_X60Y269", "BRAM_X53Y195", "BRAM_X102Y180", "CLEM_X11Y284"),
                Arguments.of("CMT_L_X72Y60", "BRAM_X74Y35", "BRAM_X32Y235", null),
                Arguments.of("CMT_L_X36Y180", "BRAM_X89Y220", "BRAM_X53Y65", null),
                Arguments.of("CMT_L_X36Y120", "BRAM_X32Y135", "BRAM_X32Y255", "CMT_L_X36Y0"),
                Arguments.of("CMT_L_X72Y60", "BRAM_X53Y255", "BRAM_X53Y255", "CMT_L_X72Y60"),
                Arguments.of("CMT_L_X36Y180", "BRAM_X68Y105", "BRAM_X53Y185", null),
                Arguments.of("BRAM_X102Y190", "BRAM_X10Y80", "BRAM_X74Y115", "BRAM_X38Y155"),
                Arguments.of("BRAM_X19Y270", "BRAM_X74Y0", "BRAM_X19Y85", "BRAM_X74Y185"),
                Arguments.of("BRAM_X32Y120", "BRAM_X38Y270", "BRAM_X32Y35", null),
                Arguments.of("BRAM_X32Y35", "BRAM_X102Y40", "BRAM_X55Y130", null),
                Arguments.of("BRAM_X89Y70", "BRAM_X68Y160", "BRAM_X89Y120", "BRAM_X68Y110"),
                Arguments.of("BRAM_X74Y15", "BRAM_X102Y155", "BRAM_X74Y165", "BRAM_X102Y5"),
                Arguments.of("BRAM_X74Y250", "BRAM_X102Y165", "BRAM_X102Y245", "BRAM_X74Y170"),
                Arguments.of("BRAM_X38Y230", "BRAM_X53Y15", "BRAM_X38Y245", "BRAM_X53Y0"),
                Arguments.of("BRAM_X19Y130", "BRAM_X89Y55", "BRAM_X55Y150", "BRAM_X53Y35"),
                Arguments.of("BRAM_X19Y240", "BRAM_X74Y65", "BRAM_X38Y225", "BRAM_X55Y80"),
                Arguments.of("LAG_LAG_X4Y274", "BRAM_X55Y45", "BRAM_X6Y125", null),
                Arguments.of("LAG_LAG_X4Y25", "BRAM_X10Y290", "BRAM_X102Y220", null),
                Arguments.of("LAG_LAG_X66Y23", "BRAM_X68Y290", "BRAM_X32Y270", "LAG_LAG_X102Y43"),
                Arguments.of("LAG_LAG_X12Y287", "BRAM_X55Y25", "BRAM_X10Y20", "LAG_LAG_X57Y292"),
                Arguments.of("LAG_LAG_X49Y272", "BRAM_X38Y280", "BRAM_X102Y180", null),
                Arguments.of("BRAM_X19Y70", "BRAM_X89Y270", "BRAM_X6Y50", "BRAM_X102Y290"),
                Arguments.of("BRAM_X55Y75", "BRAM_X53Y260", "BRAM_X6Y70", "BRAM_X102Y265"),
                Arguments.of("BRAM_X89Y75", "BRAM_X74Y65", "BRAM_X89Y20", "BRAM_X74Y120"),
                Arguments.of("BRAM_X32Y80", "BRAM_X6Y215", "BRAM_X19Y40", "BRAM_X19Y255"),
                Arguments.of("BRAM_X89Y90", "BRAM_X68Y70", "BRAM_X55Y75", "BRAM_X102Y85"),
                Arguments.of("CLEL_R_X15Y104", "BRAM_X89Y290", "BRAM_X89Y225", "CLEL_R_X15Y169"),
                Arguments.of("CLEL_R_X76Y148", "BRAM_X19Y80", "BRAM_X89Y175", "CLEL_R_X6Y53"),
                Arguments.of("CLEL_R_X67Y299", "BRAM_X32Y5", "BRAM_X19Y150", "CLEL_R_X80Y154"),
                Arguments.of("CLEL_R_X79Y293", "BRAM_X6Y5", "BRAM_X55Y35", "CLEL_R_X30Y263"),
                Arguments.of("CLEL_R_X22Y128", "BRAM_X98Y90", "BRAM_X55Y95", "CLEL_R_X65Y123"),
                Arguments.of("DSP_X16Y70", "BRAM_X10Y290", "BRAM_X6Y210", "DSP_X20Y150"),
                Arguments.of("DSP_X90Y155", "BRAM_X6Y240", "BRAM_X32Y65", null),
                Arguments.of("DSP_X59Y280", "BRAM_X38Y195", "BRAM_X10Y275", "DSP_X87Y200"),
                Arguments.of("DSP_X56Y105", "BRAM_X74Y220", "BRAM_X55Y35", "DSP_X75Y290"),
                Arguments.of("DSP_X87Y285", "BRAM_X38Y95", "BRAM_X38Y125", "DSP_X87Y255"),
                Arguments.of("CLEM_X70Y175", "BRAM_X10Y50", "BRAM_X38Y160", "CLEM_X42Y65"),
                Arguments.of("CLEM_X9Y181", "BRAM_X89Y70", "BRAM_X53Y270", null),
                Arguments.of("CLEM_X33Y127", "BRAM_X6Y230", "BRAM_X10Y120", "CLEM_X29Y237"),
                Arguments.of("CLEM_X49Y60", "BRAM_X10Y260", "BRAM_X19Y45", "CLEM_X40Y275"),
                Arguments.of("CLEM_X46Y203", "BRAM_X19Y250", "BRAM_X19Y270", "CLEM_X46Y183"),
                Arguments.of("CMT_L_X72Y240", "BRAM_X89Y135", "BRAM_X32Y210", null),
                Arguments.of("CMT_L_X72Y120", "BRAM_X74Y185", "BRAM_X38Y120", null),
                Arguments.of("CMT_L_X72Y60", "BRAM_X38Y0", "BRAM_X10Y190", null),
                Arguments.of("CMT_L_X36Y60", "BRAM_X38Y170", "BRAM_X98Y65", null),
                Arguments.of("CMT_L_X72Y180", "BRAM_X98Y190", "BRAM_X89Y150", null),
                Arguments.of("BRAM_X6Y195", "BRAM_X89Y135", "BRAM_X89Y240", "BRAM_X6Y90"),
                Arguments.of("BRAM_X55Y200", "BRAM_X68Y160", "BRAM_X68Y225", "BRAM_X55Y135"),
                Arguments.of("BRAM_X89Y245", "BRAM_X68Y130", "BRAM_X38Y115", null),
                Arguments.of("BRAM_X53Y225", "BRAM_X55Y45", "BRAM_X89Y270", "BRAM_X19Y0"),
                Arguments.of("BRAM_X89Y45", "BRAM_X38Y205", "BRAM_X74Y120", "BRAM_X53Y130"),
                Arguments.of("BRAM_X38Y155", "BRAM_X32Y200", "BRAM_X32Y205", "BRAM_X38Y150"),
                Arguments.of("BRAM_X19Y285", "BRAM_X19Y220", "BRAM_X19Y260", "BRAM_X19Y245"),
                Arguments.of("BRAM_X98Y5", "BRAM_X74Y280", "BRAM_X74Y270", "BRAM_X98Y15"),
                Arguments.of("BRAM_X74Y15", "BRAM_X19Y130", "BRAM_X38Y180", null),
                Arguments.of("BRAM_X89Y230", "BRAM_X102Y135", "BRAM_X102Y280", "BRAM_X89Y85"),
                Arguments.of("LAG_LAG_X4Y27", "BRAM_X68Y235", "BRAM_X68Y20", "LAG_LAG_X4Y242"),
                Arguments.of("LAG_LAG_X49Y37", "BRAM_X55Y80", "BRAM_X19Y90", "LAG_LAG_X85Y27"),
                Arguments.of("LAG_LAG_X76Y37", "BRAM_X38Y150", "BRAM_X74Y185", "LAG_LAG_X40Y2"),
                Arguments.of("LAG_LAG_X40Y259", "BRAM_X55Y40", "BRAM_X19Y10", "LAG_LAG_X76Y289"),
                Arguments.of("LAG_LAG_X40Y267", "BRAM_X55Y85", "BRAM_X19Y70", "LAG_LAG_X76Y282"),
                Arguments.of("BRAM_X55Y80", "LAG_LAG_X21Y299", "LAG_LAG_X4Y4", null),
                Arguments.of("BRAM_X74Y55", "LAG_LAG_X76Y287", "LAG_LAG_X21Y242", null),
                Arguments.of("BRAM_X6Y60", "LAG_LAG_X30Y7", "LAG_LAG_X66Y287", null),
                Arguments.of("BRAM_X74Y125", "LAG_LAG_X76Y45", "LAG_LAG_X4Y54", null),
                Arguments.of("BRAM_X6Y285", "LAG_LAG_X85Y34", "LAG_LAG_X94Y41", null),
                Arguments.of("CLEL_R_X15Y45", "LAG_LAG_X66Y28", "LAG_LAG_X66Y0", "CLEL_R_X15Y73"),
                Arguments.of("CLEL_R_X49Y184", "LAG_LAG_X94Y258", "LAG_LAG_X85Y242", "CLEL_R_X58Y200"),
                Arguments.of("CLEL_R_X13Y255", "LAG_LAG_X94Y32", "LAG_LAG_X40Y57", "CLEL_R_X67Y230"),
                Arguments.of("CLEL_R_X74Y0", "LAG_LAG_X76Y284", "LAG_LAG_X57Y284", "CLEL_R_X93Y0"),
                Arguments.of("CLEL_R_X36Y207", "LAG_LAG_X94Y246", "LAG_LAG_X30Y248", "CLEL_R_X100Y205"),
                Arguments.of("DSP_X33Y100", "LAG_LAG_X4Y263", "LAG_LAG_X94Y8", null),
                Arguments.of("DSP_X59Y70", "LAG_LAG_X57Y23", "LAG_LAG_X57Y8", "DSP_X59Y85"),
                Arguments.of("DSP_X90Y170", "LAG_LAG_X49Y294", "LAG_LAG_X49Y32", null),
                Arguments.of("DSP_X64Y250", "LAG_LAG_X21Y41", "LAG_LAG_X49Y276", null),
                Arguments.of("DSP_X20Y285", "LAG_LAG_X76Y285", "LAG_LAG_X76Y295", "DSP_X20Y275"),
                Arguments.of("CLEM_X88Y55", "LAG_LAG_X12Y3", "LAG_LAG_X49Y39", "CLEM_X51Y19"),
                Arguments.of("CLEM_X30Y193", "LAG_LAG_X66Y299", "LAG_LAG_X66Y281", "CLEM_X30Y211"),
                Arguments.of("CLEM_X52Y262", "LAG_LAG_X85Y250", "LAG_LAG_X49Y57", null),
                Arguments.of("CLEM_X70Y34", "LAG_LAG_X12Y19", "LAG_LAG_X4Y28", "CLEM_X78Y25"),
                Arguments.of("CLEM_X59Y82", "LAG_LAG_X57Y42", "LAG_LAG_X85Y29", "CLEM_X31Y95"),
                Arguments.of("CMT_L_X36Y120", "LAG_LAG_X4Y288", "LAG_LAG_X66Y271", null),
                Arguments.of("CMT_L_X72Y0", "LAG_LAG_X49Y54", "LAG_LAG_X40Y281", null),
                Arguments.of("CMT_L_X72Y60", "LAG_LAG_X4Y20", "LAG_LAG_X57Y14", null),
                Arguments.of("CMT_L_X72Y60", "LAG_LAG_X85Y261", "LAG_LAG_X21Y279", null),
                Arguments.of("CMT_L_X72Y120", "LAG_LAG_X85Y10", "LAG_LAG_X102Y49", null),
                Arguments.of("BRAM_X68Y120", "LAG_LAG_X76Y55", "LAG_LAG_X40Y240", null),
                Arguments.of("BRAM_X19Y165", "LAG_LAG_X4Y278", "LAG_LAG_X102Y35", null),
                Arguments.of("BRAM_X32Y200", "LAG_LAG_X49Y279", "LAG_LAG_X30Y1", null),
                Arguments.of("BRAM_X19Y20", "LAG_LAG_X94Y4", "LAG_LAG_X30Y11", null),
                Arguments.of("BRAM_X98Y140", "LAG_LAG_X30Y53", "LAG_LAG_X76Y287", null),
                Arguments.of("BRAM_X53Y45", "LAG_LAG_X40Y43", "LAG_LAG_X40Y13", "BRAM_X53Y75"),
                Arguments.of("BRAM_X89Y5", "LAG_LAG_X49Y242", "LAG_LAG_X102Y53", null),
                Arguments.of("BRAM_X19Y95", "LAG_LAG_X30Y38", "LAG_LAG_X30Y32", null),
                Arguments.of("BRAM_X38Y90", "LAG_LAG_X12Y9", "LAG_LAG_X85Y251", null),
                Arguments.of("BRAM_X6Y215", "LAG_LAG_X85Y26", "LAG_LAG_X76Y297", null),
                Arguments.of("LAG_LAG_X94Y264", "LAG_LAG_X76Y25", "LAG_LAG_X76Y265", "LAG_LAG_X94Y24"),
                Arguments.of("LAG_LAG_X49Y26", "LAG_LAG_X21Y259", "LAG_LAG_X30Y5", "LAG_LAG_X40Y280"),
                Arguments.of("LAG_LAG_X94Y59", "LAG_LAG_X4Y241", "LAG_LAG_X4Y30", "LAG_LAG_X94Y270"),
                Arguments.of("LAG_LAG_X4Y297", "LAG_LAG_X85Y248", "LAG_LAG_X4Y287", "LAG_LAG_X85Y258"),
                Arguments.of("LAG_LAG_X40Y30", "LAG_LAG_X57Y241", "LAG_LAG_X40Y263", "LAG_LAG_X57Y8")
        );
    }

    @ParameterizedTest(name="Relocate {0} with anchor from {2} to {1}")
    @MethodSource()
    public void testVersal(String template, String newAnchor, String oldAnchor, String expectedResult) {
        Device device = Device.getDevice(DEVICE_VERSAL);
        testRelocateTile(device, template, newAnchor, oldAnchor, expectedResult);
    }


    public static Stream<Arguments> testVersal() {
        return Stream.of(
                Arguments.of("CLE_E_CORE_X27Y45", "CLE_E_CORE_X77Y224", "CLE_E_CORE_X58Y150", "CLE_E_CORE_X46Y119"),
                Arguments.of("CLE_W_CORE_X49Y38", "CLE_W_CORE_X77Y331", "CLE_E_CORE_X33Y235", "CLE_W_CORE_X93Y134"),
                Arguments.of("CLE_E_CORE_X90Y218", "CLE_E_CORE_X38Y315", "CLE_E_CORE_X44Y310", "CLE_E_CORE_X84Y223"),
                Arguments.of("CLE_E_CORE_X16Y115", "CLE_E_CORE_X74Y330", "CLE_W_CORE_X70Y242", "CLE_E_CORE_X20Y203"),
                Arguments.of("CLE_W_CORE_X70Y223", "CLE_W_CORE_X49Y162", "CLE_W_CORE_X66Y308", "CLE_W_CORE_X53Y77"),
                Arguments.of("BRAM_ROCF_TR_TILE_X41Y308", "CLE_W_CORE_X50Y180", "CLE_W_CORE_X28Y218", null),
                Arguments.of("BRAM_LOCF_TR_TILE_X8Y152", "CLE_E_CORE_X34Y121", "CLE_E_CORE_X27Y123", null),
                Arguments.of("BRAM_ROCF_TR_TILE_X103Y304", "CLE_W_CORE_X17Y245", "CLE_E_CORE_X44Y135", null),
                Arguments.of("BRAM_ROCF_TR_TILE_X41Y172", "CLE_W_CORE_X100Y330", "CLE_W_CORE_X106Y294", "BRAM_ROCF_BR_TILE_X35Y208"),
                Arguments.of("BRAM_ROCF_BR_TILE_X81Y4", "CLE_W_CORE_X96Y143", "CLE_E_CORE_X57Y303", null),
                Arguments.of("CLE_W_CORE_X55Y165", "CLE_E_CORE_X109Y167", "CLE_W_CORE_X62Y138", "CLE_W_CORE_X102Y194"),
                Arguments.of("CLE_W_CORE_X14Y6", "CLE_W_CORE_X82Y319", "CLE_W_CORE_X54Y77", "CLE_W_CORE_X42Y248"),
                Arguments.of("CLE_W_CORE_X19Y89", "CLE_W_CORE_X77Y180", "CLE_E_CORE_X86Y70", "CLE_W_CORE_X10Y199"),
                Arguments.of("CLE_W_CORE_X55Y80", "CLE_W_CORE_X30Y139", "CLE_W_CORE_X71Y13", "CLE_W_CORE_X14Y206"),
                Arguments.of("CLE_W_CORE_X82Y274", "CLE_E_CORE_X69Y72", "CLE_W_CORE_X27Y115", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X79Y0", "CLE_W_CORE_X25Y143", "CLE_E_CORE_X107Y254", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLE_W_CORE_X37Y302", "CLE_E_CORE_X99Y154", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", "CLE_E_CORE_X31Y145", "CLE_W_CORE_X56Y87", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X112Y0", "CLE_W_CORE_X34Y329", "CLE_E_CORE_X103Y155", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X55Y0", "CLE_W_CORE_X67Y135", "CLE_W_CORE_X97Y330", null),
                Arguments.of("CLE_W_CORE_X105Y173", "BRAM_ROCF_TR_TILE_X81Y292", "BRAM_ROCF_BR_TILE_X81Y200", "CLE_W_CORE_X105Y265"),
                Arguments.of("CLE_W_CORE_X33Y88", "BRAM_ROCF_TR_TILE_X87Y268", "BRAM_ROCF_BR_TILE_X87Y204", "CLE_W_CORE_X33Y152"),
                Arguments.of("CLE_E_CORE_X67Y313", "BRAM_ROCF_BR_TILE_X41Y104", "BRAM_ROCF_BR_TILE_X64Y224", "CLE_E_CORE_X44Y193"),
                Arguments.of("CLE_E_CORE_X45Y52", "BRAM_ROCF_TL_TILE_X42Y308", "BRAM_ROCF_TL_TILE_X65Y264", "CLE_E_CORE_X22Y96"),
                Arguments.of("CLE_W_CORE_X49Y321", "BRAM_ROCF_TL_TILE_X42Y80", "BRAM_ROCF_TR_TILE_X58Y308", "CLE_W_CORE_X33Y93"),
                Arguments.of("BRAM_ROCF_BR_TILE_X87Y12", "BRAM_ROCF_BR_TILE_X35Y204", "BRAM_ROCF_TR_TILE_X41Y304", null),
                Arguments.of("BRAM_ROCF_TL_TILE_X88Y48", "BRAM_ROCF_BR_TILE_X87Y44", "BRAM_ROCF_TR_TILE_X87Y84", "BRAM_ROCF_BL_TILE_X88Y8"),
                Arguments.of("BRAM_ROCF_TR_TILE_X64Y296", "BRAM_ROCF_BR_TILE_X58Y12", "BRAM_ROCF_BR_TILE_X18Y36", null),
                Arguments.of("BRAM_ROCF_BR_TILE_X103Y140", "BRAM_ROCF_BR_TILE_X87Y28", "BRAM_ROCF_TR_TILE_X87Y84", "BRAM_ROCF_TR_TILE_X103Y84"),
                Arguments.of("BRAM_ROCF_BL_TILE_X42Y132", "BRAM_ROCF_TR_TILE_X64Y292", "BRAM_ROCF_TR_TILE_X41Y164", "BRAM_ROCF_TL_TILE_X65Y260"),
                Arguments.of("CLE_W_CORE_X25Y191", "BRAM_ROCF_BR_TILE_X87Y140", "BRAM_ROCF_BR_TILE_X64Y116", "CLE_W_CORE_X48Y215"),
                Arguments.of("CLE_E_CORE_X95Y108", "BRAM_ROCF_BR_TILE_X18Y40", "BRAM_ROCF_TL_TILE_X88Y60", "CLE_E_CORE_X25Y88"),
                Arguments.of("CLE_E_CORE_X72Y83", "BRAM_ROCF_TR_TILE_X87Y68", "BRAM_ROCF_BR_TILE_X58Y120", "CLE_E_CORE_X101Y31"),
                Arguments.of("CLE_W_CORE_X22Y246", "BRAM_ROCF_BL_TILE_X88Y100", "BRAM_ROCF_TL_TILE_X88Y152", "CLE_W_CORE_X22Y194"),
                Arguments.of("CLE_E_CORE_X45Y198", "BRAM_ROCF_TL_TILE_X42Y304", "BRAM_ROCF_TL_TILE_X42Y316", "CLE_E_CORE_X45Y186"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X55Y0", "BRAM_ROCF_BR_TILE_X81Y40", "BRAM_ROCF_TL_TILE_X65Y188", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X101Y0", "BRAM_ROCF_TL_TILE_X42Y264", "BRAM_ROCF_TL_TILE_X88Y264", "CLK_REBUF_BUFGS_HSR_CORE_X55Y0"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X55Y0", "BRAM_ROCF_TR_TILE_X81Y64", "BRAM_ROCF_TL_TILE_X65Y292", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X90Y0", "BRAM_ROCF_BR_TILE_X35Y136", "BRAM_ROCF_BR_TILE_X64Y192", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X0Y0", "BRAM_ROCF_TR_TILE_X41Y244", "BRAM_ROCF_TR_TILE_X103Y72", null),
                Arguments.of("CLE_W_CORE_X56Y326", "CLE_W_CORE_X90Y203", "CLE_E_CORE_X109Y231", "CLE_W_CORE_X37Y298"),
                Arguments.of("CLE_W_CORE_X14Y139", "CLE_W_CORE_X80Y232", "CLE_E_CORE_X44Y130", "CLE_W_CORE_X50Y241"),
                Arguments.of("CLE_W_CORE_X11Y236", "CLE_E_CORE_X81Y282", "CLE_W_CORE_X36Y313", "CLE_W_CORE_X56Y205"),
                Arguments.of("CLE_W_CORE_X66Y255", "CLE_W_CORE_X89Y40", "CLE_E_CORE_X79Y33", "CLE_W_CORE_X76Y262"),
                Arguments.of("CLE_W_CORE_X101Y259", "CLE_W_CORE_X9Y155", "CLE_W_CORE_X24Y159", "CLE_W_CORE_X86Y255"),
                Arguments.of("BRAM_ROCF_TL_TILE_X88Y280", "CLE_W_CORE_X45Y305", "CLE_E_CORE_X18Y7", null),
                Arguments.of("BRAM_ROCF_BR_TILE_X58Y204", "CLE_E_CORE_X7Y299", "CLE_E_CORE_X15Y232", null),
                Arguments.of("BRAM_ROCF_TR_TILE_X58Y312", "CLE_W_CORE_X44Y105", "CLE_E_CORE_X67Y297", "BRAM_ROCF_BR_TILE_X35Y120"),
                Arguments.of("BRAM_ROCF_BR_TILE_X18Y32", "CLE_W_CORE_X2Y291", "CLE_W_CORE_X65Y207", null),
                Arguments.of("BRAM_ROCF_TR_TILE_X103Y296", "CLE_W_CORE_X31Y149", "CLE_W_CORE_X53Y193", "BRAM_ROCF_TR_TILE_X81Y252"),
                Arguments.of("CLE_E_CORE_X33Y184", "CLE_W_CORE_X30Y326", "CLE_E_CORE_X51Y181", "CLE_E_CORE_X12Y329"),
                Arguments.of("CLE_E_CORE_X95Y182", "CLE_E_CORE_X28Y272", "CLE_E_CORE_X23Y238", "CLE_E_CORE_X100Y216"),
                Arguments.of("CLE_E_CORE_X79Y129", "CLE_W_CORE_X77Y256", "CLE_E_CORE_X105Y309", "CLE_E_CORE_X51Y76"),
                Arguments.of("CLE_W_CORE_X26Y76", "CLE_W_CORE_X28Y148", "CLE_E_CORE_X20Y44", "CLE_W_CORE_X34Y180"),
                Arguments.of("CLE_W_CORE_X97Y307", "CLE_W_CORE_X94Y55", "CLE_W_CORE_X98Y196", "CLE_W_CORE_X93Y166"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X101Y0", "CLE_E_CORE_X81Y191", "CLE_W_CORE_X62Y308", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X79Y0", "CLE_E_CORE_X83Y59", "CLE_E_CORE_X50Y267", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X101Y0", "CLE_E_CORE_X11Y198", "CLE_E_CORE_X96Y13", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLE_E_CORE_X83Y259", "CLE_E_CORE_X72Y261", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X31Y0", "CLE_E_CORE_X54Y277", "CLE_E_CORE_X41Y267", null),
                Arguments.of("CLE_E_CORE_X8Y202", "CLK_REBUF_BUFGS_HSR_CORE_X79Y0", "CLK_REBUF_BUFGS_HSR_CORE_X43Y0", "CLE_E_CORE_X44Y202"),
                Arguments.of("CLE_E_CORE_X67Y184", "CLK_REBUF_BUFGS_HSR_CORE_X19Y0", "CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLE_E_CORE_X20Y184"),
                Arguments.of("CLE_E_CORE_X51Y322", "CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLK_REBUF_BUFGS_HSR_CORE_X101Y0", "CLE_E_CORE_X16Y322"),
                Arguments.of("CLE_W_CORE_X105Y280", "CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", "CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", "CLE_W_CORE_X105Y280"),
                Arguments.of("CLE_W_CORE_X77Y55", "CLK_REBUF_BUFGS_HSR_CORE_X19Y0", "CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", "CLE_W_CORE_X96Y55"),
                Arguments.of("BRAM_ROCF_TR_TILE_X103Y160", "CLK_REBUF_BUFGS_HSR_CORE_X101Y0", "CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", null),
                Arguments.of("BRAM_ROCF_TR_TILE_X35Y188", "CLK_REBUF_BUFGS_HSR_CORE_X43Y0", "CLK_REBUF_BUFGS_HSR_CORE_X112Y0", null),
                Arguments.of("BRAM_ROCF_TR_TILE_X18Y244", "CLK_REBUF_BUFGS_HSR_CORE_X31Y0", "CLK_REBUF_BUFGS_HSR_CORE_X19Y0", null),
                Arguments.of("BRAM_ROCF_BL_TILE_X88Y200", "CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "BRAM_ROCF_BL_TILE_X88Y200"),
                Arguments.of("BRAM_ROCF_TR_TILE_X87Y332", "CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLK_REBUF_BUFGS_HSR_CORE_X112Y0", "BRAM_ROCF_TR_TILE_X41Y332"),
                Arguments.of("CLE_E_CORE_X29Y112", "CLK_REBUF_BUFGS_HSR_CORE_X8Y0", "CLK_REBUF_BUFGS_HSR_CORE_X8Y0", "CLE_E_CORE_X29Y112"),
                Arguments.of("CLE_W_CORE_X100Y303", "CLK_REBUF_BUFGS_HSR_CORE_X19Y0", "CLK_REBUF_BUFGS_HSR_CORE_X112Y0", "CLE_W_CORE_X7Y303"),
                Arguments.of("CLE_E_CORE_X83Y185", "CLK_REBUF_BUFGS_HSR_CORE_X8Y0", "CLK_REBUF_BUFGS_HSR_CORE_X31Y0", "CLE_E_CORE_X60Y185"),
                Arguments.of("CLE_W_CORE_X30Y44", "CLK_REBUF_BUFGS_HSR_CORE_X43Y0", "CLK_REBUF_BUFGS_HSR_CORE_X0Y0", "CLE_W_CORE_X73Y44"),
                Arguments.of("CLE_W_CORE_X11Y314", "CLK_REBUF_BUFGS_HSR_CORE_X79Y0", "CLK_REBUF_BUFGS_HSR_CORE_X90Y0", "CLE_W_CORE_X0Y314"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X112Y0", "CLK_REBUF_BUFGS_HSR_CORE_X0Y0", "CLK_REBUF_BUFGS_HSR_CORE_X43Y0", null),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X43Y0", "CLK_REBUF_BUFGS_HSR_CORE_X19Y0", "CLK_REBUF_BUFGS_HSR_CORE_X43Y0", "CLK_REBUF_BUFGS_HSR_CORE_X19Y0"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X8Y0", "CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", "CLK_REBUF_BUFGS_HSR_CORE_X0Y0", "CLK_REBUF_BUFGS_HSR_CORE_X8Y0"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_X66Y0", "CLK_REBUF_BUFGS_HSR_CORE_X112Y0", "CLK_REBUF_BUFGS_HSR_CORE_X112Y0", "CLK_REBUF_BUFGS_HSR_CORE_X66Y0"),
                Arguments.of("CLK_REBUF_BUFGS_HSR_CORE_1_X0Y0", "CLK_REBUF_BUFGS_HSR_CORE_X43Y0", "CLK_REBUF_BUFGS_HSR_CORE_X55Y0", null)
        );

    }

    static Iterator<Tile> randomTiles(Random rnd, Site[] sites) {
        return rnd.ints(0, sites.length).mapToObj(i -> sites[i].getTile()).iterator();
    }

    private static String getTileStr(Tile t) {
        if (t == null) {
            return "null";
        }
        return "\"" + t.getName() + "\"";
    }

    private static void generateTestcases(Device device) {
        Random rnd = new Random(System.nanoTime());

        List<Iterator<Tile>> randomTilesByType = new ArrayList<>();

        for (SiteTypeEnum moduleSiteType : Utils.getModuleSiteTypes()) {
            final Site[] allSitesOfType = device.getAllSitesOfType(moduleSiteType);
            if (allSitesOfType.length == 0) {
                continue;
            }

            final Iterator<Tile> typeTiles = randomTiles(rnd, allSitesOfType);
            randomTilesByType.add(typeTiles);
        }

        Stream<String> testcases = randomTilesByType.stream().flatMap(anchorIter ->
                randomTilesByType.stream().flatMap(templateIter ->
                        IntStream.range(0, 5).mapToObj(i -> {
                            while (true) {
                                Tile oldAnchor = anchorIter.next();
                                Tile newAnchor = anchorIter.next();
                                Tile template = templateIter.next();
                                Tile result = Module.getCorrespondingTile(template, newAnchor, oldAnchor);
                                if (result != null || rnd.nextDouble() < 0.05) {
                                    return "\tArguments.of("
                                            + getTileStr(template) + ", "
                                            + getTileStr(newAnchor) + ", "
                                            + getTileStr(oldAnchor) + ", "
                                            + getTileStr(result) + ")";
                                }
                            }
                        })));

        System.out.println("return Stream.of(");
        System.out.println(testcases.collect(Collectors.joining(",\n")));
        System.out.println(");");
    }

    /**
     * Entry point for generating testcases. Not in use for normal Unit Testing
     */
    public static void main(String[] args) {
        generateTestcases(Device.getDevice(DEVICE_VERSAL));
    }

}
