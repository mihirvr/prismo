package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The small pictures shown above the seek bar while the pointer is on it (1.2.1, like YouTube's): one frame per
 * slot, spread evenly over the video, made in the background by the bundled {@code ffmpeg} when the video opens.
 *
 * <p><b>How</b>: one short {@code ffmpeg} run per slot, key frames only (as video thumbnails, see
 * {@code thumbs.VideoThumbnailer}): a seek reads only a little of the file, so even a long video on a slow disk
 * gives its first pictures within a second or two. A separate process, so a damaged file can't take Prismo down.
 *
 * <p><b>Order</b>: coarse first ({@link #order}): the middle, then the quarters, the eighths and so on, so the
 * whole bar has a near picture early and the gaps fill in. Until then {@link #at} gives the nearest picture made so
 * far, or null (the preview shows the time only).
 *
 * <p>{@link #cancel} (next video, viewer closed) stops after the current run; the run itself is short.
 */
final class SeekPreviews {

    private static final Logger log = LoggerFactory.getLogger(SeekPreviews.class);

    /** At most this many pictures per video. */
    static final int MAX_SLOTS = 100;
    /** Short videos: about one picture per this many seconds. */
    static final double SECONDS_PER_SLOT = 1;
    /** The pictures' width in pixels (their height follows the video's shape). */
    static final int WIDTH = 160;
    private static final long TIMEOUT_SECONDS = 20;

    /** Finds the {@code ffmpeg} program (unpacking it can be slow, first time only). */
    @FunctionalInterface
    interface Program {
        Path path() throws IOException;
    }

    private final double seconds;
    private final AtomicReferenceArray<Image> pictures;
    private volatile boolean cancelled;

    private SeekPreviews(double seconds, int slots) {
        this.seconds = seconds;
        this.pictures = new AtomicReferenceArray<>(slots);
    }

    /** Starts making the pictures for {@code video}, {@code seconds} long, on a background thread. */
    static SeekPreviews start(Path video, double seconds, Program ffmpeg) {
        SeekPreviews previews = new SeekPreviews(seconds, slots(seconds));
        if (seconds > 0) {
            Thread.ofVirtual().name("prismo-seek-previews").start(() -> previews.make(video, ffmpeg));
        }
        return previews;
    }

    /** No pictures: for no video. */
    static SeekPreviews none() {
        return new SeekPreviews(0, 1);
    }

    /** Stops making pictures (after the current one). */
    void cancel() {
        cancelled = true;
    }

    /** The picture nearest to {@code at} seconds made so far, or null if none yet. */
    Image at(double at) {
        int slots = pictures.length();
        if (seconds <= 0) {
            return null;
        }
        int wanted = slotOf(at, seconds, slots);
        for (int distance = 0; distance < slots; distance++) {
            if (wanted - distance >= 0 && pictures.get(wanted - distance) != null) {
                return pictures.get(wanted - distance);
            }
            if (wanted + distance < slots && pictures.get(wanted + distance) != null) {
                return pictures.get(wanted + distance);
            }
        }
        return null;
    }

    /** How many pictures a video {@code seconds} long gets: one per {@link #SECONDS_PER_SLOT}, 1 to {@link #MAX_SLOTS}. */
    static int slots(double seconds) {
        return Math.clamp(Math.round(seconds / SECONDS_PER_SLOT), 1, MAX_SLOTS);
    }

    /** The slot that holds the time {@code at}. */
    static int slotOf(double at, double seconds, int slots) {
        if (seconds <= 0) {
            return 0;
        }
        return Math.clamp((int) Math.floor(at / seconds * slots), 0, slots - 1);
    }

    /** The time a slot's picture is taken at: the middle of its stretch of the video. */
    static double timeOf(int slot, double seconds, int slots) {
        return (slot + 0.5) * seconds / slots;
    }

    /**
     * The slots in the order to make them: coarse to fine. Slot positions are visited by halving the steps (middle,
     * quarters, eighths, ...), each slot once.
     */
    static List<Integer> order(int slots) {
        List<Integer> order = new ArrayList<>(slots);
        boolean[] taken = new boolean[slots];
        for (int parts = 1; order.size() < slots; parts *= 2) {
            for (int i = 0; i < parts; i++) {
                int slot = Math.clamp((int) Math.floor((i + 0.5) * slots / parts), 0, slots - 1);
                if (!taken[slot]) {
                    taken[slot] = true;
                    order.add(slot);
                }
            }
            if (parts > slots * 2) { // rounding left some out: add them in order
                for (int slot = 0; slot < slots; slot++) {
                    if (!taken[slot]) {
                        taken[slot] = true;
                        order.add(slot);
                    }
                }
            }
        }
        return order;
    }

    private void make(Path video, Program ffmpeg) {
        Path temp = null;
        try {
            Path program = ffmpeg.path();
            temp = Files.createTempFile("prismo-seek-", ".jpg");
            int slots = pictures.length();
            for (int slot : order(slots)) {
                if (cancelled) {
                    return;
                }
                Files.deleteIfExists(temp);
                ExternalProgram.run(command(program, video, temp, timeOf(slot, seconds, slots)), TIMEOUT_SECONDS);
                if (Files.isRegularFile(temp) && Files.size(temp) > 0) {
                    try (InputStream in = Files.newInputStream(temp)) {
                        Image image = new Image(in);
                        if (!image.isError()) {
                            pictures.set(slot, image);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Seek previews stopped for {}: {}", video, e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.debug("Couldn't delete {}", temp);
                }
            }
        }
    }

    /**
     * One small frame near {@code at} seconds: the first key frame at or after it (only key frames are decoded),
     * {@link #WIDTH} wide, as a JPEG. A time with no key frame after it gives no picture; that slot stays empty.
     */
    static List<String> command(Path program, Path video, Path target, double at) {
        return List.of(program.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "1",
                "-skip_frame", "nokey", "-ss", String.format(Locale.ROOT, "%.3f", at), "-i", video.toString(),
                "-map", "0:V:0", "-frames:v", "1", "-an", "-sn", "-vf", "scale=" + WIDTH + ":-2", "-q:v", "5",
                "-f", "mjpeg", "-y", target.toString());
    }
}
