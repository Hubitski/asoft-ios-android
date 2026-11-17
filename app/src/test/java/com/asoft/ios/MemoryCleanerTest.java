package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

public class MemoryCleanerTest {
    @Test
    public void theAggressiveRoundForceStopsEachPackageInOneShellCommand() {
        assertEquals("for p in com.a.b com.c.d; do am force-stop \"$p\"; done",
                MemoryCleaner.forceStopCommand(new LinkedHashSet<>(Arrays.asList("com.a.b", "com.c.d"))));
    }

    @Test
    public void theTopOfDumpsysMeminfoIsReadable() {
        String d = "Total PSS by process:\n"
                + "  1,228,800K: com.foo (pid 12)\n"
                + "  204,800K: com.bar (pid 13 / activities)\n"
                + "Total PSS by category:\n"
                + "  400,000K: .so mmap\n";
        assertEquals("com.foo — 1200 MB\ncom.bar — 200 MB", MemoryCleaner.topLines(d, 6));
        assertEquals("com.foo — 1200 MB", MemoryCleaner.topLines(d, 1));
        assertEquals("", MemoryCleaner.topLines("nothing here", 6));
    }

    @Test
    public void theForceButtonAppearsWhenMostOfTheRamIsInUse() {
        assertTrue(MemoryBeforeStart.forceButton(1_000, 399));      // 60.1 % used
        assertFalse(MemoryBeforeStart.forceButton(1_000, 400));     // exactly 60 %
        assertFalse(MemoryBeforeStart.forceButton(0, 0));
    }
}
