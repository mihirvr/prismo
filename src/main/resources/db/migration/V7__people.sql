-- V7 (1.2.0): People tags. Entirely manual: the user makes a person and tags photos and videos with them; Prismo never
-- looks at the pictures (no face detection) and never writes the tags into the files. One item can have any number
-- of people, and a person any number of items.
--
-- ON DELETE CASCADE both ways: when a media row goes (file deleted, folder removed, delete permanently) its tags go;
-- when a person is deleted their tags go (the files stay). A rename or move keeps the media row's id (the indexer and
-- FileOperations update the path), so the tags follow the file. Needs PRAGMA foreign_keys = ON, which every Prismo
-- connection sets.

CREATE TABLE person (
    id         INTEGER NOT NULL PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) > 0),
    created_at INTEGER NOT NULL                       -- epoch milliseconds
) STRICT;

CREATE TABLE media_person (
    media_id  INTEGER NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    person_id INTEGER NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    PRIMARY KEY (media_id, person_id)
) STRICT, WITHOUT ROWID;

-- A person's items (the People page, the People filter); the primary key already serves "an item's people".
CREATE INDEX idx_media_person_person ON media_person (person_id, media_id);
