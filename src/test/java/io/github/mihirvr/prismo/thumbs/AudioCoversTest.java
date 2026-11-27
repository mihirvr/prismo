package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioCoversTest {

    @TempDir
    Path tmp;

    /** Made with the bundled ffmpeg: a song, and the same song with a 400 x 300 orange cover. */
    @Test
    void readsTheCoverOfASongAndSaysWhenThereIsNone() throws Exception {
        Path cover = tmp.resolve("cover.png");
        BufferedImage orange = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 400; x++) {
            for (int y = 0; y < 300; y++) {
                orange.setRGB(x, y, 0xF97316);
            }
        }
        ImageIO.write(orange, "png", cover.toFile());
        Path song = ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "libmp3lame",
                tmp.resolve("song.mp3").toString());
        Path withArt = ffmpeg("-i", song.toString(), "-i", cover.toString(), "-map", "0:a", "-map", "1", "-c", "copy",
                "-id3v2_version", "3", "-disposition:v", "attached_pic", tmp.resolve("art.mp3").toString());

        BufferedImage full = AudioCovers.picture(withArt, 0);
        BufferedImage small = AudioCovers.picture(withArt, 100);

        assertEquals(400, full.getWidth());
        assertEquals(300, full.getHeight());
        assertEquals(100, small.getWidth(), "fits a 100 px square, shape kept");
        assertEquals(75, small.getHeight());
        int rgb = full.getRGB(200, 150) & 0xFFFFFF;
        assertTrue(Math.abs((rgb >> 16) - 0xF9) < 8 && Math.abs((rgb & 0xFF) - 0x16) < 8, Integer.toHexString(rgb));
        assertThrows(AudioCovers.NoCoverException.class, () -> AudioCovers.picture(song, 100));
        assertThrows(NoSuchFileException.class, () -> AudioCovers.picture(tmp.resolve("gone.mp3"), 100));
    }

    /** The grid's thumbnail of a song is its cover; a song without one is no thumbnail (the tile draws a note). */
    @Test
    void theThumbnailOfASongIsItsCover() throws Exception {
        Path song = ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "flac",
                tmp.resolve("song.flac").toString());
        ThumbnailGenerator generator = new ThumbnailGenerator(64);

        assertThrows(AudioCovers.NoCoverException.class, () -> generator.generate(song, tmp.resolve("t.jpg")));
        assertTrue(ThumbnailGenerator.canMake("flac"));
        assertTrue(ThumbnailGenerator.canMake("mp3"));
    }

    private static Path ffmpeg(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(VideoNatives.ffmpegExecutable().toString(), "-nostdin",
                "-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(List.of(arguments));
        String output = ExternalProgram.run(command, 60);
        Path out = Path.of(arguments[arguments.length - 1]);
        assertTrue(Files.size(out) > 0, output);
        return out;
    }
}
