package io.github.mihirvr.prismo.platform;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where Prismo keeps its own files (database, thumbnail cache, logs).
 *
 * <p>This is the only place that decides these locations. No other code may build them by hand
 * (see DEVELOPMENT.md, "Path Resolution &amp; Hardcoding").
 *
 * @param dataDir  database and settings
 * @param cacheDir thumbnail cache; safe to delete, it can be rebuilt
 * @param logDir   rotating log files
 */
public record AppPaths(Path dataDir, Path cacheDir, Path logDir) {

    private static final String WINDOWS_APP_DIR = "Prismo";
    private static final String LINUX_APP_DIR = "prismo";

    /** The SQLite database file. SQLite also creates {@code -wal} and {@code -shm} files next to it. */
    public Path databaseFile() {
        return dataDir.resolve("prismo.db");
    }

    /** Paths for the OS, environment and user this JVM runs as. */
    public static AppPaths forCurrentOs() {
        return resolve(OperatingSystem.current(), System.getenv(), Path.of(System.getProperty("user.home")));
    }

    /** Pure function of its inputs so it can be tested for every OS on any machine. */
    static AppPaths resolve(OperatingSystem os, Map<String, String> env, Path userHome) {
        return switch (os) {
            case WINDOWS -> {
                Path base = absoluteFromEnv(env, "LOCALAPPDATA", userHome.resolve("AppData").resolve("Local"))
                        .resolve(WINDOWS_APP_DIR);
                yield new AppPaths(base, base.resolve("cache"), base.resolve("logs"));
            }
            case LINUX -> new AppPaths(
                    absoluteFromEnv(env, "XDG_DATA_HOME", userHome.resolve(".local").resolve("share"))
                            .resolve(LINUX_APP_DIR),
                    absoluteFromEnv(env, "XDG_CACHE_HOME", userHome.resolve(".cache"))
                            .resolve(LINUX_APP_DIR),
                    absoluteFromEnv(env, "XDG_STATE_HOME", userHome.resolve(".local").resolve("state"))
                            .resolve(LINUX_APP_DIR).resolve("logs"));
        };
    }

    /**
     * The variable's value if it is set to an absolute path, else the fallback. The XDG spec says a
     * relative path in these variables is invalid and must be ignored; we treat LOCALAPPDATA the same.
     */
    private static Path absoluteFromEnv(Map<String, String> env, String name, Path fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path : fallback;
    }
}
