package io.github.mihirvr.prismo.thumbs;

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

/**
 * 2.0.0: a song's cover (album art): the picture stored in an MP3 (ID3 APIC), M4A, FLAC, Ogg or MKA, which FFmpeg reads
 * as a picture stream marked "attached picture". The bundled {@code ffmpeg} writes it to a temporary BMP (as for HEIF:
 * not compressed, so quick to write and read back), scaled to fit a square if asked.
 */
public final class AudioCovers {

    private static final long TIMEOUT_SECONDS = 60;

    /** A song with no cover: the grid shows a music note instead, and the file isn't tried again until it changes. */
    public static final class NoCoverException extends IOException {
        private static final long serialVersionUID = 1L;

        NoCoverException(Path file) {
            super(file.getFileName() + " has no album art");
        }
    }

    private AudioCovers() {
    }

    /** The cover of {@code file}, at most {@code maxEdge} pixels on its long side (0 = full size). */
    public static BufferedImage picture(Path file, int maxEdge) throws IOException {
        return picture(file, maxEdge, VideoNatives::ffmpegExecutable);
    }

    /** @throws NoCoverException if the file has no cover */
    static BufferedImage picture(Path file, int maxEdge, OtherStills.Program ffmpeg) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        Path out = Files.createTempFile("prismo-cover-", ".bmp");
        try {
            String error = ExternalProgram.run(command(ffmpeg.path(), file, maxEdge, out), TIMEOUT_SECONDS);
            if (error.contains("matches no streams") || error.contains("does not contain any stream")) {
                throw new NoCoverException(file);
            }
            BufferedImage image = Files.size(out) > 0 ? ImageIO.read(out.toFile()) : null;
            if (image == null) {
                throw new IOException("ffmpeg made no cover picture of " + file.getFileName()
                        + (error.isBlank() ? "" : ": " + error.lines().findFirst().orElse("")));
            }
            return image;
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /** The first picture stream ({@code 0:v:0}: covers count, unlike the video thumbnails' {@code 0:V:0}). */
    static List<String> command(Path ffmpeg, Path file, int maxEdge, Path out) {
        List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-threads", "1", "-i", file.toString(), "-map", "0:v:0", "-frames:v", "1"));
        if (maxEdge > 0) {
            command.addAll(List.of("-vf",
                    "scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease".formatted(maxEdge, maxEdge)));
        }
        command.addAll(List.of("-an", "-f", "image2", "-c:v", "bmp", "-y", out.toString()));
        return command;
    }
}
