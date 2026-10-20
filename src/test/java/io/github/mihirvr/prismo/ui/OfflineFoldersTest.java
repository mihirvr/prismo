package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OfflineFoldersTest {

    private static final Path USB = Path.of("E:\\Photos");
    private static final Path NAS = Path.of("\\\\nas\\family");

    @Test
    void anItemIsOfflineWhenItsLibraryFolderIs() {
        OfflineFolders offline = OfflineFolders.of(new LinkedHashSet<>(List.of(USB, NAS)));

        assertEquals(USB, offline.rootOf(USB.resolve("2024").resolve("a.jpg")));
        assertEquals(NAS, offline.rootOf(NAS.resolve("b.mp4")));
        assertNull(offline.rootOf(Path.of("E:\\Photos 2\\c.jpg")), "a folder whose name only starts the same");
        assertNull(offline.rootOf(Path.of("D:\\Photos\\a.jpg")));
        assertTrue(offline.contains(USB.resolve("x.png")));
        assertFalse(OfflineFolders.NONE.contains(USB.resolve("x.png")));
        assertSame(OfflineFolders.NONE, OfflineFolders.of(Set.of()));
        assertTrue(OfflineFolders.NONE.isEmpty());
    }

    @Test
    void theBannerNamesTheFolderOrCountsThem() {
        assertEquals(USB + " can't be reached right now (unplugged, or its network is off). Its photos and videos"
                + " are greyed out until it is back; nothing is lost.", OfflineFolders.of(Set.of(USB)).bannerText());
        String two = OfflineFolders.of(new LinkedHashSet<>(List.of(USB, NAS))).bannerText();
        assertTrue(two.startsWith("2 library folders can't be reached right now: " + USB + ", " + NAS), two);
        assertTrue(two.endsWith("Their photos and videos are greyed out until they are back; nothing is lost."), two);
    }

    @Test
    void theViewerSaysWhichFolderAndWhatToDo() {
        String text = OfflineFolders.viewerText(USB);
        assertTrue(text.contains("\n" + USB + "\n"), text);
        assertTrue(text.contains("Plug the drive back in"), text);
    }
}
