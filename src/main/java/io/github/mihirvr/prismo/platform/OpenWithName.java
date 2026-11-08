package io.github.mihirvr.prismo.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes Windows' "Open with" list show "Prismo" after an upgrade (1.2.0).
 *
 * <p><b>Why:</b> Windows reads an app's name for "Open with" from the exe's FileDescription once, and keeps it in the
 * user's name cache, {@code HKCU\Software\Classes\Local Settings\Software\Microsoft\Windows\Shell\MuiCache}, as
 * {@code <exe path>.FriendlyAppName} (and {@code .ApplicationCompany}). 1.0.x's exe said "A phone-style photo and video
 * gallery for your PC"; 1.1.0's says "Prismo", but an in-place upgrade keeps the same exe path, so Windows kept
 * showing the old text (seen on a real upgrade from 1.0.1 to 1.1.0).
 *
 * <p><b>What it does:</b> when Prismo runs as its installed {@code Prismo.exe} on Windows, it reads its own two
 * values and, if the cached name isn't the current one, deletes just those two values. Windows then reads the exe
 * again the next time it needs the name. Nothing else in the registry is touched, and nothing runs on Linux or from
 * a development build (no {@code Prismo.exe}). Uses Windows' own {@code reg.exe}; any failure is only logged.
 */
public final class OpenWithName {

    private static final Logger log = LoggerFactory.getLogger(OpenWithName.class);

    /** The name the exe gives itself (build.gradle.kts: {@code packageDescription} on Windows). */
    static final String NAME = "Prismo";
    static final String EXE = "Prismo.exe";
    static final String KEY = "HKCU\\Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\Shell\\MuiCache";
    private static final long TIMEOUT_SECONDS = 10;
    /** One line of {@code reg query /v}: "    <name>    REG_SZ    <value>". */
    private static final Pattern VALUE = Pattern.compile("REG_SZ\\s+(.*)$", Pattern.MULTILINE);

    private OpenWithName() {
    }

    /** Checks and fixes the cached name on a background thread; returns at once. Any thread. */
    public static void refreshInBackground() {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            return;
        }
        Thread.ofVirtual().name("prismo-open-with-name").start(() -> installedExe().ifPresent(OpenWithName::refresh));
    }

    /**
     * The installed launcher, or empty from a development build. jpackage's launcher sets {@code jpackage.app-path}
     * to itself; the process's own command is the fallback (the launcher runs Java inside its own process).
     */
    static Optional<Path> installedExe() {
        String launcher = System.getProperty("jpackage.app-path");
        Optional<String> command = launcher != null ? Optional.of(launcher) : ProcessHandle.current().info().command();
        return command.map(Path::of).filter(OpenWithName::isPrismoExe);
    }

    static boolean isPrismoExe(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().equalsIgnoreCase(EXE);
    }

    private static void refresh(Path exe) {
        try {
            String friendly = exe + ".FriendlyAppName";
            Optional<String> cached = valueIn(ExternalProgram.run(List.of("reg", "query", KEY, "/v", friendly),
                    TIMEOUT_SECONDS));
            if (!isStale(cached)) {
                return;
            }
            for (String value : List.of(friendly, exe + ".ApplicationCompany")) {
                ExternalProgram.run(List.of("reg", "delete", KEY, "/v", value, "/f"), TIMEOUT_SECONDS);
            }
            log.info("Removed Windows' old cached name for Prismo (Open with shows \"{}\" again)", NAME);
        } catch (Exception e) {
            log.debug("Could not check Windows' cached name for Prismo", e);
        }
    }

    /** The value from {@code reg query} output, or empty when the value isn't there. */
    static Optional<String> valueIn(String regOutput) {
        Matcher matcher = VALUE.matcher(regOutput);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }

    /** True if a cached name is there and isn't the current one (an empty cache is fine: Windows reads the exe). */
    static boolean isStale(Optional<String> cached) {
        return cached.isPresent() && !cached.get().equals(NAME);
    }
}
