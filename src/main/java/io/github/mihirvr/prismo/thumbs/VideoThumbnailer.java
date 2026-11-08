package io.github.mihirvr.prismo.thumbs;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A video's thumbnail as a small JPEG, made by the bundled {@code ffmpeg} program in a separate process.
 *
 * <p><b>Why a separate process</b> and not FFmpeg inside Prismo (as the player does): a damaged video can make
 * a decoder crash, and a crash in native code takes the whole JVM with it. Here it only ends that one
 * {@code ffmpeg} run, and the thumbnail is marked failed. Starting a process costs some milliseconds per video,
 * which doesn't matter for background thumbnails.
 *
 * <p><b>Which picture</b> (1.1.0; before, it was always the frame at 1 s, which in a TV episode or a phone video is
 * often a fade-in or a title card: plain black or white tiles). Always a frame of the video itself (1.2.0: a cover
 * picture stored in the file is never used; in downloaded series it is often the encoder group's logo, the same
 * tile for every episode):
 * <ol>
 *   <li>the frame at {@link #FIRST_SHARE} of the length, at least {@link #MIN_SECONDS} and at most
 *       {@link #MAX_SECONDS} in (a video shorter than that gets its first frame);</li>
 *   <li>if that frame is almost black, almost white or one flat colour ({@link Look}), the frames at
 *       {@link #RETRY_SHARES} are tried too and the best one is kept. (Our own rule: video sites pick a few frames
 *       spread over the video, but none publishes how.)</li>
 * </ol>
 * One quick extra {@code ffmpeg} run reads the length; it decodes nothing.
 *
 * <p><b>Only key frames are decoded</b> (1.2.0, {@code -skip_frame nokey}): a seek lands on the key frame before
 * the time and, to be exact, would decode every frame from there to the time. On a 1080p 10-bit HEVC file with a key
 * frame every 10 s that took 1.1-2.1 s per frame on one thread; key frames only, 0.17-0.34 s (measured 2026-11-08). A
 * thumbnail doesn't need the exact frame: the first key frame at or after the time is fine. (A file without a seek
 * index is not decoded from the start: ffmpeg reads up to the time instead, which only a slow disk notices.)
 *
 * <p>{@code ffmpeg} turns phone videos upright by itself (from the rotation tag) and decodes single-threaded, so the
 * thumbnail pool's thread count stays the CPU limit.
 */
final class VideoThumbnailer {

    private static final Logger log = LoggerFactory.getLogger(VideoThumbnailer.class);

    /**
     * Raised when video thumbnails are made differently, so the ones already made are made again once (see
     * {@link ThumbnailGenerator#VIDEO_REVISION}). 1 = 1.1.0: cover, frame at 10 %, brightness check. 2 = 1.2.0:
     * never the cover (every cover thumbnail of 1.1.0 is made again as a frame).
     */
    static final int REVISION = 2;
    static final double FIRST_SHARE = 0.10;
    static final double MIN_SECONDS = 1;
    static final double MAX_SECONDS = 60;
    static final double[] RETRY_SHARES = {0.25, 0.50, 0.75};
    /** A video shorter than this keeps its first try: other frames of a few seconds look much the same. */
    static final double MIN_SECONDS_TO_RETRY = 4;
    private static final long TIMEOUT_SECONDS = 60;
    /** A video thumbnail that takes longer than this is logged with its time and number of tries (1.2.0). */
    private static final long SLOW_MILLIS = 5_000;
    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)");

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
        long start = System.nanoTime();
        int[] frames = {0};
        try {
            generate(source, target, frames);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (millis > SLOW_MILLIS) {
                log.info("Video thumbnail for {} took {} ms ({} frame tries)", source, millis, frames[0]);
            }
        }
    }

    /** {@code frames[0]} counts the frame runs, for the slow-thumbnail log line. */
    private void generate(Path source, Path target, int[] frames) throws IOException {
        Path program = ffmpeg.path();
        Facts facts = Facts.parse(run(probeCommand(program, source)));
        double first = firstFrameAt(facts.seconds());
        String error = grab(program, source, target, first, frames);
        if (Files.size(target) == 0 && first > 0) {
            frames[0]++;
            error = run(frameCommand(program, source, target, 0, true)); // shorter than it said: the first frame
        }
        if (Files.size(target) == 0) {
            throw new IOException("ffmpeg made no picture" + (error.isEmpty() ? "" : ": " + error.strip()));
        }
        if (facts.seconds() < MIN_SECONDS_TO_RETRY) {
            return;
        }
        Look best = Look.of(target);
        if (best.good()) {
            return;
        }
        Path bestFile = target;
        List<Path> tries = new ArrayList<>();
        try {
            for (double share : RETRY_SHARES) {
                Path candidate = target.resolveSibling(target.getFileName() + "." + Math.round(share * 100) + ".jpg");
                tries.add(candidate);
                grab(program, source, candidate, facts.seconds() * share, frames);
                if (!Files.isRegularFile(candidate) || Files.size(candidate) == 0) {
                    continue;
                }
                Look look = Look.of(candidate);
                if (look.betterThan(best)) {
                    best = look;
                    bestFile = candidate;
                }
            }
            if (!bestFile.equals(target)) {
                Files.move(bestFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            for (Path candidate : tries) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    /**
     * The frame at {@code seconds} into {@code target}: key frames only first (fast, see the class comment); if that
     * gives no picture (no key frame after the time: a short clip with a single key frame at the start, say), the
     * exact frame, decoding every frame up to it (slow on a long file, but then the file is short or the time is near
     * its end). Returns what ffmpeg printed last.
     */
    private String grab(Path program, Path source, Path target, double seconds, int[] frames) throws IOException {
        frames[0]++;
        String error = run(frameCommand(program, source, target, seconds, true));
        if ((!Files.isRegularFile(target) || Files.size(target) == 0) && seconds > 0) {
            frames[0]++;
            error = run(frameCommand(program, source, target, seconds, false));
        }
        return error;
    }

    /** Where to take the first frame, in seconds; 0 = the first frame. An unknown length ({@code <= 0}): 1 s. */
    static double firstFrameAt(double seconds) {
        if (seconds <= 0) {
            return MIN_SECONDS;
        }
        if (seconds <= MIN_SECONDS) {
            return 0;
        }
        return Math.clamp(seconds * FIRST_SHARE, MIN_SECONDS, MAX_SECONDS);
    }

    /** Only reads the file's header: ffmpeg lists the streams and the length, then stops (no output file). */
    static List<String> probeCommand(Path program, Path source) {
        return List.of(program.toString(), "-nostdin", "-hide_banner", "-i", source.toString());
    }

    /**
     * One frame at {@code seconds} (0 = the first frame): quiet, long edge at most {@code size} (never enlarged), one
     * JPEG written to {@code target} (the "mjpeg" format writes a single frame as a plain JPEG file). With
     * {@code keyFramesOnly} only key frames are decoded, so the picture is the first key frame at or after
     * {@code seconds} (see the class comment); without, the exact frame.
     */
    List<String> frameCommand(Path program, Path source, Path target, double seconds, boolean keyFramesOnly) {
        List<String> command = new ArrayList<>(List.of(program.toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-threads", "1"));
        if (keyFramesOnly) {
            command.addAll(List.of("-skip_frame", "nokey"));
        }
        if (seconds > 0) {
            // Before -i: jumps to the key frame before the time (input option, like -skip_frame).
            command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", seconds)));
        }
        command.addAll(List.of("-i", source.toString(), "-map", "0:V:0"));
        command.addAll(output(target));
        return command;
    }

    private List<String> output(Path target) {
        return List.of("-frames:v", "1", "-an", "-sn",
                "-vf", "scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease".formatted(size, size),
                "-q:v", "3", "-f", "mjpeg", "-y", target.toString());
    }

    /** Runs {@code command}; returns what it printed (its errors). Gives up after a minute. */
    static String run(List<String> command) throws IOException {
        return ExternalProgram.run(command, TIMEOUT_SECONDS);
    }

    /**
     * What ffmpeg says about a file: its length in seconds (0 if unknown). {@code "0:V:0"} in {@link #frameCommand}
     * (capital V) picks the first real video stream, never a cover picture ("attached pic") stored in the file.
     */
    record Facts(double seconds) {

        static Facts parse(String ffmpegOutput) {
            double seconds = 0;
            Matcher duration = DURATION.matcher(ffmpegOutput);
            if (duration.find()) {
                seconds = Integer.parseInt(duration.group(1)) * 3600 + Integer.parseInt(duration.group(2)) * 60
                        + Double.parseDouble(duration.group(3));
            }
            return new Facts(seconds);
        }
    }

    /**
     * How a frame looks: its average brightness and its contrast (the spread of brightness), both 0 to 255. A frame
     * is good unless it is almost black, almost white or nearly one flat colour.
     */
    record Look(double brightness, double contrast) {

        static final double TOO_DARK = 28;
        static final double TOO_BRIGHT = 228;
        static final double FLAT = 12;

        boolean good() {
            return brightness >= TOO_DARK && brightness <= TOO_BRIGHT && contrast >= FLAT;
        }

        /** A good frame beats a bad one; otherwise more contrast wins. */
        boolean betterThan(Look other) {
            if (good() != other.good()) {
                return good();
            }
            return contrast > other.contrast;
        }

        static Look of(Path jpeg) throws IOException {
            BufferedImage image = ImageIO.read(jpeg.toFile());
            return image == null ? new Look(0, 0) : of(image);
        }

        /** Brightness as in video (BT.601 weights), from every second pixel each way: plenty for a thumbnail. */
        static Look of(BufferedImage image) {
            double sum = 0;
            double squares = 0;
            int count = 0;
            for (int y = 0; y < image.getHeight(); y += 2) {
                for (int x = 0; x < image.getWidth(); x += 2) {
                    int rgb = image.getRGB(x, y);
                    double luma = 0.299 * (rgb >> 16 & 0xFF) + 0.587 * (rgb >> 8 & 0xFF) + 0.114 * (rgb & 0xFF);
                    sum += luma;
                    squares += luma * luma;
                    count++;
                }
            }
            if (count == 0) {
                return new Look(0, 0);
            }
            double mean = sum / count;
            return new Look(mean, Math.sqrt(Math.max(0, squares / count - mean * mean)));
        }
    }
}
