package io.github.mihirvr.prismo.ui;

import java.util.Locale;

/**
 * How date-sorted grids are split under headers (PROJECT.md "Timeline": Smart by default, or explicit Day / Week /
 * Month / Year). Saved as {@link #settingValue} under {@code timeline.grouping}.
 */
public enum Grouping {
    SMART("Smart", "Today, Yesterday, then months"),
    DAY("Day", "One header per day"),
    WEEK("Week", "One header per week, Monday first"),
    MONTH("Month", "One header per month"),
    YEAR("Year", "One header per year");

    public static final Grouping DEFAULT = SMART;

    private final String label;
    private final String description;

    Grouping(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The saved value back; null, empty or unknown → {@link #DEFAULT}. */
    public static Grouping fromSetting(String value) {
        if (value != null) {
            for (Grouping grouping : values()) {
                if (grouping.settingValue().equals(value.strip().toLowerCase(Locale.ROOT))) {
                    return grouping;
                }
            }
        }
        return DEFAULT;
    }
}
