package io.github.mihirvr.prismo.collections;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * "Delete permanently" from Prismo's Trash: the one place Prismo removes a file from disk (PROJECT.md "Trash").
 *
 * <p>Where the operating system has a recycle bin that Java can use ({@code Desktop.moveToTrash}: Windows;
 * checked on this machine), the file goes there, so a wrong click can still be undone in Explorer. Elsewhere
 * (Linux desktops, where Java doesn't offer it) the file is deleted. The confirmation dialog says which one
 * will happen ({@link #usesRecycleBin()}).
 */
public final class FileRemover {

    /** What happened to the file. */
    public enum Outcome { RECYCLE_BIN, DELETED, ALREADY_GONE }

    /** Moves a file to the recycle bin; false if it refused. Null = no recycle bin on this computer. */
    private final Predicate<File> recycleBin;

    FileRemover(Predicate<File> recycleBin) {
        this.recycleBin = recycleBin;
    }

    /** The recycle bin if Java can use it here, otherwise plain deletes. */
    public static FileRemover forThisComputer() {
        boolean supported = Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        return new FileRemover(supported ? file -> Desktop.getDesktop().moveToTrash(file) : null);
    }

    public boolean usesRecycleBin() {
        return recycleBin != null;
    }

    /**
     * Removes {@code file}. Blocking I/O: not on the UI thread.
     *
     * @throws IOException if it couldn't be removed (in use, no permission); the file is then still there
     */
    public Outcome remove(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Outcome.ALREADY_GONE;
        }
        if (recycleBin == null) {
            Files.delete(file);
            return Outcome.DELETED;
        }
        boolean moved;
        try {
            moved = recycleBin.test(file.toFile());
        } catch (RuntimeException e) { // e.g. SecurityException, or Desktop failing for this path
            throw new IOException("the Recycle Bin refused it: " + e.getMessage(), e);
        }
        if (!moved) {
            throw new IOException("the Recycle Bin refused it (in use, or no permission?)");
        }
        return Outcome.RECYCLE_BIN;
    }
}
