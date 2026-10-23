package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalProgramTest {

    @Test
    void theProgramsOwnFolderComesFirstOnTheLibraryPath() {
        assertEquals("/opt/prismo/cache/ffmpeg", ExternalProgram.libraryPath("/opt/prismo/cache/ffmpeg", null));
        assertEquals("/opt/prismo/cache/ffmpeg", ExternalProgram.libraryPath("/opt/prismo/cache/ffmpeg", " "));
        assertEquals("/opt/prismo/cache/ffmpeg:/usr/local/lib",
                ExternalProgram.libraryPath("/opt/prismo/cache/ffmpeg", "/usr/local/lib"));
    }

    @Test
    void onLinuxTheProgramsFolderIsAddedAndElsewhereNothingChanges() {
        Path program = Path.of("tools", "ffmpeg").toAbsolutePath();
        String libraryPath = ExternalProgram.processFor(List.of(program.toString(), "-version"))
                .environment().get("LD_LIBRARY_PATH");

        if (OperatingSystem.current() == OperatingSystem.LINUX) {
            assertTrue(libraryPath.startsWith(program.getParent().toString()), libraryPath);
        } else {
            assertFalse(libraryPath != null && libraryPath.contains(program.getParent().toString()),
                    "Windows looks in the program's folder by itself");
        }
    }
}
