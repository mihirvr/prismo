package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderListingTest {

    @Test
    void theFolderIsListedInNaturalOrderWithOnlyMedia(@TempDir Path dir) throws Exception {
        for (String name : new String[] {"10.jpg", "2.jpg", "1.MP4", "notes.txt", "b.heic"}) {
            Files.writeString(dir.resolve(name), "x");
        }
        Files.createDirectory(dir.resolve("sub.jpg")); // a folder that looks like a photo is left out
        Files.writeString(Files.createDirectory(dir.resolve("Day 1")).resolve("0.jpg"), "x"); // subfolders: not listed

        List<TimelineItem> items = FolderListing.list(dir);

        assertEquals(List.of("1.MP4", "2.jpg", "10.jpg", "b.heic"),
                items.stream().map(i -> i.ref().path().getFileName().toString()).toList());
        assertEquals("mp4", items.getFirst().ref().extension(), "extension in lower case, as the viewer expects");
        assertEquals(2, FolderListing.indexOf(items, dir.resolve("10.jpg")));
        assertEquals(0, FolderListing.indexOf(items, dir.resolve("gone.jpg")), "not there: the first");
    }

    @Test
    void anItemKeepsTheSameIdOnEveryRun(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.jpg"), "x");
        Path b = Files.writeString(dir.resolve("b.jpg"), "x");
        long id = FolderListing.itemOf(a).ref().id();

        assertEquals(id, FolderListing.itemOf(dir.resolve(".").resolve("a.jpg")).ref().id(), "same file, same id");
        assertNotEquals(id, FolderListing.itemOf(b).ref().id());
        assertTrue(id > 0);
    }

    @Test
    void onlyPhotosAndVideosOpenTheStandaloneViewer(@TempDir Path dir) throws Exception {
        Path photo = Files.writeString(dir.resolve("a.jpg"), "x");
        Path text = Files.writeString(dir.resolve("a.txt"), "x");

        assertTrue(FolderListing.isMedia(photo));
        assertFalse(FolderListing.isMedia(text));
        assertFalse(FolderListing.isMedia(dir.resolve("noextension")));
    }

    @Test
    void theInstallerRegistersExactlyTheTypesPrismoReads() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));
        String block = build.substring(build.indexOf("val fileAssociations = mapOf("));
        block = block.substring(0, block.indexOf("\n)"));
        Set<String> registered = new TreeSet<>();
        Matcher pair = Pattern.compile("\"([a-z0-9]+)\" to \"[a-z0-9./+-]+\"").matcher(block);
        while (pair.find()) {
            registered.add(pair.group(1));
        }
        assertEquals(new TreeSet<>(MediaType.knownExtensions()), registered);
    }
}
