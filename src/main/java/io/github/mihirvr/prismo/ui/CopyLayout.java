package io.github.mihirvr.prismo.ui;

import java.util.Locale;

/**
 * How Collections › Duplicates lays out the copies of each file on a date sort in the Timeline view (1.4.2). Whatever
 * the choice, a file's copies are never split: the whole file goes under the date of its first copy in the Sort's
 * order. Other sorts (size, name) always show one header per file.
 */
public enum CopyLayout {
    /** A date header, and under it a smaller header per file. */
    DATES_AND_FILES("Dates and files"),
    /** Date headers only; each file's copies side by side under one date. */
    DATES("Dates"),
    /** One header per file, no dates. */
    FILES("Files");

    public static final CopyLayout DEFAULT = DATES_AND_FILES;

    private final String label;

    CopyLayout(String label) {
        this.label = label;
    }

    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The saved value back; null, empty or unknown: {@link #DEFAULT}. */
    public static CopyLayout fromSetting(String value) {
        if (value != null) {
            for (CopyLayout layout : values()) {
                if (layout.settingValue().equals(value.strip().toLowerCase(Locale.ROOT))) {
                    return layout;
                }
            }
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return label;
    }
}
