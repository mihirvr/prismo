package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRemoverTest {

    @TempDir
    Path tmp;

    @Test
    void withARecycleBinTheFileGoesThere() throws Exception {
        Path file = Files.writeString(tmp.resolve("a.jpg"), "x");
        List<File> binned = new ArrayList<>();
        FileRemover remover = new FileRemover(f -> binned.add(f) && f.delete());

        assertTrue(remover.usesRecycleBin());
        assertEquals(FileRemover.Outcome.RECYCLE_BIN, remover.remove(file));
        assertEquals(List.of(file.toFile()), binned);
        assertFalse(Files.exists(file));
    }

    @Test
    void withoutOneTheFileIsDeleted() throws Exception {
        Path file = Files.writeString(tmp.resolve("a.jpg"), "x");
        FileRemover remover = new FileRemover(null);

        assertFalse(remover.usesRecycleBin());
        assertEquals(FileRemover.Outcome.DELETED, remover.remove(file));
        assertFalse(Files.exists(file));
    }

    @Test
    void aFileAlreadyGoneIsNotAnError() throws Exception {
        List<File> binned = new ArrayList<>();
        FileRemover remover = new FileRemover(binned::add);

        assertEquals(FileRemover.Outcome.ALREADY_GONE, remover.remove(tmp.resolve("missing.jpg")));
        assertEquals(List.of(), binned, "the recycle bin isn't asked");
    }

    @Test
    void aRefusalIsAnErrorAndTheFileStays() throws Exception {
        Path file = Files.writeString(tmp.resolve("a.jpg"), "x");

        assertThrows(java.io.IOException.class, () -> new FileRemover(_ -> false).remove(file));
        assertThrows(java.io.IOException.class, () -> new FileRemover(_ -> {
            throw new IllegalStateException("no shell");
        }).remove(file));
        assertTrue(Files.exists(file));
    }
}
