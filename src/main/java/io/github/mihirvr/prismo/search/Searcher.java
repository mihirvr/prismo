package io.github.mihirvr.prismo.search;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs searches off the UI thread; only the newest one counts.
 *
 * <p>One platform thread ({@code prismo-search}): SQLite is native code (JNI), so a virtual thread would be
 * pinned anyway, and one thread means searches never compete with each other. Each call gets a number;
 * a search that a newer call has replaced is skipped if it hasn't started, and its result is dropped if it
 * has. So typing fast queues at most one real query behind the running one.
 */
public final class Searcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Searcher.class);

    /** The items found for {@code text}, newest first. */
    public record Result(String text, List<TimelineItem> items, Duration elapsed) {
    }

    /** Runs one search. The real one reads the database; tests can hold it mid-search. */
    interface Query {
        List<TimelineItem> run(SearchQuery query) throws SQLException;
    }

    private final Query query;
    private final ExecutorService thread;
    private final AtomicLong latest = new AtomicLong();

    public Searcher(Database database) {
        this(query -> database.read(connection -> SearchSql.run(connection, query)),
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("prismo-search").daemon(true).factory()));
    }

    /** For tests: what a search does, and the executor searches run on. */
    Searcher(Query query, ExecutorService thread) {
        this.query = query;
        this.thread = thread;
    }

    /**
     * Searches for {@code text} on the search thread. Exactly one of the callbacks runs there, and only if no
     * newer search was asked for in the meantime.
     */
    public void search(String text, Consumer<Result> onResult, Consumer<SQLException> onError) {
        long number = latest.incrementAndGet();
        thread.execute(() -> {
            if (number != latest.get()) {
                return; // replaced while it waited
            }
            SearchQuery parsed = SearchQuery.parse(text);
            long start = System.nanoTime();
            try {
                List<TimelineItem> items = query.run(parsed);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                log.debug("Search of {} words: {} results in {} ms", parsed.words().size(), items.size(),
                        elapsed.toMillis());
                if (number == latest.get()) { // not replaced while it ran
                    onResult.accept(new Result(text, items, elapsed));
                }
            } catch (SQLException e) {
                log.warn("Search failed", e);
                if (number == latest.get()) {
                    onError.accept(e);
                }
            }
        });
    }

    /** Stops the search thread. A query already running isn't interrupted, so this waits for it to finish. */
    @Override
    public void close() {
        thread.shutdownNow();
        try {
            if (!thread.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Search thread did not stop within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
