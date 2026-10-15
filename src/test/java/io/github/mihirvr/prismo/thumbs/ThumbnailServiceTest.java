package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void unsupportedFormatIsReportedWithoutTryingToDecode() throws Exception {
        MediaRef ref = ref(3, Files.write(tmp.resolve("photo.heic"), new byte[] {0}));

        var result = service.request(ref).get();

        assertEquals(new ThumbnailService.Unsupported("heic"), result);
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
                ref(5, Files.write(tmp.resolve("5.heic"), new byte[] {9})));
        List<ThumbnailService.WarmUpProgress> updates = new ArrayList<>();

        var done = service.warmUp(refs, updates::add);

        assertEquals(new ThumbnailService.WarmUpProgress(5, 3, 1, 1, true), done);
        assertTrue(updates.get(updates.size() - 1).done());
    }

    private static MediaRef ref(long id, Path file) throws Exception {
        String name = file.getFileName().toString();
        return new MediaRef(id, file, name.substring(name.lastIndexOf('.') + 1),
                Files.getLastModifiedTime(file).toMillis());
    }
}
