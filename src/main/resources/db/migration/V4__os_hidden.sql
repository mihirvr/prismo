-- V4: the operating system's own "hidden" mark (Windows: the Hidden attribute in File Explorer's Properties).
-- Kept apart from `hidden` (Prismo's own Hide): the indexer sets this one from the file on every look, while
-- `hidden` is the user's choice inside Prismo. A file with either mark shows only on Collections > Hidden.
-- Existing rows start at 0; the next index run reads the real mark (an attribute-only change counts).

ALTER TABLE media ADD COLUMN os_hidden INTEGER NOT NULL DEFAULT 0 CHECK (os_hidden IN (0, 1));
