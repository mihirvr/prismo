package io.github.mihirvr.prismo.video;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One sound or subtitle stream inside a video file, as the track menus show it.
 *
 * @param stream     FFmpeg's number for the stream among all the file's streams (picture, sound, subtitles)
 * @param language   the file's language tag ("eng", "hin", "und" = not given), or empty
 * @param title      the file's name for the track ("Director's commentary"), or empty
 * @param isDefault  the file marks it as the one to play by default
 * @param text       subtitles only: true for text subtitles (SRT, ASS, MP4 text) Prismo can show; false for
 *                   picture subtitles (Blu-ray / DVD), which it can't
 */
public record Track(int stream, String language, String title, boolean isDefault, boolean text) {

    /** ISO 639-2 three-letter codes ("eng") and two-letter codes ("en") → the language's English name. */
    private static final Map<String, String> LANGUAGES = new HashMap<>();

    static {
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.of(code);
            String name = locale.getDisplayLanguage(Locale.ENGLISH);
            LANGUAGES.put(code, name);
            try {
                LANGUAGES.putIfAbsent(locale.getISO3Language(), name);
            } catch (java.util.MissingResourceException e) {
                // no three-letter code for this one
            }
        }
        // ISO 639-2 has a second ("bibliographic") code for some languages; files use either.
        for (String[] pair : new String[][] {{"ger", "deu"}, {"fre", "fra"}, {"chi", "zho"}, {"dut", "nld"},
                {"cze", "ces"}, {"gre", "ell"}, {"per", "fas"}, {"rum", "ron"}, {"slo", "slk"}, {"alb", "sqi"},
                {"arm", "hye"}, {"baq", "eus"}, {"bur", "mya"}, {"geo", "kat"}, {"ice", "isl"}, {"mac", "mkd"},
                {"mao", "mri"}, {"may", "msa"}, {"tib", "bod"}, {"wel", "cym"}}) {
            String name = LANGUAGES.get(pair[1]);
            if (name != null) {
                LANGUAGES.putIfAbsent(pair[0], name);
            }
        }
    }

    /**
     * What the menu shows: the title if the file has one, else the language's name, else "Track {@code number}"
     * (1-based among tracks of its kind). The language is added after a title when both differ ("Commentary ·
     * English").
     */
    public String label(int number) {
        String languageName = languageName();
        if (!title.isBlank()) {
            return languageName == null || title.equalsIgnoreCase(languageName) ? title.strip()
                    : title.strip() + " · " + languageName;
        }
        return languageName != null ? languageName : "Track " + number;
    }

    /** The language's English name, or null if the file gave none ("und" = undetermined) or an unknown code. */
    String languageName() {
        String code = language.strip().toLowerCase(Locale.ROOT);
        if (code.isEmpty() || code.equals("und") || code.equals("zxx") || code.equals("mis")) {
            return null;
        }
        return LANGUAGES.get(code);
    }
}
