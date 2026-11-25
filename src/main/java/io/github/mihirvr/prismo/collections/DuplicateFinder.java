package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.db.LibraryRootStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds files with the same content (1.4.0, Collections › Duplicates). It only finds and shows them: Prismo never
 * removes a copy on its own; the user picks one and uses Trash as for any file.
 *
 * <p>Reads only what it must: a file can only have a copy if another file has exactly its size, so only those are
 * read, start to end, for a SHA-256. The result is kept in {@code media_hash} with the size and modified time it was
 * read at; a file that changed since is read again, one that didn't never is. Files are opened through
 * {@code java.nio} so reading never stops anyone else from renaming or deleting them ({@code FileReads}). A file that
 * can't be read (an unplugged drive, no permission) is skipped and tried again next time.
 */
public final class DuplicateFinder {

    private static final Logger log = LoggerFactory.getLogger(DuplicateFinder.class);
    private static final int BUFFER = 256 * 1024;
    private static final int BATCH = 200;

    /** Rows whose stored hash was read from an older version of the file. */
    static final String STALE = "DELETE FROM media_hash WHERE media_id IN (SELECT h.media_id FROM media_hash h "
            + "JOIN media m ON m.id = h.media_id WHERE m.size_bytes != h.size_bytes "
            + "OR m.date_modified != h.date_modified)";

    /** Files not in Trash, in enabled library folders, with no hash yet, sharing their size with another such file. */
    static final String TO_READ = "SELECT id, path, size_bytes, date_modified FROM media WHERE trashed_at IS NULL "
            + "AND size_bytes > 0 AND " + LibraryRootStore.NOT_IN_DISABLED_FOLDER
            + " AND id NOT IN (SELECT media_id FROM media_hash)"
            + " AND size_bytes IN (SELECT size_bytes FROM media WHERE trashed_at IS NULL AND size_bytes > 0"
            + " GROUP BY size_bytes HAVING COUNT(*) > 1)"
            + " ORDER BY size_bytes DESC";

    private record ToRead(long id, Path path, long size, long modified) {
    }

    private record Hashed(long id, long size, long modified, byte[] sha256) {
    }

    /**
     * How far a check is (1.4.1, for the note at the bottom of the window): files and bytes read of those to read.
     * Bytes move inside a big file too, so the note never looks stuck on one long video.
     */
    public record Progress(int done, int total, long bytesDone, long bytesTotal) {

        public boolean finished() {
            return done >= total;
        }
    }

    /** How often a file being read reports its bytes: every 16 MB. */
    private static final long REPORT_EVERY = 16L * 1024 * 1024;

    private final Database database;

    public DuplicateFinder(Database database) {
        this.database = database;
    }

    /**
     * Brings {@code media_hash} up to date. Blocking (reads files): call it off the UI thread.
     *
     * @param progress told how far it is: at the start, after every file, every 16 MB inside a big file, and at the end
     *                 (often: the caller shows only the latest)
     * @param cancelled checked between files and while reading one; true stops early (what was read so far is kept)
     * @param saved told after each batch of results is saved, so a page can show the copies found so far
     * @return how many files were read
     */
    public int refresh(Consumer<Progress> progress, BooleanSupplier cancelled, Runnable saved) throws SQLException {
        database.write(c -> {
            try (PreparedStatement stale = c.prepareStatement(STALE)) {
                return stale.executeUpdate();
            }
        }).join();
        List<ToRead> toRead = database.read(c -> {
            List<ToRead> rows = new ArrayList<>();
            try (PreparedStatement query = c.prepareStatement(TO_READ); ResultSet r = query.executeQuery()) {
                while (r.next()) {
                    rows.add(new ToRead(r.getLong(1), Path.of(r.getString(2)), r.getLong(3), r.getLong(4)));
                }
            }
            return rows;
        });
        long bytesTotal = 0;
        for (ToRead file : toRead) {
            bytesTotal += file.size();
        }
        int total = toRead.size();
        long allBytes = bytesTotal;
        List<Hashed> done = new ArrayList<>();
        int read = 0;
        long bytesDone = 0;
        progress.accept(new Progress(0, total, 0, bytesTotal));
        for (int i = 0; i < total && !cancelled.getAsBoolean(); i++) {
            ToRead file = toRead.get(i);
            int index = i;
            long before = bytesDone;
            try {
                if (Files.size(file.path()) == file.size()) { // else changed since it was indexed: indexer first
                    byte[] hash = sha256(file.path(), cancelled,
                            inFile -> progress.accept(new Progress(index, total, before + inFile, allBytes)));
                    if (hash != null) {
                        done.add(new Hashed(file.id(), file.size(), file.modified(), hash));
                        read++;
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read {} for duplicates: {}", file.path(), e.toString());
            }
            bytesDone += file.size();
            progress.accept(new Progress(i + 1, total, bytesDone, bytesTotal));
            if (done.size() >= BATCH) {
                save(done);
                done = new ArrayList<>();
                saved.run();
            }
        }
        save(done);
        progress.accept(new Progress(total, total, bytesTotal, bytesTotal));
        log.info("Duplicates: read {} of {} files that share a size", read, total);
        return read;
    }

    /** Like {@link #refresh(Consumer, BooleanSupplier, Runnable)}, telling nobody (tests). */
    public int refresh() throws SQLException {
        return refresh(_ -> { }, () -> false, () -> { });
    }

    private void save(List<Hashed> rows) {
        if (rows.isEmpty()) {
            return;
        }
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("INSERT OR REPLACE INTO media_hash "
                    + "(media_id, size_bytes, date_modified, sha256) SELECT ?, ?, ?, ? WHERE EXISTS "
                    + "(SELECT 1 FROM media WHERE id = ?)")) { // the row may have gone while reading
                for (Hashed row : rows) {
                    insert.setLong(1, row.id());
                    insert.setLong(2, row.size());
                    insert.setLong(3, row.modified());
                    insert.setBytes(4, row.sha256());
                    insert.setLong(5, row.id());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        }).join();
    }

    /** The file's SHA-256, read start to end without locking it. */
    static byte[] sha256(Path file) throws IOException {
        return sha256(file, () -> false, _ -> { });
    }

    /**
     * Like {@link #sha256(Path)}, saying every 16 MB how many bytes are read; null if {@code cancelled} turned true
     * part way (closing Prismo while a big video is read).
     */
    static byte[] sha256(Path file, BooleanSupplier cancelled, LongConsumer bytesRead) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
        byte[] buffer = new byte[BUFFER];
        long read = 0;
        long nextReport = REPORT_EVERY;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                read += n;
                if (read >= nextReport) {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    bytesRead.accept(read);
                    nextReport += REPORT_EVERY;
                }
            }
        }
        return digest.digest();
    }
}
