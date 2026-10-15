package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    @TempDir
    Path tmp;

    private Database database;
    private SettingsStore settings;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(tmp.resolve("prismo.db"));
        settings = new SettingsStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void missingKeyIsEmpty() throws Exception {
        assertEquals(Optional.empty(), settings.get("theme"));
    }

    @Test
    void putThenGet() throws Exception {
        settings.put("theme", "dark").get();
        assertEquals(Optional.of("dark"), settings.get("theme"));
    }

    @Test
    void putOverwritesExistingValue() throws Exception {
        settings.put("theme", "dark").get();
        settings.put("theme", "amoled").get();
        assertEquals(Optional.of("amoled"), settings.get("theme"));
    }

    @Test
    void valuesSurviveReopening() throws Exception {
        settings.put("theme", "light").get();
        database.close();

        database = Database.open(tmp.resolve("prismo.db"));
        assertEquals(Optional.of("light"), new SettingsStore(database).get("theme"));
    }
}
