package io.github.mihirvr.prismo.ui;

import java.util.Locale;

/**
 * The Grid tab's View (1.1.0): how the items are laid out. The Sort still decides the order; the View decides
 * whether there are headers. Saved as {@link #settingValue} under {@code grid.view}.
 */
public enum GridView {
    /** Headers by the sort's date (date sorts), as the Timeline tab did before 1.1.0. */
    TIMELINE("Timeline"),
    /** A header per folder ({@link FolderSections}), arranged by {@link FolderArrange}. */
    FOLDERS("Folders"),
    /** One flat grid, never any headers. */
    ALL_FILES("All files");

    public static final GridView DEFAULT = TIMELINE;

    private final String label;

    GridView(String label) {
        this.label = label;
    }

    /** The headers the grid shows for these options (the Folders view has folder headers instead). */
    public Headers headers(ViewOptions options) {
        return this == TIMELINE ? options.headers() : Headers.NONE;
    }

    /** True when the grid is in date order (Go to date works): a date sort, not split into folders. */
    public boolean dateOrdered(ViewOptions options) {
        return this != FOLDERS && options.grouped();
    }

    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The saved value back; null, empty or unknown: {@link #DEFAULT}. */
    public static GridView fromSetting(String value) {
        if (value != null) {
            for (GridView view : values()) {
                if (view.settingValue().equals(value.strip().toLowerCase(Locale.ROOT))) {
                    return view;
                }
            }
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return label; // what the View menu shows
    }
}
