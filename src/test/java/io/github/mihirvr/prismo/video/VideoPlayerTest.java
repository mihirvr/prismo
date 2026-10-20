package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plays real (tiny) videos made by {@link TestMedia#video}. Works with or without a sound device: without one
 * the player runs on the wall clock, which these tests accept too.
 */
@Timeout(30)
class VideoPlayerTest {

    private static final int FPS = 25;

    @TempDir
    static Path dir;
    private static Path clip;
    private static Path silent;

    @BeforeAll
    static void makeVideos() throws Exception {
        clip = TestMedia.video(dir.resolve("clip.mp4"), 320, 240, 2 * FPS, FPS, true, 0);
        silent = TestMedia.video(dir.resolve("silent.mp4"), 320, 240, FPS, FPS, false, 0);
    }

    /** Every test closes its player: its threads (and the file) must be gone soon after. */
    @AfterEach
    void threadsEnd() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (videoThreads() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, videoThreads(), "player threads still running");
    }

    @Test
    void playsAtNormalSpeedToTheEndAndPausesThere() throws Exception {
        Events events = new Events();
        long started = System.nanoTime();
        try (VideoPlayer player = quiet(VideoPlayer.open(clip, 1920, 1080, events))) {
            VideoPlayer.Info info = events.opened.get(10, TimeUnit.SECONDS);
            assertTrue(info.hasPicture());
            assertTrue(info.hasSound());
            assertEquals(320, info.width());
            assertEquals(240, info.height());
            assertEquals(0, info.rotation());
            assertEquals(2_000_000, info.durationMicros(), 100_000);
            assertEquals("h264", info.videoCodec());
            assertEquals("aac", info.audioCodec());

            List<Long> times = collectUntilEnded(player, events);
            double seconds = (System.nanoTime() - started) / 1e9;

            assertTrue(seconds >= 1.7, "paced by the clock, not decoded as fast as possible: " + seconds + " s");
            assertTrue(times.size() >= FPS, "most of the 50 pictures reached the UI: " + times.size());
            for (int i = 1; i < times.size(); i++) {
                assertTrue(times.get(i) > times.get(i - 1), "in order: " + times);
            }
            assertTrue(times.getLast() >= 1_800_000, "reached the end: " + times.getLast());
            assertFalse(player.isPlaying(), "paused itself at the end");
        }
    }

    @Test
    void aVideoWithoutSoundRunsOnTheWallClock() throws Exception {
        Events events = new Events();
        long started = System.nanoTime();
        try (VideoPlayer player = quiet(VideoPlayer.open(silent, 1920, 1080, events))) {
            VideoPlayer.Info info = events.opened.get(10, TimeUnit.SECONDS);
            assertFalse(info.hasSound());
            assertEquals("", info.audioCodec(), "no sound track, no sound format");
            collectUntilEnded(player, events);
            double seconds = (System.nanoTime() - started) / 1e9;
            assertTrue(seconds >= 0.85, "took about its 1 s length: " + seconds + " s");
        }
    }

    @Test
    void seekWhilePausedShowsThePictureThereAndStaysPaused() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = quiet(VideoPlayer.open(clip, 1920, 1080, events))) {
            events.opened.get(10, TimeUnit.SECONDS);
            player.pause();
            player.seek(1_200_000);
            assertEquals(1_200_000, player.position());

            VideoPlayer.Picture shown = waitForPicture(player, p -> p.micros() >= 1_000_000);

            assertEquals(1_200_000, shown.micros(), 40_000, "the frame at 1.2 s (one frame = 40 ms)");
            int frame = (int) Math.round(shown.micros() * FPS / 1e6);
            assertEquals(TestMedia.frameRed(frame), red(shown), 12, "its colour matches frame " + frame);
            player.recycle(shown);
            Thread.sleep(300);
            assertEquals(1_200_000, player.position(), "still paused");

            player.play();
            VideoPlayer.Picture later = waitForPicture(player, p -> p.micros() >= 1_500_000);
            assertTrue(later.micros() < 2_000_000);
        }
    }

    @Test
    void startsWhereItIsAskedWithoutShowingTheBeginning() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = quiet(VideoPlayer.open(clip, 1920, 1080, 1_200_000, events))) {
            events.opened.get(10, TimeUnit.SECONDS);
            VideoPlayer.Picture first = waitForPicture(player, _ -> true);

            assertEquals(1_200_000, first.micros(), 40_000, "the first picture shown is the one at 1.2 s");
            player.recycle(first);
            assertTrue(player.position() >= 1_150_000, "playing on from there: " + player.position());
        }
    }

    @Test
    void atDoubleSpeedAVideoTakesHalfAsLong() throws Exception {
        for (Path file : List.of(clip, silent)) { // sound (sped up, and the clock) and wall clock only
            Events events = new Events();
            try (VideoPlayer player = quiet(VideoPlayer.open(file, 1920, 1080, events))) {
                player.setSpeed(2); // before it's open, as the viewer does with the remembered speed
                events.opened.get(10, TimeUnit.SECONDS);
                long started = System.nanoTime();
                List<Long> times = collectUntilEnded(player, events);
                double seconds = (System.nanoTime() - started) / 1e9;
                double length = player.durationMicros() / 1e6;

                assertEquals(2, player.speed());
                assertTrue(seconds > length * 0.4 && seconds < length * 0.7,
                        file.getFileName() + ": " + seconds + " s for a " + length + " s video");
                assertTrue(times.getLast() >= player.durationMicros() - 200_000, "reached the end: " + times.getLast());
            }
        }
    }

    @Test
    void speedIsKeptBetweenTheLimits() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = quiet(VideoPlayer.open(silent, 1920, 1080, events))) {
            player.setSpeed(5);
            assertEquals(VideoPlayer.MAX_SPEED, player.speed());
            player.setSpeed(0.1);
            assertEquals(VideoPlayer.MIN_SPEED, player.speed());
        }
    }

    @Test
    void stepShowsTheNextAndThePreviousPicture() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = quiet(VideoPlayer.open(clip, 1920, 1080, events))) {
            events.opened.get(10, TimeUnit.SECONDS);
            player.pause();
            player.seek(1_000_000);
            player.recycle(waitForPicture(player, p -> p.micros() >= 960_000));

            player.step(1);
            assertEquals(1_040_000, player.position(), "one frame (40 ms at 25 per second) on");
            VideoPlayer.Picture next = waitForPicture(player, p -> p.micros() >= 1_020_000);
            assertEquals(1_040_000, next.micros(), 1_000);
            player.recycle(next);

            player.step(-2);
            assertEquals(960_000, player.position());
            assertFalse(player.isPlaying(), "stepping pauses");
        }
    }

    @Test
    void listsSoundAndSubtitleTracksAndSwitchesSound() throws Exception {
        Path file = TestMedia.withTracks(dir.resolve("tracks.mkv"));
        Events events = new Events();
        try (VideoPlayer player = quiet(VideoPlayer.open(file, 1920, 1080, events))) {
            VideoPlayer.Info info = events.opened.get(10, TimeUnit.SECONDS);

            assertEquals(List.of("English", "Commentary · Hindi"),
                    List.of(info.audioTracks().get(0).label(1), info.audioTracks().get(1).label(2)));
            int english = info.audioTracks().get(0).stream();
            int commentary = info.audioTracks().get(1).stream();
            assertEquals(commentary, info.audioStream(), "the track the file marks as default plays first");
            assertEquals(1, info.subtitleTracks().size());
            assertEquals("eng", info.subtitleTracks().getFirst().language());
            assertTrue(info.subtitleTracks().getFirst().text(), "SRT is text");
            assertEquals("mpeg4", info.videoCodec());
            assertEquals("aac", info.audioCodec(), "the playing track's format");

            player.selectAudio(english);
            assertEquals(english, player.audioStream());
            long at = player.position();
            player.recycle(waitForPicture(player, p -> p.micros() >= at + 300_000)); // still playing
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (soundThreads() > 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(soundThreads() <= 1, "the first track's sound thread stopped");
        }
    }

    @Test
    void movAndMkvPlayToo() throws Exception {
        for (String name : List.of("clip.mov", "clip.mkv")) {
            Path file = TestMedia.video(dir.resolve(name), 320, 240, 10, FPS, true, 0);
            Events events = new Events();
            try (VideoPlayer player = quiet(VideoPlayer.open(file, 1920, 1080, events))) {
                assertTrue(events.opened.get(10, TimeUnit.SECONDS).hasPicture(), name);
                assertNotNull(waitForPicture(player, _ -> true), name);
            }
        }
    }

    @Test
    void readsThePhoneRotationTag() throws Exception {
        Path turned = TestMedia.video(dir.resolve("turned.mp4"), 320, 240, 5, FPS, false, 90);
        Events events = new Events();
        try (VideoPlayer _ = quiet(VideoPlayer.open(turned, 1920, 1080, events))) {
            VideoPlayer.Info info = events.opened.get(10, TimeUnit.SECONDS);
            assertEquals(90, info.rotation());
            assertEquals(320, info.width(), "the stored (unturned) size");
        }
    }

    @Test
    void picturesAreScaledDownToTheScreen() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = quiet(VideoPlayer.open(clip, 160, 160, events))) {
            events.opened.get(10, TimeUnit.SECONDS);
            VideoPlayer.Picture first = waitForPicture(player, _ -> true);
            assertEquals(160, first.width());
            assertEquals(120, first.height());
        }
    }

    @Test
    void aMissingFileIsReportedInPlainWords() throws Exception {
        Events events = new Events();
        try (VideoPlayer _ = VideoPlayer.open(dir.resolve("gone.mp4"), 1920, 1080, events)) {
            assertEquals("the file couldn't be found", events.failed.get(10, TimeUnit.SECONDS));
            assertFalse(events.opened.isDone());
        }
    }

    @Test
    void aFileWithNoPictureOrSoundIsReportedAsDamaged() throws Exception {
        Path empty = TestMedia.mp4(dir.resolve("empty.mp4"), java.time.Instant.EPOCH, 5);
        Events events = new Events();
        try (VideoPlayer _ = VideoPlayer.open(empty, 1920, 1080, events)) {
            assertEquals("the file looks damaged, or its format isn't supported", events.failed.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeStopsItsThreads() throws Exception {
        Events events = new Events();
        VideoPlayer player = quiet(VideoPlayer.open(clip, 1920, 1080, events));
        events.opened.get(10, TimeUnit.SECONDS);
        assertTrue(videoThreads() > 0);
        player.close();
        threadsEnd();
    }

    @Test
    void copiesPaddedRowsAsArgbInts() {
        Frame frame = new Frame();
        frame.imageWidth = 2;
        frame.imageHeight = 2;
        frame.imageStride = 12; // 3 pixels of room per row, 2 used
        ByteBuffer bgra = ByteBuffer.allocate(24);
        bgra.put(new byte[] {1, 2, 3, (byte) 255, 4, 5, 6, (byte) 255, 9, 9, 9, 9});
        bgra.put(new byte[] {7, 8, 9, (byte) 255, 10, 11, 12, (byte) 255, 9, 9, 9, 9});
        frame.image = new Buffer[] {bgra};
        VideoPlayer.Picture picture = new VideoPlayer.Picture(2, 2);

        VideoPlayer.copyPixels(frame, picture);

        assertArrayEquals(new int[] {0xFF030201, 0xFF060504, 0xFF090807, 0xFF0C0B0A}, picture.argb());
    }

    @Test
    void soundBytesAreLittleEndianAndScaledByTheVolume() {
        ShortBuffer samples = ShortBuffer.wrap(new short[] {1000, -1000, 0x1234});
        byte[] out = new byte[6];

        VideoPlayer.toBytes(samples, 3, 1, out);
        assertArrayEquals(new byte[] {(byte) 0xE8, 0x03, 0x18, (byte) 0xFC, 0x34, 0x12}, out);

        VideoPlayer.toBytes(samples, 3, 0.5, out);
        assertArrayEquals(new byte[] {(byte) 0xF4, 0x01, 0x0C, (byte) 0xFE, 0x1A, 0x09}, out);
    }

    // --- Helpers ---

    private static final class Events implements VideoPlayer.Listener {
        final CompletableFuture<VideoPlayer.Info> opened = new CompletableFuture<>();
        final CountDownLatch ended = new CountDownLatch(1);
        final CompletableFuture<String> failed = new CompletableFuture<>();

        @Override
        public void opened(VideoPlayer.Info info) {
            opened.complete(info);
        }

        @Override
        public void ended() {
            ended.countDown();
        }

        @Override
        public void failed(String reason) {
            failed.complete(reason);
        }
    }

    /** Acts as the UI: takes each picture as it becomes due, until the player says it ended. */
    private static List<Long> collectUntilEnded(VideoPlayer player, Events events) throws InterruptedException {
        List<Long> times = new ArrayList<>();
        while (!events.ended.await(5, TimeUnit.MILLISECONDS)) {
            take(player, times);
        }
        take(player, times);
        return times;
    }

    private static void take(VideoPlayer player, List<Long> times) {
        VideoPlayer.Picture picture = player.takePicture();
        if (picture != null) {
            times.add(picture.micros());
            player.recycle(picture);
        }
    }

    private static VideoPlayer.Picture waitForPicture(VideoPlayer player, Predicate<VideoPlayer.Picture> wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            VideoPlayer.Picture picture = player.takePicture();
            if (picture != null) {
                if (wanted.test(picture)) {
                    return picture;
                }
                player.recycle(picture);
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no such picture within 5 s");
    }

    /** Red level of the picture's centre (outside the white marker corner). */
    private static int red(VideoPlayer.Picture picture) {
        int argb = picture.argb()[picture.height() / 2 * picture.width() + picture.width() / 2];
        return argb >> 16 & 0xFF;
    }

    /**
     * Muted at once: the sound still goes to the device (the clock follows it) but as silence, so running the
     * tests doesn't beep through the speakers.
     */
    private static VideoPlayer quiet(VideoPlayer player) {
        player.setMuted(true);
        return player;
    }

    private static long soundThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("prismo-video-sound") && t.isAlive()).count();
    }

    private static long videoThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("prismo-video-") && t.isAlive()).count();
    }
}
