package io.github.mihirvr.prismo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.db.Database;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class SearcherTest {

    @TempDir
    Path tmp;

    @Test
    void searchesThatWaitedAndWereReplacedAreSkipped() throws Exception {
        try (Database database = Database.open(tmp.resolve("prismo.db"))) {
            database.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("""
                            INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                               date_modified, date_added)
                            VALUES (1, 'D:\\a\\goa.jpg', 'D:\\a', 'goa.jpg', 'jpg', 'photo', 1, 0, 0)""");
                }
                return null;
            }).get();
            ExecutorService thread = Executors.newSingleThreadExecutor();
            List<String> ran = new CopyOnWriteArrayList<>();
            List<String> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            Searcher.Query real = query -> {
                ran.add(query.words().getFirst().text());
                return database.read(connection -> SearchSql.run(connection, query));
            };
            try (Searcher searcher = new Searcher(real, thread)) {
                // Hold the search thread, as a slow running query would, while three searches queue up.
                thread.execute(() -> await(release));
                searcher.search("g", r -> delivered.add(r.text()), e -> delivered.add("error"));
                searcher.search("go", r -> delivered.add(r.text()), e -> delivered.add("error"));
                searcher.search("goa", r -> {
                    delivered.add(r.text() + "=" + r.items().size());
                    done.countDown();
                }, e -> delivered.add("error"));
                release.countDown();
                done.await();
            }

            assertEquals(List.of("goa"), ran, "the two replaced searches never run");
            assertEquals(List.of("goa=1"), delivered);
        }
    }

    @Test
    void aSearchReplacedWhileRunningIsNotDelivered() throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        Searcher.Query held = query -> {
            if (query.words().getFirst().text().equals("old")) {
                firstStarted.countDown();
                await(release); // still running when the newer search is asked for
            }
            return List.of();
        };
        try (Searcher searcher = new Searcher(held, thread)) {
            searcher.search("old", r -> delivered.add(r.text()), e -> delivered.add("error"));
            firstStarted.await();
            searcher.search("new", r -> {
                delivered.add(r.text());
                done.countDown();
            }, e -> delivered.add("error"));
            release.countDown();
            done.await();
        }

        assertEquals(List.of("new"), delivered);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
