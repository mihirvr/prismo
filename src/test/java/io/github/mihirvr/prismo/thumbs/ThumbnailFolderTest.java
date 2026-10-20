package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailFolderTest {

    @TempDir
    Path cache;

    @Test
    void countsAndClearsTheThumbnailsButNeverFfmpegsFiles() throws Exception {
        assertEquals(0, ThumbnailFolder.size(cache), "nothing made yet");
        ThumbnailCache thumbs = new ThumbnailCache(cache, 320);
        write(thumbs.fileFor(1), 100);
        write(thumbs.fileFor(258), 50); // another shard folder
        write(thumbs.failMarkerFor(3), 0);
        write(new ThumbnailCache(cache, 1024).fileFor(1), 30); // another size
        Path ffmpeg = write(cache.resolve("native").resolve("avcodec-61.dll"), 500);

        assertEquals(180, ThumbnailFolder.size(cache), "only the thumbnails count");
        assertEquals(180, ThumbnailFolder.clear(cache));

        assertEquals(0, ThumbnailFolder.size(cache));
        assertTrue(Files.exists(ffmpeg), "FFmpeg's unpacked files stay");
        assertTrue(Files.isDirectory(cache.resolve("thumbs")), "the folder itself stays for new thumbnails");
        try (var left = Files.list(cache.resolve("thumbs"))) {
            assertEquals(0, left.count(), "empty size and shard folders are gone");
        }
    }

    private static Path write(Path file, int bytes) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[bytes]);
    }
}
