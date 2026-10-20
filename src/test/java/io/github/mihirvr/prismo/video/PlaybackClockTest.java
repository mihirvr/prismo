package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackClockTest {

    private long nanos = 1_000_000_000L;
    private long frames = 5_000; // the device has already played some other sound: only differences count
    private final PlaybackClock clock = new PlaybackClock(() -> nanos);

    @Test
    void wallClockRunsOnlyWhilePlaying() {
        assertEquals(0, clock.now());
        nanos += 1_000_000_000L;
        assertEquals(0, clock.now(), "not started yet");

        clock.play();
        nanos += 1_000_000_000L;
        assertEquals(1_000_000, clock.now());

        clock.pause();
        nanos += 5_000_000_000L;
        assertEquals(1_000_000, clock.now(), "paused: stands still");

        clock.play();
        nanos += 500_000_000L;
        assertEquals(1_500_000, clock.now());
    }

    @Test
    void atASpeedMediaTimeRunsThatMuchFasterOnTheWallClock() {
        clock.play();
        nanos += 1_000_000_000L;
        clock.setSpeed(2);
        assertEquals(1_000_000, clock.now(), "the time so far is kept");
        nanos += 1_000_000_000L;
        assertEquals(3_000_000, clock.now(), "one real second = two media seconds");
        clock.setSpeed(0.5);
        nanos += 1_000_000_000L;
        assertEquals(3_500_000, clock.now());
        assertEquals(0.5, clock.speed());
    }

    @Test
    void atASpeedEachPlayedSampleCountsForThatMuchMediaTime() {
        clock.useSound(() -> frames, 48_000);
        clock.setSpeed(1.5);
        clock.play();
        clock.soundStarts(0, 0);
        frames += 48_000; // one second of (sped-up) sound played
        assertEquals(1_500_000, clock.now());
    }

    @Test
    void seekJumpsAndRunsOnFromThere() {
        clock.play();
        nanos += 2_000_000_000L;
        clock.seek(10_000_000);
        assertEquals(10_000_000, clock.now());
        nanos += 100_000_000L;
        assertEquals(10_100_000, clock.now());
    }

    @Test
    void soundCountsPlayedSamples() {
        clock.useSound(() -> frames, 48_000);
        clock.play();
        frames += 4_800;
        nanos += 7_000_000_000L; // the wall clock doesn't matter in sound mode
        assertEquals(0, clock.now(), "no sound written yet: waits");

        clock.soundStarts(0, 0);
        frames += 48_000;
        assertEquals(1_000_000, clock.now());
        assertTrue(clock.followsSound());
    }

    @Test
    void afterASeekSoundTimeWaitsForTheNewSamples() {
        clock.useSound(() -> frames, 48_000);
        clock.play();
        clock.soundStarts(0, 0);
        frames += 48_000;

        clock.seek(10_000_000);
        assertEquals(new PlaybackClock.Seek(1, 10_000_000), clock.lastSeek());
        frames += 2_400; // old samples still draining
        assertEquals(10_000_000, clock.now());

        clock.soundStarts(10_020_000, 1); // the first new samples start just after the target
        frames += 4_800;
        assertEquals(10_120_000, clock.now());
    }

    @Test
    void soundStartingForAnOlderSeekIsIgnored() {
        clock.useSound(() -> frames, 48_000);
        clock.pause();
        clock.seek(1_200_000); // seek 1, while the sound thread is still busy with the start
        clock.soundStarts(0, 0);
        assertEquals(1_200_000, clock.now(), "not pulled back to 0");

        clock.soundStarts(1_200_000, 1);
        clock.play();
        frames += 4_800;
        assertEquals(1_300_000, clock.now());
    }

    @Test
    void samplesPlayedWhilePausedDoNotCount() {
        clock.useSound(() -> frames, 48_000);
        clock.play();
        clock.soundStarts(0, 0);
        frames += 24_000;
        clock.pause();
        frames += 480; // the device stops a moment after the pause
        assertEquals(500_000, clock.now());

        clock.play();
        frames += 48_000;
        assertEquals(1_500_000, clock.now());
    }

    @Test
    void whenTheSoundEndsTheWallClockCarriesOn() {
        clock.useSound(() -> frames, 48_000);
        clock.play();
        clock.soundStarts(0, 0);
        frames += 96_000;

        clock.soundEnded();
        assertFalse(clock.followsSound());
        assertEquals(2_000_000, clock.now());
        nanos += 250_000_000L;
        frames += 999_999; // ignored now
        assertEquals(2_250_000, clock.now());
    }
}
