package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Paths come from a {@code @TempDir} so they are absolute on whatever OS runs the test — a literal
 * like "/home/user" is not absolute on Windows.
 */
class AppPathsTest {

    @TempDir
    Path tmp;

    @Test
    void windowsUsesLocalAppData() {
        Path localAppData = tmp.resolve("Local");

        AppPaths paths = AppPaths.resolve(OperatingSystem.WINDOWS,
                Map.of("LOCALAPPDATA", localAppData.toString()), tmp.resolve("home"));

        Path base = localAppData.resolve("Prismo");
        assertEquals(base, paths.dataDir());
        assertEquals(base.resolve("cache"), paths.cacheDir());
        assertEquals(base.resolve("logs"), paths.logDir());
    }

    @Test
    void windowsFallsBackToHomeWhenLocalAppDataMissing() {
        Path home = tmp.resolve("home");

        AppPaths paths = AppPaths.resolve(OperatingSystem.WINDOWS, Map.of(), home);

        assertEquals(home.resolve("AppData").resolve("Local").resolve("Prismo"), paths.dataDir());
    }

    @Test
    void linuxUsesXdgVariables() {
        Map<String, String> env = Map.of(
                "XDG_DATA_HOME", tmp.resolve("data").toString(),
                "XDG_CACHE_HOME", tmp.resolve("cache").toString(),
                "XDG_STATE_HOME", tmp.resolve("state").toString());

        AppPaths paths = AppPaths.resolve(OperatingSystem.LINUX, env, tmp.resolve("home"));

        assertEquals(tmp.resolve("data").resolve("prismo"), paths.dataDir());
        assertEquals(tmp.resolve("cache").resolve("prismo"), paths.cacheDir());
        assertEquals(tmp.resolve("state").resolve("prismo").resolve("logs"), paths.logDir());
    }

    @Test
    void linuxFallsBackToXdgDefaultsWhenUnset() {
        Path home = tmp.resolve("home");

        AppPaths paths = AppPaths.resolve(OperatingSystem.LINUX, Map.of(), home);

        assertEquals(home.resolve(".local").resolve("share").resolve("prismo"), paths.dataDir());
        assertEquals(home.resolve(".cache").resolve("prismo"), paths.cacheDir());
        assertEquals(home.resolve(".local").resolve("state").resolve("prismo").resolve("logs"), paths.logDir());
    }

    @Test
    void relativeOrBlankVariablesAreIgnored() {
        Path home = tmp.resolve("home");
        Map<String, String> env = Map.of("XDG_DATA_HOME", "relative/dir", "XDG_CACHE_HOME", "  ");

        AppPaths paths = AppPaths.resolve(OperatingSystem.LINUX, env, home);

        assertEquals(home.resolve(".local").resolve("share").resolve("prismo"), paths.dataDir());
        assertEquals(home.resolve(".cache").resolve("prismo"), paths.cacheDir());
    }
}
