package io.github.mihirvr.prismo.watch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Both watchers against real file-system events on this machine. */
@Timeout(20)
class WatcherTest {

    @TempDir
    Path tmp;

    /** Records every callback as text: "changed <path>", "overflow", "stopped". */
    private static final class Recorder implements FileWatcher.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        @Override
        public void changed(Path root, Path path) {
            events.add("changed " + root.relativize(path));
        }

        @Override
        public void overflow(Path root) {
            events.add("overflow");
        }

        @Override
        public void stopped(Path root, String reason) {
            events.add("stopped");
        }

        /** Everything that arrives until nothing new has come for {@code quietMs}. */
        List<String> drain(long quietMs) throws InterruptedException {
            List<String> got = new ArrayList<>();
            String event;
            while ((event = events.poll(quietMs, TimeUnit.MILLISECONDS)) != null) {
                got.add(event);
            }
            return got;
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void treeWatcherSeesChangesDeepInTheTree() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("lib").resolve("sub").resolve("deep")).getParent().getParent()
                .toRealPath();
        Recorder recorder = new Recorder();
        assertTrue(TreeWatcher.isAvailable());
        try (FileWatcher watcher = new TreeWatcher(recorder)) {
            watcher.watch(root);
            checkCommonEvents(root, recorder);
        }
    }

    @Test
    void directoryWatcherSeesChangesAndWatchesNewFolders() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("lib").resolve("sub").resolve("deep")).getParent().getParent()
                .toRealPath();
        Recorder recorder = new Recorder();
        try (FileWatcher watcher = new DirectoryWatcher(recorder)) {
            watcher.watch(root);
            checkCommonEvents(root, recorder);

            // A folder created after watching started is watched too.
            Path later = Files.createDirectories(root.resolve("later"));
            assertTrue(recorder.drain(500).contains("changed later"));
            Files.writeString(later.resolve("x.jpg"), "x");
            assertTrue(recorder.drain(500).contains("changed " + Path.of("later", "x.jpg")),
                    "file in a folder created after watching started");
        }
    }

    /**
     * Whether Windows' event buffer overflows depends on how fast events are read (a scratch program that
     * read late saw OVERFLOW at 1,500 files; this watcher's own thread may keep up). What must hold either
     * way: no file is silently lost.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aBurstOfFilesIsEitherAllReportedOrAnOverflow() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("lib")).toRealPath();
        Recorder recorder = new Recorder();
        try (FileWatcher watcher = new TreeWatcher(recorder)) {
            watcher.watch(root);
            Path burst = Files.createDirectories(root.resolve("burst"));
            for (int i = 0; i < 2000; i++) {
                Files.writeString(burst.resolve("f" + i + ".jpg"), "b");
            }
            List<String> events = recorder.drain(1000);
            long files = events.stream().filter(e -> e.startsWith("changed burst" + root.getFileSystem().getSeparator()))
                    .distinct().count();
            assertTrue(events.contains("overflow") || files == 2000, "overflow or all 2,000 files, got " + files);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void anOverflowEventIsPassedOnAsAnOverflow() throws Exception {
        Path root = tmp.toRealPath();
        Recorder recorder = new Recorder();
        try (TreeWatcher watcher = new TreeWatcher(recorder)) {
            watcher.deliver(root, new WatchEvent<Object>() {
                @Override
                public Kind<Object> kind() {
                    return StandardWatchEventKinds.OVERFLOW;
                }

                @Override
                public int count() {
                    return 1;
                }

                @Override
                public Object context() {
                    return null;
                }
            });
            assertTrue(recorder.drain(100).contains("overflow"));
        }
    }

    private static void checkCommonEvents(Path root, Recorder recorder) throws Exception {
        String sep = root.getFileSystem().getSeparator();
        Path file = Files.writeString(root.resolve("sub").resolve("deep").resolve("a.jpg"), "hello");
        assertTrue(recorder.drain(500).contains("changed sub" + sep + "deep" + sep + "a.jpg"), "create deep down");

        Files.move(file, file.resolveSibling("b.jpg"));
        List<String> rename = recorder.drain(500);
        assertTrue(rename.contains("changed sub" + sep + "deep" + sep + "a.jpg")
                && rename.contains("changed sub" + sep + "deep" + sep + "b.jpg"), "rename = old and new path: " + rename);
        assertFalse(rename.contains("changed sub" + sep + "deep"), "a folder's own 'modified' is not reported: " + rename);

        Files.move(root.resolve("sub").resolve("deep"), root.resolve("sub").resolve("renamed"));
        List<String> folder = recorder.drain(500);
        assertTrue(folder.contains("changed sub" + sep + "deep") && folder.contains("changed sub" + sep + "renamed"),
                "folder rename = old and new folder: " + folder);
    }
}
