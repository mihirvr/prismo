package io.github.mihirvr.prismo.testing;

import java.io.File;
import java.nio.file.Path;

/**
 * Made-up absolute paths that mean the same on every OS: {@code D:\Photos\...} on Windows, {@code /media/Photos/...}
 * elsewhere. A Windows path written as one string ({@code "D:\\Photos\\Trip"}) is a single odd file name on Linux,
 * so tests that only need "some folder" build it from parts here. Nothing is created on disk.
 */
public final class TestPaths {

    public static final boolean WINDOWS = File.separatorChar == '\\';

    private TestPaths() {
    }

    /** The top of the made-up drive: {@code D:\} or {@code /media}. */
    public static Path root() {
        return WINDOWS ? Path.of("D:\\") : Path.of("/media");
    }

    /** {@code of("Photos", "Trip")}: {@code D:\Photos\Trip} or {@code /media/Photos/Trip}. */
    public static Path of(String first, String... more) {
        return root().resolve(Path.of(first, more));
    }
}
