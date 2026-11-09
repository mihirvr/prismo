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
 * <p><b>How</b>: one short {@code ffmpeg} run per slot, a separate process, so a damaged file can't take Prismo down.
 * Long videos decode key frames only (as video thumbnails, see {@code thumbs.VideoThumbnailer}): a seek reads only a
 * little of the file. Short videos ({@link #EXACT_UNDER_SECONDS}, 1.3.0) take the exact frame: a phone clip often has a
 * key frame only every few seconds, so key frames alone gave the same picture for several slots in a row; decoding a
 * few seconds of a short clip is cheap.
 *
 * <p><b>How many</b> (1.3.0): about one per second, up to {@link #SHORT_SLOTS}; a long video one per
 * {@link #LONG_SECONDS_PER_SLOT} s, up to {@link #MAX_SLOTS}, so small mouse moves on an hour-long video still change
 * the picture ({@link #slots}).
 *
 * <p><b>Order</b>: the slot under the mouse first ({@link #want}, 1.3.0), so the picture there comes within a moment;
 * otherwise coarse first ({@link #order}): the middle, the quarters, the eighths and so on, so the whole bar has a near
 * picture early and the gaps fill in. {@link #at} gives the nearest picture made so far, or null (the card shows the
 * time only), and {@code onPicture} is told about each new one so a resting pointer gets it too.
 *
 * <p>{@link #cancel} (next video, viewer closed) stops after the current run; the run itself is short.
 */
final class SeekPreviews {

    private static final Logger log = LoggerFactory.getLogger(SeekPreviews.class);

    /** Short videos: about one picture per this many seconds, up to {@link #SHORT_SLOTS}. */
    static final double SECONDS_PER_SLOT = 1;
    static final int SHORT_SLOTS = 100;
    /** Long videos: one picture per this many seconds, up to {@link #MAX_SLOTS}. */
    static final double LONG_SECONDS_PER_SLOT = 10;
    static final int MAX_SLOTS = 400;
    /** Videos shorter than this get exact frames (not only key frames). */
    static final double EXACT_UNDER_SECONDS = 120;
    /**
     * The largest picture: a wide video fits in {@link #WIDE_WIDTH} x {@link #WIDE_HEIGHT}; a tall or square one in
     * {@link #TALL_BOX} x {@link #TALL_BOX} (1.3.0: a phone video's card took half the screen).
     */
    static final double WIDE_WIDTH = 160;
    static final double WIDE_HEIGHT = 90;
    static final double TALL_BOX = 120;
    private static final long TIMEOUT_SECONDS = 20;

    /** Finds the {@code ffmpeg} program (unpacking it can be slow, first time only). */
    @FunctionalInterface
    interface Program {
        Path path() throws IOException;
    }

    private final double seconds;
    private final AtomicReferenceArray<Image> pictures;
    private final Runnable onPicture;
    private volatile boolean cancelled;
    /** The slot under the mouse, made next if it isn't yet; -1 = none. */
    private volatile int wanted = -1;

    private SeekPreviews(double seconds, int slots, Runnable onPicture) {
        this.seconds = seconds;
        this.pictures = new AtomicReferenceArray<>(slots);
        this.onPicture = onPicture;
    }

    /**
     * Starts making the pictures for {@code video}, {@code seconds} long, on a background thread. {@code onPicture} runs
     * on that thread after each new picture.
     */
    static SeekPreviews start(Path video, double seconds, Program ffmpeg, Runnable onPicture) {
        SeekPreviews previews = new SeekPreviews(seconds, slots(seconds), onPicture);
        if (seconds > 0) {
            Thread.ofVirtual().name("prismo-seek-previews").start(() -> previews.make(video, ffmpeg));
        }
        return previews;
    }

    /** No pictures: for no video. */
    static SeekPreviews none() {
        return new SeekPreviews(0, 1, () -> { });
    }

    /** Stops making pictures (after the current one). */
    void cancel() {
        cancelled = true;
    }

    /** The mouse is at {@code at} seconds: that slot is made next if it has no picture yet. */
    void want(double at) {
        if (seconds > 0) {
            wanted = slotOf(at, seconds, pictures.length());
        }
    }

    /** The picture nearest to {@code at} seconds made so far, or null if none yet. */
    Image at(double at) {
        int slots = pictures.length();
        if (seconds <= 0) {
            return null;
        }
        int slot = slotOf(at, seconds, slots);
        for (int distance = 0; distance < slots; distance++) {
            if (slot - distance >= 0 && pictures.get(slot - distance) != null) {
                return pictures.get(slot - distance);
            }
            if (slot + distance < slots && pictures.get(slot + distance) != null) {
                return pictures.get(slot + distance);
            }
        }
        return null;
    }

    /**
     * How many pictures a video {@code seconds} long gets: one per {@link #SECONDS_PER_SLOT} up to {@link #SHORT_SLOTS},
     * or one per {@link #LONG_SECONDS_PER_SLOT} if that is more, at most {@link #MAX_SLOTS}; at least 1.
     */
    static int slots(double seconds) {
        long perSecond = Math.min(Math.round(seconds / SECONDS_PER_SLOT), SHORT_SLOTS);
        long perTen = Math.round(seconds / LONG_SECONDS_PER_SLOT);
        return Math.clamp(Math.max(perSecond, perTen), 1, MAX_SLOTS);
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
     * The picture size for a video shown {@code width} x {@code height} (any unit; only the shape counts): a wide one
     * fits in {@link #WIDE_WIDTH} x {@link #WIDE_HEIGHT}, a tall or square one in {@link #TALL_BOX} square. Returns
     * {width, height}; an unknown shape gets the wide box.
     */
    static double[] pictureSize(double width, double height) {
        if (width <= 0 || height <= 0) {
            return new double[] {WIDE_WIDTH, WIDE_HEIGHT};
        }
        double boxWidth = width > height ? WIDE_WIDTH : TALL_BOX;
        double boxHeight = width > height ? WIDE_HEIGHT : TALL_BOX;
        double scale = Math.min(boxWidth / width, boxHeight / height);
        return new double[] {width * scale, height * scale};
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

    /**
     * The next slot to make: the wanted one if it wasn't tried yet, else the next untried one in {@code order} from
     * {@code cursor[0]} (moved on); -1 when every slot was tried.
     */
    static int next(int wanted, boolean[] tried, List<Integer> order, int[] cursor) {
        if (wanted >= 0 && wanted < tried.length && !tried[wanted]) {
            return wanted;
        }
        while (cursor[0] < order.size()) {
            int slot = order.get(cursor[0]++);
            if (!tried[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private void make(Path video, Program ffmpeg) {
        Path temp = null;
        try {
            Path program = ffmpeg.path();
            temp = Files.createTempFile("prismo-seek-", ".jpg");
            int slots = pictures.length();
            boolean keyFramesOnly = seconds >= EXACT_UNDER_SECONDS;
            List<Integer> order = order(slots);
            boolean[] tried = new boolean[slots];
            int[] cursor = {0};
            while (!cancelled) {
                int slot = next(wanted, tried, order, cursor);
                if (slot < 0) {
                    return;
                }
                tried[slot] = true;
                double at = timeOf(slot, seconds, slots);
                Image image = grab(program, video, temp, at, keyFramesOnly);
                if (image == null && keyFramesOnly) {
                    image = grab(program, video, temp, at, false); // no key frame after the time: the exact frame
                }
                if (image != null && !cancelled) {
                    pictures.set(slot, image);
                    onPicture.run();
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

    /** One picture at {@code at} seconds via {@code temp}, or null if ffmpeg made none. */
    private static Image grab(Path program, Path video, Path temp, double at, boolean keyFramesOnly)
            throws IOException {
        Files.deleteIfExists(temp);
        ExternalProgram.run(command(program, video, temp, at, keyFramesOnly), TIMEOUT_SECONDS);
        if (!Files.isRegularFile(temp) || Files.size(temp) == 0) {
            return null;
        }
        try (InputStream in = Files.newInputStream(temp)) {
            Image image = new Image(in);
            return image.isError() ? null : image;
        }
    }

    /**
     * One small frame at {@code at} seconds as a JPEG, at most {@link #WIDE_WIDTH} x {@link #TALL_BOX} keeping its shape
     * (the card shrinks it into its box). {@code keyFramesOnly}: only key frames are decoded, so the picture is the
     * first key frame at or after the time (fast; a time with no key frame after it gives no picture); otherwise the
     * exact frame.
     */
    static List<String> command(Path program, Path video, Path target, double at, boolean keyFramesOnly) {
        List<String> command = new ArrayList<>(List.of(program.toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-threads", "1"));
        if (keyFramesOnly) {
            command.addAll(List.of("-skip_frame", "nokey"));
        }
        command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", at), "-i", video.toString(),
                "-map", "0:V:0", "-frames:v", "1", "-an", "-sn",
                "-vf", "scale=%d:%d:force_original_aspect_ratio=decrease".formatted((int) WIDE_WIDTH, (int) TALL_BOX),
                "-q:v", "5", "-f", "mjpeg", "-y", target.toString()));
        return command;
    }
}
