package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Which sound devices can be picked (1.3.0), and that the pick lives only in memory. */
class SoundOutputsTest {

    @AfterEach
    void backToTheDefault() {
        SoundOutputs.choose(null);
    }

    @Test
    void theSystemDefaultsOwnEntriesAndVolumeControlsAreLeftOut() {
        assertEquals(List.of("Speakers (Realtek(R) Audio)", "Headphones (Bluetooth)"), SoundOutputs.choosable(List.of(
                "Primary Sound Driver", "Speakers (Realtek(R) Audio)", "Headphones (Bluetooth)",
                "Port Speakers (Realtek(R) Audio)", "Speakers (Realtek(R) Audio)", " ")), "Windows names");
        assertEquals(List.of("PCH [plughw:0,0]", "HDMI [plughw:0,3]"), SoundOutputs.choosable(List.of(
                "default [default]", "PCH [plughw:0,0]", "HDMI [plughw:0,3]", "Port PCH [hw:0]")), "Linux names");
    }

    @Test
    void thePickStartsAsTheDefaultAndAGoneDeviceShowsAsTheDefault() {
        assertNull(SoundOutputs.chosen(), "a fresh start: the system default");
        SoundOutputs.choose("Headphones (Bluetooth)");
        assertEquals("Headphones (Bluetooth)", SoundOutputs.shown(List.of("Speakers", "Headphones (Bluetooth)")));
        assertNull(SoundOutputs.shown(List.of("Speakers")), "unplugged: shown as the system default");
        assertEquals("Headphones (Bluetooth)", SoundOutputs.chosen(), "kept, so it is used again when plugged back in");
        SoundOutputs.choose(null);
        assertNull(SoundOutputs.shown(List.of("Speakers", "Headphones (Bluetooth)")));
    }
}
