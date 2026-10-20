package io.github.mihirvr.prismo.video;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Where a video's subtitles can come from, as the subtitle menu lists them, and loading the chosen one.
 *
 * <ul>
 *   <li><b>Files next to the video</b>: {@code Holiday.srt}, {@code Holiday.en.srt}, {@code Holiday.Hindi.srt}
 *       for {@code Holiday.mp4} (the usual naming; any case). The part between the names says the language.</li>
 *   <li><b>Tracks inside the video</b> (MKV, MP4): text ones are converted to SRT by the bundled {@code ffmpeg}
 *       in a separate process (like video thumbnails: a damaged file can't crash Prismo). Picture subtitles
 *       (Blu-ray, DVD) are listed but can't be chosen.</li>
 * </ul>
 */
public final class SubtitleSources {

    private static final long EXTRACT_TIMEOUT_SECONDS = 60;

    /** Finds the {@code ffmpeg} program (unpacking it can be slow, first time only). */
    @FunctionalInterface
    public interface Program {
        Path path() throws IOException;
    }

    /**
     * One choice in the menu: a file ({@code file} set) or a track inside the video ({@code stream} ≥ 0).
     * {@code supported} is false for picture subtitles.
     */
    public record Source(String label, Path file, int stream, boolean supported) {
    }

    private SubtitleSources() {
    }

    /** Every choice for {@code video}: files next to it first (sorted by name), then its own tracks. */
    public static List<Source> forVideo(Path video, List<Track> tracks) {
        List<Source> sources = new ArrayList<>(filesNextTo(video));
        int number = 0;
        for (Track track : tracks) {
            number++;
            String label = track.label(number) + (track.text() ? "" : " (picture subtitles, can't be shown)");
            sources.add(new Source(label, null, track.stream(), track.text()));
        }
        return List.copyOf(sources);
    }

    /**
     * {@code sources} with an {@code .srt} file the user picked ("Choose subtitle file…") added at the end, named by
     * its file name; unchanged if that file is already one of them.
     */
    public static List<Source> withChosenFile(List<Source> sources, Path file) {
        Path wanted = file.toAbsolutePath().normalize();
        for (Source source : sources) {
            if (source.file() != null && source.file().toAbsolutePath().normalize().equals(wanted)) {
                return sources;
            }
        }
        List<Source> more = new ArrayList<>(sources);
        more.add(new Source(wanted.getFileName().toString(), wanted, -1, true));
        return List.copyOf(more);
    }

    /** {@code .srt} files named like {@code video} in its folder. A folder that can't be read gives none. */
    static List<Source> filesNextTo(Path video) {
        Path folder = video.toAbsolutePath().getParent();
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.{srt,SRT,Srt}")) {
            for (Path file : files) {
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.equals(base + ".srt") || lower.startsWith(base + ".") && Files.isRegularFile(file)) {
                    found.add(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        List<Source> sources = new ArrayList<>();
        for (Path file : found) {
            String fileName = file.getFileName().toString();
            String middle = fileName.substring(base.length(), fileName.length() - ".srt".length());
            middle = middle.startsWith(".") ? middle.substring(1) : middle;
            sources.add(new Source(fileLabel(middle, fileName), file, -1, true));
        }
        return sources;
    }

    /** "English (file)" for {@code .en.srt}, "Hindi (file)" for {@code .Hindi.srt}, else the file's name. */
    private static String fileLabel(String middle, String fileName) {
        if (middle.isBlank()) {
            return fileName;
        }
        String language = new Track(-1, middle, "", false, true).languageName();
        return (language != null ? language : middle) + " (file)";
    }

    /** Reads the chosen subtitles. Slow for a track inside a long video ({@code ffmpeg} reads the whole file). */
    public static Subtitles load(Source source, Path video, Program ffmpeg) throws IOException {
        if (!source.supported()) {
            throw new IOException("picture subtitles can't be shown");
        }
        if (source.file() != null) {
            return Subtitles.parseSrt(Subtitles.decode(Files.readAllBytes(source.file())));
        }
        Path out = Files.createTempFile("prismo-subtitles-", ".srt");
        try {
            String errors = ExternalProgram.run(List.of(ffmpeg.path().toString(), "-nostdin", "-hide_banner",
                    "-loglevel", "error", "-i", video.toString(), "-map", "0:" + source.stream(), "-f", "srt", "-y",
                    out.toString()), EXTRACT_TIMEOUT_SECONDS);
            Subtitles subtitles = Subtitles.parseSrt(Subtitles.decode(Files.readAllBytes(out)));
            if (subtitles.isEmpty() && !errors.isEmpty()) {
                throw new IOException("ffmpeg couldn't read the subtitles: " + errors);
            }
            return subtitles;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
