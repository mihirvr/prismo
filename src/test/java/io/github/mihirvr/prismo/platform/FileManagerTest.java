package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileManagerTest {

    @Test
    void windowsSelectsTheFileInExplorerWithThePathAsItsOwnArgument() {
        Path file = Path.of("D:", "My Photos, 2024", "a b.jpg");

        // Separate arguments: Java quotes the path (spaces), and the comma in the folder name can't split it.
        assertEquals(List.of("explorer.exe", "/select,", file.toString()), FileManager.selectCommand(file, "windows 11"));
    }

    @Test
    void macOsRevealsItInFinderAndLinuxHasNoCommonCommand() {
        Path file = Path.of("photos", "a.jpg");

        assertEquals(List.of("open", "-R", file.toString()), FileManager.selectCommand(file, "mac os x"));
        assertEquals(List.of(), FileManager.selectCommand(file, "linux"));
    }
}
