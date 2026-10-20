package io.github.mihirvr.prismo.viewer;

import com.drew.lang.GeoLocation;
import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import io.github.mihirvr.prismo.ui.MediaDetails;
import io.github.mihirvr.prismo.ui.MediaDetails.Line;
import io.github.mihirvr.prismo.video.Track;
import io.github.mihirvr.prismo.video.VideoPlayer;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Info panel shows, as titled sections of "label: value" lines: Details (from the index), Camera and
 * Location (a photo's EXIF, read from the file), Video (what the player read from the file) and the full Path.
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
        List<Section> sections = new ArrayList<>();
        add(sections, "Details", details.lines(zone));
        List<Line> camera = new ArrayList<>();
        if (!details.camera().isEmpty()) {
            camera.add(new Line("Camera", details.camera()));
        }
        if (metadata != null) {
            camera.addAll(photoLines(metadata));
        }
        add(sections, "Camera", camera);
        if (metadata != null) {
            add(sections, "Location", location(metadata));
        }
        if (details.isVideo()) {
            add(sections, "Video", videoLines(details, video));
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
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
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

    /** "GPS: 18.520430, 73.856743" (latitude, longitude; south and west negative), or nothing. */
    static List<Line> location(Metadata metadata) {
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        GeoLocation at = gps == null ? null : gps.getGeoLocation();
        if (at == null || at.isZero()) {
            return List.of();
        }
        return List.of(new Line("GPS", String.format(Locale.ROOT, "%.6f, %.6f", at.getLatitude(), at.getLongitude())));
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
