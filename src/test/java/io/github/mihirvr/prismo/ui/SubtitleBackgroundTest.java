package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubtitleBackgroundTest {

    @Test
    void fiveChoicesHalfByDefault() {
        assertEquals(List.of("None", "25%", "50%", "75%", "100%"),
                Arrays.stream(SubtitleBackground.values()).map(SubtitleBackground::label).toList());
        assertEquals(SubtitleBackground.HALF, SubtitleBackground.DEFAULT);
    }

    @Test
    void savedValuesAndStyleClassesRoundTrip() {
        for (SubtitleBackground background : SubtitleBackground.values()) {
            assertEquals(background, SubtitleBackground.fromSetting(background.settingValue()));
        }
        assertEquals("none", SubtitleBackground.NONE.settingValue());
        assertEquals("75", SubtitleBackground.THREE_QUARTERS.settingValue());
        assertEquals("subtitle-100", SubtitleBackground.FULL.styleClass());
        assertEquals("subtitle-none", SubtitleBackground.NONE.styleClass());
        assertEquals(SubtitleBackground.QUARTER, SubtitleBackground.fromSetting(" 25% "), "a % and spaces are fine");
        assertEquals(SubtitleBackground.NONE, SubtitleBackground.fromSetting("NONE"));
    }

    @Test
    void anythingElseIsTheDefault() {
        assertEquals(SubtitleBackground.HALF, SubtitleBackground.fromSetting(null));
        assertEquals(SubtitleBackground.HALF, SubtitleBackground.fromSetting(""));
        assertEquals(SubtitleBackground.HALF, SubtitleBackground.fromSetting("60"));
    }
}
