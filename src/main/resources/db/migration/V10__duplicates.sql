-- V10 (1.4.0): duplicates, found and shown only (Collections › Duplicates); Prismo never removes a copy itself.
-- A file's SHA-256 is stored with the size and modified time it was read at, so a changed file is read again
-- (DuplicateFinder drops rows that no longer match). Only files that share their exact size with another file are
-- read at all: a file with a size of its own can't have a copy. Removing a media row removes its hash.
CREATE TABLE media_hash (
    media_id      INTEGER PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    size_bytes    INTEGER NOT NULL,
    date_modified INTEGER NOT NULL,
    sha256        BLOB    NOT NULL
) STRICT;

CREATE INDEX idx_media_hash_sha256 ON media_hash (sha256);
CREATE INDEX idx_media_size ON media (size_bytes);
