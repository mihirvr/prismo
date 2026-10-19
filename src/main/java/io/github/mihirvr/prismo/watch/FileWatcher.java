package io.github.mihirvr.prismo.watch;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Watches library folders for changes made outside Prismo and reports them to a {@link Listener}.
 *
 * <p>Our own interface over {@code java.nio.file.WatchService}, so the backend can change per OS (and later,
 * if needed, to a native one). {@link #forThisComputer} picks one:
 * <ul>
 *   <li>{@link TreeWatcher}: one watch per library folder covering the whole tree (Windows);</li>
 *   <li>{@link DirectoryWatcher}: one watch per folder, new folders added as they appear (Linux, where
 *       inotify isn't recursive, and anywhere the tree watch isn't available).</li>
 * </ul>
 * Watching is best effort: events can be lost ({@link Listener#overflow}) or a folder can stop being
 * watchable ({@link Listener#stopped}). The app's periodic rescan catches anything missed.
 */
public interface FileWatcher extends AutoCloseable {

    /** Called on the watcher's own thread. Must be quick: hand work elsewhere. */
    interface Listener {

        /**
         * Something at {@code path} changed: a file created, changed or deleted, or a folder created or
         * deleted (or moved in or out, which looks the same). Changes inside a folder that only touch the
         * folder's own timestamp are not reported.
         */
        void changed(Path root, Path path);

        /** Events under {@code root} were lost (too many at once): only a rescan of {@code root} is safe. */
        void overflow(Path root);

        /** {@code root} is no longer watched (deleted, unplugged, or too many folders to watch). */
        void stopped(Path root, String reason);
    }

    /** Starts watching {@code root} (a library folder, in its real on-disk form). Watching it twice is a no-op. */
    void watch(Path root) throws IOException;

    /** Stops watching everything and ends the watcher's thread. */
    @Override
    void close();

    /** The best watcher this computer offers. */
    static FileWatcher forThisComputer(Listener listener) throws IOException {
        return TreeWatcher.isAvailable() ? new TreeWatcher(listener) : new DirectoryWatcher(listener);
    }
}
