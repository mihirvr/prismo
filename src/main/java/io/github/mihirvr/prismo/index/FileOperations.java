package io.github.mihirvr.prismo.index;

import io.github.mihirvr.prismo.db.Database;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rename and Move (PROJECT.md "File Operations"): the only changes to a file besides Delete permanently, and only
 * when the user asks. The file is renamed or moved on disk, then its row follows (same id), so favorites, hidden,
 * watch progress and the cached thumbnail stay with it. The file watcher sees the change too; whichever comes first,
 * the result is the same row with the new path.
 *
 * <p>Blocking (disk and the database writer): call off the UI thread.
 */
public final class FileOperations {

    private static final Logger log = LoggerFactory.getLogger(FileOperations.class);
    /** How long a move keeps trying while the file is in use (see {@link #moveRetryingWhileInUse}). */
    static final long IN_USE_WAIT_MILLIS = 3000;
    /** Characters Windows doesn't allow in a file name (and {@code /} isn't allowed anywhere). */
    private static final String FORBIDDEN = "\\/:*?\"<>|";
    private static final List<String> RESERVED = List.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8",
            "LPT9");

    /** What to do when the target folder already has a file with the same name. */
    public enum Conflict {
        /** Leave that file where it is. */
        SKIP,
        /** Move it under a free name: "photo (2).jpg". */
        KEEP_BOTH
    }

    /**
     * @param moved     moved (or renamed) files
     * @param skipped   left because of a name conflict
     * @param failed    couldn't be moved (the first reason is in {@code problem})
     * @param leftLibrary moved to a folder outside every library folder, so they're no longer in Prismo
     * @param newPaths  where each moved file is now, by media id
     */
    public record Outcome(int moved, int skipped, int failed, int leftLibrary, Optional<String> problem,
            Map<Long, Path> newPaths) {
    }

    private final Database database;
    private final Predicate<Path> inLibrary;

    /** @param inLibrary true if a folder is inside a library folder that is on */
    public FileOperations(Database database, Predicate<Path> inLibrary) {
        this.database = database;
        this.inLibrary = inLibrary;
    }

    /**
     * Why {@code name} can't be a file name here, or empty if it can. The rules are Windows' (the strictest of the
     * systems Prismo runs on): no {@code \ / : * ? " < > |}, no control characters, no name ending in a dot or a
     * space, no reserved device names (CON, NUL, COM1…).
     */
    public static Optional<String> nameProblem(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
            return Optional.of("Type a name.");
        }
        for (char c : trimmed.toCharArray()) {
            if (FORBIDDEN.indexOf(c) >= 0 || c < 32) {
                return Optional.of("A name can't contain " + (c < 32 ? "control characters" : "“" + c + "”")
                        + ". Not allowed: \\ / : * ? \" < > |");
            }
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            return Optional.of("A name can't end with a dot or a space.");
        }
        String base = trimmed.contains(".") ? trimmed.substring(0, trimmed.indexOf('.')) : trimmed;
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            return Optional.of("“" + base + "” is reserved by Windows.");
        }
        if (trimmed.length() > 240) {
            return Optional.of("That name is too long.");
        }
        return Optional.empty();
    }

    /** {@code fileName}, or "name (2).ext", "name (3).ext"… the first that {@code taken} says is free. */
    public static String freeName(String fileName, Predicate<String> taken) {
        if (!taken.test(fileName)) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = base + " (" + n + ")" + extension;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Renames media {@code id}'s file {@code file} to {@code newName} in the same folder (a change of case only
     * works too). Returns the new path.
     *
     * @throws IOException with a plain-words message: the name is taken, the file is gone or in use
     */
    public Path rename(long id, Path file, String newName) throws IOException, SQLException {
        Optional<String> problem = nameProblem(newName);
        if (problem.isPresent()) {
            throw new IOException(problem.get());
        }
        Path target = file.resolveSibling(newName.strip());
        boolean caseOnly = target.toString().equalsIgnoreCase(file.toString());
        if (target.equals(file)) {
            return file;
        }
        if (!caseOnly && Files.exists(target)) {
            throw new IOException("There's already a file called “" + target.getFileName() + "” there.");
        }
        moveOnDisk(file, target);
        followRow(id, target, true);
        log.info("Renamed {} to {}", file, target.getFileName());
        return target;
    }

    /**
     * Moves the files ({@code ids} and {@code files} in the same order) into {@code folder}. A file already
     * there is skipped. Each file is done on its own: one failure doesn't stop the rest.
     */
    public Outcome move(List<Long> ids, List<Path> files, Path folder, Conflict conflict) throws SQLException {
        int moved = 0;
        int skipped = 0;
        int failed = 0;
        int left = 0;
        String problem = null;
        Map<Long, Path> newPaths = new LinkedHashMap<>();
        boolean staysInLibrary = inLibrary.test(folder);
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            if (folder.equals(file.getParent())) {
                skipped++; // already there
                continue;
            }
            String name = file.getFileName().toString();
            if (Files.exists(folder.resolve(name))) {
                if (conflict == Conflict.SKIP) {
                    skipped++;
                    continue;
                }
                name = freeName(name, candidate -> Files.exists(folder.resolve(candidate)));
            }
            Path target = folder.resolve(name);
            try {
                moveOnDisk(file, target);
            } catch (IOException e) {
                failed++;
                problem = problem != null ? problem : e.getMessage();
                continue;
            }
            followRow(ids.get(i), target, staysInLibrary);
            newPaths.put(ids.get(i), target);
            moved++;
            if (!staysInLibrary) {
                left++;
            }
        }
        log.info("Moved {} files to {} ({} skipped, {} failed)", moved, folder, skipped, failed);
        return new Outcome(moved, skipped, failed, left, Optional.ofNullable(problem), Map.copyOf(newPaths));
    }

    /** Files that would clash in {@code folder} (same name already there), so the user can be asked first. */
    public static int conflicts(List<Path> files, Path folder) {
        int clashes = 0;
        for (Path file : files) {
            if (!folder.equals(file.getParent()) && Files.exists(folder.resolve(file.getFileName()))) {
                clashes++;
            }
        }
        return clashes;
    }

    private static void moveOnDisk(Path file, Path target) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("“" + file.getFileName() + "” isn't there any more.");
        }
        try {
            moveRetryingWhileInUse(file, target);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("There's already a file called “" + target.getFileName() + "” there.", e);
        } catch (IOException e) {
            throw new IOException("Couldn't move “" + file.getFileName() + "”: it may be open in another"
                    + " program, or the folder is read-only (" + e.getClass().getSimpleName() + ").", e);
        }
    }

    /**
     * {@code Files.move} (same drive: a rename; another drive: a copy with its modified time, then a delete),
     * tried again for up to {@link #IN_USE_WAIT_MILLIS} while Windows says the file is in use: a video the viewer
     * just stopped is let go by the player's threads a moment later, and a virus scanner or the thumbnail maker
     * can hold a file briefly.
     */
    private static void moveRetryingWhileInUse(Path file, Path target) throws IOException {
        long giveUp = System.nanoTime() + IN_USE_WAIT_MILLIS * 1_000_000;
        while (true) {
            try {
                Files.move(file, target);
                return;
            } catch (FileAlreadyExistsException | NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                if (System.nanoTime() > giveUp) {
                    throw e;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * The row follows the file: new path, folder, name and extension, same id. A row already at the new path
     * (the watcher was quicker) is dropped first. A file moved out of the library loses its row.
     */
    private void followRow(long id, Path target, boolean staysInLibrary) throws SQLException {
        try {
            database.write(c -> {
                if (!staysInLibrary) {
                    delete(c, "DELETE FROM media WHERE id = ?", id);
                    return null;
                }
                try (PreparedStatement other = c.prepareStatement("DELETE FROM media WHERE path = ? AND id <> ?");
                        PreparedStatement update = c.prepareStatement("UPDATE media SET path = ?, folder = ?,"
                                + " file_name = ?, extension = ? WHERE id = ?")) {
                    other.setString(1, target.toString());
                    other.setLong(2, id);
                    other.executeUpdate();
                    String fileName = target.getFileName().toString();
                    update.setString(1, target.toString());
                    update.setString(2, target.getParent().toString());
                    update.setString(3, fileName);
                    update.setString(4, MediaType.extensionOf(fileName));
                    update.setLong(5, id);
                    update.executeUpdate();
                }
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while updating the library", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof SQLException sql ? sql : new SQLException(e.getCause());
        }
    }

    private static void delete(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }
}
