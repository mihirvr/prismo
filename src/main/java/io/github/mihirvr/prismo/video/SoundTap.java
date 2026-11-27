package io.github.mihirvr.prismo.video;

import java.nio.ShortBuffer;

/**
 * 2.0.0: the last few seconds of a file's decoded sound (left and right mixed to one), by the time in the file, for the
 * audio views' waveform and bars. The sound thread {@linkplain #add adds} each block as it decodes it, which is ahead
 * of what is heard by the sound device's buffer; the screen {@linkplain #copy copies} the samples that end at the
 * playback position, so the picture matches what is heard, not what was decoded.
 *
 * <p>Samples are numbered as they come in. A block that doesn't follow on from the last one in time (a seek, a new
 * sound track, a gap in the file) starts a new run: older samples are then never handed out for a time in the new run.
 */
public final class SoundTap {

    /** About 3 s at 44.1 kHz; the sound device's buffer is 200 ms, so the playing position is always inside. */
    static final int CAPACITY = 1 << 17;

    private final float[] ring = new float[CAPACITY];
    private int rate;
    private long written;      // samples added so far (all runs)
    private long runStart = -1; // number of the first sample of the current run
    private long runMicros;    // its time in the file

    /** Forgets everything (a new file, or the sound stopped). */
    public synchronized void clear() {
        runStart = -1;
    }

    /**
     * One decoded block: {@code count} 16-bit samples, left and right taking turns, the first at {@code micros} in the
     * file, {@code rate} sample pairs a second.
     */
    synchronized void add(ShortBuffer stereo, int count, long micros, int sampleRate) {
        int frames = count / 2;
        if (frames == 0 || sampleRate <= 0) {
            return;
        }
        long expected = runStart < 0 || sampleRate != rate ? -1
                : runStart + Math.round((micros - runMicros) * (double) sampleRate / 1_000_000);
        // More than 50 ms off where the last block ended: a new run.
        if (expected < 0 || Math.abs(expected - written) > sampleRate / 20) {
            rate = sampleRate;
            runStart = written;
            runMicros = micros;
        }
        int base = stereo.position();
        for (int i = 0; i < frames; i++) {
            float left = stereo.get(base + 2 * i);
            float right = stereo.get(base + 2 * i + 1);
            ring[(int) (written++ & (CAPACITY - 1))] = (left + right) / 65_536f; // -1 .. 1
        }
    }

    /** Samples a second of the current run, or 0 before any. */
    public synchronized int rate() {
        return runStart < 0 ? 0 : rate;
    }

    /**
     * Fills {@code out} with the samples that end at {@code micros} in the file. False (and {@code out} untouched) if
     * they aren't all here: not decoded yet, already overwritten, or from before the current run.
     */
    public synchronized boolean copy(long micros, float[] out) {
        if (runStart < 0) {
            return false;
        }
        long end = runStart + Math.round((micros - runMicros) * (double) rate / 1_000_000);
        long start = end - out.length;
        if (start < Math.max(runStart, written - CAPACITY) || end > written) {
            return false;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = ring[(int) ((start + i) & (CAPACITY - 1))];
        }
        return true;
    }
}
