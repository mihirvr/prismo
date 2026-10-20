package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrackTest {

    private static Track track(String language, String title) {
        return new Track(1, language, title, false, true);
    }

    @Test
    void namesTheLanguageFromItsCode() {
        assertEquals("English", track("eng", "").label(1));
        assertEquals("Hindi", track("hin", "").label(1));
        assertEquals("Japanese", track("ja", "").label(1), "two-letter codes too");
        assertEquals("German", track("ger", "").label(1), "the second (bibliographic) code too");
        assertEquals("French", track("FRE", "").label(1), "any case");
    }

    @Test
    void aTitleComesFirstWithTheLanguageAfterIt() {
        assertEquals("Commentary · Hindi", track("hin", "Commentary").label(1));
        assertEquals("English", track("eng", "English").label(1), "not \"English · English\"");
        assertEquals("Director's cut", track("", " Director's cut ").label(1));
    }

    @Test
    void withNothingToGoOnItIsNumbered() {
        assertEquals("Track 3", track("und", "").label(3), "und = not given");
        assertEquals("Track 2", track("", "").label(2));
        assertEquals("Track 1", track("xyz", "").label(1), "an unknown code");
    }
}
