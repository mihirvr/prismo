-- foreign_keys: off
-- V11 (2.0.0): audio files. The media table's checks allowed only 'photo', 'video', 'gif' and 'raw', and a length
-- or a playback position only for 'video'. SQLite can't change a CHECK in place, so the table is rebuilt the way the
-- SQLite manual says ("Making Other Kinds Of Table Schema Changes"): a new table with the new checks, every row
-- copied with the same id, the old table dropped, the new one renamed, then its indexes and triggers made again.
--
-- The first line tells Migrations to switch foreign keys off around this script (it must be done outside the
-- transaction): with them on, dropping the old table would delete every row that points at it (People, Categories,
-- duplicates, video choices). Afterwards Migrations runs PRAGMA foreign_key_check and rolls back on any problem.
-- Same ids, so those rows and the search index (media_fts, by rowid) stay correct.

CREATE TABLE media_new (
    id                        INTEGER PRIMARY KEY,
    path                      TEXT    NOT NULL UNIQUE,
    folder                    TEXT    NOT NULL,
    file_name                 TEXT    NOT NULL,
    extension                 TEXT    NOT NULL,
    media_type                TEXT    NOT NULL CHECK (media_type IN ('photo', 'video', 'gif', 'raw', 'audio')),
    size_bytes                INTEGER NOT NULL CHECK (size_bytes >= 0),
    date_modified             INTEGER NOT NULL,
    date_added                INTEGER NOT NULL,
    date_taken                INTEGER,
    width                     INTEGER CHECK (width > 0),
    height                    INTEGER CHECK (height > 0),
    camera_make               TEXT,
    camera_model              TEXT,
    duration_seconds          REAL CHECK (duration_seconds IS NULL OR media_type IN ('video', 'audio')),
    playback_position_seconds REAL CHECK (playback_position_seconds IS NULL OR media_type IN ('video', 'audio')),
    favorite                  INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
    hidden                    INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1)),
    broken                    INTEGER NOT NULL DEFAULT 0 CHECK (broken IN (0, 1)),
    trashed_at                INTEGER,
    sort_date                 INTEGER GENERATED ALWAYS AS (coalesce(date_taken, date_modified)) VIRTUAL,
    os_hidden                 INTEGER NOT NULL DEFAULT 0 CHECK (os_hidden IN (0, 1))
) STRICT;

INSERT INTO media_new (id, path, folder, file_name, extension, media_type, size_bytes, date_modified, date_added,
        date_taken, width, height, camera_make, camera_model, duration_seconds, playback_position_seconds, favorite,
        hidden, broken, trashed_at, os_hidden)
SELECT id, path, folder, file_name, extension, media_type, size_bytes, date_modified, date_added,
        date_taken, width, height, camera_make, camera_model, duration_seconds, playback_position_seconds, favorite,
        hidden, broken, trashed_at, os_hidden
FROM media;

DROP TABLE media;
ALTER TABLE media_new RENAME TO media;

CREATE INDEX idx_media_sort_date ON media (sort_date);
CREATE INDEX idx_media_folder ON media (folder);
CREATE INDEX idx_media_size ON media (size_bytes);

-- The search index's triggers, as in V3 (dropping the old table dropped them).
CREATE TRIGGER media_fts_insert AFTER INSERT ON media BEGIN
    INSERT INTO media_fts (rowid, file_name, folder, camera_make, camera_model)
    VALUES (new.id, new.file_name, new.folder, new.camera_make, new.camera_model);
END;

CREATE TRIGGER media_fts_delete AFTER DELETE ON media BEGIN
    INSERT INTO media_fts (media_fts, rowid, file_name, folder, camera_make, camera_model)
    VALUES ('delete', old.id, old.file_name, old.folder, old.camera_make, old.camera_model);
END;

CREATE TRIGGER media_fts_update AFTER UPDATE OF file_name, folder, camera_make, camera_model ON media BEGIN
    INSERT INTO media_fts (media_fts, rowid, file_name, folder, camera_make, camera_model)
    VALUES ('delete', old.id, old.file_name, old.folder, old.camera_make, old.camera_model);
    INSERT INTO media_fts (rowid, file_name, folder, camera_make, camera_model)
    VALUES (new.id, new.file_name, new.folder, new.camera_make, new.camera_model);
END;
