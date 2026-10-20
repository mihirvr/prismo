package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ReachabilityTest {

    @TempDir
    Path tmp;

    @Test
    void aFolderThatIsThereIsReachableAndOneThatIsGoneIsNot() throws Exception {
        Path here = Files.createDirectory(tmp.resolve("here"));
        Path gone = tmp.resolve("unplugged");
        Path file = Files.writeString(tmp.resolve("a.txt"), "x");

        assertEquals(Set.of(gone, file), Reachability.unreachable(List.of(here, gone, file), Duration.ofSeconds(5)),
                "a file is not a library folder either");
        assertEquals(Set.of(), Reachability.unreachable(List.of(), Duration.ofSeconds(1)));
    }

    @Test
    @Timeout(5)
    void aFolderThatDoesntAnswerInTimeCountsAsUnreachable() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        Path slow = tmp.resolve("dead-network-drive");
        long start = System.nanoTime();

        Set<Path> gone = Reachability.unreachable(List.of(slow, tmp), Duration.ofMillis(300), folder -> {
            if (folder.equals(slow)) {
                try {
                    never.await(); // like a look at a network drive whose computer is off
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        });

        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(Set.of(slow), gone);
        assertTrue(millis < 2_000, "waited for the limit only, not for the stuck look: " + millis + " ms");
        never.countDown();
    }

    @Test
    void anErrorWhileLookingCountsAsUnreachable() {
        assertEquals(Set.of(tmp), Reachability.unreachable(List.of(tmp), Duration.ofSeconds(1), _ -> {
            throw new SecurityException("no");
        }));
    }
}
