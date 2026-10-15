-- V1: the core Media table and app settings.
-- Times are epoch milliseconds (INTEGER). Booleans are INTEGER 0/1 (SQLite has no boolean type).
-- STRICT tables reject values of the wrong type instead of silently storing them.

CREATE TABLE media (
    id                        INTEGER PRIMARY KEY,           -- alias for SQLite's rowid
    path                      TEXT    NOT NULL UNIQUE,       -- absolute path to the original file
    folder                    TEXT    NOT NULL,              -- parent folder path (Folder Explorer, Albums)
    file_name                 TEXT    NOT NULL,
    extension                 TEXT    NOT NULL,              -- lower case, no dot: 'jpg', 'mp4'
    media_type                TEXT    NOT NULL CHECK (media_type IN ('photo', 'video', 'gif', 'raw')),
    size_bytes                INTEGER NOT NULL CHECK (size_bytes >= 0),
    date_modified             INTEGER NOT NULL,              -- file's last-modified time
    date_added                INTEGER NOT NULL,              -- when Prismo first indexed it (Recently Added)
    date_taken                INTEGER,                       -- from EXIF/video metadata; NULL if unknown
    width                     INTEGER CHECK (width > 0),
    height                    INTEGER CHECK (height > 0),
    camera_make               TEXT,
    camera_model              TEXT,
    duration_seconds          REAL CHECK (duration_seconds IS NULL OR media_type = 'video'),
    playback_position_seconds REAL CHECK (playback_position_seconds IS NULL OR media_type = 'video'),
    favorite                  INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
    hidden                    INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1)),
    broken                    INTEGER NOT NULL DEFAULT 0 CHECK (broken IN (0, 1)),
    trashed_at                INTEGER,                       -- NULL = not in Trash
    -- Timeline order: date taken, falling back to modified time when there is no metadata.
    sort_date                 INTEGER GENERATED ALWAYS AS (coalesce(date_taken, date_modified)) VIRTUAL
) STRICT;

CREATE INDEX idx_media_sort_date ON media (sort_date);
CREATE INDEX idx_media_folder ON media (folder);

CREATE TABLE settings (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
) STRICT, WITHOUT ROWID;
