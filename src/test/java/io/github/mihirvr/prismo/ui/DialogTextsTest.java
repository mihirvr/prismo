package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DialogTextsTest {

    @Test
    void shortNamesAreQuotedWhole() {
        assertEquals("\u201cIMG_101.jpg\u201d", DialogTexts.quoted("IMG_101.jpg"));
        String forty = "a".repeat(DialogTexts.MAX_NAME);
        assertEquals("\u201c" + forty + "\u201d", DialogTexts.quoted(forty));
    }

    @Test
    void longNamesKeepTheirStartAndEnd() {
        String name = "Family holiday in Goa, December 2024, the beach day with everyone (edited).jpg";
        String shown = DialogTexts.quoted(name);

        assertEquals(DialogTexts.MAX_NAME + 2, shown.length(), "the name part is exactly the limit, plus the quotes");
        assertTrue(shown.startsWith("\u201cFamily holiday in Go"), shown);
        assertTrue(shown.endsWith("(edited).jpg\u201d"), "the extension stays: " + shown);
        assertTrue(shown.contains("…"), shown);
    }

    @Test
    void aSelectionListsAFewNamesThenHowManyMore() {
        java.util.List<Path> files = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(i -> Path.of("D:\\Photos\\IMG_" + i + ".jpg")).toList();

        assertEquals("IMG_1.jpg\nIMG_2.jpg", DialogTexts.fileList(files.subList(0, 2)));
        String shown = DialogTexts.fileList(files);
        assertEquals(DialogTexts.LISTED + 1, shown.lines().count(), shown);
        assertTrue(shown.startsWith("IMG_1.jpg\n"), shown);
        assertTrue(shown.endsWith("\nIMG_5.jpg\nand 3 more"), shown);
        assertEquals(DialogTexts.LISTED, DialogTexts.fileList(files.subList(0, DialogTexts.LISTED)).lines().count(),
                "exactly the limit: no \"more\" line");
    }

    @Test
    void aFolderIsNamedByItsLastPartAndADriveByItsRoot() {
        assertEquals("Holidays", DialogTexts.folderName(Path.of("D:\\Photos\\Holidays")));
        assertEquals(Path.of("D:\\").toString(), DialogTexts.folderName(Path.of("D:\\")));
    }
}
