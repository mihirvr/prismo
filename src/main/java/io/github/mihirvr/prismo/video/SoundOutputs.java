package io.github.mihirvr.prismo.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which sound device videos play on (1.3.0): the system default, or one device the user picked (laptop speakers,
 * headphones, a TV over HDMI, Bluetooth). The pick lasts only while Prismo runs, for every video and both viewers; it
 * is never saved, so each start begins on the system default again (the device the operating system uses, followed
 * when that changes).
 *
 * <p>Java Sound calls each device a {@link Mixer}. Only mixers that can play ({@link SourceDataLine}) are listed: on
 * Windows the DirectSound devices by their Windows names, without "Primary Sound Driver" (that one IS the system
 * default) and without the "Port" mixers (volume controls, they can't play). On Linux, ALSA's "default" is left out the
 * same way. A picked device that has gone (unplugged) falls back to the system default when the next sound opens.
 */
public final class SoundOutputs {

    private static final Logger log = LoggerFactory.getLogger(SoundOutputs.class);

    /** The device picked this run (its mixer name), or null for the system default. Never saved. */
    private static volatile String chosen;

    private SoundOutputs() {
    }

    /** The devices that can play, by name, in the order the system lists them; the system default is not in it. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Line.Info playing = new Line.Info(SourceDataLine.class);
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (AudioSystem.getMixer(info).isLineSupported(playing)) {
                    names.add(info.getName());
                }
            }
        } catch (RuntimeException e) { // a broken sound system must not break the viewer
            log.info("Could not list the sound devices: {}", e.toString());
            return List.of();
        }
        return choosable(names);
    }

    /** {@code names} without the system default's own entries and without repeats. */
    static List<String> choosable(List<String> names) {
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT).strip();
            boolean systemDefault = lower.equals("primary sound driver") || lower.startsWith("default [")
                    || lower.equals("default");
            if (!systemDefault && !lower.startsWith("port ") && !name.isBlank() && !kept.contains(name)) {
                kept.add(name);
            }
        }
        return kept;
    }

    /** The device picked this run, or null for the system default. */
    public static String chosen() {
        return chosen;
    }

    /** Picks a device by its name ({@link #list}) for the rest of this run; null = the system default. */
    public static void choose(String name) {
        chosen = name;
    }

    /**
     * The picked device's name if it is still there, else null (the system default is used). For the menus' ticks.
     */
    public static String shown(List<String> available) {
        String now = chosen;
        return now != null && available.contains(now) ? now : null;
    }

    /** A line for {@code format} on the picked device, or on the system default if none was picked or it's gone. */
    static SourceDataLine open(AudioFormat format) throws LineUnavailableException {
        String wanted = chosen;
        if (wanted != null) {
            DataLine.Info line = new DataLine.Info(SourceDataLine.class, format);
            try {
                for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                    if (info.getName().equals(wanted)) {
                        Mixer mixer = AudioSystem.getMixer(info);
                        if (mixer.isLineSupported(line)) {
                            return (SourceDataLine) mixer.getLine(line);
                        }
                    }
                }
                log.info("Sound device {} not found; playing on the system default", wanted);
            } catch (LineUnavailableException | RuntimeException e) {
                log.info("Sound device {} could not open ({}); playing on the system default", wanted, e.toString());
            }
        }
        return AudioSystem.getSourceDataLine(format);
    }
}
