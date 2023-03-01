package com.xilinx.rapidwright.examples;

import org.junit.jupiter.api.Test;

public class TestExamples {
    @Test
    public void testPipelineGenerator() {
        PipelineGenerator.main(new String[]{});
    }

    @Test
    public void testPipelineGeneratorWithRouting() {
        PipelineGeneratorWithRouting.main(new String[]{});
    }
}
