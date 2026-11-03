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
 * often a fade-in or a title card: plain black or white tiles):
 * <ol>
 *   <li>the cover picture stored in the file, if it has one (MP4 "cover art", an MKV {@code cover.jpg}): ffmpeg
 *       lists it as an "attached pic" stream;</li>
 *   <li>else the frame at {@link #FIRST_SHARE} of the length, at least {@link #MIN_SECONDS} and at most
 *       {@link #MAX_SECONDS} in (a video shorter than that gets its first frame);</li>
 *   <li>if that frame is almost black, almost white or one flat colour ({@link Look}), the frames at
 *       {@link #RETRY_SHARES} are tried too and the best one is kept. (Our own rule: video sites pick a few frames
 *       spread over the video, but none publishes how.)</li>
 * </ol>
 * One quick extra {@code ffmpeg} run reads the length and finds the cover; it decodes nothing.
 *
 * <p>{@code ffmpeg} turns phone videos upright by itself (from the rotation tag) and decodes single-threaded, so the
 * thumbnail pool's thread count stays the CPU limit.
 */
final class VideoThumbnailer {

    private static final Logger log = LoggerFactory.getLogger(VideoThumbnailer.class);

    /**
     * Raised when video thumbnails are made differently, so the ones already made are made again once (see
     * {@link ThumbnailGenerator#VIDEO_REVISION}). 1 = 1.1.0: cover, frame at 10 %, brightness check.
     */
    static final int REVISION = 1;
    static final double FIRST_SHARE = 0.10;
    static final double MIN_SECONDS = 1;
    static final double MAX_SECONDS = 60;
    static final double[] RETRY_SHARES = {0.25, 0.50, 0.75};
    /** A video shorter than this keeps its first try: other frames of a few seconds look much the same. */
    static final double MIN_SECONDS_TO_RETRY = 4;
    private static final long TIMEOUT_SECONDS = 60;
    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)");
    /** "Stream #0:1[0x2](und): Video: mjpeg …, 600x600 … (attached pic)"; the number after "#0:" is its index. */
    private static final Pattern COVER = Pattern.compile("Stream #0:(\\d+)\\S*: Video: .*\\(attached pic\\)");

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
        Facts facts = Facts.parse(run(probeCommand(program, source)));
        if (facts.coverStream() >= 0) {
            String error = run(coverCommand(program, source, target, facts.coverStream()));
            if (Files.size(target) > 0) {
                return;
            }
            log.debug("The cover of {} could not be read ({}): a frame instead", source, error.strip());
        }
        double first = firstFrameAt(facts.seconds());
        String error = run(frameCommand(program, source, target, first));
        if (Files.size(target) == 0 && first > 0) {
            error = run(frameCommand(program, source, target, 0)); // shorter than it said: the first frame
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
                run(frameCommand(program, source, candidate, facts.seconds() * share));
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

    /** The cover stream as the thumbnail. */
    List<String> coverCommand(Path program, Path source, Path target, int stream) {
        List<String> command = new ArrayList<>(List.of(program.toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-threads", "1", "-i", source.toString(), "-map", "0:" + stream));
        command.addAll(output(target));
        return command;
    }

    /**
     * One frame at {@code seconds} (0 = the first frame): quiet, long edge at most {@code size} (never enlarged), one
     * JPEG written to {@code target} (the "mjpeg" format writes a single frame as a plain JPEG file).
     */
    List<String> frameCommand(Path program, Path source, Path target, double seconds) {
        List<String> command = new ArrayList<>(List.of(program.toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-threads", "1"));
        if (seconds > 0) {
            // Before -i: jumps to the key frame before, then decodes up to the exact time.
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
     * What ffmpeg says about a file: its length in seconds (0 if unknown) and the index of its cover stream (-1 if
     * none). {@code "0:V:0"} in {@link #frameCommand} (capital V) picks the first real video stream, never the cover.
     */
    record Facts(double seconds, int coverStream) {

        static Facts parse(String ffmpegOutput) {
            double seconds = 0;
            Matcher duration = DURATION.matcher(ffmpegOutput);
            if (duration.find()) {
                seconds = Integer.parseInt(duration.group(1)) * 3600 + Integer.parseInt(duration.group(2)) * 60
                        + Double.parseDouble(duration.group(3));
            }
            int cover = -1;
            for (String line : ffmpegOutput.split("\\R")) {
                Matcher stream = COVER.matcher(line);
                if (stream.find()) {
                    cover = Integer.parseInt(stream.group(1));
                    break;
                }
            }
            return new Facts(seconds, cover);
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
