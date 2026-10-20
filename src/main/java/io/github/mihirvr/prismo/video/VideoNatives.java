package io.github.mihirvr.prismo.video;

import java.io.IOException;
import java.nio.file.Path;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;

/**
 * FFmpeg's native files, through JavaCPP. They ship inside a per-OS jar; JavaCPP unpacks them into a cache
 * folder the first time they're needed (about 76 MB on Windows) and loads them from there afterwards.
 *
 * <p>No logger here on purpose: {@link io.github.mihirvr.prismo.Launcher} calls {@link #useCacheDir} before
 * logging is set up.
 */
public final class VideoNatives {

    /** JavaCPP's setting for where it unpacks native files (default {@code ~/.javacpp/cache}). */
    static final String CACHE_DIR_PROPERTY = "org.bytedeco.javacpp.cachedir";

    private static Path ffmpeg;
    private static boolean loaded;

    private VideoNatives() {
    }

    /**
     * Unpack into {@code dir} (Prismo's own cache) instead of the user's home folder. Call before any video
     * code runs; a value already set (tests set it in Gradle) is kept.
     */
    public static void useCacheDir(Path dir) {
        if (System.getProperty(CACHE_DIR_PROPERTY) == null) {
            System.setProperty(CACHE_DIR_PROPERTY, dir.toString());
        }
    }

    /** The bundled {@code ffmpeg} program, unpacked on first use. Thread-safe; slow only the first time. */
    public static synchronized Path ffmpegExecutable() throws IOException {
        if (ffmpeg == null) {
            try {
                ffmpeg = Path.of(Loader.load(org.bytedeco.ffmpeg.ffmpeg.class));
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                throw new IOException("FFmpeg isn't available on this computer: " + e, e);
            }
        }
        return ffmpeg;
    }

    /**
     * Loads FFmpeg's libraries into this process and turns its console messages down to errors only (by
     * default it prints a warning for every odd packet). Called before the first {@code FFmpegFrameGrabber}.
     */
    static synchronized void loadLibraries() {
        if (!loaded) {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
            loaded = true;
        }
    }
}
