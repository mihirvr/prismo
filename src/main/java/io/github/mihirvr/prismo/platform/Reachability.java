package io.github.mihirvr.prismo.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Which folders can be reached right now (P7, row 8: an unplugged USB drive, a NAS that is off). Each folder is asked
 * on its own virtual thread, and one that hasn't answered within the time limit counts as unreachable: on Windows, a
 * look at a network drive whose computer is gone can block for 20 s or more, and that must not hold anything up. A
 * thread that is still stuck finishes on its own later; it holds no lock.
 */
public final class Reachability {

    private Reachability() {
    }

    /** The folders of {@code folders} that aren't a reachable directory within {@code limit}, in the same order. */
    public static Set<Path> unreachable(List<Path> folders, Duration limit) {
        return unreachable(folders, limit, Files::isDirectory);
    }

    static Set<Path> unreachable(List<Path> folders, Duration limit, Predicate<Path> isFolder) {
        Map<Path, CompletableFuture<Boolean>> asked = new LinkedHashMap<>();
        for (Path folder : folders) {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            Thread.ofVirtual().name("prismo-reachable").start(() -> {
                try {
                    answer.complete(isFolder.test(folder));
                } catch (RuntimeException e) {
                    answer.complete(false); // e.g. a SecurityException: not usable either
                }
            });
            asked.put(folder, answer);
        }
        long deadline = System.nanoTime() + limit.toNanos();
        Set<Path> gone = new LinkedHashSet<>();
        asked.forEach((folder, answer) -> {
            try {
                long left = Math.max(0, deadline - System.nanoTime());
                if (!answer.get(left, TimeUnit.NANOSECONDS)) {
                    gone.add(folder);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                gone.add(folder);
            } catch (Exception e) { // timed out
                gone.add(folder);
            }
        });
        return gone;
    }
}
