package com.asoft.ios;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

public class MemoryCleanerTest {
    @Test
    public void theAggressiveRoundForceStopsEachPackageInOneShellCommand() {
        assertEquals("for p in com.a.b com.c.d; do am force-stop \"$p\"; done",
                MemoryCleaner.forceStopCommand(new LinkedHashSet<>(Arrays.asList("com.a.b", "com.c.d"))));
    }
}
