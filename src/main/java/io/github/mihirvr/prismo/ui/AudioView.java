package io.github.mihirvr.prismo.ui;

import java.util.Locale;

/**
 * 2.0.0: what the viewer shows while an audio file plays (the Show menu in its bar): the album art (a music note when
 * the file has none), or one of three pictures of the sound itself. Saved as {@link #settingValue} under
 * {@code audio.view}.
 */
public enum AudioView {
    ALBUM_ART("Album art", "The song's cover, or a music note when it has none"),
    WAVEFORM("Waveform", "The sound as a moving line"),
    BARS("Bars", "Bass to treble as bars that jump with the sound"),
    CIRCLE("Circle", "Bars around a ring, with the cover in the middle");

    public static final AudioView DEFAULT = ALBUM_ART;

    private final String label;
    private final String description;

    AudioView(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** "album_art", "waveform", "bars", "circle". */
    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The saved value back; null, empty or unknown → {@link #DEFAULT}. */
    public static AudioView fromSetting(String value) {
        if (value != null) {
            for (AudioView view : values()) {
                if (view.settingValue().equals(value.strip().toLowerCase(Locale.ROOT))) {
                    return view;
                }
            }
        }
        return DEFAULT;
    }
}
