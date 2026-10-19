package io.github.mihirvr.prismo.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches each library folder's whole tree with one registration, using the JDK's Windows-only
 * {@code FILE_TREE} modifier.
 *
 * <p>{@code FILE_TREE} lives in {@code com.sun.nio.file.ExtendedWatchEventModifier} (module
 * {@code jdk.unsupported}). Naming that class in code makes javac warn "internal proprietary API", which our
 * {@code -Werror} build turns into an error, so it's looked up by name at runtime instead. Checked in #8 on
 * Windows 11 / JDK 25: it registers, reports paths below the root (e.g. {@code sub\deeper\b.jpg}), reports a
 * rename as delete + create, reports a renamed folder only as the folder (not the files in it), and sends
 * {@code OVERFLOW} when about 1,500 files are created or deleted at once.
 */
final class TreeWatcher implements FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(TreeWatcher.class);
    private static final WatchEvent.Modifier FILE_TREE = fileTreeModifier();
    private static final WatchEvent.Kind<?>[] KINDS = {
            StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY};

    private final Listener listener;
    private final WatchService service;
    private final Map<WatchKey, Path> roots = new ConcurrentHashMap<>();
    private final Thread thread;

    TreeWatcher(Listener listener) throws IOException {
        if (FILE_TREE == null) {
            throw new UnsupportedOperationException("FILE_TREE watching is not available on this JDK");
        }
        this.listener = listener;
        this.service = FileSystems.getDefault().newWatchService();
        this.thread = Thread.ofPlatform().name("prismo-watcher").daemon(true).start(this::run);
    }

    /** True on Windows with a JDK that still has the modifier. */
    static boolean isAvailable() {
        return FILE_TREE != null && System.getProperty("os.name", "").startsWith("Windows");
    }

    @Override
    public void watch(Path root) throws IOException {
        if (roots.containsValue(root)) {
            return;
        }
        WatchKey key = root.register(service, KINDS, FILE_TREE);
        roots.put(key, root);
        log.info("Watching {} (whole tree)", root);
    }

    private void run() {
        try {
            while (true) {
                WatchKey key = service.take();
                Path root = roots.get(key);
                if (root != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        deliver(root, event);
                    }
                }
                if (!key.reset()) {
                    roots.remove(key);
                    if (root != null) {
                        listener.stopped(root, "the folder can no longer be watched (deleted, renamed or unplugged)");
                    }
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // closing
        }
    }

    /** One event to the listener. Package-private for a test (an OS overflow can't be forced on demand). */
    void deliver(Path root, WatchEvent<?> event) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            listener.overflow(root);
            return;
        }
        Path path = root.resolve((Path) event.context());
        // A folder's own "modified" event only says something inside it changed; that has its own event.
        if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY && Files.isDirectory(path)) {
            return;
        }
        listener.changed(root, path);
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

    private static WatchEvent.Modifier fileTreeModifier() {
        try {
            return (WatchEvent.Modifier) Class.forName("com.sun.nio.file.ExtendedWatchEventModifier")
                    .getField("FILE_TREE").get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
