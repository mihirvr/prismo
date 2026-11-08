package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenWithNameTest {

    @Test
    void readsTheValueFromRegQueryOutput() {
        // As reg.exe prints one value (made-up install folder).
        String output = """

                HKEY_CURRENT_USER\\Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\Shell\\MuiCache
                    C:\\Apps\\Prismo\\Prismo.exe.FriendlyAppName    REG_SZ    A phone-style photo and video gallery for your PC

                """;
        assertEquals(Optional.of("A phone-style photo and video gallery for your PC"), OpenWithName.valueIn(output));
        assertEquals(Optional.empty(), OpenWithName.valueIn("ERROR: The system was unable to find the specified"
                + " registry key or value."));
    }

    @Test
    void onlyAnOldCachedNameIsRemoved() {
        assertTrue(OpenWithName.isStale(Optional.of("A phone-style photo and video gallery for your PC")));
        assertFalse(OpenWithName.isStale(Optional.of("Prismo")), "already right");
        assertFalse(OpenWithName.isStale(Optional.empty()), "nothing cached: Windows reads the exe");
    }

    @Test
    void onlyTheInstalledLauncherCounts() {
        // Built from parts, so the test means the same on Linux (no backslash folders there).
        assertTrue(OpenWithName.isPrismoExe(Path.of("Apps", "Prismo", "Prismo.exe")));
        assertTrue(OpenWithName.isPrismoExe(Path.of("Apps", "Prismo", "PRISMO.EXE")));
        assertFalse(OpenWithName.isPrismoExe(Path.of("jdk", "bin", "java.exe")), "a development build");
    }
}
