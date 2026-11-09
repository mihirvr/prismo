-- V9 (1.3.0): Categories (Nature, Documents, Portraits...), made exactly like People (V7): entirely manual, the user
-- makes a category and puts it on photos and videos; Prismo never looks at the pictures and never writes the
-- categories into the files. One item can have any number of categories (and people too, separately), and a category
-- any number of items.
--
-- ON DELETE CASCADE both ways, as for People: when a media row goes its categories go; when a category is deleted it
-- comes off every item (the files stay). A rename or move keeps the media row's id, so the categories follow the
-- file. Needs PRAGMA foreign_keys = ON, which every Prismo connection sets.

CREATE TABLE category (
    id         INTEGER NOT NULL PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) > 0),
    created_at INTEGER NOT NULL                       -- epoch milliseconds
) STRICT;

CREATE TABLE media_category (
    media_id    INTEGER NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    PRIMARY KEY (media_id, category_id)
) STRICT, WITHOUT ROWID;

-- A category's items (the Categories page, the Categories filter); the primary key already serves "an item's
-- categories".
CREATE INDEX idx_media_category_category ON media_category (category_id, media_id);
