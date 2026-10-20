package io.github.mihirvr.prismo.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs another program (Prismo uses the bundled {@code ffmpeg}) as a separate process, with a time limit. If the
 * program crashes or hangs on a damaged file, only that process ends; Prismo carries on.
 */
public final class ExternalProgram {

    private ExternalProgram() {
    }

    /**
     * Runs {@code command} and returns what it printed (stdout and stderr together, at most 64 KB; with
     * {@code -loglevel error} that's its errors). Ends it and throws after {@code timeoutSeconds}.
     */
    public static String run(List<String> command, long timeoutSeconds) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        // Read its output on another thread while it runs: a full pipe would block it, and reading here
        // would wait for it to end, so a stuck program would never reach the time limit below.
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()),
                Thread.ofVirtual()::start);
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " took longer than " + timeoutSeconds + " s");
            }
            return output.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.getFirst(), e);
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            byte[] text = stream.readNBytes(64 * 1024);
            stream.transferTo(OutputStream.nullOutputStream());
            return new String(text, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
