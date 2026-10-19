package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.index.MoveMatcher.Found;
import io.github.mihirvr.prismo.index.MoveMatcher.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveMatcherTest {

    private static MediaStore.Row gone(long id, String path, long size, long modified) {
        return new MediaStore.Row(id, path, size, modified);
    }

    private static List<String> moves(Result result) {
        return result.moves().stream().map(m -> m.from().id() + "->" + m.to().path()).toList();
    }

    private static List<Long> removed(Result result) {
        return result.removed().stream().map(MediaStore.Row::id).toList();
    }

    @Test
    void aRenamedFileIsAMove() {
        Result result = MoveMatcher.match(List.of(gone(1, "/a/IMG_1.jpg", 500, 10)),
                List.of(new Found("/a/Beach day.jpg", 500, 10)));

        assertEquals(List.of("1->/a/Beach day.jpg"), moves(result));
        assertEquals(List.of(), removed(result));
    }

    @Test
    void aRenamedFolderMovesEveryFileByName() {
        // Same size and time for all three: only the names tell them apart.
        Result result = MoveMatcher.match(
                List.of(gone(1, "/Trip/a.jpg", 500, 10), gone(2, "/Trip/b.jpg", 500, 10), gone(3, "/Trip/c.jpg", 500, 10)),
                List.of(new Found("/Goa/c.jpg", 500, 10), new Found("/Goa/a.jpg", 500, 10), new Found("/Goa/b.jpg", 500, 10)));

        assertEquals(List.of("1->/Goa/a.jpg", "2->/Goa/b.jpg", "3->/Goa/c.jpg"), moves(result));
        assertEquals(List.of(), removed(result));
    }

    @Test
    void sameNameWinsOverAnotherCandidate() {
        Result result = MoveMatcher.match(List.of(gone(1, "/a/x.jpg", 500, 10)),
                List.of(new Found("/b/new.jpg", 500, 10), new Found("/b/x.jpg", 500, 10)));

        assertEquals(List.of("1->/b/x.jpg"), moves(result));
    }

    @Test
    void ambiguousGroupsAreNotGuessed() {
        // Two gone and two new with the same size and time but no names in common: could be either way round.
        Result result = MoveMatcher.match(List.of(gone(1, "/a/p.jpg", 500, 10), gone(2, "/a/q.jpg", 500, 10)),
                List.of(new Found("/b/r.jpg", 500, 10), new Found("/b/s.jpg", 500, 10)));

        assertEquals(List.of(), moves(result));
        assertEquals(List.of(1L, 2L), removed(result));
    }

    @Test
    void sizeTimeAndTypeMustAllMatch() {
        // One rule at a time: each pair would otherwise be a one-to-one rename.
        Result size = MoveMatcher.match(List.of(gone(1, "/a/one.jpg", 500, 10)), List.of(new Found("/b/one.jpg", 501, 10)));
        Result time = MoveMatcher.match(List.of(gone(2, "/a/two.jpg", 500, 10)), List.of(new Found("/b/two.jpg", 500, 11)));
        // Media type (photo / video / gif / raw), not extension: jpg → png would be a fair rename, a photo
        // can't become a video (video-only columns).
        Result type = MoveMatcher.match(List.of(gone(3, "/a/three.jpg", 500, 10)), List.of(new Found("/b/three.mp4", 500, 10)));

        assertEquals(List.of(), moves(size));
        assertEquals(List.of(), moves(time));
        assertEquals(List.of(), moves(type));
        assertEquals(List.of(3L), removed(type));
    }

    @Test
    void aChangedPhotoExtensionIsStillAMove() {
        Result result = MoveMatcher.match(List.of(gone(1, "/a/scan.jpg", 500, 10)), List.of(new Found("/a/scan.jpeg", 500, 10)));

        assertEquals(List.of("1->/a/scan.jpeg"), moves(result));
    }

    @Test
    void nothingGoneNothingMoved() {
        Result result = MoveMatcher.match(List.of(), List.of(new Found("/b/x.jpg", 1, 1)));

        assertEquals(List.of(), moves(result));
        assertEquals(List.of(), removed(result));
    }
}
