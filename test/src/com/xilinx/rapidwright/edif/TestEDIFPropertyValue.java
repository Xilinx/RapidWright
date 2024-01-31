package com.xilinx.rapidwright.edif;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestEDIFPropertyValue {
    @ParameterizedTest
    @CsvSource({
            "1'b1,INTEGER,true",
            "1'b0,INTEGER,false",
            "32'hDeAdBeEf,INTEGER,true",

            "1'b1,STRING,true",
            "1'b0,STRING,false",
            "32'hDeAdBeEf,STRING,true",
            "false,STRING,false",
            "FaLsE,STRING,false",
            "true,STRING,true",
            "deadbeef,STRING,true",

            "false,BOOLEAN,false",
            "FALSE,BOOLEAN,false",
            "true,BOOLEAN,true",
    })
    public void testGetBooleanValue(String value, EDIFValueType type, boolean expected) {
        EDIFPropertyValue v = new EDIFPropertyValue(value, type);
        Assertions.assertEquals(expected, v.getBooleanValue());
    }
}
