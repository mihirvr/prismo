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
        try (VideoPlayer player = VideoPlayer.open(clip, 1920, 1080, events)) {
            VideoPlayer.Info info = events.opened.get(10, TimeUnit.SECONDS);
            assertTrue(info.hasPicture());
            assertTrue(info.hasSound());
            assertEquals(320, info.width());
            assertEquals(240, info.height());
            assertEquals(0, info.rotation());
            assertEquals(2_000_000, info.durationMicros(), 100_000);

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
        try (VideoPlayer player = VideoPlayer.open(silent, 1920, 1080, events)) {
            assertFalse(events.opened.get(10, TimeUnit.SECONDS).hasSound());
            collectUntilEnded(player, events);
            double seconds = (System.nanoTime() - started) / 1e9;
            assertTrue(seconds >= 0.85, "took about its 1 s length: " + seconds + " s");
        }
    }

    @Test
    void seekWhilePausedShowsThePictureThereAndStaysPaused() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = VideoPlayer.open(clip, 1920, 1080, events)) {
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
    void movAndMkvPlayToo() throws Exception {
        for (String name : List.of("clip.mov", "clip.mkv")) {
            Path file = TestMedia.video(dir.resolve(name), 320, 240, 10, FPS, true, 0);
            Events events = new Events();
            try (VideoPlayer player = VideoPlayer.open(file, 1920, 1080, events)) {
                assertTrue(events.opened.get(10, TimeUnit.SECONDS).hasPicture(), name);
                assertNotNull(waitForPicture(player, _ -> true), name);
            }
        }
    }

    @Test
    void readsThePhoneRotationTag() throws Exception {
        Path turned = TestMedia.video(dir.resolve("turned.mp4"), 320, 240, 5, FPS, false, 90);
        Events events = new Events();
        try (VideoPlayer _ = VideoPlayer.open(turned, 1920, 1080, events)) {
            VideoPlayer.Info info = events.opened.get(10, TimeUnit.SECONDS);
            assertEquals(90, info.rotation());
            assertEquals(320, info.width(), "the stored (unturned) size");
        }
    }

    @Test
    void picturesAreScaledDownToTheScreen() throws Exception {
        Events events = new Events();
        try (VideoPlayer player = VideoPlayer.open(clip, 160, 160, events)) {
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
        VideoPlayer player = VideoPlayer.open(clip, 1920, 1080, events);
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

    private static long videoThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("prismo-video-") && t.isAlive()).count();
    }
}
