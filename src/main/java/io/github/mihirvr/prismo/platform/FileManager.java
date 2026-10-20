package io.github.mihirvr.prismo.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The system's file manager: "Show in folder" opens the file's folder there with the file selected.
 *
 * <p>Windows: {@code explorer.exe /select, "<path>"} (Explorer's documented switch). Java quotes the path for
 * Explorer, so spaces and commas in names are fine. macOS: {@code open -R}. Elsewhere (Linux file managers have no
 * common "select this file" switch) the caller opens the folder itself instead ({@link #canSelect} is false).
 */
public final class FileManager {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private FileManager() {
    }

    /** True if {@link #showSelected} works here; otherwise open the folder (e.g. with HostServices). */
    public static boolean canSelect() {
        return OS.startsWith("windows") || OS.startsWith("mac");
    }

    /** The command that opens {@code file}'s folder with the file selected, or empty where there is none. */
    static List<String> selectCommand(Path file, String os) {
        if (os.startsWith("windows")) {
            return List.of("explorer.exe", "/select,", file.toString());
        }
        if (os.startsWith("mac")) {
            return List.of("open", "-R", file.toString());
        }
        return List.of();
    }

    /**
     * Opens the folder with {@code file} selected. Doesn't wait: Explorer hands the request to the running shell
     * and exits (with code 1 even when it worked), so its result says nothing.
     */
    public static void showSelected(Path file) throws IOException {
        List<String> command = selectCommand(file, OS);
        if (command.isEmpty()) {
            throw new IOException("No file manager command on this system");
        }
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }
}
