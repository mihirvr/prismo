package io.github.mihirvr.prismo.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches every folder of a library separately, the portable way (Linux's inotify watches one folder per
 * registration). Folders that appear later are registered as their "created" event arrives, and reported
 * whole, because files may have landed in them before the registration.
 *
 * <p>inotify caps the number of watches per user ({@code fs.inotify.max_user_watches}). When registering
 * fails because of that limit, the whole library folder stops being watched and the listener is told
 * ({@link Listener#stopped}); the app's periodic rescan keeps it up to date instead. Any other failure
 * (a folder deleted mid-walk, access denied) only skips that folder.
 *
 * <p>Not run on Linux yet (no Linux machine; comes with CI in #10). On Windows it is used by the tests.
 */
final class DirectoryWatcher implements FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(DirectoryWatcher.class);

    /** One registered folder and the library folder it belongs to. */
    private record Watched(Path root, Path folder) {
    }

    private final Listener listener;
    private final WatchService service;
    private final Map<WatchKey, Watched> keys = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> roots = new ConcurrentHashMap<>();
    private final Thread thread;

    DirectoryWatcher(Listener listener) throws IOException {
        this.listener = listener;
        this.service = FileSystems.getDefault().newWatchService();
        this.thread = Thread.ofPlatform().name("prismo-watcher").daemon(true).start(this::run);
    }

    @Override
    public void watch(Path root) throws IOException {
        if (roots.putIfAbsent(root, Boolean.TRUE) != null) {
            return;
        }
        if (!registerTree(root, root)) {
            roots.remove(root);
            throw new IOException("Too many folders to watch under " + root);
        }
        log.info("Watching {} ({} folders)", root, keys.values().stream().filter(w -> w.root().equals(root)).count());
    }

    /**
     * Registers {@code folder} and every folder below it.
     *
     * @return false if the system's watch limit was hit (the root's registrations are then cancelled)
     */
    private boolean registerTree(Path root, Path folder) {
        boolean[] limitHit = {false};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    try {
                        keys.put(dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY),
                                new Watched(root, dir));
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        if (isWatchLimit(e)) {
                            limitHit[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        log.debug("Not watching {}: {}", dir, e.toString());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE; // an unreadable folder just isn't watched
                }
            });
        } catch (IOException e) {
            log.debug("Could not walk {} to watch it: {}", folder, e.toString());
        }
        if (limitHit[0]) {
            stopRoot(root);
            return false;
        }
        return true;
    }

    /** inotify's "User limit of inotify watches reached" (ENOSPC). */
    static boolean isWatchLimit(IOException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("limit") || message.contains("no space left");
    }

    private void stopRoot(Path root) {
        keys.entrySet().removeIf(entry -> {
            if (entry.getValue().root().equals(root)) {
                entry.getKey().cancel();
                return true;
            }
            return false;
        });
    }

    private void run() {
        try {
            while (true) {
                WatchKey key = service.take();
                Watched watched = keys.get(key);
                if (watched != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        deliver(watched, event);
                    }
                }
                if (!key.reset()) {
                    keys.remove(key);
                    if (watched != null && watched.folder().equals(watched.root())) {
                        roots.remove(watched.root());
                        listener.stopped(watched.root(),
                                "the folder can no longer be watched (deleted, renamed or unplugged)");
                    }
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // closing
        }
    }

    private void deliver(Watched watched, WatchEvent<?> event) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            listener.overflow(watched.root());
            return;
        }
        Path path = watched.folder().resolve((Path) event.context());
        boolean directory = Files.isDirectory(path);
        if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY && directory) {
            return; // only "something inside changed", which has its own event
        }
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && directory
                && !registerTree(watched.root(), path)) {
            listener.stopped(watched.root(), "too many folders to watch (the system's limit); rescanning instead");
            roots.remove(watched.root());
            return;
        }
        listener.changed(watched.root(), path);
    }

    @Override
    public void close() {
        try {
            service.close();
        } catch (IOException e) {
            log.debug("Closing the watch service: {}", e.toString());
        }
        thread.interrupt();
    }
}
