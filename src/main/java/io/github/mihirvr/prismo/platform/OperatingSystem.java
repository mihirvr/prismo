package io.github.mihirvr.prismo.platform;

import java.util.Locale;

/** The operating systems Prismo supports. */
public enum OperatingSystem {
    WINDOWS,
    LINUX;

    /** Detects the OS this JVM runs on, from the {@code os.name} system property. */
    public static OperatingSystem current() {
        return fromOsName(System.getProperty("os.name", ""));
    }

    static OperatingSystem fromOsName(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("linux")) {
            return LINUX;
        }
        throw new IllegalStateException("Unsupported operating system: " + osName);
    }
}
