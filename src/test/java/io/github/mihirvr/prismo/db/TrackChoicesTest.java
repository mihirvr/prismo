package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.video.TrackChoice;
import io.github.mihirvr.prismo.video.TrackChoice.Picked;
import io.github.mihirvr.prismo.video.TrackChoice.SubtitlePick;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-video sound track / subtitles / delay store (P8, V6). */
class TrackChoicesTest {

    @TempDir
    Path tmp;

    /** Videos 1 and 2, photo 3. */
    private Database database() throws Exception {
        Database database = Database.open(tmp.resolve("choices.db"));
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes, date_modified,
                                       date_added)
                    VALUES (?, ?, 'f', ?, ?, ?, 1, 0, 0)""")) {
                Object[][] rows = {{1, "a.mkv", "mkv", "video"}, {2, "b.mp4", "mp4", "video"}, {3, "c.jpg", "jpg", "photo"}};
                for (Object[] row : rows) {
                    insert.setObject(1, row[0]);
                    insert.setString(2, tmp.resolve((String) row[1]).toString());
                    insert.setString(3, (String) row[1]);
                    insert.setString(4, (String) row[2]);
                    insert.setString(5, (String) row[3]);
                    insert.executeUpdate();
                }
            }
            return null;
        }).get();
        return database;
    }

    private static int rows(Database database) throws Exception {
        return database.read(c -> {
            try (PreparedStatement count = c.prepareStatement("SELECT count(*) FROM video_choices");
                    ResultSet row = count.executeQuery()) {
                return row.getInt(1);
            }
        });
    }

    @Test
    void whatIsPickedReadsBackAndNothingPickedIsNothing() throws Exception {
        try (Database database = database()) {
            TrackChoices choices = new TrackChoices(database);

            assertEquals(Picked.NOTHING, choices.load(1), "never picked");
            assertEquals(Picked.NOTHING, choices.load(99), "no such row");

            Picked track = new Picked(2, SubtitlePick.track(5), -300_000);
            choices.save(1, track).get();
            assertEquals(track, choices.load(1));

            Picked file = new Picked(-1, new SubtitlePick(TrackChoice.Kind.FILE, -1, "a.en.srt"), 0);
            choices.save(1, file).get();
            assertEquals(file, choices.load(1), "a new save replaces the whole row");

            Picked off = Picked.NOTHING.withSubtitles(SubtitlePick.SUBTITLES_OFF);
            choices.save(2, off).get();
            assertEquals(off, choices.load(2), "Off is remembered too");
            assertEquals(2, rows(database));
        }
    }

    @Test
    void aPhotoNeverGetsARowAndNothingPickedRemovesIt() throws Exception {
        try (Database database = database()) {
            TrackChoices choices = new TrackChoices(database);

            choices.save(3, Picked.NOTHING.withAudio(1)).get();
            choices.save(1, Picked.NOTHING.withDelay(100_000)).get();
            assertEquals(1, rows(database), "the photo was skipped");

            choices.save(1, Picked.NOTHING).get();
            assertEquals(0, rows(database));
            assertEquals(Picked.NOTHING, choices.load(1));
        }
    }

    @Test
    void theRowGoesWithItsVideoAndStaysWhenTheVideoMoves() throws Exception {
        try (Database database = database()) {
            TrackChoices choices = new TrackChoices(database);
            Picked picked = new Picked(1, SubtitlePick.track(3), 0);
            choices.save(1, picked).get();
            choices.save(2, picked).get();

            database.write(c -> { // what a rename / move does: same row, new path
                try (PreparedStatement move = c.prepareStatement("UPDATE media SET path = ?, file_name = 'z.mkv' WHERE id = 1")) {
                    move.setString(1, tmp.resolve("z.mkv").toString());
                    move.executeUpdate();
                }
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM media WHERE id = 2")) {
                    delete.executeUpdate(); // the file is gone, deleted permanently or its folder removed
                }
                return null;
            }).get();

            assertEquals(picked, choices.load(1), "followed the move");
            assertEquals(Picked.NOTHING, choices.load(2), "gone with its video (ON DELETE CASCADE)");
            assertEquals(1, rows(database));
        }
    }

    @Test
    void aRowWithOnlySomeColumnsSetReadsBack() throws Exception {
        try (Database database = database()) {
            database.write(c -> {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO video_choices (media_id, audio_stream, subtitle) VALUES (1, 4, 'off')")) {
                    insert.executeUpdate();
                }
                return null;
            }).get();

            assertEquals(new Picked(4, SubtitlePick.SUBTITLES_OFF, 0), new TrackChoices(database).load(1));
        }
    }
}
