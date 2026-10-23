package io.github.mihirvr.prismo.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        Process process = processFor(command).redirectErrorStream(true).start();
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

    /**
     * A {@link ProcessBuilder} for {@code command} that also finds the libraries in the program's own folder. On
     * Linux the bundled {@code ffmpeg} finds its own FFmpeg libraries there ({@code RUNPATH $ORIGIN}), but those
     * libraries' own needs ({@code libva.so.2}, also in that folder) are looked up only in the system folders: a
     * RUNPATH doesn't carry over to the libraries a program loads. So the folder goes on {@code LD_LIBRARY_PATH}.
     * Windows already looks in the program's folder first.
     */
    public static ProcessBuilder processFor(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (OperatingSystem.current() == OperatingSystem.LINUX) {
            Path folder = Path.of(command.getFirst()).toAbsolutePath().getParent();
            builder.environment().merge("LD_LIBRARY_PATH", folder.toString(), (old, own) -> libraryPath(own, old));
        }
        return builder;
    }

    /** The program's folder first, then what was set already ({@code :}-separated, like Linux expects). */
    static String libraryPath(String folder, String existing) {
        return existing == null || existing.isBlank() ? folder : folder + ":" + existing;
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
