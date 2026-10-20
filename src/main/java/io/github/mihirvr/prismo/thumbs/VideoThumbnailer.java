package io.github.mihirvr.prismo.thumbs;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One frame of a video as a small JPEG, made by the bundled {@code ffmpeg} program in a separate process.
 *
 * <p><b>Why a separate process</b> and not FFmpeg inside Prismo (as the player does): a damaged video can make
 * a decoder crash, and a crash in native code takes the whole JVM with it. Here it only ends that one
 * {@code ffmpeg} run, and the thumbnail is marked failed. Starting a process costs some milliseconds per video,
 * which doesn't matter for background thumbnails.
 *
 * <p>The frame is taken at 1 s (the very first frame is often black or a fade-in), or at the start for
 * shorter videos. {@code ffmpeg} turns phone videos upright by itself (from the rotation tag) and decodes
 * single-threaded, so the thumbnail pool's thread count stays the CPU limit.
 */
final class VideoThumbnailer {

    /** Where in the video to take the frame. */
    static final String FRAME_AT = "1";
    private static final long TIMEOUT_SECONDS = 60;

    /** Finds the {@code ffmpeg} program; unpacking it can be slow (first time only). */
    @FunctionalInterface
    interface Program {
        Path path() throws IOException;
    }

    private final Program ffmpeg;
    private final int size;

    VideoThumbnailer(Program ffmpeg, int size) {
        this.ffmpeg = ffmpeg;
        this.size = size;
    }

    void generate(Path source, Path target) throws IOException {
        Path program = ffmpeg.path();
        String error = run(command(program, source, target, true));
        if (Files.size(target) == 0) {
            error = run(command(program, source, target, false)); // shorter than 1 s: take the first frame
        }
        if (Files.size(target) == 0) {
            throw new IOException("ffmpeg made no picture" + (error.isEmpty() ? "" : ": " + error));
        }
    }

    /**
     * The {@code ffmpeg} arguments: quiet, one frame, long edge at most {@code size} (never enlarged), one
     * JPEG written to {@code target} (the "mjpeg" format writes a single frame as a plain JPEG file).
     */
    List<String> command(Path program, Path source, Path target, boolean seek) {
        List<String> command = new ArrayList<>(List.of(program.toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-threads", "1"));
        if (seek) {
            command.addAll(List.of("-ss", FRAME_AT)); // before -i: jumps to the nearest key frame, then decodes on
        }
        command.addAll(List.of("-i", source.toString(), "-frames:v", "1", "-an",
                "-vf", "scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease".formatted(size, size),
                "-q:v", "3", "-f", "mjpeg", "-y", target.toString()));
        return command;
    }

    /** Runs {@code command}; returns what it printed (its errors). Gives up after a minute. */
    static String run(List<String> command) throws IOException {
        return ExternalProgram.run(command, TIMEOUT_SECONDS);
    }
}
