package com.xilinx.rapidwright.util;

public enum JobState {
    RUNNING("running"),
    EXITED("exited"),
    SUSPENDED("suspended"),
    PENDING("pending");

    private final String name;

    JobState(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
