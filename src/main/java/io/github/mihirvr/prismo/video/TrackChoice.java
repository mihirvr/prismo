package io.github.mihirvr.prismo.video;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Which sound track and subtitles a video starts with (P8). Pure rules, no FFmpeg and no JavaFX, so they are
 * unit-tested; the viewer applies them and the app keeps what they need ({@code db.TrackChoices}, two settings).
 *
 * <ol>
 *   <li><b>What the user picked for this video</b> ({@link Picked}): the sound track, the subtitles (a track inside
 *       the video, an {@code .srt} file, or Off) and the subtitle delay. Used as long as it still exists: a track
 *       number the file no longer has, or an {@code .srt} file that is gone, falls back to rule 2.</li>
 *   <li><b>The language picked last</b> (any video), separately for sound and subtitles; for subtitles "Off" counts
 *       too ({@link #OFF}). Used when the video has a track in that language.</li>
 *   <li><b>Otherwise as before P8:</b> the sound track the file marks as default (else the first); subtitles from a
 *       file next to the video turned on, else off.</li>
 * </ol>
 */
public final class TrackChoice {

    /** The subtitle language setting's value for "Off was picked last". */
    public static final String OFF = "off";

    private TrackChoice() {
    }

    /** How subtitles were picked. */
    public enum Kind {
        OFF, TRACK, FILE;

        /** The database's word for it: "off", "track", "file". */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The database's word back; null or unknown → null. */
        public static Kind fromWord(String word) {
            for (Kind kind : values()) {
                if (kind.word().equals(word)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * Subtitles the user picked: Off, the track with FFmpeg stream number {@code stream}, or the file {@code file}.
     * The file is kept as just its name when it is in the video's folder (so it still works after the video and its
     * subtitles are moved together), else as a full path ({@link #file(Path, Path)}).
     */
    public record SubtitlePick(Kind kind, int stream, String file) {

        public static final SubtitlePick SUBTITLES_OFF = new SubtitlePick(Kind.OFF, -1, null);

        public SubtitlePick {
            Objects.requireNonNull(kind);
            if (kind == Kind.TRACK && stream < 0 || kind == Kind.FILE && (file == null || file.isBlank())) {
                throw new IllegalArgumentException("incomplete subtitle pick: " + kind + " " + stream + " " + file);
            }
        }

        public static SubtitlePick track(int stream) {
            return new SubtitlePick(Kind.TRACK, stream, null);
        }

        /** {@code file} picked for {@code video}: its name if it sits next to the video, else its full path. */
        public static SubtitlePick file(Path video, Path file) {
            Path absolute = file.toAbsolutePath().normalize();
            Path folder = video.toAbsolutePath().normalize().getParent();
            boolean nextToIt = folder != null && folder.equals(absolute.getParent());
            return new SubtitlePick(Kind.FILE, -1, nextToIt ? absolute.getFileName().toString() : absolute.toString());
        }

        /** The picked file for {@code video}: a bare name is looked for in the video's folder. FILE picks only. */
        public Path fileFor(Path video) {
            Path saved = Path.of(file);
            if (saved.isAbsolute()) {
                return saved.normalize();
            }
            Path folder = video.toAbsolutePath().normalize().getParent();
            return (folder == null ? saved : folder.resolve(saved)).toAbsolutePath().normalize();
        }
    }

    /**
     * What the user picked for one video. {@code audioStream} −1 and {@code subtitles} null mean "not picked" (the
     * language rule decides). The delay counts only with subtitles on; 0 = as the file says.
     */
    public record Picked(int audioStream, SubtitlePick subtitles, long subtitleDelayMicros) {

        public static final Picked NOTHING = new Picked(-1, null, 0);

        public Picked withAudio(int stream) {
            return new Picked(stream, subtitles, subtitleDelayMicros);
        }

        public Picked withSubtitles(SubtitlePick pick) {
            return new Picked(audioStream, pick, subtitleDelayMicros);
        }

        public Picked withDelay(long micros) {
            return new Picked(audioStream, subtitles, micros);
        }

        public boolean isEmpty() {
            return equals(NOTHING);
        }
    }

    /** The subtitles to start with: {@code sources} (with a remembered file added if needed) and the one to turn on. */
    public record SubtitleStart(List<SubtitleSources.Source> sources, int choice) {
    }

    /**
     * The sound track to play: the remembered stream if the file still has it, else the first track in
     * {@code language} (the one the file marks default, if several are), else {@code fileDefault}.
     *
     * @param remembered the stream picked for this video, or −1
     * @param language   the English name of the language picked last ("English"), or null
     */
    public static int audioStream(List<Track> tracks, int fileDefault, int remembered, String language) {
        if (remembered >= 0 && tracks.stream().anyMatch(track -> track.stream() == remembered)) {
            return remembered;
        }
        if (language != null) {
            List<Track> inLanguage = tracks.stream().filter(track -> language.equalsIgnoreCase(track.languageName()))
                    .toList();
            if (!inLanguage.isEmpty()) {
                return inLanguage.stream().filter(Track::isDefault).findFirst().orElse(inLanguage.getFirst()).stream();
            }
        }
        return fileDefault;
    }

    /**
     * The subtitles to turn on when {@code video} opens.
     *
     * @param sources    the choices ({@link SubtitleSources#forVideo}): files next to the video first, then its tracks
     * @param remembered what was picked for this video, or null
     * @param language   the subtitle language picked last ("English"), {@link #OFF}, or null
     * @param exists     whether a remembered file is still there (a file check: the caller runs this off the UI thread)
     * @return the sources (plus a remembered file from elsewhere) and the index to turn on, −1 for Off
     */
    public static SubtitleStart subtitles(Path video, List<SubtitleSources.Source> sources, SubtitlePick remembered,
            String language, Predicate<Path> exists) {
        if (remembered != null) {
            switch (remembered.kind()) {
                case OFF -> {
                    return new SubtitleStart(sources, -1);
                }
                case TRACK -> {
                    for (int i = 0; i < sources.size(); i++) {
                        SubtitleSources.Source source = sources.get(i);
                        if (source.file() == null && source.stream() == remembered.stream() && source.supported()) {
                            return new SubtitleStart(sources, i);
                        }
                    }
                }
                case FILE -> {
                    Path file = remembered.fileFor(video);
                    if (exists.test(file)) {
                        List<SubtitleSources.Source> withFile = SubtitleSources.withChosenFile(sources, file);
                        for (int i = 0; i < withFile.size(); i++) {
                            if (file.equals(normalised(withFile.get(i).file()))) {
                                return new SubtitleStart(withFile, i);
                            }
                        }
                    }
                }
            }
        }
        if (OFF.equals(language)) {
            return new SubtitleStart(sources, -1);
        }
        if (language != null) {
            for (int i = 0; i < sources.size(); i++) {
                SubtitleSources.Source source = sources.get(i);
                if (source.supported() && language.equalsIgnoreCase(source.language())) {
                    return new SubtitleStart(sources, i);
                }
            }
        }
        boolean fileFirst = !sources.isEmpty() && sources.getFirst().file() != null;
        return new SubtitleStart(sources, fileFirst ? 0 : -1);
    }

    /**
     * The subtitle language setting after the user picks {@code source} (null = Off): its language, or
     * {@link #OFF}; {@code before} when the source's language is unknown (a file named only like the video).
     */
    public static String subtitleLanguageAfter(SubtitleSources.Source source, String before) {
        if (source == null) {
            return OFF;
        }
        return source.language() != null ? source.language() : before;
    }

    /** The sound language setting after the user picks {@code track}: its language, or {@code before} if unknown. */
    public static String audioLanguageAfter(Track track, String before) {
        String language = track.languageName();
        return language != null ? language : before;
    }

    private static Path normalised(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
