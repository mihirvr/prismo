package io.github.mihirvr.prismo.viewer;

import com.drew.lang.GeoLocation;
import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import io.github.mihirvr.prismo.places.GeoPoint;
import io.github.mihirvr.prismo.index.MetadataReader;
import io.github.mihirvr.prismo.ui.MediaDetails;
import io.github.mihirvr.prismo.ui.MediaDetails.Line;
import io.github.mihirvr.prismo.video.Track;
import io.github.mihirvr.prismo.video.VideoPlayer;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the Info panel shows, as titled sections of "label: value" lines: Details (from the index), Camera and
 * Location (a photo's EXIF GPS or, 1.4.0, a video's location; with the nearest place's name), Video (what the player read from the file) and the full Path.
 * Anything unknown is left out, and a section with no lines is left out too. Pure, so the wording is unit-tested.
 */
final class InfoSections {

    /** One titled group of lines. */
    record Section(String title, List<Line> lines) {
    }

    /** FFmpeg's short format names → the names people know. Anything else is shown in capitals. */
    private static final Map<String, String> FORMATS = Map.ofEntries(
            Map.entry("h264", "H.264 (AVC)"), Map.entry("hevc", "H.265 (HEVC)"), Map.entry("av1", "AV1"),
            Map.entry("vp8", "VP8"), Map.entry("vp9", "VP9"), Map.entry("mpeg4", "MPEG-4 Part 2"),
            Map.entry("mpeg2video", "MPEG-2"), Map.entry("mpeg1video", "MPEG-1"), Map.entry("mjpeg", "Motion JPEG"),
            Map.entry("prores", "ProRes"), Map.entry("theora", "Theora"), Map.entry("aac", "AAC"),
            Map.entry("mp3", "MP3"), Map.entry("mp2", "MP2"), Map.entry("opus", "Opus"), Map.entry("vorbis", "Vorbis"),
            Map.entry("flac", "FLAC"), Map.entry("alac", "ALAC"), Map.entry("ac3", "Dolby Digital (AC-3)"),
            Map.entry("eac3", "Dolby Digital Plus (E-AC-3)"), Map.entry("truehd", "Dolby TrueHD"),
            Map.entry("dts", "DTS"));

    private InfoSections() {
    }

    /**
     * The panel's sections for one item.
     *
     * @param zone     this computer's time zone (dates modified / added)
     * @param metadata the photo's metadata read from the file, or null (a video, or it couldn't be read)
     * @param video    what the player found in the video, or null (a photo, or the video isn't open yet / can't be)
     */
    static List<Section> build(MediaDetails details, ZoneId zone, Metadata metadata, VideoPlayer.Info video) {
        return build(details, zone, metadata, video, _ -> Optional.empty());
    }

    /**
     * As above, with {@code place}: the words for a spot ("Pune, Maharashtra, India", from {@code Places}), or empty
     * when there are none (yet).
     */
    static List<Section> build(MediaDetails details, ZoneId zone, Metadata metadata, VideoPlayer.Info video,
            Function<GeoPoint, Optional<String>> place) {
        List<Section> sections = new ArrayList<>();
        add(sections, "Details", details.lines(zone));
        if (!details.people().isEmpty()) { // 1.2.0: the people tagged in it
            add(sections, "People", List.of(new Line("", String.join(", ", details.people()))));
        }
        if (!details.categories().isEmpty()) { // 1.3.0: its categories
            add(sections, "Categories", List.of(new Line("", String.join(", ", details.categories()))));
        }
        List<Line> camera = new ArrayList<>();
        if (!details.camera().isEmpty()) {
            camera.add(new Line("Camera", details.camera()));
        } else if (video != null && !video.device().isEmpty()) {
            camera.add(new Line("Camera", video.device())); // 1.4.0: the phone that made the video
        }
        if (metadata != null) {
            camera.addAll(photoLines(metadata));
        }
        add(sections, "Camera", camera);
        // 1.4.0: the place name the file itself carries (a camera's or an editor's), else the nearest place to its
        // GPS; then the GPS. Videos too.
        GeoPoint at = point(metadata, video);
        String written = metadata == null ? "" : writtenPlace(metadata);
        List<Line> location = new ArrayList<>();
        if (!written.isEmpty()) {
            location.add(new Line("Place", written));
        } else if (at != null) {
            place.apply(at).ifPresent(words -> location.add(new Line("Place", words)));
        }
        if (at != null) {
            location.add(new Line("GPS", at.text()));
        }
        add(sections, "Location", location);
        if (details.isVideo()) {
            add(sections, "Video", videoLines(details, video));
        }
        if (details.isAudio()) {
            add(sections, "Song", songLines(video));
            add(sections, "Audio", audioLines(details, video));
        }
        add(sections, "Path", List.of(new Line("", details.path().toString())));
        return sections;
    }

    private static void add(List<Section> sections, String title, List<Line> lines) {
        if (!lines.isEmpty()) {
            sections.add(new Section(title, List.copyOf(lines)));
        }
    }

    /** Lens, exposure, aperture, ISO, focal length and flash, from the EXIF block. */
    static List<Line> photoLines(Metadata metadata) {
        List<Line> lines = new ArrayList<>();
        ExifSubIFDDirectory exif = MetadataReader.photoExif(metadata); // a RAW file can have several (P7)
        if (exif == null) {
            return lines;
        }
        String lens = lens(exif.getString(ExifDirectoryBase.TAG_LENS_MAKE), exif.getString(ExifDirectoryBase.TAG_LENS_MODEL));
        if (!lens.isEmpty()) {
            lines.add(new Line("Lens", lens));
        }
        double exposure = positive(exif.getRational(ExifDirectoryBase.TAG_EXPOSURE_TIME));
        if (exposure > 0) {
            lines.add(new Line("Exposure", exposure(exposure)));
        }
        double fNumber = positive(exif.getRational(ExifDirectoryBase.TAG_FNUMBER));
        if (fNumber > 0) {
            lines.add(new Line("Aperture", String.format(Locale.ROOT, "f/%.1f", fNumber)));
        }
        Integer iso = exif.getInteger(ExifDirectoryBase.TAG_ISO_EQUIVALENT);
        if (iso != null && iso > 0) {
            lines.add(new Line("ISO", Integer.toString(iso)));
        }
        double focal = positive(exif.getRational(ExifDirectoryBase.TAG_FOCAL_LENGTH));
        if (focal > 0) {
            Integer equivalent = exif.getInteger(ExifDirectoryBase.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH);
            String text = number(focal, 1) + " mm";
            if (equivalent != null && equivalent > 0 && Math.abs(equivalent - focal) >= 0.5) {
                text += " (" + equivalent + " mm in 35 mm terms)";
            }
            lines.add(new Line("Focal length", text));
        }
        Integer flash = exif.getInteger(ExifDirectoryBase.TAG_FLASH);
        if (flash != null) {
            lines.add(new Line("Flash", flash(flash)));
        }
        return lines;
    }

    /** XMP names of the same place fields, for files that carry XMP and no IPTC (Lightroom, many editors). */
    private static final String[] XMP_PLACE = {"Iptc4xmpCore:Location", "photoshop:City", "photoshop:State",
        "photoshop:Country"};

    /**
     * The place written in the file as text (1.4.0): IPTC sub-location, city, state and country, else the same from
     * XMP; "" if none. Repeats are dropped ("Delhi, Delhi, India" shows as "Delhi, India").
     */
    static String writtenPlace(Metadata metadata) {
        List<String> parts = new ArrayList<>();
        IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
        if (iptc != null) {
            for (int tag : new int[] {IptcDirectory.TAG_SUB_LOCATION, IptcDirectory.TAG_CITY,
                    IptcDirectory.TAG_PROVINCE_OR_STATE, IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_NAME}) {
                addPart(parts, iptc.getString(tag));
            }
        }
        if (parts.isEmpty()) {
            XmpDirectory xmp = metadata.getFirstDirectoryOfType(XmpDirectory.class);
            if (xmp != null) {
                Map<String, String> properties = xmp.getXmpProperties();
                for (String key : XMP_PLACE) {
                    addPart(parts, properties.get(key));
                }
            }
        }
        return String.join(", ", parts);
    }

    private static void addPart(List<String> parts, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        String trimmed = part.strip();
        if (parts.stream().noneMatch(trimmed::equalsIgnoreCase)) {
            parts.add(trimmed);
        }
    }

    /** "GPS: 18.520430, 73.856743" (latitude, longitude; south and west negative), or nothing. */
    static List<Line> location(Metadata metadata) {
        GeoPoint at = point(metadata, null);
        return at == null ? List.of() : List.of(new Line("GPS", at.text()));
    }

    /**
     * Where it was taken: a photo's EXIF GPS, or (1.4.0) the location a phone wrote into a video; null if neither
     * (0, 0 is a phone that had no fix, not a place).
     */
    static GeoPoint point(Metadata metadata, VideoPlayer.Info video) {
        if (metadata != null) {
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            GeoLocation at = gps == null ? null : gps.getGeoLocation();
            if (at != null && !at.isZero() && Math.abs(at.getLatitude()) <= 90 && Math.abs(at.getLongitude()) <= 180) {
                return new GeoPoint(at.getLatitude(), at.getLongitude());
            }
        }
        return video == null ? null : GeoPoint.parseIso6709(video.location()).orElse(null);
    }

    /**
     * Length, resolution (as shown: a phone's sideways video turned upright), frame rate, the picture's and sound's
     * formats, and the tracks. Until the player has opened the video ({@code video} null), the length and size the
     * index knows.
     */
    static List<Line> videoLines(MediaDetails details, VideoPlayer.Info video) {
        List<Line> lines = new ArrayList<>();
        double seconds = video != null && video.durationMicros() > 0 ? video.durationMicros() / 1e6 : details.duration();
        if (seconds > 0) {
            lines.add(new Line("Length", MediaDetails.length(seconds)));
        }
        if (video != null && video.hasPicture() && video.width() > 0 && video.height() > 0) {
            boolean sideways = video.rotation() % 180 != 0;
            lines.add(new Line("Resolution", MediaDetails.dimensions(sideways ? video.height() : video.width(),
                    sideways ? video.width() : video.height())));
        } else if (details.width() > 0 && details.height() > 0) {
            lines.add(new Line("Resolution", MediaDetails.dimensions(details.width(), details.height())));
        }
        if (video == null) {
            return lines;
        }
        if (video.hasPicture() && video.frameRate() > 0) {
            lines.add(new Line("Frame rate", number(video.frameRate(), 3) + " fps"));
        }
        if (!video.videoCodec().isEmpty()) {
            lines.add(new Line("Video format", format(video.videoCodec())));
        }
        if (!video.audioCodec().isEmpty()) {
            lines.add(new Line("Sound format", format(video.audioCodec())));
        }
        List<String> sound = new ArrayList<>();
        for (int i = 0; i < video.audioTracks().size(); i++) {
            sound.add(video.audioTracks().get(i).label(i + 1));
        }
        if (!sound.isEmpty()) {
            lines.add(new Line(sound.size() == 1 ? "Sound track" : "Sound tracks", String.join("\n", sound)));
        }
        List<String> subtitles = new ArrayList<>();
        for (int i = 0; i < video.subtitleTracks().size(); i++) {
            Track track = video.subtitleTracks().get(i);
            subtitles.add(track.label(i + 1) + (track.text() ? "" : " (picture subtitles: can't be shown)"));
        }
        if (!subtitles.isEmpty()) {
            lines.add(new Line("Subtitle tracks", String.join("\n", subtitles)));
        }
        return lines;
    }

    /** 2.0.0: a song's tags, in {@link VideoPlayer#SONG_TAGS} order; none known until the player has opened it. */
    static List<Line> songLines(VideoPlayer.Info audio) {
        List<Line> lines = new ArrayList<>();
        if (audio == null) {
            return lines;
        }
        Map<String, String> names = Map.of("title", "Title", "artist", "Artist", "album", "Album",
                "album_artist", "Album artist", "composer", "Composer", "genre", "Genre", "date", "Year", "track",
                "Track", "disc", "Disc");
        for (String key : VideoPlayer.SONG_TAGS) {
            String value = audio.tags().get(key);
            if (value != null) {
                lines.add(new Line(names.get(key), value));
            }
        }
        return lines;
    }

    /** 2.0.0: an audio file's length, format, sample rate, channels and bit rate. */
    static List<Line> audioLines(MediaDetails details, VideoPlayer.Info audio) {
        List<Line> lines = new ArrayList<>();
        double seconds = audio != null && audio.durationMicros() > 0 ? audio.durationMicros() / 1e6 : details.duration();
        if (seconds > 0) {
            lines.add(new Line("Length", MediaDetails.length(seconds)));
        }
        if (audio == null) {
            return lines;
        }
        if (!audio.audioCodec().isEmpty()) {
            lines.add(new Line("Format", format(audio.audioCodec())));
        }
        if (audio.sampleRate() > 0) {
            lines.add(new Line("Sample rate", number(audio.sampleRate() / 1000.0, 1) + " kHz"));
        }
        if (audio.channels() > 0) {
            lines.add(new Line("Channels", channels(audio.channels())));
        }
        if (audio.bitRate() > 0) {
            lines.add(new Line("Bit rate", Math.round(audio.bitRate() / 1000.0) + " kbps"));
        }
        return lines;
    }

    /** "Mono", "Stereo", "5.1 (6 channels)", "7.1 (8 channels)", else "N channels". */
    static String channels(int count) {
        return switch (count) {
            case 1 -> "Mono";
            case 2 -> "Stereo";
            case 6 -> "5.1 (6 channels)";
            case 8 -> "7.1 (8 channels)";
            default -> count + " channels";
        };
    }

    /** "H.264 (AVC)" for "h264"; any raw PCM is "PCM"; an unknown name in capitals. */
    static String format(String ffmpegName) {
        String name = ffmpegName.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith("pcm_")) {
            return "PCM";
        }
        return FORMATS.getOrDefault(name, name.toUpperCase(Locale.ROOT));
    }

    /** "1/250 s" under a second (as cameras show it), "2 s", "1.5 s" from a second up. */
    static String exposure(double seconds) {
        if (seconds >= 1) {
            return number(seconds, 1) + " s";
        }
        return "1/" + Math.round(1 / seconds) + " s";
    }

    /** EXIF's Flash value: bit 0 = it fired, bit 5 = the camera has no flash. */
    static String flash(int value) {
        if ((value & 0x20) != 0) {
            return "No flash";
        }
        return (value & 1) != 0 ? "Fired" : "Did not fire";
    }

    /** "Canon EF 50mm f/1.8 STM"; the make is left out when the model already starts with it. */
    static String lens(String make, String model) {
        String m = model == null ? "" : model.strip();
        String brand = make == null ? "" : make.strip();
        if (m.isEmpty() || brand.isEmpty() || m.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))) {
            return m;
        }
        return brand + " " + m;
    }

    /** The value of a rational that is there and above zero, else 0 (a 0/0 or missing tag). */
    static double positive(Rational value) {
        if (value == null || value.getDenominator() == 0) {
            return 0;
        }
        double result = value.doubleValue();
        return Double.isFinite(result) && result > 0 ? result : 0;
    }

    /** Up to {@code decimals} places, trailing zeros dropped: "29.97", "25", "4.2". */
    static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value).replaceAll("\\.?0+$", "");
    }
}
