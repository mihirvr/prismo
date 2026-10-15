package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OperatingSystemTest {

    @Test
    void detectsWindowsAndLinux() {
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.fromOsName("Windows 11"));
        assertEquals(OperatingSystem.LINUX, OperatingSystem.fromOsName("Linux"));
    }

    @Test
    void rejectsUnsupportedOs() {
        assertThrows(IllegalStateException.class, () -> OperatingSystem.fromOsName("Mac OS X"));
    }
}
