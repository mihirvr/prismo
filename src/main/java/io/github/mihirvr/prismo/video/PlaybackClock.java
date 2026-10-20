package io.github.mihirvr.prismo.video;

import java.util.function.LongSupplier;

/**
 * The player's "now": which moment of the video (microseconds from its start) should be on screen.
 *
 * <p><b>The sound is the master clock</b> when there is sound. The sound card plays samples at its own fixed
 * rate, so "how many samples has it played" is the most accurate time there is; pictures wait for it. Sound
 * can't wait for pictures without gaps you'd hear, but a picture shown a frame late is hard to see.
 * <ul>
 *   <li><b>Sound mode:</b> now = the time of the first sample written after the last seek + (samples played
 *       since then ÷ sample rate). Between a seek and the first new samples, time stands still at the seek
 *       target ("waiting"), so pictures don't run ahead.</li>
 *   <li><b>Wall mode</b> (no sound, no sound device, or the sound has ended): now = time at the last anchor +
 *       time passed on {@code System.nanoTime}.</li>
 * </ul>
 * Paused, time stands still in both modes.
 *
 * <p>Pure logic: the wall clock and the "samples played" counter are passed in, so tests can drive both.
 * Synchronized, because the sound thread, the picture thread and the UI thread all use it.
 */
final class PlaybackClock {

    /** The last seek: its number (0 = none yet, the start) and where it went. */
    record Seek(long number, long micros) {
    }

    private final LongSupplier nanoTime;
    private Seek lastSeek = new Seek(0, 0);

    private boolean running;
    /** Media time at the anchor, in microseconds. */
    private long anchorMicros;
    /** Wall mode: {@code nanoTime} at the anchor. */
    private long anchorNanos;

    private LongSupplier framesPlayed;
    private int sampleRate;
    /** Sound mode: {@code framesPlayed} at the anchor. */
    private long anchorFrames;
    /** Sound mode, after a seek: no new samples written yet, so time stands at the seek target. */
    private boolean waiting;

    PlaybackClock(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /** Follows the sound from now on: {@code framesPlayed} = sample frames the device has played so far. */
    synchronized void useSound(LongSupplier framesPlayed, int sampleRate) {
        anchorMicros = now();
        this.framesPlayed = framesPlayed;
        this.sampleRate = sampleRate;
        waiting = true;
    }

    /**
     * Sound mode: the first samples after seek number {@code seek} (or the start), of time {@code micros}, are
     * being written. Ignored if another seek came since: the sound thread may still be busy with the old one.
     */
    synchronized void soundStarts(long micros, long seek) {
        if (framesPlayed == null || seek != lastSeek.number()) {
            return;
        }
        anchorMicros = micros;
        anchorFrames = framesPlayed.getAsLong();
        waiting = false;
    }

    /** The sound has run out (it may end before the picture does): carry on in wall mode from here. */
    synchronized void soundEnded() {
        if (framesPlayed == null) {
            return;
        }
        anchorMicros = now();
        anchorNanos = nanoTime.getAsLong();
        framesPlayed = null;
        waiting = false;
    }

    synchronized boolean followsSound() {
        return framesPlayed != null;
    }

    synchronized void play() {
        if (running) {
            return;
        }
        // Re-anchor: whatever the device or the wall clock did while paused doesn't count.
        anchorNanos = nanoTime.getAsLong();
        if (framesPlayed != null) {
            anchorFrames = framesPlayed.getAsLong();
        }
        running = true;
    }

    synchronized void pause() {
        if (running) {
            anchorMicros = now();
            running = false;
        }
    }

    synchronized boolean isRunning() {
        return running;
    }

    /** Jumps to {@code micros}. In sound mode, time then waits for {@link #soundStarts}. */
    synchronized void seek(long micros) {
        lastSeek = new Seek(lastSeek.number() + 1, micros);
        anchorMicros = micros;
        anchorNanos = nanoTime.getAsLong();
        waiting = framesPlayed != null;
    }

    synchronized Seek lastSeek() {
        return lastSeek;
    }

    /** The media time to show now, in microseconds. */
    synchronized long now() {
        if (!running || waiting) {
            return anchorMicros;
        }
        if (framesPlayed != null) {
            return anchorMicros + (framesPlayed.getAsLong() - anchorFrames) * 1_000_000L / sampleRate;
        }
        return anchorMicros + (nanoTime.getAsLong() - anchorNanos) / 1_000L;
    }
}
