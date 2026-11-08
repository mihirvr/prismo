-- V8 (1.2.0): read every MKV and WebM file once more, for its length. Until 1.2.0 the metadata reader didn't read the
-- Matroska format at all, so these videos had no length (no badge on the tile); 1.2.0 reads it from the file's header.
-- The indexer re-reads a file whose size or modified time differs from its row, so a size of 0 makes the next index
-- run read each of them again (no real video is empty), as V5 did for RAW files. The row keeps its id, favorite,
-- hidden, People tags and video choices, and the thumbnail stays valid: it depends only on the modified time, which is
-- not touched. Only the rows without a length.

UPDATE media SET size_bytes = 0
WHERE media_type = 'video' AND extension IN ('mkv', 'webm') AND duration_seconds IS NULL;
