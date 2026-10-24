package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// A bug in the queue usually shows as a future that never completes: fail fast instead of hanging the build.
@Timeout(10)
class ThumbnailServiceTest {

    @TempDir
    Path tmp;

    private final AtomicInteger generated = new AtomicInteger();
    private final ThumbnailGenerator real = new ThumbnailGenerator(320);
    private ThumbnailCache cache;
    private ThumbnailService service;

    @BeforeEach
    void setUp() {
        cache = new ThumbnailCache(tmp.resolve("cache"), 320);
        service = new ThumbnailService(cache, (source, target) -> {
            generated.incrementAndGet();
            real.generate(source, target);
        }, 2);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void generatesOnceThenServesFromCache() throws Exception {
        MediaRef ref = ref(1, TestMedia.png(tmp.resolve("a.png"), 600, 400));

        var first = service.request(ref).get();
        var second = service.request(ref);

        Path file = assertInstanceOf(ThumbnailService.Ready.class, first).file();
        assertEquals(cache.fileFor(1), file);
        assertEquals(ref.dateModified(), Files.getLastModifiedTime(file).toMillis(), "stamped with the source time");
        assertTrue(second.isDone(), "fresh thumbnail is returned without queueing");
        assertEquals(1, generated.get());
    }

    @Test
    void changedSourceIsRegenerated() throws Exception {
        Path png = TestMedia.png(tmp.resolve("a.png"), 600, 400);
        service.request(ref(1, png)).get();

        MediaRef edited = new MediaRef(1, png, "png", ref(1, png).dateModified() + 60_000);
        service.request(edited).get();

        assertEquals(2, generated.get());
    }

    @Test
    void corruptFileFailsOnceAndIsNotRetried() throws Exception {
        MediaRef ref = ref(7, Files.write(tmp.resolve("bad.jpg"), new byte[] {1, 2, 3}));

        var first = service.request(ref).get();
        var second = service.request(ref).get();

        assertInstanceOf(ThumbnailService.Failed.class, first);
        assertInstanceOf(ThumbnailService.Failed.class, second);
        assertEquals(1, generated.get(), "the .fail marker stops a retry");
        try (Stream<Path> files = Files.list(cache.fileFor(7).getParent())) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "no temp files left behind");
        }
    }

    @Test
    void aFileThatWentAwayDuringTheDecodeIsMissingNotFailed() throws Exception {
        // The drive is unplugged mid-read: the decoder reports some other error, but the file is gone.
        ThumbnailService failing = new ThumbnailService(cache, (source, target) -> {
            throw new IOException("read error");
        }, 1);
        try {
            MediaRef ref = new MediaRef(9, tmp.resolve("gone.png"), "png", 1_000);

            assertInstanceOf(ThumbnailService.Missing.class, failing.request(ref).get());
            assertFalse(Files.exists(cache.failMarkerFor(9)), "no fail marker: tried again once it is back");
        } finally {
            failing.close();
        }
    }

    @Test
    void aFileThatIsntThereIsTriedAgainOnceItIsBack() throws Exception {
        Path png = tmp.resolve("on-usb.png");
        MediaRef ref = ref(8, TestMedia.png(png, 300, 200));
        Path away = Files.move(png, tmp.resolve("unplugged.png"));

        assertInstanceOf(ThumbnailService.Missing.class, service.request(ref).get(), "unplugged: not a failure");
        assertFalse(Files.exists(cache.failMarkerFor(8)), "nothing remembered");
        Files.move(away, png);
        assertInstanceOf(ThumbnailService.Ready.class, service.request(ref).get(), "plugged back in: made now");
    }

    @Test
    void unsupportedFormatIsReportedWithoutTryingToDecode() throws Exception {
        MediaRef ref = ref(3, Files.write(tmp.resolve("photo.xyz"), new byte[] {0}));

        var result = service.request(ref).get();

        assertEquals(new ThumbnailService.Unsupported("xyz"), result);
        assertEquals(0, generated.get());
    }

    @Test
    void concurrentRequestsForTheSameFileShareOneJob() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThumbnailService slow = new ThumbnailService(cache, (source, target) -> {
            generated.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted while held");
            }
            real.generate(source, target);
        }, 2);
        try {
            MediaRef ref = ref(5, TestMedia.png(tmp.resolve("b.png"), 300, 300));
            List<CompletableFuture<ThumbnailService.Result>> requests = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                requests.add(slow.request(ref));
            }
            release.countDown();
            for (var request : requests) {
                assertInstanceOf(ThumbnailService.Ready.class, request.get());
            }
            assertEquals(1, generated.get(), "10 requests, 1 decode");
        } finally {
            slow.close();
        }
    }

    @Test
    void warmUpCountsEveryOutcome() throws Exception {
        List<MediaRef> refs = List.of(
                ref(1, TestMedia.png(tmp.resolve("1.png"), 50, 50)),
                ref(2, TestMedia.png(tmp.resolve("2.png"), 50, 50)),
                ref(3, TestMedia.jpegWithExif(tmp.resolve("3.jpg"), 50, 50, "X", "Y", 1, "2024:01:01 00:00:00")),
                ref(4, Files.write(tmp.resolve("4.jpg"), new byte[] {9})),
                ref(5, Files.write(tmp.resolve("5.xyz"), new byte[] {9})));
        List<ThumbnailService.WarmUpProgress> updates = new ArrayList<>();

        var done = service.warmUp(refs, updates::add);

        assertEquals(new ThumbnailService.WarmUpProgress(5, 3, 1, 1, 0, true), done);
        assertTrue(updates.get(updates.size() - 1).done());
    }

    @Test
    void warmUpKeepsReportingAfterEverythingIsQueued() throws Exception {
        // Fewer files than the queue window: all are queued at once, so only finished jobs can report.
        ThumbnailService slow = new ThumbnailService(cache, (source, target) -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted");
            }
            real.generate(source, target);
        }, 1);
        try {
            List<MediaRef> refs = List.of(
                    ref(1, TestMedia.png(tmp.resolve("1.png"), 50, 50)),
                    ref(2, TestMedia.png(tmp.resolve("2.png"), 50, 50)),
                    ref(3, TestMedia.png(tmp.resolve("3.png"), 50, 50)));
            List<ThumbnailService.WarmUpProgress> updates = Collections.synchronizedList(new ArrayList<>());

            slow.warmUp(refs, updates::add);

            assertTrue(updates.stream().anyMatch(u -> !u.done() && u.ready() > 0),
                    "a count above 0 was shown before the last job finished: " + updates);
        } finally {
            slow.close();
        }
    }

    // --- Priority and cancelling. One worker thread, held busy on a "blocker" job while the test queues
    // more requests, so the order they then run in is exactly the queue's order.

    private final List<String> order = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch blockerRunning = new CountDownLatch(1);
    private final CountDownLatch releaseBlocker = new CountDownLatch(1);

    /** A 1-thread service that records the order files are generated in and holds "blocker" until released. */
    private ThumbnailService oneThreadService() {
        return new ThumbnailService(cache, (source, target) -> {
            String name = source.getFileName().toString();
            order.add(name.substring(0, name.indexOf('.')));
            if (name.startsWith("blocker")) {
                blockerRunning.countDown();
                try {
                    releaseBlocker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("interrupted while held");
                }
            }
            real.generate(source, target);
        }, 1);
    }

    /** Starts the blocker job and waits until the only worker is busy with it. */
    private CompletableFuture<ThumbnailService.Result> occupyWorker(ThumbnailService one) throws Exception {
        var blocker = one.request(png(100, "blocker"));
        blockerRunning.await();
        return blocker;
    }

    @Test
    void visibleRequestsRunBeforeWarmUpNewestFirst() throws Exception {
        ThumbnailService one = oneThreadService();
        try {
            var blocker = occupyWorker(one);
            var a = one.request(png(1, "a"), ThumbnailService.Priority.BACKGROUND);
            var b = one.request(png(2, "b"), ThumbnailService.Priority.BACKGROUND);
            var v1 = one.request(png(3, "v1"), ThumbnailService.Priority.VISIBLE);
            var v2 = one.request(png(4, "v2"), ThumbnailService.Priority.VISIBLE);
            releaseBlocker.countDown();
            CompletableFuture.allOf(blocker, a, b, v1, v2).get();

            // Visible beats background; among visible, the latest request (most likely still on screen) wins;
            // background keeps the order it was asked in.
            assertEquals(List.of("blocker", "v2", "v1", "a", "b"), order);
        } finally {
            one.close();
        }
    }

    @Test
    void moreThreadsStartAtOnceAndTheCountStaysInRange() throws Exception {
        ThumbnailService one = oneThreadService();
        try {
            var blocker = occupyWorker(one); // the only thread is busy until released
            one.setThreads(2);
            var a = one.request(png(1, "a"), ThumbnailService.Priority.VISIBLE);

            a.get(); // made by the new second thread while the first is still blocked
            assertFalse(blocker.isDone());
            assertEquals(2, one.threads());
            releaseBlocker.countDown();
            blocker.get();

            one.setThreads(0);
            assertEquals(1, one.threads(), "never fewer than one");
            one.setThreads(ThumbnailService.MAX_THREADS + 5);
            assertEquals(ThumbnailService.MAX_THREADS, one.threads(), "never more than one per core");
            // The service still takes work after the changes.
            assertInstanceOf(ThumbnailService.Ready.class, one.request(png(2, "b")).get());
        } finally {
            one.close();
        }
    }

    @Test
    void cancelledRequestIsSkippedNotDecoded() throws Exception {
        ThumbnailService one = oneThreadService();
        try {
            var blocker = occupyWorker(one);
            var gone = one.request(png(1, "gone"), ThumbnailService.Priority.VISIBLE);
            var kept = one.request(png(2, "kept"), ThumbnailService.Priority.VISIBLE);
            // Runs after every visible task, so once it's done the cancelled one has had its turn too.
            var last = one.request(png(3, "last"), ThumbnailService.Priority.BACKGROUND);
            assertTrue(gone.cancel(false));
            releaseBlocker.countDown();
            CompletableFuture.allOf(blocker, kept, last).get();

            assertEquals(List.of("blocker", "kept", "last"), order);
            assertFalse(Files.exists(cache.fileFor(1)), "no thumbnail made for the cancelled request");
        } finally {
            one.close();
        }
    }

    @Test
    void cancellingOneCallerKeepsTheJobForTheOthers() throws Exception {
        ThumbnailService one = oneThreadService();
        try {
            var blocker = occupyWorker(one);
            MediaRef shared = png(1, "shared");
            var onScreen = one.request(shared, ThumbnailService.Priority.VISIBLE);
            var warmUp = one.request(shared, ThumbnailService.Priority.BACKGROUND);
            onScreen.cancel(false);
            releaseBlocker.countDown();
            blocker.get();

            assertInstanceOf(ThumbnailService.Ready.class, warmUp.get());
            assertEquals(List.of("blocker", "shared"), order);
        } finally {
            one.close();
        }
    }

    @Test
    void warmUpJobJumpsTheQueueOnceItIsOnScreen() throws Exception {
        ThumbnailService one = oneThreadService();
        try {
            var blocker = occupyWorker(one);
            var a = one.request(png(1, "a"), ThumbnailService.Priority.BACKGROUND);
            MediaRef x = png(2, "x");
            var xWarmUp = one.request(x, ThumbnailService.Priority.BACKGROUND);
            var xOnScreen = one.request(x, ThumbnailService.Priority.VISIBLE);
            releaseBlocker.countDown();
            CompletableFuture.allOf(blocker, a, xWarmUp, xOnScreen).get();

            assertEquals(List.of("blocker", "x", "a"), order, "x moved ahead of a, and was made once");
        } finally {
            one.close();
        }
    }

    @Test
    void scrolledAwayRequestFallsBackToWarmUpOrder() throws Exception {
        ThumbnailService one = oneThreadService();
        try {
            var blocker = occupyWorker(one);
            var a = one.request(png(1, "a"), ThumbnailService.Priority.BACKGROUND);
            MediaRef x = png(2, "x");
            var xWarmUp = one.request(x, ThumbnailService.Priority.BACKGROUND);
            one.request(x, ThumbnailService.Priority.VISIBLE).cancel(false); // seen, then scrolled away
            releaseBlocker.countDown();
            CompletableFuture.allOf(blocker, a, xWarmUp).get();

            assertEquals(List.of("blocker", "a", "x"), order, "x waits its warm-up turn again");
        } finally {
            one.close();
        }
    }

    // --- Two-pass warm-up (1.0.1): quick thumbnails first, then the rest, videos last and at most two at once.

    /** A tiny JPEG at {@code target}, whatever the source: the tests below are about order, not pictures. */
    private static void anyThumbnail(Path target) throws IOException {
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "jpg", target.toFile());
    }

    /** A generator for which files named "q..." are quick; it records what it made, in order. */
    private ThumbnailService.Generator recordingGenerator(List<String> made) {
        return new ThumbnailService.Generator() {
            @Override
            public void generate(Path source, Path target) throws IOException {
                made.add(source.getFileName().toString());
                anyThumbnail(target);
            }

            @Override
            public boolean generateQuick(Path source, Path target) throws IOException {
                if (!source.getFileName().toString().startsWith("q")) {
                    return false;
                }
                made.add(source.getFileName().toString());
                anyThumbnail(target);
                return true;
            }
        };
    }

    @Test
    @Timeout(20)
    void warmUpMakesTheQuickOnesFirstThenStillsThenVideos() throws Exception {
        List<String> made = Collections.synchronizedList(new ArrayList<>());
        ThumbnailService one = new ThumbnailService(cache, recordingGenerator(made), 1);
        try {
            List<MediaRef> newestFirst = List.of(
                    ref(1, Files.write(tmp.resolve("v1.mp4"), new byte[] {1})),
                    ref(2, TestMedia.png(tmp.resolve("s1.png"), 20, 20)),
                    ref(3, TestMedia.png(tmp.resolve("q1.png"), 20, 20)),
                    ref(4, TestMedia.png(tmp.resolve("s2.png"), 20, 20)),
                    ref(5, TestMedia.png(tmp.resolve("q2.png"), 20, 20)));

            var done = one.warmUp(newestFirst, _ -> { });

            assertEquals(List.of("q1.png", "q2.png", "s1.png", "s2.png", "v1.mp4"), made);
            assertEquals(new ThumbnailService.WarmUpProgress(5, 5, 0, 0, 0, true), done);
        } finally {
            one.close();
        }
    }

    @Test
    @Timeout(20)
    void warmUpMakesAtMostTwoVideoThumbnailsAtOnce() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        ThumbnailService four = new ThumbnailService(cache, (source, target) -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted");
            } finally {
                running.decrementAndGet();
            }
            anyThumbnail(target);
        }, 4);
        try {
            List<MediaRef> videos = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                videos.add(ref(i, Files.write(tmp.resolve("clip" + i + ".mp4"), new byte[] {1})));
            }

            var done = four.warmUp(videos, _ -> { });

            assertEquals(6, done.ready());
            assertEquals(ThumbnailService.VIDEO_JOBS, most.get(), "4 threads, but only 2 videos at once");
        } finally {
            four.close();
        }
    }

    @Test
    @Timeout(20)
    void aQuickTryThatFailsIsLeftToPassTwoWhichRemembersTheFailure() throws Exception {
        AtomicInteger fullTries = new AtomicInteger();
        ThumbnailService failing = new ThumbnailService(cache, new ThumbnailService.Generator() {
            @Override
            public void generate(Path source, Path target) throws IOException {
                fullTries.incrementAndGet();
                throw new IOException("damaged");
            }

            @Override
            public boolean generateQuick(Path source, Path target) throws IOException {
                throw new IOException("damaged");
            }
        }, 1);
        try {
            MediaRef bad = ref(7, TestMedia.png(tmp.resolve("q-bad.png"), 20, 20));

            var done = failing.warmUp(List.of(bad), _ -> { });

            assertEquals(1, done.failed());
            assertEquals(1, fullTries.get(), "pass 2 tried it once more, the usual way");
            assertTrue(Files.exists(cache.failMarkerFor(7)), "and remembered the failure");
        } finally {
            failing.close();
        }
    }

    private MediaRef png(long id, String name) throws Exception {
        return ref(id, TestMedia.png(tmp.resolve(name + ".png"), 40, 40));
    }

    private static MediaRef ref(long id, Path file) throws Exception {
        String name = file.getFileName().toString();
        return new MediaRef(id, file, name.substring(name.lastIndexOf('.') + 1),
                Files.getLastModifiedTime(file).toMillis());
    }
}
