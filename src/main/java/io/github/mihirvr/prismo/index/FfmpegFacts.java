package io.github.mihirvr.prismo.index;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the bundled {@code ffmpeg} reads from a file's header (2.0.0): the length, the picture size and the
 * {@code creation_time} tag, for files the metadata library can't read (MPEG-TS / MTS, WMV, FLV, MPG, OGV, AVIF).
 * Without it those tiles have no length badge and are in neither Landscape nor Portrait.
 *
 * <p>{@code ffmpeg -i file} only reads the header, prints what it found and stops (it has no output file). Several
 * files go into one run, one {@code -i} each, because starting ffmpeg is most of the cost: about 100 ms for one file
 * and about 100 ms for six (bundled ffmpeg, measured 2026-11-27). ffmpeg stops at the first file it can't open, after
 * printing the ones before it; the run goes on from the file after that one.
 *
 * <p>A separate process, for the same reason as the thumbnails: a crash in native code only ends that one run.
 */
public final class FfmpegFacts {

    private static final Logger log = LoggerFactory.getLogger(FfmpegFacts.class);
    /** Files per ffmpeg run. */
    static final int BATCH = 24;
    /** Characters of paths per run: Windows allows a command line of 32,767. */
    private static final int MAX_PATH_CHARS = 20_000;
    private static final long TIMEOUT_SECONDS = 60;

    private static final Pattern INPUT = Pattern.compile("^Input #(\\d+), ");
    private static final Pattern FAILED = Pattern.compile("^\\[in#(\\d+) @ [^]]*] Error opening input");
    private static final Pattern DURATION = Pattern.compile("^\\s+Duration: (\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)");
    private static final Pattern CREATION = Pattern.compile("^\\s+creation_time\\s*: (\\S+)");
    private static final Pattern VIDEO = Pattern.compile("^\\s+Stream #\\d+:\\d+.*?: Video: ");
    // ", 640x360" (the comma keeps out codec tags like "0x001B").
    private static final Pattern SIZE = Pattern.compile(", (\\d{1,5})x(\\d{1,5})\\b");
    private static final Pattern ROTATION = Pattern.compile("rotation of (-?\\d+(?:\\.\\d+)?) degrees");

    /** Finds the {@code ffmpeg} program. */
    @FunctionalInterface
    public interface Program {
        Path path() throws IOException;
    }

    /**
     * What ffmpeg found; each part null if it said nothing about it.
     *
     * @param seconds  the length
     * @param width    the first picture's width as shown (a picture turned a quarter turn has its sides swapped)
     * @param height   its height as shown
     * @param created  the {@code creation_time} tag, a real instant (UTC)
     */
    public record Facts(Double seconds, Integer width, Integer height, Instant created) {
        static final Facts NONE = new Facts(null, null, null, null);
    }

    private final Program program;

    public FfmpegFacts(Program program) {
        this.program = program;
    }

    /** The facts of each file, in the same order; {@link Facts#NONE} for one ffmpeg couldn't read. */
    public List<Facts> read(List<Path> files) {
        Facts[] facts = new Facts[files.size()];
        Arrays.fill(facts, Facts.NONE);
        Path ffmpeg;
        try {
            ffmpeg = program.path();
        } catch (IOException e) {
            log.warn("No ffmpeg for reading file headers: {}", e.toString());
            return List.of(facts);
        }
        int from = 0;
        while (from < files.size()) {
            List<Path> batch = batchFrom(files, from);
            Run run;
            try {
                run = parse(ExternalProgram.run(command(ffmpeg, batch), TIMEOUT_SECONDS), batch.size());
            } catch (IOException e) {
                log.debug("ffmpeg could not read the headers of {} files from {}: {}", batch.size(), batch.getFirst(),
                        e.toString());
                from += batch.size();
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                if (run.facts().get(i) != null) {
                    facts[from + i] = run.facts().get(i);
                }
            }
            // ffmpeg stopped at a file it couldn't open: go on after that one.
            from += run.failedAt() >= 0 ? run.failedAt() + 1 : batch.size();
        }
        return List.of(facts);
    }

    private static List<Path> batchFrom(List<Path> files, int from) {
        List<Path> batch = new ArrayList<>();
        int chars = 0;
        for (int i = from; i < files.size() && batch.size() < BATCH; i++) {
            int length = files.get(i).toString().length() + 4;
            if (!batch.isEmpty() && chars + length > MAX_PATH_CHARS) {
                break;
            }
            batch.add(files.get(i));
            chars += length;
        }
        return batch;
    }

    static List<String> command(Path ffmpeg, List<Path> files) {
        List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-hide_banner"));
        for (Path file : files) {
            command.add("-i");
            command.add(file.toString());
        }
        return command;
    }

    /**
     * What one run printed, per input ({@code null} for one it didn't print), and the input it couldn't open (-1 if
     * none).
     */
    record Run(List<Facts> facts, int failedAt) {
    }

    static Run parse(String output, int inputs) {
        List<Facts> facts = new ArrayList<>();
        for (int i = 0; i < inputs; i++) {
            facts.add(null);
        }
        int failedAt = -1;
        int current = -1;
        Double seconds = null;
        Integer width = null;
        Integer height = null;
        Instant created = null;
        boolean inVideo = false;
        for (String line : output.split("\\R")) {
            Matcher input = INPUT.matcher(line);
            Matcher failed = FAILED.matcher(line);
            boolean isInput = input.find();
            boolean isFailed = !isInput && failed.find();
            if (isInput || isFailed) {
                if (current >= 0 && current < inputs) {
                    facts.set(current, new Facts(seconds, width, height, created));
                }
                current = isInput ? Integer.parseInt(input.group(1)) : -1;
                seconds = null;
                width = null;
                height = null;
                created = null;
                inVideo = false;
                if (isFailed && failedAt < 0) {
                    failedAt = Integer.parseInt(failed.group(1));
                }
                continue;
            }
            if (current < 0) {
                continue;
            }
            Matcher duration = DURATION.matcher(line);
            if (duration.find()) {
                double value = Integer.parseInt(duration.group(1)) * 3600 + Integer.parseInt(duration.group(2)) * 60
                        + Double.parseDouble(duration.group(3));
                seconds = value > 0 ? value : null;
                continue;
            }
            Matcher creation = CREATION.matcher(line);
            if (creation.find() && created == null) {
                created = instant(creation.group(1));
                continue;
            }
            if (line.stripLeading().startsWith("Stream #")) {
                // The first picture that isn't a cover image (an MP3's or MKV's attached picture).
                inVideo = width == null && VIDEO.matcher(line).find() && !line.contains("(attached pic)");
                if (inVideo) {
                    Matcher size = SIZE.matcher(line.substring(line.indexOf(": Video: ")));
                    if (size.find()) {
                        width = Integer.parseInt(size.group(1));
                        height = Integer.parseInt(size.group(2));
                    }
                }
                continue;
            }
            Matcher rotation = ROTATION.matcher(line);
            if (inVideo && width != null && rotation.find()) {
                long quarter = Math.round(Math.abs(Double.parseDouble(rotation.group(1))) / 90);
                if (quarter % 2 == 1) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
                inVideo = false; // turned once
            }
        }
        if (current >= 0 && current < inputs) {
            facts.set(current, new Facts(seconds, width, height, created));
        }
        if (failedAt < 0 && output.contains("Error opening input")) {
            failedAt = facts.indexOf(null); // no "[in#N" line: the first file it didn't print
        }
        return new Run(facts, failedAt);
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
