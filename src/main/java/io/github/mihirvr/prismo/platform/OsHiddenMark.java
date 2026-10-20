package io.github.mihirvr.prismo.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Locale;

/**
 * The operating system's own "hidden" mark on a file: on Windows, the Hidden attribute (File Explorer, Properties,
 * "Hidden"; Explorer doesn't show such files unless "Show hidden items" is on).
 *
 * <p>Read for free while indexing: on Windows the attributes Java hands to a folder walk are DOS attributes
 * ({@link DosFileAttributes}), which carry the Hidden bit, so no extra disk access is needed. On Linux and macOS
 * "hidden" means a name starting with a dot, which Prismo can't change without renaming the file, so the mark is
 * treated as not supported there ({@link #SUPPORTED} false, never set).
 */
public final class OsHiddenMark {

    /** True on Windows: the mark is read, and Prismo can set and clear it. */
    public static final boolean SUPPORTED = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows");

    private OsHiddenMark() {
    }

    /** True if {@code attrs} (from a folder walk or {@link #read}) carry the Hidden attribute. */
    public static boolean isHidden(BasicFileAttributes attrs) {
        return SUPPORTED && attrs instanceof DosFileAttributes dos && dos.isHidden();
    }

    /**
     * The file's attributes in the form that carries the Hidden bit where there is one (DOS attributes on
     * Windows), else the basic ones. Doesn't follow a symbolic link.
     */
    public static BasicFileAttributes read(Path path) throws IOException {
        if (SUPPORTED) {
            return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Sets or clears the Hidden attribute. Only the attribute changes: not the file's contents or its modified
     * time. Disk I/O: not on the UI thread.
     *
     * @throws IOException if the file is gone, read-only to this user, or on a drive without the attribute
     */
    public static void set(Path path, boolean hidden) throws IOException {
        if (!SUPPORTED) {
            throw new IOException("This system has no Hidden mark Prismo can set");
        }
        DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
        if (view == null) {
            throw new IOException("This drive has no Hidden mark");
        }
        view.setHidden(hidden);
    }
}
