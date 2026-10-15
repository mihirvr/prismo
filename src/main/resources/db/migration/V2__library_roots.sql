-- V2: the folders the user chose to index ("library roots").
-- Paths are stored in their real on-disk form (Path.toRealPath), so one folder can't be added twice
-- under different spellings (e.g. different letter case on Windows).

CREATE TABLE library_roots (
    id       INTEGER PRIMARY KEY,
    path     TEXT    NOT NULL UNIQUE,
    enabled  INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    added_at INTEGER NOT NULL                                   -- epoch milliseconds
) STRICT;
