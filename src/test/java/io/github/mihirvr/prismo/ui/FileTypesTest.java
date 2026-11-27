package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class FileTypesTest {

    @Test
    void otherNamesOfTheSameTypeShareOneMenuEntry() {
        assertEquals("jpg", FileTypes.typeOf("JFIF"));
        assertEquals("jpg", FileTypes.typeOf("jpe"));
        assertEquals("heic", FileTypes.typeOf("hif"));
        assertEquals("mts", FileTypes.typeOf("m2ts"));
        assertEquals("mpg", FileTypes.typeOf("mpeg"));
        assertEquals("avif", FileTypes.typeOf("avif"));
        assertNull(FileTypes.typeOf("txt"));
        assertEquals(List.of("jpg", "mts", "ts"), FileTypes.typesOf(List.of("jpeg", "jfif", "m2ts", "MTS", "ts")));
    }

    @Test
    void aMenuEntryFindsEveryExtensionOfItsType() {
        assertEquals("extension IN ('jpg', 'jpeg', 'jpe', 'jfif', 'mts', 'm2ts')",
                FileTypes.sqlCondition(List.of("mts", "jpg")));
        assertEquals("extension IN ('mpg', 'mpeg', 'wmv')", FileTypes.sqlCondition(List.of("wmv", "mpg")));
        assertNull(FileTypes.sqlCondition(List.of("nonsense")));
    }
}
