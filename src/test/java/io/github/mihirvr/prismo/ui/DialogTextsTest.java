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

    @Test
    void aLongPathIsSplitIntoLinesAfterSeparatorsAndReadsTheSame() {
        assertEquals(Path.of("D:\\Photos\\Holidays\\Goa").toString(),
                DialogTexts.wrappablePath(Path.of("D:\\Photos\\Holidays\\Goa")), "short: one line");

        String path = "E:\\Backups\\Phone 2024\\Camera\\Holidays 2026 with the family\\Day 3\\library";
        String shown = DialogTexts.wrappablePath(Path.of(path));

        assertEquals(path, shown.replace("\n", ""), "only line breaks added");
        for (String line : shown.split("\n")) {
            assertTrue(line.length() <= DialogTexts.PATH_LINE + 1 || !line.substring(0, line.length() - 1).contains("\\"),
                    "a line is short, or one long name: " + line);
        }
        String[] lines = shown.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            assertTrue(lines[i].endsWith("\\"), "breaks only after a separator: " + lines[i]);
        }
        assertEquals("library", lines[lines.length - 1].substring(lines[lines.length - 1].lastIndexOf('\\') + 1),
                "the last name is whole");

        String oneLongName = "D:\\" + "x".repeat(60) + "\\y";
        assertEquals("D:\\\n" + "x".repeat(60) + "\\\ny", DialogTexts.wrappablePath(Path.of(oneLongName)),
                "a name longer than a line keeps a line of its own");
    }
}
