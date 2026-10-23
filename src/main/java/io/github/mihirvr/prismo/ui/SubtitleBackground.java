package io.github.mihirvr.prismo.ui;

import java.util.Locale;

/**
 * The box behind the subtitle line (Settings › Video, and the video's Subtitles menu › Background): none, or black at
 * 25 / 50 / 75 / 100 % opacity. With none the text gets a dark outline instead, so it stays readable on a bright
 * picture. Each choice is a style class on the line ({@link #styleClass}); the colours are in the stylesheet. Saved as
 * {@link #settingValue} under {@code video.subtitle_background}.
 */
public enum SubtitleBackground {
    NONE("None", "No box: the text gets a dark outline, so it stays readable on bright scenes"),
    QUARTER("25%", "A light see-through box"),
    HALF("50%", "A half see-through box (the usual)"),
    THREE_QUARTERS("75%", "A dark box, the picture still shows a little"),
    FULL("100%", "A solid black box");

    public static final SubtitleBackground DEFAULT = HALF;

    private final String label;
    private final String description;

    SubtitleBackground(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** "none", "25", "50", "75", "100". */
    public String settingValue() {
        return this == NONE ? "none" : label.substring(0, label.length() - 1);
    }

    /** The line's style class for this choice: {@code subtitle-none}, {@code subtitle-25} … */
    public String styleClass() {
        return "subtitle-" + settingValue();
    }

    /** The saved value back; null, empty or unknown → {@link #DEFAULT}. */
    public static SubtitleBackground fromSetting(String value) {
        if (value != null) {
            String wanted = value.strip().toLowerCase(Locale.ROOT).replace("%", "");
            for (SubtitleBackground background : values()) {
                if (background.settingValue().equals(wanted)) {
                    return background;
                }
            }
        }
        return DEFAULT;
    }
}
