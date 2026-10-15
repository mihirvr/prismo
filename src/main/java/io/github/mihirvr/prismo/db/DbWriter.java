package io.github.mihirvr.prismo.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single writer: one platform thread that owns the only write connection.
 *
 * <p>SQLite allows one writer at a time for the whole file. Funnelling every write through one thread
 * means writes never compete for the lock, so "database is locked" can't happen between our own writes.
 * Each submitted {@link SqlWork} runs in its own transaction: committed if it returns, rolled back if it
 * throws.
 *
 * <p>A platform thread, not a virtual one: SQLite calls are native (JNI) and would pin a virtual thread
 * to its carrier anyway.
 */
final class DbWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbWriter.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private final Connection connection;
    private final ExecutorService executor;

    DbWriter(Connection connection) throws SQLException {
        this.connection = connection;
        // Every unit of work is a transaction; we commit or roll back ourselves.
        connection.setAutoCommit(false);
        // Daemon: an unclosed writer must not keep the JVM alive. close() drains queued work first.
        this.executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("prismo-db-writer").daemon(true).factory());
    }

    /**
     * Queues work for the writer thread. The future completes with the work's result after commit, or
     * exceptionally after rollback.
     *
     * @throws java.util.concurrent.RejectedExecutionException if the writer is closed
     */
    <T> CompletableFuture<T> submit(SqlWork<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        executor.execute(() -> runInTransaction(work, result));
        return result;
    }

    private <T> void runInTransaction(SqlWork<T> work, CompletableFuture<T> result) {
        try {
            T value = work.run(connection);
            connection.commit();
            result.complete(value);
        } catch (Throwable t) {
            // Complete the future in every case, or the caller would wait forever.
            rollbackQuietly(t);
            result.completeExceptionally(t);
            if (t instanceof Error error) {
                throw error;
            }
        }
    }

    private void rollbackQuietly(Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    /** Stops accepting work, waits for queued work to finish, then closes the connection. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("DB writer did not finish within {}s; abandoning queued writes", SHUTDOWN_WAIT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close the write connection", e);
        }
    }
}
