package io.github.mihirvr.prismo.collections;

/**
 * How Prismo's Hide and the operating system's Hidden mark ({@code platform.OsHiddenMark}) go together. Either
 * way, a file with the Windows Hidden mark shows only on Collections › Hidden, and the right-click menu can set
 * or clear that mark ("Hide in File Explorer").
 */
public enum HiddenMode {
    /** Prismo's Hide only hides the item in Prismo; the file is left as it is. The default. */
    SEPARATE("separate", "Only in Prismo",
            "Hide keeps an item out of Prismo's views. The file itself is not changed."),
    /** Prismo's Hide also sets the Windows Hidden mark on the file, and Unhide clears it. */
    BOTH_WAYS("both", "In Prismo and File Explorer",
            "Hide also marks the file Hidden in File Explorer, and Unhide clears that mark.");

    public static final HiddenMode DEFAULT = SEPARATE;

    private final String settingValue;
    private final String label;
    private final String description;

    HiddenMode(String settingValue, String label, String description) {
        this.settingValue = settingValue;
        this.label = label;
        this.description = description;
    }

    /** The mode stored as {@code value} in the settings table; the default for null or anything unknown. */
    public static HiddenMode fromSetting(String value) {
        for (HiddenMode mode : values()) {
            if (mode.settingValue.equals(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    public String settingValue() {
        return settingValue;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
