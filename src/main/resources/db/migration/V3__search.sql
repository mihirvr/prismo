-- V3: search. An FTS5 full-text index over the text people type to find a file: its name, its folder path,
-- and the camera make/model.
--
-- External content (content = 'media'): the index stores only the search structures, not a second copy of
-- the text; it reads the text back from `media` when it needs it. The triggers below keep it in sync.
--
-- Trigram tokenizer: indexes every 3-character sequence, so any part of a name matches
-- ('0101' finds 'IMG_20240101.jpg'). Matching ignores case; remove_diacritics makes 'cafe' find 'Café'.
-- A search shorter than 3 characters can't use this index (SearchSql falls back to LIKE).

CREATE VIRTUAL TABLE media_fts USING fts5(
    file_name, folder, camera_make, camera_model,
    content = 'media', content_rowid = 'id',
    tokenize = 'trigram remove_diacritics 1'
);

CREATE TRIGGER media_fts_insert AFTER INSERT ON media BEGIN
    INSERT INTO media_fts (rowid, file_name, folder, camera_make, camera_model)
    VALUES (new.id, new.file_name, new.folder, new.camera_make, new.camera_model);
END;

-- An external-content index can't look up what it stored for a row, so a delete must be given the old values.
CREATE TRIGGER media_fts_delete AFTER DELETE ON media BEGIN
    INSERT INTO media_fts (media_fts, rowid, file_name, folder, camera_make, camera_model)
    VALUES ('delete', old.id, old.file_name, old.folder, old.camera_make, old.camera_model);
END;

-- Also fires for the indexer's upsert (INSERT ... ON CONFLICT DO UPDATE), which sets these columns.
CREATE TRIGGER media_fts_update AFTER UPDATE OF file_name, folder, camera_make, camera_model ON media BEGIN
    INSERT INTO media_fts (media_fts, rowid, file_name, folder, camera_make, camera_model)
    VALUES ('delete', old.id, old.file_name, old.folder, old.camera_make, old.camera_model);
    INSERT INTO media_fts (rowid, file_name, folder, camera_make, camera_model)
    VALUES (new.id, new.file_name, new.folder, new.camera_make, new.camera_model);
END;

-- Libraries indexed before this version: build the index from the rows already in `media`.
INSERT INTO media_fts (media_fts) VALUES ('rebuild');
