package io.github.mihirvr.prismo.ui;

import java.util.List;
import java.util.Locale;

/**
 * The Appearance setting (PROJECT.md "Settings"): Light, Dark, AMOLED (pure black, for OLED screens) or Follow
 * system (Light or Dark, as the operating system is set, and changing with it).
 *
 * <p>Light is the base stylesheet ({@code prismo.css}); each other look is small stylesheets on top that only
 * redefine its named colours (and the logo). This class is plain Java (no JavaFX toolkit), so it is unit-tested;
 * {@link Appearance} applies it to windows.
 */
public enum Theme {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    AMOLED("amoled", "AMOLED black"),
    SYSTEM("system", "Follow system");

    /** The base stylesheet: every look starts from it. */
    public static final String BASE_STYLESHEET = "/ui/prismo.css";
    static final String DARK_STYLESHEET = "/ui/theme-dark.css";
    static final String AMOLED_STYLESHEET = "/ui/theme-amoled.css";
    /** Used when nothing (or something unknown) is saved: most people expect an app to match their system. */
    public static final Theme DEFAULT = SYSTEM;

    private final String settingValue;
    private final String label;

    Theme(String settingValue, String label) {
        this.settingValue = settingValue;
        this.label = label;
    }

    /** How it is saved in the settings table. Stable: never rename these. */
    public String settingValue() {
        return settingValue;
    }

    /** The saved value back to a theme; null, empty or unknown → {@link #DEFAULT}. */
    public static Theme fromSetting(String value) {
        if (value != null) {
            String wanted = value.strip().toLowerCase(Locale.ROOT);
            for (Theme theme : values()) {
                if (theme.settingValue.equals(wanted)) {
                    return theme;
                }
            }
        }
        return DEFAULT;
    }

    /** The name in the Settings window. */
    public String label() {
        return label;
    }

    /**
     * The look actually shown: {@link #SYSTEM} becomes Light or Dark from the operating system's setting; the others
     * are themselves. Never returns SYSTEM.
     */
    public Theme resolve(boolean systemIsDark) {
        return this == SYSTEM ? (systemIsDark ? DARK : LIGHT) : this;
    }

    /** True for looks with light text on dark backgrounds (window title bar, logo). Call on a resolved theme. */
    public boolean isDark() {
        return this == DARK || this == AMOLED;
    }

    /** The stylesheets (resource paths) for a resolved theme, in order: later ones win. */
    public List<String> stylesheets() {
        return switch (this) {
            case LIGHT -> List.of(BASE_STYLESHEET);
            case DARK -> List.of(BASE_STYLESHEET, DARK_STYLESHEET);
            case AMOLED -> List.of(BASE_STYLESHEET, DARK_STYLESHEET, AMOLED_STYLESHEET);
            case SYSTEM -> throw new IllegalStateException("resolve() first: SYSTEM has no stylesheets of its own");
        };
    }
}
