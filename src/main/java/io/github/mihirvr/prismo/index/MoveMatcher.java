package io.github.mihirvr.prismo.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which files that disappeared are the same files showing up at a new path (moved or renamed), so
 * the row, and with it the user's favorite / hidden / video position, follows the file. Pure: no database,
 * no disk.
 *
 * <p>Windows has no usable file ID in Java ({@code BasicFileAttributes.fileKey()} returned null on JDK 25,
 * checked in #8), so files are matched by what a move or rename within a disk keeps: <b>size, modified
 * time and media type</b>. Within one such group:
 * <ol>
 *   <li>a gone file and a new file with the same name, the only ones with that name on each side, are a
 *       move (a folder renamed or moved: every file keeps its name);</li>
 *   <li>then, if exactly one gone and one new file are left, they are a rename;</li>
 *   <li>anything else is ambiguous: the gone files are removed, the new ones stay new (their user state
 *       starts empty). Copies of one photo would be the usual cause.</li>
 * </ol>
 */
final class MoveMatcher {

    /** A file found on disk that had no row before this run. */
    record Found(String path, long sizeBytes, long dateModified) {
    }

    /** {@code from} (a row whose file is gone) is now at {@code to}. */
    record Move(MediaStore.Row from, Found to) {
    }

    /** Moves to apply, and rows whose file is gone for good. */
    record Result(List<Move> moves, List<MediaStore.Row> removed) {
    }

    private record Key(long sizeBytes, long dateModified, MediaType type) {
    }

    private MoveMatcher() {
    }

    static Result match(Collection<MediaStore.Row> gone, Collection<Found> found) {
        Map<Key, List<Found>> foundByKey = new LinkedHashMap<>();
        for (Found f : found) {
            foundByKey.computeIfAbsent(new Key(f.sizeBytes(), f.dateModified(), typeOf(f.path())), _ -> new ArrayList<>())
                    .add(f);
        }
        Map<Key, List<MediaStore.Row>> goneByKey = new LinkedHashMap<>();
        for (MediaStore.Row row : gone) {
            goneByKey.computeIfAbsent(new Key(row.sizeBytes(), row.dateModified(), typeOf(row.path())),
                    _ -> new ArrayList<>()).add(row);
        }

        List<Move> moves = new ArrayList<>();
        List<MediaStore.Row> removed = new ArrayList<>();
        for (Map.Entry<Key, List<MediaStore.Row>> group : goneByKey.entrySet()) {
            List<MediaStore.Row> left = new ArrayList<>(group.getValue());
            List<Found> candidates = new ArrayList<>(foundByKey.getOrDefault(group.getKey(), List.of()));
            for (MediaStore.Row row : List.copyOf(left)) {
                String name = nameOf(row.path());
                List<MediaStore.Row> sameNameGone = left.stream().filter(r -> nameOf(r.path()).equals(name)).toList();
                List<Found> sameNameFound = candidates.stream().filter(f -> nameOf(f.path()).equals(name)).toList();
                if (sameNameGone.size() == 1 && sameNameFound.size() == 1) {
                    moves.add(new Move(row, sameNameFound.getFirst()));
                    left.remove(row);
                    candidates.remove(sameNameFound.getFirst());
                }
            }
            if (left.size() == 1 && candidates.size() == 1) {
                moves.add(new Move(left.getFirst(), candidates.getFirst()));
                left.clear();
            }
            removed.addAll(left);
        }
        return new Result(moves, removed);
    }

    private static String nameOf(String path) {
        return Path.of(path).getFileName().toString();
    }

    private static MediaType typeOf(String path) {
        return MediaType.fromExtension(MediaType.extensionOf(nameOf(path))).orElse(null);
    }
}
