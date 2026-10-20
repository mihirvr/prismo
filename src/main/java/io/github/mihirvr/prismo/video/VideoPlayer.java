package io.github.mihirvr.prismo.video;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays one video file with FFmpeg (through JavaCV): pictures come out as ARGB pixel arrays for the UI to
 * draw, sound goes straight to the sound card ({@code javax.sound}).
 *
 * <p><b>Two threads, two readers.</b> The picture thread and the sound thread each open the file with their
 * own {@link FFmpegFrameGrabber}: one decodes only the picture stream, the other only the sound stream. A
 * single reader would hand out pictures and sound in the order they're stored in the file, and a file that
 * stores them far apart would make one side wait for the other. The extra cost is reading the file twice.
 *
 * <p><b>Sync.</b> Both follow one {@link PlaybackClock}, driven by the sound card (samples played) when there
 * is sound. The picture thread decodes the next picture, then sleeps until the clock reaches its time and
 * hands it over; one that's already late is handed over at once (the UI keeps only the newest).
 *
 * <p><b>Handing pictures to the UI.</b> Three pixel arrays go round: one being filled, one waiting in
 * {@link #takePicture}, one being drawn. The UI gives each back with {@link #recycle}. So memory is fixed at
 * three pictures, which are scaled down to fit the screen ({@code maxWidth} × {@code maxHeight}).
 *
 * <p>Platform threads, not virtual ones: they spend their time in native FFmpeg code, which would pin a
 * virtual thread to its carrier anyway. Listener calls come on these threads.
 */
public final class VideoPlayer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VideoPlayer.class);
    /** Sound queued in the device: rides out short pauses (GC), short enough that mute and seek feel instant. */
    private static final int SOUND_BUFFER_MILLIS = 200;
    private static final int BYTES_PER_SAMPLE_FRAME = 4; // 16-bit stereo
    private static final int PICTURE_BUFFERS = 3;
    private static final long POLL_MILLIS = 4;
    /** The picture side waits this long for the sound device before starting without it. */
    private static final long SOUND_OPEN_WAIT_MILLIS = 3_000;
    /** The device stopped counting played samples for this long after the end: treat the sound as finished. */
    private static final long SOUND_STALL_NANOS = 500_000_000L;

    /** What the file holds, known once it's open. {@code rotation} = clockwise quarter turn to show it at. */
    public record Info(long durationMicros, int width, int height, int rotation, boolean hasPicture,
            boolean hasSound) {
    }

    /** One decoded picture: {@code width} × {@code height} ARGB ints, opaque, row after row. */
    public static final class Picture {
        private final int[] argb;
        private final int width;
        private final int height;
        private long micros;

        Picture(int width, int height) {
            this.argb = new int[width * height];
            this.width = width;
            this.height = height;
        }

        public int[] argb() {
            return argb;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** Its time in the video. */
        public long micros() {
            return micros;
        }
    }

    /** Told about the player's progress, on the player's own threads. */
    public interface Listener {
        /** The file is open and playing starts. */
        void opened(Info info);

        /** Both picture and sound reached the end; the player has paused itself on the last picture. */
        void ended();

        /** The file can't be played: {@code reason} is in plain words, for the user. */
        void failed(String reason);
    }

    private final Path file;
    private final int maxWidth;
    private final int maxHeight;
    private final Listener listener;
    private final PlaybackClock clock = new PlaybackClock(System::nanoTime);
    private final AtomicReference<Picture> latest = new AtomicReference<>();
    private final BlockingQueue<Picture> free = new ArrayBlockingQueue<>(PICTURE_BUFFERS);
    private final CountDownLatch soundReady = new CountDownLatch(1);

    private volatile boolean closed;
    private volatile boolean playing = true;
    private volatile double volume = 1;
    private volatile boolean muted;
    private volatile long durationMicros;
    private volatile boolean hasPicture;
    /** True while sound is actually playing through a device (false if the file has none or no device opened). */
    private volatile boolean soundActive;
    // Guarded by "this": which sides have reached the end since the last seek.
    private boolean pictureDone;
    private boolean soundDone;
    private boolean endedSent;

    private VideoPlayer(Path file, int maxWidth, int maxHeight, Listener listener) {
        this.file = file;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.listener = listener;
    }

    /**
     * Opens {@code file} on a new thread and starts playing as soon as it can. Returns at once; the listener
     * hears {@code opened} or {@code failed}. Pictures are decoded at most {@code maxWidth} × {@code maxHeight}.
     */
    public static VideoPlayer open(Path file, int maxWidth, int maxHeight, Listener listener) {
        VideoPlayer player = new VideoPlayer(file, maxWidth, maxHeight, listener);
        Thread.ofPlatform().name("prismo-video-picture").daemon(true).start(player::runPicture);
        return player;
    }

    // --- Controls (any thread) ---

    public void play() {
        synchronized (clock) { // "playing" and the clock change together (see runPicture)
            playing = true;
            clock.play();
        }
    }

    public void pause() {
        synchronized (clock) {
            playing = false;
            clock.pause();
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Jumps to {@code micros} (clamped to the video). While paused, the picture there is shown too. */
    public void seek(long micros) {
        long target = Math.max(0, durationMicros > 0 ? Math.min(micros, durationMicros) : micros);
        synchronized (this) { // with finished(): an end reached just before this seek can't count after it
            clock.seek(target); // numbers the seek; each thread compares it with the last one it handled
            pictureDone = !hasPicture;
            soundDone = !soundActive;
            endedSent = false;
        }
    }

    /** Where playback is, in microseconds. */
    public long position() {
        long now = clock.now();
        return Math.max(0, durationMicros > 0 ? Math.min(now, durationMicros) : now);
    }

    public long durationMicros() {
        return durationMicros;
    }

    /** 0 = silent, 1 = as recorded. Takes effect within the sound buffer (about 0.2 s). */
    public void setVolume(double volume) {
        this.volume = Math.clamp(volume, 0, 1);
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** The newest picture that's due, or null if none arrived since the last call. Give it back with {@link #recycle}. */
    public Picture takePicture() {
        return latest.getAndSet(null);
    }

    public void recycle(Picture picture) {
        free.offer(picture);
    }

    /** Stops both threads; they close the file and the sound device within a few milliseconds. */
    @Override
    public void close() {
        closed = true;
    }

    // --- The picture thread ---

    private void runPicture() {
        VideoNatives.loadLibraries();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toString())) {
            Info info;
            long start;
            try {
                grabber.setPixelFormat(avutil.AV_PIX_FMT_BGRA);
                grabber.start();
                start = startMicros(grabber);
                info = describe(grabber);
            } catch (FrameGrabber.Exception | RuntimeException e) {
                fail(e);
                return;
            }
            if (!info.hasPicture() && !info.hasSound()) {
                fail(new IllegalStateException("no picture or sound stream"));
                return;
            }
            durationMicros = info.durationMicros();
            hasPicture = info.hasPicture();
            if (info.hasSound()) {
                Thread.ofPlatform().name("prismo-video-sound").daemon(true).start(() -> runSound(start));
                awaitSoundDevice();
            }
            synchronized (this) {
                pictureDone = !hasPicture;
                soundDone = !soundActive;
            }
            if (closed) {
                return;
            }
            synchronized (clock) { // a pause() can't slip in between the check and the start
                if (playing) {
                    clock.play();
                }
            }
            listener.opened(info);
            if (info.hasPicture()) {
                int[] size = VideoGeometry.decodeSize(info.width(), info.height(), info.rotation(), maxWidth, maxHeight);
                grabber.setImageWidth(size[0]);
                grabber.setImageHeight(size[1]);
                for (int i = 0; i < PICTURE_BUFFERS; i++) {
                    free.add(new Picture(size[0], size[1]));
                }
                pictureLoop(grabber, start);
            }
        } catch (FrameGrabber.Exception e) {
            log.debug("Closing {} failed: {}", file, e.toString());
        }
    }

    private Info describe(FFmpegFrameGrabber grabber) {
        boolean picture = grabber.hasVideo();
        int rotation = picture ? VideoGeometry.clockwiseQuarterTurn(grabber.getDisplayRotation()) : 0;
        return new Info(Math.max(0, grabber.getLengthInTime()), grabber.getImageWidth(), grabber.getImageHeight(),
                rotation, picture, grabber.hasAudio());
    }

    private void pictureLoop(FFmpegFrameGrabber grabber, long start) {
        long handled = 0;
        boolean showNext = true; // the first picture, and the first after a seek, show at once (even paused)
        boolean atEnd = false;
        while (!closed) {
            PlaybackClock.Seek seek = clock.lastSeek();
            if (seek.number() != handled) {
                handled = seek.number();
                try {
                    grabber.setVideoTimestamp(seek.micros());
                } catch (FrameGrabber.Exception e) {
                    log.debug("Seek failed in {}: {}", file, e.toString());
                }
                showNext = true;
                atEnd = false;
            }
            if (atEnd) {
                sleep(POLL_MILLIS);
                continue;
            }
            Frame frame;
            try {
                frame = grabber.grabImage();
            } catch (FrameGrabber.Exception e) {
                log.debug("Picture decoding stopped in {}: {}", file, e.toString());
                frame = null;
            }
            if (frame == null || frame.image == null) {
                atEnd = true;
                finished(true, handled);
                continue;
            }
            Picture picture = takeFree();
            if (picture == null) {
                return; // closed
            }
            copyPixels(frame, picture);
            picture.micros = frame.timestamp - start;
            if (!showNext && !waitUntilDue(picture.micros, handled)) {
                free.offer(picture); // a seek came in (or close) while waiting: this picture is stale
                continue;
            }
            showNext = false;
            Picture replaced = latest.getAndSet(picture);
            if (replaced != null) {
                free.offer(replaced); // the UI didn't take it in time: dropped
            }
        }
    }

    /** Sleeps until the clock reaches {@code micros}. False if a seek or close came first. */
    private boolean waitUntilDue(long micros, long generation) {
        while (!closed && clock.lastSeek().number() == generation) {
            long aheadMicros = micros - clock.now();
            if (aheadMicros <= 0) {
                return true;
            }
            sleep(Math.max(1, Math.min(POLL_MILLIS, aheadMicros / 1000)));
        }
        return false;
    }

    private Picture takeFree() {
        while (!closed) {
            try {
                Picture picture = free.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (picture != null) {
                    return picture;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * FFmpeg's BGRA bytes are, read as little-endian ints, exactly JavaFX's ARGB ints. Rows may be padded
     * ({@code imageStride} bytes each), so they're copied one by one.
     */
    static void copyPixels(Frame frame, Picture picture) {
        ByteBuffer bytes = ((ByteBuffer) frame.image[0]).duplicate();
        bytes.position(0);
        IntBuffer ints = bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int rowInts = frame.imageStride / 4;
        int width = Math.min(picture.width, frame.imageWidth);
        int height = Math.min(picture.height, frame.imageHeight);
        for (int y = 0; y < height; y++) {
            ints.get(y * rowInts, picture.argb, y * picture.width, width);
        }
    }

    // --- The sound thread ---

    private void awaitSoundDevice() {
        try {
            if (!soundReady.await(SOUND_OPEN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.info("Sound device didn't open within {} ms; playing {} without sound", SOUND_OPEN_WAIT_MILLIS, file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSound(long start) {
        SourceDataLine line = null;
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toString())) {
            int rate;
            try {
                grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16); // packed 16-bit, converted by FFmpeg
                grabber.setAudioChannels(2);                         // 5.1 and mono both become stereo
                grabber.start();
                rate = grabber.getSampleRate();
                AudioFormat format = new AudioFormat(rate, 16, 2, true, false);
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, rate * BYTES_PER_SAMPLE_FRAME * SOUND_BUFFER_MILLIS / 1000);
            } catch (FrameGrabber.Exception | LineUnavailableException | IllegalArgumentException | SecurityException e) {
                log.info("No sound for {}: {}", file, e.toString());
                soundReady.countDown(); // soundActive stays false: the picture side runs on the wall clock
                return;
            }
            SourceDataLine device = line;
            clock.useSound(device::getLongFramePosition, rate);
            soundActive = true;
            soundReady.countDown();
            soundLoop(grabber, device, rate, start);
        } catch (FrameGrabber.Exception e) {
            log.debug("Closing the sound of {} failed: {}", file, e.toString());
        } finally {
            if (line != null) {
                line.close();
            }
        }
    }

    private void soundLoop(FFmpegFrameGrabber grabber, SourceDataLine line, int rate, long start) {
        long handled = 0;
        long target = 0; // where the last seek went (the start, before any)
        boolean started = false;
        boolean atEnd = false;
        boolean endReported = false;
        boolean firstAfterSeek = true;
        long endMicros = 0;
        long lastFrames = -1;
        long framesChangedAt = System.nanoTime();
        byte[] pending = new byte[0];
        int pendingFrom = 0;
        int pendingLength = 0;
        while (!closed) {
            PlaybackClock.Seek seek = clock.lastSeek();
            if (seek.number() != handled) {
                handled = seek.number();
                target = seek.micros();
                line.stop();
                started = false;
                line.flush(); // drop what was queued for the old position
                try {
                    grabber.setAudioTimestamp(target);
                } catch (FrameGrabber.Exception e) {
                    log.debug("Sound seek failed in {}: {}", file, e.toString());
                }
                if (!clock.followsSound()) {
                    clock.useSound(line::getLongFramePosition, rate); // it had switched to the wall clock at the end
                }
                atEnd = false;
                endReported = false;
                firstAfterSeek = true;
                pendingLength = 0;
            }
            if (playing != started) {
                if (playing) {
                    line.start();
                } else {
                    line.stop(); // keeps what's queued; it plays on after a resume
                }
                started = playing;
            }
            if (pendingLength == 0 && !atEnd) {
                Frame frame;
                try {
                    frame = grabber.grabSamples();
                } catch (FrameGrabber.Exception e) {
                    log.debug("Sound decoding stopped in {}: {}", file, e.toString());
                    frame = null;
                }
                if (frame == null || frame.samples == null) {
                    atEnd = true;
                } else {
                    ShortBuffer samples = (ShortBuffer) frame.samples[0];
                    int count = samples.limit();
                    if (pending.length < count * 2) {
                        pending = new byte[count * 2];
                    }
                    toBytes(samples, count, muted ? 0 : volume, pending);
                    pendingFrom = 0;
                    pendingLength = count * 2;
                    long micros = frame.timestamp - start;
                    endMicros = micros + (count / 2) * 1_000_000L / rate;
                    if (firstAfterSeek) {
                        // A seek lands on the start of the block that holds the target (AAC: 1,024 samples, ~23 ms).
                        // Cut what comes before the target, so the sound (and so the clock) starts exactly there.
                        long skipFrames = Math.max(0, (target - micros) * rate / 1_000_000L);
                        if (skipFrames >= count / 2) {
                            pendingLength = 0; // the whole block is before the target
                            continue;
                        }
                        pendingFrom = (int) skipFrames * BYTES_PER_SAMPLE_FRAME;
                        pendingLength -= pendingFrom;
                        clock.soundStarts(Math.max(micros, target), handled); // nothing queued (just flushed): plays next
                        firstAfterSeek = false;
                    }
                }
            }
            if (pendingLength > 0) {
                int room = Math.min(line.available(), pendingLength) / BYTES_PER_SAMPLE_FRAME * BYTES_PER_SAMPLE_FRAME;
                if (room > 0) {
                    line.write(pending, pendingFrom, room);
                    pendingFrom += room;
                    pendingLength -= room;
                } else {
                    sleep(POLL_MILLIS);
                }
                continue;
            }
            if (atEnd && !endReported) {
                long frames = line.getLongFramePosition();
                long nowNanos = System.nanoTime();
                if (frames != lastFrames) {
                    lastFrames = frames;
                    framesChangedAt = nowNanos;
                }
                boolean played = clock.now() >= endMicros;
                boolean stalled = started && nowNanos - framesChangedAt > SOUND_STALL_NANOS;
                if (firstAfterSeek || played || stalled) { // firstAfterSeek: sought past the last sample
                    endReported = true;
                    clock.soundEnded(); // a picture longer than the sound carries on with the wall clock
                    finished(false, handled);
                }
            }
            sleep(POLL_MILLIS);
        }
    }

    /** 16-bit samples to little-endian bytes, scaled by {@code gain} (0..1, so they can't overflow). */
    static void toBytes(ShortBuffer samples, int count, double gain, byte[] out) {
        for (int i = 0; i < count; i++) {
            int value = gain == 1 ? samples.get(i) : (int) Math.round(samples.get(i) * gain);
            out[2 * i] = (byte) value;
            out[2 * i + 1] = (byte) (value >> 8);
        }
    }

    // --- Shared ---

    /** A side (picture or sound) reached the end for seek {@code generation}; when both have, the video ended. */
    private void finished(boolean picture, long generation) {
        boolean both;
        synchronized (this) {
            if (generation != clock.lastSeek().number()) {
                return; // a seek came in since: this end is stale
            }
            if (picture) {
                pictureDone = true;
            } else {
                soundDone = true;
            }
            both = pictureDone && soundDone && !endedSent;
            endedSent |= both;
        }
        if (both && !closed) {
            pause();
            listener.ended();
        }
    }

    private void fail(Exception error) {
        log.info("Can't play {}: {}", file, error.toString());
        if (!closed) {
            listener.failed(Files.exists(file)
                    ? "the file looks damaged, or its format isn't supported"
                    : "the file couldn't be found");
        }
    }

    /** The file's own start time: frame times count from it, so the video's "0:00" is its first moment. */
    private static long startMicros(FFmpegFrameGrabber grabber) {
        long start = grabber.getFormatContext().start_time();
        return start == avutil.AV_NOPTS_VALUE ? 0 : start; // AV_TIME_BASE is 1,000,000: already microseconds
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
