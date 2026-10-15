package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryRootStoreTest {

    @TempDir
    Path tmp;

    private Database database;
    private LibraryRootStore roots;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(tmp.resolve("prismo.db"));
        roots = new LibraryRootStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void addsFoldersInOrderAsRealPaths() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("A"));
        Path b = Files.createDirectories(tmp.resolve("B"));

        roots.add(b).get();
        roots.add(a.resolve("..").resolve("A")).get(); // non-normalised spelling of A

        assertEquals(List.of(b.toRealPath(), a.toRealPath()), roots.enabledRoots());
    }

    @Test
    void addingTheSameFolderTwiceKeepsOneRow() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("A"));

        roots.add(a).get();
        roots.add(a).get();

        assertEquals(1, roots.enabledRoots().size());
    }

    @Test
    void missingFolderIsRejected() {
        assertThrows(IOException.class, () -> roots.add(tmp.resolve("nope")));
    }
}
