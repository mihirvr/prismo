package io.github.mihirvr.prismo.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ChangeBatcherTest {

    private static final Path ROOT = Path.of("lib").toAbsolutePath();
    private static final Path OTHER = Path.of("other").toAbsolutePath();

    @Test
    void outermostDropsPathsInsideOtherPathsButNotLookalikes() {
        Set<Path> kept = ChangeBatcher.outermost(Set.of(ROOT.resolve("a"), ROOT.resolve("a").resolve("x.jpg"),
                ROOT.resolve("a").resolve("b").resolve("y.jpg"), ROOT.resolve("ab.jpg"), ROOT.resolve("c.jpg")));

        assertEquals(Set.of(ROOT.resolve("a"), ROOT.resolve("ab.jpg"), ROOT.resolve("c.jpg")), kept);
    }

    @Test
    void eventsBecomeOneBatchAfterAQuietPeriod() throws Exception {
        BlockingQueue<ChangeBatcher.Batch> batches = new LinkedBlockingQueue<>();
        try (ChangeBatcher batcher = new ChangeBatcher(Duration.ofMillis(150), Duration.ofSeconds(5), batches::add)) {
            batcher.changed(ROOT, ROOT.resolve("a.jpg"));
            batcher.changed(ROOT, ROOT.resolve("a.jpg")); // a copy in progress sends several
            batcher.changed(ROOT, ROOT.resolve("b.jpg"));
            batcher.changed(OTHER, OTHER.resolve("c.jpg"));

            ChangeBatcher.Batch batch = batches.poll(5, TimeUnit.SECONDS);

            assertEquals(Map.of(ROOT, Set.of(ROOT.resolve("a.jpg"), ROOT.resolve("b.jpg")),
                    OTHER, Set.of(OTHER.resolve("c.jpg"))), batch.changed());
            assertTrue(batch.rescan().isEmpty());
            assertEquals(null, batches.poll(400, TimeUnit.MILLISECONDS), "one batch, not one per event");
        }
    }

    @Test
    void anOverflowReplacesThatFoldersPathsWithARescan() throws Exception {
        BlockingQueue<ChangeBatcher.Batch> batches = new LinkedBlockingQueue<>();
        try (ChangeBatcher batcher = new ChangeBatcher(Duration.ofMillis(100), Duration.ofSeconds(5), batches::add)) {
            batcher.changed(ROOT, ROOT.resolve("a.jpg"));
            batcher.overflow(ROOT);
            batcher.changed(ROOT, ROOT.resolve("b.jpg")); // after the overflow: still covered by the rescan
            batcher.changed(OTHER, OTHER.resolve("c.jpg"));
            batcher.stopped(OTHER, "unplugged");

            ChangeBatcher.Batch batch = batches.poll(5, TimeUnit.SECONDS);

            assertEquals(Set.of(ROOT), batch.rescan());
            assertEquals(Map.of(OTHER, Set.of(OTHER.resolve("c.jpg"))), batch.changed());
            assertEquals(Map.of(OTHER, "unplugged"), batch.stopped());
        }
    }

    @Test
    void aFolderThatNeverGoesQuietIsStillHandledAfterMaxWait() throws Exception {
        BlockingQueue<ChangeBatcher.Batch> batches = new LinkedBlockingQueue<>();
        try (ChangeBatcher batcher = new ChangeBatcher(Duration.ofMillis(300), Duration.ofMillis(600), batches::add)) {
            long start = System.nanoTime();
            ChangeBatcher.Batch batch = null;
            for (int i = 0; batch == null && i < 60; i++) {
                batcher.changed(ROOT, ROOT.resolve("f" + i + ".jpg")); // every 50 ms: never 300 ms quiet
                batch = batches.poll(50, TimeUnit.MILLISECONDS);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(batch != null && elapsedMs < 1500, "a batch within about maxWait, got one after " + elapsedMs + " ms");
            assertTrue(batch.changed().get(ROOT).size() >= 8, "it holds the events so far: " + batch.changed());
        }
    }

    @Test
    void nothingIsSentWithoutEvents() throws Exception {
        BlockingQueue<ChangeBatcher.Batch> batches = new LinkedBlockingQueue<>();
        ChangeBatcher batcher = new ChangeBatcher(Duration.ofMillis(50), Duration.ofMillis(100), batches::add);
        try {
            assertEquals(List.of(), List.copyOf(batches));
            assertEquals(null, batches.poll(300, TimeUnit.MILLISECONDS));
        } finally {
            batcher.close();
        }
    }
}
